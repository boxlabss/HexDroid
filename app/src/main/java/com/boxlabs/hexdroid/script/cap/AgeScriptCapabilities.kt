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

package com.boxlabs.hexdroid.script.cap

import com.boxlabs.hexdroid.crypto.AgeChannel
import com.boxlabs.hexdroid.crypto.AgeCodec
import com.boxlabs.hexdroid.crypto.AgeFingerprint
import com.boxlabs.hexdroid.crypto.AgeIdentity
import com.boxlabs.hexdroid.crypto.AgePrimitives
import com.boxlabs.hexdroid.crypto.AgeSeal
import com.boxlabs.hexdroid.crypto.AgeStore
import com.boxlabs.hexdroid.crypto.AgeWire

/**
 * The `age.*` capability surface exposed to scripts — the last pillar of "features are
 * scripts". This is GENERAL crypto plumbing (any script can build an E2E feature), not
 * poker-specific: the script orchestrates, this native adapter holds the keys and does
 * the signing/encryption. Wire `capability()` from the VM's ScriptHost to here.
 *
 * Sync capabilities return a string (lists space-joined). Inbound traffic is delivered
 * back to the script as SIGNALs via [raiseSignal]. The engine keys handlers under the
 * uppercased "SIGNAL:NAME" form (see HexParser / ScriptEngine.dispatch), so the raised
 * names MUST carry that prefix or `on SIGNAL:age_msg` never matches:
 *   - `SIGNAL:AGE_MSG`  : a decrypted group message. fields: from=<fp>, chan=<channel>; args = move tokens.
 *   - `SIGNAL:AGE_DEAL` : a sealed payload addressed to us. fields: data=<plaintext>.
 */
