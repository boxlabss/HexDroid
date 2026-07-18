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

package com.boxlabs.hexdroid.ui.script

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.boxlabs.hexdroid.script.ScriptView

/**
 * A full-screen overlay that renders a script-mounted [ScriptView]
 * via [ScriptSurface]. Button taps inside the view come back through [onAction] (the engine
 * re-mounts an updated tree); the ✕ closes it.
 */
@Composable
fun ScriptViewHost(
    view: ScriptView,
    onAction: (actionId: String, args: List<String>) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xCC0B0B14)) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    // Close is the one control every script view must be able to hand back to the
                    // user, so it gets a real thumb-sized target. A 12sp glyph with 10/4dp padding is
                    // about 30x20dp: well under the 48dp minimum, and the hardest thing on screen to
                    // hit one-handed on a phone. Size the touch area, not just the glyph.
                    Surface(
                        onClick = onClose,
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0x33FFFFFF),
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "✕",
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // Cap the table width and centre it: phones fill the screen, while tablets and
                    // landscape get a comfortable centred table instead of an edge-to-edge stretch.
                    ScriptSurface(view = view, onAction = onAction, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth())
                }
            }
        }
    }
}
