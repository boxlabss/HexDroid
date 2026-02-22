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

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.boxlabs.hexdroid.BuildConfig
import com.boxlabs.hexdroid.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import android.graphics.Color as AColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val website = "https://hexdroid.boxlabs.uk/"
    val scroll = rememberScrollState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF111111)
                )
            )
        }
    ) { padding ->
        if (isLandscape) {
            // Landscape: side-by-side layout
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF111111))
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Flask on the left
                FlaskHero(
                    modifier = Modifier
                        .weight(0.45f)
                        .fillMaxHeight()
                        .padding(vertical = 8.dp),
                    logoSize = 100.dp,
                    flaskSize = 90.dp
                )

                // Info on the right (scrollable)
                Column(
                    modifier = Modifier
                        .weight(0.55f)
                        .fillMaxHeight()
                        .verticalScroll(scroll)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AboutContent(ctx, website)
                }
            }
        } else {
            // Portrait: stacked layout (scrollable)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF111111))
                    .padding(padding)
                    .verticalScroll(scroll)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                FlaskHero(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                )

                AboutContent(ctx, website)
            }
        }
    }
}

@Composable
private fun AboutContent(ctx: Context, website: String) {
    Text(
        stringResource(R.string.about_subtitle),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = Color.White
    )
    Text(
        stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.8f)
    )

    Spacer(Modifier.height(4.dp))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 400.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        )
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.about_doc_support),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White
            )
            Text(
                stringResource(R.string.about_doc_support_desc),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            OutlinedButton(
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, website.toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            ) { Text(stringResource(R.string.about_open_website)) }
        }
    }
}

/**
 * Hero area with animated flask, bubbles, and logo
 */
