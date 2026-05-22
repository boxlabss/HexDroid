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
 * Abstract end-to-end cipher contract. Each scheme (AES-GCM, Blowfish, future ratchet-
 * based) implements this interface and is dispatched by [E2eCodec] based on the wire
 * prefix of the incoming line.
 *
 * Implementations must be thread-safe with respect to encrypt/decrypt being called
 * concurrently from different IRC events (typical pattern: the IrcCore reader thread
 * calls decrypt, the user-input coroutine calls encrypt). [AesGcmCipher] satisfies
 * this by using `javax.crypto.Cipher` instances per call - cheap, hardware-accelerated.
 */
internal interface E2eCipher {
    val scheme: E2eScheme

    /**
     * Encrypt [plaintext] and return the full wire-form line including the scheme
     * prefix, ready to drop into PRIVMSG / NOTICE.
     *
     * [aadContext] is the canonical conversation identifier (computed by [E2eCodec]:
     * the channel name for channels, or the sorted nick-pair for queries). Schemes
     * that support authenticated encryption mix it into the auth tag so a ciphertext
     * intended for one conversation cannot be replayed into another. Schemes without
     * AAD support (Blowfish) ignore it.
     */
    fun encrypt(plaintext: String, aadContext: String): String

    /**
     * Attempt to decrypt [wireText] which must start with this scheme's prefix. Returns
     * the recovered plaintext, or null if:
     *   - the prefix is wrong
     *   - the payload is malformed (bad base64, wrong version, too short)
     *   - the auth tag fails (wrong key, tampered, replayed-across-conversations)
     *
     * [aadContext] must be the same canonical conversation identifier the sender used
     * (see [encrypt]). Callers should treat null as "leave the wire line visible with
     * a tamper hint".
     */
    fun decrypt(wireText: String, aadContext: String): String?
}
