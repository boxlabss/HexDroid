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
 * Anonymous sealed box (spec §4): encrypt a payload TO a recipient's DH public key.
 * Anyone can seal; only the holder of the DH seed can open. Confidential by the
 * recipient's key alone, which is why a sealed invite is safe over an unencrypted PM.
 *
 *   seal:  epk fresh; shared = dh(esk, rpk)
 *          okm   = HKDF(shared, salt = epk‖rpk, info = SEAL, 44)
 *          key   = okm[0:32]; nonce = okm[32:44]   (epk fresh ⇒ key/nonce unique ⇒ no GCM reuse)
 *          blob  = epk(32) ‖ AES-256-GCM(key, nonce, plaintext, aad)
 */
object AgeSeal {
    private val INFO = "hexdroid/+AGE/seal/v1".encodeToByteArray()

    fun seal(p: AgePrimitives, recipientDhPub: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val esk = p.generateSeed()
        val epk = p.dhPublicKey(esk)
        val shared = p.dh(esk, recipientDhPub)
        val okm = p.hkdfSha256(shared, salt = epk + recipientDhPub, info = INFO, length = 44)
        val key = okm.copyOfRange(0, 32)
        val nonce = okm.copyOfRange(32, 44)
        val ct = p.aesGcmSeal(key, nonce, plaintext, aad)
        return epk + ct
    }

    /** Returns plaintext or throws [AgeException] on any failure. */
    fun open(p: AgePrimitives, recipientDhSeed: ByteArray, recipientDhPub: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < 32 + 16) throw AgeException("seal: blob too short")
        val epk = blob.copyOfRange(0, 32)
        val ct = blob.copyOfRange(32, blob.size)
        val shared = p.dh(recipientDhSeed, epk)
        val okm = p.hkdfSha256(shared, salt = epk + recipientDhPub, info = INFO, length = 44)
        return p.aesGcmOpen(okm.copyOfRange(0, 32), okm.copyOfRange(32, 44), ct, aad)
            ?: throw AgeException("seal: open failed (bad tag / wrong recipient)")
    }
}

/**
 * Signal-style Double Ratchet for 1:1 PMs, forward secrecy + post-compromise security
 * on top of [AgeIdentity]. This is the engine the `+AGE` E2eCipher will wrap; the
 * interactive 3-DH handshake that seeds it is in [AgeHandshake].
 *
 * Symmetric-key (chain) ratchet: KDF_CK = HMAC(ck, 0x01)→messageKey, HMAC(ck, 0x02)→ck'.
 * DH ratchet: each direction change generates a fresh X25519 ratchet keypair and folds
 * DH(ours, theirs) into the root key. Out-of-order messages are handled by storing
 * skipped message keys, capped by [MAX_SKIP] to bound a memory-exhaustion DoS.
 */