@Composable
private fun FlaskHero(
    modifier: Modifier = Modifier,
    logoSize: Dp = 140.dp,
    flaskSize: Dp = 110.dp,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current

    // Accelerometer tilt for bubble physics
    var tilt by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (sensor == null) {
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val ax = event.values.getOrNull(0) ?: 0f
                    val ay = event.values.getOrNull(1) ?: 0f
                    val nx = (ax / SensorManager.GRAVITY_EARTH).coerceIn(-1.5f, 1.5f)
                    val ny = (ay / SensorManager.GRAVITY_EARTH).coerceIn(-1.5f, 1.5f)
                    // Smooth interpolation
                    tilt = Offset(
                        x = tilt.x + (nx - tilt.x) * 0.08f,
                        y = tilt.y + (ny - tilt.y) * 0.08f
                    )
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { sm.unregisterListener(listener) }
        }
    }

    // Animation values
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "hero")

    // Smooth continuous time for wave animation
    var animTime by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    val dt = (nanos - lastNanos) / 1_000_000_000f
                    animTime += dt
                }
                lastNanos = nanos
            }
        }
    }

    // Gentle hue shift
    val hueShift by inf.animateFloat(
        initialValue = 0f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hueShift"
    )

    val baseColor = Color(0xFF00B4F4)
    val accent = hueRotate(baseColor, hueShift)

    // Bubble data class
    data class Bubble(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val radius: Float,
        val baseAlpha: Float,
        val wobbleOffset: Float,
        val wobbleSpeed: Float,
        val riseSpeed: Float
    )

    BoxWithConstraints(modifier = modifier) {
        val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        val logoSizePx = with(density) { logoSize.toPx() }
        val flaskSizePx = with(density) { flaskSize.toPx() }
        val topPad = with(density) { 8.dp.toPx() }
        val bottomPad = with(density) { 8.dp.toPx() }

        // Logo rect at top center
        val logoRect = Rect(
            left = (wPx - logoSizePx) / 2f,
            top = topPad,
            right = (wPx + logoSizePx) / 2f,
            bottom = topPad + logoSizePx
        )
        val logoCenter = logoRect.center

        // Flask rect at bottom center
        val flaskRect = Rect(
            left = (wPx - flaskSizePx) / 2f,
            top = hPx - bottomPad - flaskSizePx,
            right = (wPx + flaskSizePx) / 2f,
            bottom = hPx - bottomPad
        )

        // Liquid level in flask (animated)
        val liquidFillBase = 0.65f
        val liquidWobble = sin(animTime * 0.5f) * 0.03f
        val liquidFill = (liquidFillBase + liquidWobble).coerceIn(0.5f, 0.8f)
        val liquidTopY = flaskRect.bottom - flaskRect.height * liquidFill

        // Initialize bubbles
        val rng = remember { Random(System.nanoTime().toInt()) }
        val bubbles = remember {
            MutableList(22) {
                Bubble(
                    x = 0f, y = 0f,
                    vx = 0f, vy = 0f,
                    radius = rng.nextFloat() * 6f + 4f,
                    baseAlpha = rng.nextFloat() * 0.3f + 0.4f,
                    wobbleOffset = rng.nextFloat() * PI.toFloat() * 2f,
                    wobbleSpeed = rng.nextFloat() * 0.5f + 0.8f,
                    riseSpeed = rng.nextFloat() * 30f + 40f
                )
            }
        }

        fun resetBubble(b: Bubble) {
            val pad = flaskSizePx * 0.15f
            b.x = flaskRect.left + pad + rng.nextFloat() * (flaskRect.width - pad * 2)
            b.y = flaskRect.bottom - rng.nextFloat() * flaskRect.height * 0.3f
            b.vx = (rng.nextFloat() - 0.5f) * 10f
            b.vy = -b.riseSpeed
        }

        LaunchedEffect(wPx, hPx) {
            bubbles.forEach { resetBubble(it) }
        }

        // Interaction impulse
        fun applyImpulse(at: Offset, drag: Offset?) {
            val influence = flaskSizePx * 0.8f
            for (b in bubbles) {
                val dx = b.x - at.x
                val dy = b.y - at.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > influence) continue

                val falloff = (1f - dist / influence).pow(2)
                if (drag != null) {
                    b.vx += drag.x * 15f * falloff
                    b.vy += drag.y * 10f * falloff
                } else {
                    val nx = if (dist > 0.001f) dx / dist else 0f
                    val ny = if (dist > 0.001f) dy / dist else -1f
                    b.vx += nx * 150f * falloff
                    b.vy += ny * 100f * falloff - 50f * falloff
                }
            }
        }

        // Physics tick
        var tick by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            var lastNanos = 0L
            while (true) {
                withFrameNanos { nanos ->
                    val dt = if (lastNanos == 0L) 0.016f else ((nanos - lastNanos) / 1e9f).coerceIn(0.005f, 0.033f)
                    lastNanos = nanos

                    val time = nanos / 1e9f

                    for (b in bubbles) {
                        // Buoyancy + wobble
                        val wobble = sin(time * b.wobbleSpeed + b.wobbleOffset) * 8f
                        val tiltForceX = -tilt.x * 60f
                        val tiltForceY = tilt.y * 20f

                        b.vx += (wobble + tiltForceX) * dt
                        b.vy += (-b.riseSpeed * 0.8f + tiltForceY) * dt

                        // Damping
                        b.vx *= 0.97f
                        b.vy *= 0.99f

                        b.x += b.vx * dt
                        b.y += b.vy * dt

                        // Containment within flask
                        if (b.y >= flaskRect.top) {
                            val pad = flaskSizePx * 0.12f
                            if (b.x < flaskRect.left + pad) {
                                b.x = flaskRect.left + pad
                                b.vx = abs(b.vx) * 0.5f
                            }
                            if (b.x > flaskRect.right - pad) {
                                b.x = flaskRect.right - pad
                                b.vx = -abs(b.vx) * 0.5f
                            }
                        } else {
                            // Free floating above flask
                            if (b.x < 0f) { b.x = 0f; b.vx = abs(b.vx) * 0.5f }
                            if (b.x > wPx) { b.x = wPx; b.vx = -abs(b.vx) * 0.5f }
                        }

                        // Respawn conditions
                        val distToLogo = sqrt((b.x - logoCenter.x).pow(2) + (b.y - logoCenter.y).pow(2))
                        if (distToLogo < logoSizePx * 0.25f || b.y < -50f || b.y > hPx + 50f) {
                            resetBubble(b)
                        }
                    }

                    tick = nanos
                }
            }
        }

        // Interaction modifier
        val interactionMod = Modifier
            .pointerInput(Unit) {
                detectTapGestures { pos -> applyImpulse(pos, null) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    applyImpulse(change.position, dragAmount)
                }
            }

        // Draw everything
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(interactionMod)
        ) {
            val time = animTime

            // Calculate bubble alpha based on distance to logo
            fun bubbleAlpha(center: Offset, baseAlpha: Float): Float {
                val distToLogo = sqrt((center.x - logoCenter.x).pow(2) + (center.y - logoCenter.y).pow(2))
                val fadeStart = logoSizePx * 0.7f
                val fadeEnd = logoSizePx * 0.3f
                val logoFade = ((distToLogo - fadeEnd) / (fadeStart - fadeEnd)).coerceIn(0f, 1f)

                // Also fade based on vertical position relative to logo
                val vertFade = ((center.y - logoRect.top) / (logoSizePx * 0.5f)).coerceIn(0f, 1f)

                return baseAlpha * min(logoFade, vertFade)
            }

            // Draw bubbles
            for (b in bubbles) {
                val center = Offset(b.x, b.y)

                // Size increases slightly as bubble rises
                val heightRatio = ((flaskRect.bottom - b.y) / (flaskRect.bottom - logoRect.bottom)).coerceIn(0f, 1f)
                val r = b.radius * (1f + heightRatio * 0.4f)

                val alpha = bubbleAlpha(center, b.baseAlpha)
                if (alpha < 0.02f) continue

                // Shimmer effect
                val shimmer = sin(time * 3f + b.wobbleOffset) * 0.1f

                // Bubble colors
                val coreColor = accent.copy(alpha = (0.5f + shimmer) * alpha)
                val rimColor = accent.copy(alpha = 0.7f * alpha)
                val highlightColor = Color.White.copy(alpha = 0.35f * alpha)

                // Main bubble gradient
                val bubbleBrush = Brush.radialGradient(
                    colors = listOf(
                        highlightColor,
                        coreColor,
                        coreColor.copy(alpha = coreColor.alpha * 0.5f),
                        Color.Transparent
                    ),
                    center = center + Offset(-r * 0.3f, -r * 0.3f),
                    radius = r * 1.4f
                )

                drawCircle(brush = bubbleBrush, radius = r, center = center)

                // Rim highlight
                drawCircle(
                    color = rimColor,
                    radius = r * 0.95f,
                    center = center,
                    style = Stroke(width = 1.5f, cap = StrokeCap.Round)
                )

                // Specular highlight
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f * alpha),
                    radius = r * 0.2f,
                    center = center + Offset(-r * 0.25f, -r * 0.25f)
                )
            }

            // Subtle glow/mist between flask and logo
            val mistBrush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    accent.copy(alpha = 0.04f),
                    accent.copy(alpha = 0.02f),
                    Color.Transparent
                ),
                startY = logoRect.bottom,
                endY = flaskRect.top
            )
            drawRect(
                brush = mistBrush,
                topLeft = Offset(0f, logoRect.bottom),
                size = androidx.compose.ui.geometry.Size(wPx, max(0f, flaskRect.top - logoRect.bottom))
            )
        }

        // Logo overlay
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.hexdroid_logo),
                contentDescription = "HexDroid logo",
                modifier = Modifier
                    .size(logoSize)
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            )
            Text(
                stringResource(R.string.about_free_app_by),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = flaskSize + 14.dp),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.Medium
            )

            ImprovedFlask(
                modifier = Modifier
                    .size(flaskSize)
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                accent = accent,
                fillFrac = liquidFill,
                time = animTime
            )
        }
    }
}

