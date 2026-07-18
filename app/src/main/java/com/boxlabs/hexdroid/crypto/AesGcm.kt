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
 * The one raw AES-256-GCM seal/open used by every scheme. [AesGcmCipher] (the +AGM 1:1
 * wire cipher) and [com.boxlabs.hexdroid.crypto.JcaAgePrimitives] (the +AGE
 * group/identity layer) both route their GCM through here, so there is exactly one copy
 * of the AEAD call, caller supplies key, 12-byte nonce, and AAD; nonce derivation and
 * wire framing live in the callers.
 */
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
object AesGcm {
    const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (aad.isNotEmpty()) c.updateAAD(aad)
        return c.doFinal(plaintext)
    }
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (aad.isNotEmpty()) c.updateAAD(aad)
        c.doFinal(ciphertext)
    } catch (_: Throwable) { null }
}
