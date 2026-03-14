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
import java.security.SecureRandom

sealed class IrcAction {
    data class Send(val line: String) : IrcAction()
    data class EmitStatus(val text: String) : IrcAction()
    data class EmitError(val text: String) : IrcAction()
    /** CAP NEW: server dynamically advertised new capabilities after registration. */
    data class EmitCapNew(val caps: List<String>) : IrcAction()
    /** CAP DEL: server withdrew previously negotiated capabilities. */
    data class EmitCapDel(val caps: List<String>) : IrcAction()
}

class IrcSession(private val config: IrcConfig, private val rng: SecureRandom) {
    private var capLsDone = false
    private var capEnded = false

    // Track how many CAP REQ chunks are still awaiting ACK/NAK so we do not send
    // CAP END prematurely when the initial request was split across multiple lines.
    private var pendingCapReqs = 0

    private val wantSasl = config.sasl is SaslConfig.Enabled
    private var saslInProgress = false
    private var saslDone = false

    private val serverCaps = mutableSetOf<String>()
    private val enabledCaps = mutableSetOf<String>()

    fun hasCap(name: String): Boolean = enabledCaps.contains(name)
    private var scram: ScramSha256Client? = null

    // Buffer for incoming SASL AUTHENTICATE payloads (servers may split into 400-byte chunks).
    private var saslIncomingB64: StringBuilder? = null

    fun onMessage(m: IrcMessage): List<IrcAction> {
        val out = mutableListOf<IrcAction>()

        if (m.command == "CAP" && m.params.getOrNull(1) == "LS") {
            val capsPart = m.trailing ?: ""
            serverCaps.addAll(capsPart.split(' ')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { it.substringBefore('=') }
                .map { it.lowercase() })

            // CAP LS multi-line: the continuation marker "*" is params[2] (after client-nick and "LS").
            // Using drop(2).any{} would match the client-nick "*" during pre-registration and
            // stall cap negotiation forever on some servers.
            val isContinuation = m.params.getOrNull(2) == "*"
            if (!isContinuation && !capLsDone) {
                capLsDone = true
                out += IrcAction.EmitStatus("Server CAP LS complete")
                val chunks = buildCapReqChunks()
                if (chunks.isEmpty()) {
                    // Nothing to request - end cap negotiation immediately.
                    capEnded = true
                    out += IrcAction.Send("CAP END")
                } else {
                    pendingCapReqs = chunks.size
                    chunks.forEach { out += IrcAction.Send(it) }
                }
            }
            return out
        }

        // CAP NEW: server advertises new capabilities dynamically after registration (IRCv3.2).
        // Request any that we want and don't already have.
        if (m.command == "CAP" && m.params.getOrNull(1) == "NEW") {
            val newCaps = (m.trailing ?: "").split(' ')
                .map { it.trim().substringBefore('=').lowercase() }
                .filter { it.isNotBlank() }
            serverCaps.addAll(newCaps)
            out += IrcAction.EmitStatus("CAP NEW: ${newCaps.joinToString(" ")}")
            out += IrcAction.EmitCapNew(newCaps)
            val want = buildCapReqList().filter { newCaps.contains(it) && !enabledCaps.contains(it) }
            // CAP NEW is post-registration; we don't send CAP END and the list is small enough
            // that we don't need to chunk it in practice.
            if (want.isNotEmpty()) out += IrcAction.Send("CAP REQ :${want.joinToString(" ")}")
            return out
        }

        // CAP DEL: server withdraws a capability (e.g., after services link-break).
        if (m.command == "CAP" && m.params.getOrNull(1) == "DEL") {
            val delCaps = (m.trailing ?: "").split(' ')
                .map { it.trim().substringBefore('=').lowercase() }
                .filter { it.isNotBlank() }
            serverCaps.removeAll(delCaps.toSet())
            enabledCaps.removeAll(delCaps.toSet())
            out += IrcAction.EmitStatus("CAP DEL: ${delCaps.joinToString(" ")}")
            out += IrcAction.EmitCapDel(delCaps)
            return out
        }

        if (m.command == "CAP" && m.params.getOrNull(1) == "ACK") {
            val ack = (m.trailing ?: "").split(' ')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { it.substringBefore('=') }
                .map { it.lowercase() }
            enabledCaps.addAll(ack)
            out += IrcAction.EmitStatus("CAP ACK: ${ack.joinToString(" ")}")

            // Decrement pending count; only proceed when all chunks are resolved.
            if (pendingCapReqs > 0) pendingCapReqs--

            if (wantSasl && enabledCaps.contains("sasl") && !saslInProgress && !saslDone) {
                saslInProgress = true
                out += IrcAction.EmitStatus("Starting SASL…")
                out += IrcAction.Send(startSasl())
                return out
            }

            if (!capEnded && pendingCapReqs == 0 && (!wantSasl || saslDone || !enabledCaps.contains("sasl"))) {
                capEnded = true
                out += IrcAction.Send("CAP END")
            }
            return out
        }

        if (m.command == "CAP" && m.params.getOrNull(1) == "NAK") {
            out += IrcAction.EmitError("CAP NAK: ${m.trailing ?: ""}")
            if (pendingCapReqs > 0) pendingCapReqs--
            if (!capEnded && pendingCapReqs == 0) { capEnded = true; out += IrcAction.Send("CAP END") }
            return out
        }

        when (m.command) {
            "903" -> {
                saslDone = true; saslInProgress = false
                out += IrcAction.EmitStatus("SASL authentication succeeded")
                if (!capEnded && pendingCapReqs == 0) { capEnded = true; out += IrcAction.Send("CAP END") }
                return out
            }
            "904", "905", "906", "907" -> {
                saslDone = true; saslInProgress = false
                out += IrcAction.EmitError("SASL failed (${m.command}): ${m.trailing ?: ""}")
                if (!capEnded && pendingCapReqs == 0) { capEnded = true; out += IrcAction.Send("CAP END") }
                return out
            }
        }

        if (m.command == "AUTHENTICATE" && saslInProgress) {
            val payload = m.params.firstOrNull() ?: ""
            out += handleAuthenticate(payload)
            return out
        }

        return emptyList()
    }

