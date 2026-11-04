package studio.zavi.uniremote.core.net

import kotlinx.coroutines.channels.Channel
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.thread

/**
 * A small RFC 6455 WebSocket client (text messages, ping/pong, close).
 * Samsung and LG TVs speak WebSocket; keeping our own client means no extra
 * library and the same code path for ws:// and self-signed wss://.
 *
 * Incoming text arrives on [incoming]. The channel closes when the socket does.
 */
class MiniWebSocket private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) {
    val incoming = Channel<String>(Channel.UNLIMITED)

    @Volatile
    var isOpen = true
        private set

    private val writeLock = Any()
    private val random = SecureRandom()

    private fun startReader() {
        thread(name = "ws-reader", isDaemon = true) {
            var failure: Throwable? = null
            try {
                readLoop()
            } catch (t: Throwable) {
                if (isOpen) failure = t
            } finally {
                isOpen = false
                runCatching { socket.close() }
                incoming.close(failure)
            }
        }
    }

    private fun readLoop() {
        val message = ByteArrayOutputStream()
        var messageOpcode = -1
        while (isOpen) {
            val b0 = readByte()
            val b1 = readByte()
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) {
                length = ((readByte() shl 8) or readByte()).toLong()
            } else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or readByte().toLong() }
            }
            if (length > MAX_FRAME) throw IOException("WebSocket frame too large: $length")
            val mask = if (masked) readFully(4) else null
            val payload = readFully(length.toInt())
            if (mask != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            when (opcode) {
                OP_CONTINUATION -> {
                    message.write(payload)
                    if (fin && messageOpcode != -1) {
                        deliver(message.toByteArray())
                        message.reset()
                        messageOpcode = -1
                    }
                }
                OP_TEXT, OP_BINARY -> {
                    message.reset()
                    message.write(payload)
                    if (fin) {
                        deliver(message.toByteArray())
                        message.reset()
                    } else {
                        messageOpcode = opcode
                    }
                }
                OP_CLOSE -> {
                    runCatching { writeFrame(OP_CLOSE, payload.copyOfRange(0, minOf(2, payload.size))) }
                    isOpen = false
                }
                OP_PING -> writeFrame(OP_PONG, payload)
                OP_PONG -> Unit
                else -> throw IOException("Unknown WebSocket opcode $opcode")
            }
        }
    }

    private fun deliver(bytes: ByteArray) {
        incoming.trySend(String(bytes, Charsets.UTF_8))
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("WebSocket closed by TV")
        return b
    }

    private fun readFully(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("WebSocket closed by TV")
            off += r
        }
        return buf
    }

    /** Sends a text message. Throws if the socket is closed. */
    fun send(text: String) {
        if (!isOpen) throw IOException("WebSocket is closed")
        try {
            writeFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val out = ByteArrayOutputStream(payload.size + 14)
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                out.write(0x80 or 126)
                out.write(payload.size ushr 8)
                out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(0x80 or 127)
                val len = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) out.write(((len ushr shift) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        out.write(mask)
        for (i in payload.indices) out.write(payload[i].toInt() xor mask[i % 4].toInt())
        synchronized(writeLock) {
            output.write(out.toByteArray())
            output.flush()
        }
    }

    fun close() {
        if (!isOpen) {
            runCatching { socket.close() }
            return
        }
        isOpen = false
        runCatching { writeFrame(OP_CLOSE, byteArrayOf(0x03, 0xE8.toByte())) }
        runCatching { socket.close() }
        incoming.close()
    }

    companion object {
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val MAX_FRAME = 16L * 1024 * 1024
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /**
         * Connects to a ws:// or wss:// URL. wss:// accepts the TV's self-signed certificate.
         * Blocks until the HTTP upgrade completes.
         */
        fun connect(url: String, timeoutMs: Int = 5000): MiniWebSocket {
            val uri = URI(url)
            val secure = when (uri.scheme?.lowercase()) {
                "wss" -> true
                "ws" -> false
                else -> throw IllegalArgumentException("Not a WebSocket URL: $url")
            }
            val host = uri.host ?: throw IllegalArgumentException("No host in $url")
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val path = buildString {
                append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
                if (!uri.rawQuery.isNullOrEmpty()) append('?').append(uri.rawQuery)
            }

            val socket: Socket = if (secure) {
                Tls.connect(Tls.trustAllContext, host, port, timeoutMs)
            } else {
                Socket().apply {
                    connect(InetSocketAddress(host, port), timeoutMs)
                    tcpNoDelay = true
                }
            }
            try {
                socket.soTimeout = timeoutMs * 2
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()

                val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val key = Base64.getEncoder().encodeToString(keyBytes)
                val hostHeader = if ((secure && port == 443) || (!secure && port == 80)) host else "$host:$port"
                val request = "GET $path HTTP/1.1\r\n" +
                    "Host: $hostHeader\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "\r\n"
                output.write(request.toByteArray(Charsets.US_ASCII))
                output.flush()

                val head = readHttpHead(input)
                val lines = head.split("\r\n")
                val status = lines.first()
                if (!status.contains(" 101")) throw IOException("WebSocket upgrade refused: $status")
                val headers = lines.drop(1).mapNotNull { line ->
                    val i = line.indexOf(':')
                    if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
                }.toMap()
                val expected = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII))
                )
                val accept = headers["sec-websocket-accept"]
                if (accept != null && accept != expected) throw IOException("Bad WebSocket accept header")

                socket.soTimeout = 0
                return MiniWebSocket(socket, input, output).also { it.startReader() }
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }

        private fun readHttpHead(input: InputStream): String {
            val buf = ByteArrayOutputStream()
            var matched = 0
            val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            while (matched < 4) {
                val b = input.read()
                if (b < 0) throw EOFException("Connection closed during WebSocket upgrade")
                buf.write(b)
                matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
                if (buf.size() > 16 * 1024) throw IOException("WebSocket upgrade response too large")
            }
            return String(buf.toByteArray(), Charsets.ISO_8859_1).trimEnd()
        }
    }
}
