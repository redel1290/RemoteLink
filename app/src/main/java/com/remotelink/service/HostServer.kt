package com.remotelink.service

import com.google.gson.Gson
import com.remotelink.NetworkConfig
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.experimental.xor
import kotlin.random.Random

class HostServer {

    private val gson = Gson()
    private var udpSocket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var clientOut: OutputStream? = null

    var onClientConnected: ((String) -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null
    var onTouchEvent: ((TouchEvent) -> Unit)? = null

    // 6-значний код генерується при старті
    val pairingCode: String = String.format("%06d", Random.nextInt(1000000))

    fun start() {
        startWsServer()
        startUdpBeacon()
    }

    private fun startWsServer() {
        scope.launch {
            try {
                serverSocket = ServerSocket(NetworkConfig.WS_PORT)
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    handleClient(socket)
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleClient(socket: Socket) {
        scope.launch {
            try {
                val inp = socket.getInputStream()
                val out = socket.getOutputStream()

                if (!performHandshake(inp, out)) {
                    socket.close()
                    return@launch
                }

                // Перший фрейм — це код підключення від клієнта
                val codeMsg = readTextFrame(inp)
                if (codeMsg != pairingCode) {
                    // Неправильний код — відхиляємо
                    writeTextFrame(out, "WRONG_CODE")
                    socket.close()
                    return@launch
                }

                // Код правильний — підтверджуємо
                writeTextFrame(out, "OK")
                clientSocket = socket
                clientOut = out
                onClientConnected?.invoke(socket.inetAddress.hostAddress ?: "")

                // Читаємо команди керування
                while (isActive && !socket.isClosed) {
                    val msg = readTextFrame(inp) ?: break
                    try {
                        val event = gson.fromJson(msg, TouchEvent::class.java)
                        onTouchEvent?.invoke(event)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            } finally {
                clientSocket = null
                clientOut = null
                socket.close()
                onClientDisconnected?.invoke()
            }
        }
    }

    fun sendFrame(jpegBytes: ByteArray) {
        val out = clientOut ?: return
        try {
            writeBinaryFrame(out, jpegBytes)
        } catch (_: Exception) {
            clientOut = null
        }
    }

    fun stop() {
        scope.cancel()
        udpSocket?.close()
        clientSocket?.close()
        serverSocket?.close()
    }

    private fun startUdpBeacon() {
        scope.launch {
            udpSocket = DatagramSocket()
            udpSocket?.broadcast = true
            // Включаємо код в beacon щоб клієнт міг його отримати
            val msg = "${NetworkConfig.UDP_BROADCAST_PREFIX}$pairingCode".toByteArray()
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

    // ── WebSocket handshake ──────────────────────────────────────────────────

    private fun performHandshake(inp: InputStream, out: OutputStream): Boolean {
        val request = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b == -1) return false
            request.append(b.toChar())
            if (request.endsWith("\r\n\r\n")) break
            if (request.length > 8192) return false
        }
        val headers = request.toString()
        val keyLine = headers.lines().firstOrNull {
            it.startsWith("Sec-WebSocket-Key:")
        } ?: return false
        val key = keyLine.substringAfter(":").trim()
        val acceptKey = generateAcceptKey(key)
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
        out.write(response.toByteArray())
        out.flush()
        return true
    }

    private fun generateAcceptKey(key: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = MessageDigest.getInstance("SHA-1").digest((key + magic).toByteArray())
        return Base64.getEncoder().encodeToString(sha1)
    }

    // ── Читання текстового фрейму (masked, від клієнта) ─────────────────────

    private fun readTextFrame(inp: InputStream): String? {
        return try {
            val b0 = inp.read()
            val b1 = inp.read()
            if (b0 == -1 || b1 == -1) return null
            val opcode = b0 and 0x0F
            if (opcode == 8) return null // close
            val masked = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7F).toLong()
            if (payloadLen == 126L) {
                payloadLen = ((inp.read() shl 8) or inp.read()).toLong()
            } else if (payloadLen == 127L) {
                payloadLen = 0
                for (i in 0..7) payloadLen = (payloadLen shl 8) or inp.read().toLong()
            }
            val maskKey = if (masked) ByteArray(4) { inp.read().toByte() } else null
            val payload = ByteArray(payloadLen.toInt())
            var offset = 0
            while (offset < payload.size) {
                val read = inp.read(payload, offset, payload.size - offset)
                if (read == -1) return null
                offset += read
            }
            if (masked && maskKey != null) {
                for (i in payload.indices) payload[i] = payload[i] xor maskKey[i % 4]
            }
            String(payload, Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    // ── Запис текстового фрейму (unmask, до клієнта) ────────────────────────

    private fun writeTextFrame(out: OutputStream, text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        val len = data.size
        val header = when {
            len <= 125 -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(0x81.toByte(), 126.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
        }
        synchronized(out) {
            out.write(header)
            out.write(data)
            out.flush()
        }
    }

    // ── Запис бінарного фрейму (unmask, до клієнта) ─────────────────────────

    private fun writeBinaryFrame(out: OutputStream, data: ByteArray) {
        val len = data.size
        val header = when {
            len <= 125 -> byteArrayOf(0x82.toByte(), len.toByte())
            len <= 65535 -> byteArrayOf(
                0x82.toByte(), 126.toByte(),
                (len shr 8).toByte(), (len and 0xFF).toByte()
            )
            else -> byteArrayOf(
                0x82.toByte(), 127.toByte(),
                0, 0, 0, 0,
                (len shr 24).toByte(), (len shr 16).toByte(),
                (len shr 8).toByte(), (len and 0xFF).toByte()
            )
        }
        synchronized(out) {
            out.write(header)
            out.write(data)
            out.flush()
        }
    }

    data class TouchEvent(
        val type: String,
        val x: Float = 0f,
        val y: Float = 0f,
        val x2: Float = 0f,
        val y2: Float = 0f,
        val screenWidth: Int = 0,
        val screenHeight: Int = 0
    )
}