class AgeRatchet private constructor(
    private val p: AgePrimitives,
    // DH ratchet state
    private var dhsSeed: ByteArray,            // our current ratchet private (seed)
    private var dhsPub: ByteArray,             // our current ratchet public
    private var dhrPub: ByteArray?,            // their current ratchet public (null until first recv)
    private var rk: ByteArray,                 // root key (32)
    private var cks: ByteArray?,               // sending chain key (null until established)
    private var ckr: ByteArray?,               // receiving chain key
    private var ns: Int,                       // messages sent in current sending chain
    private var nr: Int,                       // messages received in current receiving chain
    private var pn: Int,                       // # messages in previous sending chain
    private val skipped: LinkedHashMap<String, ByteArray>,  // (dhrPubHex|n) -> messageKey
    // Responder-only: the initiator's handshake ratchet public (EK_A). A freshly-seeded responder
    // has no sending chain (cks == null) until it first RECEIVES; but +AGE elects the initiator by
    // fingerprint, not by who speaks first, so the responder must be able to send the opening line
    // too. Holding EK_A here lets encrypt() run the opening DH ratchet on demand. Null for the
    // initiator, and cleared once any DH ratchet has established a sending chain.
    private var initialRemotePub: ByteArray?,
) {

    /** A ratchet message: the (cleartext) header + AEAD ciphertext. */
    data class Message(val headerDhPub: ByteArray, val pn: Int, val n: Int, val ciphertext: ByteArray)

    // ---- encrypt / decrypt --------------------------------------------------

    fun encrypt(plaintext: ByteArray, ad: ByteArray): Message {
        // Responder sending first: no sending chain yet, but it knows the initiator's ratchet key
        // (EK_A), so it performs the opening DH ratchet now. This is exactly the ratchet step a first
        // inbound message would have triggered, so the initiator decrypts our opener normally, and the
        // initiator's own opening message (header EK_A) still decrypts for us afterwards (that chain
        // became our receiving chain in the same step).
        if (cks == null) {
            val ekA = initialRemotePub ?: throw AgeException("ratchet: no sending chain (handshake incomplete)")
            dhRatchet(ekA)
        }
        val ck = cks ?: throw AgeException("ratchet: no sending chain (handshake incomplete)")
        val (ckNext, mk) = kdfCk(ck)
        cks = ckNext
        val header = Message(dhsPub, pn, ns, ByteArray(0))
        ns++
        val ct = aeadSeal(mk, plaintext, ad + headerBytes(header))
        return header.copy(ciphertext = ct)
    }

    /** Returns plaintext, or throws [AgeException] on failure (caller fails closed). */
    fun decrypt(m: Message, ad: ByteArray): ByteArray {
        // 1) maybe an out-of-order message we already have a key for (trySkipped only
        //    consumes its stored key on AEAD success, so it's already transactional).
        trySkipped(m, ad)?.let { return it }

        // A forged or corrupt message must NOT advance the ratchet — otherwise the next
        // legitimate message can't decrypt (a permanent session break / DoS). So all of
        // the state mutation below (DH ratchet, skipped-key derivation, chain advance) is
        // staged on a snapshot and only kept if the AEAD tag verifies; on any failure we
        // restore the ratchet to exactly what it was.
        val snap = snapshot()
        try {
            // 2) new ratchet key from peer => DH ratchet (after stashing skipped keys in the old chain)
            val dhr = dhrPub
            if (dhr == null || !p.constantTimeEquals(m.headerDhPub, dhr)) {
                skipReceiving(m.pn)                   // finish the previous receiving chain
                dhRatchet(m.headerDhPub)
            }

            // 3) skip forward within the current receiving chain to this message number
            skipReceiving(m.n)

            val ck = ckr ?: throw AgeException("ratchet: no receiving chain")
            val (ckNext, mk) = kdfCk(ck)
            val pt = aeadOpen(mk, m.ciphertext, ad + headerBytes(m))
                ?: throw AgeException("ratchet: AEAD open failed")

            // committed: the tag verified, so advance the receiving chain for real
            ckr = ckNext
            nr++
            return pt
        } catch (e: Throwable) {
            restore(snap)                             // leave the ratchet exactly as it was
            throw e
        }
    }

    /** Immutable snapshot of all mutable ratchet state, for rollback on a failed decrypt. */
    private class Snap(
        val dhsSeed: ByteArray, val dhsPub: ByteArray, val dhrPub: ByteArray?,
        val rk: ByteArray, val cks: ByteArray?, val ckr: ByteArray?,
        val ns: Int, val nr: Int, val pn: Int, val skipped: LinkedHashMap<String, ByteArray>,
        val initialRemotePub: ByteArray?,
    )

    private fun snapshot(): Snap =
        // chain/root keys are only ever *reassigned* to fresh arrays, never mutated in place,
        // so holding the references is enough; `skipped` is mutated in place, so copy it.
        Snap(dhsSeed, dhsPub, dhrPub, rk, cks, ckr, ns, nr, pn, LinkedHashMap(skipped), initialRemotePub)

    private fun restore(s: Snap) {
        dhsSeed = s.dhsSeed; dhsPub = s.dhsPub; dhrPub = s.dhrPub
        rk = s.rk; cks = s.cks; ckr = s.ckr
        ns = s.ns; nr = s.nr; pn = s.pn
        skipped.clear(); skipped.putAll(s.skipped)
        initialRemotePub = s.initialRemotePub
    }

    private fun trySkipped(m: Message, ad: ByteArray): ByteArray? {
        val key = skKey(m.headerDhPub, m.n)
        val mk = skipped[key] ?: return null
        val pt = aeadOpen(mk, m.ciphertext, ad + headerBytes(m)) ?: return null
        skipped.remove(key)
        return pt
    }

    private fun skipReceiving(until: Int) {
        val ck = ckr ?: return
        if (until - nr > MAX_SKIP) throw AgeException("ratchet: too many skipped messages")
        var chain = ck
        while (nr < until) {
            val (next, mk) = kdfCk(chain)
            chain = next
            skipped[skKey(dhrPub!!, nr)] = mk
            if (skipped.size > MAX_SKIP) skipped.remove(skipped.keys.first())  // bound memory
            nr++
        }
        ckr = chain
    }

    private fun dhRatchet(theirPub: ByteArray) {
        pn = ns; ns = 0; nr = 0
        dhrPub = theirPub
        initialRemotePub = null   // a real ratchet supersedes the pending opening-ratchet key
        // receiving chain from DH(our current, their new)
        run {
            val (rkNew, ck) = kdfRk(rk, p.dh(dhsSeed, theirPub))
            rk = rkNew; ckr = ck
        }
        // rotate our ratchet key, derive new sending chain
        val newSeed = p.generateSeed()
        dhsSeed = newSeed; dhsPub = p.dhPublicKey(newSeed)
        run {
            val (rkNew, ck) = kdfRk(rk, p.dh(dhsSeed, theirPub))
            rk = rkNew; cks = ck
        }
    }

    // ---- KDFs ---------------------------------------------------------------

    /** Root KDF: (rk', ck) = HKDF(salt=rk, ikm=dhOut, 64). */
    private fun kdfRk(rkIn: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val okm = p.hkdfSha256(ikm = dhOut, salt = rkIn, info = INFO_RK, length = 64)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
    }

    /** Chain KDF: messageKey = HMAC(ck, 0x01); ck' = HMAC(ck, 0x02). */
    private fun kdfCk(ck: ByteArray): Pair<ByteArray, ByteArray> {
        val mk = p.hmacSha256(ck, byteArrayOf(0x01))
        val ckNext = p.hmacSha256(ck, byteArrayOf(0x02))
        return ckNext to mk
    }

    private fun aeadSeal(mk: ByteArray, pt: ByteArray, ad: ByteArray): ByteArray {
        val okm = p.hkdfSha256(mk, ByteArray(32), INFO_MSG, 44)
        return p.aesGcmSeal(okm.copyOfRange(0, 32), okm.copyOfRange(32, 44), pt, ad)
    }

    private fun aeadOpen(mk: ByteArray, ct: ByteArray, ad: ByteArray): ByteArray? {
        val okm = p.hkdfSha256(mk, ByteArray(32), INFO_MSG, 44)
        return p.aesGcmOpen(okm.copyOfRange(0, 32), okm.copyOfRange(32, 44), ct, ad)
    }

    private fun headerBytes(m: Message): ByteArray =
        AgeCodec.Writer().bytes(m.headerDhPub).u32(m.pn).u32(m.n).build()

    private fun skKey(dhPub: ByteArray, n: Int): String = AgeFingerprint.hex(dhPub) + "|" + n

    // ---- persistence --------------------------------------------------------

    fun serialize(): ByteArray {
        val w = AgeCodec.Writer().u32(STATE_VERSION)
            .bytes(dhsSeed).bytes(dhsPub).bytes(dhrPub ?: ByteArray(0))
            .bytes(rk).bytes(cks ?: ByteArray(0)).bytes(ckr ?: ByteArray(0))
            .u32(ns).u32(nr).u32(pn).u32(skipped.size)
        for ((k, mk) in skipped) w.str(k).bytes(mk)
        return w.build()
    }

    companion object {
        const val MAX_SKIP = 1000
        const val STATE_VERSION = 1
        private val INFO_RK = "hexdroid/+AGE/ratchet/rk/v1".encodeToByteArray()
        private val INFO_MSG = "hexdroid/+AGE/ratchet/msg/v1".encodeToByteArray()

        /**
         * Initiator side. [sharedSecret] comes from [AgeHandshake]; [theirRatchetPub] is
         * the peer's handshake ephemeral public (their initial ratchet key).
         */
        fun initInitiator(p: AgePrimitives, sharedSecret: ByteArray, ourSeed: ByteArray, ourPub: ByteArray, theirRatchetPub: ByteArray): AgeRatchet {
            val r = AgeRatchet(p, ourSeed, ourPub, null, sharedSecret, null, null, 0, 0, 0, LinkedHashMap(), null)
            // First DH ratchet to derive the initial sending chain against the peer's ratchet key.
            r.dhrPub = theirRatchetPub
            val (rkNew, ck) = r.kdfRk(r.rk, p.dh(ourSeed, theirRatchetPub))
            r.rk = rkNew; r.cks = ck
            return r
        }

        /**
         * Responder side. Holds the handshake ephemeral as its initial ratchet keypair;
         * derives nothing until the initiator's first message triggers the matching ratchet.
         */
        fun initResponder(p: AgePrimitives, sharedSecret: ByteArray, ourSeed: ByteArray, ourPub: ByteArray, theirInitialRatchetPub: ByteArray): AgeRatchet =
            AgeRatchet(p, ourSeed, ourPub, null, sharedSecret, null, null, 0, 0, 0, LinkedHashMap(), theirInitialRatchetPub)

        fun deserialize(p: AgePrimitives, bytes: ByteArray): AgeRatchet {
            val r = AgeCodec.Reader(bytes)
            val v = r.u32(); if (v != STATE_VERSION) throw AgeException("ratchet state version $v")
            fun opt(b: ByteArray): ByteArray? = if (b.isEmpty()) null else b
            val dhsSeed = r.bytes(); val dhsPub = r.bytes(); val dhrPub = opt(r.bytes())
            val rk = r.bytes(); val cks = opt(r.bytes()); val ckr = opt(r.bytes())
            val ns = r.u32(); val nr = r.u32(); val pn = r.u32()
            val skipped = LinkedHashMap<String, ByteArray>()
            repeat(r.u32()) { skipped[r.str()] = r.bytes() }
            return AgeRatchet(p, dhsSeed, dhsPub, dhrPub, rk, cks, ckr, ns, nr, pn, skipped, null)
        }
    }
}

