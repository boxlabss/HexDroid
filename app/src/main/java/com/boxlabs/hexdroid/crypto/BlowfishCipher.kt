package com.boxlabs.hexdroid.crypto

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Blowfish encryption for the `+OK` wire scheme, AKA FiSH. Maintained for
 * interoperability with HexChat's fishlim plugin and other legacy IRC clients.
 *
 * **This is not a modern cipher and not recommended for new conversations.**
 * Blowfish has a 64-bit block size, which makes it vulnerable to birthday
 * collisions on long-running sessions, and the FiSH key-exchange protocol
 * (DH-1080) has been broken for over a decade. Use [AesGcmCipher] (+AGM) for
 * any new pairing. The implementation here exists so users can read what
 * their HexChat-using friends are sending and reply without changing their
 * existing key material - migration UX, nothing more.
 *
 * Two wire formats exist in the wild:
 *
 *   +OK <fishbase64>     ECB mode. The historical default. Each 8-byte plaintext
 *                        block is encrypted independently, so identical plaintexts
 *                        produce identical ciphertexts (visible pattern in long
 *                        repeated content). Encoded with a custom base64 alphabet
 *                        (./0-9A-Za-z) emitting 12 chars per 8-byte block.
 *
 *   +OK *<stdbase64>     CBC mode. Newer (~2009+). 8-byte random IV prefixed to
 *                        the ciphertext, all encoded with STANDARD base64
 *                        (RFC 4648 with +/= alphabet). PKCS#5 padding.
 *
 * Decoding tries CBC first when the payload starts with `*`, otherwise ECB.
 * Encoding always emits CBC (the more secure of the two) so a HexDroid user
 * who turns on Blowfish sending gets the best the protocol offers, but
 * receivers handle both because in-the-wild fishlim users still send ECB.
 *
 * Key handling: unlike AGM (32 random bytes), FiSH keys are user-typed
 * passphrases ranging from a few characters to 56 bytes. The passphrase is
 * fed to Blowfish as the raw key. No KDF, no salt, no stretching - matching
 * fishlim's behaviour exactly. This is one of the protocol's biggest
 * weaknesses (passphrases like "test123" become trivially brute-forceable
 * keys) and we don't try to paper over it: the EncryptionDialog warns users
 * who pick Blowfish, and short passphrases get an inline length warning.
 */
internal class BlowfishCipher(private val key: ByteArray) : E2eCipher {
    init {
        require(key.isNotEmpty()) { "Blowfish key cannot be empty" }
        // javax.crypto's Blowfish accepts 32-448 bits (4-56 bytes); under 4 we
        // reject up front rather than letting Cipher.init throw later with a
        // less actionable message.
        require(key.size in 4..56) { "Blowfish key must be 4-56 bytes (got ${key.size})" }
    }

    override val scheme: E2eScheme = E2eScheme.BLOWFISH

    private val secretKey = SecretKeySpec(key, "Blowfish")
    private val rng = SecureRandom()

