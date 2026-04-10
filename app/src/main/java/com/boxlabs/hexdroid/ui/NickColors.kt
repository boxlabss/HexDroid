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
 * Nick colour assignment
 *
 * Both the sidebar and message nicks use the same stable hash ([colorForNick]) so a
 * nick always appears in the same colour everywhere.  The sidebar additionally runs a
 * lightweight forward-scan pass ([nicklistColors]) that detects when two alphabetically
 * adjacent nicks land within [MIN_HUE_GAP]° of each other and nudges the later one to
 * the opposite side of the wheel, guaranteeing no two consecutive sidebar entries share
 * a similar hue.  Because this nudge is applied only at display time and held in a
 * remember-cached map, it carries no persistent memory overhead.
 *
 * Palette: 45 hue slots spanning the full wheel while skipping two visually muddy zones:
 *   • yellow-green (58–91°): hard to read on both light and dark backgrounds
 *   • cyan         (168–204°): low contrast, visually confusable with blue/green
 */
object NickColors {

    /**
     * 45 hue slots — midpoints interpolated within each clean arc to halve the
     * spacing from ~12° to ~6°, doubling the effective palette without adding
     * any entries in the two muddy zones.
     *
     * Arc 1 — reds / oranges / ambers (0–50°):    9 slots, ~6° apart
     * Arc 2 — greens / teals          (91–163°):  13 slots, ~6° apart
     * Arc 3 — blues / purples / pinks (204–347°): 23 slots, ~6–7° apart
     *
     * Combined with 8 lightness/saturation bands: 45 × 8 = 360 unique colour
     * slots before any repeat (up from 24 × 8 = 192).
     */
    private val hueSlots = floatArrayOf(
        // Arc 1: reds → ambers (0–50°)
          0f,   6f,  12f,  18f,  24f,  30f,  36f,  43f,  50f,
        // Arc 2: chartreuse → teal (91–163°)
         91f,  97f, 103f, 109f, 115f, 121f, 127f, 133f, 139f, 145f, 151f, 157f, 163f,
        // Arc 3: azure → deep rose (204–347°)
        204f, 210f, 217f, 223f, 230f, 236f, 243f, 249f, 256f, 262f,
        269f, 275f, 282f, 288f, 295f, 301f, 308f, 314f, 321f, 327f, 334f, 340f, 347f,
    )

    // 11 is coprime with 45 (45 = 3²×5; gcd(11,45)=1) and gives near-golden-angle
    // jumps across the slot array, so consecutive hash indices land far apart.
    // Kept for reference; the hash function uses a multiplicative probe, not this stride.
    @Suppress("unused")
    private const val HUE_STRIDE = 11

    /**
     * Minimum hue distance (°, shortest arc) between two adjacent sidebar nicks
     * before the later one is nudged away.
     */
    private const val MIN_HUE_GAP = 40f

    /**
     * Rotation applied when a sidebar conflict is detected.  167° (not exactly 180°)
     * avoids accidentally landing on a hue that is perceptually very similar at a
     * different saturation/lightness, and keeps the nudged colour in a clearly
     * distinct region of the wheel.
     */
    private const val CONFLICT_NUDGE_DEG = 167f

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
     * Stable hash-based colour for [baseNick].
     *
     * This is the single source of truth used for message rows, query window titles,
     * and any other place where a nick needs a colour without access to the full sorted
     * nicklist.  The colour depends only on the nick string and the dark/light mode —
     * it never changes because of who else happens to be online.
     */
    fun colorForNick(baseNick: String, bgLum: Float): Color {
        val bands   = if (bgLum < 0.5f) darkBgBands else lightBgBands
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

    /**
     * Pre-compute sidebar colours for a sorted nicklist in a single O(n) pass.
     *
     * Each nick starts from its stable [colorForNick] hue.  A forward scan then checks
     * consecutive pairs: if two adjacent hues are within [MIN_HUE_GAP]° of each other
     * (shortest arc on the 360° wheel), the later nick is rotated by [CONFLICT_NUDGE_DEG]°
     * so it lands on a perceptually distinct hue while keeping the same saturation and
     * lightness band.
     *
     * The returned list is parallel to [sortedBaseNicks] — index N is the sidebar colour
     * for `sortedBaseNicks[N]`.  Build a `Map<String, Color>` once (inside `remember`)
     * for O(1) lookup during LazyColumn rendering.
     *
     * Memory: one `FloatArray(n)` for intermediate hues + the returned `List<Color>`,
     * both the same size as the input.  No persistent state is stored anywhere.
     *
     * For nicks that need no nudge the returned colour is identical to [colorForNick],
     * so sidebar and message colours match for the vast majority of nicks.
     */
    fun nicklistColors(sortedBaseNicks: List<String>, bgLum: Float): List<Color> {
        if (sortedBaseNicks.isEmpty()) return emptyList()

        val n = sortedBaseNicks.size

        // Step 1: compute the raw hue for every nick including its per-nick jitter,
        // so comparisons between neighbours use the same hue that will be rendered.
        val hues = FloatArray(n) { i -> rawHueForNick(sortedBaseNicks[i]) }

        // Step 2: forward scan — nudge any nick that is too close to its predecessor.
        for (i in 1 until n) {
            if (hueDist(hues[i], hues[i - 1]) < MIN_HUE_GAP) {
                hues[i] = (hues[i] + CONFLICT_NUDGE_DEG) % 360f
            }
        }

        // Step 3: apply saturation/lightness bands and per-nick jitter to produce Colors.
        val bands = if (bgLum < 0.5f) darkBgBands else lightBgBands
        return List(n) { i ->
            val nick      = sortedBaseNicks[i]
            val h         = mixHash(nick.lowercase().hashCode())
            val bandIdx   = positiveMod(mixHash(h xor 0x517cc1b7), bands.size)
            val band      = bands[bandIdx]
            val satJitter = (positiveMod(mixHash(h xor 0x45678901), 7) - 3) * 0.012f
            val lumJitter = (positiveMod(mixHash(h xor 0x6b43a9b5), 5) - 2) * 0.012f
            Color.hsl(
                hues[i],
                (band.saturation + satJitter).coerceIn(0.30f, 0.98f),
                (band.lightness  + lumJitter).coerceIn(0.10f, 0.93f),
            )
        }
    }

    /**
     * Raw hue (0–360°) for a nick, including per-nick jitter but before any sidebar
     * conflict nudging.  Used internally by [nicklistColors] to compare adjacent hues.
     */
    private fun rawHueForNick(baseNick: String): Float {
        val h      = mixHash(baseNick.lowercase().hashCode())
        val hueIdx = positiveMod(h * 0x9e3779b9.toInt(), hueSlots.size)
        val jitter = (positiveMod(mixHash(h xor 0x2f6a8b3c), 7) - 3) * 1.2f
        return (hueSlots[hueIdx] + jitter + 360f) % 360f
    }

    /** Shortest angular distance between two hues on the 360° wheel. */
    private fun hueDist(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
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