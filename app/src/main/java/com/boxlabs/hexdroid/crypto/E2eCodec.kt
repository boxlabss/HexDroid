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
 * Entry point for E2E: encrypt for a target and decrypt incoming text, each a no-op without a key.
 * One per connection, built from the ViewModel's [E2eKeyStore]; ciphers are cached by key.
 * Thread-safe.
 */
class E2eCodec(
    private val networkId: String,
    private val keyStore: E2eKeyStore,
) {

    /**
     * Cache of (scheme + key bytes) -> instantiated cipher. Cipher construction
     * is cheap on modern Android (hardware AES) but caching saves a few microseconds
     * per message on busy channels and, more importantly, gives us one place to
     * eventually plug in per-key counters / metrics if we want them.
     */
    private val cipherCache = java.util.concurrent.ConcurrentHashMap<E2eKeyStore.Entry, E2eCipher>()

    private fun cipherFor(entry: E2eKeyStore.Entry): E2eCipher =
        cipherCache.computeIfAbsent(entry) { e ->
            when (e.scheme) {
                E2eScheme.AGM      -> AesGcmCipher(e.key)
                E2eScheme.BLOWFISH -> BlowfishCipher(e.key)
                // +AGE is a group/handshake scheme carried by AgeChannel/AgeWire, never
                // stored as a 1:1 E2eKeyStore entry, so this branch is unreachable here;
                // it exists only to keep the `when` exhaustive after registering +AGE.
                E2eScheme.AGE      -> error("+AGE is carried by AgeChannel, not the 1:1 E2eCodec")
            }
        }

    /**
     * Encrypt [plaintext] for [target] when it has a key, else return it unchanged. [selfNick]
     * makes a query's AAD the same on both ends (see [aadContext]). The caller strips CR/LF first.
     */
    fun encryptOutgoing(target: String, plaintext: String, selfNick: String): String {
        val entry = keyStore.get(networkId, target) ?: return plaintext
        return cipherFor(entry).encrypt(plaintext, aadContext(target, selfNick))
    }

    /**
     * AAD binding a ciphertext to its conversation. A channel uses its name; a query uses the two
     * nicks, lowercased (Locale.ROOT) and sorted, joined by \u0000, since each side addresses the
     * other by a different name.
     */
    private fun aadContext(target: String, selfNick: String): String {
        if (target.firstOrNull() in CHANNEL_PREFIXES) return target
        val peer = target.lowercase(java.util.Locale.ROOT)
        val me = selfNick.lowercase(java.util.Locale.ROOT)
        return if (peer <= me) "$peer\u0000$me" else "$me\u0000$peer"
    }

    /**
     * Result of an incoming decrypt: PASSTHROUGH (not encrypted), DECRYPTED (show [text] with a
     * [scheme] badge) or FAILED (show the wire text with a tamper indicator, so it can still be
     * copied).
     */
    enum class Outcome { PASSTHROUGH, DECRYPTED, FAILED }
    data class Result(val text: String, val scheme: E2eScheme?, val outcome: Outcome)

    fun decryptIncoming(target: String, wireText: String, selfNick: String): Result {
        val detected = E2eScheme.detect(wireText)
            ?: return Result(wireText, null, Outcome.PASSTHROUGH)

        val entry = keyStore.get(networkId, target)
            ?: return Result(wireText, detected, Outcome.FAILED)

        if (entry.scheme != detected) {
            // Configured scheme differs from what arrived on the wire - the peer
            // is using a different mode than us. Surface the raw line; user can
            // reconfigure if it's intentional.
            return Result(wireText, detected, Outcome.FAILED)
        }

        val pt = cipherFor(entry).decrypt(wireText, aadContext(target, selfNick))
            ?: return Result(wireText, detected, Outcome.FAILED)

        return Result(pt, detected, Outcome.DECRYPTED)
    }

    /** Used by the management UI for displaying the channel's safety number. */
    fun fingerprintFor(target: String): String? {
        val entry = keyStore.get(networkId, target) ?: return null
        return E2eFingerprint.compute(entry.scheme, entry.key)
    }

    companion object {
        // Standard IRC channel sigils. Matches the spec; intentionally NOT the server-advertised
        // CHANTYPES, because the AAD must be computed identically by every client
        // regardless of what a particular server announces.
        private val CHANNEL_PREFIXES = setOf('#', '&', '+', '!')
    }
}
