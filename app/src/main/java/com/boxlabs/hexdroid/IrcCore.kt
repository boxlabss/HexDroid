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

import com.boxlabs.hexdroid.connection.ConnectionConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Socket
import java.security.SecureRandom
import java.time.Instant
import java.util.Locale
import javax.net.ssl.SSLSocket

/** Numerics refusing a PRIVMSG or NOTICE. The refused target is the second parameter. */
private val SEND_REFUSAL_CODES = setOf("401", "403", "404", "407", "477", "486", "489", "492", "531", "716")

/** Numerics answering a channel MODE query the server can't serve, naming the channel. */
private val MODE_QUERY_ERROR_CODES = setOf("401", "403", "442", "476", "479")

/** App-provided localized-string lookup: (resId, args) -> formatted string. */
typealias StringLookup = (Int, Array<out Any?>) -> String
/** Quantity-aware lookup, backed by Resources.getQuantityString. */
typealias PluralLookup = (Int, Int, Array<out Any?>) -> String

class IrcClient(val config: IrcConfig) {
    /**
     * Localized string lookup, set by the app layer after construction (backed by
     * Context.getString). Lets the protocol engine emit translated status text without
     * depending on Android. Null only before wiring; tr() falls back to empty then.
     */
    var strings: StringLookup? = null
    /** Resolve a localized string resource with optional format args. */
    internal fun tr(id: Int, vararg args: Any?): String = strings?.invoke(id, args) ?: ""
    /**
     * Quantity lookup, set alongside [strings]. Needed because languages with more
     * than two plural forms cannot be served by gluing a number onto a fixed noun.
     */
    var plurals: PluralLookup? = null
    /** Resolve a localized plural resource for [quantity], with optional format args. */
    internal fun trPlural(id: Int, quantity: Int, vararg args: Any?): String =
        plurals?.invoke(id, quantity, args) ?: ""
    /**
     * Raw protocol logging. Off by default and flipped at runtime by the user, so the
     * hot path costs one volatile read per line when it is off.
     */
    @Volatile var rawLogEnabled = false

    /** Try to switch back to the configured nick after registering on a fallback. Applied live. */
    @Volatile var nickRegainEnabled = true

    /** Latched when the server refuses nick changes outright (Ergo strict nick-reservation). */
    @Volatile internal var nickChangeRefused = false
    /** A channel MODE query on the wire, and whether the UI sent it. */
    private class ModeQuery(val chanFold: String, val silent: Boolean)

    /** Channel MODE queries in write order. A reply ends the oldest query for its channel. Guarded by itself. */
    private val modeQueries = ArrayDeque<ModeQuery>()

    /** Casefolded channel whose 324 was the last line read, while its 329 may still follow. Read loop only. */
    private var modeReplyOpen: String? = null

    /**
     * Ask for [channel]'s modes for the UI, in the background lane. The reply (324 and 329) updates
     * state without being printed.
     */
    suspend fun querySilentModes(channel: String) {
        enqueue(OutboundUnit(listOf("MODE $channel"), background = true, silentModeQuery = true))
    }

    /** Records a channel MODE query about to be written, so its reply can be told apart. */
    private fun noteModeQuery(line: String, silent: Boolean) {
        val start = if (line.startsWith("@")) line.indexOf(' ') + 1 else 0
        if (!line.regionMatches(start, "MODE ", 0, 5, ignoreCase = true)) return
        val words = line.split(' ').filter { it.isNotEmpty() }
        val parts = if (words.firstOrNull()?.startsWith("@") == true) words.drop(1) else words
        if (parts.size != 2 || !parts[0].equals("MODE", ignoreCase = true) || !isChannelName(parts[1])) return
        synchronized(modeQueries) {
            if (modeQueries.size >= MAX_PENDING_MODE_QUERIES) modeQueries.removeFirst()
            modeQueries.addLast(ModeQuery(casefold(parts[1]), silent))
        }
    }

    /** True when the 324 or 329 for [channel] answers a query the UI sent. */
    internal fun isSilentModeReply(channel: String): Boolean = synchronized(modeQueries) {
        val fold = casefold(channel)
        modeQueries.firstOrNull { it.chanFold == fold }?.silent == true
    }

    /** Ends the oldest query for the casefolded channel [fold]. */
    private fun finishModeQuery(fold: String) = synchronized(modeQueries) {
        val idx = modeQueries.indexOfFirst { it.chanFold == fold }
        if (idx >= 0) modeQueries.removeAt(idx)
    }

    /**
     * Ends a MODE reply the server finished without a 329: a 324 is followed by its 329 or by
     * nothing of the reply at all, so any other line after a 324 ends that query.
     */
    private fun closeModeReplyBefore(msg: IrcMessage) {
        val open = modeReplyOpen ?: return
        if (msg.command == "PING" || msg.command == "PONG") return
        if (msg.command == "329" && msg.allParams.getOrNull(1)?.let { casefold(it) } == open) return
        finishModeQuery(open)
        modeReplyOpen = null
    }

    /** Keys sent with outgoing JOINs, by casefolded channel, until the server confirms the join. */
    internal val joinKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Records the key for each keyed channel in an outgoing `JOIN <chans> [<keys>]`. */
    private fun rememberJoinKeys(line: String) {
        if (!line.startsWith("JOIN ", ignoreCase = true)) return
        val args = line.substring(5).trim().split(' ').filter { it.isNotEmpty() }
        val chans = args.getOrNull(0)?.split(',') ?: return
        val keys = args.getOrNull(1)?.split(',') ?: return
        chans.forEachIndexed { i, chan ->
            val key = keys.getOrNull(i)?.trim()
            if (chan.isNotBlank() && !key.isNullOrEmpty()) joinKeys[casefold(chan.trim())] = key
        }
    }

    /** One WHO on the wire. [buffer] is where replies are printed; null for the client's own queries. */
    internal class WhoRequest(val maskFold: String, val buffer: String?, val forChannel: Boolean)

    /** WHO requests in the order they were written, so replies can be matched to them. Guarded by itself. */
    private val whoRequests = ArrayDeque<WhoRequest>()

    /** A WHO for [mask] whose replies print in [buffer], or are handled silently with null. */
    internal fun whoFor(mask: String, buffer: String?) = WhoRequest(casefold(mask), buffer, isChannelName(mask))

    /** Records a WHO about to be written. */
    private fun trackWho(req: WhoRequest) {
        synchronized(whoRequests) {
            if (whoRequests.size >= MAX_PENDING_WHO) whoRequests.removeFirst()
            whoRequests.addLast(req)
        }
    }

    /**
     * The WHO a 352/354 reply belongs to: the oldest outstanding one that could have produced it.
     * A WHO for a channel only answers with that [channel]; any other WHO can name any channel.
     */
    internal fun currentWho(channel: String? = null): WhoRequest? = synchronized(whoRequests) {
        val fold = channel?.let { casefold(it) }
        whoRequests.firstOrNull { fold == null || !it.forChannel || it.maskFold == fold }
    }

    /** Completes the WHO for [mask], also dropping any earlier request the server never ended. */
    internal fun finishWho(mask: String): WhoRequest? = synchronized(whoRequests) {
        val idx = whoRequests.indexOfFirst { it.maskFold == casefold(mask) }
        if (idx < 0) return@synchronized whoRequests.removeFirstOrNull()
        repeat(idx) { whoRequests.removeFirst() }
        whoRequests.removeFirst()
    }

    private val parser = IrcParser()
    /**
     * One unit of outbound traffic, written back to back with nothing interleaved. A multiline
     * BATCH is one unit, since any other command inside it makes the server fail the batch. [paced]
     * false skips the flood delay, as for multiline batches, which servers exempt from their own
     * flood penalty.
     */
    private data class OutboundUnit(
        val lines: List<String>,
        val paced: Boolean = true,
        /** Sent only when nothing the user typed is waiting: automatic queries and refreshes. */
        val background: Boolean = false,
        /** A MODE query the UI makes, whose reply isn't printed. */
        val silentModeQuery: Boolean = false,
        /** The WHO these lines send, recorded as they are written. */
        val who: WhoRequest? = null,
        /** Messages these lines send that the server will echo, recorded as they are written. */
        val sends: List<SendTrack> = emptyList(),
    )

    /** Outbound queue for what the user sends, and everything else not marked background. */
    private val outbound = Channel<OutboundUnit>(capacity = 300)

    /** Outbound queue for automatic queries; drained only when [outbound] is empty. */
    private val outboundBackground = Channel<OutboundUnit>(capacity = 300)

    /**
     * Drop queued outbound lines matching [pred] now, e.g. a closed game's flood backlog. Drains
     * the queue without suspending and re-enqueues the rest; a concurrent write may still send one
     * matching line or slightly reorder the survivors.
     */
    fun purgeOutbound(pred: (String) -> Boolean) {
        for (lane in listOf(outbound, outboundBackground)) {
            val survivors = ArrayList<OutboundUnit>()
            while (true) {
                val unit = lane.tryReceive().getOrNull() ?: break
                // Drop a unit only if every line in it matches; a batch is all-or-nothing,
                // since a half-dropped batch is exactly the corruption this queue prevents.
                if (!unit.lines.all(pred)) survivors.add(unit)
            }
            for (unit in survivors) lane.trySend(unit)
        }
    }

    /**
     * The writer's flood pacing, replayed as typed lines are queued, so a send can tell when it goes
     * out. Background units are left out: they wait for typed lines, not the other way round.
     */
    private val queuedPaceLock = Any()
    private var queuedFloodClock = 0L
    @Volatile private var queuedDueAtMs = 0L

    private fun noteQueued(unit: OutboundUnit) {
        if (unit.background) return
        val now = System.currentTimeMillis()
        synchronized(queuedPaceLock) {
            if (!registered || config.isBouncer || safeRate) {
                queuedDueAtMs = maxOf(queuedDueAtMs, now)
                return
            }
            if (queuedFloodClock < now) queuedFloodClock = now
            queuedFloodClock += if (unit.paced) {
                floodPenaltyMs * unit.lines.size + unit.lines.sumOf { it.length } * floodPerCharMs
            } else {
                floodPenaltyMs
            }
            val earliest = if (unit.paced) queuedFloodClock - floodBurstMs else now
            queuedDueAtMs = maxOf(queuedDueAtMs, now, earliest)
        }
    }

