package studio.zavi.uniremote.core.lg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import studio.zavi.uniremote.core.JsonX
import studio.zavi.uniremote.core.NotReachableException
import studio.zavi.uniremote.core.PairingHandler
import studio.zavi.uniremote.core.PairingRejectedException
import studio.zavi.uniremote.core.RemoteKey
import studio.zavi.uniremote.core.TvApp
import studio.zavi.uniremote.core.TvController
import studio.zavi.uniremote.core.TvDevice
import studio.zavi.uniremote.core.TvException
import studio.zavi.uniremote.core.arr
import studio.zavi.uniremote.core.asObject
import studio.zavi.uniremote.core.bool
import studio.zavi.uniremote.core.net.MiniWebSocket
import studio.zavi.uniremote.core.obj
import studio.zavi.uniremote.core.str
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * LG webOS TVs: JSON "SSAP" requests over WebSocket (ws:3000, or wss:3001 on newer
 * firmware). Arrow/OK/Back go through a second "pointer input" socket. The first
 * connection makes the TV ask the person to accept; the TV then hands us a client-key.
 */
class LgController(initial: TvDevice) : TvController {

    @Volatile
    override var device: TvDevice = initial
        private set

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    override val supportsText = true
    override val supportsApps = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val counter = AtomicInteger(0)

    @Volatile private var main: MiniWebSocket? = null
    @Volatile private var pointer: MiniWebSocket? = null
    private var playing = false

    override fun supports(key: RemoteKey): Boolean = true

    override suspend fun connect(pairing: PairingHandler): TvDevice = withContext(Dispatchers.IO) {
        lock.withLock { open(pairing) }
        device
    }

    override suspend fun send(key: RemoteKey) = withContext(Dispatchers.IO) {
        when (key) {
            RemoteKey.POWER -> request("system/turnOff").let { }
            RemoteKey.BACKSPACE -> request("com.webos.service.ime/deleteCharacters", buildJsonObject { put("count", 1) }).let { }
            RemoteKey.ENTER -> request("com.webos.service.ime/sendEnterKey").let { }
            RemoteKey.VOLUME_UP -> buttonOr("VOLUMEUP", "audio/volumeUp")
            RemoteKey.VOLUME_DOWN -> buttonOr("VOLUMEDOWN", "audio/volumeDown")
            RemoteKey.CHANNEL_UP -> buttonOr("CHANNELUP", "tv/channelUp")
            RemoteKey.CHANNEL_DOWN -> buttonOr("CHANNELDOWN", "tv/channelDown")
            RemoteKey.PLAY_PAUSE -> {
                playing = !playing
                if (playing) buttonOr("PLAY", "media.controls/play") else buttonOr("PAUSE", "media.controls/pause")
            }
            else -> button(buttonName(key))
        }
    }

    override suspend fun sendText(text: String) = withContext(Dispatchers.IO) {
        if (text.isEmpty()) return@withContext
        request("com.webos.service.ime/insertText", buildJsonObject {
            put("text", text)
            put("replace", 0)
        })
        Unit
    }

    override suspend fun apps(): List<TvApp> = withContext(Dispatchers.IO) {
        val points = request("com.webos.applicationManager/listLaunchPoints").arr("launchPoints") ?: return@withContext emptyList()
        points.mapNotNull { el ->
            val o = el.asObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            TvApp(id, o.str("title") ?: id)
        }
    }

    override suspend fun launch(app: TvApp) = withContext(Dispatchers.IO) {
        request("system.launcher/launch", buildJsonObject { put("id", app.id) })
        Unit
    }

    override fun close() {
        scope.cancel()
        pointer?.close()
        main?.close()
        pointer = null
        main = null
        _connected.value = false
        failPending()
    }

    // ---------------------------------------------------------------- connection

