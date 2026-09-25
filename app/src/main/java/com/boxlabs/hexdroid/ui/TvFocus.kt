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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

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
 * On TV, focus this element after first composition so a focus ring shows immediately. No-op
 * elsewhere. Waits a frame for the node to attach.
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
 * A visible border and tint while focused, for D-pad and keyboard navigation. Touch is unaffected,
 * since touch mode doesn't focus clickables. Place before the clickable/focusable modifier.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.focusHighlight(shape: Shape = RoundedCornerShape(8.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val base = this
        .bringIntoViewRequester(requester)
        .onFocusChanged {
            focused = it.isFocused
            // Border included: a container stops as soon as the element's bounds are visible.
            if (it.isFocused) scope.launch { runCatching { requester.bringIntoView() } }
        }
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
 * D-pad control of the hue/saturation wheel. Select toggles adjust mode; in it, left/right step hue
 * by [hueStep] and up/down saturation by [satStep]. Select, back or focus loss exits; outside
 * adjust mode the arrows move focus.
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
 * D-pad control of a pane resize handle. Select toggles resize mode, in which left/right call
 * [onLeft]/[onRight]; select, back or focus loss exits and calls [onEnd].
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
 * Lets D-pad select (centre/Enter) activate an element that only reacts to pointer input, such as
 * an ExposedDropdownMenuBox anchor. Acts on KeyDown; other keys pass through.
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
