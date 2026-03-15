package com.remotelink

object NetworkConfig {
    const val WS_PORT = 8765
    const val UDP_PORT = 8766
    const val UDP_BROADCAST_PREFIX = "REMOTELINK:"  // формат: "REMOTELINK:123456"
    const val UDP_BROADCAST_INTERVAL_MS = 1000L
    const val JPEG_QUALITY = 60
    const val FRAME_INTERVAL_MS = 50L
    const val CHANNEL_ID = "remotelink_channel"
    const val NOTIFICATION_ID = 1001
}