    override fun encrypt(plaintext: String, aadContext: String): String {
        // Emit CBC mode (`+OK *…`) - more secure than ECB and supported by every
        // modern fishlim build. ECB encoding is deliberately not exposed because
        // there is no upside to producing it for new messages.
        val iv = ByteArray(BLOCK_SIZE).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("Blowfish/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        val ct = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        val payload = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(ct, 0, payload, iv.size, ct.size)

        val b64 = Base64.encodeToString(payload, Base64.NO_WRAP)
        return "${scheme.wirePrefix} *$b64"
    }

    override fun decrypt(wireText: String, aadContext: String): String? {
        val prefix = "${scheme.wirePrefix} "
        if (!wireText.startsWith(prefix)) return null
        val body = wireText.substring(prefix.length).trim()
        return if (body.startsWith("*")) {
            decryptCbc(body.substring(1))
        } else {
            decryptEcb(body)
        }
    }

    private fun decryptCbc(b64: String): String? {
        val raw = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (raw.size < BLOCK_SIZE * 2 || raw.size % BLOCK_SIZE != 0) {
            // Need at least IV + one ciphertext block; total length must be a
            // multiple of the block size because PKCS#5 always pads to a full
            // block.
            return null
        }
        val iv = raw.copyOfRange(0, BLOCK_SIZE)
        val ct = raw.copyOfRange(BLOCK_SIZE, raw.size)
        return try {
            val cipher = Cipher.getInstance("Blowfish/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            String(cipher.doFinal(ct), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun decryptEcb(fishB64: String): String? {
        val raw = decodeFishBase64(fishB64) ?: return null
        if (raw.isEmpty() || raw.size % BLOCK_SIZE != 0) return null
        return try {
            // ECB with no padding - fishlim used a fixed-width custom base64 that
            // happens to round to block boundaries by design, so there's no
            // standard padding to strip. We trim trailing NULs that some
            // implementations emit for short last blocks.
            val cipher = Cipher.getInstance("Blowfish/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val pt = cipher.doFinal(raw)
            val trimEnd = pt.indexOfFirst { it == 0.toByte() }.let { if (it < 0) pt.size else it }
            String(pt, 0, trimEnd, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val BLOCK_SIZE = 8

        /**
         * fishlim's custom base64 alphabet: 64 characters from `./0-9A-Za-z`.
         * Differs from RFC base64 in two ways:
         *  - alphabet order (no `+/=`, uses `.` and `/` at positions 0-1)
         *  - encoding direction: every 8 ciphertext bytes produce 12 chars
         *    (instead of standard base64's 4 chars per 3 bytes), reading the
         *    least-significant 6 bits first
         *
         * The decoder below mirrors the encoder in legacy FiSH plugins exactly.
         * No tolerance: an unknown character returns null rather than guessing.
         */
        private val FISH_ALPHABET = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private val FISH_INDEX: IntArray = IntArray(128).also { idx ->
            idx.fill(-1)
            for ((i, c) in FISH_ALPHABET.withIndex()) idx[c.code] = i
        }

        internal fun decodeFishBase64(s: String): ByteArray? {
            if (s.isEmpty() || s.length % 12 != 0) return null
            val out = ByteArray((s.length / 12) * 8)
            var outIdx = 0
            var i = 0
            while (i < s.length) {
                var leftHi: Long = 0
                var leftLo: Long = 0
                // First 6 chars produce the high 4 bytes (left), next 6 produce
                // the low 4 bytes (right). Each char contributes 6 bits, read
                // from least- to most-significant. The packing matches what
                // every fishlim port does.
                for (j in 0 until 6) {
                    val c = s[i + j]
                    if (c.code >= 128) return null
                    val v = FISH_INDEX[c.code]
                    if (v < 0) return null
                    leftHi = leftHi or (v.toLong() shl (j * 6))
                }
                for (j in 0 until 6) {
                    val c = s[i + 6 + j]
                    if (c.code >= 128) return null
                    val v = FISH_INDEX[c.code]
                    if (v < 0) return null
                    leftLo = leftLo or (v.toLong() shl (j * 6))
                }
                // Write the two 32-bit halves big-endian.
                out[outIdx + 0] = ((leftHi shr 24) and 0xff).toByte()
                out[outIdx + 1] = ((leftHi shr 16) and 0xff).toByte()
                out[outIdx + 2] = ((leftHi shr 8) and 0xff).toByte()
                out[outIdx + 3] = (leftHi and 0xff).toByte()
                out[outIdx + 4] = ((leftLo shr 24) and 0xff).toByte()
                out[outIdx + 5] = ((leftLo shr 16) and 0xff).toByte()
                out[outIdx + 6] = ((leftLo shr 8) and 0xff).toByte()
                out[outIdx + 7] = (leftLo and 0xff).toByte()
                outIdx += 8
                i += 12
            }
            return out
        }
    }
}
