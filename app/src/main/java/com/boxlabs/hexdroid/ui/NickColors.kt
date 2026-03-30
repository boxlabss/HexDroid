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
 * Nick colour per-channel sorted assignment
 *
 * Each nick's colour is determined by its alphabetical rank in the current
 * channel's nicklist
 *
 * Every nick in a channel is guaranteed a distinct hue (up to 18 x 8 = 144 unique slots)
 * Adjacent nicks in the list receive hues ≈180° apart (phi-stride of 7 through
 * 18 slots) so the nicklist looks maximally spread-out.
 * Nicks not found in the current nicklist (e.g. server messages, nicks that have
 * left) fall back to [colorForNick] which is a stable pure-hash function.
 */
object NickColors {

    /**
     * 18 hues (degrees) chosen for maximum human perceptual separation.
     * Skips yellow-green (55–88°) and cyan (170–200°) muddle zones.
     */
    private val hueSlots = floatArrayOf(
          0f,   // red
         18f,   // red-orange
         35f,   // orange
         52f,   // amber / gold
         95f,   // chartreuse green  (after yellow-green muddle)
        115f,   // green
        133f,   // emerald
        152f,   // teal-green
        163f,   // teal            (before cyan muddle)
        208f,   // sky / azure     (after cyan muddle)
        226f,   // cornflower
        243f,   // blue
        260f,   // blue-indigo
        275f,   // indigo
        292f,   // violet
        308f,   // purple
        325f,   // magenta
        342f,   // rose / deep pink
    )

    // Stride through hue slots. Must be coprime with hueSlots.size (18).
    // 7 is coprime with 18 and gives phi-like spread: nicks 0,1,2,3 get
    // hues 0°, 133°, 266°, 39° roughly evenly distributed across the wheel.
    private const val HUE_STRIDE = 7

    private data class Band(val lightness: Float, val saturation: Float)

    /** Dark background (near-black): bright vivid colours. */
    private val darkBgBands = arrayOf(
        Band(0.72f, 0.92f),  // bright vivid
        Band(0.60f, 0.87f),  // deep rich
        Band(0.80f, 0.74f),  // bright soft
        Band(0.65f, 0.88f),  // mid vivid
        Band(0.56f, 0.94f),  // deep saturated
        Band(0.76f, 0.70f),  // soft medium
        Band(0.68f, 0.80f),  // balanced mid
        Band(0.82f, 0.84f),  // palest vivid
    )

    /** Light background (white/cream): darker rich colours. */
    private val lightBgBands = arrayOf(
        Band(0.28f, 0.82f),  // dark vivid
        Band(0.18f, 0.68f),  // very dark
        Band(0.38f, 0.55f),  // medium muted
        Band(0.22f, 0.90f),  // dark saturated
        Band(0.33f, 0.47f),  // dark smoky
        Band(0.13f, 0.78f),  // near-black vivid
        Band(0.26f, 0.62f),  // balanced dark
        Band(0.42f, 0.72f),  // medium vivid
    )

    /**
     * Assign a colour to [baseNick] based on its position in [sortedBaseNicks].
     *
     * [sortedBaseNicks] should be the channel's nicklist stripped of mode prefixes
     * (@ + % & ~) and lowercased, sorted alphabetically.  The caller computes and
     * caches this list for the nicklist UI anyway, so there is no extra allocation.
     *
     * Hue slots are assigned with stride [HUE_STRIDE] (coprime with 18) so that
     * alphabetically adjacent nicks are ≈ 133° apart rather than next-door.
     * After cycling through all 18 hues, the band increments, giving 144 distinct
     * slots before any repeat.
     *
     * Falls back to [colorForNick] if [baseNick] is not found (left the channel,
     * server message sender, etc.).
     */
    fun colorForNickInChannel(
        baseNick: String,
        sortedBaseNicks: List<String>,
        bgLum: Float,
    ): Color {
        val idx = sortedBaseNicks.indexOfFirst { it.equals(baseNick, ignoreCase = true) }
        if (idx < 0) return colorForNick(baseNick, bgLum)

        val bands = if (bgLum < 0.5f) darkBgBands else lightBgBands
        val hueIdx  = (idx * HUE_STRIDE) % hueSlots.size
        val bandIdx = (idx / hueSlots.size) % bands.size
        return Color.hsl(hueSlots[hueIdx], bands[bandIdx].saturation, bands[bandIdx].lightness)
    }

    /**
     * Stable hash-based colour used as a fallback for nicks not in the current
     * channel nicklist (server buffers, query windows, nicks that have left).
     */
    fun colorForNick(baseNick: String, bgLum: Float): Color {
        val bands = if (bgLum < 0.5f) darkBgBands else lightBgBands
        val h       = mixHash(baseNick.lowercase().hashCode())
        val hueIdx  = positiveMod(h * 0x9e3779b9.toInt(), hueSlots.size)
        val bandIdx = positiveMod(mixHash(h xor 0x517cc1b7), bands.size)
        val band    = bands[bandIdx]
        val hueJitter = (positiveMod(mixHash(h xor 0x2f6a8b3c), 7) - 3) * 1.2f
        val satJitter = (positiveMod(mixHash(h xor 0x45678901), 7) - 3) * 0.012f
        val lumJitter = (positiveMod(mixHash(h xor 0x6b43a9b5), 5) - 2) * 0.012f
        return Color.hsl(
            (hueSlots[hueIdx] + hueJitter + 360f) % 360f,
            (band.saturation + satJitter).coerceIn(0.30f, 0.98f),
            (band.lightness  + lumJitter).coerceIn(0.10f, 0.93f),
        )
    }

    /** Murmur3 finaliser — good avalanche on short strings. */
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