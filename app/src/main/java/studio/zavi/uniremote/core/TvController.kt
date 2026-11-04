package studio.zavi.uniremote.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One live connection to one TV. Every brand implements this the same way,
 * so the UI never needs to know which protocol is underneath.
 */
interface TvController {
    /** Latest known device info (a successful connect can fill in token, MAC or model). */
    val device: TvDevice

    /** True while the connection is open. A send on a closed connection reconnects first. */
    val connected: StateFlow<Boolean>

    /** TV volume, for platforms that report it (only Android TV today). */
    val volume: StateFlow<VolumeInfo?> get() = NO_VOLUME

    val supportsText: Boolean
    val supportsApps: Boolean
    val supportsLinks: Boolean get() = false

    fun supports(key: RemoteKey): Boolean

    /** Connects, pairing first if needed. Returns the device with any new credentials filled in. */
    suspend fun connect(pairing: PairingHandler): TvDevice

    suspend fun send(key: RemoteKey)

    /** Types [text] into whatever text field is focused on the TV. */
    suspend fun sendText(text: String)

    suspend fun apps(): List<TvApp>

    suspend fun launch(app: TvApp)

    /** Opens a URL on the TV (for example a YouTube link). Android TV only. */
    suspend fun openLink(url: String) {
        throw TvException("This TV can't open links")
    }

    fun close()

    companion object {
        private val NO_VOLUME: StateFlow<VolumeInfo?> = MutableStateFlow(null)
    }
}

/** How a controller asks the person to do something during pairing. */
interface PairingHandler {
    /** Ask for the code shown on the TV. Returns null if the person cancels. */
    suspend fun requestCode(error: String?): String?

    /** Show a "look at your TV and accept" message until [hideWaiting]. */
    fun showWaiting(message: String)

    fun hideWaiting()
}

/** Tiny key-value store so core code doesn't depend on Android. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}
