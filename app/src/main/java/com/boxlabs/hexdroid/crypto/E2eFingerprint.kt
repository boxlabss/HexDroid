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
 * A short fingerprint of an E2E key for out-of-band comparison: SHA-256(scheme tag || key), first
 * 40 bits, as 8 base32 characters (e.g. `K4XR-T9BS`). Matching it with another key costs about 2^40
 * hashes. Rendered through [Crockford32].
 */
object E2eFingerprint {

    fun compute(scheme: E2eScheme, key: ByteArray): String {
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(byteArrayOf(scheme.ordinal.toByte()))
        sha.update(key)
        // First 40 bits -> 8 base32 chars, grouped 4-4: identical output to the
        // previous inline encoder (verified byte-for-byte over random inputs).
        return Crockford32.encode(sha.digest(), chars = 8, group = 4)
    }
}
