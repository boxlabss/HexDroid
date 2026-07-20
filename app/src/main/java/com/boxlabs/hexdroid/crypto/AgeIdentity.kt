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

import com.boxlabs.hexdroid.data.SecretStore

/**
 * A long-term identity: two INDEPENDENT keypairs (spec §1).
 *   - sig*  : Ed25519, used only to sign/verify (authenticates who sent a move)
 *   - dh*   : X25519, used only via dh() (lets others seal secrets to you, and seeds
 *             the 1:1 handshake). Kept distinct from the signing key on purpose.
 *
 * Private material is the 32-byte seeds; never log or serialise them except through
 * [AgeKeystore], which wraps them at rest.
 */
class AgeIdentity(
    val sigSeed: ByteArray,
    val sigPub: ByteArray,
    val dhSeed: ByteArray,
    val dhPub: ByteArray,
) {
    /** Public half only — safe to share / pin. */
    fun publicBundle(): AgePublicIdentity = AgePublicIdentity(sigPub, dhPub)

    /** Serialise the FULL (private) identity for [AgeKeystore] to wrap. */
    fun serialize(): ByteArray =
        AgeCodec.Writer().u32(VERSION).bytes(sigSeed).bytes(sigPub).bytes(dhSeed).bytes(dhPub).build()

    companion object {
        const val VERSION = 1

        fun generate(p: AgePrimitives): AgeIdentity {
            val sigSeed = p.generateSeed()
            val dhSeed = p.generateSeed()
            return AgeIdentity(sigSeed, p.signingPublicKey(sigSeed), dhSeed, p.dhPublicKey(dhSeed))
        }

        fun deserialize(bytes: ByteArray): AgeIdentity {
            val r = AgeCodec.Reader(bytes)
            val v = r.u32(); if (v != VERSION) throw AgeException("identity version $v unsupported")
            return AgeIdentity(r.bytes(), r.bytes(), r.bytes(), r.bytes())
        }
    }
}

/** The public half of an identity: what you pin for a peer. */
class AgePublicIdentity(val sigPub: ByteArray, val dhPub: ByteArray) {
    fun serialize(): ByteArray = AgeCodec.Writer().bytes(sigPub).bytes(dhPub).build()
    companion object {
        fun deserialize(b: ByteArray): AgePublicIdentity {
            val r = AgeCodec.Reader(b); return AgePublicIdentity(r.bytes(), r.bytes())
        }
    }
}

/**
 * At-rest protection for the identity private keys (spec §2). Rather than wrap our own
 * Keystore envelope, we reuse [SecretStore] , the same Android-Keystore-AES-GCM envelope
 * that already protects SASL/server/proxy passwords and per-target E2E keys. This keeps
 * one audited storage path and one Keystore key-invalidation policy for the whole app.
 *
 * Requires the three `age:*` accessors added to SecretStore:
 *   getAgeIdentity()/setAgeIdentity()/clearAgeIdentity() and
 *   getAgePins()/setAgePins()/clearAgePins().
 *
 * Residual risk (documented): the seeds are software keys, in process memory while in use.
 */
class AgeIdentityStore(private val secrets: SecretStore) {

    /** Load the device identity, or null if none has been generated yet. */
    fun load(): AgeIdentity? =
        secrets.getAgeIdentity()?.let { AgeIdentity.deserialize(it) }

    fun store(identity: AgeIdentity) = secrets.setAgeIdentity(identity.serialize())

    /** Get-or-create the device identity. */
    fun loadOrCreate(p: AgePrimitives): AgeIdentity {
        val loaded = load()
        if (loaded != null) {
            // Derive the public halves from the seeds (the private keys are the source of truth) rather
            // than trusting the stored copies. A stored pub that has drifted from its seed - an older
            // key encoding, a partial write, a primitives change between app versions - would otherwise
            // make us announce a key we cannot actually use: peers seal replies to it and we can never
            // open them, which shows up as a permanent "openAck FAILED" handshake stall. Recomputing
            // repairs the identity in place.
            val fixed = AgeIdentity(loaded.sigSeed, p.signingPublicKey(loaded.sigSeed), loaded.dhSeed, p.dhPublicKey(loaded.dhSeed))
            if (!fixed.sigPub.contentEquals(loaded.sigPub) || !fixed.dhPub.contentEquals(loaded.dhPub)) store(fixed)
            return fixed
        }
        return AgeIdentity.generate(p).also { store(it) }
    }

