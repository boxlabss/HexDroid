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

import java.security.SecureRandom
import java.util.Locale

/**
 * Literal default value of [IrcConfig.username] when a new profile is created with no
 * user input. Treated as "unset" by the SASL authcid fallback in [IrcSession] so that
 * 1.6.1-era profiles, which commonly carry this value because the old UI didn't visibly
 * tie the field to SASL, continue to SASL as the IRC nick instead of as the literal
 * placeholder. Must stay in sync with the default sprinkled across
 * data/SettingsRepository.kt (every NetworkProfile() constructor call site).
 */
private const val DEFAULT_USERNAME = "hexdroid"

/**
 * Base64 backed by java.util.Base64 rather than android.util.Base64.
 *
 * The decoder is the MIME decoder, matching android.util.Base64.DEFAULT's tolerance of
 * line breaks and other non-alphabet characters that some servers emit.
 */
internal object B64 {
    fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    fun decode(s: String): ByteArray = java.util.Base64.getMimeDecoder().decode(s)
}

sealed class IrcAction {
    data class Send(val line: String) : IrcAction()
    data class EmitStatus(val text: String) : IrcAction()
    data class EmitError(val text: String) : IrcAction()
    /** CAP NEW: server dynamically advertised new capabilities after registration. */
    data class EmitCapNew(val caps: List<String>) : IrcAction()
    /** CAP DEL: server withdrew previously negotiated capabilities. */
    data class EmitCapDel(val caps: List<String>) : IrcAction()
    /**
     * SASL authentication failed in a way that won't recover by retrying with the
     * same credentials. Surfaces as IrcEvent.AuthFailed downstream so the viewmodel
     * can halt auto-reconnect (see authBlockedReconnect).
     */
    data class EmitAuthFailed(val reason: String) : IrcAction()
    /**
     * IRCv3 STS: the `sts` capability value was observed in CAP LS or CAP NEW.
     * The cap is never REQ'd; [port] matters on insecure connections (TLS upgrade
     * target), [durationSec] on secure connections (policy persistence; 0 = delete).
     */
    data class EmitSts(val port: Int?, val durationSec: Long?, val preload: Boolean) : IrcAction()
}

class IrcSession(private val config: IrcConfig, private val rng: SecureRandom) {
    /** Localized string lookup, set by IrcClient after construction. */
    var strings: StringLookup? = null
    /** Resolve a localized string resource with optional format args. */
    private fun tr(id: Int, vararg args: Any?): String = strings?.invoke(id, args) ?: ""
    private var capLsDone = false
    private var capEnded = false

    // Track how many CAP REQ chunks are still awaiting ACK/NAK so we do not send
    // CAP END prematurely when the initial request was split across multiple lines.
    private var pendingCapReqs = 0

    private val wantSasl = config.sasl is SaslConfig.Enabled
    private var saslInProgress = false
    private var saslDone = false

    /**
     * The mechanism this exchange is actually using. Normally the configured one, but
     * [chooseMechanism] may substitute when the server's advertised list (the value of
     * the `sasl` cap) does not include it. Null until SASL starts.
     */
    private var activeMechanism: SaslMechanism? = null
    /**
     * True once we've emitted [IrcAction.EmitAuthFailed] for this session. Bouncers (notably
     * soju forwarding upstream-IRC SASL outcomes after MOTD) can deliver a 90x SASL-fail
     * numeric a second time for the same conceptual auth failure; without dedup the user
     * sees the auth-fail buffer line twice. Reset isn't needed - one IrcSession is created
     * per connect, so a new session starts fresh.
     */
    private var saslAuthFailedEmitted = false

    private val serverCaps = mutableSetOf<String>()
    private val enabledCaps = mutableSetOf<String>()

    /**
     * True once EmitSts has been produced from CAP LS for this session, so a multi-line
     * LS carrying `sts` on an early chunk doesn't emit again on a later chunk. CAP NEW
     * deliberately bypasses this - a post-registration sts announcement is a fresh signal.
     */
    private var stsSignalled = false

    /**
     * Raw capability values from CAP LS / CAP NEW (name lowercased, value verbatim,
     * null for valueless caps). Some caps are pure signals whose VALUE carries the
     * configuration - sts (handled separately), sasl mechanism lists, and
     * draft/account-registration's flags (before-connect, email-required,
     * custom-account-name). Exposed via [capValue] for the command layer.
     */
    private val capValues = mutableMapOf<String, String?>()

