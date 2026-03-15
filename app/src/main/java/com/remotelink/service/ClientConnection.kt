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
        .readTimeout(0, TimeUnit.SECONDS) // без таймауту — постійне з'єднання
        .build()

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onFrame: ((ByteArray) -> Unit)? = null
    var onSearching: (() -> Unit)? = null

    fun startDiscovery() {
        scope.launch {
            onSearching?.invoke()
            val hostIp = discoverHost() ?: run {
                onDisconnected?.invoke()
                return@launch
            }
            connect(hostIp)
        }
    }

    private suspend fun discoverHost(): String? {
        return withTimeoutOrNull(15_000L) {
            val socket = DatagramSocket(NetworkConfig.UDP_PORT)
            socket.broadcast = true
            val buffer = ByteArray(256)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                val msg = String(packet.data, 0, packet.length)
                if (msg == NetworkConfig.UDP_BROADCAST_MSG) {
                    packet.address.hostAddress
                } else null
            } catch (_: Exception) {
                null
            } finally {
                socket.close()
            }
        }
    }

    private fun connect(ip: String) {
        val request = Request.Builder()
            .url("ws://$ip:${NetworkConfig.WS_PORT}")
            .build()

        webSocket = okClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                scope.launch(Dispatchers.Main) { onConnected?.invoke() }
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
