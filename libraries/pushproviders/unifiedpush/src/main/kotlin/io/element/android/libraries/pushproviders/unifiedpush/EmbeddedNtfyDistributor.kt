/*
 * HaohaoChat 内置 ntfy 推送分发器
 *
 * 技术实现:
 * 1. 接收 UnifiedPush connector 的 REGISTER 广播
 * 2. 生成唯一 ntfy topic，构建 push gateway endpoint
 * 3. 通过 NEW_ENDPOINT 回调将 endpoint 返回给 connector
 * 4. 建立 ntfy WebSocket 长连接接收推送消息
 * 5. 收到消息后通过 MESSAGE 广播转发给 connector
 *
 * 关键修复 (v3):
 * - 解决 NEW_ENDPOINT 广播不被 VectorUnifiedPushMessagingReceiver 接收的问题
 * - 根因: MessagingReceiver 基类会在 onReceive() 中验证 token 是否存在于 Store，
 *   如果验证失败会静默 return，不调用 onNewEndpoint()
 * - 修复: 确保所有 extra 格式与 UnifiedPush connector 库完全一致
 */

package io.element.android.libraries.pushproviders.unifiedpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.UUID

private const val LOG_TAG = "EmbeddedNtfyDist"

// ============================================
// UnifiedPush 协议常量（严格基于官方源码）
// 来源: https://codeberg.org/UnifiedPush/android-connector/src/branch/main/connector/src/main/java/org/unifiedpush/android/connector/Constants.kt
// ============================================

// --- Distributor 接收的 Actions ---
const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"

// --- Connector 接收的 Actions（Distributor 发送回 Connector）---
const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"

// --- Intent Extra Keys（注意：全部是小写简短形式！）---
// 这些值来自 org.unifiedpush.android.connector.Constants 类
// ⚠️ 绝对不能使用带完整包名前缀的形式！
const val EXTRA_TOKEN = "token"           // 注册令牌（instance/clientSecret）
const val EXTRA_ENDPOINT = "endpoint"       // 推送端点 URL
const val EXTRA_MESSAGE = "message"         // 推送消息内容（旧版，不使用）
const val EXTRA_BYTES_MESSAGE = "bytesMessage"  // ⚠️ connector 库期望的 ByteArray 消息 key
const val EXTRA_MESSAGE_ID = "id"           // 消息 ID（可选）

/**
 * 内置 ntfy 推送分发器 BroadcastReceiver
 *
 * 处理 UnifiedPush 协议的 distributor 角色：
 * - REGISTER: 注册推送，生成 endpoint 并回传
 * - UNREGISTER: 取消注册，断开 WebSocket
 */
class EmbeddedNtfyDistributor : BroadcastReceiver() {

    companion object {
        @Volatile private var wsClient: NtfyWebSocketClient? = null
        @Volatile private var currentEndpoint: String? = null
        @Volatile internal var currentInstance: String? = null
        // HaohaoChat: SharedPreferences 用于持久化 token 和 topic，支持进程被 kill 后恢复
        private const val PREFS_NAME = "embedded_ntfy_prefs"
        private const val PREF_KEY_TOKEN = "current_instance"
        private const val PREF_KEY_TOPIC = "saved_topic"
        private const val PREF_KEY_ENDPOINT = "saved_endpoint"

        /**
         * 从 SharedPreferences 恢复 token（进程被 kill 后 PushForegroundService 重启时使用）
         */
        internal fun getSavedInstance(context: Context): String? {
            return currentInstance ?: try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_KEY_TOKEN, null)
            } catch (_: Exception) { null }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(LOG_TAG, "onReceive: action=$action")

