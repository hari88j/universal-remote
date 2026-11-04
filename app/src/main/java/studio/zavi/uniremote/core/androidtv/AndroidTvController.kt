package studio.zavi.uniremote.core.androidtv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import studio.zavi.uniremote.core.AndroidKeyCodes
import studio.zavi.uniremote.core.NeedsPairingException
import studio.zavi.uniremote.core.NotReachableException
import studio.zavi.uniremote.core.PairingCancelledException
import studio.zavi.uniremote.core.PairingHandler
import studio.zavi.uniremote.core.PairingRejectedException
import studio.zavi.uniremote.core.RemoteKey
import studio.zavi.uniremote.core.TvApp
import studio.zavi.uniremote.core.TvController
import studio.zavi.uniremote.core.TvDevice
import studio.zavi.uniremote.core.TvException
import studio.zavi.uniremote.core.VolumeInfo
import studio.zavi.uniremote.core.net.Tls
import java.io.BufferedInputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Android TV / Google TV over the "Android TV Remote protocol v2" — the same
 * protocol Google's own TV remote app uses. Needs no developer settings on the TV,
 * only the pre-installed "Android TV Remote Service".
 *
 * Port 6467: one-time pairing (TV shows a 6-character code).
 * Port 6466: the remote-control channel, authenticated by our client certificate.
 */
