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

package com.boxlabs.hexdroid

// Compose imports are used by AnsiStyleState.toSpanStyle() — fully-qualified references
// are used inline so this file stays free of @Composable annotations and Activity context.

/**
 * Strip common IRC formatting control codes and ANSI escape sequences.
 *
 * Removes:
 * - mIRC colours (\u0003 + optional fg/bg digits)
 * - 24-bit hex colours (\u0004 + up to 6 hex digits)
 * - common style toggles (bold/italic/underline/reverse/strikethrough/monospace/reset)
 * - ANSI CSI escape sequences (\u001b[ ... final-byte) covers SGR colour/style codes
 * - other ANSI/C0 control chars (except \n/\r/\t)
 */
fun stripIrcFormatting(input: String): String {
    if (input.isEmpty()) return input
    val out = StringBuilder(input.length)
    var i = 0
    while (i < input.length) {
        val c = input[i]
        when (c) {
            '\u001b' -> { // ESC — start of an ANSI escape sequence
                i++
                if (i < input.length && input[i] == '[') {
                    // CSI sequence: ESC [ <params> <final>  where final is @–~ (0x40–0x7E)
                    i++
                    while (i < input.length && input[i].code !in 0x40..0x7E) i++
                    if (i < input.length) i++ // consume final byte
                }
                // Non-CSI escape sequences (e.g. ESC c): just drop the ESC; the next char
                // will be handled on the next iteration (usually harmless printable).
                continue
            }
            '\u0003' -> { // mIRC color: \x03[fg][,bg]
                i++
                var n = 0
                while (i < input.length && n < 2 && input[i].isDigit()) { i++; n++ }
                if (i < input.length && input[i] == ',') {
                    i++
                    n = 0
                    while (i < input.length && n < 2 && input[i].isDigit()) { i++; n++ }
                }
                continue
            }
            '\u0004' -> { // 24-bit hex colour: \x04RRGGBB[,RRGGBB]
                i++
                var n = 0
                while (i < input.length && n < 6 &&
                       (input[i].isDigit() || input[i].lowercaseChar() in 'a'..'f')) { i++; n++ }
                if (i < input.length && input[i] == ',') {
                    i++
                    n = 0
                    while (i < input.length && n < 6 &&
                           (input[i].isDigit() || input[i].lowercaseChar() in 'a'..'f')) { i++; n++ }
                }
                continue
            }
            '\u0002', // bold
            '\u001D', // italic
            '\u001F', // underline
            '\u0016', // reverse
            '\u001E', // strikethrough (common)
            '\u0011', // monospace (some clients)
            '\u000F'  // reset
            -> {
                i++
                continue
            }
            else -> {
                // Drop other C0 control chars except common whitespace.
                if (c.code < 32 && c != '\n' && c != '\r' && c != '\t') {
                    i++
                    continue
                }
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

// ANSI SGR rendering

/**
 * Standard ANSI 8-color palette (codes 30–37 / 40–47 foreground/background).
 * These are the classic VT100/xterm colors used by virtually every terminal.
 */
private val ANSI_STANDARD: IntArray = intArrayOf(
    0xFF000000.toInt(), // 0  Black
    0xFFAA0000.toInt(), // 1  Red
    0xFF00AA00.toInt(), // 2  Green
    0xFFAA5500.toInt(), // 3  Yellow (dark/olive)
    0xFF0000AA.toInt(), // 4  Blue
    0xFFAA00AA.toInt(), // 5  Magenta
    0xFF00AAAA.toInt(), // 6  Cyan
    0xFFAAAAAA.toInt(), // 7  White (light grey)
)

/**
 * Bright ANSI 8-color palette (codes 90–97 / 100–107).
 */
private val ANSI_BRIGHT: IntArray = intArrayOf(
    0xFF555555.toInt(), // 0  Bright Black (dark grey)
    0xFFFF5555.toInt(), // 1  Bright Red
    0xFF55FF55.toInt(), // 2  Bright Green
    0xFFFFFF55.toInt(), // 3  Bright Yellow
    0xFF5555FF.toInt(), // 4  Bright Blue
    0xFFFF55FF.toInt(), // 5  Bright Magenta
    0xFF55FFFF.toInt(), // 6  Bright Cyan
    0xFFFFFFFF.toInt(), // 7  Bright White
)

/**
 * xterm 256-color palette — indices 0–255.
 *
 * 0–7:   Standard colors (same as ANSI_STANDARD above)
 * 8–15:  Bright/high-intensity colors (same as ANSI_BRIGHT above)
 * 16–231: 6×6×6 color cube: index = 16 + 36×r + 6×g + b  (r,g,b ∈ 0..5)
 * 232–255: Greyscale ramp from dark to light
 */
private val ANSI_256: IntArray by lazy {
    IntArray(256).also { p ->
        // 0-7: standard
        for (i in 0..7) p[i] = ANSI_STANDARD[i]
        // 8-15: bright
        for (i in 0..7) p[i + 8] = ANSI_BRIGHT[i]
        // 16-231: 6x6x6 cube
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        for (r in 0..5) for (g in 0..5) for (b in 0..5) {
            val idx = 16 + 36 * r + 6 * g + b
            p[idx] = (0xFF000000.toInt()) or (levels[r] shl 16) or (levels[g] shl 8) or levels[b]
        }
        // 232-255: greyscale
        for (i in 0..23) {
            val v = 8 + i * 10
            p[232 + i] = (0xFF000000.toInt()) or (v shl 16) or (v shl 8) or v
        }
    }
}

internal data class AnsiStyleState(
    var fg: Int? = null,         // packed ARGB, null = default
    var bg: Int? = null,         // packed ARGB, null = default
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var reverse: Boolean = false,
    var strikethrough: Boolean = false,
) {
    fun reset() { fg = null; bg = null; bold = false; italic = false; underline = false; reverse = false; strikethrough = false }
    fun hasAnyStyle() = fg != null || bg != null || bold || italic || underline || reverse || strikethrough
    fun snapshot() = copy()
}

internal data class AnsiRun(val text: String, val style: AnsiStyleState)

/**
 * Parse a string containing ANSI SGR escape sequences into a list of styled runs.
 *
 * Handles:
 *  - SGR 0: reset
 *  - SGR 1/22: bold on/off
 *  - SGR 3/23: italic on/off
 *  - SGR 4/24: underline on/off
 *  - SGR 7/27: reverse on/off
 *  - SGR 9/29: strikethrough on/off
 *  - SGR 30–37 / 90–97: standard/bright foreground
 *  - SGR 40–47 / 100–107: standard/bright background
 *  - SGR 38;5;n / 48;5;n: 256-colour fg/bg
 *  - SGR 38;2;r;g;b / 48;2;r;g;b: 24-bit RGB fg/bg
 *  - SGR 39 / 49: default fg/bg
 *
 * Non-SGR escape sequences (cursor movement etc.) are silently consumed.
 * Other C0 control codes are dropped (except \n \r \t).
 */
internal fun parseAnsiRuns(input: String): List<AnsiRun> {
    if (input.isEmpty()) return emptyList()

    val out = mutableListOf<AnsiRun>()
    val buf = StringBuilder()
    val st = AnsiStyleState()

    fun flush() {
        if (buf.isNotEmpty()) {
            out += AnsiRun(buf.toString(), st.snapshot())
            buf.setLength(0)
        }
    }

    var i = 0
    while (i < input.length) {
        val c = input[i]
        when {
            c == '\u001b' && i + 1 < input.length && input[i + 1] == '[' -> {
                // CSI sequence: ESC [ <params> <final>
                flush()
                i += 2 // skip ESC [
                val paramStart = i
                while (i < input.length && input[i].code !in 0x40..0x7E) i++
                val finalByte = if (i < input.length) input[i].also { i++ } else null

                if (finalByte == 'm') {
                    // SGR — parse semicolon-separated params
                    val paramStr = input.substring(paramStart, i - 1)
                    val params = if (paramStr.isBlank()) listOf(0)
                                 else paramStr.split(';').mapNotNull { it.trim().toIntOrNull() }
                    var pi = 0
                    while (pi < params.size) {
                        when (val p = params[pi]) {
                            0                -> st.reset()
                            1                -> st.bold = true
                            2                -> { /* dim — ignore, not widely used in IRC */ }
                            3                -> st.italic = true
                            4                -> st.underline = true
                            7                -> st.reverse = true
                            9                -> st.strikethrough = true
                            22               -> st.bold = false
                            23               -> st.italic = false
                            24               -> st.underline = false
                            27               -> st.reverse = false
                            29               -> st.strikethrough = false
                            in 30..37        -> st.fg = ANSI_STANDARD[p - 30]
                            38               -> {
                                when (params.getOrNull(pi + 1)) {
                                    5 -> { // 256-colour
                                        val n = params.getOrNull(pi + 2) ?: 0
                                        st.fg = ANSI_256.getOrElse(n) { ANSI_STANDARD[7] }
                                        pi += 2
                                    }
                                    2 -> { // 24-bit RGB
                                        val r = params.getOrNull(pi + 2) ?: 0
                                        val g = params.getOrNull(pi + 3) ?: 0
                                        val b = params.getOrNull(pi + 4) ?: 0
                                        st.fg = (0xFF000000.toInt()) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
                                        pi += 4
                                    }
                                }
                            }
                            39               -> st.fg = null
                            in 40..47        -> st.bg = ANSI_STANDARD[p - 40]
                            48               -> {
                                when (params.getOrNull(pi + 1)) {
                                    5 -> {
                                        val n = params.getOrNull(pi + 2) ?: 0
                                        st.bg = ANSI_256.getOrElse(n) { ANSI_STANDARD[0] }
                                        pi += 2
                                    }
                                    2 -> {
                                        val r = params.getOrNull(pi + 2) ?: 0
                                        val g = params.getOrNull(pi + 3) ?: 0
                                        val b = params.getOrNull(pi + 4) ?: 0
                                        st.bg = (0xFF000000.toInt()) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
                                        pi += 4
                                    }
                                }
                            }
                            49               -> st.bg = null
                            in 90..97        -> st.fg = ANSI_BRIGHT[p - 90]
                            in 100..107      -> st.bg = ANSI_BRIGHT[p - 100]
                        }
                        pi++
                    }
                }
                // Non-SGR CSI sequences (cursor movement etc.) are silently consumed above.
            }
            c == '\u001b' -> {
                // Non-CSI escape (e.g. ESC c = full reset, ESC M = reverse index).
                // Drop the ESC; let the next character be handled normally.
                i++
            }
            c.code < 0x20 && c != '\n' && c != '\r' && c != '\t' -> {
                i++ // drop other C0 controls
            }
            else -> {
                buf.append(c)
                i++
            }
        }
    }
    flush()
    return out
}

internal fun AnsiStyleState.toSpanStyle(): androidx.compose.ui.text.SpanStyle {
    val rawFg = if (reverse) bg else fg
    val rawBg = if (reverse) fg else bg
    return androidx.compose.ui.text.SpanStyle(
        color      = rawFg?.let { androidx.compose.ui.graphics.Color(it.toLong() and 0xFFFFFFFFL) }
                     ?: androidx.compose.ui.graphics.Color.Unspecified,
        background = rawBg?.let { androidx.compose.ui.graphics.Color(it.toLong() and 0xFFFFFFFFL) }
                     ?: androidx.compose.ui.graphics.Color.Unspecified,
        fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
        fontStyle  = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
        textDecoration = when {
            underline && strikethrough -> androidx.compose.ui.text.style.TextDecoration.combine(
                listOf(androidx.compose.ui.text.style.TextDecoration.Underline,
                       androidx.compose.ui.text.style.TextDecoration.LineThrough)
            )
            underline     -> androidx.compose.ui.text.style.TextDecoration.Underline
            strikethrough -> androidx.compose.ui.text.style.TextDecoration.LineThrough
            else          -> null
        },
    )
}