    /**
     * How long until the last queued typed line is written, as flood pacing holds it back. A line
     * queued just before this call goes out about this far from now.
     */
    fun sendBacklogMs(): Long = (queuedDueAtMs - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * How long typed lines of [lineLengths] characters would take to go out from now under flood
     * pacing, after anything already queued. Zero where pacing is off.
     */
    fun pacedSendEstimateMs(lineLengths: List<Int>): Long {
        if (!registered || config.isBouncer || safeRate) return 0L
        val now = System.currentTimeMillis()
        synchronized(queuedPaceLock) {
            var clock = maxOf(queuedFloodClock, now)
            for (len in lineLengths) clock += floodPenaltyMs + len * floodPerCharMs
            return (clock - floodBurstMs - now).coerceAtLeast(0L)
        }
    }

    // Outbound flood pacing (applied in the writer loop). IRC servers charge a per-line penalty of
    // roughly 2s + length/120s and cut you for "excess flood" once you get ~10s ahead of real time.
    private val floodPenaltyMs = 2_200L      // base cost charged per outbound line
    private val floodPerCharMs = 8L          // plus ~length/120s, about 8ms per character
    private val floodBurstMs = 8_000L        // how far ahead of real time we may burst before pacing
    private val rng = SecureRandom()

    /**
     * End-to-end encryption codec, set by the ViewModel: encrypts outgoing PRIVMSG/NOTICE/ACTION
     * text per target and decrypts incoming text. Null when no keys are configured on this network.
     */
    @Volatile var e2eCodec: com.boxlabs.hexdroid.crypto.E2eCodec? = null
    /** Set by the VM: returns true when the user has manually turned on +AGE for [target].
     *  Used by [privmsg] to fail closed so a +AGE buffer never ships plaintext (see guard there). */
    @Volatile var ageEnabledForTarget: ((target: String) -> Boolean)? = null

    companion object {
        /** Pre-allocated CRLF terminator reused on every line send to avoid a ByteArray allocation per write. */
        private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)

        /**
         * How many open batches to track at once, bounding a server that never closes them.
         * Eviction is by oldest, so tracking keeps working rather than shutting off.
         */
        internal const val MAX_TRACKED_BATCHES = 64
        /** Outstanding WHOIS routes kept before the oldest is dropped. */
        private const val MAX_PENDING_WHOIS = 50
        /** Nicks whose user, host and account are remembered for building bans. */
        private const val MAX_KNOWN_USERS = 4000
        /** Raw lines kept for a debug report. */
        private const val RECENT_RAW_LINES = 300
        /** Outstanding WHO requests kept before the oldest is dropped. */
        private const val MAX_PENDING_WHO = 50
        /** Outstanding channel MODE queries kept before the oldest is dropped. */
        private const val MAX_PENDING_MODE_QUERIES = 64
        /** Sends awaiting their echo before the oldest is dropped; the outbound queue holds 300. */
        private const val MAX_AWAITING_ECHOES = 300
        /** Send ids, unique across connections so a reconnect never reuses one still awaited. */
        private val sendIdCounter = java.util.concurrent.atomic.AtomicLong()

        /**
         * CHATHISTORY selector timestamp format. See [historyTimestamp].
         */
        private val HISTORY_TS_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var lastQuitReason: String? = null
    /**
     * Cause that goes with [lastQuitReason]. Set at every site that sets the reason, so
     * the post-loop Disconnected can report why we closed rather than guessing from text.
     */
    @Volatile private var lastQuitCode: DisconnectCode = DisconnectCode.USER_QUIT
    private var triedAltNick = false
    /** The last nick sent from the nick-in-use fallback below, before registration. */
    private var lastNickTried: String? = null
    /** NICKLEN learned from a server cutting a nick we sent, before 005 can say. */
    private var observedNickLen: Int? = null
    // True once 001 (RPL_WELCOME) is received. After registration, 433 during pre-reg
    // IRCd's like Ergo sends the correct nick via 001 after SASL completes, so any
    // queued 433 responses for nicks tried before SASL finished should be ignored.
    internal var registered = false

    // Tracks where a WHOIS was invoked from so we can route the numeric replies back
    // to that buffer (instead of always dumping them in the server buffer). Insertion
    // ordered, so [rememberWhoisBuffer] can drop the oldest entry when it fills.
    private val pendingWhoisBufferByNick = LinkedHashMap<String, String>()

    /**
     * Route the replies of a WHOIS for [fold] back to [buffer]. Removed when the reply ends (318,
     * 401, 406); at the cap the oldest entry is evicted.
     */
    internal fun rememberWhoisBuffer(fold: String, buffer: String) = synchronized(pendingWhoisBufferByNick) {
        // Re-inserted so a repeat WHOIS counts as the newest entry.
        pendingWhoisBufferByNick.remove(fold)
        if (pendingWhoisBufferByNick.size >= MAX_PENDING_WHOIS) {
            pendingWhoisBufferByNick.remove(pendingWhoisBufferByNick.keys.first())
        }
        pendingWhoisBufferByNick[fold] = buffer
    }

    @Volatile internal var currentNick: String = config.nick

    // Lag measurement (client PING -> server PONG RTT)
    @Volatile private var pendingLagPingToken: String? = null
    @Volatile private var pendingLagPingSentAtMs: Long? = null
    @Volatile private var lastLagMs: Long? = null

    // Time of the last line received from the server, of ANY kind.
    // The stall detector keys off this rather than off our lag-ping token being echoed,
    // because older RFC-1459 daemons reply to a client PING without mirroring the token
    @Volatile private var lastInboundAtMs: Long = 0L

    // ISUPPORT-derived server features (defaults are RFC1459-ish)
    @Volatile internal var chantypes: String = "#&"
    @Volatile internal var caseMapping: String = "rfc1459"
    @Volatile internal var statusMsg: String? = null
    @Volatile internal var chanModes: String? = null
    @Volatile internal var prefixModes: String = "qaohv"
    @Volatile internal var prefixSymbols: String = "~&@%+"
    @Volatile internal var prefixModeToSymbol: Map<Char, Char> = mapOf(
        'q' to '~', 'a' to '&', 'o' to '@', 'h' to '%', 'v' to '+'
    )
    /** True when the server advertises WHOX in ISUPPORT (005). */
    @Volatile internal var whoxSupported: Boolean = false

    /**
     * ELIST=<chars> from ISUPPORT (005): which server-side LIST search extensions the server
     * supports. Each letter is a capability, most relevantly 'U' (filter by user count, e.g.
     * "LIST >50"), plus C/N/T/M (creation time / non-match mask / topic age / mask). Null until
     * 005 is seen. Stored uppercase. Used so the channel-list UI only offers server-side
     * filtering the server can actually honour, instead of having it silently ignored.
     */
    @Volatile internal var elistTokens: String? = null

    /**
     * MONITOR=<n> from ISUPPORT (005): maximum entries the server allows in this client's
     * watch list. Int.MAX_VALUE means "advertised with no value" = no limit. -1 (the
     * pre-005 default) means MONITOR support hasn't been confirmed. The /monitor dispatcher
     * uses this to surface a clear message in the server buffer when MONITOR is unsupported.
     */
    @Volatile internal var monitorLimit: Int = -1

    /**
     * CLIENTTAGDENY from ISUPPORT: client-only tags this server won't relay. Null = no restriction.
     * Comma-separated; "*" denies all, "-name" re-allows one. Interpreted by [clientTagAllowed].
     */
    @Volatile internal var clientTagDeny: String? = null

    /**
     * soju.im/FILEHOST (or draft/FILEHOST) from ISUPPORT: HTTP endpoint for file
     * uploads, advertised by soju, Ergo (additional-isupport), and standalone
     * filehost servers. Null when the server offers none.
     */
    @Volatile internal var filehostUrl: String? = null

    /** ICON / draft/ICON ISUPPORT token: server-supplied icon URL. Null = none. */
    @Volatile internal var networkIconUrl: String? = null

    /** BOT ISUPPORT token: the user-mode letter that flags a bot (e.g. 'B'). Null = unset. */
    @Volatile internal var botModeChar: Char? = null

    /** BOUNCER_NETID ISUPPORT token: the soju network this connection is bound to. Null = unbound. */
    @Volatile internal var bouncerNetId: String? = null

    /**
     * soju.im/SAFERATE ISUPPORT token: the server delays a fast client rather than disconnecting
     * it, so our own flood pacing is off.
     */
    @Volatile internal var safeRate: Boolean = false

    /** What we last saw of a nick: ident, host and services account. */
    private data class KnownUser(val user: String? = null, val host: String? = null, val account: String? = null)

    /**
     * Last known user, host and account per casefolded nick, from message prefixes, WHO replies,
     * CHGHOST and account tags, so a ban can be built without a WHOIS. Least recently used entries
     * are dropped past [MAX_KNOWN_USERS].
     */
    private val knownUsers = object : LinkedHashMap<String, KnownUser>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KnownUser>?) =
            size > MAX_KNOWN_USERS
    }

    /** Record what we know of [nick]. Null leaves a field unchanged; an [account] of "*" or "0" clears it. */
    internal fun noteUser(nick: String, user: String? = null, host: String? = null, account: String? = null) {
        if (nick.isBlank()) return
        val key = casefold(nick)
        synchronized(knownUsers) {
            val old = knownUsers[key] ?: KnownUser()
            knownUsers[key] = KnownUser(
                user = user?.takeIf { it.isNotBlank() } ?: old.user,
                host = host?.takeIf { it.isNotBlank() } ?: old.host,
                account = if (account == null) old.account else account.takeIf { it.isNotBlank() && it != "*" && it != "0" },
            )
        }
    }

    private fun knownUser(nick: String): KnownUser? = synchronized(knownUsers) { knownUsers[casefold(nick)] }

    /** The last seen `user@host` of [nick], or null when either part is unknown. */
    fun userHostOf(nick: String): String? {
        val k = knownUser(nick) ?: return null
        val user = k.user ?: return null
        val host = k.host ?: return null
        return "$user@$host"
    }

    /**
     * Asked before answering a CTCP request; true leaves it unanswered. Given the sender's nick
     * and `user@host` when known.
     */
    @Volatile var ctcpIgnored: ((nick: String, userHost: String?) -> Boolean)? = null

    /** Record the sender of [msg] from its `nick!user@host` prefix and account tag; a NICK carries over. */
    private fun noteSender(msg: IrcMessage) {
        val prefix = msg.prefix ?: return
        val bang = prefix.indexOf('!')
        val at = prefix.indexOf('@', bang + 1)
        if (bang <= 0 || at <= bang + 1 || at >= prefix.length - 1) return
        val nick = prefix.substring(0, bang)
        val user = prefix.substring(bang + 1, at)
        val host = prefix.substring(at + 1)
        noteUser(nick, user, host, msg.tags["account"])
        if (msg.command == "NICK") {
            val newNick = msg.params.getOrNull(0) ?: msg.trailing
            if (!newNick.isNullOrBlank()) {
                val known = knownUser(nick)
                noteUser(newNick, user, host, known?.account ?: "*")
            }
        }
    }

    /**
     * The account ban for [account] in the server's syntax (account-extban): EXTBAN prefix plus
     * the ACCOUNTEXTBAN name, as in `$R:bob` or `~account:bob`. Without ACCOUNTEXTBAN, the `a`
     * extban when EXTBAN lists it, else the common `$a:` form.
     */
    private fun accountBanMask(account: String): String {
        val prefix = extbanPrefix
        val name = accountExtban
        return when {
            prefix != null && name != null -> "$prefix$name:$account"
            prefix != null && extbanTypes?.contains('a') == true -> "${prefix}a:$account"
            else -> "\$a:$account"
        }
    }

    /** EXTBAN prefix (e.g. "~", or "" for no prefix). Null when the server has no EXTBAN token. */
    @Volatile internal var extbanPrefix: String? = null
    /** EXTBAN type letters. Null when unsupported. */
    @Volatile internal var extbanTypes: String? = null
    /** draft/account-extban letter (from ACCOUNTEXTBAN=<name>,<letter>). Null when unsupported. */
    @Volatile internal var accountExtban: String? = null

    /** CHATHISTORY ISUPPORT token: max messages the server returns per request. 0 = unset. */
    @Volatile internal var chatHistoryLimit: Int = 0

    /** Set when this network's own certificate couldn't be made or read, so none was presented. */
    @Volatile internal var netCertUnavailable = false

    /** Set when this connection created the network's certificate, which is announced once. */
    @Volatile internal var netCertCreated = false

    /** UTF8ONLY ISUPPORT token: the server only accepts UTF-8. */
    @Volatile internal var utf8OnlyServer: Boolean = false

    /**
     * VAPID ISUPPORT token (draft/webpush): the server's application-server public key,
     * URL-safe base64 of an uncompressed P-256 point. Handed to the UnifiedPush
     * distributor so it can reject push messages that are not signed by this server.
     * Null when the server offers no VAPID key.
     */
    @Volatile internal var vapidPublicKey: String? = null

    /**
     * Set when this 005 line introduced a VAPID key, so the event can be emitted after the
     * whole line is parsed rather than mid-token.
     */
    internal var vapidBecameReady: Boolean = false

    /**
     * MSGREFTYPES ISUPPORT token: the message-reference types the server accepts in
     * CHATHISTORY selectors (e.g. "timestamp", "msgid"). Empty = token absent, in which
     * case both are assumed per the spec default.
     */
    @Volatile internal var msgRefTypes: List<String> = emptyList()

    /**
     * The selector type used by the most recent CHATHISTORY request.
     *
     * A server that rejects one with INVALID_MSGREFTYPE is telling us it does not accept
     * that type, so it is dropped and the next request falls to the other.
     */
    @Volatile internal var lastHistoryRefType: String? = null

    /**
     * Reference types the server has rejected with INVALID_MSGREFTYPE.
     *
     * Held apart from [msgRefTypes], where an empty list is the sentinel for "the token was
     * absent, assume both are accepted": removing the last entry there would read as a
     * server that never restricted anything.
     */
    @Volatile internal var refusedRefTypes: Set<String> = emptySet()

    /** Clamp a desired CHATHISTORY count to the server's advertised limit, if any. */
    /** Max messages per CHATHISTORY request: the ISUPPORT token, else the capability's value. 0 = unknown. */
    private fun historyPageLimit(): Int {
        if (chatHistoryLimit > 0) return chatHistoryLimit
        val fromCap = capValue("draft/chathistory") ?: capValue("chathistory")
        return fromCap?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 0
    }

    internal fun clampHistoryLimit(requested: Int): Int {
        val max = historyPageLimit()
        return if (max in 1 until requested) max else requested
    }

    /** True when timestamp= selectors are usable (token absent means "assume yes"). */
    private fun historyTimestampOk(): Boolean =
        "timestamp" !in refusedRefTypes && (msgRefTypes.isEmpty() || "timestamp" in msgRefTypes)

    /** True when msgid= selectors are usable (token absent means "assume yes"). */
    private fun historyMsgidOk(): Boolean =
        "msgid" !in refusedRefTypes && (msgRefTypes.isEmpty() || "msgid" in msgRefTypes)

    /**
     * Whether a CHATHISTORY selector on this server may carry a timestamp. False means an
     * anchor must be a message the server itself sent, since only those have a msgid.
     */
    fun acceptsHistoryTimestamps(): Boolean = historyTimestampOk()

    /**
     * Format [instant] as a CHATHISTORY timestamp: `YYYY-MM-DDThh:mm:ss.sssZ` with the milliseconds
     * always present, which `Instant.toString()` drops when they are zero.
     */
    internal fun historyTimestamp(instant: java.time.Instant): String =
        HISTORY_TS_FORMAT.format(instant)

    /** Lower bound for a CHATHISTORY range that means "everything the server holds". */
    private fun historyEpochTimestamp(): String = historyTimestamp(java.time.Instant.EPOCH)

    /**
     * Server-advertised length limits from ISUPPORT (TOPICLEN, KICKLEN, AWAYLEN,
     * QUITLEN, NICKLEN, MAXNICKLEN, CHANNELLEN, NAMELEN). Absent key = no known limit.
     */
    internal val lengthLimits = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The advertised limit for [key] (uppercased ISUPPORT token), or 0 when unset. */
    fun isupportLengthLimit(key: String): Int = lengthLimits[key.uppercase()] ?: 0

    /**
     * Truncate free-text command payloads (topic, away, quit, kick reasons) to the
     * server's advertised limit so the local view matches what the server will store,
     * instead of letting the server silently cut it. A no-op when the limit is unset.
     */
    internal fun clampLen(text: String, key: String): String {
        val lim = lengthLimits[key] ?: 0
        return if (lim in 1 until text.length) text.take(lim) else text
    }

    /** KNOCK ISUPPORT token: server supports the KNOCK command for invite-only channels. */
    @Volatile internal var knockSupported = false

    /**
     * CHANLIMIT ISUPPORT token: max channels joinable per channel-type prefix
     * (e.g. "#:20"). Keyed by prefix char. Absent = no known limit.
     */
    internal val chanLimits = java.util.concurrent.ConcurrentHashMap<Char, Int>()

    /** Advertised channel-join limit for [chan]'s type prefix, or 0 when unknown. */
    internal fun channelLimitFor(chan: String): Int =
        chan.firstOrNull()?.let { chanLimits[it] } ?: 0

    /**
     * draft/account-registration: account name from the last REGISTER
     * VERIFICATION_REQUIRED response, so a bare `/verify <code>` targets the right
     * account without the user retyping it. Cleared on SUCCESS and per session.
     */
    @Volatile internal var pendingVerifyAccount: String? = null

    /**
     * True if [tag] may be sent as a client-only tag. Pass the bare name ("typing", not "+typing"):
     * the sigil is a marker, not part of the name, and CLIENTTAGDENY spells tags without it.
     *
     * Advisory per the spec, so only consulted for TAGMSG, whose sole tag being stripped leaves an
     * empty message. PRIVMSG keeps its text and is never gated on this.
     */
    private fun clientTagAllowed(tag: String): Boolean {
        val policy = clientTagDeny ?: return true
        var allowed = true
        for (entry in policy.split(',')) {
            val e = entry.trim()
            if (e.isEmpty()) continue
            when {
                e == "*" -> allowed = false
                e.startsWith("-") -> if (e.drop(1) == tag) allowed = true
                e == tag -> allowed = false
            }
        }
        return allowed
    }
    /**
     * LINELEN from ISUPPORT 005: maximum bytes per IRC line including trailing CRLF.
     * RFC 1459 = 512; Ergo / InspIRCd often advertise 4096.
     * Null until the server sends 005 — callers should treat null as 512.
     */
    @Volatile var serverLinelen: Int? = null
        internal set

    // Track joined channels (original case preserved, keyed by casefold)
    internal val joinedChannelCases = mutableMapOf<String, String>()

    // Channel for emitting events from commands (merged into events() flow)
    internal val commandEvents = Channel<IrcEvent>(capacity = Channel.UNLIMITED)  // Or adjust capacity

    /**
     * Reference to the active IrcSession so that command handlers (handleSlashCommand, etc.)
     * can query negotiated capabilities without being inside the events() channelFlow scope.
     * Written on the IO thread when the session is created; read from any coroutine.
     */
    @Volatile internal var sessionRef: IrcSession? = null

    /** Returns true if the given IRCv3 capability was successfully negotiated with the server. */
    fun hasCap(cap: String): Boolean = sessionRef?.hasCap(cap) == true

    /** The capabilities enabled on this connection, sorted. */
    fun enabledCaps(): List<String> = sessionRef?.enabledCapsList().orEmpty()

    /**
     * The most recent raw lines both ways, as (time, outgoing, line), kept for debug reports.
     * Outgoing lines are stored with passwords already hidden.
     */
    private val recentRaw = ArrayDeque<Triple<Long, Boolean, String>>()

    private fun noteRecentRaw(outgoing: Boolean, line: String) {
        synchronized(recentRaw) {
            recentRaw.addLast(Triple(System.currentTimeMillis(), outgoing, line))
            while (recentRaw.size > RECENT_RAW_LINES) recentRaw.removeFirst()
        }
    }

    /** Up to [max] of the most recent raw lines, oldest first, with passwords hidden. */
    fun recentRawLines(max: Int): List<Triple<Long, Boolean, String>> {
        val snapshot = synchronized(recentRaw) { recentRaw.toList() }
        return snapshot.takeLast(max.coerceAtLeast(0)).map { (t, out, line) -> Triple(t, out, if (out) line else redactRawLine(line)) }
    }

    /** Raw value of a capability from CAP LS/NEW (e.g. draft/account-registration flags). */
    fun capValue(cap: String): String? = sessionRef?.capValue(cap)

    /** True if either the graduated or draft chathistory cap is enabled. */
    internal fun hasChathistoryCap(): Boolean = hasCap("chathistory") || hasCap("draft/chathistory")

    /** True if either the soju.im/read or draft/read-marker cap is enabled. */
    internal fun hasReadMarkerCap(): Boolean = hasCap("draft/read-marker") || hasCap("soju.im/read")

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
    // CTCP flood protection: last reply time per nick, so we answer at most once per
    // CTCP_RATE_LIMIT_MS per sender and cannot be flooded into a K-line.
    internal val ctcpLastReplyMs = mutableMapOf<String, Long>()
    internal val CTCP_RATE_LIMIT_MS = 5_000L

    /**
     * Drop reply times that are past the rate-limit window, so a flood from rotating nicks
     * cannot grow the map for the life of the connection.
     */
    internal fun pruneCtcpReplyTimes(now: Long) {
        if (ctcpLastReplyMs.size < 64) return
        ctcpLastReplyMs.entries.removeAll { now - it.value >= CTCP_RATE_LIMIT_MS }
    }

    /**
     * MONITOR list entries (732) collected until 733 ends the list, then printed as one line. Reset
     * on each 733.
     */
    internal val monitorListBuffer = mutableListOf<String>()
    internal fun nextLabel(): String = "h${labelCounter.incrementAndGet()}"

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
	internal fun normalizeMsgTarget(target: String): String {
		val t = target.trim()
		val sm = statusMsg
		return if (sm != null && t.length >= 2 && sm.indexOf(t[0]) >= 0 && chantypes.indexOf(t[1]) >= 0) {
			t.substring(1)
		} else {
			t
		}
	}

    internal fun isChannelName(name: String): Boolean =
        name.isNotEmpty() && chantypes.contains(name[0])

    /**
     * True if [nick] is a bouncer pseudo-user whose messages go to the server buffer instead of a
     * query: any nick starting with `*` (ZNC modules; no real user can hold such a nick) or soju's
     * `BouncerServ`. Used by both the PRIVMSG and NOTICE handlers, since ZNC's `*status` replies by
     * NOTICE.
     */
    internal fun isBouncerPseudoUser(nick: String): Boolean {
        if (nick.isEmpty()) return false
        if (nick.startsWith("*")) return true
        return nick.equals("BouncerServ", ignoreCase = true)
    }

    /**
     * Mask type for /ban, /kickban and /mute, chosen by a keyword after the nick: HOST `*!*@host`
     * (default), NICK `nick!*@*`, USER `*!user@*`, DOMAIN `*!*@*.domain`, ACCOUNT (the server's
     * account extban, see [accountBanMask]), RAW for a mask typed as-is.
     */
    internal enum class BanMaskType { NICK, USER, HOST, DOMAIN, ACCOUNT, RAW }

    /**
     * Parse an optional mask-type keyword: `n|nick`, `u|user|ident`, `h|host`, `d|domain`,
     * `a|acct|account`. Null when not recognised; callers then use [BanMaskType.HOST].
     */
    internal fun parseMaskType(kw: String?): BanMaskType? = when (kw?.lowercase()) {
        null, "" -> null
        "n", "nick" -> BanMaskType.NICK
        "u", "user", "ident" -> BanMaskType.USER
        "h", "host" -> BanMaskType.HOST
        "d", "domain" -> BanMaskType.DOMAIN
        "a", "acct", "account" -> BanMaskType.ACCOUNT
        else -> null
    }

    /** True if [s] already looks like a ban mask rather than a plain nick. */
    internal fun looksLikeRawMask(s: String): Boolean =
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
     * A ban waiting for WHOIS to supply the host or account. [quiet] uses +q; [alsoKick] kicks with
     * [kickReason] after the mode; [queuedAtMs] ages out stale entries.
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
     * Bans waiting on WHOIS, by casefolded nick; several can queue for one nick and all are applied
     * when the reply arrives. Entries older than [PENDING_BAN_TIMEOUT_MS] are dropped.
     */
    private val pendingBansByNick = mutableMapOf<String, MutableList<PendingBan>>()

    /**
     * Bridge between 311 (RPL_WHOISUSER) and 330 (RPL_WHOISACCOUNT) for ACCOUNT-type
     * pending bans: 311 carries user/host, 330 carries the services account name, and
     * we need both pieces in [completePendingBans] to either build the `$a:account`
     * mask or fall back gracefully. Cleared on 330 or 318.
     */
    private val pendingWhoisHostByNick = mutableMapOf<String, Pair<String, String>>()

    // ── Read-loop state ──────────────────────────────────────────────────
    // State shared by the events() read loop and its dispatch methods. One IrcClient runs one
    // events() call, so this lives as long as the connection.

    /**
     * Channels we've requested CHATHISTORY for in this session, to avoid re-fetching on
     * rejoin. Keyed by [casefold]
     */
    internal val historyRequested = mutableSetOf<String>()

    /**
     * Channels we've explicitly requested NAMES for in this session (soju.im/no-implicit-names
     * / draft/no-implicit-names), to avoid re-fetching on rejoin. Keyed by [casefold].
     */
    internal val namesRequested = mutableSetOf<String>()

    /**
     * A PRIVMSG or NOTICE, or one multiline batch, sent with echo-message and awaiting the
     * server's answer: its echo, a refusal, or another reply.
     */
    internal class SendTrack(
        val id: Long,
        val notice: Boolean,
        /** The target as written. */
        val target: String,
        val targetFold: String,
        /** The text as written, compared formatting- and whitespace-insensitively when there is no label. */
        val text: String,
        val label: String?,
        /** The echo is dropped here: /msg, /notice and CTCP replies, which are printed when sent. */
        val suppress: Boolean,
        val multiline: Boolean,
    )

    /** Sends awaiting their answer, in write order. Guarded by itself. */
    private val awaitingEchoes = ArrayDeque<SendTrack>()

    /** A track for a message about to be queued, or null without echo-message, when no answer is coming. */
    private fun newSendTrack(
        notice: Boolean,
        target: String,
        text: String,
        label: String?,
        suppress: Boolean = false,
        multiline: Boolean = false,
    ): SendTrack? {
        if (!hasCap("echo-message")) return null
        return SendTrack(sendIdCounter.incrementAndGet(), notice, target, casefold(target), text, label, suppress, multiline)
    }

    /** Records [sends] as written. Called by the writer just before their lines go out. */
    private fun noteSendsWritten(sends: List<SendTrack>) {
        if (sends.isEmpty()) return
        synchronized(awaitingEchoes) {
            for (s in sends) {
                if (awaitingEchoes.size >= MAX_AWAITING_ECHOES) awaitingEchoes.removeFirst()
                awaitingEchoes.addLast(s)
            }
        }
    }

    /** The label of a reply: its own tag, or that of the labeled batch it arrives in. */
    internal fun replyLabel(tags: Map<String, String?>): String? =
        tags["label"]?.takeIf { it.isNotBlank() } ?: tags["batch"]?.let { batchLabels[it] }

    /** True when [echoed] is the server's copy of [sent], which it may have stripped of formatting or trimmed. */
    private fun echoTextMatches(sent: String, echoed: String): Boolean =
        sent == echoed || stripIrcFormatting(sent).trim() == stripIrcFormatting(echoed).trim()

    /**
     * Matches an echo of ours to its send: by label where the reply carries one, otherwise the
     * oldest unlabeled send of the same kind to the same target with the same text. The server
     * answers in order, so earlier sends to that target are finished too and reported delivered.
     * Returns the matched send, or null for a message sent from elsewhere.
     */
    internal suspend fun ProducerScope<IrcEvent>.confirmEcho(
        notice: Boolean,
        rawTarget: String,
        text: String,
        label: String?,
    ): SendTrack? {
        val earlier = ArrayList<Long>()
        val match = synchronized(awaitingEchoes) {
            val idx = if (label != null) {
                awaitingEchoes.indexOfFirst { it.label == label }
            } else {
                val fold = casefold(rawTarget)
                awaitingEchoes.indexOfFirst {
                    it.label == null && it.notice == notice && it.targetFold == fold && echoTextMatches(it.text, text)
                }
            }
            if (idx < 0) return@synchronized null
            val found = awaitingEchoes.removeAt(idx)
            val it = awaitingEchoes.iterator()
            var i = 0
            while (it.hasNext() && i < idx) {
                val s = it.next()
                if (s.targetFold == found.targetFold) {
                    it.remove()
                    if (!s.suppress) earlier += s.id
                }
                i++
            }
            found
        }
        if (earlier.isNotEmpty()) send(IrcEvent.SendResolved(earlier, failed = false))
        return match
    }

    /** Removes the send answered by [label], if one is awaiting it. */
    private fun takeSendByLabel(label: String): SendTrack? = synchronized(awaitingEchoes) {
        val idx = awaitingEchoes.indexOfFirst { it.label == label }
        if (idx >= 0) awaitingEchoes.removeAt(idx) else null
    }

    /**
     * The send that a refusal, ACK or FAIL answers: by label where the reply carries one,
     * otherwise the oldest unlabeled send matching [pick]. Removed from the awaiting list.
     */
    internal fun takeAnsweredSend(tags: Map<String, String?>, pick: (SendTrack) -> Boolean): SendTrack? {
        val label = replyLabel(tags)
        if (label != null) return takeSendByLabel(label)
        return synchronized(awaitingEchoes) {
            val idx = awaitingEchoes.indexOfFirst { it.label == null && pick(it) }
            if (idx >= 0) awaitingEchoes.removeAt(idx) else null
        }
    }

    /**
     * Finishes whatever this line answers among outstanding sends, MODE queries and WHOs: the
     * single labeled answer to a send (an ACK, an error, a notice), a refused message, an error
     * for a MODE query, or a server too busy for a command.
     */
    private suspend fun ProducerScope<IrcEvent>.noteQueryAnswer(msg: IrcMessage) {
        val cmd = msg.command
        val numeric = cmd.length == 3 && cmd.all { it.isDigit() }
        // A labeled command gets one answer. Its echo, a batch around several replies and a
        // FAIL for a multiline batch are matched where they are handled; anything else carrying
        // the label is the whole answer.
        val ownLabel = msg.tags["label"]?.takeIf { it.isNotBlank() }
        val ownEcho = (cmd == "PRIVMSG" || cmd == "NOTICE") && nickEquals(msg.prefixNick(), currentNick)
        val batchFail = cmd == "FAIL" && msg.params.firstOrNull().equals("BATCH", ignoreCase = true)
        if (ownLabel != null && cmd != "BATCH" && !ownEcho && !batchFail) {
            val sent = takeSendByLabel(ownLabel)
            if (sent != null) {
                val failed = cmd == "FAIL" || (numeric && cmd[0] in '4'..'5')
                if (!sent.suppress) send(IrcEvent.SendResolved(listOf(sent.id), failed))
                return
            }
        }
        if (!numeric) return
        val subject = msg.params.getOrNull(1)
        if (cmd in MODE_QUERY_ERROR_CODES && subject != null && isChannelName(subject)) {
            finishModeQuery(casefold(subject))
        }
        if (cmd == "263") {
            // RPL_TRYAGAIN: <me> <command> :Please wait. The oldest of that kind is dropped.
            when (val kind = subject?.uppercase(Locale.ROOT)) {
                "MODE" -> synchronized(modeQueries) { modeQueries.removeFirstOrNull() }
                "WHO" -> synchronized(whoRequests) { whoRequests.removeFirstOrNull() }
                "PRIVMSG", "NOTICE" -> {
                    val dropped = takeAnsweredSend(msg.tags) { it.notice == (kind == "NOTICE") }
                    if (dropped != null && !dropped.suppress) send(IrcEvent.SendResolved(listOf(dropped.id), failed = true))
                }
            }
            return
        }
        if (cmd in SEND_REFUSAL_CODES && subject != null) {
            val fold = casefold(subject)
            val refused = takeAnsweredSend(msg.tags) { it.targetFold == fold } ?: return
            if (!refused.suppress) send(IrcEvent.SendResolved(listOf(refused.id), failed = true))
        }
    }

    /**
     * Answer a CTCP request. [payload] is the CTCP body without the \u0001 framing, which is
     * added here so every reply site frames identically.
     */
    internal suspend fun sendCtcpReply(target: String, payload: String) {
        sendNotice(target, "\u0001$payload\u0001", suppressEcho = true)
    }

    /** Send a NOTICE. With [suppressEcho], its echo is dropped, since the caller printed it. */
    internal suspend fun sendNotice(target: String, text: String, suppressEcho: Boolean) {
        val line = "NOTICE $target :$text"
        sendTracked(line, newSendTrack(notice = true, target = target, text = text, label = null, suppress = suppressEcho))
    }

    /** Per-buffer "history expected until" timestamp, anything older than this is treated as
     *  history rather than live, so we don't re-notify for already-seen messages.
     *  Keyed by [casefold] - see [historyRequested] for why lowercase() is not enough. */
    internal val historyExpectUntil = mutableMapOf<String, Long>()

    /** Server time of our latest live JOIN per channel, keyed by [casefold]. */
    internal val selfJoinServerMs = mutableMapOf<String, Long>()

    /** znc.in/playback last-seen timestamps. Key = [casefold]ed buffer name. Value = epoch seconds. */
    internal val zncLastSeen = mutableMapOf<String, Long>()

    /** Open IRCv3 znc.in/playback batch IDs — messages tagged with these are historical. */
    private val openPlaybackBatches = mutableSetOf<String>()

    /** Lines seen so far inside each open chathistory batch, keyed by batch id. */
    private val chathistoryBatchLines = mutableMapOf<String, Int>()

    /**
     * Target channel of each open playback batch. A replayed QUIT carries no channel of its
     * own (QUIT never does), and the live path infers the affected channels from the current
     * nicklist, which is meaningless for someone who left before the replay. The CHATHISTORY
     * batch it arrives in names the channel, so record it and hand it to the event.
     */
    private val playbackBatchTargets = mutableMapOf<String, String>()

    /** Channel of the innermost open playback batch this message belongs to, if any. */
    internal fun playbackChannelFor(tags: Map<String, String?>): String? =
        tags["batch"]?.let { playbackBatchTargets[it] }

    /**
     * Target of each open CHATHISTORY batch, by batch id; a channel or a nick. Separate from
     * [playbackBatchTargets], which holds channels only.
     */
    private val chathistoryBatchTargets = mutableMapOf<String, String>()

    /**
     * Labeled-response label per open batch, inherited by nested batches. Lets the ViewModel
     * tell its own CHATHISTORY reply from an unrelated batch for the same target.
     */
    private val batchLabels = mutableMapOf<String, String>()

    /** soju.im/search requests in the order they went out. See [searchMessages]. */
    internal val pendingSearches = ArrayDeque<PendingSearch>()

    /** Open soju.im/search batches by id. Their lines are collected, not shown. */
    internal val searchBatches = mutableMapOf<String, SearchBatch>()

    /** Open netsplit/netjoin batch IDs → "netsplit" / "netjoin". */
    internal val openNetsplitBatches = mutableMapOf<String, String>()

    /** Buffered JOIN/QUIT lines per netsplit batch ID, used to collapse the events on close. */
    internal val netsplitBuffer = mutableMapOf<String, MutableList<IrcMessage>>()

    /**
     * An open multiline batch: its target, the BATCH command's tags (authoritative for the merged
     * message), and the accumulated lines as (text, concat) pairs, where concat means no newline
     * before that line.
     */
    internal data class MultilineBatchState(
        val target: String,
        val command: String,        // PRIVMSG or NOTICE - inferred from the first inner line
        val openTags: Map<String, String?>,
        val openSenderPrefix: String?,  // From BATCH +<id> command, may be null
        val innerSenderPrefix: String? = null,  // From first inner PRIVMSG/NOTICE; preferred over BATCH prefix
        val parts: MutableList<Pair<String, Boolean>> = mutableListOf(),
        var bytes: Int = 0,
        var truncated: Boolean = false,
    )
    internal val openMultilineBatches = mutableMapOf<String, MultilineBatchState>()

    /** Hard ceilings on inbound batch state; a server that never closes a batch can't grow us without bound. */
    private val multilineMaxParts = 8192
    private val multilineMaxBytes = 1 shl 20
    private val multilineMaxOpenBatches = 64

    /** Accept the tag with or without the client-only '+' and with or without the draft/ prefix. */
    internal fun hasConcatTag(tags: Map<String, String?>): Boolean =
        tags.keys.any {
            val k = it.removePrefix("+")
            k == "draft/multiline-concat" || k == "multiline-concat"
        }

    /** Append one inner line, enforcing the ceilings above. Silently stops once the batch is full. */
    internal fun appendMultilinePart(state: MultilineBatchState, text: String, concat: Boolean) {
        if (state.truncated) return
        if (state.parts.size >= multilineMaxParts || state.bytes + text.length > multilineMaxBytes) {
            state.truncated = true
            return
        }
        state.bytes += text.length
        state.parts.add(text to concat)
    }

    /** Heuristic: a message in [target] with [timeMs] should be treated as history if we're
     *  currently expecting history for that target and the message is older than ~now. */
    internal fun isHeuristicHistory(target: String?, timeMs: Long?, nowMs: Long): Boolean {
        if (target.isNullOrBlank() || timeMs == null) return false
        val key = casefold(target)
        val until = historyExpectUntil[key] ?: 0L
        if (until < nowMs) return false
        // Anything the server stamped before our join is a replay, whatever the device clock says.
        val joinedAt = selfJoinServerMs[key]
        return if (joinedAt != null) timeMs < joinedAt - 1_000L else timeMs < (nowMs - 15_000L)
    }

    /** Parse server-time tag from IRCv3 tags (legacy `t` from znc.in/server-time-iso, or
     *  modern `time` from server-time). Returns null if absent or malformed. */
    internal fun parseServerTimeMs(tags: Map<String, String?>): Long? {
        val raw = tags["time"] ?: tags["t"] ?: return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    /** True if the message is part of a currently-open znc.in/playback batch (i.e. history). */
    private fun isPlaybackHistory(tags: Map<String, String?>): Boolean {
        val batch = tags["batch"]
        return batch != null && openPlaybackBatches.contains(batch)
    }

    /**
     * True when this line is being replayed rather than happening now: either inside a
     * chathistory batch, or inside a znc.in/playback one.
     */
    internal fun isReplayedLine(tags: Map<String, String?>): Boolean {
        val batch = tags["batch"]
        return isPlaybackHistory(tags) || (batch != null && chathistoryBatchTargets.containsKey(batch))
    }

    /**
     * Count a line against the chathistory batch it belongs to, if any.
     *
     * Context lines are excluded: the spec says they "MUST NOT be counted towards the
     * message limit", so a batch of nothing but context carries no history.
     */
    private fun countChathistoryLine(tags: Map<String, String?>) {
        val batch = tags["batch"] ?: return
        if (!chathistoryBatchTargets.containsKey(batch)) return
        if (tags.containsKey("draft/chathistory-context")) return
        chathistoryBatchLines[batch] = (chathistoryBatchLines[batch] ?: 0) + 1
    }

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
    private fun pruneExpiredPendingBans() = synchronized(pendingBansByNick) {
        val cutoff = System.currentTimeMillis() - PENDING_BAN_TIMEOUT_MS
        val toRemove = mutableListOf<String>()
        for ((fold, list) in pendingBansByNick) {
            list.removeAll { it.queuedAtMs < cutoff }
            if (list.isEmpty()) toRemove.add(fold)
        }
        for (k in toRemove) pendingBansByNick.remove(k)
    }

    /**
     * A parsed `/cmd [#channel] <target> [args...]`: [chan] is the explicit channel or the current
     * channel buffer, [target] the nick or mask, [tail] the remaining tokens.
     */
    internal data class ParsedChanTarget(
        val chan: String,
        val target: String?,
        val tail: List<String>,
    )

    /**
     * Parse a `/cmd [#channel] <target> [tail...]` invocation, shared by the channel-op commands
     * (/kick, /ban, /unban, /kb, /mute, /unmute and so on): an optional leading #channel, then a
     * target nick or mask, then command-specific arguments. Bad input emits a usage or
     * needs-a-channel notice via [commandEvents] and returns null; otherwise
     * [ParsedChanTarget.chan] satisfies [isChannelName].
     */
    internal suspend fun parseChanTargetCommand(
        parts: List<String>,
        cmd: String,
        usageHint: String,
        needsTarget: Boolean,
        currentBuffer: String,
    ): ParsedChanTarget? {
        val a1 = parts.getOrNull(1)
        if (a1.isNullOrBlank()) {
            commandEvents.send(IrcEvent.Notice(
                from = "*", target = currentBuffer,
                text = tr(R.string.core_usage_generic, cmd, usageHint), isPrivate = true,
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
                    text = tr(R.string.core_usage_generic, cmd, usageHint), isPrivate = true,
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
                text = tr(R.string.core_needs_channel_first_arg, cmd),
                isPrivate = true,
            ))
            return null
        }
        return ParsedChanTarget(chan, target, parts.drop(restIdx))
    }

    /**
     * Apply a ban or mute now when the mask needs no remote data, otherwise queue a [PendingBan]
     * and send WHOIS. [nickOrMask] that already looks like a mask is used as-is. [quiet] uses +q,
     * falling back to +b where unsupported; [alsoKick] kicks after the mode.
     */
    internal suspend fun applyBanOrQueue(
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
        val known = knownUser(nick)
        val mask = when (type) {
            BanMaskType.NICK -> "$nick!*@*"
            BanMaskType.USER -> known?.user?.let { "*!$it@*" }
            BanMaskType.HOST -> known?.host?.let { "*!*@$it" }
            BanMaskType.DOMAIN -> known?.host?.let { buildDomainMask(it) }
            BanMaskType.ACCOUNT -> known?.account?.let { accountBanMask(it) }
            BanMaskType.RAW -> null
        }

        if (mask != null) {
            sendRaw("MODE $channel +$modeChar $mask")
            if (alsoKick) {
                sendRaw(if (kickReason.isBlank()) "KICK $channel $nick" else "KICK $channel $nick :${clampLen(kickReason, "KICKLEN")}")
            }
            return
        }

        // Queue and WHOIS.
        pruneExpiredPendingBans()
        val fold = casefold(nick)
        synchronized(pendingBansByNick) {
            pendingBansByNick.getOrPut(fold) { mutableListOf() }.add(
                PendingBan(
                    channel = channel,
                    type = type,
                    quiet = quiet,
                    alsoKick = alsoKick,
                    kickReason = kickReason,
                )
            )
        }
        // Also stash the current buffer so WHOIS reply surfaces there.
        rememberWhoisBuffer(fold, channel)
        sendRaw("WHOIS $nick $nick")  // double-nick form gets idle + full info on most ircds
        commandEvents.send(IrcEvent.Status(tr(R.string.core_looking_up_ban, nick, type.name.lowercase())))
    }

    /**
     * True if the server's CHANMODES advertises `+q` as a list mode (mute/quiet).
     * Checked via ISUPPORT. Conservative: returns false if unknown, which causes
     * [applyBanOrQueue] to fall back to +b. Ircds known to support +q include
     * InspIRCd, UnrealIRCd, Charybdis/Solanum, ircd-seven (freenode/libera).
     */
    internal fun supportsQuietMode(): Boolean {
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
        val queued = synchronized(pendingBansByNick) { pendingBansByNick.remove(fold) } ?: return
        pruneExpiredPendingBans()
        val now = System.currentTimeMillis()
        for (pb in queued) {
            if (now - pb.queuedAtMs > PENDING_BAN_TIMEOUT_MS) continue
            val mask = when (pb.type) {
                BanMaskType.USER    -> if (!user.isNullOrBlank()) "*!${user}@*" else null
                BanMaskType.HOST    -> if (!host.isNullOrBlank()) "*!*@${host}" else null
                BanMaskType.DOMAIN  -> if (!host.isNullOrBlank()) buildDomainMask(host) else null
                BanMaskType.ACCOUNT -> if (!account.isNullOrBlank()) accountBanMask(account) else null
                BanMaskType.NICK, BanMaskType.RAW -> "$nick!*@*"  // shouldn't reach here
            }
            if (mask == null) {
                val reason = when (pb.type) {
                    BanMaskType.ACCOUNT -> tr(R.string.core_ban_no_account, nick)
                    else -> tr(R.string.core_ban_no_host, nick)
                }
                commandEvents.send(IrcEvent.Error(tr(R.string.core_ban_fallback, pb.type.name.lowercase(), reason)))
                val modeChar = if (pb.quiet && supportsQuietMode()) 'q' else 'b'
                sendRaw("MODE ${pb.channel} +$modeChar $nick!*@*")
            } else {
                val modeChar = if (pb.quiet && supportsQuietMode()) 'q' else 'b'
                sendRaw("MODE ${pb.channel} +$modeChar $mask")
            }
            if (pb.alsoKick) {
                sendRaw(if (pb.kickReason.isBlank()) "KICK ${pb.channel} $nick"
                        else "KICK ${pb.channel} $nick :${clampLen(pb.kickReason, "KICKLEN")}")
            }
        }
    }

    /**
     * Pass WHOIS replies (311/330/318) to [completePendingBans] when a ban is waiting on that nick.
     * Kept out of the events flow to keep its method under the JVM size limit.
     */
    private suspend fun handlePendingBanReply(msg: IrcMessage) {
        when (msg.command) {
            "311" -> {
                // RPL_WHOISUSER: <client> <nick> <user> <host> * :realname
                val nick = msg.params.getOrNull(1) ?: return
                val user = msg.params.getOrNull(2)
                val host = msg.params.getOrNull(3)
                val fold = casefold(nick)
                // HOST/USER/DOMAIN bans can complete from this reply alone.
                // ACCOUNT bans need 330 too, so we stash the user/host pair until then.
                val queuedHere = synchronized(pendingBansByNick) { pendingBansByNick[fold]?.toList() } ?: return
                val hasAccountQueued = queuedHere.any { it.type == BanMaskType.ACCOUNT }
                if (!hasAccountQueued) {
                    completePendingBans(nick, user, host, account = null)
                } else {
                    pendingWhoisHostByNick[fold] = (user ?: "") to (host ?: "")
                }
            }
            "330" -> {
                // RPL_WHOISACCOUNT: <client> <nick> <account> :is logged in as
                val nick = msg.params.getOrNull(1) ?: return
                val account = msg.params.getOrNull(2) ?: return
                val fold = casefold(nick)
                val (u, h) = pendingWhoisHostByNick.remove(fold) ?: ("" to "")
                completePendingBans(nick, u.ifBlank { null }, h.ifBlank { null }, account)
            }
            "318" -> {
                // RPL_ENDOFWHOIS: drain any still-pending bans for this nick. They either
                // succeeded above (and were removed from the map) or the server gave us no
                // useful data — surface the fallback "couldn't resolve" error now.
                val nick = msg.params.getOrNull(1) ?: return
                val fold = casefold(nick)
                val (u, h) = pendingWhoisHostByNick.remove(fold) ?: ("" to "")
                if (synchronized(pendingBansByNick) { pendingBansByNick.containsKey(fold) }) {
                    completePendingBans(nick, u.ifBlank { null }, h.ifBlank { null }, account = null)
                }
            }
        }
    }

    internal fun casefold(s: String): String = ircCasefold(s, caseMapping)

    internal fun nickEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return casefold(a) == casefold(b)
    }

    @Volatile internal var userClosing: Boolean = false
    /**
     * One-shot guard against emitting [IrcEvent.Disconnected] more than once per
     * IrcClient lifecycle. ERROR :Closing Link triggers an explicit Disconnected
     * (so scheduleAutoReconnect doesn’t wait for the EOF that the server is
     * about to deliver), and the read loop then exits ~ms later on EOF and the
     * post-loop send fires a second Disconnected for the same logical disconnect.
     */
    @Volatile private var disconnectedEmitted: Boolean = false
    @Volatile internal var lastTlsInfo: String? = null

    /**
     * TOFU: the fingerprint we learned during THIS connection's TLS handshake when no prior
     * fingerprint was stored. Non-null signals the [events] flow to emit [IrcEvent.TlsFingerprintLearned]
     * so the caller can persist it. Cleared after emission to avoid re-firing on reconnect
     * within the same [IrcClient] instance.
     */
    @Volatile internal var learnedFingerprint: String? = null

    /**
     * Emit [IrcEvent.Disconnected] only if no Disconnected has been emitted yet
     * for this IrcClient instance. Subsequent calls are no-ops.
     */
    internal suspend fun ProducerScope<IrcEvent>.sendDisconnectedOnce(
        reason: String,
        code: DisconnectCode,
    ) {
        if (disconnectedEmitted) return
        disconnectedEmitted = true
        send(IrcEvent.Disconnected(reason, code))
    }

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
		lastQuitCode = DisconnectCode.USER_QUIT

		// send QUIT before closing.
		// Use trySend rather than suspending send: if the outbound channel is full (cap 300,
		// common during disconnect storms when the writer is stuck on a dead socket), a
		// suspending send hangs indefinitely. The delay(250) below would never fire and the
		// socket would never close — surfaces as the UI freezing on "Disconnecting…" until
		// the OS kills the app. Dropping the QUIT silently is acceptable: the server will
		// see a TCP close shortly and disconnect us anyway, just without a custom reason.
		runCatching { outbound.trySend(OutboundUnit(listOf("QUIT :${clampLen(reason, "QUITLEN")}"))) }

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
		lastQuitCode = DisconnectCode.USER_QUIT

		val s = socket
		socket = null
		runCatching { s?.close() }
		runCatching { outbound.close() }
		runCatching { outboundBackground.close() }
	}

    suspend fun sendRaw(line: String) = sendLine(line)

    /** [sendRaw] for a message the server will echo, recorded as [track] when written. */
    private suspend fun sendTracked(line: String, track: SendTrack?) =
        sendLine(line, sends = listOfNotNull(track))

    internal suspend fun sendLine(line: String, who: WhoRequest? = null, sends: List<SendTrack> = emptyList()) {
        // Sanitize: Remove any embedded CR/LF to prevent protocol injection.
        // IRC uses CRLF as line delimiter; embedded newlines would be interpreted
        // as separate commands, causing "Unknown command" errors.
        val sanitized = line.replace("\r", "").replace("\n", " ").trim()
        rememberJoinKeys(sanitized)
        if (sanitized.isNotEmpty()) {
            // Dropped if the outbound channel is closed or full; see [enqueue].
            enqueue(OutboundUnit(listOf(sanitized), who = who, sends = sends))
        }
    }

    /**
     * Send [line] in the background lane, after anything the user has typed. For automatic
     * queries and refreshes, which can wait.
     */
    suspend fun sendBackground(line: String) = sendBackground(line, who = null)

    private suspend fun sendBackground(line: String, who: WhoRequest?) {
        val sanitized = line.replace("\r", "").replace("\n", " ").trim()
        if (sanitized.isNotEmpty()) enqueue(OutboundUnit(listOf(sanitized), background = true, who = who))
    }

    /**
     * Send [lines] as one atomic unit: no pacing delay between them and no other command
     * interleaved. Used for multiline BATCHes. Servers that support multiline suspend their
     * own flood penalty for the duration of the batch (Ergo does), so skipping our pacing
     * inside the unit does not risk an excess-flood kill.
     */
    suspend fun sendRawAtomic(lines: List<String>) = sendAtomic(sanitizeAtomic(lines))

    private fun sanitizeAtomic(lines: List<String>): List<String> = lines
        .map { it.replace("\r", "").replace("\n", " ").trim() }
        .filter { it.isNotEmpty() }

    private suspend fun sendAtomic(sanitized: List<String>, sends: List<SendTrack> = emptyList()) {
        if (sanitized.isNotEmpty()) enqueue(OutboundUnit(sanitized, paced = false, sends = sends))
    }

    private suspend fun enqueue(unit: OutboundUnit) {
        noteQueued(unit)
        val lane = if (unit.background) outboundBackground else outbound
        // trySend rather than send: forceClose() may already have closed the channel on a
        // disconnecting client. A line that can't be queued is dropped, since the connection is
        // gone or saturated.
        val result = lane.trySend(unit)
        if (result.isFailure && !result.isClosed) {
            // Channel is full (capacity=300) but still open - fall back to a
            // suspending send so legitimate bursts are not silently discarded.
            // This path is rare; the capacity guard above handles the common cases.
            runCatching { lane.send(unit) }
        }
    }

    /**
     * True when this connection negotiated chathistory and can serve backfill requests.
     * Public so the UI can show or hide "load older messages"
     */
    fun supportsChatHistory(): Boolean = hasChathistoryCap()

    /**
     * A CHATHISTORY request on the wire. [label] is what the reply batch will carry, null
     * without labeled-response. [limit] is the count sent after the server's ceiling, which
     * a short reply is measured against.
     */
    data class HistoryRequest(val label: String?, val limit: Int)

    /** True when a WHO can fetch every member's host and account in one WHOX reply. */
    fun whoxAvailable(): Boolean = whoxSupported && config.capPrefs.whox

    /**
     * Re-query [target], a channel's members or one nick, for away status and account changes.
     * WHOX where the server has it, plain WHO otherwise; the away flag is in the flags field of both.
     */
    suspend fun refreshChannelWho(target: String) {
        val who = whoFor(target, null)
        if (whoxSupported && config.capPrefs.whox) sendBackground("WHO $target %tuhsnfar,42", who)
        else sendBackground("WHO $target", who)
    }

    /**
     * Request older history for [target] with CHATHISTORY BEFORE, anchored on [beforeTimestamp]
     * where the server accepts timestamps, otherwise [beforeMsgId].
     */
    suspend fun requestChatHistoryBefore(
        target: String,
        beforeTimestamp: String?,
        beforeMsgId: String? = null,
        limit: Int = 50
    ): HistoryRequest? {
        if (!hasChathistoryCap()) return null
        val anchor = historySelector(beforeTimestamp, beforeMsgId) ?: return null
        return sendHistory("BEFORE", target, anchor, null, limit)
    }

    /**
     * A CHATHISTORY selector in the server's preferred reference type (MSGREFTYPES, most preferred
     * first), msgid when none is stated. Msgids paginate exactly where two messages can share a
     * timestamp.
     */
    private fun historySelector(timestamp: String?, msgId: String?): String? {
        val preference = msgRefTypes.ifEmpty { listOf("msgid", "timestamp") }
        return preference.firstNotNullOfOrNull { type ->
            when (type) {
                "msgid" -> msgId?.takeIf { historyMsgidOk() }?.let { lastHistoryRefType = "msgid"; "msgid=$it" }
                "timestamp" -> timestamp?.takeIf { historyTimestampOk() }
                    ?.let { lastHistoryRefType = "timestamp"; "timestamp=$it" }
                else -> null
            }
        }
    }

    /** Write one CHATHISTORY subcommand and report what went out. */
    private suspend fun sendHistory(
        sub: String,
        target: String,
        first: String,
        second: String?,
        limit: Int,
    ): HistoryRequest? {
        val effective = clampHistoryLimit(limit)
        if (effective <= 0) return null
        val label = if (hasCap("labeled-response")) nextLabel() else null
        val tag = if (label != null) "@label=$label " else ""
        val mid = if (second != null) "$first $second" else first
        sendBackground("${tag}CHATHISTORY $sub $target $mid $effective")
        return HistoryRequest(label, effective)
    }

    /**
     * Request messages either side of an anchor (CHATHISTORY AROUND).
     *
     * The server decides how to split [limit] between before and after. Useful for opening
     * on a single message, such as the target of a reply that is not in the loaded window.
     */
    suspend fun requestChatHistoryAround(
        target: String,
        timestamp: String? = null,
        msgId: String? = null,
        limit: Int = 50,
    ): HistoryRequest? {
        if (!hasChathistoryCap()) return null
        val anchor = historySelector(timestamp, msgId) ?: return null
        return sendHistory("AROUND", target, anchor, null, limit)
    }

    /**
     * Request the messages between two anchors (CHATHISTORY BETWEEN).
     *
     * Counting starts from and excludes the first selector and finishes on and excludes the
     * second, in either direction. This is what the spec recommends for filling a known gap,
     * such as between the last message of a previous session and the earliest of this one.
     */
    suspend fun requestChatHistoryBetween(
        target: String,
        fromTimestamp: String? = null,
        fromMsgId: String? = null,
        toTimestamp: String? = null,
        toMsgId: String? = null,
        limit: Int = 50,
    ): HistoryRequest? {
        if (!hasChathistoryCap()) return null
        val a = historySelector(fromTimestamp, fromMsgId) ?: return null
        val b = historySelector(toTimestamp, toMsgId) ?: return null
        return sendHistory("BETWEEN", target, a, b, limit)
    }

    /**
     * Request the unread history for [target] using CHATHISTORY AFTER, anchored on
     * [afterTimestamp] so we only fetch messages newer than what we already hold.
     *
     * Used after a reconnect or when the server notifies us via read-marker that messages
     * in this buffer haven't been seen yet.
     */
    suspend fun requestChatHistoryAfter(
        target: String,
        afterTimestamp: String?,
        afterMsgId: String? = null,
        limit: Int = 100
    ): HistoryRequest? {
        if (!hasChathistoryCap()) return null
        val anchor = historySelector(afterTimestamp, afterMsgId) ?: return null
        return sendHistory("AFTER", target, anchor, null, limit)
    }

    /** True if either the draft or soju's vendored webpush cap is enabled. */
    fun hasWebPushCap(): Boolean = hasCap("draft/webpush") || hasCap("soju.im/webpush")

    /**
     * The server's VAPID public key, or null. Given to the UnifiedPush distributor so the push
     * service only accepts notifications signed by this server.
     */
    fun webPushVapidKey(): String? = vapidPublicKey

    /**
     * Register a Web Push endpoint (WEBPUSH REGISTER) with the subscription keys [auth] and
     * [p256dh] (URL-safe base64, unpadded). Re-registering replaces the keys; the server confirms
     * with its own WEBPUSH REGISTER ([IrcEvent.WebPushRegistered]) or FAIL WEBPUSH.
     */
    suspend fun webPushRegister(endpoint: String, auth: String, p256dh: String) {
        if (!hasWebPushCap()) return
        if (endpoint.isBlank() || auth.isBlank() || p256dh.isBlank()) return
        sendRaw("${labelTag()}WEBPUSH REGISTER $endpoint auth=$auth;p256dh=$p256dh")
    }

    /**
     * Drop a Web Push subscription (WEBPUSH UNREGISTER)
     */
    suspend fun webPushUnregister(endpoint: String) {
        if (!hasWebPushCap()) return
        if (endpoint.isBlank()) return
        sendRaw("${labelTag()}WEBPUSH UNREGISTER $endpoint")
    }

    /**
     * Request the most recent [limit] messages for [target] (CHATHISTORY LATEST *).
     */
    suspend fun requestChatHistoryLatest(target: String, limit: Int = 50): HistoryRequest? {
        if (!hasChathistoryCap()) return null
        // "*" means no restriction, so LATEST needs no reference type and works on a server
        // that accepts neither.
        return sendHistory("LATEST", target, "*", null, limit)
    }

    /**
     * Send a PRIVMSG, encrypted and labeled as configured. With echo-message, returns the id its
     * echo ([IrcEvent.ChatMessage.ownSendId]) or refusal ([IrcEvent.SendResolved]) will carry;
     * null when no answer is coming or nothing was sent. With [suppressEcho] the echo is dropped
     * here, for a message the caller has printed itself.
     */
    suspend fun privmsg(
        target: String,
        text: String,
        replyToMsgId: String? = null,
        suppressEcho: Boolean = false,
    ): Long? {
        // This is a safeguard in case callers don't pre-split multiline messages.
        val sanitizedText = text.replace("\r", "").replace("\n", " ")

        // Encrypt when a key is configured for [target]. For CTCP, only the payload is encrypted
        // and the \u0001 framing stays clear.
        val payload = e2eCodec?.let { codec ->
            if (sanitizedText.startsWith("\u0001") && sanitizedText.endsWith("\u0001") && sanitizedText.length > 2) {
                // CTCP. Split on the first space inside the framing: command stays clear,
                // arguments get encrypted. ACTION is the common case ("ACTION hello"); other
                // CTCP queries (VERSION, PING) typically have no user-content payload to
                // hide so encrypting them adds noise without benefit, but doing it
                // uniformly keeps the wire pattern less revealing than a "this client
                // encrypts ACTION but not VERSION" fingerprint would be.
                val inner = sanitizedText.substring(1, sanitizedText.length - 1)
                val spaceIdx = inner.indexOf(' ')
                // DCC offers stay clear: peers parse them before any decryption.
                if (spaceIdx > 0 && !inner.startsWith("DCC ", ignoreCase = true)) {
                    val cmd = inner.substring(0, spaceIdx)
                    val args = inner.substring(spaceIdx + 1)
                    val encArgs = codec.encryptOutgoing(target, args, currentNick)
                    if (encArgs === args) sanitizedText // no key, pass through
                    else "\u0001$cmd $encArgs\u0001"
                } else {
                    // CTCP with no args (e.g. \u0001VERSION\u0001) - nothing to encrypt.
                    sanitizedText
                }
            } else {
                codec.encryptOutgoing(target, sanitizedText, currentNick)
            }
        } ?: sanitizedText

        // One tag group ("@k1=v1;k2=v2 "); two separate '@' groups are malformed. Carries the label
        // and, for a reply, +draft/reply.
        val label: String? =
            if (hasCap("echo-message") && hasCap("labeled-response")) nextLabel() else null
        val tagPairs = buildList {
            if (label != null) add("label=$label")
            if (replyToMsgId != null && hasCap("message-tags")) {
                // Both the draft and the Feb 2026 ratified tag name: servers strip
                // whichever they don't relay, and receivers read either form.
                add("+draft/reply=${escapeIrcTagValue(replyToMsgId)}")
                add("+reply=${escapeIrcTagValue(replyToMsgId)}")
            }
        }
        val tag = if (tagPairs.isEmpty()) "" else tagPairs.joinToString(";", prefix = "@", postfix = " ")
        // +AGE fail-closed backstop. Manual and scripted +AGE both emit AGE-prefixed ciphertext, so a
        // payload reaching here WITHOUT that prefix for an +AGE-enabled target means something tried to
        // put plaintext on the wire while the UI shows a padlock. Refuse.
        if (ageEnabledForTarget?.invoke(target) == true &&
            !payload.startsWith("${com.boxlabs.hexdroid.crypto.E2eScheme.AGE.wirePrefix} ")) {
            commandEvents.send(IrcEvent.Error(
                tr(R.string.core_age_plaintext_refused, target)))
            return null
        }
        val track = newSendTrack(notice = false, target = target, text = payload, label = label, suppress = suppressEcho)
        sendTracked("${tag}PRIVMSG $target :$payload", track)
        return track?.id
    }

    /** Target of the last multiline BATCH sent, for a FAIL BATCH MULTILINE_* without echo-message. */
    @Volatile internal var lastMultilineTarget: String? = null

    /**
     * True when [target] can take a multiline BATCH: the capabilities are negotiated and the
     * conversation isn't encrypted, since each line would be a separate ciphertext.
     */
    fun multilineSendAvailable(target: String): Boolean {
        if (!hasCap("batch")) return false
        if (!hasCap("draft/multiline") && !hasCap("multiline")) return false
        if (ageEnabledForTarget?.invoke(target) == true) return false
        val codec = e2eCodec ?: return true
        // encryptOutgoing returns the argument identity-unchanged when no key is set.
        val probe = "\u0000probe"
        return codec.encryptOutgoing(target, probe, currentNick) === probe
    }

    /**
     * Send [lines] to [target] as one message using a multiline BATCH. Null when the capabilities
     * are missing, the target is encrypted or nothing is sendable; the caller then sends line by
     * line. Otherwise one send id per batch (empty without echo-message), as [privmsg] returns
     * for one line.
     */
    suspend fun privmsgMultiline(target: String, lines: List<String>, replyToMsgId: String? = null): List<Long>? {
        if (lines.isEmpty()) return null
        if (!multilineSendAvailable(target)) return null

        // NOT a client-only tag: no leading '+'. The spec's own example sends
        // "@batch=123;draft/multiline-concat PRIVMSG ...", and Ergo answers a '+'-prefixed
        // form with FAIL BATCH MULTILINE_INVALID. The name still tracks the batch/cap name.
        val draft = hasCap("draft/multiline")
        val batchType = if (draft) "draft/multiline" else "multiline"
        val concatTag = if (draft) "draft/multiline-concat" else "multiline-concat"

        val labelled = hasCap("echo-message") && hasCap("labeled-response")

        // "@batch=<id>;<concatTag> PRIVMSG <target> :" plus CRLF.
        val lineOverhead = 18 + concatTag.length + 10 + target.length + 4

        val wire = buildMultilineWireLines(
            target = target,
            lines = lines,
            limits = parseMultilineLimits(capValue("draft/multiline") ?: capValue("multiline")),
            // Budget for one inner line's payload: the server's line limit less the worst
            // plausible ":nick!user@host " relay prefix and our own framing.
            perLineBytes = ((serverLinelen ?: 512) - 100 - lineOverhead).coerceAtLeast(64),
            batchType = batchType,
            concatTag = concatTag,
            nextBatchId = { "hm${labelCounter.incrementAndGet()}" },
            // Called once per batch. Each batch is a separate labeled response, so each
            // needs its own label or the echoes of batches 2..n can't be correlated.
            openTagsFor = {
                buildList {
                    if (labelled) add("label=${nextLabel()}")
                    if (replyToMsgId != null && hasCap("message-tags")) {
                        add("+draft/reply=${escapeIrcTagValue(replyToMsgId)}")
                        add("+reply=${escapeIrcTagValue(replyToMsgId)}")
                    }
                }
            },
        )
        if (wire.isEmpty()) return null
        val sanitized = sanitizeAtomic(wire)
        val tracks = multilineBatchTexts(sanitized).mapNotNull { (label, text) ->
            newSendTrack(notice = false, target = target, text = text, label = label, multiline = true)
        }
        lastMultilineTarget = target
        sendAtomic(sanitized, tracks)
        return tracks.map { it.id }
    }

    /** Send a typing TAGMSG to [target]; [state] is "active", "paused" or "done". */
    suspend fun sendTypingStatus(target: String, state: String) {
        // Typing is a client tag on message-tags; there is no typing capability to negotiate.
        if (!hasCap("message-tags")) return
        // A server that denies the tag strips it, leaving an empty TAGMSG the server then rejects.
        if (!clientTagAllowed("typing")) return
        // Behind flood pacing it would arrive stale and hold up the message being typed.
        if (sendBacklogMs() > 0) return
        sendRaw("@+typing=$state TAGMSG $target")
    }

    /** Tells [target] we have read their messages up to [msgId]. */
    suspend fun sendReadReceipt(target: String, msgId: String) {
        if (!hasCap("message-tags") || !clientTagAllowed(READ_RECEIPT_TAG)) return
        sendBackground("@+$READ_RECEIPT_TAG=${escapeIrcTagValue(msgId)} TAGMSG $target")
    }
    /**
     * Send a draft/message-reactions emoji reaction to [msgId] in [target].
     * Requires the message-tags cap (reactions use client-only tags).
     * Pass [remove] = true to un-react (sends +draft/unreact, plus +draft/react-removed for older receivers).
     */
    suspend fun sendReaction(target: String, msgId: String, emoji: String, remove: Boolean = false) {
        if (!hasCap("message-tags") && !hasCap("draft/message-reactions")) return
        // Stripped react tag leaves an empty TAGMSG; the reply tag alone carries no reaction.
        // Removal: the react client-tag spec (Feb 2026) uses +draft/unreact; the older
        // message-reactions form is +draft/react-removed. Send every form the server's
        // CLIENTTAGDENY permits so either generation of receiver sees the removal
        // un-reacting twice is idempotent, so double delivery is harmless.
        val removalTags = if (remove) {
            listOf("draft/unreact", "draft/react-removed").filter { clientTagAllowed(it) }
        } else emptyList()
        if (if (remove) removalTags.isEmpty() else !clientTagAllowed("draft/react")) return
        // Single IRCv3 tag group - the optional label, the react tag, and the reply
        // tag must share one '@...' prefix joined by ';'. Emitting "@label=… @+draft/…"
        // as two groups is malformed and strict servers reject it.
        val tagPairs = buildList {
            if (hasCap("labeled-response")) add("label=${nextLabel()}")
            val reaction = escapeIrcTagValue(emoji.trim())
            if (remove) {
                for (t in removalTags) add("+$t=$reaction")
            } else {
                add("+draft/react=$reaction")
            }
            add("+draft/reply=${escapeIrcTagValue(msgId)}")
            add("+reply=${escapeIrcTagValue(msgId)}")
        }
        sendRaw(tagPairs.joinToString(";", prefix = "@", postfix = " ") + "TAGMSG $target")
    }

    /**
     * draft/metadata-2 command helper. [target] is a nick or channel; "*" means
     * ourselves and is the required form before registration completes.
     */
    suspend fun sendMetadata(target: String, subcommand: String, args: String = "") {
        if (!hasCap("draft/metadata-2")) return
        val tail = args.trim()
        sendRaw("METADATA $target ${subcommand.uppercase(Locale.ROOT)}" + if (tail.isEmpty()) "" else " $tail")
    }

    /** Set (or, with a null/blank value, remove) one of our own metadata keys. */
    suspend fun setOwnMetadata(key: String, value: String?) {
        if (!hasCap("draft/metadata-2")) return
        val v = value?.trim()
        if (v.isNullOrEmpty()) sendRaw("METADATA * SET $key")
        else sendRaw("METADATA * SET $key :$v")
    }

    /**
     * draft/account-registration: register an account. Password is sent as the
     * trailing parameter so it may contain spaces. Account/email default to "*"
     * (server picks the nick / no email) when blank. Same wire form the /register
     * slash command builds; used by the guided registration dialog.
     */
    suspend fun sendRegister(account: String, email: String, password: String) {
        if (!hasCap("draft/account-registration")) return
        val acct = account.trim().ifBlank { "*" }
        val mail = email.trim().ifBlank { "*" }
        sendRaw("REGISTER $acct $mail :$password")
    }

    /** draft/account-registration: verify a pending account with an emailed code. */
    suspend fun sendVerify(account: String, code: String) {
        if (!hasCap("draft/account-registration")) return
        val acct = account.trim().ifBlank { pendingVerifyAccount ?: currentNick }
        sendRaw("VERIFY $acct :${code.trim()}")
    }

    suspend fun sendRedact(target: String, msgId: String, reason: String? = null) {
        if (!hasCap("draft/message-redaction") && !hasCap("message-redaction")) return
        val tail = reason?.trim()?.takeIf { it.isNotEmpty() }?.let { " :$it" } ?: ""
        sendRaw("REDACT $target $msgId$tail")
    }

    /**
     * Ask for the targets with stored history (CHATHISTORY TARGETS), to find buffers, PMs
     * especially, that gained messages while offline. Replies arrive as [IrcEvent.HistoryTarget].
     * Only with draft/chathistory.
     */
    suspend fun requestChatHistoryTargets(limit: Int = 50) {
        if (!hasChathistoryCap()) return
        if (!historyTimestampOk()) return
        val from = historyEpochTimestamp()
        val now = historyTimestamp(java.time.Instant.now())
        sendRaw("${labelTag()}CHATHISTORY TARGETS timestamp=$from timestamp=$now ${clampHistoryLimit(limit)}")
    }

    /** Send a CTCP request, framed in \u0001. Returns the send id as [privmsg] does. */
    suspend fun ctcp(target: String, payload: String): Long? = privmsg(target, "\u0001$payload\u0001")

    /** Run the slash command [cmdLine], given without its slash, as typed in [currentBuffer]. */
    suspend fun handleSlashCommand(cmdLine: String, currentBuffer: String) = runSlashCommand(cmdLine, currentBuffer)

    fun events(): Flow<IrcEvent> = channelFlow {
        send(IrcEvent.Status(tr(R.string.core_connecting)))

        val s = try {
            withContext(Dispatchers.IO) { openSocket() }
        } catch (t: Throwable) {
            // TOFU pin mismatch gets its own structured event so the UI can show the
            // stored-vs-actual fingerprints and the user can make an informed choice
            // (legitimate cert renewal vs. suspected MITM).
            if (t is TlsFingerprintMismatchException) {
                send(IrcEvent.TlsFingerprintChanged(stored = t.stored, actual = t.actual))
                sendDisconnectedOnce(
                    tr(R.string.core_disconnect_tls_fingerprint_changed),
                    DisconnectCode.TLS_FINGERPRINT_CHANGED,
                )
                return@channelFlow
            }
            if (t is TlsHostnameMismatchException) {
                send(IrcEvent.TlsHostnameMismatch(expected = t.expected, sans = t.identities))
                sendDisconnectedOnce(
                    tr(R.string.core_disconnect_tls_hostname_mismatch),
                    DisconnectCode.TLS_HOSTNAME_MISMATCH,
                )
                return@channelFlow
            }
            val msg = friendlyErrorMessage(t)
            // One Disconnected event carrying the "Connect failed: …" prefix, which the ViewModel
            // renders with error styling. Mid-stream socket errors use a "Connection error: …"
            // prefix the same way.
            sendDisconnectedOnce(
                tr(R.string.core_disconnect_connect_failed, msg),
                connectFailureCode(t),
            )
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
            send(IrcEvent.ServerText("*** " + tr(R.string.core_tls, info)))
        }

        // Set up encoding-aware I/O using EncodingHelper
        val inputStream = s.getInputStream()
        val outputStream = s.getOutputStream()

        // Serialises every write to [outputStream]. See writeLine() below for why this is
        // needed. Local to this events() invocation so each new connection gets a fresh
        // mutex and we can't accidentally hold a stale lock across reconnects.
        val writeMutex = Mutex()

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
            send(IrcEvent.ServerText("*** " + tr(R.string.core_using_encoding, config.encoding)))
        }

        suspend fun writeLine(line: String) = withContext(Dispatchers.IO) {
            // Raw log, outgoing. Hooked here rather than in sendRaw so lines that bypass
            // the outbound queue (registration, PONG, the nick-reclaim retries) are logged
            val redacted = redactRawLine(line)
            if (rawLogEnabled) trySend(IrcEvent.RawLine(true, redacted))
            noteRecentRaw(true, redacted)
            // UTF8ONLY (ISUPPORT) overrides the connection's encoding, so a legacy profile
            // encoding never sends non-UTF-8 bytes to a server that rejects them.
            val utf8Only = config.capPrefs.utf8Only && utf8OnlyServer
            val enc = if (utf8Only || this@IrcClient.hasCap("utf8only")) "UTF-8" else lineReader.encoding
            val bytes = EncodingHelper.encode(line, enc)
            // Combine payload + CRLF into one array so we issue a single write() syscall
            // instead of two, and avoid allocating a new CRLF ByteArray each time.
            val packet = ByteArray(bytes.size + CRLF.size)
            bytes.copyInto(packet)
            CRLF.copyInto(packet, destinationOffset = bytes.size)
            // Writes are serialised: several coroutines write on different threads, and interleaved
            // writes corrupt the line (or the TLS record). The mutex is FIFO, so PINGs aren't
            // starved by a burst of traffic.
            writeMutex.withLock {
                outputStream.write(packet)
                outputStream.flush()
            }
        }

        val writerJob = launch(Dispatchers.IO) {
            // flood pacing so bursts don't get us flood-disconnected mid-handshake.
            // Registration lines are exempt: servers are lenient before 001 and pacing them would
            // slow every connect. A `floodClock` tracks the virtual time by which the queued penalty
            // is "paid"; when it runs more than floodBurstMs ahead of now we wait for it to catch up.
            //
            // Bouncer connections are exempt.
            var floodClock = System.currentTimeMillis()
            // A typed line that arrived while a background unit waited out its pacing, and that
            // background unit, put back to go after it.
            var jumped: OutboundUnit? = null
            var heldBack: OutboundUnit? = null
            try {
                while (true) {
                    val unit = jumped?.also { jumped = null }
                        ?: outbound.tryReceive().getOrNull()
                        ?: heldBack?.also { heldBack = null }
                        ?: select<OutboundUnit?> {
                            // select favours the first clause, so typed lines go first.
                            outbound.onReceiveCatching { it.getOrNull() }
                            outboundBackground.onReceiveCatching { it.getOrNull() }
                        }
                        ?: break
                    if (registered && !config.isBouncer && !safeRate) {
                        val now = System.currentTimeMillis()
                        if (floodClock < now) floodClock = now
                        // Charge the whole unit, then write its lines back-to-back. Pacing
                        // between them would let other queued commands interleave, which is
                        // fatal inside a BATCH. An unpaced unit (multiline) costs one line's
                        // base penalty against later traffic but is never delayed.
                        val cost = if (unit.paced) {
                            floodPenaltyMs * unit.lines.size + unit.lines.sumOf { it.length } * floodPerCharMs
                        } else floodPenaltyMs
                        val ahead = if (unit.paced) floodClock + cost - now - floodBurstMs else 0L
                        if (ahead > 0) {
                            if (unit.background) {
                                // Wait, but let a typed line that arrives meanwhile go first.
                                val typed = withTimeoutOrNull(ahead) { outbound.receiveCatching() }
                                if (typed != null) {
                                    if (typed.isClosed) break
                                    jumped = typed.getOrNull()
                                    heldBack = unit
                                    continue
                                }
                            } else {
                                delay(ahead)
                            }
                        }
                        floodClock += cost
                    }
                    // Recorded before writing, so a reply can never arrive ahead of its record.
                    unit.who?.let { trackWho(it) }
                    noteSendsWritten(unit.sends)
                    for (line in unit.lines) {
                        noteModeQuery(line, unit.silentModeQuery)
                        writeLine(line)
                    }
                }
            } catch (t: Throwable) {
                // If writes start failing (common during network handovers or SSL close),
                // force-close the socket so the read loop can notice and emit Disconnected.
                if (!userClosing) {
                    lastQuitReason = friendlyErrorMessage(t)
                    lastQuitCode = errorCode(t)
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
                lastQuitReason = tr(R.string.core_disconnect_registration_timeout)
                lastQuitCode = DisconnectCode.REGISTRATION_TIMEOUT
                send(IrcEvent.Error(
                    tr(
                        R.string.core_error_registration_timeout,
                        ConnectionConstants.REGISTRATION_TIMEOUT_MS / 1000,
                    ),
                    transient = true,
                ))
                runCatching { s.close() }
            }
        }

        val pingJob = launch {
            // Wait a moment so the socket is fully established.
            delay(5_000)
            while (true) {
                // Ping interval: 60 s direct, 90 s through a bouncer, stretched while backgrounded
                // ([BACKGROUND_PING_INTERVAL_MS]) but kept under the read timeout. Recomputed every
                // cycle, so foreground changes apply on the next ping.
                val pingIntervalMs = when {
                    !AppVisibility.isForeground -> ConnectionConstants.BACKGROUND_PING_INTERVAL_MS
                    config.isBouncer            -> 90_000L
                    else                        -> 60_000L
                }
                delay(pingIntervalMs)

                // Liveness is decided by whether ANY data has arrived recently, not by
                // whether our lag-ping token came back.A link is dead only when it has gone genuinely silent:
                // no PONG, no server PING, no message at all for PING_TIMEOUT_MS. We still
                // send our own PING below to elicit traffic on an otherwise-idle (but
                // healthy) connection so lastInboundAtMs keeps refreshing.
                val now = System.currentTimeMillis()
                if (!userClosing && now - lastInboundAtMs > ConnectionConstants.PING_TIMEOUT_MS) {
                    lastQuitReason = tr(R.string.core_disconnect_ping_timeout)
                    lastQuitCode = DisconnectCode.PING_TIMEOUT
                    runCatching { s.close() }
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

        val irc = IrcSession(config, rng).also { it.strings = strings }
        sessionRef = irc
        // Read-loop state lives in class fields so the handlers outside events() can reach it.
        // Reset it on every events() invocation (one IrcClient may technically be reused,
        // although in practice we create a fresh one per connect).
        historyRequested.clear()
        namesRequested.clear()
        historyExpectUntil.clear()
        selfJoinServerMs.clear()
        zncLastSeen.clear()
        openPlaybackBatches.clear()
        playbackBatchTargets.clear()
        openNetsplitBatches.clear()
        netsplitBuffer.clear()
        monitorListBuffer.clear()
        monitorLimit = -1
        clientTagDeny = null
        filehostUrl = null
        networkIconUrl = null
        botModeChar = null
        bouncerNetId = null
        safeRate = false
        extbanPrefix = null
        extbanTypes = null
        accountExtban = null
        chatHistoryLimit = 0
        utf8OnlyServer = false
        lastHistoryRefType = null
        msgRefTypes = emptyList()
        refusedRefTypes = emptySet()
        vapidPublicKey = null
        vapidBecameReady = false
        chathistoryBatchTargets.clear()
        batchLabels.clear()
        chathistoryBatchLines.clear()
        synchronized(pendingSearches) { pendingSearches.clear() }
        searchBatches.clear()
        lengthLimits.clear()
        knockSupported = false
        chanLimits.clear()
        pendingVerifyAccount = null
        openMultilineBatches.clear()

        val numericHandlers = buildNumericHandlers(this, { writeLine(it) }, registrationWatchdogJob)

		try {
			send(IrcEvent.Status(tr(R.string.core_negotiating_caps)))
			// PASS goes first. The password gets a leading colon only when it contains a space or
			// starts with one, as some bouncers don't strip it. It is skipped for a bouncer when SASL
			// is also configured, since the bouncer would treat it as a second login attempt.
			val skipPass = config.sasl is SaslConfig.Enabled && config.isBouncer
			if (!skipPass) {
				// effectivePassLine prepends the bouncer username + network selector when
				// applicable (e.g. "alice/libera:secret") so the bouncer can route the
				// connection to the right upstream. For non-bouncer profiles or already-
				// hand-formatted passwords, this is a no-op pass-through.
				config.effectivePassLine(config.serverPassword)?.let { pw ->
					writeLine(if (pw.contains(' ') || pw.startsWith(':')) "PASS :$pw" else "PASS $pw")
				}
			}
			writeLine("CAP LS 302")
			writeLine("NICK ${config.nick}")
			writeLine("USER ${config.effectiveAuthIdentity(config.username)} 0 * :${config.realname}")
			send(IrcEvent.Connected("${config.host}:${config.port}"))
			// Track if we've notified about encoding detection
			var encodingNotified = false

			// Track oversize-line drops so we surface a single user-visible notice on
			// the first occurrence per connection. Further drops are silent to avoid
			// a torrent of status messages if a server is wedged emitting huge lines.
			var truncatedNotified = false
			var lastTruncatedCount = 0

			// Seed the liveness clock at connection time so a server that completes the
			// socket/TLS handshake but then says nothing still gets a full PING_TIMEOUT_MS
			// grace window before the stall detector acts (rather than tripping instantly
			// off the 0L default).
			lastInboundAtMs = System.currentTimeMillis()

			while (true) {
				val prevEncoding = lineReader.encoding
				val line = withContext(Dispatchers.IO) { lineReader.readLine() } ?: break

				// Raw log, incoming. Before parsing, so a line we fail to parse is still visible.
				if (rawLogEnabled && line.isNotEmpty()) send(IrcEvent.RawLine(false, redactRawLine(line)))
				if (line.isNotEmpty()) noteRecentRaw(false, line)

				// Any successful read proves the link is alive, stamp it before parsing so
				// every kind of inbound line (PONG with or without our token, server PING,
				// PRIVMSG, even a truncated oversize line) refreshes liveness. The ping
				// coroutine reads this instead of requiring our lag-ping token to be echoed.
				lastInboundAtMs = System.currentTimeMillis()

				// A truncated line returned as "" — skip parsing, but surface the fact
				// to the user once so they know something was dropped rather than
				// silently swallowed.
				if (lineReader.truncatedLineCount > lastTruncatedCount) {
					lastTruncatedCount = lineReader.truncatedLineCount
					if (!truncatedNotified) {
						truncatedNotified = true
						send(IrcEvent.ServerText(
							"*** " + tr(R.string.core_oversize_line_dropped)
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
					send(IrcEvent.ServerText("*** " + tr(R.string.core_legacy_encoding, label)))
					encodingNotified = true
				}

				send(IrcEvent.ServerLine(line))
				val msg = parser.parse(line) ?: continue
				if (searchBatches.isNotEmpty() && collectSearchLine(msg)) continue
				noteSender(msg)
				// A line a handler can't cope with is logged and skipped. It must not end the connection:
				// a bouncer would replay it after reconnecting, and the client would loop.
				try {

				// Count the line against its chathistory batch before anything decides
				// whether to display it. Whether a batch was empty is a fact about the wire,
				// not about how many of its lines we chose to show.
				countChathistoryLine(msg.tags)
				closeModeReplyBefore(msg)
				noteQueryAnswer(msg)

				if (msg.command == "PING") {
					val payload = msg.trailing ?: msg.params.firstOrNull() ?: ""
					writeLine("PONG :$payload")
					continue
				}

				if (msg.command == "PONG") {
					// Liveness was already refreshed by lastInboundAtMs when this line was read;
					// the token only feeds the lag readout. Servers that don't echo our token just
					// show no lag figure.
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

				// A hard refusal of a nick change. Some IRCd's answer /NICK with 400 ERR_UNKNOWNERROR
				// "NICK :You must use your account name as your nickname" when nick-reservation
				// is strict; other ircds use 447 ERR_CANTCHANGENICK. Retrying can never succeed
				if ((msg.command == "400" && msg.params.getOrNull(1).equals("NICK", true)) ||
					msg.command == "447"
				) {
					val already = nickChangeRefused
					nickChangeRefused = true
					if (!already) {
						val why = msg.trailing ?: msg.params.lastOrNull() ?: ""
						send(IrcEvent.Error(tr(R.string.core_nick_change_refused, why)))
					}
					continue
				}

				if (msg.command == "433") {
					// Ignore 433 after successful registration — Ergo (and other servers with
					// SASL nick-reclaim) may send queued 433s for nicks tried pre-SASL after
					// the 001 welcome is already issued with the correct nick. Acting on them
					// would cause an endless collision loop.
					if (!registered) {
						// A server that cuts a long nick to its NICKLEN reports the cut nick, so a
						// suffix appended past that length would be cut off again on every retry.
						val sent = lastNickTried ?: config.nick
						val rejected = msg.params.getOrNull(1)?.takeIf { it != "*" }
						if (rejected != null && rejected.length < sent.length) observedNickLen = rejected.length
						val alt = config.altNick
						if (!triedAltNick && !alt.isNullOrBlank()) {
							triedAltNick = true
							lastNickTried = alt
							writeLine("NICK $alt")
							send(IrcEvent.Status(tr(R.string.core_nick_in_use_alt, alt)))
						} else {
							val suffix = "_" + (1000 + rng.nextInt(9000)).toString()
							val base = alt?.takeIf { it.isNotBlank() } ?: config.nick
							val room = observedNickLen?.let { (it - suffix.length).coerceAtLeast(1) } ?: base.length
							val next = base.take(room) + suffix
							lastNickTried = next
							writeLine("NICK $next")
							send(IrcEvent.Status(tr(R.string.core_nick_in_use_next, next)))
						}
					}
					continue
				}

				// Guard the session state machine: the SCRAM path can throw from the JCE
				// primitives. A failure is reported as an Error event rather than ending the
				// connect coroutine.
				val hsActions = runCatching { irc.onMessage(msg) }.getOrElse { t ->
					send(IrcEvent.Error(tr(R.string.core_sasl_handler_error, t.message ?: t.javaClass.simpleName)))
					emptyList()
				}
				for (a in hsActions) when (a) {
					is IrcAction.Send -> writeLine(a.line)
					is IrcAction.EmitStatus -> send(IrcEvent.Status(a.text))
					is IrcAction.EmitError -> send(IrcEvent.Error(a.text))
					is IrcAction.EmitCapNew -> send(IrcEvent.CapNew(a.caps))
					is IrcAction.EmitCapDel -> send(IrcEvent.CapDel(a.caps))
					is IrcAction.EmitSts -> send(IrcEvent.StsReceived(config.host, config.useTls, a.port, a.durationSec))
					is IrcAction.EmitAuthFailed -> send(IrcEvent.AuthFailed(reason = a.reason, source = "SASL"))
				}

				// BATCH tracking (used for draft/chathistory, draft/event-playback, labeled-response).
				if (msg.command == "BATCH") {
					// allParams, not params: any of these three can arrive as the trailing
					// parameter and mean exactly the same thing.
					val idToken = msg.allParams.getOrNull(0) ?: continue
					if (idToken.startsWith("+")) {
						val id = idToken.drop(1)
						val type = msg.allParams.getOrNull(1) ?: ""
						if (type.contains("chathistory", ignoreCase = true) ||
							type.contains("event-playback", ignoreCase = true) ||
							type.contains("playback", ignoreCase = true)
						) {
							openPlaybackBatches.add(id)
							msg.allParams.getOrNull(2)?.takeIf { isChannelName(it) }
								?.let { playbackBatchTargets[id] = it }
						}
						// A CHATHISTORY batch names its target in param 2, which may be a nick
						// as readily as a channel. Recorded and announced so the ViewModel can
						// tell an explicit backfill's reply from ordinary catch-up replay.
						// A server wrapping its reply in a labeled-response batch puts the label
						// on the wrapper, so the inner batch looks up to its parent.
						val batchLabel = msg.tags["label"]?.takeIf { it.isNotBlank() }
							?: msg.tags["batch"]?.let { batchLabels[it] }
						if (batchLabel != null) {
							if (batchLabels.size >= MAX_TRACKED_BATCHES) {
								batchLabels.remove(batchLabels.keys.first())
							}
							batchLabels[id] = batchLabel
						}
						if (type.equals("soju.im/search", ignoreCase = true)) openSearchBatch(id, batchLabel)
						if (type.contains("chathistory", ignoreCase = true)) {
							val histTarget = msg.allParams.getOrNull(2)?.takeIf { it.isNotBlank() }
							if (histTarget != null) {
								// Evict rather than refuse: refusing left the close event unsent.
								if (chathistoryBatchTargets.size >= MAX_TRACKED_BATCHES) {
									chathistoryBatchTargets.remove(chathistoryBatchTargets.keys.first())
								}
								chathistoryBatchTargets[id] = histTarget
								// draft/chathistory-end on the opener means this page is the last
								// one. Servers that probe limit+1 to detect truncation send a
								// complete final page of exactly the requested size, so page
								// length alone cannot tell the client when to stop asking.
								val complete = if (msg.tags.containsKey("draft/chathistory-end")) true else null
								send(IrcEvent.HistoryBatchStart(histTarget, batchLabel, complete))
							}
						}
						// Batches nest, and playback-ness is inherited: a multiline message
						// replayed through CHATHISTORY opens an inner draft/multiline batch tagged
						// with the outer one, and its lines reference the inner id. Record the
						// parent so those lines count as replay.
						val parentBatch = msg.tags["batch"]
						if (parentBatch != null && openPlaybackBatches.contains(parentBatch)) {
							openPlaybackBatches.add(id)
							playbackBatchTargets[parentBatch]?.let { playbackBatchTargets[id] = it }
						}
						// netsplit/netjoin: track so we can collapse the JOIN/QUIT flood.
						if (type.equals("netsplit", ignoreCase = true) || type.equals("netjoin", ignoreCase = true)) {
							openNetsplitBatches[id] = type.lowercase()
						}
						// IRCv3 multiline: BATCH +<id> draft/multiline <target>
						// Captures the target and the BATCH-level tags (server-time, msgid,
						// account, etc.); inner PRIVMSG/NOTICE lines tagged with batch=<id>
						// will be buffered instead of emitted, and flushed together when
						// BATCH -<id> closes.
						if (type.equals("draft/multiline", ignoreCase = true) ||
							type.equals("multiline", ignoreCase = true)
						) {
							val target = msg.allParams.getOrNull(2) ?: ""
							if (target.isNotBlank() && openMultilineBatches.size < multilineMaxOpenBatches) {
								openMultilineBatches[id] = MultilineBatchState(
									target = target,
									command = "PRIVMSG",  // overwritten on first inner line
									openTags = msg.tags,
									openSenderPrefix = msg.prefix,
								)
							}
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
						playbackBatchTargets.remove(id)
						val closingLabel = batchLabels.remove(id)
						val closingLines = chathistoryBatchLines.remove(id) ?: 0
						chathistoryBatchTargets.remove(id)?.let {
							send(IrcEvent.HistoryBatchEnd(it, closingLabel, closingLines))
						}
						closeSearchBatch(this, id)
						// Flush a closing multiline batch as a single ChatMessage / Notice
						// event with the joined body. Per spec: lines without
						// +draft/multiline-concat get a "\n" separator; lines WITH it get
						// no separator (handles flood-control mid-paragraph splits).
						val mlState = openMultilineBatches.remove(id)
						if (mlState != null) {
							val joined = buildString {
								for ((idx, part) in mlState.parts.withIndex()) {
									if (idx > 0 && !part.second) append('\n')
									append(part.first)
								}
							}
							// Per spec, the BATCH command MAY carry a prefix but the
							// authoritative sender lives on each inner PRIVMSG/NOTICE line.
							// Prefer the inner-line prefix; fall back to the BATCH-line
							// prefix if for some reason the inner lines arrived unprefixed
							// (which would be a server bug, but we degrade gracefully).
							val effectivePrefix = mlState.innerSenderPrefix ?: mlState.openSenderPrefix
							val from = effectivePrefix?.substringBefore('!') ?: ""
							if (from.isNotBlank() && joined.isNotEmpty()) {
								val tagsTime = parseServerTimeMs(mlState.openTags)
								val msgid = mlState.openTags["msgid"]
								val account = mlState.openTags["account"]
								val replyTo = mlState.openTags["+draft/reply"] ?: mlState.openTags["+reply"]
								val isChan = isChannelName(mlState.target)
								// A PM's batch target is whoever received it, which for an incoming
								// message is us. Name the buffer after the other party, as the
								// single-line PRIVMSG path does, or the conversation opens against
								// our own nick.
								val convo = when {
									isChan -> mlState.target
									nickEquals(from, currentNick) -> mlState.target
									else -> from
								}
								val nowMs2 = System.currentTimeMillis()
								// Reuse the same is-history determination the per-message
								// path uses (msg-time + heuristic) so a multiline message
								// replayed via chathistory is still classified correctly.
								val isHistMl = isPlaybackHistory(mlState.openTags) ||
									isHeuristicHistory(mlState.target, tagsTime, nowMs2)
								val ownMl = if (nickEquals(from, currentNick) && !isPlaybackHistory(mlState.openTags)) {
									confirmEcho(
										notice = mlState.command.equals("NOTICE", ignoreCase = true),
										rawTarget = mlState.target,
										text = joined,
										label = closingLabel,
									)
								} else null
								if (mlState.command.equals("NOTICE", ignoreCase = true)) {
									send(IrcEvent.Notice(
										from = from,
										target = convo,
										text = joined,
										isPrivate = !isChan,
										isServer = false,
										timeMs = tagsTime,
										isHistory = isHistMl,
										msgId = msgid,
										replyToMsgId = replyTo,
									))
								} else {
									send(IrcEvent.ChatMessage(
										from = from,
										target = convo,
										text = joined,
										isPrivate = !isChan,
										isAction = false,  // multiline never carries CTCP wrapping
										multiline = true,
										timeMs = tagsTime,
										isHistory = isHistMl,
										msgId = msgid,
										replyToMsgId = replyTo,
										senderAccount = account,
										ownSendId = ownMl?.id,
									))
								}
							}
						}
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
								send(IrcEvent.ServerText("*** " + tr(R.string.core_netsplit, verb, serverInfo, "$count user${if (count == 1) "" else "s"}"), code = batchType.uppercase()))
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
				var numericText = formatNumeric(msg)

				// 464 ERR_PASSWDMISMATCH: the server PASS was rejected. Emit AuthFailed so the
				// ViewModel halts auto-reconnect instead of retrying the same password; the numeric
				// still renders below.
				if (msg.command == "464") {
					send(IrcEvent.AuthFailed(
						reason = msg.trailing ?: tr(R.string.core_password_incorrect),
						source = "PASS"
					))
				}

				// Bans waiting on a WHOIS for the target's user, host or account.
				if (pendingBansByNick.isNotEmpty()) handlePendingBanReply(msg)

				// Route WHOIS numerics back to the buffer where the WHOIS was invoked.
				val whoisTargetBuffer: String? = run {
					// Every numeric an ircd may answer WHOIS with. Anything missing here is
					// printed in the server buffer instead of beside the reply it belongs to.
					val whoisCodes = setOf(
						"276","301","307","310","311","312","313","317","318","319","320","330",
						"335","337","338","339","344","350","378","379","760",
						"616",
						"401","406",
						"671","672","673","674","675"
					)
					if (msg.command !in whoisCodes) return@run null
					val nick = msg.params.getOrNull(1) ?: return@run null
					val fold = casefold(nick)
					synchronized(pendingWhoisBufferByNick) {
						val buf = pendingWhoisBufferByNick[fold]
						if (buf != null && (msg.command == "318" || msg.command == "401" || msg.command == "406")) {
							pendingWhoisBufferByNick.remove(fold)
						}
						buf
					}
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
				// 324 and 329 answer a MODE <channel> query, including the one the channel-ops
				// drawer fires on open. Drop them for those, and otherwise route them to the
				// channel they describe rather than the status buffer.
				var modeNumericBuffer: String? = null
				if (msg.command == "329" || msg.command == "324") {
					val chan = msg.allParams.getOrNull(1)
					if (chan != null) {
						if (isSilentModeReply(chan)) numericText = null else modeNumericBuffer = chan
						// 329 ends the reply; after a 324 it may still follow.
						if (msg.command == "329") {
							finishModeQuery(casefold(chan))
							modeReplyOpen = null
						} else {
							modeReplyOpen = casefold(chan)
						}
					}
				}
				if (numericText != null && msg.command !in specialNumericCodes) {
					send(IrcEvent.ServerText(
						numericText,
						code = msg.command,
						bufferName = modeNumericBuffer ?: whoisTargetBuffer,
						isWhoisReply = whoisTargetBuffer != null,
					))
				} else if (msg.command.length == 3 && msg.command.all { it.isDigit() }
					&& msg.command !in setOf(
						"001",
						"315",
						"321","322","323",
						// 324/329 are the MODE-query pair.
						"324","329",
						"332","333",
						"352","353","354","366",
						"367","368",
						"346","347",
						"348","349",
						"728","729",
						"433",
						"471","472","473","474","475","476","477",
						// draft/metadata-2 numerics: the metadata handlers already surface
						// these (761/766 update the nick display silently; 770/771/772 emit a
						// friendly status line; 774 retries the sync). Suppress the raw
						// "[NNN] ..." fallback so they don't also spam the server window.
						"761","766","770","771","772","774",
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

				handleMessageCommand(this, msg, irc, serverTimeMs, playbackHistory, nowMs)
				} catch (e: kotlinx.coroutines.CancellationException) {
					throw e
				} catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
					throw e
				} catch (e: java.io.IOException) {
					throw e
				} catch (t: Throwable) {
					android.util.Log.w("IrcCore", "skipped a line that failed to process: ${msg.command}", t)
				}
			}

			// If the user requested a disconnect, don't surface "EOF" as an error.
			if (userClosing) {
				sendDisconnectedOnce(
					lastQuitReason ?: tr(R.string.core_disconnected),
					lastQuitCode,
				)
			} else {
				sendDisconnectedOnce(tr(R.string.core_disconnected), DisconnectCode.EOF)
			}
		} catch (t: Throwable) {
			val msg = friendlyErrorMessage(t)
			if (userClosing) {
				sendDisconnectedOnce(lastQuitReason ?: tr(R.string.core_disconnected), lastQuitCode)
			} else if (t is java.net.SocketTimeoutException) {
				// Read timeout: socket silent for 150 s (Doze/NAT killed it).
				// Reconnect quietly without showing a red error banner.
				sendDisconnectedOnce(
					tr(R.string.core_err_connection_timed_out),
					DisconnectCode.READ_TIMEOUT,
				)
			} else {
				// One Disconnected event prefixed with "Connection error: …".
				sendDisconnectedOnce(
					tr(R.string.core_disconnect_connection_error, msg),
					errorCode(t),
				)
			}
		} finally {
			joinedChannelCases.clear()
			runCatching { writerJob.cancel() }
			// Nothing written on this connection will be answered now.
			synchronized(awaitingEchoes) { awaitingEchoes.clear() }
			synchronized(modeQueries) { modeQueries.clear() }
			synchronized(whoRequests) { whoRequests.clear() }
			modeReplyOpen = null
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

}
