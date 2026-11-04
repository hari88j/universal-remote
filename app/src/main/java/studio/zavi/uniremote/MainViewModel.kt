package studio.zavi.uniremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.zavi.uniremote.core.Brand
import studio.zavi.uniremote.core.NeedsPairingException
import studio.zavi.uniremote.core.PairingCancelledException
import studio.zavi.uniremote.core.PairingHandler
import studio.zavi.uniremote.core.RemoteKey
import studio.zavi.uniremote.core.TvApp
import studio.zavi.uniremote.core.TvController
import studio.zavi.uniremote.core.TvDevice
import studio.zavi.uniremote.core.TvException
import studio.zavi.uniremote.core.androidtv.AndroidTvController
import studio.zavi.uniremote.core.androidtv.ClientIdentity
import studio.zavi.uniremote.core.firetv.FireTvController
import studio.zavi.uniremote.core.lg.LgController
import studio.zavi.uniremote.core.net.WakeOnLan
import studio.zavi.uniremote.core.roku.RokuController
import studio.zavi.uniremote.core.samsung.SamsungController
import studio.zavi.uniremote.data.DeviceRepository
import studio.zavi.uniremote.data.DiscoveryManager
import studio.zavi.uniremote.data.PrefsStore
import studio.zavi.uniremote.data.AppSettings
import studio.zavi.uniremote.data.RecentApps
import studio.zavi.uniremote.data.SettingsStore
import java.io.File
import java.net.SocketTimeoutException

sealed interface Session {
    data object None : Session
    data class Connecting(val device: TvDevice, val controller: TvController) : Session
    data class Active(val device: TvDevice, val controller: TvController) : Session
}

sealed interface PairingUi {
    data class Code(val error: String?) : PairingUi
    data class Waiting(val message: String) : PairingUi
}

sealed interface AppsState {
    data object Idle : AppsState
    data object Loading : AppsState
    data class Loaded(val apps: List<TvApp>) : AppsState
    data class Failed(val message: String) : AppsState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DeviceRepository(app)
    private val identity = ClientIdentity(PrefsStore(app))
    private val discovery = DiscoveryManager(app)
    private val adbKeyDir = File(app.filesDir, "adb")
    private val settingsStore = SettingsStore(app)
    private val recentStore = RecentApps(app)

    val settings: StateFlow<AppSettings> = settingsStore.settings

    private val _recentApps = MutableStateFlow<List<TvApp>>(emptyList())
    val recentApps: StateFlow<List<TvApp>> = _recentApps.asStateFlow()

    val saved: StateFlow<List<TvDevice>> = repo.devices

    private val _discovered = MutableStateFlow<List<TvDevice>>(emptyList())
    val discovered: StateFlow<List<TvDevice>> = _discovered.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _session = MutableStateFlow<Session>(Session.None)
    val session: StateFlow<Session> = _session.asStateFlow()

    private val _pairing = MutableStateFlow<PairingUi?>(null)
    val pairing: StateFlow<PairingUi?> = _pairing.asStateFlow()

    private val _apps = MutableStateFlow<AppsState>(AppsState.Idle)
    val apps: StateFlow<AppsState> = _apps.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var hasScanned = false

    @Volatile
    private var codeReply: CompletableDeferred<String?>? = null
    private var lastMessage = ""
    private var lastMessageAt = 0L

    private val active: Session.Active? get() = _session.value as? Session.Active

    // ------------------------------------------------------------------ discovery

    fun scan() {
        if (scanJob?.isActive == true) return
        hasScanned = true
        scanJob = viewModelScope.launch {
            _scanning.value = true
            try {
                _discovered.value = discovery.scan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Scan failed: ${e.message ?: "unknown error"}")
            } finally {
                _scanning.value = false
            }
        }
    }

    fun scanIfNeeded() {
        if (!hasScanned) scan()
    }

    // ------------------------------------------------------------------ connection

