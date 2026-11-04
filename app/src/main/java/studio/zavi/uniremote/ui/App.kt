package studio.zavi.uniremote.ui

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import studio.zavi.uniremote.MainViewModel
import studio.zavi.uniremote.PairingUi
import studio.zavi.uniremote.Session

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val session by vm.session.collectAsState()
    val pairing by vm.pairing.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        containerColor = Palette.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = session) {
                is Session.Active -> key(s.device.id) { RemoteScreen(vm, s.device, s.controller) }
                else -> DevicesScreen(vm)
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
