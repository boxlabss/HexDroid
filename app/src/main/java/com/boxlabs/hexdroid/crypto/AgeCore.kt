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
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The primitives `+AGE` needs, behind one interface so the protocol layer is library-independent
 * and testable. [BouncyCastleAgePrimitives] is the only curve-library dependency; symmetric
 * operations are in [JcaAgePrimitives]. Private keys are 32-byte seeds, signing public keys 32-byte
 * Ed25519 points and DH public keys 32-byte X25519 u-coordinates. An identity has independent
 * signing and DH keypairs.
 */
interface AgePrimitives {

    /** Cryptographically secure random bytes. */
    fun randomBytes(n: Int): ByteArray

    /** Fresh 32-byte private seed. Role-agnostic random bytes: which public-key derivation
     *  ([signingPublicKey] vs [dhPublicKey]) and which operation ([sign] vs [dh]) it's fed to
     *  decides whether it acts as an Ed25519 signing seed or an X25519 DH seed. No seed is ever
     *  used in both roles (see [AgeIdentity]: independent sigSeed / dhSeed). */
    fun generateSeed(): ByteArray = randomBytes(32)

    /** Ed25519 signing public key (32B) for a signing [seed]. Pairs with [sign] / [verify]. */
    fun signingPublicKey(seed: ByteArray): ByteArray

    /** X25519 DH public key (32B) for a DH [seed]. Pairs with [dh]. */
    fun dhPublicKey(seed: ByteArray): ByteArray

    // --- Ed25519 signing (use ONLY the signing keypair) ---
    fun sign(seed: ByteArray, message: ByteArray): ByteArray
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    /**
     * X25519 Diffie-Hellman (use ONLY the DH keypair). Returns the 32-byte shared
     * secret. Must satisfy dh(a, pub(b)) == dh(b, pub(a)).
     */
    fun dh(seed: ByteArray, peerPublicKey: ByteArray): ByteArray

    // --- symmetric / hashing (stable, JCA-backed) ---
    fun sha256(data: ByteArray): ByteArray
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray

    /** Raw HMAC-SHA256 (used by the double-ratchet chain KDF). */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** AES-256-GCM. Ciphertext includes the 16-byte tag. */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /** AES-256-GCM open. Returns null on ANY failure (bad tag/aad/key) — caller fails closed. */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray?

    /** Constant-time equality. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean
}

/** Thrown for unrecoverable `+AGE` errors. Callers treat any failure as "drop + log". */
class AgeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Shared symmetric layer for every [AgePrimitives] backend: RNG, SHA-256, HMAC-SHA256,
 * RFC 5869 HKDF, AES-256-GCM (the same [AesGcm] primitive +AGM uses) and constant-time
 * equality — all JCA-backed and identical regardless of which curve library sits above.
 * Backends only differ in the asymmetric ops (Ed25519 sign/verify + X25519 agreement),
 * so keeping this here guarantees they can never silently diverge on the symmetric parts.
 */
abstract class JcaAgePrimitives : AgePrimitives {

    private val rng = SecureRandom()

    override fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    override fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869 HKDF-SHA256. */
    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // extract
        val realSalt = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(realSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // expand
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    // AES-GCM is the shared [AesGcm] primitive (same one +AGM's AesGcmCipher uses).
    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        AesGcm.seal(key, nonce, plaintext, aad)

    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray? =
        AesGcm.open(key, nonce, ciphertext, aad)   // null on bad tag => fail closed

    override fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

/**
 * The `+AGE` backend: Ed25519 and native X25519 from BouncyCastle's lightweight API (no JCA
 * provider registration), symmetric operations from [JcaAgePrimitives]. An all-zero X25519 shared
 * secret raises [AgeException].
 */
class BouncyCastleAgePrimitives : JcaAgePrimitives() {

    override fun signingPublicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "signing seed must be 32 bytes" }
        return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    }

    override fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == 32) { "signing seed must be 32 bytes" }
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: Throwable) {
            false   // malformed key/sig => not valid (fail closed)
        }

    override fun dhPublicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "dh seed must be 32 bytes" }
        return X25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    }

    override fun dh(seed: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(seed.size == 32) { "dh seed must be 32 bytes" }
        require(peerPublicKey.size == 32) { "peer dh public must be 32 bytes" }
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(seed, 0))
        val out = ByteArray(agreement.agreementSize)   // 32
        try {
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), out, 0)
        } catch (e: Throwable) {
            // BC throws when the agreement is all-zero (peer sent a low-order point). Keep the
            // fail-closed contract by turning it into the protocol's own failure type.
            throw AgeException("dh: X25519 agreement rejected (low-order / invalid peer key)", e)
        }
        return out
    }
}

