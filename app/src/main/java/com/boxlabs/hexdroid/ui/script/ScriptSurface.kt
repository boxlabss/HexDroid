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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.boxlabs.hexdroid.script.ScriptView
import com.boxlabs.hexdroid.script.ViewProps
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Script renderer.
 *
 * Buttons report back via [onAction] (actionId, args) > the script's `on SIGNAL:<id>`.
 */
private val LocalUiScale = compositionLocalOf { 1f }
private const val BASELINE_W = 360f

@Composable
fun ScriptSurface(view: ScriptView, onAction: (actionId: String, args: List<String>) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val scale = (maxWidth.value / BASELINE_W).coerceIn(0.85f, 1.8f)
        CompositionLocalProvider(LocalUiScale provides scale) {
            Render(view, onAction, Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Render(v: ScriptView, onAction: (String, List<String>) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalUiScale.current
    val m = modifier.applyProps(v.props, s)
    when (v) {
        is ScriptView.Column -> Column(
            m, verticalArrangement = Arrangement.spacedBy((v.props.gapDp * s).dp),
            horizontalAlignment = hAlign(v.props.align),
        ) { v.children.forEach { c -> Render(c, onAction, if (c.props.weight != null) Modifier.weight(c.props.weight!!) else Modifier) } }

        // A wrapping row flows its children onto extra lines instead of overflowing the screen
        // (used by the poker table's opponents strip so a full table still fits). Weight is
        // meaningless once wrapped, so wrapped children size to content.
        is ScriptView.Row -> if (v.props.wrap) FlowRow(
            m, horizontalArrangement = Arrangement.spacedBy((v.props.gapDp * s).dp),
            verticalArrangement = Arrangement.spacedBy((v.props.gapDp * s).dp),
        ) { v.children.forEach { c -> Render(c, onAction) } }
        else Row(
            m, horizontalArrangement = Arrangement.spacedBy((v.props.gapDp * s).dp),
            verticalAlignment = vAlign(v.props.align),
        ) { v.children.forEach { c -> Render(c, onAction, if (c.props.weight != null) Modifier.weight(c.props.weight!!) else Modifier) } }

        is ScriptView.Stack -> Box(m, contentAlignment = boxAlign(v.props.align)) {
            v.children.forEach { Render(it, onAction) }
        }

        is ScriptView.Ring -> {
            // Children are laid out on an ellipse: rx from `size`, ry = rx * ratio. `ratio` is a
            // unitless multiplier (like `weight`), NOT a percentage: 1.0 (the default) is a true
            // circle, 0.62 reproduces the old flattened ellipse. This used to be hardcoded to
            // 0.62, which is why the
            // poker seats collided: at 5+ players the left and right pods sat ~ry apart
            // vertically while each pod was taller than that, so they stacked on each other.
            val ratio = (v.props.ratio ?: 1f).coerceIn(0.1f, 4f).toDouble()
            val wantRxDp = ((v.props.sizeDp ?: 150) * s)
            Layout(
                content = { v.children.forEach { Render(it, onAction) } },
                modifier = m,
            ) { measurables, constraints ->
                // Measure children at their natural size. Passing our own constraints down would
                // let a fill/weight child stretch to the ring's width and swallow the layout.
                val placeables = measurables.map {
                    it.measure(constraints.copy(minWidth = 0, minHeight = 0))
                }
                val childW = placeables.maxOfOrNull { it.width } ?: 0
                val childH = placeables.maxOfOrNull { it.height } ?: 0
                // Shrink to fit rather than overflow a narrow screen, and scale ry with it so a
                // circle stays a circle instead of silently turning back into an ellipse.
                val rx = minOf(
                    wantRxDp.dp.roundToPx(),
                    ((constraints.maxWidth - childW) / 2).coerceAtLeast(0),
                )
                val ry = (rx * ratio).roundToInt()
                // Size to the content. Children are CENTRED on the ellipse, so half of the widest
                // and tallest one hangs outside it on each side. The old code reserved a fixed
                // 96dp for that, which clipped any child taller than 96.
                val w = rx * 2 + childW
                val h = ry * 2 + childH
                layout(w, h) {
                    val n = placeables.size.coerceAtLeast(1)
                    placeables.forEachIndexed { i, p ->
                        val a = Math.toRadians(90.0 + i * (360.0 / n))   // child 0 anchored at the bottom
                        p.place(
                            x = w / 2 + (cos(a) * rx).roundToInt() - p.width / 2,
                            y = h / 2 + (sin(a) * ry).roundToInt() - p.height / 2,
                        )
                    }
                }
            }
        }

        is ScriptView.Surface -> {
            val shape = when {
                v.props.ellipse -> EllipseShape
                v.props.circle -> CircleShape
                else -> RoundedCornerShape(((v.props.sizeDp ?: 12) * s).dp)
            }
            val brush = v.props.gradient?.let { gradientBrush(it) }
            var sm = m
            if (v.props.elevationDp > 0) sm = sm.shadow((v.props.elevationDp * s).dp, shape)
            // Layer, back to front: optional background image, then a gradient/solid fill (a gradient
            // over an image acts as a readability tint), then the child content on top.
            Box(sm.clip(shape).maybeBorder(v.props, shape, s)) {
                v.props.bgImage?.let { RemoteImage(it, Modifier.matchParentSize(), "crop") }
                if (brush != null) Box(Modifier.matchParentSize().background(brush))
                else if (v.props.bgImage == null) Box(Modifier.matchParentSize().background(v.props.bg.toColor() ?: MaterialTheme.colorScheme.surfaceVariant))
                v.child?.let { Render(it, onAction, Modifier.padding((v.props.padDp * s).dp)) }
            }
        }

        is ScriptView.Text -> Text(
            v.text, m,
            color = v.props.color.toColor() ?: MaterialTheme.colorScheme.onSurface,
            fontSize = ((v.props.textSp ?: 14) * s).sp,
            fontWeight = if (v.props.bold) FontWeight.Bold else FontWeight.Normal,
        )

        is ScriptView.Button -> {
            val bg = v.props.bg.toColor()
            if (bg != null) {
                val shape = RoundedCornerShape(((v.props.sizeDp ?: 12) * s).dp)
                var bm = m
                if (v.props.elevationDp > 0) bm = bm.shadow((v.props.elevationDp * s).dp, shape)
                Box(
                    bm.clip(shape).background(bg).clickable { onAction(v.actionId, v.args) }
                        .padding(horizontal = (12 * s).dp, vertical = (6 * s).dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        v.label,
                        color = v.props.color.toColor() ?: Color.White,
                        fontWeight = if (v.props.bold) FontWeight.Bold else FontWeight.Medium,
                        fontSize = ((v.props.textSp ?: 14) * s).sp,
                    )
                }
            } else {
                Button({ onAction(v.actionId, v.args) }, m) { Text(v.label) }
            }
        }

        is ScriptView.Card -> {
            val red = v.red || isRedFace(v.face)
            val blank = v.face.isBlank() && !v.back
            val cardShape = RoundedCornerShape(5.dp)
            // Game "slam": when a real face appears, the card drops in oversized and
            // bounces to rest with a quick shake. Keyed on the face, so it fires only when THIS
            // slot reveals a new card (a fresh community/hole card)
            val showFace = !v.back && !blank && v.face.isNotEmpty()
            val slam = remember(v.face) { Animatable(if (showFace) 0f else 1f) }
            LaunchedEffect(v.face) {
                if (showFace) {
                    slam.snapTo(0f)
                    slam.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 430f))
                } else {
                    slam.snapTo(1f)
                }
            }
            val pSlam = slam.value
            val cardScale = 1.5f - 0.5f * pSlam              // 1.5 -> ~1.0 (spring overshoot = the bounce)
            val cardShake = (1f - pSlam) * 7f * sin(pSlam * 18f)  // decaying wobble
            var cm = Modifier
                .width(((v.props.widthDp ?: v.props.sizeDp ?: 34) * s).dp).aspectRatio(0.7f)
                .graphicsLayer { scaleX = cardScale; scaleY = cardScale; rotationZ = cardShake }
                .then(m)
            if (v.props.elevationDp > 0) cm = cm.shadow((v.props.elevationDp * s).dp, cardShape)
            Surface(
                cm,
                shape = cardShape,
                color = when { v.back -> Color(0xFF1C1E4A); blank -> Color(0x22FFFFFF); else -> Color.White },
                border = androidx.compose.foundation.BorderStroke(1.dp, if (v.back) Color(0x55FFFFFF) else Color(0x33000000)),
            ) {
                val w = (v.props.widthDp ?: v.props.sizeDp ?: 34) * s
                when {
                    v.back -> {
                        // patterned back: indigo gradient, inset frame, centre motif
                        Box(
                            Modifier.fillMaxSize()
                                .background(Brush.linearGradient(listOf(Color(0xFF3B3F8F), Color(0xFF181A40)))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.fillMaxSize().padding((w * 0.12f).dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(3.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Text("♠", color = Color(0x99FFFFFF), fontSize = (w * 0.42f).sp) }
                        }
                    }
                    blank -> { /* faint empty slot */ }
                    else -> {
                        val ink = if (red) Color(0xFFD23B3B) else Color(0xFF15171C)
                        val rank = cardRank(v.face)
                        val suit = cardSuit(v.face)
                        Box(
                            Modifier.fillMaxSize()
                                .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFEDEDF2))))
                                .padding(horizontal = (w * 0.07f).dp, vertical = (w * 0.05f).dp),
                        ) {
                            // Corner index (rank over suit) and the centre pip are sized so they
                            // never overlap: at aspectRatio 0.7 the card is ~1.43x tall as wide, and
                            // the old 0.30/0.24 corner + 0.52 centre collided in the upper middle.
                            Column(Modifier.align(Alignment.TopStart)) {
                                Text(rank, color = ink, fontWeight = FontWeight.Bold, fontSize = (w * 0.26f).sp, lineHeight = (w * 0.26f).sp)
                                Text(suit, color = ink, fontSize = (w * 0.19f).sp, lineHeight = (w * 0.19f).sp)
                            }
                            Text(suit, color = ink, fontSize = (w * 0.38f).sp, modifier = Modifier.align(Alignment.Center))
                            if (w >= 38f) {
                                Column(Modifier.align(Alignment.BottomEnd).graphicsLayer { rotationZ = 180f }) {
                                    Text(rank, color = ink, fontWeight = FontWeight.Bold, fontSize = (w * 0.26f).sp, lineHeight = (w * 0.26f).sp)
                                    Text(suit, color = ink, fontSize = (w * 0.19f).sp, lineHeight = (w * 0.19f).sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        is ScriptView.Image -> RemoteImage(
            url = v.url,
            modifier = (if (v.props.sizeDp != null) m.size((v.props.sizeDp * s).dp) else m.fillMaxWidth())
                .then(if (v.props.circle) Modifier.clip(CircleShape) else Modifier),
            scale = v.props.scale,
        )

        is ScriptView.Spacer -> Spacer(Modifier.size(((v.props.sizeDp ?: 8) * s).dp))
    }
}

/**
 * Minimal self-contained network image
 */
@Composable
private fun RemoteImage(url: String, modifier: Modifier, scale: String? = null) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    val b = bitmap
    if (b != null) {
        val cs = when (scale) {
            "fit" -> ContentScale.Fit
            "stretch" -> ContentScale.FillBounds
            else -> ContentScale.Crop
        }
        Image(bitmap = b, contentDescription = null, modifier = modifier, contentScale = cs)
    } else {
        Box(modifier.background(Color(0x22000000)))
    }
}

/** An oval that fills its bounds, used for a poker-style table felt (surface ... ellipse). */
private val EllipseShape = object : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline = androidx.compose.ui.graphics.Outline.Generic(
        androidx.compose.ui.graphics.Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        }
    )
}

private fun Modifier.applyProps(p: ViewProps, s: Float): Modifier {
    var m = this
    if (p.fillWidth) m = m.fillMaxWidth()
    if (p.widthDp != null) m = m.width((p.widthDp * s).dp)
    if (p.heightDp != null) m = m.height((p.heightDp * s).dp)
    if (p.offsetXDp != 0 || p.offsetYDp != 0) m = m.offset((p.offsetXDp * s).dp, (p.offsetYDp * s).dp)
    if (p.padDp > 0) m = m.padding((p.padDp * s).dp)
    return m   // bg/border on Surface; weight applied in-scope by the parent
}

/** Border for non-Surface elements (to-act ring etc.); Surface passes its own shape. */
private fun Modifier.maybeBorder(p: ViewProps, shape: androidx.compose.ui.graphics.Shape, s: Float): Modifier =
    if (p.borderColor != null) this.border((p.borderWidthDp * s).dp, p.borderColor.toColor() ?: Color.Transparent, shape) else this

private fun hAlign(a: String?) = when (a) { "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start }
private fun vAlign(a: String?) = when (a) { "center" -> Alignment.CenterVertically; "bottom" -> Alignment.Bottom; else -> Alignment.Top }
private fun boxAlign(a: String?) = when (a) { "center" -> Alignment.Center; "bottom" -> Alignment.BottomCenter; "top" -> Alignment.TopCenter; else -> Alignment.Center }

private fun gradientBrush(spec: String): Brush? {
    val parts = spec.split(":")
    if (parts.size < 3) return null
    val c1 = parts[1].toColor() ?: return null
    val c2 = parts[2].toColor() ?: return null
    return if (parts[0] == "linear") Brush.linearGradient(listOf(c1, c2))
           else Brush.radialGradient(listOf(c1, c2))
}

private fun isRedFace(face: String): Boolean {
    if (face.isEmpty()) return false
    val last = face.last().lowercaseChar()
    return last == 'h' || last == 'd' || face.contains('♥') || face.contains('♦')
}

/** Suit pip for a card face like "Ah"/"Td"/"2s" (or one that already carries a ♠♥♦♣ glyph). */
private fun cardSuit(face: String): String {
    if (face.isEmpty()) return ""
    val c = face.last()
    return when (c.lowercaseChar()) {
        's' -> "♠"; 'h' -> "♥"; 'd' -> "♦"; 'c' -> "♣"
        else -> if (c == '♠' || c == '♥' || c == '♦' || c == '♣') c.toString() else ""
    }
}

/** Rank label for a card face: drops the suit, and shows "10" for the "T" shorthand. */
private fun cardRank(face: String): String {
    if (face.isEmpty()) return ""
    var r = if (cardSuit(face).isNotEmpty()) face.dropLast(1) else face
    if (r.equals("T", ignoreCase = true)) r = "10"
    return r
}

private fun String?.toColor(): Color? {
    val s = this?.removePrefix("#") ?: return null
    return try {
        val v = s.toLong(16)
        when (s.length) { 6 -> Color(0xFF000000 or v); 8 -> Color(v); else -> null }
    } catch (_: Throwable) { null }
}
