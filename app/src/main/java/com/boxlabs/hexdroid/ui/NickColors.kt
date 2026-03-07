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

/**
 * nick colour mapping
 *
 * Generates a large palette where every entry is perceptually distinct.
 *
 * Approach:
 * 1. Use golden-angle hue stepping (≈137.5°) for maximal hue spread.
 * 2. Cycle through 6 lightness/saturation bands (instead of 3) so that
 *    nicks with nearby hues land in very different shade bands.
 * 3. Bands alternate between vivid, dark, pastel, deep, airy and punchy
 *    extremes — consecutive entries differ in both hue AND brightness.
 * 4. Linear probing with a coprime step avoids clustering when a channel
 *    has many users.
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

    // Six bands with strongly varying lightness and saturation so that even nicks
    // whose hues are close look clearly different from each other.
    // Bands deliberately alternate between bright/dark/pastel extremes so that
    // consecutive palette entries (which differ by ~137.5° in hue) also differ
    // strongly in perceived brightness — avoiding the "all same shade" look.
    private val darkBgBands = listOf(
        Band(0.72f, 0.92f),   // vivid & bright
        Band(0.48f, 0.88f),   // deep / dark & punchy
        Band(0.87f, 0.58f),   // very light pastel
        Band(0.60f, 0.78f),   // medium saturated
        Band(0.92f, 0.45f),   // near-white / airy
        Band(0.38f, 0.95f),   // dark & high-saturation
    )
    private val lightBgBands = listOf(
        Band(0.28f, 0.82f),   // dark & vivid
        Band(0.18f, 0.68f),   // very dark
        Band(0.44f, 0.58f),   // medium muted
        Band(0.22f, 0.92f),   // dark & saturated
        Band(0.36f, 0.48f),   // dark muted / smoky
        Band(0.13f, 0.78f),   // near-black vivid
    )

    fun buildPalette(bgLum: Float): List<Color> {
        val bands = if (bgLum < 0.5f) darkBgBands else lightBgBands

        return List(PALETTE_SIZE) { i ->
            val hue = (i * GOLDEN_ANGLE_DEG) % 360f
            val band = bands[i % bands.size]
            // Small per-index jitter so nicks in the same band still differ subtly
            val satJitter = ((i * 11) % 7 - 3) * 0.015f
            val lumJitter = ((i * 13) % 5 - 2) * 0.015f
            Color.hsl(
                hue,
                (band.saturation + satJitter).coerceIn(0.30f, 0.98f),
                (band.lightness + lumJitter).coerceIn(0.10f, 0.93f)
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