    /** True once the ISUPPORT request for draft/extended-isupport has been sent (ACKs arrive chunked). */
    private var isupportRequested = false

    /**
     * True once RPL_WELCOME (001) has been seen, set via [markRegistered] from the
     * core's 001 handler. Lets the CAP handling tell "before registration" from
     * "after": a draft/metadata-2 that arrives via CAP NEW post-registration can be
     * subscribed to immediately (before-connect only governs METADATA sent *during*
     * registration).
     */
    private var registered = false

    /** Called when RPL_WELCOME arrives, so post-registration CAP handling behaves. */
    fun markRegistered() { registered = true }

    /** True once the initial draft/metadata-2 key subscription has been sent (ACKs arrive chunked). */
    private var metadataSubSent = false

    /**
     * Returns the initial draft/metadata-2 subscription line the first time it is
     * needed, or null if the cap is not enabled or the subscription already went out.
     * Self-guarding so both callers (CAP ACK when the server allows metadata during
     * registration, and RPL_WELCOME otherwise) can call it unconditionally.
     *
     * Keys are sent in preference order: the spec processes them in order and stops
     * at the subscription limit, so the most useful key must come first.
     */
    fun takeMetadataSubLine(): String? {
        if (metadataSubSent) return null
        if (!enabledCaps.contains("draft/metadata-2")) return null
        metadataSubSent = true
        // Keys we actually render, in preference order. The server stops at the
        // max-subs limit, so the most visible keys lead: display-name and avatar,
        // then colour and status, then the tap-sheet extras. bio trails since it's
        // the largest and least essential.
        return "METADATA * SUB display-name avatar color status pronouns homepage bio"
    }

    /**
     * True when the server's draft/metadata-2 value carries the `before-connect`
     * token, i.e. it accepts METADATA commands during connection registration.
     * Without it the spec only permits METADATA after registration completes.
     */
    fun metadataBeforeConnect(): Boolean =
        (capValues["draft/metadata-2"] ?: "")
            .split(',')
            .any { it.trim().substringBefore('=').lowercase() == "before-connect" }

    fun capValue(name: String): String? = capValues[name.lowercase()]

    fun hasCap(name: String): Boolean = enabledCaps.contains(name)
    private var scram: ScramSha256Client? = null

    // Buffer for incoming SASL AUTHENTICATE payloads (servers may split into 400-byte chunks).
    private var saslIncomingB64: StringBuilder? = null