    /**
     * The full set of capabilities we *want* to request (unfiltered by what the server supports).
     * Used both by buildCapReqChunks (initial negotiation) and CAP NEW (dynamic re-request).
     */
    private fun buildCapReqList(): List<String> {
        val req = mutableListOf<String>()

        // Core IRCv3 capabilities
        if (config.capPrefs.messageTags) req += "message-tags"
        if (config.capPrefs.serverTime) req += "server-time"
        if (config.capPrefs.echoMessage) req += "echo-message"
        if (config.capPrefs.labeledResponse) req += "labeled-response"
        if (config.capPrefs.batch) req += "batch"
        if (config.capPrefs.utf8Only) req += "utf8only"

        // History / playback: request both the graduated cap and the legacy draft/ alias so we
        // interoperate with older (draft/chathistory) and modern (chathistory) servers.
        if (config.capPrefs.draftChathistory) {
            req += "draft/chathistory"
            req += "chathistory"         // graduated (Ergo 2.11+, soju 0.7+)
        }
        if (config.capPrefs.draftEventPlayback) req += "draft/event-playback"

        // User state notifications
        if (config.capPrefs.accountNotify) req += "account-notify"
        if (config.capPrefs.awayNotify) req += "away-notify"
        if (config.capPrefs.chghost) req += "chghost"

        // Enhanced JOIN / NAMES
        if (config.capPrefs.extendedJoin) req += "extended-join"
        if (config.capPrefs.multiPrefix) req += "multi-prefix"
        if (config.capPrefs.userhostInNames) req += "userhost-in-names"

        // Invite / name changes
        if (config.capPrefs.inviteNotify) req += "invite-notify"
        if (config.capPrefs.setname) req += "setname"

        // SASL (only if configured)
        if (config.capPrefs.sasl && wantSasl) req += "sasl"

        // Optional / draft
        if (config.capPrefs.draftRelaymsg) req += "draft/relaymsg"
        if (config.capPrefs.draftReadMarker) req += "draft/read-marker"

        // MONITOR: online/offline status tracking for target nicks
        if (config.capPrefs.monitor) req += "monitor"

        // account-tag: include services account name in PRIVMSG/NOTICE message tags
        if (config.capPrefs.accountTag) req += "account-tag"

        // draft/typing / typing: typing status indicators (send/receive).
        // "draft/typing" is the legacy name; Libera and modern servers advertise the
        // graduated "typing" cap. Request both so we negotiate whichever the server offers.
        if (config.capPrefs.typingIndicator) {
            req += "draft/typing"
            req += "typing"          // graduated (Libera, Ergo 2.14+, InspIRCd 4+)
        }

        // IRCv3 standard-replies (FAIL/WARN/NOTE): structured errors from Ergo, Soju, InspIRCd 4+.
        // Request both the graduated name and its draft alias for compatibility with older servers.
        if (config.capPrefs.standardReplies) {
            req += "draft/standard-replies"
            req += "standard-replies"
        }

        // pre-away: allows AWAY before 001 welcome.
        // Request both the graduated name and its draft alias so we negotiate the cap on
        // older Ergo (< 2.9) and soju versions that still advertise "draft/pre-away".
        if (config.capPrefs.preAway) {
            req += "draft/pre-away"
            req += "pre-away"
        }

        // message-ids (msgid tag): unique message IDs for deduplication
        if (config.capPrefs.messageIds) req += "message-ids"

        // Bouncer-specific CAPs
        if (config.isBouncer) {
            // Legacy ZNC (< 1.7) uses znc.in/server-time-iso instead of server-time.
            // Requesting both ensures replayed messages get correct timestamps on all ZNC versions.
            req += "znc.in/server-time-iso"
            // ZNC native playback: lets us request only messages since we were last seen,
            // rather than receiving a fixed replay window every connect.
            req += "znc.in/playback"
            // soju/pounce: multi-upstream network context (lets us show per-upstream trees).
            req += "soju.im/bouncer-networks"
            req += "soju.im/bouncer-networks-notify"
            // soju.im/no-implicit-names: suppress automatic NAMES list on JOIN.
            // With this cap, soju does NOT send 353/366 on join unless we explicitly ask.
            // This avoids a full names re-download on every bouncer reconnect.
            if (config.capPrefs.sojuNoImplicitNames) req += "soju.im/no-implicit-names"
            // soju.im/read: proprietary read markers (parallel to draft/read-marker)
            if (config.capPrefs.sojuRead) req += "soju.im/read"
        }

        // draft/channel-rename: handle RENAME commands without a re-join cycle.
        if (config.capPrefs.channelRename) req += "draft/channel-rename"

        // draft/extended-monitor: richer MONONLINE replies with account + realname.
        if (config.capPrefs.extendedMonitor) req += "draft/extended-monitor"

        // draft/message-reactions: TAGMSG +draft/react emoji reactions.
        if (config.capPrefs.messageReactions) req += "draft/message-reactions"

        // draft/no-implicit-names: generic graduated form (not just soju).
        if (config.capPrefs.noImplicitNames) req += "draft/no-implicit-names"

        return req
    }