class AgeScriptCapabilities(
    private val p: AgePrimitives,
    private val me: AgeIdentity,
    private val myFp: String,
    private val store: AgeStore,
    private val keyFor: (fpHex: String) -> Pair<ByteArray, ByteArray>?,  // (sigPub, dhPub)
    // The group key K_G is established by the session via the +AGE invite flow (AgeInvite,
    // sealed + signed per the wire spec) and supplied here — it is NEVER invented locally and
    // NEVER passes through the script. Returns null until the channel has been keyed.
    private val groupKeyFor: (channel: String) -> ByteArray?,
    private val send: (line: String) -> Unit,
    private val raiseSignal: (name: String, fields: Map<String, String>, args: List<String>) -> Unit,
) {
    private val channels = HashMap<String, AgeChannel>()              // channel -> AgeChannel (keyed)
    private val pendingMembers = HashMap<String, MutableSet<String>>() // members added before keying

    /** Dispatch a script `age.*` call. Unknown names return "". */
    fun call(name: String, args: List<String>): String = when (name) {
        "age.me" -> myFp
        "age.fp" -> store.lookupByNick(args.getOrElse(0) { "" })?.let { AgeFingerprint.hex(AgeFingerprint.of(p, it)) } ?: ""
        "age.members" -> "" // filled by the session via observed JOINs; left to the script's own roster
        "age.sha" -> AgeFingerprint.hex(p.sha256((args.getOrElse(0) { "" }).encodeToByteArray()))
        "age.rand" -> AgeFingerprint.hex(p.randomBytes((args.getOrElse(0) { "16" }).toIntOrNull() ?: 16))
        "age.join" -> { channelFor(args.getOrElse(0) { "" }); "" }
        "age.ready" -> if (channelFor(args.getOrElse(0) { "" }) != null) "true" else "false"
        "age.send" -> { sendMove(args.getOrElse(0) { "" }, args.drop(1)); "" }
        "age.seal" -> { sealTo(args.getOrElse(0) { "" }, args.drop(1)); "" }
        else -> ""
    }

    /** VM routes inbound `AGE MSG` lines on a script channel here. */
    fun onWireMessage(channel: String, line: String) {
        val ch = channelFor(channel) ?: return   // not keyed yet → drop (fail closed)
        val enc = AgeWire.parseMsg(line) ?: return
        when (val d = ch.decrypt(enc)) {
            is AgeChannel.Decrypted.Ok -> raiseSignal(
                "SIGNAL:AGE_MSG", mapOf("from" to d.senderFpHex, "chan" to channel), d.move.decodeToString().split(' '),
            )
            is AgeChannel.Decrypted.Dropped -> {}
        }
    }

    /** VM routes inbound `AGE DEAL` lines addressed to us here. */
    fun onWireDeal(line: String) {
        val t = line.trim().split(' ')
        if (t.size < 4 || t[1] != "DEAL" || !t[2].equals(myFp, true)) return
        val blob = runCatching { AgeCodec.unb64(t.last()) }.getOrNull() ?: return
        val plain = runCatching { AgeSeal.open(p, me.dhSeed, me.dhPub, blob, DEAL_AAD) }.getOrNull() ?: return
        raiseSignal("SIGNAL:AGE_DEAL", mapOf("data" to plain.decodeToString()), emptyList())
    }

    /** Register a member so the channel can verify their signatures + we can seal to them.
     *  Safe to call before the channel is keyed — the member is queued and applied on keying. */
    fun addMember(channel: String, fpHex: String) {
        val ch = channelFor(channel)
        if (ch == null) { pendingMembers.getOrPut(channel) { HashSet() }.add(fpHex); return }
        keyFor(fpHex)?.let { (sig, _) -> ch.addMember(fpHex, sig) }
    }

    /** The channel for [channel], or null if the session hasn't supplied its K_G yet.
     *  On first keying, any members queued before keying are applied. */
    private fun channelFor(channel: String): AgeChannel? {
        channels[channel]?.let { return it }
        val k = groupKeyFor(channel) ?: return null              // not yet established (invite pending)
        val ch = AgeChannel(p, channel, me, myFp, groupKey = k)
        channels[channel] = ch
        pendingMembers.remove(channel)?.forEach { fp -> keyFor(fp)?.let { (sig, _) -> ch.addMember(fp, sig) } }
        return ch
    }

    /** Drop the cached channel so the next access rebuilds it with the current group key (after a rekey). */
    fun resetChannel(channel: String) { channels.remove(channel) }

    /** Stop accepting [fp]'s signatures on [channel] (member left). No-op if the channel isn't built. */
    fun removeMember(channel: String, fp: String) { channels[channel]?.removeMember(fp) }

    /** Encrypt [text] as an `AGE CHAT` wire line for [channel], or null if not keyed (fail closed). */
    fun encryptChat(channel: String, text: String): String? {
        val ch = channelFor(channel) ?: return null
        return AgeWire.chat(ch.encrypt(text.encodeToByteArray()))
    }

    /** Decrypt an inbound `AGE CHAT` line on [channel] to (senderFpHex, plaintext), or null on any failure. */
    fun decryptChat(channel: String, line: String): Pair<String, String>? {
        val ch = channelFor(channel) ?: return null
        val enc = AgeWire.parseChat(line) ?: return null
        return when (val d = ch.decrypt(enc)) {
            is AgeChannel.Decrypted.Ok -> d.senderFpHex to d.move.decodeToString()
            is AgeChannel.Decrypted.Dropped -> null
        }
    }

    private fun sendMove(channel: String, move: List<String>) {
        val ch = channelFor(channel) ?: return                  // not keyed → don't transmit (fail closed)
        val enc = ch.encrypt(move.joinToString(" ").encodeToByteArray())
        send(AgeWire.msg(enc))
    }

    private fun sealTo(fpHex: String, data: List<String>) {
        val dh = keyFor(fpHex)?.second ?: return
        val sealed = AgeSeal.seal(p, dh, data.joinToString(" ").encodeToByteArray(), DEAL_AAD)
        // wire: AGE DEAL <recipientFp> <b64> — must match onWireDeal()'s field layout
        send("AGE DEAL $fpHex ${AgeCodec.b64(sealed)}")
    }

    companion object {
        private val DEAL_AAD = "hexdroid/script/deal/v1".encodeToByteArray()
    }
}
