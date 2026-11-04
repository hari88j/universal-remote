package studio.zavi.uniremote.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.zavi.uniremote.core.RemoteKey
import kotlin.math.abs

private fun View.tick(enabled: Boolean, strong: Boolean = true) {
    if (enabled) performHapticFeedback(if (strong) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.CLOCK_TICK)
}

/** Frosted-glass surface: translucent fill, a top sheen and a bright edge. */
fun Modifier.glass(shape: Shape, c: AppColors, fill: Color = c.glassFill): Modifier = this
    .clip(shape)
    .background(fill)
    .background(Brush.verticalGradient(0f to c.glassSheen, 0.55f to Color.Transparent))
    .border(1.dp, Brush.verticalGradient(listOf(c.glassStroke, c.glassStroke.copy(alpha = c.glassStroke.alpha * 0.25f))), shape)

/** Soft coloured glows behind everything, which the glass picks up. */
@Composable
fun GlowBackground(modifier: Modifier = Modifier) {
    val c = C
    val strength = if (c.isDark) 0.55f else 0.35f
    Canvas(modifier.fillMaxSize()) {
        drawRect(c.background)
        val w = size.width
        val h = size.height
        drawCircle(
            Brush.radialGradient(listOf(c.blob1.copy(alpha = strength), Color.Transparent), center = Offset(w * 0.1f, h * 0.28f), radius = w * 0.75f),
            radius = w * 0.75f,
            center = Offset(w * 0.1f, h * 0.28f),
        )
        drawCircle(
            Brush.radialGradient(listOf(c.blob2.copy(alpha = strength), Color.Transparent), center = Offset(w * 0.95f, h * 0.72f), radius = w * 0.7f),
            radius = w * 0.7f,
            center = Offset(w * 0.95f, h * 0.72f),
        )
    }
}

/** Gesture + semantics shared by every key: fires on touch-down, optional repeat while held. */
@Composable
private fun Modifier.pressGesture(
    label: String,
    enabled: Boolean,
    repeat: Boolean,
    onPress: () -> Unit,
    onPressedChange: (Boolean) -> Unit,
): Modifier {
    val view = LocalView.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onPress)
    val pressedChange by rememberUpdatedState(onPressedChange)
    return this
        .semantics(mergeDescendants = true) {
            contentDescription = label
            role = Role.Button
            if (enabled) onClick(label = label) { latest(); true }
        }
        .pointerInput(enabled, repeat, haptics) {
            if (!enabled) return@pointerInput
            detectTapGestures(onPress = {
                pressedChange(true)
                view.tick(haptics)
                latest()
                var repeater: Job? = null
                if (repeat) {
                    repeater = scope.launch {
                        delay(420)
                        while (true) {
                            latest()
                            view.tick(haptics, strong = false)
                            delay(110)
                        }
                    }
                }
                tryAwaitRelease()
                repeater?.cancel()
                pressedChange(false)
            })
        }
}

/**
 * A physical-looking key: glossy gradient, top highlight edge and a soft drop shadow.
 * Pressing sinks it (shadow shrinks, gradient flips, slight scale down).
 */
@Composable
fun Key3D(
    onPress: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    repeat: Boolean = false,
    shape: Shape = CircleShape,
    tint: Color? = null,
    elevation: Dp = 8.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = C
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "scale")
    val fill = when {
        tint != null -> tint.copy(alpha = if (pressed) 0.85f else 0.6f)
        pressed -> c.glassFillPressed
        else -> c.glassFill
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.35f
            }
            .glass(shape, c, fill)
            .pressGesture(label, enabled, repeat, onPress) { pressed = it },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun IconKey3D(
    icon: ImageVector,
    label: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier.size(58.dp),
    enabled: Boolean = true,
    repeat: Boolean = false,
    tint: Color? = null,
    iconTint: Color? = null,
    iconSize: Dp = 24.dp,
) {
    Key3D(onPress, label, modifier, enabled, repeat, CircleShape, tint) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconTint ?: if (tint != null) Color.White else C.label,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Glossy 3D directional pad: a raised disc with four arrow zones and a raised OK button. */
@Composable
fun DPad3D(onKey: (RemoteKey) -> Unit, modifier: Modifier = Modifier) {
    val c = C
    Box(
        modifier = modifier
            .glass(CircleShape, c),
    ) {
        // Inner groove that frames the arrows.
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxSize(0.9f)
                .clip(CircleShape)
                .border(1.dp, c.glassStroke.copy(alpha = c.glassStroke.alpha * 0.4f), CircleShape),
        )
        ArrowZone(RemoteKey.UP, 0f, onKey, Modifier.align(Alignment.TopCenter).fillMaxWidth(0.4f).fillMaxHeight(0.3f))
        ArrowZone(RemoteKey.DOWN, 180f, onKey, Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.4f).fillMaxHeight(0.3f))
        ArrowZone(RemoteKey.LEFT, -90f, onKey, Modifier.align(Alignment.CenterStart).fillMaxWidth(0.3f).fillMaxHeight(0.4f))
        ArrowZone(RemoteKey.RIGHT, 90f, onKey, Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.3f).fillMaxHeight(0.4f))
        Key3D(
            onPress = { onKey(RemoteKey.OK) },
            label = "OK",
            modifier = Modifier.align(Alignment.Center).fillMaxSize(0.4f),
            tint = c.accent,
            elevation = 12.dp,
        ) {
            Text("OK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun ArrowZone(key: RemoteKey, rotation: Float, onKey: (RemoteKey) -> Unit, modifier: Modifier) {
    val c = C
    var pressed by remember { mutableStateOf(false) }
    val glow by animateColorAsState(if (pressed) c.accent.copy(alpha = 0.22f) else Color.Transparent, label = "glow")
    val arrow by animateColorAsState(if (pressed) c.accent else c.label.copy(alpha = 0.85f), label = "arrow")
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(30.dp))
            .background(glow)
            .pressGesture(key.name.lowercase(), enabled = true, repeat = true, onPress = { onKey(key) }) { pressed = it },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null, tint = arrow, modifier = Modifier.size(38.dp).rotate(rotation))
    }
}

