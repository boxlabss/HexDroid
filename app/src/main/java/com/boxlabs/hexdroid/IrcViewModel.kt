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
import androidx.core.app.NotificationManagerCompat
import com.boxlabs.hexdroid.BuildConfig
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxlabs.hexdroid.connection.ConnectionConstants
import com.boxlabs.hexdroid.data.AutoJoinChannel
import com.boxlabs.hexdroid.data.ChannelListEntry
import com.boxlabs.hexdroid.data.NetworkProfile
import com.boxlabs.hexdroid.data.SettingsRepository
import com.boxlabs.hexdroid.data.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

enum class AppScreen { CHAT, LIST, SETTINGS, NETWORKS, NETWORK_EDIT, TRANSFERS, ABOUT, IGNORE }

/**
 * UI-level message model.
 *
 * NOTE: [id] must be unique within a buffer list. Using timestamps alone can collide when multiple
 * lines arrive within the same millisecond (common during connect/MOTD), which can crash Compose
 * LazyColumn when keys are duplicated.
 */
data class UiMessage(
    val id: Long,
    val timeMs: Long,
    val from: String?,
    val text: String,
    val isAction: Boolean = false,
    /** True for MOTD body lines (372) so the UI can auto-size them to fit in one line. */
    val isMotd: Boolean = false,
    /**
     * IRCv3 message-id (msgid tag). When non-null, used to deduplicate messages that arrive
     * both via echo-message and chathistory replay, or after a bouncer reconnect.
     */
    val msgId: String? = null,
    /**
     * IRCv3 +reply / +draft/reply tag: the msgid of the message this is a reply to.
     * When non-null, the UI shows a small quoted preview of the parent message above
     * this one.
     */
    val replyToMsgId: String? = null,
    /**
     * End-to-end encryption scheme used on the wire for this message, or null for
     * cleartext. The UI renders a per-scheme padlock indicator when set so the user
     * can verify at a glance that the message was actually encrypted (rather than
     * sent in clear and merely *intended* to be encrypted).
     */
    val encryption: com.boxlabs.hexdroid.crypto.E2eScheme? = null,
)
data class UiBuffer(
    val name: String,
    val messages: List<UiMessage> = emptyList(),
    val unread: Int = 0,
    val highlights: Int = 0,
    val topic: String? = null,
    /** Channel mode string from 324 RPL_CHANNELMODEIS, e.g. "+nst" */
    val modeString: String? = null,
    /**
     * ISO 8601 timestamp of the last message the user has read, as confirmed by the server
     * via MARKREAD (draft/read-marker). Used to draw an unread separator in the chat view.
     * Null when the server hasn't sent a read marker for this buffer.
     */
    val lastReadTimestamp: String? = null,
    /**
     * Set of nicks currently showing a typing indicator (draft/typing CAP).
     * Cleared when the typing nick sends a message or emits "done" typing state.
     */
    val typingNicks: Set<String> = emptySet(),
    /**
     * O(1) msgId deduplication index.
     *
     * The previous implementation called `buf.messages.any { it.msgId == msgId }` on every
     * incoming message, an O(n) linear scan that could visit up to 5,000 entries at max
     * scrollback on a busy channel replaying history.
     *
     * This set mirrors the msgIds present in [messages] and is kept in sync with the
     * scrollback trim in [append]: when [messages] is trimmed via `takeLast(maxLines)`, the
     * set is rebuilt from the retained messages so evicted entries don't accumulate forever.
     *
     * Not part of equals/hashCode (it is derived from [messages]) and excluded from Compose
     * stability checks - it is an internal performance cache, not observable UI state.
     */
    val seenMsgIds: Set<String> = emptySet(),

    /**
     * Content-fingerprint dedup for messages whose msgid path can't dedupe.
     *
     * [seenMsgIds] handles the modern path (IRCv3 message-tags `msgid=…`) but several real-world
     * cases break it: ZNC's `*playback` module replays buffered messages with `time=` tags but
     * no `msgid`; modern bouncers replay the same message via two paths (e.g. their automatic
     * buffer dump on connect plus a subsequent CHATHISTORY LATEST) which can carry different
     * msgids; and our own isHistory heuristic doesn't always classify the first delivery of a
     * replayed line as history. Without a content-level dedup the user sees the same message
     * twice with the same timestamp.
     *
     * Each entry is `"${timeMs}|${from}|${text.hashCode()}"`. Computed for every message with a
     * sender — live messages too, because the millisecond `ts` resolution makes false collisions
     * effectively impossible on real human-typed traffic, and this way replays dedupe against
     * the original delivery regardless of which path delivered it.
     *
     * Sized identically to [seenMsgIds] and rebuilt the same way on scrollback trim.
     */
    val seenHistoryFingerprints: Set<String> = emptySet()
)

enum class FontChoice { OPEN_SANS, INTER, MONOSPACE, CUSTOM }

/** Default style applied to chat text (buffer + input). IRC formatting codes can still override this per-span. */
enum class ChatFontStyle { REGULAR, BOLD, ITALIC, BOLD_ITALIC }

enum class VibrateIntensity { LOW, MEDIUM, HIGH }


/** How we initiate DCC SEND connections. */
enum class DccSendMode { AUTO, ACTIVE, PASSIVE }

data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val compactMode: Boolean = false,
    val showTimestamps: Boolean = true,
    val timestampFormat: String = "HH:mm:ss",
    val fontScale: Float = 1.0f,
    val fontChoice: FontChoice = FontChoice.OPEN_SANS,
    val chatFontChoice: FontChoice = FontChoice.MONOSPACE,
    val customFontPath: String? = null,
    val customChatFontPath: String? = null,

    val chatFontStyle: ChatFontStyle = ChatFontStyle.REGULAR,
    val showTopicBar: Boolean = true,
    val hideMotdOnConnect: Boolean = false,
    val hideJoinPartQuit: Boolean = false,
    /**
     * Render channel-event lines (joins / parts / quits / kicks / nick changes / mode
     * changes) with mIRC colour codes so they pop out against regular conversation:
     * green for joins, orange for parts/quits, red for kicks, etc. Only affects display;
     * the underlying log files store the raw codes (which most log-readers strip), and
     * text copy preserves them too. Toggleable so users on monochrome themes or those
     * who prefer plain output can opt out without disabling joins entirely.
     */
    val colorChannelEvents: Boolean = true,
    /**
     * Suppress "* user is away" / "* user is back" lines emitted by the away-notify
     * IRCv3 capability. Bouncers (ZNC, soju) typically forward away-notify downstream
     * regardless of which caps the client itself negotiates, and away/back scripts in
     * the user's other clients can produce a constant trickle of these on busy networks.
     * The away/back state tracking continues even when this is on — only the inline
     * announcement is suppressed; the nicklist still reflects each nick's away status
     * (typically by dimming the entry).
     */
    val hideAwayNotify: Boolean = false,
    val hideTopicOnEntry: Boolean = false,
    val defaultShowNickList: Boolean = true,
    val defaultShowBufferList: Boolean = true,

    // Landscape split-pane fractions, updated by draggable handles.
    val bufferPaneFracLandscape: Float = 0.22f,
    val nickPaneFracLandscape: Float = 0.18f,

    val highlightOnNick: Boolean = true,
    val extraHighlightWords: List<String> = emptyList(),

    val notificationsEnabled: Boolean = true,
    val notifyOnHighlights: Boolean = true,
    val notifyOnPrivateMessages: Boolean = true,
    val showConnectionStatusNotification: Boolean = true,
    val keepAliveInBackground: Boolean = true,
    val autoReconnectEnabled: Boolean = true,
    val autoReconnectDelaySec: Int = 10,
    val autoConnectOnStartup: Boolean = false,
    /**
     * When true, automatically rejoin a channel if the user is kicked from it. Off by
     * default because auto-rejoin can be perceived as rude on some servers (operators
     * may interpret it as ignoring the kick), and can cause a loop if the channel has
     * a set mode like +i that the user can't satisfy. The one-attempt behaviour avoids
     * the loop case: a second kick within [AUTO_REJOIN_SUPPRESS_MS] is NOT rejoined.
     */
    val rejoinOnKick: Boolean = false,
    val playSoundOnHighlight: Boolean = false,
    val vibrateOnHighlight: Boolean = false,
    val vibrateIntensity: VibrateIntensity = VibrateIntensity.MEDIUM,

    val loggingEnabled: Boolean = false,
    val logServerBuffer: Boolean = false,
    val retentionDays: Int = 14,
    val logFolderUri: String? = null,
    val maxScrollbackLines: Int = 800,

    val ircHistoryLimit: Int = 50,
    val ircHistoryCountsAsUnread: Boolean = false,
    val ircHistoryTriggersNotifications: Boolean = false,

    val dccEnabled: Boolean = false,
    val dccSendMode: DccSendMode = DccSendMode.AUTO,
    val dccSecure: Boolean = false,      // SDCC: wrap transfers in TLS
    val dccIncomingPortMin: Int = 5000,
    val dccIncomingPortMax: Int = 5010,
    val dccDownloadFolderUri: String? = null,

    val quitMessage: String = "HexDroid IRC - https://hexdroid.boxlabs.uk",
    val partMessage: String = "Leaving",

    val colorizeNicks: Boolean = true,
    /**
     * Custom colour for your own nick, stored as ARGB int (e.g. 0xFF_FF6600.toInt()).
     * Null means "Auto" - let [NickColors.colorForNick] pick a colour from the hash,
     * the same as any other nick.
     */
    val ownNickColorInt: Int? = null,

    val introTourSeenVersion: Int = 0,
    val mircColorsEnabled: Boolean = true,
    val ansiColorsEnabled: Boolean = true,

    val welcomeCompleted: Boolean = false,
    val appLanguage: String? = null,
    val portraitNicklistOverlay: Boolean = true,
    val portraitNickPaneFrac: Float = 0.35f,

    /** Broadcast typing status to others (draft/typing CAP). Off by default for privacy. */
    val sendTypingIndicator: Boolean = false,
    /** Show typing indicators from others. Independent of sendTypingIndicator. */
    val receiveTypingIndicator: Boolean = true,

    /** Show inline image and YouTube thumbnail previews in chat. */
    val imagePreviewsEnabled: Boolean = false,
    /** When true, only load previews on Wi-Fi to save mobile data. */
    val imagePreviewsWifiOnly: Boolean = true,
)

data class NetConnState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val status: String = "Disconnected",
    val myNick: String = "me",
    val lagMs: Long? = null,
    /**
     * Server-advertised *list* channel modes (from ISUPPORT CHANMODES group 1).
     * Common: b,e,I,q. Defaults to a permissive set until ISUPPORT arrives.
     */
    val listModes: String = "bqeI",
    /** True after 381 RPL_YOUREOPER is received for this connection */
    val isIrcOper: Boolean = false,
    /** True when the message-tags or draft/message-reactions cap is negotiated.
     *  Used by ChatScreen to decide whether to offer emoji reactions. */
    val hasReactionSupport: Boolean = false,
    /**
     * True from the moment a [IrcEvent.TlsFingerprintChanged] fires until the next successful
     * connection (or until the pin is cleared by the user). Drives the "Reset & re-pin" button
     * in NetworkEditScreen
     */
    val tlsPinMismatch: Boolean = false,
    /**
     * The actual fingerprint the server presented at the most recent mismatch, stashed so
     * the edit screen can offer "Trust this server too" without re-deriving it. Cleared on
     * next successful connect alongside [tlsPinMismatch]. Null when no mismatch is active.
     */
    val tlsPinMismatchActualFp: String? = null,
)

data class BanEntry(
    val mask: String,
    val setBy: String? = null,
    val setAtMs: Long? = null
)

/**
 * Snapshot of one upstream network reported by a soju bouncer via the
 * `soju.im/bouncer-networks` extension. Distinct from [NetworkProfile] - this is what the
 * bouncer *says* exists, not what HexDroid is configured to connect to. The difference is
 * what lets the UI offer "bouncer has a network you haven't added" hints.
 *
 * [id] is the stable per-user netid assigned by the bouncer. The spec guarantees it does
 * not change during the lifetime of the network, so it's safe to use as a map key.
 *
 * [state] mirrors the spec values: "connected" | "connecting" | "disconnected". Null until
 * the bouncer has told us, when an update message omits the attribute (spec rule: a missing
 * attribute means "preserve the previous value"; we honour that via the merge logic in the
 * BouncerNetwork handler), or when the bouncer explicitly cleared it via `state=` with an
 * empty value (signalled in BouncerNetwork.clearedKeys).
 */
data class BouncerUpstreamInfo(
    val id: String,
    val name: String? = null,
    val host: String? = null,
    val state: String? = null,
    /** Epoch-ms of the most recent BOUNCER NETWORK update for this upstream. */
    val lastSeenMs: Long = 0L,
)

/**
 * State for the /find search overlay in ChatScreen.
 * [query] is the search term, [matchIds] are UiMessage.id values of all matches
 * in chronological order, [currentIndex] is which one is focused (0 = oldest).
 * [bufferKey] ties the overlay to the buffer where /find was invoked.
 */
data class FindOverlay(
    val query: String,
    val matchIds: List<Long>,
    val currentIndex: Int = matchIds.lastIndex.coerceAtLeast(0),
    val bufferKey: String,
)

data class UiState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val status: String = "Disconnected",
    val myNick: String = "me",

    val screen: AppScreen = AppScreen.NETWORKS,

    /**
     * When the user taps a highlight/PM notification, the internal [UiMessage.id] of the
     * triggering message is stored here so [ChatScreen] can scroll to and flash it.
     * Cleared by [clearHighlightScroll] once the animation has been consumed.
     */
    /** Stable anchor for scrolling to a notified message. Set by handleIntent() when the user
     *  taps a highlight/PM notification. Format: "msgid:<ircId>" or "ts:<sec>|<nick>|<text>". */
    val pendingHighlightAnchor: String? = null,
    /** Text shared from another app via ACTION_SEND. ChatScreen pre-fills the input with this
     *  and clears it once consumed. */
    val pendingShareText: String? = null,
    /** Epoch-ms when pendingHighlightAnchor was last set; used to time-out the scroll attempt. */
    val pendingHighlightSetAtMs: Long = 0L,
    /** The buffer key that pendingHighlightAnchor belongs to. */
    val pendingHighlightBufferKey: String? = null,

    /** Non-null while the /find overlay is open. */
    val findOverlay: FindOverlay? = null,

    val connections: Map<String, NetConnState> = emptyMap(),
    val buffers: Map<String, UiBuffer> = emptyMap(),
    val selectedBuffer: String = "",
    val nicklists: Map<String, List<String>> = emptyMap(),

    // Channel metadata
    val banlists: Map<String, List<BanEntry>> = emptyMap(),
    val banlistLoading: Map<String, Boolean> = emptyMap(),

    // Channel mode lists (common across ircu/unrealircd/nefarious/inspircd)
    val quietlists: Map<String, List<BanEntry>> = emptyMap(),
    val quietlistLoading: Map<String, Boolean> = emptyMap(),
    val exceptlists: Map<String, List<BanEntry>> = emptyMap(),
    val exceptlistLoading: Map<String, Boolean> = emptyMap(),
    val invexlists: Map<String, List<BanEntry>> = emptyMap(),
    val invexlistLoading: Map<String, Boolean> = emptyMap(),

    val showBufferList: Boolean = true,
    val showNickList: Boolean = false,
    val channelsOnly: Boolean = false,

    // /LIST UI (active network only)
    val listInProgress: Boolean = false,
    val channelDirectory: List<ChannelListEntry> = emptyList(),
    val listFilter: String = "",
    /** Sort order for the channel list: "size_desc", "size_asc", "name_asc", "name_desc". */
    val listSort: String = "size_desc",
    /**
     * True when the active network advertises ELIST=...U, i.e. it can filter LIST by user
     * count server-side ("LIST >N"). When true the channel-list min/max user fields are sent
     * to the server on refresh (smaller, faster result that avoids the slow-reader cutoff on
     * huge networks); when false they only filter the already-received list client-side.
     */
    val listElistUserFilter: Boolean = false,

    val collapsedNetworkIds: Set<String> = emptySet(),
    val settings: UiSettings = UiSettings(),
    // Prevents a one-frame default-value flicker before DataStore loads.
    val settingsLoaded: Boolean = false,
    val networks: List<NetworkProfile> = emptyList(),
    val activeNetworkId: String? = null,
    val editingNetwork: NetworkProfile? = null,

    val networkEditError: String? = null,

    /**
     * Per-connection map of upstream bouncer networks reported by `soju.im/bouncer-networks`.
     * Key = our local network profile id (the id used in [connections] and [runtimes]); inner
     * key = the bouncer's upstream netid (stable per user). This is "what soju says exists",
     * not "what HexDroid is configured to connect to". Distinguishing the two is what lets
     * the UI show "your bouncer has a new network you haven't added yet" hints.
     *
     * Kept in sync with `BOUNCER NETWORK` push notifications (see the BouncerNetwork handler).
     * Cleared when the connection drops so stale upstream state doesn't leak across reconnects.
     */
    val bouncerNetworks: Map<String, Map<String, BouncerUpstreamInfo>> = emptyMap(),

    val plaintextWarningNetworkId: String? = null,
    /** Non-null when a connect attempt was blocked because ACCESS_LOCAL_NETWORK is not granted (API 37+). */
    val localNetworkWarningNetworkId: String? = null,

    val dccOffers: List<DccOffer> = emptyList(),
    val dccChatOffers: List<DccChatOffer> = emptyList(),
    val dccTransfers: List<DccTransferState> = emptyList(),

    val backupMessage: String? = null,

    /**
     * Transient toast/feedback after a bouncer-network discover-and-clone import attempt.
     * Set by [IrcViewModel.cloneBouncerNetwork] / [IrcViewModel.refreshBouncerNetworks];
     * cleared by the screen via [IrcViewModel.clearBouncerCloneMessage] once shown.
     */
    val bouncerCloneMessage: String? = null,

    /**
     * True when the currently-saved `settings.logFolderUri` is in [LogWriter]'s
     * unreadable-URIs set - i.e. a read or write against it threw SecurityException
     * earlier in the session and we're now silently skipping log I/O for it. Surfaced
     * to the Settings screen as a warning badge with a re-pick CTA. The most common
     * trigger is a backup restore on a fresh install: the saved URI string is in
     * settings but the matching SAF permission grant didn't survive the reinstall
     * (those grants are stored per-install in the system, not in app data, and so are
     * not part of any backup or D2D transfer payload).
     */
    val logFolderUnreadable: Boolean = false,

    /**
     * Monotonic counter incremented every time an E2E key is added/changed/removed.
     * The chat screen reads it (alongside selectedBuffer + activeNetworkId) to
     * derive the current buffer's encryption state for the compose-input lock
     * badge. We track a counter rather than putting the full key info into state
     * because the keys themselves never belong in observable state (they're
     * sensitive, and including them in UiState would have them flow through
     * every recomposition snapshot, logcat dump, and any future state-debug
     * tooling). The counter lets compose re-derive the answer on demand without
     * surfacing the bytes.
     */
    val e2eKeyVersion: Int = 0,
)

class IrcViewModel(
    private val repo: SettingsRepository,
    context: Context
) : ViewModel() {
    // ConcurrentHashMap used as a thread-safe set (touched from Main + IO).
    private val scrollbackRequested: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    // Start time of scrollback loading, used to insert an end-of-scrollback marker before any live messages that arrived during load.
    private val scrollbackLoadStartedAtMs: MutableMap<String, Long> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Per-buffer "newest server-history message timestamp seen since the last live message".
     *
     * Populated as messages with `isHistory = true` flow through [append]. Read on the FIRST
     * subsequent live (`isHistory = false`) message for the same buffer: if a meaningful gap
     * exists between the newest history line and the live one, [append] inserts a "── Chat
     * history • Last message: <ts> ──" separator just before the live message and clears the
     * entry. Mirrors the [scrollbackLoadStartedAtMs]-driven scrollback marker, but for
     * server-replayed history (CHATHISTORY, znc.in/playback, soju buffer playback) instead of
     * disk logs — the user gets the same visual cue at both kinds of catch-up boundary.
     *
     * ConcurrentHashMap because [append] runs on Main but isHistory updates can race with
     * dispatcher-bounded log loads that also touch other parts of [append].
     */
    private val pendingChathistoryMarkerMs: MutableMap<String, Long> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * "Catch-up window" for the chathistory marker, keyed by buffer. Set when a self-JOIN
     * fires (or the bouncer playback batch on a fresh connect), holding the wall-clock
     * deadline by which the marker must fire, after that, it's discarded silently and any
     * subsequent live message in that buffer renders without a separator.
     *
     * Window length matches the upstream history-expect window (15 s for znc.in/playback,
     * 7 s for IRCv3 CHATHISTORY) plus a 30 s grace for the first live message to arrive.
     */
    private val chathistoryMarkerArmedUntilMs: MutableMap<String, Long> =
        java.util.concurrent.ConcurrentHashMap()

    @SuppressLint("StaticFieldLeak")
    private val appContext: Context = context.applicationContext

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /**
     * Accumulation buffer for incoming 322 LIST replies.
     *
     * Large servers (e.g. Libera) send 10 000+ channel entries. If we update [_state] on
     * every entry the entire UiState - including all message buffers - is copied O(n) times
     * and the UI re-renders for each one. Instead we collect entries here and flush to
     * [_state] on a time throttle (see [_channelListLastFlushMs]), with a final flush on 323
     * (ListEnd). The buffer is cleared on ListStart so back-to-back /list calls are safe.
     */
    private val _channelListBuffer = ArrayList<ChannelListEntry>()
    /**
     * Wall-clock (elapsedRealtime) of the last time the streaming LIST buffer was pushed to
     * [_state]. Flushing is time-throttled rather than per-N-items: on large networks (Libera)
     * each RPL_LIST line is consumed on the Main thread, and a UI flush triggers a recomposition
     * of the channel list.
     */
    private var _channelListLastFlushMs = 0L
    private companion object {
        /** Minimum gap between streaming LIST UI flushes. See [_channelListLastFlushMs]. */
        const val CHANNEL_LIST_FLUSH_INTERVAL_MS = 300L
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
         * How long a locally-echoed outgoing message stays eligible to suppress a
         * bouncer's history-replay of that same message after a reconnect. Generous
         * enough to cover a reconnect that happens a few minutes after sending (the
         * common "phone changed networks / woke from doze" case), short enough that a
         * genuinely-repeated message typed much later isn't wrongly deduped. Only ever
         * suppresses a replay (isHistory) line, never a live one, so the worst-case
         * effect of the window being too long is that a deliberately repeated message
         * shows once instead of twice after a reconnect.
         */
        const val SELF_SEND_RETAIN_MS = 15 * 60_000L
        /**
         * After a *reconnect*, how long self-JOIN echoes are treated as automatic rejoins
         * (and therefore do NOT switch the active buffer). Covers both the client-sent
         * rejoin burst and bouncer-replayed JOINs; sized to the 45 s upstream history-expect
         * ceiling that bouncer playback uses (see the Connected handler). An explicit user
         * /join during this window still switches, because it is recorded in
         * [NetRuntime.pendingUserJoinSwitch] which overrides the suppression.
         */
        const val AUTO_JOIN_SWITCH_SUPPRESS_MS = 45_000L
    }

    private data class NamesRequest(
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


    private data class NetRuntime(
        val netId: String,
        val client: IrcClient,
        var job: Job? = null,
        var suppressMotd: Boolean = false,
        var manualMotdAtMs: Long = 0L,
        var myNick: String = client.config.nick,
		val namesRequests: MutableMap<String, NamesRequest> = mutableMapOf(),
		// Throttled to avoid spamming the server when the nicklist opens/closes rapidly.
		val lastNamesRefreshAtMs: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap(),
        var support: NetSupport = NetSupport(),
        // Manually-joined channels not covered by autoJoin, rejoined on reconnect.
        // Key = channel name (server casing), value = channel key or null.
        val manuallyJoinedChannels: MutableMap<String, String?> = mutableMapOf(),
        // Channels the user EXPLICITLY asked to JOIN this session (join button or a typed
        // /join). A self-JOIN echo for one of these always switches the active buffer to it,
        // even inside the post-reconnect suppression window below. Folded channel names;
        // entries are consumed (removed) when the matching self-JOIN arrives.
        val pendingUserJoinSwitch: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet(),
        // While System.currentTimeMillis() < this value, the JOIN handler does NOT auto-switch
        // the active buffer for self-joins we did NOT explicitly request — i.e. the burst of
        // rejoins after a reconnect (client-sent JOINs for autojoin + manuallyJoinedChannels,
        // AND bouncer-replayed JOINs from our prior session). Set ONLY on a reconnect, so a
        // first connect still lands the user in an autojoin channel as before. Without this, a
        // reconnect that re-joins every channel yanks the user onto whichever JOIN echoes last.
        @Volatile var suppressAutoJoinSwitchUntilMs: Long = 0L
    )

    // Many of the per-network maps and sets below are mutated and read from multiple
    // coroutines simultaneously: each network's IRC events flow runs on its own
    // Dispatchers.IO coroutine, so the moment the user has two networks active, every
    // event handler touching these structures races against its sibling. Plain Java
    // HashMap is documented as undefined-behaviour under concurrent mutation; on Android
    // (OpenJDK 17 runtime) the failure mode ranges from silent data loss to internal
    // table corruption that causes get() to loop forever (an ANR), and rarely throws
    // ConcurrentModificationException outright when an iterator notices the structural
    // mutation. ConcurrentHashMap is correct per-operation; the read-modify-write races
    // that survive that fix are documented separately.
    private val runtimes: MutableMap<String, NetRuntime> = java.util.concurrent.ConcurrentHashMap()

    private val desiredConnected: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var desiredNetworkIdsLoaded = false
    private var desiredNetworkIdsApplied = false
    private val autoReconnectJobs: MutableMap<String, Job> = java.util.concurrent.ConcurrentHashMap()
    private val reconnectAttempts: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()
    /**
     * Per-network jobs that fire after STABLE_CONNECTION_MS of uptime to reset the
     * reconnect backoff counter. Cancelled on disconnect so a short-lived connection
     * (e.g. a Z-lined or immediately-dropped session) never clears the backoff,
     * preserving exponential back-off across rapid connect/disconnect cycles.
     */
    private val stableConnectionJobs: MutableMap<String, Job> = java.util.concurrent.ConcurrentHashMap()
    // Cache the last label/status sent to the foreground service notification so we can
    // skip the startService() Binder IPC when nothing has changed. Every setNetConn() call
    // (lag updates, status changes, etc.) goes through refreshConnectionNotification(),
    // which would otherwise fire an IPC and wake the NotificationManager on every ping.
    private var lastNotifLabel: String? = null
    private var lastNotifStatus: String? = null
    private val manualDisconnecting: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val noNetworkNotice: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Networks where the last connection attempt failed with an authentication error
     * (server PASS rejected via 464 ERR_PASSWDMISMATCH, or SASL aborted via 904/905/906).
     * scheduleAutoReconnect bails when a netId is in this set, so the client doesn't
     * re-fire the same wrong credentials every few seconds and either flood the bouncer
     * UI or trip rate-limits / IP bans on the IRCd.
     *
     * Cleared when the user explicitly reconnects, edits the profile, or toggles
     * autoConnect — i.e. anywhere they've had a chance to fix the credentials.
     * NOT cleared on routine disconnects.
     */
    private val authBlockedReconnect: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Networks that have successfully REGISTERED (received 001) at least once during this
     * app-process session. Used to decide whether a given registration is a genuine first
     * connect or a re-connect of any kind (auto-reconnect, the user's manual "Reconnect",
     * or a manual disconnect followed by a manual connect).
     *
     * On a first connect we let autojoin pull the user into a channel; on any re-connect we
     * arm [NetRuntime.suppressAutoJoinSwitchUntilMs] so the rejoin burst doesn't yank the
     * user off the buffer they were viewing.
     *
     * Deliberately NOT cleared on disconnect (including manual disconnect): a manual
     * disconnect→reconnect must still count as a re-connect. Only cleared when the profile
     * is removed (deleteNetwork / orphan-import cleanup); the whole set is naturally empty
     * again on the next app launch.
     */
    private val everRegisteredThisSession: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    // Flap detection: track timestamps (ms) of ping-timeout disconnects per network.
    // If ≥ FLAP_THRESHOLD occur within FLAP_WINDOW_MS the connection is deemed unstable
    // and auto-reconnect is suspended until the user manually reconnects.
    //
    // Outer map is concurrent. Inner ArrayDeque is intentionally not thread-safe because
    // it's only read/mutated under per-key access patterns from the same coroutine
    // sequence (the ping loop for a given network) so there's no concurrent inner access
    // in practice.
    private val pingTimeoutTimestamps: MutableMap<String, ArrayDeque<Long>> =
        java.util.concurrent.ConcurrentHashMap()

    // Flap-paused state is persisted via DataStore
    // DataStore is the rest of the app's persistence layer and is immune to the data-loss
    // bugs that SharedPreferences can exhibit under process death on certain OEM ROMs.
    //
    // In-memory set for fast synchronous checks during event handling; the DataStore copy
    // is the durable source of truth that survives process kills.
    private val flapPaused: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var flapPausedLoaded = false

    /**
     * Per-network dedup tracker for transient connection-status lines (disconnect reasons,
     * connect-failure errors, recurring SASL/keystore warnings). Within
     * [CONN_STATUS_DEDUP_WINDOW_MS] of the first occurrence, a repeated identical line is
     * NOT appended again, instead the existing message is updated in place with a
     * "(×N)" counter suffix so the user can still see that the same condition happened
     * multiple times.
     */
    private data class ConnStatusDedupEntry(
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
    private val lastConnStatusLine: MutableMap<String, ConnStatusDedupEntry> =
        java.util.concurrent.ConcurrentHashMap()
    private val connStatusLock = Any()
    private val CONN_STATUS_DEDUP_WINDOW_MS = 60_000L
    /**
     * Per-network buffer of the most recent server-sent `ERROR :..` payload. Populated
     * when the [IrcEvent.ServerError] handler fires; read by the [IrcEvent.Disconnected]
     * handler to recover the rejection reason in cases where the disconnect itself
     * arrives with a generic reason (e.g. "socket closed", "EOF") that's stripped of
     * the server's actual explanation. The classic case is `ERROR :Closing Link: <addr>
     * (SASL required for this connection class)` followed immediately by socket close
     * without correlation the disconnect handler can't see "SASL required" in `r` and
     * scheduleAutoReconnect happily floods.
     *
     * Cleared on successful registration
     *
     * Value: (message text, epoch ms). Correlation window is intentionally short
     * ([SERVER_ERROR_DISCONNECT_CORRELATION_MS]) a server error from 30 s ago is
     * almost certainly unrelated to the disconnect happening now.
     */
    private val lastServerErrorByNet: MutableMap<String, Pair<String, Long>> =
        java.util.concurrent.ConcurrentHashMap()
        private val SERVER_ERROR_DISCONNECT_CORRELATION_MS = 5_000L

    /** Hydrate the in-memory flapPaused set from DataStore (called once, lazily, on first use). */
    private suspend fun ensureFlapPausedLoaded() {
        if (flapPausedLoaded) return
        flapPausedLoaded = true
        val now = System.currentTimeMillis()
        val stored = repo.readFlapPaused()
        // Drop entries older than 2× the flap window so a week-old pause doesn't
        // block reconnect forever after a stable period.
        val active = stored.filter { (_, pausedAt) ->
            pausedAt + ConnectionConstants.FLAP_WINDOW_MS * 2 > now
        }
        flapPaused.addAll(active.keys)
        // Persist the cleaned-up map back so expired entries don't accumulate.
        if (active.size != stored.size) {
            val newMap = stored.filterKeys { it in flapPaused }
            viewModelScope.launch(Dispatchers.IO) { runCatching { repo.writeFlapPaused(newMap) } }
        }
    }

    private fun markFlapPaused(netId: String) {
        flapPaused.add(netId)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val current = repo.readFlapPaused().toMutableMap()
                current[netId] = System.currentTimeMillis()
                repo.writeFlapPaused(current)
            }
        }
    }

    private fun clearFlapPaused(netId: String) {
        flapPaused.remove(netId)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val current = repo.readFlapPaused().toMutableMap()
                current.remove(netId)
                repo.writeFlapPaused(current)
            }
        }
    }

    // Not persisted; resets to all-expanded on process restart.
    private val _collapsedNetworkIds = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Programmatic entry point for the buffer-list toolbar's search button. Equivalent to
     * the user typing `/find <query>` in the currently-selected buffer. Kept separate from
     * the slash-command dispatch path so the dispatcher can stay focused on parsing chat
     * input; the toolbar already knows it wants to search and has the query in hand.
     *
     * If [global] is true, searches across all loaded buffers on the current network
     * (mirroring `/gsearch`); otherwise only the active buffer.
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
            append(currentKey, from = null, text = "*** No matches for \"$q\"", isLocal = true, doNotify = false)
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


    private fun launchExpandedNetworkIdsSync() {
        viewModelScope.launch {
            _collapsedNetworkIds.collect { ids ->
                _state.update { it.copy(collapsedNetworkIds = ids) }
            }
        }
    }

    private val netOpLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun netLock(netId: String): Mutex {
        netOpLocks[netId]?.let { return it }
        val created = Mutex()
        val prev = netOpLocks.putIfAbsent(netId, created)
        return prev ?: created
    }
    private suspend inline fun <T> withNetLock(netId: String, crossinline block: suspend () -> T): T {
        return netLock(netId).withLock { block() }
    }

    private fun hasInternetConnection(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        val hasTransport =
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        return hasTransport && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }


    /**
     * Returns true when [host] resolves to a private/loopback/link-local address
     * that requires ACCESS_LOCAL_NETWORK on Android 17+.
     * Does a quick string-based check first (no DNS lookup) to avoid blocking the
     * calling coroutine; unresolvable hostnames are assumed to be public.
     */
    private fun isLocalHost(host: String): Boolean {
        val h = host.trim().lowercase()
        // Loopback
        if (h == "localhost" || h == "::1" || h.startsWith("127.")) return true
        // Private IPv4 ranges: 10.x, 172.16–31.x, 192.168.x
        if (h.startsWith("10.")) return true
        if (h.startsWith("192.168.")) return true
        if (h.startsWith("172.")) {
            val second = h.split(".").getOrNull(1)?.toIntOrNull() ?: return false
            if (second in 16..31) return true
        }
        // IPv6 link-local (fe80::) and unique-local (fc00::/7 = fc..–fd..)
        if (h.startsWith("fe80:")) return true
        if (h.startsWith("fc") || h.startsWith("fd")) return true
        // Let DNS sort out anything else
        return false
    }

    /**
     * The SOCKS proxy configured for [netId]'s profile, or a disabled config when the
     * network has none. Used to tunnel DCC connections through the same proxy as the IRC
     * link, and to decide whether listen-based DCC operations are available.
     *
     * The proxy password isn't needed here: DccManager only calls SocksProxy.connect, and an
     * authenticated proxy will already be holding an authenticated IRC connection but to be
     * correct for proxies that authenticate per-connection we load it from SecretStore.
     */
    private fun proxyForNetwork(netId: String): com.boxlabs.hexdroid.connection.ProxyConfig {
        val profile = _state.value.networks.firstOrNull { it.id == netId }
            ?: return com.boxlabs.hexdroid.connection.ProxyConfig()
        if (profile.proxyType == com.boxlabs.hexdroid.connection.ProxyType.NONE) {
            return com.boxlabs.hexdroid.connection.ProxyConfig()
        }
        val pw = runCatching { repo.secretStore.getProxyPassword(netId) }.getOrNull()
        return com.boxlabs.hexdroid.connection.ProxyConfig(
            type = profile.proxyType,
            host = profile.proxyHost,
            port = profile.proxyPort,
            username = profile.proxyUsername?.takeIf { it.isNotEmpty() },
            password = pw?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Cheap check (no keystore/disk access) for whether [netId] is configured to use a
     * proxy. Safe to call on the main thread; use this for UI-thread gating, and call
     * [proxyForNetwork] (which loads the encrypted password) only on a background dispatcher.
     */
    private fun isProxiedNetwork(netId: String): Boolean {
        val profile = _state.value.networks.firstOrNull { it.id == netId } ?: return false
        return profile.proxyType != com.boxlabs.hexdroid.connection.ProxyType.NONE &&
            profile.proxyHost.isNotBlank() && profile.proxyPort in 1..65535
    }

    /**
     * Returns true when the app holds ACCESS_LOCAL_NETWORK (required on Android 17+).
     * On earlier API levels the permission doesn't exist and this always returns true.
     */
    private fun hasLocalNetworkPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 37) return true
        return android.content.pm.PackageManager.PERMISSION_GRANTED ==
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, "android.permission.ACCESS_LOCAL_NETWORK"
            )
    }

    private fun persistDesiredNetworkIds() {
        val ids = desiredConnected.toSet()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.setDesiredNetworkIds(ids) }
        }
    }

    private fun maybeRestoreDesiredConnections() {
        if (desiredNetworkIdsApplied) return
        if (!desiredNetworkIdsLoaded) return
        val st = _state.value
        if (st.networks.isEmpty()) return

        if (!st.settings.keepAliveInBackground) return

        desiredNetworkIdsApplied = true
        val existing = st.networks.map { it.id }.toSet()
        // Only restore networks that BOTH were previously desired AND have autoConnect on.
        // desiredConnected captures "was connected at last process death" which is the
        // correct intent for "reconnect after network loss within a session", but for
        // process-restart restoration it must be intersected with the per-network
        // autoConnect flag. Otherwise toggling autoConnect off has no effect on a
        // network that's currently connected
        val autoConnectIds = st.networks.filter { it.autoConnect }.map { it.id }.toSet()
        val targets = desiredConnected
            .filter { existing.contains(it) && autoConnectIds.contains(it) }
            .toList()

        // Drop any persisted desired-connect entries that no longer correspond to an
        // existing+autoConnect network so they don't re-trigger on subsequent launches.
        // (Networks deleted entirely are also pruned here.)
        val before = desiredConnected.size
        desiredConnected.retainAll { existing.contains(it) && autoConnectIds.contains(it) }
        if (desiredConnected.size != before) persistDesiredNetworkIds()

        if (targets.isEmpty()) return
        // Same rationale as maybeAutoConnect: stagger to avoid bouncer rate-limit bounces.
        viewModelScope.launch {
            targets.forEachIndexed { i, id ->
                if (i > 0) delay(CONNECT_FAN_OUT_DELAY_MS)
                connectNetwork(id)
            }
        }
    }


    private fun vibrateForHighlight(intensity: VibrateIntensity) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            val vm = appContext.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        if (vibrator == null || !vibrator.hasVibrator()) return

        val (durationMs, amplitude) = when (intensity) {
            VibrateIntensity.LOW -> 25L to 80
            VibrateIntensity.MEDIUM -> 40L to 160
            VibrateIntensity.HIGH -> 70L to 255
        }

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Throwable) {
            // Ignore vibration failures.
        }
    }

    // PART is sent when the user closes a buffer; the buffer is removed when we receive our own PART back.
    private val pendingCloseAfterPart: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Volatile private var appExitRequested: Boolean = false

    private fun isRecentEvent(timeMs: Long): Boolean {
        val now = System.currentTimeMillis()
        // 30s window should cover clock skew + batching without letting real playback mutate state.
        return timeMs >= (now - 30_000L) && timeMs <= (now + 30_000L)
    }

    // History events only affect live state if their timestamp is within 30s of now.
    // Some bouncers mis-tag live messages as history, but those carry a recent @time.
    private fun shouldAffectLiveState(isHistory: Boolean, timeMs: Long?): Boolean =
        if (!isHistory) true else (timeMs != null && isRecentEvent(timeMs))


    // Per-channel nick prefix tracking. Outer key = bufferKey, inner key = case-folded nick.
    // chanNickCase / chanNickStatus / nickAwayState: outer maps are concurrent. Inner
    // maps stay as plain mutableMapOf because they're only mutated under per-channel
    // event sequences (NAMES/JOIN/PART/QUIT for one channel arrive serially from one
    // network's events flow, so the inner maps don't see concurrent writers in practice).
    private val chanNickCase: MutableMap<String, MutableMap<String, String>> =
        java.util.concurrent.ConcurrentHashMap()
    private val chanNickStatus: MutableMap<String, MutableMap<String, MutableSet<Char>>> =
        java.util.concurrent.ConcurrentHashMap()

    private val nickAwayState: MutableMap<String, MutableMap<String, String>> =
        java.util.concurrent.ConcurrentHashMap()

    // Auto-rejoin throttle. Key = "$netId::${chan.lowercase()}", value = epoch-ms of the last
    // auto-rejoin attempt. Used to suppress repeat rejoins within AUTO_REJOIN_SUPPRESS_MS so a
    // user who is being kick-banned (or who can't satisfy +i / +k) doesn't get into an
    // immediate kick → rejoin → kick loop. One auto-rejoin per minute per channel is plenty.
    private val recentKickRejoins: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Per-buffer-key timestamp of our most recent self-JOIN. Read by the notice routing
     * to attribute a service-bot welcome NOTICE that arrived within ~5 s of the join to
     * the channel we just joined - even when the notice body doesn't mention the channel
     * name (e.g. Anope BotServ-assigned bots that send "Welcome, $nick!" with no channel
     * reference). Bounded by an opportunistic eviction in the JOIN handler; never holds
     * entries longer than the read window cares about.
     */
    private val recentJoinAtMs: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    private var autoConnectAttempted = false

    private val notifier = NotificationHelper(appContext)
    private val logs = LogWriter(appContext)

    /**
     * Shared E2E keystore. One instance per ViewModel (and therefore per app process),
     * since per-target keys are persisted via SecretStore and need to be visible across
     * every network's IrcClient. The keystore lazily hydrates per-network from the
     * underlying SharedPreferences on first access, so networks that never use E2E
     * pay zero startup cost.
     */
    val e2eKeyStore = com.boxlabs.hexdroid.crypto.E2eKeyStore(repo.secretStore)
    private val dcc = DccManager(appContext)
    private val dccPartials = DccResumeStore(appContext)

    private data class DccChatSession(
        val netId: String,
        val peer: String,
        val bufferKey: String,
        val socket: Socket,
        val writer: BufferedWriter,
        val readJob: Job
    )

    private val dccChatSessions: MutableMap<String, DccChatSession> =
        java.util.concurrent.ConcurrentHashMap()

    private data class PendingPassiveDccSend(
        val target: String,
        val filename: String,
        val size: Long,
        val reply: CompletableDeferred<DccOffer>
    )

    private val pendingPassiveDccSends: MutableMap<Long, PendingPassiveDccSend> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Jobs for in-progress outgoing DCC sends, keyed by "$target/$filename".
     * Stored so the user can cancel a send from the Transfers screen.
     */
    private val outgoingSendJobs: MutableMap<String, kotlinx.coroutines.Job> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Tracks in-flight DCC receive coroutines, keyed by the offer. Mirrors [outgoingSendJobs]
     * so the user can cancel an incoming transfer from the Transfers screen X button.
     * Cancelling the Job triggers [DccManager]'s `invokeOnCompletion` socket-close, which
     * unblocks the receive loop and aborts the transfer.
     */
    private val incomingReceiveJobs: MutableMap<DccOffer, kotlinx.coroutines.Job> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Outgoing sends that are currently "live"  either listening for an
     * active-DCC connect or having sent a passive-DCC offer and waiting for the peer.
     * Keyed by the peer's view of the file: `nick.lowercase() + "|" + baseName + "|" + size`.
     * Used to look up the matching send when a DCC RESUME arrives from the peer.
     *
     * The CompletableDeferred receives the agreed start offset once we've replied with
     * DCC ACCEPT and committed to seeking the file there. The actual seek happens inside
     * the DccManager send path.
     */
    private data class LiveOutgoingSend(
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
    private val liveOutgoingSends: MutableMap<String, LiveOutgoingSend> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Pending DCC RESUME requests *we* sent, keyed by `peer.lowercase()|baseName|size`.
     * Completes when the peer replies with DCC ACCEPT confirming the offset.
     */
    private val pendingResumeRequests: MutableMap<String, CompletableDeferred<DccAccept>> =
        java.util.concurrent.ConcurrentHashMap()

    private val nextUiMsgId = AtomicLong(1L)

    private val logTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun formatLogLine(timeMs: Long, from: String?, text: String, isAction: Boolean): String {
        val ts = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).format(logTimeFormatter)
        val t = stripIrcFormatting(text)
        val body = when {
            from == null -> t
            // *nick* text - asterisk-wrapped nick is unambiguous: server-status lines always
            // use "* word …" (asterisk-space) and can never produce this pattern.
            // Old logs used "* nick text"; the parser below handles both for backward compat.
            isAction -> "*$from* $t"
            else -> "<$from> $t"
        }
        return "$ts\t$body"
    }

    private data class SentSig(val bufferKey: String, val text: String, val isAction: Boolean, val ts: Long)
    private val pendingSendsByNet: MutableMap<String, ArrayDeque<SentSig>> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Per-buffer record of messages WE sent and locally echoed, used to dedup the
     * bouncer's history replay of our own messages on reconnect.
     *
     * The problem this solves: a local echo is appended with `timeMs = local clock`,
     * but when a bouncer (ZNC / soju) replays that same message via CHATHISTORY or
     * buffer playback after a reconnect, it carries the SERVER's `time=` tag. The
     * content-fingerprint dedup ([UiBuffer.seenHistoryFingerprints]) keys on
     * `"$ts|$from|$hash"`, so the differing timestamps mean the replay never matches
     * the echo and the user sees their last few messages twice.
     *
     * The echo-message dedup deque ([pendingSendsByNet]) can't help either: it's
     * pruned to an 8-second window because it exists to catch the near-instant
     * server reflection, not a reconnect that might happen minutes later.
     *
     * This map stores, per buffer, the (signature, insert-time) of each message we
     * locally echoed. On a history replay attributed to our own nick we look for a
     * matching un-expired signature; a hit means "already shown as a local echo" and
     * we drop the replay. Entries expire after [SELF_SEND_RETAIN_MS] and are capped
     * per buffer so a long-lived session can't grow this unbounded. Matching consumes
     * the entry, so sending the identical text twice is reconciled correctly (two
     * echoes, two entries, two replays each consume one).
     */
    private val recentSelfSends: MutableMap<String, ArrayDeque<Pair<Long, String>>> =
        java.util.concurrent.ConcurrentHashMap()

    private fun selfSendSig(text: String, isAction: Boolean): String = "${if (isAction) "A" else "M"}\u0000$text"

    private fun recordSelfSend(bufferKey: String, text: String, isAction: Boolean) {
        val now = System.currentTimeMillis()
        val dq = recentSelfSends.getOrPut(bufferKey) { ArrayDeque(16) }
        synchronized(dq) {
            dq.addLast(now to selfSendSig(text, isAction))
            // Prune by age and cap. The cap is generous (one entry per message we've
            // sent in the retention window); a human can't realistically send 64
            // messages to one buffer inside the window and then reconnect, but the
            // cap guarantees boundedness regardless.
            while (dq.isNotEmpty() && now - dq.first().first > SELF_SEND_RETAIN_MS) dq.removeFirst()
            while (dq.size > 64) dq.removeFirst()
        }
    }

    /**
     * Returns true (and consumes the matching entry) if [text]/[isAction] matches a
     * message we locally echoed to [bufferKey] within the retention window. Used to
     * recognise the bouncer replaying our own message back to us after a reconnect.
     */
    private fun consumeSelfSendIfMatch(bufferKey: String, text: String, isAction: Boolean): Boolean {
        val dq = recentSelfSends[bufferKey] ?: return false
        val now = System.currentTimeMillis()
        val sig = selfSendSig(text, isAction)
        synchronized(dq) {
            while (dq.isNotEmpty() && now - dq.first().first > SELF_SEND_RETAIN_MS) dq.removeFirst()
            // Last match wins so the most recent echo of a repeated message is the one
            // reconciled first, mirroring consumeEchoIfMatch's ordering.
            val idx = dq.indexOfLast { it.second == sig }
            if (idx < 0) return false
            dq.removeAt(idx)
            return true
        }
    }

    private fun bufKey(netId: String, bufferName: String): String = "$netId::$bufferName"

    /**
     * Wrap [text] in mIRC colour-code framing (`\u0003<code>` + text + `\u0003`) when the
     * `colorChannelEvents` setting is on. The renderer's existing mIRC parser turns this
     * into a Compose SpanStyle on display: log files, copy-to-clipboard, and any other
     * sink that strips formatting will see the unwrapped text.
     *
     * Colour code conventions (mIRC palette indices):
     *   3  green   joins (positive event)
     *   7  orange  parts (neutral departure, client-initiated)
     *   5  brown   quits (server-initiated departure, distinct from parts)
     *   4  red     kicks (forced removal, demands attention)
     *   10 cyan    nick changes (informational, low-priority)
     */
    private fun colorEvent(text: String, code: Int): String {
        if (!_state.value.settings.colorChannelEvents) return text
        return "\u0003$code$text\u0003"
    }

    // Case-fold aware lookup; merges duplicate buffers if the server changes name casing.
    private fun resolveBufferKey(netId: String, bufferName: String): String {
        val name = bufferName.trim().ifBlank { "*server*" }
        val fold = casefoldText(netId, name)

        val st0 = _state.value
        val candidates = st0.buffers.keys.filter { k ->
            val (nid, bn) = splitKey(k)
            nid == netId && casefoldText(netId, bn) == fold
        }

        if (candidates.isEmpty()) return bufKey(netId, name)

        val chosen = when {
            st0.selectedBuffer in candidates -> st0.selectedBuffer
            else -> candidates.maxByOrNull { st0.buffers[it]?.messages?.size ?: 0 } ?: candidates.first()
        }

        if (candidates.size > 1) {
            mergeDuplicateBuffers(chosen, candidates.filter { it != chosen })
        }

        return chosen
    }

    private fun resolveIncomingBufferKey(netId: String, raw: String?): String {
        val name = normalizeIncomingBufferName(netId, raw)
        return resolveBufferKey(netId, name)
    }

    private fun mergeDuplicateBuffers(keepKey: String, dropKeys: List<String>) {
        if (dropKeys.isEmpty()) return
        val st0 = _state.value
        val keepBuf0 = st0.buffers[keepKey] ?: return

        val maxLines = st0.settings.maxScrollbackLines.coerceIn(100, 5000)

        var mergedMsgs = keepBuf0.messages
        var unread = keepBuf0.unread
        var highlights = keepBuf0.highlights
        var topic = keepBuf0.topic

        for (k in dropKeys) {
            val b = st0.buffers[k] ?: continue
            if (b.messages.isNotEmpty()) mergedMsgs = mergedMsgs + b.messages
            unread += b.unread
            highlights += b.highlights
            if (topic == null) topic = b.topic

            chanNickCase.remove(k)?.let { other ->
                val keep = chanNickCase.getOrPut(keepKey) { mutableMapOf() }
                keep.putAll(other)
            }
            chanNickStatus.remove(k)?.let { other ->
                val keep = chanNickStatus.getOrPut(keepKey) { mutableMapOf() }
                for ((fold, modes) in other) {
                    val mm = keep.getOrPut(fold) { mutableSetOf() }
                    mm.addAll(modes)
                }
            }
        }

        val merged = mergedMsgs
            .distinctBy { it.id }
            .sortedWith(compareBy<UiMessage> { it.timeMs }.thenBy { it.id })
            .takeLast(maxLines)

        // Rebuild seenMsgIds from the retained messages so the O(1) dedup index stays
        // consistent with the actual message list after a merge/rename operation.
        val mergedSeenMsgIds: Set<String> = merged.mapNotNullTo(HashSet()) { it.msgId }

        val keepBuf = keepBuf0.copy(messages = merged, seenMsgIds = mergedSeenMsgIds, unread = unread, highlights = highlights, topic = topic)

        fun <T> adoptIfMissing(map: Map<String, T>): Map<String, T> {
            var out = map
            if (!out.containsKey(keepKey)) {
                val adopt = dropKeys.firstNotNullOfOrNull { out[it] }
                if (adopt != null) out = out + (keepKey to adopt)
            }
            for (k in dropKeys) out = out - k
            return out
        }

        if (pendingCloseAfterPart.any { it == keepKey || dropKeys.contains(it) }) {
            pendingCloseAfterPart.removeAll(dropKeys.toSet())
            pendingCloseAfterPart.add(keepKey)
        }

        scrollbackRequested.removeAll(dropKeys.toSet())
        for (k in dropKeys) pendingChathistoryMarkerMs.remove(k)

        var newBuffers = st0.buffers + (keepKey to keepBuf)
        for (k in dropKeys) newBuffers = newBuffers - k

        val newSelected = if (dropKeys.contains(st0.selectedBuffer)) keepKey else st0.selectedBuffer

        val next = st0.copy(
            buffers = newBuffers,
            selectedBuffer = newSelected,
            nicklists = adoptIfMissing(st0.nicklists),
            banlists = adoptIfMissing(st0.banlists),
            banlistLoading = adoptIfMissing(st0.banlistLoading),
            quietlists = adoptIfMissing(st0.quietlists),
            quietlistLoading = adoptIfMissing(st0.quietlistLoading),
            exceptlists = adoptIfMissing(st0.exceptlists),
            exceptlistLoading = adoptIfMissing(st0.exceptlistLoading),
            invexlists = adoptIfMissing(st0.invexlists),
            invexlistLoading = adoptIfMissing(st0.invexlistLoading)
        )
        _state.value = syncActiveNetworkSummary(next)
    }

    /**
     * Pending-close tracking is keyed by the *exact* UI buffer key the user closed.
     * Server replies may use a different case for the channel name, so match using CASEMAPPING-aware
     * case-folding.
     */
    private fun popPendingCloseForChannel(netId: String, channel: String): String? {
        val fold = casefoldText(netId, channel)
        val match = pendingCloseAfterPart.firstOrNull { k ->
            val (nid, bn) = splitKey(k)
            nid == netId && casefoldText(netId, bn) == fold
        }
        if (match != null) pendingCloseAfterPart.remove(match)
        return match
    }

    private fun splitKey(key: String): Pair<String, String> {
        val idx = key.indexOf("::")
        return if (idx <= 0) ("unknown" to key) else (key.take(idx) to key.drop(idx + 2))
    }

    private fun normalizeIncomingBufferName(netId: String, raw: String?): String {
        val t = raw?.trim().orEmpty()
        if (t.isBlank() || t == "?" || t == "*" || t.equals("AUTH", ignoreCase = true)) return "*server*"
        return t
    }

    private fun isChannelOnNet(netId: String, name: String): Boolean {
        val chantypes = runtimes[netId]?.support?.chantypes ?: "#&"
        return name.isNotBlank() && chantypes.any { name.startsWith(it) }
    }

	private fun stripStatusMsgPrefix(netId: String, name: String): String {
		val support = runtimes[netId]?.support ?: return name
		val sm = support.statusMsg ?: return name
		val chantypes = support.chantypes
		return if (name.length >= 2 && sm.contains(name[0]) && chantypes.contains(name[1])) {
			name.substring(1)
		} else {
			name
		}
	}

    /**
     * Generic helper: initialises a mode-list buffer and marks it as loading.
     * Replaces the four near-identical startBanList/startQuietList/startExceptList/startInvexList
     * functions that were previously written out verbatim.
     */
    private fun startModeList(
        netId: String,
        channel: String,
        getList: (UiState) -> Map<String, List<BanEntry>>,
        getLoading: (UiState) -> Map<String, Boolean>,
        setList: UiState.(Map<String, List<BanEntry>>) -> UiState,
        setLoading: UiState.(Map<String, Boolean>) -> UiState
    ) {
        val key = resolveBufferKey(netId, channel)
        ensureBuffer(key)
        val st = _state.value
        _state.value = syncActiveNetworkSummary(
            st.setList(getList(st) + (key to emptyList()))
                .setLoading(getLoading(st) + (key to true))
        )
    }

    private fun startBanList(netId: String, channel: String) = startModeList(
        netId, channel,
        { it.banlists }, { it.banlistLoading },
        { copy(banlists = it) }, { copy(banlistLoading = it) }
    )

    private fun startQuietList(netId: String, channel: String) = startModeList(
        netId, channel,
        { it.quietlists }, { it.quietlistLoading },
        { copy(quietlists = it) }, { copy(quietlistLoading = it) }
    )

    private fun startExceptList(netId: String, channel: String) = startModeList(
        netId, channel,
        { it.exceptlists }, { it.exceptlistLoading },
        { copy(exceptlists = it) }, { copy(exceptlistLoading = it) }
    )

    private fun startInvexList(netId: String, channel: String) = startModeList(
        netId, channel,
        { it.invexlists }, { it.invexlistLoading },
        { copy(invexlists = it) }, { copy(invexlistLoading = it) }
    )

    private fun pendingDeque(netId: String): ArrayDeque<SentSig> =
        pendingSendsByNet.getOrPut(netId) { ArrayDeque(32) }

    private fun recordLocalSend(netId: String, bufferKey: String, text: String, isAction: Boolean) {
        val now = System.currentTimeMillis()
        val dq = pendingDeque(netId)
        dq.addLast(SentSig(bufferKey.lowercase(), text, isAction, now))
        while (dq.size > 30) dq.removeFirst()
    }

    private fun consumeEchoIfMatch(netId: String, bufferKey: String, text: String, isAction: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val dq = pendingDeque(netId)
        val bufKeyLower = bufferKey.lowercase()

        while (dq.isNotEmpty() && now - dq.first().ts > 8000) dq.removeFirst()

        // Last match wins so sending the same message twice dedupes correctly.
        val matchIdx = dq.indexOfLast { it.bufferKey == bufKeyLower && it.text == text && it.isAction == isAction }
        if (matchIdx < 0) return false

        dq.removeAt(matchIdx)
        return true
    }

    init {
        launchExpandedNetworkIdsSync()
        // Hydrate flap-paused state from DataStore before any connections start.
        // This must be a suspend call, so we run it in viewModelScope. It completes almost
        // instantly (single DataStore read) and sets flapPausedLoaded=true so the lazy guard
        // in ensureFlapPausedLoaded() is a no-op on any subsequent call.
        viewModelScope.launch { runCatching { ensureFlapPausedLoaded() } }
        // Surface "log folder is unreadable" to the UI. LogWriter populates its
        // unreadableTreeUrisFlow whenever a SAF query against a tree URI throws
        // SecurityException (most often: backup-restore brought the URI string into
        // settings but the persisted SAF permission grant didn't transfer to this
        // install). We combine that set with the currently-saved logFolderUri to
        // produce a single boolean that the Settings screen renders as a "re-pick
        // your log folder" warning row. Done as a separate collector (not folded
        // into the settingsFlow one above) so the badge updates in real time when
        // LogWriter discovers the URI is dead - the user doesn't need to navigate
        // away and back to see the warning appear.
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
            var prevLogFolderUri: String? = null
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
                if (s.loggingEnabled) logs.purgeOlderThan(s.retentionDays, s.logFolderUri)
                maybeAutoConnect()
                maybeRestoreDesiredConnections()
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
            repo.desiredNetworkIdsFlow.collect { ids ->
                desiredConnected.clear()
                desiredConnected.addAll(ids)
                desiredNetworkIdsLoaded = true
                refreshConnectionNotification()
                maybeRestoreDesiredConnections()
            }
        }
        
        registerNetworkCallback()
        
        notifier.ensureChannels()
    }
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    private fun registerNetworkCallback() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // Network became available - check if any desired connections need reconnecting
                viewModelScope.launch {
                    delay(1000) // Brief delay to let the network stabilize
                    val st = _state.value
                    // Snapshot before iterating: connectNetwork() inside the loop body adds
                    // to desiredConnected (and the auth-fail path may also remove from it
                    // via downstream disconnect handlers), so iterating the live set risks
                    // ConcurrentModificationException on any background ramp-up.
                    val snapshot = desiredConnected.toList()
                    for (netId in snapshot) {
                        val conn = st.connections[netId]
                        if (conn?.connected != true && conn?.connecting != true) {
                            val serverKey = bufKey(netId, "*server*")
                            if (noNetworkNotice.remove(netId)) {
                                append(serverKey, from = null, text = "*** Network available. Reconnecting…", doNotify = false)
                            }
                            connectNetwork(netId, force = true)
                        }
                    }
                }
            }
            
            override fun onLost(network: android.net.Network) {
                // Network lost - check if we still have connectivity via another network.
                viewModelScope.launch {
                    delay(500) // Brief window to let a failover interface take over.
                    if (!hasInternetConnection()) {
                        // No connectivity at all. Tear down each affected socket so its
                        // readLine() unblocks immediately instead of blocking for up to
                        // SOCKET_READ_TIMEOUT_MS (150 s). Without this, the UI freezes on
                        // the stale connection state, the exit drawer button appears to do
                        // nothing (the disconnect coroutine is queued behind the blocked
                        // read), and the app can hang long enough for the OS to restart it.
                        // dropConnectionForNetworkLoss() drops the socket but KEEPS the
                        // network in desiredConnected and resumes auto-reconnect.
                        val st = _state.value
                        val snapshot = desiredConnected.toList()
                        for (netId in snapshot) {
                            val conn = st.connections[netId]
                            if (conn?.connected == true || conn?.connecting == true) {
                                val serverKey = bufKey(netId, "*server*")
                                append(serverKey, from = null, text = "*** Network lost. Waiting for connectivity…", doNotify = false)
                                noNetworkNotice.add(netId)
                                dropConnectionForNetworkLoss(netId)
                            }
                        }
                    }
                }
            }
            
            override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                // IrcCore's ping cycle handles stale sockets; nothing to do here.
            }
        }
        networkCallback = cb
        try {
            val request = android.net.NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, cb)
        } catch (e: Exception) {
            // Registration can fail on some OEM ROMs (e.g. missing permission, broken
            // ConnectivityManager implementation). Log it to every server buffer so the
            // user knows auto-reconnect on network change won't work.
            val msg = "*** Network callback registration failed: ${e.message ?: e.javaClass.simpleName} - " +
                "auto-reconnect on network change may not work on this device"
            viewModelScope.launch {
                for (netId in _state.value.networks.map { it.id }) {
                    append(bufKey(netId, "*server*"), from = null, text = msg, doNotify = false)
                }
            }
        }
    }

    private fun maybeAutoConnect() {
        val st = _state.value
        if (autoConnectAttempted) return
        if (st.networks.isEmpty()) return
        autoConnectAttempted = true
        val targets = st.networks.filter { it.autoConnect }
        if (targets.isEmpty()) return
        // Stagger the fan-out. Bouncers and many IRCds rate-limit new TCP from one IP,
        // so firing N connect attempts simultaneously triggers "reconnect too fast" errors
        // against soju in particular. A small inter-connect delay is invisible to the user
        // (the UI still shows all networks connecting) but satisfies typical rate limits.
        viewModelScope.launch {
            targets.forEachIndexed { i, n ->
                if (i > 0) delay(CONNECT_FAN_OUT_DELAY_MS)
                connectNetwork(n.id)
            }
        }
    }

    fun setNetworkAutoConnect(netId: String, enabled: Boolean) {
        val n = _state.value.networks.firstOrNull { it.id == netId } ?: return
        viewModelScope.launch { repo.upsertNetwork(n.copy(autoConnect = enabled)) }

        // When autoConnect is being turned OFF, also clear any pending auto-reconnect
        // state for this network. Without this, a network that's currently connected
        // (or in reconnect-backoff) keeps its slot in [desiredConnected], so it gets
        // restored on the next process restart and the in-flight reconnect coroutine
        // keeps trying - both directly contradicting the toggle the user just flipped.
        // The user can still manually connect; they just won't get implicit reconnects.
        //
        // We do NOT immediately disconnect: if the network is currently connected the
        // user is using it, and turning off autoConnect shouldn't drop them. They're
        // saying "don't bring this back automatically", not "kill the connection now".
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

    private data class IrcUri(
        val host: String,
        val port: Int,
        val useTls: Boolean,
        val channels: List<String>,
        val channelKey: String? = null,
        val serverPassword: String? = null,
    )

    /**
     * Parses irc://, ircs://, and irc+ssl:// URIs into an [IrcUri].
     *
     * Handles every form seen in the wild:
     *   irc://host/channel           plain, port 6667
     *   irc://host:+6697/channel     TLS via +port convention (mIRC/ZNC) - note: Chrome
     *                                rejects this as an invalid URI; ircs:// or irc+ssl:// are
     *                                the browser-safe alternatives
     *   ircs://host:6697/channel     TLS via scheme (standard)
     *   irc+ssl://host:6697/channel  TLS via scheme (HexChat/irssi alternative)
     *   irc://host/#channel          # consumed as fragment by Uri; recovered
     *   irc://host/%23channel        percent-encoded; decoded automatically
     *   irc://host/chan?key=secret   channel key
     */
    private fun parseIrcUri(raw: String): IrcUri? {
        // Detect the +port TLS flag before Uri.parse() silently drops the '+'.
        val plusPortTls = Regex("""://[^/]*:\+\d+""").containsMatchIn(raw)
        // Normalise +port → plain port so android.net.Uri can parse correctly.
        val normalised = raw.replace(Regex("""(://[^/]*):(\+)(\d+)"""), "$1:$3")

        val uri = Uri.parse(normalised) ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "irc" && scheme != "ircs" && scheme != "irc+ssl") return null

        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val useTls = scheme == "ircs" || scheme == "irc+ssl" || plusPortTls
        val port = uri.port.takeIf { it in 1..65535 } ?: if (useTls) 6697 else 6667

        // Channel in path segments (irc://host/channel or irc://host/%23channel).
        // If '#' was unencoded, android.net.Uri puts it in the fragment instead.
        val rawChannel = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.fragment?.takeIf { it.isNotBlank() }

        val channels = rawChannel
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { ch -> if (ch[0] in "#&+!") ch else "#$ch" }
            ?: emptyList()

        val channelKey = uri.getQueryParameter("key") ?: uri.getQueryParameter("pass")
        val serverPassword = uri.userInfo?.split(":", limit = 2)?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }

        return IrcUri(host, port, useTls, channels, channelKey, serverPassword)
    }

    /**
     * Opens (or creates) a network matching an IRC URI and navigates to it.
     *
     * Match priority:
     *  1. Existing network whose host + port + TLS match exactly → re-use.
     *  2. Existing network whose host matches (port/TLS differ) → re-use as-is.
     *  3. No match → create a new [NetworkProfile] pre-filled from the URI and
     *     open the Network Edit screen so the user can review before connecting.
     *
     * Channels from the URI are merged into the profile's autoJoin list if not
     * already present.  When an existing network is matched, the app connects
     * immediately and navigates to the first channel buffer.
     */
    private fun handleIrcUri(ircUri: IrcUri) {
        viewModelScope.launch {
            val st = _state.value

            // Inherit the nick from an existing network, or fall back to app default.
            val defaultNick = st.networks.firstOrNull()?.nick
                ?: st.myNick.takeIf { it != "me" }
                ?: "HexDroidUser"

            val existing = st.networks.firstOrNull { n ->
                n.host.equals(ircUri.host, ignoreCase = true) &&
                n.port == ircUri.port &&
                n.useTls == ircUri.useTls
            } ?: st.networks.firstOrNull { n ->
                n.host.equals(ircUri.host, ignoreCase = true)
            }

            val newAutoJoin = ircUri.channels.map { ch ->
                AutoJoinChannel(ch, ircUri.channelKey)
            }

            if (existing != null) {
                // Merge any new channels into the existing autoJoin list.
                val mergedJoin = (existing.autoJoin + newAutoJoin)
                    .distinctBy { it.channel.lowercase() }
                if (mergedJoin != existing.autoJoin) {
                    repo.updateNetworkProfile(existing.id) { it.copy(autoJoin = mergedJoin) }
                }
                setActiveNetwork(existing.id)
                if (ircUri.channels.isNotEmpty()) {
                    openBuffer(bufKey(existing.id, ircUri.channels.first()))
                } else {
                    backToChat()
                }
            } else {
                // New server - pre-fill from URI and open the edit screen for review.
                val n = NetworkProfile(
                    id = "net_" + java.util.UUID.randomUUID().toString().replace("-", ""),
                    name = ircUri.host,
                    host = ircUri.host,
                    port = ircUri.port,
                    useTls = ircUri.useTls,
                    allowInvalidCerts = false,
                    nick = defaultNick,
                    altNick = "${defaultNick}_",
                    username = defaultNick.lowercase(),
                    realname = "HexDroid IRC",
                    serverPassword = ircUri.serverPassword,
                    saslEnabled = false,
                    saslMechanism = SaslMechanism.PLAIN,
                    caps = CapPrefs(),
                    autoJoin = newAutoJoin,
                )
                _state.value = _state.value.copy(
                    screen = AppScreen.NETWORK_EDIT,
                    editingNetwork = n,
                    networkEditError = null,
                )
            }
        }
    }

    fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // Handle text shared from another app (e.g. share a URL to paste into IRC).
        if (intent.action == Intent.ACTION_SEND &&
            intent.type?.startsWith("text/") == true) {
            val sharedText = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                ?.trim()?.takeIf { it.isNotBlank() }
            if (sharedText != null) {
                // Navigate to chat screen if not already there. Use backToChat so
                // unread/highlights on the previously-selected buffer get cleared
                // along with the screen flip - the user is effectively going to
                // read the chat now.
                if (_state.value.screen != AppScreen.CHAT) {
                    backToChat()
                }
                _state.value = _state.value.copy(pendingShareText = sharedText)
            }
            return
        }

        // Handle irc:// and ircs:// deep links before any notification extras.
        if (intent.action == Intent.ACTION_VIEW) {
            val uriString = intent.dataString
            if (!uriString.isNullOrBlank()) {
                val scheme = Uri.parse(uriString)?.scheme?.lowercase()
                if (scheme == "irc" || scheme == "ircs" || scheme == "irc+ssl") {
                    parseIrcUri(uriString)?.let { handleIrcUri(it) }
                    return
                }
            }
        }

        val netId = intent.getStringExtra(NotificationHelper.EXTRA_NETWORK_ID)
        val buf = intent.getStringExtra(NotificationHelper.EXTRA_BUFFER)
        val action = intent.getStringExtra(NotificationHelper.EXTRA_ACTION)
        val highlightMsgId = intent.getLongExtra(NotificationHelper.EXTRA_MSG_ID, -1L)
            .takeIf { it >= 0L }
        val highlightAnchor = intent.getStringExtra(NotificationHelper.EXTRA_MSG_ANCHOR)

        if (action == NotificationHelper.ACTION_OPEN_TRANSFERS) {
            if (!netId.isNullOrBlank()) setActiveNetwork(netId)
            _state.value = _state.value.copy(screen = AppScreen.TRANSFERS)
            return
        }

        if (action == NotificationHelper.ACTION_ACCEPT_DCC) {
            val from     = intent.getStringExtra(NotificationHelper.EXTRA_DCC_FROM)     ?: ""
            val filename = intent.getStringExtra(NotificationHelper.EXTRA_DCC_FILENAME) ?: ""
            val notifId  = intent.getIntExtra(NotificationHelper.EXTRA_NOTIF_ID, -1)
            if (!netId.isNullOrBlank()) setActiveNetwork(netId)
            _state.value = _state.value.copy(screen = AppScreen.TRANSFERS)
            // Dismiss the incoming-file notification immediately so the user
            // doesn't see a stale "incoming file" banner after accepting.
            if (notifId >= 0) NotificationManagerCompat.from(appContext).cancel(notifId)
            // Find the matching pending offer and accept it automatically.
            val offer = _state.value.dccOffers.firstOrNull { o ->
                (netId.isNullOrBlank() || o.netId == netId) &&
                o.from.equals(from, ignoreCase = true) &&
                o.filename == filename
            }
            if (offer != null) acceptDcc(offer)
            return
        }

        if (netId.isNullOrBlank() && buf.isNullOrBlank()) return

        if (!netId.isNullOrBlank()) setActiveNetwork(netId)

        val key = if (!netId.isNullOrBlank() && !buf.isNullOrBlank()) resolveBufferKey(netId, buf) else null
        if (key != null) openBuffer(key) else _state.value = _state.value.copy(screen = AppScreen.CHAT)
        if (highlightAnchor != null) {
            _state.value = _state.value.copy(
                pendingHighlightAnchor = highlightAnchor,
                pendingHighlightSetAtMs = System.currentTimeMillis(),
                pendingHighlightBufferKey = key,
            )
        } else if (highlightMsgId != null) {
            // old notifications stored a Long id, keep compat for stale notifs
            _state.value = _state.value.copy(
                pendingHighlightAnchor = "uiid:$highlightMsgId",
                pendingHighlightSetAtMs = System.currentTimeMillis(),
                pendingHighlightBufferKey = key,
            )
        }
    }

    /** Called by ChatScreen once the scroll-and-flash animation has run. */
    fun clearHighlightScroll() {
        _state.value = _state.value.copy(
            pendingHighlightAnchor = null,
            pendingHighlightBufferKey = null,
        )
    }

    fun consumeShareText() {
        _state.value = _state.value.copy(pendingShareText = null)
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

    fun openBuffer(key: String) {
        ensureBuffer(key)
        val (netId, _) = splitKey(key)

        val actualConnected = runtimes[netId]?.client?.isConnectedNow() == true
        val conn0 = _state.value.connections[netId]
        if (conn0?.connected == true && !actualConnected) {
            setNetConn(netId) { it.copy(connected = false, connecting = false, status = "Disconnected") }
        } else if (conn0?.connected != true && actualConnected) {
            setNetConn(netId) { it.copy(connected = true, connecting = false, status = "Connected") }
        }
        if (_state.value.activeNetworkId != netId) setActiveNetwork(netId)

        // Collect MARKREAD params here so we can fire the coroutine after _state.update returns.
        // Launching inside update {} is wrong: the CAS loop can retry, sending MARKREAD multiple times.
        var markReadNet: String? = null
        var markReadName: String? = null
        var markReadTs: String? = null

        // One atomic update: stamp leaving buffer, anchor separator, switch buffer, clear badge.
        _state.update { st ->
            // Stamp the leaving buffer so new messages appear after the separator on return.
            val leaving = st.selectedBuffer
            var buffers = st.buffers
            if (leaving.isNotBlank() && leaving != key) {
                val leavingBuf = buffers[leaving]
                if (leavingBuf != null) {
                    val lastMsg = leavingBuf.messages.lastOrNull()
                    if (lastMsg != null) {
                        val ts = java.time.Instant.ofEpochMilli(lastMsg.timeMs + 1L).toString()
                        buffers = buffers + (leaving to leavingBuf.copy(lastReadTimestamp = ts))

                        val (leavingNet, leavingName) = splitKey(leaving)
                        val rt = runtimes[leavingNet]
                        if (rt != null && rt.client.hasCap("draft/read-marker")) {
                            markReadNet = leavingNet
                            markReadName = leavingName
                            markReadTs = ts
                        }
                    }
                }
            }

            // Anchor a read marker if there isn't one yet, so the separator shows in the right place.
            val openingBuf = buffers[key]
            if (openingBuf != null && openingBuf.lastReadTimestamp == null && openingBuf.unread > 0) {
                val msgs = openingBuf.messages
                val firstUnreadPos = msgs.size - openingBuf.unread
                val anchorMs = if (firstUnreadPos > 0) msgs[firstUnreadPos - 1].timeMs + 1L else 0L
                val anchorTs = java.time.Instant.ofEpochMilli(anchorMs).toString()
                buffers = buffers + (key to openingBuf.copy(lastReadTimestamp = anchorTs))
            }

            val afterOpen = buffers[key]
            if (afterOpen != null && (afterOpen.unread > 0 || afterOpen.highlights > 0)) {
                buffers = buffers + (key to afterOpen.copy(unread = 0, highlights = 0))
            }

            st.copy(buffers = buffers, selectedBuffer = key, screen = AppScreen.CHAT)
        }

        // Send MARKREAD once, after the state update has settled.
        val mrNet = markReadNet; val mrName = markReadName; val mrTs = markReadTs
        if (mrNet != null && mrName != null && mrTs != null) {
            val rt = runtimes[mrNet]
            if (rt != null) {
                viewModelScope.launch { runCatching { rt.client.sendRaw("MARKREAD $mrName timestamp=$mrTs") } }
            }
        }
        // The read marker for the opening buffer is stamped when the user reaches the bottom, not here.
    }

    /**
     * Set [UiBuffer.lastReadTimestamp] to the timestamp of the last message currently in [key],
     * then send MARKREAD to the server if the cap is available.
     * No-op if the buffer has no messages.
     */
    private fun stampReadMarker(key: String) {
        val st = _state.value
        val buf = st.buffers[key] ?: return
        val lastMsg = buf.messages.lastOrNull() ?: return
        // +1ms so the separator check (timeMs > lastReadMs) works even at second granularity.
        val ts = java.time.Instant.ofEpochMilli(lastMsg.timeMs + 1L).toString()
        _state.value = st.copy(buffers = st.buffers + (key to buf.copy(lastReadTimestamp = ts)))
        val (netId, bufferName) = splitKey(key)
        val rt = runtimes[netId] ?: return
        if (rt.client.hasCap("draft/read-marker")) {
            viewModelScope.launch { rt.client.sendRaw("MARKREAD $bufferName timestamp=$ts") }
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
			runCatching { rt.client.sendRaw("NAMES $buf") }
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
     * Sends [text] as a PRIVMSG to [buffer] on [networkId] without switching the active buffer.
     * Used by [NotificationReplyReceiver] for inline notification replies.
     *
     * If the server supports IRCv3 `+reply`/`draft/reply` and [msgId] is known, the reply tag
     * is attached so clients that understand threading show it as a reply.
     *
     * If the server does NOT support reply tags and [buffer] is a channel (not a PM), the
     * message is prefixed with `Nick: (quote) - ` so context isn't lost when replying
     * to an older message from the notification drawer.
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
            val rt = runtimes[networkId] ?: return@launch
            val client = rt.client
            val myNick = _state.value.connections[networkId]?.myNick ?: _state.value.myNick
            val key = bufKey(networkId, buffer)

            // draft/reply is a client-only tag ("+draft/reply")
            // It's permitted whenever message-tags is negotiated. Some servers also
            // whitelist it explicitly via CLIENTTAGDENY=*,-draft/reply but we don't
            // need to parse that. message-tags is the correct gate.
            val hasReplyTagCap = client.hasCap("message-tags")
            val isChannel      = buffer.isNotEmpty() && buffer[0] in "#&+!"

            val outText = when {
                // Server supports draft/reply AND we have a real server msgId: send a
                // reply-tagged message. Route through privmsg() (NOT a direct sendRaw)
                // so the E2E encryption hook applies - a raw send here would ship the
                // reply in cleartext while the local echo still shows a padlock. privmsg
                // also builds a single well-formed tag group (@label=…;+draft/reply=…).
                hasReplyTagCap && msgId != null -> {
                    val sanitised = text.replace("\r", "").replace("\n", " ")
                    client.privmsg(buffer, sanitised, replyToMsgId = msgId)
                    // Record so incoming echo-message is consumed rather than shown twice.
                    // Dedup is content-based (buffer + decrypted text), so recording the
                    // plaintext matches the decrypted echo regardless of wire encryption.
                    recordLocalSend(networkId, key, sanitised, isAction = false)
                    sanitised
                }
                // Channel without reply-tag support: prepend "Nick: (quote..) - reply"
                isChannel && from.isNotBlank() && originalText.isNotBlank() -> {
                    val quote = originalText.take(60).let { if (originalText.length > 60) "$it…" else it }
                    "$from: ($quote) - $text"
                }
                // Channel, no quote available but sender known
                isChannel && from.isNotBlank() -> "$from: $text"
                // PM or no context
                else -> text
            }

            // For all non-tag paths, use privmsg() which handles echo-message + label correctly.
            if (!(hasReplyTagCap && msgId != null)) {
                client.privmsg(buffer, outText)
                recordLocalSend(networkId, key, outText, isAction = false)
            }

            // Pass replyToMsgId so our own local echo shows the reply quote UI,
            // matching what other clients will see when the tagged message arrives.
            // Mirror the wire-level encryption state into the local echo so the lock
            // icon shows up immediately instead of waiting for the echo-message round
            // trip. Look up the per-target key at send-time, NOT at append-time, so a
            // key cleared between send and echo doesn't retroactively mark the local
            // line as cleartext.
            val localEncryption = e2eKeyStore.get(networkId, buffer)?.scheme
            append(key, from = myNick, text = outText, isLocal = true,
                replyToMsgId = if (hasReplyTagCap && msgId != null) msgId else null,
                encryption = localEncryption)
        }
    }
    /**
     * Returns true when there is a live, registered IRC connection for [networkId].
     * Used by [NotificationReplyReceiver] to detect the dead-process restart case where
     * the ViewModel exists but has no active runtime, and suppress silent reply drops.
     */
    fun hasLiveConnection(networkId: String): Boolean =
        runtimes[networkId]?.client?.isConnectedNow() == true

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
        val next = st.copy(activeNetworkId = id)
        _state.value = syncActiveNetworkSummary(next)
        ensureServerBuffer(id)
        viewModelScope.launch(Dispatchers.IO) { runCatching { repo.setLastNetworkId(id) } }
    }

    private fun syncActiveNetworkSummary(st: UiState): UiState {
        val id = st.activeNetworkId ?: return st.copy(connected = false, connecting = false, status = "Disconnected", myNick = "me")
        val conn = st.connections[id] ?: NetConnState()
        return st.copy(connected = conn.connected, connecting = conn.connecting, status = conn.status, myNick = conn.myNick)
    }

    // Network

    
    /**
     * Restore the built-in AfterNET profile (used for support) if the user deleted it.
     * Safe to call multiple times; it no-ops if a profile named/id AfterNET already exists.
     */
    fun addAfterNetDefaults() {
        viewModelScope.launch {
            val st = _state.value
            val exists = st.networks.any { it.id.equals("AfterNET", ignoreCase = true) || it.name.equals("AfterNET", ignoreCase = true) }
            if (exists) return@launch

            val n = NetworkProfile(
                id = "AfterNET",
                name = "AfterNET",
                host = "irc.afternet.org",
                port = 6697,
                useTls = true,
                allowInvalidCerts = true,
                nick = "HexDroidUser",
                altNick = "HexDroidUser_",
                username = "hexdroid",
                realname = "HexDroid IRC for Android",
                saslEnabled = false,
                saslMechanism = SaslMechanism.PLAIN,
                caps = CapPrefs(),
                autoJoin = listOf(AutoJoinChannel("#HexDroid", null))
            )

            repo.upsertNetwork(n)
        }
    }

    /**
     * Update the nick (and altNick) on all default server profiles that still have
     * the factory-default "HexDroidUser" / "HexDroid" nick. Called from the welcome screen
     * so the user's chosen nickname is applied everywhere before they even connect.
     */
    fun updateAllDefaultNetworkNicks(nick: String) {
        viewModelScope.launch {
            val st = _state.value
            val defaultNicks = setOf("HexDroidUser", "HexDroid", "HexDroidUser_")
            for (net in st.networks) {
                if (net.nick in defaultNicks) {
                    val updated = net.copy(
                        nick = nick,
                        altNick = "${nick}_",
                        username = nick.lowercase()
                    )
                    repo.upsertNetwork(updated)
                }
            }
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

            // Passwords/secrets are stored in SecretStore (Android Keystore). Load them for the edit form only.
            val serverPass = repo.secretStore.getServerPassword(id)
            val saslPass = repo.secretStore.getSaslPassword(id)
            val proxyPass = repo.secretStore.getProxyPassword(id)

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
            // clear the auth block.
            connectNetwork(netId, force = true, clearAuthBlock = true)
        }
    }

    fun saveEditingNetwork(profile: NetworkProfile, clientCertDraft: ClientCertDraft?, removeClientCert: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(networkEditError = null)
            // SecretStore writes go through Android Keystore, which can throw
            // IllegalStateException / KeyStoreException when the keystore has been
            // invalidated (e.g. lock-screen change with a binding policy in effect,
            // user fingerprint reset, OEM keystore corruption after an OTA). The
            // encrypt path internally retries once with a regenerated key, but the
            // second-attempt failure propagates. Without a guard here, that throw
            // bubbles up the viewModelScope.launch and crashes the app exactly when
            // the user tapped Save - a particularly bad failure mode because the
            // user thinks they just saved their credentials and instead lost the
            // app session. Catch, surface as an in-screen error on the edit screen,
            // and leave the credential state untouched so the user can try again.
            val secretsResult = runCatching {
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
            }
            if (secretsResult.isFailure) {
                val t = secretsResult.exceptionOrNull()
                _state.value = _state.value.copy(
                    screen = AppScreen.NETWORK_EDIT,
                    editingNetwork = profile,
                    networkEditError = "Failed to save credentials: ${t?.message ?: t?.javaClass?.simpleName ?: "keystore error"}. Try again, or restart the app if the problem persists."
                )
                return@launch
            }

            var updated = profile.copy(
                saslPassword = null,
                serverPassword = null,
                proxyPassword = null,
                tlsClientCertLabel = profile.tlsClientCertLabel
            )

            if (removeClientCert) {
                repo.secretStore.removeClientCert(profile.id, profile.tlsClientCertId)
                updated = updated.copy(tlsClientCertId = null, tlsClientCertLabel = null)
            }

            if (clientCertDraft != null) {
                repo.secretStore.removeClientCert(profile.id, updated.tlsClientCertId)
                val stored = try {
                    repo.secretStore.importClientCert(profile.id, clientCertDraft)
                } catch (t: Throwable) {
                    _state.value = _state.value.copy(
                        screen = AppScreen.NETWORK_EDIT,
                        editingNetwork = profile,
                        networkEditError = t.message ?: "Failed to import certificate"
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
     * Purges all per-network in-memory maps for [netId].
     *
     * Called on disconnect, network deletion, and any hard reset so that:
     * - chanNickCase / chanNickStatus (per-channel nick tracking)
     * - nickAwayState (away status per nick)
     * - pendingSendsByNet (echo-message dedup queue)
     * - pendingCloseAfterPart (channels awaiting close after /part)
     * - receivedTypingExpiryJobs (typing indicator timers)
     * - reconnectAttempts / autoReconnectJobs
     * ...do not accumulate entries for networks that no longer exist.
     */
    private fun cleanupNetworkMaps(netId: String, resetReconnectState: Boolean = false) {
        // Per-channel nick maps
        val chanPrefix = "$netId::"
        chanNickCase.keys.filter   { it.startsWith(chanPrefix) }.forEach { chanNickCase.remove(it) }
        chanNickStatus.keys.filter { it.startsWith(chanPrefix) }.forEach { chanNickStatus.remove(it) }
        // The UI-visible nicklist is derived from the two maps above. Clear it as well, or the
        // channel keeps rendering a stale member list after a disconnect, making it look like
        // you're still joined when you aren't. Normally a rejoin's NAMES reply rebuilds it within
        // a second, so this is invisible; it only became visible when the rejoin itself failed
        // (manually-joined channels, see manuallyJoinedChannels preservation in connectNetworkInternal).
        _state.update { st ->
            val staleKeys = st.nicklists.keys.filter { it.startsWith(chanPrefix) }.toSet()
            if (staleKeys.isEmpty()) st else st.copy(nicklists = st.nicklists - staleKeys)
        }
        // Away state
        nickAwayState.remove(netId)
        // Echo dedup queue
        pendingSendsByNet.remove(netId)
        // NOTE: recentSelfSends is deliberately NOT cleared here. cleanupNetworkMaps runs
        // on every disconnect, including the unexpected-drop path that immediately auto-
        // reconnects - and the whole point of recentSelfSends is to dedup our own messages
        // when the bouncer replays them on that reconnect. Wiping it here would defeat the
        // fix. It self-expires (SELF_SEND_RETAIN_MS) and is per-buffer size-capped, so
        // leaving it across a reconnect is safe and bounded. It IS cleared in
        // deleteNetwork() when the profile is removed entirely.
        // Pending close-after-part
        pendingCloseAfterPart.removeAll(pendingCloseAfterPart.filter { it.startsWith(chanPrefix) }.toSet())
        // Typing expiry jobs: cancel and remove all for this network
        val typingKeys = receivedTypingExpiryJobs.keys.filter { it.startsWith(chanPrefix) }
        typingKeys.forEach { receivedTypingExpiryJobs.remove(it)?.cancel() }
        // Notice-routing recently-joined fallback: drop entries for buffers on this network.
        // The opportunistic eviction in the JOIN handler already prunes by the 5-second
        // window cutoff, but if a user disconnects from a network without joining anything
        // afterwards, those entries linger until the cap eviction fires later. Cleaning
        // here is purely an upper bound on the leak.
        recentJoinAtMs.keys.filter { it.startsWith(chanPrefix) }.toList()
            .forEach { recentJoinAtMs.remove(it) }
        // Same per-network sweep for the chathistory marker armed window.
        chathistoryMarkerArmedUntilMs.keys.filter { it.startsWith(chanPrefix) }.toList()
            .forEach { chathistoryMarkerArmedUntilMs.remove(it) }
        // Flap-detection history (pingTimeoutTimestamps) and the exponential-backoff
        // counter (reconnectAttempts) MUST survive a transient disconnect, because the
        // whole point of both is to accumulate state across a connect>Drop>reconnect
        // cycle:
        //   - pingTimeoutTimestamps lets the flap detector count repeated timeouts and
        //     pause auto-reconnect once FLAP_THRESHOLD is hit within the window.
        //   - reconnectAttempts is the backoff exponent. The design (see the Connected
        //     handler and STABLE_CONNECTION_MS) is that it resets ONLY after a connection
        //     stays up for STABLE_CONNECTION_MS.
        // We only clear them on a PERMANENT teardown (user disconnect, profile delete,
        // orphan-import cleanup) where there is no pending auto-reconnect to preserve them
        if (resetReconnectState) {
            pingTimeoutTimestamps.remove(netId)
            reconnectAttempts.remove(netId)
        }
        autoReconnectJobs.remove(netId)?.cancel()
        stableConnectionJobs.remove(netId)?.cancel()
        noNetworkNotice.remove(netId)
        // The flap-PAUSED set (flapPaused) is likewise NOT cleared here: a network that
        // tripped flap protection must stay paused across reconnect cycles until the user
        // explicitly intervenes. Cleared in reconnectNetwork() on a manual reconnect.

        // Bouncer upstream cache: drop so a reconnect doesn't surface stale "discovered"
        // entries from the previous session. The bouncer re-announces the full list on
        // every reconnect, so rebuilding from scratch is correct and cheap.
        _state.update { st ->
            if (!st.bouncerNetworks.containsKey(netId)) st
            else st.copy(bouncerNetworks = st.bouncerNetworks - netId)
        }
        // NOTE: authBlockedReconnect is deliberately NOT cleared here, cleanupNetworkMaps
        // runs on every disconnect (including the auth-failure-induced disconnect that
        // SET the block), and clearing here would defeat the block entirely. Cleared
        // explicitly in connectNetwork(), reconnectNetwork(), saveEditingNetwork(), and
        // deleteNetwork() instead.
    }

    /**
     * Append a transient connection-status line (disconnect reason, retry notice, auth
     * warning) to the *server* buffer for [netId]. If an identical line was emitted within
     * [CONN_STATUS_DEDUP_WINDOW_MS], the existing message is updated in place with a
     * "(×N)" counter suffix rather than being re-appended; the connection-state status
     * field is updated regardless so the toolbar still reflects the latest situation.
     *
     * Defaults are tuned for routine connectivity blips: no notification, no highlight,
     * so the user's badge counts don't fill up while the network flaps. Callers that
     * surface genuinely-actionable failures override these.
     *
     * When [broadcast] is true AND this call resulted in a fresh append (not a counter
     * bump on an existing line), the same text is also mirrored into every non-server,
     * non-DCC-chat buffer on this network, so a user reading a channel sees "Disconnected"
     * / "Reconnecting in 5s" / "Reconnected" inline.
     *
     * Returns true if a new line was appended, false if it was collapsed into the
     * existing line's counter.
     *
     * Thread-safety: serialised on [connStatusLock] so two concurrent calls (e.g. one
     * from a Default-dispatched reconnect coroutine and one from a Main.immediate event
     * handler) can't both decide they're the first to collapse and end up racing on the
     * message search and counter increment.
     */
    private fun appendConnStatus(
        netId: String,
        text: String,
        isHighlight: Boolean = false,
        doNotify: Boolean = false,
        from: String? = null,
        broadcast: Boolean = false,
        isError: Boolean = false,
    ): Boolean {
        val appendedFresh: Boolean = synchronized(connStatusLock) {
            val now = System.currentTimeMillis()
            val key = "${from ?: ""}|$text"
            val bufferKey = bufKey(netId, "*server*")
            val prev = lastConnStatusLine[netId]

            if (prev != null && prev.key == key && now - prev.lastSeenMs < CONN_STATUS_DEDUP_WINDOW_MS) {
                // Same line, same network, still within window — try to bump the existing
                // message's counter rather than appending a new one.
                val newCount = prev.count + 1
                val newText = "${prev.baseText} (×$newCount)"
                // What the buffered message currently reads. First repeat: it's just the
                // baseText (no suffix yet). Subsequent repeats: "(×N)" where N = prev.count.
                val expectedText = if (prev.count == 1) prev.baseText
                else "${prev.baseText} (×${prev.count})"
                    var collapsed = false
                    _state.update { st ->
                        // Reset on every CAS retry so a failed retry doesn't leave `collapsed`
                        // stuck at true from a previous attempt that didn't make it into state.
                        collapsed = false
                        val buf = st.buffers[bufferKey] ?: return@update st
                        // Connection-status lines are recent by construction, so search only the
                        // last few messages (bounded scan, ignores the full scrollback.) Matching
                        // on (from, text) is enough here: the count suffix differs between calls
                        // but expectedText reflects what the message reads RIGHT NOW.
                        val tailStart = (buf.messages.size - 8).coerceAtLeast(0)
                        val tail = buf.messages.subList(tailStart, buf.messages.size)
                        val idxInTail = tail.indexOfLast { it.from == from && it.text == expectedText }
                        if (idxInTail < 0) return@update st
                            val realIdx = tailStart + idxInTail
                            val updated = buf.messages[realIdx].copy(text = newText)
                            val newMessages = buf.messages.toMutableList().also { it[realIdx] = updated }
                            collapsed = true
                            st.copy(buffers = st.buffers + (bufferKey to buf.copy(messages = newMessages)))
                    }
                    if (collapsed) {
                        lastConnStatusLine[netId] = prev.copy(count = newCount, lastSeenMs = now)
                        return@synchronized false
                    }
                    // Else: original message has been trimmed out of scrollback (the buffer rolled
                    // past it). Fall through and append fresh as a new line.
            }

            append(bufferKey, from = from, text = text, isHighlight = isHighlight, doNotify = doNotify, isError = isError)
            lastConnStatusLine[netId] = ConnStatusDedupEntry(
                key = key,
                baseText = text,
                from = from,
                lastSeenMs = now,
                count = 1,
                bufferKey = bufferKey,
            )
            true
        }

        if (broadcast && appendedFresh) {
            broadcastConnStatusToOtherBuffers(netId, text, from)
        }

        return appendedFresh
    }

    /**
     * Mirror a connection-status line into every channel and query buffer on [netId] so
     * the user sees state changes inline regardless of which buffer they're reading. The
     * *server* buffer already has the canonical copy (with dedup-counter handling); this
     * writes the secondary copies for visibility only.
     *
     * Exclusions:
     *  - The *server* buffer itself (already has the canonical copy).
     *  - DCC chat buffers
     *  - Buffers on other networks
     *
     * Cross-posts are written with `isLocal = true` (no unread/highlight increment — a
     * network-wide event shouldn't bump the badge on every channel) and `doNotify = false`
     * (the *server*-buffer copy already ran the notification policy for this event;
     * we mustn't double-fire).
     *
     * Disk-logging is left enabled: each channel log gets its own "*** Disconnected" /
     * "*** Reconnected" entry at the matching timestamp, which gives a clear visual
     * break for anyone reviewing logs later.
     */
    private fun broadcastConnStatusToOtherBuffers(netId: String, text: String, from: String?) {
        val keys = _state.value.buffers.keys.filter { key ->
            val (kNetId, kBufName) = splitKey(key)
            kNetId == netId && kBufName != "*server*" && !isDccChatBufferName(kBufName)
        }
        for (key in keys) {
            append(
                bufferKey = key,
                from = from,
                text = text,
                isLocal = true,
                doNotify = false,
            )
        }
    }

    /**
     * Forget the dedup tracker for [netId] so subsequent connection-status lines pass
     * unconditionally. Called after a successful registration: the user has been on a
     * working connection long enough that a fresh disconnect deserves a fresh log line,
     * even if the new reason happens to match the last one we saw an hour ago.
     */
    private fun resetConnStatusDedup(netId: String) {
        lastConnStatusLine.remove(netId)
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
            // Drop self-send replay-dedup signatures for this network's buffers.
            val pfx = "$id::"
            recentSelfSends.keys.filter { it.startsWith(pfx) }.forEach { recentSelfSends.remove(it) }
        }
        disconnectNetwork(id)
        cleanupNetworkMaps(id, resetReconnectState = true)
        // Profile is gone; drop any auth-failure block that pinned it. (cleanupNetworkMaps
        // doesn't touch authBlockedReconnect, see the note there.)
        authBlockedReconnect.remove(id)
        // Profile is gone for good; drop its session-registration marker too. (Unlike a
        // disconnect, a delete means there's no reconnect that should still count as one.)
        everRegisteredThisSession.remove(id)
        // Purge orphan state for the deleted network: buffers, the connection record, and
        // the per-buffer chathistory marker tracker. Without this, buffer messages and
        // associated state stay in memory until the process is killed - a real leak in
        // long-running sessions where the user is provisioning/deprovisioning networks.
        // (cleanupNetworkMaps clears per-channel maps but deliberately doesn't touch
        // _state, since most of its callers want to keep buffer history across reconnects.)
        val prefix = "$id::"
        _state.update { st ->
            val orphanKeys = st.buffers.keys.filter { it.startsWith(prefix) }
            for (k in orphanKeys) pendingChathistoryMarkerMs.remove(k)
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
        _state.update { it.copy(backupMessage = null) }
    }

    /**
     * Write a backup of current settings and networks to [uri] (obtained from
     * ACTION_CREATE_DOCUMENT).  The URI must be writable.
     *
     * Passwords and TLS client certificates are excluded - they are tied to device-specific
     * Android Keystore keys and cannot be transferred.
     */
    fun exportBackup(uri: android.net.Uri) {
        val st = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val json = repo.exportBackupJson(st.networks, st.settings)
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: throw java.io.IOException("Unable to open output stream for backup file")
                "Backup saved successfully.\nNote: passwords and certificates are not included."
            }
            val msg = result.getOrElse { e -> "Backup failed: ${e.message}" }
            _state.update { it.copy(backupMessage = msg) }
        }
    }

    /**
     * Read a backup file from [uri] (obtained from ACTION_OPEN_DOCUMENT) and restore
     * settings and networks.  Existing networks are replaced.
     *
     * On success, UI settings take effect on next DataStore emission.
     * Passwords are not restored and will need to be re-entered.
     */
    fun importBackup(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val json = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?.toString(Charsets.UTF_8)
                    ?: throw java.io.IOException("Unable to read backup file")
                val orphanedIds = repo.importBackup(json)
                // Clear encrypted secrets for profiles that existed locally before the restore
                // but are absent from the imported backup. Without this, SASL passwords / server
                // passwords / TLS client certs for deleted profiles linger in SecretStore
                // indefinitely - a data-hygiene issue rather than an active security bug, but
                // worth closing because the material is encrypted credential data.
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
                // Disconnect any live runtimes for profiles that were deleted by the import.
                // Without this, the IrcClient keeps running against the now-orphan profile,
                // would auto-reconnect with stale credentials on next disconnect, and pollutes
                // the connection notification with a network the user can no longer see in the
                // sidebar. Switch to the Main dispatcher because disconnectNetwork mutates state.
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
                            _state.update { st ->
                                val orphanKeys = st.buffers.keys.filter { it.startsWith(prefix) }
                                for (k in orphanKeys) pendingChathistoryMarkerMs.remove(k)
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
                "Backup restored successfully.\nPasswords were not restored - please re-enter them in each network's settings."
            }
            val msg = result.getOrElse { e -> "Restore failed: ${e.message}" }
            _state.update { it.copy(backupMessage = msg) }
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

    /**
     * Ask a bouncer to (re-)send its current upstream-network list. Per-kind dispatch:
     *
     *  - SOJU: sends `BOUNCER LISTNETWORKS`. The bouncer replies with one
     *    `BOUNCER NETWORK <id> <attrs>` per known upstream, terminated by
     *    `BOUNCER NETWORK *`. Feeds the existing structured handler. Soju emits explicit
     *    `BOUNCER NETWORK <id> *` deletion sentinels so the cache stays consistent
     *    without explicit eviction.
     *  - ZNC: sends `PRIVMSG *status :ListNetworks`. The reply is a free-text NOTICE
     *    table that is scraped opportunistically by [parseZncListNetworksLine] and
     *    surfaced as the same `BouncerNetwork` events soju produces — so the UI and
     *    cache code paths are shared. Because ZNC has NO deletion sentinel (a removed
     *    network simply doesn't appear in the next ListNetworks output), we wipe the
     *    cache for this profile before issuing the command so the rebuild is authoritative.
     *    A brief UI flicker is the cost; permanently-stale entries are the alternative.
     *  - GENERIC / NONE: no-op. Generic bouncers have no standardised list command.
     *
     * Idempotent: re-receiving the same upstream attrs produces no visible change in
     * the cache (the merge logic is value-based) so we don't need a per-request marker.
     */
    fun refreshBouncerNetworks(parentNetId: String) {
        val rt = runtimes[parentNetId] ?: return
        val profile = _state.value.networks.firstOrNull { it.id == parentNetId } ?: return
        val cmd = when (profile.bouncerKind) {
            BouncerKind.SOJU -> {
                // Wipe the cache for this profile so the LISTNETWORKS response is
                // authoritative. soju's `BOUNCER NETWORK <id> *` delete frames are only
                // sent for networks the bouncer believes our session knows about; a
                // network removed via `sojuctl` while this client was offline is dropped
                // from soju's state without a delete frame ever being delivered to us.
                // Without the pre-wipe the stale entry lingers in our UI even after the
                // user explicitly hits Refresh - and worse, an Import button click on a
                // stale entry would create a profile pointing at a network the bouncer
                // no longer routes. ZNC takes the same approach for the same reason.
                _state.update { st ->
                    if (!st.bouncerNetworks.containsKey(parentNetId)) st
                    else st.copy(bouncerNetworks = st.bouncerNetworks + (parentNetId to emptyMap()))
                }
                "BOUNCER LISTNETWORKS"
            }
            BouncerKind.ZNC -> {
                // Wipe the cache for this profile so the table-scrape rebuild is
                // authoritative. Without this, a network removed via ZNC's `DelNetwork`
                // would linger in our UI forever because no row referencing it would
                // arrive in the new reply.
                _state.update { st ->
                    if (!st.bouncerNetworks.containsKey(parentNetId)) st
                    else st.copy(bouncerNetworks = st.bouncerNetworks + (parentNetId to emptyMap()))
                }
                "PRIVMSG *status :ListNetworks"
            }
            BouncerKind.GENERIC, BouncerKind.NONE -> return  // no equivalent command
        }
        viewModelScope.launch { runCatching { rt.client.sendRaw(cmd) } }
    }

    /**
     * "Discover-and-clone" import: take a bouncer-reported upstream and create a local
     * NetworkProfile for it that copies the bouncer connection details (host, port, TLS,
     * SASL) from [parentNetId] but binds to the discovered upstream via [bouncerNetworkName].
     *
     * Why clone the parent rather than ask the user to fill in a fresh form: the bouncer-
     * facing connection details (host/port/TLS/credentials) are identical for every upstream
     * served by the same bouncer instance. only the per-upstream `bouncerNetworkName`
     * differs. Re-typing those is the painful part of bouncer onboarding and the entire
     * reason the discover-and-clone flow exists.
     *
     * The clone inherits the **parent's bouncerKind** so the SASL authcid / USER suffix is
     * composed with the right syntax (soju's `user/network[@cid]` vs ZNC's
     * `user[@cid]/network`). Parent kinds other than SOJU/ZNC are rejected, generic
     * bouncers don't expose a discoverable upstream list and there's nothing meaningful
     * to clone from.
     *
     * The new profile:
     *  - inherits everything bouncer-side (host, port, useTls, allowInvalidCerts, nick,
     *    username, realname, SASL config + secrets, server password + secret, caps)
     *  - inherits the parent's bouncerKind (so SOJU stays SOJU, ZNC stays ZNC)
     *  - overrides bouncerNetworkName = [bouncerNetworkName]
     *  - clears clientId (per-client buffers are device-local, copying it would create
     *    two profiles fighting over the same client buffer slot on the bouncer)
     *  - clears autoJoin (the upstream may have its own server-side autojoin list and
     *    the user usually wants to opt in to autoConnect deliberately on a fresh profile)
     *  - autoConnect = false; user enables explicitly
     *  - new UUID id; sortOrder placed at the end of the list
     *
     * On success, [bouncerCloneMessage] is set so the screen can surface a brief toast.
     * Idempotency: if a profile already exists with the same bouncerKind+host+port
     * targeting this upstream name, no clone is created and the existing profile is
     * surfaced via the message instead.
     */
    fun cloneBouncerNetwork(parentNetId: String, bouncerNetworkName: String) {
        viewModelScope.launch {
            val st = _state.value
            val parent = st.networks.firstOrNull { it.id == parentNetId } ?: run {
                _state.update { it.copy(bouncerCloneMessage = "Parent profile not found.") }
                return@launch
            }
            if (parent.bouncerKind != BouncerKind.SOJU && parent.bouncerKind != BouncerKind.ZNC) {
                // Defensive: the UI only shows the section for SOJU/ZNC, but the action could
                // be invoked through other paths (deep links, future automation). Reject so
                // the resulting clone can't end up with a misconfigured authcid syntax.
                _state.update { it.copy(bouncerCloneMessage = "Discover-and-clone is only available for soju and ZNC.") }
                return@launch
            }
            val targetName = bouncerNetworkName.trim()
            if (targetName.isEmpty()) {
                _state.update { it.copy(bouncerCloneMessage = "Bouncer network has no name yet. try refresh.") }
                return@launch
            }

            // Idempotency: scope by parent host+port AND bouncerKind so two bouncers of
            // different kinds that happen to expose a network of the same name don't collide,
            // and so a soju profile and a ZNC profile pointing at "libera" on the same host
            // (e.g. during a migration) are treated as distinct imports.
            val existing = st.networks.firstOrNull {
                it.bouncerKind == parent.bouncerKind &&
                    it.bouncerNetworkName.equals(targetName, ignoreCase = true) &&
                    it.host.equals(parent.host, ignoreCase = true) &&
                    it.port == parent.port
            }
            if (existing != null) {
                _state.update { it.copy(bouncerCloneMessage = "Already imported as \"${existing.name}\".") }
                return@launch
            }

            // Pull credentials out of SecretStore so the clone gets a working copy. Without
            // this the new profile would silently fall back to plaintext PASS / abort SASL
            // on first connect.
            val parentServerPass = runCatching { repo.secretStore.getServerPassword(parentNetId) }.getOrNull()
            val parentSaslPass = runCatching { repo.secretStore.getSaslPassword(parentNetId) }.getOrNull()
            val parentProxyPass = runCatching { repo.secretStore.getProxyPassword(parentNetId) }.getOrNull()

            val newId = "net_" + java.util.UUID.randomUUID().toString().replace("-", "")
            val maxSort = st.networks.maxOfOrNull { it.sortOrder } ?: -1

            // Prefer SASL for the cloned profile.
            //
            // Why: SASL PLAIN is the modern, structured auth path supported by all current
            // soju and ZNC versions; the PASS line is a legacy fallback that requires the
            // user to know the exact format their bouncer wants (and the format differs
            // across bouncers). When the parent already has SASL enabled, inherit it.
            // When the parent has no SASL but does have a server password, opportunistically
            // upgrade the clone to SASL using the same credential, this is the path users
            // almost always want when migrating a PASS-only setup to per-network profiles.
            //
            // SASL authcid format follows effectiveAuthIdentity (e.g. "eck/afternet" for
            // soju, "eck@hexdroid/afternet" for ZNC), so inheriting `username` from the parent
            // and adding bouncerNetworkName=targetName produces the right wire format
            // automatically.
            //
            // If the parent has neither SASL nor a server password, we leave SASL off. the
            // user clearly hasn't set credentials yet and the clone shouldn't pretend to
            // have them.
            val parentHasSasl = parent.saslEnabled && !parentSaslPass.isNullOrEmpty()
            // Refuse the PASS->SASL auto-upgrade if the parent's serverPassword looks like
            // a hand-formatted bouncer PASS line (e.g. "alice/libera:secret" — same shape
            // effectivePassLine produces). SASL PLAIN sends the password verbatim in the
            // wire frame, so copying a colon-delimited PASS string into the SASL slot would
            // try to authenticate with the password literally being "alice/libera:secret"
            // and the bouncer would reject it. Detection mirrors effectivePassLine's own
            // hand-assembly heuristic: a `/` before the first `:` is unambiguous because
            // no real IRC username contains `/`, and `@` isn't used as a hint because it
            // appears in real passwords routinely.
            val parentPassLooksHandFormatted = parentServerPass?.let { pw ->
                val firstColon = pw.indexOf(':')
                firstColon > 0 && pw.substring(0, firstColon).contains('/')
            } ?: false
            val parentHasUsablePassForSasl = !parentServerPass.isNullOrEmpty() && !parentPassLooksHandFormatted
            val cloneShouldUseSasl = parentHasSasl || parentHasUsablePassForSasl
            val cloneSaslPassword = when {
                parentHasSasl -> parentSaslPass
                parentHasUsablePassForSasl -> parentServerPass
                else -> null
            }
            // Mechanism choice for the clone:
            //  - parent already had SASL enabled -> inherit verbatim (user picked it knowingly).
            //  - parent was PASS-only -> force PLAIN. The SCRAM-* mechanisms negotiate a salted
            //    challenge-response specific to how the bouncer stored the password, and a
            //    server-PASS string almost certainly wasn't stored that way; trying SCRAM
            //    with a PASS credential produces a 904 SASL fail. Worse, parent.saslMechanism
            //    may be set to SCRAM as a leftover from a previous experiment that the user
            //    abandoned by disabling SASL. copying it blindly would mean the clone
            //    silently uses a mechanism the credential can't satisfy.
            val cloneSaslMechanism = if (parentHasSasl) parent.saslMechanism else SaslMechanism.PLAIN

            val clone = parent.copy(
                id = newId,
                name = "${parent.name} – $targetName",
                isBouncer = true,
                // bouncerKind inherited from parent.copy() - soju stays soju, ZNC stays ZNC.
                bouncerNetworkName = targetName,
                bouncerClientId = null,
                autoJoin = emptyList(),
                autoConnect = false,
                isFavourite = false,
                sortOrder = maxSort + 1,
                // Promote to SASL when we have a credential to put there. saslAuthcid is
                // copied from the parent only when the parent ALREADY had SASL enabled.
                // For an auto-upgrade (parent was PASS-only) we deliberately clear it so
                // effectiveAuthIdentity falls back to `username` and re-suffixes it with
                // the *cloned* bouncerNetworkName. Without this clear, a stale legacy value
                // like "alice/libera" on the parent would short-circuit effectiveAuthIdentity
                // (which leaves identities containing '/' untouched) and the clone would
                // silently SASL as the OLD network name instead of `targetName`.
                saslEnabled = cloneShouldUseSasl,
                saslMechanism = cloneSaslMechanism,
                saslAuthcid = if (parentHasSasl) parent.saslAuthcid else null,
                // tlsTofuFingerprint carries over (same bouncer host, same cert).
                // Don't carry serverPassword / saslPassword through the JSON profile; those
                // live in SecretStore and are written below.
                serverPassword = null,
                saslPassword = null,
                // proxy* non-secret fields carry over via copy(); the proxy password lives
                // in SecretStore and is re-written below for the clone's own id.
                proxyPassword = null,
            )
            repo.upsertNetwork(clone)
            // Carry the proxy password over to the clone (same proxy host/port inherited via
            // copy()), so a proxied parent produces a working proxied clone.
            if (clone.proxyType != com.boxlabs.hexdroid.connection.ProxyType.NONE && !parentProxyPass.isNullOrEmpty()) {
                runCatching { repo.secretStore.setProxyPassword(newId, parentProxyPass) }
            }
            // Write the chosen SASL secret (if any). Always leave server password empty on
            // the clone when SASL is the active auth path, having a stray serverPassword
            // around could trip the bouncer's "two auth attempts" warning.
            if (cloneShouldUseSasl && !cloneSaslPassword.isNullOrEmpty()) {
                runCatching { repo.secretStore.setSaslPassword(newId, cloneSaslPassword) }
            } else if (!parentServerPass.isNullOrEmpty()) {
                // SASL upgrade declined (no SASL on parent and the parent's PASS looks
                // hand-formatted, OR no usable credential at all). Fall back to copying
                // the server PASS verbatim. The user wanted credentials migrated; if the
                // PASS-line format is wrong for the new profile they can edit the clone
                // to fix it manually.
                runCatching { repo.secretStore.setServerPassword(newId, parentServerPass) }
            }
            val resultHint = when {
                parentHasSasl -> "Imported \"$targetName\" (enable auto-connect to use it.)"
                parentHasUsablePassForSasl -> "Imported \"$targetName\" with SASL PLAIN auth from server password. Enable auto-connect to use it."
                parentPassLooksHandFormatted -> "Imported \"$targetName\". Parent password looks pre-formatted for the bouncer; review the clone's auth settings before enabling auto-connect."
                else -> "Imported \"$targetName\". Set credentials and auto-connect on the new profile to use it."
            }
            _state.update { it.copy(bouncerCloneMessage = resultHint) }
        }
    }

    /** Clear the transient bouncer-clone status message after the UI has consumed it. */
    fun clearBouncerCloneMessage() {
        _state.update { it.copy(bouncerCloneMessage = null) }
    }

    // -------------------------------------------------------------------------
    // E2E encryption: public API used by the EncryptionDialog and the compose-
    // input lock badge. All operations are sync (cheap memory + SharedPreferences
    // backed by SecretStore) so they can be called directly from Compose event
    // handlers without launching a coroutine.
    //
    // The caller (UI) carries the responsibility for passing a sensible target -
    // typically the active channel name or query nick. We lowercase internally
    // through E2eKeyStore so casing inconsistency between callers (e.g. UI typed
    // "#Foo" vs server-cased "#foo") still hits the same key.
    // -------------------------------------------------------------------------

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
        if (cleaned.isEmpty()) return E2eImportResult.Failure("Key is empty")
        val keyBytes = try {
            android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return E2eImportResult.Failure("Not a valid base64 string")
        }
        if (keyBytes.size != 32) {
            return E2eImportResult.Failure("Expected 32 bytes after base64 decode, got ${keyBytes.size}")
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
            E2eImportResult.Failure("Failed to store key: ${t.message ?: t::class.java.simpleName}")
        }
    }

    fun clearE2eKeyForTarget(networkId: String, target: String) {
        e2eKeyStore.clear(networkId, target)
        bumpE2eKeyVersion()
    }

    /**
     * Set a Blowfish key for [target] from a passphrase. The passphrase bytes are
     * used directly as the Blowfish key, matching HexChat fishlim's behaviour
     * exactly so a passphrase shared between the two clients produces the same
     * key on both sides without any client-specific salt/KDF.
     *
     * Returns Success with the new key info, or Failure with a human-readable
     * reason (passphrase too short / too long / Blowfish init failed).
     *
     * Note that "passphrase too short" is a UX-level guard, not a security one
     * the cipher itself accepts 4+ bytes, but the dialog warns users when the
     * passphrase has fewer than 8 characters because anything shorter is
     * trivially brute-forceable. The actual key cap is 56 bytes (Blowfish's
     * theoretical maximum); longer passphrases are truncated by some fishlim
     * implementations and not others, so we reject rather than silently
     * truncate.
     */
    fun setE2eBlowfishPassphrase(networkId: String, target: String, passphrase: String): E2eImportResult {
        val raw = passphrase.toByteArray(Charsets.UTF_8)
        if (raw.isEmpty()) return E2eImportResult.Failure("Passphrase is empty")
        if (raw.size < 4) return E2eImportResult.Failure("Passphrase must be at least 4 bytes")
        if (raw.size > 56) return E2eImportResult.Failure("Passphrase must be at most 56 bytes (got ${raw.size})")
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
            E2eImportResult.Failure("Failed to store key: ${t.message ?: t::class.java.simpleName}")
        }
    }

    /**
     * Trivial recomposition trigger for the compose-input lock badge: bumped on
     * every key set/clear so UI observers re-read getE2eKeyInfo from a stable
     * snapshot. State-flow-driven so we don't need to plumb a separate event
     * bus or expose the keystore directly to compose.
     */
    private fun bumpE2eKeyVersion() {
        _state.update { it.copy(e2eKeyVersion = it.e2eKeyVersion + 1) }
    }

    /**
     * Connect (or re-connect) a network.
     *
     * @param clearAuthBlock  Pass `true` only for user-initiated retries (manual button
     *     tap, profile save, "retry after granting permission" flows, "allow plaintext
     *     and connect" flow). The auth-failure block is then cleared so the scheduled
     *     reconnect (which scheduleAutoReconnect would otherwise short-circuit on) can
     *     run again - the user implicitly opted into one more attempt by reaching this
     *     path. Pass `false` for automated callers (autoconnect on app start, restore-
     *     desired-connections after process resume, the ConnectivityManager onAvailable
     *     callback). Those paths must NOT clear the block, otherwise an auth-rejected
     *     network will retry the same wrong credentials every time the WiFi reconnects
     *     or the cell switches over - which is the "auto-reconnect halted but retries
     *     again in 10 minutes" bug from 1.6.2: connectNetwork was unconditionally
     *     clearing authBlockedReconnect, and the network callback fires roughly every
     *     Doze cycle.
     */
    fun connectNetwork(netId: String, force: Boolean = false, clearAuthBlock: Boolean = false) {
        if (clearAuthBlock) authBlockedReconnect.remove(netId)
        viewModelScope.launch {
            // Ensure flap state is loaded from DataStore before checking it.
            // In the normal case this is a no-op (init already loaded it); this guards
            // the race where a connect is requested before the init coroutine completes.
            ensureFlapPausedLoaded()
            withNetLock(netId) {
                // Same gate as scheduleAutoReconnect's entry: if the network is auth-
                // blocked and the caller didn't explicitly clear the block (i.e. this
                // is an automated path), refuse to connect and leave the existing
                // "Auth failed - reconnect halted" status in place. Without this gate,
                // the connect would proceed, re-trip the same SASL/PASS rejection, and
                // the user would see a flood of repeat auth-failure lines whenever
                // network connectivity flapped.
                if (!clearAuthBlock && netId in authBlockedReconnect) return@withNetLock
                connectNetworkInternal(netId, force)
            }
        }
    }

    private fun connectNetworkInternal(netId: String, force: Boolean = false) {
        val st = _state.value
        val conn = st.connections[netId]
        if (!force && (conn?.connected == true || conn?.connecting == true)) return

        val profilePre = st.networks.firstOrNull { it.id == netId }
        if (profilePre != null && !profilePre.useTls && !profilePre.allowInsecurePlaintext) {
            val removedDesired = desiredConnected.remove(netId)
            if (removedDesired) persistDesiredNetworkIds()
            manualDisconnecting.remove(netId)
            autoReconnectJobs.remove(netId)?.cancel()
            setNetConn(netId) { it.copy(connected = false, connecting = false, status = "Plaintext disabled") }
            if (_state.value.activeNetworkId == netId) clearConnectionNotification()
            _state.value = _state.value.copy(plaintextWarningNetworkId = netId)
            return
        }

        // Android 17+: connecting to a local IP requires ACCESS_LOCAL_NETWORK at runtime.
        if (profilePre != null && isLocalHost(profilePre.host) && !hasLocalNetworkPermission()) {
            val removedDesired2 = desiredConnected.remove(netId)
            if (removedDesired2) persistDesiredNetworkIds()
            manualDisconnecting.remove(netId)
            autoReconnectJobs.remove(netId)?.cancel()
            setNetConn(netId) { it.copy(connected = false, connecting = false, status = "Local network permission required") }
            if (_state.value.activeNetworkId == netId) clearConnectionNotification()
            _state.value = _state.value.copy(localNetworkWarningNetworkId = netId)
            return
        }

        val addedDesired = desiredConnected.add(netId)
        if (addedDesired) persistDesiredNetworkIds()
        manualDisconnecting.remove(netId)
        autoReconnectJobs.remove(netId)?.cancel()

        val existing = runtimes.remove(netId)
        // Channels the user joined manually (i.e. not in the profile's autoJoin list) are
        // tracked on the NetRuntime. A reconnect discards the old runtime and builds a fresh
        // one below, so without carrying this map over it would start empty and those channels
        // would never be rejoined.
        val carriedManualJoins = existing?.manuallyJoinedChannels?.toMap().orEmpty()
        if (existing != null) {
            runCatching { existing.client.forceClose("Reconnecting") }
            runCatching { existing.job?.cancel() }
        }

        val profile = profilePre ?: st.networks.firstOrNull { it.id == netId }
        if (profile == null) {
            val removedDesired = desiredConnected.remove(netId)
            if (removedDesired) persistDesiredNetworkIds()
            manualDisconnecting.remove(netId)
            autoReconnectJobs.remove(netId)?.cancel()
            setNetConn(netId) { it.copy(connected = false, connecting = false, status = "Not configured") }
            if (_state.value.activeNetworkId == netId) clearConnectionNotification()
            return
        }
        val saslPasswordResult = repo.secretStore.getSaslPasswordResult(profile.id)
        val saslPassword = when (saslPasswordResult) {
            is com.boxlabs.hexdroid.data.SecretStore.SecretResult.Value -> saslPasswordResult.secret
            is com.boxlabs.hexdroid.data.SecretStore.SecretResult.KeystoreInvalidated -> {
                // Keystore key was invalidated (biometric change, factory reset of
                // Keystore, etc.). The stored SASL password has been cleared.
                ensureServerBuffer(netId)
                appendConnStatus(
                    netId = netId,
                    text = "*** ⚠ SASL credentials unavailable. the Android Keystore key was " +
                        "invalidated (this can happen after a biometric or screen-lock change). " +
                        "Connecting without SASL; please re-enter your SASL password in Network " +
                        "Settings to restore services authentication.",
                    from = null,
                    isHighlight = false,
                    doNotify = false,
                )
                null
            }
            is com.boxlabs.hexdroid.data.SecretStore.SecretResult.NotSet -> null
        }
        val serverPassword = repo.secretStore.getServerPassword(profile.id)
        val proxyPassword = repo.secretStore.getProxyPassword(profile.id)
        val tlsCert = repo.secretStore.loadTlsClientCert(profile.id, profile.tlsClientCertId)
        val cfg = profile.toIrcConfig(
                        saslPasswordOverride = saslPassword,
                        serverPasswordOverride = serverPassword,
                        proxyPasswordOverride = proxyPassword,
                        tlsClientCert = tlsCert
                    ).copy(
                        historyLimit = st.settings.ircHistoryLimit
                    )

        ensureServerBuffer(netId)

        val serverKey = bufKey(netId, "*server*")

        // If there's no active network with Internet capability, don't attempt to connect (it will just fail and spam).
        if (!hasInternetConnection()) {
            if (!noNetworkNotice.contains(netId)) {
                noNetworkNotice.add(netId)
                append(serverKey, from = null, text = "*** Please turn on WiFi or Mobile data to auto-reconnect.", doNotify = false)
            }
            setNetConn(netId) { it.copy(connected = false, connecting = false, status = "Waiting for network…", myNick = cfg.nick) }
            if (_state.value.activeNetworkId == netId) updateConnectionNotification("Waiting for network…")
            if (_state.value.settings.autoReconnectEnabled) scheduleAutoReconnect(netId)
            return
        } else {
            noNetworkNotice.remove(netId)
        }

        val preservedListModes = conn?.listModes ?: NetConnState().listModes
        val newConns = st.connections + (netId to NetConnState(
            connected = false,
            connecting = true,
            status = "Connecting…",
            myNick = cfg.nick,
            listModes = preservedListModes
        ))
        // Focus handling: a fresh user-initiated connect from the Networks list should land
        // on the chat screen for the new connection. But every subsequent reconnect attempt
        // (auto-reconnect after a drop, manual retry from the Reconnect button while already
        // on a channel, etc.) should stay on the same buffer they were reading.
        //
        //   * Only force `screen = AppScreen.CHAT` when the user was on the Networks list,
        //     which is the typical first-connect entry point. Settings / Transfers / Edit
        //     screens are off-limits, the user is doing something deliberate there.
        //   * Only force `selectedBuffer` when the user has no buffer selected at all. If
        //     they're already focused on a buffer (channel, query, or this network's own
        //     server buffer), leave that selection alone.
        val curScreen = st.screen
        val nextScreen = if (curScreen == AppScreen.NETWORKS) AppScreen.CHAT else curScreen
        val nextSelectedBuffer = if (st.selectedBuffer.isBlank()) serverKey else st.selectedBuffer
        _state.value = syncActiveNetworkSummary(
            st.copy(
                connections = newConns,
                screen = nextScreen,
                selectedBuffer = nextSelectedBuffer
            )
        )

        val client = IrcClient(cfg)
        // Attach the E2E codec for this network. The codec wraps the per-process
        // shared keystore (which lazily hydrates from SecretStore) and is held by
        // the IrcClient for its lifetime - reconnecting the same network reuses
        // the same client instance, so existing per-target keys carry over. A
        // network with no keys configured pays only the per-message null-check
        // since encryptOutgoing/decryptIncoming short-circuit on an empty cache.
        client.e2eCodec = com.boxlabs.hexdroid.crypto.E2eCodec(netId, e2eKeyStore)
        val thisClient = client
        val rt = NetRuntime(netId = netId, client = client, myNick = cfg.nick, suppressMotd = _state.value.settings.hideMotdOnConnect)
        // Restore manually-joined channels so they're rejoined after a reconnect (see the
        // capture of carriedManualJoins above). Empty on a first connect.
        rt.manuallyJoinedChannels.putAll(carriedManualJoins)
        runtimes[netId] = rt

        if (st.activeNetworkId == netId) updateConnectionNotification("Connecting…")

        rt.job?.cancel()
        rt.job = viewModelScope.launch(Dispatchers.IO) {
            // Hold a scoped WakeLock for the connect/TLS handshake burst, then release it.
            // The foreground service keeps the process alive; the lock just covers the CPU-
            // intensive initial handshake so Android can't suspend us mid-handshake.
            // Pass netId so concurrent multi-network connects each get their own lock.
            KeepAliveService.acquireScopedWakeLock(appContext, netId)
            try {
                // Outer try around the flow collection itself, not just the per-event
                // handler. The events() flow can throw mid-stream for several reasons:
                //   - writeLine() called from the inline registration sequence (PASS / CAP
                //     LS / NICK / USER) can raise IOException if the socket dropped between
                //     openSocket and the first write (rare; fast network blips, server-side
                //     instant-flood disconnects, midline TLS aborts on some bouncers).
                //   - EncodingHelper.encode() can throw on degenerate input encodings.
                //   - The IrcSession state machine reaches an assertion only triggered by
                //     a very specific server-side malformed CAP / SASL sequence.
                // Without this outer try, any such throw propagates out of collect, kills
                // this viewModelScope.launch coroutine, and (because viewModelScope's default
                // uncaught-exception handler re-throws on Android) crashes the whole process.
                // The user-visible symptom is "app got to Negotiating capabilities, then
                // crashed and closed" - exactly because the throw lands between sending the
                // "Negotiating capabilities..." status and the first user-visible event of
                // the new connection.
                //
                // Containment policy:
                //   - CancellationException is re-thrown so coroutine cancellation still
                //     works (this is what releases the wakelock via the finally block, and
                //     what lets manual disconnect / reconnect cycles tear down cleanly).
                //   - Any other Throwable is logged, surfaced in the server buffer, and
                //     translated to a Disconnected handler invocation so the connection
                //     state UI reaches a coherent terminal state (status pill, reconnect
                //     scheduling, etc.) instead of being stuck at "Connecting".
                try {
                    client.events()
                        // Decouple the socket read loop from Main-thread event handling. Without
                        // this, the channelFlow's small default buffer (64) fills whenever Main
                        // lags, the producer's send() suspends, and the socket stops being
                        // drained, which makes us a "slow reader".
                        .buffer(capacity = EVENT_DRAIN_BUFFER_CAPACITY)
                        .collect { ev ->
                        // Hop to Main.immediate before touching state. The dozens of
                        //
                        //     val st = _state.value
                        //     ... compute ...
                        //     _state.value = st.copy(...)
                        //
                        // patterns scattered through handleEvent are NOT atomic: between
                        // the read and the write, a concurrent UI tap (which mutates
                        // state on the Main thread) can land its own write, and the
                        // event handler then clobbers it with a state value derived from
                        // the pre-tap snapshot. The visible symptom is "I tapped a
                        // buffer and the selection bounced back" / "my unread badge
                        // cleared then came back" - rare per-tap, but the race window
                        // grows with IRC event volume (busy channel = more handler runs
                        // = more chances to clobber).
                        //
                        // Forcing event handling onto the Main thread serializes it with
                        // UI mutations: there is now exactly one thread that ever writes
                        // _state.value, so the read-modify-write sequence is atomic by
                        // construction. We use Main.immediate so callers that are
                        // already on Main don't pay an extra dispatch hop (in this code
                        // path the collect runs on IO so we'll dispatch every time, but
                        // .immediate is the right semantic - "be on Main, dispatch only
                        // if necessary"). CPU cost per event is microseconds; the only
                        // real concern would be a single slow handler blocking UI
                        // frames, which doesn't happen in practice (scrollback-load and
                        // similar heavy work already runs in a separate launch).
                        //
                        // Throws from handleEvent are caught here on Main; the outer
                        // try below still catches anything that escapes from inside the
                        // collect's flow emission (writeLine IOException, etc.).
                        withContext(Dispatchers.Main.immediate) {
                            runCatching { handleEvent(netId, ev) }
                                .onFailure { t ->
                                    val msg = (t.message ?: t::class.java.simpleName)
                                    append(bufKey(netId, "*server*"), from = "CLIENT", text = "Event handler error: $msg", isHighlight = true)
                                }
                        }
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    android.util.Log.e("IrcViewModel", "events() flow crashed for $netId", t)
                    val msg = (t.message ?: t::class.java.simpleName)
                    runCatching {
                        append(
                            bufKey(netId, "*server*"),
                            from = "CLIENT",
                            text = "*** Connection error: $msg (stack trace in logcat)",
                            isHighlight = true,
                        )
                    }
                    // Drive the connection back to a clean Disconnected state so the UI
                    // doesn't get stuck on the pre-crash status. We can't rely on the
                    // server side sending QUIT here - the throw probably happened before
                    // any clean shutdown sequence ran.
                    runCatching { handleEvent(netId, IrcEvent.Disconnected(msg)) }
                }
            } finally {
                KeepAliveService.releaseScopedWakeLock(netId)
            }
        }
		
        // FALLBACK ONLY: if the collector exits without ever emitting Disconnected,
        // clean up and schedule reconnect. In the NORMAL path the IrcCore read loop
        // does emit Disconnected; that fires handleEvent/Disconnected handler which
        // calls scheduleAutoReconnect itself, and re-scheduling here would cancel and
        // recreate the auto-reconnect coroutine, racing the reconnectAttempts increment
        // and visibly freezing the "(attempt N)" counter.
        // Guard: if the job was *cancelled* (intentional teardown. force-close, manual
        // disconnect, reconnect replacing this runtime) we must not treat it as an
        // unexpected drop. CancellationException means someone called job.cancel().
        rt.job?.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) return@invokeOnCompletion
            viewModelScope.launch {
                if (runtimes[netId]?.client !== thisClient) return@launch

                val cur = _state.value.connections[netId]
                val wasConnectedOrConnecting = (cur?.connected == true || cur?.connecting == true)
                if (!wasConnectedOrConnecting) {
                    // Disconnected handler already ran (it sets connected/connecting = false
                    // and schedules its own auto-reconnect). Nothing to do here.
                    return@launch
                }

                // True fallback path: do the cleanup the Disconnected handler would have
                // done, then schedule reconnect.
                append(bufKey(netId, "*server*"), from = null, text = "*** Disconnected", doNotify = false)
                setNetConn(netId) { it.copy(connected = false, connecting = false, status = "Disconnected") }
                if (_state.value.activeNetworkId == netId) clearConnectionNotification()

                if (!_state.value.settings.autoReconnectEnabled) return@launch
                val wasManual = manualDisconnecting.remove(netId)
                if (wasManual && !desiredConnected.contains(netId)) return@launch
                if (desiredConnected.contains(netId)) scheduleAutoReconnect(netId)
            }
        }
    }

    fun reconnectActive() {
        val netId = _state.value.activeNetworkId ?: return
        val cur = _state.value.connections[netId]
        // If we were never connected / no runtime exists, treat reconnect as a connect.
        if ((cur?.connected != true && cur?.connecting != true) && runtimes[netId] == null) {
            connectNetwork(netId, force = true)
            return
        }
        reconnectNetwork(netId)
    }

    fun reconnectNetwork(netId: String) {
        val quitMsg = "Reconnecting"
        viewModelScope.launch {
            withNetLock(netId) {
            val addedDesired = desiredConnected.add(netId)
            if (addedDesired) persistDesiredNetworkIds()
            manualDisconnecting.add(netId)
            autoReconnectJobs.remove(netId)?.cancel()
            reconnectAttempts.remove(netId)
            // Clear flap detection state: the user has explicitly chosen to reconnect,
            // so we give the connection a fresh start.
            clearFlapPaused(netId)
            pingTimeoutTimestamps.remove(netId)
            // Manual reconnect also clears the auth-failure block (see connectNetwork
            // for rationale). Reaches the user-facing "reconnect" button via
            // reconnectActive() and the bouncer-side reconnect via this path.
            authBlockedReconnect.remove(netId)
            // Forget the connection-status dedup tracker too: the user explicitly chose
            // to retry, so any failure on this fresh attempt should surface even if it
            // matches the last failure verbatim.
            resetConnStatusDedup(netId)
            // Same lifecycle: drop any stashed server-error text. A current session
            // that registered cleanly invalidates any pre-registration ERROR we might
            // have correlated with a disconnect later on.
            lastServerErrorByNet.remove(netId)

            val oldRt = runtimes.remove(netId)
            runCatching { oldRt?.client?.disconnect(quitMsg) }
            runCatching { oldRt?.client?.forceClose() }
            runCatching { oldRt?.job?.cancel() }

            // Mark as disconnected before re-connecting. (connectNetwork() will flip to "Connecting...".)
            setNetConn(netId) { it.copy(connected = false, connecting = false, status = "Reconnecting…") }

            // Bypass the "already connecting" guard by calling the internal variant.
            connectNetworkInternal(netId, force = true)
            }
        }
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
            val removedDesired = desiredConnected.remove(netId)
            if (removedDesired) persistDesiredNetworkIds()
            manualDisconnecting.add(netId)
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
            val newConns = st.connections + (netId to prev.copy(connected = false, connecting = false, status = "Disconnected"))
            _state.value = syncActiveNetworkSummary(st.copy(connections = newConns))
            if (st.activeNetworkId == netId) clearConnectionNotification()
        }
            }
    }

    /**
     * User-requested full shutdown ("Exit").
     *
     * We aggressively suppress auto-reconnect + foreground-service restarts so the user doesn't
     * have to Force Stop the app.
     */
    fun exitApp() {
        appExitRequested = true

        // Prevent auto-reconnect from bringing things back up while we're exiting.
        desiredConnected.clear()
        persistDesiredNetworkIds()
        manualDisconnecting.clear()
        reconnectAttempts.clear()
        autoReconnectJobs.values.forEach { it.cancel() }
        autoReconnectJobs.clear()
        stableConnectionJobs.values.forEach { it.cancel() }
        stableConnectionJobs.clear()
        noNetworkNotice.clear()

        // Stop the foreground service + cancel notifications immediately.
        runCatching { NotificationHelper.cancelAll(appContext) }
        runCatching { appContext.stopService(Intent(appContext, KeepAliveService::class.java)) }
        runCatching {
            val i = Intent(appContext, KeepAliveService::class.java).apply { action = KeepAliveService.ACTION_STOP }
            appContext.startService(i)
        }
        runCatching { notifier.cancelConnection() }

        // Then disconnect everything.
        disconnectAll()
    }

    fun disconnectAll() {
        val netIds = runtimes.keys.toList()
        for (id in netIds) disconnectNetwork(id)
    }

    /**
     * Drop a live connection because the underlying network interface went away
     * (Wi-Fi/mobile handover with no failover)
     *
     * We tear the socket down (rather than waiting for readLine() to time out) so the
     * UI updates instantly and the read coroutine doesn't sit blocked for up to
     * SOCKET_READ_TIMEOUT_MS. Recovery is driven explicitly via [scheduleAutoReconnect]
     */
    private fun dropConnectionForNetworkLoss(netId: String) {
        viewModelScope.launch {
            withNetLock(netId) {
                val oldRt = runtimes.remove(netId)
                runCatching { oldRt?.client?.forceClose("Network lost") }
                runCatching { oldRt?.job?.cancel() }
                // Reset the backoff counter so the first attempt after the network
                // returns fires promptly instead of waiting out a stale exponential delay.
                reconnectAttempts.remove(netId)
                setNetConn(netId) {
                    it.copy(connected = false, connecting = false, status = "Waiting for network…", lagMs = null)
                }
                if (_state.value.activeNetworkId == netId) updateConnectionNotification("Waiting for network…")
                    if (_state.value.settings.autoReconnectEnabled && desiredConnected.contains(netId)) {
                        scheduleAutoReconnect(netId)
                    }
            }
        }
    }

    // Auto-reconnect
    private fun scheduleAutoReconnect(netId: String) {
        val st0 = _state.value
        if (!st0.settings.autoReconnectEnabled) return
        // Per-network override.
        if (st0.networks.firstOrNull { it.id == netId }?.autoReconnect == false) return
        // Auth failure on the previous attempt: do nothing. Reconnecting with the same
        // (wrong) credentials would just trigger the same 464 / SASL fail in a tight
        // loop, flooding the server log and hitting bouncer rate limits or IRCd bans.
        // The user must explicitly reconnect (or fix the profile) to clear the block.
        if (netId in authBlockedReconnect) {
            val serverKey = bufKey(netId, "*server*")
            append(serverKey, from = null,
                text = "*** Auto-reconnect halted: authentication failed. " +
                       "Fix credentials, then reconnect manually.",
                doNotify = false)
            setNetConn(netId) { it.copy(status = "Auth failed — reconnect halted") }
            return
        }
        // One job per network.
        autoReconnectJobs.remove(netId)?.cancel()
        val serverKey = bufKey(netId, "*server*")
        autoReconnectJobs[netId] = viewModelScope.launch(Dispatchers.Default) {
            // Outer guard. The loop body touches a lot of subsystems - state mutation,
            // notifications, log writes, network checks, KeepAliveService wakelock, the
            // socket connect itself - any of which can throw in the wild (Samsung
            // PendingIntent rate-limit firing under load, SAF revocation mid-session,
            // a transient JCE provider failure during TLS handshake, OOM during a
            // memory-pressured connect). viewModelScope is a SupervisorJob so a child
            // failure doesn't propagate to the parent, but the default
            // CoroutineExceptionHandler on Android re-throws unhandled child exceptions
            // and crashes the app. Catching here keeps the reconnect machinery resilient:
            // the failed attempt is logged, the netId stays in autoReconnectJobs only
            // briefly (cleared in finally), and the next manual reconnect can start fresh.
            // CancellationException is re-thrown so explicit cancel() still works as
            // structured-concurrency expects.
            try {
                while (isActive) {
                val attempt = reconnectAttempts[netId] ?: 0
                // Without this guard we'd print "Reconnecting in Ns
                // (attempt N)…" and run the whole countdown over a connection that's already
                // up, only self-correcting at the post-countdown check below.
                if (_state.value.connections[netId]?.connected == true) {
                    reconnectAttempts.remove(netId)
                    break
                }
                    val baseDelaySec = _state.value.settings.autoReconnectDelaySec.coerceIn(
                    ConnectionConstants.RECONNECT_BASE_DELAY_MIN_SEC,
                    ConnectionConstants.RECONNECT_BASE_DELAY_MAX_SEC
                )
                if (attempt > 0) {
                    val exp = attempt.coerceAtMost(ConnectionConstants.RECONNECT_MAX_EXPONENT)
                    val planned = (baseDelaySec.toLong() * (1L shl exp)).coerceAtMost(ConnectionConstants.RECONNECT_MAX_DELAY_SEC)
                    val jitter = (planned * ConnectionConstants.RECONNECT_JITTER_FACTOR).toLong()
                    val actual = if (jitter > 0) planned - jitter + Random.nextLong(jitter * 2 + 1) else planned
                    setNetConn(netId) { it.copy(status = "Reconnecting in ${actual}s…") }
                    // Route through appendConnStatus for consistency with the rest of the
                    // connection-status pipeline. Each attempt has a different N and Xs so
                    // dedup never collapses these in practice, but a malicious server that
                    // forces us to retry on the same counter twice in a row wouldn't get to
                    // print two identical lines.
                    appendConnStatus(
                        netId = netId,
                        text = "*** Reconnecting in ${actual}s (attempt ${attempt + 1})…",
                        from = null,
                        doNotify = false,
                        isHighlight = false,
                        broadcast = true,
                    )
                    // While backgrounded, no one's looking at the countdown — skip the tick
                    // loop and just wait the full duration in one delay() call. Avoids waking
                    // the CPU every 1-5 s purely to update a status string the user can't see.
                    if (!AppVisibility.isForeground) {
                        delay(actual * 1000L)
                    } else {
                        // Show countdown in the server buffer, updating every 5s for long delays.
                        val tickInterval = when {
                            actual > 30 -> 5L
                            actual > 10 -> 2L
                            else -> 1L
                        }
                        var remaining = actual
                        while (remaining > 0 && isActive) {
                            // If the connection came up mid-countdown (an in-flight attempt
                            // succeeded, or onAvailable reconnected us), stop ticking now so we
                            // don't paint a stale "Reconnecting in Ns…" over "Connected".
                            if (_state.value.connections[netId]?.connected == true) break
                            val tick = remaining.coerceAtMost(tickInterval)
                            delay(tick * 1000L)
                            remaining -= tick
                            if (remaining > 0) {
                                // Re-check foreground state on every tick: if the user backgrounds the
                                // app mid-countdown, swallow the rest of the wait without further updates.
                                if (!AppVisibility.isForeground) {
                                    delay(remaining * 1000L)
                                    remaining = 0
                                } else {
                                    if (_state.value.connections[netId]?.connected == true) break
                                    setNetConn(netId) { it.copy(status = "Reconnecting in ${remaining}s…") }
                                }
                            }
                        }
                    }
                    if (!isActive) break
                }

                // Stop if the user no longer wants this network connected.
                if (!desiredConnected.contains(netId)) break
                // If the user explicitly disconnected/reconnected, don't fight them.
                if (manualDisconnecting.contains(netId)) continue

                val st = _state.value
                // If the profile no longer exists, stop retrying.
                if (st.networks.none { it.id == netId }) {
                    desiredConnected.remove(netId)
                    reconnectAttempts.remove(netId)
                    break
                }

                val cur = st.connections[netId]
                if (cur?.connected == true) {
                    reconnectAttempts.remove(netId)
                    break
                }
                if (cur?.connecting == true) continue


                // If there's no connectivity at all (Wi‑Fi + Mobile disabled), pause auto-reconnect until it returns.
                if (!hasInternetConnection()) {
                    if (!noNetworkNotice.contains(netId)) {
                        noNetworkNotice.add(netId)
                        append(serverKey, from = null, text = "*** Please turn on WiFi or Mobile data to auto-reconnect.", doNotify = false)
                        setNetConn(netId) { it.copy(connected = false, connecting = false, status = "Waiting for network…") }
                        if (_state.value.activeNetworkId == netId) updateConnectionNotification("Waiting for network…")
                    }
                    delay(5000L)
                    continue
                } else if (noNetworkNotice.remove(netId)) {
                    // Connectivity is back; let the user know once and try again.
                    append(serverKey, from = null, text = "*** Network available. Retrying…", doNotify = false)
                }

                // Pause reconnect when battery saver is active to avoid draining battery.
                // We still attempt to reconnect when the user has the app in the foreground,
                // but when backgrounded + battery saver on, we wait until saver turns off.
                if (!AppVisibility.isForeground) {
                    val pm = appContext.getSystemService(android.content.Context.POWER_SERVICE)
                        as? android.os.PowerManager
                    if (pm?.isPowerSaveMode == true) {
                        append(serverKey, from = null, text = "*** Battery saver is active - reconnect paused. Will retry when battery saver is off.", doNotify = false)
                        setNetConn(netId) { it.copy(status = "Paused (battery saver)") }
                        // Poll every 30s until battery saver is disabled or app comes to foreground.
                        while (!AppVisibility.isForeground && pm.isPowerSaveMode && isActive) {
                            delay(30_000L)
                        }
                        if (isActive && !pm.isPowerSaveMode) {
                            append(serverKey, from = null, text = "*** Battery saver off. Retrying…", doNotify = false)
                        }
                        continue
                    }
                }

                if (attempt > 0) {
                    appendConnStatus(
                        netId = netId,
                        text = "*** Retrying to connect (attempt ${attempt + 1})…",
                        from = null,
                        doNotify = false,
                        isHighlight = false,
                    )
                }
                setNetConn(netId) { it.copy(status = "Retrying to connect…") }
                if (st.activeNetworkId == netId) updateConnectionNotification("Retrying to connect…")

                // Force a clean reconnect (drops stale runtimes if present).
                withNetLock(netId) { KeepAliveService.withWakeLock(appContext) { connectNetworkInternal(netId, force = true) } }
                reconnectAttempts[netId] = (attempt + 1).coerceAtMost(ConnectionConstants.RECONNECT_MAX_ATTEMPTS)
            }
            autoReconnectJobs.remove(netId)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Normal cancellation from autoReconnectJobs.remove(netId)?.cancel() upstream.
                // Re-throw so structured concurrency cleanup runs normally.
                throw ce
            } catch (t: Throwable) {
                android.util.Log.e("IrcViewModel", "autoReconnect loop crashed for $netId", t)
                runCatching {
                    append(
                        serverKey,
                        from = "CLIENT",
                        text = "*** Auto-reconnect halted: ${t.message ?: t::class.java.simpleName} (manual reconnect needed)",
                        isHighlight = true,
                    )
                }
            } finally {
                // Always evict the stale job reference so a fresh scheduleAutoReconnect
                // can start cleanly. Without this, a thrown-out job would linger in
                // autoReconnectJobs and the "already have a job for this netId" early
                // return in scheduleAutoReconnect would silently refuse all later retries.
                autoReconnectJobs.remove(netId)
            }
        }
    }

    /**
     * when the app returns to the foreground, re-check the socket state so the UI doesn't drift.
     * (E.g. if a lifecycle event caused UI state to reset while the socket is still alive.)
     * Also triggers reconnection for networks that should be connected but aren't.
     */
    /**
     * When the app returns to the foreground, re-check the socket state so the UI doesn't drift.
     * (E.g. if a lifecycle event caused UI state to reset while the socket is still alive.)
     * Also triggers reconnection for networks that should be connected but went down while backgrounded.
     *
     * Important: we skip any network that is actively connecting or already has a reconnect job
     * queued - isConnectedNow() can transiently return false during the handshake window, and
     * interfering with an in-flight attempt would cause a duplicate reconnect race.
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
                newMap[net.id] = cur.copy(connected = true, connecting = false, status = "Connected")
                changed = true
            }

            // Socket is gone but UI thinks we're connected - correct the UI and maybe reconnect.
            if (!actual && cur.connected) {
                newMap[net.id] = cur.copy(connected = false, connecting = false, status = "Disconnected")
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

        if (networksToReconnect.isNotEmpty() && hasInternetConnection()) {
            viewModelScope.launch {
                delay(500) // Brief delay to let UI settle
                for (netId in networksToReconnect) {
                    // Re-check: a reconnect job may have been scheduled in the meantime.
                    if (autoReconnectJobs.containsKey(netId)) continue
                    val cur = _state.value.connections[netId]
                    if (cur?.connecting == true || cur?.connected == true) continue
                    append(bufKey(netId, "*server*"), from = null, text = "*** Resuming connection…", doNotify = false)
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
    fun markBufferRead(bufferKey: String, timestamp: String? = null) {
        if (timestamp != null) {
            // Explicit timestamp from server - apply directly.
            val (netId, bufferName) = splitKey(bufferKey)
            val rt = runtimes[netId] ?: return
            val st = _state.value
            val buf = st.buffers[bufferKey]
            if (buf != null) {
                _state.value = st.copy(buffers = st.buffers + (bufferKey to buf.copy(lastReadTimestamp = timestamp)))
            }
            if (rt.client.hasCap("draft/read-marker")) {
                viewModelScope.launch { rt.client.sendRaw("MARKREAD $bufferName timestamp=$timestamp") }
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
    private var typingDoneJob: kotlinx.coroutines.Job? = null
    private var typingLastKey: String? = null

    // Track when we last sent "active" to enforce the IRCv3 minimum
    // interval of 3 seconds between "active" sends. Without this, every keystroke fires a
    // TAGMSG, which causes Excess Flood disconnection on any server with normal flood limits.
    private var typingActiveLastSentMs: Long = 0L
    private val TYPING_ACTIVE_INTERVAL_MS = 3_000L   // IRCv3 spec minimum

    // Auto-expiry jobs for *received* typing indicators.
    // Key: "$bufferKey/$nick". IRCv3 spec recommends expiring after 30 s with no update.
    private val receivedTypingExpiryJobs: MutableMap<String, kotlinx.coroutines.Job> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Called by the UI whenever the input text changes. Sends "active" typing status at most
     * once every 3 seconds per the IRCv3 spec, then schedules a "paused" → "done" timeout
     * if the user stops typing. Sending an empty string immediately sends "done".
     *
     * No-op if the user has disabled [UiSettings.sendTypingIndicator] in Settings (privacy).
     */
    /**
     * Called when the app transitions to background. Immediately sends a "done" typing
     * indicator so remote users don't see us typing forever, and cancels the pending
     * paused/done timer coroutine so it doesn't wake the CPU 6–30 s later.
     */
    /** Called when the app goes to background: flush log buffers to disk. */
    fun flushLogs() {
        if (_state.value.settings.loggingEnabled) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { logs.flushAll() } }
        }
    }

    fun cancelTypingOnBackground() {
        typingDoneJob?.cancel()
        typingDoneJob = null
        val prevKey = typingLastKey ?: return
        typingLastKey = null
        typingActiveLastSentMs = 0L
        val (prevNet, prevBuf) = splitKey(prevKey)
        viewModelScope.launch {
            runCatching { runtimes[prevNet]?.client?.sendTypingStatus(prevBuf, "done") }
        }
    }

    /**
     * Called from [HexDroidApp.onActivityStarted] when the app comes back to the
     * foreground. Resets unread and highlight counters on the currently-selected
     * buffer because the user is, by definition, looking at it.
     *
     * Why this is needed: append()'s isSelected predicate includes
     * AppVisibility.isForeground. While the app is backgrounded, isForeground is
     * false, so an incoming message for the selected buffer increments unread.
     * Without this hook, the user comes back to find a stale "1" badge on the
     * channel they're actively viewing - particularly visible when they open the
     * sidebar to switch channels. The reset here mirrors what openBuffer does
     * for an explicit buffer-switch.
     *
     * Also anchors a lastReadTimestamp so the "unread separator" line shows up
     * in the right place if the user later switches AWAY and then comes back -
     * otherwise the separator would be anchored at the moment of the most
     * recent foreground transition, which is wrong (no actual messages were
     * "marked as read", we just suppressed the badge).
     *
     * Idempotent: safe to call when nothing is selected, when the selected
     * buffer has zero unread, or when the buffer doesn't exist.
     */
    fun consumeUnreadOnForeground() {
        _state.update { st ->
            val key = st.selectedBuffer
            if (key.isBlank()) return@update st
            // Only reset when actually on the chat screen - foregrounding while on
            // Settings or Networks shouldn't clear unread counters because the
            // user isn't seeing the chat content yet.
            if (st.screen != AppScreen.CHAT) return@update st
            val buf = st.buffers[key] ?: return@update st
            if (buf.unread == 0 && buf.highlights == 0) return@update st
            val updated = buf.copy(unread = 0, highlights = 0)
            st.copy(buffers = st.buffers + (key to updated))
        }
    }

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
        if (bufferName == "*server*") return
        // DCC chat buffers are peer-to-peer and don't speak IRC - sending a TAGMSG with
        // a DCC buffer name as the target routes it to the IRC server, which bounces it
        // back as ERR_NOSUCHNICK. Skip silently.
        if (isDccChatBufferName(bufferName)) return

        typingDoneJob?.cancel()

        if (text.isEmpty()) {
            // User cleared input - send "done" immediately to the correct network.
            // look up the client at send time rather than capturing
            // `rt` here. If the user reconnected between keystrokes, `rt` would be the old
            // disconnected client; runtimes[prevNet] gives the live one.
            typingLastKey?.let { prevKey ->
                val (prevNet, prevBuf) = splitKey(prevKey)
                viewModelScope.launch { runtimes[prevNet]?.client?.sendTypingStatus(prevBuf, "done") }
            }
            typingLastKey = null
            typingActiveLastSentMs = 0L
            return
        }

        // If buffer changed, send "done" to the OLD buffer on whichever network it belonged to.
        val prevKey = typingLastKey
        if (prevKey != null && prevKey != currentKey) {
            val (prevNet, prevBuf) = splitKey(prevKey)
            viewModelScope.launch { runtimes[prevNet]?.client?.sendTypingStatus(prevBuf, "done") }
            typingActiveLastSentMs = 0L
        }

        typingLastKey = currentKey

        // only send "active" if 3+ seconds have passed since the
        // last send. The IRCv3 draft/typing spec explicitly requires this rate limit.
        // Capture only the string ids, not the client reference, to avoid the stale-client bug.
        val now = System.currentTimeMillis()
        if (now - typingActiveLastSentMs >= TYPING_ACTIVE_INTERVAL_MS) {
            typingActiveLastSentMs = now
            val capturedNetId = netId
            val capturedBuffer = bufferName
            viewModelScope.launch {
                runtimes[capturedNetId]?.client?.sendTypingStatus(capturedBuffer, "active")
            }
        }

        // After 6 s of inactivity -> "paused"; after another 24 s -> "done".
        val capturedNetId = netId
        val capturedBuffer = bufferName
        typingDoneJob = viewModelScope.launch {
            delay(6_000L)
            runtimes[capturedNetId]?.client?.sendTypingStatus(capturedBuffer, "paused")
            delay(24_000L)
            runtimes[capturedNetId]?.client?.sendTypingStatus(capturedBuffer, "done")
            typingLastKey = null
        }
    }

    fun sendInput(raw: String) {
        val st = _state.value
        val currentKey = st.selectedBuffer
        if (currentKey.isBlank()) return
        val (netId, bufferName) = splitKey(currentKey)
        // Some commands (/SYSINFO) should work even when disconnected.
        // For network-bound commands, we'll surface a friendly "Not connected" message.
        val rt = runtimes[netId]
        val c = rt?.client

        viewModelScope.launch {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@launch

            // Strip IRC formatting codes (bold, colour, italic, etc.) from the front of the
            // input before checking for a leading '/'. If the user has bold or colour active
            // in the input field, the raw string starts with formatting bytes, not '/'.
            val strippedForCommandCheck = trimmed.trimStart(
                '\u0002', '\u0003', '\u000f', '\u0016', '\u001d', '\u001e', '\u001f'
            ).let {
                // \u0003 may be followed by colour digits - skip them too
                it.replace(Regex("^\u0003\\d{0,2}(?:,\\d{0,2})?"), "")
            }

            // Check if this is a command (starts with /)
            // Use the formatting-stripped version for detection, but keep `trimmed` for
            // the actual command content so explicit /me with colour still works.
            if (strippedForCommandCheck.startsWith("/")) {
                // Use the stripped string to parse the command name/args, but content
                // after the command verb is taken from strippedForCommandCheck directly.
                val cmdLine = strippedForCommandCheck.drop(1).substringBefore('\n').trim()
                val cmd = cmdLine.substringBefore(' ').lowercase()

                // A typed /join is forwarded to the client below (the else branch) and is
                // NOT pre-switched by openBuffer the way the join button is, so it relies on
                // the JOIN-handler auto-switch to land the user in the channel. Record the
                // intent here so that switch still fires even if a post-reconnect suppression
                // window is active. Args: /join #a,#b [key] — only the channel list matters.
                if (cmd == "join") {
                    cmdLine.substringAfter(' ', "").trim().substringBefore(' ')
                        .split(",")
                        .forEach { ch ->
                            ch.trim().takeIf { it.isNotBlank() }
                                ?.let { rt?.pendingUserJoinSwitch?.add(casefoldText(netId, it)) }
                        }
                }

                when (cmd) {
                    "quit", "disconnect" -> {
                        // User-initiated disconnect. Send QUIT (so the server / bouncer sees
                        // a graceful goodbye and any active channels announce a clean exit
                        // to other users), then mark the network as manual-disconnecting
                        // before the socket closes so the Disconnected handler's reconnect
                        // path correctly identifies this as deliberate and bails. Without
                        // this dual action, the raw "/quit" would just send QUIT to the
                        // server; the server-side disconnect would then look identical to
                        // an unexpected drop (manualDisconnecting unset, desiredConnected
                        // still true) and scheduleAutoReconnect would immediately bring
                        // the connection back, with the user-visible symptom of "I typed
                        // /quit and it reconnected".
                        //
                        // Everything after the command verb is the quit message:
                        //   /quit                       -> persisted settings.quitMessage
                        //   /quit gone for tea          -> "gone for tea"
                        //   /quit "back in 5"           -> "\"back in 5\"" (quotes preserved
                        //                                  because IRC QUIT trailing is free-
                        //                                  form text; users can include
                        //                                  formatting / colour codes here too)
                        // We use cmdLine (not the lowercased cmd) so the casing of the
                        // user's message is preserved on the wire.
                        val reason = cmdLine.substringAfter(' ', missingDelimiterValue = "")
                            .trim()
                            .takeIf { it.isNotBlank() }
                        disconnectNetwork(netId, reasonOverride = reason)
                        return@launch
                    }
                    "agm-key" -> {
                        // Manages per-target AES-256-GCM keys
                        // for end-to-end encryption.
                        //
                        //   /agm-key gen [target]           - generate a fresh 32-byte key,
                        //                                     store it, print it so the
                        //                                     other side can /agm-key set
                        //   /agm-key set <target> <b64>     - install a base64-encoded key
                        //   /agm-key clear <target>         - remove the key
                        //   /agm-key info [target]          - show scheme + fingerprint
                        //   /agm-key                        - usage
                        //
                        // "target" defaults to the current buffer (channel or query nick).
                        // base64 form is standard (with or without padding)
                        val parts = cmdLine.split(Regex("\\s+"), limit = 4)
                        val sub = parts.getOrNull(1)?.lowercase() ?: ""
                        fun usage() {
                            append(currentKey, from = null, isLocal = true, doNotify = false,
                                text = "*** /agm-key gen [target] | set <target> <b64> | clear <target> | info [target]")
                        }
                        val defaultTarget = if (bufferName == "*server*") null else bufferName
                        when (sub) {
                            "gen" -> {
                                val target = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: defaultTarget
                                if (target == null) {
                                    append(currentKey, from = null, isLocal = true, doNotify = false,
                                        text = "*** /agm-key gen needs a target (or run it inside a channel/query)")
                                    return@launch
                                }
                                val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                                e2eKeyStore.set(netId, target, com.boxlabs.hexdroid.crypto.E2eKeyStore.Entry(
                                    com.boxlabs.hexdroid.crypto.E2eScheme.AGM, key))
                                val b64 = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                                val fp = com.boxlabs.hexdroid.crypto.E2eFingerprint.compute(com.boxlabs.hexdroid.crypto.E2eScheme.AGM, key)
                                append(currentKey, from = null, isLocal = true, doNotify = false,
                                    text = "*** AGM key generated for $target. Fingerprint: $fp")
                                append(currentKey, from = null, isLocal = true, doNotify = false,
                                    text = "*** On the other device, run:   /agm-key set $target $b64")
                                append(currentKey, from = null, isLocal = true, doNotify = false,
                                    text = "*** (Verify the fingerprint matches on both sides before sending anything sensitive.)")
                                return@launch
                            }
                            "set" -> {
                                val target = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                                val b64 = parts.getOrNull(3)?.trim()
                                if (target == null || b64.isNullOrBlank()) {
                                    append(currentKey, from = null, isLocal = true, doNotify = false,
                                        text = "*** Usage: /agm-key set <target> <base64>")
                                    return@launch
                                }
                                val keyBytes = try {
                                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                } catch (_: IllegalArgumentException) {
                                    append(currentKey, from = null, isLocal = true, doNotify = false,
                                        text = "*** /agm-key set: invalid base64 in key argument")
                                    return@launch
                                }
                                if (keyBytes.size != 32) {
                                    append(currentKey, from = null, isLocal = true, doNotify = false,
                                        text = "*** /agm-key set: expected 32-byte key (got ${keyBytes.size} bytes after base64 decode)")
                                    return@launch
                                }
                                try {
                                    e2eKeyStore.set(netId, target, com.boxlabs.hexdroid.crypto.E2eKeyStore.Entry(
                                        com.boxlabs.hexdroid.crypto.E2eScheme.AGM, keyBytes))
                                    val fp = com.boxlabs.hexdroid.crypto.E2eFingerprint.compute(com.boxlabs.hexdroid.crypto.E2eScheme.AGM, keyBytes)
                                    append(currentKey, from = null, isLocal = true, doNotify = false,
                                        text = "*** AGM key installed for $target. Fingerprint: $fp")
                                } catch (t: Throwable) {
                                    append(currentKey, from = null, isLocal = true, doNotify = false,
                                        text = "*** Failed to store key: ${t.message ?: t.javaClass.simpleName}")
                                }
                                return@launch
                            }
                            "clear" -> {
                                val target = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: defaultTarget
                                if (target == null) {
                                    append(currentKey, from = null, isLocal = true, doNotify = false,
                                        text = "*** /agm-key clear needs a target (or run it inside a channel/query)")
                                    return@launch
                                }
                                e2eKeyStore.clear(netId, target)
                                append(currentKey, from = null, isLocal = true, doNotify = false,
                                    text = "*** AGM key cleared for $target. Messages will now be sent in cleartext.")
                                return@launch
                            }
                            "info" -> {
                                val target = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: defaultTarget
                                if (target == null) {
                                    append(currentKey, from = null, isLocal = true, doNotify = false,
                                        text = "*** /agm-key info needs a target (or run it inside a channel/query)")
                                    return@launch
                                }
                                val entry = e2eKeyStore.get(netId, target)
                                if (entry == null) {
                                    append(currentKey, from = null, isLocal = true, doNotify = false,
                                        text = "*** No encryption key configured for $target")
                                } else {
                                    val fp = com.boxlabs.hexdroid.crypto.E2eFingerprint.compute(entry.scheme, entry.key)
                                    append(currentKey, from = null, isLocal = true, doNotify = false,
                                        text = "*** $target: scheme=${entry.scheme.displayName}  fingerprint=$fp")
                                }
                                return@launch
                            }
                            else -> {
                                usage()
                                return@launch
                            }
                        }
                    }
                    "list" -> {
                        goTo(AppScreen.LIST)
                        return@launch
                    }
                    "sysinfo" -> {
                        val line = withContext(Dispatchers.Default) { buildSysInfoLine() }
                        val fromNick = st.connections[netId]?.myNick ?: st.myNick
                        // If we're in a channel/query and connected, send it as a normal message.
                        if (bufferName != "*server*" && c != null) {
                            c.privmsg(bufferName, line)
                            val enc = e2eKeyStore.get(netId, bufferName)?.scheme
                            append(currentKey, from = fromNick, text = line, isLocal = true, encryption = enc)
                            recordLocalSend(netId, currentKey, line, isAction = false)
                        } else {
                            append(currentKey, from = fromNick, text = line, isLocal = true)
                        }
                        return@launch
                    }

                    "react", "unreact" -> {
                        // /react <emoji> [n]   - react to the n-th most recent message (n=1, default)
                        // /unreact <emoji> [n] - remove a reaction from the n-th most recent message
                        // Examples: /react 👍   /react :tada: 3   /unreact ❤️
                        //
                        // Reacting requires a server msgId on the target message (IRCv3 message-tags),
                        // so the lookup walks backwards from the newest message and skips any line
                        // without a msgId (server-status lines, /me actions if echo-message wasn't on,
                        // older messages from before the cap was negotiated, etc.).
                        val remove = (cmd == "unreact")
                        val args = cmdLine.substringAfter(' ', "").trim().split(Regex("\\s+"))
                        val emoji = args.getOrNull(0)?.takeIf { it.isNotBlank() }
                        if (emoji == null) {
                            append(currentKey, from = null, isLocal = true, doNotify = false,
                                text = "*** Usage: /${cmd} <emoji> [n]   - n is how many messages back, default 1")
                            return@launch
                        }
                        if (bufferName == "*server*" || c == null) {
                            append(currentKey, from = null, isLocal = true, doNotify = false,
                                text = "*** /${cmd} requires an active channel or query")
                            return@launch
                        }
                        // Match the long-press UI's friendly fail mode: surface a clear message
                        // when the server doesn't support reactions rather than silently no-op.
                        // hasReactionSupport is set on CAP-negotiated -> ack of message-tags.
                        if (st.connections[netId]?.hasReactionSupport != true) {
                            append(currentKey, from = null, isLocal = true, doNotify = false,
                                text = "*** This server doesn't support reactions (no message-tags cap)")
                            return@launch
                        }
                        val nBack = args.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 100) ?: 1
                        val msgs = _state.value.buffers[currentKey]?.messages ?: emptyList()
                        // Walk newest-first, collect messages with msgIds, pick the nBack-th one.
                        val withMsgId = msgs.asReversed().asSequence()
                            .filter { !it.msgId.isNullOrBlank() }
                            .take(nBack)
                            .toList()
                        val target = withMsgId.getOrNull(nBack - 1)
                        if (target?.msgId == null) {
                            val noun = if (nBack == 1) "the latest message" else "message #$nBack back"
                            append(currentKey, from = null, isLocal = true, doNotify = false,
                                text = "*** /${cmd} couldn't find $noun with a server msgId - the server may not support message-tags")
                            return@launch
                        }
                        c.sendReaction(bufferName, target.msgId, emoji, remove = remove)
                        return@launch
                    }

                    "find", "grep", "search" -> {
                        val query = cmdLine.substringAfter(' ', "").trim()
                        if (query.isBlank()) {
                            append(currentKey, from = null, text = "*** Usage: /find <text>", isLocal = true, doNotify = false)
                            return@launch
                        }
                        val msgs = _state.value.buffers[currentKey]?.messages.orEmpty()
                        val matches = msgs.filter {
                            it.text.contains(query, ignoreCase = true) ||
                                it.from?.contains(query, ignoreCase = true) == true
                        }
                        if (matches.isEmpty()) {
                            append(currentKey, from = null, text = "*** No matches for \"$query\"", isLocal = true, doNotify = false)
                            return@launch
                        }
                        _state.value = _state.value.copy(
                            findOverlay = FindOverlay(
                                query = query,
                                matchIds = matches.map { it.id },
                                currentIndex = matches.lastIndex,
                                bufferKey = currentKey,
                            )
                        )
                        return@launch
                    }

                    "gsearch", "gfind" -> {
                        // Global search across all loaded buffers on the current network.
                        val query = cmdLine.substringAfter(' ', "").trim()
                        if (query.isBlank()) {
                            append(currentKey, from = null, text = "*** Usage: /gsearch <text>", isLocal = true, doNotify = false)
                            return@launch
                        }
                        val allMatches = _state.value.buffers
                            .filter { (k, _) -> splitKey(k).first == netId }
                            .flatMap { (_, buf) ->
                                buf.messages.filter {
                                    it.text.contains(query, ignoreCase = true) ||
                                        it.from?.contains(query, ignoreCase = true) == true
                                }
                            }
                            .sortedBy { it.timeMs }
                        if (allMatches.isEmpty()) {
                            append(currentKey, from = null, text = "*** No matches for \"$query\" across ${_state.value.buffers.count { splitKey(it.key).first == netId }} buffers", isLocal = true, doNotify = false)
                            return@launch
                        }
                        _state.value = _state.value.copy(
                            findOverlay = FindOverlay(
                                query = query,
                                matchIds = allMatches.map { it.id },
                                currentIndex = allMatches.lastIndex,
                                bufferKey = "GLOBAL:$netId",
                            )
                        )
                        append(currentKey, from = null, text = "*** Found ${allMatches.size} matches for \"$query\" - use /gsearch overlay to navigate", isLocal = true, doNotify = false)
                        return@launch
                    }

                    "flip" -> {
                        // secret table-flip easter egg
                        if (bufferName == "*server*") return@launch
                        val rt = runtimes[netId] ?: return@launch
                        val myNick = _state.value.connections[netId]?.myNick ?: _state.value.myNick
                        rt.client.sendRaw("PRIVMSG $bufferName :(╯°□°)╯┬─┬")
                        append(currentKey, from = myNick, text = "(╯°□°)╯┬─┬", isLocal = true, doNotify = false)
                        kotlinx.coroutines.delay(800L)
                        rt.client.sendRaw("PRIVMSG $bufferName :(ノ°□°)ノ┻━┻")
                        append(currentKey, from = myNick, text = "(ノ°□°)ノ┻━┻", isLocal = true, doNotify = false)
                        return@launch
                    }

                    "ignore" -> {
                        val arg = cmdLine.substringAfter(' ', "").trim()
                        val listOnly = arg.isBlank() || arg.equals("list", ignoreCase = true) || arg.equals("ls", ignoreCase = true)
                        if (listOnly) {
                            val net = _state.value.networks.firstOrNull { it.id == netId }
                            val items = net?.ignoredNicks.orEmpty()
                            if (items.isEmpty()) {
                                append(currentKey, from = null, text = "*** Ignore list is empty. Usage: /ignore <nick>", isLocal = true, doNotify = false)
                            } else {
                                append(currentKey, from = null, text = "*** Ignored (${items.size}): ${items.joinToString(", ")}", isLocal = true, doNotify = false)
                            }
                            return@launch
                        }
                        val nick = arg.substringBefore(' ').trim()
                        val canon = canonicalIgnoreNick(nick)
                        if (canon == null) {
                            append(currentKey, from = null, text = "*** Usage: /ignore <nick>", isLocal = true, doNotify = false)
                            return@launch
                        }
                        ignoreNick(netId, canon)
                        return@launch
                    }

                    "unignore" -> {
                        val arg = cmdLine.substringAfter(' ', "").trim()
                        if (arg.isBlank()) {
                            append(currentKey, from = null, text = "*** Usage: /unignore <nick>", isLocal = true, doNotify = false)
                            return@launch
                        }
                        val nick = arg.substringBefore(' ').trim()
                        val canon = canonicalIgnoreNick(nick)
                        if (canon == null) {
                            append(currentKey, from = null, text = "*** Usage: /unignore <nick>", isLocal = true, doNotify = false)
                            return@launch
                        }
                        unignoreNick(netId, canon)
                        return@launch
                    }

                    "motd" -> {
                        if (c == null) {
                            append(currentKey, from = null, text = "*** Not connected.", doNotify = false)
                            return@launch
                        }
                        // If user explicitly requests MOTD, show it even if we hide on connect
                        runtimes[netId]?.apply { manualMotdAtMs = System.currentTimeMillis(); suppressMotd = false }
                        val args = cmdLine.substringAfter(' ', "").trim()
                        val line = if (args.isBlank()) "MOTD" else "MOTD $args"
                        c.sendRaw(line)
                        return@launch
                    }
                    "names" -> {
                        if (c == null) {
                            append(currentKey, from = null, text = "*** Not connected.", doNotify = false)
                            return@launch
                        }

                        val arg = cmdLine.substringAfter(' ', "").trim()
                        val target = when {
                            arg.isNotBlank() -> arg.substringBefore(' ')
                            bufferName != "*server*" -> bufferName
                            else -> ""
                        }

                        if (target.isBlank()) {
                            append(currentKey, from = null, text = "*** Usage: /names #channel", doNotify = false)
                            return@launch
                        }

                        // Track this request so we can print a clean consolidated list.
                        rt.namesRequests[namesKeyFold(target)] = NamesRequest(replyBufferKey = currentKey)
                        c.sendRaw("NAMES $target")
                        return@launch
                    }

                    "me" -> {
                        if (c == null) {
                            append(currentKey, from = null, text = "*** Not connected.", doNotify = false)
                            return@launch
                        }
                        val msg = cmdLine.drop(2).trim()
                        if (msg.isBlank()) return@launch

                        // DCC CHAT buffer: send over the DCC socket instead of IRC.
                        if (isDccChatBufferName(bufferName)) {
                            sendDccChatLine(currentKey, msg, isAction = true)
                            return@launch
                        }

                        val target = if (bufferName == "*server*") return@launch else bufferName
                        // Route through ctcp() so privmsg()'s E2E hook gets to encrypt
                        // the ACTION body when a per-target key is configured. Direct
                        // c.sendRaw("PRIVMSG …") would bypass that hook and ship the
                        // ACTION text in clear, which would silently break encryption
                        // for /me lines only - a particularly confusing partial-failure
                        // for anyone debugging "why does my chat message look encrypted
                        // but my /me line doesn't?".
                        c.ctcp(target, "ACTION $msg")
                        val actionEnc = e2eKeyStore.get(netId, target)?.scheme
                        append(currentKey, from = st.connections[netId]?.myNick ?: st.myNick, text = msg, isAction = true, isLocal = true, encryption = actionEnc)
                        recordLocalSend(netId, currentKey, msg, isAction = true)
                        return@launch
                    }

                    "banlist" -> {
                        if (c == null) {
                            append(currentKey, from = null, text = "*** Not connected.", doNotify = false)
                            return@launch
                        }
                        val arg = cmdLine.substringAfter(' ', "").trim()
                        val chan = when {
                            arg.isNotBlank() -> arg.substringBefore(' ')
                            isChannelOnNet(netId, bufferName) -> bufferName
                            else -> ""
                        }
                        if (chan.isBlank() || !isChannelOnNet(netId, chan)) {
                            append(currentKey, from = null, text = "*** Usage: /banlist #channel", doNotify = false)
                            return@launch
                        }
                        startBanList(netId, chan)
                        c.sendRaw("MODE $chan +b")
                        return@launch
                    }

                    "quietlist" -> {
                        val arg = cmdLine.substringAfter(' ', "").trim()
                        val chan = when {
                            arg.isNotBlank() -> arg.substringBefore(' ')
                            isChannelOnNet(netId, bufferName) -> bufferName
                            else -> ""
                        }
                        if (chan.isBlank() || !isChannelOnNet(netId, chan)) {
                            append(currentKey, from = null, text = "*** Usage: /quietlist #channel", doNotify = false)
                            return@launch
                        }
                        if (c == null) {
                            append(currentKey, from = null, text = "*** Not connected.", doNotify = false)
                            return@launch
                        }
                        startQuietList(netId, chan)
                        c.sendRaw("MODE $chan +q")
                        return@launch
                    }

                    "exceptlist" -> {
                        val arg = cmdLine.substringAfter(' ', "").trim()
                        val chan = when {
                            arg.isNotBlank() -> arg.substringBefore(' ')
                            isChannelOnNet(netId, bufferName) -> bufferName
                            else -> ""
                        }
                        if (chan.isBlank() || !isChannelOnNet(netId, chan)) {
                            append(currentKey, from = null, text = "*** Usage: /exceptlist #channel", doNotify = false)
                            return@launch
                        }
                        if (c == null) {
                            append(currentKey, from = null, text = "*** Not connected.", doNotify = false)
                            return@launch
                        }
                        startExceptList(netId, chan)
                        c.sendRaw("MODE $chan +e")
                        return@launch
                    }

                    "invexlist" -> {
                        val arg = cmdLine.substringAfter(' ', "").trim()
                        val chan = when {
                            arg.isNotBlank() -> arg.substringBefore(' ')
                            isChannelOnNet(netId, bufferName) -> bufferName
                            else -> ""
                        }
                        if (chan.isBlank() || !isChannelOnNet(netId, chan)) {
                            append(currentKey, from = null, text = "*** Usage: /invexlist #channel", doNotify = false)
                            return@launch
                        }
                        if (c == null) {
                            append(currentKey, from = null, text = "*** Not connected.", doNotify = false)
                            return@launch
                        }
                        startInvexList(netId, chan)
                        c.sendRaw("MODE $chan +I")
                        return@launch
                    }

                    "close" -> {
                        // Close the current buffer, or a specific buffer name on this network.
                        val arg = cmdLine.substringAfter(' ', "").trim()
                        val targetName = when {
                            arg.isNotBlank() -> arg.substringBefore(' ')
                            else -> bufferName
                        }
                        if (targetName.isBlank() || targetName == "*server*") return@launch
                        val key = if (arg.isBlank()) currentKey else resolveBufferKey(netId, targetName)
                        if (isChannelOnNet(netId, targetName)) {
                            val cli = runtimes[netId]?.client
                            if (cli != null) {
                                pendingCloseAfterPart.add(key)
                                // Close immediately; we still send PART but suppress recreating the buffer on the echo.
                                removeBuffer(key)
                                cli.sendRaw("PART $targetName")
                            } else {
                                removeBuffer(key)
                            }
                        } else {
                            removeBuffer(key)
                        }
                        return@launch
                    }

                    "closekey" -> {
                        // Internal helper used by the sidebar X button: /closekey <netId>::<buffer>
                        val arg = cmdLine.substringAfter(' ', "").trim()
                        if (arg.isBlank() || !arg.contains("::")) return@launch
                        val (targetNet, targetName) = splitKey(arg)
                        if (targetNet.isBlank() || targetName.isBlank() || targetName == "*server*") return@launch
                        // IMPORTANT: keep the *exact* buffer key the user clicked.
                        // (If we resolve/normalize here, we can end up closing a different buffer when
                        // duplicate buffers exist due to case differences, leaving the clicked one stuck.)
                        val key = arg
                        if (isChannelOnNet(targetNet, targetName)) {
                            val cli = runtimes[targetNet]?.client
                            if (cli != null) {
                                pendingCloseAfterPart.add(key)
                                // Close immediately; we still send PART but suppress recreating the buffer on the echo.
                                removeBuffer(key)
                                cli.sendRaw("PART $targetName")
                            } else {
                                removeBuffer(key)
                            }
                        } else {
                            removeBuffer(key)
                        }
                        return@launch
                    }

                    "dcc" -> {
                        val rest = cmdLine.substringAfter(' ', "").trim()
                        val sub = rest.substringBefore(' ').lowercase(Locale.ROOT)
                        val arg = rest.substringAfter(' ', "").trim()
                        when (sub) {
                            "chat" -> {
                                if (arg.isBlank()) {
                                    append(currentKey, from = null, text = "*** Usage: /dcc chat <nick>", doNotify = false)
                                } else {
                                    startDccChat(arg.substringBefore(' '))
                                }
                            }
                            else -> append(currentKey, from = null, text = "*** Usage: /dcc chat <nick>", doNotify = false)
                        }
                        return@launch
                    }

                    "mode" -> {
                        if (c == null) {
                            append(currentKey, from = null, text = "*** Not connected.", doNotify = false)
                            return@launch
                        }

                        // Intercept MODE list requests (e.g. +b, +q, +e, +I) so we can populate the list UI.
                        val args = cmdLine.substringAfter(' ', "").trim()
                        val toks = args.split(Regex("\\s+")).filter { it.isNotBlank() }
                        if (toks.isNotEmpty()) {
                            val t0 = toks.getOrNull(0).orEmpty()
                            val t1 = toks.getOrNull(1)
                            val t2 = toks.getOrNull(2)

                            val (chan, modeTok, maskTok) = if (isChannelOnNet(netId, t0)) {
                                Triple(t0, t1, t2)
                            } else if (isChannelOnNet(netId, bufferName)) {
                                Triple(bufferName, t0, t1)
                            } else {
                                Triple("", null, null)
                            }

                            val modeNorm = modeTok?.trim()
                            val isListQuery = (maskTok == null)
                            if (chan.isNotBlank() && isListQuery) {
                                when (modeNorm) {
                                    "+b", "b" -> {
                                        startBanList(netId, chan)
                                        c.sendRaw("MODE $chan +b")
                                        return@launch
                                    }
                                    "+q", "q" -> {
                                        startQuietList(netId, chan)
                                        c.sendRaw("MODE $chan +q")
                                        return@launch
                                    }
                                    "+e", "e" -> {
                                        startExceptList(netId, chan)
                                        c.sendRaw("MODE $chan +e")
                                        return@launch
                                    }
                                    "+I", "I" -> {
                                        startInvexList(netId, chan)
                                        c.sendRaw("MODE $chan +I")
                                        return@launch
                                    }
                                }
                            }
                        }

                        // Default MODE handling
                        c.handleSlashCommand(cmdLine, bufferName)
                        return@launch
                    }

                    else -> {
                        if (c == null) {
                            append(currentKey, from = null, text = "*** Not connected.", doNotify = false)
                            return@launch
                        }
                        // Let the IRC client handle it
                        c.handleSlashCommand(cmdLine, bufferName)
                        return@launch
                    }
                }
            }

            // Regular text message, join any newlines into a single message.
            // Only split if the message exceeds the server's max line length.
            // The IRC protocol limit is typically 512 bytes (including CRLF), but many
            // servers support more via ISUPPORT LINELEN.
            
            // Replace newlines with spaces to create one continuous message
            val fullMessage = trimmed.replace('\n', ' ').replace('\r', ' ').replace("  ", " ").trim()
            
            if (fullMessage.isEmpty()) return@launch
            
            if (isDccChatBufferName(bufferName)) {
                sendDccChatLine(currentKey, fullMessage, isAction = false)
                return@launch
            }
            // Verify the connection is actually live and tell the user,
            // rather than swallowing the message. We accept either the live socket
            // or the UI state reporting connected, so a brief state-drift can't false-block.
            val liveConnected = c?.isConnectedNow() == true
            val stateConnected = _state.value.connections[netId]?.connected == true
            if (c == null || (!liveConnected && !stateConnected)) {
                append(currentKey, from = null, text = "*** Not connected.", doNotify = false)
                return@launch
            }
            if (bufferName == "*server*") {
                c.sendRaw(fullMessage)
                return@launch
            }
            
            // Calculate max message length for PRIVMSG
            // Format: ":nick!user@host PRIVMSG <target> :<message>\r\n"
            // We derive the limit from the server's LINELEN ISUPPORT token when available.
            // LINELEN covers the full wire line including CRLF; subtract the overhead of
            // the longest plausible sender prefix + "PRIVMSG <target> :" to get the safe
            // payload budget. We cap the overhead estimate conservatively at 100 bytes
            // (64 max nick + ident + host + "!@" + " PRIVMSG " + channel + " :" + "\r\n").
            val myNick = st.connections[netId]?.myNick ?: st.myNick
            val serverLimit = runtimes[netId]?.support?.linelen ?: 512
            val baseBudget = (serverLimit - 100).coerceIn(200, serverLimit - 10)

            // When a key is configured, the wire line carries the *encrypted* form,
            // which is larger than the plaintext: AES-GCM prepends a version byte +
            // 12-byte nonce and appends a 16-byte tag, then the whole thing is base64'd
            // (~+33%) behind a "+AGM " prefix; Blowfish prepends an 8-byte IV and
            // zero-pads to the block size before base64 behind "+OK *". Splitting on the
            // plaintext budget would let the encrypted line blow past the server limit
            // and get truncated, which corrupts the tag and breaks decryption on the
            // far side. So shrink the plaintext budget
            // to leave headroom for the expansion. The formulas invert
            // "prefix + base64(overhead + P) <= baseBudget" for P, minus a 2-byte margin.
            val sendEncryption = e2eKeyStore.get(netId, bufferName)?.scheme
            val maxMsgLen = when (sendEncryption) {
                com.boxlabs.hexdroid.crypto.E2eScheme.AGM ->
                    ((baseBudget - 5) * 3 / 4) - 29 - 2          // "+AGM " + base64(1+12+P+16)
                com.boxlabs.hexdroid.crypto.E2eScheme.BLOWFISH ->
                    ((baseBudget - 5) * 3 / 4) - 15 - 2          // "+OK *" + base64(8 IV + P + ≤7 pad)
                null -> baseBudget
            }.coerceAtLeast(64)

            // Cancel pending typing-done timer and send "done" immediately on send.
            typingDoneJob?.cancel()
            typingDoneJob = null
            if (st.settings.sendTypingIndicator && typingLastKey == currentKey) {
                c.sendTypingStatus(bufferName, "done")
                typingLastKey = null
            }

            // Split message if it exceeds max length
            val chunks = splitMessageByLength(fullMessage, maxMsgLen)

            for (chunk in chunks) {
                if (chunk.isEmpty()) continue
                c.privmsg(bufferName, chunk)
                append(currentKey, from = myNick, text = chunk, isLocal = true, encryption = sendEncryption)
                recordLocalSend(netId, currentKey, chunk, isAction = false)
            }
        }
    }
    
    /**
     * Split a message into chunks that don't exceed [maxLen] bytes (UTF-8).
     *
     * Uses a single-pass byte-slice strategy instead of repeated toByteArray() calls,
     * keeping allocations O(n) regardless of how many chunks the message produces.
     * Tries to split on a word boundary (space) when one falls in the back half of a chunk.
     */
    private fun splitMessageByLength(text: String, maxLen: Int): List<String> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxLen) return listOf(text)

        val chunks = mutableListOf<String>()
        var byteOffset = 0

        while (byteOffset < bytes.size) {
            val remaining = bytes.size - byteOffset
            if (remaining <= maxLen) {
                chunks.add(String(bytes, byteOffset, remaining, Charsets.UTF_8))
                break
            }

            // End candidate: maxLen bytes from current offset.
            var end = byteOffset + maxLen

            // Walk back to a UTF-8 character boundary (continuation bytes start with 10xxxxxx).
            while (end > byteOffset && (bytes[end].toInt() and 0xC0) == 0x80) end--

            // Decode the candidate slice to find a word-boundary split point.
            val slice = String(bytes, byteOffset, end - byteOffset, Charsets.UTF_8)
            val lastSpace = slice.lastIndexOf(' ')
            val chunk = if (lastSpace > slice.length / 2) slice.substring(0, lastSpace) else slice

            chunks.add(chunk.trim())
            // Advance byte offset by the exact byte count of the chunk we actually kept.
            byteOffset += chunk.toByteArray(Charsets.UTF_8).size
            // Skip any leading whitespace at the new offset to avoid empty chunks.
            while (byteOffset < bytes.size && bytes[byteOffset] == ' '.code.toByte()) byteOffset++
        }

        return chunks.filter { it.isNotEmpty() }
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
     * Request the channel list.
     *
     * On servers that advertise ELIST user-count filtering (ELIST=...U)
     * we always send a range: ">0" (every channel with at least one member)
     * up to [maxUsers], or [DEFAULT_LIST_MAX_USERS] when unspecified, with [minUsers]
     * raising the lower bound. The ListScreen's min/max fields narrow it further. Servers without
     * ELIST U get a plain LIST and the list is filtered client-side.
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

    private fun canonicalIgnoreNick(raw: String): String? {
        val t = raw.trim()
        if (t.isBlank()) return null
        val token = t.split(Regex("\\s+"), limit = 2).firstOrNull()?.trim() ?: return null
        // If it looks like a mask (nick!user@host or *!*@host), keep it as-is (trimmed).
        if (token.contains('!') || token.contains('@') || token.contains('*') || token.contains('?')) {
            return token.takeIf { it.isNotBlank() }
        }
        // Plain nick: strip mode prefix and trailing punctuation.
        val base = token.trimEnd(':', ',').trimStart('~','&','@','%','+')
        if (base.isBlank() || base == "." || base == "..") return null
        val cleaned = base.replace(Regex("[\u0000-\u001F\u007F]"), "").trim()
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun isNickIgnored(netId: String, nick: String?, userHost: String? = null): Boolean {
        val n = nick?.trim().takeIf { !it.isNullOrBlank() } ?: return false
        val base = n.trimStart('~','&','@','%','+')
        val list = _state.value.networks.firstOrNull { it.id == netId }?.ignoredNicks.orEmpty()
        // Build full nick!user@host string for mask matching if we have it.
        val fullMask = if (userHost != null) "$base!$userHost" else base
        return list.any { pattern ->
            if (pattern.contains('*') || pattern.contains('?') || pattern.contains('!')) {
                // Wildcard pattern - convert IRC glob to regex and match against full mask.
                matchIrcGlob(pattern, fullMask)
            } else {
                // Simple exact nick match (original behaviour).
                pattern.equals(base, ignoreCase = true)
            }
        }
    }

    /** Match an IRC-style glob pattern (*, ?) against [input], case-insensitive. */
    private fun matchIrcGlob(pattern: String, input: String): Boolean {
        val regex = buildString {
            append("(?i)\\A")
            for (ch in pattern) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append(".")
                    else -> append(Regex.escape(ch.toString()))
                }
            }
            append("\\z")
        }
        return Regex(regex).containsMatchIn(input)
    }

    /**
     * True when [nick]'s messages on [netId] should NOT raise a highlight or PM
     * notification, per NetworkProfile.highlightIgnoreMasks. Unlike isNickIgnored (which
     * drops the message entirely), this only suppresses the *alert* — the message still
     * lands in its buffer. Matches the bare nick (status prefixes stripped) against each
     * mask as a regex (`/.../`), an IRC glob (`*`/`?`), or a plain case-insensitive nick.
     */
    private fun isNotifyIgnoredSender(netId: String, nick: String?): Boolean {
        val base = nick?.trim()?.trimStart('~', '&', '@', '%', '+').takeIf { !it.isNullOrBlank() }
            ?: return false
        val masks = _state.value.networks.firstOrNull { it.id == netId }?.highlightIgnoreMasks.orEmpty()
        if (masks.isEmpty()) return false
        return masks.any { raw ->
            val m = raw.trim()
            when {
                m.isEmpty() -> false
                m.length >= 2 && m.startsWith("/") && m.endsWith("/") ->
                    runCatching {
                        Regex(m.substring(1, m.length - 1), RegexOption.IGNORE_CASE).containsMatchIn(base)
                    }.getOrDefault(false)
                m.contains('*') || m.contains('?') -> matchIrcGlob(m, base)
                else -> m.equals(base, ignoreCase = true)
            }
        }
    }

    private fun updateNetworkInState(updated: com.boxlabs.hexdroid.data.NetworkProfile) {
        val st = _state.value
        val next = st.networks.map { if (it.id == updated.id) updated else it }
        _state.value = st.copy(networks = next)
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
        append(dest, from = null, text = "*** Ignoring $base", isLocal = true, doNotify = false)
    }

    fun unignoreNick(netId: String, nick: String) {
        val base = canonicalIgnoreNick(nick) ?: return
        val st = _state.value
        val net = st.networks.firstOrNull { it.id == netId } ?: return
        val nextList = net.ignoredNicks.filterNot { it.equals(base, ignoreCase = true) }
        val updated = net.copy(ignoredNicks = nextList)
        updateNetworkInState(updated)
        viewModelScope.launch { repo.upsertNetwork(updated) }
        val sel = _state.value.selectedBuffer
        val (selNet, _) = splitKey(sel)
        val dest = if (sel.isNotBlank() && selNet == netId) sel else bufKey(netId, "*server*")
        append(dest, from = null, text = "*** Unignored $base", isLocal = true, doNotify = false)
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
        append(dest, from = null, text = "*** Notifications muted for $base", isLocal = true, doNotify = false)
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
        append(dest, from = null, text = "*** Notifications unmuted for $base", isLocal = true, doNotify = false)
    }

    fun openIgnoreList() { goTo(AppScreen.IGNORE) }
    // IRC event handling

    private fun handleEvent(netId: String, ev: IrcEvent) {
        when (ev) {
            is IrcEvent.Status -> {
                setNetConn(netId) { it.copy(status = ev.text) }
                // Route through appendConnStatus so rapid retries (every "*** Connecting.."
                // before the next failure) collapse into "(×N)" instead of stacking
                // 7–8 deep between two error lines.
                appendConnStatus(netId, "*** ${ev.text}", from = null, doNotify = false, isHighlight = false)
            }
            is IrcEvent.Connected -> {
                manualDisconnecting.remove(netId)
                // Do NOT reset reconnectAttempts here, the connection may be dropped
                // immediately (Z-line, cert error, etc.).  The backoff is only cleared
                // after STABLE_CONNECTION_MS of uptime (see IrcEvent.Registered).
                stableConnectionJobs.remove(netId)?.cancel() // cancel any leftover timer
                runtimes[netId]?.apply { suppressMotd = _state.value.settings.hideMotdOnConnect; manualMotdAtMs = 0L }
                autoReconnectJobs.remove(netId)?.cancel()
                setNetConn(netId) {
                    // Clear tlsPinMismatch: a Connected event means the TLS handshake AND
                    // the post-handshake pin check both passed (a mismatch would have thrown
                    // during openSocket and we'd never see Connected). The "Reset & re-pin"
                    // and "Trust this server too" buttons hide on the next render. Stashed
                    // actual-fp is dropped because it's now either in the trust set or has
                    // been superseded by a full reset.
                    it.copy(connecting = false, connected = true, status = "Connected to ${ev.server}", lagMs = null, tlsPinMismatch = false, tlsPinMismatchActualFp = null)
                }
                // Arm chathistory marker windows for known PM-style buffers on this network.
                // Bouncer playback delivers PRIVMSGs to query buffers without a corresponding
                // JOIN event, so the JOIN-handler arm in the channel case doesn't cover them.
                // We arm any buffer we already know about that isn't a channel — those are
                // exactly the PM/query buffers that bouncer playback will likely re-deliver.
                // Channel buffers don't need arming here: the bouncer replays our prior
                // session's JOINs, which fire the JOIN handler's arm. 45 s window matches
                // the upstream history-expect ceiling.
                val nowMsArm = System.currentTimeMillis()
                val armDeadline = nowMsArm + 45_000L
                val chantypes = runtimes[netId]?.support?.chantypes ?: "#&+!"
                val pmKeys = _state.value.buffers.keys.filter { k ->
                    val (nid, bn) = splitKey(k)
                    nid == netId && bn != "*server*" && (bn.firstOrNull() !in chantypes.toSet())
                }
                for (k in pmKeys) chathistoryMarkerArmedUntilMs[k] = armDeadline
                if (chathistoryMarkerArmedUntilMs.size > 64) {
                    chathistoryMarkerArmedUntilMs.entries.removeAll { it.value < nowMsArm }
                }
                if (_state.value.activeNetworkId == netId) updateConnectionNotification("Connected")
            }
            is IrcEvent.LagUpdated -> {
                if (!AppVisibility.isForeground) {
                    // Backgrounded: skip the startService() IPC call (notification text
                    // never shows lag values) and skip the state write entirely if the
                    // lag value hasn't changed - every PING/PONG would otherwise trigger
                    // a full Compose recomposition for no visible benefit.
                    val current = _state.value.connections[netId]?.lagMs
                    if (current != ev.lagMs) {
                        _state.update { st ->
                            val old = st.connections[netId] ?: NetConnState()
                            val newConns = st.connections + (netId to old.copy(lagMs = ev.lagMs))
                            syncActiveNetworkSummary(st.copy(connections = newConns))
                        }
                    }
                } else {
                    setNetConn(netId) { it.copy(lagMs = ev.lagMs) }
                }
            }
            is IrcEvent.Disconnected -> {
                // A disconnect cancels the stability timer so a short-lived session
                // (dropped before STABLE_CONNECTION_MS) never clears the backoff counter.
                stableConnectionJobs.remove(netId)?.cancel()
                val r = ev.reason?.trim()
                // Connect failures and mid-stream connection errors arrive prefixed
                // ("Connect failed: ...", "Connection error: ...") - emit those as ERROR
                // styled lines, Tray notifications stay suppressed (isHighlight = false, doNotify = false)
                // because a routine connect-failure-and-retry shouldn't ping the user;
                // the in-buffer error line is enough.
                val isConnectFailureLine = r != null && (
                    r.startsWith("Connect failed:", ignoreCase = true) ||
                    r.startsWith("Connection error:", ignoreCase = true) ||
                    r.startsWith("Connection failed:", ignoreCase = true)
                )
                val pretty = when {
                    r.isNullOrBlank() -> "Disconnected"
                    r.equals("Client disconnect", ignoreCase = true) -> "Disconnected"
                    r.equals("EOF", ignoreCase = true) -> "Disconnected"
                    r.equals("socket closed", ignoreCase = true) -> "Disconnected"
                    isConnectFailureLine -> r
                    else -> "Disconnected: $r"
                }
                if (isConnectFailureLine) {
                    appendConnStatus(netId, pretty, from = "ERROR", doNotify = false, isHighlight = false, broadcast = true)
                } else {
                    appendConnStatus(netId, "*** $pretty", from = null, doNotify = false, isHighlight = false, broadcast = true)
                }
                setNetConn(netId) { it.copy(connecting = false, connected = false, status = pretty, lagMs = null) }
                if (_state.value.activeNetworkId == netId) clearConnectionNotification()
                cleanupNetworkMaps(netId)

                // Flap detection: count ping-timeout / dead-socket disconnects within the window.
                //   - "Connection timed out"  : the 150 s SOCKET_READ_TIMEOUT_MS path (the most
                //                               common dead-socket case on mobile, and it fires
                //                               BEFORE the 180 s client-ping timeout).
                //   - "read timed out"        : raw SocketTimeoutException message (rare; usually
                //                               overridden to "Connection timed out" above).
                //   - "connection reset"      : friendlyErrorMessage("Connection reset by server").
                //   - "ping timeout"          : server-sent "Closing Link: ... (Ping timeout)".
                val isPingTimeout = r != null && (
                    r.contains("ping timeout", ignoreCase = true) ||
                    r.contains("ping time out", ignoreCase = true) ||
                    r.contains("connection reset", ignoreCase = true) ||
                    r.contains("connection timed out", ignoreCase = true) ||
                    r.contains("SocketTimeout", ignoreCase = true) ||
                    r.contains("read timed out", ignoreCase = true)
                )
                if (isPingTimeout) {
                    val now = System.currentTimeMillis()
                    val q = pingTimeoutTimestamps.getOrPut(netId) { ArrayDeque() }
                    q.addLast(now)
                    // Drop events older than the flap window.
                    while (q.isNotEmpty() && now - q.first() > ConnectionConstants.FLAP_WINDOW_MS) {
                        q.removeFirst()
                    }
                    if (q.size >= ConnectionConstants.FLAP_THRESHOLD && !flapPaused.contains(netId)) {
                        markFlapPaused(netId)
                        val serverKey = bufKey(netId, "*server*")
                        append(serverKey, from = null, text =
                            "*** ⚠ Connection is unstable - ${q.size} ping timeouts in the last " +
                            "${ConnectionConstants.FLAP_WINDOW_MS / 60000} minutes. " +
                            "Auto-reconnect paused to avoid flooding the server. " +
                            "Tap 'Reconnect' to try again when your network is stable.",
                            doNotify = false)
                        setNetConn(netId) { it.copy(status = "Unstable - reconnect manually") }
                    }
                }

                val wasManual = manualDisconnecting.remove(netId)
                if (wasManual && !desiredConnected.contains(netId)) return

                // Don't auto-reconnect if flap detection has paused this network.
                if (flapPaused.contains(netId)) {
                    setNetConn(netId) { it.copy(status = "Unstable - reconnect manually") }
                    return
                }

                // Disconnect reasons that won't recover by retrying are split into two
                // categories so the user gets an actionable message specific to the
                // failure mode. In both cases we halt auto-reconnect via authBlockedReconnect
                // without this, a misconfigured network cycles through the exponential backoff forever,
                // hitting the same failure each time and burning battery.
                if (r != null && netId !in authBlockedReconnect) {
                    val tlsUnrecoverable =
                        r.contains("TLS certificate verification failed", ignoreCase = true) ||
                        r.contains("TLS handshake rejected by server", ignoreCase = true) ||
                        r.contains("TLS pin enforcement failed", ignoreCase = true) ||
                        r.contains("server may not support TLS", ignoreCase = true)
                    // "Server doesn't exist" class: the hostname doesn't resolve, or it
                    // resolves but nothing is listening on the configured port. Patterns
                    // cover Android's libcore DNS resolver, the bare JVM exception name
                    // (in case it leaks through wrapping), the libc-level messages from
                    // glibc/BSD, and the TCP-level "connection refused"
                    val hostUnreachable =
                        r.contains("Unable to resolve host", ignoreCase = true) ||
                        r.contains("UnknownHostException", ignoreCase = true) ||
                        r.contains("No address associated with hostname", ignoreCase = true) ||
                        r.contains("nodename nor servname provided", ignoreCase = true) ||
                        r.contains("Name or service not known", ignoreCase = true) ||
                        r.contains("Connection refused", ignoreCase = true)
                    // "Server rejected the connection" class: the TCP+TLS handshake succeeded
                    // and the server then told us, in plain words, that it won't have us. The
                    // wire form is typically a raw `ERROR :Closing Link: <addr> (<reason>)`
                    // frame followed by the socket close; the `<reason>` text comes through as
                    // part of `r`.
                    //
                    // We can't blanket-halt on "Closing Link" because the SAME framing is
                    // used for routine drops the user DOES want auto-reconnected, most
                    // notably "Closing Link: nick[host] (Ping timeout: 240 seconds)". So we
                    // match the specific reasons that mean "your reconnect will hit the same
                    // wall":
                    //
                    //   SASL-required class      bouncer/server demands SASL we don't provide
                    //   K/G/Z/D/X-line class     IRCd ban
                    //   bad-password class       PASS rejected via raw ERROR rather than the
                    //                            464 numeric
                    //   connection-limit class   server is at capacity OR we've hit a
                    //                            per-IP / per-account connection cap
                    //   access-denied / banned   catch-all for "you're not welcome here"
                    val lowerR = r.lowercase()
                    val recentServerErrorText = lastServerErrorByNet[netId]?.let { (msg, ts) ->
                        if (System.currentTimeMillis() - ts < SERVER_ERROR_DISCONNECT_CORRELATION_MS)
                            msg.lowercase()
                        else null
                    }
                    fun anyMatches(vararg needles: String): Boolean = needles.any { n ->
                        lowerR.contains(n) || (recentServerErrorText?.contains(n) ?: false)
                    }
                    val serverRejection =
                        // SASL-required class
                        anyMatches(
                            "sasl required", "sasl needed", "sasl is required",
                            "you must authenticate", "authentication required",
                        ) ||
                        // *-lined: hyphenated and concatenated forms
                        anyMatches(
                            "k-lined", "klined",
                            "g-lined", "glined",
                            "z-lined", "zlined",
                            "d-lined", "dlined",
                            "x-lined", "xlined",
                            "you are banned",
                            "banned from this server", "banned from server",
                        ) ||
                        // bad password via raw ERROR
                        anyMatches(
                            "bad password", "password incorrect",
                            "password mismatch", "invalid password",
                        ) ||
                        // connection-limit
                        anyMatches(
                            "too many connections", "too many clients", "too many users",
                            "connection limit", "max clients",
                        ) ||
                        // access-denied
                        anyMatches("access denied", "not authorized", "not authorised")
                    if (tlsUnrecoverable) {
                        authBlockedReconnect.add(netId)
                        appendConnStatus(
                            netId = netId,
                            text = "*** Auto-reconnect halted: TLS error is unlikely to fix itself. " +
                                   "Adjust certificate trust settings, then reconnect manually.",
                            from = null,
                            doNotify = false,
                            isHighlight = false,
                            broadcast = true,
                        )
                        setNetConn(netId) { it.copy(status = "TLS error: reconnect halted") }
                        return
                    }
                    // Only treat host-unreachable as a permanent halt if we never managed
                    // to register on this server this session. A network that DID register
                    // has a known-good host/port, so a sudden "unable to resolve host" /
                    // "connection refused" is almost always transient.
                    if (hostUnreachable && !everRegisteredThisSession.contains(netId)) {
                        authBlockedReconnect.add(netId)
                        appendConnStatus(
                            netId = netId,
                            text = "*** Auto-reconnect halted: server unreachable (hostname doesn't resolve " +
                                   "or port is closed). Check the host and port in Network Settings, " +
                                   "then reconnect manually.",
                            from = null,
                            doNotify = false,
                            isHighlight = false,
                            broadcast = true,
                        )
                        setNetConn(netId) { it.copy(status = "Server unreachable: reconnect halted") }
                        return
                    }
                    if (serverRejection) {
                        authBlockedReconnect.add(netId)
                        appendConnStatus(
                            netId = netId,
                            text = "*** Auto-reconnect halted: server rejected the connection.",
                            from = null,
                            doNotify = false,
                            isHighlight = false,
                            broadcast = true,
                        )
                        setNetConn(netId) { it.copy(status = "Server rejected connection: reconnect halted") }
                        return
                    }

                }

                if (desiredConnected.contains(netId)) scheduleAutoReconnect(netId)
            }
            is IrcEvent.Error -> {
                val msg = ev.message
                // "Transient" errors are routine connectivity blips that don't need to ping
                // the user or bump the highlight badge: connect failures, socket-level
                // timeouts, network resets, server-side closing-link messages from short
                // disconnects, etc. Anything not matched here is treated as a genuine error
                // We dedup either way so a flapping connection can't fill the buffer with identical
                // "Read timed out" lines.
                val lower = msg.lowercase()
                val isTransient = msg.startsWith("Connect failed", ignoreCase = true) ||
                    msg.startsWith("Connection failed", ignoreCase = true) ||
                    lower.contains("read timed out") ||
                    lower.contains("ping timeout") ||
                    lower.contains("connection reset") ||
                    lower.contains("broken pipe") ||
                    lower.contains("closing link") ||
                    lower.contains("socket closed") ||
                    lower.contains("network is unreachable") ||
                    lower.contains("software caused connection abort")
                appendConnStatus(
                    netId = netId,
                    text = msg,
                    from = "ERROR",
                    isHighlight = !isTransient,
                    doNotify = !isTransient,
                    isError = true,
                )
            }
            is IrcEvent.AuthFailed -> {
                /*
                * Auth failures
                *
                *   PASS (464 ERR_PASSWDMISMATCH): the server REJECTS the connection
                *      entirely. The TCP socket is closed shortly after. Retrying with
                *      the same credentials trips the same rejection. On bouncers,
                *      repeated PASS failures can rate-limit or IP-ban us.
                *        > Halt auto-reconnect via authBlockedReconnect, regardless of
                *          whether the upstream is a bouncer.
                *
                *   SASL (904 / 905 / 906) on a direct IRC server: the server rejects
                *      only the auth bundle, not the connection. SASL is optional,
                *      the session proceeds.
                *       > Warn the user but do NOT halt: a later ping-timeout drop on
                *         this still-useful session should reconnect normally.
                *
                *   SASL on a bouncer (profile.isBouncer): the bouncer REQUIRES SASL
                *      to route us to an upstream and will drop the connection within
                *      seconds of the 904/905/906. Without a halt, we re-connect > re-
                *      fail SASL > re-drop in a tight loop bounded only by the backoff,
                *      flooding the bouncer's logs and burning battery for nothing.
                *       >  Halt the same way as PASS.
                */
                val profile = _state.value.networks.firstOrNull { it.id == netId }
                val isBouncerProfile = profile?.isBouncer == true
                val isPassFailure = ev.source.equals("PASS", ignoreCase = true)
                val isSaslFailure = ev.source.equals("SASL", ignoreCase = true)
                val shouldHalt = isPassFailure || (isSaslFailure && isBouncerProfile)
                if (shouldHalt) {
                    authBlockedReconnect.add(netId)
                }
                val hint = when {
                    isPassFailure -> "Server password (PASS) rejected. " +
                        "If this is a bouncer profile, the password format is usually " +
                        "user:password or user/network:password."
                    isSaslFailure && isBouncerProfile -> "SASL authentication rejected. " +
                        "Bouncers require valid SASL to route the connection. Check the " +
                        "SASL username/password (or client certificate) on this profile."
                    isSaslFailure -> "SASL authentication rejected. " +
                        "Check the SASL username/password " +
                        "(or client certificate) on this profile."
                    else -> "Authentication rejected."
                }
                val haltSuffix = if (shouldHalt)
                    " Auto-reconnect halted; tap reconnect after fixing credentials."
                else ""
                appendConnStatus(
                    netId = netId,
                    text = "*** ${ev.reason} — $hint$haltSuffix",
                    from = "AUTH",
                    isHighlight = false,
                    doNotify = false,
                    broadcast = true,
                )
            }

            is IrcEvent.TlsFingerprintLearned -> {
                // First time we see this server's TLS certificate - persist the fingerprint so
                // future connections use TOFU pinning instead of trusting all certs blindly.
                val fp = ev.fingerprint
                viewModelScope.launch {
                    try {
                        repo.updateNetworkProfile(netId) { it.copy(tlsTofuFingerprint = fp) }
                        append(
                            bufKey(netId, "*server*"), from = null,
                            text = "*** TLS: Certificate fingerprint learned and pinned (TOFU). " +
                                   "Future connections will verify: $fp",
                            doNotify = false
                        )
                    } catch (t: Throwable) {
                        append(bufKey(netId, "*server*"), from = null,
                            text = "*** TLS: Could not persist certificate fingerprint: ${t.message}", doNotify = false)
                    }
                }
            }

            is IrcEvent.TlsHostnameMismatch -> {
                // Soft warning: the cert chains to a CA but its SAN list doesn't cover the
                // host we connected to. Connection has already been allowed to proceed - the
                // user's choice of cert-trust posture is "best-effort with TOFU as the strict
                // option", not "refuse on every SAN typo". Surface it once per session so the
                // user can spot it and decide whether to pin.
                val sansStr = if (ev.sans.isEmpty()) "(none)" else ev.sans.joinToString(", ")
                append(
                    bufKey(netId, "*server*"), from = "TLS",
                    text = "*** Certificate hostname mismatch: connected to ${ev.expected} but cert SANs are: $sansStr. " +
                           "Connection allowed.",
                    doNotify = false
                )
            }

            is IrcEvent.TlsFingerprintChanged -> {
                // The server is presenting a DIFFERENT certificate than the ones we trust.
                // Two distinct legitimate cases:
                //   - Cert renewal/rotation. The whole pin set is stale; the user resets and
                //     re-pins.
                //   - Round-robin DNS landed on a different server with its own cert. The
                //     user grows the trust set with "Trust this server too" - the previous
                //     fingerprints stay, the new one is added, and future connects to either
                //     server succeed without further intervention.
                //
                // Halt auto-reconnect either way: silently retrying every few seconds against
                // a possibly-malicious endpoint floods the server buffer with TLS WARNING
                // lines and risks the user missing the original alert. Block clears on the
                // next manual reconnect, like authBlockedReconnect.
                authBlockedReconnect.add(netId)
                // Stash the actual fingerprint so NetworkEditScreen can offer the "Trust
                // this server too" action without re-deriving the value from somewhere.
                setNetConn(netId) {
                    it.copy(tlsPinMismatch = true, tlsPinMismatchActualFp = ev.actual)
                }
                append(
                    bufKey(netId, "*server*"), from = "TLS WARNING", isHighlight = true,
                    text = "⚠️  Server certificate fingerprint has CHANGED! " +
                           "Expected: ${ev.stored}  •  Got: ${ev.actual}  - " +
                           "Connection refused. Auto-reconnect halted. " +
                           "If this server uses round-robin DNS (irc.example.tld), " +
                           "open Network Settings and tap 'Trust this server too' to add the new " +
                           "fingerprint without losing the others. " +
                           "If the cert was rotated/renewed, tap 'Reset & re-pin' instead - " +
                           "that discards every previously-trusted fingerprint and re-learns from scratch."
                )
            }
            is IrcEvent.ServerLine -> {
                val stNow = _state.value
                if (stNow.settings.loggingEnabled && stNow.settings.logServerBuffer) {
                    val netName = stNow.networks.firstOrNull { it.id == netId }?.name ?: netId
                    val ts = System.currentTimeMillis()
                    val line = ev.line
                    val logLine = formatLogLine(ts, from = null, text = line, isAction = false)
                    val logFolderUri = stNow.settings.logFolderUri
                    // Dispatch to IO so the disk/SAF write doesn't run on Main. See the
                    // matching comment in append() for the full rationale - same race
                    // between handleEvent now running on Main.immediate and LogWriter
                    // doing synchronous buffered writes that occasionally flush.
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { logs.append(netName, "*server*", logLine, logFolderUri) }
                    }
                }
                // PONG handling and lag measurement are done in IrcCore; LagUpdated events update the UI.
            }
            is IrcEvent.ServerText -> {
                val code = ev.code
                val rt = runtimes[netId]
                val motdCodes = setOf("375","372","376","422")
                val hideMotd = _state.value.settings.hideMotdOnConnect
                val now = System.currentTimeMillis()
                val manualMotdActive = rt?.manualMotdAtMs?.let { it != 0L && now - it < 60_000L } == true
                // Never suppress bouncer MOTD - it contains useful status (e.g. which upstream networks are connected).
                val isBouncer = _state.value.networks.firstOrNull { it.id == netId }?.isBouncer == true
                if (!manualMotdActive && hideMotd && !isBouncer && code != null && code in motdCodes) {
                    // Some connect paths can build the runtime before settings are loaded; re-arm suppression here too.
                    if (rt != null && !rt.suppressMotd) rt.suppressMotd = true
                    if (rt?.suppressMotd != false) {
                        // Suppress automatic MOTD output on connect if configured
                        if (code == "376" || code == "422") rt?.suppressMotd = false
                        return
                    }
                }
                val targetKey = if (!ev.bufferName.isNullOrBlank() && ev.bufferName != "*server*") {
                    resolveBufferKey(netId, ev.bufferName)
                } else {
                    bufKey(netId, "*server*")
                }
                val isMotdLine = code == "372"
                append(targetKey, from = null, text = ev.text, doNotify = false, isMotd = isMotdLine)

if (code == "442") {
    // Not on channel. If this was triggered by the UI close-buffer flow, remove the buffer anyway.
    val chan = Regex("([#&+!][^\\s]+)").find(ev.text)?.groupValues?.getOrNull(1)
    val key = if (chan != null) {
        popPendingCloseForChannel(netId, chan)
    } else {
        pendingCloseAfterPart.firstOrNull { it.startsWith("$netId::") }?.also { pendingCloseAfterPart.remove(it) }
    }

    if (key != null) {
        chanNickCase.remove(key)
        chanNickStatus.remove(key)
        removeBuffer(key)
    }
}
                if (code == "376" || code == "422") {
                    // End of MOTD (or no MOTD) - stop suppressing for this session
                    if (rt != null) { rt.suppressMotd = false; rt.manualMotdAtMs = 0L }
                }
            }

            is IrcEvent.JoinError -> {
                val st = _state.value
                val chanKey = resolveBufferKey(netId, ev.channel)
                val dest = when {
                    st.buffers.containsKey(chanKey) -> chanKey
                    splitKey(st.selectedBuffer).first == netId -> st.selectedBuffer
                    else -> bufKey(netId, "*server*")
                }
                append(dest, from = null, text = "*** ${ev.message}", doNotify = false)
            }
            is IrcEvent.ChannelModeIs -> {
                val st = _state.value
                val chanKey = resolveBufferKey(netId, ev.channel)
                val dest = if (st.buffers.containsKey(chanKey)) chanKey else bufKey(netId, "*server*")
                append(dest, from = null, text = "* Mode ${ev.channel} ${ev.modes}", doNotify = false)
                // Store mode string so Channel Tools can show/toggle modes
                val buf = st.buffers[chanKey]
                if (buf != null) {
                    val modeOnly = ev.modes.split(" ").firstOrNull() ?: ev.modes
                    _state.update { it.copy(buffers = it.buffers + (chanKey to buf.copy(modeString = modeOnly))) }
                }
            }

            is IrcEvent.YoureOper -> {
                append(bufKey(netId, "*server*"), from = null, text = "*** ${ev.message}", doNotify = false)
                setNetConn(netId) { it.copy(isIrcOper = true) }
            }
            is IrcEvent.YoureDeOpered -> {
                setNetConn(netId) { it.copy(isIrcOper = false) }
            }

            is IrcEvent.BanListItem -> {
                val st0 = _state.value
                val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread
                val chanKey = resolveBufferKey(netId, ev.channel)
                ensureBuffer(chanKey)

                val cur = st0.banlists[chanKey].orEmpty()
                val nextList = (cur + BanEntry(ev.mask, ev.setBy, ev.setAtMs)).distinctBy { it.mask }
                _state.value = syncActiveNetworkSummary(
                    st0.copy(
                        banlists = st0.banlists + (chanKey to nextList),
                        banlistLoading = st0.banlistLoading + (chanKey to true)
                    )
                )

                // Don't spam the channel buffer with every ban entry.
                // (Users can view them via the Channel tools -> Ban list UI.)
            }

            is IrcEvent.BanListEnd -> {
                val st0 = _state.value
                val chanKey = resolveBufferKey(netId, ev.channel)
                _state.value = syncActiveNetworkSummary(
                    st0.copy(banlistLoading = st0.banlistLoading + (chanKey to false))
                )
            }

            is IrcEvent.QuietListItem -> {
                val st0 = _state.value
                val chanKey = resolveBufferKey(netId, ev.channel)
                ensureBuffer(chanKey)
                val cur = st0.quietlists[chanKey].orEmpty()
                val nextList = (cur + BanEntry(ev.mask, ev.setBy, ev.setAtMs)).distinctBy { it.mask }
                _state.value = syncActiveNetworkSummary(
                    st0.copy(
                        quietlists = st0.quietlists + (chanKey to nextList),
                        quietlistLoading = st0.quietlistLoading + (chanKey to true)
                    )
                )
            }

            is IrcEvent.QuietListEnd -> {
                val st0 = _state.value
                val chanKey = resolveBufferKey(netId, ev.channel)
                _state.value = syncActiveNetworkSummary(
                    st0.copy(quietlistLoading = st0.quietlistLoading + (chanKey to false))
                )
            }

            is IrcEvent.ExceptListItem -> {
                val st0 = _state.value
                val chanKey = resolveBufferKey(netId, ev.channel)
                ensureBuffer(chanKey)
                val cur = st0.exceptlists[chanKey].orEmpty()
                val nextList = (cur + BanEntry(ev.mask, ev.setBy, ev.setAtMs)).distinctBy { it.mask }
                _state.value = syncActiveNetworkSummary(
                    st0.copy(
                        exceptlists = st0.exceptlists + (chanKey to nextList),
                        exceptlistLoading = st0.exceptlistLoading + (chanKey to true)
                    )
                )
            }

            is IrcEvent.ExceptListEnd -> {
                val st0 = _state.value
                val chanKey = resolveBufferKey(netId, ev.channel)
                _state.value = syncActiveNetworkSummary(
                    st0.copy(exceptlistLoading = st0.exceptlistLoading + (chanKey to false))
                )
            }

            is IrcEvent.InvexListItem -> {
                val st0 = _state.value
                val chanKey = resolveBufferKey(netId, ev.channel)
                ensureBuffer(chanKey)
                val cur = st0.invexlists[chanKey].orEmpty()
                val nextList = (cur + BanEntry(ev.mask, ev.setBy, ev.setAtMs)).distinctBy { it.mask }
                _state.value = syncActiveNetworkSummary(
                    st0.copy(
                        invexlists = st0.invexlists + (chanKey to nextList),
                        invexlistLoading = st0.invexlistLoading + (chanKey to true)
                    )
                )
            }

            is IrcEvent.InvexListEnd -> {
                val st0 = _state.value
                val chanKey = resolveBufferKey(netId, ev.channel)
                _state.value = syncActiveNetworkSummary(
                    st0.copy(invexlistLoading = st0.invexlistLoading + (chanKey to false))
                )
            }
            is IrcEvent.ISupport -> {
                val rt = runtimes[netId]
                if (rt != null) {
                    rt.support = NetSupport(
                        chantypes = ev.chantypes,
                        caseMapping = ev.caseMapping,
                        prefixModes = ev.prefixModes,
                        prefixSymbols = ev.prefixSymbols,
                        statusMsg = ev.statusMsg,
                        chanModes = ev.chanModes,
                        linelen = ev.linelen,
                        elist = ev.elist
                    )
                }

                // Surface server-side LIST user-count filtering ("LIST >N") to the channel-list UI
                if (netId == _state.value.activeNetworkId) {
                    _state.update { it.copy(listElistUserFilter = ev.elist?.contains('U') == true) }
                }

                // Expose list modes to the UI so the Channel lists sheet can adapt per-ircd.
                val listModes = ev.chanModes
                    ?.split(',')
                    ?.getOrNull(0)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                if (listModes != null) {
                    setNetConn(netId) { it.copy(listModes = listModes) }
                }
            }

            is IrcEvent.Registered -> {
                runtimes[netId]?.myNick = ev.nick
                val rt0 = runtimes[netId]
                val hasReact = rt0 != null &&
                    (rt0.client.hasCap("message-tags") || rt0.client.hasCap("draft/message-reactions"))
                setNetConn(netId) { it.copy(myNick = ev.nick, hasReactionSupport = hasReact) }
                append(bufKey(netId, "*server*"), from = null, text = "*** Registered as ${ev.nick}", doNotify = false)
                // If this was a reconnect (we had been retrying), announce success in every
                // channel/query buffer so the user reading a channel sees their connection
                // come back without having to switch to the *server* buffer. Heuristic:
                // reconnectAttempts[netId] > 0 means scheduleAutoReconnect had at least
                // one round of backoff, i.e. this is a recovery, not a first connect.
                // (For first connects, the user has just chosen to connect and doesn't
                // need a "Reconnected" line in every channel they're about to join.)
                //
                // Done BEFORE resetConnStatusDedup so the dedup tracker, which is holding
                // the previous "*** Disconnected:.." entry doesn't suppress the announce.
                // Reconnected and Disconnected have different keys (different text), so
                // they wouldn't collide anyway, but the ordering keeps intent obvious.
                val wasReconnect = (reconnectAttempts[netId] ?: 0) > 0
                if (wasReconnect) {
                    appendConnStatus(
                        netId = netId,
                        text = "*** Reconnected",
                        from = null,
                        doNotify = false,
                        isHighlight = false,
                        broadcast = true,
                    )
                }

                // Successful registration is the natural reset point for connection-status
                // dedup: if the connection drops AGAIN for the same reason after this, the
                // user has just been online long enough to care about seeing it logged.
                resetConnStatusDedup(netId)

                // Start the stability timer.  Only once this fires do we consider the
                // connection stable enough to reset the exponential backoff counter.
                stableConnectionJobs.remove(netId)?.cancel()
                stableConnectionJobs[netId] = viewModelScope.launch {
                    delay(ConnectionConstants.STABLE_CONNECTION_MS)
                    reconnectAttempts.remove(netId)
                    stableConnectionJobs.remove(netId)
                }

                val rt = runtimes[netId] ?: return
                val profile = _state.value.networks.firstOrNull { it.id == netId }

                // On any RE-connect (auto-reconnect, manual "Reconnect", or manual
                // disconnect→connect) the channels we re-join — and, for bouncers, the JOINs
                // the bouncer replays for our prior session — arrive as self-JOIN echoes.
                // Suppress the JOIN handler's automatic buffer switch for that window so the
                // user stays on the buffer they were reading instead of being yanked onto
                // whichever channel happens to JOIN last.
                //
                // We key this off "has this network ever registered this session" rather than
                // the backoff counter: a manual reconnect clears reconnectAttempts, so the old
                // wasReconnect check treated manual reconnects as first connects and still
                // switched. A genuine FIRST connect (set.add returns true) deliberately does
                // NOT arm, so initial autojoin still lands the user in a channel.
                val firstRegistrationThisSession = everRegisteredThisSession.add(netId)
                if (!firstRegistrationThisSession) {
                    rt.suppressAutoJoinSwitchUntilMs =
                        System.currentTimeMillis() + AUTO_JOIN_SWITCH_SUPPRESS_MS
                }

                viewModelScope.launch {
                    // Service auth command (e.g. /msg NickServ IDENTIFY password)
                    //    Runs first, before autojoin, so channels with +r can be joined.
                    profile?.serviceAuthCommand?.takeIf { it.isNotBlank() }?.let { cmd ->
                        val trimmed = cmd.trim()
                        if (trimmed.startsWith("/")) {
                            // Client command aliases
                            rt.client.handleSlashCommand(trimmed.drop(1), "*server*")
                        } else {
                            // Raw IRC line
                            rt.client.sendRaw(trimmed)
                        }
                    }

                    // 3. Optional delay before autojoin & commands
                    //    Gives services time to identify/cloak before joining channels.
                    val delaySec = profile?.autoCommandDelaySeconds ?: 0
                    if (delaySec > 0) {
                        append(bufKey(netId, "*server*"), from = null,
                            text = "*** Waiting ${delaySec}s before auto-join & commands…", doNotify = false)
                        delay(delaySec * 1000L)
                    }

                    // 4. Autojoin channels (skipped for bouncers - they keep you joined server-side)
                    if (!rt.client.config.isBouncer) {
                        val aj = rt.client.config.autoJoin
                        for (c in aj) {
                            val join = if (c.key.isNullOrBlank()) "JOIN ${c.channel}" else "JOIN ${c.channel} ${c.key}"
                            rt.client.sendRaw(join)
                        }

                        // Rejoin channels the user joined manually (outside autoJoin) that were
                        // lost when the connection dropped.
                        for ((chan, key) in rt.manuallyJoinedChannels.toMap()) {
                            val join = if (key.isNullOrBlank()) "JOIN $chan" else "JOIN $chan $key"
                            rt.client.sendRaw(join)
                        }
                    }

                    // 5. Post-connect commands (one per line, like mIRC's Perform)
                    //    Supports both /slash commands and raw IRC lines.
                    //    Per-command delay: append  ;wait N  or  ;wait Ns  to a line
                    //    (e.g. "/msg NickServ identify pass ;wait 3") to pause N seconds
                    //    after that command before sending the next one.
                    profile?.autoCommandsText?.takeIf { it.isNotBlank() }?.let { text ->
                        // Trim each line so that accidental leading/trailing whitespace does not
                        // cause commands to be sent verbatim with a leading space (silent failure).
                        val waitRegex = Regex("""\s*;wait\s+(\d+)s?\s*$""", RegexOption.IGNORE_CASE)
                        val commands = text.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        for (rawLine in commands) {
                            // Extract optional ;wait N suffix before sending.
                            val waitMatch = waitRegex.find(rawLine)
                            val waitMs = waitMatch?.groupValues?.get(1)?.toLongOrNull()
                                ?.coerceIn(1L, 300L)?.times(1000L) ?: 0L
                            val cmd = if (waitMatch != null) rawLine.substring(0, waitMatch.range.first).trim()
                                      else rawLine

                            if (cmd.isNotEmpty()) {
                                if (cmd.startsWith("/")) {
                                    rt.client.handleSlashCommand(cmd.drop(1), "*server*")
                                } else {
                                    rt.client.sendRaw(cmd)
                                }
                            }

                            if (waitMs > 0) {
                                append(bufKey(netId, "*server*"), from = null,
                                    text = "*** Waiting ${waitMs / 1000}s…", doNotify = false)
                                delay(waitMs)
                            }
                        }
                    }
                }
            }
            is IrcEvent.NickChanged -> {
                val st0 = _state.value
                val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread

                val my = st0.connections[netId]?.myNick ?: runtimes[netId]?.myNick ?: st0.myNick
                val isMe = casefoldText(netId, ev.oldNick) == casefoldText(netId, my)

                // Show nick changes in-channel:
                //   * old is now known as new
                //   * You are now known as new
                val line = if (isMe) "* You are now known as ${ev.newNick}"
                else "* ${ev.oldNick} is now known as ${ev.newNick}"

                // Determine which channel buffers to print to.
                // Prefer channels where we currently see the old nick in the nicklist; otherwise
                // fall back to all joined channels for this network.
                val affectedChannels = st0.nicklists
                    .filterKeys { it.startsWith("$netId::") }
                    .filter { (k, list) ->
                        val (_, name) = splitKey(k)
                        isChannelOnNet(netId, name) &&
                            list.any { parseNickWithPrefixes(netId, it).first.let { b -> casefoldText(netId, b) == casefoldText(netId, ev.oldNick) } }
                    }
                    .map { it.key }

                val allChannelTargets = st0.buffers.keys
                    .filter { it.startsWith("$netId::") }
                    .filter { key ->
                        val (_, name) = splitKey(key)
                        isChannelOnNet(netId, name)
                    }

                val targets = when {
                    affectedChannels.isNotEmpty() -> affectedChannels
                    allChannelTargets.isNotEmpty() -> allChannelTargets
                    else -> emptyList()
                }

                val lineColoured = colorEvent(line, 10)  // cyan
                for (k in targets) {
                    append(
                        k,
                        from = null,
                        text = lineColoured,
                        isLocal = suppressUnread,
                        timeMs = ev.timeMs,
                        doNotify = false
                    )
                }

                // If we couldn't attribute this nick to any channel buffers, surface it in the server buffer.
                if (targets.isEmpty()) {
                    append(
                        bufKey(netId, "*server*"),
                        from = null,
                        text = lineColoured,
                        isLocal = suppressUnread,
                        timeMs = ev.timeMs,
                        doNotify = false
                    )
                }

                if (!ev.isHistory) {
                    // If it's our nick, update runtime + UI connection state first.
                    if (isMe) {
                        runtimes[netId]?.myNick = ev.newNick
                        setNetConn(netId) { it.copy(myNick = ev.newNick) }
                    }

                    // Re-read state after appends/setNetConn so we don't overwrite newer state.
                    val st1 = _state.value

                    
                    // Update nicklists for this network (multi-status safe).
                    moveNickAcrossChannels(netId, ev.oldNick, ev.newNick)

                    // Transfer away state from old nick to new nick.
                    val awayMap = nickAwayState[netId]
                    if (awayMap != null) {
                        val oldFold = casefoldText(netId, ev.oldNick)
                        val newFold = casefoldText(netId, ev.newNick)
                        awayMap.remove(oldFold)?.let { awayMap[newFold] = it }
                    }

                    val updatedNicklists = st1.nicklists.mapValues { (k, list) ->
                        val (kid, _) = splitKey(k)
                        if (kid != netId) list
                        else rebuildNicklist(netId, k)
                    }

                    // Drop the old nick's typing indicator from all channel buffers on this network.
                    // The new nick hasn't sent a TAGMSG typing event yet, so don't carry it over.
                    val updatedBufs = st1.buffers.mapValues { (k, buf) ->
                        if (k.startsWith("$netId::") && ev.oldNick in buf.typingNicks)
                            buf.copy(typingNicks = buf.typingNicks - ev.oldNick)
                        else buf
                    }

                    var next = st1.copy(nicklists = updatedNicklists, buffers = updatedBufs)
                    // Rename private-message buffer key if present.
                    val oldKey = bufKey(netId, ev.oldNick)
                    val newKey = bufKey(netId, ev.newNick)
                    if (next.buffers.containsKey(oldKey) && !next.buffers.containsKey(newKey)) {
                        val b = next.buffers[oldKey]
                        if (b != null) next = next.copy(
                            buffers = (next.buffers - oldKey) + (newKey to b.copy(name = newKey)),
                            selectedBuffer = if (next.selectedBuffer == oldKey) newKey else next.selectedBuffer
                        )
                    }

                    _state.value = syncActiveNetworkSummary(next)
                }
            }

            is IrcEvent.DccOfferEvent -> {
                if (isNickIgnored(netId, ev.offer.from)) return

                val offer0 = ev.offer.copy(netId = netId)

                // If this is a passive/reverse DCC reply for one of our outgoing sends, consume it.
                val baseName = offer0.filename.substringAfterLast('/').substringAfterLast('\\')
                val token = offer0.token
                if (token != null) {
                    val pending = pendingPassiveDccSends[token]
                    if (pending != null
                        && pending.target.equals(offer0.from, ignoreCase = true)
                        && pending.filename == baseName
                        && offer0.port > 0
                        && (offer0.size == 0L || pending.size == 0L || offer0.size == pending.size)
                    ) {
                        pendingPassiveDccSends.remove(token)
                        pending.reply.complete(offer0)
                        return
                    }
                } else {
                    // Fallback: some clients (or bouncers) reply without a token; match by target+filename(+size).
                    val match = pendingPassiveDccSends.entries.firstOrNull { (_, p) ->
                        p.target.equals(offer0.from, ignoreCase = true)
                            && p.filename == baseName
                            && offer0.port > 0
                            && (offer0.size == 0L || p.size == 0L || offer0.size == p.size)
                    }
                    if (match != null) {
                        pendingPassiveDccSends.remove(match.key)
                        match.value.reply.complete(offer0)
                        return
                    }
                }

                val st = _state.value
                _state.value = st.copy(dccOffers = st.dccOffers + offer0)
                append(bufKey(netId, "*server*"), from = null, text = "*** Incoming DCC file offer from ${offer0.from}: ${offer0.filename} (Transfers screen to accept)")
                if (st.settings.notificationsEnabled) {
                    notifier.notifyDccIncomingFile(netId, offer0.from, baseName)
                }
            }

            is IrcEvent.DccResumeRequest -> {
                // Peer wants to resume one of our outgoing sends. Find the matching live
                // send by (peer, basename, port-or-token) and, if the position is in range,
                // reply with DCC ACCEPT so the send path can seek the file to that offset.
                val r = ev.resume
                val from = ev.from
                val baseName = r.filename.substringAfterLast('/').substringAfterLast('\\')
                if (isNickIgnored(netId, from)) return

                // Two ways to match: passive (token-based) or active (port-based).
                val match = liveOutgoingSends.values.firstOrNull { live ->
                    if (!live.target.equals(from, ignoreCase = true)) return@firstOrNull false
                    val nameMatches = live.filename.substringAfterLast('/').substringAfterLast('\\') == baseName
                    if (!nameMatches) return@firstOrNull false
                    if (r.token != null && live.token != null) {
                        live.token == r.token
                    } else {
                        live.port == r.port && r.port > 0
                    }
                }
                if (match == null) {
                    append(bufKey(netId, "*server*"), from = null,
                        text = "*** Ignored DCC RESUME from $from for $baseName — no matching send in progress.",
                        doNotify = false)
                    return
                }
                if (r.position < 0L || r.position >= match.size) {
                    append(bufKey(netId, "*server*"), from = null,
                        text = "*** DCC RESUME from $from has out-of-range position ${r.position} (size ${match.size}); ignoring.",
                        doNotify = false)
                    return
                }

                // Send DCC ACCEPT echoing the same triple/quadruple, then surface the offset
                // to whoever is waiting on the LiveOutgoingSend's resumeRequest deferred.
                // handleEvent isn't suspend, so the CTCP send hops to a coroutine; the
                // deferred-completion below stays on the event thread so the awaiting
                // sender sees the offset without an extra dispatch.
                val rt = runtimes[netId] ?: return
                val c = rt.client
                val name = quoteDccFilenameIfNeeded(match.filename)
                val tokenField = if (r.token != null) " ${r.token}" else if (match.token != null) " ${match.token}" else ""
                val payload = "DCC ACCEPT $name ${r.port} ${r.position}$tokenField"
                viewModelScope.launch { runCatching { c.ctcp(from, payload) } }
                append(bufKey(netId, "*server*"), from = null,
                    text = "*** Honouring DCC RESUME from $from at ${r.position} bytes.",
                    doNotify = false)
                // Don't blow up if multiple RESUMEs arrive (unusual but not illegal).
                if (!match.resumeRequest.isCompleted) match.resumeRequest.complete(r.position)
            }

            is IrcEvent.DccAcceptResponse -> {
                // The peer ACCEPTed a RESUME we sent. Hand the accept to the waiting
                // negotiateResumeOrZero() coroutine so it can proceed with the receive.
                val a = ev.accept
                val baseName = a.filename.substringAfterLast('/').substringAfterLast('\\')
                if (isNickIgnored(netId, ev.from)) return
                // The pending map is keyed by (peer, basename, size). We don't have `size`
                // in the ACCEPT payload, so we have to look up by (peer, basename) and
                // tolerate at most one match per peer/basename at a time. Realistically
                // a user never has two distinct in-flight RESUME requests for the same
                // basename from the same peer.
                val prefix = "${ev.from.lowercase()}|$baseName|"
                val key = pendingResumeRequests.keys.firstOrNull { it.startsWith(prefix) }
                if (key != null) {
                    pendingResumeRequests[key]?.complete(a)
                }
            }

            is IrcEvent.DccChatOfferEvent -> {
                if (isNickIgnored(netId, ev.offer.from)) return

                val offer0 = ev.offer.copy(netId = netId)
                val st = _state.value
                // De-dupe by peer + endpoint.
                val exists = st.dccChatOffers.any {
                    it.netId == netId && it.from.equals(offer0.from, ignoreCase = true) && it.ip == offer0.ip && it.port == offer0.port
                }
                if (!exists) {
                    _state.value = st.copy(dccChatOffers = st.dccChatOffers + offer0)
                    // Create the DCC chat buffer immediately so the user can see and act on the
                    // offer without having to navigate to the Transfers screen.
                    val chatKey = dccChatBufferKey(netId, offer0.from)
                    ensureBuffer(chatKey)
                    append(
                        bufKey(netId, "*server*"),
                        from = null,
                        text = "*** Incoming DCC CHAT from ${offer0.from} - tap 'DCCCHAT:${offer0.from}' buffer, or open Transfers to accept",
                        doNotify = false
                    )
                    // Show the offer inline inside the dedicated buffer with a clear prompt.
                    append(
                        chatKey,
                        from = null,
                        text = "*** DCC CHAT offer from ${offer0.from} (${offer0.ip}:${offer0.port}). Use /dcc accept ${offer0.from} or open Transfers to accept.",
                        doNotify = false
                    )
                    if (st.settings.notificationsEnabled) {
                        // Pass the DCC chat buffer key so the notification deep-links directly
                        // into the buffer (where Accept/Reject inline actions live), rather than
                        // requiring the user to navigate to the generic Transfers screen first.
                        val chatBufKey = dccChatBufferKey(netId, offer0.from)
                        notifier.notifyDccIncomingChat(netId, offer0.from, dccBufferKey = chatBufKey)
                    }
                }
            }

            is IrcEvent.NotOnChannel -> {
                val chan = normalizeIncomingBufferName(netId, ev.channel)
                val pendingKey = popPendingCloseForChannel(netId, chan)
                if (pendingKey != null) {
                    // We tried to part/close a channel we're not in; drop the buffer anyway.
                    removeBuffer(pendingKey)
                    append(bufKey(netId, "*server*"), from = null, text = "*** Closed buffer $chan (not on channel)", doNotify = false)
                }
            }
            is IrcEvent.ChatMessage -> {
                // Drop PRIVMSGs whose body is literally empty (zero bytes of trailing).
                // Common sources: malformed CTCP wrappers that left only the SOH sentinel,
                // a bouncer flushing a buffered control line with empty trailing, or a
                // remote client sending "PRIVMSG #chan :". Without this guard the UI
                // renders a bare "<nick> " line.
                //
                // Important: we only drop *truly empty* text. A message that looks blank
                // because the wrong encoding decoded its bytes into invisible/control chars
                // is still real content - dropping it would hide that the encoding is wrong.
                // The encoding-detection fix (5-minute timeout + stricter thresholds in
                // EncodingHelper) addresses the root cause; this guard only covers the
                // protocol-level empty-trailing case.
                if (ev.text.isEmpty()) return
                val my = _state.value.connections[netId]?.myNick ?: runtimes[netId]?.myNick ?: _state.value.myNick
                val fromMe = ev.from.equals(my, ignoreCase = true)
                if (!fromMe && isNickIgnored(netId, ev.from)) return
                val st = _state.value
                val suppressUnread = ev.isHistory && !st.settings.ircHistoryCountsAsUnread
                val allowNotify = if (ev.isHistory) st.settings.ircHistoryTriggersNotifications else true
                val targetKey = resolveIncomingBufferKey(netId, ev.target)

                if (!ev.isHistory && fromMe && consumeEchoIfMatch(netId, targetKey, ev.text, ev.isAction)) {
                    // We deliberately dropped this echo, but if the server tagged it with a msgid
                    // we need to record it in the buffer's seenMsgIds so a *second* echo of the
                    // same message (e.g. ZNC reflecting via both server-time and legacy
                    // server-time-iso paths) is caught by append()'s O(1) msgid dedup rather
                    // than slipping through as a visible duplicate.
                    val mid = ev.msgId
                    if (!mid.isNullOrBlank()) {
                        _state.update { s ->
                            val buf = s.buffers[targetKey] ?: UiBuffer(targetKey)
                            if (buf.seenMsgIds.contains(mid)) s
                            else s.copy(buffers = s.buffers + (targetKey to buf.copy(seenMsgIds = buf.seenMsgIds + mid)))
                        }
                    }
                    return
                }

                ensureBuffer(targetKey)
                // Clear this nick's typing indicator when they send a message (implicit "done").
                if (!fromMe) {
                    _state.update { st ->
                        val buf = st.buffers[targetKey]
                        if (buf != null && ev.from in buf.typingNicks) {
                            st.copy(buffers = st.buffers + (targetKey to buf.copy(typingNicks = buf.typingNicks - ev.from)))
                        } else st
                    }
                }
                val highlight = if (fromMe) false else isHighlight(netId, ev.text, ev.isPrivate)
                append(
                    targetKey,
                    from = ev.from,
                    text = ev.text,
                    isAction = ev.isAction,
                    isHighlight = highlight,
                    isPrivate = ev.isPrivate,
                    isLocal = fromMe || suppressUnread,
                    timeMs = ev.timeMs,
                    doNotify = allowNotify,
                    msgId = ev.msgId,
                    replyToMsgId = ev.replyToMsgId,
                    isHistory = ev.isHistory,
                    encryption = ev.encryption,
                )
            }
            is IrcEvent.Notice -> {
                // Drop empty-bodied notices (literally zero bytes of trailing). Mirrors the
                // ChatMessage guard above. Trigger seen in practice: bouncer bootstrapping
                // notices (`NOTICE * :` with empty body), services pings, and the corner
                // case where a remote client sends `NOTICE #chan :` with no payload.
                // Without this guard the renderer paints a blank line attributed to the
                // sender. Note: we don't filter encoding-mangled but non-empty content
                // here (same as ChatMessage) - the user might want to see and fix the
                // encoding rather than have the message silently dropped.
                if (ev.text.isEmpty()) return
                val st = _state.value
                val suppressUnread = ev.isHistory && !st.settings.ircHistoryCountsAsUnread
                if (!ev.isServer && isNickIgnored(netId, ev.from)) return
                val normTarget0 = normalizeIncomingBufferName(netId, ev.target)
                val normTarget = stripStatusMsgPrefix(netId, normTarget0)
                val isChanTarget = isChannelOnNet(netId, normTarget)
                val targetIsServerBuffer = normTarget == "*server*"

                // Notice routing rules:
                //
                //  1. Server notices (prefix is a hostname, not nick!user@host) →
                //     *server* buffer. These are auth notices, MOTD-adjacent, network
                //     announcements; they belong in the server log.
                //  2. Notice targeted at a channel we have a buffer for → that channel.
                //     This is the normal NOTICE-to-channel case.
                //  3. Channel-mention rule: if the notice text contains a channel
                //     name AND we have an open buffer for that channel, route there.
                //     This catches the on-join welcome from service bots: ChanServ
                //     ("[#chan] Welcome to #chan, ..."), X3 ("Welcome to #chan!"),
                //     Anope BotServ-assigned bots with arbitrary names (since we
                //     match on the body, not the sender), and similar. The notice
                //     was a PM-target NOTICE so rule 2 didn't fire, but the user
                //     clearly wants to see the greeting in the channel they just
                //     walked into.
                //
                //     Note: Anope BotServ entry messages typically arrive as PRIVMSG
                //     to the channel (so they don't even reach this notice handler).
                //     The mention rule still helps for ChanServ-style PM greetings
                //     and for custom service bots that send to-nick NOTICEs.
                //
                //  4. Recently-joined-channel rule: if rule 3 didn't match but the
                //     notice arrived within ~5 s of us joining a channel on this
                //     network, route to that channel. Catches BotServ greetings
                //     whose body doesn't mention the channel name (e.g.
                //     "Welcome, alice!"). Time-bounded to avoid catching unrelated
                //     later notices. Only fires when there's exactly one recent
                //     join — multiple recent joins are ambiguous.
                //  5. Everything else → the currently selected buffer on this
                //     network. If nothing is selected, fall back to *server*.
                //
                // The mention rule is conservative on purpose: it only routes when we
                // ALREADY have a buffer for that channel name. A notice that mentions
                // "#somechan" we never joined still goes to the selected buffer / server,
                // because creating a buffer for a channel we're not in would be misleading.
                // Multiple mentions: pick the first that resolves to an existing buffer
                // (services typically only mention one channel per notice anyway).
                fun firstMentionedKnownChannelKey(): String? {
                    val chantypes = runtimes[netId]?.support?.chantypes ?: "#&"
                    val text = ev.text
                    var i = 0
                    while (i < text.length) {
                        val c = text[i]
                        if (c in chantypes) {
                            // Walk forward over channel-name characters. RFC 2812 forbids
                            // space, control chars, comma, BEL, NUL inside channel names;
                            // we stop at any non-name char.
                            var j = i + 1
                            while (j < text.length) {
                                val ch = text[j]
                                if (ch == ' ' || ch == ',' || ch == '\u0007' || ch == '\u0000' ||
                                    ch == '\r' || ch == '\n' || ch == ':') break
                                j++
                            }
                            // Trim trailing punctuation that's grammatically part of the
                            // sentence, not the channel name: ".", ",", "!", "?", ")", "]",
                            // ":", ";", and quotes. Don't strip "-" or "_" (legitimate in
                            // channel names like #foo-bar / #foo_bar).
                            var end = j
                            while (end > i + 1) {
                                val tail = text[end - 1]
                                if (tail in ".,!?)]:;\"'") end-- else break
                            }
                            if (end > i + 1) {
                                val candidate = text.substring(i, end)
                                val key = bufKey(netId, candidate)
                                val foldCandidate = casefoldText(netId, candidate)
                                val match = st.buffers.keys.firstOrNull { k ->
                                    val (nid, bn) = splitKey(k)
                                    nid == netId && casefoldText(netId, bn) == foldCandidate
                                }
                                if (match != null) return match
                                // Also accept an exact bufKey match (covers freshly-ensured
                                // buffers not yet in the casefold sweep).
                                if (st.buffers.containsKey(key)) return key
                            }
                            i = j
                        } else {
                            i++
                        }
                    }
                    return null
                }

                fun recentlyJoinedChannelKey(): String? {
                    val now = System.currentTimeMillis()
                    val cutoff = now - 5_000L
                    // Find channels we joined in the last 5 s on this network. Multiple
                    // matches: bail (ambiguous) — better to fall through to the selected
                    // buffer than guess wrong.
                    val matches = recentJoinAtMs.entries
                        .filter { (key, ts) ->
                            ts >= cutoff && key.startsWith("$netId::")
                        }
                        .map { it.key }
                    return if (matches.size == 1) matches.single() else null
                }

                val destKey = when {
                    ev.isServer -> bufKey(netId, "*server*")
                    targetIsServerBuffer -> bufKey(netId, "*server*")
                    isChanTarget -> resolveBufferKey(netId, normTarget)
                    else -> {
                        firstMentionedKnownChannelKey()
                            ?: recentlyJoinedChannelKey()
                            ?: run {
                                val sel = st.selectedBuffer
                                val (selNet, _) = splitKey(sel)
                                if (sel.isNotBlank() && selNet == netId) sel
                                else bufKey(netId, "*server*")
                            }
                    }
                }

                ensureBuffer(destKey)
                // Notice rendering: `* <nick> text` is the deliberate visual marker that
                // distinguishes a notice from a channel message — the leading `* ` is what
                // the user is looking for to know "this is a NOTICE, not a PRIVMSG".
                //
                // Exception: bouncer pseudo-users (`*status`, `*controlpanel`, `BouncerServ`,
                // etc.) whose nicks already start with `*` produce a confusing `* <*status>`
                // pile-up. For those, render as `<nick> text` — the pseudo-user prefix
                // already signals "this is bouncer output, not a real user", which is the
                // job the leading `* ` would have done.
                //
                // Detection mirrors IrcCore.isBouncerPseudoUser:
                //  - ZNC convention: any nick starting with '*' (loaded modules)
                //  - soju convention: the named pseudo-user "BouncerServ"
                val fromNick = ev.from
                val isBouncerPseudo = ev.isServer && (
                    fromNick.startsWith("*") ||
                    fromNick.equals("BouncerServ", ignoreCase = true)
                )
                val rendered = if (isBouncerPseudo) {
                    "<$fromNick> ${ev.text}"
                } else {
                    "* <${ev.from}> ${ev.text}"
                }
                append(
                    destKey,
                    from = null,
                    text = rendered,
                    isLocal = suppressUnread,
                    timeMs = ev.timeMs,
                    doNotify = false,
                    msgId = ev.msgId,
                    replyToMsgId = ev.replyToMsgId,
                    encryption = ev.encryption,
                )
            }

            is IrcEvent.CtcpReply -> {
                // Display CTCP replies in the current buffer or server buffer
                val st = _state.value
                val sel = st.selectedBuffer
                val (selNet, _) = splitKey(sel)
                val destKey = if (sel.isNotBlank() && selNet == netId) sel else bufKey(netId, "*server*")
                ensureBuffer(destKey)
                
                val text = when (ev.command.uppercase()) {
                    "PING" -> {
                        // Calculate round-trip time if args is a timestamp we sent
                        // Our timestamps are 13-digit millisecond values from System.currentTimeMillis()
                        val sent = ev.args.trim().toLongOrNull()
                        val now = System.currentTimeMillis()
                        if (sent != null && sent > 1000000000000L && sent < now + 60000) {
                            // Looks like a valid recent timestamp
                            val rtt = now - sent
                            "*** CTCP PING reply from ${ev.from}: ${rtt}ms"
                        } else {
                            // Not our timestamp format, just show raw
                            "*** CTCP PING reply from ${ev.from}: ${ev.args}"
                        }
                    }
                    else -> "*** CTCP ${ev.command} reply from ${ev.from}: ${ev.args}"
                }
                append(destKey, from = null, text = text, isLocal = true, timeMs = ev.timeMs, doNotify = false)
            }

            is IrcEvent.ChannelModeLine -> {
                val st = _state.value
                val suppressUnread = ev.isHistory && !st.settings.ircHistoryCountsAsUnread
                val chanKey = resolveBufferKey(netId, ev.channel)
                ensureBuffer(chanKey)
                append(chanKey, from = null, text = ev.line, isLocal = suppressUnread, timeMs = ev.timeMs, doNotify = false)
            }

            is IrcEvent.Names -> {
				// Treat NAMES as a bounded snapshot (353...366). Even if we didn't explicitly request it,
				// servers send NAMES after JOIN and some bouncers can replay it. We accumulate until
				// NamesEnd then replace the channel's userlist.
					val rt = runtimes[netId]
					if (rt == null) {
						// Network is no longer active; ignore.
					} else {
				val keyFold = namesKeyFold(ev.channel)
						val existing = rt.namesRequests[keyFold]
						if (existing != null) {
							existing.names.addAll(ev.names)
						} else {
							val chanKey = resolveBufferKey(netId, ev.channel)
							ensureBuffer(chanKey)
							val nr = NamesRequest(replyBufferKey = chanKey, printToBuffer = false)
							nr.names.addAll(ev.names)
							rt.namesRequests[keyFold] = nr
						}
					}
            }

            is IrcEvent.NamesEnd -> {
                val rt = runtimes[netId]
                val keyFold = namesKeyFold(ev.channel)
                val req = rt?.namesRequests?.remove(keyFold)
                if (req != null) {
                    val chanKey = resolveBufferKey(netId, ev.channel)
                    ensureBuffer(chanKey)

                    // Guard: some servers/bouncers can send EndOfNames without any 353 lines (or with partial output).
                    // Don't wipe a populated nicklist in that case.
                    val st1 = _state.value
                    val currentSize = st1.nicklists[chanKey]?.size ?: 0
                    val incomingSize = req.names.size
                    val looksBogus = (incomingSize == 0 && currentSize > 0) ||
                        (currentSize >= 5 && incomingSize < 3)
                    if (!looksBogus) {
                        applyNamesSnapshot(netId, chanKey, req.names.toList())
                    }

                    val names = rebuildNicklist(netId, chanKey)
                    if (req.printToBuffer) {
                        appendNamesList(req.replyBufferKey, ev.channel, names)
                    }
                }
            }

            is IrcEvent.Joined -> {
                val st0 = _state.value
                val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread

                val chanKey = resolveBufferKey(netId, ev.channel)
                ensureBuffer(chanKey)

                if (!st0.settings.hideJoinPartQuit) {
                    val myNick = st0.connections[netId]?.myNick ?: st0.myNick
                    val msg = if (ev.nick.equals(myNick, ignoreCase = true)) {
                        "* Now talking on ${ev.channel}"
                    } else {
                        val host = ev.userHost ?: "*!*@*"
                        // extended-join: include account name if logged in
                        val accountSuffix = ev.account?.let { " [logged in as $it]" } ?: ""
                        "* ${ev.nick} ($host) has joined ${ev.channel}$accountSuffix"
                    }
                    append(
                        chanKey,
                        from = null,
                        text = colorEvent(msg, 3),  // green
                        isLocal = suppressUnread,
                        timeMs = ev.timeMs,
                        doNotify = false
                    )
                }

                
                val myNickNow = st0.connections[netId]?.myNick ?: st0.myNick
                val isMeNow = casefoldText(netId, ev.nick) == casefoldText(netId, myNickNow)

                // Track our own join times for the notice-routing recently-joined-channel
                // fallback. Stamps the per-buffer key when WE join (not when other users
                // join), and only for live joins (history replays don't represent a "we
                // just walked into this channel" moment that bots would greet on).
                if (isMeNow && !ev.isHistory) {
                    val now = System.currentTimeMillis()
                    recentJoinAtMs[chanKey] = now
                    // Cap map size: we only ever read entries within a 5 s window in the
                    // notice handler, so there's no point holding older ones around. A
                    // user who join-spams 32+ channels back-to-back would otherwise grow
                    // this map unbounded.
                    if (recentJoinAtMs.size > 32) {
                        val cutoff = now - 5_000L
                        recentJoinAtMs.entries.removeAll { it.value < cutoff }
                    }
                    // Arm the chathistory marker window for THIS join. If the bouncer or
                    // CHATHISTORY response delivers replay messages within the next 45 s,
                    // and the next live message arrives within that window, we'll insert
                    // the "── Chat history • Last message: <ts> ──" separator. Outside the
                    // window, history messages will not arm the marker - so a bouncer that
                    // happens to deliver a delayed playback batch (or a manual /chathistory
                    // call hours later) won't cause the marker to fire when the user
                    // eventually types something. Window matches upstream history-expect
                    // (15 s for znc.in/playback, 7 s for IRCv3 CHATHISTORY) plus a 30 s
                    // grace for the first live message after the burst.
                    chathistoryMarkerArmedUntilMs[chanKey] = now + 45_000L
                    // Cap map size for the same reason recentJoinAtMs is capped.
                    if (chathistoryMarkerArmedUntilMs.size > 64) {
                        chathistoryMarkerArmedUntilMs.entries.removeAll { it.value < now }
                    }
                }

                if (isMeNow || shouldAffectLiveState(ev.isHistory, ev.timeMs)) {
                    // Re-read state after append/ensureBuffer so we don't overwrite newly appended messages.
                    val st1 = _state.value

                    upsertNickInChannel(netId, chanKey, baseNick = ev.nick)
                    val updated = rebuildNicklist(netId, chanKey)

                    val myNick = st1.connections[netId]?.myNick ?: st1.myNick
                    val isMe = casefoldText(netId, ev.nick) == casefoldText(netId, myNick)

                    // On self-join, request a fresh NAMES snapshot so the nicklist includes users who were already in the channel.
                    // Don't print it to the buffer (this is an automatic refresh, not an explicit /names).
                    if (isMe) {
                        val rt = runtimes[netId]
                        val keyFold = namesKeyFold(ev.channel)
                        if (rt != null && !rt.namesRequests.containsKey(keyFold)) {
                            rt.namesRequests[keyFold] = NamesRequest(replyBufferKey = chanKey, printToBuffer = false)
                            viewModelScope.launch { runCatching { rt.client.sendRaw("NAMES ${ev.channel}") } }
                        }

                        // Track for reconnect rejoin if not already covered by autoJoin.
                        // Skip history/playback - we only care about live self-joins.
                        if (!ev.isHistory && rt != null) {
                            val profile = st1.networks.firstOrNull { it.id == netId }
                            val isAutoJoin = profile?.autoJoin?.any {
                                casefoldText(netId, it.channel.split(",")[0].trim()) == casefoldText(netId, ev.channel)
                            } == true
                            if (!isAutoJoin) {
                                // Channel key is not available from the JOIN event - store null.
                                // The user will be prompted by the server on reconnect if +k is still set.
                                rt.manuallyJoinedChannels[ev.channel] = null
                            }
                        }
                    }
                    // Decide whether this self-JOIN should pull the user onto the channel.
                    // It should for an explicit user join (typed /join or the join button),
                    // recorded in pendingUserJoinSwitch. It should NOT for the automatic rejoin
                    // burst after a reconnect (client-sent rejoins and bouncer-replayed JOINs),
                    // which is what suppressAutoJoinSwitchUntilMs guards. An explicit user join
                    // always wins, even inside the suppression window.
                    val rtForSwitch = runtimes[netId]
                    // Consume the intent only on an actual self-join so a JOIN by someone else
                    // never clears it.
                    val userRequestedJoin =
                        isMe && rtForSwitch?.pendingUserJoinSwitch?.remove(casefoldText(netId, ev.channel)) == true
                    val autoSwitchSuppressed =
                        rtForSwitch != null &&
                            System.currentTimeMillis() < rtForSwitch.suppressAutoJoinSwitchUntilMs
                    val shouldSwitch =
                        isMe &&
                            st1.activeNetworkId == netId &&
                            (st1.screen == AppScreen.CHAT || st1.screen == AppScreen.NETWORKS) &&
                            (userRequestedJoin || !autoSwitchSuppressed)

                    if (shouldSwitch) {
                        val leaving = st1.selectedBuffer
                        if (leaving.isNotBlank() && leaving != chanKey) stampReadMarker(leaving)
                    }

                    // Re-read after stampReadMarker so its write isn't clobbered.
                    val st2 = _state.value
                    val next = st2.copy(
                        nicklists = st2.nicklists + (chanKey to updated),
                        selectedBuffer = if (shouldSwitch) chanKey else st2.selectedBuffer,
                        screen = if (shouldSwitch) AppScreen.CHAT else st2.screen
                    )
                    _state.value = syncActiveNetworkSummary(next)
                }
            }

            
            is IrcEvent.Parted -> {
                val st0 = _state.value
                val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread

                // If this PART is the result of closing the buffer, don't recreate the buffer on the echo.
                val myNickNow = st0.connections[netId]?.myNick ?: st0.myNick
                val isMe = casefoldText(netId, ev.nick) == casefoldText(netId, myNickNow)
                if (isMe) {
                    val pendingKey = popPendingCloseForChannel(netId, ev.channel)
                    // User explicitly left - remove from reconnect rejoin list.
                    runtimes[netId]?.manuallyJoinedChannels?.remove(ev.channel)
                    if (pendingKey != null) {
                        append(
                            bufKey(netId, "*server*"),
                            from = null,
                            text = "*** Left ${ev.channel}",
                            isLocal = suppressUnread,
                            timeMs = ev.timeMs,
                            doNotify = false
                        )
                        return
                    }
                }

                val chanKey = resolveBufferKey(netId, ev.channel)

                if (!st0.settings.hideJoinPartQuit) {
                    val msg = if (isMe) {
                        "* You have left channel ${ev.channel}"
                    } else {
                        val host = ev.userHost ?: "*!*@*"
                        "* ${ev.nick} ($host) has left ${ev.channel}" +
                            (ev.reason?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: "")
                    }
                    append(
                        chanKey,
                        from = null,
                        text = colorEvent(msg, 7),  // orange
                        isLocal = suppressUnread,
                        timeMs = ev.timeMs,
                        doNotify = false
                    )
                }

                // Nicklist removal: same defensive policy as Quit. For live PARTs we
                // always update; for history-flagged PARTs we still remove the nick when
                // they are actually present in the channel right now, because the alternative
                // (display "X left" in chat while X stays in the nicklist) is the worse UX.
                // removeNickFromChannel is a no-op if the nick isn't currently listed, so
                // a genuinely-historical replay can't synthesize a fake removal.
                val affectLivePart = shouldAffectLiveState(ev.isHistory, ev.timeMs)
                val nickIsHere = chanNickCase[chanKey]?.containsKey(casefoldText(netId, ev.nick)) == true
                if (affectLivePart || nickIsHere) {
                    // Re-read state after append so we don't overwrite the message we just appended.
                    val st1 = _state.value
                    removeNickFromChannel(netId, chanKey, ev.nick)
                    val updated = rebuildNicklist(netId, chanKey)
                    // Clear any pending typing indicator for the parted nick,
                    // and cancel the expiry coroutine so it doesn't linger for 30 s.
                    val bufAfterPart = st1.buffers[chanKey]
                    val clearedBuf = if (bufAfterPart != null && ev.nick in bufAfterPart.typingNicks)
                        bufAfterPart.copy(typingNicks = bufAfterPart.typingNicks - ev.nick) else bufAfterPart
                    val newBufs = if (clearedBuf != null) st1.buffers + (chanKey to clearedBuf) else st1.buffers
                    receivedTypingExpiryJobs.remove("$chanKey/${ev.nick}")?.cancel()
                    _state.value = syncActiveNetworkSummary(st1.copy(nicklists = st1.nicklists + (chanKey to updated), buffers = newBufs))
                }
            }

            is IrcEvent.Kicked -> {
                val st0 = _state.value
                val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread

                val chanKey = resolveBufferKey(netId, ev.channel)
                ensureBuffer(chanKey)

                run {
                    val myNick = st0.connections[netId]?.myNick ?: st0.myNick
                    val by = ev.byNick ?: "?"
                    val reason = ev.reason?.takeIf { it.isNotBlank() }
                    val msg = if (ev.victim.equals(myNick, ignoreCase = true)) {
                        "* You were kicked from ${ev.channel} by $by" + (reason?.let { " [$it]" } ?: "")
                    } else {
                        "* ${ev.victim} was kicked by $by" + (reason?.let { " [$it]" } ?: "")
                    }

                    append(
                        chanKey,
                        from = null,
                        text = colorEvent(msg, 4),  // red
                        isLocal = suppressUnread,
                        timeMs = ev.timeMs,
                        doNotify = false
                    )
                }

                if (shouldAffectLiveState(ev.isHistory, ev.timeMs)) {
                    // Re-read state after append so we don't overwrite the message we just appended.
                    val st1 = _state.value

                    val myNick = st1.connections[netId]?.myNick ?: st1.myNick
                    val victimIsMe = casefoldText(netId, ev.victim) == casefoldText(netId, myNick)

                    removeNickFromChannel(netId, chanKey, ev.victim)
                    if (victimIsMe) {
                        chanNickCase[chanKey] = mutableMapOf()
                        chanNickStatus[chanKey] = mutableMapOf()
                    }

                    val finalList = if (victimIsMe) emptyList() else rebuildNicklist(netId, chanKey)
                    _state.value = syncActiveNetworkSummary(st1.copy(nicklists = st1.nicklists + (chanKey to finalList)))

                    // Auto-rejoin on kick. Off by default. Throttled to one rejoin per channel per
                    // AUTO_REJOIN_SUPPRESS_MS so we don't loop against +i/+k/+b modes or against an
                    // op who is actively kick-banning. The small AUTO_REJOIN_DELAY_MS makes it feel
                    // less adversarial than an instant rejoin.
                    if (victimIsMe && st1.settings.rejoinOnKick) {
                        val rejoinKey = "$netId::${ev.channel.lowercase()}"
                        val now = System.currentTimeMillis()
                        // Opportunistic eviction: if the map has grown past a small bound, drop entries
                        // older than 2× the suppress window so it can't grow without limit on a busy
                        // channel-hopping user.
                        if (recentKickRejoins.size > 32) {
                            val cutoff = now - (AUTO_REJOIN_SUPPRESS_MS * 2)
                            recentKickRejoins.entries.removeAll { it.value < cutoff }
                        }
                        val last = recentKickRejoins[rejoinKey] ?: 0L
                        if (now - last > AUTO_REJOIN_SUPPRESS_MS) {
                            recentKickRejoins[rejoinKey] = now
                            viewModelScope.launch {
                                delay(AUTO_REJOIN_DELAY_MS)
                                runtimes[netId]?.client?.sendRaw("JOIN ${ev.channel}")
                            }
                        } else {
                            append(chanKey, from = null,
                                text = "*** Auto-rejoin suppressed (kicked again within ${AUTO_REJOIN_SUPPRESS_MS / 1000}s)",
                                doNotify = false)
                        }
                    }
                }
            }

            is IrcEvent.Quit -> {
                val st0 = _state.value
                val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread
                val reason = ev.reason?.takeIf { it.isNotBlank() }

                val affectLive = shouldAffectLiveState(ev.isHistory, ev.timeMs)

                // Compute which channels the quitting nick is currently in, REGARDLESS of
                // affectLive. The flag only gates state *mutation*; we still need an accurate
                // target list to display the QUIT line only in channels the user actually
                // shared with us.
                val foldedNick = casefoldText(netId, ev.nick)
                val affected = st0.nicklists
                    .asSequence()
                    .filter { (k, _) -> k.startsWith("$netId::") }
                    .filter { (_, list) ->
                        list.any { display ->
                            casefoldText(netId, parseNickWithPrefixes(netId, display).first) == foldedNick
                        }
                    }
                    .map { it.key }
                    .toList()

                // Targets: ONLY channels where the user actually shared a nicklist with us.
                // Previous behavior fell back to "all channels on this network" when the
                // nicklist scan came up empty - that caused QUIT lines to appear in channels
                // the parting user was never in. If we have no evidence they shared a
                // channel with us, we have nothing meaningful to show, so the message is
                // dropped entirely (matches how HexChat / Konversation behave).
                val targets = affected
                if (!st0.settings.hideJoinPartQuit) {
                    val host = ev.userHost ?: "*!*@*"
                    val msg = "* ${ev.nick} ($host) has quit" + (reason?.let { " [$it]" } ?: "")
                    val coloured = colorEvent(msg, 5)  // brown — distinguishes server-side QUIT from client-side PART
                    for (k in targets) {
                        append(
                            k,
                            from = null,
                            text = coloured,
                            isLocal = suppressUnread,
                            timeMs = ev.timeMs,
                            doNotify = false
                        )
                    }
                }


                // Nicklist removal:
                //   - For live events (affectLive = true): remove from every nicklist on this network.
                //   - For events flagged as history (affectLive = false): still remove from any channel
                //     where this nick is currently listed - the user was genuinely there and is now
                //     gone, regardless of the timestamp gating. Without this, a slightly-stale @time
                //     tag (clock skew, brief netsplit on the bouncer side, replayed-as-history live
                //     QUIT) would leave the nick hanging in the nicklist after their PART/QUIT was
                //     already displayed in chat. removeNickFromChannel is a no-op when the nick
                //     isn't actually present, so non-shared channels are unaffected.
                val st1 = _state.value
                val mutatedKeys = mutableListOf<String>()
                if (affectLive) {
                    val keys = st1.nicklists.keys.filter { it.startsWith("$netId::") }
                    for (k in keys) {
                        removeNickFromChannel(netId, k, ev.nick)
                        mutatedKeys.add(k)
                    }
                } else {
                    for (k in affected) {
                        removeNickFromChannel(netId, k, ev.nick)
                        mutatedKeys.add(k)
                    }
                }
                if (mutatedKeys.isNotEmpty() || affectLive) {
                    val newNicklists = st1.nicklists.mapValues { (k, list) ->
                        val (kid, _) = splitKey(k)
                        if (kid != netId || k !in mutatedKeys) list else rebuildNicklist(netId, k)
                    }
                    // Also remove from away state map - only on truly live events; a stale replay
                    // shouldn't reset somebody's current away status.
                    if (affectLive) {
                        nickAwayState[netId]?.remove(casefoldText(netId, ev.nick))
                    }
                    // Clear any pending typing indicator for the quitting nick across all buffers on this network.
                    val newBufs = st1.buffers.mapValues { (k, buf) ->
                        if (k.startsWith("$netId::") && ev.nick in buf.typingNicks) {
                            receivedTypingExpiryJobs.remove("$k/${ev.nick}")?.cancel()
                            buf.copy(typingNicks = buf.typingNicks - ev.nick)
                        }
                        else buf
                    }
                    _state.value = syncActiveNetworkSummary(st1.copy(nicklists = newNicklists, buffers = newBufs))
                }
            }

            is IrcEvent.TopicReply -> {
                val st0 = _state.value
                val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread

                val chanKey = resolveBufferKey(netId, ev.channel)
                ensureBuffer(chanKey)

                if (!ev.isHistory) setTopic(chanKey, ev.topic)

                if (!st0.settings.hideTopicOnEntry) {
                    // mIRC-style join/topic info line
                    val topicText = ev.topic ?: ""
                    val msg = "* Topic for ${ev.channel} is: $topicText"
                    append(
                        chanKey,
                        from = null,
                        text = msg,
                        isLocal = suppressUnread,
                        timeMs = ev.timeMs,
                        doNotify = false
                    )
                }
            }
            is IrcEvent.TopicWhoTime -> {
                val st0 = _state.value
                val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread

                val chanKey = resolveBufferKey(netId, ev.channel)
                ensureBuffer(chanKey)

                if (!st0.settings.hideTopicOnEntry) {
                    val whenStr = ev.setAtMs?.let {
                        try {
                            java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", java.util.Locale.US).format(java.util.Date(it))
                        } catch (_: Throwable) {
                            java.util.Date(it).toString()
                        }
                    } ?: "unknown time"

                    val msg = "* Topic for ${ev.channel} set by ${ev.setter} at $whenStr"
                    append(
                        chanKey,
                        from = null,
                        text = msg,
                        isLocal = suppressUnread,
                        timeMs = ev.timeMs,
                        doNotify = false
                    )
                }
            }
            is IrcEvent.Topic -> {
                val chanKey = resolveBufferKey(netId, ev.channel)
                ensureBuffer(chanKey)
                // Always update the topic bar - isHistory only gates the chat line below.
                // A live TOPIC command whose server-time tag is >15 s in the past (clock
                // drift, or topic set just before you joined) was being flagged as history
                // and setTopic was skipped, leaving the bar showing the old topic.
                setTopic(chanKey, ev.topic)
                if (!ev.isHistory) {
                    // Append a status line so the change is visible in the buffer.
                    val topicText = ev.topic?.takeIf { it.isNotBlank() } ?: "(topic cleared)"
                    val line = if (ev.setter != null)
                        "* ${ev.setter} changed the topic to: $topicText"
                    else
                        "* Topic changed to: $topicText"
                    append(chanKey, from = null, text = line, doNotify = false, timeMs = ev.timeMs)
                }
            }
            is IrcEvent.ChannelUserMode -> {
                if (!ev.isHistory) {
                    val chanKey = resolveBufferKey(netId, ev.channel)
                    updateUserMode(netId, chanKey, ev.nick, ev.prefix, ev.adding)
                }
            }
            is IrcEvent.ChannelListStart -> {
                _channelListBuffer.clear()
                _channelListLastFlushMs = 0L
                _state.value = _state.value.copy(listInProgress = true, channelDirectory = emptyList())
            }
            is IrcEvent.ChannelListItem -> {
                _channelListBuffer.add(ChannelListEntry(ev.channel, ev.users, ev.topic))
                // Time-throttled flush (not every-N-items): keeps the socket draining fast on
                // huge networks so the server's slow-reader LIST cutoff isn't tripped. The
                // first item flushes immediately (lastFlush == 0), then at most every
                // CHANNEL_LIST_FLUSH_INTERVAL_MS. ListEnd always does a final flush.
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - _channelListLastFlushMs >= CHANNEL_LIST_FLUSH_INTERVAL_MS) {
                    _channelListLastFlushMs = now
                    val snapshot = _channelListBuffer.toList()
                    _state.update { it.copy(channelDirectory = snapshot) }
                }
            }
            is IrcEvent.ChannelListEnd -> {
                val snapshot = _channelListBuffer.toList()
                _channelListBuffer.clear()
                _state.update { it.copy(channelDirectory = snapshot, listInProgress = false) }
            }

            // IRCv3 CHGHOST: update user@host for nick in all shared channel nicklists.
            // The nicklist stores raw "prefix+nick" strings, not full masks, so we don't need
            // to update the display - just surface an info line in channels where the nick is present.
            is IrcEvent.Chghost -> {
                if (ev.isHistory) return
                val myNick = _state.value.connections[netId]?.myNick ?: return
                val isMe = casefoldText(netId, ev.nick) == casefoldText(netId, myNick)
                // Find channels where this nick is present
                val affectedChannels = _state.value.nicklists
                    .filterKeys { it.startsWith("$netId::") }
                    .filter { (_, list) ->
                        list.any { parseNickWithPrefixes(netId, it).first.let { b ->
                            casefoldText(netId, b) == casefoldText(netId, ev.nick) } }
                    }
                    .map { it.key }
                val line = if (isMe) "* Your host is now ${ev.newUser}@${ev.newHost}"
                           else "* ${ev.nick} is now ${ev.newUser}@${ev.newHost}"
                for (k in affectedChannels) {
                    append(k, from = null, text = line, timeMs = ev.timeMs, doNotify = false, isLocal = true)
                }
                if (affectedChannels.isEmpty()) {
                    append(bufKey(netId, "*server*"), from = null, text = line, timeMs = ev.timeMs, doNotify = false, isLocal = true)
                }
            }

            // IRCv3 ACCOUNT: services account login/logout notification.
            is IrcEvent.AccountChanged -> {
                if (ev.isHistory) return
                val myNick = _state.value.connections[netId]?.myNick ?: return
                val isMe = casefoldText(netId, ev.nick) == casefoldText(netId, myNick)
                val line = when {
                    ev.account == "*" -> if (isMe) "* You are no longer logged in" else "* ${ev.nick} logged out"
                    isMe -> "* You are now logged in as ${ev.account}"
                    else -> "* ${ev.nick} is now logged in as ${ev.account}"
                }
                // Surface in channels where this nick is visible, or server buffer
                val affected = _state.value.nicklists
                    .filterKeys { it.startsWith("$netId::") }
                    .filter { (_, list) ->
                        list.any { parseNickWithPrefixes(netId, it).first.let { b ->
                            casefoldText(netId, b) == casefoldText(netId, ev.nick) } }
                    }
                    .map { it.key }
                val targets = if (affected.isNotEmpty()) affected else listOf(bufKey(netId, "*server*"))
                for (k in targets) {
                    append(k, from = null, text = line, timeMs = ev.timeMs, doNotify = false, isLocal = true)
                }
            }

            // IRCv3 SETNAME: user changed their realname.
            is IrcEvent.Setname -> {
                if (ev.isHistory) return
                val myNick = _state.value.connections[netId]?.myNick ?: return
                val isMe = casefoldText(netId, ev.nick) == casefoldText(netId, myNick)
                val line = if (isMe) "* Your realname is now \"${ev.newRealname}\""
                           else "* ${ev.nick} changed realname to \"${ev.newRealname}\""
                val affected = _state.value.nicklists
                    .filterKeys { it.startsWith("$netId::") }
                    .filter { (_, list) ->
                        list.any { parseNickWithPrefixes(netId, it).first.let { b ->
                            casefoldText(netId, b) == casefoldText(netId, ev.nick) } }
                    }
                    .map { it.key }
                val targets = if (affected.isNotEmpty()) affected else listOf(bufKey(netId, "*server*"))
                for (k in targets) {
                    append(k, from = null, text = line, timeMs = ev.timeMs, doNotify = false, isLocal = true)
                }
            }

            // Incoming channel invite.
            is IrcEvent.InviteReceived -> {
                val serverKey = bufKey(netId, "*server*")
                val line = "* ${ev.from} has invited you to ${ev.channel}"
                append(serverKey, from = null, text = line, timeMs = ev.timeMs, doNotify = false, isLocal = false, isHighlight = true)
                // Also surface in the channel buffer if it already exists (e.g. we were kicked)
                val chanKey = resolveBufferKey(netId, ev.channel)
                if (_state.value.buffers.containsKey(chanKey)) {
                    append(chanKey, from = null, text = "* ${ev.from} invited you here", timeMs = ev.timeMs, doNotify = false, isLocal = false)
                }
            }

            // Server-sent ERROR (fatal). IrcCore already emits Disconnected afterwards.
            is IrcEvent.ServerError -> {
                // Stash for the imminent Disconnected handler. IrcCore may report the
                // disconnect reason as generic "socket closed" while the actual rejection
                // text (SASL required, K-Lined, etc.) only came in via this ERROR frame.
                lastServerErrorByNet[netId] = ev.message to System.currentTimeMillis()
                val serverKey = bufKey(netId, "*server*")
                append(serverKey, from = null, text = "*** Server error: ${ev.message}", doNotify = false, isLocal = false)
            }

            // AWAY status change for another user (away-notify CAP).
            // Track away state per-nick so the nicklist can reflect it.
            //
            // Note: we always update the nickAwayState map (so the nicklist can dim away
            // users), but only emit the inline "* foo is away/back" announcement when the
            // user hasn't suppressed it via hideAwayNotify. Bouncers can forward
            // away-notify regardless of which caps the client itself negotiates, so the
            // suppression has to happen at render time — disabling the cap on our side
            // doesn't stop the bouncer from sending these.
            is IrcEvent.AwayChanged -> {
                val awayMap = nickAwayState.getOrPut(netId) { mutableMapOf() }
                // On large servers with away-notify, every away transition adds an entry.
                // Nicks are evicted on QUIT but not on PART - cap to prevent unbounded growth.
                if (awayMap.size >= 2000) awayMap.clear()
                val fold = casefoldText(netId, ev.nick)
                val wasAway = awayMap.containsKey(fold)
                val suppressAnnouncement = _state.value.settings.hideAwayNotify
                if (ev.awayMessage != null) {
                    // Nick set or changed away message.
                    awayMap[fold] = ev.awayMessage
                    if (!wasAway && !suppressAnnouncement) {
                        // Only print "went away" on transition (not on away-message updates).
                        val msg = if (ev.awayMessage.isBlank()) "* ${ev.nick} is now away"
                                  else "* ${ev.nick} is now away (${ev.awayMessage})"
                        val affected = _state.value.nicklists
                            .filterKeys { it.startsWith("$netId::") }
                            .filter { (_, list) ->
                                list.any { parseNickWithPrefixes(netId, it).first
                                    .let { b -> casefoldText(netId, b) == fold } }
                            }.map { it.key }
                        for (k in affected) {
                            append(k, from = null, text = msg, timeMs = ev.timeMs, doNotify = false, isLocal = true)
                        }
                    }
                } else {
                    // Nick returned from away.
                    if (wasAway) {
                        awayMap.remove(fold)
                        if (!suppressAnnouncement) {
                            val msg = "* ${ev.nick} is back"
                            val affected = _state.value.nicklists
                                .filterKeys { it.startsWith("$netId::") }
                                .filter { (_, list) ->
                                    list.any { parseNickWithPrefixes(netId, it).first
                                        .let { b -> casefoldText(netId, b) == fold } }
                                }.map { it.key }
                            for (k in affected) {
                                append(k, from = null, text = msg, timeMs = ev.timeMs, doNotify = false, isLocal = true)
                            }
                        }
                    }
                }
            }

            // CAP NEW / CAP DEL - already logged by IrcSession via EmitStatus; just re-surface as server text.
            is IrcEvent.CapNew -> {
                val serverKey = bufKey(netId, "*server*")
                append(serverKey, from = null, text = "*** Server added capabilities: ${ev.caps.joinToString(" ")}", doNotify = false, isLocal = true)
            }
            is IrcEvent.CapDel -> {
                val serverKey = bufKey(netId, "*server*")
                append(serverKey, from = null, text = "*** Server removed capabilities: ${ev.caps.joinToString(" ")}", doNotify = false, isLocal = true)
            }

            // soju BOUNCER NETWORK: track upstream network info.
            is IrcEvent.BouncerNetwork -> {
                val serverKey = bufKey(netId, "*server*")

                // Deletion path - spec: `BOUNCER NETWORK <id> *`
                if (ev.removed) {
                    val stillPresent = _state.value.bouncerNetworks[netId]?.get(ev.networkId)
                    _state.update { st ->
                        val inner = st.bouncerNetworks[netId] ?: return@update st
                        val next = inner - ev.networkId
                        st.copy(bouncerNetworks = st.bouncerNetworks + (netId to next))
                    }
                    if (stillPresent != null) {
                        val nameStr = stillPresent.name ?: ev.networkId
                        append(serverKey, from = null, text = "*** Bouncer network removed: $nameStr",
                            doNotify = false, isLocal = true)
                    }
                    return
                }

                // Upsert path - per spec, a missing attribute means "preserve the prior value",
                // an explicitly cleared attribute (`key=` with empty value, surfaced via
                // ev.clearedKeys) means "drop the prior value". The three-way merge:
                //   ev.X non-null              → use ev.X
                //   ev.X null + key cleared    → null (drop)
                //   ev.X null + key not cleared → keep prev.X
                val prev = _state.value.bouncerNetworks[netId]?.get(ev.networkId)
                fun pick(field: String, evVal: String?, prevVal: String?): String? = when {
                    evVal != null -> evVal
                    field in ev.clearedKeys -> null
                    else -> prevVal
                }
                val merged = BouncerUpstreamInfo(
                    id = ev.networkId,
                    name = pick("name", ev.name, prev?.name),
                    host = pick("host", ev.host, prev?.host),
                    state = pick("state", ev.state, prev?.state),
                    lastSeenMs = System.currentTimeMillis(),
                )
                _state.update { st ->
                    val inner = st.bouncerNetworks[netId] ?: emptyMap()
                    st.copy(bouncerNetworks = st.bouncerNetworks + (netId to (inner + (ev.networkId to merged))))
                }

                // User-visible status - only on genuine change (first-seen or state transition)
                // to avoid the server buffer filling with "network [connected]" repeats on
                // every reconnect when soju re-sends the whole list.
                val isNew = prev == null
                val stateChanged = prev != null && prev.state != merged.state && merged.state != null
                if (isNew || stateChanged) {
                    val stateStr = merged.state ?: "unknown"
                    val nameStr = merged.name ?: ev.networkId
                    val hostStr = if (merged.host != null) " (${merged.host})" else ""
                    val verb = if (isNew) "discovered" else "state changed"
                    append(serverKey, from = null,
                        text = "*** Bouncer network $verb: $nameStr$hostStr [$stateStr]",
                        doNotify = false, isLocal = true)

                    // Hint for first-seen upstreams that don't correspond to any local profile.
                    // Match on the bouncer-reported host (upstream hostname, e.g. "irc.libera.chat")
                    // against our configured profile hosts - a user who's already set up a profile
                    // for libera.chat shouldn't be nagged. This is conservative: if the bouncer
                    // doesn't report a host, we skip the hint rather than risk a false positive.
                    if (isNew && !merged.host.isNullOrBlank()) {
                        val profileHosts = _state.value.networks.map { it.host.lowercase() }.toSet()
                        if (merged.host.lowercase() !in profileHosts) {
                            val displayName = merged.name ?: merged.host
                            append(serverKey, from = null,
                                text = "    → no local profile for \"$displayName\" - add one to connect to this upstream.",
                                doNotify = false, isLocal = true)
                        }
                    }
                }
            }

            is IrcEvent.MonitorStatus -> {
                // MONITOR: a watched nick came online or went offline.
                // Show a brief status line in the server buffer (and PM buffer if open).
                val statusLine = if (ev.online) "*** ${ev.nick} is online" else "*** ${ev.nick} is offline"
                val serverKey = bufKey(netId, "*server*")
                append(serverKey, from = null, text = statusLine, doNotify = false, isLocal = true, timeMs = ev.timeMs)
                // Also show in the PM buffer for that nick, if it exists.
                val pmKey = resolveBufferKey(netId, ev.nick)
                if (_state.value.buffers.containsKey(pmKey)) {
                    append(pmKey, from = null, text = statusLine, doNotify = false, isLocal = true, timeMs = ev.timeMs)
                }
            }

            is IrcEvent.ReadMarker -> {
                // Server confirmed a read marker update. Store it so the UI can show
                // unread-message separators when catching up after reconnect.
                val targetKey = resolveBufferKey(netId, ev.target)
                _state.update { st ->
                    val buf = st.buffers[targetKey] ?: return@update st
                    st.copy(buffers = st.buffers + (targetKey to buf.copy(
                        lastReadTimestamp = ev.timestamp
                    )))
                }
            }

            is IrcEvent.TypingStatus -> {
                // draft/typing: update per-buffer typing indicator set.
                // Silently ignore if user has opted out of receiving typing indicators.
                if (!_state.value.settings.receiveTypingIndicator) return
                // For channel TAGMSGs, ev.target is the channel name → route there.
                // For PM TAGMSGs, ev.target is our own nick; the buffer is keyed by the sender.
                val bufferName = if (isChannelOnNet(netId, ev.target)) ev.target else ev.nick
                val targetKey = resolveBufferKey(netId, bufferName)
                _state.update { st ->
                    val buf = st.buffers[targetKey] ?: return@update st
                    val updatedTyping = when (ev.state) {
                        "active", "paused" -> buf.typingNicks + ev.nick
                        else /* "done" */  -> buf.typingNicks - ev.nick
                    }
                    st.copy(buffers = st.buffers + (targetKey to buf.copy(typingNicks = updatedTyping)))
                }
                // Manage the auto-expiry timer for this nick (IRCv3 recommends expiring after 30 s
                // of no update so stale "is typing..." banners don't persist if "done" is never sent).
                val expiryKey = "$targetKey/${ev.nick}"
                receivedTypingExpiryJobs[expiryKey]?.cancel()
                if (ev.state == "active" || ev.state == "paused") {
                    receivedTypingExpiryJobs[expiryKey] = viewModelScope.launch {
                        delay(30_000L)
                        receivedTypingExpiryJobs.remove(expiryKey)
                        _state.update { st ->
                            val buf = st.buffers[targetKey] ?: return@update st
                            st.copy(buffers = st.buffers + (targetKey to buf.copy(typingNicks = buf.typingNicks - ev.nick)))
                        }
                    }
                } else {
                    receivedTypingExpiryJobs.remove(expiryKey)
                }
            }

            is IrcEvent.WhoxReply -> {
                // WHOX 354 reply: update away status from flags field.
                val fold = casefoldText(netId, ev.nick)

                // Track away state from WHOX flags ('G'=gone/away, 'H'=here).
                if (ev.isAway != null) {
                    val awayMap = nickAwayState.getOrPut(netId) { mutableMapOf() }
                    if (ev.isAway) {
                        awayMap.putIfAbsent(fold, "")  // Set away without overwriting a known message.
                    } else {
                        awayMap.remove(fold)
                    }
                }
                // WhoxReply account field currently informational; full account enrichment can
                // be added to the nicklist display in a future UI pass.
            }

            // draft/channel-rename: server renamed a channel we're in.
            // Update all buffer keys and nicklist keys that use the old name.
            is IrcEvent.ChannelRenamed -> {
                val oldKey = resolveBufferKey(netId, ev.oldName)
                val newKey = resolveBufferKey(netId, ev.newName)
                // Mutate in-memory nick maps BEFORE _state.value assignment (not inside update{} to
                // avoid double-execution on CAS retry).
                chanNickCase.remove(oldKey)?.let { chanNickCase[newKey] = it }
                chanNickStatus.remove(oldKey)?.let { chanNickStatus[newKey] = it }
                val st = _state.value
                val bufs = st.buffers.toMutableMap()
                val oldBuf = bufs.remove(oldKey)
                if (oldBuf != null) bufs[newKey] = oldBuf.copy(name = ev.newName)
                val nickLists = st.nicklists.toMutableMap()
                val oldNicks = nickLists.remove(oldKey)
                if (oldNicks != null) nickLists[newKey] = oldNicks
                val selectedBuf = if (st.selectedBuffer == oldKey) newKey else st.selectedBuffer
                _state.value = syncActiveNetworkSummary(st.copy(
                    buffers = bufs,
                    nicklists = nickLists,
                    selectedBuffer = selectedBuf
                ))
            }

            // draft/message-reactions: an emoji reaction was added or removed.
            // Surface as a brief status line in the target buffer.
            is IrcEvent.MessageReaction -> {
                val bufKey = resolveBufferKey(netId, ev.target)
                val verb = if (ev.adding) "reacted with" else "removed reaction"
                val refStr = ev.msgId?.let { " (ref: $it)" } ?: ""
                append(bufKey, from = null,
                    text = "* ${ev.fromNick} $verb ${ev.reaction}$refStr",
                    timeMs = ev.timeMs, doNotify = false, isLocal = true)
            }

            // ChannelModeChanged: live simple-mode delta on a channel. We merge it into the
            // buffer's stored modeString so the Channel Tools toggles reflect the change
            // immediately, instead of only updating on a 324 snapshot. The readable status line
            // is emitted separately via ChannelModeLine in the MODE command handler.
            is IrcEvent.ChannelModeChanged -> {
                val chanKey = resolveBufferKey(netId, ev.channel)
                val buf = _state.value.buffers[chanKey] ?: return
                val support = runtimes[netId]?.support
                // Modes to ignore when tracking the channel's simple-mode letters:
                //  - prefix modes (o/v/h/q/a): per-user rank, handled by the nicklist, not the
                //    channel mode string.
                //  - type-A list modes (b/e/I/q…): per-mask lists, not channel-wide flags.
                val prefixModes = (support?.prefixModes ?: "qaohv").toSet()
                val listModes = support?.chanModes?.split(",")?.getOrNull(0)?.toSet()
                    ?: setOf('b', 'e', 'I')
                val ignore = prefixModes + listModes

                // Apply the +/- delta to the existing letter set. Args (keys/limits/nicks) are
                // separate params and never appear in ev.modes, so only letters are processed.
                // LinkedHashSet keeps a stable order so the rebuilt string is deterministic.
                val current = buf.modeString ?: ""
                val letters = LinkedHashSet<Char>(current.removePrefix("+").toList())
                var adding = true
                for (c in ev.modes) {
                    when (c) {
                        '+' -> adding = true
                        '-' -> adding = false
                        in ignore -> { /* not a channel-wide flag */ }
                        else -> if (adding) letters.add(c) else letters.remove(c)
                    }
                }
                val merged = if (letters.isEmpty()) "" else "+" + letters.joinToString("")
                if (merged != current) {
                    _state.update { it.copy(buffers = it.buffers + (chanKey to buf.copy(modeString = merged))) }
                }
            }

            is IrcEvent.OpenQueryBuffer -> {
                // /query <nick> - open a PM buffer and switch to it.
                val key = bufKey(netId, ev.nick)
                ensureBuffer(key)
                openBuffer(key)
            }
        }
    }

    private fun setNetConn(netId: String, f: (NetConnState) -> NetConnState) {
        var shouldRefresh = false
        var changed = false
        _state.update { st: UiState ->
            val old = st.connections[netId] ?: NetConnState()
            val next = f(old)
            if (next == old) return@update st  // no-op: skip the state copy and notification refresh
            changed = true
            val newConns = st.connections + (netId to next)
            val updated = syncActiveNetworkSummary(st.copy(connections = newConns))
            shouldRefresh = updated.settings.showConnectionStatusNotification || updated.settings.keepAliveInBackground
            updated
        }
        if (changed && shouldRefresh) {
            refreshConnectionNotification()
        }
    }

    // Buffer + message helpers

    private fun ensureServerBuffer(netId: String) {
        ensureBuffer(bufKey(netId, "*server*"))
    }

    private fun ensureBuffer(key: String) {
        // Use atomic update to prevent race conditions when multiple events create buffers.
        _state.update { st0: UiState ->
            if (!st0.buffers.containsKey(key)) {
                st0.copy(buffers = st0.buffers + (key to UiBuffer(key)))
            } else {
                st0
            }
        }

        // Optional scrollback: preload the latest on-disk log tail into the buffer.
        // This is independent of "logging enabled" (writing). Users often expect scrollback to load
        // even if they later turn logging off, as long as logs exist.
        //
        // We load disk scrollback even when the server has chathistory, because chathistory
        // typically provides only 20–50 messages while the user's scrollback may be 800+.
        // Chathistory messages that arrive afterward are deduplicated in append() by msgid.
        // The merge below uses a ±3 second fuzzy window to handle timestamp skew between
        // log-file timestamps (second precision) and server-time tags (millisecond precision).
        val st = _state.value
        val buf0 = st.buffers[key] ?: return
        if (!scrollbackRequested.add(key)) return

        val (netId, bufferName) = splitKey(key)
        val netName = st.networks.firstOrNull { it.id == netId }?.name ?: "network"
        val maxLines = st.settings.maxScrollbackLines.coerceIn(100, 5000)

        val loadStartMs = System.currentTimeMillis()
        scrollbackLoadStartedAtMs[key] = loadStartMs

        viewModelScope.launch(Dispatchers.IO) {
            // Outer try around the whole scrollback pipeline. A throw here lands in
            // viewModelScope's default uncaught-exception handler, which on Android
            // re-throws and crashes the process. Concrete trigger we've seen in the
            // wild: LogWriter.readTail -> readTailSaf -> findChild -> ContentResolver
            // .query, which throws SecurityException when the saved logFolderUri came
            // from a previous install via backup-restore and the SAF permission grant
            // didn't transfer with the data. LogWriter now catches that at the source,
            // but parseLogLineToUiMessage, the dedup arithmetic, and the merge into
            // state are all I/O- and parse-heavy code paths that could plausibly throw
            // on a partially-corrupted log file, so the belt-and-braces catch stays.
            // Any failure here just means "no scrollback preload" - the user can still
            // chat normally; live messages aren't affected.
            val lines = try {
                logs.readTail(netName, bufferName, maxLines, st.settings.logFolderUri)
            } catch (t: Throwable) {
                android.util.Log.w("IrcViewModel", "Scrollback preload failed for $key", t)
                scrollbackRequested.remove(key)
                scrollbackLoadStartedAtMs.remove(key)
                return@launch
            }
            if (lines.isEmpty()) {
                // Allow a later retry if a log is created after the buffer exists.
                scrollbackRequested.remove(key)

                scrollbackLoadStartedAtMs.remove(key)

                // Nick tracking is live state - empty scrollback (logging off, new buffer) must not clear it.
                return@launch
            }

            val now = System.currentTimeMillis()
            val start = now - (lines.size.toLong() * 1000L)
            val loaded = lines.mapIndexedNotNull { idx, line ->
                parseLogLineToUiMessage(line, fallbackTimeMs = start + idx * 1000L)
            }
            if (loaded.isEmpty()) return@launch

            withContext(Dispatchers.Main) {
                val cur = _state.value
                val buf = cur.buffers[key] ?: return@withContext

                // Merge scrollback with any live messages that may have arrived since we started loading.
                // We keep a start timestamp so we can place an "end of scrollback" marker before any
                // post-connect lines, and avoid obvious duplicates.
                val startedAt = scrollbackLoadStartedAtMs.remove(key) ?: loadStartMs

                val preExisting = buf.messages.filter { it.timeMs < startedAt }
                val liveDuringLoad = buf.messages.filter { it.timeMs >= startedAt }
                val firstLiveTime = liveDuringLoad.minOfOrNull { it.timeMs } ?: Long.MAX_VALUE

                // Build a set of message signatures for deduplication against what is ALREADY
                // in the buffer. This must cover *every* message currently displayed, not just
                // the ones that arrived after this scrollback pass started (liveDuringLoad).
                val existingMsgIds = buf.messages.mapNotNull { it.msgId }.toHashSet()
                data class FuzzySig(val sec: Long, val from: String?, val text: String)
                val existingFuzzy = buildSet<FuzzySig> {
                    for (msg in buf.messages) {
                        val sec = msg.timeMs / 1000
                        val from = msg.from?.lowercase()
                        val text = msg.text.take(100).lowercase()
                        for (delta in -3L..3L) add(FuzzySig(sec + delta, from, text))
                    }
                }

                // Filter loaded messages: must be older than first live, and not a duplicate.
                // Also filter out messages that are too close to the load start time (within 2 seconds)
                // to avoid showing messages from the current session as "scrollback".
                val olderLoaded = loaded.filter { msg ->
                    val isOlder = msg.timeMs < (firstLiveTime - 500L)
                    val isTooRecent = msg.timeMs > (startedAt - 2000L)  // Within 2 seconds of buffer creation
                    // Prefer msgid-based dedup; fall back to fuzzy ±3s window. Compared against
                    // every message already in the buffer (preExisting + liveDuringLoad) so an
                    // already-displayed line is never re-added regardless of when it arrived
                    // relative to this scrollback pass.
                    val isDupe = if (msg.msgId != null) {
                        existingMsgIds.contains(msg.msgId)
                    } else {
                        existingFuzzy.contains(FuzzySig(
                            msg.timeMs / 1000,
                            msg.from?.lowercase(),
                            msg.text.take(100).lowercase()
                        ))
                    }
                    isOlder && !isDupe && !isTooRecent
                }

                // Only show scrollback marker if there are actual old messages (not from current session)
                // and there's a meaningful time gap between scrollback and live messages.
                val showMarker = cur.settings.loggingEnabled && 
                    olderLoaded.isNotEmpty() && 
                    liveDuringLoad.isNotEmpty() &&
                    (firstLiveTime - olderLoaded.maxOf { it.timeMs }) > 5000L  // At least 5 second gap

                val withMarker = if (showMarker) {
                    // Show the NEWEST scrollback message time (when last activity was)
                    val newestMs = olderLoaded.maxOf { it.timeMs }
                    val newestStr = runCatching {
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(newestMs))
                    }.getOrElse { java.util.Date(newestMs).toString() }

                    val markerTimeMs = if (firstLiveTime != Long.MAX_VALUE) {
                        // Ensure the marker sorts between scrollback and the first live line.
                        (firstLiveTime - 1L).coerceAtLeast(newestMs + 1L)
                    } else {
                        newestMs + 1L
                    }

                    val marker = UiMessage(
                        id = nextUiMsgId.getAndIncrement(),
                        timeMs = markerTimeMs,
                        from = null,
                        text = "── Scrollback from logs • Last message: $newestStr ──",
                        isAction = false
                    )
                    olderLoaded + preExisting + marker + liveDuringLoad
                } else {
                    olderLoaded + preExisting + liveDuringLoad
                }

                val merged = withMarker.takeLast(maxLines)

                // Rebuild seenMsgIds AND seenHistoryFingerprints from the retained messages so
                // both O(1) dedup indices stay consistent with the actual message list after a
                // scrollback load. Rebuilding the fingerprint set (not just seenMsgIds) matters:
                // disk-loaded lines carry no msgid, so a subsequent live re-delivery of one of
                // them would otherwise have no fingerprint to match against in append() and would
                // render a second time. The key formula mirrors append()'s historyFingerprint.
                val mergedSeenMsgIds: Set<String> = merged.mapNotNullTo(HashSet()) { it.msgId }
                val mergedSeenFingerprints: Set<String> = merged.mapNotNullTo(HashSet()) { m ->
                    if (m.from != null) "${m.timeMs}|${m.from}|${m.text.hashCode()}" else null
                }

                // Use atomic update to prevent race conditions
                _state.update { currentState: UiState ->
                    val currentBuf = currentState.buffers[key] ?: return@update currentState
                    val newBuf = currentBuf.copy(
                        messages = merged,
                        seenMsgIds = mergedSeenMsgIds,
                        seenHistoryFingerprints = mergedSeenFingerprints,
                    )
                    currentState.copy(buffers = currentState.buffers + (key to newBuf))
                }
            }
        }
    }

    private fun removeBuffer(key: String) {
        // If this is a DCC CHAT buffer, close the underlying session.
        val (_, name) = splitKey(key)
        if (isDccChatBufferName(name)) {
            closeDccChatSession(key)
        }

        scrollbackRequested.remove(key)
        scrollbackLoadStartedAtMs.remove(key)
        pendingChathistoryMarkerMs.remove(key)

        // Use atomic update to prevent race conditions
        _state.update { st0: UiState ->
            if (!st0.buffers.containsKey(key)) return@update st0

            val newBuffers = st0.buffers - key
            val newNicklists = st0.nicklists - key
            val newBanlists = st0.banlists - key
            val newBanLoading = st0.banlistLoading - key
            val newQuietlists = st0.quietlists - key
            val newQuietLoading = st0.quietlistLoading - key
            val newExceptlists = st0.exceptlists - key
            val newExceptLoading = st0.exceptlistLoading - key
            val newInvexlists = st0.invexlists - key
            val newInvexLoading = st0.invexlistLoading - key

            val newSelected = if (st0.selectedBuffer == key) {
                val (netId, _) = splitKey(key)
                val serverKey = bufKey(netId, "*server*")
                when {
                    newBuffers.containsKey(serverKey) -> serverKey
                    newBuffers.isNotEmpty() -> newBuffers.keys.first()
                    else -> ""
                }
            } else st0.selectedBuffer

            syncActiveNetworkSummary(
                st0.copy(
                    buffers = newBuffers,
                    nicklists = newNicklists,
                    banlists = newBanlists,
                    banlistLoading = newBanLoading,
                    quietlists = newQuietlists,
                    quietlistLoading = newQuietLoading,
                    exceptlists = newExceptlists,
                    exceptlistLoading = newExceptLoading,
                    invexlists = newInvexlists,
                    invexlistLoading = newInvexLoading,
                    selectedBuffer = newSelected
                )
            )
        }
    }

    private fun parseLogLineToUiMessage(line: String, fallbackTimeMs: Long): UiMessage? {
        val trimmed = line.trimEnd()
        if (trimmed.isBlank()) return null

        var timeMs = fallbackTimeMs
        var body = trimmed

        // New log format: "yyyy-MM-dd HH:mm:ss	<message>"
        val parts = trimmed.split('	', limit = 2)
        if (parts.size == 2) {
            val maybeTs = parts[0]
            val maybeBody = parts[1]
            val parsed = runCatching {
                LocalDateTime
                    .parse(maybeTs, logTimeFormatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
            if (parsed != null) {
                timeMs = parsed
                body = maybeBody
            }
        }

        var from: String? = null
        var text = body
        var isAction = false

        // Common IRC log line styles - tried in priority order:
        //
        //   "*nick* action text"      NEW action format (unambiguous - written by this client
        //                             going forward).  Server-status lines use "* word …"
        //                             (asterisk-SPACE) and can never produce this pattern.
        //
        //   "<nick> hello"            Regular chat message.
        //
        //   "* nick action text"      OLD action format written by earlier versions of this
        //                             client, and by HexChat/irssi/etc.  Treated as an action
        //                             only when the first word passes IRC nick validation AND
        //                             is not a known server-status sentinel word - otherwise
        //                             the line is kept as a plain server message (from = null).
        //
        //   Anything else             Server/status line, rendered as plain text (from = null).

        // IRC nick validation: may start with letter or _\[]{}|`^ and contain only those
        // characters plus digits and -.  Crucially excludes <, (, #, @, !, digits as first char.
        fun isValidNickChar(c: Char, first: Boolean): Boolean = when {
            c.isLetter() -> true
            c.isDigit() -> !first
            c == '-' -> !first
            c in "_\\[]{}|`^" -> true
            else -> false
        }
        fun looksLikeNick(s: String): Boolean =
            s.isNotEmpty() && s.length <= 32 &&
            s[0].let { isValidNickChar(it, first = true) } &&
            s.all { isValidNickChar(it, first = false) }

        // Exact first words that HexDroid itself writes in server-status lines that start
        // with "* " - these must never be misidentified as action nicks from old-format logs.
        // (e.g. "* Now talking on #channel", "* Topic for #channel is: …", "* Mode #ch +n")
        val serverStatusFirstWords = setOf("Now", "Topic", "Mode")

        if (body.startsWith("*") && body.length > 2 && body[1] != ' ' && body[1] != '*') {
            // New format: *nick* text
            val closeAst = body.indexOf('*', 1)
            if (closeAst > 1 && closeAst + 2 <= body.length) {
                val nick = body.substring(1, closeAst)
                if (looksLikeNick(nick)) {
                    from = nick
                    text = if (closeAst + 1 < body.length && body[closeAst + 1] == ' ')
                        body.substring(closeAst + 2)
                    else
                        body.substring(closeAst + 1)
                    isAction = true
                }
            }
        } else if (body.startsWith("<") && body.contains("> ")) {
            val end = body.indexOf("> ")
            if (end > 1) {
                from = body.substring(1, end)
                text = body.substring(end + 2)
            }
        } else if (body.startsWith("* ") && body.length > 2) {
            // Old action format - guard against server-status lines.
            val rest = body.substring(2)
            val sp = rest.indexOf(' ')
            if (sp > 0) {
                val nick = rest.substring(0, sp)
                if (looksLikeNick(nick) && nick !in serverStatusFirstWords) {
                    from = nick
                    text = rest.substring(sp + 1)
                    isAction = true
                }
                // else: server-status line - leave from=null, text=body (full line)
            }
        }

        return UiMessage(
            id = nextUiMsgId.getAndIncrement(),
            timeMs = timeMs,
            from = from,
            text = text,
            isAction = isAction,
        )
    }

    private fun setTopic(key: String, topic: String?) {
        val st = _state.value
        val buf = st.buffers[key] ?: UiBuffer(key)
        _state.value = st.copy(buffers = st.buffers + (key to buf.copy(topic = topic)))
    }


    private fun appendNamesList(bufferKey: String, channel: String, names: List<String>) {
        if (names.isEmpty()) {
            append(bufferKey, from = null, text = "*** NAMES for $channel: (none)", doNotify = false)
            return
        }

        append(bufferKey, from = null, text = "*** NAMES for $channel (${names.size}):", doNotify = false)

        val maxLen = 380
        var sb = StringBuilder()
        for (n in names) {
            if (sb.isEmpty()) {
                sb.append(n)
            } else if (sb.length + 1 + n.length > maxLen) {
                append(bufferKey, from = null, text = "***   ${sb}", doNotify = false)
                sb = StringBuilder(n)
            } else {
                sb.append(' ').append(n)
            }
        }
        if (sb.isNotEmpty()) {
            append(bufferKey, from = null, text = "***   ${sb}", doNotify = false)
        }
    }

    private fun append(
        bufferKey: String,
        from: String?,
        text: String,
        isAction: Boolean = false,
        isHighlight: Boolean = false,
        isPrivate: Boolean = false,
        isLocal: Boolean = false,
        timeMs: Long? = null,
        doNotify: Boolean = true,
        isMotd: Boolean = false,
        /**
         * True for genuine server/connection error lines (from = "ERROR"). When set, the
         * notification (if any) is routed through NotificationHelper.notifyError and gated
         * on the per-network NetworkProfile.notifyOnErrors flag instead of the highlight/PM
         * settings, so errors stay out of the tray unless the user opted in for that network.
         */
        isError: Boolean = false,
        msgId: String? = null,
        replyToMsgId: String? = null,
        /**
         * True when this line is being replayed from a server- or bouncer-side history
         * source (chathistory, znc.in/playback, soju buffer playback). Drives the content-
         * fingerprint dedup path that catches duplicate replays without a msgid; live
         * messages skip that path entirely.
         */
        isHistory: Boolean = false,
        /**
         * E2E scheme the wire payload arrived under. Propagated into the resulting
         * UiMessage so the chat renderer can draw a per-scheme padlock icon. Null
         * for messages that never had an encryption prefix on the wire (the common
         * case for system lines, server numerics, etc.).
         */
        encryption: com.boxlabs.hexdroid.crypto.E2eScheme? = null,
    ) {
        val ts = timeMs ?: System.currentTimeMillis()
        // A sender on this network's highlight-ignore list never highlights or alerts; the
        // message still gets appended. effectiveHighlight is used everywhere below in place
        // of the raw isHighlight so the badge, colour intent, and tray alert all agree.
        val senderNotifyIgnored = from != null && isNotifyIgnoredSender(splitKey(bufferKey).first, from)
        val effectiveHighlight = isHighlight && !senderNotifyIgnored
        val msg = UiMessage(
            id = nextUiMsgId.getAndIncrement(),
            timeMs = ts,
            from = from,
            text = text,
            isAction = isAction,
            isMotd = isMotd,
            msgId = msgId,
            replyToMsgId = replyToMsgId,
            encryption = encryption,
        )

        // Content-fingerprint dedup. `time=` is server-stamped on replayed messages so two
        // replays of the same line produce identical fingerprints. The hashCode of text is
        // sufficient — these are best-effort dedup keys, not a security boundary, and the
        // false-collision odds (same epoch ms, same nick, same text-hashCode, same buffer,
        // different real text) are vanishingly small in practice.
        //
        // We compute this for every message with a sender, NOT just messages we've classified
        // as history. Reason: bouncer replays don't always carry markers we recognise — ZNC's
        // legacy `*playback` module dumps stamped lines without a BATCH wrapper, and our
        // isHistory heuristic returns false for them when [historyExpectUntil] hasn't been set
        // (which it isn't, on a fresh JOIN before our own CHATHISTORY request fires). Without
        // a fingerprint on that first delivery, the subsequent CHATHISTORY reply for the same
        // line — which IS classified as history — has nothing to match against, and the user
        // sees the same `[03:13:52] <nick> message` twice.
        //
        // Live messages register a fingerprint too. They effectively never collide because the
        // local-now `ts` has millisecond resolution and human typing can't produce two messages
        // in the same millisecond.
        val historyFingerprint: String? = if (from != null) {
            "$ts|$from|${text.hashCode()}"
        } else null

        // Self-echo replay dedup. The fingerprint above keys on the timestamp, which
        // works for replay-vs-replay (both carry the server `time=`) but NOT for
        // local-echo-vs-replay: our own outgoing message was echoed locally with the
        // device clock, while the bouncer replays it on reconnect with the server
        // clock, so the fingerprints differ and the replay slips through as a visible
        // duplicate (the classic "my last few messages appear twice after reconnect"
        // bug). We catch it with a content+sender match against the recentSelfSends
        // tracker, independent of timestamp. Only applied to history/replay lines
        // attributed to our own nick; a live message or a message from anyone else is
        // never affected. Computed outside the _state.update block because it consumes
        // a tracker entry (a side effect that must not run on a CAS retry).
        val (selfNetId, _) = splitKey(bufferKey)
        val myNick = _state.value.connections[selfNetId]?.myNick ?: runtimes[selfNetId]?.myNick
        val isFromMe = from != null && myNick != null && from.equals(myNick, ignoreCase = true)
        val isSelfEchoReplayDuplicate =
            isFromMe && isHistory && consumeSelfSendIfMatch(bufferKey, text, isAction)

        // Chathistory marker: when the FIRST live message for this buffer arrives after one
        // or more isHistory=true messages, emit a "── Chat history • Last message: <ts> ──"
        // separator just before it. Mirrors the scrollback marker (which marks the boundary
        // between disk logs and the current session) — same visual cue at both kinds of
        // catch-up boundary.
        //
        // The 5 s gap requirement avoids false-firing during the rare race where a live PRIVMSG
        // arrives mid-replay. In that case the live ts is essentially the same as the latest
        // history ts and inserting a marker would put the rest of the still-arriving history
        // *after* the boundary — confusing.
        //
        // Computed outside the state.update block so the read of pendingChathistoryMarkerMs
        // doesn't get re-run on a state.update retry. The map is cleared lazily inside the
        // update only if we actually emit the marker.
        val markerToEmit: UiMessage? = run {
            if (isHistory) return@run null
            val pending = pendingChathistoryMarkerMs[bufferKey] ?: return@run null
            // Only emit when the buffer is currently in its "catch-up" window — armed by
            // a self-JOIN or by Connected (for bouncer-playback PMs). Outside that window,
            // a pending history timestamp is from an unrelated catch-up that already
            // happened and should not insert a marker into the user's typing flow.
            val armedUntil = chathistoryMarkerArmedUntilMs[bufferKey] ?: 0L
            val nowMs = System.currentTimeMillis()
            if (armedUntil < nowMs) {
                // Window expired: discard the stale pending timestamp so a future arm
                // (re-join, reconnect) starts clean.
                pendingChathistoryMarkerMs.remove(bufferKey)
                return@run null
            }
            if (ts <= pending + 5_000L) return@run null
            val newestStr = runCatching {
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(pending))
            }.getOrElse { java.util.Date(pending).toString() }
            UiMessage(
                id = nextUiMsgId.getAndIncrement(),
                timeMs = (pending + 1L).coerceAtMost(ts - 1L),
                from = null,
                text = "── Chat history • Last message: $newestStr ──",
            )
        }

        // Atomic update, then read the committed state for logging/notifications.
        var msgWasDuplicate = false
        _state.update { st: UiState ->
            val buf = st.buffers[bufferKey] ?: UiBuffer(bufferKey)

            // Self-echo replay dedup (computed above, outside the update so the tracker
            // consume runs exactly once). A history line that matched a message we
            // already showed as a local echo is dropped here.
            if (isSelfEchoReplayDuplicate) {
                msgWasDuplicate = true
                return@update st
            }

            // Deduplicate by msgId using the O(1) seenMsgIds HashSet
            if (msgId != null && buf.seenMsgIds.contains(msgId)) {
                msgWasDuplicate = true
                return@update st
            }
            // Fallback content-fingerprint dedup for history messages without msgid.
            // Catches the ZNC *playback + chathistory overlap that the msgid path misses.
            if (historyFingerprint != null && buf.seenHistoryFingerprints.contains(historyFingerprint)) {
                msgWasDuplicate = true
                return@update st
            }

            // A buffer is only "selected" (suppressing unread tracking) when the user can
            // actually see it: right screen, right buffer, AND the app is in the foreground.
            // Without the foreground check, messages arriving while the app is backgrounded
            // mark themselves as read and advance lastReadTimestamp, so the unread bar never
            // appears when the user returns - even though a notification fired for the message.
            val isSelected = (bufferKey == st.selectedBuffer
                && st.screen == AppScreen.CHAT
                && AppVisibility.isForeground)
            val unreadInc = if (!isSelected && !isLocal) 1 else 0
            val highlightInc = if (!isSelected && effectiveHighlight && !isLocal) 1 else 0

            val maxLines = st.settings.maxScrollbackLines.coerceIn(100, 5000)
            // Splice in the chathistory marker (if any) before the new live message, in a
            // single state update. The marker is a system line (from = null) and does not
            // affect dedup — its from is null so historyFingerprint isn't computed for it.
            val toAppend: List<UiMessage> = if (markerToEmit != null) listOf(markerToEmit, msg) else listOf(msg)
            val combined = buf.messages + toAppend
            val newMessages = if (combined.size > maxLines) combined.takeLast(maxLines) else combined

            // Rebuild seenMsgIds from retained messages so evicted entries don't accumulate.
            // When the buffer isn't trimmed (the common case) we just add the new id; only
            // after a trim do we pay the cost of rebuilding the set from scratch.
            val newSeenMsgIds: Set<String> = when {
                msgId == null && combined.size <= maxLines -> buf.seenMsgIds
                msgId != null && combined.size <= maxLines -> buf.seenMsgIds + msgId
                else -> newMessages.mapNotNullTo(HashSet()) { it.msgId }
            }

            // Content fingerprints: only added when we computed one for this message; rebuilt
            // from scratch on trim. The set is bounded by maxLines (one entry per retained
            // message with a sender) and is cleared whenever the buffer is.
            val newSeenHistoryFingerprints: Set<String> = when {
                historyFingerprint == null && combined.size <= maxLines -> buf.seenHistoryFingerprints
                historyFingerprint != null && combined.size <= maxLines -> buf.seenHistoryFingerprints + historyFingerprint
                else -> {
                    // After a trim we recompute fingerprints for the retained messages. The gate
                    // here mirrors the gate at the top of [append] — every message with a sender
                    // gets a fingerprint, so a future replay of any retained line dedupes against
                    // it regardless of which path delivered the original.
                    newMessages.mapNotNullTo(HashSet()) { m ->
                        if (m.from != null) "${m.timeMs}|${m.from}|${m.text.hashCode()}"
                        else null
                    }
                }
            }

            // Advance lastReadTimestamp for every message on the selected buffer so the
            // unread separator never appears for messages the user is actively watching.
            val newLastRead = if (isSelected)
                java.time.Instant.ofEpochMilli(ts + 1L).toString()
            else
                buf.lastReadTimestamp
            val newBuf = buf.copy(
                messages = newMessages,
                seenMsgIds = newSeenMsgIds,
                seenHistoryFingerprints = newSeenHistoryFingerprints,
                unread = buf.unread + unreadInc,
                highlights = buf.highlights + highlightInc,
                lastReadTimestamp = newLastRead
            )
            st.copy(buffers = st.buffers + (bufferKey to newBuf))
        }
        val st = _state.value
        if (msgWasDuplicate) return

        // Record genuine local echoes of our own outgoing messages so a later bouncer
        // history-replay of the same line (after a reconnect) can be recognised and
        // dropped. Gate: it's our nick, it's NOT history (a real live echo, not a
        // replay), and it's a local echo (isLocal). The isHistory guard prevents a
        // replayed own-message that slipped past dedup from re-arming the tracker, and
        // the !isSelfEchoReplayDuplicate guard is implicit since duplicates already
        // returned above.
        if (isFromMe && !isHistory && isLocal) {
            recordSelfSend(bufferKey, text, isAction)
        }

        // Maintain the chathistory marker tracker. Done after a non-duplicate state update so a
        // dedup'd replay doesn't move the "last history" pointer forward — only newly committed
        // history messages do. Order matters: clear-on-emit must run BEFORE the isHistory write
        // so a history message that arrives with a still-pending marker (rare, but possible if a
        // mixed batch interleaves) doesn't immediately re-arm the marker for itself.
        if (markerToEmit != null) {
            // We just inserted the separator; the boundary it marked is now drawn. Also
            // disarm the window so a second history burst from the same catch-up doesn't
            // re-arm and fire again on the next live message after that.
            pendingChathistoryMarkerMs.remove(bufferKey)
            chathistoryMarkerArmedUntilMs.remove(bufferKey)
        }
        if (isHistory) {
            // Only track the newest history ts when the buffer's marker window is armed.
            // Armed = "user just joined this channel" or "we're in the bouncer playback
            // window after Connected" — i.e. a known catch-up, not a delayed playback
            // burst hours later. Without this gate, any history-flagged message at any
            // time would arm the marker, and the next live message (the user typing)
            // would fire it. That was the "marker randomly appears whilst typing" bug.
            val armedUntil = chathistoryMarkerArmedUntilMs[bufferKey] ?: 0L
            if (armedUntil >= System.currentTimeMillis()) {
                val prev = pendingChathistoryMarkerMs[bufferKey] ?: 0L
                if (ts > prev) pendingChathistoryMarkerMs[bufferKey] = ts
            }
        }

        // logging
        if (st.settings.loggingEnabled) {
            val (netId, bufferName) = splitKey(bufferKey)
            if (bufferName != "*server*" || st.settings.logServerBuffer) {
                val netName = st.networks.firstOrNull { it.id == netId }?.name ?: "network"
                val logLine = formatLogLine(ts, from, text, isAction)
                val logFolderUri = st.settings.logFolderUri
                // Dispatch the disk write off Main. Since the dispatcher refactor that
                // serialises handleEvent on Main.immediate, append() runs on the UI
                // thread for every incoming message; calling LogWriter.append directly
                // here would mean a BufferedWriter / SAF OutputStream write happens on
                // every message on Main. BufferedWriter usually completes in a few
                // microseconds (the buffer absorbs it), but when the 8 KB buffer fills
                // it flushes synchronously to disk - on slow storage, or on SAF where
                // the ContentResolver IPC adds milliseconds even for cached streams,
                // this can stack up into visible jank in busy channels. Worse, a flush
                // that takes >5 s during a heavy log-folder traversal triggers ANR.
                // Launching on IO trades zero-ordering between the in-memory append
                // and the on-disk write (the message reaches the screen first, the
                // file is updated a fraction of a second later) for guaranteed
                // off-Main I/O - logs are eventually-consistent anyway and this is
                // the same trade-off that every other Android logger makes.
                viewModelScope.launch(Dispatchers.IO) {
                    val err = runCatching {
                        logs.append(netName, bufferName, logLine, logFolderUri)
                    }.getOrElse { t -> t.message ?: t::class.java.simpleName }
                    if (err != null) {
                        // Surface failures back on Main via append, which is itself a
                        // recursive call - the inner append re-enters this branch but
                        // its bufferKey is the *server* buffer, so the loggingEnabled
                        // path appends to logs and the "log write failed" line itself
                        // gets logged too. That recursion is bounded by the buffer-name
                        // check above: a write failure on the server buffer just gets
                        // appended back to the server buffer in-memory and no further
                        // disk write is attempted because the *server* buffer's own
                        // log write either succeeded the same way or already failed
                        // and is silenced by logServerBuffer = false in the common case.
                        withContext(Dispatchers.Main.immediate) {
                            append(bufKey(netId, "*server*"), from = null, text = "*** Log write failed for $bufferName: $err", isLocal = true, doNotify = false)
                        }
                    }
                }
            }
        }

        // notifications
        // Suppress only when the buffer is actively visible to the user - i.e. it's the
        // selected buffer on the CHAT screen AND the app is in the foreground.
        // If the app is backgrounded, always notify regardless of which buffer is "selected",
        // because the user can't see the message.
        val isActivelyVisible = (bufferKey == st.selectedBuffer
            && st.screen == AppScreen.CHAT
            && AppVisibility.isForeground)
        if (doNotify && !isActivelyVisible && !isLocal && st.settings.notificationsEnabled) {
            val (netId, bufferName) = splitKey(bufferKey)
            val cleanText = stripIrcFormatting(text)
            val preview = when {
                from == null -> cleanText
                isAction -> "* $from $cleanText"
                else -> "<$from> $cleanText"
            }
            // notifTitle is what the user sees; bufferForNotif is the key used to
            // route the tap back to the correct buffer.  Keep them separate so that
            // the human-readable network name can be shown without being mistaken
            // for a channel name when the intent is handled.
            val notifTitle = if (bufferName == "*server*") {
                st.networks.firstOrNull { it.id == netId }?.name ?: "Server"
            } else bufferName
            val netDisplayName = st.networks.firstOrNull { it.id == netId }?.name ?: ""
            val bufferForNotif = bufferName  // always the real buffer key segment
            // Snippet for quote-fallback when server lacks +reply cap.
            val originalSnippet = stripIrcFormatting(text).take(100)
            val senderNick = from ?: ""
            // Build a stable cross-session anchor for notification → scroll.
            // Prefer the server-assigned IRC msgid (survives process restarts).
            // Fall back to epoch-seconds|nick|textPrefix which survives chathistory reload.
            val msgAnchor = when {
                msgId != null -> "msgid:$msgId"
                else -> "ts:${ts / 1000}|${(from ?: "")}|${stripIrcFormatting(text).take(80)}"
            }
            val net = st.networks.firstOrNull { it.id == netId }
            when {
                // Errors: opt-in per network, dedicated error notification, never a chat ping.
                isError -> {
                    if (net?.notifyOnErrors == true) {
                        runCatching { notifier.notifyError(netId, bufferForNotif, cleanText, notifTitle, msgAnchor = msgAnchor) }
                    }
                }
                // Sender on the highlight-ignore list: message is visible but raises no alert.
                senderNotifyIgnored -> { /* intentionally silent */ }
                isPrivate && st.settings.notifyOnPrivateMessages -> {
                    runCatching { notifier.notifyPm(netId, bufferForNotif, preview, msg.id, notifTitle, from = senderNick, originalText = originalSnippet, msgAnchor = msgAnchor, networkName = netDisplayName) }
                    if (st.settings.vibrateOnHighlight) {
                        runCatching { vibrateForHighlight(st.settings.vibrateIntensity) }
                    }
                }
                effectiveHighlight && st.settings.notifyOnHighlights -> {
                    runCatching { notifier.notifyHighlight(netId, bufferForNotif, preview, st.settings.playSoundOnHighlight, msg.id, notifTitle, from = senderNick, originalText = originalSnippet, msgAnchor = msgAnchor, networkName = netDisplayName) }
                    if (st.settings.vibrateOnHighlight) {
                        runCatching { vibrateForHighlight(st.settings.vibrateIntensity) }
                    }
                }
            }
        }
    }

    /**
    * Determine whether a message should be highlighted for a specific network.
    *
    * Important: nicks can differ per network, so we must NOT use the global UiState.myNick.
    * Highlight rules:
    * - Private messages always highlight.
    * - Nick + extra highlight words match as whole-words (prevents "eck" matching "check").
    * - Uses per-network CASEMAPPING when folding.
    */
    private fun isHighlight(netId: String, text: String, isPrivate: Boolean): Boolean {
        if (isPrivate) return true
        val s = _state.value.settings
        if (!s.highlightOnNick && s.extraHighlightWords.isEmpty()) return false

        val plain = stripIrcFormatting(text)
        val foldedText = casefoldText(netId, plain)

        fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

        fun containsWholeWord(needleFolded: String): Boolean {
            if (needleFolded.isBlank()) return false
            var from = 0
            while (true) {
                val idx = foldedText.indexOf(needleFolded, startIndex = from)
                if (idx < 0) return false
                val beforeIdx = idx - 1
                val afterIdx = idx + needleFolded.length
                val beforeOk = beforeIdx < 0 || !isWordChar(foldedText[beforeIdx])
                val afterOk = afterIdx >= foldedText.length || !isWordChar(foldedText[afterIdx])
                if (beforeOk && afterOk) return true
                from = idx + 1
                if (from >= foldedText.length) return false
            }
        }

        if (s.highlightOnNick) {
            val nick = _state.value.connections[netId]?.myNick ?: _state.value.myNick
            if (nick.isNotBlank() && containsWholeWord(casefoldText(netId, nick))) return true
        }

        for (w in s.extraHighlightWords) {
            val ww = w.trim()
            if (ww.isBlank()) continue
            if (containsWholeWord(casefoldText(netId, ww))) return true
        }

        return false
    }

    // Nicklist helpers (multi-status + CASEMAPPING aware)

    /**
     * Stable casefold for tracking in-flight /NAMES requests.
     *
     * We intentionally do NOT use casefoldText(netId, ...) here because CASEMAPPING (005) can arrive mid-request.
     * If folding rules change between the 353 and 366 numerics, the request key won't match, and the nicklist will
     * never get an initial snapshot (you'll only see users who join after you).
     */
    private fun namesKeyFold(channel: String): String {
        val sb = StringBuilder(channel.length)
        for (ch0 in channel) {
            var ch = ch0
            if (ch in 'A'..'Z') ch = (ch.code + 32).toChar()
            ch = when (ch) {
                '[', '{' -> '{'
                ']', '}' -> '}'
                '\\', '|' -> '|'
                '^', '~' -> '~'
                else -> ch
            }
            sb.append(ch)
        }
        return sb.toString()
    }

/**
 * Casefold [s] using the CASEMAPPING advertised by the given network's ISUPPORT 005.
 *
 * Mirrors IrcClient.casefold() exactly so that buffer-key comparisons in the ViewModel
 * are consistent with the comparisons IrcCore makes when routing incoming messages.
 *
 * rfc1459 / strict-rfc1459 - map the four extended ASCII special-char pairs.
 * ascii                     - ASCII A-Z only.
 * anything else             - full Unicode lowercase + RFC1459 special-char pairs.
 *   (Covers "BulgarianCyrillic+EnglishAlphabet" and any other non-standard token.)
 */
private fun casefoldText(netId: String, s: String): String {
    val cm = (runtimes[netId]?.support?.caseMapping ?: "rfc1459").lowercase(Locale.ROOT)
    val sb = StringBuilder(s.length)
    for (ch0 in s) {
        var ch = ch0
        if (ch in 'A'..'Z') ch = (ch.code + 32).toChar()
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
            "ascii" -> { /* ASCII A-Z already handled */ }
            else -> {
                ch = ch.lowercaseChar()
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

private fun prefixModes(netId: String): String = runtimes[netId]?.support?.prefixModes ?: "qaohv"
private fun prefixSymbols(netId: String): String = runtimes[netId]?.support?.prefixSymbols ?: "~&@%+"

private fun parseNickWithPrefixes(netId: String, display: String): Pair<String, Set<Char>> {
    val ps = prefixSymbols(netId)
    val pm = prefixModes(netId)
    var i = 0
    val modes = linkedSetOf<Char>()
    while (i < display.length && ps.indexOf(display[i]) >= 0) {
        val idx = ps.indexOf(display[i])
        if (idx in 0 until pm.length) modes.add(pm[idx])
        i++
    }
    val base = display.substring(i)
    return base to modes
}

private fun modeForPrefixSymbol(netId: String, sym: Char?): Char? {
    if (sym == null) return null
    val idx = prefixSymbols(netId).indexOf(sym)
    if (idx < 0) return null
    val pm = prefixModes(netId)
    return pm.getOrNull(idx)
}

private fun highestPrefixSymbol(netId: String, modes: Set<Char>): Char? {
    if (modes.isEmpty()) return null
    val pm = prefixModes(netId)
    val ps = prefixSymbols(netId)
    var bestIdx = Int.MAX_VALUE
    for (m in modes) {
        val idx = pm.indexOf(m)
        if (idx >= 0 && idx < bestIdx) bestIdx = idx
    }
    return if (bestIdx != Int.MAX_VALUE) ps.getOrNull(bestIdx) else null
}

private fun nickRank(netId: String, display: String): Int {
    val (_, modes) = parseNickWithPrefixes(netId, display)
    val sym = highestPrefixSymbol(netId, modes)
    val idx = if (sym != null) prefixSymbols(netId).indexOf(sym) else -1
    return if (idx >= 0) idx else prefixSymbols(netId).length
}

private fun rebuildNicklist(netId: String, chanKey: String): List<String> {
    val baseMap = chanNickCase[chanKey].orEmpty()
    val modeMap = chanNickStatus[chanKey].orEmpty()
    val ps = prefixSymbols(netId)
    val prefixChars = ps.toCharArray()

    val out = baseMap.entries.map { (fold, base) ->
        val modes = modeMap[fold].orEmpty()
        val sym = highestPrefixSymbol(netId, modes)
        (sym?.toString() ?: "") + base
    }

    return out.distinct().sortedWith(Comparator { a, b ->
        val ra = nickRank(netId, a)
        val rb = nickRank(netId, b)
        if (ra != rb) ra - rb
        else {
            val ba = a.trimStart(*prefixChars)
            val bb = b.trimStart(*prefixChars)
            casefoldText(netId, ba).compareTo(casefoldText(netId, bb))
        }
    })
}

private fun upsertNickInChannel(netId: String, chanKey: String, baseNick: String, modes: Set<Char>? = null) {
    val fold = casefoldText(netId, baseNick)
    val baseMap = chanNickCase.getOrPut(chanKey) { mutableMapOf() }
    baseMap[fold] = baseNick
    val modeMap = chanNickStatus.getOrPut(chanKey) { mutableMapOf() }
    if (modes != null) {
        modeMap[fold] = modes.toMutableSet()
    } else {
        modeMap.getOrPut(fold) { mutableSetOf() }
    }
}

private fun removeNickFromChannel(netId: String, chanKey: String, nick: String) {
    val fold = casefoldText(netId, nick)
    chanNickCase[chanKey]?.remove(fold)
    chanNickStatus[chanKey]?.remove(fold)
    if (chanNickCase[chanKey]?.isEmpty() == true) chanNickCase.remove(chanKey)
    if (chanNickStatus[chanKey]?.isEmpty() == true) chanNickStatus.remove(chanKey)
}

private fun setNicklistState(netId: String, chanKey: String) {
    val st = _state.value
    val rebuilt = rebuildNicklist(netId, chanKey)
    _state.value = syncActiveNetworkSummary(st.copy(nicklists = st.nicklists + (chanKey to rebuilt)))
}

private fun applyNamesDelta(netId: String, chanKey: String, names: List<String>) {
    for (raw in names) {
        val (base, modes) = parseNickWithPrefixes(netId, raw)
        if (base.isBlank()) continue
        upsertNickInChannel(netId, chanKey, baseNick = base, modes = modes)
    }
    setNicklistState(netId, chanKey)
}

private fun applyNamesSnapshot(netId: String, chanKey: String, names: List<String>) {
    chanNickCase[chanKey] = mutableMapOf()
    chanNickStatus[chanKey] = mutableMapOf()
    applyNamesDelta(netId, chanKey, names)
}

private fun updateUserMode(netId: String, chanKey: String, nick: String, prefixSym: Char?, adding: Boolean) {
    val fold = casefoldText(netId, nick)
    val baseMap = chanNickCase.getOrPut(chanKey) { mutableMapOf() }
    val modeMap = chanNickStatus.getOrPut(chanKey) { mutableMapOf() }
    baseMap.putIfAbsent(fold, nick)
    val set = modeMap.getOrPut(fold) { mutableSetOf() }
    val mode = modeForPrefixSymbol(netId, prefixSym) ?: return
    if (adding) set.add(mode) else set.remove(mode)
    setNicklistState(netId, chanKey)
}

private fun moveNickAcrossChannels(netId: String, oldNick: String, newNick: String) {
    val oldFold = casefoldText(netId, oldNick)
    val newFold = casefoldText(netId, newNick)
    val keys = chanNickCase.keys.filter { it.startsWith("$netId::") }
    for (k in keys) {
        val baseMap = chanNickCase[k] ?: continue
        val base = baseMap.remove(oldFold) ?: continue
        val modes = chanNickStatus[k]?.remove(oldFold)
        baseMap[newFold] = newNick
        if (modes != null) {
            val mm = chanNickStatus.getOrPut(k) { mutableMapOf() }
            mm[newFold] = modes
        }
    }
}



    // Connection notifications

    private fun updateConnectionNotification(status: String) {
        refreshConnectionNotification(statusOverride = status)
    }

    private fun clearConnectionNotification() {
        refreshConnectionNotification(statusOverride = null)
    }

    private fun refreshConnectionNotification(statusOverride: String? = null) {
        val st = _state.value
        if (appExitRequested) {
            // Don't resurrect the notification/FGS during an explicit user exit.
            runCatching { appContext.stopService(Intent(appContext, KeepAliveService::class.java)) }
            runCatching {
                val i = Intent(appContext, KeepAliveService::class.java).apply { action = KeepAliveService.ACTION_STOP }
                appContext.startService(i)
            }
            runCatching { notifier.cancelConnection() }
            return
        }
        if (!st.settings.showConnectionStatusNotification && !st.settings.keepAliveInBackground) {
            // Ensure we don't leave stale notifications behind.
            runCatching {
                val i = Intent(appContext, KeepAliveService::class.java).apply { action = KeepAliveService.ACTION_STOP }
                appContext.startService(i)
            }
            notifier.cancelConnection()
            return
        }

        val connectedIds = st.connections.filterValues { it.connected }.keys
        val connectingIds = st.connections.filterValues { it.connecting }.keys

        val displayIds: List<String> = when {
            connectedIds.isNotEmpty() -> connectedIds.toList()
            connectingIds.isNotEmpty() -> connectingIds.toList()
            else -> emptyList()
        }

        if (displayIds.isEmpty()) {
            // If keep-alive is enabled and the user still wants networks connected (auto-reconnect),
            // keep the foreground service alive so the process + reconnect loop can keep running.
            if (st.settings.keepAliveInBackground && desiredConnected.isNotEmpty()) {
                val wanted = desiredConnected.toList()
                val namesWanted = wanted.mapNotNull { id -> st.networks.firstOrNull { it.id == id }?.name }.ifEmpty { wanted }
                val labelWanted = if (wanted.size > 1) {
                    "${wanted.size} networks: ${namesWanted.joinToString(", ")}"
                } else {
                    val net = st.networks.firstOrNull { it.id == wanted.first() }
                    if (net != null) "${net.name} • ${net.host}:${net.port}" else "HexDroid IRC"
                }
                val netIdForIntent = st.activeNetworkId?.takeIf { wanted.contains(it) } ?: wanted.first()
                val statusTxt = if (!hasInternetConnection()) "Waiting for network…" else "Reconnecting…"
                val i = Intent(appContext, KeepAliveService::class.java).apply {
                    action = KeepAliveService.ACTION_UPDATE
                    putExtra(KeepAliveService.EXTRA_NETWORK_ID, netIdForIntent)
                    putExtra(KeepAliveService.EXTRA_SERVER_LABEL, labelWanted)
                    putExtra(KeepAliveService.EXTRA_STATUS, statusTxt)
                }
                runCatching {
                    if (KeepAliveService.isRunning) {
                        appContext.startService(i)
                    } else if (AppVisibility.isForeground) {
                        ContextCompat.startForegroundService(appContext, i)
                    } else {
                        notifier.showConnection(netIdForIntent, labelWanted, statusTxt)
                    }
                }.onFailure {
                    notifier.showConnection(netIdForIntent, labelWanted, statusTxt)
                }
                return
            }

            lastNotifLabel = null
            lastNotifStatus = null
            runCatching {
                val i = Intent(appContext, KeepAliveService::class.java).apply { action = KeepAliveService.ACTION_STOP }
                appContext.startService(i)
            }
            notifier.cancelConnection()
            return
        }

        val netIdForIntent = st.activeNetworkId?.takeIf { displayIds.contains(it) } ?: displayIds.first()
        val names = displayIds.mapNotNull { id -> st.networks.firstOrNull { it.id == id }?.name }.ifEmpty { displayIds }

        val label = if (displayIds.size > 1) {
            "${displayIds.size} networks: ${names.joinToString(", ")}" // NotificationHelper prefixes with "Connected to"
        } else {
            val net = st.networks.firstOrNull { it.id == displayIds.first() }
            if (net != null) "${net.name} • ${net.host}:${net.port}" else "HexDroid IRC"
        }

        val status = statusOverride ?: when {
            connectedIds.isNotEmpty() && connectingIds.isNotEmpty() ->
                "Connected (${connectedIds.size}), connecting (${connectingIds.size})"
            connectedIds.isNotEmpty() -> "Connected"
            else -> "Connecting…"
        }

        if (st.settings.keepAliveInBackground) {
            // Skip the Binder IPC if the visible text hasn't changed - avoids waking
            // NotificationManager on every lag update / ping cycle (once per minute).
            if (label == lastNotifLabel && status == lastNotifStatus && KeepAliveService.isRunning) return
            lastNotifLabel = label
            lastNotifStatus = status

            val i = Intent(appContext, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_UPDATE
                putExtra(KeepAliveService.EXTRA_NETWORK_ID, netIdForIntent)
                putExtra(KeepAliveService.EXTRA_SERVER_LABEL, label)
                putExtra(KeepAliveService.EXTRA_STATUS, status)
            }

            // Android 12+ can throw ForegroundServiceStartNotAllowedException if we try to start an FGS
            // while the app is in the background. Also, if the service is already running, we can just
            // deliver the update intent via startService().
            runCatching {
                if (KeepAliveService.isRunning) {
                    appContext.startService(i)
                } else if (AppVisibility.isForeground) {
                    ContextCompat.startForegroundService(appContext, i)
                } else {
                    // Background-start of a foreground service may be blocked on Android 12+.
                    notifier.showConnection(netIdForIntent, label, status)
                    return
                }
            }.onFailure {
                // how a normal notification instead of crashing.
                notifier.showConnection(netIdForIntent, label, status)
            }
            return
        }

        if (st.settings.showConnectionStatusNotification) {
            notifier.showConnection(netIdForIntent, label, status)
        } else {
            notifier.cancelConnection()
        }
    }

    // DCC

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

    private fun doAcceptDccCommon(offer: DccOffer, resumeFrom: PartialTransfer?) {
        val st = _state.value

        // Android 17+: DCC connections to LAN peers require ACCESS_LOCAL_NETWORK.
        // Active DCC connects to the sender's IP; passive DCC binds a local port (that's fine,
        // but the sender then connects back to us over the LAN - still needs the permission).
        if (!offer.isPassive && isLocalHost(offer.ip) && !hasLocalNetworkPermission()) {
            append(bufKey(offer.netId.ifBlank { st.activeNetworkId ?: "" }, "*server*"),
                from = null,
                text = "*** DCC from ${offer.from}: local network permission required (Android 17+). Grant it in Settings → Apps → HexDroid → Permissions.",
                isHighlight = true)
            return
        }

        _state.value = st.copy(dccOffers = st.dccOffers.filterNot { it == offer })

        val resumeOffset = resumeFrom?.receivedBytes ?: 0L
        val incoming = DccTransferState.Incoming(
            offer = offer,
            received = resumeOffset,
            resumeOffset = resumeOffset,
            savedPath = resumeFrom?.savedPath,
        )
        _state.value = _state.value.copy(dccTransfers = _state.value.dccTransfers + incoming)

        // route the transfer through the network where the offer was received.
        val netId = offer.netId.takeIf { it.isNotBlank() } ?: _state.value.activeNetworkId ?: return
        val rt = runtimes[netId] ?: return
        val c = rt.client
        // A passive offer means WE must listen for the sender to connect back. A CONNECT-only
        // proxy (Tor) can't accept inbound connections, and listening on a local port while
        // proxied would expose our real IP/port to the sender outside the tunnel — defeating
        // the point. Refuse rather than leak. (Active offers, where we dial out, are fine and
        // get tunnelled below.) Use the cheap flag check here; the full proxy config (with the
        // encrypted password) is loaded off the main thread inside the launch below.
        if (isProxiedNetwork(netId) && offer.isPassive) {
            _state.value = _state.value.copy(dccTransfers = _state.value.dccTransfers.filterNot {
                it is DccTransferState.Incoming && it.offer == offer
            })
            append(bufKey(netId, "*server*"), from = null,
                text = "*** Can't accept passive DCC from ${offer.from} while a proxy is active: " +
                    "it would require listening outside the tunnel. Ask them to send actively, or disable the proxy for this transfer.",
                isHighlight = true)
            return
        }
        // Active offer through a proxy: we dial out via the tunnel, which is fine for a
        // routable peer. But a private/loopback/link-local target can't be the peer's real
        // address once we're tunnelling. connecting to it would make the proxy probe its OWN
        // local network on our behalf. Refuse rather than turn the proxy into a scanner.
        if (isProxiedNetwork(netId) && !offer.isPassive && isLocalHost(offer.ip)) {
            _state.value = _state.value.copy(dccTransfers = _state.value.dccTransfers.filterNot {
                it is DccTransferState.Incoming && it.offer == offer
            })
            append(bufKey(netId, "*server*"), from = null,
                text = "*** Refusing DCC from ${offer.from}: it advertises a private/LAN address (${offer.ip}) " +
                    "that isn't reachable through the proxy. Disable the proxy for this transfer if they're on your LAN.",
                isHighlight = true)
            return
        }
        val minP = st.settings.dccIncomingPortMin
        val maxP = st.settings.dccIncomingPortMax
        val customFolder = st.settings.dccDownloadFolderUri
        val baseName = offer.filename.substringAfterLast('/').substringAfterLast('\\')

        viewModelScope.launch {
            // Register the Job so cancelIncomingDcc() can find it. The map entry is
            // removed on completion regardless of how the coroutine ended (normal,
            // error, or cancellation) so we don't leak entries for finished transfers.
            incomingReceiveJobs[offer] = checkNotNull(coroutineContext[kotlinx.coroutines.Job]) {
                "No Job in coroutine context"
            }
            try {
                // Load the proxy (incl. encrypted password) off the main thread (this launch
                // runs on Main by default). Only the active (outbound) receive path uses it;
                // the passive branch can't be reached while proxied (guarded above).
                val proxy = withContext(Dispatchers.IO) { proxyForNetwork(netId) }
                // If resuming, run the CTCP RESUME / ACCEPT handshake first. If the sender
                // doesn't ACCEPT within the timeout (it may not support RESUME), we silently
                // fall back to a fresh download — better UX than failing the transfer.
                val effectiveOffset: Long = if (resumeFrom != null) {
                    negotiateResumeOrZero(netId, c, offer, resumeFrom)
                } else 0L

                val savedPath = if (offer.isPassive) {
                    // Passive/reverse DCC: we open a port and tell the sender to connect.
                    dcc.receivePassive(
                        offer = offer,
                        portMin = minP,
                        portMax = maxP,
                        customFolderUri = customFolder,
                        resumeOffset = effectiveOffset,
                        resumeSavedPath = if (effectiveOffset > 0L) resumeFrom?.savedPath else null,
                        onSavedPath = { path ->
                            // Stamp the path on the Incoming state as soon as the file is opened
                            // so a mid-transfer error can still leave a useful partial pointer.
                            updateIncoming(offer) { it.copy(savedPath = path) }
                        },
                        onListening = { addrField, port, size, token ->
                            val name = quoteDccFilenameIfNeeded(offer.filename)
                            val tokenStr = if (offer.turbo) "${token}T" else token.toString()
                            val payload = "DCC SEND $name $addrField $port $size $tokenStr"
                            c.ctcp(offer.from, payload)
                            val resumeNote = if (effectiveOffset > 0L) " (resuming from ${effectiveOffset} bytes)" else ""
                            append(bufKey(netId, "*server*"), from = null, text = "*** Accepted passive DCC offer: ${offer.filename} (listening on $port)$resumeNote", doNotify = false)
                        }
                    ) { got, _ ->
                        updateIncoming(offer) { it.copy(received = got) }
                    }
                } else {
                    dcc.receive(
                        offer = offer,
                        customFolderUri = customFolder,
                        resumeOffset = effectiveOffset,
                        resumeSavedPath = if (effectiveOffset > 0L) resumeFrom?.savedPath else null,
                        proxy = proxy,
                        onSavedPath = { path -> updateIncoming(offer) { it.copy(savedPath = path) } },
                    ) { got, _ ->
                        updateIncoming(offer) { it.copy(received = got) }
                    }
                }
                updateIncoming(offer) { it.copy(done = true, savedPath = savedPath, endTimeMs = System.currentTimeMillis()) }
                // Clear the partial record on success (don't delete the file — it IS the completed download).
                dccPartials.remove(offer.from, baseName, offer.size)
                val displayPath = if (savedPath.startsWith("content://")) "Downloads" else savedPath.substringAfterLast('/')
                notifier.notifyFileDone(netId, offer.filename, displayPath)
            } catch (t: Throwable) {
                // A user cancel manifests in two ways depending on timing: as a
                // CancellationException at a suspension point, OR as an IOException
                // bubbling up from the socket close that DccManager's invokeOnCompletion
                // triggered. Either way, !isActive is true once cancel() has been called,
                // which is the reliable signal.
                val cancelled = !isActive || t is kotlinx.coroutines.CancellationException
                val msg = if (cancelled) "Cancelled" else (t.message ?: "error")
                val cur = _state.value.dccTransfers.firstOrNull {
                    it is DccTransferState.Incoming && it.offer == offer
                } as? DccTransferState.Incoming
                updateIncoming(offer) {
                    if (it.done || it.error != null) it
                    else it.copy(error = msg, endTimeMs = System.currentTimeMillis())
                }
                // Record the partial so a future offer can be RESUMEd. We need both a non-empty
                // saved path and at least one byte on disk for the entry to be useful.
                val partialPath = cur?.savedPath
                val partialBytes = cur?.received ?: 0L
                if (!partialPath.isNullOrBlank() && partialBytes > 0L && offer.size > 0L) {
                    dccPartials.put(
                        PartialTransfer(
                            from = offer.from,
                            filename = baseName,
                            size = offer.size,
                            savedPath = partialPath,
                            receivedBytes = partialBytes,
                            secure = offer.secure,
                            turbo = offer.turbo,
                        )
                    )
                }
                if (t is kotlinx.coroutines.CancellationException) throw t
            } finally {
                incomingReceiveJobs.remove(offer)
            }
        }
    }

    /**
     * Send `DCC RESUME` to the peer and wait for a matching `DCC ACCEPT`. Returns the agreed
     * offset (typically equal to [partial].receivedBytes) on success, or 0L if the peer didn't
     * reply within the timeout (graceful fallback to a fresh transfer).
     *
     * Why we don't fail hard on no-reply: many older clients/bots don't implement RESUME, but
     * happily send a fresh stream from byte 0 in response to our normal SEND-acknowledgement.
     * Falling back to byte 0 is strictly better than telling the user "RESUME failed, try again".
     */
    private suspend fun negotiateResumeOrZero(
        netId: String,
        c: IrcClient,
        offer: DccOffer,
        partial: PartialTransfer,
        timeoutMs: Long = 10_000L,
    ): Long {
        val baseName = offer.filename.substringAfterLast('/').substringAfterLast('\\')
        val key = "${offer.from.lowercase()}|$baseName|${offer.size}"
        val def = CompletableDeferred<DccAccept>()
        pendingResumeRequests[key] = def

        val name = quoteDccFilenameIfNeeded(offer.filename)
        // Active offer: <port> is the sender's port from the offer.
        // Passive offer: <port> is 0 and <token> is required (echoes the offer's token).
        val portField = if (offer.isPassive) 0 else offer.port
        val tokenField = if (offer.isPassive) " ${offer.token}" else ""
        val resumePayload = "DCC RESUME $name $portField ${partial.receivedBytes}$tokenField"
        c.ctcp(offer.from, resumePayload)
        append(bufKey(netId, "*server*"), from = null,
            text = "*** Requested DCC RESUME for ${offer.filename} at ${partial.receivedBytes} bytes…",
            doNotify = false)

        return try {
            val accept = withTimeout(timeoutMs) { def.await() }
            // Defensive: if the peer ACCEPTed a different position than we asked for, honour
            // their position (clamped to the partial's actual size). Some clients align to
            // block boundaries.
            val agreed = accept.position.coerceIn(0L, partial.receivedBytes)
            if (agreed != partial.receivedBytes) {
                append(bufKey(netId, "*server*"), from = null,
                    text = "*** Sender accepted RESUME at $agreed bytes (we asked for ${partial.receivedBytes})",
                    doNotify = false)
            }
            agreed
        } catch (_: TimeoutCancellationException) {
            append(bufKey(netId, "*server*"), from = null,
                text = "*** No DCC ACCEPT from ${offer.from} — restarting transfer from the beginning.",
                doNotify = false)
            0L
        } finally {
            pendingResumeRequests.remove(key)
        }
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
        append(bufKey(netId, "*server*"), from = null, text = "*** Rejected DCC offer: ${offer.filename}", doNotify = false)
    }

    private fun isDccChatBufferName(name: String): Boolean = name.startsWith("DCCCHAT:")

    private fun dccChatBufferKey(netId: String, peerNick: String): String = bufKey(netId, "DCCCHAT:$peerNick")

    private fun closeDccChatSession(bufferKey: String, reason: String? = null) {
        val ses = dccChatSessions.remove(bufferKey) ?: return
        runCatching { ses.readJob.cancel() }
        runCatching { ses.socket.close() }
        val r = reason?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        append(bufferKey, from = null, text = "*** DCC CHAT disconnected$r", doNotify = false)
    }

    private fun startDccChatSession(netId: String, peer: String, bufferKey: String, socket: Socket) {
        // Replace any existing session for this buffer.
        closeDccChatSession(bufferKey, reason = "replaced")

        runCatching {
            socket.tcpNoDelay = true
            socket.keepAlive = true
        }

        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

        val job = viewModelScope.launch(Dispatchers.IO) {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val isAction = line.startsWith("\u0001ACTION ") && line.endsWith("\u0001")
                    val text = if (isAction) {
                        line.removePrefix("\u0001ACTION ").removeSuffix("\u0001")
                    } else line
                    withContext(Dispatchers.Main) {
                        append(bufferKey, from = peer, text = text, isAction = isAction)
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    append(bufferKey, from = null, text = "*** DCC CHAT failed: ${(t.message ?: t::class.java.simpleName)}", isHighlight = true)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    dccChatSessions.remove(bufferKey)
                    runCatching { socket.close() }
                    append(bufferKey, from = null, text = "*** DCC CHAT closed", doNotify = false)
                }
            }
        }

        dccChatSessions[bufferKey] = DccChatSession(netId, peer, bufferKey, socket, writer, job)
        append(bufferKey, from = null, text = "*** DCC CHAT connected to $peer", doNotify = false)
    }

    private fun sendDccChatLine(bufferKey: String, line: String, isAction: Boolean) {
        val ses = dccChatSessions[bufferKey]
        if (ses == null) {
            append(bufferKey, from = null, text = "*** DCC CHAT not connected.", isHighlight = true)
            return
        }

        val payload = if (isAction) "\u0001ACTION $line\u0001" else line

        // Writing to a socket on the main thread can throw (StrictMode / NetworkOnMainThreadException)
        // and will also make typing feel laggy if the peer/network is slow. Always write on IO.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                synchronized(ses.writer) {
                    ses.writer.write(payload)
                    ses.writer.write("\r\n")
                    ses.writer.flush()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    append(
                        bufferKey,
                        from = null,
                        text = "*** DCC CHAT send failed: ${(t.message ?: t::class.java.simpleName)}",
                        isHighlight = true
                    )
                    closeDccChatSession(bufferKey, reason = t.message)
                }
                return@launch
            }

            withContext(Dispatchers.Main.immediate) {
                val myNick = _state.value.connections[ses.netId]?.myNick ?: _state.value.myNick
                append(bufferKey, from = myNick, text = line, isAction = isAction)
            }
        }
    }

    fun acceptDccChat(offer: DccChatOffer) {
        val st = _state.value
        _state.value = st.copy(dccChatOffers = st.dccChatOffers.filterNot { it == offer })

        val netId = offer.netId.takeIf { it.isNotBlank() } ?: st.activeNetworkId ?: return
        val peer = offer.from
        val key = dccChatBufferKey(netId, peer)
        ensureBuffer(key)
        _state.value = _state.value.copy(selectedBuffer = key)

        // See acceptDcc: a private/LAN target can't be the real peer through a proxy tunnel,
        // and dialling it would make the proxy probe its own local network. Refuse.
        if (isProxiedNetwork(netId) && isLocalHost(offer.ip)) {
            append(key, from = null,
                text = "*** Refusing DCC CHAT from $peer: it advertises a private/LAN address (${offer.ip}) " +
                    "not reachable through the proxy. Disable the proxy for this network if they're on your LAN.",
                isHighlight = true)
            return
        }

        // Android 17+: connecting to a LAN peer requires ACCESS_LOCAL_NETWORK.
        if (isLocalHost(offer.ip) && !hasLocalNetworkPermission()) {
            append(key, from = null,
                text = "*** DCC CHAT from $peer: local network permission required (Android 17+). Grant it in Settings → Apps → HexDroid → Permissions.",
                isHighlight = true)
            return
        }

        viewModelScope.launch {
            try {
                append(key, from = null, text = "*** Connecting DCC CHAT to ${offer.from} (${offer.ip}:${offer.port})…", doNotify = false)
                val chatProxy = withContext(Dispatchers.IO) { proxyForNetwork(netId) }
                val socket = dcc.connectChat(offer, proxy = chatProxy)
                startDccChatSession(netId, peer, key, socket)
            } catch (t: Throwable) {
                append(key, from = null, text = "*** DCC CHAT connect failed: ${(t.message ?: t::class.java.simpleName)}", isHighlight = true)
            }
        }
    }

    fun rejectDccChat(offer: DccChatOffer) {
        _state.value = _state.value.copy(dccChatOffers = _state.value.dccChatOffers.filterNot { it == offer })
        val netId = offer.netId.takeIf { it.isNotBlank() } ?: _state.value.activeNetworkId ?: return
        append(bufKey(netId, "*server*"), from = null, text = "*** Rejected DCC CHAT offer from ${offer.from}", doNotify = false)
    }

    fun startDccChat(targetNick: String) = startDccChatFlow(targetNick)

    fun startDccChatFlow(targetNick: String) {
        val st = _state.value
        val netId = st.activeNetworkId ?: return
        val rt = runtimes[netId] ?: return
        val c = rt.client
        val peer = targetNick.trim().trimStart('~', '&', '@', '%', '+')
        if (peer.isBlank()) return

        if (!st.settings.dccEnabled) {
            append(bufKey(netId, "*server*"), from = "DCC", text = "DCC is disabled in settings.", isHighlight = true)
            return
        }

        // Active DCC CHAT requires us to listen for the peer to connect in. A CONNECT-only
        // proxy can't do that, and listening locally while proxied would expose our real
        // address. There's no passive/reverse CHAT equivalent that's widely supported, so
        // refuse outright while a proxy is active.
        if (isProxiedNetwork(netId)) {
            append(bufKey(netId, "*server*"), from = "DCC",
                text = "Can't offer DCC CHAT while a proxy is active (it requires listening outside the tunnel). Disable the proxy for this network to use DCC CHAT.",
                isHighlight = true)
            return
        }

        val key = dccChatBufferKey(netId, peer)
        ensureBuffer(key)
        _state.value = _state.value.copy(selectedBuffer = key)

        val minP = st.settings.dccIncomingPortMin
        val maxP = st.settings.dccIncomingPortMax

        viewModelScope.launch {
            try {
                val secure = st.settings.dccSecure
                val chatVerb = if (secure) "SCHAT" else "CHAT"
                val secureLabel = if (secure) " (SDCC/TLS)" else ""
                append(key, from = null, text = "*** Offering DCC${secureLabel} CHAT to $peer…", doNotify = false)
                val socket = dcc.startChat(
                    portMin = minP,
                    portMax = maxP,
                    onClient = { addrField, port ->
                        val payload = "DCC $chatVerb chat $addrField $port"
                        c.ctcp(peer, payload)
                        append(bufKey(netId, "*server*"), from = null, text = "*** Sent DCC$secureLabel CHAT offer to $peer (port $port)", doNotify = false)
                    }
                )
                startDccChatSession(netId, peer, key, socket)
            } catch (t: Throwable) {
                append(key, from = null, text = "*** DCC CHAT offer failed: ${(t.message ?: t::class.java.simpleName)}", isHighlight = true)
            }
        }
    }

    private fun updateIncoming(offer: DccOffer, f: (DccTransferState.Incoming) -> DccTransferState.Incoming) {
        val st = _state.value
        val updated = st.dccTransfers.map {
            if (it is DccTransferState.Incoming && it.offer == offer) f(it) else it
        }
        _state.value = st.copy(dccTransfers = updated)
    }

    private fun quoteDccFilenameIfNeeded(nameRaw: String): String {
        val name = nameRaw.replace('"', '_').trim()
        return if (name.any { it.isWhitespace() }) "\"$name\"" else name
    }

    fun sendDccFileFlow(uri: android.net.Uri, targetNick: String) {
        val netId = _state.value.activeNetworkId ?: return
        val rt = runtimes[netId] ?: return
        val c = rt.client
        if (targetNick.isBlank()) return
        val target = targetNick.trimStart('~', '&', '@', '%', '+')

        if (!_state.value.settings.dccEnabled) {
            append(bufKey(netId, "*server*"), from = "DCC", text = "DCC is disabled in settings.", isHighlight = true)
            return
        }

        val job = viewModelScope.launch {
            var offerNameForState: String? = null
            // Buffer to show DCC status messages in - the target's query buffer if open,
            // otherwise the server buffer.
            val statusKey = run {
                val targetKey = resolveBufferKey(netId, target)
                if (_state.value.buffers.containsKey(targetKey)) targetKey
                else bufKey(netId, "*server*")
            }
            try {
                val prepared = prepareDccSendFile(uri)
                val file = prepared.file
                val offerName = prepared.offerName
                offerNameForState = offerName
                val jobKey = "$target/$offerName"
                // coroutineContext[Job] is always non-null inside a launch block.
                outgoingSendJobs[jobKey] = checkNotNull(coroutineContext[kotlinx.coroutines.Job]) { "No Job in coroutine context" }
                val st = _state.value
                val minP = st.settings.dccIncomingPortMin
                val maxP = st.settings.dccIncomingPortMax
                val mode = st.settings.dccSendMode

                val offerNamePayload = quoteDccFilenameIfNeeded(offerName)
                val fileSize = runCatching { file.length() }.getOrDefault(0L)

                // This network's proxy, captured once before the send helpers so the passive
                // connect can tunnel through it. Loaded off the main thread (this launch runs
                // on Main) since proxyForNetwork reads the encrypted password. Passed
                // explicitly to dcc.sendFileConnect rather than via shared state, so concurrent
                // transfers can't race over it.
                val sendProxy = withContext(Dispatchers.IO) { proxyForNetwork(netId) }

                val outgoing = DccTransferState.Outgoing(target = target, filename = offerName, fileSize = fileSize)
                _state.value = st.copy(dccTransfers = st.dccTransfers + outgoing)

                fun updateOutgoing(sent: Long) {
                    val st2 = _state.value
                    _state.value = st2.copy(dccTransfers = st2.dccTransfers.map {
                        if (it is DccTransferState.Outgoing && it.target == target && it.filename == offerName) it.copy(bytesSent = sent) else it
                    })
                }

                suspend fun doActiveSend() {
                    val secure = st.settings.dccSecure
                    val verb = if (secure) "SSEND" else "SEND"
                    // We need to know our listening port before we can register the live-send
                    // entry that incoming DCC RESUME requests are matched against. Bind happens
                    // inside dcc.sendFile; the port is delivered to us in `onClient`. To bridge
                    // that we keep a placeholder key and rewrite it once the port is known.
                    val baseName = offerName.substringAfterLast('/').substringAfterLast('\\')
                    var liveKey: String? = null
                    val liveDeferred = CompletableDeferred<Long>()
                    val absolutePath = file.absolutePath
                    dcc.sendFile(
                        file = file,
                        portMin = minP,
                        portMax = maxP,
                        secure = secure,
                        onClient = { addrField, port, size ->
                            val payload = "DCC $verb $offerNamePayload $addrField $port $size"
                            // Register BEFORE sending the CTCP so a quick RESUME reply finds us.
                            val key = "${target.lowercase()}|$baseName|$size"
                            liveKey = key
                            liveOutgoingSends[key] = LiveOutgoingSend(
                                target = target,
                                filename = offerName,
                                absolutePath = absolutePath,
                                size = size,
                                port = port,
                                token = null,
                                resumeRequest = liveDeferred,
                            )
                            c.ctcp(target, payload)
                            val secureLabel = if (secure) " (SDCC/TLS)" else ""
                            append(statusKey, from = null, text = "*** Offering $offerName to $target via DCC$secureLabel (active, port $port)…", doNotify = false)
                        },
                        awaitStartOffset = {
                            // Short window for a late RESUME; if the peer didn't ask for resume,
                            // proceed from byte 0. The receiver-side spec says RESUME, if used,
                            // is sent before the data connection, but a tiny race grace is cheap.
                            // withTimeoutOrNull keeps us off the experimental getCompleted() API.
                            val offset = withTimeoutOrNull(500L) { liveDeferred.await() } ?: 0L
                            if (offset > 0L) {
                                val st4 = _state.value
                                _state.value = st4.copy(dccTransfers = st4.dccTransfers.map {
                                    if (it is DccTransferState.Outgoing && it.target == target && it.filename == offerName)
                                        it.copy(resumeOffset = offset, bytesSent = offset)
                                    else it
                                })
                            }
                            offset
                        },
                        onProgress = { sent, _ -> updateOutgoing(sent) }
                    )
                    liveKey?.let { liveOutgoingSends.remove(it) }
                }

                suspend fun doPassiveSend(timeoutMs: Long = 120_000L) {
                    val secure = st.settings.dccSecure
                    val verb = if (secure) "SSEND" else "SEND"
                    val token = Random.nextLong(1L, 0x7FFFFFFFL)
                    val def = CompletableDeferred<DccOffer>()
                    val baseName = offerName.substringAfterLast('/').substringAfterLast('\\')
                    pendingPassiveDccSends[token] = PendingPassiveDccSend(target, baseName, fileSize, def)
                    val liveKey = "${target.lowercase()}|$baseName|$fileSize"
                    val liveDeferred = CompletableDeferred<Long>()
                    liveOutgoingSends[liveKey] = LiveOutgoingSend(
                        target = target,
                        filename = offerName,
                        absolutePath = file.absolutePath,
                        size = fileSize,
                        port = 0,
                        token = token,
                        resumeRequest = liveDeferred,
                    )
                    try {
                        // In passive DCC the advertised IP is unused for the connection (the
                        // receiver sends back its own address and we dial out). When proxied
                        // we deliberately advertise 0 rather than our real LAN IP: emitting
                        // the local address in the CTCP would leak network metadata about a
                        // user who turned the proxy on precisely for anonymity. Many clients
                        // already tolerate 0 here since the field is informational for passive.
                        val ipField = if (sendProxy.enabled) "0" else dcc.dccAddressField()
                        val payload = "DCC $verb $offerNamePayload $ipField 0 $fileSize $token"
                        c.ctcp(target, payload)
                        val secureLabel = if (secure) " (SDCC/TLS)" else ""
                        append(statusKey, from = null, text = "*** Offering $offerName to $target via DCC$secureLabel (passive)…", doNotify = false)

                        val reply = withTimeout(timeoutMs) { def.await() }
                        if (reply.port <= 0) throw IOException("Invalid passive DCC reply")
                        // Resume race: per the DCC RESUME protocol the receiver sends RESUME
                        // *before* its DCC SEND reply opens the port, so by the time we get
                        // `reply` here the resume offset (if any) is already settled and the
                        // await() returns immediately. The small timeout is a generous safety
                        // margin for thread-scheduling races and avoids the experimental
                        // getCompleted() API.
                        val startOffset = withTimeoutOrNull(100L) { liveDeferred.await() } ?: 0L
                        if (startOffset > 0L) {
                            val st4 = _state.value
                            _state.value = st4.copy(dccTransfers = st4.dccTransfers.map {
                                if (it is DccTransferState.Outgoing && it.target == target && it.filename == offerName)
                                    it.copy(resumeOffset = startOffset, bytesSent = startOffset)
                                else it
                            })
                            append(statusKey, from = null, text = "*** $target accepted; resuming from $startOffset bytes…", doNotify = false)
                        } else {
                            append(statusKey, from = null, text = "*** $target accepted; connecting…", doNotify = false)
                        }

                        dcc.sendFileConnect(
                            file = file,
                            host = reply.ip,
                            port = reply.port,
                            secure = secure,
                            startOffset = startOffset,
                            proxy = sendProxy,
                            onProgress = { sent, _ -> updateOutgoing(sent) }
                        )
                    } finally {
                        pendingPassiveDccSends.remove(token)
                        liveOutgoingSends.remove(liveKey)
                    }
                }

                // With a proxy active we can't listen for an inbound connection, so an ACTIVE
                // send (where the peer dials us) is impossible. Force PASSIVE — we dial the
                // peer outbound through the proxy instead. This mirrors how mIRC behind a
                // SOCKS firewall falls back to reverse/passive DCC. If the user explicitly
                // pinned ACTIVE, tell them why we're overriding.
                val effectiveMode = if (sendProxy.enabled && mode != DccSendMode.PASSIVE) {
                    if (mode == DccSendMode.ACTIVE) {
                        append(statusKey, from = null,
                            text = "*** Proxy active: using passive DCC (active send can't listen through a proxy).",
                            doNotify = false)
                    }
                    DccSendMode.PASSIVE
                } else mode

                when (effectiveMode) {
                    DccSendMode.ACTIVE -> doActiveSend()
                    DccSendMode.PASSIVE -> doPassiveSend()
                    DccSendMode.AUTO -> {
                        // AUTO tries passive first. If the peer doesn't respond within the
                        // timeout we give up rather than sending a second unsolicited CTCP -
                        // firing two DCC SEND offers for the same file confuses clients and
                        // can result in duplicate transfers. The user can retry manually.
                        try {
                            doPassiveSend()
                        } catch (t: TimeoutCancellationException) {
                            // Re-throw as a plain IOException so the outer catch marks the
                            // transfer as an error rather than falling through to the success path.
                            throw IOException("No response from $target - DCC timed out")
                        }
                    }
                }

                val st3 = _state.value
                _state.value = st3.copy(dccTransfers = st3.dccTransfers.map {
                    if (it is DccTransferState.Outgoing && it.target == target && it.filename == offerName) it.copy(done = true, endTimeMs = System.currentTimeMillis()) else it
                })
                outgoingSendJobs.remove(jobKey)
                append(statusKey, from = null, text = "*** DCC send complete: $offerName → $target", doNotify = false)

            } catch (t: Throwable) {
                // See incoming catch above for why !isActive is the reliable cancel signal:
                // a user cancel can manifest either as CancellationException at a suspension
                // point or as an IOException bubbling up from the closed socket. Surface
                // "Cancelled" as the error string (not null) so the Transfers screen renders
                // it as a stopped transfer rather than a successful one.
                val cancelled = !isActive || t is kotlinx.coroutines.CancellationException
                val msg = if (cancelled) "Cancelled" else (t.message ?: t::class.java.simpleName).trim()
                val stErr = _state.value
                offerNameForState?.let { fn ->
                    outgoingSendJobs.remove("$target/$fn")
                    _state.value = stErr.copy(dccTransfers = stErr.dccTransfers.map {
                        if (it is DccTransferState.Outgoing && it.target == target && it.filename == fn)
                            it.copy(done = true, error = msg, endTimeMs = System.currentTimeMillis())
                        else it
                    })
                    if (!cancelled) append(statusKey, from = "DCC", text = "*** DCC send failed: $msg", isHighlight = true)
                    else append(statusKey, from = null, text = "*** DCC send cancelled: $fn", doNotify = false)
                } ?: run {
                    _state.value = stErr.copy(dccTransfers = stErr.dccTransfers + DccTransferState.Outgoing(target = target, filename = "(unknown)", done = true, error = msg, endTimeMs = System.currentTimeMillis()))
                    if (!cancelled) append(statusKey, from = "DCC", text = "*** DCC send failed: $msg", isHighlight = true)
                }
                if (t is kotlinx.coroutines.CancellationException) throw t   // re-throw so coroutine completes correctly
            }
        }

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
     * Cancel an in-progress incoming DCC receive.
     *
     * Cancelling the Job triggers [DccManager]'s `invokeOnCompletion(onCancelling = true)`
     * socket-close, which unblocks the receive loop synchronously. The launched coroutine's
     * catch block transitions the transfer to `error = "Cancelled"`; the user then taps the
     * separate X (clearDccTransfer) to dismiss the cancelled entry.
     */
    fun cancelIncomingDcc(offer: DccOffer) {
        incomingReceiveJobs[offer]?.cancel()
    }
    private fun queryDisplayName(uri: android.net.Uri): String? {
        return try {
            val proj = arrayOf(
                OpenableColumns.DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                "_display_name",
                "display_name"
            )
            appContext.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                for (col in proj) {
                    val idx = c.getColumnIndex(col)
                    if (idx >= 0) {
                        val v = runCatching { c.getString(idx) }.getOrNull()
                        if (!v.isNullOrBlank()) return@use v
                    }
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private data class PreparedDccSend(val file: File, val offerName: String)

    private suspend fun prepareDccSendFile(uri: android.net.Uri): PreparedDccSend = withContext(Dispatchers.IO) {
        // Try hard to preserve a meaningful filename for the DCC offer.
        val raw = queryDisplayName(uri)
            ?: runCatching { java.net.URLDecoder.decode(uri.lastPathSegment ?: "", "UTF-8") }.getOrNull()
            ?: ("dcc_send_" + System.currentTimeMillis())

        // Document IDs often look like "primary:Download/foo.txt".
        val cleaned = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringAfterLast(':')

        val offerName = cleaned
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .ifBlank { "dcc_send_" + System.currentTimeMillis() }
            .replace(' ', '_') // avoid spaces in CTCP DCC payload

        val out = run {
            val candidate = File(appContext.cacheDir, offerName)
            if (!candidate.exists()) candidate else {
                val dot = offerName.lastIndexOf('.')
                val stem = if (dot > 0) offerName.take(dot) else offerName
                val ext = if (dot > 0) offerName.drop(dot) else ""
                File(appContext.cacheDir, "${stem}_${System.currentTimeMillis()}$ext")
            }
        }

        val inp = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Unable to open selected file")
        inp.use { input ->
            out.outputStream().use { fos -> input.copyTo(fos) }
        }

        PreparedDccSend(file = out, offerName = out.name)
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
            val toLaunch = (if (asChooser) Intent.createChooser(base, "Open with") else base)
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

    private fun toastShareError() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                android.widget.Toast.makeText(
                    appContext, "Couldn't open this file", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // /SYSINFO

	private var cachedGpu: String? = null

	private fun readGpuRendererBestEffort(): String {
		return try {
			val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
			if (display == EGL14.EGL_NO_DISPLAY) return "Unknown"

			val vers = IntArray(2)
			if (!EGL14.eglInitialize(display, vers, 0, vers, 1)) return "Unknown"

			// From here on, eglTerminate must be called in the finally block.
			var ctx: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
			var surf: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE
			try {
				val configAttribs = intArrayOf(
					EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
					EGL14.EGL_RED_SIZE, 8,
					EGL14.EGL_GREEN_SIZE, 8,
					EGL14.EGL_BLUE_SIZE, 8,
					EGL14.EGL_ALPHA_SIZE, 8,
					EGL14.EGL_NONE
				)
				val configs = arrayOfNulls<EGLConfig>(1)
				val num = IntArray(1)
				if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, num, 0)) {
					return "Unknown"
				}
				val config = configs[0] ?: return "Unknown"

				val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
				ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
				if (ctx == EGL14.EGL_NO_CONTEXT) return "Unknown"

				val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
				surf = EGL14.eglCreatePbufferSurface(display, config, surfAttribs, 0)
				if (surf == EGL14.EGL_NO_SURFACE) return "Unknown"

				EGL14.eglMakeCurrent(display, surf, surf, ctx)

				val vendor = GLES20.glGetString(GLES20.GL_VENDOR)?.trim().orEmpty()
				val renderer = GLES20.glGetString(GLES20.GL_RENDERER)?.trim().orEmpty()

				val joined = listOf(vendor, renderer).filter { it.isNotBlank() }.joinToString(" ")
				if (joined.isBlank()) "Unknown" else joined
			} finally {
				// Always detach context and release resources even on early returns above.
				EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
				if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surf)
				if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx)
				EGL14.eglTerminate(display)
			}
		} catch (_: Throwable) {
			"Unknown"
		}
	}

    private fun buildSysInfoLine(): String {
        val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        val api = android.os.Build.VERSION.SDK_INT
        val release = android.os.Build.VERSION.RELEASE ?: "?"
        val codename = android.os.Build.VERSION.CODENAME ?: "?"
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val cpuModel = readCpuModel().ifBlank { "Unknown" }

        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalMem = mi.totalMem
        val availMem = mi.availMem
        val usedMem = (totalMem - availMem).coerceAtLeast(0L)

        val stat = StatFs(android.os.Environment.getDataDirectory().absolutePath)
        val totalStorage = stat.blockCountLong * stat.blockSizeLong
        val freeStorage = stat.availableBlocksLong * stat.blockSizeLong
        val usedStorage = (totalStorage - freeStorage).coerceAtLeast(0L)

        val usedMemPct = if (totalMem > 0) usedMem.toDouble() / totalMem.toDouble() else 0.0
        val usedStoPct = if (totalStorage > 0) usedStorage.toDouble() / totalStorage.toDouble() else 0.0

        val uptimeMs = SystemClock.elapsedRealtime()
        val uptime = fmtUptime(uptimeMs)

        val gpu = cachedGpu ?: readGpuRendererBestEffort().also { cachedGpu = it }

        return "HexDroid v${BuildConfig.VERSION_NAME} | " +
            "Device: $device running Android $release $codename (API $api), CPU: ${cpuCores}-core $cpuModel, " +
            "Memory: ${fmtBytes(totalMem)} total, ${fmtBytes(usedMem)} (${fmtPct(usedMemPct)}) used, ${fmtBytes(availMem)} (${fmtPct(1.0 - usedMemPct)}) free, " +
            "Storage: ${fmtBytes(totalStorage)} total, ${fmtBytes(usedStorage)} (${fmtPct(usedStoPct)}) used, ${fmtBytes(freeStorage)} (${fmtPct(1.0 - usedStoPct)}) free, " +
            "Graphics: $gpu, Uptime: $uptime"
    }

    private fun readCpuModel(): String {
        return runCatching {
            val txt = File("/proc/cpuinfo").readText()
            // Try common keys
            val keys = listOf("Hardware", "Model", "model name", "Processor", "CPU implementer")
            for (k in keys) {
                val m = Regex("^\\s*${Regex.escape(k)}\\s*:\\s*(.+)$", RegexOption.MULTILINE).find(txt)
                if (m != null) return m.groupValues[1].trim()
            }
            ""
        }.getOrDefault("")
    }

    private fun fmtBytes(b: Long): String {
        val gb = 1024.0 * 1024.0 * 1024.0
        val mb = 1024.0 * 1024.0
        return when {
            b >= gb -> String.format(Locale.US, "%.1fGB", b / gb)
            b >= mb -> String.format(Locale.US, "%.0fMB", b / mb)
            else -> "${b}B"
        }
    }

    private fun fmtPct(v: Double): String =
        String.format(Locale.US, "%.1f%%", (v * 100.0).coerceIn(0.0, 100.0))

    private fun fmtUptime(ms: Long): String {
        val s = ms / 1000
        val days = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (days > 0) "${days}d ${h}h ${m}m ${sec}s" else "${h}h ${m}m ${sec}s"
    }

    override fun onCleared() {
        super.onCleared()
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        // Flush and close all open log file handles so the last few lines written via the
        // BufferedWriter cache are not lost when the ViewModel is destroyed.
        logs.closeAll()
    }
}
