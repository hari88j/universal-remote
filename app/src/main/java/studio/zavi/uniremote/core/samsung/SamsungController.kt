package studio.zavi.uniremote.core.samsung

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import studio.zavi.uniremote.core.JsonX
import studio.zavi.uniremote.core.NotReachableException
import studio.zavi.uniremote.core.PairingHandler
import studio.zavi.uniremote.core.PairingRejectedException
import studio.zavi.uniremote.core.RemoteKey
import studio.zavi.uniremote.core.TvApp
import studio.zavi.uniremote.core.TvController
import studio.zavi.uniremote.core.TvDevice
import studio.zavi.uniremote.core.TvException
import studio.zavi.uniremote.core.net.Http
import studio.zavi.uniremote.core.net.MiniWebSocket
import studio.zavi.uniremote.core.obj
import studio.zavi.uniremote.core.str
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64

/**
 * Samsung Tizen TVs (2016 onwards): WebSocket remote channel on port 8002 (wss, with a
 * saved token) or 8001 (ws, older sets). The first connection makes the TV ask "Allow?".
 */
class SamsungController(
    initial: TvDevice,
    private val appName: String,
) : TvController {

    data class Info(val name: String?, val model: String?, val mac: String?, val tokenAuth: Boolean)

    @Volatile
    override var device: TvDevice = initial
        private set

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    override val supportsText = true
    override val supportsApps = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    @Volatile
    private var socket: MiniWebSocket? = null
    private var playing = false

    override fun supports(key: RemoteKey): Boolean = key == RemoteKey.PLAY_PAUSE || keyCode(key) != null

    override suspend fun connect(pairing: PairingHandler): TvDevice = withContext(Dispatchers.IO) {
        lock.withLock { open(pairing) }
        device
    }

    override suspend fun send(key: RemoteKey) = withContext(Dispatchers.IO) {
        val code = if (key == RemoteKey.PLAY_PAUSE) {
            playing = !playing
            if (playing) "KEY_PLAY" else "KEY_PAUSE"
        } else {
            keyCode(key) ?: return@withContext
        }
        sendJson(remoteKey(code))
    }

    override suspend fun sendText(text: String) = withContext(Dispatchers.IO) {
        if (text.isEmpty()) return@withContext
        sendJson(buildJsonObject {
            put("method", "ms.channel.emit")
            putJsonObject("params") {
                put("event", "custom.remote.textReceived")
                put("to", "broadcast")
            }
        }.toString())
        sendJson(buildJsonObject {
            put("method", "ms.remote.control")
            putJsonObject("params") {
                put("Cmd", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
                put("DataOfCmd", "base64")
                put("TypeOfRemote", "SendInputString")
            }
        }.toString())
        sendJson(buildJsonObject {
            put("method", "ms.remote.control")
            putJsonObject("params") { put("TypeOfRemote", "SendInputEnd") }
        }.toString())
    }

    override suspend fun apps(): List<TvApp> = APPS

    override suspend fun launch(app: TvApp) = withContext(Dispatchers.IO) {
        val viaRest = runCatching { Http.post("http://${device.ip}:8001/api/v2/applications/${app.id}", timeoutMs = 4000) }
        if (viaRest.isFailure) {
            sendJson(buildJsonObject {
                put("method", "ms.channel.emit")
                putJsonObject("params") {
                    put("event", "ed.apps.launch")
                    put("to", "host")
                    putJsonObject("data") {
                        put("appId", app.id)
                        put("action_type", "DEEP_LINK")
                    }
                }
            }.toString())
        }
    }

    override fun close() {
        scope.cancel()
        socket?.close()
        socket = null
        _connected.value = false
    }

    private suspend fun sendJson(json: String) {
        val ws = ensure()
        try {
            ws.send(json)
        } catch (e: IOException) {
            // The TV dropped us (it does after standby); reconnect once and retry.
            val again = lock.withLock { open(null); socket ?: throw TvException("Not connected") }
            again.send(json)
        }
    }

    private suspend fun ensure(): MiniWebSocket {
        socket?.takeIf { it.isOpen }?.let { return it }
        return lock.withLock {
            socket?.takeIf { it.isOpen } ?: run {
                open(null)
                socket ?: throw TvException("Not connected")
            }
        }
    }

    private suspend fun open(pairing: PairingHandler?) {
        socket?.close()
        socket = null
        val info = fetchInfo(device.ip)
        if (info != null) {
            device = device.copy(mac = info.mac ?: device.mac, model = info.model ?: device.model)
        }
        val name = URLEncoder.encode(Base64.getEncoder().encodeToString(appName.toByteArray(Charsets.UTF_8)), "UTF-8")
        val secure = info?.tokenAuth ?: true
        val url = if (secure) {
            val token = device.token?.let { "&token=" + URLEncoder.encode(it, "UTF-8") } ?: ""
            "wss://${device.ip}:8002/api/v2/channels/samsung.remote.control?name=$name$token"
        } else {
            "ws://${device.ip}:8001/api/v2/channels/samsung.remote.control?name=$name"
        }
        val ws = try {
            MiniWebSocket.connect(url, 5000)
        } catch (e: IOException) {
            throw NotReachableException("Can't reach ${device.name}. Check it's on and on the same Wi-Fi.", e)
        }

        try {
            coroutineScope {
                val prompt = launch {
                    if (pairing == null) return@launch
                    if (device.token != null) delay(2500)
                    pairing.showWaiting("Look at your TV and choose Allow.")
                }
                try {
                    withTimeout(if (pairing != null) 45_000 else 8_000) {
                        var done = false
                        while (!done) {
                            val msg = JsonX.parseObject(ws.incoming.receive()) ?: continue
                            when (msg.str("event")) {
                                "ms.channel.connect" -> {
                                    msg.obj("data")?.str("token")?.let { device = device.copy(token = it) }
                                    done = true
                                }
                                "ms.channel.unauthorized" -> throw PairingRejectedException(
                                    "The TV blocked this phone. On the TV open Settings > General > External Device Manager > " +
                                        "Device Connection Manager > Device List, remove it, then try again."
                                )
                                "ms.channel.timeOut" -> throw PairingRejectedException("No answer on the TV. Try again and choose Allow.")
                            }
                        }
                    }
                } finally {
                    prompt.cancel()
                    pairing?.hideWaiting()
                }
            }
        } catch (e: TimeoutCancellationException) {
            ws.close()
            throw PairingRejectedException("Timed out waiting for ${device.name}. Choose Allow on the TV and try again.")
        } catch (e: CancellationException) {
            ws.close()
            throw e
        } catch (e: TvException) {
            ws.close()
            throw e
        } catch (e: Exception) {
            ws.close()
            throw NotReachableException("${device.name} closed the connection.", e)
        }

        socket = ws
        _connected.value = true
        scope.launch {
            runCatching { for (ignored in ws.incoming) Unit }
            if (socket === ws) _connected.value = false
        }
    }

    private fun remoteKey(code: String): String = buildJsonObject {
        put("method", "ms.remote.control")
        putJsonObject("params") {
            put("Cmd", "Click")
            put("DataOfCmd", code)
            put("Option", "false")
            put("TypeOfRemote", "SendRemoteKey")
        }
    }.toString()

    private fun keyCode(key: RemoteKey): String? = when (key) {
        RemoteKey.POWER -> "KEY_POWER"
        RemoteKey.HOME -> "KEY_HOME"
        RemoteKey.BACK -> "KEY_RETURN"
        RemoteKey.MENU -> "KEY_MENU"
        RemoteKey.INPUT -> "KEY_SOURCE"
        RemoteKey.INFO -> "KEY_INFO"
        RemoteKey.UP -> "KEY_UP"
        RemoteKey.DOWN -> "KEY_DOWN"
        RemoteKey.LEFT -> "KEY_LEFT"
        RemoteKey.RIGHT -> "KEY_RIGHT"
        RemoteKey.OK -> "KEY_ENTER"
        RemoteKey.VOLUME_UP -> "KEY_VOLUP"
        RemoteKey.VOLUME_DOWN -> "KEY_VOLDOWN"
        RemoteKey.MUTE -> "KEY_MUTE"
        RemoteKey.CHANNEL_UP -> "KEY_CHUP"
        RemoteKey.CHANNEL_DOWN -> "KEY_CHDOWN"
        RemoteKey.PLAY_PAUSE -> null
        RemoteKey.REWIND -> "KEY_REWIND"
        RemoteKey.FAST_FORWARD -> "KEY_FF"
        RemoteKey.ENTER -> "KEY_ENTER"
        RemoteKey.BACKSPACE -> null
        else -> if (key in RemoteKey.digits) "KEY_${RemoteKey.digits.indexOf(key)}" else null
    }

    companion object {
        val APPS = listOf(
            TvApp("111299001912", "YouTube"),
            TvApp("3201907018807", "Netflix"),
            TvApp("3201910019365", "Prime Video"),
            TvApp("3201901017640", "Disney+"),
            TvApp("3201606009684", "Spotify"),
        )

        /** Reads the TV's REST info (name, model, Wi-Fi MAC). Blocking; null if unreachable. */
        fun fetchInfo(ip: String, timeoutMs: Int = 2500): Info? = runCatching {
            val json = JsonX.parseObject(Http.get("http://$ip:8001/api/v2/", timeoutMs)) ?: return null
            val dev = json.obj("device")
            val rawName = dev?.str("name") ?: json.str("name")
            Info(
                name = rawName?.removePrefix("[TV]")?.trim()?.takeIf { it.isNotEmpty() },
                model = dev?.str("modelName"),
                mac = dev?.str("wifiMac")?.takeIf { it.isNotBlank() && it != "00:00:00:00:00:00" },
                tokenAuth = dev?.str("TokenAuthSupport")?.equals("true", ignoreCase = true) ?: true,
            )
        }.getOrNull()
    }
}
