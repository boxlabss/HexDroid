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

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for the `+AGM` wire scheme.
 *
 * Wire format inside the base64 blob:
 *
 *     byte  0       version  (0x01)
 *     bytes 1..12   nonce    (12 random bytes per message)
 *     bytes 13..N   ciphertext
 *     bytes N..N+15 GCM auth tag (16 bytes)
 *
 * The full IRC line is `+AGM <base64(version || nonce || ciphertext || tag)>`.
 *
 * The target name (channel or nick, lowercased + UTF-8 encoded) is fed as Additional
 * Authenticated Data so a ciphertext can't be replayed across channels: if Alice's
 * `#secret` message is re-injected into `#public`, the AAD differs and the GCM tag
 * fails, the receiver renders a tampering indicator instead of a confusing decrypt.
 *
 * Nonce uniqueness with a fresh-per-message 96-bit random value:
 *   collision probability after 2^32 messages = ~2^-32 ≈ negligible.
 * The cipher does NOT counter-derive nonces because counter state can be lost across
 * process restarts and a counter reset under the same key is catastrophic for GCM
 * (an attacker who sees two ciphertexts with the same nonce can recover the keystream
 * and forge arbitrary messages). Random nonces avoid that failure mode entirely.
 */
internal class AesGcmCipher(private val key: ByteArray) : E2eCipher {
    init {
        require(key.size == 32) { "AES-256-GCM requires a 32-byte key, got ${key.size}" }
    }

    override val scheme: E2eScheme = E2eScheme.AGM

    private val secretKey = SecretKeySpec(key, "AES")
    private val rng = SecureRandom()

    override fun encrypt(plaintext: String, aadContext: String): String {
        val nonce = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
        // AAD binds the conversation into the auth tag - replaying #foo's ciphertext
        // into #bar (or one query into another) fails the integrity check.
        // lowercase(Locale.ROOT) is mandatory here, NOT the default-locale
        // lowercase(): on a Turkish-locale device "#IRC".lowercase() yields "#ırc"
        // (dotless i), so the AAD bytes would differ from an English device's "#irc"
        // and two users in the same channel on different locales would fail
        // every decrypt. Locale.ROOT does Unicode locale-independent case folding.
        cipher.updateAAD(aadContext.lowercase(java.util.Locale.ROOT).toByteArray(StandardCharsets.UTF_8))
        val ct = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        val out = ByteArray(1 + NONCE_LEN + ct.size)
        out[0] = VERSION
        System.arraycopy(nonce, 0, out, 1, NONCE_LEN)
        System.arraycopy(ct, 0, out, 1 + NONCE_LEN, ct.size)

        return "${scheme.wirePrefix} " + Base64.encodeToString(out, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    override fun decrypt(wireText: String, aadContext: String): String? {
        val prefix = "${scheme.wirePrefix} "
        if (!wireText.startsWith(prefix)) return null
            val b64 = wireText.substring(prefix.length).trim()
            // Tolerate both padded and unpadded base64. We emit unpadded (NO_PADDING)
            val raw = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (raw.size < 1 + NONCE_LEN + TAG_BYTES) return null
                if (raw[0] != VERSION) return null

                    val nonce = raw.copyOfRange(1, 1 + NONCE_LEN)
                    val ctAndTag = raw.copyOfRange(1 + NONCE_LEN, raw.size)

                    // Computed once and reused for both the AAD and the replay-cache key so the two
                    // can never disagree about how the conversation id is folded.
                    val convLower = aadContext.lowercase(java.util.Locale.ROOT)

                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
                    cipher.updateAAD(convLower.toByteArray(StandardCharsets.UTF_8))
                    val plaintext = try {
                        // doFinal throws AEADBadTagException on a bad tag (wrong key, tamper, or
                        // wrong AAD i.e. replayed-across-conversations). Return null and let the
                        // caller surface a "decryption failed" hint to the user rather than
                        // swallowing.
                        String(cipher.doFinal(ctAndTag), StandardCharsets.UTF_8)
                    } catch (_: Throwable) {
                        return null
                    }

                    // Replay guard
                    val replayKey = convLower + "\u0000" +
                    Base64.encodeToString(nonce, Base64.NO_WRAP or Base64.NO_PADDING)
                    synchronized(seenNonces) {
                        if (seenNonces.put(replayKey, PRESENT) != null) {
                            // Already seen for this conversation -> replayed/duplicate. Drop it.
                            return null
                        }
                    }
                    return plaintext
    }

    /**
     * Bounded LRU of recently authenticated `conversation\u0000nonce` keys. access-order =
     * true + [removeEldestEntry] gives insertion with automatic eviction of the least
     * recently seen entry once [REPLAY_CACHE_MAX] is exceeded, so memory stays flat no
     * matter how long the session runs. Guarded by `synchronized` because decrypt() can be
     * invoked from multiple threads.
     */
    private val seenNonces = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
        size > REPLAY_CACHE_MAX
    }

    companion object {
        private const val VERSION: Byte = 0x01
            private const val NONCE_LEN = 12
            private const val TAG_BITS = 128
            private const val TAG_BYTES = TAG_BITS / 8

            // Upper bound on remembered nonces per cipher (i.e. per key, across all
            // conversations that use it). 2048 covers very heavy use while costing well under
            // ~200 KB worst case; the oldest entry is evicted past this.
            private const val REPLAY_CACHE_MAX = 2048
            // Sentinel value for the set-like LinkedHashMap (we only care about key presence).
            private const val PRESENT = true
    }
}