    fun connect(device: TvDevice) {
        connectJob?.cancel()
        currentController()?.close()
        val controller = controllerFor(device)
        _session.value = Session.Connecting(device, controller)
        connectJob = viewModelScope.launch {
            try {
                val connected = controller.connect(pairingHandler).copy(lastUsed = System.currentTimeMillis())
                repo.upsert(connected)
                val stored = repo.devices.value.firstOrNull { it.id == connected.id } ?: connected
                _apps.value = AppsState.Idle
                _recentApps.value = recentStore.get(stored.id)
                _session.value = Session.Active(stored, controller)
            } catch (e: CancellationException) {
                controller.close()
                throw e
            } catch (e: Exception) {
                controller.close()
                if ((_session.value as? Session.Connecting)?.controller === controller) _session.value = Session.None
                if (e !is PairingCancelledException) say(friendly(e))
            } finally {
                _pairing.value = null
            }
        }
    }

    fun addManual(name: String, ip: String, brand: Brand) {
        val clean = ip.trim()
        connect(TvDevice(name = name.trim().ifEmpty { "${brand.short} TV" }, ip = clean, brand = brand))
    }

    fun cancelConnect() {
        codeReply?.complete(null)
        codeReply = null
        connectJob?.cancel()
        (_session.value as? Session.Connecting)?.controller?.close()
        if (_session.value is Session.Connecting) _session.value = Session.None
        _pairing.value = null
    }

    fun disconnect() {
        currentController()?.close()
        _session.value = Session.None
        _apps.value = AppsState.Idle
    }

    fun submitCode(code: String) {
        codeReply?.complete(code)
    }

    fun rename(device: TvDevice, name: String) {
        if (name.isNotBlank()) repo.rename(device.id, name.trim())
    }

    fun forget(device: TvDevice) {
        if (active?.device?.id == device.id) disconnect()
        repo.remove(device.id)
    }

    // ------------------------------------------------------------------ remote