    private suspend fun open(pairing: PairingHandler?) {
        pointer?.close()
        main?.close()
        pointer = null
        main = null

        val ws = try {
            MiniWebSocket.connect("ws://${device.ip}:3000", 4000)
        } catch (e: IOException) {
            try {
                MiniWebSocket.connect("wss://${device.ip}:3001", 4000)
            } catch (e2: IOException) {
                throw NotReachableException("Can't reach ${device.name}. Check it's on and on the same Wi-Fi.", e2)
            }
        }

        try {
            // Same opening as aiowebostv: hello, system info (newer webOS wants it), then register.
            ws.send("""{"id":"hello","type":"hello","payload":{}}""")
            receiveMatching(ws, 4000) { it.str("id") == "hello" || it.str("type") == "hello" }
            ws.send(requestJson("get_sys_info", "system/getSystemInfo", JsonObject(emptyMap())))
            receiveMatching(ws, 4000) { it.str("id") == "get_sys_info" }

            ws.send(registerJson(device.token))
            var clientKey: String? = null
            val finished = withTimeoutOrNull(if (pairing != null) 60_000L else 8_000L) {
                while (clientKey == null) {
                    val msg = JsonX.parseObject(ws.incoming.receive()) ?: continue
                    if (msg.str("id") != "register_0") continue
                    when (msg.str("type")) {
                        "response" -> if (msg.obj("payload")?.str("pairingType") == "PROMPT") {
                            pairing?.showWaiting("Look at your TV and accept the connection request.")
                        }
                        "registered" -> clientKey = msg.obj("payload")?.str("client-key") ?: ""
                        "error" -> throw PairingRejectedException(
                            "The TV refused the connection" + (msg.str("error")?.let { " ($it)" } ?: "") + "."
                        )
                    }
                }
                true
            }
            if (finished == null) {
                throw PairingRejectedException("No answer from ${device.name}. Accept the request on the TV and try again.")
            }
            if (!clientKey.isNullOrEmpty()) device = device.copy(token = clientKey)

            main = ws
            scope.launch {
                runCatching { for (text in ws.incoming) dispatch(text) }
                if (main === ws) _connected.value = false
                failPending()
            }
            _connected.value = true

            pointer = openPointer(ws)
            if (device.mac == null) fetchMac(ws)
        } catch (e: TimeoutCancellationException) {
            ws.close()
            throw NotReachableException("${device.name} stopped responding.")
        } catch (e: CancellationException) {
            ws.close()
            throw e
        } catch (e: TvException) {
            ws.close()
            throw e
        } catch (e: Exception) {
            ws.close()
            throw NotReachableException("${device.name} closed the connection.", e)
        } finally {
            pairing?.hideWaiting()
        }
    }

    private suspend fun receiveMatching(ws: MiniWebSocket, timeoutMs: Long, match: (JsonObject) -> Boolean): JsonObject? =
        withTimeoutOrNull(timeoutMs) {
            var found: JsonObject? = null
            while (found == null) {
                val msg = JsonX.parseObject(ws.incoming.receive()) ?: continue
                if (match(msg)) found = msg
            }
            found
        }

    private fun dispatch(text: String) {
        val msg = JsonX.parseObject(text) ?: return
        val id = msg.str("id") ?: return
        pending.remove(id)?.complete(msg)
    }

