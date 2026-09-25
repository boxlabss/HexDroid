package com.boxlabs.hexdroid.crypto

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Blowfish for the `+OK` (FiSH) scheme, for interoperability with fishlim and other legacy clients
 * only; not recommended (64-bit blocks, passphrase used directly as the key with no KDF, as fishlim
 * does).
 *   +OK <fishbase64>  ECB, fishlim's base64 alphabet, 12 chars per 8-byte block
 *   +OK *<base64>     CBC, random 8-byte IV, standard base64, zero padding
 * Decoding tries CBC when the payload starts with `*`, else ECB; encoding always uses CBC.
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
        //
        // JCE's PKCS5 unpadding rejects fishlim's zero-padded ciphertext as a bad pad,
        // so decryption fails outright. We therefore use NoPadding and pad manually.
        // IRC text never contains a NUL byte, so stripping trailing NULs on the far side is unambiguous.
        val iv = ByteArray(BLOCK_SIZE).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        val ct = cipher.doFinal(zeroPad(plaintext.toByteArray(StandardCharsets.UTF_8)))

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
            // multiple of the block size.
            return null
        }
        val iv = raw.copyOfRange(0, BLOCK_SIZE)
        val ct = raw.copyOfRange(BLOCK_SIZE, raw.size)
        return try {
            // NoPadding to match FiSH/Mircryption. After
            // decrypting we strip trailing NUL bytes.
            val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val pt = cipher.doFinal(ct)
            var end = pt.size
            while (end > 0 && pt[end - 1] == 0.toByte()) end--
            String(pt, 0, end, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun decryptEcb(fishB64: String): String? {
        val raw = decodeFishBase64(fishB64) ?: return null
        if (raw.isEmpty() || raw.size % BLOCK_SIZE != 0) return null
        return try {
            // ECB with no padding. fishlim used a fixed-width custom base64 that
            // happens to round to block boundaries by design, so there's no
            // standard padding to strip. We trim trailing NULs that some
            // implementations emit for short last blocks.
            val cipher = Cipher.getInstance("Blowfish/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val pt = cipher.doFinal(raw)
            // Strip trailing zero padding only. walk NULs off the END rather than
            // cutting at the first NUL. Cutting at the first NUL would silently
            // truncate a message that legitimately contained an embedded 0x00.
            var end = pt.size
            while (end > 0 && pt[end - 1] == 0.toByte()) end--
            String(pt, 0, end, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val BLOCK_SIZE = 8

        /**
         * Zero-pad [b] up to a multiple of [BLOCK_SIZE] for FiSH/Mircryption CBC.
         * `copyOf` fills the extra bytes with 0x00. Returns [b] unchanged when it is
         * already block-aligned (FiSH does not add a spurious full padding block).
         */
        private fun zeroPad(b: ByteArray): ByteArray {
            val pad = (BLOCK_SIZE - b.size % BLOCK_SIZE) % BLOCK_SIZE
            return if (pad == 0) b else b.copyOf(b.size + pad)
        }

        /**
         * fishlim's base64: alphabet `./0-9a-zA-Z` (lowercase before uppercase); each 8-byte block
         * becomes 12 characters, the right 32-bit word first, least-significant 6 bits first. An
         * unknown character fails the decode.
         */
        private val FISH_ALPHABET = "./0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
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
                var rightWord: Long = 0
                var leftWord: Long = 0
                // FiSH packs each 8-byte block as two big-endian 32-bit words and
                // emits the RIGHT word (bytes 4-7) first, then the LEFT word (bytes
                // 0-3). Each char contributes 6 bits, least-significant first. So the
                // first 6 chars rebuild the right word and the next 6 the left word.
                for (j in 0 until 6) {
                    val c = s[i + j]
                    if (c.code >= 128) return null
                    val v = FISH_INDEX[c.code]
                    if (v < 0) return null
                    rightWord = rightWord or (v.toLong() shl (j * 6))
                }
                for (j in 0 until 6) {
                    val c = s[i + 6 + j]
                    if (c.code >= 128) return null
                    val v = FISH_INDEX[c.code]
                    if (v < 0) return null
                    leftWord = leftWord or (v.toLong() shl (j * 6))
                }
                // Write left word to bytes 0-3, right word to bytes 4-7 (big-endian).
                out[outIdx + 0] = ((leftWord shr 24) and 0xff).toByte()
                out[outIdx + 1] = ((leftWord shr 16) and 0xff).toByte()
                out[outIdx + 2] = ((leftWord shr 8) and 0xff).toByte()
                out[outIdx + 3] = (leftWord and 0xff).toByte()
                out[outIdx + 4] = ((rightWord shr 24) and 0xff).toByte()
                out[outIdx + 5] = ((rightWord shr 16) and 0xff).toByte()
                out[outIdx + 6] = ((rightWord shr 8) and 0xff).toByte()
                out[outIdx + 7] = (rightWord and 0xff).toByte()
                outIdx += 8
                i += 12
            }
            return out
        }
    }
}
