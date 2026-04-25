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

import android.annotation.SuppressLint
import com.boxlabs.hexdroid.connection.ConnectionConstants
import com.boxlabs.hexdroid.data.AutoJoinChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

enum class SaslMechanism { PLAIN, EXTERNAL, SCRAM_SHA_256 }

sealed class SaslConfig {
    data object Disabled : SaslConfig()
    data class Enabled(
        val mechanism: SaslMechanism,
        val authcid: String?,
        val password: String?
    ) : SaslConfig()
}

data class CapPrefs(
    val messageTags: Boolean = true,
    val serverTime: Boolean = true,
    val echoMessage: Boolean = true,
    val labeledResponse: Boolean = true,
    val batch: Boolean = true,
    // Both the graduated cap and its draft alias are requested so we work with
    // older (draft/chathistory) and modern (chathistory) servers simultaneously.
    val draftChathistory: Boolean = true,
    val draftEventPlayback: Boolean = true,
    val utf8Only: Boolean = true,
    val accountNotify: Boolean = true,
    val awayNotify: Boolean = true,
    val chghost: Boolean = true,
    val extendedJoin: Boolean = true,
    val inviteNotify: Boolean = true,
    val multiPrefix: Boolean = true,
    val sasl: Boolean = true,
    val setname: Boolean = false,
    val userhostInNames: Boolean = false,
    val draftRelaymsg: Boolean = false,
    val draftReadMarker: Boolean = true,
    /** IRCv3 MONITOR: track online/offline status of specific nicks. */
    val monitor: Boolean = true,
    /** IRCv3 account-tag: include services account in PRIVMSG/NOTICE tags. */
    val accountTag: Boolean = true,
    /** draft/typing (+typing tag): show when other users are typing. */
    val typingIndicator: Boolean = true,
    /** soju.im/no-implicit-names: suppress automatic NAMES list on JOIN (bouncer only). */
    val sojuNoImplicitNames: Boolean = true,
    /**
     * IRCv3 standard-replies (FAIL/WARN/NOTE): structured error replies from modern IRCd.
     * Ergo, Soju, and InspIRCd 4+ emit these instead of raw numerics.
     */
    val standardReplies: Boolean = true,
    /**
     * IRCv3 pre-away: allows sending AWAY before numeric 001.
     */
    val preAway: Boolean = true,
    /**
     * IRCv3 message-ids (msgid tag): unique ID per message, used for deduplication
     * when echo-message and history replay are both active.
     */
    val messageIds: Boolean = true,
    /**
     * soju.im/read: soju's proprietary read-marker (parallel to draft/read-marker).
     * Requesting both ensures read position syncs on soju-based bouncers.
     */
    val sojuRead: Boolean = true,
    /**
     * WHOX: send WHO #chan %uhsnfar,42 on join to obtain full ident/host/account for all
     * members. Only sent when the server advertises WHOX in ISUPPORT (005).
     */
    val whox: Boolean = true,

    /**
     * draft/channel-rename: handle RENAME commands so channel renames update the buffer
     * key and display name without a full re-join cycle.
     */
    val channelRename: Boolean = true,

    /**
     * draft/extended-monitor: richer MONONLINE replies that include account name and
     * real name alongside the nick!user@host prefix. Ergo 2.13+.
     */
    val extendedMonitor: Boolean = true,

    /**
     * draft/message-reactions: emoji reactions sent via TAGMSG +draft/react.
     * When enabled, incoming reactions are surfaced as status lines in the buffer.
     * Outgoing reactions: long-press a message in the chat UI to pick a preset emoji,
     * or use the /react and /unreact slash commands for arbitrary emoji.
     */
    val messageReactions: Boolean = true,

    /**
     * draft/no-implicit-names: suppress automatic NAMES list on JOIN (generic form,
     * graduated from the draft). Parallel to soju.im/no-implicit-names.
     */
    val noImplicitNames: Boolean = false
)

data class TlsClientCert(
    val pkcs12: ByteArray,
    val password: String? = null
)

data class IrcConfig(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val allowInvalidCerts: Boolean,
    val nick: String,
    val altNick: String?,
    val username: String,
    val realname: String,
    val serverPassword: String? = null,
    val sasl: SaslConfig = SaslConfig.Disabled,
    val clientCert: TlsClientCert? = null,
    val capPrefs: CapPrefs = CapPrefs(),
    val autoJoin: List<AutoJoinChannel> = emptyList(),
    val historyLimit: Int = 50,
    val connectTimeoutMs: Int = ConnectionConstants.SOCKET_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = ConnectionConstants.SOCKET_READ_TIMEOUT_MS,
    val tcpNoDelay: Boolean = false,  // Nagle coalescing is fine for IRC; disabling it causes extra radio wake-ups
    val keepAlive: Boolean = ConnectionConstants.TCP_KEEPALIVE,
    /**
     * Character encoding for this connection.
     * - "auto" = try UTF-8, auto-detect non-UTF-8 encodings
     * - Or explicit: "UTF-8", "windows-1251", "ISO-8859-1", etc.
     */
    val encoding: String = "auto",
    /** True when connecting through a bouncer (ZNC, soju, etc). */
    val isBouncer: Boolean = false,
    /**
     * Optional away message to set at connection time (pre-away).
     * When non-null and the pre-away CAP is negotiated, AWAY is sent before 001
     * so the server marks the user as away from session start.
     */
    val initialAwayMessage: String? = null,
    /**
     * Trust-On-First-Use (TOFU) certificate fingerprint (SHA-256 hex, lowercase, colon-separated).
     *
     * When set, TLS certificate validation is replaced with fingerprint pinning instead of the
     * blanket "trust everything" behaviour of [allowInvalidCerts]:
     *  - On first connect with an unknown/self-signed cert, [allowInvalidCerts] = true is still
     *    needed; the fingerprint is captured and should be persisted by the caller for future use.
     *  - On subsequent connects with a stored fingerprint, the cert is accepted ONLY if its
     *    SHA-256 fingerprint matches — protecting against certificate replacement / MITM.
     *  - If the fingerprint changes, connection is rejected and a [IrcEvent.TlsFingerprintChanged]
     *    event is emitted so the UI can warn the user.
     *
     * When null and [allowInvalidCerts] = true, the legacy "trust everything" path is used.
     */
    val tlsTofuFingerprint: String? = null,
    /**
     * Which bouncer protocol family this profile targets, if any. Drives the syntax that
     * [effectiveAuthIdentity] uses to assemble the SASL authcid and the USER command:
     *
     *  - [BouncerKind.NONE]: direct IRCd connection — auth identity is the username unchanged.
     *  - [BouncerKind.SOJU]:  `user/network@clientid`  (slash before at-sign — soju spec)
     *  - [BouncerKind.ZNC]:   `user@clientid/network`  (at-sign before slash — ZNC FAQ)
     *  - [BouncerKind.GENERIC]: legacy / other bouncers — falls back to the soju-style
     *     `user/network` form because that's what most other bouncers (kiwibnc, pounce in
     *     non-multi mode) accept. No client-id support for generic.
     *
     * The order matters: soju parses the outer `/` first then `@` inside; ZNC parses `@`
     * first then `/`. Producing the wrong order silently routes the connection to the
     * wrong upstream (or fails authentication entirely).
     */
    val bouncerKind: BouncerKind = BouncerKind.NONE,
    /**
     * For bouncers using a network selector in the username: the upstream network name.
     * Composed into the auth identity per [bouncerKind]'s rules. Use [effectiveAuthIdentity]
     * at every send site rather than concatenating inline.
     */
    val bouncerNetworkName: String? = null,
    /**
     * For bouncers supporting per-client identification (ZNC clientbuffer, soju per-client
     * history): a short identifier for THIS device/client (e.g. "phone", "desktop").
     * Composed into the auth identity per [bouncerKind]'s rules. Leave null when not using
     * per-client buffers, or for bouncers that don't support the concept.
     */
    val bouncerClientId: String? = null
) {
    /**
     * Assemble the authentication identity string for this connection, applying the bouncer-
     * specific syntax for embedding the upstream network name and per-client identifier.
     *
     * Defensive guards:
     *  - If [base] already contains a '/', the user has hand-assembled the identity (legacy
     *    workaround from before the dedicated fields existed); we leave it untouched.
     *  - For [BouncerKind.NONE] or with both [bouncerNetworkName] and [bouncerClientId]
     *    blank, the result is just [base] unchanged.
     *
     * Examples:
     *   kind=SOJU,    name="libera",    clientId="phone" → "user/libera@phone"
     *   kind=SOJU,    name="libera",    clientId=null    → "user/libera"
     *   kind=SOJU,    name=null,        clientId="phone" → "user@phone"   (soju per-client only)
     *   kind=ZNC,     name="libera",    clientId="phone" → "user@phone/libera"
     *   kind=ZNC,     name="libera",    clientId=null    → "user/libera"
     *   kind=GENERIC, name="libera",    clientId=*       → "user/libera"  (clientId ignored)
     *   kind=NONE                                         → "user"
     */
    fun effectiveAuthIdentity(base: String): String {
        // Legacy hand-rolled identity — don't double-up.
        if (base.contains('/') || base.contains('@')) return base

        val net = bouncerNetworkName?.takeIf { it.isNotBlank() }
        val cid = bouncerClientId?.takeIf { it.isNotBlank() }

        return when (bouncerKind) {
            BouncerKind.NONE -> base
            BouncerKind.SOJU -> when {
                net != null && cid != null -> "$base/$net@$cid"
                net != null -> "$base/$net"
                cid != null -> "$base@$cid"
                else -> base
            }
            BouncerKind.ZNC -> when {
                net != null && cid != null -> "$base@$cid/$net"
                net != null -> "$base/$net"
                cid != null -> "$base@$cid"
                else -> base
            }
            // Generic bouncers (kiwibnc, pounce single-network, anything not explicitly typed):
            // accept the soju-style `user/network` form. Ignore clientId since the convention
            // varies and we can't safely guess the order.
            BouncerKind.GENERIC -> if (net != null) "$base/$net" else base
        }
    }
}

/**
 * Bouncer protocol family. Determines how [IrcConfig.effectiveAuthIdentity] composes the
 * username/network/clientId fields into the SASL authcid and USER command.
 */
enum class BouncerKind { NONE, SOJU, ZNC, GENERIC }

sealed class IrcEvent {
    data class Status(val text: String) : IrcEvent()
    data class Connected(val server: String) : IrcEvent()
    data class Registered(val nick: String) : IrcEvent()
    data class Disconnected(val reason: String?) : IrcEvent()
    /**
     * Emitted when the server presents a TLS certificate whose fingerprint differs from the
     * stored TOFU fingerprint. The connection is refused. The UI should warn the user — this
     * could indicate a certificate rotation (legitimate) or a MITM attack.
     *
     * @param stored  The fingerprint that was expected (from [IrcConfig.tlsTofuFingerprint]).
     * @param actual  The fingerprint the server actually presented.
     */
    data class TlsFingerprintChanged(val stored: String, val actual: String) : IrcEvent()
    /**
     * Emitted on the first TLS connection when no TOFU fingerprint was stored yet.
     * The caller should persist [fingerprint] in the network profile for future verification.
     */
    data class TlsFingerprintLearned(val fingerprint: String) : IrcEvent()
    data class Error(val message: String) : IrcEvent()

    // get latency from PING/PONG (milliseconds)
    data class LagUpdated(val lagMs: Long?) : IrcEvent()

    // Raw server line (for logging/debug) */
    data class ServerLine(val line: String) : IrcEvent()

    // server output (MOTD/WHOIS/etc)
    data class ServerText(
        val text: String,
        val code: String? = null,
        val bufferName: String? = null
    ) : IrcEvent()

    // CTCP replies
    data class CtcpReply(
        val from: String,
        val command: String,
        val args: String,
        val timeMs: Long? = null
    ) : IrcEvent()


    // ISUPPORT (005) tokens
    data class ISupport(
        val chantypes: String,
        val caseMapping: String,
        val prefixModes: String,
        val prefixSymbols: String,
        val statusMsg: String? = null,
        /** Raw CHANMODES token value (e.g. "b,e,I,k,l,imnpst"). */
        val chanModes: String? = null,
        /**
         * LINELEN ISUPPORT token: maximum bytes per IRC line including the trailing CRLF.
         * RFC 1459 = 512; IRCv3 / Ergo / InspIRCd = typically 4096.
         * Null when the server did not advertise LINELEN (assume 512).
         */
        val linelen: Int? = null
    ) : IrcEvent()

    // Join failure numerics (e.g. 471-477) with the channel extracted
    data class JoinError(val channel: String, val message: String, val code: String) : IrcEvent()

    // Channel modes as reported by RPL_CHANNELMODEIS (324)
    data class ChannelModeIs(val channel: String, val modes: String, val code: String = "324") : IrcEvent()