@Composable
private fun ImprovedFlask(
    modifier: Modifier = Modifier,
    accent: Color,
    fillFrac: Float,
    time: Float
) {
    val borderColor = accent.copy(alpha = 0.8f)

    BoxWithConstraints(
        modifier = modifier
            .shadow(12.dp, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .border(2.dp, borderColor, RoundedCornerShape(4.dp))
            .clipToBounds()
    ) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }
        val hPx = with(LocalDensity.current) { maxHeight.toPx() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val liquidHeight = hPx * fillFrac
            val liquidTop = hPx - liquidHeight

            // Create smooth wave path
            val wavePath = Path()
            val waveAmp = 4f
            val waveFreq = 2f

            wavePath.moveTo(0f, hPx)
            wavePath.lineTo(0f, liquidTop)

            // Smooth wave using multiple sine components
            var x = 0f
            val step = wPx / 30f
            while (x <= wPx) {
                val phase = (x / wPx) * PI.toFloat() * waveFreq
                val y = liquidTop +
                        sin(phase * 2f + time * 2f) * waveAmp * 0.6f +
                        sin(phase * 3f + time * 2.5f) * waveAmp * 0.3f +
                        cos(phase + time * 1.5f) * waveAmp * 0.2f
                wavePath.lineTo(x, y)
                x += step
            }

            wavePath.lineTo(wPx, hPx)
            wavePath.close()

            // Liquid gradient
            val liquidBrush = Brush.verticalGradient(
                colors = listOf(
                    accent.copy(alpha = 0.95f),
                    accent.copy(alpha = 0.85f),
                    accent.copy(alpha = 0.75f)
                ),
                startY = liquidTop,
                endY = hPx
            )

            drawPath(path = wavePath, brush = liquidBrush)

            // Depth shadow at bottom
            clipPath(wavePath) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.15f)
                        ),
                        startY = hPx * 0.6f,
                        endY = hPx
                    )
                )
            }

            // Surface highlight
            val surfacePath = Path()
            x = 0f
            while (x <= wPx) {
                val phase = (x / wPx) * PI.toFloat() * waveFreq
                val y = liquidTop +
                        sin(phase * 2f + time * 2f) * waveAmp * 0.6f +
                        sin(phase * 3f + time * 2.5f) * waveAmp * 0.3f +
                        cos(phase + time * 1.5f) * waveAmp * 0.2f
                if (x == 0f) surfacePath.moveTo(x, y)
                else surfacePath.lineTo(x, y)
                x += step
            }

            drawPath(
                path = surfacePath,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.1f),
                        Color.White.copy(alpha = 0.25f),
                        Color.White.copy(alpha = 0.1f)
                    )
                ),
                style = Stroke(width = 2f, cap = StrokeCap.Round)
            )

            // Glass reflection overlay
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.1f),
                        Color.Transparent,
                        Color.Transparent,
                        Color.White.copy(alpha = 0.05f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(wPx, hPx)
                )
            )

            // Left edge highlight
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    startX = 0f,
                    endX = wPx * 0.15f
                )
            )
        }

        // Boxlabs logo in liquid
        val liquidHeightDp = maxHeight * fillFrac
        val liquidTopDp = maxHeight - liquidHeightDp
        val markAspect = 175f / 959f
        val markW = maxWidth * 0.82f
        val markH = markW * markAspect
        val markCenterY = liquidTopDp + liquidHeightDp * 0.5f
        val markTop = (markCenterY - markH / 2f).coerceIn(0.dp, maxHeight - markH)

        Image(
            painter = painterResource(R.drawable.boxlabs),
            contentDescription = "boxlabs",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = markTop)
                .width(markW)
                .height(markH)
        )
    }
}

private fun hueRotate(color: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    AColor.colorToHSV(color.toArgb(), hsv)
    hsv[0] = ((hsv[0] + degrees) % 360f + 360f) % 360f
    return Color(AColor.HSVToColor((color.alpha * 255).roundToInt(), hsv))
}
