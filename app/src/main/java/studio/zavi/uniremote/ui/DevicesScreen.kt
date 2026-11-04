package studio.zavi.uniremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.zavi.uniremote.MainViewModel
import studio.zavi.uniremote.core.TvDevice

@Composable
fun DevicesScreen(vm: MainViewModel) {
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
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 10.dp)) {
                Text("Universal Remote", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Control your TV from your phone over Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
            }
        }

        if (saved.isNotEmpty()) {
            item { SectionTitle("Your TVs", Modifier.padding(top = 6.dp)) }
            items(saved, key = { "saved_" + it.id }) { device ->
                DeviceRow(
                    device = device,
                    saved = true,
                    onClick = { vm.connect(device) },
                    onRename = { renaming = device },
                    onForget = { vm.forget(device) },
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("On this Wi-Fi", Modifier.weight(1f))
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Scanning…", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                } else {
                    TextButton(onClick = { vm.scan() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Scan again")
                    }
                }
            }
        }

        if (fresh.isEmpty() && !scanning) {
            item {
                Text(
                    if (saved.isEmpty()) {
                        "No TVs found yet. Make sure the TV is on and your phone is on the same Wi-Fi, " +
                            "or add it by IP address below."
                    } else {
                        "No other TVs found."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }

        items(fresh, key = { "found_" + it.id }) { device ->
            DeviceRow(device = device, saved = false, onClick = { vm.connect(device) })
        }

        item {
            OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add a TV by IP address")
            }
        }

        item { Tips() }
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
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val tint = brandColor(device.brand)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Surface)
            .border(1.dp, Palette.Stroke, shape)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Tv, contentDescription = null, tint = tint)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${device.brand.short} · ${device.ip}",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
            )
        }
        if (saved) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More options", tint = Palette.Muted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                        menu = false
                        onRename()
                    })
                    DropdownMenuItem(text = { Text("Forget") }, onClick = {
                        menu = false
                        onForget()
                    })
                }
            }
        } else {
            Text(
                "Connect",
                style = MaterialTheme.typography.labelLarge,
                color = Palette.Accent,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
    }
}

@Composable
private fun Tips() {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(shape)
            .background(Palette.Surface)
            .border(1.dp, Palette.Stroke, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Setup tips", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Tip("Phone and TV must be on the same Wi-Fi network.")
        Tip("Android / Google TV: the TV shows a code the first time — type it in here.")
        Tip("Samsung & LG: choose Allow on the TV the first time you connect.")
        Tip("Fire TV: turn on Settings → My Fire TV → Developer options → ADB debugging.")
        Tip("Roku: set Settings → System → Advanced system settings → Control by mobile apps → Network access to Default.")
        Tip("To switch Samsung / LG on from standby, enable \"Power On with Mobile\" (Samsung) or \"Turn on via Wi-Fi\" (LG).")
    }
}

@Composable
private fun Tip(text: String) {
    Row {
        Text("•", color = Palette.Accent, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
    }
}