/**
 * Interactive 3-DH handshake that seeds an [AgeRatchet] (an online-friendly X3DH-lite —
 * no prekey server, since both peers are connected to IRC). Two messages:
 *
 *   A → B : HELLO  = seal_B( sign_A( A.identity ‖ EK_A_pub ) )
 *   B → A : ACK    = seal_A( sign_B( EK_B_pub ) )
 *
 * Shared secret (initiator A, responder B):
 *   SK = HKDF( DH(IK_A, EK_B) ‖ DH(EK_A, IK_B) ‖ DH(EK_A, EK_B) )
 * Mutual auth comes from the identity keys in the DHs + the signatures; ephemeral
 * secrecy from EK_A/EK_B. (We omit DH(IK_A,IK_B) to preserve deniability, like X3DH.)
 */
object AgeHandshake {
    private val SK_INFO = "hexdroid/+AGE/handshake/v1".encodeToByteArray()
    private val HELLO_SIGN = "hexdroid/+AGE/hello-sign/v1".encodeToByteArray()
    private val ACK_SIGN = "hexdroid/+AGE/ack-sign/v1".encodeToByteArray()
    private val HELLO_AAD = "hexdroid/+AGE/hello/v1".encodeToByteArray()
    private val ACK_AAD = "hexdroid/+AGE/ack/v1".encodeToByteArray()

