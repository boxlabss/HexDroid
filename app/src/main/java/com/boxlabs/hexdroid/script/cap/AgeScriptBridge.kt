/*
 * HexDroid — AgeScriptBridge
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.boxlabs.hexdroid.script.cap

import com.boxlabs.hexdroid.crypto.AgeCodec
import com.boxlabs.hexdroid.crypto.AgeFingerprint
import com.boxlabs.hexdroid.crypto.AgeHandshake
import com.boxlabs.hexdroid.crypto.AgeRatchet
import com.boxlabs.hexdroid.crypto.AgeIdent
import com.boxlabs.hexdroid.crypto.AgeIdentity
import com.boxlabs.hexdroid.crypto.AgeInvite
import com.boxlabs.hexdroid.crypto.AgePrimitives
import com.boxlabs.hexdroid.crypto.AgePublicIdentity
import com.boxlabs.hexdroid.crypto.AgeStore
import com.boxlabs.hexdroid.crypto.AgeWire

/**
 * The encrypted-transport sibling of the loopback in [com.boxlabs.hexdroid.IrcViewModel].
 *
 * Owns one [AgeScriptCapabilities] for a network and turns a script's `age.*` calls into real
 * +AGE wire traffic, and inbound +AGE wire lines back into `age_msg` / `age_deal` script events.
 * It also performs the two things `AgeScriptCapabilities` deliberately does NOT invent itself:
 *
 *   1. **identity exchange** — pins peers' public keys (`AGE IDENT`) so signatures verify and
 *      deals can be sealed to them;
 *   2. **group-key establishment** — the host mints K_G and distributes it via signed+sealed
 *      `AGE INVITE` blobs ([AgeInvite]); invitees open them and remember K_G per channel.
 *
 * The VM constructs one of these per connected network and feeds it:
 *   - [sendPrivmsg]  — `(channel, line) -> Unit`, ships one wire line as a PRIVMSG to a channel;
 *   - [raiseSignal]  — `(event, fields, args) -> Unit`, raises a script SIGNAL synchronously.
 *
 * Routing note: `AgeScriptCapabilities.send` hands us a bare `AGE …` line with no IRC target.
 * `AGE MSG` carries its channel as `gameId` (token 2), so we route those precisely; everything
 * else (`DEAL` / `IDENT` / `INVITE`) is sent to [activeChannel], the channel of the current game.
 * A game occupies one channel at a time per network, which is the assumption to revisit if you
 * ever run two scripted tables on one network simultaneously.
 */
