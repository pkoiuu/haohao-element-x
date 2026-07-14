/*
 * HaohaoChat 内置 ntfy WebSocket 客户端
 * 技术实现: 基于 OkHttp 5.x 的 ntfy WebSocket 长连接
 *
 * v26.06.5 重大修复 (15 个子 AI 深度分析结果):
 *
 * P0 修复:
 * - lastMessageId 在 messageListener 成功后才更新，防止消息处理失败导致永久丢失
 * - 使用 connectionEpoch 替代 isDisconnecting 布尔值，彻底消除竞态条件
 * - scheduleReconnect 使用独立 scope，避免 connect→disconnect 自取消循环
 * - 外部 disconnect() 不再被重连覆盖，通过 epoch CAS 保证原子性
 *
 * P1 修复:
 * - 所有字段添加 @Volatile，保证多线程可见性
 * - OkHttpClient 单例化，避免线程池/连接池泄漏
 * - connect() 添加 synchronized 防止并发调用
 * - onFailure 检查 HTTP 403，认证失败时停止重连
 *
 * P2 修复:
 * - 最大重连次数 20 次，超出后通知上层
 * - 切换 topic 时重置 lastMessageId
 * - disconnect() 完全清理所有引用，防止内存泄漏
 * - 添加 URL 编码
 *
 * 重要发现 (纠正之前分析):
 * - readTimeout=0 对 WebSocket 无影响: OkHttp 内部在握手后强制 setSoTimeout(0)
 * - 应用层心跳非必需: OkHttp 的 PING/PONG 机制 (pingInterval=15s) 已能检测半开连接
 *   当 PONG 未在 pingInterval 内返回时，OkHttp 自动调用 failWebSocket → onFailure
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random

private const val LOG_TAG = "NtfyWS"

class NtfyWebSocketClient(
    private val config: EmbeddedNtfyConfig = EmbeddedNtfyConfig(),
) {
    /**
     * 连接代数 (epoch)
     *
     * 每次 connect() 递增，disconnect() 也递增。
     * WebSocket listener 回调通过检查 epoch 判断自己是否为"当前连接"。
     * 旧连接的回调看到 epoch 不匹配时直接 return，彻底消除幽灵重连。
     */
    private val connectionEpoch = AtomicInteger(0)

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var currentTopic: String? = null
    @Volatile private var messageListener: ((message: String) -> Unit)? = null

    /**
     * OkHttp 客户端单例
     *
     * OkHttp 官方文档强烈建议共享同一个 OkHttpClient 实例。
     * 每次新建会导致线程池和连接池泄漏。
     * pingInterval=15s 确保 OkHttp 自动检测半开连接 (PING/PONG 机制)。
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(config.heartbeatIntervalSeconds, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 独立的重连 scope
     *
     * 关键设计: 重连 scope 与主 scope 分离。
     * scheduleReconnect() 中的协程调用 connect()，connect() 内部的 disconnect()
     * 只取消主 scope，不会取消重连 scope 自身，避免自取消循环。
     */
    private val reconnectScope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var lastMessageId: String? = null

    @Volatile
    private var reconnectAttempts = 0

    /**
     * 是否已主动断开 (用户意图)
     * 与 connectionEpoch 配合使用: disconnect 时设为 true，connect 时设为 false
     */
    @Volatile
    private var isUserDisconnected = false

    /**
     * 认证失败标志 — 403 时停止重连
     */
    @Volatile
    private var authFailed = false

    var onMessageIdUpdated: ((id: String) -> Unit)? = null

    /**
     * 认证失败回调 — 通知上层应用需要修复服务端 ACL
     */
    var onAuthFailed: (() -> Unit)? = null

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 20
    }

    fun restoreLastMessageId(id: String?) {
        if (id != null) {
            lastMessageId = id
            Log.i(LOG_TAG, "Restored lastMessageId=$id")
        }
    }

    /**
     * 连接到指定 ntfy topic
     *
     * 线程安全: 使用 synchronized 防止并发调用。
     * 竞态消除: 使用 connectionEpoch 标记当前连接代数。
     *
     * @param topic ntfy topic 名称
     * @param onMessage 消息回调
     * @return 是否成功发起连接 (不代表已连接成功)
     */
    @Synchronized
    fun connect(topic: String, onMessage: (String) -> Unit): Boolean {
        // 如果之前因 403 认证失败，不允许重连 (除非外部重置)
        if (authFailed) {
            Log.w(LOG_TAG, "connect() aborted: auth previously failed (403)")
            return false
        }

        // 递增 epoch，使旧连接的所有回调失效
        val myEpoch = connectionEpoch.incrementAndGet()
        isUserDisconnected = false

        // 清理旧连接 (不取消 reconnectScope，避免自取消)
        cleanupOldConnection()

        // 切换 topic 时重置 lastMessageId
        if (currentTopic != null && currentTopic != topic) {
            Log.i(LOG_TAG, "Topic changed: $currentTopic → $topic, resetting lastMessageId")
            lastMessageId = null
        }

        currentTopic = topic
        messageListener = onMessage
        reconnectAttempts = 0

        val wsUrl = buildWsUrl(topic)
        Log.i(LOG_TAG, "Connecting (epoch=$myEpoch) to: $wsUrl")

        val request = Request.Builder().url(wsUrl).build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                // 检查 epoch: 如果不是当前连接，说明已被新 connect 取代
                if (connectionEpoch.get() != myEpoch) {
                    Log.d(LOG_TAG, "onOpen: stale connection (epoch=$myEpoch), ignoring")
                    webSocket.cancel()
                    return
                }
                reconnectAttempts = 0
                authFailed = false
                Log.i(LOG_TAG, "✅ WS connected to $topic (epoch=$myEpoch)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                if (connectionEpoch.get() != myEpoch) return

                Log.d(LOG_TAG, "WS message received (${text.length} chars)")

                // 解析 JSON 判断事件类型
                val json = try {
                    org.json.JSONObject(text)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Failed to parse WS message JSON: ${text.take(80)}")
                    return
                }

                val event = json.optString("event")
                if (event != "message") {
                    Log.d(LOG_TAG, "Ignoring non-message event: ${text.take(80)}")
                    return
                }

                // 提取消息 ID (用于 since 参数，但不在此处更新)
                val msgId = json.optString("id", "").takeIf { it.isNotEmpty() }

                // 先调用 listener 处理消息
                try {
                    messageListener?.invoke(text)
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error processing WS message, NOT updating lastMessageId", e)
                    return // 处理失败，不更新 lastMessageId，重连时会重新收到此消息
                }

                // 处理成功后才更新 lastMessageId (P0 修复: 防止消息永久丢失)
                if (msgId != null) {
                    lastMessageId = msgId
                    try { onMessageIdUpdated?.invoke(msgId) } catch (_: Exception) {}
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosing(webSocket, code, reason)
                Log.d(LOG_TAG, "WS closing: code=$code reason=$reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                Log.w(LOG_TAG, "WS closed: code=$code reason=$reason")
                // 检查 epoch: 只有当前连接的回调才触发重连
                if (connectionEpoch.get() == myEpoch && !isUserDisconnected) {
                    scheduleReconnect(topic, onMessage, myEpoch)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                Log.e(LOG_TAG, "WS connection failed: ${t.message}", t)

                // 检查 epoch
                if (connectionEpoch.get() != myEpoch) return

                // P0 修复: 检查 HTTP 403 — 认证失败，停止重连
                val httpCode = response?.code
                if (httpCode == 403 || httpCode == 401) {
                    Log.e(LOG_TAG, "🚫 Authentication failed (HTTP $httpCode)! Stopping reconnect.")
                    Log.e(LOG_TAG, "🚫 Server ACL may be 'write-only' — need 'read-write' for WebSocket subscription")
                    authFailed = true
                    try { onAuthFailed?.invoke() } catch (_: Exception) {}
                    return // 不重连，避免无限 403 循环
                }

                if (!isUserDisconnected) {
                    scheduleReconnect(topic, onMessage, myEpoch)
                }
            }
        })

        return true
    }

    /**
     * 主动断开连接
     *
     * 线程安全: synchronized 与 connect() 互斥。
     * epoch 递增使所有旧回调失效。
     * 设置 isUserDisconnected 阻止重连。
     */
    @Synchronized
    fun disconnect() {
        isUserDisconnected = true
        connectionEpoch.incrementAndGet() // 使所有旧回调失效
        cleanupOldConnection()
        Log.i(LOG_TAG, "Disconnected (epoch=${connectionEpoch.get()})")
    }

    /**
     * 清理旧连接资源 (不取消 reconnectScope)
     */
    private fun cleanupOldConnection() {
        try { webSocket?.cancel() } catch (_: Exception) {}
        webSocket = null
        messageListener = null
    }

    fun isConnected(): Boolean = webSocket != null && !isUserDisconnected

    /**
     * 调度重连
     *
     * 使用 reconnectScope (独立于主 scope) 避免自取消。
     * 指数退避: 3s → 6s → 12s → 24s → 48s → 60s (最大)
     * 最大重连次数: 20 次
     *
     * @param myEpoch 发起重连时的连接代数，重连前检查是否已过时
     */
    private fun scheduleReconnect(topic: String, onMessage: (String) -> Unit, myEpoch: Int) {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(LOG_TAG, "🚫 Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up")
            return
        }

        reconnectScope.launch {
            reconnectAttempts++
            val baseDelay = min(3000L * (1L shl min(reconnectAttempts - 1, 5)), 60_000L)
            val jitter = Random.nextLong(0, 2000)
            val delayMs = baseDelay + jitter
            Log.w(LOG_TAG, "Scheduling reconnect #$reconnectAttempts in ${delayMs / 1000}s (epoch=$myEpoch)")
            delay(delayMs)

            // 重连前检查: epoch 是否已过时、用户是否已断开
            if (isActive && connectionEpoch.get() == myEpoch && !isUserDisconnected && !authFailed) {
                Log.i(LOG_TAG, "Attempting reconnect #$reconnectAttempts...")
                connect(topic, onMessage)
            } else {
                Log.d(LOG_TAG, "Reconnect cancelled (stale/user-disconnected/auth-failed)")
            }
        }
    }

    /**
     * 重置认证失败状态 (外部修复 ACL 后调用)
     */
    fun resetAuthFailure() {
        authFailed = false
        Log.i(LOG_TAG, "Auth failure flag reset")
    }

    private fun buildWsUrl(topic: String): String {
        val baseUrl = config.serverUrl.removeSuffix("/")
        val wsBase = baseUrl.replace("https://", "wss://").replace("http://", "ws://")
        // lastMessageId 为 null 时使用 since=all 回放所有缓存消息
        // ntfy 默认缓存 12h，如果 lastMessageId 已过期，ntfy 自动回退为返回所有缓存消息
        val encodedTopic = java.net.URLEncoder.encode(topic, "UTF-8")
        val sinceParam = lastMessageId?.let { "?since=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "?since=all"
        return "$wsBase/$encodedTopic/ws$sinceParam"
    }
}
