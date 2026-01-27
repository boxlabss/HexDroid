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

/**
 * Strip common IRC formatting control codes.
 *
 * This is intentionally conservative (keeps normal whitespace) and removes:
 * - mIRC colours (\u0003 + optional fg/bg digits)
 * - common style toggles (bold/italic/underline/reverse/strikethrough/monospace/reset)
 * - other C0 control chars (except \n/\r/\t)
 */
fun stripIrcFormatting(input: String): String {
    if (input.isEmpty()) return input
    val out = StringBuilder(input.length)
    var i = 0
    while (i < input.length) {
        val c = input[i]
        when (c) {
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
