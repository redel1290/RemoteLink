package com.remotelink.service

import com.google.gson.Gson
import com.remotelink.NetworkConfig
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket

class HostServer {

    private val gson = Gson()
    private var udpSocket: DatagramSocket? = null
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clients = mutableListOf<WebSocket>()

    // Простий WebSocket сервер на OkHttp не підтримується напряму —
    // використовуємо raw TCP + WebSocket handshake через NanoHTTPD-подібний підхід
    // Але для простоти використаємо ServerSocket з власним WS протоколом
    private var rawServer: RawWebSocketServer? = null

    var onClientConnected: ((String) -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null
    var onTouchEvent: ((TouchEvent) -> Unit)? = null

    fun start() {
        rawServer = RawWebSocketServer(NetworkConfig.WS_PORT).also {
            it.onClientConnected = { ip -> onClientConnected?.invoke(ip) }
            it.onClientDisconnected = { onClientDisconnected?.invoke() }
            it.onMessage = { json ->
                try {
                    val event = gson.fromJson(json, TouchEvent::class.java)
                    onTouchEvent?.invoke(event)
                } catch (_: Exception) {}
            }
            it.start()
        }
        startUdpBeacon()
    }

    fun sendFrame(jpegBytes: ByteArray) {
        rawServer?.broadcast(jpegBytes)
    }

    fun stop() {
        scope.cancel()
        udpSocket?.close()
        rawServer?.stop()
    }

    private fun startUdpBeacon() {
        scope.launch {
            udpSocket = DatagramSocket()
            udpSocket?.broadcast = true
            val msg = NetworkConfig.UDP_BROADCAST_MSG.toByteArray()
            while (isActive) {
                try {
                    val packet = DatagramPacket(
                        msg, msg.size,
                        InetAddress.getByName("255.255.255.255"),
                        NetworkConfig.UDP_PORT
                    )
                    udpSocket?.send(packet)
                } catch (_: Exception) {}
                delay(NetworkConfig.UDP_BROADCAST_INTERVAL_MS)
            }
        }
    }

    data class TouchEvent(
        val type: String,   // tap, swipe, longpress, back, home, recents
        val x: Float = 0f,
        val y: Float = 0f,
        val x2: Float = 0f,
        val y2: Float = 0f,
        val screenWidth: Int = 0,
        val screenHeight: Int = 0
    )
}