    /** Ephemeral ratchet keypair created at the start of a handshake. */
    class Ephemeral(val seed: ByteArray, val pub: ByteArray)

    fun newEphemeral(p: AgePrimitives): Ephemeral {
        val seed = p.generateSeed(); return Ephemeral(seed, p.dhPublicKey(seed))
    }

    // ---- A > B : HELLO ----

    fun buildHello(p: AgePrimitives, initiator: AgeIdentity, ekA: Ephemeral, responderDhPub: ByteArray): ByteArray {
        val body = AgeCodec.Writer()
            .bytes(initiator.sigPub).bytes(initiator.dhPub).bytes(ekA.pub).build()
        val sig = p.sign(initiator.sigSeed, HELLO_SIGN + body)
        val signed = AgeCodec.Writer().bytes(body).bytes(sig).build()
        return AgeSeal.seal(p, responderDhPub, signed, HELLO_AAD)
    }

    class Hello(val initiator: AgePublicIdentity, val ekAPub: ByteArray)

    /** B opens A's HELLO, verifying A's self-signature. Pass A's pinned sig key to bind identity. */
    fun openHello(p: AgePrimitives, responder: AgeIdentity, blob: ByteArray, expectedInitiatorSig: ByteArray?): Hello {
        val signed = AgeSeal.open(p, responder.dhSeed, responder.dhPub, blob, HELLO_AAD)
        val r = AgeCodec.Reader(signed); val body = r.bytes(); val sig = r.bytes()
        val br = AgeCodec.Reader(body)
        val sigPub = br.bytes(); val dhPub = br.bytes(); val ekAPub = br.bytes()
        if (expectedInitiatorSig != null && !p.constantTimeEquals(expectedInitiatorSig, sigPub))
            throw AgeException("hello: initiator key != pinned")
        if (!p.verify(sigPub, HELLO_SIGN + body, sig)) throw AgeException("hello: bad signature")
        return Hello(AgePublicIdentity(sigPub, dhPub), ekAPub)
    }

    // ---- B > A : ACK ----

    fun buildAck(p: AgePrimitives, responder: AgeIdentity, ekB: Ephemeral, initiatorDhPub: ByteArray): ByteArray {
        val body = ekB.pub
        val sig = p.sign(responder.sigSeed, ACK_SIGN + body)
        val signed = AgeCodec.Writer().bytes(body).bytes(sig).build()
        return AgeSeal.seal(p, initiatorDhPub, signed, ACK_AAD)
    }

    /** A opens B's ACK, verifying B's signature against B's pinned sig key. Returns EK_B_pub. */
    fun openAck(p: AgePrimitives, initiator: AgeIdentity, blob: ByteArray, responderSigPub: ByteArray): ByteArray {
        val signed = AgeSeal.open(p, initiator.dhSeed, initiator.dhPub, blob, ACK_AAD)
        val r = AgeCodec.Reader(signed); val ekBPub = r.bytes(); val sig = r.bytes()
        if (!p.verify(responderSigPub, ACK_SIGN + ekBPub, sig)) throw AgeException("ack: bad signature")
        return ekBPub
    }

    // ---- derive sessions ----

    private fun sk(p: AgePrimitives, dh1: ByteArray, dh2: ByteArray, dh3: ByteArray): ByteArray =
        p.hkdfSha256(ikm = dh1 + dh2 + dh3, salt = ByteArray(32), info = SK_INFO, length = 32)

    /** A, after receiving EK_B: build the initiator ratchet. */
    fun initiatorSession(p: AgePrimitives, a: AgeIdentity, ekA: Ephemeral, bDhPub: ByteArray, ekBPub: ByteArray): AgeRatchet {
        val dh1 = p.dh(a.dhSeed, ekBPub)
        val dh2 = p.dh(ekA.seed, bDhPub)
        val dh3 = p.dh(ekA.seed, ekBPub)
        return AgeRatchet.initInitiator(p, sk(p, dh1, dh2, dh3), ekA.seed, ekA.pub, ekBPub)
    }

    /** B, after receiving HELLO: build the responder ratchet. */
    fun responderSession(p: AgePrimitives, b: AgeIdentity, ekB: Ephemeral, aDhPub: ByteArray, ekAPub: ByteArray): AgeRatchet {
        val dh1 = p.dh(ekB.seed, aDhPub)
        val dh2 = p.dh(b.dhSeed, ekAPub)
        val dh3 = p.dh(ekB.seed, ekAPub)
        return AgeRatchet.initResponder(p, sk(p, dh1, dh2, dh3), ekB.seed, ekB.pub, ekAPub)
    }
}

