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

package com.boxlabs.hexdroid.ui.tour

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IntroTourOverlay(
    step: IntroTourStep,
    stepIndex: Int,
    stepCount: Int,
    registry: TourRegistry,
    onBack: (() -> Unit)?,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onAction: ((IntroTourActionId) -> Unit)? = null,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val layoutDir = LocalLayoutDirection.current

        val maxW = maxWidth
        val maxH = maxHeight

        val screenWpx = with(density) { maxW.toPx().coerceAtLeast(1f) }
        val screenHpx = with(density) { maxH.toPx().coerceAtLeast(1f) }

        val safeInsets = WindowInsets.safeDrawing.union(WindowInsets.ime).asPaddingValues()
        val safeLeftPx  = with(density) { safeInsets.calculateLeftPadding(layoutDir).toPx() }
        val safeTopPx   = with(density) { safeInsets.calculateTopPadding().toPx() }
        val safeRightPx = with(density) { safeInsets.calculateRightPadding(layoutDir).toPx() }
        val safeBottomPx = with(density) { safeInsets.calculateBottomPadding().toPx() }

        val primaryRect  = step.target?.let { registry.targets[it] }
        val fallbackRect = step.fallbackTarget?.let { registry.targets[it] }

        // Wait a little after step change for lazy/scrolling composables to settle
        // before deciding that the primary target is absent.
        val fallbackDelayMs = when (step.target) {
            TourTarget.NETWORKS_CONNECT_BUTTON,
            TourTarget.NETWORKS_AFTERNET_ITEM,
            TourTarget.SETTINGS_APPEARANCE_SECTION,
            TourTarget.SETTINGS_RUN_TOUR -> 1000L
            else -> 400L
        }

        var showFallbackCard by remember(stepIndex, primaryRect) { mutableStateOf(false) }
        LaunchedEffect(stepIndex, primaryRect) {
            showFallbackCard = false
            if (primaryRect == null) {
                delay(fallbackDelayMs)
                showFallbackCard = true
            }
        }

        // Animate spotlight rect smoothly between steps
        val targetRect: Rect? = primaryRect ?: if (showFallbackCard) fallbackRect else null

        var cardSize by remember { mutableStateOf(IntSize(0, 0)) }
        val padPx   = with(density) { 10.dp.toPx() }
        val gapPx   = with(density) { 12.dp.toPx() }
        val marginPx = with(density) { 16.dp.toPx() }

        Box(modifier = Modifier.fillMaxSize()) {
            // Scrim + cutout
            CanvasScrim(rect = targetRect, screenWpx = screenWpx, screenHpx = screenHpx)

            // Tap gesture layer: tap inside spotlight = next, outside = skip
            val tapRect = targetRect?.let {
                Rect(it.left - padPx, it.top - padPx, it.right + padPx, it.bottom + padPx)
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(stepIndex, tapRect) {
                        detectTapGestures { pos ->
                            val r = tapRect
                            if (r != null && r.contains(pos)) onNext() else onSkip()
                        }
                    }
            )

            // Tooltip card
            val usingFallback = step.target != null && primaryRect == null && showFallbackCard && fallbackRect != null
            if (targetRect != null || showFallbackCard) {
                val safeMinX = safeLeftPx + marginPx
                val safeMaxX = screenWpx - safeRightPx - marginPx
                val safeMinY = safeTopPx + marginPx
                val safeMaxY = screenHpx - safeBottomPx - marginPx

                val cardBelow = remember(targetRect, cardSize, screenWpx, screenHpx) {
                    if (targetRect == null) return@remember true
                    val ch = cardSize.height.toFloat()
                    val belowY = targetRect.bottom + gapPx
                    belowY + ch <= screenHpx - safeBottomPx - marginPx
                }

                val (xPx, yPx) = remember(targetRect, cardSize, screenWpx, screenHpx, safeMinX, safeMaxX, safeMinY, safeMaxY) {
                    val cw = cardSize.width.toFloat()
                    val ch = cardSize.height.toFloat()

                    val minX = if (safeMaxX - cw < safeMinX) safeLeftPx else safeMinX
                    val maxX = if (safeMaxX - cw < safeMinX) (screenWpx - safeRightPx - cw).coerceAtLeast(safeLeftPx) else (safeMaxX - cw)
                    val minY = if (safeMaxY - ch < safeMinY) safeTopPx else safeMinY
                    val maxY = if (safeMaxY - ch < safeMinY) (screenHpx - safeBottomPx - ch).coerceAtLeast(safeTopPx) else (safeMaxY - ch)

                    if (targetRect == null || cw <= 0f || ch <= 0f) {
                        val cx = ((minX + maxX) / 2f).coerceIn(minX, maxX)
                        val cy = ((minY + maxY) / 2f).coerceIn(minY, maxY)
                        cx to cy
                    } else {
                        val desiredX = targetRect.center.x - (cw / 2f)
                        val clampedX = desiredX.coerceIn(minX, maxX)
                        val belowY   = targetRect.bottom + gapPx
                        val aboveY   = targetRect.top - gapPx - ch
                        val desiredY = if (cardBelow) belowY else aboveY
                        val clampedY = desiredY.coerceIn(minY, maxY)
                        clampedX to clampedY
                    }
                }

                // Arrow pointing from card toward the spotlight
                if (targetRect != null && cardSize.width > 0 && cardSize.height > 0) {
                    val arrowSizePx = with(density) { 10.dp.toPx() }
                    val cardCenterX = xPx + cardSize.width / 2f
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val arrowX = cardCenterX.coerceIn(xPx + 24f, xPx + cardSize.width - 24f)
                        val path = Path()
                        if (cardBelow) {
                            // Arrow pointing up (card below spotlight)
                            val arrowY = yPx
                            path.moveTo(arrowX, arrowY - arrowSizePx)
                            path.lineTo(arrowX - arrowSizePx, arrowY)
                            path.lineTo(arrowX + arrowSizePx, arrowY)
                            path.close()
                        } else {
                            // Arrow pointing down (card above spotlight)
                            val arrowY = yPx + cardSize.height
                            path.moveTo(arrowX, arrowY + arrowSizePx)
                            path.lineTo(arrowX - arrowSizePx, arrowY)
                            path.lineTo(arrowX + arrowSizePx, arrowY)
                            path.close()
                        }
                        drawPath(path, Color.White.copy(alpha = 0.9f))
                    }
                }

                val maxCardWidth  = (maxW - 32.dp).coerceAtLeast(0.dp).coerceAtMost(420.dp)
                val maxCardHeight = (maxH - 32.dp).coerceAtLeast(0.dp)

                Surface(
                    tonalElevation = 6.dp,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier
                        .widthIn(max = maxCardWidth)
                        .heightIn(max = maxCardHeight)
                        .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                        .onSizeChanged { cardSize = it }
                        .shadow(8.dp, MaterialTheme.shapes.extraLarge)
                ) {
                    val bodyText = if (usingFallback && step.fallbackBody != null) step.fallbackBody else step.body
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Title row with close button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                step.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onSkip, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.skip),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        Text(bodyText, style = MaterialTheme.typography.bodyMedium)

                        // Action button (e.g. "Add AfterNET")
                        val action = step.action
                        if (action != null && onAction != null && (!action.fallbackOnly || usingFallback)) {
                            OutlinedButton(
                                onClick = { onAction(action.id) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(action.label) }
                        }

                        // Progress dots + prev/next row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Back
                            if (onBack != null) {
                                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), modifier = Modifier.size(18.dp))
                                }
                            } else {
                                Spacer(Modifier.size(36.dp))
                            }

                            // Progress dots
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                repeat(stepCount) { i ->
                                    Box(
                                        modifier = Modifier
                                            .size(if (i == stepIndex) 8.dp else 6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (i == stepIndex)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                            )
                                    )
                                }
                            }

                            // Next / Done
                            IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                                if (stepIndex + 1 == stepCount) {
                                    Icon(Icons.Default.Close, contentDescription = "Done", modifier = Modifier.size(18.dp))
                                } else {
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CanvasScrim(rect: Rect?, screenWpx: Float, screenHpx: Float) {
    val density = LocalDensity.current
    val padPx    = with(density) { 10.dp.toPx() }
    val radiusPx = with(density) { 14.dp.toPx() }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        drawRect(Color(0xCC000000))

        if (rect != null) {
            val r = Rect(
                left   = rect.left   - padPx,
                top    = rect.top    - padPx,
                right  = rect.right  + padPx,
                bottom = rect.bottom + padPx,
            )
            val clamped = Rect(
                left   = r.left.coerceIn(0f, screenWpx),
                top    = r.top.coerceIn(0f, screenHpx),
                right  = r.right.coerceIn(0f, screenWpx),
                bottom = r.bottom.coerceIn(0f, screenHpx),
            )
            // Highlight border ring
            drawRoundRect(
                color = Color.White.copy(alpha = 0.25f),
                topLeft = Offset(clamped.left - 2f, clamped.top - 2f),
                size = Size(clamped.width + 4f, clamped.height + 4f),
                cornerRadius = CornerRadius(radiusPx + 2f, radiusPx + 2f),
                style = Stroke(width = 2.5f),
            )
            // Punch hole
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(clamped.left, clamped.top),
                size = Size(clamped.width, clamped.height),
                cornerRadius = CornerRadius(radiusPx, radiusPx),
                blendMode = BlendMode.Clear,
            )
        }
    }
}