package studio.zavi.uniremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import studio.zavi.uniremote.MainViewModel
import studio.zavi.uniremote.PairingUi
import studio.zavi.uniremote.Session

private enum class Screen { Devices, Settings, Remote }

@Composable
fun App(vm: MainViewModel) {
    val session by vm.session.collectAsState()
    val pairing by vm.pairing.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    val active = session as? Session.Active
    val screen = when {
        showSettings -> Screen.Settings
        active != null -> Screen.Remote
        else -> Screen.Devices
    }

    BackHandler(enabled = showSettings) { showSettings = false }

    Box(Modifier.fillMaxSize()) {
    GlowBackground()
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally { w -> if (forward) w / 4 else -w / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { w -> if (forward) -w / 4 else w / 4 } + fadeOut())
                },
                label = "screen",
            ) { target ->
                when (target) {
                    Screen.Settings -> SettingsScreen(vm, onBack = { showSettings = false })
                    Screen.Remote -> {
                        val s = vm.session.collectAsState().value as? Session.Active
                        if (s != null) {
                            key(s.device.id) {
                                RemoteScreen(vm, s.device, s.controller, onOpenSettings = { showSettings = true })
                            }
                        }
                    }
                    Screen.Devices -> DevicesScreen(vm, onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    }

    val connecting = session as? Session.Connecting
    when (val p = pairing) {
        is PairingUi.Code -> PairingCodeDialog(
            deviceName = connecting?.device?.name ?: "TV",
            error = p.error,
            onSubmit = { vm.submitCode(it) },
            onCancel = { vm.cancelConnect() },
        )
        is PairingUi.Waiting -> WaitingDialog(message = p.message, onCancel = { vm.cancelConnect() })
        null -> if (connecting != null) ConnectingDialog(connecting.device.name, onCancel = { vm.cancelConnect() })
    }
}
