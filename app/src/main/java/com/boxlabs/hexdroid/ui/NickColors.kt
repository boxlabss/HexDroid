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
 *
 * Generates a large palette where every entry has a visually distinct
 * hue *and* lightness, so even nicks that hash to nearby hues end up
 * with clearly different shades.
 *
 * Approach:
 * 1. Use golden-angle hue stepping (≈137.5°) for maximal hue spread.
 * 2. Alternate lightness across 3 bands so consecutive palette entries
 *    differ in both hue AND lightness.
 * 3. Vary saturation slightly per band for extra differentiation.
 * 4. Linear probing with a coprime step avoids clustering when the
 *    channel has many users.
 */
object NickColors {
    private const val PALETTE_SIZE = 192
    private const val GOLDEN_ANGLE_DEG = 137.50776f
    private const val PROBE_STEP = 37

    /**
     * Lightness/saturation bands.  Each band produces a different "shade"
     * feel — bright & vivid, medium & rich, light & soft — so even if
     * two nicks get the same hue region they look clearly different.
     */
    private data class Band(val lightness: Float, val saturation: Float)

    private val darkBgBands = listOf(
        Band(0.72f, 0.90f),   // vivid
        Band(0.58f, 0.78f),   // deeper / richer
        Band(0.82f, 0.65f),   // pastel / light
    )
    private val lightBgBands = listOf(
        Band(0.38f, 0.75f),   // dark & saturated
        Band(0.28f, 0.60f),   // very dark
        Band(0.48f, 0.55f),   // medium, muted
    )

    fun buildPalette(bgLum: Float): List<Color> {
        val bands = if (bgLum < 0.5f) darkBgBands else lightBgBands

        return List(PALETTE_SIZE) { i ->
            val hue = (i * GOLDEN_ANGLE_DEG) % 360f
            val band = bands[i % bands.size]
            // Small per-index saturation jitter to break up any residual patterns
            val satJitter = ((i * 7) % 5 - 2) * 0.02f
            Color.hsl(
                hue,
                (band.saturation + satJitter).coerceIn(0.35f, 0.95f),
                band.lightness
            )
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

        val sorted = baseNicks.distinct().sortedBy { it.lowercase() }

        for (nick in sorted) {
            val idx = pickIndex(nick, used, n)
            used[idx] = true
            out[nick.lowercase()] = palette[idx]
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
     * Better mixing for short strings (Murmur-like finaliser)
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