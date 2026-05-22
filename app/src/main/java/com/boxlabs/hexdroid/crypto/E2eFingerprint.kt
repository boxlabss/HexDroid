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

import java.security.MessageDigest

/**
 * Computes a short, human-comparable fingerprint of an E2E key for out-of-band
 * verification ("hey on Signal, what's your AGM fingerprint for #foo?").
 *
 * The fingerprint is SHA-256(scheme-tag || key) truncated to the first 5 bytes
 * (40 bits) and rendered as 8 base32 characters with a hyphen for readability:
 * e.g. `K4XR-T9BS`. 40 bits is enough to make a deliberate collision attack
 * infeasible (2^40 SHA-256 evaluations = days on a single GPU, but each candidate
 * would need to also encode a usable AES key chosen by an attacker who actually
 * controls the channel), while keeping the string short enough to compare over a
 * phone call.
 *
 * The scheme byte mixes into the digest so that the same raw bytes used with two
 * different schemes (which would technically be a misconfiguration) produce
 * different fingerprints, helping the user notice the mismatch.
 */
object E2eFingerprint {

    private val BASE32_ALPHABET = charArrayOf(
        'A','B','C','D','E','F','G','H','J','K','L','M','N','P','Q','R',
        'S','T','U','V','W','X','Y','Z','2','3','4','5','6','7','8','9'
    ) // Crockford-style: no 0/1/I/O to avoid visual ambiguity over voice.

    fun compute(scheme: E2eScheme, key: ByteArray): String {
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(byteArrayOf(scheme.ordinal.toByte()))
        sha.update(key)
        val digest = sha.digest()

        // Take 40 bits (5 bytes) and base32-encode into 8 chars (5 bits each).
        val bits = ((digest[0].toLong() and 0xff) shl 32) or
                   ((digest[1].toLong() and 0xff) shl 24) or
                   ((digest[2].toLong() and 0xff) shl 16) or
                   ((digest[3].toLong() and 0xff) shl 8)  or
                    (digest[4].toLong() and 0xff)

        val out = StringBuilder(9)
        for (i in 7 downTo 0) {
            val sym = ((bits shr (i * 5)) and 0x1f).toInt()
            out.append(BASE32_ALPHABET[sym])
            if (i == 4) out.append('-') // K4XR-T9BS
        }
        return out.toString()
    }
}
