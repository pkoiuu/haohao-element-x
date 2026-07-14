/*
 * HaohaoChat 内置 ntfy WebSocket 客户端
 * 技术实现: 基于 OkHttp 5.x 的 ntfy WebSocket 长连接
 *
 * v26.06.4 修复:
 * - lastMessageId 为 null 时使用 since=all，避免断连期间消息永久丢失
 * - connect() 异常时也触发重连，防止重连链路断裂
 * - disconnect() 使用 cancel() 替代 close()，避免触发 onClosed 导致僵尸重连
 * - 真正的指数退避重连策略（3s → 6s → 12s → ... → 最大 60s）
 * - 添加 isDisconnecting 标志，防止旧 listener 回调干扰新连接
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
import kotlin.math.min
import kotlin.random.Random

private const val LOG_TAG = "NtfyWS"

class NtfyWebSocketClient(
    private val config: EmbeddedNtfyConfig = EmbeddedNtfyConfig(),
) {
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var scope: CoroutineScope? = null
    private var reconnectScope: CoroutineScope? = null
    private var currentTopic: String? = null
    private var messageListener: ((message: String) -> Unit)? = null

    @Volatile
    private var lastMessageId: String? = null

    // HaohaoChat v26.06.4: 防止僵尸重连的标志
    @Volatile
    private var isDisconnecting = false

    // HaohaoChat v26.06.4: 指数退避计数器
    private var reconnectAttempts = 0

    var onMessageIdUpdated: ((id: String) -> Unit)? = null

    fun restoreLastMessageId(id: String?) {
        if (id != null) {
            lastMessageId = id
            Log.i(LOG_TAG, "Restored lastMessageId=$id")
        }
    }

    fun connect(topic: String, onMessage: (String) -> Unit): Boolean {
        return try {
            disconnect()
            isDisconnecting = false

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
                    reconnectAttempts = 0
                    Log.i(LOG_TAG, "WS connected to $topic")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    super.onMessage(webSocket, text)
                    Log.d(LOG_TAG, "WS message received (${text.length} chars)")
                    // 使用 JSON 解析判断事件类型，避免 "message_delete" 误判
                    try {
                        val json = org.json.JSONObject(text)
                        val event = json.optString("event")
                        if (event != "message") {
                            Log.d(LOG_TAG, "Ignoring non-message event: ${text.take(80)}")
                            return
                        }
                        json.optString("id")?.takeIf { it.isNotEmpty() }?.let { id ->
                            lastMessageId = id
                            try { onMessageIdUpdated?.invoke(id) } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Failed to parse WS message JSON: ${text.take(80)}")
                        return
                    }
                    try {
                        messageListener?.invoke(text)
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Error processing WS message", e)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    super.onClosing(webSocket, code, reason)
                    Log.d(LOG_TAG, "WS closing: code=$code reason=$reason")
                    // 不在这里调用 scheduleReconnect，由 onClosed 统一处理
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    super.onClosed(webSocket, code, reason)
                    Log.w(LOG_TAG, "WS closed: code=$code reason=$reason")
                    // HaohaoChat v26.06.4: 只有非主动断开时才重连
                    if (!isDisconnecting) {
                        scheduleReconnect(topic, onMessage)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    super.onFailure(webSocket, t, response)
                    Log.e(LOG_TAG, "WS connection failed: ${t.message}", t)
                    // HaohaoChat v26.06.4: 只有非主动断开时才重连
                    if (!isDisconnecting) {
                        scheduleReconnect(topic, onMessage)
                    }
                }
            })

            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to create WS connection", e)
            // HaohaoChat v26.06.4: connect 异常时也要触发重连，防止链路断裂
            if (!isDisconnecting) {
                scheduleReconnect(topic, onMessage)
            }
            false
        }
    }

    fun disconnect() {
        isDisconnecting = true
        try {
            reconnectScope?.cancel()
            reconnectScope = null
            // HaohaoChat v26.06.4: 使用 cancel() 替代 close()
            // close() 会触发 onClosed 回调导致僵尸重连，cancel() 立即终止不触发回调
            webSocket?.cancel()
            webSocket = null
            client?.dispatcher?.executorService?.shutdownNow()
            client = null
            scope?.cancel()
            scope = null
            currentTopic = null
            messageListener = null
        } catch (_: Exception) {}
    }

    fun isConnected(): Boolean = webSocket != null

    private fun scheduleReconnect(topic: String, onMessage: (String) -> Unit) {
        reconnectScope?.cancel()
        reconnectScope = CoroutineScope(Dispatchers.IO)
        reconnectScope!!.launch {
            reconnectAttempts++
            // HaohaoChat v26.06.4: 真正的指数退避
            // 基础 3s，每次翻倍: 3, 6, 12, 24, 48, 60, 60, 60...
            val baseDelay = min(3000L * (1L shl min(reconnectAttempts - 1, 5)), 60_000L)
            val jitter = Random.nextLong(0, 2000)
            val delayMs = baseDelay + jitter
            Log.w(LOG_TAG, "Scheduling reconnect #$reconnectAttempts in ${delayMs / 1000}s")
            delay(delayMs)
            if (isActive && !isDisconnecting) {
                Log.i(LOG_TAG, "Attempting reconnect...")
                connect(topic, onMessage)
            }
        }
    }

    private fun buildWsUrl(topic: String): String {
        val baseUrl = config.serverUrl.removeSuffix("/")
        val wsBase = baseUrl.replace("https://", "wss://").replace("http://", "ws://")
        // HaohaoChat v26.06.4: lastMessageId 为 null 时使用 since=all 回放所有缓存消息
        // ntfy 默认缓存 12h，不会返回过多消息
        val sinceParam = lastMessageId?.let { "?since=$it" } ?: "?since=all"
        return "$wsBase/$topic/ws$sinceParam"
    }
}
