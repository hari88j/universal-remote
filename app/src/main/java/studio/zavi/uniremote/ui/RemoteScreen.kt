package studio.zavi.uniremote.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsInputComponent
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.zavi.uniremote.MainViewModel
import studio.zavi.uniremote.core.RemoteKey
import studio.zavi.uniremote.core.TvController
import studio.zavi.uniremote.core.TvDevice

@Composable
fun RemoteScreen(vm: MainViewModel, device: TvDevice, controller: TvController, onOpenSettings: () -> Unit) {
    val c = C
    val connected by controller.connected.collectAsState()
    val volume by controller.volume.collectAsState()
    val apps by vm.apps.collectAsState()
    val recent by vm.recentApps.collectAsState()
    val saved by vm.saved.collectAsState()
    val settings by vm.settings.collectAsState()
    var mode by rememberSaveable { mutableStateOf(0) } // 0 remote, 1 touch, 2 numbers
    var showKeyboard by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var switcher by remember { mutableStateOf(false) }

    BackHandler { vm.disconnect() }

    // Keep the screen awake while the remote is open (a setting).
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Voice search: Android's speech recogniser, then the words are typed into the TV.
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) vm.sendText(text)
    }
    val startVoice = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what to search on ${device.name}")
        try {
            voice.launch(intent)
        } catch (e: ActivityNotFoundException) {
            vm.notify("Voice input isn't available on this phone.")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ---------------------------------------------------------- header
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { switcher = true }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                device.name,
                                style = MaterialTheme.typography.titleLarge,
                                color = c.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Switch TV", tint = c.secondaryLabel)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(connected)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (connected) "Connected · ${device.brand.short}" else "Reconnects on next press",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.secondaryLabel,
                            )
                        }
                    }
                }
                DropdownMenu(expanded = switcher, onDismissRequest = { switcher = false }) {
                    saved.forEach { tv ->
                        DropdownMenuItem(
                            text = { Text(tv.name) },
                            leadingIcon = { Icon(Icons.Rounded.Tv, contentDescription = null, tint = brandColor(tv.brand)) },
                            trailingIcon = if (tv.id == device.id) {
                                { Icon(Icons.Rounded.Check, contentDescription = null, tint = c.accent) }
                            } else {
                                null
                            },
                            onClick = {
                                switcher = false
                                if (tv.id != device.id) vm.connect(tv)
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("All TVs…") }, onClick = {
                        switcher = false
                        vm.disconnect()
                    })
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = c.secondaryLabel)
            }
            IconKey3D(
                Icons.Rounded.PowerSettingsNew,
                "Power",
                { vm.power() },
                modifier = Modifier.size(48.dp),
                tint = c.red,
                iconSize = 22.dp,
            )
        }

        Spacer(Modifier.height(14.dp))
        Segmented(listOf("Remote", "Touch", "Numbers"), mode, { mode = it }, Modifier.fillMaxWidth())

        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(recent, key = { it.id }) { app ->
                    Box(
                        Modifier
                            .glass(CircleShape, c)
                            .clickable { vm.launch(app) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(app.name, style = MaterialTheme.typography.labelLarge, color = c.label, maxLines = 1)
                    }
                }
            }
        }

        // ---------------------------------------------------------- main area
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            val square = Modifier
                .sizeIn(maxWidth = 310.dp, maxHeight = 310.dp)
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
            when (mode) {
                1 -> Trackpad(onKey = { vm.send(it) }, modifier = square)
                2 -> InlineNumpad(
                    canBackspace = controller.supports(RemoteKey.BACKSPACE),
                    onKey = { vm.send(it) },
                    modifier = Modifier.sizeIn(maxWidth = 300.dp).fillMaxHeight(),
                )
                else -> DPad3D(onKey = { vm.send(it) }, modifier = square)
            }
        }

        // ---------------------------------------------------------- navigation keys
        KeyRow {
            IconKey3D(Icons.Rounded.Undo, "Back", { vm.send(RemoteKey.BACK) })
            IconKey3D(Icons.Rounded.Home, "Home", { vm.send(RemoteKey.HOME) })
            IconKey3D(Icons.Rounded.Menu, "Menu", { vm.send(RemoteKey.MENU) }, enabled = controller.supports(RemoteKey.MENU))
        }

        Spacer(Modifier.height(18.dp))

        // ---------------------------------------------------------- volume, media, channel
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val v = volume
            Rocker3D(
                label = if (v != null && v.max > 0) "${v.level}" else "VOL",
                onUp = { vm.send(RemoteKey.VOLUME_UP) },
                onDown = { vm.send(RemoteKey.VOLUME_DOWN) },
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Key3D(
                    onPress = { vm.send(RemoteKey.PLAY_PAUSE) },
                    label = "Play or pause",
                    modifier = Modifier.size(72.dp),
                    tint = c.accent,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                        Icon(Icons.Rounded.Pause, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconKey3D(Icons.Rounded.FastRewind, "Rewind", { vm.send(RemoteKey.REWIND) }, Modifier.size(44.dp), iconSize = 20.dp)
                    IconKey3D(Icons.Rounded.VolumeOff, "Mute", { vm.send(RemoteKey.MUTE) }, Modifier.size(44.dp), iconSize = 20.dp)
                    IconKey3D(Icons.Rounded.FastForward, "Fast forward", { vm.send(RemoteKey.FAST_FORWARD) }, Modifier.size(44.dp), iconSize = 20.dp)
                }
            }
            Rocker3D(
                label = "CH",
                onUp = { vm.send(RemoteKey.CHANNEL_UP) },
                onDown = { vm.send(RemoteKey.CHANNEL_DOWN) },
                enabled = controller.supports(RemoteKey.CHANNEL_UP),
            )
        }

        Spacer(Modifier.height(18.dp))

        // ---------------------------------------------------------- tools
        KeyRow(Modifier.padding(bottom = 10.dp)) {
            ToolButton(Icons.Rounded.Keyboard, "Keyboard", { showKeyboard = true }, enabled = controller.supportsText)
            ToolButton(Icons.Rounded.Mic, "Voice", { startVoice() }, enabled = controller.supportsText)
            ToolButton(Icons.Rounded.Apps, "Apps", {
                showApps = true
                vm.loadApps()
            }, enabled = controller.supportsApps)
            ToolButton(
                Icons.Rounded.SettingsInputComponent,
                "Input",
                { vm.send(RemoteKey.INPUT) },
                enabled = controller.supports(RemoteKey.INPUT),
            )
            ToolButton(Icons.Rounded.Info, "Info", { vm.send(RemoteKey.INFO) }, enabled = controller.supports(RemoteKey.INFO))
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

/** Number keys laid out like a phone keypad, filling the main area. */
@Composable
private fun InlineNumpad(canBackspace: Boolean, onKey: (RemoteKey) -> Unit, modifier: Modifier = Modifier) {
    val c = C
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("del", "0", "ok"))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in rows) {
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                for (k in row) {
                    val keyMod = Modifier.weight(1f).fillMaxHeight()
                    when (k) {
                        "del" -> Key3D(
                            onPress = { onKey(RemoteKey.BACKSPACE) },
                            label = "Delete",
                            modifier = keyMod,
                            enabled = canBackspace,
                            shape = RoundedCornerShape(22.dp),
                        ) {
                            Icon(Icons.Rounded.Backspace, contentDescription = null, tint = c.label)
                        }
                        "ok" -> Key3D(
                            onPress = { onKey(RemoteKey.OK) },
                            label = "OK",
                            modifier = keyMod,
                            shape = RoundedCornerShape(22.dp),
                            tint = c.accent,
                        ) {
                            Text("OK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        else -> Key3D(
                            onPress = { onKey(RemoteKey.digit(k.toInt())) },
                            label = k,
                            modifier = keyMod,
                            shape = RoundedCornerShape(22.dp),
                        ) {
                            Text(k, color = c.label, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
