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
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxlabs.hexdroid.BuildConfig
import com.boxlabs.hexdroid.data.AutoJoinChannel
import com.boxlabs.hexdroid.data.ChannelListEntry
import com.boxlabs.hexdroid.data.ChannelPrefs
import com.boxlabs.hexdroid.data.ChannelPrefsCodec
import com.boxlabs.hexdroid.data.IgnoreRules
import com.boxlabs.hexdroid.data.IgnoreType
import com.boxlabs.hexdroid.data.NetworkProfile
import com.boxlabs.hexdroid.data.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.net.Socket
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

class IrcViewModel(
    internal val repo: SettingsRepository,
    context: Context
) : ViewModel() {
    /** Who spoke recently per channel, for the smart filter. Main thread only. */
    internal val smartFilter = SmartFilter()

    /** Buffers open in an expanded bubble, which count as on screen. */
    internal val bubblesShowing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Recent errors, oldest first, for the debug report. Guarded by itself. */
    internal val recentErrors = ArrayDeque<DebugError>()

    // ConcurrentHashMap used as a thread-safe set (touched from Main + IO).
    internal val scrollbackRequested: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    // Start time of scrollback loading, used to insert an end-of-scrollback marker before any live messages that arrived during load.
    /**
     * Timestamp of the newest line the on-disk log already holds for each buffer.
     *
     * The log is append-only and in order, so a replay stamped at or before this is already
     * in the file. Seeded from the scrollback load, advanced by each write; absent means
     * nothing is known yet and nothing is suppressed.
     */
    /**
     * Away message per network, restored on the next connection. Away is per-session on the
     * server, so it is otherwise lost on any disconnect.
     */
    internal val awayMessages: MutableMap<String, String> =
        java.util.concurrent.ConcurrentHashMap()

    /** Networks marked away by auto-away, which are the only ones it marks back. */
    internal val autoAwayNets: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Waits out the background delay before auto-away. */
    internal var autoAwayJob: Job? = null
    /** Auto-away is in effect: the app has been in the background long enough. */
    @Volatile internal var autoAwayActive = false

    /** Per buffer, the newest server-stamped line written to the log. */
    internal val lastLoggedServerMs: MutableMap<String, Long> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Per buffer, the newest line already logged when the current connection began, from the log
     * on disk or from earlier connections. A replayed line at or before it is on disk already; a
     * newer one, such as a bouncer playing back what it held while we were away, is not.
     */
    internal val loggedBeforeConnectMs: MutableMap<String, Long> =
        java.util.concurrent.ConcurrentHashMap()

    /** A disk scrollback read in progress. [key] follows the buffer if it is renamed meanwhile. */
    internal class ScrollbackLoad(val startedAtMs: Long, @Volatile var key: String)

    internal val scrollbackLoads: MutableMap<String, ScrollbackLoad> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Newest timestamp of the disk block merged into each buffer. Lines placed out of order
     * stop below it rather than sinking into or above the logged stretch.
     */
    internal val scrollbackFloorMs: MutableMap<String, Long> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Newest server-time seen on live traffic per network. Staleness is judged against this
     * rather than the device clock, which may be set wrong.
     */
    internal val newestLiveServerTimeMs: MutableMap<String, Long> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Replayed lines held while a buffer's disk scrollback is still being read.
     *
     * Server playback follows a join within milliseconds and the read does not, so without
     * this the playback fills an empty buffer and the log block is placed under it. Held
     * here, the log arrives first and the playback deduplicates and sorts against it.
     */
    internal val deferredReplays: MutableMap<String, MutableList<(String) -> Unit>> =
        java.util.concurrent.ConcurrentHashMap()

    /** Releases a buffer's held replays if its scrollback read never finishes. */
    internal val deferredReplayTimeouts: MutableMap<String, Job> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * CHATHISTORY requests in flight, the divider windows, and the catch-up throttle.
     *
     * Everything to do with asking a server for history and recognising the reply lives here.
     * The ViewModel keeps the parts that need its state and its IO: choosing an anchor,
     * sending, and merging a finished page into the buffer.
     */
    internal val chatHistory: ChatHistoryController by lazy {
        ChatHistoryController(
            scope = viewModelScope,
            onFinished = { result -> onBackfillFinished(result) },
            onCatchupPage = { page -> onCatchupPage(page) },
        )
    }

    @SuppressLint("StaticFieldLeak")
    internal val appContext: Context = context.applicationContext

    internal val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /**
     * Incoming 322 LIST entries, flushed to state on a time throttle and once more at 323, rather
     * than copying state per entry. Cleared on ListStart.
     */
    internal val _channelListBuffer = ArrayList<ChannelListEntry>()
    /**
     * Wall-clock (elapsedRealtime) of the last time the streaming LIST buffer was pushed to
     * [_state]. Flushing is time-throttled rather than per-N-items: on large networks (Libera)
     * each RPL_LIST line is consumed on the Main thread, and a UI flush triggers a recomposition
     * of the channel list.
     */
    internal var _channelListLastFlushMs = 0L
    internal companion object {
        /**
         * Every "* "-prefixed status line this client writes, read by [statusLineFirstWords];
         * add new banners here. In the companion rather than an instance field: the scrollback
         * reader calls that function from its own thread, which can start before the
         * constructor has worked through every property initialiser, and an instance field
         * read ahead of its initialiser is null.
         */
        val STATUS_LINE_BANNERS = intArrayOf(
            R.string.vm_deleted_message, R.string.vm_ev_back, R.string.vm_ev_has_joined,
            R.string.vm_ev_has_left, R.string.vm_ev_has_quit, R.string.vm_ev_host_now,
            R.string.vm_ev_has_joined_nohost, R.string.vm_ev_has_left_nohost, R.string.vm_ev_has_quit_nohost,
            R.string.vm_ev_invited_you, R.string.vm_ev_kicked, R.string.vm_ev_logged_out,
            R.string.vm_ev_nick_logged_in_as, R.string.vm_ev_now_away, R.string.vm_ev_now_away_msg,
            R.string.vm_ev_now_known_as, R.string.vm_ev_now_talking, R.string.vm_ev_realname_changed,
            R.string.vm_ev_topic_changed, R.string.vm_ev_topic_changed_by, R.string.vm_ev_topic_is,
            R.string.vm_ev_topic_set_by, R.string.vm_ev_you_kicked, R.string.vm_ev_you_left,
            R.string.vm_ev_you_logged_in_as, R.string.vm_ev_you_logged_out,
            R.string.vm_ev_you_now_known_as, R.string.vm_ev_your_host_now,
            R.string.vm_ev_your_realname_now, R.string.vm_invited_here, R.string.vm_mode_change,
        )

        /** Minimum gap between streaming LIST UI flushes. See [_channelListLastFlushMs]. */
        const val CHANNEL_LIST_FLUSH_INTERVAL_MS = 300L
        /** How many replayed lines one buffer holds while its scrollback is being read. */
        const val MAX_DEFERRED_REPLAYS = 400
        /** How long held replays wait for a scrollback read that has not reported back. */
        const val DEFERRED_REPLAY_TIMEOUT_MS = 12_000L
        /**
         * How far behind the network's newest live server-time a line delivered as live must be
         * to be handled as a replay for logging, unread and notifications.
         */
        const val STALE_LIVE_LINE_MS = 10 * 60_000L
        /** Sent lines awaiting their echo per network; the outbound queue holds 300. */
        const val MAX_PENDING_OWN_ECHOES = 300
        /** Errors kept for the debug report. */
        const val MAX_DEBUG_ERRORS = 30
        /** Bound on reactions waiting for their message in one buffer. */
        const val MAX_PENDING_REACTIONS = 32
        /** Largest backwards step [settleLogOrder] will correct. */
        const val LOG_REORDER_WINDOW_MS = 60_000L
        /** How long after our own join a notice counts as that channel's entry notice. */
        const val ENTRY_NOTICE_WINDOW_MS = 15_000L
        /** How long a declined script file pick keeps refusing requests the user did not start. */
        const val SCRIPT_PICK_QUIET_MS = 60_000L
        /** Quiet time after the last replayed line before the replay summary is posted. */
        const val REPLAY_SUMMARY_QUIET_MS = 4_000L
        /** How long after registration a replay can begin and still be summarised. */
        const val REPLAY_SUMMARY_OPEN_MS = 60_000L
        /** How many older messages one "load older" request asks the server for. */
        /**
         * Default upper user-count bound for ELIST range queries when the user hasn't set a max
         * (see requestList).
         */
        const val DEFAULT_LIST_MAX_USERS = 10000
        /**
         * Capacity of the buffer that decouples the per-connection socket read loop from
         * Main-thread event handling (see the events().buffer(...) call). Sized far above any
         * realistic /LIST so a full channel-list burst never blocks the reader, while still
         * bounding memory and re-applying backpressure under a pathological flood. Each buffered
         * IrcEvent is small; the worst-case transient footprint is a few MB.
         */
        const val EVENT_DRAIN_BUFFER_CAPACITY = 65536
        /**
         * Delay between successive connectNetwork() calls when fanning out autoconnect or
         * restoring a set of keep-alive connections. ~500 ms is enough to satisfy typical
         * bouncer and IRCd "reconnect too fast" rate limits (soju defaults to one new TCP
         * per second per source IP) without being perceptible to the user.
         */
        const val CONNECT_FAN_OUT_DELAY_MS = 500L
        /**
         * Suppression window for auto-rejoin after a kick. A second kick on the same channel
         * within this window will NOT trigger another rejoin attempt - protects against
         * loops when the user can't satisfy a channel mode (+i, +k, +b) or is being
         * deliberately kick-banned by an op.
         */
        const val AUTO_REJOIN_SUPPRESS_MS = 60_000L
        /** Small delay before sending the rejoin so it doesn't feel adversarial to the kicker. */
        const val AUTO_REJOIN_DELAY_MS = 1500L
        /**
         * How long a local echo can suppress a bouncer's replay of the same message after a
         * reconnect. Only replayed lines are suppressed, never live ones.
         */
        const val SELF_SEND_RETAIN_MS = 15 * 60_000L

        /** Fish used by the /slap command, picked at random. */
        val SLAP_FISH = listOf(
            "a large trout", "a slippery eel", "a giant squid", "a rubber halibut",
            "a flapping salmon", "a tiny herring", "a fierce piranha", "a bucket of sardines",
            "a frozen mackerel", "an irate pufferfish", "a soggy catfish", "a wet flounder",
            "a tin of anchovies", "a startled clownfish", "a barracuda", "a sturgeon",
            "a confused cod", "a haddock", "a swordfish (flat side)", "a jellyfish",
            "an electric eel", "a koi carp", "a sardine", "an anchovy", "a kipper",
        )

        /** Time allowed to resolve a server name when checking whether it is on the local network. */
        internal const val LAN_LOOKUP_TIMEOUT_MS = 5_000

        /** A picker MIME filter given to /dcc send: type/subtype, either part may be *. */
        val DCC_MIME_FILTER = Regex("^(\\*|[A-Za-z0-9][A-Za-z0-9.+-]*)/(\\*|[A-Za-z0-9][A-Za-z0-9.+-]*)$")

        /**
         * Matches the substitution tokens in a command-alias template: $channel, $chan,
         * $network, $net, $server, $nick, $me, $*, $1..$9, and $$ (a literal dollar).
         */
        val ALIAS_VAR = Regex(
            "\\$\\$|\\$(channel|chan|network|net|server|nick|me|\\*|[1-9])(?![A-Za-z0-9])",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Minimum gap between the newest logged line and the start of this session before a
         * scrollback divider is worth drawing.
         */
        const val SCROLLBACK_DIVIDER_MIN_GAP_MS = 5_000L

        /**
         * How often the visible channel is re-queried for away status.
         *
         * away-notify carries the transitions on servers that have it, so this is a safety
         * net rather than the primary source and does not need to be frequent.
         */
        const val AWAY_POLL_INTERVAL_MS = 120_000L

        /** Channels larger than this are not polled: the reply is too big to be worth it. */
        const val AWAY_POLL_MAX_MEMBERS = 300
        /** Largest channel that gets a member WHO as soon as it is joined. */
        const val WHO_ON_JOIN_MAX_MEMBERS = 150

        /** Shortest gap between away lookups for one nick's sheet. */
        const val NICK_AWAY_LOOKUP_GAP_MS = 60_000L

        /** Away states tracked per network before those of people we no longer see are dropped. */
        const val MAX_TRACKED_AWAY = 2000

        /** Max chained alias expansions before we give up (alias-invokes-alias loop guard). */
        const val MAX_ALIAS_DEPTH = 8
        /**
         * After a reconnect, how long self-JOIN echoes count as automatic rejoins that don't switch
         * the active buffer. An explicit /join still switches ([NetRuntime.pendingUserJoinSwitch]).
         */
        const val AUTO_JOIN_SWITCH_SUPPRESS_MS = 45_000L
    }

    internal data class NamesRequest(
        val replyBufferKey: String,
        val printToBuffer: Boolean = true,
        val createdAtMs: Long = android.os.SystemClock.elapsedRealtime(),
        val names: LinkedHashSet<String> = linkedSetOf()
    )

    data class NetSupport(
        val chantypes: String = "#&",
        val caseMapping: String = "rfc1459",
        val prefixModes: String = "qaohv",
        val prefixSymbols: String = "~&@%+",
        val statusMsg: String? = null,
        val chanModes: String? = null,
        /**
         * LINELEN from ISUPPORT 005: max bytes per IRC line including CRLF.
         * Null = server didn't advertise it; treat as the RFC 1459 default of 512.
         */
        val linelen: Int? = null,
        /**
         * ELIST token from ISUPPORT 005 (uppercased): supported server-side LIST filters.
         * Contains 'U' when "LIST >N" / "LIST <N" user-count filtering is available.
         */
        val elist: String? = null
    )

    internal data class NetRuntime(
        val netId: String,
        val client: IrcClient,
        var job: Job? = null,
        var suppressMotd: Boolean = false,
        var manualMotdAtMs: Long = 0L,
        var myNick: String = client.config.nick,
		val namesRequests: MutableMap<String, NamesRequest> = mutableMapOf(),
		// Throttled to avoid spamming the server when the nicklist opens/closes rapidly.
		val lastNamesRefreshAtMs: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap(),
        /** Channels just joined whose member WHO waits for the join's member list, by names fold. */
        val whoAfterJoin: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
        /** Channels too large for a WHO on join, which get one when their member list is shown. */
        val whoDeferred: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
        var support: NetSupport = NetSupport(),
        // Manually-joined channels not covered by autoJoin, rejoined on reconnect.
        // Key = channel name (server casing), value = channel key or null.
        val manuallyJoinedChannels: MutableMap<String, String?> = mutableMapOf(),
        /** Queries whose read marker was requested on this connection, casefolded. */
        val queryReadMarkersRequested: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
        // Channels the user EXPLICITLY asked to JOIN this session (join button or a typed
        // /join). A self-JOIN echo for one of these always switches the active buffer to it,
        // even inside the post-reconnect suppression window below. Folded channel names;
        // entries are consumed (removed) when the matching self-JOIN arrives.
        val pendingUserJoinSwitch: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet(),
        // Until this time, self-JOINs we didn't explicitly request (the rejoin burst after a
        // reconnect, including bouncer-replayed JOINs) don't switch the active buffer. Set only on
        // a reconnect, so a first connect still opens an autojoin channel.
        @Volatile var suppressAutoJoinSwitchUntilMs: Long = 0L,
        // The Network this connection's socket was established on (captured at Connected).
        // Used by the ConnectivityManager onLost callback to detect a Wi-Fi<->cellular
        // handoff: when the interface a live socket is riding goes away but other
        // connectivity remains (failover), the socket is dead yet readLine() would block
        // until SOCKET_READ_TIMEOUT_MS (150 s). Matching the lost Network against this lets
        // us drop and reconnect immediately instead of waiting out that timeout.
        @Volatile var boundNetwork: android.net.Network? = null,
        /** Bouncer network edits awaiting the bouncer's answer, oldest first. */
        val bouncerOps: ArrayDeque<BouncerOp> = ArrayDeque(),
        /** soju.im/muted values listed on this connection before the subscription was confirmed. */
        val muteSync: MuteSync = MuteSync(),
        /** The notify list has been sent with MONITOR on this connection. */
        @Volatile var notifyListSent: Boolean = false,
    )

    // These per-network maps and sets are read and written from each network's event coroutine
    // concurrently, so they are ConcurrentHashMap-backed; a plain HashMap can corrupt under
    // concurrent writes. Read-modify-write sequences still need their own care.
    internal val runtimes: MutableMap<String, NetRuntime> = java.util.concurrent.ConcurrentHashMap()

    internal val desiredConnected: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    internal var desiredNetworkIdsLoaded = false
    internal var desiredNetworkIdsApplied = false
    internal val autoReconnectJobs: MutableMap<String, Job> = java.util.concurrent.ConcurrentHashMap()
    internal val reconnectAttempts: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()
    /**
     * Per-network jobs that fire after STABLE_CONNECTION_MS of uptime to reset the
     * reconnect backoff counter. Cancelled on disconnect so a short-lived connection
     * (e.g. a Z-lined or immediately-dropped session) never clears the backoff,
     * preserving exponential back-off across rapid connect/disconnect cycles.
     */
    internal val stableConnectionJobs: MutableMap<String, Job> = java.util.concurrent.ConcurrentHashMap()
    // Cache the last label/status sent to the foreground service notification so we can
    // skip the startService() Binder IPC when nothing has changed. Every setNetConn() call
    // (lag updates, status changes, etc.) goes through refreshConnectionNotification(),
    // which would otherwise fire an IPC and wake the NotificationManager on every ping.
    internal var lastNotifLabel: String? = null
    internal var lastNotifStatus: String? = null
    /** Whether [lastNotifLabel]/[lastNotifStatus] went to the service or to a plain notification. */
    internal var lastNotifViaService = false
    /** Pending keep-alive notification update: a burst of status changes posts only the last. */
    internal var keepAliveUpdateJob: Job? = null
    internal val KEEPALIVE_UPDATE_COALESCE_MS = 300L

    internal val manualDisconnecting: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    internal val noNetworkNotice: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Networks already told on their server buffer that the boot connect is waiting for Wi-Fi.
     * Separate from [noNetworkNotice] so the two notices don't suppress each other.
     */
    internal val waitingForWifiNotice: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Networks whose connect was held until connectivity, or Wi-Fi at boot, is available.
     * These are finished when the network returns even with auto-reconnect off, since
     * the connect was already asked for and never attempted.
     */
    internal val deferredConnects: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Networks already told, on their server buffer, that the push subscription was
     * rejected. Cleared when a registration succeeds so the next failure is reported.
     */
    internal val webPushFailureNotice: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Networks whose last attempt failed authentication (464, SASL 904/905/906). Auto-reconnect
     * skips them until the user reconnects, edits the profile or toggles autoConnect.
     */
    internal val authBlockedReconnect: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Networks that have registered at least once in this process, so any later registration counts
     * as a reconnect and arms [NetRuntime.suppressAutoJoinSwitchUntilMs]. Not cleared on
     * disconnect; cleared only when the profile is removed.
     */
    internal val everRegisteredThisSession: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Replayed lines counted since registration, until the summary is posted. */
    internal class ReplayTally(val previousDisconnect: String?) {
        var lines = 0
        val buffers = HashSet<String>()
        var lastAtMs = 0L
        var job: Job? = null
    }

    /** Per network, the replay being counted for this connection. */
    internal val replayTallies: MutableMap<String, ReplayTally> =
        java.util.concurrent.ConcurrentHashMap()

    /** Per network, why the last connection that was up ended. */
    internal val previousDisconnect: MutableMap<String, String> =
        java.util.concurrent.ConcurrentHashMap()

    // Ping-timeout disconnect times per network for flap detection: FLAP_THRESHOLD within
    // FLAP_WINDOW_MS pauses auto-reconnect. Each inner deque is only touched from its own network's
    // ping loop.
    internal val pingTimeoutTimestamps: MutableMap<String, ArrayDeque<Long>> =
        java.util.concurrent.ConcurrentHashMap()

    // Flap-paused state is persisted via DataStore
    // DataStore is the rest of the app's persistence layer and is immune to the data-loss
    // bugs that SharedPreferences can exhibit under process death on certain OEM ROMs.
    //
    // In-memory set for fast synchronous checks during event handling; the DataStore copy
    // is the durable source of truth that survives process kills.
    internal val flapPaused: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    internal var flapPausedLoaded = false

    /**
     * IRCv3 STS policies keyed by lowercased hostname, hydrated lazily from DataStore
     * (see ensureStsPoliciesLoaded). While a host's entry is unexpired, every connection
     * to it is forced onto TLS with allowInvalidCerts overridden to false, per spec.
     */
    /**
     * The user's own metadata values, persisted per network id then key, and re-applied
     * on connect so they survive reconnects even on servers that don't store them.
     */
    internal val ownMetadataStore: MutableMap<String, MutableMap<String, String>> =
        java.util.concurrent.ConcurrentHashMap()
    internal var ownMetadataLoaded = false

    /**
     * Guards hydration and persistence of [ownMetadataStore].
     *
     * The editor saves one key per coroutine, so several run at once against a store that is
     * still being read from disk and a persisted blob covering every network.
     */
    internal val ownMetadataLock = Mutex()

    internal val stsPolicies: MutableMap<String, StsPolicyEntry> =
        java.util.concurrent.ConcurrentHashMap()
    internal var stsPoliciesLoaded = false

    /**
     * One-shot TLS ports learned mid-connection from an insecure CAP LS `sts port=<n>`.
     * Consumed by the immediate secure retry in connectNetworkInternal. Deliberately NOT
     * persisted: the durable policy is only written once the secure connection confirms
     * it by advertising a duration, per the spec's trust-on-verify flow.
     */
    internal val stsUpgradePorts: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Tracks a repeated connection-status line per network, so a repeat within
     * [CONN_STATUS_DEDUP_WINDOW_MS] updates the existing line with a "(×N)" count instead of adding
     * another.
     */
    internal data class ConnStatusDedupEntry(
        /** "$from|$text" identity key must match exactly for dedup. */
        val key: String,
        /** Original text without any "(×N)" suffix. Used to construct the updated string. */
        val baseText: String,
        /** Original sender field. Used together with [baseText] to find the message in the buffer. */
        val from: String?,
        /** Most-recent occurrence epoch ms. Drives the [CONN_STATUS_DEDUP_WINDOW_MS] window. */
        val lastSeenMs: Long,
        /** How many times we've seen this line so far (1 = original; subsequent hits >2). */
        val count: Int,
        /** Buffer the original message was appended to (always the *server* buffer in practice). */
        val bufferKey: String,
    )
    internal val lastConnStatusLine: MutableMap<String, ConnStatusDedupEntry> =
        java.util.concurrent.ConcurrentHashMap()
    internal val connStatusLock = Any()
    internal val CONN_STATUS_DEDUP_WINDOW_MS = 60_000L
    /**
     * The last server `ERROR :...` per network, as (text, epoch ms), so a disconnect with a generic
     * reason can recover the server's explanation (e.g. "SASL required"). Only used within
     * [SERVER_ERROR_DISCONNECT_CORRELATION_MS]; cleared on registration.
     */
    internal val lastServerErrorByNet: MutableMap<String, Pair<String, Long>> =
        java.util.concurrent.ConcurrentHashMap()
        internal val SERVER_ERROR_DISCONNECT_CORRELATION_MS = 5_000L

    /** Serialises loading and read-modify-write persistence of the flap-paused map. */
    internal val flapPausedLock = Mutex()

    /** Serialises loading of [stsPolicies], so no caller sees it before the read completes. */
    internal val stsLoadLock = Mutex()

    /**
     * Per-network jobs that end a flap pause after FLAP_WINDOW_MS, so auto-reconnect resumes on its
     * own. The backoff counter is kept, so resuming stays gentle on the server.
     */
    internal val flapResumeJobs: MutableMap<String, kotlinx.coroutines.Job> =
        java.util.concurrent.ConcurrentHashMap()

    // Not persisted; resets to all-expanded on process restart.
    internal val _collapsedNetworkIds = MutableStateFlow<Set<String>>(emptySet())
    /**
     * The buffer-list search button: the same as typing `/find <query>` in the selected buffer, or
     * `/gsearch` across the network when [global].
     */
    fun searchFromToolbar(query: String, global: Boolean = false) {
        val q = query.trim()
        if (q.isBlank()) return
        val st = _state.value
        val currentKey = st.selectedBuffer.takeIf { it.isNotBlank() } ?: return
        val (netId, _) = splitKey(currentKey)
        val matches: List<UiMessage> = if (global) {
            st.buffers
                .filter { (k, _) -> splitKey(k).first == netId }
                .flatMap { (_, buf) ->
                    buf.messages.filter {
                        it.text.contains(q, ignoreCase = true) ||
                            it.from?.contains(q, ignoreCase = true) == true
                    }
                }
                .sortedBy { it.timeMs }
        } else {
            (st.buffers[currentKey]?.messages.orEmpty()).filter {
                it.text.contains(q, ignoreCase = true) ||
                    it.from?.contains(q, ignoreCase = true) == true
            }
        }
        if (matches.isEmpty()) {
            append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_no_matches, q), isLocal = true, doNotify = false)
            return
        }
        _state.value = st.copy(
            findOverlay = FindOverlay(
                query = q,
                matchIds = matches.map { it.id },
                currentIndex = matches.lastIndex,
                bufferKey = if (global) "GLOBAL:$netId" else currentKey,
            )
        )
    }

    fun toggleNetworkExpanded(netId: String) {
        _collapsedNetworkIds.update { current ->
            if (current.contains(netId)) current - netId else current + netId
        }
    }

    /**
     * Collapse every network in the buffer drawer in one action. Triggered by the drawer's
     * top-bar "collapse all" button. If every network is already collapsed, expand them all
     * instead (toggle behaviour) so the same button does the right thing on a second tap.
     */
    fun collapseOrExpandAllNetworks() {
        val st = _state.value
        val allIds = st.networks.map { it.id }.toSet()
        if (allIds.isEmpty()) return
        _collapsedNetworkIds.update { current ->
            // If every network is currently collapsed, expand them all; otherwise collapse all.
            if (allIds.all { it in current }) emptySet() else allIds
        }
    }

    /**
     * Mark every buffer (across every network) as read: clear the unread + highlight counters
     * and stamp a fresh lastReadTimestamp matching the newest message in each buffer so the
     * unread separator lands at the bottom on next view. Triggered by the drawer's "clear
     * unread" button. No server-side MARKREAD is sent here, that's only emitted when the
     * user actually views a buffer; this is purely a local "I've seen everything" sweep.
     */
    fun markAllBuffersRead() {
        _state.update { st ->
            if (st.buffers.isEmpty()) return@update st
            val nowIso = java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 1L).toString()
            val newBuffers = st.buffers.mapValues { (_, buf) ->
                if (buf.unread == 0 && buf.highlights == 0) buf
                else {
                    val lastTs = buf.messages.lastOrNull()?.timeMs
                    val newLastRead = if (lastTs != null) {
                        java.time.Instant.ofEpochMilli(lastTs + 1L).toString()
                    } else nowIso
                    buf.copy(unread = 0, highlights = 0, lastReadTimestamp = newLastRead)
                }
            }
            st.copy(buffers = newBuffers)
        }
    }

    internal val netOpLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    /**
     * True while a boot-time connect is holding out for Wi-Fi.
     *
     * Set by [BootReceiver] before the ViewModel is constructed, because the decision
     * belongs to the boot path only: a connect the user asks for, on mobile data, is a
     * connect they meant.
     */
    internal var bootWifiWait = BootConnectGate.waitForWifi.also { BootConnectGate.waitForWifi = false }

    internal val desiredPersistLock = Mutex()

    // PART is sent when the user closes a buffer; the buffer is removed when we receive our own PART back.
    internal val pendingCloseAfterPart: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Volatile internal var appExitRequested: Boolean = false

    // Per-channel nick prefix tracking. Outer key = bufferKey, inner key = case-folded nick.
    // chanNickCase / chanNickStatus / nickAwayState: outer maps are concurrent. Inner
    // maps stay as plain mutableMapOf because they're only mutated under per-channel
    // event sequences (NAMES/JOIN/PART/QUIT for one channel arrive serially from one
    // network's events flow, so the inner maps don't see concurrent writers in practice).
    internal val chanNickCase: MutableMap<String, MutableMap<String, String>> =
        java.util.concurrent.ConcurrentHashMap()
    internal val chanNickStatus: MutableMap<String, MutableMap<String, MutableSet<Char>>> =
        java.util.concurrent.ConcurrentHashMap()

    internal val nickAwayState: MutableMap<String, MutableMap<String, String>> =
        java.util.concurrent.ConcurrentHashMap()

    // Auto-rejoin throttle. Key = "$netId::${chan.lowercase()}", value = epoch-ms of the last
    // auto-rejoin attempt. Used to suppress repeat rejoins within AUTO_REJOIN_SUPPRESS_MS so a
    // user who is being kick-banned (or who can't satisfy +i / +k) doesn't get into an
    // immediate kick → rejoin → kick loop. One auto-rejoin per minute per channel is plenty.
    internal val recentKickRejoins: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    /**
     * When we last joined each buffer, so a service bot's welcome NOTICE arriving within a few
     * seconds can be attributed to that channel even when it doesn't name it.
     */
    internal val recentJoinAtMs: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    internal var autoConnectAttempted = false

    internal val notifier = NotificationHelper(appContext)
    internal val logs = LogWriter(appContext)

    /**
     * Shared E2E keystore. One instance per ViewModel (and therefore per app process),
     * since per-target keys are persisted via SecretStore and need to be visible across
     * every network's IrcClient. The keystore lazily hydrates per-network from the
     * underlying SharedPreferences on first access, so networks that never use E2E
     * pay zero startup cost.
     */
    val e2eKeyStore = com.boxlabs.hexdroid.crypto.E2eKeyStore(repo.secretStore)
    internal val dcc = DccManager(appContext)
    internal val dccPartials = DccResumeStore(appContext)

    internal data class DccChatSession(
        val netId: String,
        val peer: String,
        val bufferKey: String,
        val socket: Socket,
        val writer: BufferedWriter,
        val readJob: Job
    )

    internal val dccChatSessions: MutableMap<String, DccChatSession> =
        java.util.concurrent.ConcurrentHashMap()

    /** DCC chats still listening for, or dialling, the peer, by buffer key. */
    internal val dccChatPending = java.util.concurrent.ConcurrentHashMap<String, Job>()

    internal data class PendingPassiveDccSend(
        val target: String,
        val filename: String,
        val size: Long,
        val reply: CompletableDeferred<DccOffer>
    )

    internal val pendingPassiveDccSends: MutableMap<Long, PendingPassiveDccSend> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Jobs for in-progress outgoing DCC sends, keyed by "$target/$filename".
     * Stored so the user can cancel a send from the Transfers screen.
     */
    internal val outgoingSendJobs: MutableMap<String, kotlinx.coroutines.Job> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Tracks in-flight DCC receive coroutines, keyed by the offer. Mirrors [outgoingSendJobs]
     * so the user can cancel an incoming transfer from the Transfers screen X button.
     * Cancelling the Job triggers [DccManager]'s `invokeOnCompletion` socket-close, which
     * unblocks the receive loop and aborts the transfer.
     */
    internal val incomingReceiveJobs: MutableMap<DccOffer, kotlinx.coroutines.Job> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * An outgoing DCC send waiting for its peer, keyed by `nick|baseName|size`, so an incoming DCC
     * RESUME can find it. [startOffset] receives the agreed offset once we have replied with DCC
     * ACCEPT.
     */
    internal data class LiveOutgoingSend(
        val target: String,
        val filename: String,
        val absolutePath: String,
        val size: Long,
        /** Active SEND's listening port, or 0 for passive sends. */
        val port: Int,
        /** Passive-DCC token, or null for active sends. */
        val token: Long?,
        /** Completes when peer sends DCC RESUME; surfaces the start offset to honour. */
        val resumeRequest: CompletableDeferred<Long> = CompletableDeferred()
    )
    internal val liveOutgoingSends: MutableMap<String, LiveOutgoingSend> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Pending DCC RESUME requests *we* sent, keyed by `peer.lowercase()|baseName|size`.
     * Completes when the peer replies with DCC ACCEPT confirming the offset.
     */
    internal val pendingResumeRequests: MutableMap<String, CompletableDeferred<DccAccept>> =
        java.util.concurrent.ConcurrentHashMap()

    internal val nextUiMsgId = AtomicLong(1L)

    internal val logTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * A line shown when we sent it, awaiting the server's answer to each of its [sendIds] (one per
     * multiline batch), which are removed as they are answered.
     */
    internal class PendingOwnEcho(
        val bufferKey: String,
        val text: String,
        val isAction: Boolean,
        val sendIds: MutableList<Long>,
    ) {
        val batches = sendIds.size
        /** The line's log entry, held until the server answers so it can carry the server's time. */
        var heldLog: HeldOwnLine? = null
    }

    /** Sent lines awaiting an answer per network, oldest first. Each deque guards itself. */
    internal val pendingOwnEchoes: MutableMap<String, ArrayDeque<PendingOwnEcho>> =
        java.util.concurrent.ConcurrentHashMap()

    /** One of our sent lines answered: [finished] when that was the last of its batches. */
    internal class OwnSendAnswer(val line: PendingOwnEcho, val finished: Boolean)

    /**
     * Per buffer, the (signature, insert time) of each local echo, used to drop a bouncer's replay
     * of our own messages after a reconnect (the replay's server time never matches the echo's
     * local time). Matching consumes the entry; entries expire after [SELF_SEND_RETAIN_MS] and are
     * capped per buffer.
     */
    internal val recentSelfSends: MutableMap<String, ArrayDeque<Pair<Long, String>>> =
        java.util.concurrent.ConcurrentHashMap()

    /** Pseudo-buffer holding the raw protocol log, alongside the existing "*server*". */
    internal val RAW_BUFFER = "*raw*"

    /**
     * Nicks we've recently held on each network, so our own messages replayed via CHATHISTORY are
     * recognised as ours after reconnecting on a fallback nick (e.g. "eck_" while a ghost session
     * still holds "eck"). Survives reconnect, expires after [SELF_SEND_RETAIN_MS], and is refreshed
     * on every self-send.
     */
    internal val recentOwnNicks: MutableMap<String, MutableMap<String, Long>> =
        java.util.concurrent.ConcurrentHashMap()

    // Declared above the init block, which registers the network callback: property initialisers
    // run in source order, and a callback firing during construction must find these initialised.
    internal var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** Last-seen NET_CAPABILITY_VALIDATED per live Network handle, so onCapabilitiesChanged can
     *  act on the not-validated -> validated EDGE only (the callback also fires for signal
     *  strength and bandwidth churn). Entries are dropped in onLost. */
    internal val validatedNetworks = java.util.concurrent.ConcurrentHashMap<android.net.Network, Boolean>()

    /** Scope for teardown work that must outlive the ViewModel. */
    internal val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    /**
     * The graceful-quit hook this instance installed, kept so onCleared can withdraw exactly
     * the one it put there. A newer ViewModel may have replaced it in the meantime, and
     * clearing the field blindly would disarm the live one.
     */
    private val gracefulQuitHook: (onDone: () -> Unit) -> Boolean = ::onTaskRemovedGracefulQuit

    init {
        // Let KeepAliveService request a clean QUIT if the user swipes the app away while
        // background persistence is off (see onTaskRemovedGracefulQuit). Cleared in onCleared.
        KeepAliveService.gracefulQuitOnSwipe = gracefulQuitHook
        launchExpandedNetworkIdsSync()
        launchNotificationAvatarSync()
        startAwayPolling()
        // Hydrate flap-paused state from DataStore before any connections start.
        // This must be a suspend call, so we run it in viewModelScope. It completes almost
        // instantly (single DataStore read) and sets flapPausedLoaded=true so the lazy guard
        // in ensureFlapPausedLoaded() is a no-op on any subsequent call.
        viewModelScope.launch { runCatching { ensureFlapPausedLoaded() } }
        // Hydrate STS policies early too, so a startup auto-connect to a policy-covered
        // host is TLS-enforced from the very first attempt.
        viewModelScope.launch { runCatching { ensureStsPoliciesLoaded() } }
        // Expose "log folder is unreadable" to Settings: LogWriter's unreadable-URI set combined
        // with the saved log folder. A separate collector so the warning appears as soon as
        // LogWriter discovers the problem.
        viewModelScope.launch {
            logs.unreadableTreeUrisFlow.collect { unreadable ->
                _state.update { st ->
                    val current = st.settings.logFolderUri
                    val isUnreadable = !current.isNullOrBlank() && unreadable.contains(current)
                    if (st.logFolderUnreadable == isUnreadable) st
                    else st.copy(logFolderUnreadable = isUnreadable)
                }
            }
        }
        viewModelScope.launch {
            repo.migrateLegacySecretsIfNeeded()
            repo.migrateLegacyQuitMessageIfNeeded()
            var prevLogFolderUri: String? = null
            var lastPurgeKey: Triple<Boolean, Int, String?>? = null
            repo.settingsFlow.collect { s ->
                val st = _state.value
                val applyDefaults = st.settings == UiSettings()
                // When the user re-picks the log folder (even to the same URI), give
                // LogWriter's "unreadable" cache for that URI a fresh shot - re-picking
                // grants a new persistable permission, so even if the URI string is the
                // same the access situation has changed and we shouldn't keep returning
                // empty results from the cached "this is dead" state.
                val newLogUri = s.logFolderUri
                if (newLogUri != prevLogFolderUri) {
                    if (!newLogUri.isNullOrBlank()) logs.clearUnreadable(newLogUri)
                    prevLogFolderUri = newLogUri
                }
                // Recompute the unreadable flag against the new settings.logFolderUri:
                // when the user picks a fresh folder we want the warning to clear
                // immediately, without waiting for the next LogWriter event.
                val currentUnreadable = logs.unreadableTreeUrisFlow.value
                val newUnreadable = !s.logFolderUri.isNullOrBlank() &&
                    currentUnreadable.contains(s.logFolderUri)
                _state.value = st.copy(
                    settings = s,
                    settingsLoaded = true,
                    logFolderUnreadable = newUnreadable,
                    // Only sync pane visibility to the new default if the user hasn't overridden it manually.
                    showNickList = when {
                        applyDefaults -> s.defaultShowNickList
                        s.defaultShowNickList != st.settings.defaultShowNickList &&
                            st.showNickList == st.settings.defaultShowNickList -> s.defaultShowNickList
                        else -> st.showNickList
                    },
                    showBufferList = when {
                        applyDefaults -> s.defaultShowBufferList
                        s.defaultShowBufferList != st.settings.defaultShowBufferList &&
                            st.showBufferList == st.settings.defaultShowBufferList -> s.defaultShowBufferList
                        else -> st.showBufferList
                    }
                )
                // Retention sweep, off the collector's thread since on SAF it is several IPC calls
                // per file. Only runs when the policy or folder changes, because settings emit on
                // every preference change.
                val purgeKey = Triple(s.loggingEnabled, s.retentionDays, s.logFolderUri)
                if (s.loggingEnabled && purgeKey != lastPurgeKey) {
                    lastPurgeKey = purgeKey
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { logs.purgeOlderThan(s.retentionDays, s.logFolderUri) }
                    }
                }
                // Applies live: flipping the switch starts or stops logging on connections
                // that are already up, which is the point of a debugging toggle.
                runtimes.values.forEach {
                    it.client.rawLogEnabled = s.rawLog
                    it.client.nickRegainEnabled = s.nickRegainEnabled
                }
                syncPushNotifyPolicy()
                maybeAutoConnect()
                maybeRestoreDesiredConnections()
            }
        }
        viewModelScope.launch {
            repo.channelPrefsFlow.collect { prefs ->
                _state.update { it.copy(channelPrefs = prefs) }
                syncPushNotifyPolicy()
            }
        }
        viewModelScope.launch {
            repo.networksFlow.collect { nets ->
                val st = _state.value
                val active = st.activeNetworkId ?: nets.firstOrNull()?.id
                val next = st.copy(networks = nets, activeNetworkId = active)

                _state.value = next
                active?.let { ensureServerBuffer(it) }
                if (st.selectedBuffer.isBlank() && active != null) {
                    _state.value = _state.value.copy(selectedBuffer = bufKey(active, "*server*"), screen = AppScreen.NETWORKS)
                }
                syncPushNotifyPolicy()
                maybeAutoConnect()
                maybeRestoreDesiredConnections()
            }
        }
        viewModelScope.launch {
            repo.lastNetworkIdFlow.collect { last ->
                val st = _state.value
                if (!last.isNullOrBlank() && st.activeNetworkId == null) {
                    _state.value = st.copy(activeNetworkId = last)
                    ensureServerBuffer(last)
                }
            }
        }

        viewModelScope.launch {
            // Seeds the set from the last process only. After this the in-memory set is authoritative.
            desiredConnected.addAll(repo.desiredNetworkIdsFlow.first())
            desiredNetworkIdsLoaded = true
            refreshConnectionNotification()
            maybeRestoreDesiredConnections()
        }

        registerNetworkCallback()

        notifier.ensureChannels()
    }

    fun setNetworkAutoConnect(netId: String, enabled: Boolean) {
        val n = _state.value.networks.firstOrNull { it.id == netId } ?: return
        viewModelScope.launch { repo.upsertNetwork(n.copy(autoConnect = enabled)) }

        // Turning autoConnect off also clears pending auto-reconnect state, so the network is
        // neither restored on the next process start nor retried by a running backoff. A current
        // connection is left up: the user is asking not to bring it back automatically, not to drop
        // it now.
        if (!enabled) {
            val removed = desiredConnected.remove(netId)
            if (removed) persistDesiredNetworkIds()
            // Cancel any backoff coroutine waiting to retry. If it's currently sleeping,
            // its next wake-up will see desiredConnected no longer contains netId and exit.
            autoReconnectJobs.remove(netId)?.cancel()
            reconnectAttempts.remove(netId)
        }
    }

    // ── IRC URI deep-link support ─────────────────────────────────────────────────

    internal data class IrcUri(
        val host: String,
        val port: Int,
        val useTls: Boolean,
        val channels: List<String>,
        val channelKey: String? = null,
        val serverPassword: String? = null,
    )

    private val _logSearch = MutableStateFlow(LogSearchState())
    val logSearch: StateFlow<LogSearchState> = _logSearch
    private var logSearchJob: Job? = null

    /** Bouncer searches a log search is waiting on, by request id. */
    internal val serverSearchWaits: MutableMap<String, CompletableDeferred<IrcEvent.SearchResults>> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Search the logs for [raw], in [bufferKey]'s log, its network's logs, or all of them. The
     * result arrives in [logSearch].
     */
    fun searchLogs(raw: String, scope: LogSearchScope, bufferKey: String?) {
        logSearchJob?.cancel()
        val query = LogSearch.parse(raw)
        if (query.isEmpty) {
            _logSearch.value = LogSearchState(query = raw)
            return
        }
        val st = _state.value
        val (netId, bufName) = bufferKey?.takeIf { it.contains("::") }?.let { splitKey(it) } ?: ("" to "")
        val netName = st.networks.firstOrNull { it.id == netId }?.name
        val networkFilter = if (scope == LogSearchScope.ALL || netName == null) null else netName
        val bufferFilter = if (scope == LogSearchScope.BUFFER && netName != null && bufName.isNotBlank()) bufName else null
        val folder = st.settings.logFolderUri
        _logSearch.value = LogSearchState(running = true, query = raw)
        logSearchJob = viewModelScope.launch {
            val job = coroutineContext.job
            // A bouncer that keeps history is searched alongside the files.
            val server = ArrayList<ServerSearch>()
            try {
                server += startServerSearches(query, scope, bufferKey)
                val (hits, more) = withContext(Dispatchers.IO) {
                    runCatching {
                        logs.search(query, networkFilter, bufferFilter, folder, cancelled = { !job.isActive })
                    }.getOrDefault(emptyList<LogSearchHit>() to false)
                }
                if (!job.isActive) return@launch
                _logSearch.value = LogSearchState(
                    running = false, query = raw, hits = hits, truncated = more, searched = true,
                    serverRunning = server.isNotEmpty(),
                )
                if (server.isEmpty()) return@launch
                val (all, answered, truncated) = finishServerSearches(query, server, hits, more)
                if (job.isActive) {
                    _logSearch.value = LogSearchState(
                        running = false, query = raw, hits = all, truncated = truncated, searched = true,
                        serverSearched = answered,
                    )
                }
            } finally {
                server.forEach { serverSearchWaits.remove(it.id) }
            }
        }
    }

    fun clearLogSearch() {
        logSearchJob?.cancel()
        _logSearch.value = LogSearchState()
    }

    /** The network a log folder belongs to, if a profile still has that name. */
    fun networkForLogDir(dir: String): NetworkProfile? =
        _state.value.networks.firstOrNull { logs.networkDirName(it.name).equals(dir, ignoreCase = true) }

    /**
     * Open the buffer [hit] was found in and scroll to its line, when that line is in the
     * scrollback. A hit from the bouncer's history is fetched there when it is not. Returns
     * false when no network has the hit's folder name.
     */
    fun openLogSearchHit(hit: LogSearchHit): Boolean {
        val net = hit.netId?.let { id -> _state.value.networks.firstOrNull { it.id == id } }
            ?: networkForLogDir(hit.networkDir) ?: return false
        val key = resolveBufferKey(net.id, hit.buffer)
        setActiveNetwork(net.id)
        openBuffer(key)
        val atMs = parseLogLineTimeMs(hit.line)
        val from = LogSearch.senderOf(hit.body)
        if (atMs != null && from != null) {
            val text = LogSearch.textOf(hit.body).take(80)
            _state.value = _state.value.copy(
                pendingHighlightAnchor = "ts:${atMs / 1000}|$from|$text",
                pendingHighlightSetAtMs = System.currentTimeMillis(),
                pendingHighlightBufferKey = key,
            )
            val loaded = hit.fromServer && _state.value.buffers[key]?.messages?.any { m ->
                kotlin.math.abs(m.timeMs - atMs) <= 3_000L && m.from.equals(from, ignoreCase = true) &&
                    stripIrcFormatting(m.text).startsWith(text, ignoreCase = true)
            } == true
            if (hit.fromServer && !loaded) loadMessageContext(key, hit.msgId, timeMs = atMs)
        }
        return true
    }

    /** Called by ChatScreen once the scroll-and-flash animation has run. */
    fun clearHighlightScroll() {
        _state.value = _state.value.copy(
            pendingHighlightAnchor = null,
            pendingHighlightBufferKey = null,
        )
    }

    fun consumeShareText() {
        _state.value = _state.value.copy(pendingShareText = null, pendingShareTarget = null)
    }

    fun goTo(screen: AppScreen) {
        _state.value = _state.value.copy(screen = screen)
        if (screen == AppScreen.LIST) requestList()
    }
    fun backToChat() {
        _state.update { st ->
            val key = st.selectedBuffer
            val buf = if (key.isNotBlank()) st.buffers[key] else null
            val needsClear = buf != null && (buf.unread > 0 || buf.highlights > 0)
            val nextBuffers = if (needsClear) {
                st.buffers + (key to buf.copy(unread = 0, highlights = 0))
            } else st.buffers
            st.copy(screen = AppScreen.CHAT, buffers = nextBuffers)
        }
    }

    fun openBuffer(key: String) = openBuffer(key, switchToChat = true)

    /**
     * Select [key] without bringing the chat screen forward, for the floating window: the
     * user is looking at another app, and the screen they left the app on should still be
     * there when they come back.
     */
    fun openBufferInBackground(key: String) = openBuffer(key, switchToChat = false)

    /** Newest msgid a read receipt was sent for, per query buffer. */
    internal val readReceiptSentFor: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    /** Pending read receipts, per query buffer, so a burst of messages sends one. */
    internal val readReceiptJobs: MutableMap<String, Job> = java.util.concurrent.ConcurrentHashMap()
    internal val READ_RECEIPT_DELAY_MS = 1_500L
    /** The buffer whose newest message is on screen, as reported by the chat screen. */
    @Volatile internal var viewingLatestKey: String? = null

    /** Called by the chat screen when [bufferKey]'s newest message scrolls into or out of view. */
    fun onViewingLatest(bufferKey: String, atLatest: Boolean) {
        if (atLatest) {
            viewingLatestKey = bufferKey
            sendReadReceipt(bufferKey)
        } else if (viewingLatestKey == bufferKey) {
            viewingLatestKey = null
        }
    }

    fun toggleBufferList() {
        val st = _state.value
        _state.value = st.copy(showBufferList = !st.showBufferList)
        viewModelScope.launch { runCatching { repo.updateSettings { it.copy(defaultShowBufferList = _state.value.showBufferList) } } }
    }

    fun toggleNickList() {
        val st = _state.value
        _state.value = st.copy(showNickList = !st.showNickList)
        viewModelScope.launch { runCatching { repo.updateSettings { it.copy(defaultShowNickList = _state.value.showNickList) } } }
    }

	/**
	 * Re-query the selected channel's modes so the ops drawer shows live server state.
	 * Marked silent first: this is a UI refresh, not something the user asked to see, so
	 * the 324/329 replies update state without printing anything.
	 */
	fun refreshChannelModesForSelectedBuffer() {
		val st = _state.value
		val key = st.selectedBuffer
		if (key.isBlank()) return
		val (netId, rawBuf) = splitKey(key)
		if (netId.isBlank()) return
		val buf = stripStatusMsgPrefix(netId, rawBuf)
		if (!isChannelOnNet(netId, buf)) return
		if (st.connections[netId]?.connected != true) return
		val rt = runtimes[netId] ?: return
		viewModelScope.launch { runCatching { rt.client.querySilentModes(buf) } }
	}

	/**
	 * Look up [nick]'s away status as their nick sheet opens, unless it is already current:
	 * away-notify keeps it so once the open channel's member WHO has run.
	 */
	fun lookUpNickAway(nick: String) {
		val st = _state.value
		val key = st.selectedBuffer.takeIf { it.isNotBlank() } ?: return
		val (netId, buffer) = splitKey(key)
		val base = nick.trim().takeIf { it.isNotEmpty() } ?: return
		if (st.connections[netId]?.connected != true || isMyNick(netId, base)) return
		val rt = runtimes[netId] ?: return
		val chan = stripStatusMsgPrefix(netId, buffer)
		// The member WHO on join runs only with WHOX.
		val snapshotTaken = isChannelOnNet(netId, chan) && rt.client.whoxAvailable() &&
			namesKeyFold(chan) !in rt.whoDeferred && namesKeyFold(chan) !in rt.whoAfterJoin
		if (snapshotTaken && rt.client.hasCap("away-notify")) return
		val lookupKey = "$netId::" + casefoldText(netId, base)
		val now = SystemClock.elapsedRealtime()
		val last = nickAwayLookups[lookupKey]
		if (last != null && now - last < NICK_AWAY_LOOKUP_GAP_MS) return
		if (nickAwayLookups.size > 512) nickAwayLookups.clear()
		nickAwayLookups[lookupKey] = now
		viewModelScope.launch { runCatching { rt.client.refreshChannelWho(base) } }
	}

	fun refreshNicklistForSelectedBuffer(force: Boolean = false) {
		val st = _state.value
		val key = st.selectedBuffer
		if (key.isBlank()) return
		val (netId, rawBuf) = splitKey(key)
		if (netId.isBlank()) return
		val buf = stripStatusMsgPrefix(netId, rawBuf)
		if (!isChannelOnNet(netId, buf)) return
		val conn = st.connections[netId]
		if (conn?.connected != true) return
		val rt = runtimes[netId] ?: return

		val fold = namesKeyFold(buf)
		// A large channel's member WHO, skipped on join, runs the first time its list is shown.
		if (rt.whoDeferred.remove(fold)) {
			viewModelScope.launch { runCatching { rt.client.refreshChannelWho(buf) } }
		}
		val now = SystemClock.elapsedRealtime()
		val last = rt.lastNamesRefreshAtMs[fold] ?: 0L
		if (!force && (now - last) < 5_000L) return
		rt.lastNamesRefreshAtMs[fold] = now

		rt.namesRequests[fold]?.let { inFlight ->
			if ((now - inFlight.createdAtMs) < 12_000L) return
			rt.namesRequests.remove(fold)
		}
		val replyKey = resolveBufferKey(netId, buf)
		ensureBuffer(replyKey)
		rt.namesRequests[fold] = NamesRequest(replyBufferKey = replyKey, printToBuffer = false)
		viewModelScope.launch {
			runCatching { rt.client.sendBackground("NAMES $buf") }
		}
	}

    fun toggleChannelsOnly() { _state.value = _state.value.copy(channelsOnly = !_state.value.channelsOnly) }
    fun setListFilter(v: String) { _state.value = _state.value.copy(listFilter = v) }
    fun setListSort(v: String)   { _state.value = _state.value.copy(listSort = v) }

    fun closeFindOverlay() { _state.value = _state.value.copy(findOverlay = null) }
    fun findNavigate(delta: Int) {
        val ov = _state.value.findOverlay ?: return
        val newIdx = (ov.currentIndex + delta).coerceIn(0, ov.matchIds.lastIndex)
        _state.value = _state.value.copy(findOverlay = ov.copy(currentIndex = newIdx))
    }

    /**
     * Send a draft/message-reactions emoji reaction to [msgId] in the currently
     * selected buffer. No-op if the server doesn't support message-tags.
     */
    fun sendReaction(msgId: String, emoji: String, remove: Boolean = false) {
        val st = _state.value
        val key = st.selectedBuffer.takeIf { it.isNotBlank() } ?: return
        val (netId, bufferName) = splitKey(key)
        val rt = runtimes[netId] ?: return
        viewModelScope.launch { rt.client.sendReaction(bufferName, msgId, emoji, remove) }
    }

    /**
     * Send [text] to [buffer] on [networkId] without switching buffers, for notification replies.
     * With reply-tag support and a known [msgId] the reply tag is attached; without it, the text is
     * prefixed with a quote of the original (and in a channel the sender's nick).
     */
    fun sendToBuffer(
        networkId: String,
        buffer: String,
        text: String,
        from: String = "",
        originalText: String = "",
        msgId: String? = null,
    ) {
        viewModelScope.launch {
            if (isDccChatBufferName(buffer)) {
                sendDccChatLine(findDccChatKey(networkId, buffer.removePrefix("DCCCHAT:")), text, isAction = false)
                return@launch
            }
            val rt = runtimes[networkId] ?: return@launch
            val client = rt.client
            val myNick = _state.value.connections[networkId]?.myNick ?: _state.value.myNick
            val key = resolveBufferKey(networkId, buffer)

            // draft/reply is a client-only tag ("+draft/reply")
            // It's permitted whenever message-tags is negotiated. Some servers also
            // whitelist it explicitly via CLIENTTAGDENY=*,-draft/reply but we don't
            // need to parse that. message-tags is the correct gate.
            val hasReplyTagCap = client.hasCap("message-tags")
            val isChannel      = buffer.isNotEmpty() && buffer[0] in "#&+!"

            // +AGE: sent over the encrypted session, which carries no reply tag.
            if (ageOn(networkId, buffer)) {
                sendAgeText(networkId, key, buffer, if (isChannel && from.isNotBlank()) "$from: $text" else text)
                return@launch
            }

            // The local echo mirrors the wire: one line per PRIVMSG, so each matches its echo and
            // any later replay of it.
            val sentChunks = mutableListOf<String>()

            val outText = when {
                // Server supports draft/reply AND we have a real server msgId: send a
                // reply-tagged message. Route through privmsg() (NOT a direct sendRaw)
                // so the E2E encryption hook applies - a raw send here would ship the
                // reply in cleartext while the local echo still shows a padlock. privmsg
                // also builds a single well-formed tag group (@label=…;+draft/reply=…).
                hasReplyTagCap && msgId != null -> {
                    val sanitised = text.replace("\r", "").replace("\n", " ")
                    // The reply tag goes on the first piece only: that is what the far
                    // side's quote points at.
                    val pieces = outgoingChunks(networkId, buffer, sanitised)
                    pieces.forEachIndexed { idx, chunk ->
                        val id = client.privmsg(
                            buffer,
                            chunk,
                            replyToMsgId = if (idx == 0) msgId else null,
                        )
                        // Recorded so the echo is consumed rather than shown twice.
                        recordLocalSend(networkId, key, chunk, isAction = false, sendIds = listOf(id))
                        sentChunks += chunk
                    }
                    sanitised
                }
                // Channel without reply-tag support: prepend "Nick: (quote..) - reply"
                isChannel && from.isNotBlank() && originalText.isNotBlank() -> {
                    val quote = originalText.take(60).let { if (originalText.length > 60) "$it…" else it }
                    "$from: ($quote) - $text"
                }
                // Channel, no quote available but sender known
                isChannel && from.isNotBlank() -> "$from: $text"
                // PM without reply-tag support: keep the quote so the counterparty can tell
                // which message this answers, but skip the nick prefix (pointless 1:1).
                !isChannel && originalText.isNotBlank() -> {
                    val quote = originalText.take(60).let { if (originalText.length > 60) "$it…" else it }
                    "($quote) - $text"
                }
                // No context available
                else -> text
            }

            // For all non-tag paths, use privmsg() which handles echo-message + label correctly.
            if (!(hasReplyTagCap && msgId != null)) {
                // The quote prefix above adds length, so this overflows more readily.
                for (chunk in outgoingChunks(networkId, buffer, outText)) {
                    val id = client.privmsg(buffer, chunk)
                    recordLocalSend(networkId, key, chunk, isAction = false, sendIds = listOf(id))
                    sentChunks += chunk
                }
            }

            // Pass replyToMsgId so our own local echo shows the reply quote UI,
            // matching what other clients will see when the tagged message arrives.
            // Mirror the wire-level encryption state into the local echo so the lock
            // icon shows up immediately instead of waiting for the echo-message round
            // trip. Look up the per-target key at send-time, NOT at append-time, so a
            // key cleared between send and echo doesn't retroactively mark the local
            // line as cleartext.
            val localEncryption = e2eKeyStore.get(networkId, buffer)?.scheme
            sentChunks.forEachIndexed { idx, chunk ->
                append(key, from = myNick, text = chunk, isLocal = true,
                    replyToMsgId = if (idx == 0 && hasReplyTagCap && msgId != null) msgId else null,
                    encryption = localEncryption)
            }
        }
    }
    /**
     * Apply [update] to the settings and persist the result.
     *
     * [update] runs twice: once against the in-memory copy for an immediate UI change, and
     * once inside the DataStore write against whatever is stored. It must therefore read
     * only captured values, never mutable state that the caller goes on to change.
     */
    fun updateSettings(update: UiSettings.() -> UiSettings) {
        // Apply immediately; DataStore confirms shortly after.
        val st = _state.value
        val next = st.settings.update()
        _state.value = st.copy(settings = next)

        viewModelScope.launch {
            runCatching { repo.updateSettings { it.update() } }
        }
    }
    fun setDccEnabled(enabled: Boolean) { updateSettings { copy(dccEnabled = enabled) } }
    fun setDccSendMode(mode: DccSendMode) { updateSettings { copy(dccSendMode = mode) } }

    fun setActiveNetwork(id: String) {
        val st = _state.value
        // A mounted script view is tied to the network/buffer it was opened
        // from, and its game state is global to the single script engine.
        if (id != st.activeNetworkId && _scriptView.value != null) closeScriptView()
        val next = st.copy(activeNetworkId = id)
        _state.value = syncActiveNetworkSummary(next)
        ensureServerBuffer(id)
        viewModelScope.launch(Dispatchers.IO) { runCatching { repo.setLastNetworkId(id) } }
    }

    /**
     * Restore the built-in AfterNET profile (used for support) if the user deleted it.
     * Safe to call multiple times; it no-ops if a profile named/id AfterNET already exists.
     */
    fun addAfterNetDefaults() {
        viewModelScope.launch {
            val st = _state.value
            val exists = st.networks.any { it.id.equals("AfterNET", ignoreCase = true) || it.name.equals("AfterNET", ignoreCase = true) }
            if (exists) return@launch

            // The same profile a fresh install starts with.
            val n = repo.defaultNetworks().firstOrNull { it.id == "AfterNET" } ?: return@launch
            repo.upsertNetwork(n)
        }
    }

    /**
     * Called from the WelcomeScreen to persist the chosen language and nick, mark welcome as done,
     * and apply the nick to all default network profiles.
     */
    fun completeWelcome(languageCode: String, nick: String) {
        updateAllDefaultNetworkNicks(nick)
        updateSettings {
            copy(
                welcomeCompleted = true,
                appLanguage = languageCode
            )
        }
    }

