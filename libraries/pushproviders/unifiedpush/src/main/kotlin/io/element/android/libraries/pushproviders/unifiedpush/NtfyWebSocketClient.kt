/*
 * HaohaoChat 内置 ntfy WebSocket 客户端
 * 技术实现: 基于 OkHttp 5.x 的 ntfy WebSocket 长连接
 */

package io.element.android.libraries.pushproviders.unifiedpush

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private const val LOG_TAG = "NtfyWS"

/**
 * ntfy WebSocket 客户端
 * 连接到 ntfy 服务器的 /{topic}/ws 端点接收实时推送
 */
class NtfyWebSocketClient(
    private val config: EmbeddedNtfyConfig = EmbeddedNtfyConfig(),
) {
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var scope: CoroutineScope? = null
    // HaohaoChat: 将 reconnectScope 作为实例变量管理，在 disconnect() 中取消
    // 原代码每次 scheduleReconnect 都创建独立 scope，disconnect() 无法取消，导致僵尸重连
    private var reconnectScope: CoroutineScope? = null
    private var heartbeatJob: Job? = null
    private var currentTopic: String? = null
    private var messageListener: ((message: String) -> Unit)? = null

    /**
     * 最后收到的消息 ID，用于重连时通过 since= 参数恢复断连期间的消息
     */
    @Volatile
    private var lastMessageId: String? = null

    /**
     * 消息 ID 变化回调，用于外部持久化 lastMessageId
     * HaohaoChat: PushForegroundService 设置此回调，将 lastMessageId 保存到 SharedPreferences
     * 进程重启后创建新 NtfyWebSocketClient 时可恢复，避免断连期间消息丢失
     */
    var onMessageIdUpdated: ((id: String) -> Unit)? = null

    /**
     * 恢复上次的消息 ID（进程重启后从 SharedPreferences 恢复）
     */
    fun restoreLastMessageId(id: String?) {
        if (id != null) {
            lastMessageId = id
            Log.i(LOG_TAG, "Restored lastMessageId=$id")
        }
    }

    /**
     * 连接到 ntfy WebSocket
     * @param topic ntfy topic 名称（不含前缀）
     * @param onMessage 收到消息时的回调
     */
    fun connect(topic: String, onMessage: (String) -> Unit): Boolean {
        return try {
            disconnect()

            currentTopic = topic
            messageListener = onMessage
            scope = CoroutineScope(Dispatchers.IO)

            val wsUrl = buildWsUrl(topic)
            Log.i(LOG_TAG, "Connecting to: $wsUrl")

            client = OkHttpClient.Builder()
                .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(config.heartbeatIntervalSeconds, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(wsUrl).build()

            webSocket = client!!.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    super.onOpen(webSocket, response)
                    Log.i(LOG_TAG, "WS connected to $topic")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    super.onMessage(webSocket, text)
                    Log.d(LOG_TAG, "WS message received (${text.length} chars)")
                    // 仅处理 event=message 的消息，忽略 open/keepalive 等控制事件
                    if (!text.contains("\"event\":\"message\"")) {
                        Log.d(LOG_TAG, "Ignoring non-message event: ${text.take(80)}")
                        return
                    }
                    // 记录消息 ID 用于重连恢复
                    extractMessageId(text)?.let { id ->
                        lastMessageId = id
                        // HaohaoChat: 通知外部持久化 lastMessageId
                        try { onMessageIdUpdated?.invoke(id) } catch (_: Exception) {}
                    }
                    try {
                        messageListener?.invoke(text)
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Error processing WS message", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    super.onFailure(webSocket, t, response)
                    Log.e(LOG_TAG, "WS connection failed: ${t.message}", t)
                    scheduleReconnect(topic, onMessage)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    super.onClosed(webSocket, code, reason)
                    Log.w(LOG_TAG, "WS closed: code=$code reason=$reason")
                    scheduleReconnect(topic, onMessage)
                }
            })

            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to create WS connection", e)
            false
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        try {
            heartbeatJob?.cancel()
            heartbeatJob = null
            // HaohaoChat: 取消挂起的重连任务
            reconnectScope?.cancel()
            reconnectScope = null
            webSocket?.close(1000, "Client disconnect")
            webSocket = null
            client?.dispatcher?.executorService?.shutdown()
            client = null
            scope?.cancel()
            scope = null
            currentTopic = null
            messageListener = null
        } catch (_: Exception) {}
    }

    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean = webSocket != null

    private fun scheduleReconnect(topic: String, onMessage: (String) -> Unit) {
        // HaohaoChat: 使用实例变量 reconnectScope，在 disconnect() 中可被取消
        // 原代码每次创建独立 scope，disconnect() 无法取消，导致僵尸重连
        reconnectScope?.cancel()
        reconnectScope = CoroutineScope(Dispatchers.IO)
        reconnectScope!!.launch {
            // 指数退避: 基础 5 秒，加随机抖动
            val delayMs = (5 + Random.nextLong(1, 15)) * 1000L
            Log.w(LOG_TAG, "Scheduling reconnect in ${delayMs / 1000}s")
            delay(delayMs)
            if (isActive) {
                Log.i(LOG_TAG, "Attempting reconnect...")
                connect(topic, onMessage)
            }
        }
    }

    private fun buildWsUrl(topic: String): String {
        val baseUrl = config.serverUrl.removeSuffix("/")
        val wsBase = baseUrl.replace("https://", "wss://").replace("http://", "ws://")
        // 重连时附加 since= 参数，恢复断连期间的消息
        // ntfy 支持 since=<message_id> 获取该 ID 之后的所有缓存消息
        val sinceParam = lastMessageId?.let { "?since=$it" } ?: ""
        return "$wsBase/$topic/ws$sinceParam"
    }

    /**
     * 从 ntfy JSON 消息中提取消息 ID
     */
    private fun extractMessageId(text: String): String? {
        return try {
            val json = org.json.JSONObject(text)
            json.optString("id", null)
        } catch (e: Exception) {
            null
        }
    }
}