    // Channel ban list entry (RPL_BANLIST / 367)
    data class BanListItem(
        val channel: String,
        val mask: String,
        val setBy: String? = null,
        val setAtMs: Long? = null,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // End of channel ban list (RPL_ENDOFBANLIST / 368)
    data class BanListEnd(
        val channel: String,
        val code: String = "368",
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // Channel quiet list entry (common: RPL_QUIETLIST / 728)
    data class QuietListItem(
        val channel: String,
        val mask: String,
        val setBy: String? = null,
        val setAtMs: Long? = null,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // End of channel quiet list (common: RPL_ENDOFQUIETLIST / 729)
    data class QuietListEnd(
        val channel: String,
        val code: String = "729",
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // Channel exception list entry (+e) (RPL_EXCEPTLIST / 348)
    data class ExceptListItem(
        val channel: String,
        val mask: String,
        val setBy: String? = null,
        val setAtMs: Long? = null,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // End of channel exception list (+e) (RPL_ENDOFEXCEPTLIST / 349)
    data class ExceptListEnd(
        val channel: String,
        val code: String = "349",
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // Channel invite-exemption list (+I) (RPL_INVEXLIST / 346)
    data class InvexListItem(
        val channel: String,
        val mask: String,
        val setBy: String? = null,
        val setAtMs: Long? = null,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // End of invite-exemption list (+I) (RPL_ENDOFINVEXLIST / 347)
    data class InvexListEnd(
        val channel: String,
        val code: String = "347",
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    data class ChatMessage(
        val from: String,
        val target: String,
        val text: String,
        val isPrivate: Boolean,
        val isAction: Boolean = false,
        val timeMs: Long? = null,
        val isHistory: Boolean = false,
        /** IRCv3 msgid tag — used for deduplication when echo-message and chathistory overlap. */
        val msgId: String? = null,
        /**
         * IRCv3 +draft/reply / +reply tag: the msgid of the message this is a reply to.
         * Non-null when the sender used a reply feature (Ergo, soju, modern clients).
         */
        val replyToMsgId: String? = null,
        /**
         * IRCv3 account-tag: services account name of the sender, when available.
         * Requires the account-tag CAP to be negotiated.
         */
        val senderAccount: String? = null
    ) : IrcEvent()

    data class Notice(
        val from: String,
        // IRC target param (channel, our nick, etc.)
        val target: String,
        val text: String,
        val isPrivate: Boolean,
        /** True when the NOTICE prefix looks like a server prefix (no '!'). */
        val isServer: Boolean = false,
        val timeMs: Long? = null,
        val isHistory: Boolean = false,
        /** IRCv3 msgid tag — used for deduplication. */
        val msgId: String? = null,
        /**
         * IRCv3 +draft/reply / +reply tag: the msgid of the message this NOTICE replies to.
         * Non-null when the sender attached a reply tag.
         */
        val replyToMsgId: String? = null
    ) : IrcEvent()

    data class DccOfferEvent(val offer: DccOffer) : IrcEvent()

    // CTCP DCC CHAT offer
    data class DccChatOfferEvent(val offer: DccChatOffer) : IrcEvent()

    // Numeric 442 (ERR_NOTONCHANNEL)
    data class NotOnChannel(val channel: String, val message: String, val code: String = "442") : IrcEvent()
    /** 381 RPL_YOUREOPER — user successfully authenticated as IRC operator */
    data class YoureOper(val message: String) : IrcEvent()
    /** User MODE -o/-O received on our own nick — de-opered */
    object YoureDeOpered : IrcEvent()
    /** ChannelModeChanged — live MODE change on a channel (not 324 snapshot) */
    data class ChannelModeChanged(val channel: String, val modes: String) : IrcEvent()

    data class Joined(val channel: String, val nick: String, val userHost: String? = null, val timeMs: Long? = null, val isHistory: Boolean = false,
        /** IRCv3 extended-join: services account name sent in JOIN params[1], or null if not logged in ("*"). */
        val account: String? = null,
        /** IRCv3 extended-join: realname (gecos) sent as trailing in JOIN. */
        val realname: String? = null
    ) : IrcEvent()
    data class Parted(val channel: String, val nick: String, val userHost: String? = null, val reason: String?, val timeMs: Long? = null, val isHistory: Boolean = false) : IrcEvent()
    data class Quit(val nick: String, val userHost: String? = null, val reason: String?, val timeMs: Long? = null, val isHistory: Boolean = false) : IrcEvent()

    data class Kicked(
        val channel: String,
        val victim: String,
        val byNick: String?,
        val byHost: String? = null,
        val reason: String? = null,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // Names list items may include prefixes (@,+,%,&,~)
    data class Names(val channel: String, val names: List<String>) : IrcEvent()
    data class NamesEnd(val channel: String) : IrcEvent()

    data class Topic(val channel: String, val topic: String?, val setter: String? = null, val timeMs: Long? = null, val isHistory: Boolean = false) : IrcEvent()
    
    // Topic text reurned by server (RPL_TOPIC / 332) sent after JOIN or /TOPIC.
    data class TopicReply(val channel: String, val topic: String?, val timeMs: Long? = null, val isHistory: Boolean = false) : IrcEvent()

    // Topic setter + time (RPL_TOPICWHOTIME / 333)
    data class TopicWhoTime(val channel: String, val setter: String, val setAtMs: Long?, val timeMs: Long? = null, val isHistory: Boolean = false) : IrcEvent()

	/**
     * Channel user mode change (e.g. MODE #chan +o Nick).
     * @prefix is one of '~','&','@','%','+' depending on mode, or null if not a rank mode.
     */
    data class ChannelUserMode(val channel: String, val nick: String, val prefix: Char?, val adding: Boolean, val timeMs: Long? = null, val isHistory: Boolean = false) : IrcEvent()

    // MODE line for a channel (includes channel modes and user rank mode changes)
    data class ChannelModeLine(val channel: String, val line: String, val timeMs: Long? = null, val isHistory: Boolean = false) : IrcEvent()
    data object ChannelListStart : IrcEvent()
    data class ChannelListItem(val channel: String, val users: Int, val topic: String) : IrcEvent()
    data object ChannelListEnd : IrcEvent()

    data class NickChanged(val oldNick: String, val newNick: String, val timeMs: Long? = null, val isHistory: Boolean = false) : IrcEvent()

    // LagUpdated is defined above with a nullable value so callers can clear lag on disconnect.

    // IRCv3 CHGHOST: user changed their ident/host (requires chghost CAP)
    data class Chghost(
        val nick: String,
        val newUser: String,
        val newHost: String,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // IRCv3 ACCOUNT: user's services account name changed (requires account-notify CAP)
    data class AccountChanged(
        val nick: String,
        /** New account name, or "*" if logged out. */
        val account: String,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // IRCv3 SETNAME: user changed their realname (requires setname CAP)
    data class Setname(
        val nick: String,
        val newRealname: String,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // Incoming INVITE
    data class InviteReceived(
        val from: String,
        val channel: String,
        val timeMs: Long? = null
    ) : IrcEvent()

    // ERROR :message - server-sent fatal error (usually precedes disconnect)
    data class ServerError(val message: String) : IrcEvent()

    // AWAY status change for another user in a shared channel (requires away-notify CAP)
    data class AwayChanged(
        val nick: String,
        /** null = no longer away; non-null = new away message */
        val awayMessage: String?,
        val timeMs: Long? = null
    ) : IrcEvent()

    // IRCv3 CAP NEW: server advertised a new capability after registration
    data class CapNew(val caps: List<String>) : IrcEvent()

    // IRCv3 CAP DEL: server withdrew a previously negotiated capability
    data class CapDel(val caps: List<String>) : IrcEvent()

    /**
     * soju/bouncer network context: emitted when the bouncer sends a BOUNCER NETWORK command
     * indicating which upstream network a message belongs to.
     *
     * Modern bouncers (soju, pounce) multiplex many upstream networks onto a single connection.
     * Each upstream has a networkId that prefixes target names (e.g. "libera/#channel").
     * This event lets the UI show per-upstream channel trees instead of a flat list.
     *
     * @param removed True when the bouncer signalled deletion via `BOUNCER NETWORK <id> *`
     *                (per the soju.im/bouncer-networks spec). When true, [name], [host] and
     *                [state] are all null — the only meaningful field is [networkId].
     */
    data class BouncerNetwork(
        val networkId: String,
        val name: String?,
        val host: String?,
        val state: String?,      // "connected" | "connecting" | "disconnected"
        val removed: Boolean = false
    ) : IrcEvent()

    /**
     * IRCv3 MONITOR: online/offline status notification for a watched nick.
     * Emitted when MONONLINE or MONOFFLINE is received, or on MONLIST reply (731/732).
     *
     * With draft/extended-monitor, MONONLINE entries are nick!user@host [account],
     * so ident, host, and account are populated when available.
     */
    data class MonitorStatus(
        val nick: String,
        val online: Boolean,
        val timeMs: Long? = null,
        /** ident from extended-monitor MONONLINE nick!user@host (null when not present). */
        val ident: String? = null,
        /** host from extended-monitor MONONLINE nick!user@host (null when not present). */
        val host: String? = null,
        /** services account from extended-monitor MONONLINE (null when not logged in or not present). */
        val account: String? = null
    ) : IrcEvent()

    /**
     * IRCv3 draft/read-marker: server sent an updated read marker (last-read message ID)
     * for a buffer. The client can use this to show unread-message indicators.
     *
     * @param target  Channel or nick buffer name.
     * @param timestamp  ISO 8601 timestamp of the last-read message.
     */
    data class ReadMarker(
        val target: String,
        val timestamp: String
    ) : IrcEvent()

    /**
     * IRCv3 draft/typing (+typing tag on TAGMSG): another user is typing (or stopped).
     *
     * @param target  Channel or nick that received the TAGMSG.
     * @param nick    Nick of the user whose typing state changed.
     * @param state   "active" | "paused" | "done"
     */
    data class TypingStatus(
        val target: String,
        val nick: String,
        val state: String,        // "active" | "paused" | "done"
        val timeMs: Long? = null
    ) : IrcEvent()

    /**
     * WHOX reply (354) for a nick: provides enriched ident/host/account data.
     * Emitted after a WHO #chan %uhsnfar,42 query sent on channel join (when WHOX is
     * advertised in ISUPPORT 005).  The UI can use this to enrich the nicklist with
     * full hostname and services account information.
     */
    data class WhoxReply(
        val nick: String,
        val ident: String,
        val host: String,
        /** Services account name, or null if the user is not identified. */
        val account: String? = null,
        /**
         * True when the WHOX flags field starts with 'G' (Gone/away).
         * 'H' means Here (present), 'G' means Gone (away).
         * Null when flags were not included in the reply.
         */
        val isAway: Boolean? = null
    ) : IrcEvent()

    /**
     * draft/channel-rename: the server renamed [oldName] to [newName].
     * The client should update its buffer key, display name, and re-advertise the join.
     */
    data class ChannelRenamed(
        val oldName: String,
        val newName: String,
        val timeMs: Long? = null
    ) : IrcEvent()

    /**
     * draft/message-reactions: a TAGMSG with +draft/react was received.
     * [adding] = true means the reaction was added, false means it was removed.
     */
    data class MessageReaction(
        val fromNick: String,
        val target: String,
        val reaction: String,
        val msgId: String?,
        val adding: Boolean,
        val timeMs: Long? = null
    ) : IrcEvent()

    /**
     * Emitted by /query to ask the ViewModel to open/focus a PM buffer without
     * necessarily sending a message. The ViewModel handles ensureBuffer + selectBuffer.
     */
    data class OpenQueryBuffer(val nick: String) : IrcEvent()
}

class IrcClient(val config: IrcConfig) {
    private val parser = IrcParser()
    private val outbound = Channel<String>(capacity = 300)
    private val rng = SecureRandom()

    companion object {
        /** Pre-allocated CRLF terminator reused on every line send to avoid a ByteArray allocation per write. */
        private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)

        /**
         * Shared [SSLContext] cache, keyed by the tuple that determines the context's trust
         * and key material. Reconnects to the same profile (same trust settings, same client
         * cert) get the same context — and therefore the same JSSE session cache — which
         * lets the platform perform TLS session resumption, skipping the full handshake.
         *
         * The saving is meaningful on mobile: a full TLS handshake costs a round-trip and
         * ~5-15 ms of CPU on a cold radio; resumption halves that. Across a day of
         * reconnects on a flaky network this is real battery.
         *
         * ConcurrentHashMap is safe because connect() may run on multiple dispatcher threads
         * for different networks simultaneously. Entries are never evicted — the cache is
         * bounded by the number of distinct (trust, cert) tuples in use, which is small.
         */
        private val sslContextCache = java.util.concurrent.ConcurrentHashMap<SslContextKey, SSLContext>()

        /**
         * Cache key for [sslContextCache]. Together the fields fully determine the resulting
         * context's trust/key material behaviour:
         *  - [allowInvalidCerts]: strict vs. insecure trust manager (different behaviours).
         *  - [clientCertContentHash]: identity of the mounted client cert (0 when none).
         *    Uses the PKCS12 bytes' [java.util.Arrays.hashCode] so a rotated cert gets a
         *    different key automatically, without us keeping a reference to the secret bytes.
         *  - [clientCertPasswordHash]: password changes also invalidate the cached context.
         *  - [tlsTofuFingerprint]: TOFU pinning uses a profile-specific trust manager, so
         *    two profiles with different pins must NOT share a context.
         */
        private data class SslContextKey(
            val allowInvalidCerts: Boolean,
            val clientCertContentHash: Int,
            val clientCertPasswordHash: Int,
            val tlsTofuFingerprint: String?,
        )
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var lastQuitReason: String? = null
    private var triedAltNick = false
    // True once 001 (RPL_WELCOME) is received. After registration, 433 during pre-reg
    // IRCd's like Ergo sends the correct nick via 001 after SASL completes, so any
    // queued 433 responses for nicks tried before SASL finished should be ignored.
    private var registered = false

    // Tracks where a WHOIS was invoked from so we can route the numeric replies back
    // to that buffer (instead of always dumping them in the server buffer).
    private val pendingWhoisBufferByNick = mutableMapOf<String, String>()

    @Volatile private var currentNick: String = config.nick

    // Lag measurement (client PING -> server PONG RTT)
    @Volatile private var pendingLagPingToken: String? = null
    @Volatile private var pendingLagPingSentAtMs: Long? = null
    @Volatile private var lastLagMs: Long? = null

    // ISUPPORT-derived server features (defaults are RFC1459-ish)
    @Volatile private var chantypes: String = "#&"
    @Volatile private var caseMapping: String = "rfc1459"
    @Volatile private var statusMsg: String? = null
    @Volatile private var chanModes: String? = null
    @Volatile private var prefixModes: String = "qaohv"
    @Volatile private var prefixSymbols: String = "~&@%+"
    @Volatile private var prefixModeToSymbol: Map<Char, Char> = mapOf(
        'q' to '~', 'a' to '&', 'o' to '@', 'h' to '%', 'v' to '+'
    )
    /** True when the server advertises WHOX in ISUPPORT (005). */
    @Volatile private var whoxSupported: Boolean = false
    /**
     * LINELEN from ISUPPORT 005: maximum bytes per IRC line including trailing CRLF.
     * RFC 1459 = 512; Ergo / InspIRCd often advertise 4096.
     * Null until the server sends 005 — callers should treat null as 512.
     */
    @Volatile var serverLinelen: Int? = null
        private set

    // Track joined channels (original case preserved, keyed by casefold)
    private val joinedChannelCases = mutableMapOf<String, String>()

    // Channel for emitting events from commands (merged into events() flow)
    private val commandEvents = Channel<IrcEvent>(capacity = Channel.UNLIMITED)  // Or adjust capacity

    /**
     * Reference to the active IrcSession so that command handlers (handleSlashCommand, etc.)
     * can query negotiated capabilities without being inside the events() channelFlow scope.
     * Written on the IO thread when the session is created; read from any coroutine.
     */
    @Volatile private var sessionRef: IrcSession? = null

    /** Returns true if the given IRCv3 capability was successfully negotiated with the server. */
    fun hasCap(cap: String): Boolean = sessionRef?.hasCap(cap) == true

    /** True if either the graduated or draft chathistory cap is enabled. */
    private fun hasChathistoryCap(): Boolean = hasCap("chathistory") || hasCap("draft/chathistory")

    /** True if either the soju.im/read or draft/read-marker cap is enabled. */
    private fun hasReadMarkerCap(): Boolean = hasCap("draft/read-marker") || hasCap("soju.im/read")

    /** True if the draft/typing or graduated typing cap is enabled. */
    private fun hasTypingCap(): Boolean = hasCap("draft/typing") || hasCap("typing")

    /** True if either the graduated or draft pre-away cap is enabled. */
    private fun hasPreAwayCap(): Boolean = hasCap("pre-away") || hasCap("draft/pre-away")

    /** True if either the graduated or draft standard-replies cap is enabled. */
    @Suppress("unused")
    private fun hasStandardRepliesCap(): Boolean = hasCap("standard-replies") || hasCap("draft/standard-replies")

    /**
     * Generate a unique label for labeled-response correlation.
     * Labels are short alphanumeric strings; we use a simple monotonic counter
     * prefixed with "h" so they're valid as IRC parameter tokens.
     */
    private val labelCounter = java.util.concurrent.atomic.AtomicLong(0)
    // CTCP flood protection: track last reply time per nick so we respond at most
    // once per CTCP_RATE_LIMIT_MS per sender. This prevents a remote user from
    // causing a K-line by flooding CTCP requests at us.
    private val ctcpLastReplyMs = mutableMapOf<String, Long>()
    private val CTCP_RATE_LIMIT_MS = 5_000L
    private fun nextLabel(): String = "h${labelCounter.incrementAndGet()}"

    /**
     * Build an optional `@label=<id>` tag prefix for use with labeled-response.
     * Returns empty string if the cap is not negotiated (so callers can always
     * prefix their sendRaw calls without an extra hasCap check).
     */
    private fun labelTag(): String = if (hasCap("labeled-response")) "@label=${nextLabel()} " else ""

	/**
	 * STATUSMSG targets (e.g. "@#chan") should be routed to the underlying channel buffer.
	 * See ISUPPORT STATUSMSG.
	 */
	private fun normalizeMsgTarget(target: String): String {
		val t = target.trim()
		val sm = statusMsg
		return if (sm != null && t.length >= 2 && sm.indexOf(t[0]) >= 0 && chantypes.indexOf(t[1]) >= 0) {
			t.substring(1)
		} else {
			t
		}
	}

    private fun isChannelName(name: String): Boolean =
        name.isNotEmpty() && chantypes.contains(name[0])

    /**
     * True if [nick] is a bouncer-provided pseudo-user whose messages should route to the
     * server buffer rather than opening a query window. Matches both conventions:
     *
     *  - ZNC modules: any nick starting with `*` (e.g. `*status`, `*playback`, `*clientbuffer`,
     *    `*controlpanel`, plus any user-loaded module). The `*` prefix is reserved by ZNC and
     *    no real user can hold a nick starting with `*`.
     *  - soju: the single named pseudo-user `BouncerServ` (soju doesn't use a prefix convention).
     *
     * Called from both the PRIVMSG and NOTICE handlers — ZNC's `*status` replies via NOTICE to
     * commands issued through `/znc …`, and without this routing those NOTICEs would open a
     * query window with the pseudo-user instead of rendering inline with the server log.
     */
    private fun isBouncerPseudoUser(nick: String): Boolean {
        if (nick.isEmpty()) return false
        if (nick.startsWith("*")) return true
        return nick.equals("BouncerServ", ignoreCase = true)
    }

    /**
     * Mask type for channel-op commands (/ban, /kickban, /mute). Chosen with a short
     * keyword after the nick: `/ban spammer host` → ban `*!*@<host>`.
     *
     *  - [NICK] — `nick!*@*`. Weakest; trivial to evade by changing nick. Current default.
     *  - [USER] — `*!<user>@*`. Bans the ident/username; survives nick changes, breaks
     *    if the user can control their ident (many desktop clients let them).
     *  - [HOST] — `*!*@<host>`. Bans the whole hostname; most common "real" ban and the
     *    strongest that works everywhere.
     *  - [DOMAIN] — `*!*@*.<base-domain>`. Bans the entire reverse-DNS suffix; useful
     *    against users who rotate addresses within one ISP or cloaking domain.
     *  - [ACCOUNT] — `$a:<services-account>`. IRCv3 extban, strongest where supported —
     *    survives nick changes, host changes, and reconnects. Requires the user to be
     *    logged in to services; we get this from 330 (RPL_WHOISACCOUNT).
     *  - [RAW] — the user typed a literal mask (contains `!`, `@`, or starts with `$`).
     *    Passed through unchanged.
     */
    private enum class BanMaskType { NICK, USER, HOST, DOMAIN, ACCOUNT, RAW }

    /**
     * Parse an optional mask-type keyword. `n|nick`, `u|user|ident`, `h|host`, `d|domain`,
     * `a|acct|account`. Returns null if the keyword isn't recognised — callers treat that
     * as the default ([BanMaskType.NICK]).
     */
    private fun parseMaskType(kw: String?): BanMaskType? = when (kw?.lowercase()) {
        null, "" -> null
        "n", "nick" -> BanMaskType.NICK
        "u", "user", "ident" -> BanMaskType.USER
        "h", "host" -> BanMaskType.HOST
        "d", "domain" -> BanMaskType.DOMAIN
        "a", "acct", "account" -> BanMaskType.ACCOUNT
        else -> null
    }

    /** True if [s] already looks like a ban mask rather than a plain nick. */
    private fun looksLikeRawMask(s: String): Boolean =
        s.contains('!') || s.contains('@') || s.startsWith("$")

    /**
     * Build a domain-wildcard mask from a hostname.
     * `foo.bar.isp.example` → `*!*@*.isp.example`; keeps the final two labels, wildcards
     * the rest. IPv4-ish strings (all-numeric labels) are passed through as `*!*@<host>`
     * so we don't produce meaningless `*.1.2` masks.
     */
    private fun buildDomainMask(host: String): String {
        if (host.isBlank()) return "*!*@*"
        // IPv4 literal — don't mangle.
        if (host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) return "*!*@$host"
        // IPv6 literal (contains ':') — can't meaningfully domain-wildcard; fall back to host.
        if (host.contains(':')) return "*!*@$host"
        val labels = host.split('.')
        return if (labels.size <= 2) "*!*@$host"
        else "*!*@*." + labels.takeLast(2).joinToString(".")
    }

    /**
     * A ban queued while we wait for a WHOIS to supply the required host/account data.
     *
     * [channel]     — channel to apply +b/+q on.
     * [type]        — mask type; determines how we interpret the 311/330 reply.
     * [quiet]       — if true, use +q (mute) instead of +b (ban).
     * [alsoKick]    — if true, issue KICK after the mode is set.
     * [kickReason]  — reason for the kick.
     * [queuedAtMs]  — used to age-out stale entries if WHOIS is slow or never replies.
     */
    private data class PendingBan(
        val channel: String,
        val type: BanMaskType,
        val quiet: Boolean,
        val alsoKick: Boolean,
        val kickReason: String,
        val queuedAtMs: Long = System.currentTimeMillis(),
    )

    /**
     * WHOIS-pending bans keyed by casefolded nick. A single nick can have multiple entries
     * queued (e.g. user typed `/ban spammer host` then `/kickban spammer account` in quick
     * succession). All are flushed when the WHOIS reply arrives.
     *
     * Entries older than [PENDING_BAN_TIMEOUT_MS] are discarded when a reply arrives or
     * when we add a new entry; this avoids applying a stale ban if the user WHOIS'd
     * themselves much later on.
     */
    private val pendingBansByNick = mutableMapOf<String, MutableList<PendingBan>>()

    /**
     * Bridge between 311 (RPL_WHOISUSER) and 330 (RPL_WHOISACCOUNT) for ACCOUNT-type
     * pending bans: 311 carries user/host, 330 carries the services account name, and
     * we need both pieces in [completePendingBans] to either build the `$a:account`
     * mask or fall back gracefully. Cleared on 330 or 318.
     */
    private val pendingWhoisHostByNick = mutableMapOf<String, Pair<String, String>>()

    /**
     * Max age of a [PendingBan] before it's discarded. A nick's WHOIS normally completes
     * in < 1 s on a healthy connection; 10 s is generous enough to cover a bouncer
     * round-trip on flaky mobile networks without applying stale bans long after the
     * user has moved on.
     */
    private val PENDING_BAN_TIMEOUT_MS = 10_000L

    /**
     * Drop [pendingBansByNick] entries older than [PENDING_BAN_TIMEOUT_MS]. Called
     * whenever a new entry is queued or a WHOIS reply arrives so the map doesn't grow.
     */
    private fun pruneExpiredPendingBans() {
        val cutoff = System.currentTimeMillis() - PENDING_BAN_TIMEOUT_MS
        val toRemove = mutableListOf<String>()
        for ((fold, list) in pendingBansByNick) {
            list.removeAll { it.queuedAtMs < cutoff }
            if (list.isEmpty()) toRemove.add(fold)
        }
        for (k in toRemove) pendingBansByNick.remove(k)
    }

    /**
     * Result of parsing a slash command of the shape
     *   `/cmd [#channel] <target> [args...]`
     *
     *  [chan]   — channel the command applies to. Either the explicit `#channel` arg or
     *             the current buffer if it's a channel.
     *  [target] — the nick / mask / etc. that comes after the channel (or first if no
     *             explicit channel was given). Null only when the command was called with
     *             needsTarget = false and no target was supplied.
     *  [tail]   — remaining tokens after the target (mask type, kick reason, etc.).
     */
    private data class ParsedChanTarget(
        val chan: String,
        val target: String?,
        val tail: List<String>,
    )

    /**
     * Parse a `/cmd [#channel] <target> [tail...]` style invocation, surfacing user-friendly
     * usage and "needs a channel" errors via [commandEvents] instead of silently returning.
     *
     * Channel-op slash commands (/kick, /ban, /unban, /kb, /mute, /unmute, etc.) all share
     * the same arg shape: an optional leading `#channel`, then a target nick/mask, then
     * command-specific tail args. Without this helper each command repeated the same
     * `parts.getOrNull(1) ?: return` boilerplate, which silently no-op'd if the user typed
     * the command from a server buffer with no channel arg or omitted the target.
     *
     * Returns null and emits a notice on bad input; non-null result has [ParsedChanTarget.chan]
     * guaranteed to satisfy [isChannelName].
     */
    private suspend fun parseChanTargetCommand(
        parts: List<String>,
        cmd: String,
        usageHint: String,
        needsTarget: Boolean,
    ): ParsedChanTarget? {
        val a1 = parts.getOrNull(1)
        if (a1.isNullOrBlank()) {
            commandEvents.send(IrcEvent.Notice(
                from = "*", target = currentBuffer,
                text = "Usage: /$cmd $usageHint", isPrivate = true,
            ))
            return null
        }
        val chan: String
        val target: String?
        val restIdx: Int
        if (isChannelName(a1)) {
            // Explicit channel arg.
            val t = parts.getOrNull(2)
            if (needsTarget && t.isNullOrBlank()) {
                commandEvents.send(IrcEvent.Notice(
                    from = "*", target = currentBuffer,
                    text = "Usage: /$cmd $usageHint", isPrivate = true,
                ))
                return null
            }
            chan = a1
            target = t
            restIdx = 3
        } else {
            // No explicit channel — use the current buffer if it is one.
            chan = currentBuffer
            target = a1
            restIdx = 2
        }
        if (!isChannelName(chan)) {
            commandEvents.send(IrcEvent.Notice(
                from = "*", target = currentBuffer,
                text = "/$cmd needs a channel — switch to one, or pass #channel as the first argument",
                isPrivate = true,
            ))
            return null
        }
        return ParsedChanTarget(chan, target, parts.drop(restIdx))
    }

    /**
     * Apply a ban/mute synchronously if [type] doesn't need remote data, otherwise queue
     * a [PendingBan] and issue a WHOIS so the 311 / 330 handler can finish the job.
     *
     * Slash commands ([handleSlashCommand] cases for ban / unban / kickban / mute / unmute)
     * funnel through this so the mask-construction logic lives in one place.
     *
     * [nickOrMask] — what the user typed. If it already looks like a mask (contains `!`,
     *                `@`, or starts with `$`), [type] is forced to [BanMaskType.RAW] and
     *                the string is passed through unchanged.
     * [quiet]      — +q instead of +b. Server must support `+q` in CHANMODES; otherwise
     *                we fall back to +b silently so the command still has an effect.
     * [alsoKick]   — issue KICK after the mode (for /kickban and /kb).
     */
    private suspend fun applyBanOrQueue(
        channel: String,
        nickOrMask: String,
        type: BanMaskType,
        quiet: Boolean,
        alsoKick: Boolean,
        kickReason: String,
    ) {
        val modeChar = if (quiet && supportsQuietMode()) 'q' else 'b'

        // Raw mask — bypass everything.
        if (type == BanMaskType.RAW || looksLikeRawMask(nickOrMask)) {
            sendRaw("MODE $channel +$modeChar $nickOrMask")
            // Raw masks don't have a single nick to KICK, so alsoKick is a no-op here.
            // If the user really wanted /kickban on a raw mask, they probably also want
            // to kick a specific nick separately.
            return
        }

        val nick = nickOrMask
        val mask = when (type) {
            BanMaskType.NICK -> "$nick!*@*"
            else -> null  // needs WHOIS
        }

        if (mask != null) {
            sendRaw("MODE $channel +$modeChar $mask")
            if (alsoKick) {
                sendRaw(if (kickReason.isBlank()) "KICK $channel $nick" else "KICK $channel $nick :$kickReason")
            }
            return
        }

        // Queue and WHOIS.
        pruneExpiredPendingBans()
        val fold = casefold(nick)
        pendingBansByNick.getOrPut(fold) { mutableListOf() }.add(
            PendingBan(
                channel = channel,
                type = type,
                quiet = quiet,
                alsoKick = alsoKick,
                kickReason = kickReason,
            )
        )
        // Also stash the current buffer so WHOIS reply surfaces there.
        if (pendingWhoisBufferByNick.size >= 50) pendingWhoisBufferByNick.clear()
        pendingWhoisBufferByNick[fold] = channel
        sendRaw("WHOIS $nick $nick")  // double-nick form gets idle + full info on most ircds
        commandEvents.send(IrcEvent.Status("Looking up $nick for ${type.name.lowercase()}-based ban…"))
    }

    /**
     * True if the server's CHANMODES advertises `+q` as a list mode (mute/quiet).
     * Checked via ISUPPORT. Conservative: returns false if unknown, which causes
     * [applyBanOrQueue] to fall back to +b. Ircds known to support +q include
     * InspIRCd, UnrealIRCd, Charybdis/Solanum, ircd-seven (freenode/libera).
     */
    private fun supportsQuietMode(): Boolean {
        // CHANMODES is parsed into chanModes; its first segment lists type-A (list) modes.
        val cm = chanModes ?: return false
        val typeA = cm.substringBefore(',', cm)
        return typeA.contains('q')
    }

    /**
     * Build the final mask from WHOIS data and apply a queued [PendingBan]. Called from
     * the 311 (RPL_WHOISUSER) handler for HOST/USER/DOMAIN, and from 330 (RPL_WHOISACCOUNT)
     * for ACCOUNT. 311 fires for every successful WHOIS; 330 only when the user is
     * logged in to services.
     */
    private suspend fun completePendingBans(nick: String, user: String?, host: String?, account: String?) {
        val fold = casefold(nick)
        val queued = pendingBansByNick.remove(fold) ?: return
        pruneExpiredPendingBans()
        val now = System.currentTimeMillis()
        for (pb in queued) {
            if (now - pb.queuedAtMs > PENDING_BAN_TIMEOUT_MS) continue
            val mask = when (pb.type) {
                BanMaskType.USER    -> if (!user.isNullOrBlank()) "*!${user}@*" else null
                BanMaskType.HOST    -> if (!host.isNullOrBlank()) "*!*@${host}" else null
                BanMaskType.DOMAIN  -> if (!host.isNullOrBlank()) buildDomainMask(host) else null
                BanMaskType.ACCOUNT -> if (!account.isNullOrBlank()) "\$a:${account}" else null
                BanMaskType.NICK, BanMaskType.RAW -> "$nick!*@*"  // shouldn't reach here
            }
            if (mask == null) {
                val reason = when (pb.type) {
                    BanMaskType.ACCOUNT -> "$nick is not logged in to services"
                    else -> "couldn't resolve host for $nick"
                }
                commandEvents.send(IrcEvent.Error("Ban by ${pb.type.name.lowercase()} failed — $reason. Falling back to nick mask."))
                val modeChar = if (pb.quiet && supportsQuietMode()) 'q' else 'b'
                sendRaw("MODE ${pb.channel} +$modeChar $nick!*@*")
            } else {
                val modeChar = if (pb.quiet && supportsQuietMode()) 'q' else 'b'
                sendRaw("MODE ${pb.channel} +$modeChar $mask")
            }
            if (pb.alsoKick) {
                sendRaw(if (pb.kickReason.isBlank()) "KICK ${pb.channel} $nick"
                        else "KICK ${pb.channel} $nick :${pb.kickReason}")
            }
        }
    }

    private fun casefold(s: String): String {
        val cm = caseMapping.lowercase(Locale.ROOT)
        val sb = StringBuilder(s.length)

        for (ch0 in s) {
            var ch = ch0

            // Standard ASCII case folding (always applied for all modes)
            if (ch in 'A'..'Z') ch = (ch.code + 32).toChar()

            // Non-ASCII case folding based on server-advertised CASEMAPPING.
            //
            // rfc1459 / strict-rfc1459:
            //   Map the four extended ASCII pairs used in old European IRC nicks.
            //   rfc1459 additionally equates ^ and ~ (the "tilde" pair).
            //
            // ascii:
            //   Only ASCII A-Z folding; all other chars are left as-is.
            //
            // Non-standard caseMapping values (e.g. "BulgarianCyrillic+EnglishAlphabet"):
            //   Use Char.lowercaseChar() for full Unicode lowercasing, then apply the
            //   standard RFC1459 special-char pairs on top.  This is correct for any
            //   Cyrillic-based network and is a safe fallback for any other unknown mapping.
            //
            // Unknown / unrecognised:
            //   Fall back to rfc1459-like behaviour (the most common default on IRC).
            when (cm) {
                "rfc1459", "strict-rfc1459" -> {
                    ch = when (ch) {
                        '[', '{' -> '{'
                        ']', '}' -> '}'
                        '\\', '|' -> '|'
                        else -> ch
                    }
                    if (cm == "rfc1459") {
                        if (ch == '^' || ch == '~') ch = '~'
                    }
                }

                "ascii" -> { /* ASCII-only: A-Z already handled above */ }

                else -> {
                    // Full Unicode lowercasing covers Cyrillic, Greek, and any other script
                    // advertised via a non-standard CASEMAPPING token.
                    ch = ch.lowercaseChar()
                    // Also apply RFC1459 special-char pairs, which many non-ASCII IRC
                    // networks still use in nick/channel names alongside Cyrillic.
                    ch = when (ch) {
                        '[', '{' -> '{'
                        ']', '}' -> '}'
                        '\\', '|' -> '|'
                        '^', '~' -> '~'
                        else -> ch
                    }
                }
            }

            sb.append(ch)
        }
        return sb.toString()
    }

    private fun nickEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return casefold(a) == casefold(b)
    }

    @Volatile private var userClosing: Boolean = false
    @Volatile private var lastTlsInfo: String? = null

    /**
     * TOFU: the fingerprint we learned during THIS connection's TLS handshake when no prior
     * fingerprint was stored. Non-null signals the [events] flow to emit [IrcEvent.TlsFingerprintLearned]
     * so the caller can persist it. Cleared after emission to avoid re-firing on reconnect
     * within the same [IrcClient] instance.
     */
    @Volatile private var learnedFingerprint: String? = null

    fun tlsInfo(): String? = lastTlsInfo
	
	fun isConnectedNow(): Boolean {
		val s = socket ?: return false
		if (!s.isConnected || s.isClosed) return false
		// Additional check: try to peek at the input stream availability.
		// This helps detect half-open connections that Java's socket state doesn't catch.
		return try {
			// If the socket is truly connected, getting inputStream should work.
			// We're not reading from it, just checking it's accessible.
			s.isInputShutdown.not() && s.isOutputShutdown.not()
		} catch (_: Exception) {
			false
		}
	}

	suspend fun disconnect(reason: String) {
		userClosing = true
		lastQuitReason = reason

		// send QUIT before closing
		runCatching { outbound.send("QUIT :$reason") }

		delay(250)

		// Close + null out the socket so isConnectedNow() becomes accurate immediately
		val s = socket
		socket = null
		runCatching { s?.close() }
	}

	/**
	 * Immediate hard close (no QUIT / no delay). Useful when reconnecting so we don't
	 * briefly end up with two live sockets during network handovers.
	 */
	fun forceClose(reason: String? = null) {
		userClosing = true
		if (reason != null) lastQuitReason = reason

		val s = socket
		socket = null
		runCatching { s?.close() }
		runCatching { outbound.close() }
	}

    suspend fun sendRaw(line: String) {
        // Sanitize: Remove any embedded CR/LF to prevent protocol injection.
        // IRC uses CRLF as line delimiter; embedded newlines would be interpreted
        // as separate commands, causing "Unknown command" errors.
        val sanitized = line.replace("\r", "").replace("\n", " ").trim()
        if (sanitized.isNotEmpty()) {
            // Use trySend so that calling sendRaw on a disconnecting/reconnecting client
            // (whose outbound Channel may have been closed by forceClose()) never throws
            // ClosedSendChannelException (surfaced in crash reports as obfuscated k7.m).
            // If the channel is closed or full, the line is silently dropped - this is
            // safe because the connection is already gone or saturated.
            val result = outbound.trySend(sanitized)
            if (result.isFailure && !result.isClosed) {
                // Channel is full (capacity=300) but still open - fall back to a
                // suspending send so legitimate bursts are not silently discarded.
                // This path is rare; the capacity guard above handles the common cases.
                runCatching { outbound.send(sanitized) }
            }
        }
    }

    /**
     * Request older history for [target] using IRCv3 CHATHISTORY BEFORE.
     *
     * Servers that support `draft/chathistory` will send back at most [limit] messages
     * that occurred strictly before [beforeTimestamp] (ISO 8601, e.g. "2024-01-15T10:00:00.000Z").
     * Pass null for [beforeTimestamp] to use the oldest message already displayed (i.e. server
     * decides the anchor point, which most implementations treat as the current oldest).
     *
     * This is used to implement "pull up to load more history" in the chat scroll view.
     */
    suspend fun requestChatHistoryBefore(
        target: String,
        beforeTimestamp: String?,
        limit: Int = 50
    ) {
        if (!hasChathistoryCap()) return
        val anchor = if (beforeTimestamp != null) "timestamp=$beforeTimestamp" else "*"
        sendRaw("${labelTag()}CHATHISTORY BEFORE $target $anchor $limit")
    }

    /**
     * Request the unread history for [target] using CHATHISTORY LATEST with an after-timestamp
     * anchor so we only fetch messages newer than [afterTimestamp].
     *
     * Used after a reconnect or when the server notifies us via read-marker that messages
     * in this buffer haven't been seen yet.
     */
    suspend fun requestChatHistoryAfter(
        target: String,
        afterTimestamp: String,
        limit: Int = 100
    ) {
        if (!hasChathistoryCap()) return
        sendRaw("${labelTag()}CHATHISTORY AFTER $target timestamp=$afterTimestamp $limit")
    }

    suspend fun privmsg(target: String, text: String) {
        // This is a safeguard in case callers don't pre-split multiline messages.
        val sanitizedText = text.replace("\r", "").replace("\n", " ")
        // Attach a label when echo-message + labeled-response are both active so we can
        // correlate the echoed reply back to our outbound message for deduplication.
        val tag = if (hasCap("echo-message") && hasCap("labeled-response")) labelTag() else ""
        sendRaw("${tag}PRIVMSG $target :$sanitizedText")
    }

    /**
     * Send a draft/typing indicator TAGMSG to [target].
     *
     * [state] must be one of: "active" (user is composing), "paused" (user stopped briefly),
     * or "done" (user cleared input or sent message).
     *
     * No-op if the draft/typing capability was not negotiated.
     */
    suspend fun sendTypingStatus(target: String, state: String) {
        // Require either a dedicated typing cap OR the base message-tags cap.
        // Servers like Libera advertise CLIENTTAGDENY=*,-typing rather than a separate
        // typing cap - they permit the tag via message-tags without advertising it separately.
        val hasTyping = hasCap("typing") || hasCap("draft/typing")
        val hasTagsPermit = hasCap("message-tags")
        if (!hasTyping && !hasTagsPermit) return
        // Graduated "typing" cap uses the standard tag name (no "+" prefix).
        // Draft or message-tags fallback uses the vendor tag "+typing".
        val tag = if (hasCap("typing")) "typing=$state" else "+typing=$state"
        sendRaw("@$tag TAGMSG $target")
    }

    /**
     * Send a draft/message-reactions emoji reaction to [msgId] in [target].
     * Requires the message-tags cap (reactions use client-only tags).
     * Pass [remove] = true to un-react (sends +draft/react-removed instead).
     */
    suspend fun sendReaction(target: String, msgId: String, emoji: String, remove: Boolean = false) {
        if (!hasCap("message-tags") && !hasCap("draft/message-reactions")) return
        val tagName = if (remove) "+draft/react-removed" else "+draft/react"
        val labelPart = if (hasCap("labeled-response")) "@label=${nextLabel()} " else ""
        sendRaw("${labelPart}@$tagName=${emoji.trim()};+draft/reply=$msgId TAGMSG $target")
    }

    /**
     * Request messages around a specific message ID using CHATHISTORY AROUND.
     *
     * Useful for providing context when jumping to a linked or referenced message.
     * [aroundMsgId] is the IRCv3 msgid of the pivot message.
     */
    suspend fun requestChatHistoryAround(
        target: String,
        aroundMsgId: String,
        limit: Int = 50
    ) {
        if (!hasChathistoryCap()) return
        sendRaw("CHATHISTORY AROUND $target msgid=$aroundMsgId $limit")
    }

    /**
     * Request the list of all targets (channels + queries) for which the server holds stored
     * history using IRCv3 CHATHISTORY TARGETS.  The server responds with a series of
     * numeric 761 (RPL_LISTSTART) + 762 (RPL_LIST) + 763 (RPL_LISTEND) messages
     * wrapped in a batch.  Results are surfaced as [IrcEvent.ServerText] lines.
     *
     * Only sent when the [hasChathistoryCap] is negotiated.
     */
    suspend fun requestChatHistoryTargets(limit: Int = 50) {
        if (!hasChathistoryCap()) return
        sendRaw("${labelTag()}CHATHISTORY TARGETS * timestamp=* $limit")
    }

    suspend fun ctcp(target: String, payload: String) {
        // CTCP wrapped with 0x01
        privmsg(target, "\u0001$payload\u0001")
    }

    suspend fun handleSlashCommand(cmdLine: String, currentBuffer: String) {
        val parts = cmdLine.trim().split(Regex("\\s+"))
        if (parts.isEmpty()) return
        val cmd = parts[0].lowercase()

        when (cmd) {
            "join" -> parts.getOrNull(1)?.let { chan ->
                val key = parts.getOrNull(2)
                sendRaw(if (key.isNullOrBlank()) "JOIN $chan" else "JOIN $chan $key")
            }
            "part" -> {
                val arg1 = parts.getOrNull(1)
                val hasChan = arg1 != null && isChannelName(arg1)
                val chan = when {
                    hasChan -> arg1
                    currentBuffer != "*server*" -> currentBuffer
                    else -> arg1 ?: return
                }
                val reason = (if (hasChan) parts.drop(2) else parts.drop(1)).joinToString(" ").trim()
                sendRaw(if (reason.isBlank()) "PART $chan" else "PART $chan :$reason")
            }
            "cycle" -> {
                val arg1 = parts.getOrNull(1)
                val hasChan = arg1 != null && isChannelName(arg1)
                val chan = when {
                    hasChan -> arg1
                    currentBuffer != "*server*" -> currentBuffer
                    else -> arg1 ?: return
                }
                val key = if (hasChan) parts.getOrNull(2) else parts.getOrNull(1)
                sendRaw("PART $chan :Rejoining")
                // Small delay so servers process PART before JOIN
                delay(300)
                sendRaw(if (key.isNullOrBlank()) "JOIN $chan" else "JOIN $chan $key")
            }
            "msg" -> {
                val target = parts.getOrNull(1)
                val msg = parts.drop(2).joinToString(" ")
                if (target.isNullOrBlank() || msg.isBlank()) {
                    commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
                        text = "Usage: /msg <nick|#channel> <message>", isPrivate = true))
                    return
                }
                privmsg(target, msg)
            }
            // /query <nick> [message] - open a PM buffer with a user (buffer switching handled in ViewModel)
            // /query with no message just opens the buffer; with a message it sends it too.
            "query" -> {
                val target = parts.getOrNull(1)
                if (target.isNullOrBlank()) {
                    commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
                        text = "Usage: /query <nick> [message]", isPrivate = true))
                    return
                }
                val msg = parts.drop(2).joinToString(" ").trim()
                if (msg.isNotBlank()) privmsg(target, msg)
                // Signal the ViewModel to open/focus the query buffer via a fake incoming event.
                commandEvents.trySend(IrcEvent.OpenQueryBuffer(target))
            }
            // Services shorthands: /ns, /cs, /as, /hs, /ms, /bs
            "ns" -> {
                val rest = parts.drop(1).joinToString(" ").trim()
                if (rest.isNotBlank()) privmsg("NickServ", rest)
            }
            "cs" -> {
                val rest = parts.drop(1).joinToString(" ").trim()
                if (rest.isNotBlank()) privmsg("ChanServ", rest)
            }
            "as" -> {
                val rest = parts.drop(1).joinToString(" ").trim()
                if (rest.isNotBlank()) privmsg("AuthServ", rest)
            }
            "hs" -> {
                val rest = parts.drop(1).joinToString(" ").trim()
                if (rest.isNotBlank()) privmsg("HostServ", rest)
            }
            "ms" -> {
                val rest = parts.drop(1).joinToString(" ").trim()
                if (rest.isNotBlank()) privmsg("MemoServ", rest)
            }
            "bs" -> {
                val rest = parts.drop(1).joinToString(" ").trim()
                if (rest.isNotBlank()) privmsg("BotServ", rest)
            }
            // ZNC bouncer control: /znc <command> → PRIVMSG *status :<command>
            // ZNC's own clients have used this shorthand for years; supporting it keeps muscle
            // memory from HexChat / Quassel etc. working. Modules can be addressed with their
            // own pseudo-user (/msg *clientbuffer ..., /msg *playback ...) the long way.
            "znc" -> {
                val rest = parts.drop(1).joinToString(" ").trim()
                if (rest.isNotBlank()) privmsg("*status", rest)
            }
            // soju bouncer control: /bouncerserv <command> → PRIVMSG BouncerServ :<command>
            // BouncerServ is soju's equivalent of *status. Note BouncerServ is a normal nick
            // (not a *-prefixed pseudo-user) so it routes through the regular PRIVMSG path;
            // the routing-to-*server* logic in the PRIVMSG handler still folds replies into
            // the server buffer for cleanliness.
            "bouncerserv", "bnc" -> {
                val rest = parts.drop(1).joinToString(" ").trim()
                if (rest.isNotBlank()) privmsg("BouncerServ", rest)
            }
            "me" -> {
                val msg = parts.drop(1).joinToString(" ")
                val target = if (currentBuffer == "*server*") return else currentBuffer
                sendRaw("PRIVMSG $target :\u0001ACTION $msg\u0001")
            }
			"amsg" -> {
				val msg = parts.drop(1).joinToString(" ").trim()
				if (msg.isBlank()) {
					// Give feedback in current buffer
					// Option A: raw status
					// send(IrcEvent.Status("Usage: /amsg <message>"))

					// Option B: send a fake notice to current buffer
					commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer, text = "No text to send", isPrivate = true))
					return
				}
				for (chan in joinedChannelCases.values) {
					privmsg(chan, msg)
                    // Echo locally only if the server won't reflect it back via echo-message.
                    if (!hasCap("echo-message")) {
                        commandEvents.send(
                            IrcEvent.ChatMessage(
                                from = currentNick,
                                target = chan,
                                text = msg,
                                isPrivate = false,
                                timeMs = System.currentTimeMillis()
                            )
                        )
                    }
				}
			}
			"ame" -> {
				val msg = parts.drop(1).joinToString(" ").trim()
				if (msg.isBlank()) {
					commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer, text = "No text to send", isPrivate = true))
					return
				}
				for (chan in joinedChannelCases.values) {
					ctcp(chan, "ACTION $msg")
                    // Echo locally only if the server won't reflect it back via echo-message.
                    if (!hasCap("echo-message")) {
                        commandEvents.send(
                            IrcEvent.ChatMessage(
                                from = currentNick,
                                target = chan,
                                text = msg,
                                isPrivate = false,
                                isAction = true,
                                timeMs = System.currentTimeMillis()
                            )
                        )
                    }
				}
			}
            "list" -> sendRaw("LIST")
            "motd" -> {
                val arg = parts.drop(1).joinToString(" ")
                sendRaw(if (arg.isBlank()) "MOTD" else "MOTD $arg")
            }
            "whois" -> {
                val arg = parts.drop(1).joinToString(" ").trim()
                val nick = parts.getOrNull(1)?.trim()
                if (arg.isBlank() || nick.isNullOrBlank()) return
                // Cap to prevent unanswered WHOIS requests from accumulating indefinitely.
                if (pendingWhoisBufferByNick.size >= 50) pendingWhoisBufferByNick.clear()
                pendingWhoisBufferByNick[casefold(nick)] = currentBuffer
                sendRaw("WHOIS $arg")
            }
            "who" -> {
                val arg = parts.drop(1).joinToString(" ")
                sendRaw(if (arg.isBlank()) "WHO" else "WHO $arg")
            }
            "nick" -> parts.getOrNull(1)?.let { sendRaw("NICK $it") }
            "topic" -> {
                val target = parts.getOrNull(1) ?: currentBuffer
                val newTopic = parts.drop(2).joinToString(" ").takeIf { it.isNotBlank() }
                sendRaw(if (newTopic == null) "TOPIC $target" else "TOPIC $target :$newTopic")
            }
            "mode" -> {
                val arg = parts.drop(1).joinToString(" ")
                if (arg.isNotBlank()) sendRaw("MODE $arg")
            }
            "kick" -> {
                // /kick <nick> [reason]      — kick from current channel
                // /kick <#chan> <nick> [reason] — kick from a different channel
                val parsed = parseChanTargetCommand(parts, cmd, "<nick>", needsTarget = true) ?: return
                val reason = parsed.tail.joinToString(" ").trim()
                sendRaw(if (reason.isBlank()) "KICK ${parsed.chan} ${parsed.target}"
                        else "KICK ${parsed.chan} ${parsed.target} :$reason")
            }
            "ban" -> {
                // /ban <nick-or-mask> [type]      — ban in current channel
                // /ban <#chan> <nick-or-mask> [type] — ban in a different channel
                // [type] is one of: nick (default), user, host, domain, account
                // If <nick-or-mask> contains !, @, or starts with $, it's treated as a raw mask.
                val parsed = parseChanTargetCommand(parts, cmd, "<nick|mask> [type]", needsTarget = true) ?: return
                val type = parseMaskType(parsed.tail.firstOrNull()) ?: BanMaskType.NICK
                applyBanOrQueue(parsed.chan, parsed.target!!, type, quiet = false, alsoKick = false, kickReason = "")
            }
            "unban" -> {
                // /unban <nick-or-mask>      — remove ban in current channel
                // /unban <#chan> <nick-or-mask> — remove ban in a different channel
                // Plain nicks are expanded to nick!*@*; raw masks pass through. To remove a
                // host/account ban, paste the exact mask shown by /banlist.
                val parsed = parseChanTargetCommand(parts, cmd, "<nick|mask>", needsTarget = true) ?: return
                val mask = if (looksLikeRawMask(parsed.target!!)) parsed.target else "${parsed.target}!*@*"
                sendRaw("MODE ${parsed.chan} -b $mask")
            }
            "kb", "kickban" -> {
                // /kb <nick-or-mask> [type] [reason...]
                // /kb <#chan> <nick-or-mask> [type] [reason...]
                // type, if present, is the same keyword as /ban. When the target is a raw
                // mask, the kick step is skipped (no single nick to kick).
                val parsed = parseChanTargetCommand(parts, cmd, "<nick|mask> [type] [reason...]", needsTarget = true) ?: return
                // Distinguish "/kb nick host with extra reason words" from "/kb nick reason words"
                // by checking whether the next word is a recognised type keyword. If it is,
                // it's the type; otherwise it's the start of the reason.
                val maybeType = parseMaskType(parsed.tail.firstOrNull())
                val (type, reasonStartIdx) = if (maybeType != null) maybeType to 1 else BanMaskType.NICK to 0
                val reason = parsed.tail.drop(reasonStartIdx).joinToString(" ").trim()
                applyBanOrQueue(parsed.chan, parsed.target!!, type, quiet = false, alsoKick = true, kickReason = reason)
            }
            "mute", "quiet" -> {
                // /mute <nick-or-mask> [type]      — set +q (quiet) in current channel
                // /mute <#chan> <nick-or-mask> [type] — same in a different channel
                // Same syntax as /ban. Falls back to +b on ircds without quiet support.
                val parsed = parseChanTargetCommand(parts, cmd, "<nick|mask> [type]", needsTarget = true) ?: return
                val type = parseMaskType(parsed.tail.firstOrNull()) ?: BanMaskType.NICK
                applyBanOrQueue(parsed.chan, parsed.target!!, type, quiet = true, alsoKick = false, kickReason = "")
            }
            "unmute", "unquiet" -> {
                val parsed = parseChanTargetCommand(parts, cmd, "<nick|mask>", needsTarget = true) ?: return
                val mask = if (looksLikeRawMask(parsed.target!!)) parsed.target else "${parsed.target}!*@*"
                val modeChar = if (supportsQuietMode()) 'q' else 'b'
                sendRaw("MODE ${parsed.chan} -$modeChar $mask")
            }
			"sajoin" -> {
				// Services/admin forced join: SAJOIN <nick> <#channel>
				val a1 = parts.getOrNull(1) ?: return
				val a2 = parts.getOrNull(2) ?: return
				val (nick, chan) = if (isChannelName(a1) && !isChannelName(a2)) (a2 to a1) else (a1 to a2)
				sendRaw("SAJOIN $nick $chan")
			}
			"sapart" -> {
				// Services/admin forced part: SAPART <nick> <#channel> [:reason]
				val a1 = parts.getOrNull(1) ?: return
				val a2 = parts.getOrNull(2) ?: return
				val (nick, chan) = if (isChannelName(a1) && !isChannelName(a2)) (a2 to a1) else (a1 to a2)
				val reason = parts.drop(3).joinToString(" ").trim()
				sendRaw(if (reason.isBlank()) "SAPART $nick $chan" else "SAPART $nick $chan :$reason")
			}
			"gline", "zline", "kline", "dline", "eline", "qline", "shun", "kill" -> {
				// Common IRCop/line commands usually take a trailing reason; prefix ':' so spaces are preserved.
				// Examples:
				//   /gline *!*@bad.host 1d no spam pls
				//   /zline 203.0.113.0/24 2h scanning
				val raw = cmd.uppercase(Locale.ROOT)
				if (parts.size >= 4) {
					val head = parts.subList(1, 3).joinToString(" ")
					val reason = parts.drop(3).joinToString(" ").trim()
					sendRaw(if (reason.isBlank()) "$raw $head" else "$raw $head :$reason")
				} else {
					val rest = parts.drop(1).joinToString(" ").trim()
					sendRaw(if (rest.isBlank()) raw else "$raw $rest")
				}
			}
            "wallops", "globops", "locops", "operwall" -> {
                val msg = parts.drop(1).joinToString(" ").trim()
                if (msg.isNotBlank()) sendRaw("${cmd.uppercase(Locale.ROOT)} :$msg")
            }

            "ctcp" -> {
                val target = parts.getOrNull(1) ?: return
                val payload = parts.drop(2).joinToString(" ").trim().uppercase()
                if (payload.isBlank()) return
                
                // For PING, add timestamp if not provided
                val actualPayload = if (payload == "PING") {
                    "PING ${System.currentTimeMillis()}"
                } else {
                    payload
                }
                ctcp(target, actualPayload)
                commandEvents.send(IrcEvent.Status("CTCP $payload sent to $target"))
            }
            "finger" -> {
                val target = parts.getOrNull(1) ?: return
                ctcp(target, "FINGER")
                commandEvents.send(IrcEvent.Status("CTCP FINGER sent to $target"))
            }
            "userinfo" -> {
                val target = parts.getOrNull(1) ?: return
                ctcp(target, "USERINFO")
                commandEvents.send(IrcEvent.Status("CTCP USERINFO sent to $target"))
            }
            "clientinfo" -> {
                val target = parts.getOrNull(1) ?: return
                ctcp(target, "CLIENTINFO")
                commandEvents.send(IrcEvent.Status("CTCP CLIENTINFO sent to $target"))
            }
            "away" -> {
                val msg = parts.drop(1).joinToString(" ").trim()
                sendRaw(if (msg.isBlank()) "AWAY" else "AWAY :$msg")
            }
			"setname" -> {
				// IRCv3 SETNAME: change your own realname (requires setname CAP).
				val newRealname = parts.drop(1).joinToString(" ").trim()
				if (newRealname.isBlank()) {
					commandEvents.trySend(IrcEvent.ServerText("Usage: /setname <new realname>"))
				} else if (!hasCap("setname")) {
					commandEvents.trySend(IrcEvent.ServerText("Server does not support SETNAME (setname CAP not negotiated)"))
				} else {
					sendRaw("SETNAME :$newRealname")
				}
			}
			"quit" -> {
				val reason = parts.drop(1).joinToString(" ").trim()
				sendRaw(if (reason.isBlank()) "QUIT" else "QUIT :$reason")
				delay(500)
				// Give time for QUIT to send before disconnect
				disconnect(reason.ifBlank { "Quitting" })
			}
			"notice" -> {
				val target = parts.getOrNull(1)
				val msg = parts.drop(2).joinToString(" ")
				if (target.isNullOrBlank() || msg.isBlank()) {
					commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
						text = "Usage: /notice <nick|#channel> <message>", isPrivate = true))
					return
				}
				sendRaw("NOTICE $target :$msg")
			}
			"invite" -> {
				val nick = parts.getOrNull(1)
				if (nick.isNullOrBlank()) {
					commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
						text = "Usage: /invite <nick> [#channel]", isPrivate = true))
					return
				}
				val chan = parts.getOrNull(2)
					?: if (currentBuffer != "*server*" && isChannelName(currentBuffer)) currentBuffer
					   else {
						commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
							text = "/invite needs a channel — switch to one or pass it as the second argument",
							isPrivate = true))
						return
					   }
				sendRaw("INVITE $nick $chan")
			}
			"op", "deop", "voice", "devoice" -> {
				val nick = parts.getOrNull(1)
				if (nick.isNullOrBlank()) {
					commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
						text = "Usage: /$cmd <nick> [#channel]", isPrivate = true))
					return
				}
				val chan = parts.getOrNull(2)
					?: if (currentBuffer != "*server*" && isChannelName(currentBuffer)) currentBuffer
					   else {
						commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
							text = "/$cmd needs a channel — switch to one or pass it as the second argument",
							isPrivate = true))
						return
					   }
				val mode = when (cmd) {
					"op" -> "+o"; "deop" -> "-o"; "voice" -> "+v"; "devoice" -> "-v"
					else -> return  // unreachable; satisfies the when-expression exhaustiveness check
				}
				sendRaw("MODE $chan $mode $nick")
			}
			"ctcpping", "ping" -> {
				val target = parts.getOrNull(1) ?: return
				ctcp(target, "PING ${System.currentTimeMillis()}")
				commandEvents.send(IrcEvent.Status("CTCP PING sent to $target"))
			}
			"time" -> {
				val arg = parts.drop(1).joinToString(" ")
				sendRaw(if (arg.isBlank()) "TIME" else "TIME $arg")
			}
			"version" -> {
				val arg = parts.drop(1).joinToString(" ").trim()
				if (arg.isBlank()) {
					// No argument: query server version
					sendRaw("VERSION")
				} else {
					// Argument provided: send CTCP VERSION to target
					ctcp(arg, "VERSION")
				}
			}
			"admin" -> {
				val arg = parts.drop(1).joinToString(" ")
				sendRaw(if (arg.isBlank()) "ADMIN" else "ADMIN $arg")
			}
			"info" -> {
				val arg = parts.drop(1).joinToString(" ")
				sendRaw(if (arg.isBlank()) "INFO" else "INFO $arg")
			}
			"oper" -> {
				val args = parts.drop(1).joinToString(" ")
				if (args.isNotBlank()) sendRaw("OPER $args")
			}
			"raw" -> {
				val line = parts.drop(1).joinToString(" ")
				if (line.isNotBlank()) sendRaw(line)
			}
			// IRCv3 MONITOR: watch list management
			// /monitor + nick[,nick...]   - add to watch list
			// /monitor - nick[,nick...]   - remove from watch list
			// /monitor C                  - clear watch list
			// /monitor L                  - list current watch list
			// /monitor S                  - request status of all watched nicks
			"monitor" -> {
				val arg = parts.drop(1).joinToString(" ").trim()
				if (arg.isBlank()) {
					commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
						text = "Usage: /monitor +nick[,nick] | -nick[,nick] | C | L | S", isPrivate = false))
				} else {
					sendRaw("MONITOR $arg")
				}
			}
			// IRCv3 draft/read-marker: mark a buffer as read up to now (or a specific timestamp).
			// /markread [#channel|nick] [timestamp]
			"markread" -> {
				val target = parts.getOrNull(1)
					?: (if (currentBuffer != "*server*") currentBuffer else null)
					?: return
				val ts = parts.getOrNull(2)
					?: java.time.Instant.now().toString()
				if (hasReadMarkerCap()) {
                    // soju.im/read uses the "READ" command; draft/read-marker uses "MARKREAD".
                    // Both carry the same "timestamp=<ISO8601>" argument.
                    val readCmd = if (hasCap("soju.im/read") && !hasCap("draft/read-marker")) "READ" else "MARKREAD"
					sendRaw("$readCmd $target timestamp=$ts")
				} else {
					commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
						text = "Server does not support read markers", isPrivate = false))
				}
			}
			"dns" -> {
				val arg = parts.getOrNull(1)?.trim() ?: return
				if (arg.isBlank()) return

				coroutineScope {
					launch {
						try {
							commandEvents.send(IrcEvent.ServerText("Looking up $arg...", bufferName = currentBuffer))
							val resolved = resolveDns(arg)
							if (resolved.isNotEmpty()) {
								commandEvents.send(IrcEvent.ServerText("Resolved to:", bufferName = currentBuffer))
								resolved.forEach { line ->
									commandEvents.send(IrcEvent.ServerText("    $line", bufferName = currentBuffer))
								}
							} else {
								commandEvents.send(IrcEvent.ServerText("No resolution found for $arg", bufferName = currentBuffer))
							}
						} catch (e: Exception) {
							commandEvents.send(IrcEvent.ServerText("DNS lookup failed: ${e.message ?: "Unknown error"}", bufferName = currentBuffer))
						}
					}
				}
			}
            else -> {
                // Pass through unknown commands
                val rawCmd = parts[0].uppercase(Locale.ROOT)
                val rest = cmdLine.trim().drop(parts[0].length).trimStart()
                sendRaw(if (rest.isBlank()) rawCmd else "$rawCmd $rest")
            }
        }
    }

    fun events(): Flow<IrcEvent> = channelFlow {
        send(IrcEvent.Status("Connecting…"))

        val s = try {
            withContext(Dispatchers.IO) { openSocket() }
        } catch (t: Throwable) {
            // TOFU pin mismatch gets its own structured event so the UI can show the
            // stored-vs-actual fingerprints and the user can make an informed choice
            // (legitimate cert renewal vs. suspected MITM).
            if (t is TlsFingerprintMismatchException) {
                send(IrcEvent.TlsFingerprintChanged(stored = t.stored, actual = t.actual))
                send(IrcEvent.Disconnected("TLS certificate fingerprint changed"))
                return@channelFlow
            }
            val msg = friendlyErrorMessage(t)
            send(IrcEvent.Error("Connect failed: $msg"))
            send(IrcEvent.Disconnected(msg))
            return@channelFlow
        }

        socket = s

        // If TOFU captured a fresh fingerprint during the handshake, emit the learned event
        // so the view model persists it into the profile. Clear the field so a subsequent
        // reconnect on the same IrcClient instance doesn't re-learn and re-pin.
        learnedFingerprint?.let { fp ->
            learnedFingerprint = null
            send(IrcEvent.TlsFingerprintLearned(fp))
        }

        // If TLS is enabled put TLS session info in the server buffer.
        tlsInfo()?.takeIf { it.isNotBlank() }?.let { info ->
            send(IrcEvent.ServerText("*** TLS: $info"))
        }

        // Set up encoding-aware I/O using EncodingHelper
        val inputStream = s.getInputStream()
        val outputStream = s.getOutputStream()

        // Wrap the raw socket InputStream in a BufferedInputStream so EncodingLineReader's
        // byte-by-byte read() loop draws from an 8 KB in-memory buffer instead of issuing
        // a JNI syscall per byte. On a busy channel this can be thousands of syscalls/sec.
        val bufferedInput = java.io.BufferedInputStream(inputStream, 8192)

        // Create line reader with encoding detection
        val lineReader = EncodingLineReader(bufferedInput, config.encoding)
        
        // Only notify about the encoding when a non-default encoding is explicitly configured.
        // Auto-detect mode is silent on connect - a notification fires later only if a
        // non-UTF-8 encoding is actually detected (see the encodingNotified block below).
        if (!config.encoding.equals("auto", ignoreCase = true) &&
            !config.encoding.equals("UTF-8", ignoreCase = true)) {
            send(IrcEvent.ServerText("*** Using encoding: ${config.encoding}"))
        }

        suspend fun writeLine(line: String) = withContext(Dispatchers.IO) {
            // IRCv3 utf8only: when the server has negotiated this cap, all messages MUST
            // be UTF-8. Override the per-connection encoding so legacy windows-1251 /
            // ISO-8859-x configs don't accidentally send non-UTF-8 bytes on a strict server.
            val enc = if (this@IrcClient.hasCap("utf8only")) "UTF-8" else lineReader.encoding
            val bytes = EncodingHelper.encode(line, enc)
            // Combine payload + CRLF into one array so we issue a single write() syscall
            // instead of two, and avoid allocating a new CRLF ByteArray each time.
            val packet = ByteArray(bytes.size + CRLF.size)
            bytes.copyInto(packet)
            CRLF.copyInto(packet, destinationOffset = bytes.size)
            outputStream.write(packet)
            outputStream.flush()
        }

        val writerJob = launch(Dispatchers.IO) {
            try {
                for (line in outbound) writeLine(line)
            } catch (t: Throwable) {
                // If writes start failing (common during network handovers or SSL close),
                // force-close the socket so the read loop can notice and emit Disconnected.
                if (!userClosing) {
                    lastQuitReason = friendlyErrorMessage(t)
                    runCatching { s.close() }
                }
                // If userClosing, this is expected (socket was closed while a write was pending)
            }
        }

        // Registration / SASL watchdog: if 001 has not arrived within
        // REGISTRATION_TIMEOUT_MS the server may be ignoring us or SASL has stalled.
        val registrationWatchdogJob = launch {
            delay(ConnectionConstants.REGISTRATION_TIMEOUT_MS)
            if (!registered && !userClosing) {
                lastQuitReason = "Registration timeout"
                send(IrcEvent.Error("Registration timeout — server did not send 001 within ${ConnectionConstants.REGISTRATION_TIMEOUT_MS / 1000}s"))
                runCatching { s.close() }
            }
        }

        val pingJob = launch {
            // Wait a moment so the socket is fully established.
            delay(5_000)
            while (true) {
                // Ping interval: 60 s for direct IRCd connections (many IRCds drop idle
                // connections after ~90 s, so 60 s is the safe minimum).
                // For bouncer connections the bouncer maintains its own persistent upstream
                // session, so the client-to-bouncer link only needs to detect TCP stalls —
                // 90 s is safe and saves one ping/PONG round-trip per minute (~2 packets,
                // ~200 bytes) per connection when the device is idle.
                val pingIntervalMs = if (config.isBouncer) 90_000L else 60_000L
                delay(pingIntervalMs)

                // If we're waiting on a PONG for a previous probe and it's taking too long,
                // consider the connection stalled and force a reconnect.
                val now = System.currentTimeMillis()
                val pendingTok = pendingLagPingToken
                val pendingAt = pendingLagPingSentAtMs
                if (pendingTok != null && pendingAt != null) {
                    if (now - pendingAt > ConnectionConstants.PING_TIMEOUT_MS && !userClosing) {
                        lastQuitReason = "Ping timeout"
                        runCatching { s.close() }
                    }
                    continue
                }

                val token = "hexlag-$now"
                pendingLagPingToken = token
                // Capture send time after writeLine so RTT is pure network latency,
                // not including coroutine scheduling jitter from delay().
                val writeResult = runCatching { writeLine("PING :$token") }
                pendingLagPingSentAtMs = System.currentTimeMillis()
                if (writeResult.isFailure && !userClosing) runCatching { s.close() }
            }
        }

        // Collect command events and forward to the flow
        launch {
            for (event in commandEvents) {
                send(event)
            }
        }

        val irc = IrcSession(config, rng)
        sessionRef = irc
        val historyRequested = mutableSetOf<String>()
        val historyExpectUntil = mutableMapOf<String, Long>()
        // znc.in/playback: last-seen timestamps sent by ZNC's *playback module.
        // Key = lowercase buffer name. Value = epoch seconds (as sent by ZNC).
        val zncLastSeen = mutableMapOf<String, Long>()
        val openPlaybackBatches = mutableSetOf<String>()
        // netsplit/netjoin: track open batch IDs -> type, buffer events for collapse.
        val openNetsplitBatches = mutableMapOf<String, String>()  // id -> "netsplit"|"netjoin"
        val netsplitBuffer = mutableMapOf<String, MutableList<IrcMessage>>()  // id -> buffered JOIN/QUITs

        fun parseServerTimeMs(tags: Map<String, String?>): Long? {
            // "time" = IRCv3 server-time (standard)
            // "t"    = znc.in/server-time-iso (legacy ZNC < 1.7)
            val raw = tags["time"] ?: tags["t"] ?: return null
            return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
        }

        fun isPlaybackHistory(tags: Map<String, String?>): Boolean {
            val batch = tags["batch"]
            return batch != null && openPlaybackBatches.contains(batch)
        }

        fun isHeuristicHistory(target: String?, timeMs: Long?, nowMs: Long): Boolean {
            if (target.isNullOrBlank() || timeMs == null) return false
            val until = historyExpectUntil[target.lowercase()] ?: 0L
            if (until < nowMs) return false
            // Only treat it as history if it's not "now".
            return timeMs < (nowMs - 15_000L)
        }

// Numeric dispatch table (RFC + common de-facto numerics)
//
// Add/extend handlers here instead of growing a giant `when` block.
// Unknown numerics will still be surfaced via formatNumeric()/fallback ServerText.

/**
 * Helper that builds the item + end-of-list handler pair for channel list modes (ban, invex,
 * except, quiet).  All four use identical parameter layouts:
 *   <me> <#chan> <mask> [setBy] [setAt (epoch seconds)]
 * Extracted to eliminate ~60 lines of copy-paste across 367/368, 346/347, 348/349, 728/729.
 */
fun listModeHandlers(
    itemNumeric: String,
    endNumeric: String,
    makeItem: (chan: String, mask: String, setBy: String?, setAtMs: Long?, timeMs: Long?, isHistory: Boolean) -> IrcEvent,
    makeEnd: (chan: String, code: String, timeMs: Long?, isHistory: Boolean) -> IrcEvent
): List<Pair<String, suspend (IrcMessage, Long?, Boolean, Long) -> Unit>> = listOf(
    itemNumeric to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
        val chan = msg.params.getOrNull(1) ?: return@handler
        val mask = msg.params.getOrNull(2) ?: return@handler
        val setBy = msg.params.getOrNull(3)
        val setAtMs = msg.params.getOrNull(4)?.toLongOrNull()?.let { it * 1000L }
        val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
        send(makeItem(chan, mask, setBy, setAtMs, serverTimeMs, hist))
    },
    endNumeric to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
        val chan = msg.params.getOrNull(1) ?: return@handler
        val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
        send(makeEnd(chan, msg.command, serverTimeMs, hist))
    }
)

