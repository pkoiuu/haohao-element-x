/*
 * HaohaoChat 内置 ntfy 推送分发器配置
 * 技术实现: 使用 ntfy WebSocket 接收推送消息
 *
 * v26.06.5 重要发现:
 * readTimeoutSeconds 不再用于 OkHttpClient 构建。
 * OkHttp 源码中 RealWebSocket.connect() 在握手成功后强制执行
 * socket.setSoTimeout(0)，无论 OkHttpClient 配置的 readTimeout 是什么值。
 * WebSocket 的死连接检测完全依赖 OkHttp 的 PING/PONG 机制:
 * - 每 pingIntervalSeconds 发送 PING 帧
 * - 如果上一个 PING 的 PONG 未收到，立即 failWebSocket → onFailure
 * - 这能可靠检测半开连接 (如 WiFi 休眠导致的 TCP 断开)
 * 因此 readTimeoutSeconds 字段保留但不再实际使用。
 */

package io.element.android.libraries.pushproviders.unifiedpush

/**
 * 内置 ntfy 客户端配置
 */
data class EmbeddedNtfyConfig(
    /** ntfy 服务器地址，如 https://notify.hhj520.top */
    val serverUrl: String = "https://notify.hhj520.top",
    /** topic 前缀 */
    val topicPrefix: String = "up_",
    /** WebSocket 心跳间隔（秒）— 15s 确保在 nginx proxy_read_timeout 之前发送 ping
     *  OkHttp 的 PING/PONG 机制也依赖此间隔检测半开连接 */
    val heartbeatIntervalSeconds: Long = 15L,
    /** 连接超时（秒） */
    val connectTimeoutSeconds: Long = 10L,
    /** 读取超时（秒）— 已废弃，OkHttp WebSocket 内部强制设为 0，不依赖此值 */
    val readTimeoutSeconds: Long = 0L,
)
