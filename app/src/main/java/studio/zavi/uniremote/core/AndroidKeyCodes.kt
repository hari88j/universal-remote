package studio.zavi.uniremote.core

/** Android KeyEvent codes, shared by Android TV (remote protocol) and Fire TV (adb input). */
object AndroidKeyCodes {
    fun of(key: RemoteKey): Int = when (key) {
        RemoteKey.POWER -> 26
        RemoteKey.HOME -> 3
        RemoteKey.BACK -> 4
        RemoteKey.MENU -> 82
        RemoteKey.INPUT -> 178
        RemoteKey.INFO -> 165
        RemoteKey.UP -> 19
        RemoteKey.DOWN -> 20
        RemoteKey.LEFT -> 21
        RemoteKey.RIGHT -> 22
        RemoteKey.OK -> 23
        RemoteKey.VOLUME_UP -> 24
        RemoteKey.VOLUME_DOWN -> 25
        RemoteKey.MUTE -> 164
        RemoteKey.CHANNEL_UP -> 166
        RemoteKey.CHANNEL_DOWN -> 167
        RemoteKey.PLAY_PAUSE -> 85
        RemoteKey.REWIND -> 89
        RemoteKey.FAST_FORWARD -> 90
        RemoteKey.BACKSPACE -> 67
        RemoteKey.ENTER -> 66
        RemoteKey.DIGIT_0 -> 7
        RemoteKey.DIGIT_1 -> 8
        RemoteKey.DIGIT_2 -> 9
        RemoteKey.DIGIT_3 -> 10
        RemoteKey.DIGIT_4 -> 11
        RemoteKey.DIGIT_5 -> 12
        RemoteKey.DIGIT_6 -> 13
        RemoteKey.DIGIT_7 -> 14
        RemoteKey.DIGIT_8 -> 15
        RemoteKey.DIGIT_9 -> 16
    }
}