/** Glass-like trackpad: swipe moves one step per swipe distance, tap is OK. */
@Composable
fun Trackpad(onKey: (RemoteKey) -> Unit, modifier: Modifier = Modifier) {
    val c = C
    val view = LocalView.current
    val haptics = LocalHaptics.current
    val latest by rememberUpdatedState(onKey)
    val shape = RoundedCornerShape(40.dp)
    Box(
        modifier = modifier
            .glass(shape, c)
            .pointerInput(haptics) {
                val step = 42.dp.toPx()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var acc = Offset.Zero
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        acc += change.positionChange()
                        if (abs(acc.x) > step || abs(acc.y) > step) {
                            moved = true
                            val key = if (abs(acc.x) > abs(acc.y)) {
                                if (acc.x > 0) RemoteKey.RIGHT else RemoteKey.LEFT
                            } else {
                                if (acc.y > 0) RemoteKey.DOWN else RemoteKey.UP
                            }
                            latest(key)
                            view.tick(haptics, strong = false)
                            acc = Offset.Zero
                        }
                        change.consume()
                    }
                    if (!moved) {
                        latest(RemoteKey.OK)
                        view.tick(haptics)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = c.secondaryLabel, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(10.dp))
            Text("Swipe to move", style = MaterialTheme.typography.titleMedium, color = c.label)
            Text("Tap anywhere for OK", style = MaterialTheme.typography.bodySmall, color = c.secondaryLabel)
        }
    }
}

/** Raised capsule with + and − halves (volume / channel). Hold to repeat. */
@Composable
fun Rocker3D(
    label: String,
    onUp: () -> Unit,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = C
    val shape = RoundedCornerShape(36.dp)
    Column(
        modifier = modifier
            .width(72.dp)
            .height(160.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .glass(shape, c),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RockerHalf(Icons.Rounded.Add, "$label up", enabled, onUp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel, letterSpacing = 1.5.sp)
        RockerHalf(Icons.Rounded.Remove, "$label down", enabled, onDown)
    }
}

@Composable
private fun ColumnScope.RockerHalf(icon: ImageVector, label: String, enabled: Boolean, onPress: () -> Unit) {
    val c = C
    var pressed by remember { mutableStateOf(false) }
    val overlay by animateColorAsState(
        if (pressed) c.glassFillPressed else Color.Transparent,
        label = "half",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(overlay)
            .pressGesture(label, enabled, repeat = true, onPress = onPress) { pressed = it },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = c.label, modifier = Modifier.size(26.dp))
    }
}

/** iOS-style segmented control. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = C
    val outer = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .glass(outer, c)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, text ->
            val isSel = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSel) c.accent.copy(alpha = 0.45f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                    color = c.label,
                )
            }
        }
    }
}

/** Icon tile over a caption, for the bottom tool row. */
@Composable
fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, active: Boolean = false) {
    val c = C
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.alpha(if (enabled) 1f else 0.35f),
    ) {
        Key3D(
            onPress = { if (enabled) onClick() },
            label = label,
            modifier = Modifier.size(54.dp),
            enabled = enabled,
            shape = RoundedCornerShape(17.dp),
            tint = if (active) c.accent else null,
        ) {
            Icon(icon, contentDescription = null, tint = if (active) Color.White else c.label)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.secondaryLabel)
    }
}

/** Rounded white/grey card, like an iOS inset grouped list. */
@Composable
fun InsetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(16.dp), C),
        content = content,
    )
}

@Composable
fun GroupDivider(startPadding: Dp = 16.dp) {
    Box(
        Modifier
            .padding(start = startPadding)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(C.separator),
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = C.secondaryLabel,
        letterSpacing = 0.6.sp,
    )
}

@Composable
fun StatusDot(on: Boolean) {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (on) C.green else C.secondaryLabel),
    )
}

@Composable
fun KeyRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