class AgeScriptBridge(
    private val p: AgePrimitives,
    private val me: AgeIdentity,
    private val store: AgeStore,
    private val sendPrivmsg: (channel: String, line: String) -> Unit,
    private val raiseSignal: (event: String, fields: Map<String, String>, args: List<String>) -> Unit,
    private val myNick: () -> String,
    private val deliverChat: (channel: String, fromNick: String, text: String) -> Unit = { _, _, _ -> },
    /**
     * Called after held PM messages for a peer are drained to the wire (the ratchet came up, or the
     * grace period self-keyed them). Lets the UI clear the "pending" state on the local echoes it
     * showed while the messages were held, so an optimistic echo can't stay looking un-sent forever.
     */
    private val onPmFlushed: (peerNick: String) -> Unit = {},
    /**
     * Durable store-and-forward for the held-PM outbox. [saveOutbox] persists the serialised queue
     * (encrypted at rest by the caller) after every change; [loadOutbox] returns the last persisted
     * blob at construction so messages queued before an app kill survive to be flushed later. Both
     * default to no-ops (memory-only) so tests and solo/practice paths need no storage wiring.
     */
    private val saveOutbox: (blob: String) -> Unit = {},
    private val loadOutbox: () -> String? = { null },
    /** Diagnostic hook: receives one-line strings tracing the PM handshake, so a field log can show
     *  exactly where a handshake stalls (who was/wasn't enabled, which frame failed to open, etc.).
     *  No-op by default. */
    private val debug: (String) -> Unit = {},
    /**
     * PM handshake state changes for the UI to surface (fail-loud instead of a silent stall).
     * [state] is one of "NEGOTIATING", "ESTABLISHED", "FAILED"; [detail] is a short human reason for
     * FAILED. Fired only on an actual change, so callers can print one status line per transition.
     */
    private val onPmState: (peerNick: String, state: String, detail: String) -> Unit = { _, _, _ -> },
    /**
     * A PM peer announced +AGE (sent us their IDENT) while we have NOT enabled +AGE for that
     * conversation. Lets the UI prompt the user to turn it on, so encryption can actually be
     * negotiated instead of one side waiting forever for the other to notice. Fired once per peer.
     */
    private val onPmInterest: (peerNick: String) -> Unit = {},
    /**
     * A peer announced a DIFFERENT +AGE key than the one already pinned for their nick. Not pinned:
     * the identity is held pending until the user accepts ([trustPendingIdent]) or rejects
     * ([rejectPendingIdent]). Benign causes (reinstall, second device) look identical to a nick
     * takeover from here, so this is a decision only the user can make. [target] is the channel or
     * PM the announce arrived on, so the prompt can be shown where the user is actually looking.
     */
    private val onIdentConflict: (target: String, nick: String, newFp: String, pinnedFp: String) -> Unit =
        { _, _, _, _ -> },
) {
    val myFp: String = AgeFingerprint.hex(AgeFingerprint.of(p, me.publicBundle()))

    /** channel -> K_G (group key). Present once the channel is keyed (host minted, or invite opened). */
    private val groupKeys = HashMap<String, ByteArray>()

    /**
     * channel -> the Ed25519 signing key that minted the current K_G (our own when we host/self-key,
     * or the invite's owner key when we adopt one). Used to authorise a re-key: an already-established
     * channel is only rotated to a new key by its current minter, or (for a hostless chat channel) by
     * the deterministically-elected owner. This closes the group-key-injection hole where any peer who
     * has seen our public IDENT could self-sign an invite and rotate us onto a key they control.
     */
    private val keyMinter = HashMap<String, ByteArray>()

    /** The channel of the current game, used to route DEAL/IDENT/INVITE sends that carry no channel. */
    @Volatile private var activeChannel: String = ""

    /** Reassembly buffers for chunked AGE INVITE: id -> (parts, received-count). */
    private val inviteBufs = HashMap<String, Array<String?>>()
    private var inviteSeq = 0
    /** Reassembly buffers for AGE FRAG (generic over-long-line fragmentation): "from\u0000id" -> parts. */
    private val fragBufs = HashMap<String, Array<String?>>()
    private var fragSeq = 0

    /** channel -> (memberFp -> nick), built from inbound AGE IDENT. A host keys the table from this. */
    private val roster = HashMap<String, LinkedHashMap<String, String>>()

    /** Channels the user has turned on +AGE for as manual (typed) Secure Chat. */
    private val chatChannels = HashSet<String>()
    /** (channel\u0000fp) we have re-announced to, so each new peer pins us exactly once. */
    private val announcedBack = HashSet<String>()
    /** Channels we have already proactively announced our identity on (enable/join/host). Lets
     *  announceIdentOnce() stay idempotent so enabling +AGE doesn't emit IDENT twice, while the
     *  per-peer reciprocal announce-back below still forces a fresh IDENT for each new peer. */
    private val announcedSelf = HashSet<String>()
    /**
     * Lower-case nicks that joined a channel AFTER our most recent IDENT there, so they cannot have
     * seen it. Only these need a reciprocal announce-back: a peer who was already in the channel when
     * we announced has our identity, and announcing again at them is pure duplicate traffic (the
     * "+AGE announces IDENT twice" case, which is two clients announcing and then each answering an
     * announce the other had already received). Cleared whenever we announce, because a fresh IDENT
     * reaches everyone currently present.
     */
    private val joinedSinceAnnounce = HashMap<String, MutableSet<String>>()
    /** (channel\u0000fp) we have already sealed the current K_G to, so re-announces don't re-invite. */
    private val invited = HashSet<String>()

    private val caps = AgeScriptCapabilities(
        p = p,
        me = me,
        myFp = myFp,
        store = store,
        keyFor = { fp -> store.lookupByFingerprint(fp)?.let { it.sigPub to it.dhPub } },
        groupKeyFor = { channel -> groupKeys[channel] },
        send = { line -> routeSend(line) },
        raiseSignal = raiseSignal,
    )

    /** Identities a peer announced that clash with the key pinned for their nick, awaiting the
     *  user's decision. Keyed by lower-case nick; only the newest announce per nick is held. */
    private val pendingIdent = HashMap<String, AgePublicIdentity>()

    /** Fingerprint of the identity [nick] is currently waiting on a decision for, or null. */
    fun pendingIdentFp(nick: String): String? =
        pendingIdent[nick.lowercase()]?.let { AgeFingerprint.hex(AgeFingerprint.of(p, it)) }

    /** Nicks currently awaiting an identity decision. */
    fun pendingIdentNicks(): List<String> = pendingIdent.keys.toList()

    /** User accepted [nick]'s new key: pin it, replacing the old one, and let traffic flow again. */
    fun trustPendingIdent(nick: String): Boolean {
        val id = pendingIdent.remove(nick.lowercase()) ?: return false
        store.pinConfirmed(nick, id)
        val fp = AgeFingerprint.hex(AgeFingerprint.of(p, id))
        // Re-attach the newly trusted key wherever this nick is a member, so their messages verify
        // without needing a rejoin: the roster is keyed by fingerprint, so drop the stale entry first.
        roster.forEach { (channel, members) ->
            if (members.values.any { it.equals(nick, ignoreCase = true) }) {
                members.entries.removeAll { (f, n) -> n.equals(nick, ignoreCase = true) && f != fp }
                members[fp] = nick
                runCatching { caps.addMember(channel, fp) }
            }
        }
        return true
    }

    /** User rejected [nick]'s new key: forget it. The previously pinned key stays authoritative. */
    fun rejectPendingIdent(nick: String): Boolean = pendingIdent.remove(nick.lowercase()) != null

    /** True once `age.send` on [channel] can actually encrypt+transmit (K_G established). */
    fun ready(channel: String): Boolean = groupKeys.containsKey(channel)

    /** True once [channel] is keyed for manual chat (same condition as [ready]). */
    fun chatReady(channel: String): Boolean = groupKeys.containsKey(channel)

    /**
     * Self-key [target] (a channel or a PM nick) with a fresh K_G if it isn't keyed yet, so the client
     * can send `AGE CHAT` immediately even when no secure session is ready. Used for a PM whose peer
     * runs no +AGE client (a double ratchet can't be established without their key) or whose handshake
     * is still pending. The message goes on the wire as ciphertext, garbled to anyone lacking K_G, and
     * decrypts locally for our own echo. Fail-closed: plaintext is never transmitted. When a real +AGE
     * peer later appears, the ratchet path ([pmReady]/[sendPm]) is preferred for its forward secrecy.
     */
    fun ensureSelfKeyed(target: String) {
        if (target.isEmpty() || groupKeys.containsKey(target)) return
        chatChannels.add(target)
        if (activeChannel.isEmpty()) activeChannel = target
        caps.resetChannel(target)          // drop any stale channel object before keying afresh
        groupKeys[target] = p.randomBytes(32)
        keyMinter[target] = me.sigPub
    }

    /** Turn on manual +AGE for [channel]: announce our identity and start hostless key agreement. */
    fun enableChat(channel: String) {
        if (channel.isEmpty()) return
        chatChannels.add(channel)
        activeChannel = channel
        announceIdentOnce(channel)
        onChatPeer(channel, myFp, myNick())
    }

    fun disableChat(channel: String) {
        chatChannels.remove(channel)
        announcedSelf.remove(channel)
        announcedBack.removeAll { it.startsWith("$channel\u0000") }
        joinedSinceAnnounce.remove(channel)
    }

    /** Tear down a game channel's +AGE state so a closed table leaves nothing keyed or half-announced:
     *  a fresh open re-announces, re-elects, and re-keys from scratch instead of reusing stale state.
     *  Does NOT itself touch the outbound queue; the caller decides whether to purge queued traffic. */
    fun closeGame(channel: String) {
        if (channel.isEmpty()) return
        groupKeys.remove(channel)
        keyMinter.remove(channel)
        roster.remove(channel)
        announcedSelf.remove(channel)
        announcedBack.removeAll { it.startsWith("$channel\u0000") }
        joinedSinceAnnounce.remove(channel)
        invited.removeAll { it.startsWith("$channel\u0000") }
        liveIdentFp.remove(channel)
        runCatching { caps.resetChannel(channel) }
        if (activeChannel == channel) activeChannel = ""
    }

    // ---- 1:1 PM sessions (X3DH handshake + double ratchet) ---------------------
    // A +AGE private conversation keeps a per-peer ratchet established by a 2-message handshake
    // (HELLO/ACK). The deterministic initiator (lower fingerprint) sends HELLO once both sides have
    // exchanged identities. Sessions are in-memory: a restart re-handshakes on the next enable.
    private class PmSession(val peerNick: String, val peerFp: String) {
        var ekSelf: AgeHandshake.Ephemeral? = null   // our HELLO ephemeral (initiator side)
        var ackEk: AgeHandshake.Ephemeral? = null    // our ACK ephemeral (responder side), kept so a
                                                     // retried HELLO can be re-ACKed without a rebuild
        var ratchet: AgeRatchet? = null
        var helloSent = false
    }
    private val pmPeers = HashSet<String>()
    // Peers whose +AGE IDENT we have received LIVE on the wire this session, mapped to the exact
    // fingerprint they announced. Only these are known to be online and running +AGE right now, so
    // only these are safe to drive a catch-up HELLO toward when we enable - and we drive it against
    // this freshly announced fingerprint, never a nick lookup (which returns null the moment two keys
    // have ever claimed the same nick, e.g. after the peer reinstalled once). A pin left over from an
    // earlier session does NOT prove the peer is here now.
    private val liveIdentFp = HashMap<String, String>()
    // Fail-loud bookkeeping: last state we reported per peer (so we only surface transitions), and the
    // peers we've already prompted the user about after they announced +AGE while we were off.
    private val pmLastState = HashMap<String, String>()
    private val pmInterestNotified = HashSet<String>()
    // Peers for which a handshake frame failed to VERIFY/decrypt (bad signature or unopenable seal).
    // That is deterministic - re-sending HELLO/IDENT can't fix it and would just flood the server - so
    // the reliability layer stops retrying once a peer is in here, and we surface FAILED instead.
    private val pmVerifyFailed = HashSet<String>()
    private val pmByFp = HashMap<String, PmSession>()
    private val pmByNick = HashMap<String, PmSession>()

    /** Report a PM handshake transition once (dedups repeats of the same state). */
    private fun pmState(peerNick: String, state: String, detail: String = "") {
        when (state) {
            "FAILED" -> pmVerifyFailed.add(peerNick)        // deterministic: stop the retry loop
            "ESTABLISHED" -> pmVerifyFailed.remove(peerNick) // recovered (e.g. peer fixed its identity)
        }
        if (pmLastState[peerNick] == state) return
        pmLastState[peerNick] = state
        onPmState(peerNick, state, detail)
    }

    /** A nick, not an IRC channel (channels start with a CHANTYPES sigil). */
    private fun isPmName(name: String): Boolean = name.firstOrNull() !in setOf('#', '&', '+', '!')

    /**
     * Held outbound PM plaintext per peer, queued while we wait to learn whether the peer runs a
     * +AGE client. Flushed through the ratchet once the handshake completes (so the peer decrypts it
     * with full forward secrecy), or self-keyed as `AGE CHAT` if the grace period lapses with no
     * `AGE IDENT` from them. This is the IRC analogue of Signal sending to a not-yet-established
     * session: we can't pre-fetch a prekey bundle (no server), but both clients are online, so we
     * hold for the live handshake instead of racing past it under a key the peer will never hold.
     */
    private val pmOutbox = HashMap<String, MutableList<Held>>()
    /** A held message plus the wall-clock time it was queued, used for the persistence TTL. */
    private class Held(val t: Long, val text: String)
    /** Guards all [pmOutbox] access so a persist snapshot can't race a hold/flush mutation. */
    private val outboxLock = Any()
    /** Held messages older than this are dropped on load, so nothing ancient silently delivers later. */
    private val outboxTtlMs = 7L * 24 * 60 * 60 * 1000

    init {
        // Rehydrate any messages that were held when the process was last killed, so they can flush
        // once +AGE re-establishes with the peer. Best-effort: a corrupt/undecryptable blob is ignored.
        runCatching {
            val blob = loadOutbox() ?: return@runCatching
            val r = AgeCodec.Reader(AgeCodec.unb64(blob))
            val now = System.currentTimeMillis()
            val peers = r.u32()
            synchronized(outboxLock) {
                repeat(peers) {
                    val peer = r.str()
                    val n = r.u32()
                    val kept = ArrayList<Held>(n)
                    repeat(n) {
                        val t = r.u64()
                        val text = r.str()
                        if (now - t <= outboxTtlMs) kept.add(Held(t, text))
                    }
                    if (kept.isNotEmpty()) pmOutbox[peer] = kept
                }
            }
        }
    }

    /** Serialise [pmOutbox] and hand it to [saveOutbox] for encrypted-at-rest persistence. */
    private fun persistOutbox() {
        val blob = synchronized(outboxLock) {
            val w = AgeCodec.Writer().u32(pmOutbox.size)
            for ((peer, q) in pmOutbox) {
                w.str(peer).u32(q.size)
                for (h in q) w.u64(h.t).str(h.text)
            }
            AgeCodec.b64(w.build())
        }
        runCatching { saveOutbox(blob) }
    }
    /** Peers we've decided run no +AGE client, so their PMs self-key immediately (no more holding). */
    private val pmSelfKeyed = HashSet<String>()

    /** Per-conversation associated data, identical on both sides (sorted fingerprints). */
    private fun pmAd(peerFp: String): ByteArray =
        listOf(myFp.lowercase(), peerFp.lowercase()).sorted().joinToString("|").encodeToByteArray()

    /** Turn on +AGE for a 1:1 PM with [peerNick]: advertise identity; handshake starts on IDENT exchange. */
    fun enablePm(peerNick: String) {
        if (peerNick.isEmpty()) return
        pmPeers.add(peerNick)
        activeChannel = peerNick
        pmVerifyFailed.remove(peerNick)   // fresh attempt: let the reliability layer retry again
        pmInterestNotified.add(peerNick)   // we've acted on their interest; no need to prompt anymore
        if (!pmReady(peerNick)) pmState(peerNick, "NEGOTIATING")
        announceIdent(peerNick)
        // Catch-up: if this peer already announced their IDENT LIVE earlier this session (they enabled
        // first, so the inbound IDENT handler ran once and will not run again for that line), drive the
        // handshake now so the deterministic initiator still emits HELLO - against the exact fingerprint
        // they announced. Gated on a live sighting: a bare stored pin from an earlier session is NOT
        // enough, because the peer may be offline now - firing a HELLO into the void would latch
        // helloSent and deadlock once they do come back. When they are absent we simply wait; their
        // live IDENT will drive onPmIdent the moment they announce. onPmIdent is idempotent
        // (helloSent / ratchet guards) and only the lower-fingerprint side emits.
        val liveFp = liveIdentFp[peerNick]
        debug("enablePm $peerNick liveSeen=${liveFp != null} me=${myFp.take(8)}")
        if (liveFp != null) onPmIdent(peerNick, liveFp)
    }

    fun disablePm(peerNick: String) {
        pmPeers.remove(peerNick)
        pmByNick.remove(peerNick)?.let { pmByFp.remove(it.peerFp) }
    }

    /** True once the ratchet with [peerNick] is established (handshake complete). */
    fun pmReady(peerNick: String): Boolean = pmByNick[peerNick]?.ratchet != null

    /**
     * Reliability layer: re-drive an incomplete PM handshake. The host calls this on a bounded timer
     * (every few seconds) until it returns false, so a lost or mistimed IDENT/HELLO/ACK recovers on its
     * own instead of stranding the conversation. Every step is idempotent and reuses existing keys, so
     * repeated calls never desync an established or in-flight session.
     *
     * Returns true while the handshake is still pending (keep retrying), false once it is established or
     * no longer enabled (stop).
     */
    fun retryPmHandshake(peerNick: String): Boolean {
        if (peerNick !in pmPeers) return false
        if (pmReady(peerNick)) return false
        if (peerNick in pmVerifyFailed) return false   // deterministic failure: retrying only floods
        // Re-announce so the peer (re)learns we are here and +AGE-capable; this is how a side that
        // missed the other's first IDENT eventually picks it up.
        announceIdent(peerNick)
        val s = pmByNick[peerNick]
        val liveFp = liveIdentFp[peerNick]
        when {
            // Initiator waiting on an ACK: re-send the SAME HELLO (same ephemeral) to prompt a fresh ACK.
            s != null && s.helloSent && s.ekSelf != null -> {
                val pub = store.lookupByFingerprint(s.peerFp)
                val hello = if (pub != null) runCatching { AgeHandshake.buildHello(p, me, s.ekSelf!!, pub.dhPub) }.getOrNull() else null
                if (hello != null) { emit(peerNick, "AGE HELLO ${AgeCodec.b64(hello)}"); debug("retry $peerNick re-sent HELLO") }
            }
            // We have the peer's live key but haven't emitted HELLO yet (initiator that hadn't fired, or
            // a session not built): drive it. onPmIdent is a no-op for the responder side.
            liveFp != null -> { debug("retry $peerNick drive from liveFp"); onPmIdent(peerNick, liveFp) }
            else -> debug("retry $peerNick awaiting peer IDENT")
        }
        return true
    }

    /** True once we've received an `AGE IDENT` from [peerNick], i.e. they demonstrably run +AGE and a
     *  ratchet handshake is possible (even if not yet complete). Drives the grace-period decision. */
    fun pmIdentSeen(peerNick: String): Boolean = pmByNick[peerNick] != null

    /**
     * Send [text] to [peerNick] if we can do so meaningfully now, otherwise hold it:
     *   - ratchet established        -> encrypt + send over the ratchet immediately;
     *   - already decided non-+AGE   -> self-key + send as `AGE CHAT` immediately;
     *   - otherwise                  -> queue it (the caller schedules a grace timeout that resolves
     *                                   to one of the two flush paths).
     * Returns false only on a hard failure; true means sent-or-accepted-for-hold (the caller may echo
     * the message locally either way, since it's our own text).
     */
    /** Outcome of [sendOrHoldPm]: went on the wire now, held for later, or failed outright. */
    enum class PmSend { SENT, HELD, FAILED }

    fun sendOrHoldPm(peerNick: String, text: String): PmSend {
        if (pmByNick[peerNick]?.ratchet != null)
            return if (sendPm(peerNick, text)) PmSend.SENT else PmSend.FAILED
        if (peerNick in pmSelfKeyed) {
            ensureSelfKeyed(peerNick)
            return if (sendChat(peerNick, text)) PmSend.SENT else PmSend.FAILED
        }
        synchronized(outboxLock) {
            pmOutbox.getOrPut(peerNick) { mutableListOf() }.add(Held(System.currentTimeMillis(), text))
        }
        persistOutbox()   // durable: survive an app kill during the hold window
        return PmSend.HELD
    }

    /** Flush any held messages for [peerNick] through the now-ready ratchet, in order. */
    private fun flushPmOutboxRatchet(peerNick: String) {
        pmSelfKeyed.remove(peerNick)
        val q = synchronized(outboxLock) { pmOutbox.remove(peerNick) } ?: return
        // Track any send that fails so a failure can never masquerade as a delivery. With the ratchet
        // now able to open from either side this should not happen, but if a send does fail we re-hold
        // the message rather than dropping it and (below) skip clearing the pending echo, so the UI
        // never shows an un-sent line as sent.
        val failed = ArrayList<Held>()
        for (h in q) if (!sendPm(peerNick, h.text)) failed.add(h)
        if (failed.isNotEmpty()) synchronized(outboxLock) {
            pmOutbox.getOrPut(peerNick) { mutableListOf() }.addAll(0, failed)
        }
        persistOutbox()
        if (failed.isEmpty()) onPmFlushed(peerNick)   // everything really went out: clear pending echoes
    }

    /**
     * The grace period lapsed without a usable ratchet: treat [peerNick] as a non-+AGE peer, self-key,
     * and flush the held messages as `AGE CHAT` (ciphertext, garbled to them, readable in our echo).
     * No-op if the ratchet came up first (its own flush already drained the outbox). Future PMs to this
     * peer self-key immediately until a real ratchet later supersedes them.
     */
    fun flushPmOutboxSelfKey(peerNick: String) {
        if (pmByNick[peerNick]?.ratchet != null) return
        pmSelfKeyed.add(peerNick)
        val q = synchronized(outboxLock) { pmOutbox.remove(peerNick) } ?: return
        ensureSelfKeyed(peerNick)
        for (h in q) sendChat(peerNick, h.text)
        persistOutbox()
        onPmFlushed(peerNick)   // held echoes are now on the wire (as self-keyed AGE CHAT): clear pending
    }

    /** True if +AGE is already running for [target] (channel or PM). Used to resume after a restart. */
    fun isActive(target: String): Boolean = target in chatChannels || target in pmPeers

    /** Encrypt + ship [text] to [peerNick] over the ratchet. False (fail closed) if no session yet. */
    fun sendPm(peerNick: String, text: String): Boolean {
        val s = pmByNick[peerNick] ?: return false
        val r = s.ratchet ?: return false
        val m = runCatching { r.encrypt(text.encodeToByteArray(), pmAd(s.peerFp)) }.getOrNull() ?: return false
        emit(peerNick, "AGE PM ${AgeCodec.b64(m.headerDhPub)} ${m.pn} ${m.n} ${AgeCodec.b64(m.ciphertext)}")
        return true
    }

    private fun pmSessionFor(peerNick: String, peerFp: String): PmSession =
        pmByFp[peerFp] ?: PmSession(peerNick, peerFp).also { pmByFp[peerFp] = it; pmByNick[peerNick] = it }

    /** On a PM peer's IDENT: if we are the deterministic initiator, send HELLO. */
    private fun onPmIdent(peerNick: String, peerFp: String) {
        if (peerNick !in pmPeers) return
        val s = pmSessionFor(peerNick, peerFp)
        if (s.ratchet != null || s.helloSent) return
        val initiator = myFp.lowercase() < peerFp.lowercase()
        debug("onPmIdent $peerNick fp=${peerFp.take(8)} initiator=$initiator")
        if (initiator) {
            val pub = store.lookupByFingerprint(peerFp) ?: run { debug("onPmIdent $peerNick NO PIN for fp"); return }
            val ek = AgeHandshake.newEphemeral(p)
            s.ekSelf = ek; s.helloSent = true
            val hello = runCatching { AgeHandshake.buildHello(p, me, ek, pub.dhPub) }.getOrNull()
                ?: run { debug("onPmIdent $peerNick buildHello FAILED"); return }
            emit(peerNick, "AGE HELLO ${AgeCodec.b64(hello)}")
            debug("onPmIdent $peerNick HELLO sent")
        }
    }

    private fun onPmHello(peerNick: String, line: String) {
        if (peerNick !in pmPeers) { debug("onPmHello $peerNick IGNORED not-pm-enabled"); return }
        val blob = runCatching { AgeCodec.unb64(line.split(' ').getOrNull(2).orEmpty()) }.getOrNull()
            ?: run { debug("onPmHello $peerNick bad blob"); pmState(peerNick, "FAILED", "malformed HELLO"); return }
        // If we already pinned this peer (via their IDENT), require the HELLO to carry that same identity,
        // so an attacker cannot race a HELLO with a different identity and hijack the conversation.
        val pinnedSig = pmByNick[peerNick]?.peerFp?.let { store.lookupByFingerprint(it)?.sigPub }
        val opened = runCatching { AgeHandshake.openHello(p, me, blob, pinnedSig) }.getOrNull()
            ?: run {
                debug("onPmHello $peerNick openHello FAILED (pinnedSig=${pinnedSig != null})")
                pmState(peerNick, "FAILED", "couldn't verify peer identity"); return
            }
        val peerId = opened.initiator
        val peerFp = AgeFingerprint.hex(AgeFingerprint.of(p, peerId))
        if (store.observe(peerNick, peerId) == AgeStore.Result.CONFLICT_KEY_FOR_NICK) {
            pendingIdent[peerNick.lowercase()] = peerId
            onIdentConflict(
                peerNick, peerNick,
                AgeFingerprint.hex(AgeFingerprint.of(p, peerId)),
                store.fingerprintForNick(peerNick).orEmpty(),
            )
            return
        }
        val s = pmSessionFor(peerNick, peerFp)
        // Retry path: we already established this responder session but the initiator is HELLOing again,
        // which means our ACK never reached them. Re-send the SAME ACK (same stored ephemeral) so they
        // can finish; do NOT rebuild the ratchet, which would desync our established session.
        val existingEk = s.ackEk
        if (s.ratchet != null && existingEk != null) {
            val ack = runCatching { AgeHandshake.buildAck(p, me, existingEk, peerId.dhPub) }.getOrNull()
                ?: run { debug("onPmHello $peerNick re-buildAck FAILED"); return }
            emit(peerNick, "AGE ACK ${AgeCodec.b64(ack)}")
            debug("onPmHello $peerNick duplicate HELLO, re-sent ACK")
            return
        }
        val ek = AgeHandshake.newEphemeral(p)
        s.ackEk = ek
        s.ratchet = runCatching { AgeHandshake.responderSession(p, me, ek, peerId.dhPub, opened.ekAPub) }.getOrNull()
            ?: run { debug("onPmHello $peerNick responderSession FAILED"); pmState(peerNick, "FAILED", "key agreement failed"); return }
        val ack = runCatching { AgeHandshake.buildAck(p, me, ek, peerId.dhPub) }.getOrNull()
            ?: run { debug("onPmHello $peerNick buildAck FAILED"); pmState(peerNick, "FAILED", "key agreement failed"); return }
        emit(peerNick, "AGE ACK ${AgeCodec.b64(ack)}")
        debug("onPmHello $peerNick ratchet up, ACK sent")
        pmState(peerNick, "ESTABLISHED")
        flushPmOutboxRatchet(peerNick)   // ratchet is up: deliver anything we held during the handshake
    }

    private fun onPmAck(peerNick: String, line: String) {
        val s = pmByNick[peerNick] ?: run { debug("onPmAck $peerNick no session"); return }
        val ek = s.ekSelf ?: run { debug("onPmAck $peerNick no ekSelf (not our HELLO / already acked)"); return }
        val pub = store.lookupByFingerprint(s.peerFp)
            ?: run { debug("onPmAck $peerNick no pin for peerFp"); pmState(peerNick, "FAILED", "peer identity unknown"); return }
        val blob = runCatching { AgeCodec.unb64(line.split(' ').getOrNull(2).orEmpty()) }.getOrNull()
            ?: run { debug("onPmAck $peerNick bad blob"); pmState(peerNick, "FAILED", "malformed ACK"); return }
        val ekBPub = runCatching { AgeHandshake.openAck(p, me, blob, pub.sigPub) }.getOrNull()
            ?: run { debug("onPmAck $peerNick openAck FAILED"); pmState(peerNick, "FAILED", "couldn't verify peer identity"); return }
        s.ratchet = runCatching { AgeHandshake.initiatorSession(p, me, ek, pub.dhPub, ekBPub) }.getOrNull()
            ?: run { debug("onPmAck $peerNick initiatorSession FAILED"); pmState(peerNick, "FAILED", "key agreement failed"); return }
        s.ekSelf = null
        debug("onPmAck $peerNick ratchet up")
        pmState(peerNick, "ESTABLISHED")
        flushPmOutboxRatchet(peerNick)   // ratchet is up: deliver anything we held during the handshake
    }

    private fun onPmMessage(peerNick: String, line: String) {
        val s = pmByNick[peerNick] ?: run { debug("onPmMessage $peerNick no session"); return }
        val r = s.ratchet ?: run {
            // The peer sent us an encrypted PM, so THEIR ratchet is up - they got our HELLO and sent an
            // ACK we never completed (lost, or it lost a race). Re-send our SAME HELLO to prompt a fresh
            // ACK so we can catch up. Reusing ekSelf keeps the handshake keys identical, so the peer's
            // established ratchet is not disturbed. Bounded: only fires when a PM actually arrives.
            val ek = s.ekSelf
            val pub = if (ek != null) store.lookupByFingerprint(s.peerFp) else null
            if (ek != null && s.helloSent && pub != null && peerNick !in pmVerifyFailed) {
                val hello = runCatching { AgeHandshake.buildHello(p, me, ek, pub.dhPub) }.getOrNull()
                if (hello != null) {
                    emit(peerNick, "AGE HELLO ${AgeCodec.b64(hello)}")
                    debug("onPmMessage $peerNick ratchet-not-up: re-sent HELLO to recover")
                    return
                }
            }
            debug("onPmMessage $peerNick DROPPED ratchet-not-up")
            return
        }
        val t = line.split(' ')   // AGE PM <b64 headerDhPub> <pn> <n> <b64 ct>
        if (t.size < 6) return
        val msg = AgeRatchet.Message(
            runCatching { AgeCodec.unb64(t[2]) }.getOrNull() ?: return,
            t[3].toIntOrNull() ?: return,
            t[4].toIntOrNull() ?: return,
            runCatching { AgeCodec.unb64(t[5]) }.getOrNull() ?: return,
        )
        val pt = runCatching { r.decrypt(msg, pmAd(s.peerFp)) }.getOrNull()
            ?: run { debug("onPmMessage $peerNick decrypt FAILED"); return }
        debug("onPmMessage $peerNick delivered")
        deliverChat(peerNick, peerNick, pt.decodeToString())
    }

    /** A member left [channel]. On a keyed chat channel, drop them; if we are the owner, rekey so the
     *  departed member loses access to future messages (forward secrecy across membership change). */
    fun onMemberLeft(channel: String, nick: String) {
        if (channel !in chatChannels) return
        val fp = roster[channel]?.entries?.firstOrNull { it.value.equals(nick, ignoreCase = true) }?.key ?: return
        roster[channel]?.remove(fp)
        invited.remove("$channel\u0000$fp")
        caps.removeMember(channel, fp)
        if (!ready(channel)) return
        if (ownerOf(channel).equals(myFp, ignoreCase = true)) rekeyChannel(channel)
    }

    /** Owner mints a fresh K_G, rebuilds the channel on it, and re-seals it to the remaining members.
     *  The departed member keeps only the old key, so future AGE CHAT lines won't decrypt for them. */
    private fun rekeyChannel(channel: String) {
        groupKeys[channel] = p.randomBytes(32)
        caps.resetChannel(channel)
        roster[channel]?.keys?.toList()?.forEach { caps.addMember(channel, it) }   // re-register on the fresh channel
        roster[channel]?.toList()?.forEach { (fp, nick) ->
            invited.remove("$channel\u0000$fp")                                    // force a fresh invite carrying the new key
            inviteExisting(channel, fp, nick)
        }
    }

    /** Encrypt + ship [text] on [channel]. Returns false (fail closed) if the channel isn't keyed yet. */
    fun sendChat(channel: String, text: String): Boolean {
        val line = caps.encryptChat(channel, text) ?: return false
        emit(channel, line)
        return true
    }

    /** Deterministic key owner = lowest fingerprint among us and everyone who has announced here. */
    private fun ownerOf(channel: String): String {
        val fps = (roster[channel]?.keys?.toMutableSet() ?: mutableSetOf()).apply { add(myFp) }
        return fps.minByOrNull { it.lowercase() } ?: myFp
    }

    /** A peer was seen (or we enabled) on a chat channel: ensure mutual pinning, and if we are the
     *  deterministic owner, mint+distribute K_G once, then invite later joiners with the same key. */
    private fun onChatPeer(channel: String, fp: String, nick: String) {
        if (channel !in chatChannels) return
        if (!ownerOf(channel).equals(myFp, ignoreCase = true)) return
        if (!ready(channel)) {
            // Self-key even when we're the only +AGE member present, so +AGE is usable immediately in
            // a channel where not everyone runs a +AGE client. hostTable with an empty member list mints
            // K_G and sends no invites; late +AGE members are invited (below) as they announce.
            val members = roster[channel]?.map { (f, n) -> n to f }.orEmpty()
            hostTable(channel, members)
        } else if (!fp.equals(myFp, ignoreCase = true)) {
            inviteExisting(channel, fp, nick)
        }
    }

    /** Owner re-seals the existing K_G to a single late-joining member (no rekey). */
    private fun inviteExisting(channel: String, fp: String, nick: String) {
        if ("$channel\u0000$fp" in invited) return
        val kg = groupKeys[channel] ?: return
        val pub = store.lookupByFingerprint(fp) ?: return
        val now = System.currentTimeMillis()
        val payload = AgeInvite.Payload(
            gameId = channel, groupKey = kg, params = "",
            members = roster[channel]?.map { AgeInvite.Member(it.value, it.key) }.orEmpty(),
            hostSigPub = me.sigPub, issuedAt = now, expiresAt = now + INVITE_TTL_MS,
        )
        val blob = runCatching { AgeInvite.build(p, me, pub, nick, payload) }.getOrNull() ?: return
        val id = "i${inviteSeq++}"
        invited.add("$channel\u0000$fp")
        AgeWire.inviteChunks(id, blob).forEach { sendPrivmsg(channel, it) }
    }

    fun activeChannel(): String = activeChannel

    /** Delegate a script `age.*` call to the real capability (used for the keyed/transmit path). */
    fun call(name: String, args: List<String>): String {
        // Track the game channel so DEAL/IDENT routing has a target.
        when (name) {
            "age.join", "age.send", "age.ready" -> args.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { activeChannel = it }
            // Deterministic host election, exposed to scripts. `age.owner <chan>` is the lowest
            // fingerprint among us and everyone who has announced +AGE on the channel (identical on
            // every client once IDENTs are exchanged), so a game like poker can pick ONE host instead
            // of each client electing itself. `age.members <chan>` is the space-joined fingerprints of
            // the OTHER announced members, letting a script tell whether it is still alone (the
            // IDENT-timing gate: don't mint until a peer has had a chance to appear).
            "age.owner" -> return ownerOf(args.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: activeChannel)
            "age.members" -> return peersOf(args.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: activeChannel)
        }
        return caps.call(name, args)
    }

    /** Space-joined fingerprints of the peers (never ourselves) who have announced +AGE on [channel]. */
    private fun peersOf(channel: String): String =
        roster[channel]?.keys?.joinToString(" ").orEmpty()

    /**
     * The fingerprint of the [channel] member whose PINNED signing key is [sigPub], or null if no
     * member matches. Matches on the signing key itself rather than on a nick, so the answer is only
     * ever a fingerprint we have actually pinned from an IDENT on this channel.
     */
    private fun fpForSigPub(channel: String, sigPub: ByteArray): String? =
        roster[channel]?.keys?.firstOrNull { fp ->
            store.lookupByFingerprint(fp)?.sigPub?.let { p.constantTimeEquals(it, sigPub) } == true
        }

    /** Broadcast our public identity so peers can pin us (verify our signatures, seal deals to us).
     *  Always emits (a new peer needs it even if we announced before), and records the channel so
     *  [announceIdentOnce] can suppress redundant proactive re-announces. */
    fun announceIdent(channel: String) {
        if (channel.isNotEmpty()) activeChannel = channel
        announcedSelf.add(activeChannel)
        // Everyone currently in the channel is about to receive this IDENT, so nobody is owed a
        // reciprocal announce until the next person joins.
        joinedSinceAnnounce.remove(activeChannel)
        emit(activeChannel, AgeIdent.announce(p, me, System.currentTimeMillis()))
    }

    /**
     * A peer joined [channel]. If we have already announced our identity there, they missed it, so
     * they are owed a reciprocal announce when they introduce themselves; see [joinedSinceAnnounce].
     * Cheap and unconditional by design: the caller should not gate this on the chat +AGE pref,
     * because a scripted game channel keys itself via `age.join` without that pref ever being set.
     */
    fun onMemberJoined(channel: String, nick: String) {
        if (channel.isEmpty() || nick.isEmpty()) return
        if (nick.equals(myNick(), ignoreCase = true)) return
        if (channel in announcedSelf) joinedSinceAnnounce.getOrPut(channel) { HashSet() }.add(nick.lowercase())
    }

    /** Announce our identity only if we haven't already announced on [channel] this session. Used by
     *  the proactive paths (enable / join / host) that would otherwise each emit their own IDENT and
     *  stack up (e.g. enableChat + its self-key hostTable both announcing => a double IDENT). New
     *  peers are still covered by the forced reciprocal announce-back in the IDENT handler. */
    private fun announceIdentOnce(channel: String) {
        val ch = channel.takeIf { it.isNotEmpty() } ?: activeChannel
        if (ch in announcedSelf) { if (channel.isNotEmpty()) activeChannel = channel; return }
        announceIdent(channel)
    }

    /** Host using the roster collected from inbound AGE IDENT on [channel] (everyone who announced,
     *  minus ourselves). Convenience for a script that doesn't track fingerprints itself. */
    fun hostTable(channel: String) {
        val members = roster[channel]?.map { (fp, nick) -> nick to fp }.orEmpty()
        hostTable(channel, members)
    }

    /**
     * Host a table on [channel] for [members] (their (nick, fpHex), pubkeys already pinned via
     * prior AGE IDENT exchange). Mints K_G, registers members, announces our identity, and seals a
     * signed invite carrying K_G to each member. After this the channel is `ready()` for `age.send`.
     */
    fun hostTable(channel: String, members: List<Pair<String, String>>) {
        val kg = p.randomBytes(32)
        groupKeys[channel] = kg
        keyMinter[channel] = me.sigPub
        activeChannel = channel
        members.forEach { (_, fp) -> if (store.lookupByFingerprint(fp) != null) caps.addMember(channel, fp) }
        announceIdentOnce(channel)
        val now = System.currentTimeMillis()
        val payload = AgeInvite.Payload(
            gameId = channel,
            groupKey = kg,
            params = "",
            members = members.map { AgeInvite.Member(it.first, it.second) },
            hostSigPub = me.sigPub,
            issuedAt = now,
            expiresAt = now + INVITE_TTL_MS,
        )
        members.forEach { (nick, fp) ->
            val pub = store.lookupByFingerprint(fp) ?: return@forEach   // can't seal to an unknown key
            val blob = runCatching { AgeInvite.build(p, me, pub, nick, payload) }.getOrNull() ?: return@forEach
            val id = "i${inviteSeq++}"
            invited.add("$channel\u0000$fp")   // so a later IDENT from the same peer doesn't re-invite
            AgeWire.inviteChunks(id, blob).forEach { sendPrivmsg(channel, it) }
        }
    }

    /**
     * Inbound router: hand every `AGE …` line that arrives on IRC here. [from] is the sender nick,
     * [channel] the IRC target. Returns true if the line was an +AGE wire line we consumed (so the
     * caller can suppress it from the chat view).
     */
    fun onAgeLine(channel: String, from: String, line: String): Boolean {
        val sub = line.substringAfter("AGE ", "").substringBefore(' ')
        when (sub) {
            "FRAG" -> onFragChunk(channel, from, line)   // reassemble, then re-dispatch the whole line
            "MSG" -> {
                activeChannel = channel
                // token layout: AGE MSG <gameId> <senderFp> <epoch> <seq> <b64>
                val senderFp = line.split(' ').getOrNull(3).orEmpty()
                // our own move already landed via the local loopback; don't double-apply an echo.
                if (!senderFp.equals(myFp, ignoreCase = true)) caps.onWireMessage(channel, line)
            }
            "DEAL" -> caps.onWireDeal(line)
            "HELLO" -> { activeChannel = channel; onPmHello(channel, line) }
            "ACK" -> { activeChannel = channel; onPmAck(channel, line) }
            "PM" -> { activeChannel = channel; onPmMessage(channel, line) }
            "CHAT" -> {
                activeChannel = channel
                caps.decryptChat(channel, line)?.let { (fp, text) ->
                    if (!fp.equals(myFp, ignoreCase = true)) deliverChat(channel, roster[channel]?.get(fp) ?: from, text)
                }
            }
            "IDENT" -> AgeIdent.parse(p, line)?.let { parsed ->
                // A conflicting key is NOT pinned; hold it and ask the user. Everything below is
                // skipped for this announce, so an unaccepted key never enters the roster and never
                // gets sealed to or verified against.
                if (store.observe(from, parsed.identity) == AgeStore.Result.CONFLICT_KEY_FOR_NICK) {
                    pendingIdent[from.lowercase()] = parsed.identity
                    onIdentConflict(
                        channel, from,
                        AgeFingerprint.hex(AgeFingerprint.of(p, parsed.identity)),
                        store.fingerprintForNick(from).orEmpty(),
                    )
                    return true
                }
                if (activeChannel.isEmpty()) activeChannel = channel        // first contact sets the game channel
                val fp = AgeFingerprint.hex(AgeFingerprint.of(p, parsed.identity))
                caps.addMember(channel, fp)                                 // key off the ARRIVAL channel, not a stale one
                if (!fp.equals(myFp, ignoreCase = true)) {
                    roster.getOrPut(channel) { LinkedHashMap() }[fp] = from
                    // Reciprocal identity exchange for every AGE channel we're active on (chat AND game).
                    // Only for a peer who joined AFTER our last IDENT here: they are the only ones who
                    // could have missed it. A peer already present when we announced has our identity,
                    // and answering them would just duplicate it on the wire. Without this exchange at
                    // all, a late joiner on a game channel deadlocks: the host lacks the joiner (=> not
                    // in the invite member list => invite rejected) or the joiner lacks the host (=> the
                    // host's state is dropped as an unknown sender).
                    val missedOurIdent = joinedSinceAnnounce[channel]?.contains(from.lowercase()) == true
                    if (channel in announcedSelf && missedOurIdent && announcedBack.add("$channel\u0000$fp")) {
                        announceIdent(channel)
                    }
                    onChatPeer(channel, fp, from)
                    liveIdentFp[channel] = fp   // a live IDENT: peer is online now with this exact key
                    debug("recv IDENT from=$from chan=$channel fp=${fp.take(8)} pmEnabled=${channel in pmPeers}")
                    if (!isPmName(channel)) {
                        // A +AGE peer announced on a channel. Nudge scripts (e.g. poker) so they can
                        // re-run host election before anyone mints a group key; this is the script
                        // side of the IDENT-timing gate: a racing/late identity is accounted for.
                        raiseSignal("SIGNAL:AGE_PEER", mapOf("chan" to channel, "fp" to fp), emptyList())
                        // If we already host this GAME channel's key (we are its minter) and the channel
                        // is keyed, re-seal the SAME K_G to the newcomer (no rekey) so a player who joins
                        // after we opened the table is keyed in. Idempotent via `invited`; the chat path
                        // (onChatPeer -> inviteExisting) already covers chatChannels, so this is game-only.
                        if (channel !in chatChannels && ready(channel) &&
                            keyMinter[channel]?.let { p.constantTimeEquals(it, me.sigPub) } == true) {
                            inviteExisting(channel, fp, from)
                        }
                    }
                    if (channel in pmPeers) {
                        onPmIdent(channel, fp)
                    } else if (isPmName(channel) && channel !in chatChannels && pmInterestNotified.add(channel)) {
                        // The peer wants +AGE with us but we haven't turned it on. Prompt once so the
                        // user can enable it, instead of silently swallowing their identity.
                        onPmInterest(channel)
                    }
                }
            }
            "INVITE" -> onInviteChunk(line)
            "REKEY" -> { /* epoch bump handled inside AgeChannel on next decrypt; no-op here */ }
            else -> return false   // "AGE " prefix but not one of ours — leave it for the chat view
        }
        return true
    }

    /** Route a bare `AGE …` wire line to IRC. `AGE MSG` carries its channel as gameId (token 2);
     *  everything else (DEAL/IDENT/INVITE) goes to the current game channel. */
    private fun routeSend(line: String) {
        val t = line.split(' ', limit = 4)
        val target = if (t.size >= 3 && t[0] == "AGE" && t[1] == "MSG") t[2] else activeChannel
        if (target.isNotEmpty()) emit(target, line)
    }

    /**
     * Send an `AGE …` line to [target], fragmenting it under the IRC line limit if needed. IRC caps a
     * line at 512 bytes including "PRIVMSG <target> :" and CRLF; an encrypted game move or a long chat
     * easily exceeds that and the server would silently truncate it, breaking decryption. Over-long
     * lines are split into AGE FRAG chunks the peer reassembles before processing. Already-short lines
     * (the common case: handshake frames, small moves) go straight out with no overhead.
     */
    private fun emit(target: String, line: String) {
        if (target.isEmpty()) return
        // "PRIVMSG " (8) + target + " :" (2) + payload + CRLF (2); keep a margin for server-added tags.
        val overhead = 8 + target.toByteArray(Charsets.UTF_8).size + 2 + 2
        if (overhead + line.toByteArray(Charsets.UTF_8).size <= 480) { sendPrivmsg(target, line); return }
        val id = "f${fragSeq++}"
        val chunks = AgeWire.fragChunks(id, line)
        if (chunks.size > AgeWire.FRAG_MAX) { debug("emit DROP oversized line ${chunks.size} frags"); return }
        debug("emit $target fragmented into ${chunks.size}")
        chunks.forEach { sendPrivmsg(target, it) }
    }

    /** Reassemble an `AGE FRAG <id> <i>/<n> <b64chunk>` line; on the last piece, decode and re-dispatch
     *  the original line so the rest of the bridge never sees fragmentation. */
    private fun onFragChunk(channel: String, from: String, line: String) {
        val t = line.split(' ')
        if (t.size < 5) return
        val id = t[2]
        val (iStr, nStr) = t[3].split('/').let { it.getOrNull(0) to it.getOrNull(1) }
        val i = iStr?.toIntOrNull() ?: return
        val n = nStr?.toIntOrNull() ?: return
        if (i < 1 || n < 1 || i > n || n > AgeWire.FRAG_MAX) return
        val key = "$from\u0000$id"
        val buf = fragBufs.getOrPut(key) { arrayOfNulls(n) }
        if (buf.size != n) { fragBufs.remove(key); return }        // n changed under a reused id: reset
        buf[i - 1] = t[4]
        if (buf.any { it == null }) return                         // still reassembling
        fragBufs.remove(key)
        // Bound in-flight reassembly so a flood of never-completing frags can't grow unboundedly.
        if (fragBufs.size > 32) fragBufs.clear()
        val whole = runCatching { AgeCodec.unb64(buf.joinToString("")).toString(Charsets.UTF_8) }.getOrNull() ?: return
        if (!whole.startsWith("AGE ") || whole.startsWith("AGE FRAG")) return   // never nest / re-fragment
        onAgeLine(channel, from, whole)
    }

    private fun onInviteChunk(line: String) {
        // AGE INVITE <id> <i>/<n> <chunk>
        val t = line.split(' ')
        if (t.size < 5) return
        val id = t[2]
        val (iStr, nStr) = t[3].split('/').let { it.getOrNull(0) to it.getOrNull(1) }
        val i = iStr?.toIntOrNull() ?: return
        val n = nStr?.toIntOrNull() ?: return
        if (i < 1 || n < 1 || i > n) return
        val buf = inviteBufs.getOrPut(id) { arrayOfNulls(n) }
        if (buf.size != n) return
        buf[i - 1] = t[4]
        if (buf.any { it == null }) return                       // still reassembling
        inviteBufs.remove(id)
        val blob = runCatching { AgeCodec.unb64(buf.joinToString("")) }.getOrNull() ?: return
        // Open under TOFU first: this only recovers the payload and proves the invite is self-consistent
        // (validly signed by whatever key it names). The gameId we need to identify the channel's owner
        // is inside the sealed blob, so authorisation happens after the open, below.
        val open = runCatching {
            AgeInvite.open(p, me, myNick(), myFp, blob, null, System.currentTimeMillis())
        }.getOrNull() ?: return
        if (open !is AgeInvite.Open.Ok) return
        val pl = open.payload

        // Authorise the key. Without this, any peer who has seen our public IDENT could self-sign an
        // invite naming themselves owner, seal it to us, and rotate us onto a key they control. Policy:
        // the FIRST key for a channel is trust-on-first-use (the same first-contact risk as identity
        // pinning), but RE-KEYING an already-established channel must come from the key's current minter,
        // or, for a hostless chat channel, from its deterministically-elected owner (so a lower-fingerprint
        // member can still legitimately take over). This protects scripted-game hosts and chat owners alike.
        val hadKey = groupKeys.containsKey(pl.gameId)
        val keyChanged = groupKeys[pl.gameId]?.let { !it.contentEquals(pl.groupKey) } ?: false
        if (hadKey && keyChanged) {
            val fromMinter = keyMinter[pl.gameId]?.let { p.constantTimeEquals(it, pl.hostSigPub) } ?: false
            val fromElectedOwner = pl.gameId in chatChannels &&
                (store.lookupByFingerprint(ownerOf(pl.gameId))?.sigPub
                    ?.let { p.constantTimeEquals(it, pl.hostSigPub) } == true)
            // GAME channels only: the player who opens the table hosts it, so two players tapping Open
            // at the same moment both mint a K_G and invite each other. Without a tie-break each side
            // keeps its own key, neither can decrypt the other, and the table deadlocks with both sides
            // waiting. Deterministic resolution with no extra round-trip: the LOWER fingerprint's key
            // wins, and both sides compute the same verdict from data they already hold.
            //
            // Deliberately narrow, so this cannot become a key-injection primitive:
            //  - only when the key we currently hold is one WE minted (a key we merely adopted is never
            //    rotated by this path), and
            //  - only for a strictly LOWER fingerprint than our own (a higher one can never rotate us), and
            //  - only for a fingerprint already pinned in our roster for this channel, matched by its
            //    signing key rather than by nick, so the invite must come from an identity we pinned.
            // Chat channels keep the strict elected-owner rule above; only games loosen.
            val weMintedIt = keyMinter[pl.gameId]?.let { p.constantTimeEquals(it, me.sigPub) } ?: false
            val fromLowerFpOpener = pl.gameId !in chatChannels && weMintedIt &&
                (fpForSigPub(pl.gameId, pl.hostSigPub)?.let { it.lowercase() < myFp.lowercase() } == true)
            if (!fromMinter && !fromElectedOwner && !fromLowerFpOpener) return   // unauthorised re-key: keep ours
        }

        groupKeys[pl.gameId] = pl.groupKey
        keyMinter[pl.gameId] = pl.hostSigPub
        if (keyChanged) caps.resetChannel(pl.gameId)   // rekey: rebuild on the new key before re-adding members
        activeChannel = pl.gameId
        pl.members.forEach { m -> if (store.lookupByFingerprint(m.fpHex) != null) caps.addMember(pl.gameId, m.fpHex) }
        // Re-add everyone we have pinned on this channel, not just the invite's member list. Two reasons,
        // both of which otherwise leave us unable to READ the very host we just adopted:
        //  - `members` carries the host's PEERS and never the host itself, so it alone never restores the
        //    host's signing key; normally we hold it from their IDENT.
        //  - a keyChanged adopt calls resetChannel, which drops every sender key we had learned from those
        //    IDENTs, so the IDENT-derived keys must be re-added here or they are simply gone.
        // AgeChannel.decrypt drops any message from a sender it has no pinned signing key for, so without
        // this a re-key silently turns the channel one-way: we can encrypt to them, they can read us, and
        // every message they send back is dropped as "unknown sender".
        roster[pl.gameId]?.keys?.forEach { fp -> if (store.lookupByFingerprint(fp) != null) caps.addMember(pl.gameId, fp) }
        // table is now keyed for us; nudge the script so any waiting view refreshes.
        raiseSignal("SIGNAL:AGE_READY", mapOf("chan" to pl.gameId), emptyList())
    }

    private companion object {
        const val INVITE_TTL_MS = 6L * 60 * 60 * 1000   // 6h
    }
}