    fun onMessage(m: IrcMessage): List<IrcAction> {
        val out = mutableListOf<IrcAction>()

        if (m.command == "CAP" && m.params.getOrNull(1) == "LS") {
            // Cap list is the last param, so it may be a trailing or a middle. The "*" continuation
            // marker never is, hence m.params for that check.
            val capsPart = m.allParams.getOrNull(if (m.params.getOrNull(2) == "*") 3 else 2) ?: ""
            serverCaps.addAll(capsPart.split(' ')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { it.substringBefore('=') }
                .map { it.lowercase() })

            // Record cap values (sasl mech lists, account-registration flags, etc).
            for (tok in capsPart.split(' ')) {
                val t = tok.trim()
                if (t.isEmpty()) continue
                capValues[t.substringBefore('=').lowercase()] =
                    if (t.contains('=')) t.substringAfter('=') else null
            }

            // IRCv3 STS: `sts` always carries a value and must never be REQ'd - just
            // observe it. Any LS chunk may carry it; emit once per session.
            // parseStsCapValue returns null for a valueless or invalid sts, which the
            // spec says to ignore.
            if (!stsSignalled) {
                val stsRaw = capsPart.split(' ')
                    .map { it.trim() }
                    .firstOrNull { it.substringBefore('=').lowercase() == "sts" }
                    ?.let { if (it.contains('=')) it.substringAfter('=') else null }
                parseStsCapValue(stsRaw)?.let { v ->
                    stsSignalled = true
                    out += IrcAction.EmitSts(v.port, v.durationSec, v.preload)
                }
            }

            // CAP LS multi-line: the continuation marker "*" is params[2] (after client-nick and "LS").
            // Using drop(2).any{} would match the client-nick "*" during pre-registration and
            // stall cap negotiation forever on some servers.
            val isContinuation = m.params.getOrNull(2) == "*"
            if (!isContinuation && !capLsDone) {
                capLsDone = true
                out += IrcAction.EmitStatus(tr(R.string.session_cap_ls_complete))
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
            val newCaps = (m.allParams.getOrNull(2) ?: "").split(' ')
                .map { it.trim().substringBefore('=').lowercase() }
                .filter { it.isNotBlank() }
            serverCaps.addAll(newCaps)
            // Record/refresh cap values from CAP NEW too.
            for (tok in (m.allParams.getOrNull(2) ?: "").split(' ')) {
                val t = tok.trim()
                if (t.isEmpty()) continue
                capValues[t.substringBefore('=').lowercase()] =
                    if (t.contains('=')) t.substringAfter('=') else null
            }
            // STS can also arrive via CAP NEW on a live connection (e.g. a secure
            // connection announcing or refreshing its policy after a services sync).
            val stsRawNew = (m.allParams.getOrNull(2) ?: "").split(' ')
                .map { it.trim() }
                .firstOrNull { it.substringBefore('=').lowercase() == "sts" }
                ?.let { if (it.contains('=')) it.substringAfter('=') else null }
            parseStsCapValue(stsRawNew)?.let { v ->
                out += IrcAction.EmitSts(v.port, v.durationSec, v.preload)
            }
            out += IrcAction.EmitStatus(tr(R.string.session_cap_new, newCaps.joinToString(" ")))
            out += IrcAction.EmitCapNew(newCaps)
            val want = buildCapReqList().filter { newCaps.contains(it) && !enabledCaps.contains(it) }
            // CAP NEW is post-registration; we don't send CAP END and the list is small enough
            // that we don't need to chunk it in practice.
            if (want.isNotEmpty()) out += IrcAction.Send("CAP REQ :${want.joinToString(" ")}")
            return out
        }

        // CAP DEL: server withdraws a capability (e.g., after services link-break).
        if (m.command == "CAP" && m.params.getOrNull(1) == "DEL") {
            val delCaps = (m.allParams.getOrNull(2) ?: "").split(' ')
                .map { it.trim().substringBefore('=').lowercase() }
                .filter { it.isNotBlank() }
            serverCaps.removeAll(delCaps.toSet())
            enabledCaps.removeAll(delCaps.toSet())
            out += IrcAction.EmitStatus(tr(R.string.session_cap_del, delCaps.joinToString(" ")))
            out += IrcAction.EmitCapDel(delCaps)
            return out
        }

        if (m.command == "CAP" && m.params.getOrNull(1) == "ACK") {
            val ack = (m.allParams.getOrNull(2) ?: "").split(' ')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { it.substringBefore('=') }
                .map { it.lowercase() }
            enabledCaps.addAll(ack)
            out += IrcAction.EmitStatus(tr(R.string.session_cap_ack, ack.joinToString(" ")))

            // Decrement pending count; only proceed when all chunks are resolved.
            if (pendingCapReqs > 0) pendingCapReqs--

            // draft/extended-isupport: once enabled, ask for the full RPL_ISUPPORT list
            // right away so tokens (CLIENTTAGDENY, FILEHOST, NETWORK, ...) are known
            // before registration completes. The reply is plain 005s (in a
            // draft/isupport batch when batch is also enabled), which flow through the
            // normal ISUPPORT handling; the pre-registration form uses "*" as the
            // client parameter, which the 005 parser already skips.
            if (!isupportRequested && enabledCaps.contains("draft/extended-isupport")) {
                isupportRequested = true
                out += IrcAction.Send("ISUPPORT")
            }

            // draft/metadata-2: subscribe to the keys this client actually renders.
            // During registration this is only permitted when the server advertised
            // `before-connect`; otherwise the subscription is deferred to RPL_WELCOME
            // (see the "001" numeric handler). Once registered, METADATA is always
            // allowed, so a cap enabled later via CAP NEW subscribes here regardless.
            // "*" is the required self-target before a nick has been assigned.
            if (metadataBeforeConnect() || registered) {
                takeMetadataSubLine()?.let { out += IrcAction.Send(it) }
            }

            if (wantSasl && enabledCaps.contains("sasl") && !saslInProgress && !saslDone) {
                // IRCv3.2 servers advertise their mechanism list as the cap value
                // (sasl=PLAIN,EXTERNAL,SCRAM-SHA-256). Consult it before sending
                // AUTHENTICATE rather than discovering the mismatch from a 908 several
                // round-trips later, and with a much less useful message.
                val chosen = chooseMechanism(out)
                if (chosen == null) {
                    saslAbort(out)
                    return out
                }
                activeMechanism = chosen
                saslInProgress = true
                out += IrcAction.EmitStatus(tr(R.string.session_starting_sasl))
                out += IrcAction.Send(startSasl(chosen))
                return out
            }

            maybeCapEnd(out)
            return out
        }

        if (m.command == "CAP" && m.params.getOrNull(1) == "NAK") {
            out += IrcAction.EmitError(tr(R.string.session_cap_nak, m.allParams.getOrNull(2) ?: ""))
            if (pendingCapReqs > 0) pendingCapReqs--
            // NB: must not end negotiation while an AUTHENTICATE exchange is in flight.
            // buildCapReqChunks() splits the request across several lines; an ACK on the
            // chunk carrying "sasl" starts SASL, and a NAK on a later chunk would
            // otherwise send CAP END mid-authentication, which the server reads as an
            // abort and completes registration unauthenticated. The remaining CAP END
            // is emitted by the 903/904-908 handlers once SASL settles.
            maybeCapEnd(out)
            return out
        }

        when (m.command) {
            "903" -> {
                saslDone = true; saslInProgress = false
                out += IrcAction.EmitStatus(tr(R.string.session_sasl_success))
                maybeCapEnd(out)
                return out
            }
            "904", "905", "906", "907" -> {
                saslDone = true; saslInProgress = false
                val reason = m.trailing ?: ""
                // 904/905/906 are credential-rejection or aborted-auth — retrying with the
                // same creds yields the same result. 907 ("already authenticated") is benign
                // and is excluded from the AuthFailed signal so we don't accidentally halt
                // reconnect after a successful re-auth race.
                //
                // We emit ONLY EmitAuthFailed (not also EmitError as before): the viewmodel's
                // AuthFailed handler already prints a buffer line that includes the SASL
                // reason and an actionable hint, so adding a separate EmitError just produces
                // a redundant pair of red lines for one conceptual failure.
                //
                // Dedup via saslAuthFailedEmitted: bouncers sometimes deliver a second 906
                // post-MOTD when relaying upstream-IRC SASL outcomes (soju does this if the
                // bouncer's upstream auth also fails). The second numeric is the same logical
                // event from the user's perspective.
                if (m.command != "907" && !saslAuthFailedEmitted) {
                    saslAuthFailedEmitted = true
                    out += IrcAction.EmitAuthFailed(
                        if (reason.isNotBlank()) "SASL: $reason" else "SASL authentication failed"
                    )
                }
                maybeCapEnd(out)
                return out
            }
            // 908: server does not support our mechanism; it advertises alternatives.
            // Abort cleanly so CAP END is sent and the connection is not left stalled.
            "908" -> {
                // `<nick> <mechanisms> :are available SASL mechanisms` - the list is param 1 and
                // the trailing is prose, so read by index rather than reaching for trailing.
                val alternatives = m.allParams.getOrNull(1) ?: ""
                saslDone = true; saslInProgress = false
                out += IrcAction.EmitError(
                    if (alternatives.isNotBlank())
                        "SASL: mechanism not supported by server. Supported: $alternatives"
                    else
                        "SASL: mechanism not supported by server"
                )
                maybeCapEnd(out)
                return out
            }
        }

        if (m.command == "AUTHENTICATE" && saslInProgress) {
            // May arrive as a middle ("+") or a trailing (":+"); allParams covers both.
            val payload = m.allParams.firstOrNull() ?: ""
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
        if (config.capPrefs.labeledResponse && config.capPrefs.batch) req += "labeled-response"
        if (config.capPrefs.batch) req += "batch"
        if (config.capPrefs.utf8Only) req += "utf8only"

        // History / playback: request both the graduated cap and the legacy draft/ alias so we
        // interoperate with older (draft/chathistory) and modern (chathistory) servers.
        if (config.capPrefs.draftChathistory && config.capPrefs.batch) {
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
        if (wantSasl) req += "sasl"

        // Optional / draft
        if (config.capPrefs.draftRelaymsg) req += "draft/relaymsg"
        if (config.capPrefs.draftReadMarker) req += "draft/read-marker"

        // MONITOR: online/offline status tracking for target nicks
        if (config.capPrefs.monitor) req += "monitor"

        // account-tag: include services account name in PRIVMSG/NOTICE message tags
        if (config.capPrefs.accountTag) req += "account-tag"

        // No typing cap is requested: typing is a client tag carried by message-tags, and whether
        // the server relays it is advertised via CLIENTTAGDENY (see IrcCore.clientTagAllowed).

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
            // Modern ZNC (>= 1.7) advertises BOTH, but enabling both causes the bouncer to
            // play back every buffered message twice (once per time-tag scheme). Per ZNC
            // upstream guidance, only request the legacy cap when the server does not
            // advertise the graduated "server-time" cap OR when the user has disabled it.
            val serverSupportsStandardTime = serverCaps.contains("server-time")
            val wantStandardTime = config.capPrefs.serverTime
            if (!(serverSupportsStandardTime && wantStandardTime)) {
                req += "znc.in/server-time-iso"
            }
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
        if (config.capPrefs.extendedMonitor) {
            req += "draft/extended-monitor"
            req += "extended-monitor"        // graduated: registry lists the bare name
        }

        // draft/message-reactions: TAGMSG +draft/react emoji reactions.
        if (config.capPrefs.messageReactions) req += "draft/message-reactions"

        // draft/no-implicit-names: generic graduated form (not just soju).
        if (config.capPrefs.noImplicitNames) {
            req += "draft/no-implicit-names"
            req += "no-implicit-names"       // graduated: registry lists the bare name
        }

        // draft/metadata-2: user/channel key-value metadata (display names, avatars).
        // The spec REQUIRES the batch capability, and forbids requesting metadata-notify
        // alongside it (hexdroid never requests the deprecated metadata-notify at all).
        // Draft name only, same MUST NOT-unprefixed clause as the other WIP specs.
        if (config.capPrefs.metadata2 && config.capPrefs.batch) req += "draft/metadata-2"

        // draft/extended-isupport: ISUPPORT metadata before registration completes.
        // Its spec also carries the MUST NOT-use-unprefixed-name clause, so only the
        // draft form is requested.
        if (config.capPrefs.extendedIsupport) req += "draft/extended-isupport"

        // draft/account-registration: unlike most drafts here, its spec explicitly says
        // implementations MUST NOT use the unprefixed name while work-in-progress, so
        // only the draft form is requested (no forward-compat bare REQ).
        if (config.capPrefs.accountRegistration) req += "draft/account-registration"

        if (config.capPrefs.messageRedaction) {
            req += "draft/message-redaction"
            req += "message-redaction"       // forward-compat with eventual ratification
        }

        // multiline: receive messages longer than 512 bytes/containing line breaks as a single grouped BATCH.
        if (config.capPrefs.multiline && config.capPrefs.batch) {
            req += "draft/multiline"
            req += "multiline"
        }

        // Web Push: lets the server deliver messages of interest while no TCP connection
        // is open. soju shipped this as a vendored extension before the draft existed and
        // still advertises the vendored name, so both are requested.
        if (config.capPrefs.webPush) {
            req += "draft/webpush"
            req += "soju.im/webpush"
        }

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

    private fun startSasl(mechanism: SaslMechanism): String = "AUTHENTICATE " + wireName(mechanism)

    private fun wireName(mechanism: SaslMechanism): String = when (mechanism) {
        SaslMechanism.PLAIN -> "PLAIN"
        SaslMechanism.EXTERNAL -> "EXTERNAL"
        SaslMechanism.SCRAM_SHA_256 -> "SCRAM-SHA-256"
    }

    /**
     * Decide which mechanism to authenticate with, given the server's advertised list.
     *
     * Returns null when nothing usable is available, having emitted an error naming what
     * the server does offer; the caller aborts SASL so registration continues instead of
     * stalling. Appends any status/error lines to [out].
     *
     * Rules, in order:
     *  - No list advertised (IRCv3.1-era `sasl` with no value): use the configured
     *    mechanism and let the server answer. We can't do better than guessing.
     *  - Configured mechanism is advertised: use it.
     *  - PLAIN configured, SCRAM-SHA-256 offered: silently upgrade to TLS.
     *  - EXTERNAL is never substituted in either direction: it authenticates with a client
     *    certificate, so swapping to or from it changes the identity being asserted.
     */
    private fun chooseMechanism(out: MutableList<IrcAction>): SaslMechanism? {
        val configured = (config.sasl as SaslConfig.Enabled).mechanism
        val advertised = capValues["sasl"]
            ?.split(',')
            ?.map { it.trim().uppercase(Locale.ROOT) }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        if (advertised.isEmpty()) return configured
        if (advertised.contains(wireName(configured))) return configured

        val offered = advertised.joinToString(", ")

        if (configured == SaslMechanism.PLAIN && advertised.contains("SCRAM-SHA-256")) {
            out += IrcAction.EmitStatus(tr(R.string.session_sasl_mech_upgraded, offered))
            return SaslMechanism.SCRAM_SHA_256
        }

        if (configured == SaslMechanism.SCRAM_SHA_256 && advertised.contains("PLAIN")) {
            if (!config.useTls) {
                out += IrcAction.EmitError(tr(R.string.session_sasl_mech_no_downgrade_cleartext, offered))
                return null
            }
            out += IrcAction.EmitError(tr(R.string.session_sasl_mech_downgraded, offered))
            return SaslMechanism.PLAIN
        }

        out += IrcAction.EmitError(
            tr(R.string.session_sasl_mech_unavailable, wireName(configured), offered)
        )
        return null
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
            saslIncomingB64 = null
            // Return whatever was accumulated (possibly empty string), not null.
            // null means "not yet complete"; "" means "complete with empty payload".
            return buf?.toString() ?: ""
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

        // activeMechanism, not s.mechanism: chooseMechanism() may have substituted one
        // the server actually supports, and the rest of the exchange has to follow it.
        when (activeMechanism ?: s.mechanism) {
            SaslMechanism.PLAIN -> when (serverPayload) {
                "+" -> {
                    if (!config.useTls) {
                        out += IrcAction.EmitError(tr(R.string.session_sasl_plain_no_tls))
                        out += IrcAction.Send("AUTHENTICATE *")
                        // Abort locally so CAP END is still sent even if the server
                        // doesn't reply with 906 (the spec says it SHOULD; not all do).
                        // Without this, registration stalls forever after a refused PLAIN.
                        saslAbort(out)
                        return out
                    }
                    // Pre-flight: refuse to send PLAIN when we have no password. Sending an
                    // empty pass would just earn a 904 from the server, but failing fast here
                    // with a clear actionable message is much better UX than the cryptic
                    // "SASL: Authentication failed" the server returns. Common trigger: backup
                    // restore on a fresh install (secrets don't survive uninstall by design).
                    if (s.password.isNullOrEmpty()) {
                        out += IrcAction.EmitError(tr(R.string.session_sasl_plain_no_pw))
                        out += IrcAction.Send("AUTHENTICATE *")
                        saslAbort(out)
                        return out
                    }
                    // Fall back to the connection username (bouncer login) or nick when no
                    // explicit authcid is set. Sending an empty authcid (\u0000\u0000pass)
                    // is rejected by most servers including ZNC.
                    //
                    // For bouncer profiles the authcid must be the *bouncer username*, not
                    // the IRC nick — they're conceptually different (one is "how the
                    // bouncer knows you", the other is "how IRC users see you") and using
                    // the nick produces a 904 SASL fail with bouncers that have a separate
                    // login. config.username is the field the user fills with their bouncer
                    // account name, so prefer it for bouncer profiles. For direct IRCd the
                    // two are usually the same; we still prefer username since IRCv3 SASL
                    // PLAIN expects a stable identity, not a transient nickname.
                    //
                    // Migration guard: 1.6.1 used the IRC nick as the only fallback (no
                    // username preference). Profiles created or last edited under 1.6.1
                    // commonly carry username = "hexdroid" (the literal default) because the
                    // old UI didn't visibly tie that field to SASL auth. If we blindly
                    // preferred username here, those profiles would silently SASL as
                    // "hexdroid/<network>" and the bouncer would reject the unknown account.
                    // So we treat the literal default value as "unset" for the purpose of
                    // authcid fallback - users who genuinely want their bouncer login to
                    // be the string "hexdroid" can set the explicit saslAuthcid field.
                    //
                    // effectiveAuthIdentity then suffixes the result with /network and/or
                    // @clientid per the bouncer kind so the bouncer can route the connection.
                    val baseAuthcid = s.authcid?.takeIf { it.isNotBlank() }
                        ?: config.username.takeIf { it.isNotBlank() && it != DEFAULT_USERNAME }
                        ?: config.nick
                    val authcid = config.effectiveAuthIdentity(baseAuthcid)
                    // RFC 4616: PLAIN carries SASLprep'd values, same as SCRAM. Without
                    // this a non-ASCII password whose keyboard emitted NFD authenticates
                    // against an NFKC-normalised server verifier and fails with a bare 904.
                    val prepped = try {
                        SaslPrep.prepare(authcid) to SaslPrep.prepare(s.password)
                    } catch (e: SaslPrepException) {
                        out += IrcAction.EmitError(tr(R.string.session_sasl_prep_failed, e.message ?: ""))
                        out += IrcAction.Send("AUTHENTICATE *")
                        saslAbort(out)
                        return out
                    }
                    val msg = "\u0000${prepped.first}\u0000${prepped.second}"
                    val b64 = B64.encode(msg.toByteArray(Charsets.UTF_8))
                    out += chunkAuthenticate(b64)
                }
                "*" -> {
                    // Server aborted the exchange.
                    out += IrcAction.EmitError(tr(R.string.session_sasl_plain_aborted))
                    saslAbort(out)
                }
                else -> {
                    // PLAIN is a single-round mechanism; the server should only send "+"
                    // or a numeric. Any other payload is unexpected. abort cleanly so
                    // CAP END is still sent and the connection does not stall.
                    out += IrcAction.EmitError(tr(R.string.session_sasl_plain_unexpected, serverPayload))
                    out += IrcAction.Send("AUTHENTICATE *")
                    saslAbort(out)
                }
            }
            SaslMechanism.EXTERNAL -> when (serverPayload) {
                "+" -> out += IrcAction.Send("AUTHENTICATE +")
                "*" -> {
                    out += IrcAction.EmitError(tr(R.string.session_sasl_external_aborted))
                    saslAbort(out)
                }
                else -> {
                    out += IrcAction.EmitError(tr(R.string.session_sasl_external_unexpected, serverPayload))
                    out += IrcAction.Send("AUTHENTICATE *")
                    saslAbort(out)
                }
            }
            SaslMechanism.SCRAM_SHA_256 -> {
                // Server sends "+" to prompt the client for the first message.
                if (serverPayload == "+" && scram == null && (saslIncomingB64?.isNotEmpty() != true)) {
                    // Pre-flight: refuse to start the exchange at all if we have no password.
                    // ScramSha256Client.hi() derives PBKDF2 by hand over the UTF-8 bytes and
                    // keys an HmacSHA256 Mac with them; SecretKeySpec rejects a zero-length key
                    // with IllegalArgumentException. That would propagate out of
                    // onServerMessage() and kill the connection coroutine.
                    //
                    // The empty-password case is real: after a backup-restore on a fresh
                    // install, the SecretStore is empty (secrets are device-keystore-encrypted
                    // and intentionally don't survive an uninstall), but the imported profile
                    // still carries saslEnabled = true and saslMechanism = SCRAM_SHA_256. The
                    // user hits Connect without re-entering their password and the app dies.
                    //
                    // Emit a clear error so the user knows what to do, then abort SASL the
                    // same way the PLAIN-over-plaintext refusal does. Registration continues
                    // without SASL (CAP END is sent), letting the user reach the server buffer
                    // and read the diagnostic.
                    val pass = s.password
                    if (pass.isNullOrEmpty()) {
                        out += IrcAction.EmitError(tr(R.string.session_sasl_scram_no_pw))
                        out += IrcAction.Send("AUTHENTICATE *")
                        saslAbort(out)
                        return out
                    }
                    // Authcid fallback: prefer the username field (bouncer login) over nick.
                    // Same rationale as PLAIN — see the comment there for the full reasoning,
                    // including the migration guard that treats the literal default username
                    // ("hexdroid") as unset so 1.6.1-era profiles continue to SASL as their
                    // nick rather than as the placeholder default value.
                    val baseAuthcid = s.authcid?.takeIf { it.isNotBlank() }
                        ?: config.username.takeIf { it.isNotBlank() && it != DEFAULT_USERNAME }
                        ?: config.nick
                    val authcid = config.effectiveAuthIdentity(baseAuthcid)
                    val clientNonce = randomNonce()
                    // SASLprep runs in the client's constructor and rejects prohibited
                    // codepoints; abort cleanly rather than letting the exception escape
                    // into the read loop and kill the connection.
                    scram = try {
                        ScramSha256Client(authcid, pass, clientNonce)
                    } catch (e: SaslPrepException) {
                        out += IrcAction.EmitError(tr(R.string.session_sasl_prep_failed, e.message ?: ""))
                        out += IrcAction.Send("AUTHENTICATE *")
                        saslAbort(out)
                        return out
                    }
                    val first = scram!!.clientFirstMessage()
                    val b64 = B64.encode(first.toByteArray(Charsets.UTF_8))
                    out += chunkAuthenticate(b64)
                    return out
                }

                // Server payloads may be chunked into 400-byte AUTHENTICATE messages.
                val fullB64 = consumeSaslServerB64Chunk(serverPayload) ?: return out

                val decoded = try {
                    String(B64.decode(fullB64), Charsets.UTF_8)
                } catch (_: Throwable) {
                    out += IrcAction.EmitError(tr(R.string.session_sasl_decode_fail))
                    out += IrcAction.Send("AUTHENTICATE *")
                    // Defensive abort: server SHOULD reply with 904/906 after our `*` but
                    // some implementations stall instead. Force-end CAP locally.
                    saslAbort(out)
                    return out
                }

                val sc = scram ?: return listOf(IrcAction.EmitError(tr(R.string.session_scram_state_missing)))
                // Defensive try/catch around the SCRAM state machine: hi() can throw
                // IllegalArgumentException on degenerate inputs (empty password — which
                // the pre-flight above already filters, but belt-and-braces), and
                // PBKDF2/HMAC providers can throw GeneralSecurityException for invalid
                // algorithm parameters in rare device-specific cases. Without this
                // catch, any such throw kills the connection coroutine via an
                // uncaught exception, taking the whole connect down with it instead
                // of degrading to a clean 904-style failure path.
                val next = try {
                    sc.onServerMessage(decoded)
                } catch (t: Throwable) {
                    out += IrcAction.EmitError(tr(R.string.session_sasl_scram_aborted, t.message ?: t.javaClass.simpleName))
                    out += IrcAction.Send("AUTHENTICATE *")
                    saslAbort(out)
                    return out
                }
                when (next) {
                    is ScramNext.SendClientFinal -> {
                        val b64 = B64.encode(next.clientFinal.toByteArray(Charsets.UTF_8))
                        out += chunkAuthenticate(b64)
                    }
                    is ScramNext.Done -> if (next.verified) {
                        // Server signature verified@ emit a status so it's visible in logs.
                        // The server will follow up with numeric 903, which is what sets
                        // saslDone = true and sends CAP END. If 903 never arrives (broken
                        // server), the SASL timeout watchdog will abort the connection.
                        out += IrcAction.EmitStatus(tr(R.string.session_scram_sig_verified))
                    } else {
                        // Server signature verification failed (or server sent an "e=" error).
                        // Abort so the server doesn't hang waiting for our client-final, and
                        // also abort locally in case the server itself stalls waiting for our
                        // next AUTHENTICATE rather than responding with 904.
                        out += IrcAction.EmitError(tr(R.string.session_scram_sig_failed))
                        out += IrcAction.Send("AUTHENTICATE *")
                        saslAbort(out)
                    }
                }
            }
        }
        return out
    }

    /**
     * Emit `CAP END` if - and only if - negotiation is genuinely finished: every
     * `CAP REQ` chunk has been answered AND no SASL exchange is still running.
     *
     * Every path that wants to close negotiation goes through here so the SASL guard
     * cannot be forgotten on one of them. It was forgotten on `CAP NAK`: with a cap
     * list long enough for [buildCapReqChunks] to split it, an ACK on the chunk
     * carrying `sasl` starts the exchange and a NAK on a later chunk ended
     * negotiation mid-AUTHENTICATE, which servers read as an abort - the connection
     * then registers unauthenticated with no visible error.
     */
    private fun maybeCapEnd(out: MutableList<IrcAction>) {
        if (capEnded) return
        if (pendingCapReqs > 0) return
        if (saslInProgress) return
        if (wantSasl && !saslDone && enabledCaps.contains("sasl")) return
        capEnded = true
        out += IrcAction.Send("CAP END")
    }

    /**
     * Mark SASL as done (failed) and send CAP END if we haven't already.
     * Call this whenever an unexpected condition aborts the exchange mid-flight
     * so the connection is never left stalled waiting for a 903/904 that won't come.
     */
    private fun saslAbort(out: MutableList<IrcAction>) {
        saslDone = true
        saslInProgress = false
        scram = null
        saslIncomingB64 = null
        maybeCapEnd(out)
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
        return B64.encode(bytes).replace("=", "")
    }
}
