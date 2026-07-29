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

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.boxlabs.hexdroid.script.ScriptView
import com.boxlabs.hexdroid.ui.focusHighlight
import com.boxlabs.hexdroid.ui.tvInitialFocus

/**
 * A true full-screen surface for a script-mounted [ScriptView], rendered via [ScriptSurface].
 *
 * Implemented as its own edge-to-edge window rather than an in-hierarchy overlay so it is
 * guaranteed above every app surface, but it behaves like a real screen: OPAQUE background
 * (no chat bleeding through), content drawn behind the system bars, and the bars themselves
 * hidden (swipe reveals them transiently) while the view is mounted. Back closes it, matching
 * every other screen in the app.
 *
 * Button taps inside the view come back through [onAction] (the engine re-mounts an updated
 * tree); the corner ✕ (or back) closes it. [onScreenChanged] fires when the device rotates
 * while mounted, so orientation-aware scripts can re-render (SIGNAL:screenchange).
 */
@Composable
fun ScriptViewHost(
    view: ScriptView,
    onAction: (actionId: String, args: List<String>) -> Unit,
    onClose: () -> Unit,
    onScreenChanged: () -> Unit = {},
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            // Edge-to-edge + immersive: draw behind the bars and hide them for the life of the
            // screen. Everything is scoped to the dialog's own window, so the activity window's
            // inset handling is untouched and nothing needs restoring on close.
            dialogWindow?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, false)
                val c = WindowInsetsControllerCompat(w, w.decorView)
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose { }
        }

        // Rotation while mounted: tell the engine so layout-aware scripts re-render.
        val orientation = LocalConfiguration.current.orientation
        LaunchedEffect(orientation) {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE ||
                orientation == Configuration.ORIENTATION_PORTRAIT
            ) onScreenChanged()
        }

        Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0B14)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val viewportH = maxHeight
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // Cap the table width and centre it: phones fill the screen, while tablets,
                    // TV, and landscape get a comfortable centred table instead of an
                    // edge-to-edge stretch. Min-height = viewport so fill/weight layouts can
                    // claim the whole screen; the scroll only engages when content is taller.
                    ScriptSurface(
                        view = view,
                        onAction = onAction,
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .heightIn(min = viewportH),
                    )
                }
                // Close floats over the content in the top-end corner. Kept inside the safe-draw
                // insets so a display cutout cannot swallow it. It is the one control every
                // script view must be able to hand back to the user, so it keeps a real
                // thumb-sized 48dp target, and on TV it takes initial focus so a D-pad user is
                // never stranded inside an unfocused screen.
                Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopEnd) {
                    Surface(
                        onClick = onClose,
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0x33FFFFFF),
                        modifier = Modifier
                            .padding(6.dp)
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .tvInitialFocus()
                            .focusHighlight(RoundedCornerShape(14.dp)),
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
            }
        }
    }
}