    /** Persistence hooks for [AgeStore]'s TOFU pin table (integrity-protected by the envelope). */
    fun restorePins(): ByteArray? = secrets.getAgePins()
    fun persistPins(blob: ByteArray) = secrets.setAgePins(blob)
}

/**
 * TOFU pin store (spec §3). Pins peers BY KEY (fingerprint), not by nick
 * Surfaces the security-relevant transitions so the UI can warn.
 *
 * Persistence is injected ([persist]/[restore]) so this stays testable; back it with a
 * small file or your settings store. Entries are keyed by fingerprint hex.
 */
class AgeStore(
    private val p: AgePrimitives,
    private val restore: () -> ByteArray? = { null },
    private val persist: (ByteArray) -> Unit = {},
) {
    data class Pin(val identity: AgePublicIdentity, var lastNick: String, var verified: Boolean)

    private val pins = LinkedHashMap<String, Pin>()  // fpHex -> pin

    init { load() }

    enum class Result { PINNED_NEW, MATCH, NICK_UPDATED, CONFLICT_KEY_FOR_NICK }

    /**
     * Observe a peer's announced identity under [nick].
     *  - PINNED_NEW            : first time we've seen this key — pinned (TOFU).
     *  - MATCH                 : key already pinned, same nick.
     *  - NICK_UPDATED          : key already pinned, peer now using a different nick.
     *  - CONFLICT_KEY_FOR_NICK : a DIFFERENT key arrived for a nick we already pinned a
     *                            key for — likely nick-takeover / MitM. Caller must warn
     *                            and must NOT silently trust it.
     */
    fun observe(nick: String, identity: AgePublicIdentity): Result {
        val fp = AgeFingerprint.hex(AgeFingerprint.of(p, identity))
        val existing = pins[fp]
        if (existing != null) {
            return if (existing.lastNick.equals(nick, ignoreCase = true)) Result.MATCH
            else { existing.lastNick = nick; save(); Result.NICK_UPDATED }
        }
        // New key. Is there already a pin for this nick under a *different* key?
        val nickClash = pins.values.any { it.lastNick.equals(nick, ignoreCase = true) }
        if (nickClash) {
            // Do NOT pin. A different key for a nick we already hold a key for is the one case the
            // caller must not treat as routine: it is a reinstall / second device, or a nick takeover,
            // and the two are indistinguishable from here. Pinning first and reporting the conflict
            // afterwards (as this used to) meant a silent trust decision had already been made by the
            // time anyone could object. The caller decides via [pinConfirmed].
            return Result.CONFLICT_KEY_FOR_NICK
        }
        pins[fp] = Pin(identity, nick, verified = false)
        save()
        return Result.PINNED_NEW
    }

    /**
     * Pin [identity] for [nick] after the user has explicitly accepted it, overriding the
     * conflict that [observe] refused to resolve on its own. Any older pin still bound to [nick]
     * is dropped, so a nick maps to exactly one key and a stale key cannot linger and re-verify.
     * Only ever call this from a path where a human actually agreed.
     */
    fun pinConfirmed(nick: String, identity: AgePublicIdentity) {
        val fp = AgeFingerprint.hex(AgeFingerprint.of(p, identity))
        pins.entries.removeAll { (f, pin) -> f != fp && pin.lastNick.equals(nick, ignoreCase = true) }
        pins[fp] = Pin(identity, nick, verified = false)
        save()
    }

    /** Fingerprint currently pinned for [nick], if any. */
    fun fingerprintForNick(nick: String): String? =
        pins.entries.firstOrNull { it.value.lastNick.equals(nick, ignoreCase = true) }?.key

    fun lookupByFingerprint(fpHex: String): AgePublicIdentity? = pins[fpHex]?.identity

    /** The pinned fingerprint whose signing key is [sigPub], or null if none is pinned. Lets an
     *  invite adopter turn the payload's hostSigPub back into the host's fingerprint (the invite
     *  proves the signing key but a fingerprint hashes BOTH keys, so it cannot be derived from
     *  the payload alone). */
    fun fingerprintForSigPub(sigPub: ByteArray): String? =
        pins.entries.firstOrNull { p.constantTimeEquals(it.value.identity.sigPub, sigPub) }?.key

    /** Returns null if NO pin or if MULTIPLE keys claim this nick (a conflict — caller must
     *  resolve by fingerprint, never guess which key is real). */
    fun lookupByNick(nick: String): AgePublicIdentity? {
        val matches = pins.values.filter { it.lastNick.equals(nick, ignoreCase = true) }
        return if (matches.size == 1) matches[0].identity else null
    }

    fun isVerified(fpHex: String): Boolean = pins[fpHex]?.verified == true

    /** Call after the user compares fingerprints out of band. */
    fun markVerified(fpHex: String) { pins[fpHex]?.let { it.verified = true; save() } }

    private fun save() {
        val w = AgeCodec.Writer().u32(pins.size)
        for ((fp, pin) in pins) {
            w.str(fp).bytes(pin.identity.serialize()).str(pin.lastNick).u32(if (pin.verified) 1 else 0)
        }
        persist(w.build())
    }

    private fun load() {
        val data = restore() ?: return
        val r = AgeCodec.Reader(data)
        repeat(r.u32()) {
            val fp = r.str()
            val id = AgePublicIdentity.deserialize(r.bytes())
            val nick = r.str(); val verified = r.u32() == 1
            pins[fp] = Pin(id, nick, verified)
        }
    }
}

