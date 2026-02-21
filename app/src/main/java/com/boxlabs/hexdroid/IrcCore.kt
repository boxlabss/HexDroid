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
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
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
    val draftReadMarker: Boolean = false
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
    val ctcpVersionReply: String = "HexDroid v${BuildConfig.VERSION_NAME} - https://hexdroid.boxlabs.uk/",
    /**
     * Character encoding for this connection.
     * - "auto" = try UTF-8, auto-detect non-UTF-8 encodings
     * - Or explicit: "UTF-8", "windows-1251", "ISO-8859-1", etc.
     */
    val encoding: String = "auto"
)

sealed class IrcEvent {
    data class Status(val text: String) : IrcEvent()
    data class Connected(val server: String) : IrcEvent()
    data class Registered(val nick: String) : IrcEvent()
    data class Disconnected(val reason: String?) : IrcEvent()
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
        val chanModes: String? = null
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
        val isHistory: Boolean = false
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
        val isHistory: Boolean = false
    ) : IrcEvent()

    data class DccOfferEvent(val offer: DccOffer) : IrcEvent()

    // CTCP DCC CHAT offer
    data class DccChatOfferEvent(val offer: DccChatOffer) : IrcEvent()

    // Numeric 442 (ERR_NOTONCHANNEL)
    data class NotOnChannel(val channel: String, val message: String, val code: String = "442") : IrcEvent()

    data class Joined(val channel: String, val nick: String, val userHost: String? = null, val timeMs: Long? = null, val isHistory: Boolean = false) : IrcEvent()
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

    data class Topic(val channel: String, val topic: String?, val timeMs: Long? = null, val isHistory: Boolean = false) : IrcEvent()
    
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
}

class IrcClient(val config: IrcConfig) {
    private val parser = IrcParser()
    private val outbound = Channel<String>(capacity = 300)
    private val rng = SecureRandom()

    @Volatile private var socket: Socket? = null
    @Volatile private var lastQuitReason: String? = null
    private var triedAltNick = false

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

    // Track joined channels (original case preserved, keyed by casefold)
    private val joinedChannelCases = mutableMapOf<String, String>()