    private fun failPending() {
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(IOException("Connection closed")) }
    }

    private suspend fun ensureMain(): MiniWebSocket {
        main?.takeIf { it.isOpen }?.let { return it }
        return lock.withLock {
            main?.takeIf { it.isOpen } ?: run {
                open(null)
                main ?: throw TvException("Not connected")
            }
        }
    }

    /** Sends an SSAP request. [on] is used during [open], where [ensureMain] would deadlock on the lock. */
    private suspend fun request(
        uri: String,
        payload: JsonObject = JsonObject(emptyMap()),
        on: MiniWebSocket? = null,
    ): JsonObject {
        val ws = on ?: ensureMain()
        val id = "req_" + counter.incrementAndGet()
        val reply = CompletableDeferred<JsonObject>()
        pending[id] = reply
        try {
            ws.send(requestJson(id, uri, payload))
            val msg = withTimeout(8000) { reply.await() }
            val body = msg.obj("payload") ?: JsonObject(emptyMap())
            if (msg.str("type") == "error" || body.bool("returnValue") == false) {
                throw TvException(msg.str("error") ?: body.str("errorText") ?: "The TV couldn't do that.")
            }
            return body
        } catch (e: TimeoutCancellationException) {
            throw TvException("${device.name} didn't answer.")
        } catch (e: IOException) {
            throw NotReachableException("Lost connection to ${device.name}.", e)
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun openPointer(on: MiniWebSocket): MiniWebSocket? {
        val path = runCatching {
            request("com.webos.service.networkinput/getPointerInputSocket", on = on).str("socketPath")
        }.getOrNull() ?: return null
        return runCatching { MiniWebSocket.connect(path, 4000) }.getOrElse {
            // Some firmware hands out a ws://…:3000 path but only accepts wss://…:3001.
            if (path.startsWith("ws://")) {
                val alt = path.replaceFirst("ws://", "wss://").replace(":3000/", ":3001/")
                runCatching { MiniWebSocket.connect(alt, 4000) }.getOrNull()
            } else {
                null
            }
        }
    }

    private suspend fun ensurePointer(): MiniWebSocket {
        pointer?.takeIf { it.isOpen }?.let { return it }
        val p = openPointer(ensureMain()) ?: throw TvException("${device.name} didn't accept button input.")
        pointer = p
        return p
    }

    private suspend fun button(name: String) {
        val message = "type:button\nname:$name\n\n"
        try {
            ensurePointer().send(message)
        } catch (e: IOException) {
            pointer = null
            ensurePointer().send(message)
        }
    }

    /** Tries the pointer-socket button first and falls back to the SSAP request. */
    private suspend fun buttonOr(name: String, uri: String) {
        try {
            button(name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            request(uri)
        }
    }

    private suspend fun fetchMac(on: MiniWebSocket) {
        val info = runCatching { request("com.webos.service.connectionmanager/getinfo", on = on) }.getOrNull() ?: return
        val macs = listOfNotNull(
            info.obj("wiredInfo")?.str("macAddress"),
            info.obj("wifiInfo")?.str("macAddress"),
        ).filter { it.isNotBlank() }
        if (macs.isNotEmpty()) device = device.copy(mac = macs.joinToString(","))
    }

    private fun buttonName(key: RemoteKey): String = when (key) {
        RemoteKey.HOME -> "HOME"
        RemoteKey.BACK -> "BACK"
        RemoteKey.MENU -> "MENU"
        RemoteKey.INPUT -> "INPUT_HUB"
        RemoteKey.INFO -> "INFO"
        RemoteKey.UP -> "UP"
        RemoteKey.DOWN -> "DOWN"
        RemoteKey.LEFT -> "LEFT"
        RemoteKey.RIGHT -> "RIGHT"
        RemoteKey.OK -> "ENTER"
        RemoteKey.MUTE -> "MUTE"
        RemoteKey.REWIND -> "REWIND"
        RemoteKey.FAST_FORWARD -> "FASTFORWARD"
        RemoteKey.VOLUME_UP -> "VOLUMEUP"
        RemoteKey.VOLUME_DOWN -> "VOLUMEDOWN"
        RemoteKey.CHANNEL_UP -> "CHANNELUP"
        RemoteKey.CHANNEL_DOWN -> "CHANNELDOWN"
        RemoteKey.PLAY_PAUSE -> "PLAY"
        RemoteKey.POWER -> "POWER"
        RemoteKey.BACKSPACE, RemoteKey.ENTER -> "ENTER"
        else -> RemoteKey.digits.indexOf(key).toString()
    }

    private fun requestJson(id: String, uri: String, payload: JsonObject): String = buildJsonObject {
        put("id", id)
        put("type", "request")
        put("uri", "ssap://$uri")
        put("payload", payload)
    }.toString()

    private fun registerJson(clientKey: String?): String = buildJsonObject {
        put("type", "register")
        put("id", "register_0")
        putJsonObject("payload") {
            put("forcePairing", false)
            put("pairingType", "PROMPT")
            if (!clientKey.isNullOrEmpty()) put("client-key", clientKey)
            putJsonObject("manifest") {
                put("appVersion", "1.1")
                put("manifestVersion", 1)
                putJsonArray("permissions") { PERMISSIONS.forEach { add(it) } }
            }
        }
    }.toString()

    companion object {
        /** Same permission set aiowebostv (Home Assistant) requests. */
        private val PERMISSIONS = listOf(
            "APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
            "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_TEXT",
            "CONTROL_INPUT_TV", "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER", "CONTROL_TV_SCREEN",
            "LAUNCH", "LAUNCH_WEBAPP", "READ_APP_STATUS", "READ_COUNTRY_INFO", "READ_CURRENT_CHANNEL",
            "READ_INPUT_DEVICE_LIST", "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_LGE_TV_INPUT_EVENTS",
            "READ_NETWORK_STATE", "READ_NOTIFICATIONS", "READ_POWER_STATE", "READ_RUNNING_APPS",
            "READ_SETTINGS", "READ_TV_CHANNEL_LIST", "READ_TV_CURRENT_TIME", "READ_UPDATE_INFO",
            "SEARCH", "TEST_OPEN", "TEST_PROTECTED", "TEST_SECURE", "UPDATE_FROM_REMOTE_APP",
            "WRITE_NOTIFICATION_ALERT", "WRITE_NOTIFICATION_TOAST", "WRITE_SETTINGS",
        )
    }
}
