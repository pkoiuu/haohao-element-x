/*
 * HaohaoChat 内置 ntfy 推送分发器配置
 * 技术实现: 使用 ntfy WebSocket 接收推送消息
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
    /** WebSocket 心跳间隔（秒）— 15s 确保在 nginx proxy_read_timeout 之前发送 ping */
    val heartbeatIntervalSeconds: Long = 15L,
    /** 连接超时（秒） */
    val connectTimeoutSeconds: Long = 10L,
    /** 读取超时（秒） */
    val readTimeoutSeconds: Long = 0L, // 0 = 无限等待
)
