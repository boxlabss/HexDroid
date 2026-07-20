package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/**
 * Draws a clearly visible border and tint on the element while it holds focus.
 *
 * Purpose: D-pad and keyboard navigation (Android TV, ChromeOS, hardware
 * keyboards). Material3's default focus indication is a faint state layer that
 * is nearly invisible on dark themes at TV viewing distance; this makes the
 * focused element unmistakable.
 *
 * Phone behavior is unchanged: in touch mode Compose does not assign focus to
 * clickable elements, so the highlight never appears for touch interaction.
 *
 * Place BEFORE the clickable/focusable modifier in the chain:
 *     Modifier.focusHighlight().clickable { ... }
 */
@Composable
fun Modifier.focusHighlight(shape: Shape = RoundedCornerShape(8.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val base = this.onFocusChanged { focused = it.isFocused }
    return if (focused) {
        base
            .border(2.dp, color, shape)
            .background(color.copy(alpha = 0.12f), shape)
    } else {
        base
    }
}

/**
 * Makes a drag-reorder handle usable without touch. The handle becomes
 * focusable; pressing select (D-pad center / Enter) toggles "move mode",
 * shown as a filled highlight. While in move mode, D-pad up/down call
 * [onMoveUp]/[onMoveDown] instead of moving focus. Select, back, or moving
 * focus away exits move mode.
 *
 * Touch drag behavior is unaffected; apply this in addition to the existing
 * pointerInput drag detection.
 */
@Composable
fun Modifier.dpadReorder(onMoveUp: () -> Unit, onMoveDown: () -> Unit): Modifier {
    var focused by remember { mutableStateOf(false) }
    var moveMode by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(4.dp)
    return this
        .onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) moveMode = false
        }
        .onKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (ev.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    moveMode = !moveMode
                    true
                }
                Key.DirectionUp -> {
                    if (moveMode) { onMoveUp(); true } else false
                }
                Key.DirectionDown -> {
                    if (moveMode) { onMoveDown(); true } else false
                }
                Key.Back, Key.Escape -> {
                    if (moveMode) { moveMode = false; true } else false
                }
                else -> false
            }
        }
        .focusable()
        .then(
            when {
                moveMode -> Modifier.background(color.copy(alpha = 0.35f), shape)
                focused -> Modifier.border(2.dp, color, shape)
                else -> Modifier
            }
        )
}
