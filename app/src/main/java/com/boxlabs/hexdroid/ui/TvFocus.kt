/*
 * HexDroidIRC - An IRC Client for Android
 * Copyright (C) 2026 boxlabs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.boxlabs.hexdroid.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * True when running on an Android TV / leanback device (UiModeManager reports
 * UI_MODE_TYPE_TELEVISION). Remembered per composition; the mode type cannot
 * change while the activity is alive.
 */
@Composable
fun isTvDevice(): Boolean {
    val ctx = LocalContext.current
    return remember {
        val uiMode = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}

/**
 * On TV only, requests initial focus on this element after first composition,
 * so a D-pad user sees a visible focus ring immediately instead of a screen
 * that appears unresponsive until the first key press. No-op on phones and
 * tablets (touch mode must not be disturbed, and focusing a field could pop
 * the keyboard).
 *
 * The one-frame wait lets the node attach and any enter animation begin;
 * requestFocus on an unattached node throws, hence the runCatching. Losing
 * the race is harmless: the user's first D-pad press assigns focus anyway.
 */
@Composable
fun Modifier.tvInitialFocus(): Modifier {
    val isTv = isTvDevice()
    val fr = remember { FocusRequester() }
    LaunchedEffect(isTv) {
        if (isTv) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
        }
    }
    return this.focusRequester(fr)
}

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

/**
 * Makes the HSV hue/saturation wheel usable without touch, mirroring the
 * dpadReorder select-to-engage pattern so arrow keys are only captured while
 * the user has explicitly entered adjust mode (otherwise up/down could never
 * move focus off the wheel to the brightness slider and dialog buttons).
 *
 * Focused: dim circular ring, arrows move focus normally.
 * Select (D-pad center / Enter): toggles adjust mode, shown as a bold ring.
 * In adjust mode: left/right step hue by [hueStep] degrees, up/down step
 * saturation by [satStep]; key auto-repeat makes held keys sweep smoothly.
 * Select again, back, or focus loss exits adjust mode.
 */
@Composable
fun Modifier.dpadColourWheel(
    onHue: (deltaDegrees: Float) -> Unit,
    onSat: (delta: Float) -> Unit,
    hueStep: Float = 10f,
    satStep: Float = 0.05f,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    var adjustMode by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) adjustMode = false
        }
        .onKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (ev.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    adjustMode = !adjustMode
                    true
                }
                Key.DirectionLeft -> {
                    if (adjustMode) { onHue(-hueStep); true } else false
                }
                Key.DirectionRight -> {
                    if (adjustMode) { onHue(hueStep); true } else false
                }
                Key.DirectionUp -> {
                    if (adjustMode) { onSat(satStep); true } else false
                }
                Key.DirectionDown -> {
                    if (adjustMode) { onSat(-satStep); true } else false
                }
                Key.Back, Key.Escape -> {
                    if (adjustMode) { adjustMode = false; true } else false
                }
                else -> false
            }
        }
        .focusable()
        .then(
            when {
                adjustMode -> Modifier.border(3.dp, color, CircleShape)
                focused -> Modifier.border(2.dp, color.copy(alpha = 0.6f), CircleShape)
                else -> Modifier
            }
        )
}

/**
 * Makes a drag handle that resizes a pane usable without touch, following the
 * same select-to-engage pattern as [dpadReorder]. The handle becomes focusable;
 * select (D-pad centre/enter) toggles resize mode, shown as a filled highlight.
 * While in resize mode, left/right call [onLeft]/[onRight] instead
 * of moving focus, and key auto-repeat makes a held key sweep the pane. Select,
 * back, or moving focus away exits resize mode, and [onEnd] runs so the caller
 * can persist the new width.
 */
@Composable
fun Modifier.dpadResize(
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onEnd: () -> Unit = {},
): Modifier {
    var focused by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(4.dp)

    fun exit() {
        if (resizeMode) {
            resizeMode = false
            onEnd()
        }
    }

    return this
        .onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) exit()
        }
        .onKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (ev.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    if (resizeMode) exit() else resizeMode = true
                    true
                }
                Key.DirectionLeft -> {
                    if (resizeMode) { onLeft(); true } else false
                }
                Key.DirectionRight -> {
                    if (resizeMode) { onRight(); true } else false
                }
                Key.Back, Key.Escape -> {
                    if (resizeMode) { exit(); true } else false
                }
                else -> false
            }
        }
        .focusable()
        .then(
            when {
                resizeMode -> Modifier.background(color.copy(alpha = 0.35f), shape)
                focused -> Modifier.border(2.dp, color, shape)
                else -> Modifier
            }
        )
}

/**
 * Makes a D-pad select press (center / Enter) activate an element that only
 * responds to pointer input. Compose clickables handle select natively, but
 * ExposedDropdownMenuBox's menuAnchor opens its menu from a pointerInput tap
 * detector, so a focused read-only anchor field ignores the select button and
 * the menu can never be opened from a remote or hardware keyboard.
 *
 * Apply to the anchor field and toggle the menu's expanded state in
 * [onActivate]. Acts on KeyDown, matching the other helpers in this file; the
 * paired KeyUp propagates but unpaired KeyUps are ignored by clickables, so
 * nothing double-fires. All other keys pass through, leaving normal focus
 * traversal unaffected. Touch behavior is unchanged.
 */
fun Modifier.dpadActivate(onActivate: () -> Unit): Modifier {
    return this.onKeyEvent { ev ->
        if (ev.type == KeyEventType.KeyDown &&
            (ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter)
        ) {
            onActivate()
            true
        } else {
            false
        }
    }
}
