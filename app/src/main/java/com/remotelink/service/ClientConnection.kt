package com.remotelink.service

import com.google.gson.Gson
import com.remotelink.NetworkConfig
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.TimeUnit

class ClientConnection {

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private val okClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onWrongCode: (() -> Unit)? = null
    var onFrame: ((ByteArray) -> Unit)? = null
    var onSearching: (() -> Unit)? = null
    var onUdpTimeout: (() -> Unit)? = null  // UDP не знайшов — просимо IP вручну

    // Підключення через UDP auto-discovery
    fun connectWithCode(code: String) {
        scope.launch {
            onSearching?.invoke()
            val hostIp = discoverHost(code)
            if (hostIp == null) {
                // UDP не спрацював — просимо IP вручну
                onUdpTimeout?.invoke()
                return@launch
            }
            connectToIp(hostIp, code)
        }
    }

    // Підключення з ручним IP (якщо UDP не знайшов)
    fun connectWithIp(ip: String, code: String) {
        scope.launch {
            onSearching?.invoke()
            connectToIp(ip, code)
        }
    }

    private suspend fun discoverHost(code: String): String? {
        return withTimeoutOrNull(15_000L) {
            var found: String? = null
            val socket = try {
                DatagramSocket(NetworkConfig.UDP_PORT)
            } catch (_: Exception) { return@withTimeoutOrNull null }
            socket.broadcast = true
            socket.soTimeout = 2000
            val buffer = ByteArray(256)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                while (found == null) {
                    try {
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        if (msg == "${NetworkConfig.UDP_BROADCAST_PREFIX}$code") {
                            found = packet.address.hostAddress
                        }
                    } catch (_: Exception) { /* timeout — спробуємо ще */ }
                }
                found
            } finally {
                socket.close()
            }
        }
    }

    private fun connectToIp(ip: String, code: String) {
        val request = Request.Builder()
            .url("ws://$ip:${NetworkConfig.WS_PORT}")
            .build()

        webSocket = okClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(code)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                when (text) {
                    "OK" -> scope.launch(Dispatchers.Main) { onConnected?.invoke() }
                    "WRONG_CODE" -> {
                        scope.launch(Dispatchers.Main) { onWrongCode?.invoke() }
                        ws.close(1000, "Wrong code")
                    }
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // Декодуємо в IO потоці — не чіпаємо Main
                onFrame?.invoke(bytes.toByteArray())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                scope.launch(Dispatchers.Main) { onDisconnected?.invoke() }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                scope.launch(Dispatchers.Main) { onDisconnected?.invoke() }
            }
        })
    }

    fun sendTouchEvent(event: HostServer.TouchEvent) {
        webSocket?.send(gson.toJson(event))
    }

    fun disconnect() {
        webSocket?.close(1000, "Client closed")
        scope.cancel()
    }
}
