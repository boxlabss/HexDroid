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
 * An end-to-end cipher, dispatched by [E2eCodec] on the wire prefix. Must be thread-safe: decrypt
 * runs on the reader thread while encrypt runs from input.
 */
internal interface E2eCipher {
    val scheme: E2eScheme

    /**
     * Encrypt [plaintext] into a full wire line with the scheme prefix. [aadContext] identifies the
     * conversation (see [E2eCodec]); authenticated schemes bind it into the tag so a ciphertext
     * can't be replayed elsewhere. Blowfish ignores it.
     */
    fun encrypt(plaintext: String, aadContext: String): String

    /**
     * Decrypt [wireText], or null for a wrong prefix, a malformed payload or a failed tag.
     * [aadContext] must match the sender's. Callers show the wire text with a tamper hint on null.
     */
    fun decrypt(wireText: String, aadContext: String): String?
}