/**
 * Deterministic length-prefixed encoding for everything signed or sealed, since signatures need a
 * byte-exact form. Each field is `u32 length (big-endian) || bytes`, strings UTF-8, in a
 * caller-fixed order.
 */
object AgeCodec {

    class Writer {
        private val out = ArrayList<ByteArray>()
        private var size = 0
        fun bytes(b: ByteArray): Writer { putLen(b.size); add(b); return this }
        fun str(s: String): Writer = bytes(s.encodeToByteArray())
        fun u32(v: Int): Writer { val b = ByteArray(4); writeU32(b, 0, v); add(b); return this }
        fun u64(v: Long): Writer { val b = ByteArray(8); for (i in 0..7) b[i] = (v ushr (56 - i * 8)).toByte(); add(b); return this }
        fun build(): ByteArray {
            val r = ByteArray(size); var p = 0
            for (chunk in out) { System.arraycopy(chunk, 0, r, p, chunk.size); p += chunk.size }
            return r
        }
        private fun putLen(n: Int) { val b = ByteArray(4); writeU32(b, 0, n); add(b) }
        private fun add(b: ByteArray) { out.add(b); size += b.size }
    }

    class Reader(private val buf: ByteArray) {
        private var pos = 0
        fun bytes(): ByteArray {
            val len = readU32()
            // Overflow-safe bounds check: pos + len can wrap past Int.MAX_VALUE for a hostile
            // length near 2^31, so compare against the remaining space instead of summing.
            if (len < 0 || len > buf.size - pos) throw AgeException("codec: truncated field")
            val r = buf.copyOfRange(pos, pos + len); pos += len; return r
        }
        fun str(): String = bytes().decodeToString()
        fun u32(): Int { ensure(4); val v = readU32At(pos); pos += 4; return v }
        fun u64(): Long { ensure(8); var v = 0L; for (i in 0..7) v = (v shl 8) or (buf[pos + i].toLong() and 0xff); pos += 8; return v }
        fun remaining(): ByteArray = buf.copyOfRange(pos, buf.size)
        fun atEnd(): Boolean = pos >= buf.size
        private fun readU32(): Int { ensure(4); val v = readU32At(pos); pos += 4; return v }
        private fun readU32At(p: Int): Int =
            ((buf[p].toInt() and 0xff) shl 24) or ((buf[p + 1].toInt() and 0xff) shl 16) or
                ((buf[p + 2].toInt() and 0xff) shl 8) or (buf[p + 3].toInt() and 0xff)
        private fun ensure(n: Int) { if (pos + n > buf.size) throw AgeException("codec: truncated") }
    }

    private fun writeU32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
    }

    // base64 (no wrap) for IRC wire framing
    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray =
        try { Base64.decode(s, Base64.NO_WRAP) } catch (e: Throwable) { throw AgeException("bad base64", e) }
}

/**
 * Identity fingerprint: SHA-256("hexdroid/+AGE/identity/v1" || sigPub || dhPub), the protocol-level
 * peer id. The safety number shows its first 80 bits in [Crockford32], longer than the 40-bit
 * per-key fingerprints because an identity guards everything downstream.
 */
object AgeFingerprint {
    private val LABEL = "hexdroid/+AGE/identity/v1".encodeToByteArray()

    fun of(p: AgePrimitives, id: AgePublicIdentity): ByteArray =
        p.sha256(AgeCodec.Writer().bytes(LABEL).bytes(id.sigPub).bytes(id.dhPub).build())

    /** Full 32-byte fingerprint as lowercase hex (protocol id / map key). */
    fun hex(fp: ByteArray): String = fp.joinToString("") { "%02x".format(it) }

    /**
     * Human-comparable safety number: first 80 bits as 16 base32 chars in quads,
     * e.g. `K4XR-T9BS-MN2P-QW7V`.
     */
    fun display(fp: ByteArray): String = Crockford32.encode(fp, chars = 16, group = 4)
}
