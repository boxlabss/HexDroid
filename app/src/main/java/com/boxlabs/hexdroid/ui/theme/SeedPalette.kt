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

package com.boxlabs.hexdroid.ui.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Accent roles derived from one seed colour, as ARGB ints. Primary keeps the seed's hue,
 * secondary is a muted version of it and tertiary sits 60 degrees round the wheel. Lightness is
 * adjusted until each role reads against its "on" colour and the background. Pure JVM.
 */
internal object SeedPalette {

    data class Roles(
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val secondary: Int,
        val onSecondary: Int,
        val secondaryContainer: Int,
        val onSecondaryContainer: Int,
        val tertiary: Int,
        val onTertiary: Int,
        val tertiaryContainer: Int,
        val onTertiaryContainer: Int,
        /** Gradient behind the send button. */
        val sendStart: Int,
        val sendEnd: Int,
        /** Icon colour on the send gradient. */
        val onSend: Int,
    )

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val NEAR_BLACK = 0xFF0D1117.toInt()

    /** Roles for [seed] on a dark or light theme whose background is [background]. */
    fun roles(seed: Int, dark: Boolean, background: Int): Roles {
        val (h, s0, _) = toHsl(seed)
        // A grey seed stays nearly grey rather than picking up the hue of 0 degrees.
        val s = if (s0 < 0.08f) 0.08f else s0.coerceIn(0.35f, 0.9f)
        val h3 = (h + 60f) % 360f
        return if (dark) {
            val onPrimary = fromHsl(h, s * 0.5f, 0.12f)
            val primary = lightenUntil(fromHsl(h, s, 0.72f), listOf(onPrimary, background), 4.5)
            val secondary = lightenUntil(fromHsl(h, s * 0.3f, 0.70f), listOf(background), 4.5)
            val tertiary = lightenUntil(fromHsl(h3, s * 0.55f, 0.72f), listOf(background), 4.5)
            val send = fromHsl(h, s, 0.50f)
            Roles(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = fromHsl(h, s * 0.6f, 0.24f),
                onPrimaryContainer = fromHsl(h, s * 0.6f, 0.88f),
                secondary = secondary,
                onSecondary = fromHsl(h, s * 0.3f, 0.12f),
                secondaryContainer = fromHsl(h, s * 0.25f, 0.18f),
                onSecondaryContainer = fromHsl(h, s * 0.25f, 0.85f),
                tertiary = tertiary,
                onTertiary = fromHsl(h3, s * 0.5f, 0.12f),
                tertiaryContainer = fromHsl(h3, s * 0.45f, 0.22f),
                onTertiaryContainer = fromHsl(h3, s * 0.45f, 0.86f),
                sendStart = send,
                sendEnd = fromHsl((h + 25f) % 360f, s, 0.56f),
                onSend = onColourFor(send),
            )
        } else {
            val primary = darkenUntil(fromHsl(h, s, 0.42f), listOf(WHITE, background), 4.5)
            val secondary = darkenUntil(fromHsl(h, s * 0.3f, 0.40f), listOf(WHITE, background), 4.5)
            val tertiary = darkenUntil(fromHsl(h3, s * 0.55f, 0.40f), listOf(WHITE, background), 4.5)
            val send = fromHsl(h, s, 0.50f)
            Roles(
                primary = primary,
                onPrimary = WHITE,
                primaryContainer = fromHsl(h, s * 0.8f, 0.91f),
                onPrimaryContainer = fromHsl(h, s * 0.7f, 0.18f),
                secondary = secondary,
                onSecondary = WHITE,
                secondaryContainer = fromHsl(h, s * 0.3f, 0.90f),
                onSecondaryContainer = fromHsl(h, s * 0.3f, 0.16f),
                tertiary = tertiary,
                onTertiary = WHITE,
                tertiaryContainer = fromHsl(h3, s * 0.5f, 0.91f),
                onTertiaryContainer = fromHsl(h3, s * 0.5f, 0.18f),
                sendStart = send,
                sendEnd = fromHsl((h + 25f) % 360f, s, 0.56f),
                onSend = onColourFor(send),
            )
        }
    }

    /** White or near-black, whichever reads better on [colour]. */
    fun onColourFor(colour: Int): Int =
        if (contrast(WHITE, colour) >= contrast(NEAR_BLACK, colour)) WHITE else NEAR_BLACK

    /** WCAG contrast ratio between two colours. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val x = v / 255.0
            return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch((c shr 16) and 0xff) + 0.7152 * ch((c shr 8) and 0xff) + 0.0722 * ch(c and 0xff)
    }

    private fun lightenUntil(colour: Int, against: List<Int>, ratio: Double): Int {
        var (h, s, l) = toHsl(colour)
        var c = colour
        while (l < 0.95f && against.any { contrast(c, it) < ratio }) {
            l += 0.02f
            c = fromHsl(h, s, l)
        }
        return c
    }

    private fun darkenUntil(colour: Int, against: List<Int>, ratio: Double): Int {
        var (h, s, l) = toHsl(colour)
        var c = colour
        while (l > 0.08f && against.any { contrast(c, it) < ratio }) {
            l -= 0.02f
            c = fromHsl(h, s, l)
        }
        return c
    }

    /** Hue in degrees, saturation and lightness in 0..1. */
    fun toHsl(argb: Int): Triple<Float, Float, Float> {
        val r = ((argb shr 16) and 0xff) / 255f
        val g = ((argb shr 8) and 0xff) / 255f
        val b = (argb and 0xff) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        if (mx == mn) return Triple(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return Triple(h, s, l)
    }

    fun fromHsl(h: Float, s: Float, l: Float): Int {
        val sat = s.coerceIn(0f, 1f)
        val lig = l.coerceIn(0f, 1f)
        val c = (1f - abs(2f * lig - 1f)) * sat
        val hp = ((h % 360f + 360f) % 360f) / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = lig - c / 2f
        fun to255(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (to255(r1) shl 16) or (to255(g1) shl 8) or to255(b1)
    }
}