val numericHandlers: Map<String, suspend (IrcMessage, Long?, Boolean, Long) -> Unit> = mapOf(
    "001" to handler@{ msg, _, _, _ ->
        // Welcome: <me> ...
        val me = msg.params.getOrNull(0) ?: config.nick
        currentNick = me
        registered = true
        registrationWatchdogJob.cancel()  // 001 received — connection is live
        send(IrcEvent.Registered(me))
    },

    "005" to handler@{ msg, _, _, _ ->
        // ISUPPORT: drive channel detection, prefix rank mapping, and casemapping.
        // Example: PREFIX=(qaohv)~&@%+ CHANTYPES=#& CASEMAPPING=rfc1459 STATUSMSG=@+
        val tokens = msg.params.drop(1)
        var chant = chantypes
        var cm = caseMapping
        var pm = prefixModes
        var ps = prefixSymbols
        var sm: String? = statusMsg
        var chm: String? = chanModes
        var ll: Int? = serverLinelen

        for (tok in tokens) {
            if (tok.isBlank()) continue
            val parts = tok.split("=", limit = 2)
            val k = parts[0].trim().uppercase(Locale.ROOT)
            val v = parts.getOrNull(1)?.trim()
            when (k) {
                "CHANTYPES" -> if (!v.isNullOrBlank()) chant = v
                "CASEMAPPING" -> if (!v.isNullOrBlank()) cm = v
                "STATUSMSG" -> if (!v.isNullOrBlank()) sm = v
                "CHANMODES" -> if (!v.isNullOrBlank()) chm = v
                "PREFIX" -> if (!v.isNullOrBlank()) {
                    val m0 = Regex("^\\(([^)]+)\\)(.+)$").find(v)
                    if (m0 != null) {
                        pm = m0.groupValues[1]
                        ps = m0.groupValues[2]
                    }
                }
                // parse LINELEN so the ViewModel can use the server's actual limit
                "LINELEN" -> v?.toIntOrNull()?.takeIf { it in 512..65535 }?.let { ll = it }
                // WHOX is a flag token (no value): server supports extended WHO %fields,querytype
                "WHOX" -> whoxSupported = true
            }
        }

        chantypes = chant
        caseMapping = cm
        statusMsg = sm
        chanModes = chm
        prefixModes = pm
        prefixSymbols = ps
        serverLinelen = ll

        val mp = mutableMapOf<Char, Char>()
        val n = minOf(pm.length, ps.length)
        for (i in 0 until n) mp[pm[i]] = ps[i]
        if (mp.isNotEmpty()) prefixModeToSymbol = mp

        send(IrcEvent.ISupport(chantypes, caseMapping, prefixModes, prefixSymbols, statusMsg, chanModes, ll))
    },

    // LIST output
    "321" to handler@{ _, _, _, _ -> send(IrcEvent.ChannelListStart) },
    "322" to handler@{ msg, _, _, _ ->
        // RPL_LIST: <me> <#chan> <visible> :topic
        val chan = msg.params.getOrNull(1) ?: return@handler
        val users = msg.params.getOrNull(2)?.toIntOrNull() ?: 0
        val topic = (msg.trailing ?: "").let { stripIrcFormatting(it) }
        send(IrcEvent.ChannelListItem(chan, users, topic))
    },
    "323" to handler@{ _, _, _, _ -> send(IrcEvent.ChannelListEnd) },

    // CHATHISTORY TARGETS response: 761 (start), 762 (item), 763 (end).
    // Each 762 line has the format: <me> <target> timestamp=<ISO8601>
    // We emit them as ServerText so they appear in the server buffer.
    "761" to handler@{ _, _, _, _ ->
        send(IrcEvent.ServerText("History targets:", code = "761"))
    },
    "762" to handler@{ msg, _, _, _ ->
        val target = msg.params.getOrNull(1) ?: return@handler
        val ts = msg.params.getOrNull(2) ?: ""
        send(IrcEvent.ServerText("  $target  $ts", code = "762"))
    },
    "763" to handler@{ _, _, _, _ ->
        send(IrcEvent.ServerText("(End of history targets)", code = "763"))
    },

    // Topic numerics
    "332" to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
        // RPL_TOPIC: <me> <#chan> :topic
        val chan = msg.params.getOrNull(1) ?: return@handler
        val topic = msg.trailing
        val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
        send(IrcEvent.TopicReply(chan, topic, timeMs = serverTimeMs, isHistory = hist))
    },
    "333" to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
        // RPL_TOPICWHOTIME: <me> <#chan> <setter> <time>
        val chan = msg.params.getOrNull(1) ?: return@handler
        val setter = msg.params.getOrNull(2) ?: return@handler
        val secs = msg.params.getOrNull(3)?.toLongOrNull()
        val setAtMs = secs?.let { it * 1000L }
        val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
        send(IrcEvent.TopicWhoTime(chan, setter, setAtMs, timeMs = serverTimeMs, isHistory = hist))
    },

    "324" to handler@{ msg, _, _, _ ->
        // RPL_CHANNELMODEIS: <me> <#chan> <modes> [mode params...]
        val chan = msg.params.getOrNull(1) ?: return@handler
        val modes = msg.params.drop(2).joinToString(" ").trim()
        if (modes.isNotBlank()) send(IrcEvent.ChannelModeIs(chan, modes, code = msg.command))
    },
    "381" to handler@{ msg, _, _, _ ->
        // RPL_YOUREOPER
        val text = msg.trailing ?: msg.params.drop(1).joinToString(" ").trim().ifBlank { "You are now an IRC operator" }
        send(IrcEvent.YoureOper(text))
    },

    // Names list
    "353" to handler@{ msg, _, _, _ ->
        // RPL_NAMREPLY: <me> <symbol> <#chan> :[prefix]nick ...
        // With userhost-in-names CAP, entries are [prefix]nick!user@host - strip the
        // user@host so channel tracking, nick colouring, and case-folding work correctly.
        val chan = msg.params.getOrNull(2) ?: return@handler
        val names = (msg.trailing ?: "").split(Regex("\\s+")).filter { it.isNotBlank() }
            .map { raw ->
                // Find where prefixes end and the nick!user@host begins.
                // Use server-negotiated prefixSymbols (updated from 005 PREFIX) so non-standard
                // rank symbols (e.g. '!' or '~' on custom IRCd configs) are stripped correctly.
                val validPrefixes = prefixSymbols
                val prefixEnd = raw.indexOfFirst { it !in validPrefixes }
                if (prefixEnd < 0) return@map raw  // only prefixes? keep as-is
                val prefixes = raw.substring(0, prefixEnd)
                val rest = raw.substring(prefixEnd)
                // Strip !user@host if present (userhost-in-names CAP)
                val nick = rest.substringBefore('!')
                prefixes + nick
            }
        if (names.isNotEmpty()) send(IrcEvent.Names(chan, names))
    },
    "366" to handler@{ msg, _, _, _ ->
        // RPL_ENDOFNAMES: <me> <#chan> :End of /NAMES list.
        val chan = msg.params.getOrNull(1) ?: return@handler
        send(IrcEvent.NamesEnd(chan))
    },

    // WHOX reply (354): response to WHO #chan %uhsnfar,42
    // Params depend on the %fields requested. With %uhsnfar,42:
    //   <me> 42 <ident> <host> <server> <nick> <flags> <account> :<realname>
    //   The query type (42) is in params[1]; we skip this numeric if it doesn't match ours.
    "354" to handler@{ msg, _, _, _ ->
        // Verify this is our WHOX query (query type 42)
        if (msg.params.getOrNull(1) != "42") return@handler
        val ident   = msg.params.getOrNull(2) ?: return@handler
        val host    = msg.params.getOrNull(3) ?: return@handler
        val nick    = msg.params.getOrNull(5) ?: return@handler
        // flags field (params[6]): 'H'=Here (present), 'G'=Gone (away). May have extra flags like '*'=oper.
        val flags   = msg.params.getOrNull(6)
        val isAway  = flags?.firstOrNull { it == 'H' || it == 'G' }?.let { it == 'G' }
        val account = msg.params.getOrNull(7)?.takeIf { it != "0" }  // "0" = not logged in
        send(IrcEvent.WhoxReply(nick = nick, ident = ident, host = host, account = account, isAway = isAway))
    },

    // ERR_NOTONCHANNEL
    "442" to handler@{ msg, _, _, _ ->
        // <me> <#chan> :You're not on that channel
        val chan = msg.params.getOrNull(1) ?: return@handler
        val reason = msg.trailing?.let { stripIrcFormatting(it) } ?: "You're not on that channel"
        send(IrcEvent.NotOnChannel(chan, reason, code = msg.command))
    },

    // Ban / invex / except / quiet list modes - all use identical param layouts:
    // <me> <#chan> <mask> [setBy] [setAt(epoch)]. Delegated to listModeHandlers().
    *listModeHandlers("367", "368",
        makeItem = { c, m, by, at, ts, hist -> IrcEvent.BanListItem(c, m, by, at, timeMs = ts, isHistory = hist) },
        makeEnd  = { c, code, ts, hist -> IrcEvent.BanListEnd(c, code = code, timeMs = ts, isHistory = hist) }
    ).toTypedArray(),
    *listModeHandlers("346", "347",
        makeItem = { c, m, by, at, ts, hist -> IrcEvent.InvexListItem(c, m, by, at, timeMs = ts, isHistory = hist) },
        makeEnd  = { c, code, ts, hist -> IrcEvent.InvexListEnd(c, code = code, timeMs = ts, isHistory = hist) }
    ).toTypedArray(),
    *listModeHandlers("348", "349",
        makeItem = { c, m, by, at, ts, hist -> IrcEvent.ExceptListItem(c, m, by, at, timeMs = ts, isHistory = hist) },
        makeEnd  = { c, code, ts, hist -> IrcEvent.ExceptListEnd(c, code = code, timeMs = ts, isHistory = hist) }
    ).toTypedArray(),
    *listModeHandlers("728", "729",
        makeItem = { c, m, by, at, ts, hist -> IrcEvent.QuietListItem(c, m, by, at, timeMs = ts, isHistory = hist) },
        makeEnd  = { c, code, ts, hist -> IrcEvent.QuietListEnd(c, code = code, timeMs = ts, isHistory = hist) }
    ).toTypedArray(),

    // Join failures (ircu/unreal/inspircd/nefarious all use these)
    "471" to handler@{ msg, _, _, _ ->
        val chan = msg.params.getOrNull(1) ?: return@handler
        val reason = msg.trailing ?: "Cannot join channel (+l)"
        send(IrcEvent.JoinError(chan, reason, code = msg.command))
    },
    "472" to handler@{ msg, _, _, _ ->
        val chan = msg.params.getOrNull(1) ?: return@handler
        val reason = msg.trailing ?: "Cannot join channel"
        send(IrcEvent.JoinError(chan, reason, code = msg.command))
    },
    "473" to handler@{ msg, _, _, _ ->
        val chan = msg.params.getOrNull(1) ?: return@handler
        val reason = msg.trailing ?: "Cannot join channel (+i)"
        send(IrcEvent.JoinError(chan, reason, code = msg.command))
    },
    "474" to handler@{ msg, _, _, _ ->
        val chan = msg.params.getOrNull(1) ?: return@handler
        val reason = msg.trailing ?: "Cannot join channel (+b)"
        send(IrcEvent.JoinError(chan, reason, code = msg.command))
    },
    "475" to handler@{ msg, _, _, _ ->
        val chan = msg.params.getOrNull(1) ?: return@handler
        val reason = msg.trailing ?: "Cannot join channel (+k)"
        send(IrcEvent.JoinError(chan, reason, code = msg.command))
    },
    "476" to handler@{ msg, _, _, _ ->
        val chan = msg.params.getOrNull(1) ?: return@handler
        val reason = msg.trailing ?: "Cannot join channel"
        send(IrcEvent.JoinError(chan, reason, code = msg.command))
    },
    "477" to handler@{ msg, _, _, _ ->
        val chan = msg.params.getOrNull(1) ?: return@handler
        val reason = msg.trailing ?: "Cannot join channel"
        send(IrcEvent.JoinError(chan, reason, code = msg.command))
    },

    // IRCv3 MONITOR - online/offline notifications for watched nicks.
    // 730 MONONLINE  <nick>[!user@host] [account][,...]  - nick(s) came online
    //   With draft/extended-monitor, each entry is nick!user@host or nick!user@host account.
    // 731 MONOFFLINE <nick>[,<nick>...]  - nick(s) went offline
    // 732 MONLIST    <nick>[,<nick>...]  - list entry (may span multiple 732 lines)
    // 733 MONLISTFULL                   - watch list is full
    "730" to handler@{ msg, serverTime, _, _ ->
        val raw = (msg.trailing ?: msg.params.drop(1).joinToString(","))
        for (entry in raw.split(",").map { it.trim() }.filter { it.isNotBlank() }) {
            // extended-monitor: "nick!user@host" or "nick!user@host account"
            val spaceIdx = entry.indexOf(' ')
            val hostPart = if (spaceIdx > 0) entry.substring(0, spaceIdx) else entry
            val accountPart = if (spaceIdx > 0) entry.substring(spaceIdx + 1).trim().takeIf { it.isNotBlank() && it != "*" } else null
            val bangIdx = hostPart.indexOf('!')
            val nick = if (bangIdx > 0) hostPart.substring(0, bangIdx) else hostPart
            val ident = if (bangIdx > 0) hostPart.substring(bangIdx + 1).substringBefore('@').takeIf { it.isNotBlank() } else null
            val host = if (bangIdx > 0) hostPart.substringAfter('@', "").takeIf { it.isNotBlank() } else null
            send(IrcEvent.MonitorStatus(nick, online = true, timeMs = serverTime, ident = ident, host = host, account = accountPart))
        }
    },
    "731" to handler@{ msg, serverTime, _, _ ->
        val nicks = (msg.trailing ?: msg.params.drop(1).joinToString(","))
            .split(",").map { it.trim().substringBefore("!") }.filter { it.isNotBlank() }
        for (nick in nicks) send(IrcEvent.MonitorStatus(nick, online = false, timeMs = serverTime))
    },
    "732" to handler@{ msg, serverTime, _, _ ->
        val nicks = (msg.trailing ?: msg.params.drop(1).joinToString(","))
            .split(",").map { it.trim().substringBefore("!") }.filter { it.isNotBlank() }
        // 732 = MONLIST reply - treat as online (we only know they're watched, not their status)
        // Surface in server buffer as a status text so the user can see their list.
        val listStr = nicks.joinToString(", ")
        if (listStr.isNotBlank()) send(IrcEvent.ServerText("MONITOR list: $listStr"))
    },
    "733" to handler@{ msg, _, _, _ ->
        val limit = msg.params.getOrNull(1) ?: "?"
        send(IrcEvent.ServerText("MONITOR list is full (limit: $limit)"))
    },
)

		try {
			send(IrcEvent.Status("Negotiating capabilities…"))
			// Some servers expect PASS to precede any other registration-time commands.
			// Always colon-prefix the password as a proper IRC trailing parameter so that
			// passwords containing spaces (e.g. ZNC "user/network:pass phrase") are not
			// silently truncated at the first space.
			//
			// Skip PASS for bouncer connections when SASL is also configured: bouncers like
			// ZNC process PASS *after* SASL completes and treat it as a second authentication
			// attempt, producing a spurious "invalid password" even though SASL succeeded.
			// For direct IRCd connections we always send PASS when set, because many servers
			// require a server-wide PASS independently of per-user SASL authentication.
			val skipPass = config.sasl is SaslConfig.Enabled && config.isBouncer
			if (!skipPass) {
				config.serverPassword?.takeIf { it.isNotBlank() }?.let { writeLine("PASS :$it") }
			}
			writeLine("CAP LS 302")
			writeLine("NICK ${config.nick}")
			writeLine("USER ${config.effectiveAuthIdentity(config.username)} 0 * :${config.realname}")
			send(IrcEvent.Connected("${config.host}:${config.port}"))
			// IRCv3 pre-away: if the user has a stored away message, send AWAY before 001
			// so the server marks us as away from the start of the session (no window where
			// we appear "here"). Sent unconditionally when the pref is on — servers that
			// don't understand AWAY before 001 simply ignore it (RFC 2812 allows AWAY at any
			// time, but some strict IRCds may return ERR_NOTREGISTERED). The cap merely
			// advertises *guaranteed* support; we err on the side of sending it regardless
			// because the worst case is a harmless unknown-command error.
			if (config.capPrefs.preAway && !config.initialAwayMessage.isNullOrBlank()) {
				writeLine("AWAY :${config.initialAwayMessage}")
			}

			// Track if we've notified about encoding detection
			var encodingNotified = false

			// Track oversize-line drops so we surface a single user-visible notice on
			// the first occurrence per connection. Further drops are silent to avoid
			// a torrent of status messages if a server is wedged emitting huge lines.
			var truncatedNotified = false
			var lastTruncatedCount = 0

			while (true) {
				val prevEncoding = lineReader.encoding
				val line = withContext(Dispatchers.IO) { lineReader.readLine() } ?: break

				// A truncated line returned as "" — skip parsing, but surface the fact
				// to the user once so they know something was dropped rather than
				// silently swallowed.
				if (lineReader.truncatedLineCount > lastTruncatedCount) {
					lastTruncatedCount = lineReader.truncatedLineCount
					if (!truncatedNotified) {
						truncatedNotified = true
						send(IrcEvent.ServerText(
							"*** Dropped an oversize inbound IRC line (>32 KiB). " +
							"Connection kept alive; further drops will be silent."
						))
					}
					continue
				}
				
				// Notify user if encoding was auto-detected and changed
				if (!encodingNotified && lineReader.hasDetectedNonUtf8() && prevEncoding != lineReader.encoding) {
					// Give the detected encoding a friendly label (e.g. "Cyrillic (Windows-1251)")
					val enc = lineReader.encoding
					val label = when (enc.uppercase().replace("-", "").replace("_", "")) {
						"WINDOWS1251", "CP1251" -> "Cyrillic (Windows-1251)"
						"WINDOWS1252", "CP1252" -> "Western European (Windows-1252)"
						"WINDOWS1256", "CP1256" -> "Arabic (Windows-1256)"
						"WINDOWS1254", "CP1254" -> "Turkish (Windows-1254)"
						"WINDOWS1253", "CP1253" -> "Greek (Windows-1253)"
						"WINDOWS1255", "CP1255" -> "Hebrew (Windows-1255)"
						"KOI8R" -> "Cyrillic (KOI8-R)"
						"KOI8U" -> "Cyrillic (KOI8-U)"
						"ISO88591" -> "Latin-1 (ISO-8859-1)"
						"ISO88592" -> "Central European (ISO-8859-2)"
						"ISO88597" -> "Greek (ISO-8859-7)"
						"ISO88598" -> "Hebrew (ISO-8859-8)"
						"ISO88599" -> "Turkish (ISO-8859-9)"
						"GBK", "GB2312", "GB18030" -> "Chinese ($enc)"
						"SHIFTJIS", "SHIFTJIS2004" -> "Japanese (Shift_JIS)"
						"EUCJP" -> "Japanese (EUC-JP)"
						"EUCKR" -> "Korean (EUC-KR)"
						else -> enc
					}
					send(IrcEvent.ServerText("*** Auto-detected legacy encoding: $label — you can set this explicitly in Network Settings"))
					encodingNotified = true
				}
				
				send(IrcEvent.ServerLine(line))
				val msg = parser.parse(line) ?: continue

				if (msg.command == "PING") {
					val payload = msg.trailing ?: msg.params.firstOrNull() ?: ""
					writeLine("PONG :$payload")
					continue
				}

				if (msg.command == "PONG") {
					// Update lag bar if this is a response to our last lag probe.
					val payload = msg.trailing ?: msg.params.lastOrNull() ?: ""
					val tok = pendingLagPingToken
					val sentAt = pendingLagPingSentAtMs
					if (tok != null && sentAt != null && payload == tok) {
						val lag = (System.currentTimeMillis() - sentAt).coerceAtLeast(0L)
						lastLagMs = lag
						pendingLagPingToken = null
						pendingLagPingSentAtMs = null
						send(IrcEvent.LagUpdated(lag))
					}
					continue
				}

				if (msg.command == "433") {
					// Ignore 433 after successful registration — Ergo (and other servers with
					// SASL nick-reclaim) may send queued 433s for nicks tried pre-SASL after
					// the 001 welcome is already issued with the correct nick. Acting on them
					// would cause an endless collision loop.
					if (!registered) {
						val alt = config.altNick
						if (!triedAltNick && !alt.isNullOrBlank()) {
							triedAltNick = true
							writeLine("NICK $alt")
							send(IrcEvent.Status("Nick in use; trying alt nick: $alt"))
						} else {
							val rnd = (1000 + rng.nextInt(9000)).toString()
							val next = (alt ?: config.nick) + "_" + rnd
							writeLine("NICK $next")
							send(IrcEvent.Status("Nick in use; trying: $next"))
						}
					}
					continue
				}

				val hsActions = irc.onMessage(msg)
				for (a in hsActions) when (a) {
					is IrcAction.Send -> writeLine(a.line)
					is IrcAction.EmitStatus -> send(IrcEvent.Status(a.text))
					is IrcAction.EmitError -> send(IrcEvent.Error(a.text))
					is IrcAction.EmitCapNew -> send(IrcEvent.CapNew(a.caps))
					is IrcAction.EmitCapDel -> send(IrcEvent.CapDel(a.caps))
				}

				// BATCH tracking (used for draft/chathistory, draft/event-playback, labeled-response).
				if (msg.command == "BATCH") {
					val idToken = msg.params.getOrNull(0) ?: continue
					if (idToken.startsWith("+")) {
						val id = idToken.drop(1)
						val type = msg.params.getOrNull(1) ?: ""
						if (type.contains("chathistory", ignoreCase = true) ||
							type.contains("event-playback", ignoreCase = true) ||
							type.contains("playback", ignoreCase = true)
						) {
							openPlaybackBatches.add(id)
						}
						// netsplit/netjoin: track so we can collapse the JOIN/QUIT flood.
						if (type.equals("netsplit", ignoreCase = true) || type.equals("netjoin", ignoreCase = true)) {
							openNetsplitBatches[id] = type.lowercase()
						}
						// labeled-response: BATCH +<id> labeled-response - the reply batch for
						// a labeled outbound command (e.g. CHATHISTORY or PRIVMSG with echo-message).
						// We don't need to open any special state for it; incoming messages within
						// this batch already carry the label tag for correlation. The batch is
						// effectively a signal that the server is delivering a grouped reply.
						// We do NOT add it to openPlaybackBatches to avoid misclassifying the
						// echoed PRIVMSG reply as history.
					} else if (idToken.startsWith("-")) {
						val id = idToken.drop(1)
						openPlaybackBatches.remove(id)
						// Flush buffered netsplit/netjoin events as a single collapsed status line.
						val batchType = openNetsplitBatches.remove(id)
						if (batchType != null) {
							val buffered = netsplitBuffer.remove(id)
							if (!buffered.isNullOrEmpty()) {
								val verb = if (batchType == "netsplit") "Netsplit" else "Netjoin"
								val servers = msg.params.drop(2).joinToString(" ↔ ").ifBlank {
									buffered.flatMap { it.params.drop(0) }.distinct().joinToString(" ↔ ")
								}
								val count = buffered.size
								val serverInfo = if (servers.isNotBlank()) " ($servers)" else ""
								send(IrcEvent.ServerText("*** $verb$serverInfo — $count user${if (count == 1) "" else "s"} affected", code = batchType.uppercase()))
							}
						}
					}
					continue
				}

				val nowMs = System.currentTimeMillis()
				val serverTimeMs = parseServerTimeMs(msg.tags)
				val playbackHistory = isPlaybackHistory(msg.tags) ||
					(openPlaybackBatches.size == 1 && serverTimeMs != null && serverTimeMs < (nowMs - 15_000L))

				val isHistory = playbackHistory || isHeuristicHistory(null, serverTimeMs, nowMs) // Target will be set per-event

				// server numerics (MOTD/WHOIS/errors/etc)
				val numericText = formatNumeric(msg)

				// Pending-ban completion: when a queued [PendingBan] is waiting on WHOIS
				// data for this nick, fish the user/host (311) or services account (330)
				// out of the reply and apply the queued bans. 318 (end of WHOIS) is also
				// a sentinel — if we never saw a 311 we drain the queue with whatever data
				// we have (typically nothing), which surfaces a "couldn't resolve" error.
				if (pendingBansByNick.isNotEmpty()) {
					when (msg.command) {
						"311" -> {
							val nick = msg.params.getOrNull(1)
							val user = msg.params.getOrNull(2)
							val host = msg.params.getOrNull(3)
							if (nick != null && pendingBansByNick.containsKey(casefold(nick))) {
								// HOST/USER/DOMAIN bans complete here; ACCOUNT bans wait for 330.
								val hasAccountQueued = pendingBansByNick[casefold(nick)]
									?.any { it.type == BanMaskType.ACCOUNT } == true
								if (!hasAccountQueued) {
									completePendingBans(nick, user, host, account = null)
								}
								// stash for 330 handler in case ACCOUNT is queued
								if (hasAccountQueued) {
									pendingWhoisHostByNick[casefold(nick)] = (user ?: "") to (host ?: "")
								}
							}
						}
						"330" -> {
							// RPL_WHOISACCOUNT: <client> <nick> <account> :is logged in as
							val nick = msg.params.getOrNull(1)
							val account = msg.params.getOrNull(2)
							if (nick != null && account != null) {
								val fold = casefold(nick)
								val (u, h) = pendingWhoisHostByNick.remove(fold) ?: ("" to "")
								completePendingBans(nick, u.ifBlank { null }, h.ifBlank { null }, account)
							}
						}
						"318" -> {
							// End of WHOIS: drain any still-pending bans for this nick. They
							// either succeeded (handled above and removed from the map) or the
							// server gave us no useful data — surface the failure now.
							val nick = msg.params.getOrNull(1)
							if (nick != null) {
								val fold = casefold(nick)
								val (u, h) = pendingWhoisHostByNick.remove(fold) ?: ("" to "")
								if (pendingBansByNick.containsKey(fold)) {
									completePendingBans(nick, u.ifBlank { null }, h.ifBlank { null }, account = null)
								}
							}
						}
					}
				}

				// Route WHOIS numerics back to the buffer where the WHOIS was invoked.
				val whoisTargetBuffer: String? = run {
					val whoisCodes = setOf(
						"301","311","312","313","317","318","319","320","330",
						"335","338","378","379",
						"401","406",
						"671","672","673","674","675"
					)
					if (msg.command !in whoisCodes) return@run null
					val nick = msg.params.getOrNull(1) ?: return@run null
					val fold = casefold(nick)
					val buf = pendingWhoisBufferByNick[fold] ?: return@run null
					if (msg.command == "318" || msg.command == "401" || msg.command == "406") {
						pendingWhoisBufferByNick.remove(fold)
					}
					buf
				}
				val specialNumericCodes = setOf(
					"315",           // RPL_ENDOFWHO (sent after WHOX nicklist query; suppress from buffers)
					"324",
					"354",           // WHOX reply (handled silently; WhoxReply event emitted)
					"367","368", // ban list
					"346","347", // +I (invex) list
					"348","349", // +e (except) list
					"728","729", // +q (quiet) list
					"471","472","473","474","475","476","477"
				)
				if (numericText != null && msg.command !in specialNumericCodes) {
					send(IrcEvent.ServerText(numericText, code = msg.command, bufferName = whoisTargetBuffer))
				} else if (msg.command.length == 3 && msg.command.all { it.isDigit() }
					&& msg.command !in setOf(
						"001",
						"315",
						"321","322","323",
						"324",
						"332","333",
						"353","354","366",
						"367","368",
						"346","347",
						"348","349",
						"728","729",
						"433",
						"471","472","473","474","475","476","477",
						// SASL numerics: emitted as Status/Error by IrcSession's CAP state
						// machine. Suppress the raw-form fallback so the user sees each
						// event once, not twice.
						"903","904","905","906","907","908"
					)
				) {
					// surface unknown numerics in a readable form, even if raw server lines are hidden.
					val bodyParts = (msg.params.drop(1).map { stripIrcFormatting(it) } + listOfNotNull(msg.trailing?.let { stripIrcFormatting(it) }))
						.filter { it.isNotBlank() }
					val body = bodyParts.joinToString(" ")
					if (body.isNotBlank()) {
						send(IrcEvent.ServerText("[${msg.command}] $body", code = msg.command))
					}
				}


				if (msg.command.length == 3 && msg.command.all { it.isDigit() }) {
					val h = numericHandlers[msg.command]
					if (h != null) {
						h(msg, serverTimeMs, playbackHistory, nowMs)
						continue
					}
				}

				when (msg.command.uppercase(Locale.ROOT)) {
					"NICK" -> {
						val old = msg.prefixNick()
						val newNick = (msg.trailing ?: msg.params.firstOrNull())
						if (old != null && newNick != null) {
							send(IrcEvent.NickChanged(old, newNick, timeMs = serverTimeMs, isHistory = playbackHistory))
						}
						if (old != null && newNick != null && nickEquals(old, currentNick)) {
							currentNick = newNick
						}
					}

					"PRIVMSG" -> {
						val from = msg.prefixNick() ?: "?"
						val rawTarget = msg.params.getOrNull(0) ?: continue
						val target = normalizeMsgTarget(rawTarget)
						val textRaw = msg.trailing ?: ""

						// znc.in/playback: *playback module sends TIMESTAMP <buffer> <epoch>
						// so we know when we were last seen and can request only missed messages.
						// Only consume TIMESTAMP messages here; other messages from *playback
						// (e.g. "Module not loaded") fall through to the generic pseudo-user
						// routing below and surface in the server buffer.
						if (config.isBouncer && from.equals("*playback", ignoreCase = true)) {
							val parts = textRaw.trim().split(" ")
							if (parts.size >= 2 && parts[0].equals("TIMESTAMP", ignoreCase = true)) {
								val bufName = parts[1]
								val epochSecs = parts.getOrNull(2)?.toLongOrNull()
								if (epochSecs != null) zncLastSeen[bufName.lowercase()] = epochSecs
								continue  // Internal plumbing only — never shown.
							}
							// Non-TIMESTAMP: fall through.
						}

						// When echo-message is ACK'd the server reflects our own outbound
						// "PRIVMSG *playback :PLAY …" commands back to us. Without this filter
						// those echoes create a visible *playback query buffer on every connect.
						// Treat them as internal plumbing and discard.
						if (config.isBouncer
							&& target.equals("*playback", ignoreCase = true)
							&& nickEquals(from, currentNick)
						) {
							continue
						}

						// ZNC / soju internal pseudo-users (*status, *controlpanel, *playback,
						// *clientbuffer, BouncerServ on soju, etc.). These send administrative
						// messages that we route to the *server* buffer instead of creating a new
						// DM buffer so they don't clutter the buffer list with noise the user
						// didn't initiate.
						//
						// ZNC convention: any nick starting with '*' is a loaded module. We match
						// the prefix rather than enumerating module names because users can load
						// arbitrary modules and the set is extensible.
						// soju convention: a single named pseudo-user "BouncerServ".
						if (config.isBouncer && !isChannelName(target) && isBouncerPseudoUser(from)) {
							// Show the message but route it to the server buffer.
							send(IrcEvent.Notice(
								from = from,
								target = "*server*",
								text = textRaw,
								isPrivate = false,
								isServer = true,
								timeMs = serverTimeMs,
								isHistory = (playbackHistory || isHeuristicHistory("*server*", serverTimeMs, nowMs))
							))
							continue
						}

						val isChannel = isChannelName(target)
						val isPrivate = !isChannel

						// If this PRIVMSG comes from a server prefix (no '!'), route it to *server*.
						val isServerPrefix = (msg.prefix != null && !msg.prefix.contains('!') && !msg.prefix.contains('@'))

						// For private messages, use the *other party* as the buffer name.
						val buf = if (isPrivate) {
							when {
								isServerPrefix -> "*server*"
								nickEquals(from, currentNick) -> target
								else -> from
							}
						} else {
							target
						}

						// Handle CTCP requests
						val trimmedText = textRaw.trim()
						if (trimmedText.startsWith("\u0001") && !nickEquals(from, currentNick)) {
							// Strip leading \x01 and optional trailing \x01
							val ctcpContent = trimmedText.removePrefix("\u0001").removeSuffix("\u0001").trim()
							if (ctcpContent.isNotEmpty()) {
								val spaceIdx = ctcpContent.indexOf(' ')
								val ctcpCmd = (if (spaceIdx > 0) ctcpContent.substring(0, spaceIdx) else ctcpContent).uppercase()
								val ctcpArgs = if (spaceIdx > 0) ctcpContent.substring(spaceIdx + 1) else ""

								// Sanitise the sender nick used in outgoing NOTICE targets: strip
								// CR/LF/NUL so a malicious server prefix cannot inject IRC commands.
								val safeSender = from.replace(Regex("[\r\n\u0000]"), "")

								// Rate-limit replies to at most one per CTCP_RATE_LIMIT_MS per nick
								// so a flood of CTCP requests cannot get us K-lined.
								// ACTION and DCC are never rate-limited (they don't generate replies).
								val now = System.currentTimeMillis()
								val senderKey = safeSender.lowercase()
								val lastReply = ctcpLastReplyMs[senderKey] ?: 0L
								val rateLimited = ctcpCmd != "ACTION" && ctcpCmd != "DCC"
									&& (now - lastReply) < CTCP_RATE_LIMIT_MS
								if (rateLimited) {
									send(IrcEvent.Status("CTCP $ctcpCmd from $safeSender ignored (rate limited)"))
									continue
								}

								when (ctcpCmd) {
									"VERSION" -> {
										// Build the reply at call time so it always reflects the
										// installed app version - never stale from a saved config.
										ctcpLastReplyMs[senderKey] = now
										writeLine("NOTICE $safeSender :\u0001VERSION HexDroid v${BuildConfig.VERSION_NAME} - https://hexdroid.boxlabs.uk/\u0001")
										send(IrcEvent.Status("CTCP VERSION reply sent to $safeSender"))
										continue
									}
									"PING" -> {
										// Strip CR/LF/NUL from the echoed payload to prevent IRC
										// command injection via a crafted CTCP PING argument.
										val safeArgs = ctcpArgs.replace(Regex("[\r\n\u0000\u0001]"), "").take(200)
										ctcpLastReplyMs[senderKey] = now
										writeLine("NOTICE $safeSender :\u0001PING $safeArgs\u0001")
										send(IrcEvent.Status("CTCP PING reply sent to $safeSender"))
										continue
									}
									"TIME" -> {
										val timeStr = java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", java.util.Locale.US).format(java.util.Date())
										ctcpLastReplyMs[senderKey] = now
										writeLine("NOTICE $safeSender :\u0001TIME $timeStr\u0001")
										send(IrcEvent.Status("CTCP TIME reply sent to $safeSender"))
										continue
									}
									"FINGER", "USERINFO" -> {
										val safeRealname = config.realname.take(100)
										ctcpLastReplyMs[senderKey] = now
										writeLine("NOTICE $safeSender :\u0001$ctcpCmd $safeRealname\u0001")
										send(IrcEvent.Status("CTCP $ctcpCmd reply sent to $safeSender"))
										continue
									}
									"CLIENTINFO" -> {
										ctcpLastReplyMs[senderKey] = now
										writeLine("NOTICE $safeSender :\u0001CLIENTINFO ACTION PING VERSION TIME FINGER USERINFO CLIENTINFO SOURCE DCC\u0001")
										send(IrcEvent.Status("CTCP CLIENTINFO reply sent to $safeSender"))
										continue
									}
									"SOURCE" -> {
										ctcpLastReplyMs[senderKey] = now
										writeLine("NOTICE $safeSender :\u0001SOURCE https://hexdroid.boxlabs.uk/\u0001")
										send(IrcEvent.Status("CTCP SOURCE reply sent to $safeSender"))
										continue
									}
									"ACTION" -> {
										// ACTION is handled below as a message
									}
									"DCC" -> {
										// DCC is handled below
									}
									else -> {
										// Unknown CTCP — log but don't reply (no reply = no flood risk).
										send(IrcEvent.Status("Unknown CTCP $ctcpCmd from $safeSender"))
										continue
									}
								}
							}
						}

						// CTCP DCC: consume offers so the raw CTCP line doesn't show in chat.
						val dccSend = parseDccSend(textRaw)
						if (dccSend != null) {
							if (!nickEquals(from, currentNick)) {
								send(IrcEvent.DccOfferEvent(dccSend.copy(from = from)))
							}
							continue
						}

						val dccChat = parseDccChat(textRaw)
						if (dccChat != null) {
							if (!nickEquals(from, currentNick)) {
								send(IrcEvent.DccChatOfferEvent(dccChat.copy(from = from)))
							}
							continue
						}

						val isAction = textRaw.startsWith("\u0001ACTION ") && textRaw.endsWith("\u0001")
						val text = if (isAction) {
							textRaw.removePrefix("\u0001ACTION ").removeSuffix("\u0001")
						} else {
							textRaw
						}

						// Keep raw formatting codes. UI chooses to strip or render them.
						send(
							IrcEvent.ChatMessage(
								from = from,
								target = buf,
								text = text,
								isPrivate = isPrivate,
								isAction = isAction,
								timeMs = serverTimeMs,
								isHistory = (playbackHistory || isHeuristicHistory(buf, serverTimeMs, nowMs)),
								msgId = msg.tags["msgid"],
								// IRCv3 +draft/reply / +reply tag: msgid of message being replied to.
								replyToMsgId = msg.tags["+draft/reply"] ?: msg.tags["+reply"],
								// IRCv3 account-tag: services account of the sender.
								senderAccount = msg.tags["account"]?.takeIf { it.isNotBlank() && it != "*" }
							)
						)
					}

					"NOTICE" -> {
						val from = msg.prefixNick() ?: (msg.prefix ?: "?")
						val rawTarget = msg.params.getOrNull(0) ?: "*server*"
						val target = normalizeMsgTarget(rawTarget)
						val text = msg.trailing ?: ""

						// Check for CTCP reply (wrapped in \x01)
						// Only process if it's from someone else, not our own echoed reply
						if (text.startsWith("\u0001") && text.endsWith("\u0001") && !nickEquals(from, currentNick)) {
							val ctcpContent = text.trim('\u0001')
							val spaceIdx = ctcpContent.indexOf(' ')
							val ctcpCmd = if (spaceIdx > 0) ctcpContent.substring(0, spaceIdx) else ctcpContent
							val ctcpArgs = if (spaceIdx > 0) ctcpContent.substring(spaceIdx + 1) else ""
							send(
								IrcEvent.CtcpReply(
									from = from,
									command = ctcpCmd,
									args = ctcpArgs,
									timeMs = serverTimeMs
								)
							)
							continue
						}

						val isChannel = isChannelName(target)
						val isServerPrefix = (msg.prefix != null && !msg.prefix.contains('!') && !msg.prefix.contains('@'))

						// Bouncer pseudo-user NOTICE routing. ZNC replies to /msg *status
						// commands (and to /znc slash-command wrapper) via NOTICE — without
						// this branch the reply opens a query buffer for *status rather than
						// rendering inline in the server log. Same rationale and prefix check
						// as the PRIVMSG handler above; see [isBouncerPseudoUser].
						if (config.isBouncer && !isChannel && isBouncerPseudoUser(from)) {
							send(IrcEvent.Notice(
								from = from,
								target = "*server*",
								text = text,
								isPrivate = false,
								isServer = true,
								timeMs = serverTimeMs,
								isHistory = (playbackHistory || isHeuristicHistory("*server*", serverTimeMs, nowMs)),
								msgId = msg.tags["msgid"],
								replyToMsgId = msg.tags["+draft/reply"] ?: msg.tags["+reply"]
							))
							continue
						}

						// Keep the IRC target intact and let the UI decide routing.
						// Use a stable buffer name for history heuristics.
						val histBuf = if (isChannel) target else "*server*"
						send(
							IrcEvent.Notice(
								from = from,
								target = target,
								text = text,
								isPrivate = !isChannel && !isServerPrefix,
								isServer = isServerPrefix,
								timeMs = serverTimeMs,
								isHistory = (playbackHistory || isHeuristicHistory(histBuf, serverTimeMs, nowMs)),
								msgId = msg.tags["msgid"],
								replyToMsgId = msg.tags["+draft/reply"] ?: msg.tags["+reply"]
							)
						)
					}

					"JOIN" -> {
						val nick = msg.prefixNick() ?: continue
						// JOIN can be "JOIN :#chan" or, with extended-join, "JOIN #chan account :realname".
						// Prefer the first param when it looks like a channel; otherwise fall back to trailing.
						val chanRaw = msg.params.firstOrNull()?.takeIf { isChannelName(it) }
							?: msg.trailing?.takeIf { isChannelName(it) }
							?: continue

						// Suppress JOIN events that are part of a netjoin batch - emit one collapsed line instead.
						val batchId = msg.tags["batch"]
						if (batchId != null && openNetsplitBatches[batchId] == "netjoin") {
							netsplitBuffer.getOrPut(batchId) { mutableListOf() }.add(msg)
							continue
						}

						// JOIN may include a comma-separated list (JOIN #a,#b). Emit one event per channel.
						val chans = chanRaw
							.split(',')
							.map { it.trim() }
							.filter { isChannelName(it) }
							.ifEmpty { listOf(chanRaw) }

						val userHost = msg.prefix?.substringAfter('!', missingDelimiterValue = "")
							?.takeIf { it.isNotBlank() }

						// IRCv3 extended-join: params[1] = services account ("*" = not logged in),
						// trailing = realname (gecos). Standard JOIN has no params[1].
						val extAccount = if (irc.hasCap("extended-join")) {
							msg.params.getOrNull(1)?.takeIf { it.isNotBlank() && it != "*" }
						} else null
						val extRealname = if (irc.hasCap("extended-join")) msg.trailing else null

						for (chan in chans) {
							val chanHist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
							send(
								IrcEvent.Joined(
									channel = chan,
									nick = nick,
									userHost = userHost,
									timeMs = serverTimeMs,
									isHistory = chanHist,
									account = extAccount,
									realname = extRealname
								)
							)
							if (nickEquals(nick, currentNick) && !chanHist) {
								val fold = casefold(chan)
								joinedChannelCases[fold] = chan
							}

							// IRCv3 chathistory: request recent messages when we (re)join.
							// Supports both the graduated "chathistory" and legacy "draft/chathistory" cap.
							//
							// Skip when chanHist is true: a JOIN arriving as part of buffer playback
							// represents our PRIOR session, not the current one. Firing CHATHISTORY
							// LATEST against it re-requests history we're already in the middle of
							// receiving — same rationale as the znc.in/playback guard below.
							if (nickEquals(nick, currentNick)
								&& config.capPrefs.draftChathistory
								&& hasChathistoryCap()
								&& !chanHist
								&& historyRequested.add(chan.lowercase())
							) {
								val lim = config.historyLimit.coerceIn(0, 500)
								if (lim > 0) {
									writeLine("${labelTag()}CHATHISTORY LATEST $chan * $lim")
									historyExpectUntil[chan.lowercase()] = nowMs + 7_000L
								}
							}

							// znc.in/playback: request only messages we missed since last seen.
							// Sends: PRIVMSG *playback :PLAY <buffer> <lastSeen> <now>
							//
							// Skip when chanHist is true: a JOIN arriving as part of the bouncer's
							// own buffer playback represents our PRIOR session, not the current one.
							// Firing PLAY against it re-requests history we're already in the middle
							// of receiving and produces duplicate lines.
							if (nickEquals(nick, currentNick)
								&& config.isBouncer
								&& irc.hasCap("znc.in/playback")
								&& !chanHist
							) {
								val lastSeen = zncLastSeen[chan.lowercase()] ?: 0L
								val nowSecs = nowMs / 1000L
								writeLine("PRIVMSG *playback :PLAY $chan $lastSeen $nowSecs")
								historyExpectUntil[chan.lowercase()] = nowMs + 15_000L
							}

							// WHOX: on joining a channel, query the full user/host/account info
							// for all members using WHO #chan %uhsnfar,42. The query type "42"
							// is an arbitrary cookie used to identify WHOX replies (354) vs
							// regular WHO replies (352).  We only do this if the server advertises
							// WHOX in ISUPPORT(005) to avoid sending a WHO that returns nothing
							// useful on non-WHOX servers.
							if (nickEquals(nick, currentNick) && whoxSupported && config.capPrefs.whox && !chanHist) {
								// %u=ident %h=host %s=server %n=nick %f=flags %a=account %r=realname
								writeLine("WHO $chan %uhsnfar,42")
							}
						}
					}

					"PART" -> {
						val nick = msg.prefixNick() ?: continue

						// Most servers send:  PART <channel>[,<channel>...] [:reason]
						// But some bouncers/bridges send malformed variants
						// where the channel list lands in the trailing field (with no params).
						// Accept both so we still update nicklists.

						val trailing0 = msg.trailing?.trim()
						val chanRaw = when {
							msg.params.isNotEmpty() -> msg.params[0]
							// Only treat trailing as channel list if it's a single token and looks like a channel.
							trailing0 != null && !trailing0.contains(' ') && (trailing0.startsWith('#') || trailing0.startsWith('&')) -> trailing0
							else -> continue
						}

						// PART may include a comma-separated list (PART #a,#b :reason). Emit one event per channel.
						val chans = chanRaw
							.split(',')
							.map { it.trim() }
							.filter { it.isNotBlank() }
							.ifEmpty { listOf(chanRaw) }

						val userHost = msg.prefix?.substringAfter('!', missingDelimiterValue = "")
							?.takeIf { it.isNotBlank() }
						// Reason can be the IRC trailing parameter, or a second param without ':'
						// e.g. "PART #chan goodbye".
						val reason = when {
							msg.params.size >= 2 && msg.trailing == null -> msg.params[1]
							// If we had to read the channel from trailing (malformed form), don't reuse it as reason.
							msg.params.isEmpty() -> null
							else -> msg.trailing
						}

						for (chan in chans) {
							val chanHist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
							send(
								IrcEvent.Parted(
									channel = chan,
									nick = nick,
									userHost = userHost,
									reason = reason,
									timeMs = serverTimeMs,
									isHistory = chanHist
								)
							)
							if (nickEquals(nick, currentNick) && !chanHist) {
								joinedChannelCases.remove(casefold(chan))
							}
						}
					}

					"KICK" -> {
						val kicker = msg.prefixNick() ?: continue
						val kickerHost = msg.prefix?.substringAfter('!', missingDelimiterValue = "")
							?.takeIf { it.isNotBlank() }
						val chan = msg.params.getOrNull(0) ?: continue
						val victim = msg.params.getOrNull(1) ?: continue
						val reason = msg.trailing
						val chanHist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
						send(
							IrcEvent.Kicked(
								channel = chan,
								victim = victim,
								byNick = kicker,
								byHost = kickerHost,
								reason = reason,
								timeMs = serverTimeMs,
								isHistory = chanHist
							)
						)
						if (nickEquals(victim, currentNick) && !chanHist) {
							joinedChannelCases.remove(casefold(chan))
						}
					}

					"QUIT" -> {
						val nick = msg.prefixNick() ?: continue
						// Suppress QUIT events that are part of a netsplit batch - emit one collapsed line instead.
						val batchId = msg.tags["batch"]
						if (batchId != null && openNetsplitBatches[batchId] == "netsplit") {
							netsplitBuffer.getOrPut(batchId) { mutableListOf() }.add(msg)
							continue
						}
						val userHost = msg.prefix?.substringAfter('!', missingDelimiterValue = "")
							?.takeIf { it.isNotBlank() }
						val reason = msg.trailing
						send(IrcEvent.Quit(nick = nick, userHost = userHost, reason = reason, timeMs = serverTimeMs, isHistory = playbackHistory))
					}


					"WALLOPS", "GLOBOPS", "LOCOPS", "OPERWALL", "SNOTICE" -> {
						val sender = msg.prefixNick() ?: (msg.prefix ?: "server")
						val txt = (msg.trailing ?: msg.params.drop(0).joinToString(" ")).let { stripIrcFormatting(it) }
						if (txt.isNotBlank()) {
							send(IrcEvent.ServerText("* ${msg.command.uppercase(Locale.ROOT)} from $sender: $txt", code = msg.command.uppercase(Locale.ROOT)))
						}
					}

					"TOPIC" -> {
						val chan = msg.params.firstOrNull() ?: continue
						val topic = msg.trailing
						val setter = msg.prefixNick()
						send(IrcEvent.Topic(chan, topic, setter = setter, timeMs = serverTimeMs, isHistory = (playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs))))
					}


					"MODE" -> {
						val rawTarget = msg.params.getOrNull(0) ?: continue
						val target = normalizeMsgTarget(rawTarget)
						if (!isChannelName(target)) {
							// User MODE change (target is a nick, not a channel).
							// Detect +o/+O on our own nick - covers auto-oper via services,
							// not just explicit /OPER (which triggers 381 RPL_YOUREOPER).
							if (nickEquals(target, currentNick)) {
								val modeStr = msg.params.getOrNull(1) ?: ""
								var adding = true
								for (ch in modeStr) {
									when (ch) {
										'+' -> adding = true
										'-' -> adding = false
										'o', 'O' -> {
											if (adding) {
												send(IrcEvent.YoureOper("You are now an IRC operator"))
											} else {
												send(IrcEvent.YoureDeOpered)
											}
										}
									}
								}
							}
							continue
						}

						val modeStr = msg.params.getOrNull(1) ?: continue
						val args = msg.params.drop(2)

						// Update nick prefixes for rank modes (op/voice/etc).
						parseChannelUserModes(target, modeStr, args).forEach { (nick, prefix, adding) ->
							send(IrcEvent.ChannelUserMode(target, nick, prefix, adding, timeMs = serverTimeMs, isHistory = (playbackHistory || isHeuristicHistory(target, serverTimeMs, nowMs))))
						}

						// Also surface the mode change as a readable line in the channel buffer.
						val setter = msg.prefixNick() ?: (msg.prefix ?: "server")
						val extra = if (args.isEmpty()) "" else " " + args.joinToString(" ")
						send(IrcEvent.ChannelModeLine(target, "*** $setter sets mode $modeStr$extra", timeMs = serverTimeMs, isHistory = (playbackHistory || isHeuristicHistory(target, serverTimeMs, nowMs))))
					}

					// IRCv3 CHGHOST: ident or hostname changed (requires chghost CAP)
					"CHGHOST" -> {
						val nick = msg.prefixNick() ?: continue
						val newUser = msg.params.getOrNull(0) ?: continue
						val newHost = msg.params.getOrNull(1) ?: continue
						send(IrcEvent.Chghost(nick, newUser, newHost, timeMs = serverTimeMs, isHistory = playbackHistory))
					}

					// IRCv3 ACCOUNT: services account changed (requires account-notify CAP)
					// account name is params[0]; "*" means logged out
					"ACCOUNT" -> {
						val nick = msg.prefixNick() ?: continue
						val account = msg.params.getOrNull(0) ?: "*"
						send(IrcEvent.AccountChanged(nick, account, timeMs = serverTimeMs, isHistory = playbackHistory))
					}

					// IRCv3 SETNAME: realname changed (requires setname CAP)
					"SETNAME" -> {
						val nick = msg.prefixNick() ?: continue
						val newRealname = msg.trailing ?: msg.params.getOrNull(0) ?: continue
						send(IrcEvent.Setname(nick, newRealname, timeMs = serverTimeMs, isHistory = playbackHistory))
					}

					// INVITE: received an invite to a channel
					"INVITE" -> {
						// :inviter INVITE targetNick #channel
						val from = msg.prefixNick() ?: continue
						val targetNick = msg.params.getOrNull(0) ?: continue
						val channel = msg.trailing ?: msg.params.getOrNull(1) ?: continue
						if (nickEquals(targetNick, currentNick)) {
							// This invite is for us.
							send(IrcEvent.InviteReceived(from, channel, timeMs = serverTimeMs))
						} else if (irc.hasCap("invite-notify")) {
							// IRCv3 invite-notify: server broadcasts invites to others in shared channels.
							// Surface as a status line in the channel buffer (and server buffer as fallback).
							val bufTarget = if (isChannelName(channel)) channel else "*server*"
							send(IrcEvent.ServerText(
								"*** $from invited $targetNick to $channel",
								code = "INVITE",
								bufferName = bufTarget
							))
						}
					}

					// ERROR: fatal server message, always followed by connection close
					"ERROR" -> {
						val message = msg.trailing ?: msg.params.joinToString(" ")
						send(IrcEvent.ServerError(message))
						// Emit Disconnected immediately so the reconnect loop doesn't wait for EOF
						send(IrcEvent.Disconnected("Server error: $message"))
					}

					// AWAY: another user's away status changed (requires away-notify CAP).
					// No trailing = returned from away; trailing = new away message.
					"AWAY" -> {
						val nick = msg.prefixNick() ?: continue
						if (nickEquals(nick, currentNick)) continue  // skip our own reflected echo
						send(IrcEvent.AwayChanged(nick, msg.trailing, timeMs = serverTimeMs))
					}

					// draft/relaymsg: relay bot forwarded a message on behalf of another user.
					// Format: ":relaybot!u@h RELAYMSG #channel relayednick :message"
					// params[0] = channel/target, params[1] = relayed nick, trailing = message text.
					// Surface as a regular chat message attributed to the relayed nick so the UI
					// renders it identically to a direct PRIVMSG from that nick.
					"RELAYMSG" -> {
						if (!config.capPrefs.draftRelaymsg) continue
						val target    = msg.params.getOrNull(0) ?: continue
						val relayNick = msg.params.getOrNull(1) ?: continue
						val text      = msg.trailing ?: continue
						val isAction  = text.startsWith("\u0001ACTION ") && text.endsWith("\u0001")
						val body      = if (isAction) text.removePrefix("\u0001ACTION ").removeSuffix("\u0001") else text
						send(IrcEvent.ChatMessage(
							from          = relayNick,
							target        = target,
							text          = body,
							isPrivate     = !isChannelName(target),
							isAction      = isAction,
							timeMs        = serverTimeMs,
							isHistory     = false,
							msgId         = msg.tags["msgid"],
							replyToMsgId  = msg.tags["+draft/reply"] ?: msg.tags["+reply"],
							senderAccount = null   // relay bots don't expose the relayed user's account
						))
					}

					// TAGMSG: message-tags-only (no body text).
					// Used for typing indicators (draft/typing), reactions, and other tag-only events.
					"TAGMSG" -> {
						val fromNick = msg.prefixNick() ?: continue
						val rawTarget = msg.params.getOrNull(0) ?: continue
						val target = normalizeMsgTarget(rawTarget)
						// draft/typing: +typing tag indicates composing status.
						// Values: "active" (typing), "paused" (stopped briefly), "done" (cleared/sent).
						// typing is a client-only tag
						// Libera permits it via CLIENTTAGDENY=*,-typing using message-tags.
						val typingState = msg.tags["+typing"] ?: msg.tags["typing"]
						if (typingState != null &&
							(irc.hasCap("draft/typing") || irc.hasCap("typing") || irc.hasCap("message-tags"))) {
							send(IrcEvent.TypingStatus(
								target = target,
								nick = fromNick,
								state = typingState,
								timeMs = serverTimeMs
							))
						}
						// draft/message-reactions: +draft/react tag carries the emoji.
						// Format: TAGMSG <target> with tags +draft/react=<emoji> +draft/reply=<msgid-of-original>
						// Removal uses +draft/react-removed=<emoji>.
						val reactEmoji = msg.tags["+draft/react"]
						val reactRemoved = msg.tags["+draft/react-removed"]
						if ((reactEmoji != null || reactRemoved != null) && irc.hasCap("draft/message-reactions")) {
							val emoji = reactEmoji ?: reactRemoved!!
							val adding = reactEmoji != null
							// The target message's msgid is in "+draft/reply" (or "+reply" for the
							// graduated tag name), NOT in "msgid" which is the TAGMSG's own ID.
							val replyMsgId = msg.tags["+draft/reply"] ?: msg.tags["+reply"]
							send(IrcEvent.MessageReaction(
								fromNick = fromNick,
								target = target,
								reaction = emoji,
								msgId = replyMsgId,
								adding = adding,
								timeMs = serverTimeMs
							))
						}
					}

					// IRCv3 MARKREAD (draft/read-marker) and READ (soju.im/read):
					// server confirms updated read pointer for a buffer.
					// Format: MARKREAD <target> [timestamp=<ISO8601>]
					//         READ <target> timestamp=<ISO8601>   (soju.im/read)
					"MARKREAD", "READ" -> {
						val target = msg.params.getOrNull(0) ?: continue
						val tsParam = (msg.params.drop(1) + listOfNotNull(msg.trailing))
							.firstOrNull { it.startsWith("timestamp=") }
							?.removePrefix("timestamp=")
						if (tsParam != null) {
							send(IrcEvent.ReadMarker(target = target, timestamp = tsParam))
						}
					}

					// soju/pounce BOUNCER sub-protocol: upstream network info
					"BOUNCER" -> {
						val subCmd = msg.params.getOrNull(0)?.uppercase(Locale.ROOT) ?: continue
						when (subCmd) {
							"NETWORK" -> {
								// BOUNCER NETWORK <id> <attrs> | BOUNCER NETWORK <id> *
								//
								// Per the soju.im/bouncer-networks spec:
								//  - `*` as the attributes field signals the network was deleted.
								//  - Otherwise each token is key=value; a bare key (no `=`) means
								//    the server didn't include that attribute in this update
								//    (the client should preserve its existing value).
								//  - Per the updated spec, key= with empty value means the
								//    attribute was REMOVED. We don't model per-attribute removal
								//    here since we only track three fields, but we treat empty
								//    values as "no information" rather than "set to empty string".
								val networkId = msg.params.getOrNull(1) ?: continue
								val attrTokens = msg.params.drop(2) +
									listOfNotNull(msg.trailing).flatMap { it.split(' ') }

								// Deletion sentinel: a lone "*" in the attrs slot.
								if (attrTokens.size == 1 && attrTokens[0] == "*") {
									send(IrcEvent.BouncerNetwork(networkId, null, null, null, removed = true))
									continue
								}

								var name: String? = null; var host: String? = null; var state: String? = null
								for (tok in attrTokens) {
									val eq = tok.indexOf('='); if (eq < 0) continue
									val key = tok.substring(0, eq).lowercase()
									val value = tok.substring(eq + 1).takeIf { it.isNotEmpty() }
									when (key) {
										"name"  -> if (name  == null) name  = value
										"host"  -> if (host  == null) host  = value
										"state" -> if (state == null) state = value
									}
								}
								send(IrcEvent.BouncerNetwork(networkId, name, host, state))
							}
							// BOUNCER ADDNETWORK / DELNETWORK / CHANGENETWORK: soju bouncer management commands.
							// Surface them as status text so the user can see the result in the server buffer.
							"ADDNETWORK", "DELNETWORK", "CHANGENETWORK" -> {
								val detail = (msg.params.drop(1) + listOfNotNull(msg.trailing)).joinToString(" ")
								val text = "BOUNCER $subCmd${if (detail.isNotBlank()) " $detail" else ""}"
								send(IrcEvent.ServerText(text, code = "BOUNCER"))
							}
							"ERROR" -> {
								val detail = msg.params.drop(1).joinToString(" ") + (msg.trailing?.let { " :$it" } ?: "")
								send(IrcEvent.Error("Bouncer error: $detail"))
							}
						}
					}

					// draft/channel-rename: server renamed a channel we're in.
					// Format: RENAME <old> <new> [:<reason>]
					// The client must update its buffer key and membership records.
					"RENAME" -> {
						if (!config.capPrefs.channelRename) continue
						val oldName = msg.params.getOrNull(0) ?: continue
						val newName = msg.params.getOrNull(1) ?: continue
						send(IrcEvent.ChannelRenamed(oldName = oldName, newName = newName, timeMs = serverTimeMs))
						// Also emit a status line so the rename appears in the buffer history.
						val reason = msg.trailing
						val text = if (reason.isNullOrBlank()) "Channel renamed: $oldName → $newName"
						          else "Channel renamed: $oldName → $newName ($reason)"
						send(IrcEvent.ServerText(text, code = "RENAME"))
					}

					// IRCv3 standard-replies (FAIL/WARN/NOTE): structured error/warning/info from
					// modern IRCd (Ergo 2.x, soju, InspIRCd 4+).  Format:
					//   FAIL <command> <code> [<context>...] :<description>
					//   WARN <command> <code> [<context>...] :<description>
					//   NOTE <command> <code> [<context>...] :<description>
					// params[2..n-1] are optional context tokens (e.g. channel name, offending nick).
					// The trailing parameter is the human-readable description; params.drop(2) are
					// only context tokens (not the description) when trailing is present.
					"FAIL", "WARN", "NOTE" -> {
						val srCmd = msg.params.getOrNull(0) ?: "?"
						val srCode = msg.params.getOrNull(1) ?: "?"
						// Context tokens are params[2..n-1] when trailing carries the description;
						// when there is no trailing, the last param IS the description (no context).
						val contextTokens = if (msg.trailing != null) msg.params.drop(2) else emptyList()
						val srDesc = msg.trailing ?: msg.params.lastOrNull() ?: "?"
						val srContextStr = if (contextTokens.isNotEmpty()) " [${contextTokens.joinToString(" ")}]" else ""
						val srText = "${msg.command} $srCmd $srCode$srContextStr: $srDesc"
						if (msg.command == "FAIL") send(IrcEvent.Error(srText))
						else send(IrcEvent.ServerText(srText, code = msg.command))
					}


				}
			}

			// If the user requested a disconnect, don't surface "EOF" as an error.
			send(IrcEvent.Disconnected(if (userClosing) (lastQuitReason ?: "Disconnected") else "EOF"))
		} catch (t: Throwable) {
			val msg = friendlyErrorMessage(t)
			if (userClosing) {
				send(IrcEvent.Disconnected(lastQuitReason ?: "Disconnected"))
			} else if (t is java.net.SocketTimeoutException) {
				// Read timeout: socket silent for 150 s (Doze/NAT killed it).
				// Reconnect quietly without showing a red error banner.
				send(IrcEvent.Disconnected("Connection timed out"))
			} else {
				send(IrcEvent.Error("Connection error: $msg"))
				send(IrcEvent.Disconnected(msg))
			}
		} finally {
			joinedChannelCases.clear()
			runCatching { writerJob.cancel() }
			runCatching { pingJob.cancel() }
			runCatching { registrationWatchdogJob.cancel() }
			// Attempt graceful SSL close_notify only when the connection ended cleanly
			// (i.e. the user requested a disconnect, not an error path). Calling
			// shutdownOutput() on a socket that already got an SSL_ERROR_SYSCALL or a
			// BoringSSL "Success" error causes a second SSLException that can confuse
			// the reconnect state machine on some devices. Safe to skip on error paths
			// because the server will time out the half-open session anyway.
			if (userClosing) {
				runCatching {
					(s as? SSLSocket)?.let { ssl ->
						ssl.soTimeout = 2_000  // don't hang waiting for close_notify echo
						runCatching { ssl.shutdownOutput() }
					}
				}
			}
			runCatching { s.close() }
		}
	}

		private fun parseDccSend(textRaw: String): DccOffer? {
		// CTCP wrapper: \u0001DCC SEND|TSEND <filename> <ip> <port> <size> [token]\u0001
		if (!textRaw.startsWith("\u0001DCC ", ignoreCase = false) || !textRaw.endsWith("\u0001")) return null

		val inner = textRaw.removePrefix("\u0001").removeSuffix("\u0001").trim()
		if (!inner.startsWith("DCC ", ignoreCase = true)) return null

		val afterDcc = inner.drop(3).trimStart() // remove "DCC"
		val verb = afterDcc.substringBefore(' ').uppercase()
		if (verb != "SEND" && verb != "TSEND" && verb != "SSEND") return null
		var rest = afterDcc.substringAfter(verb, "").trimStart()
		if (rest.isBlank()) return null

		// Filename
		val (filename, afterName) = if (rest.startsWith('"')) {
			val endQuote = rest.indexOf('"', startIndex = 1)
			if (endQuote <= 0) return null
			rest.substring(1, endQuote) to rest.substring(endQuote + 1).trim()
		} else {
			val firstSpace = rest.indexOf(' ')
			if (firstSpace <= 0) return null
			rest.substring(0, firstSpace) to rest.substring(firstSpace + 1).trim()
		}

		val parts = afterName.split(Regex("\\s+")).filter { it.isNotBlank() }
		if (parts.size < 3) return null

		val ipField = parts[0]
		val port = parts[1].toIntOrNull() ?: return null
		val size = parts[2].toLongOrNull() ?: 0L

		// SSEND: TLS-wrapped transfer.
		val secure = verb == "SSEND"

		// Turbo DCC:
		// - If TSEND, receiver SHOULD NOT send ACKs.
		// - Some clients append 'T' to the reverse-DCC token to signal turbo.
		var turbo = verb == "TSEND"
		var token: Long? = null
		parts.getOrNull(3)?.let { tokRaw ->
			val t = tokRaw.trim()
			val hasT = t.endsWith("T", ignoreCase = true)
			val numeric = if (hasT) t.dropLast(1) else t
			turbo = turbo || hasT
			token = numeric.toLongOrNull()
		}

		val ip = if (ipField.contains('.')) ipField else ipFromLong(ipField.toLongOrNull() ?: return null)
		if (ip.isBlank()) return null
		return DccOffer(from = "?", filename = filename, ip = ip, port = port, size = size, token = token, turbo = turbo, secure = secure)
	}

	private fun parseDccChat(textRaw: String): DccChatOffer? {
		// CTCP wrapper: \u0001DCC CHAT <proto> <ip> <port>\u0001
		if (!textRaw.startsWith("\u0001DCC ") || !textRaw.endsWith("\u0001")) return null

		val inner = textRaw.removePrefix("\u0001").removeSuffix("\u0001").trim()
		if (!inner.startsWith("DCC ", ignoreCase = true)) return null

		val afterDcc = inner.drop(3).trimStart() // remove "DCC"
		val verb = afterDcc.substringBefore(' ').uppercase(Locale.ROOT)
		if (verb != "CHAT" && verb != "SCHAT") return null

		// SCHAT: TLS-wrapped chat session.
		val secure = verb == "SCHAT"

		val rest = afterDcc.substringAfter(verb, "").trimStart()
		val parts = rest.split(Regex("\\s+")).filter { it.isNotBlank() }
		if (parts.size < 3) return null

		val proto = parts[0]
		val ipField = parts[1]
		val port = parts[2].toIntOrNull() ?: return null
		val ip = if (ipField.contains('.')) ipField else ipFromLong(ipField.toLongOrNull() ?: return null)
		if (ip.isBlank()) return null

		return DccChatOffer(from = "?", protocol = proto, ip = ip, port = port, secure = secure)
	}

	private fun ipFromLong(v: Long): String {
		val b1 = (v shr 24) and 255
		val b2 = (v shr 16) and 255
		val b3 = (v shr 8) and 255
		val b4 = v and 255
		return "$b1.$b2.$b3.$b4"
	}
	private fun openSocket(): Socket {
		// Sane socket defaults for mobile networks.
		// - connect timeout avoids hanging forever on bad networks
		// - read timeout helps detect half-open connections (ping loop also guards this)
		fun baseSocket(): Socket = Socket().apply {
			tcpNoDelay = config.tcpNoDelay
			keepAlive = config.keepAlive
			soTimeout = config.readTimeoutMs
		}

		return if (!config.useTls) {
			val s = baseSocket()
			s.connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMs)
			s
		} else {
			// Shared SSLContext cache: reconnects to the same profile reuse the same context
			// and therefore the same JSSE session cache, letting the platform perform TLS
			// session resumption (abbreviated handshake). Cuts a round-trip on reconnects.
			//
			// SSLContext.init() may only be called ONCE per context instance, so the cache key
			// must encode every aspect of the context's behaviour — see SslContextKey above.
			val cacheKey = SslContextKey(
				allowInvalidCerts = config.allowInvalidCerts,
				clientCertContentHash = config.clientCert?.pkcs12?.let { java.util.Arrays.hashCode(it) } ?: 0,
				clientCertPasswordHash = config.clientCert?.password?.hashCode() ?: 0,
				tlsTofuFingerprint = config.tlsTofuFingerprint,
			)
			val sslContext = sslContextCache.getOrPut(cacheKey) {
				val ctx = SSLContext.getInstance("TLS")
				val tm = if (config.allowInvalidCerts) arrayOf<TrustManager>(InsecureTrustManager()) else null
				val km: Array<KeyManager>? = config.clientCert?.let { cert ->
					try {
						val ks = KeyStore.getInstance("PKCS12")
						val pwdChars = cert.password?.toCharArray()
						ByteArrayInputStream(cert.pkcs12).use { ks.load(it, pwdChars) }
						val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
						kmf.init(ks, pwdChars)
						kmf.keyManagers
					} catch (t: Throwable) {
						throw IllegalStateException(
							"Client certificate could not be loaded: " + (t.message ?: t::class.java.simpleName),
							t
						)
					}
				}
				ctx.init(km, tm, SecureRandom())
				ctx
			}

			val raw = baseSocket().apply {
				connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMs)
			}

			val ss = sslContext.socketFactory.createSocket(raw, config.host, config.port, true) as SSLSocket
			val allowed = ss.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
			if (allowed.isNotEmpty()) ss.enabledProtocols = allowed.toTypedArray()

			// Apply a bounded soTimeout during startHandshake() so TLS negotiation cannot hang
			// forever. On some devices (MediaTek SoCs, certain MIUI/OneUI builds) BoringSSL
			// stalls mid-handshake and eventually surfaces SSL_ERROR_SYSCALL with errno 0
			// ("Success" / "I/O error during system call, Success") - or never returns at all
			// when the radio power-manager suspends the socket during negotiation.
			// A bounded timeout ensures a clean exception and re-entry into the reconnect loop
			// rather than a permanently hung coroutine.
			// After the handshake we restore readTimeoutMs (normally 0 = infinite, relying on
			// the PING/PONG loop for mid-session liveness detection).
			ss.soTimeout = ConnectionConstants.TLS_HANDSHAKE_TIMEOUT_MS
			try {
				ss.startHandshake()
			} catch (e: Exception) {
				runCatching { ss.close() }
				runCatching { raw.close() }
				throw e
			}
			ss.soTimeout = config.readTimeoutMs  // restore post-handshake timeout

			// TOFU certificate pinning — performed AFTER the handshake so we have access to
			// the peer cert chain. Strategy:
			//   1. Compute SHA-256 of the leaf (peer) cert.
			//   2. If no fingerprint is stored on this config, capture the computed one into
			//      [learnedFingerprint] — the events flow will emit TlsFingerprintLearned so
			//      the caller can persist it. This ONLY fires when allowInvalidCerts = true
			//      (otherwise the standard trust chain already vouched for the cert and TOFU
			//      pinning would add no value).
			//   3. If a fingerprint IS stored, compare (after [normaliseFingerprint]). On
			//      mismatch, throw [TlsFingerprintMismatchException] so the connect flow
			//      surfaces TlsFingerprintChanged and refuses the connection.
			//   4. On match, proceed silently.
			//
			// Any exception during cert extraction is logged-and-ignored: the fallback is
			// the standard JSSE trust decision that already happened during the handshake,
			// so TOFU failures on edge-case peers don't break connectivity.
			runCatching {
				val peerCerts = ss.session.peerCertificates
				val leaf = peerCerts.firstOrNull() as? java.security.cert.X509Certificate
				if (leaf != null) {
					val actualFp = computeFingerprintSha256(leaf)
					val storedRaw = config.tlsTofuFingerprint?.takeIf { it.isNotBlank() }
					if (storedRaw == null) {
						// First-seen: only "learn" a fingerprint when we were trusting
						// everything anyway. Pinning a CA-issued cert would just double-lock
						// the user out on legitimate renewals for no security gain.
						if (config.allowInvalidCerts) {
							learnedFingerprint = actualFp
						}
					} else {
						val storedNorm = normaliseFingerprint(storedRaw)
						if (!storedNorm.equals(actualFp, ignoreCase = true)) {
							runCatching { ss.close() }
							runCatching { raw.close() }
							throw TlsFingerprintMismatchException(
								stored = storedNorm,
								actual = actualFp,
							)
						}
					}
				}
			}.onFailure { t ->
				// Re-throw the sentinel; swallow anything else (cert-extraction quirks
				// shouldn't break an already-successfully-handshook connection).
				if (t is TlsFingerprintMismatchException) throw t
			}

			// Capture basic session info for UI (cipher/protocol/cert subject)
			lastTlsInfo = runCatching {
				val sess = ss.session
				val proto = sess.protocol ?: "?"
				val cipher = sess.cipherSuite ?: "?"
				val peer = runCatching { sess.peerPrincipal?.name }.getOrNull()
				val verified = if (config.allowInvalidCerts) "(unverified)" else "(verified)"
				val peerShort = peer?.substringAfter("CN=")?.substringBefore(',')?.takeIf { it.isNotBlank() }
					?: peer
					?: "peer"
				"$proto $cipher $verified • $peerShort"
			}.getOrNull()

			// Apply socket options on the wrapped SSLSocket as well.
			ss.tcpNoDelay = config.tcpNoDelay
			ss.keepAlive = config.keepAlive
			ss.soTimeout = config.readTimeoutMs

			ss
		}
	}

	/**
	 * Translate raw exception messages — especially opaque OpenSSL/BoringSSL strings —
	 * into something a user can understand and act on.
	 *
	 * Walks the cause chain when matching messages and types: Android's networking stack
	 * frequently wraps the real error (e.g. an SSLProtocolException from Conscrypt) inside
	 * a generic IOException, and checking only [t.message] / `t is SSLException` would miss
	 * it, surfacing the raw library text in the UI.
	 */
	private fun friendlyErrorMessage(t: Throwable): String {
		// Walk the cause chain (bounded to 8 hops to avoid pathological cycles) so the
		// message-substring checks can match regardless of which wrapper level carries
		// the diagnostic string.
		val chain = generateSequence<Throwable>(t) { it.cause.takeIf { c -> c !== it } }
			.take(8)
			.toList()
		val raw = chain.mapNotNull { it.message }.joinToString(" | ")
			.ifBlank { t::class.java.simpleName }
		val anyIs: (Class<out Throwable>) -> Boolean = { cls -> chain.any { cls.isInstance(it) } }

		// SSL handshake failures (certificate problems, protocol mismatch)
		if (anyIs(SSLHandshakeException::class.java)) {
			return when {
				raw.contains("CERTIFICATE_VERIFY_FAILED", ignoreCase = true) ||
				raw.contains("CertPathValidatorException", ignoreCase = true) ->
					"TLS certificate verification failed — the server's certificate may be expired, self-signed, or untrusted"
				raw.contains("PROTOCOL_VERSION", ignoreCase = true) ||
				raw.contains("NO_PROTOCOLS_AVAILABLE", ignoreCase = true) ->
					"TLS handshake failed — the server may not support TLS 1.2/1.3"
				raw.contains("HANDSHAKE_FAILURE", ignoreCase = true) ->
					"TLS handshake rejected by server — check port and TLS settings"
				else ->
					"TLS handshake failed: ${raw.take(120)}"
			}
		}

		// General SSL exceptions (mid-session errors)
		if (anyIs(SSLException::class.java)) {
			return when {
				// BoringSSL/OpenSSL "Success" (errno=0): TCP FIN received without SSL close_notify.
				// Common on mobile when the radio silently drops the connection or when the
				// server closes TCP without a proper SSL shutdown.  Not an error the user can
				// action; connection will be re-established automatically.
				//
				// "Internal OpenSSL error or protocol error" is the BoringSSL signature for a
				// state-machine confusion — commonly seen when the app was force-killed (e.g.
				// by Play Store during an update) without a clean TLS close_notify, and the
				// server still holds the old session open when we reconnect. Not actionable;
				// reconnect will sort itself out.
				raw.contains(", Success", ignoreCase = false) ||
				raw.contains("I/O error during system call, Success", ignoreCase = true) ||
				raw.contains("Internal error in SSL library", ignoreCase = true) ||
				raw.contains("SSL_ERROR_INTERNAL", ignoreCase = true) ||
				raw.contains("Internal OpenSSL error", ignoreCase = true) ||
				raw.contains("or protocol error", ignoreCase = true) ->
					"TLS session interrupted — connection will retry"
				raw.contains("Connection reset", ignoreCase = true) ||
				raw.contains("ECONNRESET", ignoreCase = true) ->
					"Connection reset by server"
				raw.contains("PROTOCOL_ERROR", ignoreCase = true) ||
				raw.contains("protocol_error", ignoreCase = true) ->
					"TLS protocol error — connection will retry"
				raw.contains("Read error", ignoreCase = true) ||
				raw.contains("SSL_ERROR_SYSCALL", ignoreCase = true) ->
					"TLS read error — network may have changed"
				raw.contains("write", ignoreCase = true) ->
					"TLS write error — network may have changed"
				raw.contains("closed", ignoreCase = true) ||
				raw.contains("shutdown", ignoreCase = true) ->
					"TLS session closed"
				else ->
					"TLS error: ${raw.take(120)}"
			}
		}

		// Non-SSL socket/IO errors
		return when {
			raw.contains("Connection refused", ignoreCase = true) ->
				"Connection refused — check hostname and port"
			raw.contains("Network is unreachable", ignoreCase = true) ->
				"Network unreachable — check your internet connection"
			raw.contains("Connection timed out", ignoreCase = true) ||
			raw.contains("connect timed out", ignoreCase = true) ->
				"Connection timed out"
			raw.contains("UnknownHost", ignoreCase = true) ||
			raw.contains("No address associated", ignoreCase = true) ->
				"Could not resolve hostname"
			raw.contains("Broken pipe", ignoreCase = true) ->
				"Connection lost (broken pipe)"
			raw.contains("Connection reset", ignoreCase = true) ->
				"Connection reset by server"
			raw.contains("Socket closed", ignoreCase = true) ->
				"Connection closed"
			else ->
				raw.take(160)
		}
	}

	@SuppressLint("TrustAllX509TrustManager")
	private class InsecureTrustManager : X509TrustManager {
		override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
		override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
		override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
	}

	/**
	 * Sentinel exception thrown from [openSocket] when a TOFU-pinned server presents a
	 * certificate whose SHA-256 fingerprint does NOT match the stored one. Carries both the
	 * expected (stored) and presented (actual) fingerprints so the [events] flow can surface
	 * them to the user via [IrcEvent.TlsFingerprintChanged]. This is a distinct type (not a
	 * plain IOException) so the flow can recognise it without string-matching the message.
	 */
	private class TlsFingerprintMismatchException(
		val stored: String,
		val actual: String,
	) : java.io.IOException(
		"TLS certificate fingerprint mismatch — expected $stored, got $actual"
	)

	/**
	 * Compute the SHA-256 fingerprint of an X.509 certificate's DER encoding, formatted as
	 * lowercase hex pairs separated by colons (e.g. "a1:b2:c3:…"). This is the canonical
	 * representation used by OpenSSL, Firefox, and most IRC clients that expose TOFU, so it
	 * round-trips cleanly if a user ever wants to copy-paste a fingerprint between tools.
	 */
	private fun computeFingerprintSha256(cert: java.security.cert.X509Certificate): String {
		val der = cert.encoded
		val md = java.security.MessageDigest.getInstance("SHA-256")
		val digest = md.digest(der)
		val sb = StringBuilder(digest.size * 3)
		for ((i, b) in digest.withIndex()) {
			if (i > 0) sb.append(':')
			sb.append(String.format("%02x", b.toInt() and 0xFF))
		}
		return sb.toString()
	}

	/**
	 * Normalise a TOFU fingerprint string for comparison. The stored value SHOULD already
	 * be lowercase colon-separated hex (we emit it that way in [computeFingerprintSha256])
	 * but accept a few common variations defensively: uppercase hex, missing colons, or
	 * stray whitespace. This way a user who pastes a fingerprint from OpenSSL or a browser
	 * into the profile JSON by hand doesn't get locked out by formatting alone.
	 */
	private fun normaliseFingerprint(raw: String): String {
		val cleaned = raw.trim().lowercase().filter { it.isLetterOrDigit() }
		// Re-insert colons between byte pairs.
		return buildString(cleaned.length + cleaned.length / 2) {
			for (i in cleaned.indices) {
				if (i > 0 && i % 2 == 0) append(':')
				append(cleaned[i])
			}
		}
	}

	private fun formatNumeric(msg: IrcMessage): String? {
		val code = msg.command
		if (code.length != 3 || !code.all { it.isDigit() }) return null
		
		// Don't double-emit for numerics that already have dedicated events.
		// SASL numerics 903-908 are emitted as IrcEvent.Status/Error by IrcSession's
		// CAP/SASL state machine; they must be suppressed here or the user sees the
		// success/failure message twice (once as a clean Status, once as raw ServerText).
		// 900 (RPL_LOGGEDIN), 901 (RPL_LOGGEDOUT), and 902 (ERR_NICKLOCKED) are NOT
		// handled by IrcSession, so they fall through to the generic ServerText path.
		if (code in setOf(
				"001","005","321","322","323","324","332","333","353","366","367","368","381",
				"433","442","471","472","473","474","475","476","477",
				"903","904","905","906","907","908"
			)) return null

		fun p(i: Int) = msg.params.getOrNull(i)
		// Strip IRC formatting for most numerics, but preserve it for MOTD lines (372/375/376)
		// so mIRC colours and bold/italic show up when the user has them enabled in settings.
		// The rendering layer (IrcLinkifiedText) decides whether to show or strip colours
		// based on the mircColorsEnabled setting.
		val motdCodes = setOf("372", "375", "376")
		val t = msg.trailing?.let { if (code in motdCodes) it else stripIrcFormatting(it) }

		return when (code) {
			// MOTD - pass raw text so colours/formatting are preserved for the renderer
			"375" -> t ?: "— MOTD —"
			"372" -> t ?: p(1) ?: ""
			"376" -> t ?: "— End of MOTD command —"
			"422" -> t ?: "No MOTD found"
			
			// ISUPPORT
			"005" -> {
				val tokens = msg.params.drop(1).filter { it.isNotBlank() }
				if (tokens.isEmpty()) null else "ISUPPORT: " + tokens.joinToString(" ")
			}

			// Host hidden
			"396" -> {
				// Typical: :server 396 <nick> <hiddenHost> :is now your hidden host
				val hidden = p(1)
				when {
					hidden != null -> "Your hidden host is now $hidden"
					t != null -> t
					else -> null
				}
			}

			// LUSERS
			"251" -> t ?: "There are ${p(1) ?: "?"} users and ${p(2) ?: "?"} invisible on ${p(3) ?: "?"} servers"
			"252" -> {
				val n = p(1) ?: return t
				val tail = t ?: "operator(s) online"
				"$n $tail"
			}
			"254" -> {
				val n = p(1) ?: return t
				val tail = t ?: "channels formed"
				"$n $tail"
			}
			"253" -> t ?: "${p(1) ?: "?"} unknown connection(s)"
			"255" -> t ?: "I have ${p(1) ?: "?"} clients and ${p(2) ?: "?"} servers"
			"265" -> t ?: "Current local users: ${p(1) ?: "?"}  Max: ${p(2) ?: "?"}"
			"266" -> t ?: "Current global users: ${p(1) ?: "?"}  Max: ${p(2) ?: "?"}"

			// Channel info
			"328" -> t?.let { "Channel URL: $it" }
			"329" -> {
				val ts = p(2)?.toLongOrNull()?.times(1000L)
				val date = ts?.let {
					val sdf = SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.getDefault())
					sdf.format(java.util.Date(it))
				} ?: "unknown"
				"Channel created on: $date"
			}

			// WHOIS / away / logged-in
			"301" -> {
				val nick = p(1) ?: return null
				val awayMsg = t ?: "Away"
				"$nick is away: $awayMsg"
			}
			"307" -> {
				val nick = p(1) ?: return null
				t?.let { "$nick is logged in as $it" }
			}
			"311" -> {
				val nick = p(1) ?: return null
				val user = p(2) ?: "?"
				val host = p(3) ?: "?"
				val real = t ?: ""
				"$nick is $user@$host ${if (real.isBlank()) "" else "($real)"}"
			}
			"312" -> {
				val nick = p(1) ?: return null
				val server = p(2) ?: "?"
				val info = t ?: ""
				"$nick using $server ${if (info.isBlank()) "" else "($info)"}"
			}
			"313" -> {
				val nick = p(1) ?: return null
				t ?: "$nick is an IRC operator"
			}
			"317" -> {
				val nick = p(1) ?: return null
				val idle = p(2)?.toLongOrNull()
				val signon = p(3)?.toLongOrNull()
				val idleStr = idle?.let { "${it / 3600}h ${(it % 3600) / 60}m ${it % 60}s idle" } ?: ""
				val signonStr = signon?.let { "signed on ${java.util.Date(it * 1000L)}" } ?: ""
				listOf(idleStr, signonStr).filter { it.isNotBlank() }.joinToString(", ").let {
					if (it.isBlank()) null else "$nick: $it"
				}
			}
			"318" -> {
				val nick = p(1) ?: return null
				t ?: "End of /WHOIS list."
			}
			"319" -> {
				val nick = p(1) ?: return null
				val chans = t ?: ""
				"$nick on channels: $chans"
			}
			"320" -> {
				val nick = p(1) ?: return null
				t ?: "$nick has special/registered status"
			}
			"335" -> {
				val nick = p(1) ?: return null
				t ?: "$nick is marked as a bot/service"
			}

			// Common errors
			"401" -> t ?: "No such nickname/channel"
			"402" -> t ?: "No such server"
			"403" -> t ?: "No such channel"
			"404" -> t ?: "Cannot send to channel"
			"406" -> t ?: "There was no such nickname"
			"421" -> t ?: "Unknown command"
			"433" -> t ?: "Nickname is already in use"
			"442" -> t ?: "You're not on that channel"
			"461" -> t ?: "Not enough parameters"
			"462" -> t ?: "You may not reregister"
			"464" -> t ?: "Password incorrect"
			"465" -> t ?: "You are banned from this server"

			// RPL_ADMIN
			"256" -> t ?: "Administrative info about this server"
			"257" -> t?.let { "Admin location: $it" }
			"258" -> t?.let { "Admin info: $it" }
			"259" -> t?.let { "Admin contact: $it" }
			"260" -> t?.let { "Extended admin info: $it" }

			// Generic fallback
			else -> {
				val bodyParts = msg.params.drop(1).map { stripIrcFormatting(it) } + listOfNotNull(t)
				val body = bodyParts.filter { it.isNotBlank() }.joinToString(" ")
				if (body.isNotBlank()) "[$code] $body" else null
			}
		}
	}

	private fun parseChannelUserModes(
		channel: String,
		modeStr: String,
		args: List<String>
	): List<Triple<String, Char?, Boolean>> {
		// Returns list of (nick, prefixChar, adding).
		//
		// We must correctly account for ALL mode arguments, not just prefix-mode args,
		// so that a mixed MODE line like "+bov nick!*@* victim" doesn't consume
		// arguments from the wrong position.
		//
		// CHANMODES ISUPPORT partitions non-prefix modes into four types:
		//   A (list modes: b,e,I,q…)  → always take a parameter
		//   B (key: k)                → always take a parameter
		//   C (limit: l)              → take a parameter only when adding (+)
		//   D (flag modes: m,n,t,…)   → never take a parameter
		// Prefix modes (o,v,h,@,+,…)  → always take a parameter (the nick)
		//
		// When chanModes is available from ISUPPORT 005, parse it into sets.
		// Fall back to a conservative default (treat unknown modes as type A)
		// to avoid under-consuming args on unfamiliar servers.
		val cm = chanModes ?: ""
		val cmParts = cm.split(",")
		val typeA = cmParts.getOrNull(0)?.toSet() ?: setOf('b', 'e', 'I', 'q')
		val typeB = cmParts.getOrNull(1)?.toSet() ?: setOf('k')
		val typeC = cmParts.getOrNull(2)?.toSet() ?: setOf('l')
		// typeD: any mode not in A, B, C, or prefix — no parameter needed.

		val results = mutableListOf<Triple<String, Char?, Boolean>>()
		var adding = true
		var argIdx = 0

		for (c in modeStr) {
			when (c) {
				'+' -> adding = true
				'-' -> adding = false
				else -> {
					val prefix = prefixModeToSymbol[c]
					if (prefix != null) {
						// Prefix mode (op, voice, etc.) — consumes a nick argument.
						val nick = args.getOrNull(argIdx) ?: continue
						argIdx++
						results.add(Triple(nick, prefix, adding))
					} else {
						// Non-prefix mode — consume its argument (if any) so argIdx
						// stays aligned for subsequent prefix modes in the same line.
						val takesArg = when {
							c in typeA -> true          // list modes always take a param
							c in typeB -> true          // key mode always takes a param
							c in typeC -> adding        // limit mode: param only when adding
							else       -> false         // flag mode: no param
						}
						if (takesArg && argIdx < args.size) argIdx++
						// Do not emit a ChannelUserMode event for non-prefix modes.
					}
				}
			}
		}
		return results
	}

	private suspend fun resolveDns(query: String): List<String> = withContext(Dispatchers.IO) {
		val results = mutableListOf<String>()
		var hasIpv4 = false
		var hasIpv6 = false

		try {
			// Forward lookup: hostname > IP(s)
			// Android's resolver won't return IPv6 if the device lacks IPv6 connectivity
			val addresses = InetAddress.getAllByName(query)
			for (addr in addresses) {
				val ip = addr.hostAddress ?: continue
				when (addr) {
					is java.net.Inet6Address -> {
						hasIpv6 = true
						results.add("IPv6: $ip")
					}
					is java.net.Inet4Address -> {
						hasIpv4 = true
						results.add("IPv4: $ip")
					}
					else -> results.add("IP: $ip")
				}

				// Try reverse lookup (PTR) for each IP
				try {
					val reverse = InetAddress.getByAddress(addr.address).canonicalHostName
					if (reverse != ip && reverse != query) {
						results.add("  PTR: $reverse")
					}
				} catch (_: Exception) {
					// Reverse failed, skip
				}
			}

			// If we only got IPv4, note that IPv6 may exist but wasn't returned
			if (hasIpv4 && !hasIpv6 && results.isNotEmpty()) {
				results.add("(IPv6 records may exist but device has no IPv6 connectivity)")
			}

			// If it's an IP address and no forward results, try reverse directly
			if (results.isEmpty() && isValidIp(query)) {
				try {
					val addr = InetAddress.getByName(query)
					val ptr = addr.canonicalHostName
					if (ptr != query) {
						results.add("PTR: $ptr")
					}
				} catch (_: Exception) {}
			}
		} catch (_: UnknownHostException) {
			// Host not found
		}

		results
	}

	// Validate IPs
	private fun isValidIp(input: String): Boolean {
		return input.matches(Regex("^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")) ||
			   input.matches(Regex("^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$")) // rough IPv6 check
	}
}