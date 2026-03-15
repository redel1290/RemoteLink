package com.remotelink.service

import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import kotlin.experimental.xor

class RawWebSocketServer(private val port: Int) {

    var onClientConnected: ((String) -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var clientOut: OutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        scope.launch {
            serverSocket = ServerSocket(port)
            while (isActive) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    handleClient(socket)
                } catch (_: Exception) {}
            }
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

                clientSocket = socket
                clientOut = out
                onClientConnected?.invoke(socket.inetAddress.hostAddress ?: "")

                // Читаємо повідомлення від клієнта
                while (isActive && !socket.isClosed) {
                    val msg = readFrame(inp) ?: break
                    onMessage?.invoke(msg)
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

    fun broadcast(data: ByteArray) {
        val out = clientOut ?: return
        try {
            writeBinaryFrame(out, data)
        } catch (_: Exception) {
            clientOut = null
        }
    }

    fun stop() {
        scope.cancel()
        clientSocket?.close()
        serverSocket?.close()
    }

    // ── WebSocket handshake ──────────────────────────────────────────────────

    private fun performHandshake(inp: InputStream, out: OutputStream): Boolean {
        val request = StringBuilder()
        val buffer = ByteArray(4096)
        var totalRead = 0

        // Читаємо HTTP запит до \r\n\r\n
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
        val combined = key + magic
        val sha1 = MessageDigest.getInstance("SHA-1").digest(combined.toByteArray())
        return Base64.getEncoder().encodeToString(sha1)
    }

    // ── Читання фрейму від клієнта (masked) ─────────────────────────────────

    private fun readFrame(inp: InputStream): String? {
        return try {
            val b0 = inp.read()
            val b1 = inp.read()
            if (b0 == -1 || b1 == -1) return null

            val opcode = b0 and 0x0F
            if (opcode == 8) return null // close frame

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
                for (i in payload.indices) {
                    payload[i] = payload[i] xor maskKey[i % 4]
                }
            }

            String(payload, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    // ── Запис бінарного фрейму до клієнта (unmask) ──────────────────────────

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
}