        when (action) {
            ACTION_REGISTER -> handleRegister(context, intent)
            ACTION_UNREGISTER -> handleUnregister(context)
        }
    }

    /**
     * 处理注册请求
     *
     * 完整流程:
     * 1. 从 intent 获取 token（即 clientSecret/instance）
     * 2. 生成唯一 ntfy topic
     * 3. 构建 endpoint URL
     * 4. 发送 NEW_ENDPOINT 回调给 connector（必须包含正确的 token）
     * 5. 启动 WebSocket 连接监听推送
     */
    private fun handleRegister(context: Context, intent: Intent) {
        // 提取 token — 必须使用 "token" 这个 exact key
        val token = intent.getStringExtra(EXTRA_TOKEN)

        Log.i(LOG_TAG, "=== REGISTER START ===")
        Log.i(LOG_TAG, "REGISTER: raw token from intent='$token'")
        Log.i(LOG_TAG, "REGISTER: token isNullOrEmpty=${token.isNullOrEmpty()}")

        if (token.isNullOrBlank()) {
            Log.w(LOG_TAG, "REGISTER: blank or null token, ignoring")
            // 打印所有 extras 用于调试
            logAllExtras(intent)
            return
        }

        currentInstance = token
        // HaohaoChat: 持久化 token，进程被 kill 后 PushForegroundService 重启时可以恢复
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY_TOKEN, token)
                .apply()
        } catch (_: Exception) {}

        // HaohaoChat: 复用已有 topic，避免每次注册都生成新 topic 导致 WebSocket 断开重连
        // 如果已有 topic 且 WebSocket 仍在连接，直接复用
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedTopic = prefs.getString(PREF_KEY_TOPIC, null)
        val savedEndpoint = prefs.getString(PREF_KEY_ENDPOINT, null)

        // 生成唯一的 ntfy topic: up_ + 16位随机十六进制
        val topic = savedTopic ?: ("up_" + UUID.randomUUID().toString().replace("-", "").take(16))

        // 构建 endpoint URL
        // ⚠️ 关键修复: endpoint URL 应为 ntfy topic URL，而非 Matrix gateway URL + topic
        // pushkey 被注册到 Synapse，当 Synapse 发送推送时:
        // 1. Synapse POST 到 gateway URL (由 UnifiedPushGatewayResolver 解析为 .../_matrix/push/v1/notify)
        // 2. 请求体包含 pushkey (即此 endpoint URL)
        // 3. ntfy Matrix Gateway 从 pushkey 提取 topic URL，创建内部发布请求
        // 4. 如果 pushkey = https://notify.hhj520.top/up_xxx → 内部 POST /up_xxx → 消息发布到 topic up_xxx ✓
        // 5. 如果 pushkey = https://notify.hhj520.top/_matrix/push/v1/notify/up_xxx → 内部 POST /_matrix/push/v1/notify/up_xxx → 路由失败 ✗
        val config = EmbeddedNtfyConfig()
        val endpoint = "${config.serverUrl.removeSuffix("/")}/$topic"

        Log.i(LOG_TAG, "REGISTER: topic=$topic")
        Log.i(LOG_TAG, "REGISTER: endpoint=$endpoint")

        currentEndpoint = endpoint
        // HaohaoChat: 持久化 topic 和 endpoint，进程被 kill 后可以恢复
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY_TOPIC, topic)
                .putString(PREF_KEY_ENDPOINT, endpoint)
                .apply()
        } catch (_: Exception) {}

        // 发送 NEW_ENDPOINT 给 UnifiedPush connector
        sendNewEndpoint(context, endpoint, token)

        // 启动 WebSocket 连接接收推送消息
        startWebSocket(context, topic)
    }

    /**
     * 处理取消注册请求
     */
    private fun handleUnregister(context: Context) {
        Log.i(LOG_TAG, "=== UNREGISTER ===")

        try {
            wsClient?.disconnect()
            wsClient = null
        } catch (_: Exception) {}

        currentInstance?.let { instance ->
            sendUnregistered(context, instance)
        }

        currentEndpoint = null
        currentInstance = null
        // HaohaoChat: 清除持久化的 token、topic 和 endpoint
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        } catch (_: Exception) {}
    }

    /**
     * 发送 NEW_ENDPOINT 广播通知 connector 注册成功
     *
     * ⚠️ 关键：此 Intent 必须与 org.unifiedpush.android.connector.MessagingReceiver
     *    基类期望的格式完全一致，否则会被静默丢弃！
     *
     * MessagingReceiver.onReceive() 会：
     * 1. 提取 "token" extra
     * 2. 在 Store 中查找该 token 对应的 instance
     * 3. 找不到则直接 return（不调用 onNewEndpoint）
     * 4. 找到则提取 "endpoint"，创建 PushEndpoint 对象
     * 5. 调用 onNewEndpoint(context, PushEndpoint(endpoint), instance)
     */
    private fun sendNewEndpoint(context: Context, endpoint: String, token: String) {
        try {
            val targetPackage = context.packageName
            Log.i(LOG_TAG, "sendNewEndpoint: preparing broadcast to $targetPackage")
            Log.i(LOG_TAG, "sendNewEndpoint: token='$token'")
            Log.i(LOG_TAG, "sendNewEndpoint: endpoint='$endpoint'")

            val callbackIntent = Intent(ACTION_NEW_ENDPOINT).apply {
                // ✅ 必需 extras（严格按照 UnifiedPush 规范）
                putExtra(EXTRA_TOKEN, token)      // 注册时的原始 token，用于验证
                putExtra(EXTRA_ENDPOINT, endpoint) // 推送端点 URL

                // 可选 extras
                putExtra(EXTRA_MESSAGE_ID, UUID.randomUUID().toString())

                // 设置目标包名（显式定向）
                `package` = targetPackage

                // Android 14+ 需要 flags
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    // 允许跨组件通信
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
            }

            Log.i(LOG_TAG, "sendNewEndpoint: sending broadcast...")
            context.sendBroadcast(callbackIntent)
            Log.i(LOG_TAG, "✅ Sent NEW_ENDPOINT successfully")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "❌ Failed to send NEW_ENDPOINT", e)
        }
    }

    /**
     * 发送 UNREGISTERED 广播
     */
    private fun sendUnregistered(context: Context, instance: String) {
        try {
            val callbackIntent = Intent(ACTION_UNREGISTERED).apply {
                putExtra(EXTRA_TOKEN, instance)
                `package` = context.packageName
            }
            context.sendBroadcast(callbackIntent)
            Log.i(LOG_TAG, "Sent UNREGISTERED: instance=$instance")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to send UNREGISTERED", e)
        }
    }

    /**
     * 启动 ntfy WebSocket 连接
     *
     * ⚠️ 关键改进：使用 ForegroundService 保持连接在后台存活
     *
     * 直接创建 WebSocket 的问题：
     * - Android 在应用后台时杀死进程和网络连接
     * - Doze 模式下网络访问被限制
     * - WebSocket 通常在几秒到几分钟内断开
     *
     * ForegroundService 解决方案：
     * - startForeground() 显示常驻通知，绕过 Doze
     * - START_STICKY 进程被杀后自动重启
     * - WebSocket 在独立服务进程中运行，不受 Activity 生命周期影响
     */
    private fun startWebSocket(context: Context, topic: String) {
        Log.i(LOG_TAG, "startWebSocket: starting PushForegroundService for topic=$topic")

        // 通过 ForegroundService 启动 WebSocket（保持后台存活）
        val serviceIntent = Intent(context, PushForegroundService::class.java).apply {
            putExtra("topic", topic)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(LOG_TAG, "✅ PushForegroundService started for topic=$topic")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "❌ Failed to start PushForegroundService", e)
            // Fallback：直接启动 WebSocket（不保证后台存活）
            startWebSocketDirect(context, topic)
        }
    }

    /**
     * 直接启动 WebSocket（Fallback，不保证后台存活）
     */
    private fun startWebSocketDirect(context: Context, topic: String) {
        Log.w(LOG_TAG, "startWebSocketDirect: using fallback mode (no foreground service)")
        try { wsClient?.disconnect() } catch (_: Exception) {}
        wsClient = NtfyWebSocketClient()
        val connected = wsClient!!.connect(topic) { message ->
            handlePushMessage(context, message)
        }
        if (!connected) {
            Log.e(LOG_TAG, "Failed to start WebSocket for topic=$topic")
        } else {
            Log.i(LOG_TAG, "WebSocket started for topic=$topic (fallback mode)")
        }
    }

    /**
     * 处理从 ntfy WebSocket 收到的推送消息
     *
     * ⚠️ 关键改进：
     * 1. 从 ntfy JSON 中提取 Matrix 通知负载（而非转发原始 ntfy 包装）
     *
     * ntfy 消息格式:
     * {"id":"xxx","time":123,"event":"message","topic":"up_xxx",
     *  "message":"{\"notification\":{\"event_id\":\"$xxx\",...}}","priority":4}
     *
     * VectorUnifiedPushMessagingReceiver.onMessage() 期望收到:
     * - bytesMessage: Synapse 发送的 Matrix Push Format JSON (ByteArray)
     * - token: 注册时的 instance 标识符
     */
    private fun handlePushMessage(context: Context, message: String) {
        Log.d(LOG_TAG, "Received push message: ${message.take(120)}")

        try {
            // 提取 Matrix 通知负载（ntfy JSON 的 "message" 字段内容）
            val matrixPayload = extractMatrixPayload(message)

            val messageIntent = Intent(ACTION_MESSAGE).apply {
                // ⚠️ 关键修复: connector 库 MessagingReceiver.onReceive() 使用 "token" 查找 instance
                putExtra(EXTRA_TOKEN, currentInstance ?: "")
                // ⚠️ 关键修复: connector 库使用 getByteArrayExtra("bytesMessage") 读取消息
                putExtra(EXTRA_BYTES_MESSAGE, matrixPayload.toByteArray(Charsets.UTF_8))
                putExtra(EXTRA_MESSAGE_ID, java.util.UUID.randomUUID().toString())
                `package` = context.packageName

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
            }

            context.sendBroadcast(messageIntent)
            Log.d(LOG_TAG, "✅ Forwarded push to connector (${matrixPayload.length} bytes payload)")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "❌ Failed to forward push message", e)
        }
    }

    /**
     * 从 ntfy JSON 中提取 Matrix 通知负载
     *
     * ntfy 发送的消息格式:
     * {"id":"abc","time":1234567890,"event":"message",
     *  "topic":"up_xxx","message":"{...Matrix数据...}","priority":4}
     *
     * 我们需要提取内嵌的 "message" 字段值作为 UnifiedPush payload
     * ⚠️ 使用 JSONObject 解析，自动处理 JSON 转义字符
     */
    private fun extractMatrixPayload(ntfyJson: String): String {
        return try {
            val json = org.json.JSONObject(ntfyJson)
            json.optString("message", ntfyJson)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "extractMatrixPayload failed, using raw", e)
            ntfyJson
        }
    }

    /**
     * 调试工具：打印 intent 中所有 extras
     */
    private fun logAllExtras(intent: Intent) {
        Log.d(LOG_TAG, "=== Intent Extras Dump ===")
        intent.extras?.let { bundle ->
            for (key in bundle.keySet()) {
                val value = bundle.get(key)
                Log.d(LOG_TAG, "  extra[$key] = $value (${value?.javaClass?.simpleName})")
            }
        } ?: Log.d(LOG_TAG, "  (no extras)")
    }
}
