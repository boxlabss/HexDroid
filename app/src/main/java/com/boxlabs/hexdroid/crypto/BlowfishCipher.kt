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
 *                        (RFC 4648 with +/= alphabet). Zero-padding to the block
 *                        size (FiSH/Mircryption convention - NOT PKCS#5; the
 *                        receiver strips trailing NUL bytes).
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
        //
        // FiSH/Mircryption CBC zero-pads
        // the plaintext to a multiple of the block size and strips trailing zero bytes
        // on decrypt. JCE's "Blowfish/CBC/PKCS5Padding" appends non-zero pad bytes
        // (0x01..0x08) which fishlim does not strip. JCE's PKCS5 unpadding rejects
        // fishlim's zero-padded ciphertext as a bad pad, so decryption fails outright.
        // We therefore use NoPadding and pad manually. (IRC text never contains a NUL
        // byte, so stripping trailing NULs on the far side is unambiguous.)
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
                val trimEnd = pt.indexOfFirst { it == 0.toByte() }.let { if (it < 0) pt.size else it }
                String(pt, 0, trimEnd, StandardCharsets.UTF_8)
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
         * fishlim's custom base64 alphabet: 64 characters from `./0-9a-zA-Z`.
         * Differs from RFC base64 in three ways:
         *  - alphabet order (no `+/=`, uses `.` and `/` at positions 0-1, then
         *    digits, then **lowercase a-z, then uppercase A-Z** - lowercase first)
         *  - encoding direction: every 8 ciphertext bytes produce 12 chars
         *    (instead of standard base64's 4 chars per 3 bytes), reading the
         *    least-significant 6 bits first
         *  - block layout: each 8-byte block is two big-endian 32-bit words
         *    (left = bytes 0-3, right = bytes 4-7); the first 6 chars encode the
         *    *right* word and the next 6 encode the *left* word
         *
         * The decoder below mirrors the canonical FiSH encoder.
         * No tolerance: an unknown character returns null
         * rather than guessing.
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
