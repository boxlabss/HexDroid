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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.boxlabs.hexdroid.BuildConfig
import com.boxlabs.hexdroid.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import android.graphics.Color as AColor

/** Theme colour */
private val SITE_BG = Color(0xFF0F1216)
private val CARD_BG = Color(0xFF171B21)

/** Keeps the column readable on tablets and in landscape instead of running edge to edge. */
private val CONTENT_MAX_WIDTH = 420.dp

/*
 * Fixed links shared with hexdroid.org
 *
 */
private const val SUPPORT_IRC_URL = "ircs://irc.afternet.org:6697/HexDroid"
private const val SUPPORT_CHANNEL_LABEL = "#HexDroid on irc.afternet.org"
private const val WEBSITE_LABEL = "hexdroid.org"

/*
 *  CONTRIBUTORS
 */
private data class Credit(val name: String, val detail: String? = null, val url: String? = null)
private data class CreditGroup(val title: String, val members: List<Credit>)

private val CREDITS: List<CreditGroup> = listOf(
    CreditGroup(
        "Development",
        listOf(
            Credit("eck", "Lead", "https://github.com/boxlabss"),
        ),
    ),
    CreditGroup(
        "Translations",
        listOf(
            Credit("cyberdyne-sys", "Hungarian", "https://github.com/cyberdyne-sys"),
        ),
    ),
    CreditGroup(
        "Special thanks",
        listOf(
            //Credit("name", "something", "url")
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val website = "https://hexdroid.org"
    val sourceUrl = "https://github.com/boxlabss/HexDroid"
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.tvInitialFocus().focusHighlight()) { Text("←") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SITE_BG
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SITE_BG)
                .padding(padding)
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AboutContent(ctx, website, sourceUrl)
        }
    }
}

@Composable
private fun AboutHeader(accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = CONTENT_MAX_WIDTH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.hexdroid_logo),
            contentDescription = stringResource(R.string.about_logo_desc),
            modifier = Modifier.size(64.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.55f),
            )
            Text(
                "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = accent.copy(alpha = 0.9f),
            )
        }
    }
}

@Composable
private fun BoxlabsSignature() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "A free (and ad-free) app by",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.4f),
        )
        FlaskHero(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp),
            logoSize = null,
            flaskSize = 76.dp,
        )
    }
}

@Composable
private fun AboutContent(ctx: Context, website: String, sourceUrl: String) {
    // Cyan accent, used only for links, the version line and the flask
    val accent = Color(0xFF00B4F4)

    AboutHeader(accent)

    // Documentation
    AboutCard(accent, stringResource(R.string.about_support_title)) {
        Text(
            stringResource(R.string.about_support_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f)
        )
        PrimaryButton(
            label = stringResource(R.string.about_open_website),
            accent = accent,
        ) { openUrl(ctx, website) }

        val docs = listOf(
            Triple(R.string.about_doc_getting_started, R.string.about_doc_getting_started_desc, "$website/getting-started"),
            Triple(R.string.about_doc_features, R.string.about_doc_features_desc, "$website/features"),
            Triple(R.string.about_doc_reference, R.string.about_doc_reference_desc, "$website/reference"),
            Triple(R.string.about_doc_commands, R.string.about_doc_commands_desc, "$website/commands"),
            Triple(R.string.about_doc_scripting, R.string.about_doc_scripting_desc, "$website/scripting"),
            Triple(R.string.about_doc_encryption, R.string.about_doc_encryption_desc, "$website/encryption"),
            Triple(R.string.about_doc_troubleshooting, R.string.about_doc_troubleshooting_desc, "$website/troubleshooting"),
            Triple(R.string.about_doc_changelog, R.string.about_doc_changelog_desc, "$sourceUrl/blob/main/CHANGELOG.md"),
        )
        docs.forEach { (titleRes, descRes, url) ->
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            LinkRow(
                title = stringResource(titleRes),
                subtitle = stringResource(descRes),
                accent = accent,
            ) { openUrl(ctx, url) }
        }
    }

    // Support
    AboutCard(accent, stringResource(R.string.about_help_title)) {
        PrimaryButton(
            label = stringResource(R.string.about_join_support_channel),
            accent = accent,
        ) { openUrl(ctx, SUPPORT_IRC_URL) }
        Text(
            SUPPORT_CHANNEL_LABEL,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.45f),
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        LinkRow(
            title = stringResource(R.string.about_report_issue),
            subtitle = stringResource(R.string.about_report_issue_desc),
            accent = accent,
        ) { openUrl(ctx, "$sourceUrl/issues") }
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        LinkRow(
            title = stringResource(R.string.about_privacy_policy),
            subtitle = stringResource(R.string.about_privacy_policy_desc),
            accent = accent,
        ) { openUrl(ctx, "$website/privacy") }
    }

    CreditsCard(ctx, accent, sourceUrl)

    Spacer(Modifier.height(2.dp))

    // Footer
    Text(
        stringResource(R.string.about_footer_stack),
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.45f),
        textAlign = TextAlign.Center,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "GPLv3",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.45f),
        )
        Text("·", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.3f))
        Text(
            "GitHub",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = accent.copy(alpha = 0.85f),
            modifier = Modifier.focusHighlight().clickable { openUrl(ctx, sourceUrl) }
        )
        Text("·", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.3f))
        Text(
            WEBSITE_LABEL,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = accent.copy(alpha = 0.85f),
            modifier = Modifier.focusHighlight().clickable { openUrl(ctx, website) }
        )
    }
    Spacer(Modifier.height(4.dp))

    BoxlabsSignature()
}