/**
 * Encrypted group game channel (spec §6, §8). Holds the per-game key K_G and does
 * sign-then-encrypt outbound/decrypt-then-verify inbound. The shared key gives
 * confidentiality from outsiders; per-sender Ed25519 signatures give authentication
 * BETWEEN players (a shared key alone lets any member forge as any other).
 *
 *   out: inner = canonical(gameId, epoch, seq, senderFp, move)
 *        sig   = sign(my_sig_seed, MSG_TAG ‖ inner)
 *        k_s   = HKDF(K_G, info = MSG_KEY_INFO ‖ senderFp ‖ be32(epoch))   per-sender message key
 *        nonce = senderFp[0:8] ‖ be32(seq)
 *        ct    = AES-256-GCM(k_s, nonce, inner‖sig, aad = MSG_AAD ‖ gameId ‖ senderFp ‖ be32(seq))
 *
 * Deriving a per-sender key k_s from K_G (rather than encrypting under K_G directly) is what
 * stops the truncated-fingerprint nonce from causing GCM (key, nonce) reuse: the nonce only
 * carries 8 bytes of senderFp, so two members with a grindable 64-bit fingerprint-prefix
 * collision would otherwise share a nonce under the one shared key. Distinct identities derive
 * distinct k_s, so a prefix collision is harmless. See [senderKey].
 *
 * Membership: [rekey] on removal (the removed member keeps the old K_G, so anything
 * they must not read uses the new one). Re-sealing K_G to members is [AgeInvite]'s job.
 */
