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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.shadow
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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

        // Keep the tooltip within safe drawing areas, and also above the IME if it's open.
        val safeInsets = WindowInsets.safeDrawing.union(WindowInsets.ime).asPaddingValues()
        val safeLeftPx = with(density) { safeInsets.calculateLeftPadding(layoutDir).toPx() }
        val safeTopPx = with(density) { safeInsets.calculateTopPadding().toPx() }
        val safeRightPx = with(density) { safeInsets.calculateRightPadding(layoutDir).toPx() }
        val safeBottomPx = with(density) { safeInsets.calculateBottomPadding().toPx() }

        val primaryRect = step.target?.let { registry.targets[it] }
        val fallbackRect = step.fallbackTarget?.let { registry.targets[it] }

        // Some steps require scrolling / lazy composition to materialize their target bounds.
        val fallbackDelayMs = when (step.target) {
            TourTarget.NETWORKS_CONNECT_BUTTON,
            TourTarget.NETWORKS_AFTERNET_ITEM,
            TourTarget.SETTINGS_APPEARANCE_SECTION -> 900L
            else -> 350L
        }

        var showFallbackCard by remember(stepIndex, primaryRect) { mutableStateOf(false) }
        LaunchedEffect(stepIndex, primaryRect) {
            showFallbackCard = false
            if (primaryRect == null) {
                delay(fallbackDelayMs)
                showFallbackCard = true
            }
        }

        val targetRect: Rect? = primaryRect ?: if (showFallbackCard) fallbackRect else null
        var cardSize by remember { mutableStateOf(IntSize(0, 0)) }

        val padPx = with(density) { 10.dp.toPx() }

        // Pointer handling:
        // - Let taps/clicks on the tooltip card behave normally
        // - Taps inside the spotlight advance to the next step.
        // - Taps outside the spotlight skip/dismiss.
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
                    // Scrim + cutout (draw-only) ---
                    CanvasScrim(rect = targetRect, screenWpx = screenWpx, screenHpx = screenHpx)


                    // Gesture layer (behind tooltip card) ---
                    val tapRect = targetRect?.let {
                        Rect(
                            left = it.left - padPx,
                            top = it.top - padPx,
                            right = it.right + padPx,
                            bottom = it.bottom + padPx
                        )
                    }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(stepIndex, tapRect) {
                                detectTapGestures(
                                    onTap = { pos ->
                                        val r = tapRect
                                        if (r != null && r.contains(pos)) onNext() else onSkip()
                                    }
                                )
                            }
                    )

                    // Tooltip card
                    val usingFallback = step.target != null && primaryRect == null && showFallbackCard && fallbackRect != null
                    if (targetRect != null || showFallbackCard) {
                        val marginPx = with(density) { 16.dp.toPx() }
                        val gapPx = with(density) { 12.dp.toPx() }

                        val safeMinX = safeLeftPx + marginPx
                        val safeMaxX = (screenWpx - safeRightPx - marginPx)
                        val safeMinY = safeTopPx + marginPx
                        val safeMaxY = (screenHpx - safeBottomPx - marginPx)

                        val (xPx, yPx) = remember(targetRect, cardSize, screenWpx, screenHpx, safeMinX, safeMaxX, safeMinY, safeMaxY) {
                            val cw = cardSize.width.toFloat()
                            val ch = cardSize.height.toFloat()

                            // If constraints are super tight (small screens, large font), fall back to a zero-margin clamp.
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

                                val belowY = targetRect.bottom + gapPx
                                val aboveY = targetRect.top - gapPx - ch

                                val fitsBelow = belowY + ch <= (screenHpx - safeBottomPx - marginPx)
                                val desiredY = if (fitsBelow) belowY else aboveY
                                val clampedY = desiredY.coerceIn(minY, maxY)

                                clampedX to clampedY
                            }
                        }

                        val maxCardWidth = (maxW - 32.dp).coerceAtLeast(0.dp).coerceAtMost(420.dp)
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
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(step.title, style = MaterialTheme.typography.titleLarge)
                                Text(bodyText, style = MaterialTheme.typography.bodyMedium)

                                Text(
                                    "${stepIndex + 1} / $stepCount",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val action = step.action
                                    if (action != null && onAction != null && (!action.fallbackOnly || usingFallback)) {
                                        OutlinedButton(onClick = { onAction(action.id) }) { Text(action.label) }
                                    }
                                    TextButton(onClick = onSkip) { Text("Skip") }
                                    if (onBack != null) {
                                        OutlinedButton(onClick = onBack) { Text("Back") }
                                    }
                                    Button(onClick = onNext) { Text(if (stepIndex + 1 == stepCount) "Done" else "Next") }
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
    val padPx = with(density) { 10.dp.toPx() }
    val radiusPx = with(density) { 14.dp.toPx() }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            // Needed for BlendMode.Clear to punch a hole.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        drawRect(Color(0xB3000000))

        if (rect != null) {
            val r = Rect(
                left = rect.left - padPx,
                top = rect.top - padPx,
                right = rect.right + padPx,
                bottom = rect.bottom + padPx
            )

            // Clamp to screen bounds
            val clamped = Rect(
                left = r.left.coerceIn(0f, screenWpx),
                top = r.top.coerceIn(0f, screenHpx),
                right = r.right.coerceIn(0f, screenWpx),
                bottom = r.bottom.coerceIn(0f, screenHpx)
            )

            drawRoundRect(
                color = Color.Transparent,
                topLeft = androidx.compose.ui.geometry.Offset(clamped.left, clamped.top),
                size = androidx.compose.ui.geometry.Size(clamped.width, clamped.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx),
                blendMode = BlendMode.Clear
            )
        }
    }
}