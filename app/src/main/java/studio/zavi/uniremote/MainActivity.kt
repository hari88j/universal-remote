package studio.zavi.uniremote

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import studio.zavi.uniremote.core.RemoteKey
import studio.zavi.uniremote.ui.App
import studio.zavi.uniremote.ui.RemoteTheme
import studio.zavi.uniremote.ui.ThemeMode

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings.collectAsState()
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            RemoteTheme(mode = settings.theme, haptics = settings.haptics) {
                App(vm)
            }
        }
    }

    /** On the remote screen, the phone's volume buttons change the TV's volume. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> RemoteKey.VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> RemoteKey.VOLUME_DOWN
            else -> null
        }
        if (key != null && vm.volumeKeysActive()) {
            if (event.action == KeyEvent.ACTION_DOWN) vm.send(key)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
