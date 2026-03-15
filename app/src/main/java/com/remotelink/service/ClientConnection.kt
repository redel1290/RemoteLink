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

    // Підключення з ручним введенням коду
    fun connectWithCode(code: String) {
        scope.launch {
            onSearching?.invoke()
            val result = discoverHost(code)
            if (result == null) {
                onDisconnected?.invoke()
                return@launch
            }
            connect(result, code)
        }
    }

    // UDP discovery — шукає beacon з потрібним кодом
    private suspend fun discoverHost(code: String): String? {
        return withTimeoutOrNull(15_000L) {
            var found: String? = null
            val socket = DatagramSocket(NetworkConfig.UDP_PORT)
            socket.broadcast = true
            val buffer = ByteArray(256)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                while (found == null) {
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    // beacon формат: "REMOTELINK:123456"
                    if (msg == "${NetworkConfig.UDP_BROADCAST_PREFIX}$code") {
                        found = packet.address.hostAddress
                    }
                }
                found
            } catch (_: Exception) {
                null
            } finally {
                socket.close()
            }
        }
    }

    private fun connect(ip: String, code: String) {
        val request = Request.Builder()
            .url("ws://$ip:${NetworkConfig.WS_PORT}")
            .build()

        webSocket = okClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                // Одразу надсилаємо код для верифікації
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
        val json = gson.toJson(event)
        webSocket?.send(json)
    }

    fun disconnect() {
        webSocket?.close(1000, "Client closed")
        scope.cancel()
    }
}
