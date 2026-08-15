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

import java.text.Normalizer

/**
 * Thrown when a SASL identity or password contains a codepoint SASLprep forbids.
 * Callers abort the exchange and surface a message rather than putting a
 * differently-normalised credential on the wire and getting an opaque 904 back.
 */
class SaslPrepException(message: String) : IllegalArgumentException(message)

/**
 * SASLprep (RFC 4013), the stringprep profile RFC 5802 (SCRAM) and RFC 4616 (PLAIN)
 * both require to be applied to the authcid, authzid and password before use.
 *
 * The server stores a SCRAM verifier derived from the *prepared* password.
 * If it prepared and we don't, every non-ASCII password fails with a plain
 * "authentication failed" that the user cannot debug, the credential is correct, the
 * bytes just differ.
 * Scope: this implements the mapping, normalisation and prohibited-output steps.
 * The bidi rules (RFC 3454 s6) are enforced only in their cheap form. a string may
 * not mix RandALCat and LCat characters, and one that contains RandALCat must both
 * start and end with one. Unassigned-codepoint checking (s7, "stored strings") is
 * deliberately skipped: we are preparing query strings, where RFC 4013 permits it.
 */
object SaslPrep {

    /** Prepare [s] for use as a SASL password (allows the empty string). */
    fun prepare(s: String): String {
        if (s.isEmpty()) return s
        if (isPureAscii(s)) {
            // ASCII still has prohibited members: C0 controls and DEL.
            for (c in s) {
                if (c.code < 0x20 || c.code == 0x7F) {
                    throw SaslPrepException("control character U+%04X is not allowed".format(c.code))
                }
            }
            return s
        }

        // Step 1 (RFC 4013 s2.1): map non-ASCII space to SPACE, map "commonly mapped
        // to nothing" to nothing.
        val mapped = buildString(s.length) {
            for (c in s) {
                when {
                    // B.1 first: U+200B is listed in BOTH B.1 and C.1.2, and the order
                    // decides whether it becomes a space or vanishes. Server-side
                    // stringprep implementations (PostgreSQL's SASLprep, the common Java
                    // ones) apply B.1 first, so a password containing a zero-width space
                    // must be prepared the same way or the derived key won't match.
                    isMappedToNothing(c) -> Unit
                    isNonAsciiSpace(c) -> append(' ')
                    else -> append(c)
                }
            }
        }

        // Step 2 (s2.2): Unicode normalisation form KC.
        val normalized = Normalizer.normalize(mapped, Normalizer.Form.NFKC)

        // Step 3 (s2.3): prohibited output.
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            prohibitedReason(cp)?.let {
                throw SaslPrepException("U+%04X is not allowed in a SASL credential: %s".format(cp, it))
            }
            i += Character.charCount(cp)
        }

        // Step 4 (s2.4): bidirectional characters.
        checkBidi(normalized)

        return normalized
    }

    private fun isPureAscii(s: String): Boolean {
        for (c in s) if (c.code > 0x7F) return false
        return true
    }

    /**
     * RFC 3454 table C.1.2, non-ASCII space characters. U+200B is in the table but is
     * handled by [isMappedToNothing] first (see the mapping step), so the range stops
     * at U+200A here to keep the two tables from disagreeing.
     */
    private fun isNonAsciiSpace(c: Char): Boolean = when (c) {
        '\u00A0', '\u1680', '\u202F', '\u205F', '\u3000' -> true
        in '\u2000'..'\u200A' -> true
        else -> false
    }

    /** RFC 3454 table B.1, characters commonly mapped to nothing. */
    private fun isMappedToNothing(c: Char): Boolean = when (c) {
        '\u00AD', '\u034F', '\u1806', '\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF' -> true
        in '\u180B'..'\u180D' -> true
        in '\uFE00'..'\uFE0F' -> true
        else -> false
    }

    /**
     * Returns a reason when [cp] is in one of the prohibited tables
     * (RFC 3454 C.1.2, C.2.1, C.2.2, C.3-C.9), or null when it is allowed.
     */
    private fun prohibitedReason(cp: Int): String? = when {
        cp < 0x20 || cp == 0x7F -> "control character"
        cp in 0x0080..0x009F -> "control character"
        // NB: 200C/200D/2060/FEFF are B.1 "mapped to nothing" and are removed by step 1,
        // so they are not repeated here as prohibited output.
        cp == 0x06DD || cp == 0x070F || cp == 0x180E ||
            cp == 0x2028 || cp == 0x2029 -> "control character"
        cp in 0x2061..0x2063 -> "control character"
        cp in 0x206A..0x206F -> "control character"
        cp in 0xFFF9..0xFFFC -> "control character"
        cp in 0x1D173..0x1D17A -> "control character"
        isNonAsciiSpace(cp.toChar2()) -> "non-ASCII space"
        cp in 0xE000..0xF8FF || cp in 0xF0000..0xFFFFD || cp in 0x100000..0x10FFFD -> "private use"
        cp in 0xFDD0..0xFDEF -> "non-character"
        (cp and 0xFFFE) == 0xFFFE -> "non-character"
        cp in 0xD800..0xDFFF -> "surrogate"
        cp == 0xFFFD -> "inappropriate for plain text"
        cp in 0x2FF0..0x2FFB -> "ideographic description character"
        cp == 0x0340 || cp == 0x0341 || cp == 0x200E || cp == 0x200F ||
            cp in 0x202A..0x202E || cp in 0x206A..0x206F -> "change display / deprecated"
        cp in 0xE0001..0xE0001 || cp in 0xE0020..0xE007F -> "tagging character"
        else -> null
    }

    /** Safe narrowing for the BMP-only space table above. */
    private fun Int.toChar2(): Char = if (this in 0..0xFFFF) this.toChar() else '\u0000'

    private fun checkBidi(s: String) {
        var hasRandAL = false
        var hasL = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            when (Character.getDirectionality(cp)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> hasRandAL = true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> hasL = true
                else -> Unit
            }
            i += Character.charCount(cp)
        }
        if (!hasRandAL) return
        if (hasL) throw SaslPrepException("string mixes right-to-left and left-to-right characters")
        val firstDir = Character.getDirectionality(s.codePointAt(0))
        val lastCp = s.codePointBefore(s.length)
        val lastDir = Character.getDirectionality(lastCp)
        val ok = { d: Byte ->
            d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        }
        if (!ok(firstDir) || !ok(lastDir)) {
            throw SaslPrepException("right-to-left string must start and end with a right-to-left character")
        }
    }
}