class AgeChannel(
    private val p: AgePrimitives,
    val gameId: String,
    private val me: AgeIdentity,
    private val mySigFpHex: String,
    groupKey: ByteArray,
    epoch: Int = 0,
) {
    private var key: ByteArray = groupKey.copyOf()
    private var epoch: Int = epoch
    private var sendSeq: Int = 0

    /** Highest seq accepted per sender fp (monotonic replay guard). */
    private val lastSeqByFp = HashMap<String, Int>()
    /** Pinned signing keys of members, fpHex -> sigPub. */
    private val memberSigKeys = HashMap<String, ByteArray>()

    fun addMember(fpHex: String, sigPub: ByteArray) { memberSigKeys[fpHex.lowercase()] = sigPub }
    fun removeMember(fpHex: String) { memberSigKeys.remove(fpHex.lowercase()) }

    fun rekey(newKey: ByteArray, newEpoch: Int) {
        require(newKey.size == 32)
        key = newKey.copyOf(); epoch = newEpoch
        lastSeqByFp.clear()  // seq space resets per epoch
    }

    /** Encrypt a move (opaque bytes from the game/.hex layer). Returns the AGE MSG body. */
    fun encrypt(move: ByteArray): EncMessage {
        val seq = sendSeq++
        val inner = AgeCodec.Writer().str(gameId).u32(epoch).u32(seq).str(mySigFpHex).bytes(move).build()
        val sig = p.sign(me.sigSeed, MSG_TAG + inner)
        val signed = AgeCodec.Writer().bytes(inner).bytes(sig).build()
        val nonce = nonceFor(mySigFpHex, seq)
        val aad = msgAad(gameId, mySigFpHex, seq)
        val ct = p.aesGcmSeal(senderKey(mySigFpHex), nonce, signed, aad)
        return EncMessage(gameId, mySigFpHex, epoch, seq, ct)
    }

    sealed class Decrypted {
        data class Ok(val senderFpHex: String, val seq: Int, val move: ByteArray) : Decrypted()
        data class Dropped(val reason: String) : Decrypted()
    }

    /** Decrypt + verify an inbound AGE MSG. Any failure ⇒ Dropped (never throw to caller). */
    fun decrypt(m: EncMessage): Decrypted {
        if (m.gameId != gameId) return Decrypted.Dropped("wrong game")
        if (m.epoch != epoch) return Decrypted.Dropped("stale/foreign epoch ${m.epoch}")
        val senderKey = memberSigKeys[m.senderFpHex.lowercase()]
            ?: return Decrypted.Dropped("unknown sender ${m.senderFpHex}")

        // Everything below can throw on a hostile input: a group-key holder (any member) can
        // craft a ciphertext that GCM-verifies but whose inner TLV is truncated, which makes the
        // AgeCodec.Reader throw AgeException. The contract here is "never throw", and both wire
        // call sites (onWireMessage/decryptChat) rely on it, so contain any failure as Dropped
        // rather than letting a malicious member turn a bad decode into an escaping exception.
        return try {
            val nonce = nonceFor(m.senderFpHex, m.seq)
            val aad = msgAad(m.gameId, m.senderFpHex, m.seq)
            val signed = p.aesGcmOpen(senderKey(m.senderFpHex), nonce, m.ciphertext, aad)
                ?: return Decrypted.Dropped("decrypt failed (bad tag/aad)")

            val r = AgeCodec.Reader(signed)
            val inner = r.bytes(); val sig = r.bytes()
            if (!p.verify(senderKey, MSG_TAG + inner, sig)) return Decrypted.Dropped("bad signature")

            // Re-parse inner and cross-check the header fields against the signed content,
            // so a tampered cleartext header can't desync from what was actually signed.
            val ir = AgeCodec.Reader(inner)
            val gid = ir.str(); val ep = ir.u32(); val seq = ir.u32(); val fp = ir.str(); val move = ir.bytes()
            if (gid != m.gameId || ep != m.epoch || seq != m.seq || !fp.equals(m.senderFpHex, true))
                return Decrypted.Dropped("header/inner mismatch")

            // Replay / reorder guard: strictly increasing seq per sender within an epoch.
            val last = lastSeqByFp[fp.lowercase()]
            if (last != null && seq <= last) return Decrypted.Dropped("replay/old seq $seq")
            lastSeqByFp[fp.lowercase()] = seq

            Decrypted.Ok(fp, seq, move)
        } catch (e: Throwable) {
            Decrypted.Dropped("malformed message")
        }
    }

    /**
     * Per-sender AES-256-GCM message key, derived from the shared group key.
     *
     * The wire nonce (see [nonceFor]) only carries the first 8 bytes of the sender's fingerprint,
     * so under a single shared group key two members whose fingerprints collide in those 64 bits
     * (grindable at ~2^64 targeted, or ~2^32 by birthday for two attacker-chosen identities) would
     * reuse a (key, nonce) pair and break GCM. Binding the FULL fingerprint plus the epoch into a
     * per-sender key removes that: distinct identities always get distinct keys, so a fingerprint
     * PREFIX collision is harmless. The inner Ed25519 signature already prevents impersonation; this
     * closes the AEAD-layer confidentiality gap so the group cipher meets the 128-bit bar on its own.
     *
     * fpHex is lowercased so both sides derive the same key regardless of wire-case.
     */
    private fun senderKey(fpHex: String): ByteArray =
        p.hkdfSha256(
            ikm = key,
            salt = ByteArray(0),
            info = MSG_KEY_INFO + fpHex.lowercase().encodeToByteArray() + byteArrayOf(
                (epoch ushr 24).toByte(), (epoch ushr 16).toByte(), (epoch ushr 8).toByte(), epoch.toByte(),
            ),
            length = 32,
        )

    private fun nonceFor(fpHex: String, seq: Int): ByteArray {
        // fp is hex; take first 8 bytes (16 hex chars) + 4-byte big-endian seq = 12-byte nonce.
        val fpBytes = ByteArray(8)
        var i = 0
        while (i < 8) { fpBytes[i] = ((hexVal(fpHex[i * 2]) shl 4) or hexVal(fpHex[i * 2 + 1])).toByte(); i++ }
        val n = ByteArray(12)
        System.arraycopy(fpBytes, 0, n, 0, 8)
        n[8] = (seq ushr 24).toByte(); n[9] = (seq ushr 16).toByte(); n[10] = (seq ushr 8).toByte(); n[11] = seq.toByte()
        return n
    }

    private fun msgAad(gameId: String, fpHex: String, seq: Int): ByteArray =
        MSG_AAD + gameId.encodeToByteArray() + fpHex.encodeToByteArray() + byteArrayOf(
            (seq ushr 24).toByte(), (seq ushr 16).toByte(), (seq ushr 8).toByte(), seq.toByte(),
        )

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'; in 'a'..'f' -> c - 'a' + 10; in 'A'..'F' -> c - 'A' + 10
        else -> throw AgeException("bad fingerprint hex")
    }

    data class EncMessage(
        val gameId: String, val senderFpHex: String, val epoch: Int, val seq: Int, val ciphertext: ByteArray,
    )

    companion object {
        private val MSG_TAG = "hexdroid/+AGE/msg-sign/v1".encodeToByteArray()
        private val MSG_AAD = "hexdroid/+AGE/msg/v1".encodeToByteArray()
        private val MSG_KEY_INFO = "hexdroid/+AGE/msg-key/v1".encodeToByteArray()
    }
}

/**
 * Game invite (spec §5): a signed payload, sealed to the invitee.
 *
 *   signed = canonical(payload) ‖ sig(host_sig_seed, INVITE_TAG ‖ canonical(payload))
 *   blob   = seal(invitee_dh_pub, signed, aad = INVITE_AAD ‖ invitee_nick ‖ game_id)
 *
 * Verify: open > split signed > verify host signature against the PINNED host sig key >
 * check expiry, unseen game_id, and that we're in members.
 */
object AgeInvite {
    private val SIGN_TAG = "hexdroid/+AGE/invite-sign/v1".encodeToByteArray()
    private val AAD_TAG = "hexdroid/+AGE/invite-aad/v1".encodeToByteArray()
    const val VERSION = 1

    data class Member(val nick: String, val fpHex: String)

    data class Payload(
        val gameId: String,
        val groupKey: ByteArray,          // K_G, 32 bytes
        val params: String,               // opaque (JSON/whatever the game layer wants)
        val members: List<Member>,
        val hostSigPub: ByteArray,
        val issuedAt: Long,
        val expiresAt: Long,
    )

