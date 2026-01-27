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

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * nick colour mapping
 * Uses golden-angle hue scrambling for perceptual spread
 * Better hash mixing for short/low-entropy nicks
 * Large palette (128) to reduce collisions
 * Adjustable lightness/saturation based on background
 * Linear probing with a larger coprime step to spread assignments
 */
object NickColors {
    private const val PALETTE_SIZE = 128          // Larger palette = fewer collisions
    private const val GOLDEN_ANGLE_DEG = 137.50776f
    private const val PROBE_STEP = 37             // Larger coprime step spreads better

    /**
     * Build a palette suitable for the current background luminance.
     * Dark BG - brighter/saturated colours
     * Light BG  - darker/muted colours
     */
    fun buildPalette(bgLum: Float): List<Color> {
        val lightness = if (bgLum < 0.5f) 0.75f else 0.40f   // Slightly brighter on dark, darker on light
        val saturation = if (bgLum < 0.5f) 0.85f else 0.65f   // Punchier on dark backgrounds
        return List(PALETTE_SIZE) { i ->
            val hue = (i * GOLDEN_ANGLE_DEG) % 360f
            Color.hsl(hue, saturation, lightness)
        }
    }

    /**
     * Assign unique colours for a set of base nicks.
     * Deterministic (sorted input + stable probing).
     */
    fun assignColors(baseNicks: List<String>, palette: List<Color>): Map<String, Color> {
        if (baseNicks.isEmpty()) return emptyMap()

        val n = palette.size
        val used = BooleanArray(n)
        val out = LinkedHashMap<String, Color>(baseNicks.size)

        // Sort for stable order across recompositions
        val sorted = baseNicks.distinct().sortedBy { it.lowercase() }

        for (nick in sorted) {
            val idx = pickIndex(nick, used, n)
            used[idx] = true
            out[nick.lowercase()] = palette[idx]  // Key by lowercase for case-insensitive lookup
        }
        return out
    }

    /**
     * Fallback for nicks not in the assignment map.
     */
    fun colorFromHash(baseNick: String, palette: List<Color>): Color {
        val n = palette.size
        val mixedHash = mixHash(baseNick.lowercase().hashCode())
        val idx = positiveMod(mixedHash, n)
        return palette[idx]
    }

    private fun pickIndex(baseNick: String, used: BooleanArray, n: Int): Int {
        val hash = mixHash(baseNick.lowercase().hashCode())
        var idx = positiveMod(hash, n)

        var tries = 0
        while (tries < n && used[idx]) {
            idx = (idx + PROBE_STEP) % n
            tries++
        }
        return idx
    }

    /**
     * Better mixing for short strings (simple Murmur-like hash mixer)
     */
    private fun mixHash(h: Int): Int {
        var hash = h
        hash = hash xor (hash ushr 16)
        hash *= 0x85ebca6b.toInt()
        hash = hash xor (hash ushr 13)
        hash *= 0xc2b2ae35.toInt()
        hash = hash xor (hash ushr 16)
        return hash
    }

    private fun positiveMod(x: Int, m: Int): Int {
        val r = x % m
        return if (r < 0) r + m else r
    }
}