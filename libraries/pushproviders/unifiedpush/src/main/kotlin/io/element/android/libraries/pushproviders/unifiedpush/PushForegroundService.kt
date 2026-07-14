/*
 * HaohaoChat 推送前台服务
 *
 * 功能: 保持 ntfy WebSocket 长连接在后台存活
 *
 * 技术实现:
 * - 使用 startForeground() 显示常驻通知，绕过 Android Doze 模式
 * - 返回 START_STICKY，进程被杀后自动重启
 * - 管理 NtfyWebSocketClient 生命周期
 *
 * 为什么需要这个服务:
 * Android 系统会在应用后台时杀死网络连接和进程。
 * 没有前台服务，WebSocket 会在几秒到几分钟内断开，
 * 导致推送消息无法接收。这是嵌入式分发器的核心基础设施。
 */

package io.element.android.libraries.pushproviders.unifiedpush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 推送前台服务 — 保持 WebSocket 连接在后台存活
 */
class PushForegroundService : Service() {

    companion object {
        private const val LOG_TAG = "PushFGService"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "push_foreground_channel"
        private const val CHANNEL_NAME = "推送服务"
        // HaohaoChat: SharedPreferences 用于持久化 topic，支持服务被 kill 后恢复
        private const val PREFS_NAME = "push_foreground_prefs"
        private const val PREF_KEY_TOPIC = "saved_topic"
        // HaohaoChat: 持久化 lastMessageId，新 NtfyWebSocketClient 实例可恢复，避免断连期间消息丢失
        private const val PREF_KEY_LAST_MSG_ID = "last_message_id"

        // 单例引用，供 EmbeddedNtfyDistributor 调用
        @Volatile private var instance: PushForegroundService? = null

        /**
         * 启动推送前台服务
         */
        fun start(context: Context) {
            val intent = Intent(context, PushForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止推送前台服务
         */
        fun stop(context: Context) {
            val intent = Intent(context, PushForegroundService::class.java)
            context.stopService(intent)
        }

        /**
         * 获取当前运行的实例（用于管理 WebSocket）
         */
        fun getInstance(): PushForegroundService? = instance
    }

    @Volatile private var wsClient: NtfyWebSocketClient? = null
    @Volatile private var currentTopic: String? = null
    // HaohaoChat v26.06.4: 网络状态监听，网络恢复时自动重连 WebSocket
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(LOG_TAG, "onCreate: PushForegroundService created")
        createNotificationChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(LOG_TAG, "onStartCommand: starting foreground")

        // 显示常驻通知 (HaohaoChat v26.06.4: Android 14+ 兼容)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // HaohaoChat: 从 intent 获取 topic，或从 SharedPreferences 恢复（服务被 kill 后重启）
        val topic = intent?.getStringExtra("topic") ?: getSavedTopic()
        if (topic != null && (currentTopic == null || currentTopic != topic)) {
            startWebSocket(topic)
        } else if (wsClient == null && currentTopic != null) {
            // 服务重启后重新连接
            startWebSocket(currentTopic!!)
        } else if (wsClient == null && topic != null) {
            // HaohaoChat: currentTopic 为 null 但 SharedPreferences 有 topic（服务被 kill 重启后）
            startWebSocket(topic)
        }

        // START_STICKY: 进程被杀后自动重启
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(LOG_TAG, "onDestroy: stopping WebSocket")
        unregisterNetworkCallback()
        try {
            wsClient?.disconnect()
            wsClient = null
        } catch (_: Exception) {}
        currentTopic = null
        instance = null
    }

    // ==================== SharedPreferences 持久化 ====================

    // HaohaoChat: 服务被系统 kill 后以 START_STICKY 重启时 intent 为 null，
    // 从 SharedPreferences 恢复 topic 以重新建立 WebSocket 连接

    private fun saveTopic(topic: String) {
        try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY_TOPIC, topic)
                .apply()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to save topic", e)
        }
    }

    private fun getSavedTopic(): String? {
        return try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_KEY_TOPIC, null)
        } catch (e: Exception) {
            null
        }
    }

    private fun clearSavedTopic() {
        try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_KEY_TOPIC)
                .apply()
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 网络状态监听 (v26.06.4) ====================

    // HaohaoChat v26.06.4: 监听网络变化，网络恢复时自动重连 WebSocket
    // 这是"完全收不到消息"的核心修复：手机无网络时 WebSocket 断开，
    // 网络恢复后如果没有主动重连，推送将永久不可用
    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder().build()
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(LOG_TAG, "Network available, reconnecting WebSocket")
                    val topic = currentTopic
                    if (topic != null) {
                        // 网络恢复，主动重连
                        startWebSocket(topic)
                    }
                }
                override fun onLost(network: Network) {
                    Log.w(LOG_TAG, "Network lost, WebSocket will reconnect automatically")
                }
            }
            cm.registerNetworkCallback(request, networkCallback!!)
            Log.i(LOG_TAG, "Network callback registered")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    /**
     * 主动重连 WebSocket（供外部调用）
     */
    fun reconnect() {
        val topic = currentTopic ?: getSavedTopic()
        if (topic != null) {
            Log.i(LOG_TAG, "Manual reconnect to topic=$topic")
            startWebSocket(topic)
        }
    }

    /**
     * 启动或切换 WebSocket 连接到指定 topic
     */
    fun connectToTopic(topic: String) {
        currentTopic = topic
        // HaohaoChat: 持久化 topic 到 SharedPreferences，支持服务被 kill 后恢复
        saveTopic(topic)
        startWebSocket(topic)
    }

    /**
     * 断开当前 WebSocket 连接
     */
    fun disconnect() {
        try {
            wsClient?.disconnect()
            wsClient = null
        } catch (_: Exception) {}
        currentTopic = null
        // HaohaoChat: 清除持久化的 topic
        clearSavedTopic()
    }

    /**
     * 当前是否已连接
     */
    fun isConnected(): Boolean = wsClient?.isConnected() == true

    // ==================== 私有方法 ====================

    private fun startWebSocket(topic: String) {
        Log.i(LOG_TAG, "startWebSocket: connecting to topic=$topic")

        // HaohaoChat: 设置 currentTopic 并持久化，防止 onStartCommand 重复调用时断开已有连接
        // 原代码未设置 currentTopic，导致每次 onStartCommand 都判断 currentTopic == null 为 true，重复断开重连
        currentTopic = topic
        saveTopic(topic)

        // 先断开旧连接
        try { wsClient?.disconnect() } catch (_: Exception) {}
        wsClient = null

        // 创建新客户端并连接
        wsClient = NtfyWebSocketClient()

        // HaohaoChat: 恢复 lastMessageId，避免断连期间消息丢失
        val savedMsgId = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY_LAST_MSG_ID, null)
        wsClient!!.restoreLastMessageId(savedMsgId)

        // HaohaoChat: 设置回调，将 lastMessageId 持久化到 SharedPreferences
        wsClient!!.onMessageIdUpdated = { id ->
            try {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_KEY_LAST_MSG_ID, id)
                    .apply()
            } catch (_: Exception) {}
        }

        val connected = wsClient!!.connect(topic) { message ->
            handlePushMessage(message)
        }

        if (connected) {
            Log.i(LOG_TAG, "✅ WebSocket connected for topic=$topic")
            // 更新通知显示状态
            updateNotification("推送服务运行中 ($topic)")
        } else {
            Log.e(LOG_TAG, "❌ Failed to connect WebSocket for topic=$topic")
        }
    }

    /**
     * 处理从 ntfy 收到的推送消息
     *
     * 关键修复:
     * 1. 解析 ntfy JSON 提取实际 Matrix 通知负载
     * 2. 使用正确的 extra key 名称: token + bytesMessage (与 connector 库一致)
     * 3. 发送 ACTION_MESSAGE 广播给 connector
     */
    private fun handlePushMessage(message: String) {
        Log.d(LOG_TAG, "handlePushMessage: received ${message.take(120)}")

        try {
            // ntfy 发送的 JSON 格式:
            // {"id":"xxx","time":1234,"event":"message","topic":"up_xxx","message":"{...}","title":"...","priority":...}
            //
            // 其中 "message" 字段包含 Synapse 通过 Matrix Gateway 发送的实际通知数据
            // 我们需要提取这个内部消息作为 UnifiedPush 的 payload

            val matrixPayload = extractMatrixPayload(message)

            val messageIntent = Intent(ACTION_MESSAGE).apply {
                // ⚠️ 关键修复: connector 库 MessagingReceiver.onReceive() 使用 "token" 查找 instance
                // HaohaoChat: 进程被 kill 后 currentInstance 为 null，从 SharedPreferences 恢复
                val token = EmbeddedNtfyDistributor.getSavedInstance(this@PushForegroundService) ?: ""
                putExtra(EXTRA_TOKEN, token)
                // ⚠️ 关键修复: connector 库使用 getByteArrayExtra("bytesMessage") 读取消息
                putExtra(EXTRA_BYTES_MESSAGE, matrixPayload.toByteArray(Charsets.UTF_8))

                // 可选：消息 ID 用于 ACK
                putExtra(EXTRA_MESSAGE_ID, java.util.UUID.randomUUID().toString())

                // 显式定向到本应用的 connector
                `package` = packageName

                // 高优先级广播
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
            }

            sendBroadcast(messageIntent)
            Log.d(LOG_TAG, "✅ Forwarded push message to connector (${matrixPayload.length} bytes)")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "❌ Failed to forward push message", e)
        }
    }

    /**
     * 从 ntfy JSON 中提取 Matrix 通知负载
     *
     * ntfy 消息格式示例:
     * {"id":"abc","time":1234567890,"event":"message","topic":"up_xxx",
     *  "message":"{\"notification\":{\"event_id\":\"$xxx\",...}}",
     *  "priority":4,"tags":["matrix"]}
     *
     * 我们需要提取 "message" 字段的内容作为 UnifiedPush 的 payload
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

    // ==================== 通知相关 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "保持推送连接在后台活跃"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String = "正在监听推送通知..."): Notification {
        // 点击通知打开主界面
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Haohao聊天 推送服务")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
