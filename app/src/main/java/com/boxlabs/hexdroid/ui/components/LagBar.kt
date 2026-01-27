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

package com.boxlabs.hexdroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun LagBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    val p = progress.coerceIn(0f, 1f)
    val cs = MaterialTheme.colorScheme

    val track = cs.surfaceVariant.copy(alpha = 0.6f)
    val indicatorBrush = remember(cs.primary, cs.tertiary) {
        Brush.horizontalGradient(listOf(cs.primary, cs.tertiary))
    }

    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = size.height / 2f
        // Track
        drawRoundRect(
            color = track,
            cornerRadius = CornerRadius(r, r)
        )

        // Indicator
        if (p > 0f) {
            drawRoundRect(
                brush = indicatorBrush,
                size = Size(width = size.width * p, height = size.height),
                cornerRadius = CornerRadius(r, r)
            )
        }
    }
}