/** ACTION_VIEW in a new task, swallowing the "no activity found" case. */
private fun openUrl(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
/**
 * Section container.
 */
@Composable
private fun AboutCard(
    accent: Color,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = CONTENT_MAX_WIDTH)
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CARD_BG)
    ) {
        Column(
            Modifier
                .padding(18.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Leadinng dot
                Box(
                    Modifier
                        .width(4.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(accent.copy(alpha = 0.8f))
                )
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = Color.White.copy(alpha = 0.62f),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            content()
        }
    }
}

/**
 * Flat accent-tinted action
 */
@Composable
private fun PrimaryButton(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .focusHighlight(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
    }
}

/** Title + description row */
@Composable
private fun LinkRow(
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .focusHighlight(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.92f)
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
        Text(
            "›",
            style = MaterialTheme.typography.titleMedium,
            color = accent.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreditsCard(ctx: Context, accent: Color, repoUrl: String) {
    val groups = remember { CREDITS.filter { it.members.isNotEmpty() } }

    AboutCard(accent, stringResource(R.string.about_credits_title)) {
        groups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    group.title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.5f)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    group.members.forEach { CreditChip(ctx, it, accent) }
                }
            }
        }

        Text(
            stringResource(R.string.about_credits_contribute),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = accent.copy(alpha = 0.85f),
            modifier = Modifier.focusHighlight().clickable { openUrl(ctx, repoUrl) }
        )
    }
}

@Composable
private fun CreditChip(ctx: Context, credit: Credit, accent: Color) {
    val hasLink = !credit.url.isNullOrBlank()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (hasLink) accent.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f))
            .border(
                1.dp,
                if (hasLink) accent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(50)
            )
            .then(
                if (hasLink) Modifier.focusHighlight(RoundedCornerShape(50)).clickable {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, credit.url.toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                } else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            credit.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.92f)
        )
        if (!credit.detail.isNullOrBlank()) {
            Text(
                " · ${credit.detail}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * Hero area with animated flask, bubbles, and logo
 */
@Composable
private fun FlaskHero(
    modifier: Modifier = Modifier,
    /** Null draws the flask on its own, for use as a signature rather than a hero. */
    logoSize: Dp? = 140.dp,
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

        val hasLogo = logoSize != null
        val logoSizePx = with(density) { (logoSize ?: 0.dp).toPx() }
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
                        val absorbedByLogo = hasLogo && distToLogo < logoSizePx * 0.25f
                        val escapedTop = !hasLogo && b.y < 0f
                        if (absorbedByLogo || escapedTop || b.y < -50f || b.y > hPx + 50f) {
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
                // No logo to fade against, so bubbles simply thin out as they near the
                // top of the box. Guards the divide below, which is zero-width at
                // logoSizePx == 0.
                if (!hasLogo) {
                    val topFade = (center.y / (hPx * 0.45f)).coerceIn(0f, 1f)
                    return baseAlpha * topFade
                }
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

            // The mist gradient that used to fill the gap between logo and flask is gone.
            // It was a full-width rect, so it was never bounded by the flask it was meant
            // to be rising from, and with the mark now sitting in the flask there is
            // nothing above it for the haze to sell.
        }

        // Logo overlay. The byline moved out to the caller so the flask can stand alone.
        Box(modifier = Modifier.fillMaxSize()) {
            if (logoSize != null) {
                Image(
                    painter = painterResource(R.drawable.hexdroid_logo),
                    contentDescription = stringResource(R.string.about_logo_desc),
                    modifier = Modifier
                        .size(logoSize)
                        .align(Alignment.TopCenter)
                        .padding(top = 2.dp)
                )
            }

            ImprovedFlask(
                modifier = Modifier
                    .size(flaskSize)
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp),
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
            .shadow(12.dp, RoundedCornerShape(0.dp))
            .clip(RoundedCornerShape(0.dp))
            .border(2.dp, borderColor, RoundedCornerShape(0.dp))
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
            contentDescription = stringResource(R.string.about_boxlabs_desc),
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