fun startAddNetwork() {
	val n = NetworkProfile(
		// Use UUID instead of currentTimeMillis() to avoid ID collisions when two
		// networks are created within the same millisecond (e.g. from a backup restore).
		id = "net_" + java.util.UUID.randomUUID().toString().replace("-", ""),
		name = "New network",
		host = "irc.example.org",
		port = 6697,
		useTls = true,
		allowInvalidCerts = false,
		nick = "HexDroidUser",
		altNick = "HexDroidUser_",
		username = "hexdroid",
		realname = "HexDroid IRC",
		saslEnabled = false,
		saslMechanism = SaslMechanism.PLAIN,
		caps = CapPrefs(),
		autoJoin = emptyList()
	)
        _state.value = _state.value.copy(screen = AppScreen.NETWORK_EDIT, editingNetwork = n, networkEditError = null)
    }

    fun startEditNetwork(id: String) {
        viewModelScope.launch {
            val st0 = _state.value
            val n = st0.networks.firstOrNull { it.id == id } ?: return@launch

            // Passwords/secrets are stored in SecretStore (Android Keystore). Load them for the edit
            // form only, off the main thread since Keystore operations can be slow.
            val (serverPass, saslPass, proxyPass) = withContext(Dispatchers.IO) {
                Triple(
                    repo.secretStore.getServerPassword(id),
                    repo.secretStore.getSaslPassword(id),
                    repo.secretStore.getProxyPassword(id),
                )
            }

            val withSecrets = n.copy(
                serverPassword = serverPass,
                saslPassword = saslPass,
                proxyPassword = proxyPass
            )

            val st1 = _state.value
            _state.value = st1.copy(
                screen = AppScreen.NETWORK_EDIT,
                editingNetwork = withSecrets,
                networkEditError = null
            )
        }
    }

    fun cancelEditNetwork() { _state.value = _state.value.copy(screen = AppScreen.NETWORKS, editingNetwork = null, networkEditError = null) }

    fun dismissLocalNetworkWarning() {
        _state.value = _state.value.copy(localNetworkWarningNetworkId = null)
    }

    /** Called after the user has granted ACCESS_LOCAL_NETWORK - retry the connection. */
    fun retryAfterLocalNetworkPermission(netId: String) {
        _state.value = _state.value.copy(localNetworkWarningNetworkId = null)
        // User-initiated retry (they just acted on the permission prompt) - clear the
        // auth block so a previously-halted reconnect can run again.
        connectNetwork(netId, clearAuthBlock = true)
    }

    fun dismissPlaintextWarning() {
        _state.value = _state.value.copy(plaintextWarningNetworkId = null)
    }

    fun allowPlaintextAndConnect(netId: String) {
        viewModelScope.launch {
            val st = _state.value
            val n = st.networks.firstOrNull { it.id == netId } ?: return@launch
            val updated = n.copy(allowInsecurePlaintext = true)
            repo.upsertNetwork(updated)
            _state.value = _state.value.copy(
                networks = st.networks.map { if (it.id == netId) updated else it },
                plaintextWarningNetworkId = null
            )
            // User explicitly opted to connect insecurely; treat as manual retry and
            // clear the auth block. This path is only reachable from the Connect button,
            // so it opens the server buffer for the same reason that does.
            connectNetwork(netId, force = true, clearAuthBlock = true, openServerBuffer = true)
        }
    }

    fun saveEditingNetwork(profile: NetworkProfile, clientCertDraft: ClientCertDraft?, removeClientCert: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(networkEditError = null)
            // SecretStore writes go through Android Keystore, which throws when the keystore has
            // been invalidated (lock-screen or fingerprint change, OEM corruption) and its one
            // internal retry also fails. Report that on the edit screen and leave the stored
            // credentials untouched.
            val secretsResult = withContext(Dispatchers.IO) { runCatching {
                if (profile.saslEnabled) {
                    val p = profile.saslPassword?.trim()
                    if (!p.isNullOrBlank()) {
                        repo.secretStore.setSaslPassword(profile.id, p)
                    } else {
                        // SASL is enabled but the password field was cleared - remove the
                        // stored secret so the old password does not persist in SecretStore.
                        repo.secretStore.clearSaslPassword(profile.id)
                    }
                } else {
                    repo.secretStore.clearSaslPassword(profile.id)
                }

                val sp = profile.serverPassword?.trim()
                if (!sp.isNullOrBlank()) {
                    repo.secretStore.setServerPassword(profile.id, sp)
                } else {
                    repo.secretStore.clearServerPassword(profile.id)
                }

                // Proxy password (SOCKS5 auth). Only meaningful when a proxy is configured;
                // clear it otherwise so a stale secret can't linger after the user turns the
                // proxy off.
                val pp = profile.proxyPassword?.trim()
                if (profile.proxyType != com.boxlabs.hexdroid.connection.ProxyType.NONE && !pp.isNullOrBlank()) {
                    repo.secretStore.setProxyPassword(profile.id, pp)
                } else {
                    repo.secretStore.clearProxyPassword(profile.id)
                }
            } }
            if (secretsResult.isFailure) {
                val t = secretsResult.exceptionOrNull()
                _state.value = _state.value.copy(
                    screen = AppScreen.NETWORK_EDIT,
                    editingNetwork = profile,
                    networkEditError = appContext.getString(R.string.vm_creds_save_failed, t?.message ?: t?.javaClass?.simpleName ?: appContext.getString(R.string.vm_keystore_error))
                )
                return@launch
            }

            var updated = profile.copy(
                saslPassword = null,
                serverPassword = null,
                proxyPassword = null,
                tlsClientCertLabel = profile.tlsClientCertLabel
            )

            // A soju netid belongs to the account and network it was learned for; pointing the
            // profile elsewhere drops it, or a rename seen on the old network would undo the edit.
            val stored = _state.value.networks.firstOrNull { it.id == profile.id }
            if (stored != null && updated.bouncerNetworkId != null && (
                    stored.bouncerKind != updated.bouncerKind ||
                    !stored.host.equals(updated.host, ignoreCase = true) || stored.port != updated.port ||
                    stored.bouncerAccount() != updated.bouncerAccount() ||
                    !stored.bouncerNetworkName.equals(updated.bouncerNetworkName, ignoreCase = true))
            ) {
                updated = updated.copy(bouncerNetworkId = null)
            }

            if (removeClientCert) {
                withContext(Dispatchers.IO) { repo.secretStore.removeClientCert(profile.id, profile.tlsClientCertId) }
                updated = updated.copy(tlsClientCertId = null, tlsClientCertLabel = null)
            }

            if (clientCertDraft != null) {
                val oldCertId = updated.tlsClientCertId
                val stored = try {
                    withContext(Dispatchers.IO) {
                        repo.secretStore.removeClientCert(profile.id, oldCertId)
                        repo.secretStore.importClientCert(profile.id, clientCertDraft)
                    }
                } catch (t: Throwable) {
                    _state.value = _state.value.copy(
                        screen = AppScreen.NETWORK_EDIT,
                        editingNetwork = profile,
                        networkEditError = t.message ?: appContext.getString(R.string.vm_cert_import_failed)
                    )
                    return@launch
                }
                updated = updated.copy(
                    tlsClientCertId = stored.certId,
                    tlsClientCertLabel = stored.label
                )
            }

            repo.upsertNetwork(updated)
            repo.setLastNetworkId(updated.id)
            // Editing the profile gives the user a chance to fix bad credentials, so
            // clear the auth-failure block. Even if they didn't actually touch the
            // password fields, the next manual reconnect deserves the same one-shot
            // policy as connectNetwork().
            authBlockedReconnect.remove(updated.id)

            _state.value = _state.value.copy(
                screen = AppScreen.NETWORKS,
                editingNetwork = null,
                activeNetworkId = updated.id,
                networkEditError = null
            )
        }
    }

    /**
     * The fingerprint of [networkId]'s own certificate, creating it if needed (off the main
     * thread). Null when the Keystore can't make one.
     */
    suspend fun networkCertFingerprint(networkId: String): String? = withContext(Dispatchers.IO) {
        val alias = NetworkCertificates.aliasFor(networkId)
        runCatching { NetworkCertificates.ensure(alias) }
        NetworkCertificates.fingerprint(alias)
    }

    /** Networks whose account service is AuthServ (X3/srvx), learned from its notices. */
    internal val authServNetworks: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The account service to offer for [networkId]: AuthServ once it has spoken there, else NickServ. */
    fun accountService(networkId: String): String =
        if (networkId in authServNetworks) "AuthServ" else "NickServ"

    /**
     * Ask the account service ([service], NickServ or AuthServ) to add [fingerprint] to the account
     * the user is identified to on [networkId], so later logins can use SASL EXTERNAL. False when
     * that network isn't connected.
     */
    fun addCertToServices(networkId: String, fingerprint: String, service: String): Boolean {
        val rt = runtimes[networkId] ?: return false
        if (_state.value.connections[networkId]?.connected != true) return false
        val command = if (service.equals("AuthServ", ignoreCase = true)) "ADDCERTFP $fingerprint" else "CERT ADD $fingerprint"
        viewModelScope.launch { runCatching { rt.client.sendRaw("PRIVMSG $service :$command") } }
        return true
    }

    fun deleteNetwork(id: String) {
        viewModelScope.launch {
            repo.deleteNetwork(id)
            repo.secretStore.clearSaslPassword(id)
            repo.secretStore.clearServerPassword(id)
            repo.secretStore.clearProxyPassword(id)
            // Drop every E2E key configured for this network's targets - the profile
            // is gone, the keys would dangle and leak storage (and confusing UI if
            // the same network slug is later re-created).
            runCatching { repo.secretStore.clearAllE2eKeysForNetwork(id) }
            e2eKeyStore.forgetNetwork(id)
            runCatching { repo.removeChannelPrefsForNetwork(id) }
            withContext(Dispatchers.IO) { NetworkCertificates.delete(NetworkCertificates.aliasFor(id)) }
            // Drop self-send replay-dedup signatures for this network's buffers.
            val pfx = "$id::"
            recentSelfSends.keys.filter { it.startsWith(pfx) }.forEach { recentSelfSends.remove(it) }
        }
        disconnectNetwork(id)
        cleanupNetworkMaps(id, resetReconnectState = true)
        // Profile is gone; drop any auth-failure block that pinned it. (cleanupNetworkMaps
        // doesn't touch authBlockedReconnect, see the note there.)
        authBlockedReconnect.remove(id)
        smartFilter.forgetPrefix("$id::")
        // Profile is gone for good; drop its session-registration marker too. (Unlike a
        // disconnect, a delete means there's no reconnect that should still count as one.)
        everRegisteredThisSession.remove(id)
        // Profile is gone, so its unsent composer text is too. removeBuffer() handles the
        // per-buffer case, but a delete drops every buffer at once without going through it.
        draftStore.clearNetwork(id)
        // Its DCC chats too, for the same reason.
        dccChatPending.keys.filter { it.startsWith("$id::") }.forEach { dccChatPending.remove(it)?.cancel() }
        dccChatSessions.keys.filter { it.startsWith("$id::") }.forEach { closeDccChatSession(it) }
        // Purge the deleted network's buffers, connection record and chathistory marker tracker.
        // cleanupNetworkMaps doesn't touch _state, since most of its callers keep buffer history
        // across reconnects.
        val prefix = "$id::"
        chatHistory.forgetNetwork(id)
        // Its conversations' notifications and shortcuts go with it.
        for (key in _state.value.buffers.keys.filter { it.startsWith(prefix) }) {
            runCatching { notifier.removeConversation(id, splitKey(key).second) }
        }
        // The profile is gone, so its away message has nothing left to be restored onto.
        awayMessages.remove(id)
        _state.update { st ->
            val orphanKeys = st.buffers.keys.filter { it.startsWith(prefix) }
            val newBuffers = if (orphanKeys.isEmpty()) st.buffers else st.buffers - orphanKeys.toSet()
            val newConns = if (st.connections.containsKey(id)) st.connections - id else st.connections
            val newSelected = if (st.selectedBuffer.startsWith(prefix)) "" else st.selectedBuffer
            val newActive = if (st.activeNetworkId == id) {
                st.networks.firstOrNull { it.id != id }?.id
            } else st.activeNetworkId
            syncActiveNetworkSummary(st.copy(
                buffers = newBuffers,
                connections = newConns,
                selectedBuffer = newSelected,
                activeNetworkId = newActive,
            ))
        }
    }

    /** Clear the transient backup/restore result message (called after the UI has shown it). */
    fun clearBackupMessage() {
        _state.update { it.copy(backupMessage = null, backupIsError = false) }
    }

    /** Bytes used by logs kept in app storage. */
    suspend fun internalLogsSize(): Long =
        withContext(Dispatchers.IO) { runCatching { logs.internalLogsSize() }.getOrDefault(0L) }

    /**
     * Delete every log kept in app storage, then call [onDone] on the main thread. Runs in the
     * ViewModel's scope, so leaving Settings doesn't lose the result. Scrollback already loaded
     * stays in memory.
     */
    fun deleteInternalLogs(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { logs.deleteInternalLogs() }.isSuccess }
            logsToast(if (ok) R.string.setting_logs_deleted else R.string.setting_logs_delete_failed)
            onDone()
        }
    }

    /** Zip every log kept in app storage into [uri], a document the user chose to create. */
    fun exportInternalLogs(uri: android.net.Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openOutputStream(uri)?.use { logs.exportInternalLogs(it) }
                        ?: throw java.io.IOException("no output stream")
                }.isSuccess
            }
            logsToast(if (ok) R.string.setting_logs_exported else R.string.setting_logs_export_failed)
        }
    }

    /**
     * Write a backup of current settings and networks to [uri] (obtained from
     * ACTION_CREATE_DOCUMENT), without passwords. TLS client certificates are never included:
     * their keys are tied to this device's Android Keystore.
     */
    fun exportBackup(uri: android.net.Uri) = exportBackup(uri, null)

    /** Write a backup to [uri]; with a [passphrase], the network passwords go in encrypted with it. */
    fun exportBackup(uri: android.net.Uri, passphrase: String?) {
        val st = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val pass = passphrase?.takeIf { it.isNotEmpty() }?.toCharArray()
                val secrets = if (pass != null) repo.collectSecrets(st.networks) else emptyMap()
                val json = try {
                    repo.exportBackupJson(st.networks, st.settings, st.channelPrefs, secrets, pass)
                } finally {
                    pass?.fill('\u0000')
                }
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: throw java.io.IOException(appContext.getString(R.string.vm_backup_no_output))
                appContext.getString(if (secrets.isNotEmpty()) R.string.vm_backup_saved_with_passwords else R.string.vm_backup_saved)
            }
            val msg = result.getOrElse { e -> appContext.getString(R.string.vm_backup_failed, e.message) }
            _state.update { it.copy(backupMessage = msg, backupIsError = result.isFailure) }
        }
    }

    /**
     * Read a backup file from [uri] (obtained from ACTION_OPEN_DOCUMENT) and restore
     * settings and networks.  Existing networks are replaced.
     *
     * On success, UI settings take effect on next DataStore emission. Passwords come back only
     * from a backup that holds them encrypted, once its passphrase is given.
     */
    fun importBackup(uri: android.net.Uri) = restoreBackup(uri, passphrase = null, withoutPasswords = false)

    /** Stop a restore that is waiting for its passphrase. */
    fun cancelRestore() {
        _state.update { it.copy(restorePassphraseUri = null, restorePassphraseWrong = false) }
    }

    /**
     * Restore the backup at [uri]. A backup with encrypted passwords and no [passphrase] waits
     * for one (see [UiState.restorePassphraseUri]) unless [withoutPasswords].
     */
    fun restoreBackup(uri: android.net.Uri, passphrase: String?, withoutPasswords: Boolean) {
        // The prompt closes while the key is derived; a wrong passphrase brings it back.
        if (passphrase != null || withoutPasswords) {
            _state.update { it.copy(restorePassphraseUri = null, restorePassphraseWrong = false) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val json = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?.toString(Charsets.UTF_8)
                    ?: throw java.io.IOException(appContext.getString(R.string.vm_restore_no_input))
                if (passphrase == null && !withoutPasswords && repo.backupHasEncryptedSecrets(json)) {
                    _state.update { it.copy(restorePassphraseUri = uri.toString(), restorePassphraseWrong = false) }
                    return@launch
                }
                val pass = if (withoutPasswords) null else passphrase?.toCharArray()
                val orphanedIds = try {
                    repo.importBackup(json, pass)
                } catch (_: com.boxlabs.hexdroid.data.BackupCrypto.WrongPassphrase) {
                    _state.update { it.copy(restorePassphraseUri = uri.toString(), restorePassphraseWrong = true) }
                    return@launch
                } finally {
                    pass?.fill('\u0000')
                }
                _state.update { it.copy(restorePassphraseUri = null, restorePassphraseWrong = false) }
                // Clear stored secrets for profiles that existed before the restore but aren't in
                // the backup.
                for (id in orphanedIds) {
                    runCatching { repo.secretStore.clearSaslPassword(id) }
                    runCatching { repo.secretStore.clearServerPassword(id) }
                    runCatching { repo.secretStore.clearProxyPassword(id) }
                    // Client cert lookup requires the certId stored on the (now-deleted) profile;
                    // we no longer have that reference after the import overwrites NETWORKS_JSON,
                    // so cert files under /client_certs/*.bin for deleted profiles may still
                    // remain. They'll never be used (loadTlsClientCert requires both netId and
                    // certId), but a follow-up housekeeping pass could walk the directory.
                }
                // Disconnect live runtimes for profiles the import deleted, on Main since
                // disconnectNetwork mutates state.
                if (orphanedIds.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        for (id in orphanedIds) {
                            // disconnectNetwork is idempotent and safe to call on a netId that
                            // isn't currently connected, so we don't need a connection check.
                            disconnectNetwork(id)
                            // Drop in-memory state for the orphan: buffers, connection record,
                            // chathistory marker tracker. Same purge pattern as deleteNetwork()
                            // — the profile is gone from disk; its UI state should follow.
                            authBlockedReconnect.remove(id)
                            everRegisteredThisSession.remove(id)
                            cleanupNetworkMaps(id, resetReconnectState = true)
                            val prefix = "$id::"
                            chatHistory.forgetNetwork(id)
                            _state.update { st ->
                                val orphanKeys = st.buffers.keys.filter { it.startsWith(prefix) }
                                val newBuffers = if (orphanKeys.isEmpty()) st.buffers
                                                 else st.buffers - orphanKeys.toSet()
                                val newConns = if (st.connections.containsKey(id)) st.connections - id
                                               else st.connections
                                val newSelected = if (st.selectedBuffer.startsWith(prefix)) ""
                                                  else st.selectedBuffer
                                val newActive = if (st.activeNetworkId == id) {
                                    st.networks.firstOrNull { it.id != id }?.id
                                } else st.activeNetworkId
                                syncActiveNetworkSummary(st.copy(
                                    buffers = newBuffers,
                                    connections = newConns,
                                    selectedBuffer = newSelected,
                                    activeNetworkId = newActive,
                                ))
                            }
                        }
                    }
                }
                appContext.getString(R.string.vm_restore_done)
            }
            val msg = result.getOrElse { e -> appContext.getString(R.string.vm_restore_failed, e.message) }
            _state.update {
                it.copy(
                    backupMessage = msg,
                    backupIsError = result.isFailure,
                    restorePassphraseUri = null,
                    restorePassphraseWrong = false,
                )
            }
        }
    }

    /**
     * Reorder the network list after a drag-and-drop gesture.
     * [fromIndex] and [toIndex] are indices into the currently-displayed sorted list.
     * sortOrder values are reassigned sequentially so they remain stable after serialisation.
     */
    fun reorderNetworks(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val sorted = _state.value.networks
                .sortedWith(compareBy({ !it.isFavourite }, { it.sortOrder }, { it.name }))
                .toMutableList()
            if (fromIndex !in sorted.indices || toIndex !in sorted.indices) return@launch
            val item = sorted.removeAt(fromIndex)
            sorted.add(toIndex, item)
            val updated = sorted.mapIndexed { i, n -> n.copy(sortOrder = i) }
            // One write to avoid race conditions when upsertNetwork() is called in a loop.
            repo.saveNetworks(updated)
        }
    }

    /** Toggle the favourite flag for a network. Favourites sort before non-favourites. */
    fun toggleFavourite(netId: String) {
        viewModelScope.launch {
            val profile = _state.value.networks.firstOrNull { it.id == netId } ?: return@launch
            repo.upsertNetwork(profile.copy(isFavourite = !profile.isFavourite))
        }
    }

    /** Set whether a network appears in the buffer drawer / channel switcher. */
    fun setNetworkShowInSidebar(netId: String, show: Boolean) {
        viewModelScope.launch {
            val profile = _state.value.networks.firstOrNull { it.id == netId } ?: return@launch
            if (profile.showInSidebar == show) return@launch
            repo.upsertNetwork(profile.copy(showInSidebar = show))
        }
    }

    /**
     * Ask a bouncer to resend its upstream network list: soju gets `BOUNCER LISTNETWORKS`; ZNC gets
     * `ListNetworks` sent to *status. No-op for other kinds.
     */
    fun refreshBouncerNetworks(parentNetId: String) = refreshBouncerNetworksImpl(parentNetId)

    /**
     * Create a profile for a bouncer-reported upstream by cloning [parentNetId]'s bouncer
     * connection with [bouncerNetworkName] set; [upstreamId] is its soju netid when known. Only
     * for soju and ZNC parents. A matching profile that already exists is reported instead;
     * [UiState.bouncerCloneMessage] reports the result either way.
     */
    fun cloneBouncerNetwork(parentNetId: String, bouncerNetworkName: String, upstreamId: String? = null) =
        cloneBouncerNetworkImpl(parentNetId, bouncerNetworkName, upstreamId)

    /** Import every named upstream of [parentNetId]'s bouncer that has no profile yet. */
    fun importAllBouncerNetworks(parentNetId: String) = importAllBouncerNetworksImpl(parentNetId)

    /**
     * Ask [parentNetId]'s soju to add an upstream network, then, with [importAfter], import it as
     * a profile. The outcome arrives in [UiState.bouncerCloneMessage].
     */
    fun addBouncerNetwork(parentNetId: String, draft: BouncerNetworkDraft, importAfter: Boolean) =
        addBouncerNetworkImpl(parentNetId, draft, importAfter)

    /** Ask [parentNetId]'s soju to change upstream [upstreamId] to match [draft]. */
    fun editBouncerNetwork(parentNetId: String, upstreamId: String, draft: BouncerNetworkDraft) =
        editBouncerNetworkImpl(parentNetId, upstreamId, draft)

    /**
     * Ask [parentNetId]'s soju to remove upstream [upstreamId]; with [deleteProfiles], the
     * profiles imported for it go too once the bouncer confirms.
     */
    fun deleteBouncerNetwork(parentNetId: String, upstreamId: String, deleteProfiles: Boolean) =
        deleteBouncerNetworkImpl(parentNetId, upstreamId, deleteProfiles)

    /** Clear the transient bouncer-clone status message after the UI has consumed it. */
    fun clearBouncerCloneMessage() {
        _state.update { it.copy(bouncerCloneMessage = null) }
    }

    // ── E2E encryption ──
    // Used by the encryption dialog and the input lock badge. Synchronous, so it can be called from
    // Compose handlers. Targets are lowercased by E2eKeyStore.

    /** Snapshot of the current encryption state for a target, for UI rendering. */
    data class E2eKeyInfo(
        val scheme: com.boxlabs.hexdroid.crypto.E2eScheme,
        val fingerprint: String,
        /** Base64-encoded raw key bytes. Shown in the dialog so the user can copy/share. */
        val keyB64: String,
    )

    fun getE2eKeyInfo(networkId: String, target: String): E2eKeyInfo? {
        val entry = e2eKeyStore.get(networkId, target) ?: return null
        val fp = com.boxlabs.hexdroid.crypto.E2eFingerprint.compute(entry.scheme, entry.key)
        val b64 = android.util.Base64.encodeToString(entry.key, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        return E2eKeyInfo(entry.scheme, fp, b64)
    }

    // ── +AGE encryption dialog ──
    // No pasted key: +AGE uses a per-device identity, TOFU-pinned peers and a forward-secret
    // ratchet. Backs the dialog's safety number, pin/verify status and per-target enable flag.
    // Failures degrade to "unavailable".

    internal val ageP: com.boxlabs.hexdroid.crypto.AgePrimitives? by lazy {
        // Backend: BouncyCastle (native Ed25519 + X25519, audited). runCatching guards
        // class-init (missing provider class etc.) so a backend problem degrades +AGE to
        // unavailable rather than crashing the VM.
        runCatching { com.boxlabs.hexdroid.crypto.BouncyCastleAgePrimitives() }.getOrNull()
    }
    internal val ageIdentityStore by lazy {
        com.boxlabs.hexdroid.crypto.AgeIdentityStore(repo.secretStore)
    }
    internal val ageStore: com.boxlabs.hexdroid.crypto.AgeStore? by lazy {
        val p = ageP ?: return@lazy null
        runCatching {
            com.boxlabs.hexdroid.crypto.AgeStore(
                p,
                restore = { ageIdentityStore.restorePins() },
                persist = { ageIdentityStore.persistPins(it) },
            )
        }.getOrNull()
    }
    internal val ageEnabledPrefs by lazy {
        appContext.getSharedPreferences("hexdroid_age_enabled", Context.MODE_PRIVATE)
    }
    /** Everything the Encryption dialog needs to render the +AGE panel for a target. */
    data class AgeUiInfo(
        val available: Boolean,         // false if curve/keystore init failed on this device
        val myFingerprint: String,      // our +AGE safety number (Crockford quads), or ""
        val isChannel: Boolean,
        val peerKnown: Boolean,         // (queries) have we pinned this contact's key?
        val peerFingerprint: String?,   // their safety number, if known
        val peerVerified: Boolean,
        val enabled: Boolean,           // is +AGE turned on for this target?
    )

    fun getAgeUiInfo(networkId: String, target: String): AgeUiInfo {
        val p = ageP
        val store = ageStore
        val isChan = isChannelTarget(target)
        if (p == null || store == null) return AgeUiInfo(false, "", isChan, false, null, false, false)

        val me = runCatching { ageIdentityStore.loadOrCreate(p) }.getOrNull()
        val myFp = me?.let {
            com.boxlabs.hexdroid.crypto.AgeFingerprint.display(
                com.boxlabs.hexdroid.crypto.AgeFingerprint.of(p, it.publicBundle()))
        } ?: ""

        var peerKnown = false; var peerFp: String? = null; var peerVerified = false
        if (!isChan) {
            val pin = runCatching { store.lookupByNick(target) }.getOrNull()
            if (pin != null) {
                peerKnown = true
                val fpBytes = com.boxlabs.hexdroid.crypto.AgeFingerprint.of(p, pin)
                peerFp = com.boxlabs.hexdroid.crypto.AgeFingerprint.display(fpBytes)
                peerVerified = store.isVerified(com.boxlabs.hexdroid.crypto.AgeFingerprint.hex(fpBytes))
            }
        }
        val enabled = ageEnabledPrefs.getBoolean(ageKey(networkId, target), false)
        return AgeUiInfo(myFp.isNotEmpty(), myFp, isChan, peerKnown, peerFp, peerVerified, enabled)
    }

    fun enableAge(networkId: String, target: String) {
        ageEnabledPrefs.edit().putBoolean(ageKey(networkId, target), true).apply()
        ageP?.let { runCatching { ageIdentityStore.loadOrCreate(it) } }   // ensure we have an identity to announce
        val bridge = ageBridgeFor(networkId)
        if (isChannelTarget(target)) bridge?.enableChat(target)  // channels: announce + hostless key agreement
        else {
            bridge?.enablePm(target)                             // 1:1 PM: announce + X3DH handshake
            // Start the reliability retry loop now, so the handshake completes (and shows Established)
            // even if the user enables without sending a message. No-op once the ratchet is up.
            if (bridge != null && !bridge.pmReady(target)) scheduleAgePmGrace(networkId, target, bridge)
        }
    }

    fun disableAge(networkId: String, target: String) {
        ageEnabledPrefs.edit().remove(ageKey(networkId, target)).apply()
        ageBridgeFor(networkId)?.disableChat(target)
        ageBridgeFor(networkId)?.disablePm(target)
    }

    fun markAgeContactVerified(networkId: String, target: String) {
        val p = ageP ?: return
        val store = ageStore ?: return
        val pin = runCatching { store.lookupByNick(target) }.getOrNull() ?: return
        store.markVerified(com.boxlabs.hexdroid.crypto.AgeFingerprint.hex(
            com.boxlabs.hexdroid.crypto.AgeFingerprint.of(p, pin)))
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Script runtime owns the engine/manager and the bridge HexDroidScriptHost
    //  delegates to. Lazy: nothing is built until scripts are first touched. Call
    //  initScripts() once after startup so enabled scripts load and can handle events.
    // ──────────────────────────────────────────────────────────────────────────

    private val scriptHost: com.boxlabs.hexdroid.script.ScriptHost by lazy {
        com.boxlabs.hexdroid.script.HexDroidScriptHost(this)
    }
    internal val scriptEngineDelegate = lazy {
        com.boxlabs.hexdroid.script.ScriptEngine(scriptHost, com.boxlabs.hexdroid.script.HexScriptBackend())
    }
    val scriptEngine: com.boxlabs.hexdroid.script.ScriptEngine by scriptEngineDelegate
    internal val scriptManager: com.boxlabs.hexdroid.script.ScriptManager by lazy {
        com.boxlabs.hexdroid.script.ScriptManager(
            appContext,
            scriptEngine,
            appContext.getSharedPreferences("hexdroid_scripts", Context.MODE_PRIVATE),
        )
    }

    /** Reactive scripts state for the Scripts UI — updates on seed/install/toggle/remove/reload. */
    internal val _scriptsState = kotlinx.coroutines.flow.MutableStateFlow(
        com.boxlabs.hexdroid.script.ScriptsUiState(emptyList(), "hex")
    )
    val scriptsState: kotlinx.coroutines.flow.StateFlow<com.boxlabs.hexdroid.script.ScriptsUiState> = _scriptsState
    /**
     * Load scripts, once. Called as the ViewModel starts, so scripts run when the app starts in
     * the background (after a reboot, or restarted by the keep-alive service) with no screen
     * open; the UI's call is then a no-op.
     */
    fun initScripts() {
        if (scriptsStarted) return
        scriptsStarted = true
        _scriptLaunchers.value = emptyList()
        runCatching { scriptManager.seedBundled(); scriptManager.reloadAll() }
        refreshScripts()
    }
    private var scriptsStarted = false

    // Posted rather than run inline, so it starts once construction has finished.
    init { viewModelScope.launch(Dispatchers.Main) { initScripts() } }

    fun setScriptEnabled(name: String, enabled: Boolean) { beforeScriptReload(); scriptManager.setEnabled(name, enabled); refreshScripts() }
    fun installScript(name: String, source: String) { beforeScriptReload(); scriptManager.install(name, source); refreshScripts() }
    fun removeScript(name: String) { beforeScriptReload(); scriptManager.remove(name); refreshScripts() }
    fun restoreBundledScripts() { beforeScriptReload(); scriptManager.restoreBundled(); refreshScripts() }
    fun reloadScripts() { beforeScriptReload(); scriptManager.reloadAll(); refreshScripts() }
    fun readScript(name: String): String? = scriptManager.read(name)

    /** Reset a bundled script to its shipped default; returns the restored source (or null). */
    fun revertScript(name: String): String? {
        beforeScriptReload()
        val src = scriptManager.revertToBundled(name)
        refreshScripts()
        return src
    }

    // ---- script-contributed launchers (a menu that appears when scripts register entries) ----
    data class ScriptLauncher(val id: String, val label: String, val command: String)
    internal val _scriptLaunchers = kotlinx.coroutines.flow.MutableStateFlow<List<ScriptLauncher>>(emptyList())
    val scriptLaunchers: kotlinx.coroutines.flow.StateFlow<List<ScriptLauncher>> = _scriptLaunchers
    // ---- script file picking -------------------------------------------------
    /** An outstanding request from a script for the user to choose a file. */
    data class ScriptFilePick(val id: String, val mimeFilter: String, val buffer: String?)

    internal val _scriptFilePick = kotlinx.coroutines.flow.MutableStateFlow<ScriptFilePick?>(null)
    val scriptFilePick: kotlinx.coroutines.flow.StateFlow<ScriptFilePick?> = _scriptFilePick

    internal var scriptPickCallback: ((com.boxlabs.hexdroid.script.ScriptMediaRef?) -> Unit)? = null

    /** The script whose pick prompt is showing. */
    internal var scriptPickOwner: String? = null

    /** A file handed to [owner], addressed by an opaque token. */
    internal data class ScriptMediaGrant(val uri: android.net.Uri, val owner: String)

    /**
     * Files the user handed to scripts this session, keyed by an opaque token. Scripts get the
     * token, never the URI, and only the script that asked can use it. Dropped on script reload
     * and capped, since read permission on each lasts only as long as the process.
     */
    internal val scriptMediaTokens = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, ScriptMediaGrant>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScriptMediaGrant>) = size > 16
        }
    )

    /** Per script, when a declined pick stops refusing requests the user did not start. */
    internal val scriptPickQuietUntil = HashMap<String, Long>()

    /** Called by the UI once the user has picked a file or declined. */
    fun scriptFilePickResult(uri: android.net.Uri?) {
        val cb = scriptPickCallback
        val owner = scriptPickOwner
        scriptPickCallback = null
        scriptPickOwner = null
        _scriptFilePick.value = null
        if (cb == null || owner == null) return
        if (uri == null) {
            scriptPickQuietUntil[owner] = System.currentTimeMillis() + SCRIPT_PICK_QUIET_MS
            cb(null)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val name = queryDisplayName(uri) ?: "file"
            val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
                ?: "application/octet-stream"
            val size = runCatching {
                appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
                    } else -1L
                } ?: -1L
            }.getOrDefault(-1L)
            val token = java.util.UUID.randomUUID().toString()
            scriptMediaTokens[token] = ScriptMediaGrant(uri, owner)
            withContext(Dispatchers.Main) {
                cb(com.boxlabs.hexdroid.script.ScriptMediaRef(token, name, mime, size))
            }
        }
    }

    internal val scriptUploadDirLock = Any()
    internal var scriptUploadDirSwept = false

    // ---- a mounted script view (e.g. the poker table), shown as an overlay ----
    internal val _scriptView = kotlinx.coroutines.flow.MutableStateFlow<com.boxlabs.hexdroid.script.ScriptView?>(null)
    val scriptView: kotlinx.coroutines.flow.StateFlow<com.boxlabs.hexdroid.script.ScriptView?> = _scriptView
    fun closeScriptView() {
        _scriptView.value = null
        // Also tell the script to stop driving its view; otherwise a pending timer (e.g. poker's
        // scheduled bot loop) re-renders and the table pops straight back. Generic signal that any
        // view-mounting script can listen for to halt.
        runCatching {
            scriptEngine.dispatch(
                "SIGNAL:VIEW_CLOSED",
                com.boxlabs.hexdroid.script.EventData(
                    network = _state.value.activeNetworkId ?: "",
                    buffer = splitKey(_state.value.selectedBuffer).second,
                ),
            )
        }
    }

    /** Run a launcher's command/signal (opens its view). */
    fun runScriptLauncher(command: String) = runScriptAction(command, emptyList())

    /** A tap inside a mounted script view -> run the alias or fire the signal named by the button. */
    fun scriptViewAction(actionId: String, args: List<String>) = runScriptAction(actionId, args)

    /** Whether the app was last reported in front, so APPACTIVE fires only on a change. */
    internal var appActiveForScripts: Boolean? = null

    /**
     * Fired by the script view screen when the device rotates while a script view is mounted,
     * so a layout-aware script (poker) can re-render for the new orientation. Scripts opt in
     * with `on SIGNAL:screenchange { ... }`; scripts without a handler are unaffected.
     */
    fun scriptScreenChanged() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            runCatching {
                scriptEngine.dispatch(
                    "SIGNAL:screenchange",
                    com.boxlabs.hexdroid.script.EventData(network = "", buffer = ""),
                )
            }
        }
    }

    internal val ageBridges =
        java.util.concurrent.ConcurrentHashMap<String, com.boxlabs.hexdroid.script.cap.AgeScriptBridge>()
    /** The active network's bridge, for script capabilities and user actions that run on the current buffer. */
    internal val ageBridge: com.boxlabs.hexdroid.script.cap.AgeScriptBridge?
        get() = ageBridgeFor(_state.value.activeNetworkId)

    // Conversations ("netId\u0000peer") with a running +AGE PM grace timer, so we schedule exactly one.
    internal val agePmGracePending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal val AGE_PM_GRACE_MS = 3000L
    // How many times the reliability layer re-drives an unestablished PM handshake before giving up
    // (about AGE_PM_GRACE_MS apart). Held messages still flush the instant the ratchet comes up, even
    // if that is after the cap; this only bounds the active retransmit nudging.
    internal val AGE_PM_MAX_RETRIES = 5
    // Ceiling for the exponential handshake-retry backoff, so the interval never grows unbounded.
    internal val AGE_PM_RETRY_CAP_MS = 24000L

    /**
     * Generate a fresh 256-bit AES-GCM key for [target] on [networkId] and store it.
     * Returns the key info so the dialog can display the fingerprint and copyable
     * base64 immediately. Overwrites any existing key (the dialog confirms first).
     */
    fun generateE2eKey(networkId: String, target: String): E2eKeyInfo {
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        e2eKeyStore.set(networkId, target, com.boxlabs.hexdroid.crypto.E2eKeyStore.Entry(
            com.boxlabs.hexdroid.crypto.E2eScheme.AGM, key))
        bumpE2eKeyVersion()
        return E2eKeyInfo(
            com.boxlabs.hexdroid.crypto.E2eScheme.AGM,
            com.boxlabs.hexdroid.crypto.E2eFingerprint.compute(com.boxlabs.hexdroid.crypto.E2eScheme.AGM, key),
            android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING),
        )
    }

    /** Result of attempting to install a key entered by the user. */
    sealed class E2eImportResult {
        data class Success(val info: E2eKeyInfo) : E2eImportResult()
        data class Failure(val reason: String) : E2eImportResult()
    }

    fun importE2eKey(networkId: String, target: String, b64: String): E2eImportResult {
        val cleaned = b64.trim().replace(Regex("\\s+"), "")
        if (cleaned.isEmpty()) return E2eImportResult.Failure(appContext.getString(R.string.vm_key_empty))
        val keyBytes = try {
            android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return E2eImportResult.Failure(appContext.getString(R.string.vm_key_not_base64))
        }
        if (keyBytes.size != 32) {
            return E2eImportResult.Failure(appContext.getString(R.string.vm_key_wrong_length, keyBytes.size))
        }
        return try {
            e2eKeyStore.set(networkId, target, com.boxlabs.hexdroid.crypto.E2eKeyStore.Entry(
                com.boxlabs.hexdroid.crypto.E2eScheme.AGM, keyBytes))
            bumpE2eKeyVersion()
            val fp = com.boxlabs.hexdroid.crypto.E2eFingerprint.compute(com.boxlabs.hexdroid.crypto.E2eScheme.AGM, keyBytes)
            E2eImportResult.Success(E2eKeyInfo(
                com.boxlabs.hexdroid.crypto.E2eScheme.AGM, fp,
                android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING),
            ))
        } catch (t: Throwable) {
            E2eImportResult.Failure(appContext.getString(R.string.vm_key_store_failed, t.message ?: t::class.java.simpleName))
        }
    }

    fun clearE2eKeyForTarget(networkId: String, target: String) {
        e2eKeyStore.clear(networkId, target)
        bumpE2eKeyVersion()
    }

    /**
     * Set a Blowfish key for [target] from a passphrase used directly as the key, as HexChat's
     * fishlim does, so both clients derive the same key. Passphrases over 56 bytes are rejected
     * rather than truncated. Returns the key info, or a failure reason.
     */
    fun setE2eBlowfishPassphrase(networkId: String, target: String, passphrase: String): E2eImportResult {
        val raw = passphrase.toByteArray(Charsets.UTF_8)
        if (raw.isEmpty()) return E2eImportResult.Failure(appContext.getString(R.string.vm_pass_empty))
        if (raw.size < 4) return E2eImportResult.Failure(appContext.getString(R.string.vm_pass_too_short))
        if (raw.size > 56) return E2eImportResult.Failure(appContext.getString(R.string.vm_pass_too_long, raw.size))
        return try {
            e2eKeyStore.set(networkId, target, com.boxlabs.hexdroid.crypto.E2eKeyStore.Entry(
                com.boxlabs.hexdroid.crypto.E2eScheme.BLOWFISH, raw))
            bumpE2eKeyVersion()
            val fp = com.boxlabs.hexdroid.crypto.E2eFingerprint.compute(com.boxlabs.hexdroid.crypto.E2eScheme.BLOWFISH, raw)
            // The "key bytes" surfaced to the dialog for Blowfish are the
            // passphrase the user typed. Showing them back is useful for the
            // "reveal key" flow so the user can re-copy the passphrase for
            // their HexChat-using friend later.
            E2eImportResult.Success(E2eKeyInfo(
                com.boxlabs.hexdroid.crypto.E2eScheme.BLOWFISH, fp,
                android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING),
            ))
        } catch (t: Throwable) {
            E2eImportResult.Failure(appContext.getString(R.string.vm_key_store_failed, t.message ?: t::class.java.simpleName))
        }
    }

    /** Stored credentials for one connect attempt, read before it starts. */
    internal class ConnectSecrets(
        val profileId: String,
        val certId: String?,
        val sasl: com.boxlabs.hexdroid.data.SecretStore.SecretResult,
        val server: String?,
        val proxy: String?,
        val cert: TlsClientCert?,
    )

    /**
     * Connect (or reconnect) a network.
     *
     * @param clearAuthBlock True only for user-initiated retries, which clear the auth-failure
     *   block; automated callers pass false.
     * @param openServerBuffer Switch to this network's server buffer once the attempt starts. Set
     *   only by the Connect button, never by automated paths.
     */
    fun connectNetwork(
        netId: String,
        force: Boolean = false,
        clearAuthBlock: Boolean = false,
        openServerBuffer: Boolean = false,
    ) {
        if (clearAuthBlock) authBlockedReconnect.remove(netId)
        viewModelScope.launch {
            // Ensure flap state is loaded from DataStore before checking it.
            // In the normal case this is a no-op (init already loaded it); this guards
            // the race where a connect is requested before the init coroutine completes.
            ensureFlapPausedLoaded()
            ensureStsPoliciesLoaded()
            var started = false
            withNetLock(netId) {
                // An automated connect to an auth-blocked network is refused, leaving the "Auth
                // failed - reconnect halted" status in place.
                if (!clearAuthBlock && netId in authBlockedReconnect) return@withNetLock
                connectNetworkInternal(netId, force)
                started = true
            }
            if (openServerBuffer && started) {
                // connectNetworkInternal bails out and raises a dialog for a plaintext
                // host or a missing local-network permission.
                val st = _state.value
                if (st.plaintextWarningNetworkId == null && st.localNetworkWarningNetworkId == null) {
                    openBuffer(bufKey(netId, "*server*"))
                }
            }
        }
    }

    fun reconnectActive() {
        val netId = _state.value.activeNetworkId ?: return
        val cur = _state.value.connections[netId]
        // If we were never connected / no runtime exists, treat reconnect as a connect. This is also
        // the usual state during a long auto-reconnect backoff so we MUST clear the backoff/flap/auth state here too.
        if ((cur?.connected != true && cur?.connecting != true) && runtimes[netId] == null) {
            autoReconnectJobs.remove(netId)?.cancel()
            reconnectAttempts.remove(netId)
            clearFlapPaused(netId)
            pingTimeoutTimestamps.remove(netId)
            connectNetwork(netId, force = true, clearAuthBlock = true)
            return
        }
        reconnectNetwork(netId)
    }

    fun disconnectActive() {
        val netId = _state.value.activeNetworkId ?: return
        disconnectNetwork(netId)
    }

    /**
     * Disconnect [netId]. The optional [reasonOverride] is sent to the server as
     * the QUIT message; if null or blank, the persisted `settings.quitMessage`
     * (or "Client disconnect" if that's also blank) is used. This is what makes
     * `/quit Going to lunch, back in an hour` actually send "Going to lunch,
     * back in an hour" on the wire instead of the user's default quit message.
     */
    fun disconnectNetwork(netId: String, reasonOverride: String? = null) {
        val quitMsg = reasonOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: _state.value.settings.quitMessage.ifBlank { "Client disconnect" }
        viewModelScope.launch {
            withNetLock(netId) {
            rescheduleStsOnClose(netId)
            val removedDesired = desiredConnected.remove(netId)
            if (removedDesired) persistDesiredNetworkIds()
            manualDisconnecting.add(netId)
            deferredConnects.remove(netId)
            pendingUriJoins.remove(netId)
            reconnectAttempts.remove(netId)  // Clear reconnect backoff
            autoReconnectJobs.remove(netId)?.cancel()
            cleanupNetworkMaps(netId, resetReconnectState = true)

            val oldRt = runtimes.remove(netId)
            runCatching { oldRt?.client?.disconnect(quitMsg) }
            // Ensure we hard-close even if QUIT can't be delivered (e.g., during network handover).
            runCatching { oldRt?.client?.forceClose() }
            runCatching { oldRt?.job?.cancel() }

            val st = _state.value
            val prev = st.connections[netId] ?: NetConnState()
            val newConns = st.connections + (netId to prev.copy(connected = false, connecting = false, status = appContext.getString(R.string.vm_status_disconnected)))
            _state.value = syncActiveNetworkSummary(st.copy(connections = newConns))
            if (st.activeNetworkId == netId) clearConnectionNotification()
        }
            }
    }

    /**
     * On returning to the foreground, re-check connection state and reconnect wanted networks that
     * dropped while backgrounded. Networks already connecting or with a reconnect queued are
     * skipped.
     */
    fun resyncConnectionsOnResume() {
        val st = _state.value
        var changed = false
        val newMap = st.connections.toMutableMap()
        val networksToReconnect = mutableListOf<String>()

        for (net in st.networks) {
            val rt = runtimes[net.id]
            val cur = newMap[net.id] ?: NetConnState()

            // Don't touch anything that is already mid-connect or has a reconnect scheduled -
            // isConnectedNow() is unreliable during the handshake and we'd create a double-reconnect.
            if (cur.connecting) continue
            if (autoReconnectJobs.containsKey(net.id)) continue

            val actual = rt?.client?.isConnectedNow() == true

            // Socket is alive but UI thinks we're disconnected - correct the UI.
            if (actual && !cur.connected) {
                newMap[net.id] = cur.copy(connected = true, connecting = false, status = appContext.getString(R.string.vm_status_connected))
                changed = true
            }

            // Socket is gone but UI thinks we're connected - correct the UI and maybe reconnect.
            if (!actual && cur.connected) {
                newMap[net.id] = cur.copy(connected = false, connecting = false, status = appContext.getString(R.string.vm_status_disconnected))
                changed = true
                if (desiredConnected.contains(net.id)) networksToReconnect.add(net.id)
            }

            // Not connected, not connecting, but should be - reconnect.
            if (!actual && !cur.connected && desiredConnected.contains(net.id)) {
                if (!networksToReconnect.contains(net.id)) networksToReconnect.add(net.id)
            }
        }

        if (changed) {
            _state.value = syncActiveNetworkSummary(st.copy(connections = newMap))
        }

        networksToReconnect.retainAll { it in deferredConnects || autoReconnectAllowed(it) }
        if (networksToReconnect.isNotEmpty() && hasInternetConnection()) {
            viewModelScope.launch {
                delay(500) // Brief delay to let UI settle
                for (netId in networksToReconnect) {
                    // Re-check: a reconnect job may have been scheduled in the meantime.
                    if (autoReconnectJobs.containsKey(netId)) continue
                    val cur = _state.value.connections[netId]
                    if (cur?.connecting == true || cur?.connected == true) continue
                    append(bufKey(netId, "*server*"), from = null, text = "*** " + appContext.getString(R.string.status_resuming), doNotify = false)
                    connectNetwork(netId, force = true)
                }
            }
        }
    }

    // Sending

    /**
     * Record that the user has read up to the current last message in [bufferKey], updating
     * [UiBuffer.lastReadTimestamp] and sending MARKREAD to the server if the cap is available.
     * Passing an explicit [timestamp] overrides the last-message lookup (used for server-driven
     * read marker updates, e.g. from a bouncer).
     */
    fun markBufferRead(bufferKey: String, timestamp: String? = null, fromServer: Boolean = false) {
        if (timestamp != null) {
            // Explicit timestamp from server - apply directly.
            val (netId, bufferName) = splitKey(bufferKey)
            val rt = runtimes[netId] ?: return
            val st = _state.value
            val buf = st.buffers[bufferKey]
            if (buf != null) {
                _state.value = st.copy(buffers = st.buffers + (bufferKey to buf.copy(lastReadTimestamp = timestamp)))
            }
            // A value the server gave us needs no echoing back: it already holds it, and the
            // spec has it ignore anything not newer than what it has.
            if (fromServer) return
            if (rt.client.hasCap("draft/read-marker") && !isPseudoBuffer(bufferName)) {
                viewModelScope.launch { rt.client.sendBackground("MARKREAD $bufferName timestamp=$timestamp") }
            }
        } else {
            // No explicit timestamp: anchor to the last message's own time.
            stampReadMarker(bufferKey)
        }
    }

    // --- Outgoing draft/typing indicator ---
    // typingLastKey stores the FULL buffer key (netId::bufferName) so that cross-network
    // "done" is sent to the correct connection when the user switches between buffers on
    // different networks (e.g. #general on net-A and #general on net-B).
    internal var typingDoneJob: kotlinx.coroutines.Job? = null
    internal var typingLastKey: String? = null

    /** When a typing notification last went to each buffer key; draft/typing requires 3 s between them. */
    internal val typingSentAtMs: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()
    /** "done" notifications held back by that spacing, per buffer key. */
    internal val pendingTypingDone: MutableMap<String, kotlinx.coroutines.Job> =
        java.util.concurrent.ConcurrentHashMap()
    internal val TYPING_MIN_INTERVAL_MS = 3_000L

    // Auto-expiry jobs for *received* typing indicators, keyed "$bufferKey/$nick".
    internal val receivedTypingExpiryJobs: MutableMap<String, kotlinx.coroutines.Job> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Called by the UI whenever the input text changes. Sends "active" at most once every 3 s
     * while composing, "paused" after 6 s without changes and "done" 24 s after that, or when
     * the input is cleared. A slash command doesn't count as composing.
     *
     * No-op unless [UiSettings.sendTypingIndicator] is on.
     */
    fun notifyTypingChanged(text: String) {
        val st = _state.value

        // Privacy gate: user must explicitly opt in to broadcasting typing status.
        if (!st.settings.sendTypingIndicator) return

        val currentKey = st.selectedBuffer
        if (currentKey.isBlank()) return
        val (netId, bufferName) = splitKey(currentKey)
        val rt = runtimes[netId] ?: return
        if (!rt.client.hasCap("draft/typing") && !rt.client.hasCap("typing")
            && !rt.client.hasCap("message-tags")) return
        // DCC: A pseudo-buffer name is not a nick or a channel, so a typing TAGMSG addressed to it comes straight back as 401.
        // Also bounces back as ERR_NOSUCHNICK. Skip silently.
        if (isPseudoBuffer(bufferName)) return
        if (isDccChatBufferName(bufferName)) return

        typingDoneJob?.cancel()

        val isCommand = SlashInput.isCommand(SlashInput.stripLeadingFormatting(text.trimStart()))
        if (text.isEmpty() || isCommand) {
            typingLastKey?.let { sendTypingDone(it) }
            typingLastKey = null
            return
        }

        typingLastKey?.takeIf { it != currentKey }?.let { sendTypingDone(it) }
        typingLastKey = currentKey
        pendingTypingDone.remove(currentKey)?.cancel()

        if (System.currentTimeMillis() - (typingSentAtMs[currentKey] ?: 0L) >= TYPING_MIN_INTERVAL_MS) {
            sendTypingNow(currentKey, "active")
        }

        typingDoneJob = viewModelScope.launch {
            delay(6_000L)
            sendTypingNow(currentKey, "paused")
            delay(24_000L)
            sendTypingNow(currentKey, "done")
            typingLastKey = null
        }
    }

    fun sendInput(raw: String, targetKey: String? = null) = sendInputInternal(raw, 0, targetKey)

    /**
     * Send what the user typed in the input box. `on INPUT` script handlers see it first and may
     * rewrite or halt it; lines sent by scripts, buttons and menus use [sendInput] and skip them.
     * [pasteGuard] is false for composers outside the main screen, which cannot show its dialog.
     */
    fun sendUserInput(raw: String, targetKey: String? = null, pasteGuard: Boolean = true) {
        onAutoAwayInteraction()
        val st = _state.value
        val key = targetKey?.takeIf { it.isNotBlank() && st.buffers.containsKey(it) } ?: st.selectedBuffer
        if (key.isBlank() || raw.isBlank()) return
        val (netId, bufferName) = splitKey(key)
        val result = scriptEngine.onInput(com.boxlabs.hexdroid.script.InputEvent(netId, bufferName, raw))
        if (result.halted) return
        sendInputInternal(result.text, 0, targetKey, fromUser = true, pasteConfirmed = !pasteGuard)
    }

    fun joinChannel(channel: String) {
        val netId = _state.value.activeNetworkId ?: return
        val rt = runtimes[netId] ?: return
        // Mark as an explicit user join so the self-JOIN echo switches the buffer even if a
        // reconnect suppression window is active. (openBuffer below also switches us there
        // immediately; this keeps the two switch paths consistent.)
        channel.split(",").forEach { ch ->
            ch.trim().takeIf { it.isNotBlank() }?.let { rt.pendingUserJoinSwitch.add(casefoldText(netId, it)) }
        }
        viewModelScope.launch { rt.client.sendRaw("JOIN $channel") }
        openBuffer(resolveBufferKey(netId, channel))
    }

    /**
     * Request the channel list. With ELIST user-count filtering (ELIST=...U) a range is always
     * sent, from [minUsers] (at least ">0") to [maxUsers] or [DEFAULT_LIST_MAX_USERS]; otherwise a
     * plain LIST filtered client-side.
     */
    fun requestList(minUsers: Int? = null, maxUsers: Int? = null) {
        val netId = _state.value.activeNetworkId ?: return
        val rt = runtimes[netId] ?: return
        val supportsUserFilter = rt.support.elist?.contains('U') == true

        val listCmd = if (supportsUserFilter) {
            val lo = (minUsers ?: 0).coerceAtLeast(0)
            val hi = maxUsers?.takeIf { it > 0 } ?: DEFAULT_LIST_MAX_USERS
            "LIST >$lo,<$hi"
        } else {
            "LIST"
        }

        viewModelScope.launch {
            _channelListBuffer.clear()
            _channelListLastFlushMs = 0L
            _state.update {
                it.copy(
                    listInProgress = true,
                    channelDirectory = emptyList(),
                    listElistUserFilter = supportsUserFilter
                )
            }
            rt.client.sendRaw(listCmd)
        }
    }

    fun whois(nick: String) {
        // Route through the slash-command path so WHOIS replies can be routed
        // back to the current buffer (channel/query) instead of always the server buffer.
        sendInput("/whois $nick")
    }

    // Ignore list

    fun setDccAutoAccept(netId: String, nick: String, enabled: Boolean) {
        val base = nick.trim().takeIf { it.isNotBlank() } ?: return
        val st = _state.value
        val net = st.networks.firstOrNull { it.id == netId } ?: return
        val nextList = if (enabled) {
            (net.dccAutoAcceptNicks + base)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
        } else {
            net.dccAutoAcceptNicks.filterNot { it.equals(base, ignoreCase = true) }
        }
        val updated = net.copy(dccAutoAcceptNicks = nextList)
        updateNetworkInState(updated)
        viewModelScope.launch { repo.upsertNetwork(updated) }
        val sel = _state.value.selectedBuffer
        val (selNet, _) = splitKey(sel)
        val dest = if (sel.isNotBlank() && selNet == netId) sel else bufKey(netId, "*server*")
        val msg = if (enabled) R.string.vm_dcc_auto_accept_on else R.string.vm_dcc_auto_accept_off
        append(dest, from = null, text = "*** " + appContext.getString(msg, base), isLocal = true, doNotify = false)
    }

    fun ignoreNick(netId: String, nick: String) {
        val base = canonicalIgnoreNick(nick) ?: return
        val st = _state.value
        val net = st.networks.firstOrNull { it.id == netId } ?: return
        val nextList = (net.ignoredNicks + base)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        val updated = net.copy(ignoredNicks = nextList)
        updateNetworkInState(updated)
        viewModelScope.launch { repo.upsertNetwork(updated) }
        val sel = _state.value.selectedBuffer
        val (selNet, _) = splitKey(sel)
        val dest = if (sel.isNotBlank() && selNet == netId) sel else bufKey(netId, "*server*")
        append(dest, from = null, text = "*** " + appContext.getString(R.string.vm_ignoring, base), isLocal = true, doNotify = false)
    }

    /** Sets what the ignore entry [mask] drops on [netId]. The default set is stored as no entry. */
    fun setIgnoreTypes(netId: String, mask: String, types: Set<IgnoreType>) {
        val st = _state.value
        val net = st.networks.firstOrNull { it.id == netId } ?: return
        val key = IgnoreRules.key(mask)
        if (net.ignoredNicks.none { IgnoreRules.key(it) == key }) return
        val levels = if (types == IgnoreRules.DEFAULT_TYPES) net.ignoreLevels - key else net.ignoreLevels + (key to types)
        if (levels == net.ignoreLevels) return
        val updated = net.copy(ignoreLevels = levels)
        updateNetworkInState(updated)
        viewModelScope.launch { repo.upsertNetwork(updated) }
    }

    fun unignoreNick(netId: String, nick: String) {
        val base = canonicalIgnoreNick(nick) ?: return
        val st = _state.value
        val net = st.networks.firstOrNull { it.id == netId } ?: return
        val nextList = net.ignoredNicks.filterNot { it.equals(base, ignoreCase = true) }
        val updated = net.copy(ignoredNicks = nextList, ignoreLevels = net.ignoreLevels - IgnoreRules.key(base))
        updateNetworkInState(updated)
        viewModelScope.launch { repo.upsertNetwork(updated) }
        val sel = _state.value.selectedBuffer
        val (selNet, _) = splitKey(sel)
        val dest = if (sel.isNotBlank() && selNet == netId) sel else bufKey(netId, "*server*")
        append(dest, from = null, text = "*** " + appContext.getString(R.string.vm_unignored, base), isLocal = true, doNotify = false)
    }

    /**
     * Mute highlight/PM notifications from [nick] on [netId] without ignoring the user:
     * their messages still land in the buffer (see [isNotifyIgnoredSender]), only the alert
     * is suppressed. Stores the bare nick in NetworkProfile.highlightIgnoreMasks, which the
     * notify gate matches case-insensitively as a plain nick.
     */
    fun ignoreNotifications(netId: String, nick: String) {
        val base = canonicalIgnoreNick(nick) ?: return
        val st = _state.value
        val net = st.networks.firstOrNull { it.id == netId } ?: return
        val nextList = (net.highlightIgnoreMasks + base)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
        val updated = net.copy(highlightIgnoreMasks = nextList)
        updateNetworkInState(updated)
        viewModelScope.launch { repo.upsertNetwork(updated) }
        val sel = _state.value.selectedBuffer
        val (selNet, _) = splitKey(sel)
        val dest = if (sel.isNotBlank() && selNet == netId) sel else bufKey(netId, "*server*")
        append(dest, from = null, text = "*** " + appContext.getString(R.string.vm_notif_muted, base), isLocal = true, doNotify = false)
    }

    /**
     * Reverse [ignoreNotifications]. Removes only the exact bare-nick entry, leaving any
     * glob/regex masks the user added by hand in highlightIgnoreMasks intact.
     */
    fun unignoreNotifications(netId: String, nick: String) {
        val base = canonicalIgnoreNick(nick) ?: return
        val st = _state.value
        val net = st.networks.firstOrNull { it.id == netId } ?: return
        val nextList = net.highlightIgnoreMasks.filterNot { it.trim().equals(base, ignoreCase = true) }
        val updated = net.copy(highlightIgnoreMasks = nextList)
        updateNetworkInState(updated)
        viewModelScope.launch { repo.upsertNetwork(updated) }
        val sel = _state.value.selectedBuffer
        val (selNet, _) = splitKey(sel)
        val dest = if (sel.isNotBlank() && selNet == netId) sel else bufKey(netId, "*server*")
        append(dest, from = null, text = "*** " + appContext.getString(R.string.vm_notif_unmuted, base), isLocal = true, doNotify = false)
    }

    fun openIgnoreList() { goTo(AppScreen.IGNORE) }
    // IRC event handling

    internal fun handleEvent(netId: String, ev: IrcEvent) {
        when (ev) {
            is IrcEvent.Status, is IrcEvent.Connected, is IrcEvent.LagUpdated,
            is IrcEvent.Disconnected, is IrcEvent.RawLine, is IrcEvent.SendResolved,
            is IrcEvent.MultilineSendFailed, is IrcEvent.Error, is IrcEvent.AuthFailed,
            is IrcEvent.TlsFingerprintLearned, is IrcEvent.TlsHostnameMismatch,
            is IrcEvent.TlsFingerprintChanged, is IrcEvent.ServerLine, is IrcEvent.ServerText,
            is IrcEvent.JoinError, is IrcEvent.ChannelModeIs, is IrcEvent.YoureOper,
            is IrcEvent.YoureDeOpered, is IrcEvent.BanListItem, is IrcEvent.BanListEnd,
            is IrcEvent.QuietListItem, is IrcEvent.QuietListEnd, is IrcEvent.ExceptListItem,
            is IrcEvent.ExceptListEnd, is IrcEvent.InvexListItem, is IrcEvent.InvexListEnd,
            is IrcEvent.ISupport, is IrcEvent.StsReceived, is IrcEvent.Registered,
            is IrcEvent.NickChanged -> handleServerEvent(netId, ev)
            is IrcEvent.DccOfferEvent, is IrcEvent.DccResumeRequest, is IrcEvent.DccAcceptResponse,
            is IrcEvent.DccChatOfferEvent, is IrcEvent.NotOnChannel, is IrcEvent.ChatMessage,
            is IrcEvent.Notice, is IrcEvent.CtcpReply -> handleMessageEvent(netId, ev)
            is IrcEvent.ChannelModeLine, is IrcEvent.Names, is IrcEvent.NamesEnd, is IrcEvent.Joined,
            is IrcEvent.Parted, is IrcEvent.Kicked, is IrcEvent.Quit, is IrcEvent.TopicReply,
            is IrcEvent.TopicWhoTime, is IrcEvent.Topic, is IrcEvent.ChannelUserMode,
            is IrcEvent.ChannelListStart, is IrcEvent.ChannelListItem, is IrcEvent.ChannelListEnd,
            is IrcEvent.TryAgain -> handleChannelEvent(netId, ev)
            is IrcEvent.Chghost, is IrcEvent.AccountChanged, is IrcEvent.Setname,
            is IrcEvent.InviteReceived, is IrcEvent.ServerError, is IrcEvent.AwayChanged,
            is IrcEvent.CapNew, is IrcEvent.CapDel, is IrcEvent.BouncerNetwork,
            is IrcEvent.MonitorStatus, is IrcEvent.ReadMarker, is IrcEvent.HistoryRequestFailed,
            is IrcEvent.SelfAwayChanged, is IrcEvent.HistoryBatchStart, is IrcEvent.HistoryBatchEnd,
            is IrcEvent.JoinHistoryWanted, is IrcEvent.HistoryTarget, is IrcEvent.OutgoingEcho,
            is IrcEvent.CtcpExchange, is IrcEvent.WebPushVapidReady, is IrcEvent.WebPushRegistered,
            is IrcEvent.WebPushUnregistered, is IrcEvent.WebPushFailed, is IrcEvent.TypingStatus,
            is IrcEvent.WhoxReply, is IrcEvent.ChannelRenamed, is IrcEvent.MessageReaction,
            is IrcEvent.MetadataChanged, is IrcEvent.AccountRegUpdate, is IrcEvent.MessageRedacted,
            is IrcEvent.ChannelModeChanged, is IrcEvent.ReadReceipt, is IrcEvent.ChannelModeChanges,
            is IrcEvent.CtcpRequest, is IrcEvent.OpenQueryBuffer, is IrcEvent.BouncerCommandResult,
            is IrcEvent.SearchResults, is IrcEvent.MetadataSubscribed -> handleStateEvent(netId, ev)
        }
    }

    /** Buffers whose replay batch ended while its lines were still held. */
    internal val dividerAfterFlush: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Oldest message of the last page each buffer received, anchoring the next request. The spec
     * pages from the previous reply, not from the screen: a page that deduplicated to nothing
     * leaves the buffer unchanged, and anchoring on it would repeat the request forever.
     */
    internal val historyAnchors: MutableMap<String, UiMessage> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Oldest message each buffer saw arrive live this session, which is the far end of any
     * gap a catch-up has to fill. Dropped with the rest of a network's history state on
     * registration, since it describes the connection that ended.
     */
    internal val sessionFirstLive: MutableMap<String, UiMessage> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Fetch the page of messages immediately older than the top of [key]'s scrollback.
     *
     * Called when the user reaches the top of the chat view. With no message to anchor on,
     * the server is asked for its most recent page instead, so a channel that is quiet and
     * has no local logs can still be filled.
     */
    fun loadOlderHistory(key: String) {
        val st = _state.value
        val buf = st.buffers[key] ?: return
        if (buf.historyLoading || buf.historyExhausted) return
        if (chatHistory.isBackfilling(key)) return

        val (netId, bufferName) = splitKey(key)
        if (isPseudoBuffer(bufferName) || isDccChatBufferName(bufferName)) return
        val rt = runtimes[netId] ?: return
        if (!rt.client.supportsChatHistory()) return

        // Anchor on the oldest message of the previous page where there was one, otherwise
        // on the oldest the buffer holds. System lines are generated locally, so their
        // timestamps and absent msgids mean nothing to the server.
        //
        // A server that takes no timestamp selector can only be anchored on a message it
        // sent, so lines read back from the log files are no use. With none to anchor on the
        // request below falls through to LATEST, whose reply carries msgids to page from.
        val needsMsgId = !rt.client.acceptsHistoryTimestamps()
        val usable = { m: UiMessage -> m.from != null && (!needsMsgId || m.msgId != null) }
        val oldest = historyAnchors[key]?.takeIf(usable) ?: buf.messages.firstOrNull(usable)
        val anchorTs = oldest?.let {
            runCatching { ChatHistoryController.anchorTimestamp(it.timeMs) }.getOrNull()
        }

        // Before sending: the send suspends and a reply can arrive before it resumes.
        if (!chatHistory.begin(key, BackfillKind.OLDER)) return
        setHistoryFlags(key, loading = true, exhausted = false)

        viewModelScope.launch {
            val request = runCatching {
                if (oldest == null) {
                    rt.client.requestChatHistoryLatest(bufferName, ChatHistoryController.PAGE_SIZE)
                } else {
                    rt.client.requestChatHistoryBefore(
                        target = bufferName,
                        beforeTimestamp = anchorTs,
                        beforeMsgId = oldest.msgId,
                        limit = ChatHistoryController.PAGE_SIZE,
                    )
                }
            }.getOrNull()

            if (request == null) {
                // Nothing went out, so nothing comes back. Abandoned, not exhausted.
                chatHistory.finish(key, BackfillOutcome.ABANDONED)
                return@launch
            }
            chatHistory.attach(key, request.label, request.limit)
        }
    }

    /**
     * Fetch the messages either side of [msgId], or of the moment [timeMs], in [key]. The server
     * gets whichever of the two its selectors take.
     *
     * For a reply whose parent is older than the loaded window: the quote has nothing to
     * point at, so the surrounding stretch is fetched and merged into place. Registered as a
     * request in its own right so it does not disturb where paging had reached.
     */
    fun loadMessageContext(
        key: String,
        msgId: String?,
        limit: Int = ChatHistoryController.PAGE_SIZE,
        timeMs: Long? = null,
    ): Boolean {
        if (msgId == null && timeMs == null) return false
        val st = _state.value
        val buf = st.buffers[key] ?: return false
        if (buf.historyLoading || chatHistory.isBackfilling(key)) return false

        val (netId, bufferName) = splitKey(key)
        if (isPseudoBuffer(bufferName) || isDccChatBufferName(bufferName)) return false
        val rt = runtimes[netId] ?: return false
        if (!rt.client.supportsChatHistory()) return false

        if (!chatHistory.begin(key, BackfillKind.CONTEXT)) return false
        setHistoryFlags(key, loading = true, exhausted = buf.historyExhausted)

        viewModelScope.launch {
            val request = runCatching {
                rt.client.requestChatHistoryAround(
                    bufferName,
                    timestamp = timeMs?.let { ChatHistoryController.anchorTimestamp(it) },
                    msgId = msgId,
                    limit = limit,
                )
            }.getOrNull()
            if (request == null) {
                chatHistory.finish(key, BackfillOutcome.ABANDONED)
                return@launch
            }
            chatHistory.attach(key, request.label, request.limit)
        }
        return true
    }

    /** A reaction whose message is not loaded yet. */
    internal data class PendingReaction(
        val parentId: String?,
        val reaction: String,
        val fromNick: String,
        val adding: Boolean,
        val timeMs: Long?,
        val isHistory: Boolean,
    )

    internal enum class ReactionResult { ATTACHED, UNCHANGED, MISSING }

    /** Reactions waiting on a fetch of their message, per buffer. Main thread only. */
    internal val pendingReactions = HashMap<String, ArrayDeque<PendingReaction>>()

    /** The message id each buffer's reaction fetch asked for. Main thread only. */
    internal val reactionFetchFor = HashMap<String, String>()

    /**
     * True when [key]'s network negotiated chathistory, so a backfill request would actually
     * be answered. Read by the chat view to decide whether to offer the control at all.
     */
    fun supportsChatHistory(key: String?): Boolean {
        if (key == null) return false
        val (netId, bufferName) = splitKey(key)
        if (isPseudoBuffer(bufferName) || isDccChatBufferName(bufferName)) return false
        return runtimes[netId]?.client?.supportsChatHistory() == true
    }

    /** Where each buffer's catch-up has reached, for the next page and for resuming a gap. */
    internal val catchupCursor: MutableMap<String, UiMessage> = java.util.concurrent.ConcurrentHashMap()

    /** The first message id of each network's current connection. */
    internal val historySessionStartId: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    /** The first message id of each channel's current membership. */
    internal val joinStartId: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    /** When we last left each channel this session, by the server's clock where given. */
    internal val leftChannelAtMs: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    /** Channels whose join catch-up waits for their saved log, with the page size to use. */
    internal val pendingJoinCatchups: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()

    /** Log writes run here one at a time, so lines reach each file in the order they arrived. */
    internal val logLane = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)

    /** When each nick's away status was last looked up for its nick sheet, by network and nick. */
    internal val nickAwayLookups: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    /** Channels from an irc:// link to join once each network has registered. */
    internal val pendingUriJoins: MutableMap<String, List<AutoJoinChannel>> = java.util.concurrent.ConcurrentHashMap()

    /** Where each buffer's last catch-up request was anchored, to tell progress from a loop. */
    internal val catchupAnchorMs: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    /** The line marking each buffer's unfilled gap, so it can be taken out again. */
    internal val gapMarkers: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Unsent composer text per buffer. Delegated to [DraftStore] rather than held as
     * another map on this class.
     */
    internal val draftStore = com.boxlabs.hexdroid.data.DraftStore()

    /** The saved draft for [bufferKey], or an empty one. Read when the composer switches buffer. */
    fun draftFor(bufferKey: String): com.boxlabs.hexdroid.data.Draft = draftStore.get(bufferKey)

    /**
     * Save the composer's current text and caret offset for [bufferKey]. Blank text
     * discards the draft.
     */
    fun saveDraft(bufferKey: String, text: String, cursor: Int) =
        draftStore.put(bufferKey, text, cursor)

    /**
     * Put an inline reply that could not be sent into [buffer]'s message box, after anything
     * already typed there.
     */
    fun keepFailedReply(netId: String, buffer: String, text: String) {
        val key = resolveBufferKey(netId, buffer)
        val existing = draftStore.get(key).text
        val merged = if (existing.isBlank()) text else "$existing $text"
        draftStore.put(key, merged, merged.length)
        // The open conversation's composer reads its draft only when switched to.
        if (_state.value.selectedBuffer == key) {
            _state.update { it.copy(pendingShareText = merged, pendingShareTarget = key) }
        }
    }

    // Web Push (draft/webpush)

    /**
     * Choose [distributor] and subscribe through it, bound to a connected webpush-capable network's
     * VAPID key. With none connected, the subscription is made keyless and [maybeRegisterWebPush]
     * resubscribes once a key is available.
     */
    fun selectPushDistributor(distributor: String) {
        val vapid = runtimes.values
            .firstOrNull { it.client.hasWebPushCap() && it.client.webPushVapidKey() != null }
            ?.client?.webPushVapidKey()
        com.boxlabs.hexdroid.push.WebPushManager.selectDistributor(appContext, distributor, vapid)
    }

    /**
     * Apply a change to the Web Push setting. Turning it off unregisters from every connected
     * server first, since the endpoint can't be named once the local subscription is gone.
     */
    fun applyWebPushSetting(enabled: Boolean) {
        if (enabled) {
            for (netId in runtimes.keys.toList()) maybeRegisterWebPush(netId)
            return
        }

        val sub = com.boxlabs.hexdroid.push.WebPushManager.subscription(appContext)
        val targets = runtimes.entries.toList().filter { (_, rt) -> rt.client.hasWebPushCap() }
        for ((netId, _) in targets) webPushFailureNotice.remove(netId)

        // One coroutine rather than one per network plus a synchronous end, so the
        // UNREGISTER lines exist before the distributor drops the endpoint.
        viewModelScope.launch {
            if (sub != null) {
                for ((_, rt) in targets) {
                    runCatching { rt.client.webPushUnregister(sub.endpoint) }
                }
            }
            com.boxlabs.hexdroid.push.WebPushManager.unregister(appContext)
        }
    }

    /** The log entry of one of our own lines, held until the server answers it. */
    internal class HeldOwnLine(
        val bufferKey: String,
        val from: String?,
        val text: String,
        val isAction: Boolean,
        val localTs: Long,
    )

    /** The settings stored for [bufferKey], or the defaults. */
    fun channelPrefsFor(bufferKey: String): ChannelPrefs =
        _state.value.channelPrefs[ChannelPrefsCodec.keyFor(bufferKey)] ?: ChannelPrefs()

    /**
     * Saves [prefs] for [bufferKey]. Default values clear what was stored. A change of mute is
     * passed on to a soju bouncer that shares mutes.
     */
    fun setChannelPrefs(bufferKey: String, prefs: ChannelPrefs) {
        val before = channelPrefsFor(bufferKey)
        storeChannelPrefs(bufferKey, prefs.copy(syncedMute = before.syncedMute))
        if (prefs.muted != before.muted) shareSojuMute(bufferKey, prefs.muted)
    }

    /** [setChannelPrefs] without telling the bouncer. */
    internal fun storeChannelPrefs(bufferKey: String, prefs: ChannelPrefs) {
        val key = ChannelPrefsCodec.keyFor(bufferKey)
        val cleaned = prefs.copy(highlightWords = prefs.highlightWords.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        _state.update { st ->
            st.copy(channelPrefs = if (cleaned.isDefault) st.channelPrefs - key else st.channelPrefs + (key to cleaned))
        }
        viewModelScope.launch { runCatching { repo.setChannelPrefs(bufferKey, cleaned) } }
    }

    internal enum class ChannelEventKind { JOIN, PART, QUIT, NICK, TOPIC, KICK, MODE }

    fun acceptDcc(offer: DccOffer) {
        doAcceptDccCommon(offer, resumeFrom = null)
    }

    /**
     * Look up a recorded partial for this offer and, if one exists, resume the transfer from
     * that offset. Falls back to a fresh accept if no partial is found.
     *
     * The actual CTCP RESUME / ACCEPT exchange happens inside [doAcceptDccCommon] so the
     * UI doesn't have to know which DCC mode (active vs passive) the offer was in.
     */
    fun acceptDccResume(offer: DccOffer) {
        val partial = getPartialFor(offer)
        if (partial == null) {
            acceptDcc(offer)
            return
        }
        doAcceptDccCommon(offer, resumeFrom = partial)
    }

    /**
     * The partial recorded for [offer], if any, and currently usable.
     * Public so the UI can decide whether to render a "Resume" button.
     */
    fun getPartialFor(offer: DccOffer): PartialTransfer? {
        val baseName = offer.filename.substringAfterLast('/').substringAfterLast('\\')
        return dccPartials.get(offer.from, baseName, offer.size)
    }

    /**
     * Remove a completed or errored transfer entry from the list.
     * Active in-progress transfers are silently ignored.
     */
    fun clearDccTransfer(transfer: DccTransferState) {
        val canClear = when (transfer) {
            is DccTransferState.Incoming -> transfer.done || transfer.error != null
            is DccTransferState.Outgoing -> transfer.done || transfer.error != null
        }
        if (!canClear) return
        // Match by logical identity key rather than full structural equality:
        // the transfer state is updated frequently (progress ticks, endTimeMs stamp)
        // so a full == comparison against the rendered snapshot will fail once
        // any field has changed since the item was composed.
        _state.update { st ->
            st.copy(dccTransfers = st.dccTransfers.filterNot { t ->
                when {
                    t is DccTransferState.Incoming && transfer is DccTransferState.Incoming ->
                        t.offer == transfer.offer
                    t is DccTransferState.Outgoing && transfer is DccTransferState.Outgoing ->
                        t.target == transfer.target &&
                        t.filename == transfer.filename &&
                        t.startTimeMs == transfer.startTimeMs
                    else -> false
                }
            })
        }
    }

    fun rejectDcc(offer: DccOffer) {
        _state.value = _state.value.copy(dccOffers = _state.value.dccOffers.filterNot { it == offer })
        val netId = offer.netId.takeIf { it.isNotBlank() } ?: _state.value.activeNetworkId ?: return
        // Discard any partial we had for this offer: the user explicitly rejected, so we
        // shouldn't keep the bytes (or the resume option) around. The store does both:
        // unlinks the partial file and drops the registry entry.
        val baseName = offer.filename.substringAfterLast('/').substringAfterLast('\\')
        dccPartials.removeAndDeleteFile(offer.from, baseName, offer.size)
        append(bufKey(netId, "*server*"), from = null, text = "*** " + appContext.getString(R.string.vm_dcc_rejected, offer.filename), doNotify = false)
    }

    fun acceptDccChat(offer: DccChatOffer) {
        val st = _state.value
        val netId = offer.netId.takeIf { it.isNotBlank() } ?: st.activeNetworkId ?: return
        val peer = offer.from
        val key = findDccChatKey(netId, peer)

        if (!st.settings.dccEnabled) {
            append(key, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_disabled), isHighlight = true)
            return
        }

        _state.value = st.copy(dccChatOffers = st.dccChatOffers.filterNot { it == offer })
        openBuffer(key)

        // See acceptDcc: a private/LAN target can't be the real peer through a proxy tunnel,
        // and dialling it would make the proxy probe its own local network. Refuse.
        if (isProxiedNetwork(netId) && isLocalHost(offer.ip)) {
            append(key, from = null,
                text = "*** " + appContext.getString(R.string.vm_dcc_chat_refuse_private, peer, offer.ip),
                isHighlight = true)
            return
        }

        // Android 17+: connecting to a LAN peer requires ACCESS_LOCAL_NETWORK.
        if (isLanHost(offer.ip) && !hasLocalNetworkPermission()) {
            append(key, from = null,
                text = "*** " + appContext.getString(R.string.vm_dcc_chat_lan_perm, peer),
                isHighlight = true)
            return
        }

        launchDccChatConnect(key) {
            try {
                append(key, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_chat_connecting, offer.from, offer.ip, dccChatPortLabel(offer.port, offer.secure)), doNotify = false)
                val chatProxy = withContext(Dispatchers.IO) { proxyForNetwork(netId) }
                val socket = dcc.connectChat(offer, proxy = chatProxy)
                startDccChatSession(netId, peer, key, socket)
                appendDccTlsInfo(key, socket, server = false)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                if (isActive) {
                    append(key, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_chat_connect_failed, t.message ?: t::class.java.simpleName), isHighlight = true)
                }
            }
        }
    }

    fun rejectDccChat(offer: DccChatOffer) {
        _state.value = _state.value.copy(dccChatOffers = _state.value.dccChatOffers.filterNot { it == offer })
        val netId = offer.netId.takeIf { it.isNotBlank() } ?: _state.value.activeNetworkId ?: return
        append(bufKey(netId, "*server*"), from = null, text = "*** " + appContext.getString(R.string.vm_dcc_chat_rejected, offer.from), doNotify = false)
    }

    fun startDccChat(targetNick: String) = startDccChatFlow(targetNick)

    // ---- DCC send file picking -----------------------------------------------
    /** A DCC send waiting for the user to choose its file. */
    data class DccSendPick(
        val id: String,
        val netId: String,
        val nick: String,
        val networkName: String,
        val mimeFilter: String,
        /** Show a prompt naming the recipient before the picker opens. */
        val confirm: Boolean,
        /** Buffer for the send's status lines; null picks the query or server buffer. */
        val statusKey: String? = null,
        /** The picker has been opened for this request. */
        val launched: Boolean = false,
    )

    private val _dccSendPick = MutableStateFlow<DccSendPick?>(null)
    val dccSendPick: StateFlow<DccSendPick?> = _dccSendPick

    /**
     * Ask the UI for a file to offer [targetNick] on [netIdArg] (default: the active network).
     * [confirm] shows a prompt naming the recipient before the picker; [statusKey] is where the
     * send reports. Replaces any pending request.
     */
    fun requestDccSendPick(
        targetNick: String,
        netIdArg: String? = null,
        mimeFilter: String = "*/*",
        confirm: Boolean = false,
        statusKey: String? = null,
    ) {
        val st = _state.value
        val netId = netIdArg ?: st.activeNetworkId ?: return
        val nick = targetNick.trim().trimStart('~', '&', '@', '%', '+')
        if (nick.isBlank()) return
        if (!st.settings.dccEnabled) {
            append(bufKey(netId, "*server*"), from = "DCC", text = appContext.getString(R.string.vm_dcc_disabled), isHighlight = true)
            return
        }
        _dccSendPick.value = DccSendPick(
            id = java.util.UUID.randomUUID().toString(),
            netId = netId,
            nick = nick,
            networkName = st.networks.firstOrNull { it.id == netId }?.name ?: netId,
            mimeFilter = mimeFilter,
            confirm = confirm,
            statusKey = statusKey,
        )
    }

    /** The UI has opened the picker for request [id]. */
    fun dccSendPickLaunched(id: String) {
        _dccSendPick.update { p -> if (p?.id == id) p.copy(launched = true) else p }
    }

    /** The file chosen for the pending DCC send, or null when the user declined. */
    fun dccSendPickResult(uri: android.net.Uri?) {
        val pick = _dccSendPick.value ?: return
        _dccSendPick.value = null
        if (uri != null) sendDccFile(uri, pick.nick, pick.netId, pick.statusKey)
    }

    /** No picker could be opened for the pending DCC send. */
    fun dccSendPickUnavailable() {
        val pick = _dccSendPick.value ?: return
        _dccSendPick.value = null
        append(bufKey(pick.netId, "*server*"), from = "DCC", text = appContext.getString(R.string.no_file_picker), isHighlight = true)
    }

    /**
     * Cancel an in-progress outgoing DCC send.
     * [target] and [filename] must match the values in [DccTransferState.Outgoing].
     */
    fun cancelOutgoingDcc(target: String, filename: String) {
        val jobKey = "$target/$filename"
        outgoingSendJobs[jobKey]?.cancel()
        outgoingSendJobs.remove(jobKey)
        // We deliberately don't remove the transfer from dccTransfers here. The send
        // coroutine's catch block transitions it to error = "Cancelled" so the UI shows
        // the cancelled state; the user then taps the X (clearDccTransfer) to dismiss
        // it. Two buttons, two actions.
    }

    /**
     * Cancel an incoming DCC receive. Cancelling the job closes the socket, which ends the receive
     * loop; the transfer then shows "Cancelled" until dismissed.
     */
    fun cancelIncomingDcc(offer: DccOffer) {
        incomingReceiveJobs[offer]?.cancel()
    }
    internal data class PreparedDccSend(val file: File, val offerName: String)

    /**
     * Upload a picked document to the network's soju.im/FILEHOST endpoint; [onDone] gets (url,
     * error) on the main thread, exactly one non-null. Credentials are the connection's SASL PLAIN
     * identity and password, or the server password for PASS-authenticated bouncers; SCRAM and
     * EXTERNAL passwords are never sent. Refused on proxied networks, since the upload would bypass
     * the proxy.
     */
    fun uploadFileToFilehost(netId: String, uri: android.net.Uri, onDone: (url: String?, error: String?) -> Unit) {
        uploadToFilehost(
            netId = netId,
            describe = {
                val name = queryDisplayName(uri)
                    ?: runCatching { java.net.URLDecoder.decode(uri.lastPathSegment ?: "", "UTF-8") }.getOrNull()
                val size = try {
                    appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.SIZE)
                            if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
                        } else -1L
                    } ?: -1L
                } catch (_: Throwable) { -1L }
                val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
                Triple(name, mime, size)
            },
            open = { appContext.contentResolver.openInputStream(uri) },
            onDone = onDone,
        )
    }

    /** Upload [bytes] as a file named [fileName] to the network's FILEHOST endpoint. See [uploadFileToFilehost]. */
    fun uploadBytesToFilehost(
        netId: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        onDone: (url: String?, error: String?) -> Unit,
    ) {
        uploadToFilehost(
            netId = netId,
            describe = { Triple(fileName, mimeType, bytes.size.toLong()) },
            open = { java.io.ByteArrayInputStream(bytes) },
            onDone = onDone,
        )
    }

    /**
     * The FILEHOST upload behind [uploadFileToFilehost] and [uploadBytesToFilehost]. [describe]
     * gives the name, MIME type and size (or -1), and [open] the content; both run off the main thread.
     */
    private fun uploadToFilehost(
        netId: String,
        describe: () -> Triple<String?, String?, Long>,
        open: () -> java.io.InputStream?,
        onDone: (url: String?, error: String?) -> Unit,
    ) {
        val cfg = runtimes[netId]?.client?.config
        val uploadUrl = _state.value.connections[netId]?.filehostUrl
        if (cfg == null || uploadUrl.isNullOrBlank()) {
            onDone(null, appContext.getString(R.string.vm_upload_unsupported))
            return
        }
        if (!cfg.capPrefs.filehostUploads) {
            onDone(null, appContext.getString(R.string.vm_upload_disabled))
            return
        }
        if (cfg.proxy.enabled) {
            onDone(null, appContext.getString(R.string.vm_upload_no_proxy))
            return
        }
        val saslCfg = cfg.sasl as? SaslConfig.Enabled
        val baseUser = saslCfg?.authcid?.takeIf { it.isNotBlank() } ?: cfg.nick
        val username = cfg.effectiveAuthIdentity(baseUser)
        val secret = when {
            saslCfg == null -> cfg.serverPassword
            saslCfg.mechanism == SaslMechanism.PLAIN -> saslCfg.password ?: cfg.serverPassword
            else -> cfg.serverPassword
        }
        val uploadHost = runCatching { java.net.URI(uploadUrl.trim()).host }.getOrNull().orEmpty()
        val sameSite = FilehostUpload.sameSite(uploadHost, cfg.host)
        val password = secret?.takeIf { sameSite }
        val withheldReason = when {
            secret != null && !sameSite -> "the upload host $uploadHost is not part of ${cfg.host}"
            secret == null && saslCfg != null && saslCfg.mechanism != SaslMechanism.PLAIN ->
                "logins using ${saslCfg.mechanism.name.replace('_', '-')} do not share their password"
            else -> null
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                val (name, mime, size) = describe()

                fun send(declaredLength: Long): FilehostUpload.Result {
                    val stream = open()
                        ?: return FilehostUpload.Result(null, appContext.getString(R.string.vm_file_open_failed))
                    return stream.use { inp ->
                        FilehostUpload.upload(
                            uploadUrl = uploadUrl,
                            username = username,
                            password = password,
                            fileName = name,
                            mimeType = mime,
                            contentLength = declaredLength,
                            input = inp,
                            connectionUsesTls = cfg.useTls,
                            withheldReason = withheldReason,
                        )
                    }
                }

                val first = send(size)
                // A provider's reported SIZE can disagree with what the stream actually
                // yields: cloud-backed documents report a stale or approximate size, and a
                // file edited between the query and the read is a different length. The
                // fixed-length request then refuses the body mid-write. Resend without a
                // declared length rather than making the user pick the file again.
                if (first.ok || !FilehostUpload.isLengthMismatch(first.error)) first
                else send(-1L)
            } catch (t: Throwable) {
                FilehostUpload.Result(null, appContext.getString(R.string.vm_upload_failed, t.message ?: t.javaClass.simpleName))
            }
            withContext(Dispatchers.Main) { onDone(result.url, result.error) }
        }
    }

    /**
     * Ask the server to delete one of our own messages (draft/message-redaction).
     * The buffer is updated when the server relays the REDACT back to us.
     */
    fun redactMessage(netId: String, target: String, msgId: String) {
        viewModelScope.launch {
            runCatching { runtimes[netId]?.client?.sendRedact(target, msgId) }
        }
    }

    /** Set one of our own draft/metadata-2 keys (null or blank removes it). */
    fun setOwnMetadata(netId: String, key: String, value: String?) {
        viewModelScope.launch {
            runCatching { runtimes[netId]?.client?.setOwnMetadata(key, value) }
            // Persist so the value can be re-applied on reconnect. Under the lock: the whole
            // store is written as one blob, so two saves racing would each persist a snapshot
            // taken before the other's change.
            runCatching {
                ensureOwnMetadataLoaded()
                ownMetadataLock.withLock {
                    val map = ownMetadataStore.getOrPut(netId) { java.util.concurrent.ConcurrentHashMap() }
                    val v = value?.trim()
                    if (v.isNullOrEmpty()) map.remove(key) else map[key] = v
                    repo.writeOwnMetadata(ownMetadataStore.mapValues { it.value.toMap() })
                }
            }
        }
    }

    /**
     * Ask the server to list our own metadata (METADATA * LIST). Replies arrive as
     * 761 RPL_KEYVALUE and flow into NetConnState.ownMetadata, pre-filling the editor.
     */
    fun requestOwnMetadata(netId: String) {
        viewModelScope.launch {
            runCatching { runtimes[netId]?.client?.sendMetadata("*", "LIST") }
        }
    }

    /** True when the connected server keeps metadata for users (soju keeps only its own per-target keys). */
    fun serverSupportsMetadata(netId: String): Boolean =
        runtimes[netId]?.client?.let { it.hasCap("draft/metadata-2") && !it.isSoju } == true

    /** True when the connected server negotiated draft/account-registration. */
    fun serverSupportsAccountReg(netId: String): Boolean =
        runtimes[netId]?.client?.hasCap("draft/account-registration") == true

    /**
     * The comma-separated cap-value flags of draft/account-registration, lowercased
     * (e.g. "email-required", "custom-account-name", "before-connect"). Empty when the
     * cap has no value or is absent.
     */
    fun accountRegFlags(netId: String): List<String> =
        (runtimes[netId]?.client?.capValue("draft/account-registration") ?: "")
            .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /** Reset the registration dialog's state (on open, and after it closes). */
    fun clearRegState(netId: String) {
        setNetConn(netId) { it.copy(regState = RegState()) }
    }

    /** draft/account-registration: send a REGISTER for the guided dialog. */
    fun registerAccount(netId: String, account: String, email: String, password: String) {
        viewModelScope.launch {
            runCatching { runtimes[netId]?.client?.sendRegister(account, email, password) }
        }
    }

    /** draft/account-registration: send a VERIFY for the guided dialog. */
    fun verifyAccount(netId: String, account: String, code: String) {
        viewModelScope.launch {
            runCatching { runtimes[netId]?.client?.sendVerify(account, code) }
        }
    }

    /**
     * After a successful REGISTER, optionally persist the password so SASL logs the
     * user in automatically next connect. Writes the encrypted SecretStore entry and
     * flips the profile to SASL PLAIN with [account] as the authcid. Opt-in only,
     * driven by an explicit checkbox in the dialog.
     */
    fun saveSaslCredentialsAfterRegister(netId: String, account: String, password: String) {
        viewModelScope.launch {
            runCatching {
                repo.secretStore.setSaslPassword(netId, password)
                repo.updateNetworkProfile(netId) { p ->
                    p.copy(
                        saslEnabled = true,
                        saslMechanism = SaslMechanism.PLAIN,
                        saslAuthcid = account.takeIf { it.isNotBlank() } ?: p.saslAuthcid,
                    )
                }
            }
        }
    }

    /** True when an unexpired IRCv3 STS policy is stored for [host]. */
    fun hasActiveStsPolicy(host: String): Boolean {
        val key = host.trim().lowercase()
        if (key.isBlank()) return false
        return stsPolicies[key]?.isActive(System.currentTimeMillis()) == true
    }

    /**
     * User-requested escape hatch: forget the stored STS policy for [host], e.g.
     * when a server published a policy and then moved or broke its TLS listener
     * before the policy expired. The next connection follows the profile settings
     * again (and will simply re-learn the policy if the server still advertises it).
     */
    fun clearStsPolicy(host: String) {
        val key = host.trim().lowercase()
        if (stsPolicies.remove(key) != null) {
            persistStsPolicies()
        }
    }

    // Sharing

    fun shareFile(path: String) {
        val uri: android.net.Uri = if (path.startsWith("content://")) {
            // MediaStore or SAF path - already a content URI, use directly.
            android.net.Uri.parse(path)
        } else {
            // Filesystem path, wrap with FileProvider so other apps can read it.
            val f = File(path)
            if (!f.exists()) return
            // getUriForFile throws IllegalArgumentException when the path isn't under one of the
            // FileProvider's configured roots. Treat that as "can't share" instead of crashing.
            runCatching {
                FileProvider.getUriForFile(appContext, appContext.packageName + ".fileprovider", f)
           }.getOrElse {
                toastShareError()
                return
            }
        }
        val mime = appContext.contentResolver.getType(uri) ?: "*/*"
        // Try, in order: open directly (ACTION_VIEW), then a share chooser (ACTION_SEND) each
        // first WITH a read-permission grant, then WITHOUT it.
        //
        // The grant is needed for our own FileProvider URIs. But for SAF / MediaStore content://
        // URIs we don't own (DCC downloads on Android 10+ are saved via MediaStore and stored as
        // content:// URIs), asking the system to grant access on our behalf makes
        // UriGrantsManagerService.checkGrantUriPermission throw SecurityException at startActivity.
        val clip = android.content.ClipData.newRawUri("", uri)

        fun tryStart(action: String, withGrant: Boolean, asChooser: Boolean): Boolean {
            val base = Intent(action).apply {
                if (action == Intent.ACTION_VIEW) {
                    setDataAndType(uri, mime)
                } else {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                }
                if (withGrant) {
                    // Setting ClipData makes the grant reliably cover the data/stream URI across
                    // OEM implementations, not just the bare data field.
                    clipData = clip
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            val toLaunch = (if (asChooser) Intent.createChooser(base, appContext.getString(R.string.vm_open_with)) else base)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                appContext.startActivity(toLaunch)
                true
            } catch (_: android.content.ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                // Can't grant this URI (SAF/MediaStore URI we don't own) fall through to retry.
                false
            } catch (_: RuntimeException) {
                false
            }
        }

        if (tryStart(Intent.ACTION_VIEW, withGrant = true, asChooser = false)) return
            if (tryStart(Intent.ACTION_SEND, withGrant = true, asChooser = true)) return
                // Grant-less retries: for content:// URIs the target can resolve on its own.
                if (tryStart(Intent.ACTION_VIEW, withGrant = false, asChooser = false)) return
                    if (tryStart(Intent.ACTION_SEND, withGrant = false, asChooser = true)) return
                        toastShareError()
    }

	internal var cachedGpu: String? = null

    /** A recent error, for debug reports. */
    internal class DebugError(val timeMs: Long, val netId: String, val text: String)

    /**
     * A plain-text report for bug reports: app and device, each network's state and negotiated
     * capabilities, recent errors, and with [rawLines] above zero that many recent raw lines per
     * connected network. Passwords are hidden in the raw lines.
     */
    fun buildDebugInfo(rawLines: Int): String {
        val st = _state.value
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
        fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)
        val out = StringBuilder()
        out.appendLine("HexDroid debug info")
        out.appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        out.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        out.appendLine("Generated: ${Instant.now()}")
        val s = st.settings
        out.appendLine(
            "Settings: keepAlive=${s.keepAliveInBackground} webPush=${s.webPushEnabled} " +
                "logging=${s.loggingEnabled} rawLog=${s.rawLog} smartFilter=${s.smartFilter}"
        )
        for (net in st.networks) {
            val conn = st.connections[net.id]
            val rt = runtimes[net.id]
            out.appendLine()
            out.appendLine("Network: ${net.name} (${net.host}:${net.port}${if (net.useTls) ", TLS" else ", plaintext"}${if (net.bouncerKind != BouncerKind.NONE) ", ${net.bouncerKind}" else ""})")
            val stateText = when {
                conn?.connected == true -> "connected"
                conn?.connecting == true -> "connecting"
                else -> "not connected"
            }
            out.appendLine("  State: $stateText${conn?.status?.let { ", \"$it\"" } ?: ""}${conn?.lagMs?.let { ", lag ${it} ms" } ?: ""}")
            if (rt != null) {
                val caps = runCatching { rt.client.enabledCaps() }.getOrDefault(emptyList())
                out.appendLine("  Caps: ${if (caps.isEmpty()) "(none)" else caps.joinToString(" ")}")
                val sup = rt.support
                out.appendLine("  ISUPPORT: CASEMAPPING=${sup.caseMapping} CHANTYPES=${sup.chantypes} PREFIX=(${sup.prefixModes})${sup.prefixSymbols}${sup.linelen?.let { " LINELEN=$it" } ?: ""}")
                out.appendLine("  Pending send: ${rt.client.sendBacklogMs()} ms")
            }
            out.appendLine("  Buffers: ${st.buffers.keys.count { it.startsWith("${net.id}::") }}")
        }
        val errors = synchronized(recentErrors) { recentErrors.toList() }
        out.appendLine()
        out.appendLine("Recent errors:")
        if (errors.isEmpty()) out.appendLine("  (none)")
        for (e in errors) {
            val name = st.networks.firstOrNull { it.id == e.netId }?.name ?: e.netId
            out.appendLine("  ${clock(e.timeMs)} $name: ${e.text}")
        }
        if (rawLines > 0) {
            for (net in st.networks) {
                val rt = runtimes[net.id] ?: continue
                val lines = runCatching { rt.client.recentRawLines(rawLines) }.getOrDefault(emptyList())
                if (lines.isEmpty()) continue
                out.appendLine()
                out.appendLine("Raw lines, ${net.name} (last ${lines.size}, passwords hidden):")
                for ((t, outgoing, line) in lines) {
                    out.appendLine("  ${clock(t)} ${if (outgoing) ">>" else "<<"} $line")
                }
            }
        }
        return out.toString()
    }

    override fun onCleared() {
        super.onCleared()
        flushHeldOwnLines()
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        validatedNetworks.clear()
        // Withdraw the swipe hook, but only while it is still ours: a replacement ViewModel
        // installs its own, and clearing that one would leave a swipe with no clean QUIT.
        // A stale hook holds this instance alive and runs teardown against its dead runtimes.
        if (KeepAliveService.gracefulQuitOnSwipe === gracefulQuitHook) {
            KeepAliveService.gracefulQuitOnSwipe = null
        }
        // Stops the script engine's worker threads. Skipped when nothing ever touched
        // scripts, since reading the property would build the engine only to tear it down.
        if (scriptEngineDelegate.isInitialized()) {
            runCatching { scriptEngine.shutdown() }
        }
        // Flush and close all open log file handles so the last few lines written via the
        // BufferedWriter cache are not lost when the ViewModel is destroyed.
        logs.closeAll()
    }
}