    private fun encodePayload(pl: Payload): ByteArray {
        val w = AgeCodec.Writer().u32(VERSION).str(pl.gameId).bytes(pl.groupKey).str(pl.params)
            .u32(pl.members.size)
        for (m in pl.members) w.str(m.nick).str(m.fpHex)
        return w.bytes(pl.hostSigPub).u64(pl.issuedAt).u64(pl.expiresAt).build()
    }

    private fun decodePayload(b: ByteArray): Payload {
        val r = AgeCodec.Reader(b)
        val v = r.u32(); if (v != VERSION) throw AgeException("invite version $v unsupported")
        val gameId = r.str(); val gk = r.bytes(); val params = r.str()
        val n = r.u32(); val members = ArrayList<Member>(n)
        repeat(n) { members.add(Member(r.str(), r.str())) }
        return Payload(gameId, gk, params, members, r.bytes(), r.u64(), r.u64())
    }

    /** Host side: build the sealed invite blob for [invitee]. */
    fun build(p: AgePrimitives, host: AgeIdentity, invitee: AgePublicIdentity, inviteeNick: String, pl: Payload): ByteArray {
        require(pl.groupKey.size == 32) { "group key must be 32 bytes" }
        val body = encodePayload(pl)
        val sig = p.sign(host.sigSeed, SIGN_TAG + body)
        val signed = AgeCodec.Writer().bytes(body).bytes(sig).build()
        // AAD binds the recipient nick only. game_id can't be in the AAD because the
        // opener doesn't know it until after decryption — but it's inside the signed
        // body, so the host signature already protects it from substitution.
        val aad = AAD_TAG + inviteeNick.encodeToByteArray()
        return AgeSeal.seal(p, invitee.dhPub, signed, aad)
    }

    sealed class Open {
        data class Ok(val payload: Payload) : Open()
        data class Rejected(val reason: String) : Open()
    }

    /**
     * Invitee side. Opens, verifies host signature against [expectedHostSigPub], checks expiry / membership.
     *
     * @param myNick   the nick the invite was addressed to (binds the AAD)
     * @param myFpHex  our own fingerprint, to confirm we're actually in members
     * @param now      current epoch millis
     */
    fun open(
        p: AgePrimitives,
        me: AgeIdentity,
        myNick: String,
        myFpHex: String,
        blob: ByteArray,
        expectedHostSigPub: ByteArray?,
        now: Long,
    ): Open {
        val signed = try {
            // We don't yet know game_id (it's inside) AAD binds nick + game_id, so we
            // must reconstruct AAD after a tentative decode. Resolve by trying with the
            // game_id we recover: seal AAD uses nick+gameId, but gameId is inside the
            // sealed plaintext. So bind AAD to nick only at this layer and put game_id
            // inside the signed body (already done). Re-seal AAD accordingly:
            AgeSeal.open(p, me.dhSeed, me.dhPub, blob, AAD_TAG + myNick.encodeToByteArray())
        } catch (e: AgeException) {
            return Open.Rejected("open failed: ${e.message}")
        }
        val r = AgeCodec.Reader(signed)
        val body = r.bytes(); val sig = r.bytes()
        val pl = try { decodePayload(body) } catch (e: AgeException) { return Open.Rejected("decode: ${e.message}") }

        val hostKey = expectedHostSigPub ?: pl.hostSigPub  // TOFU fallback (weaker)
        if (expectedHostSigPub != null && !p.constantTimeEquals(expectedHostSigPub, pl.hostSigPub))
            return Open.Rejected("host key in invite != pinned host key")
        if (!p.verify(hostKey, SIGN_TAG + body, sig)) return Open.Rejected("bad host signature")

        if (now > pl.expiresAt) return Open.Rejected("invite expired")
        if (pl.members.none { it.fpHex.equals(myFpHex, ignoreCase = true) })
            return Open.Rejected("not a listed member")
        if (pl.groupKey.size != 32) return Open.Rejected("bad group key length")
        return Open.Ok(pl)
    }
}

/**
 * `AGE ...` PRIVMSG framing (spec §7). One sub-protocol verb space carried in PRIVMSG,
 * same lineage as CTCP/DCC; non-+AGE clients ignore it. Large blobs are base64'd and
 * chunked under the ~512-byte line limit, then reassembled.
 *
 * Line shapes:
 *   AGE IDENT 1 <b64(ed)> <b64(dh)> <createdAt> <b64(sig)>
 *   AGE INVITE <id> <i>/<n> <b64chunk>
 *   AGE MSG <gameId> <senderFp> <epoch> <seq> <b64(ct)>
 *   AGE REKEY <gameId> <epoch>
 */
object AgeWire {
    const val PREFIX = "AGE"
    /** Conservative per-chunk base64 budget, leaving room for "AGE INVITE <id> <i>/<n> " + IRC overhead. */
    const val CHUNK = 350

    fun ident(edPub: ByteArray, dhPub: ByteArray, createdAt: Long, sig: ByteArray): String =
        "AGE IDENT 1 ${AgeCodec.b64(edPub)} ${AgeCodec.b64(dhPub)} $createdAt ${AgeCodec.b64(sig)}"

