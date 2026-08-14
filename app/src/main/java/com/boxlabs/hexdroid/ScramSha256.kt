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

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

/** Minimum PBKDF2 iteration count mandated by RFC 7677 for SCRAM-SHA-256. */
private const val SCRAM_MIN_ITERATIONS = 4096

/** Maximum server nonce length we accept (defensive bound against malformed/malicious servers). */
private const val SCRAM_MAX_NONCE_LENGTH = 512

sealed class ScramNext {
    data class SendClientFinal(val clientFinal: String) : ScramNext()
    data class Done(val verified: Boolean) : ScramNext()
}

class ScramSha256Client(
    username: String,
    password: String,
    private val clientNonce: String
) {
    // RFC 5802 s5.1: both the authcid and the password are SASLprep'd before use.
    // The server derived its stored verifier from the prepared password, so skipping
    // this makes every non-ASCII password fail with an unexplainable 904.
    // Throws SaslPrepException on a prohibited codepoint; the caller aborts SASL.
    private val username: String = SaslPrep.prepare(username)
    private val password: String = SaslPrep.prepare(password)

    private val gs2Header = "n,,"
    private val clientFirstBare = "n=${saslName(username)},r=$clientNonce"
    private val clientFirst = gs2Header + clientFirstBare

    private var serverFirst: String? = null
    private var expectedServerSigB64: String? = null

    fun clientFirstMessage(): String = clientFirst

    fun onServerMessage(msg: String): ScramNext {
        if (serverFirst == null) {
            serverFirst = msg
            val attrs = parseAttrs(msg)
            val nonce = attrs["r"] ?: return ScramNext.Done(false)

            // Reject excessively long nonces from buggy/malicious servers.
            if (nonce.length > SCRAM_MAX_NONCE_LENGTH) return ScramNext.Done(false)

            val saltB64 = attrs["s"] ?: return ScramNext.Done(false)
            val iter = attrs["i"]?.toIntOrNull() ?: return ScramNext.Done(false)

            // RFC 7677 mandates a minimum of 4096 iterations for SCRAM-SHA-256.
            // Accepting fewer would reduce the PBKDF2 security margin to near-zero.
            if (iter < SCRAM_MIN_ITERATIONS) return ScramNext.Done(false)

            // The server nonce is our nonce plus the server's own appended randomness.
            // A server that echoes ours back unchanged has contributed no entropy, which
            // RFC 5802 s5.1 forbids ("the server MUST append its own nonce").
            if (!nonce.startsWith(clientNonce) || nonce.length <= clientNonce.length) {
                return ScramNext.Done(false)
            }

            val salt = B64.decode(saltB64)
            val saltedPassword = hi(password, salt, iter)

            val clientKey = hmac(saltedPassword, "Client Key".toByteArray(StandardCharsets.UTF_8))
            val storedKey = sha256(clientKey)

            val cbindInput = gs2Header.toByteArray(StandardCharsets.UTF_8)
            val cbind = B64.encode(cbindInput)

            val clientFinalWithoutProof = "c=$cbind,r=$nonce"
            val authMessage = clientFirstBare + "," + serverFirst + "," + clientFinalWithoutProof

            val clientSignature = hmac(storedKey, authMessage.toByteArray(StandardCharsets.UTF_8))
            val proof = xorBytes(clientKey, clientSignature)
            val proofB64 = B64.encode(proof)

            val serverKey = hmac(saltedPassword, "Server Key".toByteArray(StandardCharsets.UTF_8))
            val serverSig = hmac(serverKey, authMessage.toByteArray(StandardCharsets.UTF_8))
            expectedServerSigB64 = B64.encode(serverSig)

            return ScramNext.SendClientFinal(clientFinalWithoutProof + ",p=$proofB64")
        }

        val attrs = parseAttrs(msg)
        if (attrs.containsKey("e")) return ScramNext.Done(false)
        val v = attrs["v"] ?: return ScramNext.Done(false)
        // Constant-time comparison: although in normal operation the server is trusted,
        // SCRAM's mutual-authentication design requires this comparison to be timing-safe
        // so a malicious server can't probe for the expected signature byte-by-byte by
        // measuring how long different `v=` values take to be rejected.
        val expected = expectedServerSigB64 ?: return ScramNext.Done(false)
        return ScramNext.Done(constantTimeEquals(v, expected))
    }

    /** Constant-time string comparison to avoid leaking comparison-position via timing. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    private fun parseAttrs(s: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        s.split(",").forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) out[part.substring(0, idx)] = part.substring(idx + 1)
        }
        return out
    }

    private fun saslName(name: String): String =
        name.replace("=", "=3D").replace(",", "=2C")

    /**
     * PBKDF2-HMAC-SHA256, computed directly over the UTF-8 bytes of the password.
     *
     * NOT PBEKeySpec + SecretKeyFactory: PBEKeySpec takes a CharArray and leaves the
     * char-to-byte conversion to the provider. Providers disagree - some use UTF-8,
     * the PKCS#5 lineage uses only the low byte of each char - so a password with any
     * character above U+007F derives a different key depending on which provider the
     * device happens to ship. RFC 5802 defines Hi() over the UTF-8 encoding of the
     * SASLprep'd password, so we encode it ourselves and the result is identical on
     * every device.
     *
     * dkLen is one hash block (32 bytes), so the outer PBKDF2 loop runs exactly once.
     */
    private fun hi(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val pw = password.toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pw, "HmacSHA256"))

        // U1 = HMAC(pw, salt || INT(1))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val out = u.copyOf()

        for (i in 2..iterations) {
            u = mac.doFinal(u)
            for (j in out.indices) out[j] = out[j] xor u[j]
        }
        java.util.Arrays.fill(pw, 0)
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    private fun xorBytes(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size)
        for (i in a.indices) out[i] = a[i] xor b[i]
        return out
    }
}