    // Channel for emitting events from commands (merged into events() flow)
    private val commandEvents = Channel<IrcEvent>(capacity = Channel.UNLIMITED)  // Or adjust capacity

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
            // ClosedSendChannelException (surfaced in Play crash reports as obfuscated k7.m).
            // If the channel is closed or full, the line is silently dropped — this is
            // safe because the connection is already gone or saturated.
            val result = outbound.trySend(sanitized)
            if (result.isFailure && !result.isClosed) {
                // Channel is full (capacity=300) but still open fall back to a
                // suspending send so legitimate bursts are not silently discarded.
                // This path is rare; the capacity guard above handles the common cases.
                runCatching { outbound.send(sanitized) }
            }
        }
    }

    suspend fun privmsg(target: String, text: String) {
        // Sanitize the text portion to remove any embedded newlines.
        // This is a safeguard in case callers don't pre-split multiline messages.
        val sanitizedText = text.replace("\r", "").replace("\n", " ")
        sendRaw("PRIVMSG $target :$sanitizedText")
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
            "join" -> parts.getOrNull(1)?.let { sendRaw("JOIN $it") }
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
                val target = parts.getOrNull(1) ?: return
                val msg = parts.drop(2).joinToString(" ")
                privmsg(target, msg)
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
                    // Manually echo to local buffer (if echo-message CAP not enabled)
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
			"ame" -> {
				val msg = parts.drop(1).joinToString(" ").trim()
				if (msg.isBlank()) {
					commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer, text = "No text to send", isPrivate = true))
					return
				}
				for (chan in joinedChannelCases.values) {
					ctcp(chan, "ACTION $msg")
                    // Manually echo to local buffer
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
            "list" -> sendRaw("LIST")
            "motd" -> {
                val arg = parts.drop(1).joinToString(" ")
                sendRaw(if (arg.isBlank()) "MOTD" else "MOTD $arg")
            }
            "whois" -> {
                val arg = parts.drop(1).joinToString(" ").trim()
                val nick = parts.getOrNull(1)?.trim()
                if (arg.isBlank() || nick.isNullOrBlank()) return
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
                val chan = parts.getOrNull(1) ?: currentBuffer
                val nick = parts.getOrNull(2) ?: return
                val reason = parts.drop(3).joinToString(" ").trim()
                sendRaw(if (reason.isBlank()) "KICK $chan $nick" else "KICK $chan $nick :$reason")
            }
            "ban" -> {
                val chan = parts.getOrNull(1) ?: currentBuffer
                val nick = parts.getOrNull(2) ?: return
                val mask = parts.getOrNull(3) ?: "$nick!*@*"
                sendRaw("MODE $chan +b $mask")
            }
            "unban" -> {
                val chan = parts.getOrNull(1) ?: currentBuffer
                val mask = parts.getOrNull(2) ?: return
                sendRaw("MODE $chan -b $mask")
            }
            "kb", "kickban" -> {
                val chan = parts.getOrNull(1) ?: currentBuffer
                val nick = parts.getOrNull(2) ?: return
                val reason = parts.drop(3).joinToString(" ").trim()
                val mask = "$nick!*@*"
                sendRaw("MODE $chan +b $mask")
                sendRaw(if (reason.isBlank()) "KICK $chan $nick" else "KICK $chan $nick :$reason")
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
			"quit" -> {
				val reason = parts.drop(1).joinToString(" ").trim()
				sendRaw(if (reason.isBlank()) "QUIT" else "QUIT :$reason")
				delay(500)
				// Give time for QUIT to send before disconnect
				disconnect(reason.ifBlank { "Quitting" })
			}
			"notice" -> {
				val target = parts.getOrNull(1) ?: return
				val msg = parts.drop(2).joinToString(" ")
				if (msg.isNotBlank()) sendRaw("NOTICE $target :$msg")
			}
			"invite" -> {
				val nick = parts.getOrNull(1) ?: return
				val chan = parts.getOrNull(2) ?: if (currentBuffer != "*server*" && isChannelName(currentBuffer)) currentBuffer else return
				sendRaw("INVITE $nick $chan")
			}
			"op" -> {
				val nick = parts.getOrNull(1) ?: return
				val chan = parts.getOrNull(2) ?: if (currentBuffer != "*server*" && isChannelName(currentBuffer)) currentBuffer else return
				sendRaw("MODE $chan +o $nick")
			}
			"deop" -> {
				val nick = parts.getOrNull(1) ?: return
				val chan = parts.getOrNull(2) ?: if (currentBuffer != "*server*" && isChannelName(currentBuffer)) currentBuffer else return
				sendRaw("MODE $chan -o $nick")
			}
			"voice" -> {
				val nick = parts.getOrNull(1) ?: return
				val chan = parts.getOrNull(2) ?: if (currentBuffer != "*server*" && isChannelName(currentBuffer)) currentBuffer else return
				sendRaw("MODE $chan +v $nick")
			}
			"devoice" -> {
				val nick = parts.getOrNull(1) ?: return
				val chan = parts.getOrNull(2) ?: if (currentBuffer != "*server*" && isChannelName(currentBuffer)) currentBuffer else return
				sendRaw("MODE $chan -v $nick")
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
            val msg = friendlyErrorMessage(t)
            send(IrcEvent.Error("Connect failed: $msg"))
            send(IrcEvent.Disconnected(msg))
            return@channelFlow
        }

        socket = s

        // If TLS is enabled put TLS session info in the server buffer.
        tlsInfo()?.takeIf { it.isNotBlank() }?.let { info ->
            send(IrcEvent.ServerText("*** TLS: $info"))
        }

        // Set up encoding-aware I/O using EncodingHelper
        val inputStream = s.getInputStream()
        val outputStream = s.getOutputStream()
        
        // Create line reader with encoding detection
        val lineReader = EncodingLineReader(inputStream, config.encoding)
        
        // Report encoding mode
        if (config.encoding.equals("auto", ignoreCase = true)) {
            send(IrcEvent.ServerText("*** Encoding: auto-detect (starting with UTF-8)"))
        } else {
            send(IrcEvent.ServerText("*** Encoding: ${config.encoding}"))
        }

        suspend fun writeLine(line: String) = withContext(Dispatchers.IO) {
            val bytes = EncodingHelper.encode(line, lineReader.encoding)
            outputStream.write(bytes)
            outputStream.write("\r\n".toByteArray(Charsets.US_ASCII))
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

        val pingJob = launch {
            // Wait a moment so the socket is fully established.
            delay(5_000)
            while (true) {
                // Adaptive ping interval: when the app is in the foreground (screen on, user
                // active) ping every 60 s for a responsive lag meter. When backgrounded, stretch
                // to 90 s — still well within the 2-5 min NAT timeout window — saving ~1/3 of
                // periodic radio wake-ups.
                val pingInterval = if (AppVisibility.isForeground) 60_000L else 90_000L
                delay(pingInterval)

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
                pendingLagPingSentAtMs = now
                if (runCatching { writeLine("PING :$token") }.isFailure && !userClosing) runCatching { s.close() }
            }
        }

        // Collect command events and forward to the flow
        launch {
            for (event in commandEvents) {
                send(event)
            }
        }

        val irc = IrcSession(config, rng)
        val historyRequested = mutableSetOf<String>()
        val historyExpectUntil = mutableMapOf<String, Long>()
        val openPlaybackBatches = mutableSetOf<String>()

        fun parseServerTimeMs(tags: Map<String, String?>): Long? {
            val raw = tags["time"] ?: return null
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
val numericHandlers: Map<String, suspend (IrcMessage, Long?, Boolean, Long) -> Unit> = mapOf(
    "001" to handler@{ msg, _, _, _ ->
        // Welcome: <me> ...
        val me = msg.params.getOrNull(0) ?: config.nick
        currentNick = me
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
            }
        }

        chantypes = chant
        caseMapping = cm
        statusMsg = sm
        chanModes = chm
        prefixModes = pm
        prefixSymbols = ps

        val mp = mutableMapOf<Char, Char>()
        val n = minOf(pm.length, ps.length)
        for (i in 0 until n) mp[pm[i]] = ps[i]
        if (mp.isNotEmpty()) prefixModeToSymbol = mp

        send(IrcEvent.ISupport(chantypes, caseMapping, prefixModes, prefixSymbols, statusMsg, chanModes))
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

    // Names list
    "353" to handler@{ msg, _, _, _ ->
        // RPL_NAMREPLY: <me> <symbol> <#chan> :[prefix]nick ...
        val chan = msg.params.getOrNull(2) ?: return@handler
        val names = (msg.trailing ?: "").split(Regex("\\s+")).filter { it.isNotBlank() }
        if (names.isNotEmpty()) send(IrcEvent.Names(chan, names))
    },
    "366" to handler@{ msg, _, _, _ ->
        // RPL_ENDOFNAMES: <me> <#chan> :End of /NAMES list.
        val chan = msg.params.getOrNull(1) ?: return@handler
        send(IrcEvent.NamesEnd(chan))
    },

    // ERR_NOTONCHANNEL
    "442" to handler@{ msg, _, _, _ ->
        // <me> <#chan> :You're not on that channel
        val chan = msg.params.getOrNull(1) ?: return@handler
        val reason = msg.trailing?.let { stripIrcFormatting(it) } ?: "You're not on that channel"
        send(IrcEvent.NotOnChannel(chan, reason, code = msg.command))
    },

    // Ban list
    "367" to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
        // RPL_BANLIST: <me> <#chan> <mask> [setBy] [setAt]
        val chan = msg.params.getOrNull(1) ?: return@handler
        val mask = msg.params.getOrNull(2) ?: return@handler
        val setBy = msg.params.getOrNull(3)
        val setAtMs = msg.params.getOrNull(4)?.toLongOrNull()?.let { it * 1000L }
        val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
        send(IrcEvent.BanListItem(chan, mask, setBy, setAtMs, timeMs = serverTimeMs, isHistory = hist))
    },
    "368" to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
        // RPL_ENDOFBANLIST: <me> <#chan> :End of Channel Ban List
        val chan = msg.params.getOrNull(1) ?: return@handler
        val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
        send(IrcEvent.BanListEnd(chan, code = msg.command, timeMs = serverTimeMs, isHistory = hist))
    },

    // Invite exception (+I) list
    "346" to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
        // RPL_INVEXLIST: <me> <#chan> <mask> [setBy] [setAt]
        val chan = msg.params.getOrNull(1) ?: return@handler
        val mask = msg.params.getOrNull(2) ?: return@handler
        val setBy = msg.params.getOrNull(3)
        val setAtMs = msg.params.getOrNull(4)?.toLongOrNull()?.let { it * 1000L }
        val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
        send(IrcEvent.InvexListItem(chan, mask, setBy, setAtMs, timeMs = serverTimeMs, isHistory = hist))
    },
    "347" to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
        // RPL_ENDOFINVEXLIST
        val chan = msg.params.getOrNull(1) ?: return@handler
        val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
        send(IrcEvent.InvexListEnd(chan, code = msg.command, timeMs = serverTimeMs, isHistory = hist))
    },

    // Ban exception (+e) list
    "348" to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
        // RPL_EXCEPTLIST: <me> <#chan> <mask> [setBy] [setAt]
        val chan = msg.params.getOrNull(1) ?: return@handler
        val mask = msg.params.getOrNull(2) ?: return@handler
        val setBy = msg.params.getOrNull(3)
        val setAtMs = msg.params.getOrNull(4)?.toLongOrNull()?.let { it * 1000L }
        val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
        send(IrcEvent.ExceptListItem(chan, mask, setBy, setAtMs, timeMs = serverTimeMs, isHistory = hist))
    },
    "349" to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
        // RPL_ENDOFEXCEPTLIST
        val chan = msg.params.getOrNull(1) ?: return@handler
        val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
        send(IrcEvent.ExceptListEnd(chan, code = msg.command, timeMs = serverTimeMs, isHistory = hist))
    },

    // Quiet (+q) list (common on InspIRCd/UnrealIRCd/Nefarious; ircu may not support)
    "728" to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
        // RPL_QUIETLIST: <me> <#chan> <mask> [setBy] [setAt]
        val chan = msg.params.getOrNull(1) ?: return@handler
        val mask = msg.params.getOrNull(2) ?: return@handler
        val setBy = msg.params.getOrNull(3)
        val setAtMs = msg.params.getOrNull(4)?.toLongOrNull()?.let { it * 1000L }
        val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
        send(IrcEvent.QuietListItem(chan, mask, setBy, setAtMs, timeMs = serverTimeMs, isHistory = hist))
    },
    "729" to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
        // RPL_ENDOFQUIETLIST
        val chan = msg.params.getOrNull(1) ?: return@handler
        val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
        send(IrcEvent.QuietListEnd(chan, code = msg.command, timeMs = serverTimeMs, isHistory = hist))
    },

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
)

		try {
			send(IrcEvent.Status("Negotiating capabilities…"))
			// Some servers expect PASS to precede any other registration-time commands.
			config.serverPassword?.takeIf { it.isNotBlank() }?.let { writeLine("PASS $it") }
			writeLine("CAP LS 302")
			writeLine("NICK ${config.nick}")
			writeLine("USER ${config.username} 0 * :${config.realname}")
			send(IrcEvent.Connected("${config.host}:${config.port}"))

			// Track if we've notified about encoding detection
			var encodingNotified = false

			while (true) {
				val prevEncoding = lineReader.encoding
				val line = withContext(Dispatchers.IO) { lineReader.readLine() } ?: break
				
				// Notify user if encoding was auto-detected and changed
				if (!encodingNotified && lineReader.hasDetectedNonUtf8() && prevEncoding != lineReader.encoding) {
					send(IrcEvent.ServerText("*** Detected encoding: ${lineReader.encoding}"))
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
					continue
				}

				val hsActions = irc.onMessage(msg)
				for (a in hsActions) when (a) {
					is IrcAction.Send -> writeLine(a.line)
					is IrcAction.EmitStatus -> send(IrcEvent.Status(a.text))
					is IrcAction.EmitError -> send(IrcEvent.Error(a.text))
				}

				// BATCH tracking (used for draft/chathistory and draft/event-playback).
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
					} else if (idToken.startsWith("-")) {
						val id = idToken.drop(1)
						openPlaybackBatches.remove(id)
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
					"324",
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
						"321","322","323",
						"324",
						"332","333",
						"353","366",
						"367","368",
						"346","347",
						"348","349",
						"728","729",
						"433",
						"471","472","473","474","475","476","477"
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

								when (ctcpCmd) {
									"VERSION" -> {
										writeLine("NOTICE $from :\u0001VERSION ${config.ctcpVersionReply}\u0001")
										send(IrcEvent.Status("CTCP VERSION reply sent to $from"))
										continue
									}
									"PING" -> {
										writeLine("NOTICE $from :\u0001PING $ctcpArgs\u0001")
										send(IrcEvent.Status("CTCP PING reply sent to $from"))
										continue
									}
									"TIME" -> {
										val timeStr = java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", java.util.Locale.US).format(java.util.Date())
										writeLine("NOTICE $from :\u0001TIME $timeStr\u0001")
										send(IrcEvent.Status("CTCP TIME reply sent to $from"))
										continue
									}
									"FINGER", "USERINFO" -> {
										writeLine("NOTICE $from :\u0001$ctcpCmd ${config.realname}\u0001")
										send(IrcEvent.Status("CTCP $ctcpCmd reply sent to $from"))
										continue
									}
									"CLIENTINFO" -> {
										writeLine("NOTICE $from :\u0001CLIENTINFO ACTION PING VERSION TIME FINGER USERINFO CLIENTINFO SOURCE DCC\u0001")
										send(IrcEvent.Status("CTCP CLIENTINFO reply sent to $from"))
										continue
									}
									"SOURCE" -> {
										writeLine("NOTICE $from :\u0001SOURCE https://hexdroid.boxlabs.uk/\u0001")
										send(IrcEvent.Status("CTCP SOURCE reply sent to $from"))
										continue
									}
									"ACTION" -> {
										// ACTION is handled below as a message
									}
									"DCC" -> {
										// DCC is handled below
									}
									else -> {
										// Unknown CTCP, log it but don't consume (might be custom)
										send(IrcEvent.Status("Unknown CTCP $ctcpCmd from $from"))
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
								isHistory = (playbackHistory || isHeuristicHistory(buf, serverTimeMs, nowMs))
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
								isHistory = (playbackHistory || isHeuristicHistory(histBuf, serverTimeMs, nowMs))
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

						// JOIN may include a comma-separated list (JOIN #a,#b). Emit one event per channel.
						val chans = chanRaw
							.split(',')
							.map { it.trim() }
							.filter { isChannelName(it) }
							.ifEmpty { listOf(chanRaw) }

						val userHost = msg.prefix?.substringAfter('!', missingDelimiterValue = "")
							?.takeIf { it.isNotBlank() }

						val hist = playbackHistory || isHeuristicHistory(null, serverTimeMs, nowMs) // Will override per-chan

						for (chan in chans) {
							val chanHist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
							send(
								IrcEvent.Joined(
									channel = chan,
									nick = nick,
									userHost = userHost,
									timeMs = serverTimeMs,
									isHistory = chanHist
								)
							)
							if (nickEquals(nick, currentNick) && !chanHist) {
								val fold = casefold(chan)
								joinedChannelCases[fold] = chan
							}

							// IRCv3 draft/chathistory: request recent messages when we (re)join.
							if (nickEquals(nick, currentNick)
								&& config.capPrefs.draftChathistory
								&& irc.hasCap("draft/chathistory")
								&& historyRequested.add(chan.lowercase())
							) {
								val lim = config.historyLimit.coerceIn(0, 500)
								if (lim > 0) {
									writeLine("CHATHISTORY LATEST $chan * $lim")
									historyExpectUntil[chan.lowercase()] = nowMs + 7_000L
								}
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
						send(IrcEvent.Topic(chan, topic, timeMs = serverTimeMs, isHistory = (playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs))))
					}


					"MODE" -> {
						val rawTarget = msg.params.getOrNull(0) ?: continue
						val target = normalizeMsgTarget(rawTarget)
						if (!isChannelName(target)) continue

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


				}
			}

			// If the user requested a disconnect, don't surface "EOF" as an error.
			send(IrcEvent.Disconnected(if (userClosing) (lastQuitReason ?: "Disconnected") else "EOF"))
		} catch (t: Throwable) {
			val msg = friendlyErrorMessage(t)
			if (userClosing) {
				send(IrcEvent.Disconnected(lastQuitReason ?: "Disconnected"))
			} else {
				send(IrcEvent.Error("Connection error: $msg"))
				send(IrcEvent.Disconnected(msg))
			}
		} finally {
			joinedChannelCases.clear()
			runCatching { writerJob.cancel() }
			runCatching { pingJob.cancel() }
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
		if (verb != "SEND" && verb != "TSEND") return null
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
		return DccOffer(from = "?", filename = filename, ip = ip, port = port, size = size, token = token, turbo = turbo)
	}

	private fun parseDccChat(textRaw: String): DccChatOffer? {
		// CTCP wrapper: \u0001DCC CHAT <proto> <ip> <port>\u0001
		if (!textRaw.startsWith("\u0001DCC ") || !textRaw.endsWith("\u0001")) return null

		val inner = textRaw.removePrefix("\u0001").removeSuffix("\u0001").trim()
		if (!inner.startsWith("DCC ", ignoreCase = true)) return null

		val afterDcc = inner.drop(3).trimStart() // remove "DCC"
		val verb = afterDcc.substringBefore(' ').uppercase(Locale.ROOT)
		if (verb != "CHAT") return null

		val rest = afterDcc.substringAfter(verb, "").trimStart()
		val parts = rest.split(Regex("\\s+")).filter { it.isNotBlank() }
		if (parts.size < 3) return null

		val proto = parts[0]
		val ipField = parts[1]
		val port = parts[2].toIntOrNull() ?: return null
		val ip = if (ipField.contains('.')) ipField else ipFromLong(ipField.toLongOrNull() ?: return null)

		return DccChatOffer(from = "?", protocol = proto, ip = ip, port = port)
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
			val sslContext = SSLContext.getInstance("TLS")
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
			sslContext.init(km, tm, SecureRandom())

			val raw = baseSocket().apply {
				connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMs)
			}

			val ss = sslContext.socketFactory.createSocket(raw, config.host, config.port, true) as SSLSocket
			val allowed = ss.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
			if (allowed.isNotEmpty()) ss.enabledProtocols = allowed.toTypedArray()

			// Apply a bounded soTimeout during startHandshake() so TLS negotiation cannot hang
			// forever. On some devices (MediaTek SoCs, certain MIUI/OneUI builds) BoringSSL
			// stalls mid-handshake and eventually surfaces SSL_ERROR_SYSCALL with errno 0
			// ("Success" / "I/O error during system call, Success") — or never returns at all
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
	 */
	private fun friendlyErrorMessage(t: Throwable): String {
		val raw = t.message ?: t::class.java.simpleName

		// SSL handshake failures (certificate problems, protocol mismatch)
		if (t is SSLHandshakeException) {
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
		if (t is SSLException) {
			return when {
				// BoringSSL/OpenSSL "Success" (errno=0): TCP FIN received without SSL close_notify.
				// Common on mobile when the radio silently drops the connection or when the
				// server closes TCP without a proper SSL shutdown.  Not an error the user can
				// action; connection will be re-established automatically.
				raw.contains(", Success", ignoreCase = false) ||
				raw.contains("I/O error during system call, Success", ignoreCase = true) ||
				raw.contains("Internal error in SSL library", ignoreCase = true) ||
				raw.contains("SSL_ERROR_INTERNAL", ignoreCase = true) ||
				raw.contains("Internal OpenSSL error", ignoreCase = true) ->
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

	private fun formatNumeric(msg: IrcMessage): String? {
		val code = msg.command
		if (code.length != 3 || !code.all { it.isDigit() }) return null
		
		// Don't double-emit for numerics that already have dedicated events.
		if (code in setOf("001","005","321","322","323","324","332","333","353","366","367","368","433","442","471","472","473","474","475","476","477")) return null

		fun p(i: Int) = msg.params.getOrNull(i)
		val t = msg.trailing?.let { stripIrcFormatting(it) }

		return when (code) {
			// MOTD
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
		// Returns list of (nick, prefixChar, adding)
		val results = mutableListOf<Triple<String, Char?, Boolean>>()
		var adding = true
		var argIdx = 0

		fun prefixForMode(c: Char): Char? = prefixModeToSymbol[c]

		for (c in modeStr) {
			when (c) {
				'+' -> adding = true
				'-' -> adding = false
				else -> {
					val prefix = prefixForMode(c) ?: continue
					val nick = args.getOrNull(argIdx) ?: continue
					argIdx += 1
					results.add(Triple(nick, prefix, adding))
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