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

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

sealed class ScramNext {
    data class SendClientFinal(val clientFinal: String) : ScramNext()
    data object ExpectServerFinal : ScramNext()
    data class Done(val verified: Boolean) : ScramNext()
}

class ScramSha256Client(
    private val username: String,
    private val password: String,
    private val clientNonce: String
) {
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
            val saltB64 = attrs["s"] ?: return ScramNext.Done(false)
            val iter = attrs["i"]?.toIntOrNull() ?: return ScramNext.Done(false)
            if (!nonce.startsWith(clientNonce)) return ScramNext.Done(false)

            val salt = Base64.decode(saltB64, Base64.DEFAULT)
            val saltedPassword = hi(password, salt, iter)

            val clientKey = hmac(saltedPassword, "Client Key".toByteArray(StandardCharsets.UTF_8))
            val storedKey = sha256(clientKey)

            val cbindInput = gs2Header.toByteArray(StandardCharsets.UTF_8)
            val cbind = Base64.encodeToString(cbindInput, Base64.NO_WRAP)

            val clientFinalWithoutProof = "c=$cbind,r=$nonce"
            val authMessage = clientFirstBare + "," + serverFirst + "," + clientFinalWithoutProof

            val clientSignature = hmac(storedKey, authMessage.toByteArray(StandardCharsets.UTF_8))
            val proof = xorBytes(clientKey, clientSignature)
            val proofB64 = Base64.encodeToString(proof, Base64.NO_WRAP)

            val serverKey = hmac(saltedPassword, "Server Key".toByteArray(StandardCharsets.UTF_8))
            val serverSig = hmac(serverKey, authMessage.toByteArray(StandardCharsets.UTF_8))
            expectedServerSigB64 = Base64.encodeToString(serverSig, Base64.NO_WRAP)

            return ScramNext.SendClientFinal(clientFinalWithoutProof + ",p=$proofB64")
        }

        val attrs = parseAttrs(msg)
        if (attrs.containsKey("e")) return ScramNext.Done(false)
        val v = attrs["v"] ?: return ScramNext.Done(false)
        return ScramNext.Done(v == expectedServerSigB64)
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

    private fun hi(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return skf.generateSecret(spec).encoded
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
