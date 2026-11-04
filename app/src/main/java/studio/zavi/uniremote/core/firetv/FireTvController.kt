package studio.zavi.uniremote.core.firetv

import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.zavi.uniremote.core.AndroidKeyCodes
import studio.zavi.uniremote.core.NotReachableException
import studio.zavi.uniremote.core.PairingHandler
import studio.zavi.uniremote.core.PairingRejectedException
import studio.zavi.uniremote.core.RemoteKey
import studio.zavi.uniremote.core.TvApp
import studio.zavi.uniremote.core.TvController
import studio.zavi.uniremote.core.TvDevice
import studio.zavi.uniremote.core.TvException
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Amazon Fire TV over network ADB (port 5555). Needs "ADB debugging" switched on in
 * the Fire TV's Developer options; the first connection shows "Allow USB debugging?".
 * Keys are sent with `input keyevent`, so each press takes a fraction of a second.
 */
class FireTvController(
    initial: TvDevice,
    private val keyDir: File,
) : TvController {

    @Volatile
    override var device: TvDevice = initial
        private set

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    override val supportsText = true
    override val supportsApps = true

    private val lock = Mutex()

    @Volatile
    private var dadb: Dadb? = null

    override fun supports(key: RemoteKey): Boolean = key != RemoteKey.INPUT

    override suspend fun connect(pairing: PairingHandler): TvDevice = withContext(Dispatchers.IO) {
        lock.withLock { open(pairing) }
        device
    }

    override suspend fun send(key: RemoteKey) = withContext(Dispatchers.IO) {
        if (!supports(key)) return@withContext
        shell("input keyevent ${AndroidKeyCodes.of(key)}")
        Unit
    }

    override suspend fun sendText(text: String) = withContext(Dispatchers.IO) {
        if (text.isEmpty()) return@withContext
        // `input text` treats %s as a space; quote everything else for the shell.
        val arg = text.replace(" ", "%s").replace("'", "'\\''")
        shell("input text '$arg'")
        Unit
    }

    override suspend fun apps(): List<TvApp> = withContext(Dispatchers.IO) {
        shell("pm list packages -3").lines()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotEmpty() && PACKAGE.matches(it) }
            .map { TvApp(it, prettyName(it)) }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun launch(app: TvApp) = withContext(Dispatchers.IO) {
        if (!PACKAGE.matches(app.id)) throw TvException("Bad app id")
        val out = shell(
            "monkey -p ${app.id} -c android.intent.category.LEANBACK_LAUNCHER -c android.intent.category.LAUNCHER 1"
        )
        if (out.contains("No activities found", ignoreCase = true)) throw TvException("${app.name} can't be opened from here.")
    }

    override fun close() {
        runCatching { dadb?.close() }
        dadb = null
        _connected.value = false
    }

    private suspend fun shell(command: String): String {
        val d = dadb ?: lock.withLock { dadb ?: run { open(null); dadb!! } }
        return try {
            d.shell(command).allOutput
        } catch (e: Exception) {
            // Connection went stale (TV slept); reconnect once.
            val again = lock.withLock { open(null); dadb!! }
            try {
                again.shell(command).allOutput
            } catch (e2: Exception) {
                _connected.value = false
                throw NotReachableException("Lost connection to ${device.name}.", e2)
            }
        }
    }

    private suspend fun open(pairing: PairingHandler?) = coroutineScope {
        runCatching { dadb?.close() }
        dadb = null
        try {
            Socket().use { it.connect(InetSocketAddress(device.ip, PORT), 3000) }
        } catch (e: IOException) {
            throw NotReachableException(
                "Can't reach ${device.name}. On the Fire TV turn on Settings > My Fire TV > Developer options > ADB debugging.",
                e,
            )
        }
        val keyPair = loadKeyPair()
        // A socket timeout bounds how long we wait for the person to press Allow on the TV.
        val waitMs = if (pairing != null) 60_000 else 8_000
        val d = Dadb.create(device.ip, PORT, keyPair, 4000, waitMs)
        val prompt = launch {
            if (pairing == null) return@launch
            delay(1500)
            pairing.showWaiting("On the TV choose Allow (tick \"Always allow from this computer\").")
        }
        try {
            val reply = d.shell("echo ok").allOutput
            if (!reply.contains("ok")) throw TvException("${device.name} gave an unexpected reply.")
        } catch (e: TvException) {
            runCatching { d.close() }
            throw e
        } catch (e: Exception) {
            runCatching { d.close() }
            throw PairingRejectedException(
                "${device.name} didn't allow the connection. Choose Allow on the TV, or check ADB debugging is on."
            )
        } finally {
            prompt.cancel()
            pairing?.hideWaiting()
        }
        dadb = d
        _connected.value = true
    }

    private fun loadKeyPair(): AdbKeyPair {
        keyDir.mkdirs()
        val privateFile = File(keyDir, "adbkey")
        val publicFile = File(keyDir, "adbkey.pub")
        if (!privateFile.exists() || !publicFile.exists()) AdbKeyPair.generate(privateFile, publicFile)
        return AdbKeyPair.read(privateFile, publicFile)
    }

    private fun prettyName(pkg: String): String = KNOWN[pkg] ?: pkg.split('.')
        .lastOrNull { it.length > 2 && it !in GENERIC }
        ?.replaceFirstChar { it.uppercase() } ?: pkg

    companion object {
        private const val PORT = 5555
        private val PACKAGE = Regex("[A-Za-z0-9_.]+")
        private val GENERIC = setOf("android", "tv", "app", "firetv", "amazon", "com", "mobile", "leanback")
        private val KNOWN = mapOf(
            "com.netflix.ninja" to "Netflix",
            "com.amazon.firetv.youtube" to "YouTube",
            "com.google.android.youtube.tv" to "YouTube",
            "in.startv.hotstar" to "JioHotstar",
            "com.spotify.tv.android" to "Spotify",
            "com.disney.disneyplus" to "Disney+",
            "com.jio.media.ondemand" to "JioCinema",
            "com.graymatrix.did" to "ZEE5",
            "com.sonyliv" to "SonyLIV",
            "tv.twitch.android.viewer" to "Twitch",
            "org.xbmc.kodi" to "Kodi",
            "com.plexapp.android" to "Plex",
        )
    }
}
