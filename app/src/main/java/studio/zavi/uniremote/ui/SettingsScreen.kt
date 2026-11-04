package studio.zavi.uniremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import studio.zavi.uniremote.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val c = C
    val settings by vm.settings.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column {
                TextButton(onClick = onBack) {
                    Icon(Icons.Rounded.KeyboardArrowLeft, contentDescription = null, tint = c.accent)
                    Text("Back", color = c.accent, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineLarge,
                    color = c.label,
                    modifier = Modifier.padding(start = 4.dp, bottom = 16.dp),
                )
            }
        }

        item { SectionHeader("Appearance") }
        item {
            InsetGroup {
                Column(Modifier.padding(12.dp)) {
                    Segmented(
                        options = ThemeMode.entries.map { it.label },
                        selected = settings.theme.ordinal,
                        onSelect = { i -> vm.updateSettings { it.copy(theme = ThemeMode.entries[i]) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
        item { SectionHeader("Theme") }
        item {
            InsetGroup {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    AccentTheme.entries.forEach { theme ->
                        val selected = settings.accent == theme
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { vm.updateSettings { it.copy(accent = theme) } }
                                .padding(6.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(theme.glow1, theme.glow2)))
                                    .border(if (selected) 3.dp else 0.dp, if (selected) c.label else Color.Transparent, CircleShape),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                theme.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) c.label else c.secondaryLabel,
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
        item { SectionHeader("Remote") }
        item {
            InsetGroup {
                ToggleRow("Vibration", "Feel a tap on every button", settings.haptics) { on ->
                    vm.updateSettings { it.copy(haptics = on) }
                }
                GroupDivider()
                ToggleRow("Phone volume buttons", "Change the TV's volume with your phone's buttons", settings.volumeKeys) { on ->
                    vm.updateSettings { it.copy(volumeKeys = on) }
                }
                GroupDivider()
                ToggleRow("Keep screen on", "Stop the phone sleeping while the remote is open", settings.keepScreenOn) { on ->
                    vm.updateSettings { it.copy(keepScreenOn = on) }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
        item { SectionHeader("Setting up your TV") }
        item {
            InsetGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tip("Same Wi-Fi", "Phone and TV must be on the same network. Guest networks usually block this.")
                    Tip("Android / Google TV", "The TV shows a 6-character code the first time. Type it in the app.")
                    Tip("Samsung & LG", "Choose Allow on the TV. To switch on from standby, enable \"Power On with Mobile\" (Samsung) or \"Turn on via Wi-Fi\" (LG).")
                    Tip("Fire TV", "Settings → My Fire TV → Developer options → ADB debugging: ON, then choose Allow on the TV.")
                    Tip("Roku", "Settings → System → Advanced system settings → Control by mobile apps → Network access: Default.")
                }
            }
        }

        item {
            Text(
                "Universal Remote 3.0",
                style = MaterialTheme.typography.bodySmall,
                color = c.tertiaryLabel,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = C
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.label)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.secondaryLabel)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.green,
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun Tip(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = C.label)
        Text(body, style = MaterialTheme.typography.bodySmall, color = C.secondaryLabel)
    }
}
