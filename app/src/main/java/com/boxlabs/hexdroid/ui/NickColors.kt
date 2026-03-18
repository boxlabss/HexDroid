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
 * Nick colour assignment.
 *
 * Every nick gets a colour derived purely from its own name — two independent
 * hash axes drive hue and band (lightness + saturation), so the result is a
 * pure function of the nick string and the theme brightness.
 *
 * This replaces an earlier van-der-Corput rank-based approach which, although
 * it gave a guaranteed minimum hue gap across the current nicklist, changed
 * every nick's colour whenever anyone joined or left the channel because ranks
 * shifted.
 *
 * Stability beats theoretical optimality here: a nick should always look the
 * same regardless of who else is present.  The Murmur-like finaliser gives
 * good avalanche on short strings, so nicks with similar names (e.g. "bot1"
 * and "bot2") still land on well-separated hues in practice.
 */
object NickColors {

    private data class Band(val lightness: Float, val saturation: Float)

    // Dark backgrounds: L 0.57–0.80, S 0.72–0.94 (bright vivid / deep rich / bright soft).
    private val darkBgBands = listOf(
        Band(0.72f, 0.92f),   // bright vivid  — electric highlight
        Band(0.60f, 0.88f),   // deep rich     — jewel / punchy
        Band(0.80f, 0.76f),   // bright soft   — airy pastel-vivid
        Band(0.66f, 0.86f),   // mid vivid     — warm saturated
        Band(0.57f, 0.94f),   // deep saturated — darkest allowed, max chroma
        Band(0.76f, 0.72f),   // soft medium   — lightest allowed, gentle S
    )
    private val lightBgBands = listOf(
        Band(0.28f, 0.82f),   // dark & vivid
        Band(0.18f, 0.68f),   // very dark
        Band(0.44f, 0.58f),   // medium muted
        Band(0.22f, 0.92f),   // dark & saturated
        Band(0.36f, 0.48f),   // dark muted / smoky
        Band(0.13f, 0.78f),   // near-black vivid
    )

    /**
     * Returns a stable colour for [baseNick] that depends only on the nick name
     * and [bgLum].  Safe to call for any nick at any time — no channel state needed.
     */
    fun colorForNick(baseNick: String, bgLum: Float): Color {
        val bands = if (bgLum < 0.5f) darkBgBands else lightBgBands
        val nick  = baseNick.lowercase()
        val h     = mixHash(nick.hashCode())

        // Fibonacci/golden-ratio scramble: multiplying by 0x9e3779b9 (the 32-bit
        // approximation of 2^32 / phi) maps the Murmur-mixed hash to a uniformly
        // spread position on the hue wheel regardless of how similar the raw hash
        // values are.  Without this step, nicks whose Java hashCode() values happen
        // to cluster (e.g. short 4-letter words) land on adjacent hues.
        val hueSeed = h * 0x9e3779b9.toInt()
        val hue = positiveMod(hueSeed, 3600) / 10f

        // Band: second independent hash derivation so hue and brightness vary
        // orthogonally — two nicks with similar hues still differ in lightness.
        val band      = bands[positiveMod(mixHash(h xor 0x517cc1b7), bands.size)]
        val satJitter = (positiveMod(mixHash(h xor 0x45678901), 7) - 3) * 0.015f
        val lumJitter = (positiveMod(mixHash(h xor 0x6b43a9b5), 5) - 2) * 0.015f

        return Color.hsl(
            hue,
            (band.saturation + satJitter).coerceIn(0.30f, 0.98f),
            (band.lightness  + lumJitter).coerceIn(0.10f, 0.93f),
        )
    }

    /** Murmur-like finaliser — good avalanche for short strings. */
    private fun mixHash(h: Int): Int {
        var x = h
        x = x xor (x ushr 16)
        x *= 0x85ebca6b.toInt()
        x = x xor (x ushr 13)
        x *= 0xc2b2ae35.toInt()
        x = x xor (x ushr 16)
        return x
    }

    private fun positiveMod(x: Int, m: Int): Int {
        val r = x % m
        return if (r < 0) r + m else r
    }
}