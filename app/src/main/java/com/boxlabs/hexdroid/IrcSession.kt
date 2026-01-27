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
}

class IrcSession(private val config: IrcConfig, private val rng: SecureRandom) {
    private var capLsDone = false
    private var capEnded = false

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

            // CAP LS can be multi-line. Servers indicate continuation using a "*" parameter
            // *after* the subcommand (or after the "302" version token). The first parameter is
            // often "*" during registration (unregistered), so we must not treat that as continuation.
            val continuation = m.params.drop(2).any { it == "*" }
            val isFinal = !continuation
            if (isFinal && !capLsDone) {
                capLsDone = true
                out += IrcAction.EmitStatus("Server CAP LS complete")
                out += IrcAction.Send(buildCapReq())
            }
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

            if (wantSasl && enabledCaps.contains("sasl") && !saslInProgress && !saslDone) {
                saslInProgress = true
                out += IrcAction.EmitStatus("Starting SASL…")
                out += IrcAction.Send(startSasl())
                return out
            }

            if (!capEnded && (!wantSasl || saslDone || !enabledCaps.contains("sasl"))) {
                capEnded = true
                out += IrcAction.Send("CAP END")
            }
            return out
        }

        if (m.command == "CAP" && m.params.getOrNull(1) == "NAK") {
            out += IrcAction.EmitError("CAP NAK: ${m.trailing ?: ""}")
            if (!capEnded) { capEnded = true; out += IrcAction.Send("CAP END") }
            return out
        }

        when (m.command) {
            "903" -> {
                saslDone = true; saslInProgress = false
                out += IrcAction.EmitStatus("SASL authentication succeeded")
                if (!capEnded) { capEnded = true; out += IrcAction.Send("CAP END") }
                return out
            }
            "904", "905", "906", "907" -> {
                saslDone = true; saslInProgress = false
                out += IrcAction.EmitError("SASL failed (${m.command}): ${m.trailing ?: ""}")
                if (!capEnded) { capEnded = true; out += IrcAction.Send("CAP END") }
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

	private fun buildCapReq(): String {
		val req = mutableListOf<String>()

		// Core IRCv3 capabilities
		if (config.capPrefs.messageTags) req += "message-tags"
		if (config.capPrefs.serverTime) req += "server-time"
		if (config.capPrefs.echoMessage) req += "echo-message"
		if (config.capPrefs.labeledResponse) req += "labeled-response"
		if (config.capPrefs.batch) req += "batch"
		if (config.capPrefs.utf8Only) req += "utf8only"

		// History / playback
		if (config.capPrefs.draftChathistory) req += "draft/chathistory"
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

		// Filter to only request what the server supports
		val filtered = req.filter { serverCaps.contains(it.lowercase()) }

		return if (filtered.isEmpty()) {
			"CAP END"
		} else {
			"CAP REQ :${filtered.joinToString(" ")}"
		}
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
                    out += IrcAction.EmitError("SCRAM server signature verification failed")
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
