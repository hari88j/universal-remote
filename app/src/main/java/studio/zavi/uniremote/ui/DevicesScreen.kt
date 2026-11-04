package studio.zavi.uniremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.zavi.uniremote.MainViewModel
import studio.zavi.uniremote.core.TvDevice

@Composable
fun DevicesScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val c = C
    val saved by vm.saved.collectAsState()
    val discovered by vm.discovered.collectAsState()
    val scanning by vm.scanning.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TvDevice?>(null) }

    LaunchedEffect(Unit) { vm.scanIfNeeded() }

    val savedIds = saved.map { it.id }.toSet()
    val fresh = discovered.filter { it.id !in savedIds }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
          Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = c.accent)
                }
            }
            Text("Remote", style = MaterialTheme.typography.headlineLarge, color = c.label, modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                "Control any TV on your Wi-Fi.",
                style = MaterialTheme.typography.bodyLarge,
                color = c.secondaryLabel,
                modifier = Modifier.padding(start = 4.dp, bottom = 14.dp),
            )
          }
        }

        if (saved.isNotEmpty()) {
            item { SectionHeader("My TVs") }
            item {
                InsetGroup {
                    saved.forEachIndexed { i, device ->
                        if (i > 0) GroupDivider(startPadding = 72.dp)
                        DeviceRow(
                            device = device,
                            saved = true,
                            onClick = { vm.connect(device) },
                            onRename = { renaming = device },
                            onForget = { vm.forget(device) },
                        )
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader("On this Wi-Fi", Modifier.weight(1f))
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.secondaryLabel)
                } else {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { vm.scan() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Scan", style = MaterialTheme.typography.labelLarge, color = c.accent)
                    }
                }
            }
        }

        item {
            InsetGroup {
                if (fresh.isEmpty()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Wifi, contentDescription = null, tint = c.secondaryLabel)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            when {
                                scanning -> "Looking for TVs…"
                                saved.isEmpty() -> "No TVs found yet. Turn the TV on and keep your phone on the same Wi-Fi."
                                else -> "No other TVs found."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.secondaryLabel,
                        )
                    }
                } else {
                    fresh.forEachIndexed { i, device ->
                        if (i > 0) GroupDivider(startPadding = 72.dp)
                        DeviceRow(device = device, saved = false, onClick = { vm.connect(device) })
                    }
                }
                GroupDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showAdd = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = c.accent)
                    Spacer(Modifier.width(12.dp))
                    Text("Add TV by IP address", style = MaterialTheme.typography.bodyLarge, color = c.accent)
                }
            }
        }

        item {
            Text(
                "Android/Google TV shows a code the first time. Samsung and LG ask you to Allow. " +
                    "Fire TV needs ADB debugging on. More tips in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = c.secondaryLabel,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }

    if (showAdd) {
        AddDeviceDialog(
            onAdd = { name, ip, brand ->
                showAdd = false
                vm.addManual(name, ip, brand)
            },
            onDismiss = { showAdd = false },
        )
    }
    renaming?.let { device ->
        RenameDialog(
            current = device.name,
            onRename = {
                vm.rename(device, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun DeviceRow(
    device: TvDevice,
    saved: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onForget: () -> Unit = {},
) {
    val c = C
    var menu by remember { mutableStateOf(false) }
    val tint = brandColor(device.brand)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, top = 11.dp, bottom = 11.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // App-icon style tile with a glossy gradient.
        Box(
            Modifier
                .size(42.dp)
                .shadow(4.dp, RoundedCornerShape(11.dp), ambientColor = tint, spotColor = tint)
                .clip(RoundedCornerShape(11.dp))
                .background(Brush.verticalGradient(listOf(lerp(tint, Color.White, 0.2f), lerp(tint, Color.Black, 0.15f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Tv, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = c.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${device.brand.short} · ${device.ip}", style = MaterialTheme.typography.bodySmall, color = c.secondaryLabel)
        }
        if (saved) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More options", tint = c.tertiaryLabel)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                        menu = false
                        onRename()
                    })
                    DropdownMenuItem(text = { Text("Forget", color = c.red) }, onClick = {
                        menu = false
                        onForget()
                    })
                }
            }
        } else {
            Icon(
                Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = c.tertiaryLabel,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
    }
}