    fun send(key: RemoteKey) {
        val s = active ?: return
        viewModelScope.launch {
            try {
                s.controller.send(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsPairingException) {
                say("${s.device.name} needs to be paired again.")
                connect(s.device)
            } catch (e: Exception) {
                say(friendly(e))
            }
        }
    }

    fun power() {
        val s = active ?: return
        viewModelScope.launch {
            val macs = s.device.mac?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            if (!s.controller.connected.value && macs.isNotEmpty()) {
                wake(s.device, macs)
                return@launch
            }
            try {
                s.controller.send(RemoteKey.POWER)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (macs.isNotEmpty()) wake(s.device, macs) else say(friendly(e))
            }
        }
    }

    private suspend fun wake(device: TvDevice, macs: List<String>) {
        withContext(Dispatchers.IO) { WakeOnLan.wake(macs, device.ip) }
        say("Waking ${device.name}… give it a few seconds.")
    }

    fun sendText(text: String) {
        val s = active ?: return
        viewModelScope.launch {
            try {
                s.controller.sendText(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(friendly(e))
            }
        }
    }

    // ------------------------------------------------------------------ on-screen messages

    /**
     * Shows [text] as a pop-up on the TV. Needs the "Universal Remote TV" helper app
     * running on the TV (Android TV / Google TV / Fire TV), which listens on port 7890.
     */
    fun sendMessage(text: String, fullScreen: Boolean, seconds: Int) {
        val s = active ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val line = kotlinx.serialization.json.buildJsonObject {
                put("text", kotlinx.serialization.json.JsonPrimitive(clean))
                put("seconds", kotlinx.serialization.json.JsonPrimitive(seconds))
                put("style", kotlinx.serialization.json.JsonPrimitive(if (fullScreen) "full" else "banner"))
            }.toString() + "\n"
            try {
                withContext(Dispatchers.IO) {
                    java.net.Socket().use { sock ->
                        sock.connect(java.net.InetSocketAddress(s.device.ip, TV_HELPER_PORT), 3000)
                        sock.soTimeout = 4000
                        sock.getOutputStream().write(line.toByteArray(Charsets.UTF_8))
                        sock.getOutputStream().flush()
                        sock.getInputStream().bufferedReader().readLine()
                    }
                }
                say("Shown on ${s.device.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.net.ConnectException) {
                say("Install and open the Universal Remote TV app on ${s.device.name} first.")
            } catch (e: Exception) {
                say("Couldn't reach the TV app on ${s.device.name}. Is it installed and open once?")
            }
        }
    }

    // ------------------------------------------------------------------ live typing

    private var liveSent = ""
    private var liveTarget = ""
    private var liveJob: Job? = null
    private val liveLock = kotlinx.coroutines.sync.Mutex()

    /** Call when the keyboard opens: the TV field is assumed empty. */
    fun startLiveTyping() {
        liveJob?.cancel()
        liveSent = ""
        liveTarget = ""
    }

    /**
     * Mirrors the phone's text field onto the TV as you type. Changes are batched briefly,
     * then sent either as the whole text (Android TV) or as backspaces + new characters.
     */
    fun liveType(text: String) {
        val s = active ?: return
        liveTarget = text
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(120)
            liveLock.withLock {
                val target = liveTarget
                val before = liveSent
                if (target == before) return@withLock
                try {
                    val c = s.controller
                    if (c.textReplacesField) {
                        if (target.isEmpty()) {
                            repeat(before.length.coerceAtMost(40)) { c.send(RemoteKey.BACKSPACE) }
                        } else {
                            c.sendText(target)
                        }
                    } else {
                        val common = before.commonPrefixWith(target).length
                        repeat(before.length - common) { c.send(RemoteKey.BACKSPACE) }
                        val added = target.substring(common)
                        if (added.isNotEmpty()) c.sendText(added)
                    }
                    liveSent = target
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    say(friendly(e))
                }
            }
        }
    }

    fun loadApps() {
        val s = active ?: return
        _apps.value = AppsState.Loading
        viewModelScope.launch {
            _apps.value = try {
                AppsState.Loaded(s.controller.apps())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppsState.Failed(friendly(e))
            }
        }
    }

    fun launch(app: TvApp) {
        val s = active ?: return
        viewModelScope.launch {
            try {
                s.controller.launch(app)
                if (active?.device?.id == s.device.id) _recentApps.value = recentStore.add(s.device.id, app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(friendly(e))
            }
        }
    }

    fun openLink(url: String) {
        val s = active ?: return
        val clean = url.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            try {
                s.controller.openLink(if (clean.contains("://")) clean else "https://$clean")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(friendly(e))
            }
        }
    }

    // ------------------------------------------------------------------ settings

    fun notify(message: String) = say(message)

    fun updateSettings(change: (AppSettings) -> AppSettings) = settingsStore.update(change)

    /** True when the phone's volume buttons should control the TV instead of the phone. */
    fun volumeKeysActive(): Boolean = settings.value.volumeKeys && active != null

    // ------------------------------------------------------------------ helpers

    private val pairingHandler = object : PairingHandler {
        override suspend fun requestCode(error: String?): String? {
            val reply = CompletableDeferred<String?>()
            codeReply = reply
            _pairing.value = PairingUi.Code(error)
            return try {
                reply.await()
            } finally {
                if (codeReply === reply) codeReply = null
                if (_pairing.value is PairingUi.Code) _pairing.value = null
            }
        }

        override fun showWaiting(message: String) {
            _pairing.value = PairingUi.Waiting(message)
        }

        override fun hideWaiting() {
            if (_pairing.value is PairingUi.Waiting) _pairing.value = null
        }
    }

    private fun controllerFor(device: TvDevice): TvController = when (device.brand) {
        Brand.ANDROID_TV -> AndroidTvController(device, identity, CLIENT_NAME)
        Brand.SAMSUNG -> SamsungController(device, CLIENT_NAME)
        Brand.LG -> LgController(device)
        Brand.ROKU -> RokuController(device)
        Brand.FIRE_TV -> FireTvController(device, adbKeyDir)
    }

    private fun currentController(): TvController? = when (val s = _session.value) {
        is Session.Active -> s.controller
        is Session.Connecting -> s.controller
        Session.None -> null
    }

    private fun friendly(e: Throwable): String = when (e) {
        is TvException -> e.message ?: "Something went wrong."
        is SocketTimeoutException -> "The TV didn't respond in time."
        else -> e.message ?: "Something went wrong."
    }

    /** Shows a message, skipping repeats so a dead TV doesn't spam one per button press. */
    private fun say(message: String) {
        val now = System.currentTimeMillis()
        if (message == lastMessage && now - lastMessageAt < 4000) return
        lastMessage = message
        lastMessageAt = now
        _messages.tryEmit(message)
    }

    override fun onCleared() {
        currentController()?.close()
        super.onCleared()
    }

    companion object {
        /** The name the TV shows when it asks whether to allow this phone. */
        const val CLIENT_NAME = "Universal Remote"

        /** Port the TV helper app listens on. */
        const val TV_HELPER_PORT = 7890
    }
}