class AndroidTvController(
    initial: TvDevice,
    private val identity: ClientIdentity,
    private val clientName: String,
    private val remotePort: Int = 6466,
    private val pairingPort: Int = 6467,
) : TvController {

    @Volatile
    override var device: TvDevice = initial
        private set

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _volume = MutableStateFlow<VolumeInfo?>(null)
    override val volume: StateFlow<VolumeInfo?> = _volume.asStateFlow()

    override val supportsText = true
    override val supportsApps = true
    override val supportsLinks = true
    override val textReplacesField = true

    override fun supports(key: RemoteKey): Boolean = true

    private val lock = Mutex()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var closed = false

    override suspend fun connect(pairing: PairingHandler): TvDevice = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                openSession()
            } catch (e: NeedsPairingException) {
                pair(pairing)
                openSession()
            }
        }
        device
    }

    override suspend fun send(key: RemoteKey) = withContext(Dispatchers.IO) {
        ensureSession().sendKey(AndroidKeyCodes.of(key))
    }

    override suspend fun sendText(text: String) = withContext(Dispatchers.IO) {
        if (text.isNotEmpty()) ensureSession().sendText(text)
    }

    override suspend fun apps(): List<TvApp> = APP_LINKS

    override suspend fun launch(app: TvApp) = openLink(app.id)

    override suspend fun openLink(url: String) = withContext(Dispatchers.IO) {
        ensureSession().openLink(url)
    }

    override fun close() {
        closed = true
        session?.close()
        session = null
        _connected.value = false
    }

    private suspend fun ensureSession(): Session {
        session?.takeIf { it.isOpen }?.let { return it }
        return lock.withLock {
            session?.takeIf { it.isOpen } ?: run {
                openSession()
                session ?: throw TvException("Not connected")
            }
        }
    }

    // ---------------------------------------------------------------- remote channel

    private suspend fun openSession() {
        if (closed) throw TvException("Disconnected")
        session?.close()
        val (key, cert) = identity.get()
        val socket = try {
            Tls.connect(Tls.clientCertContext(key, cert), device.ip, remotePort, 5000)
        } catch (e: SSLException) {
            throw NeedsPairingException(cause = e)
        } catch (e: IOException) {
            throw NotReachableException("Can't reach ${device.name}. Check it's on and on the same Wi-Fi.", e)
        }
        val s = Session(socket)
        session = s
        s.start()
        try {
            withTimeout(8000) { s.started.await() }
        } catch (e: TimeoutCancellationException) {
            s.close()
            throw NotReachableException("${device.name} didn't answer. Try again.")
        } catch (e: CancellationException) {
            s.close()
            throw e
        } catch (e: Exception) {
            s.close()
            // A paired phone gets a message right away; being dropped before any is the TV refusing our cert.
            val unpaired = e is SSLException || (e is IOException && !s.receivedAny)
            throw if (unpaired) NeedsPairingException(cause = e)
            else NotReachableException("Lost connection to ${device.name}.", e)
        }
        _connected.value = true
    }

    private inner class Session(private val socket: SSLSocket) {
        private val input = BufferedInputStream(socket.inputStream)
        private val output = socket.outputStream
        private val writeLock = Any()
        val started = CompletableDeferred<Unit>()

        @Volatile var isOpen = true
            private set
        @Volatile var receivedAny = false
            private set
        @Volatile private var imeCounter = 0
        @Volatile private var fieldCounter = 0
        private var active = FEATURES

        fun start() {
            // The TV pings every 5 s; 20 s of silence means it's gone.
            socket.soTimeout = 20_000
            thread(name = "atv-remote", isDaemon = true) { readLoop() }
        }

        private fun readLoop() {
            try {
                while (isOpen) {
                    val raw = ProtoFraming.read(input)
                    receivedAny = true
                    handle(ProtoMessage.parse(raw))
                }
            } catch (t: Throwable) {
                started.completeExceptionally(t)
            } finally {
                close()
            }
        }

        private fun handle(m: ProtoMessage) {
            when {
                m.has(F_CONFIGURE) -> {
                    val supported = m.message(F_CONFIGURE)?.long(1) ?: 0L
                    active = active and supported
                    write {
                        message(F_CONFIGURE) {
                            varint(1, active)
                            message(2) {
                                varint(3, 1)
                                string(4, "1")
                                string(5, "atvremote")
                                string(6, "1.0.0")
                            }
                        }
                    }
                }
                m.has(F_SET_ACTIVE) -> write { message(F_SET_ACTIVE) { varint(1, active) } }
                m.has(F_PING_REQUEST) -> {
                    val v = m.message(F_PING_REQUEST)?.long(1) ?: 0L
                    write { message(F_PING_RESPONSE) { if (v != 0L) varint(1, v) } }
                }
                m.has(F_START) -> started.complete(Unit)
                m.has(F_IME_BATCH_EDIT) -> {
                    val b = m.message(F_IME_BATCH_EDIT) ?: return
                    imeCounter = b.int(1)
                    fieldCounter = b.int(2)
                }
                m.has(F_SET_VOLUME) -> {
                    val v = m.message(F_SET_VOLUME) ?: return
                    _volume.value = VolumeInfo(level = v.int(7), max = v.int(6), muted = v.long(8) != 0L)
                }
            }
        }

        fun sendKey(code: Int) = write {
            message(F_KEY_INJECT) {
                varint(1, code)
                varint(2, DIRECTION_SHORT)
            }
        }

        fun sendText(text: String) {
            val pos = text.codePointCount(0, text.length) - 1
            write {
                message(F_IME_BATCH_EDIT) {
                    if (imeCounter != 0) varint(1, imeCounter)
                    if (fieldCounter != 0) varint(2, fieldCounter)
                    message(3) {
                        varint(1, 1)
                        message(2) {
                            if (pos != 0) varint(1, pos)
                            if (pos != 0) varint(2, pos)
                            string(3, text)
                        }
                    }
                }
            }
        }

        fun openLink(url: String) = write { message(F_APP_LINK) { string(1, url) } }

        private fun write(build: ProtoWriter.() -> Unit) {
            val bytes = ProtoWriter().apply(build).toByteArray()
            try {
                synchronized(writeLock) { ProtoFraming.write(output, bytes) }
            } catch (e: IOException) {
                close()
                throw NotReachableException("Lost connection to ${device.name}.", e)
            }
        }

        fun close() {
            if (!isOpen) return
            isOpen = false
            runCatching { socket.close() }
            if (session === this) _connected.value = false
        }
    }

    // ---------------------------------------------------------------- pairing

    private suspend fun pair(handler: PairingHandler) {
        val (key, cert) = identity.get()
        val socket = try {
            Tls.connect(Tls.clientCertContext(key, cert), device.ip, pairingPort, 5000)
        } catch (e: IOException) {
            throw NotReachableException("Couldn't start pairing with ${device.name}.", e)
        }
        try {
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.inputStream)
            val output = socket.outputStream

            fun sendPolo(build: ProtoWriter.() -> Unit) {
                val msg = ProtoWriter().varint(1, 2).varint(2, STATUS_OK).apply(build).toByteArray()
                ProtoFraming.write(output, msg)
            }

            fun expect(field: Int): ProtoMessage {
                val m = ProtoMessage.parse(ProtoFraming.read(input))
                val status = m.int(2)
                if (status != STATUS_OK) {
                    throw PairingRejectedException(
                        if (status == STATUS_BAD_SECRET) "The TV rejected the code." else "The TV refused pairing (code $status)."
                    )
                }
                if (!m.has(field)) throw PairingRejectedException("Unexpected reply from the TV during pairing.")
                return m
            }

            sendPolo { message(10) { string(1, "atvremote"); string(2, clientName) } }
            expect(11)
            sendPolo {
                message(20) {
                    message(1) { varint(1, ENCODING_HEX); varint(2, 6) }
                    varint(3, ROLE_INPUT)
                }
            }
            expect(20)
            sendPolo {
                message(30) {
                    message(1) { varint(1, ENCODING_HEX); varint(2, 6) }
                    varint(2, ROLE_INPUT)
                }
            }
            expect(31) // the TV now shows the code

            val serverKey = (socket.session.peerCertificates.first() as X509Certificate).publicKey as RSAPublicKey
            val clientKey = cert.publicKey as RSAPublicKey
            var error: String? = null
            while (true) {
                val code = handler.requestCode(error)?.trim()?.uppercase() ?: throw PairingCancelledException()
                val secret = PairingSecret.compute(clientKey, serverKey, code)
                if (secret == null) {
                    error = "That code doesn't match. Check the TV and try again."
                    continue
                }
                sendPolo { message(40) { bytes(1, secret) } }
                expect(41)
                return
            }
        } catch (e: SSLException) {
            throw NotReachableException("Secure connection to ${device.name} failed during pairing.", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val F_CONFIGURE = 1
        private const val F_SET_ACTIVE = 2
        private const val F_PING_REQUEST = 8
        private const val F_PING_RESPONSE = 9
        private const val F_KEY_INJECT = 10
        private const val F_IME_BATCH_EDIT = 21
        private const val F_START = 40
        private const val F_SET_VOLUME = 50
        private const val F_APP_LINK = 90

        private const val DIRECTION_SHORT = 3
        private const val STATUS_OK = 200
        private const val STATUS_BAD_SECRET = 402
        private const val ENCODING_HEX = 3
        private const val ROLE_INPUT = 1

        // PING | KEY | IME | POWER | VOLUME | APP_LINK
        private const val FEATURES = 1L or 2L or 4L or 32L or 64L or 512L

        val APP_LINKS = listOf(
            TvApp("https://www.youtube.com", "YouTube"),
            TvApp("https://www.netflix.com/title", "Netflix"),
            TvApp("https://app.primevideo.com", "Prime Video"),
            TvApp("https://www.hotstar.com", "JioHotstar"),
            TvApp("https://music.youtube.com", "YouTube Music"),
            TvApp("https://open.spotify.com", "Spotify"),
            TvApp("https://www.disneyplus.com", "Disney+"),
        )
    }
}

/**
 * The pairing secret: SHA-256 over both public keys and the last 4 hex digits of the
 * on-screen code. The first 2 hex digits of the code must equal the hash's first byte,
 * which lets us reject a mistyped code before sending anything.
 */
object PairingSecret {
    fun compute(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray? {
        if (code.length != 6 || !code.all { it in '0'..'9' || it in 'A'..'F' }) return null
        val md = MessageDigest.getInstance("SHA-256")
        md.update(hex(client.modulus.toString(16)))
        md.update(hex("0" + client.publicExponent.toString(16)))
        md.update(hex(server.modulus.toString(16)))
        md.update(hex("0" + server.publicExponent.toString(16)))
        md.update(hex(code.substring(2)))
        val hash = md.digest()
        return if ((hash[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)) hash else null
    }

    private fun hex(s: String): ByteArray {
        val h = if (s.length % 2 == 1) "0$s" else s
        return ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
