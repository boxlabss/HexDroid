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
 * Top-level entry point used by IrcCore / IrcMessageCrypto. Hides the cipher zoo
 * behind two simple operations: encrypt-for-target and decrypt-incoming. Either is
 * a no-op when no key is configured for the target.
 *
 * Lifetime: one E2eCodec per IrcClient (i.e. per network connection), constructed
 * from the shared [E2eKeyStore] singleton owned by the ViewModel. Cipher instances
 * are cached by raw key bytes so a hot channel doesn't re-init AES on every line.
 *
 * Thread-safety: all internal state is concurrent-safe. encrypt() and decrypt()
 * can be called from any thread - typical pattern has the IrcCore reader thread
 * calling decrypt while the user-input coroutine calls encrypt.
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
            }
        }

    /**
     * If a key is configured for [target], encrypt [plaintext] and return the wire
     * line (with prefix). Otherwise return [plaintext] unchanged.
     *
     * [selfNick] is the local user's current nick. It is needed so query (private
     * message) AAD can be made symmetric between the two endpoints - see
     * [aadContext]. For channel targets it is ignored.
     *
     * Note that the IRC-side caller is responsible for sanitising newlines and
     * carriage returns before calling - we don't re-validate.
     */
    fun encryptOutgoing(target: String, plaintext: String, selfNick: String): String {
        val entry = keyStore.get(networkId, target) ?: return plaintext
        return cipherFor(entry).encrypt(plaintext, aadContext(target, selfNick))
    }

    /**
     * Canonical AAD context for a [target].
     *
     * The AAD binds a ciphertext to its conversation so it can't be replayed into a
     * different one. The subtlety is that the two endpoints of a *query* see the
     * conversation under different target names: the sender addresses the recipient's
     * nick, the receiver sees the sender's nick.
     * For queries, bind to the *unordered pair* of the two nicks. Both endpoints
     * compute the same bytes regardless of direction. For channels the channel name is
     * already identical for everyone, so it is used as-is (and stays wire-compatible
     * with the previous format).
     *
     *   channel  ->  "#channel"                 (lowercased by the cipher)
     *   query    ->  "user1\u0000user2"           (the two nicks, lowercased + sorted)
     *
     * Nicks are lowercased with [java.util.Locale.ROOT] (locale-independent) and
     * compared by their natural string order; for the ASCII nicks IRC uses this is a
     * stable, cross-platform ordering.
     */
    private fun aadContext(target: String, selfNick: String): String {
        if (target.firstOrNull() in CHANNEL_PREFIXES) return target
        val peer = target.lowercase(java.util.Locale.ROOT)
        val me = selfNick.lowercase(java.util.Locale.ROOT)
        return if (peer <= me) "$peer\u0000$me" else "$me\u0000$peer"
    }

    /**
     * Result of an incoming decrypt attempt.
     *
     * - PASSTHROUGH: the wire didn't look encrypted; render [text] as cleartext.
     * - DECRYPTED: successfully decrypted; render [text] as the plaintext with
     *   an [scheme] padlock annotation.
     * - FAILED: the wire looked encrypted but decrypt failed (wrong key, tamper,
     *   replay across channels). Render the original wire text with a tamper
     *   indicator. We keep the wire text visible rather than swallowing it so
     *   the user can copy it out for diagnosis or relay to a working client.
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