/**
 * Identity announcement (spec §3): build and verify a signed `AGE IDENT` line. This is the
 * entry point to trust-on-first-use, a peer announces {edPub, dhPub, createdAt} with an
 * Ed25519 signature that binds all three together, so an active attacker can't swap the DH
 * key under a victim's signing key in transit. The verified [AgePublicIdentity] is then handed
 * to [AgeStore.observe] for pinning.
 *
 * Wire (spec §7):  AGE IDENT 1 <b64(edPub)> <b64(dhPub)> <createdAt> <b64(sig)>
 *   sig = Ed25519_sign( sigSeed, TLV("hexdroid/+AGE/ident/v1", edPub, dhPub, u64(createdAt)) )
 */
object AgeIdent {
    private val LABEL = "hexdroid/+AGE/ident/v1".encodeToByteArray()

    private fun preimage(edPub: ByteArray, dhPub: ByteArray, createdAt: Long): ByteArray =
        AgeCodec.Writer().bytes(LABEL).bytes(edPub).bytes(dhPub).u64(createdAt).build()

    data class Parsed(val identity: AgePublicIdentity, val createdAt: Long)

    /** Build the signed `AGE IDENT` line announcing [me]. */
    fun announce(p: AgePrimitives, me: AgeIdentity, createdAt: Long): String {
        val sig = p.sign(me.sigSeed, preimage(me.sigPub, me.dhPub, createdAt))
        return AgeWire.ident(me.sigPub, me.dhPub, createdAt, sig)
    }

    /**
     * Parse + verify an inbound `AGE IDENT` line. Returns null — meaning **do not pin** — if the
     * line is malformed or the signature doesn't bind (edPub, dhPub, createdAt). On success the
     * caller passes [Parsed.identity] to [AgeStore.observe] and acts on the returned transition.
     */
    fun parse(p: AgePrimitives, line: String): Parsed? {
        val t = line.trim().split(' ')
        if (t.size != 7 || t[0] != "AGE" || t[1] != "IDENT" || t[2] != "1") return null
        val ed = runCatching { AgeCodec.unb64(t[3]) }.getOrNull() ?: return null
        val dh = runCatching { AgeCodec.unb64(t[4]) }.getOrNull() ?: return null
        val createdAt = t[5].toLongOrNull() ?: return null
        val sig = runCatching { AgeCodec.unb64(t[6]) }.getOrNull() ?: return null
        if (!p.verify(ed, preimage(ed, dh, createdAt), sig)) return null
        return Parsed(AgePublicIdentity(ed, dh), createdAt)
    }
}
