package studio.zavi.uniremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SettingsInputComponent
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.zavi.uniremote.MainViewModel
import studio.zavi.uniremote.core.RemoteKey
import studio.zavi.uniremote.core.TvController
import studio.zavi.uniremote.core.TvDevice

@Composable
fun RemoteScreen(vm: MainViewModel, device: TvDevice, controller: TvController) {
    val connected by controller.connected.collectAsState()
    val volume by controller.volume.collectAsState()
    val apps by vm.apps.collectAsState()
    var touchMode by rememberSaveable { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }
    var showNumpad by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }

    BackHandler { vm.disconnect() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Header: back to TV list, name + status, power.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.disconnect() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "All TVs", tint = Palette.Text)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(connected)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (connected) "Connected · ${device.brand.short}" else "Reconnects when you press a button",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                    )
                }
            }
            IconKey(
                icon = Icons.Rounded.PowerSettingsNew,
                label = "Power",
                onPress = { vm.power() },
                modifier = Modifier.size(52.dp),
                tint = Color.White,
                color = Palette.Power,
                pressedColor = Palette.Power.copy(alpha = 0.7f),
            )
        }

        Spacer(Modifier.height(16.dp))

        KeyRow {
            IconKey(Icons.Rounded.Undo, "Back", { vm.send(RemoteKey.BACK) })
            IconKey(Icons.Rounded.Home, "Home", { vm.send(RemoteKey.HOME) })
            IconKey(Icons.Rounded.Menu, "Menu", { vm.send(RemoteKey.MENU) }, enabled = controller.supports(RemoteKey.MENU))
            IconKey(
                Icons.Rounded.SettingsInputComponent,
                "Input source",
                { vm.send(RemoteKey.INPUT) },
                enabled = controller.supports(RemoteKey.INPUT),
            )
        }

        Spacer(Modifier.height(12.dp))

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val pad = Modifier
                .sizeIn(maxWidth = 300.dp, maxHeight = 300.dp)
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
            if (touchMode) {
                Touchpad(onKey = { vm.send(it) }, modifier = pad)
            } else {
                DPad(onKey = { vm.send(it) }, modifier = pad)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val v = volume
            Rocker(
                label = if (v != null && v.max > 0) "${v.level}" else "VOL",
                onUp = { vm.send(RemoteKey.VOLUME_UP) },
                onDown = { vm.send(RemoteKey.VOLUME_DOWN) },
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconKey(Icons.Rounded.VolumeOff, "Mute", { vm.send(RemoteKey.MUTE) })
                IconKey(Icons.Rounded.Info, "Info", { vm.send(RemoteKey.INFO) }, enabled = controller.supports(RemoteKey.INFO))
            }
            Rocker(
                label = "CH",
                onUp = { vm.send(RemoteKey.CHANNEL_UP) },
                onDown = { vm.send(RemoteKey.CHANNEL_DOWN) },
                enabled = controller.supports(RemoteKey.CHANNEL_UP),
            )
        }

        Spacer(Modifier.height(14.dp))

        KeyRow {
            IconKey(Icons.Rounded.FastRewind, "Rewind", { vm.send(RemoteKey.REWIND) }, Modifier.size(52.dp))
            PressableKey(
                onPress = { vm.send(RemoteKey.PLAY_PAUSE) },
                label = "Play or pause",
                modifier = Modifier.size(width = 96.dp, height = 52.dp),
                shape = RoundedCornerShape(26.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(24.dp))
                    Icon(Icons.Rounded.Pause, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(20.dp))
                }
            }
            IconKey(Icons.Rounded.FastForward, "Fast forward", { vm.send(RemoteKey.FAST_FORWARD) }, Modifier.size(52.dp))
        }

        Spacer(Modifier.height(14.dp))

        KeyRow {
            ActionButton(Icons.Rounded.Keyboard, "Keyboard", { showKeyboard = true }, enabled = controller.supportsText)
            ActionButton(Icons.Rounded.Dialpad, "Numbers", { showNumpad = true })
            ActionButton(Icons.Rounded.Apps, "Apps", {
                showApps = true
                vm.loadApps()
            }, enabled = controller.supportsApps)
            ActionButton(
                if (touchMode) Icons.Rounded.Gamepad else Icons.Rounded.TouchApp,
                if (touchMode) "D-pad" else "Touchpad",
                { touchMode = !touchMode },
            )
        }
    }

    if (showKeyboard) {
        KeyboardDialog(
            canBackspace = controller.supports(RemoteKey.BACKSPACE),
            onSend = { vm.sendText(it) },
            onKey = { vm.send(it) },
            onDismiss = { showKeyboard = false },
        )
    }
    if (showNumpad) {
        NumpadSheet(
            canBackspace = controller.supports(RemoteKey.BACKSPACE),
            onKey = { vm.send(it) },
            onDismiss = { showNumpad = false },
        )
    }
    if (showApps) {
        AppsSheet(
            state = apps,
            supportsLinks = controller.supportsLinks,
            onLaunch = {
                vm.launch(it)
                showApps = false
            },
            onOpenLink = {
                vm.openLink(it)
                showApps = false
            },
            onRetry = { vm.loadApps() },
            onDismiss = { showApps = false },
        )
    }
}