    /**
     * Build one or more "CAP REQ :..." lines for the capabilities the server supports.
     *
     * IRC lines are limited to 512 bytes.  A CAP REQ with many caps can easily exceed this.
     * We split the list into chunks so that each line's cap payload stays under 400 bytes,
     * leaving room for the command prefix and CRLF.
     *
     * Returns an empty list when there are no matching caps (caller should send "CAP END").
     */
    private fun buildCapReqChunks(): List<String> {
        val filtered = buildCapReqList().filter { serverCaps.contains(it.lowercase()) }
        if (filtered.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        val chunk = StringBuilder()
        for (cap in filtered) {
            val toAdd = if (chunk.isEmpty()) cap else " $cap"
            if (chunk.length + toAdd.length > 400) {
                lines += "CAP REQ :$chunk"
                chunk.clear()
                chunk.append(cap)
            } else {
                chunk.append(toAdd)
            }
        }
        if (chunk.isNotEmpty()) lines += "CAP REQ :$chunk"
        return lines
    }

    private fun startSasl(): String {
        val s = config.sasl as SaslConfig.Enabled
        return when (s.mechanism) {
            SaslMechanism.PLAIN -> "AUTHENTICATE PLAIN"
            SaslMechanism.EXTERNAL -> "AUTHENTICATE EXTERNAL"
            SaslMechanism.SCRAM_SHA_256 -> "AUTHENTICATE SCRAM-SHA-256"
        }
    }

    /**
     * Servers may split SASL AUTHENTICATE payloads into 400-byte chunks.
     * Returns the full base64 payload once complete, otherwise null.
     */
    private fun consumeSaslServerB64Chunk(payload: String): String? {
        if (payload == "*") {
            saslIncomingB64 = null
            return null
        }

        if (payload == "+") {
            val buf = saslIncomingB64
            if (buf != null && buf.isNotEmpty()) {
                val full = buf.toString()
                saslIncomingB64 = null
                return full
            }
            return null
        }

        val buf = saslIncomingB64 ?: StringBuilder().also { saslIncomingB64 = it }
        buf.append(payload)

        // Final chunk is shorter than 400 bytes.
        if (payload.length < 400) {
            val full = buf.toString()
            saslIncomingB64 = null
            return full
        }
        return null
    }

    private fun handleAuthenticate(serverPayload: String): List<IrcAction> {
        val out = mutableListOf<IrcAction>()
        val s = config.sasl as? SaslConfig.Enabled ?: return out

        when (s.mechanism) {
            SaslMechanism.PLAIN -> if (serverPayload == "+") {
                if (!config.useTls) {
                    out += IrcAction.EmitError("SASL PLAIN aborted: refusing to send password over an unencrypted connection. Enable TLS or switch to SCRAM-SHA-256.")
                    out += IrcAction.Send("AUTHENTICATE *")
                    return out
                }
                val authcid = s.authcid ?: ""
                val pass = s.password ?: ""
                val msg = "\u0000$authcid\u0000$pass"
                val b64 = Base64.encodeToString(msg.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                out += chunkAuthenticate(b64)
            }
            SaslMechanism.EXTERNAL -> if (serverPayload == "+") out += IrcAction.Send("AUTHENTICATE +")
            SaslMechanism.SCRAM_SHA_256 -> {
                // Server sends "+" to prompt the client for the first message.
                if (serverPayload == "+" && scram == null && (saslIncomingB64?.isNotEmpty() != true)) {
                    val authcid = s.authcid ?: ""
                    val pass = s.password ?: ""
                    val clientNonce = randomNonce()
                    scram = ScramSha256Client(authcid, pass, clientNonce)
                    val first = scram!!.clientFirstMessage()
                    val b64 = Base64.encodeToString(first.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    out += chunkAuthenticate(b64)
                    return out
                }

                // Server payloads may be chunked into 400-byte AUTHENTICATE messages.
                val fullB64 = consumeSaslServerB64Chunk(serverPayload) ?: return out

                val decoded = try {
                    String(Base64.decode(fullB64, Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: Throwable) {
                    out += IrcAction.EmitError("SASL: could not decode server AUTHENTICATE payload")
                    out += IrcAction.Send("AUTHENTICATE *")
                    return out
                }

                val sc = scram ?: return listOf(IrcAction.EmitError("SCRAM state missing"))
                val next = sc.onServerMessage(decoded)
                if (next is ScramNext.SendClientFinal) {
                    val b64 = Base64.encodeToString(next.clientFinal.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    out += chunkAuthenticate(b64)
                } else if (next is ScramNext.Done && !next.verified) {
                    // Server signature verification failed (or server sent an "e=" error attribute).
                    // Abort the exchange so the server doesn't hang waiting for our client-final.
                    out += IrcAction.EmitError("SCRAM server signature verification failed")
                    out += IrcAction.Send("AUTHENTICATE *")
                }
            }
        }
        return out
    }

    private fun chunkAuthenticate(b64: String): List<IrcAction> {
        val out = mutableListOf<IrcAction>()
        var i = 0
        while (i < b64.length) {
            val end = minOf(i + 400, b64.length)
            out += IrcAction.Send("AUTHENTICATE ${b64.substring(i, end)}")
            i = end
        }
        if (b64.length % 400 == 0) out += IrcAction.Send("AUTHENTICATE +")
        return out
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(18)
        rng.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP).replace("=", "")
    }
}