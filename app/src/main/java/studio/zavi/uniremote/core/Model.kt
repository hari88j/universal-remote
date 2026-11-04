package studio.zavi.uniremote.core

/** TV platforms the app knows how to talk to over Wi-Fi. */
enum class Brand(val label: String, val short: String) {
    ANDROID_TV("Android / Google TV", "Google TV"),
    SAMSUNG("Samsung (Tizen)", "Samsung"),
    LG("LG (webOS)", "LG"),
    ROKU("Roku", "Roku"),
    FIRE_TV("Amazon Fire TV", "Fire TV");

    companion object {
        fun fromName(name: String?): Brand? = entries.firstOrNull { it.name == name }
    }
}

/**
 * A TV the app has found or saved.
 * [token] holds the brand's saved credential (Samsung token, LG client-key).
 */
data class TvDevice(
    val name: String,
    val ip: String,
    val brand: Brand,
    val mac: String? = null,
    val token: String? = null,
    val model: String? = null,
    val lastUsed: Long = 0L,
) {
    val id: String get() = "${brand.name}@$ip"
}

/** Every button the remote screen can send. Each controller maps these to its own protocol. */
enum class RemoteKey {
    POWER, HOME, BACK, MENU, INPUT, INFO,
    UP, DOWN, LEFT, RIGHT, OK,
    VOLUME_UP, VOLUME_DOWN, MUTE,
    CHANNEL_UP, CHANNEL_DOWN,
    PLAY_PAUSE, REWIND, FAST_FORWARD,
    DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4,
    DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9,
    BACKSPACE, ENTER;

    companion object {
        val digits = listOf(DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4, DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9)
        fun digit(n: Int): RemoteKey = digits[n]
    }
}

data class TvApp(val id: String, val name: String)

data class VolumeInfo(val level: Int, val max: Int, val muted: Boolean)

open class TvException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The user backed out of a pairing prompt. */
class PairingCancelledException : TvException("Pairing cancelled")

/** The TV said no, or the code was wrong. */
class PairingRejectedException(message: String) : TvException(message)

/** The TV could not be reached at all (off, wrong IP, different Wi-Fi). */
class NotReachableException(message: String, cause: Throwable? = null) : TvException(message, cause)

/** The TV no longer trusts this phone and must be paired again. */
class NeedsPairingException(message: String = "Pairing needed", cause: Throwable? = null) : TvException(message, cause)
