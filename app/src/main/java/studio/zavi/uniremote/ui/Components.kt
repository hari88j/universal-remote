package studio.zavi.uniremote.ui

import android.view.HapticFeedbackConstants
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * The remote's basic button: fires on touch-down (feels instant), gives haptic feedback,
 * shrinks slightly while held and, with [repeat], keeps firing while held (volume, arrows).
 */
@Composable
fun PressableKey(
    onPress: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    repeat: Boolean = false,
    shape: Shape = CircleShape,
    color: Color = Palette.Raised,
    pressedColor: Color = Palette.RaisedHi,
    border: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (pressed) 0.93f else 1f, label = "keyScale")

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.3f
            }
            .clip(shape)
            .background(if (pressed) pressedColor else color)
            .then(if (border) Modifier.border(1.dp, Palette.Stroke, shape) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
                if (enabled) {
                    onClick(label = label) {
                        latest()
                        true
                    }
                }
            }
            .pointerInput(enabled, repeat) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    latest()
                    var repeater: Job? = null
                    if (repeat) {
                        repeater = scope.launch {
                            delay(450)
                            while (true) {
                                latest()
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                delay(120)
                            }
                        }
                    }
                    tryAwaitRelease()
                    repeater?.cancel()
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun IconKey(
    icon: ImageVector,
    label: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier.size(56.dp),
    enabled: Boolean = true,
    repeat: Boolean = false,
    tint: Color = Palette.Text,
    color: Color = Palette.Raised,
    pressedColor: Color = Palette.RaisedHi,
    iconSize: Dp = 24.dp,
) {
    PressableKey(
        onPress = onPress,
        label = label,
        modifier = modifier,
        enabled = enabled,
        repeat = repeat,
        color = color,
        pressedColor = pressedColor,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Round directional pad: four arrow zones around a centre OK button. Arrows repeat when held. */
@Composable
fun DPad(onKey: (RemoteKey) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Palette.RaisedHi, Palette.Raised, Palette.Surface)))
            .border(1.dp, Palette.Stroke, CircleShape),
    ) {
        DirectionKey(RemoteKey.UP, 0f, onKey, Modifier.align(Alignment.TopCenter).fillMaxWidth(0.36f).fillMaxHeight(0.31f))
        DirectionKey(RemoteKey.DOWN, 180f, onKey, Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.36f).fillMaxHeight(0.31f))
        DirectionKey(RemoteKey.LEFT, -90f, onKey, Modifier.align(Alignment.CenterStart).fillMaxWidth(0.31f).fillMaxHeight(0.36f))
        DirectionKey(RemoteKey.RIGHT, 90f, onKey, Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.31f).fillMaxHeight(0.36f))
        PressableKey(
            onPress = { onKey(RemoteKey.OK) },
            label = "OK",
            modifier = Modifier.align(Alignment.Center).fillMaxSize(0.38f),
            color = Palette.AccentDim,
            pressedColor = Palette.Accent,
        ) {
            Text("OK", color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun DirectionKey(key: RemoteKey, rotation: Float, onKey: (RemoteKey) -> Unit, modifier: Modifier) {
    PressableKey(
        onPress = { onKey(key) },
        label = key.name.lowercase(),
        modifier = modifier,
        repeat = true,
        shape = RoundedCornerShape(28.dp),
        color = Color.Transparent,
        pressedColor = Palette.Accent.copy(alpha = 0.18f),
        border = false,
    ) {
        Icon(
            Icons.Rounded.KeyboardArrowUp,
            contentDescription = null,
            tint = Palette.Text,
            modifier = Modifier.size(34.dp).rotate(rotation),
        )
    }
}

/** Swipe to move one step per swipe distance, tap for OK. */
@Composable
fun Touchpad(onKey: (RemoteKey) -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val latest by rememberUpdatedState(onKey)
    val shape = RoundedCornerShape(36.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Palette.Raised)
            .border(1.dp, Palette.Stroke, shape)
            .pointerInput(Unit) {
                val step = 44.dp.toPx()
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
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            acc = Offset.Zero
                        }
                        change.consume()
                    }
                    if (!moved) {
                        latest(RemoteKey.OK)
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(8.dp))
            Text("Swipe to move · tap for OK", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
    }
}

/** Tall pill with + and − halves, for volume and channel. */
@Composable
fun Rocker(
    label: String,
    onUp: () -> Unit,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(34.dp)
    Column(
        modifier = modifier
            .width(68.dp)
            .height(150.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .clip(shape)
            .background(Palette.Raised)
            .border(1.dp, Palette.Stroke, shape),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PressableKey(
            onPress = onUp,
            label = "$label up",
            modifier = Modifier.fillMaxWidth().weight(1f),
            enabled = enabled,
            repeat = true,
            shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
            color = Color.Transparent,
            border = false,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = Palette.Text)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.Muted, letterSpacing = 1.sp)
        PressableKey(
            onPress = onDown,
            label = "$label down",
            modifier = Modifier.fillMaxWidth().weight(1f),
            enabled = enabled,
            repeat = true,
            shape = RoundedCornerShape(bottomStart = 34.dp, bottomEnd = 34.dp),
            color = Color.Transparent,
            border = false,
        ) {
            Icon(Icons.Rounded.Remove, contentDescription = null, tint = Palette.Text)
        }
    }
}

/** Icon-over-label button for the bottom row (keyboard, numbers, apps…). */
@Composable
fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.3f)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(shape)
                .background(Palette.Raised)
                .border(1.dp, Palette.Stroke, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Palette.Text)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = Palette.Muted,
        letterSpacing = 1.2.sp,
    )
}

@Composable
fun StatusDot(on: Boolean) {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (on) Palette.Good else Palette.Muted),
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
