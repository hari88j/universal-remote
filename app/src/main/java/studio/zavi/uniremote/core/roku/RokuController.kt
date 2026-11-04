package studio.zavi.uniremote.core.roku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.zavi.uniremote.core.NotReachableException
import studio.zavi.uniremote.core.PairingHandler
import studio.zavi.uniremote.core.RemoteKey
import studio.zavi.uniremote.core.TvApp
import studio.zavi.uniremote.core.TvController
import studio.zavi.uniremote.core.TvDevice
import studio.zavi.uniremote.core.TvException
import studio.zavi.uniremote.core.discovery.Xml
import studio.zavi.uniremote.core.net.Http
import studio.zavi.uniremote.core.net.HttpStatusException
import java.io.IOException
import java.net.URLEncoder

/** Roku External Control Protocol: plain HTTP on port 8060, no pairing. */
class RokuController(initial: TvDevice) : TvController {

    @Volatile
    override var device: TvDevice = initial
        private set

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    override val supportsText = true
    override val supportsApps = true

    // Keeps key presses in order when they are fired quickly.
    private val order = Mutex()

    private val base get() = "http://${device.ip}:8060"

    override fun supports(key: RemoteKey): Boolean = keyName(key) != null

    override suspend fun connect(pairing: PairingHandler): TvDevice = withContext(Dispatchers.IO) {
        val info = call { Http.get("$base/query/device-info", 3000) }
        val mac = Xml.tag(info, "wifi-mac") ?: Xml.tag(info, "ethernet-mac")
        device = device.copy(model = Xml.tag(info, "model-name") ?: device.model, mac = mac ?: device.mac)
        _connected.value = true
        device
    }

    override suspend fun send(key: RemoteKey) = withContext(Dispatchers.IO) {
        val name = keyName(key) ?: return@withContext
        order.withLock { call { Http.post("$base/keypress/$name", timeoutMs = 3000) } }
        Unit
    }

    override suspend fun sendText(text: String) = withContext(Dispatchers.IO) {
        order.withLock {
            for (ch in text.codePoints().toArray()) {
                val encoded = URLEncoder.encode(String(Character.toChars(ch)), "UTF-8").replace("+", "%20")
                call { Http.post("$base/keypress/Lit_$encoded", timeoutMs = 3000) }
            }
        }
    }

    override suspend fun apps(): List<TvApp> = withContext(Dispatchers.IO) {
        val xml = call { Http.get("$base/query/apps", 4000) }
        Regex("<app\\s+id=\"([^\"]+)\"[^>]*>(.*?)</app>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { TvApp(it.groupValues[1], Xml.unescape(it.groupValues[2].trim())) }
            .toList()
    }

    override suspend fun launch(app: TvApp) = withContext(Dispatchers.IO) {
        val id = URLEncoder.encode(app.id, "UTF-8")
        call { Http.post("$base/launch/$id", timeoutMs = 4000) }
        Unit
    }

    override fun close() {
        _connected.value = false
    }

    private inline fun <T> call(block: () -> T): T = try {
        block().also { _connected.value = true }
    } catch (e: HttpStatusException) {
        if (e.code == 403) {
            throw TvException(
                "${device.name} blocked the request. On the Roku open Settings > System > Advanced system settings > " +
                    "Control by mobile apps > Network access and choose Default."
            )
        }
        throw TvException("${device.name} returned an error (HTTP ${e.code}).")
    } catch (e: IOException) {
        _connected.value = false
        throw NotReachableException("Can't reach ${device.name}. Check it's on and on the same Wi-Fi.", e)
    }

    private fun keyName(key: RemoteKey): String? = when (key) {
        RemoteKey.POWER -> "Power"
        RemoteKey.HOME -> "Home"
        RemoteKey.BACK -> "Back"
        RemoteKey.MENU -> "Info"
        RemoteKey.INFO -> "Info"
        RemoteKey.INPUT -> null
        RemoteKey.UP -> "Up"
        RemoteKey.DOWN -> "Down"
        RemoteKey.LEFT -> "Left"
        RemoteKey.RIGHT -> "Right"
        RemoteKey.OK -> "Select"
        RemoteKey.VOLUME_UP -> "VolumeUp"
        RemoteKey.VOLUME_DOWN -> "VolumeDown"
        RemoteKey.MUTE -> "VolumeMute"
        RemoteKey.CHANNEL_UP -> "ChannelUp"
        RemoteKey.CHANNEL_DOWN -> "ChannelDown"
        RemoteKey.PLAY_PAUSE -> "Play"
        RemoteKey.REWIND -> "Rev"
        RemoteKey.FAST_FORWARD -> "Fwd"
        RemoteKey.BACKSPACE -> "Backspace"
        RemoteKey.ENTER -> "Enter"
        else -> "Lit_" + RemoteKey.digits.indexOf(key)
    }
}
