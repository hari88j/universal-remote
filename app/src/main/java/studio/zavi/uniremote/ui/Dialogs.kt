@file:OptIn(ExperimentalMaterial3Api::class)

package studio.zavi.uniremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import studio.zavi.uniremote.AppsState
import studio.zavi.uniremote.core.Brand
import studio.zavi.uniremote.core.RemoteKey
import studio.zavi.uniremote.core.TvApp

private val IP_REGEX = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")

// ---------------------------------------------------------------------- connection & pairing

@Composable
fun ConnectingDialog(deviceName: String, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Connecting") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(14.dp))
                Text("Connecting to $deviceName…")
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
fun WaitingDialog(message: String, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Check your TV") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(14.dp))
                Text(message)
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
fun PairingCodeDialog(deviceName: String, error: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    // A new error means a retry: start with an empty field.
    var code by remember(error) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Pair with $deviceName") },
        text = {
            Column {
                Text("Enter the 6-character code shown on your TV.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = if (error != null) {
                        { Text(error) }
                    } else {
                        null
                    },
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 26.sp,
                        letterSpacing = 6.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (code.length == 6) onSubmit(code) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = code.length == 6, onClick = { onSubmit(code) }) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
    LaunchedEffect(error) {
        delay(200)
        runCatching { focus.requestFocus() }
    }
}

// ---------------------------------------------------------------------- remote extras

@Composable
fun KeyboardDialog(onStart: () -> Unit, onType: (String) -> Unit, onKey: (RemoteKey) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { onStart() }
    val submit = {
        onKey(RemoteKey.ENTER)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type on TV") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(true)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Live — appears on the TV as you type",
                        style = MaterialTheme.typography.labelMedium,
                        color = C.green,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Open a search box on the TV first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = C.secondaryLabel,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        onType(it)
                    },
                    singleLine = true,
                    placeholder = { Text("Start typing…") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        text = ""
                        onType("")
                    }) {
                        Icon(Icons.Rounded.Backspace, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear")
                    }
                    OutlinedButton(onClick = { submit() }) { Text("Search / Enter") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    LaunchedEffect(Unit) {
        delay(200)
        runCatching { focus.requestFocus() }
    }
}

private val MESSAGE_PRESETS = listOf("Khana ready hai 🍛", "Bas 5 minute aur", "Sone ka time 😴", "Door pe koi hai 🚪", "Phone dekho 📱")

/** Write a message that pops up on the TV screen. */
@Composable
fun MessageDialog(tvName: String, onSend: (text: String, fullScreen: Boolean, seconds: Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var full by rememberSaveable { mutableStateOf(0) }
    var secs by rememberSaveable { mutableStateOf(1) }
    val durations = listOf(5, 10, 30)
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message on TV") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Pops up on $tvName's screen over whatever is playing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = C.secondaryLabel,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(300) },
                    placeholder = { Text("Type your message…") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(MESSAGE_PRESETS.size) { i ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(C.cardHigh)
                                .clickable { text = MESSAGE_PRESETS[i] }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(MESSAGE_PRESETS[i], style = MaterialTheme.typography.labelMedium, color = C.label)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Style", style = MaterialTheme.typography.labelMedium, color = C.secondaryLabel)
                Spacer(Modifier.height(6.dp))
                Segmented(listOf("Banner", "Full screen"), full, { full = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Show for", style = MaterialTheme.typography.labelMedium, color = C.secondaryLabel)
                Spacer(Modifier.height(6.dp))
                Segmented(durations.map { "${it}s" }, secs, { secs = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSend(text, full == 1, durations[secs]) }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) {
        delay(200)
        runCatching { focus.requestFocus() }
    }
}

@Composable
fun AppsSheet(
    state: AppsState,
    supportsLinks: Boolean,
    onLaunch: (TvApp) -> Unit,
    onOpenLink: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.card) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            when (state) {
                AppsState.Idle, AppsState.Loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is AppsState.Failed -> Column {
                    Text(state.message, color = C.secondaryLabel)
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
                is AppsState.Loaded -> if (state.apps.isEmpty()) {
                    Text("No apps found on this TV.", color = C.secondaryLabel)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(84.dp),
                        modifier = Modifier.heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.apps, key = { it.id }) { app -> AppTile(app) { onLaunch(app) } }
                    }
                }
            }
            if (supportsLinks) {
                Spacer(Modifier.height(20.dp))
                LinkField(onOpenLink)
            }
        }
    }
}

@Composable
private fun AppTile(app: TvApp, onClick: () -> Unit) {
    val hue = (app.name.hashCode() and 0x7FFFFFFF) % 360
    val tile = androidx.compose.ui.graphics.Color.hsv(hue.toFloat(), 0.45f, 0.55f)
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(tile),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials(app.name), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = C.label)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            app.name,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun initials(name: String): String {
    val words = name.split(' ', '-', '.').filter { it.isNotBlank() && it.first().isLetterOrDigit() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).replaceFirstChar { it.uppercase() }
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

@Composable
private fun LinkField(onOpen: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    Text("Open a link on the TV", style = MaterialTheme.typography.labelLarge, color = C.secondaryLabel)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            placeholder = { Text("Paste a YouTube link…") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank()) onOpen(url) }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(enabled = url.isNotBlank(), onClick = { onOpen(url) }) { Text("Open") }
    }
}

// ---------------------------------------------------------------------- device management

@Composable
fun AddDeviceDialog(onAdd: (name: String, ip: String, brand: Brand) -> Unit, onDismiss: () -> Unit) {
    var ip by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var brand by rememberSaveable { mutableStateOf(Brand.ANDROID_TV) }
    val ipValid = IP_REGEX.matches(ip.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a TV") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP address") },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    isError = ip.isNotBlank() && !ipValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text("TV type", style = MaterialTheme.typography.labelLarge, color = C.secondaryLabel)
                for (b in Brand.entries) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { brand = b },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = brand == b, onClick = { brand = b })
                        Text(b.label)
                    }
                }
                Text(
                    "The TV's IP address is usually under Settings → Network → Status.",
                    style = MaterialTheme.typography.bodySmall,
                    color = C.secondaryLabel,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = ipValid, onClick = { onAdd(name, ip.trim(), brand) }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename TV") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onRename(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