    fun msg(m: AgeChannel.EncMessage): String =
        "AGE MSG ${m.gameId} ${m.senderFpHex} ${m.epoch} ${m.seq} ${AgeCodec.b64(m.ciphertext)}"

    /** Manual (typed) channel chat rides the same per-channel crypto as game moves, but on its own
     *  verb so it never collides with script `AGE MSG` traffic. Same 7-token layout. */
    fun chat(m: AgeChannel.EncMessage): String =
        "AGE CHAT ${m.gameId} ${m.senderFpHex} ${m.epoch} ${m.seq} ${AgeCodec.b64(m.ciphertext)}"

    fun parseChat(line: String): AgeChannel.EncMessage? {
        val t = line.trim().split(' ')
        if (t.size != 7 || t[0] != "AGE" || t[1] != "CHAT") return null
        return try {
            AgeChannel.EncMessage(t[2], t[3], t[4].toInt(), t[5].toInt(), AgeCodec.unb64(t[6]))
        } catch (_: Throwable) { null }
    }

    fun rekey(gameId: String, epoch: Int): String = "AGE REKEY $gameId $epoch"

    /** Parse an `AGE REKEY <gameId> <epoch>` line into (gameId, epoch), or null. */
    fun parseRekey(line: String): Pair<String, Int>? {
        val t = line.trim().split(' ')
        if (t.size != 4 || t[0] != "AGE" || t[1] != "REKEY") return null
        val epoch = t[3].toIntOrNull() ?: return null
        return t[2] to epoch
    }

    /** Split a big blob into AGE INVITE chunk lines. [id] correlates the chunks. */
    fun inviteChunks(id: String, blob: ByteArray): List<String> {
        val b64 = AgeCodec.b64(blob)
        val parts = b64.chunked(CHUNK)
        return parts.mapIndexed { i, c -> "AGE INVITE $id ${i + 1}/${parts.size} $c" }
    }

    /** Largest number of fragments we will emit or accept for one line (bounds memory / abuse). */
    const val FRAG_MAX = 64

    /**
     * Split any over-long `AGE ...` line into `AGE FRAG <id> <i>/<n> <b64chunk>` lines that the peer
     * reassembles before processing. The whole line is base64'd first, so a server that trims trailing
     * whitespace or normalises spacing can't corrupt a chunk. [id] correlates the pieces. Returns the
     * original single line unchanged when it already fits (caller decides via the length check).
     */
    fun fragChunks(id: String, line: String): List<String> {
        val b64 = AgeCodec.b64(line.toByteArray(Charsets.UTF_8))
        val parts = b64.chunked(CHUNK)
        return parts.mapIndexed { i, c -> "AGE FRAG $id ${i + 1}/${parts.size} $c" }
    }

    /** Parse one received MSG line into an EncMessage, or null if it isn't a well-formed AGE MSG. */
    fun parseMsg(line: String): AgeChannel.EncMessage? {
        // AGE MSG <gameId> <senderFp> <epoch> <seq> <b64ct>  => 7 tokens
        val t = line.trim().split(' ')
        if (t.size != 7 || t[0] != "AGE" || t[1] != "MSG") return null
        return try {
            AgeChannel.EncMessage(t[2], t[3], t[4].toInt(), t[5].toInt(), AgeCodec.unb64(t[6]))
        } catch (_: Throwable) { null }
    }

    /** Reassembles chunked AGE INVITE blobs keyed by (sender, id). */
    class Reassembler(private val maxChunks: Int = 64, private val maxInflight: Int = 32) {
        private data class Key(val sender: String, val id: String)
        // Insertion-ordered so the oldest partial can be evicted when too many ids are in flight.
        private val buffers = LinkedHashMap<Key, Array<String?>>()

        /** Feed a line. Returns the full blob bytes once all chunks for an id arrive, else null. */
        fun offer(sender: String, line: String): ByteArray? {
            val t = line.trim().split(' ', limit = 5)
            if (t.size != 5 || t[0] != "AGE" || t[1] != "INVITE") return null
            val id = t[2]
            val seq = t[3].split('/')
            if (seq.size != 2) return null
            val i = seq[0].toIntOrNull() ?: return null
            val n = seq[1].toIntOrNull() ?: return null
            if (n !in 1..maxChunks || i !in 1..n) return null
            val key = Key(sender, id)
            // Bound memory: a peer can spray INVITE chunks with distinct ids that never complete,
            // each allocating an n-slot array. Cap the number of partial reassemblies and drop the
            // oldest to make room rather than letting the map grow without limit.
            if (!buffers.containsKey(key) && buffers.size >= maxInflight) {
                buffers.keys.firstOrNull()?.let { buffers.remove(it) }
            }
            val arr = buffers.getOrPut(key) { arrayOfNulls(n) }
            if (arr.size != n) { buffers.remove(key); return null }   // inconsistent count
            arr[i - 1] = t[4]
            if (arr.any { it == null }) return null
            buffers.remove(key)
            return AgeCodec.unb64(arr.joinToString(""))
        }
    }
}
