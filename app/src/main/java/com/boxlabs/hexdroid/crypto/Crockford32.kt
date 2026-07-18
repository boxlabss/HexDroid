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

package com.boxlabs.hexdroid.crypto

/**
 * Crockford-style base32 used for human-comparable safety numbers across every E2E
 * scheme. The per-key `+AGM`/`+OK` fingerprint ([E2eFingerprint]) and the `+AGE`
 * identity fingerprint ([com.boxlabs.hexdroid.crypto.AgeFingerprint]) both render
 * through here, so the verification UX (and the alphabet) stays identical and there is
 * exactly one copy of this logic.
 *
 * No 0/1/I/O in the alphabet, to stay unambiguous when read aloud over voice/Signal.
 * Symbols are emitted MSB-first, 5 bits at a time.
 */
object Crockford32 {

    val ALPHABET = charArrayOf(
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M', 'N', 'P', 'Q', 'R',
        'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '2', '3', '4', '5', '6', '7', '8', '9',
    )

    /**
     * Encode the first [chars] base32 symbols (5 bits each, MSB-first) of [data],
     * inserting a '-' every [group] symbols (0 = no grouping).
     * e.g. encode(digest, 8, 4) -> "K4XR-T9BS"; encode(fp, 16, 4) -> "K4XR-T9BS-MN2P-QW7V".
     */
    fun encode(data: ByteArray, chars: Int, group: Int = 0): String {
        val out = StringBuilder(chars + if (group > 0) (chars - 1) / group else 0)
        var buf = 0L
        var bits = 0
        var i = 0
        var emitted = 0
        while (emitted < chars) {
            if (bits < 5) { buf = (buf shl 8) or (data[i++].toLong() and 0xff); bits += 8 }
            val sym = ((buf shr (bits - 5)) and 0x1f).toInt()
            bits -= 5
            out.append(ALPHABET[sym])
            emitted++
            if (group > 0 && emitted < chars && emitted % group == 0) out.append('-')
        }
        return out.toString()
    }
}
