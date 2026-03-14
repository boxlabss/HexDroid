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
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    val msgId: String? = null
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
    val typingNicks: Set<String> = emptySet()
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
    val dccIncomingPortMin: Int = 5000,
    val dccIncomingPortMax: Int = 5010,
    val dccDownloadFolderUri: String? = null,

    val quitMessage: String = "HexDroid IRC - https://hexdroid.boxlabs.uk/",
    val partMessage: String = "Leaving",

    val colorizeNicks: Boolean = true,

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
    val isIrcOper: Boolean = false
)

data class BanEntry(
    val mask: String,
    val setBy: String? = null,
    val setAtMs: Long? = null
)

data class UiState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val status: String = "Disconnected",
    val myNick: String = "me",

    val screen: AppScreen = AppScreen.NETWORKS,

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

    val collapsedNetworkIds: Set<String> = emptySet(),
    val settings: UiSettings = UiSettings(),
    // Prevents a one-frame default-value flicker before DataStore loads.
    val settingsLoaded: Boolean = false,
    val networks: List<NetworkProfile> = emptyList(),
    val activeNetworkId: String? = null,
    val editingNetwork: NetworkProfile? = null,

    val networkEditError: String? = null,

    val plaintextWarningNetworkId: String? = null,

    val dccOffers: List<DccOffer> = emptyList(),
    val dccChatOffers: List<DccChatOffer> = emptyList(),
    val dccTransfers: List<DccTransferState> = emptyList(),

    val backupMessage: String? = null,
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

    @SuppressLint("StaticFieldLeak")
    private val appContext: Context = context.applicationContext

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

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
        val chanModes: String? = null
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
        val manuallyJoinedChannels: MutableMap<String, String?> = mutableMapOf()
    )

    private val runtimes = mutableMapOf<String, NetRuntime>()

    private val desiredConnected = mutableSetOf<String>()
    private var desiredNetworkIdsLoaded = false
    private var desiredNetworkIdsApplied = false
    private val autoReconnectJobs = mutableMapOf<String, Job>()
    private val reconnectAttempts = mutableMapOf<String, Int>()
    private val manualDisconnecting = mutableSetOf<String>()
    private val noNetworkNotice = mutableSetOf<String>()

    // Not persisted; resets to all-expanded on process restart.
    private val _collapsedNetworkIds = MutableStateFlow<Set<String>>(emptySet())
    fun toggleNetworkExpanded(netId: String) {
        _collapsedNetworkIds.update { current ->
            if (current.contains(netId)) current - netId else current + netId
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
        val targets = desiredConnected.filter { existing.contains(it) }.toList()
        if (targets.isEmpty()) return
        targets.forEach { id -> connectNetwork(id) }
        val before = desiredConnected.size
        desiredConnected.retainAll(existing)
        if (desiredConnected.size != before) persistDesiredNetworkIds()
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
    private val pendingCloseAfterPart = mutableSetOf<String>()

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
    private val chanNickCase: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
    private val chanNickStatus: MutableMap<String, MutableMap<String, MutableSet<Char>>> = mutableMapOf()

    // away-notify state. Outer key = netId, inner key = case-folded nick, value = away message ("" if away with no message). Absent = present.
    private val nickAwayState: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    private var autoConnectAttempted = false

    private val notifier = NotificationHelper(appContext)
    private val logs = LogWriter(appContext)
    private val dcc = DccManager(appContext)

    private data class DccChatSession(
        val netId: String,
        val peer: String,
        val bufferKey: String,
        val socket: Socket,
        val writer: BufferedWriter,
        val readJob: Job
    )

    private val dccChatSessions: MutableMap<String, DccChatSession> = mutableMapOf()

    private data class PendingPassiveDccSend(
        val target: String,
        val filename: String,
        val size: Long,
        val reply: CompletableDeferred<DccOffer>
    )

    private val pendingPassiveDccSends = mutableMapOf<Long, PendingPassiveDccSend>()

    /**
     * Jobs for in-progress outgoing DCC sends, keyed by "$target/$filename".
     * Stored so the user can cancel a send from the Transfers screen.
     */
    private val outgoingSendJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    private val nextUiMsgId = AtomicLong(1L)

    private val logTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun formatLogLine(timeMs: Long, from: String?, text: String, isAction: Boolean): String {
        val ts = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).format(logTimeFormatter)
        val t = stripIrcFormatting(text)
        val body = when {
            from == null -> t
            isAction -> "* $from $t"
            else -> "<$from> $t"
        }
        return "$ts\t$body"
    }

    private data class SentSig(val bufferKey: String, val text: String, val isAction: Boolean, val ts: Long)
    private val pendingSendsByNet = mutableMapOf<String, ArrayDeque<SentSig>>()

    private fun bufKey(netId: String, bufferName: String): String = "$netId::$bufferName"

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

        val keepBuf = keepBuf0.copy(messages = merged, unread = unread, highlights = highlights, topic = topic)

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
        viewModelScope.launch {
            repo.migrateLegacySecretsIfNeeded()
            repo.settingsFlow.collect { s ->
                val st = _state.value
                val applyDefaults = st.settings == UiSettings()
                _state.value = st.copy(
                    settings = s,
                    settingsLoaded = true,
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
                    for (netId in desiredConnected) {
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
                // Network lost - check if we still have connectivity via another network
                viewModelScope.launch {
                    delay(500) // Brief delay to see if another network takes over
                    if (!hasInternetConnection()) {
                        // No connectivity at all - mark connections as waiting
                        val st = _state.value
                        for (netId in desiredConnected) {
                            val conn = st.connections[netId]
                            if (conn?.connected == true || conn?.connecting == true) {
                                val serverKey = bufKey(netId, "*server*")
                                append(serverKey, from = null, text = "*** Network lost. Waiting for connectivity…", doNotify = false)
                                setNetConn(netId) { it.copy(status = "Waiting for network…") }
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
            // Ignore - some devices may not support this
        }
    }

    private fun maybeAutoConnect() {
        val st = _state.value
        if (autoConnectAttempted) return
        if (st.networks.isEmpty()) return
        autoConnectAttempted = true
        val targets = st.networks.filter { it.autoConnect }
        if (targets.isEmpty()) return
        targets.forEach { connectNetwork(it.id) }
    }

    fun setNetworkAutoConnect(netId: String, enabled: Boolean) {
        val n = _state.value.networks.firstOrNull { it.id == netId } ?: return
        viewModelScope.launch { repo.upsertNetwork(n.copy(autoConnect = enabled)) }
    }

    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val netId = intent.getStringExtra(NotificationHelper.EXTRA_NETWORK_ID)
        val buf = intent.getStringExtra(NotificationHelper.EXTRA_BUFFER)
        val action = intent.getStringExtra(NotificationHelper.EXTRA_ACTION)

        if (action == NotificationHelper.ACTION_OPEN_TRANSFERS) {
            if (!netId.isNullOrBlank()) setActiveNetwork(netId)
            _state.value = _state.value.copy(screen = AppScreen.TRANSFERS)
            return
        }

        if (netId.isNullOrBlank() && buf.isNullOrBlank()) return

        if (!netId.isNullOrBlank()) setActiveNetwork(netId)

        val key = if (!netId.isNullOrBlank() && !buf.isNullOrBlank()) resolveBufferKey(netId, buf) else null
        if (key != null) openBuffer(key) else _state.value = _state.value.copy(screen = AppScreen.CHAT)
    }

    fun goTo(screen: AppScreen) {
        _state.value = _state.value.copy(screen = screen)
        if (screen == AppScreen.LIST) requestList()
    }
    fun backToChat() { _state.value = _state.value.copy(screen = AppScreen.CHAT) }

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
                saslMechanism = SaslMechanism.SCRAM_SHA_256,
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
		id = "net_" + System.currentTimeMillis(),
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
		saslMechanism = SaslMechanism.SCRAM_SHA_256,
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

            val withSecrets = n.copy(
                serverPassword = serverPass,
                saslPassword = saslPass
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
            connectNetwork(netId, force = true)
        }
    }

    fun saveEditingNetwork(profile: NetworkProfile, clientCertDraft: ClientCertDraft?, removeClientCert: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(networkEditError = null)
            if (profile.saslEnabled) {
                val p = profile.saslPassword?.trim()
                if (!p.isNullOrBlank()) {
                    repo.secretStore.setSaslPassword(profile.id, p)
                }
            } else {
                repo.secretStore.clearSaslPassword(profile.id)
            }

            val sp = profile.serverPassword?.trim()
            if (!sp.isNullOrBlank()) repo.secretStore.setServerPassword(profile.id, sp)

            var updated = profile.copy(
                saslPassword = null,
                serverPassword = null,
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

            _state.value = _state.value.copy(
                screen = AppScreen.NETWORKS,
                editingNetwork = null,
                activeNetworkId = updated.id,
                networkEditError = null
            )
        }
    }

    fun deleteNetwork(id: String) {
        viewModelScope.launch {
            repo.deleteNetwork(id)
            repo.secretStore.clearSaslPassword(id)
            repo.secretStore.clearServerPassword(id)
        }
        disconnectNetwork(id)
        val st = _state.value
        if (st.activeNetworkId == id) _state.value = syncActiveNetworkSummary(st.copy(activeNetworkId = st.networks.firstOrNull { it.id != id }?.id))
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
                repo.importBackup(json)
                "Backup restored successfully.\nPasswords were not restored — please re-enter them in each network's settings."
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

    fun connectNetwork(netId: String, force: Boolean = false) {
        viewModelScope.launch {
            withNetLock(netId) {
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

        val addedDesired = desiredConnected.add(netId)
        if (addedDesired) persistDesiredNetworkIds()
        manualDisconnecting.remove(netId)
        autoReconnectJobs.remove(netId)?.cancel()

        val existing = runtimes.remove(netId)
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
        val saslPassword = repo.secretStore.getSaslPassword(profile.id)
        val serverPassword = repo.secretStore.getServerPassword(profile.id)
        val tlsCert = repo.secretStore.loadTlsClientCert(profile.id, profile.tlsClientCertId)
        val cfg = profile.toIrcConfig(
                        saslPasswordOverride = saslPassword,
                        serverPasswordOverride = serverPassword,
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
        _state.value = syncActiveNetworkSummary(
            st.copy(
                connections = newConns,
                screen = AppScreen.CHAT,
                selectedBuffer = if (st.activeNetworkId == netId) serverKey else st.selectedBuffer
            )
        )

        val client = IrcClient(cfg)
        val thisClient = client
        val rt = NetRuntime(netId = netId, client = client, myNick = cfg.nick, suppressMotd = _state.value.settings.hideMotdOnConnect)
        runtimes[netId] = rt

        if (st.activeNetworkId == netId) updateConnectionNotification("Connecting…")

        rt.job?.cancel()
        rt.job = viewModelScope.launch(Dispatchers.IO) {
            // Hold a scoped WakeLock for the connect/TLS handshake burst, then release it.
            // The foreground service keeps the process alive; the lock just covers the CPU-
            // intensive initial handshake so Android can't suspend us mid-handshake.
            KeepAliveService.acquireScopedWakeLock(appContext)
            try {
                client.events().collect { ev ->
                    runCatching { handleEvent(netId, ev) }
                        .onFailure { t ->
                            val msg = (t.message ?: t::class.java.simpleName)
                            append(bufKey(netId, "*server*"), from = "CLIENT", text = "Event handler error: $msg", isHighlight = true)
                        }
                }
            } finally {
                KeepAliveService.releaseScopedWakeLock()
            }
        }
		
        // If the collector exits without emitting Disconnected, clean up and maybe reconnect.
        // Guard: if the job was *cancelled* (intentional teardown — force-close, manual
        // disconnect, reconnect replacing this runtime) we must not treat it as an unexpected
        // drop. CancellationException means someone called job.cancel() on purpose.
        rt.job?.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) return@invokeOnCompletion
            viewModelScope.launch {
                if (runtimes[netId]?.client !== thisClient) return@launch

                val cur = _state.value.connections[netId]
                val wasConnectedOrConnecting = (cur?.connected == true || cur?.connecting == true)
                if (wasConnectedOrConnecting) {
                    append(bufKey(netId, "*server*"), from = null, text = "*** Disconnected", doNotify = false)
                    setNetConn(netId) { it.copy(connected = false, connecting = false, status = "Disconnected") }
                    if (_state.value.activeNetworkId == netId) clearConnectionNotification()
                }

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
            // We'll intentionally drop the socket before reconnecting; suppress the auto-reconnect loop.
            manualDisconnecting.add(netId)
            autoReconnectJobs.remove(netId)?.cancel()
            // Bug fix: reset backoff counter so the next auto-reconnect after this manual reconnect
            // starts from attempt 0 rather than inheriting the old stale exponential delay.
            reconnectAttempts.remove(netId)

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

    fun disconnectNetwork(netId: String) {
        val quitMsg = _state.value.settings.quitMessage.ifBlank { "Client disconnect" }
        viewModelScope.launch {
            withNetLock(netId) {
            val removedDesired = desiredConnected.remove(netId)
            if (removedDesired) persistDesiredNetworkIds()
            manualDisconnecting.add(netId)
            reconnectAttempts.remove(netId)  // Clear reconnect backoff
            autoReconnectJobs.remove(netId)?.cancel()

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

    // Auto-reconnect

    private fun scheduleAutoReconnect(netId: String) {
        val st0 = _state.value
        if (!st0.settings.autoReconnectEnabled) return
        // Per-network override.
        if (st0.networks.firstOrNull { it.id == netId }?.autoReconnect == false) return
        // One job per network.
        autoReconnectJobs.remove(netId)?.cancel()
        val serverKey = bufKey(netId, "*server*")
        autoReconnectJobs[netId] = viewModelScope.launch {
            while (isActive) {
                val attempt = reconnectAttempts[netId] ?: 0
                val baseDelaySec = _state.value.settings.autoReconnectDelaySec.coerceIn(
                    ConnectionConstants.RECONNECT_BASE_DELAY_MIN_SEC,
                    ConnectionConstants.RECONNECT_BASE_DELAY_MAX_SEC
                )
                if (attempt > 0) {
                    // Exponential backoff with small jitter to avoid herd effects after outages.
                    val exp = attempt.coerceAtMost(ConnectionConstants.RECONNECT_MAX_EXPONENT)
                    val planned = (baseDelaySec.toLong() * (1L shl exp)).coerceAtMost(ConnectionConstants.RECONNECT_MAX_DELAY_SEC)
                    val jitter = (planned * ConnectionConstants.RECONNECT_JITTER_FACTOR).toLong()
                    val actual = if (jitter > 0) planned - jitter + Random.nextLong(jitter * 2 + 1) else planned
                    delay(actual * 1000L)
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

                append(serverKey, from = null, text = "*** Retrying to connect (attempt ${attempt + 1})…", doNotify = false)
                setNetConn(netId) { it.copy(status = "Retrying to connect…") }
                if (st.activeNetworkId == netId) updateConnectionNotification("Retrying to connect…")

                // Force a clean reconnect (drops stale runtimes if present).
                withNetLock(netId) { KeepAliveService.withWakeLock(appContext) { connectNetworkInternal(netId, force = true) } }
                reconnectAttempts[netId] = (attempt + 1).coerceAtMost(ConnectionConstants.RECONNECT_MAX_ATTEMPTS)
            }
            autoReconnectJobs.remove(netId)
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
     * queued — isConnectedNow() can transiently return false during the handshake window, and
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

            // Don't touch anything that is already mid-connect or has a reconnect scheduled —
            // isConnectedNow() is unreliable during the handshake and we'd create a double-reconnect.
            if (cur.connecting) continue
            if (autoReconnectJobs.containsKey(net.id)) continue

            val actual = rt?.client?.isConnectedNow() == true

            // Socket is alive but UI thinks we're disconnected — correct the UI.
            if (actual && !cur.connected) {
                newMap[net.id] = cur.copy(connected = true, connecting = false, status = "Connected")
                changed = true
            }

            // Socket is gone but UI thinks we're connected — correct the UI and maybe reconnect.
            if (!actual && cur.connected) {
                newMap[net.id] = cur.copy(connected = false, connecting = false, status = "Disconnected")
                changed = true
                if (desiredConnected.contains(net.id)) networksToReconnect.add(net.id)
            }

            // Not connected, not connecting, but should be — reconnect.
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

    // Auto-expiry jobs for *received* typing indicators.
    // Key: "$bufferKey/$nick". IRCv3 spec recommends expiring after 30 s with no update.
    private val receivedTypingExpiryJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * Called by the UI whenever the input text changes.  Sends "active" typing status immediately,
     * then schedules a "paused" → "done" timeout if the user stops typing.
     * Sending an empty string immediately sends "done".
     *
     * No-op if the user has disabled [UiSettings.sendTypingIndicator] in Settings (privacy).
     */
    fun notifyTypingChanged(text: String) {
        val st = _state.value

        // Privacy gate: user must explicitly opt in to broadcasting typing status.
        if (!st.settings.sendTypingIndicator) return

        val currentKey = st.selectedBuffer
        if (currentKey.isBlank()) return
        val (netId, bufferName) = splitKey(currentKey)
        val rt = runtimes[netId] ?: return
        if (!rt.client.hasCap("draft/typing") && !rt.client.hasCap("typing")) return
        if (bufferName == "*server*") return

        typingDoneJob?.cancel()

        if (text.isEmpty()) {
            // User cleared input - send "done" immediately to the correct network.
            typingLastKey?.let { prevKey ->
                val (prevNet, prevBuf) = splitKey(prevKey)
                viewModelScope.launch { runtimes[prevNet]?.client?.sendTypingStatus(prevBuf, "done") }
            }
            typingLastKey = null
            return
        }

        // If buffer changed, send "done" to the OLD buffer on whichever network it belonged to.
        val prevKey = typingLastKey
        if (prevKey != null && prevKey != currentKey) {
            val (prevNet, prevBuf) = splitKey(prevKey)
            val prevRt = runtimes[prevNet]
            viewModelScope.launch { prevRt?.client?.sendTypingStatus(prevBuf, "done") }
        }

        typingLastKey = currentKey
        viewModelScope.launch { rt.client.sendTypingStatus(bufferName, "active") }

        // After 6 s of inactivity -> "paused"; after another 24 s -> "done"
        typingDoneJob = viewModelScope.launch {
            delay(6_000L)
            rt.client.sendTypingStatus(bufferName, "paused")
            delay(24_000L)
            rt.client.sendTypingStatus(bufferName, "done")
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

            // Check if this is a command (starts with /)
            if (trimmed.startsWith("/")) {
                // Commands are processed as-is (first line only if multiline)
                val cmdLine = trimmed.drop(1).substringBefore('\n').trim()
                val cmd = cmdLine.substringBefore(' ').lowercase()

                when (cmd) {
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
                            append(currentKey, from = fromNick, text = line, isLocal = true)
                            recordLocalSend(netId, currentKey, line, isAction = false)
                        } else {
                            append(currentKey, from = fromNick, text = line, isLocal = true)
                        }
                        return@launch
                    }

                    "find", "grep", "search" -> {
                        val rest = cmdLine.substringAfter(' ', "").trim()
                        if (rest.isBlank()) {
                            append(currentKey, from = null, text = "*** Usage: /find <text> [limit]", isLocal = true, doNotify = false)
                            return@launch
                        }

                        val toks = rest.split(Regex("\\s+")).filter { it.isNotBlank() }
                        var limit = 20
                        var query = rest
                        toks.lastOrNull()?.toIntOrNull()?.let { n ->
                            if (toks.size >= 2) {
                                limit = n.coerceIn(1, 50)
                                query = toks.dropLast(1).joinToString(" ")
                            }
                        }

                        val stNow = _state.value
                        val msgs = stNow.buffers[currentKey]?.messages.orEmpty()
                        val matches = msgs.filter {
                            it.text.contains(query, ignoreCase = true) || (it.from?.contains(query, ignoreCase = true) == true)
                        }
                        val shown = matches.takeLast(limit)

                        val timeFmt = try { SimpleDateFormat(stNow.settings.timestampFormat, Locale.getDefault()) }
                        catch (_: Throwable) { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

                        append(currentKey, from = null, text = "*** Found ${matches.size} matches for \"$query\" (showing ${shown.size})", isLocal = true, doNotify = false)
                        for (m in shown) {
                            val ts = timeFmt.format(Date(m.timeMs))
                            val who = m.from?.let { "<$it> " } ?: ""
                            append(currentKey, from = null, text = "[$ts] $who${m.text}", isLocal = true, doNotify = false)
                        }
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
                        c.sendRaw("PRIVMSG $target :\u0001ACTION $msg\u0001")
                        append(currentKey, from = st.connections[netId]?.myNick ?: st.myNick, text = msg, isAction = true, isLocal = true)
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

            // Regular text message - join any newlines into a single message.
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
            if (c == null) {
                append(currentKey, from = null, text = "*** Not connected.", doNotify = false)
                return@launch
            }
            if (bufferName == "*server*") {
                c.sendRaw(fullMessage)
                return@launch
            }
            
            // Calculate max message length for PRIVMSG
            // Format: ":nick!user@host PRIVMSG <target> :<message>\r\n"
            // IRC RFC 1459 limit is 512 bytes total. We use 400 as a safe message length
            // to account for: sender prefix (~60), "PRIVMSG " (8), target, " :" (2), CRLF (2)
            // This is conservative but avoids truncation on any server.
            val myNick = st.connections[netId]?.myNick ?: st.myNick
            val maxMsgLen = 400
            
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
                append(currentKey, from = myNick, text = chunk, isLocal = true)
                recordLocalSend(netId, currentKey, chunk, isAction = false)
            }
        }
    }
    
    /**
     * Split a message into chunks that don't exceed maxLen bytes (UTF-8).
     * Tries to split on word boundaries when possible.
     */
    private fun splitMessageByLength(text: String, maxLen: Int): List<String> {
        if (text.toByteArray(Charsets.UTF_8).size <= maxLen) {
            return listOf(text)
        }
        
        val chunks = mutableListOf<String>()
        var remaining = text
        
        while (remaining.isNotEmpty()) {
            if (remaining.toByteArray(Charsets.UTF_8).size <= maxLen) {
                chunks.add(remaining)
                break
            }
            
            // Find a good split point
            var splitAt = maxLen
            // Start from maxLen and work backwards to find a character boundary
            while (splitAt > 0 && remaining.substring(0, minOf(splitAt, remaining.length))
                    .toByteArray(Charsets.UTF_8).size > maxLen) {
                splitAt--
            }
            
            if (splitAt == 0) splitAt = 1  // Ensure we make progress
            splitAt = minOf(splitAt, remaining.length)
            
            // Try to split on a word boundary (space)
            val chunk = remaining.substring(0, splitAt)
            val lastSpace = chunk.lastIndexOf(' ')
            val actualSplit = if (lastSpace > splitAt / 2) lastSpace else splitAt
            
            chunks.add(remaining.substring(0, actualSplit).trim())
            remaining = remaining.substring(actualSplit).trim()
        }
        
        return chunks.filter { it.isNotEmpty() }
    }

    fun joinChannel(channel: String) {
        val netId = _state.value.activeNetworkId ?: return
        val rt = runtimes[netId] ?: return
        viewModelScope.launch { rt.client.sendRaw("JOIN $channel") }
        openBuffer(resolveBufferKey(netId, channel))
    }

    fun requestList() {
        val netId = _state.value.activeNetworkId ?: return
        val rt = runtimes[netId] ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(listInProgress = true, channelDirectory = emptyList())
            rt.client.sendRaw("LIST")
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
        // Only take the first token (allowing the user to paste "nick: ..." etc.)
        val token = t.split(Regex("\\s+"), limit = 2).firstOrNull()?.trim() ?: return null
        val base = token.trimEnd(':', ',').trimStart('~','&','@','%','+')
        if (base.isBlank() || base == "." || base == "..") return null
        // Disallow spaces and control chars; keep it simple.
        val cleaned = base.replace("", "").trim()
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun isNickIgnored(netId: String, nick: String?): Boolean {
        val n = nick?.trim().takeIf { !it.isNullOrBlank() } ?: return false
        val base = n.trimStart('~','&','@','%','+')
        val list = _state.value.networks.firstOrNull { it.id == netId }?.ignoredNicks.orEmpty()
        return list.any { it.equals(base, ignoreCase = true) }
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

    fun openIgnoreList() { goTo(AppScreen.IGNORE) }
    // IRC event handling

    private fun handleEvent(netId: String, ev: IrcEvent) {
        when (ev) {
            is IrcEvent.Status -> {
                setNetConn(netId) { it.copy(status = ev.text) }
                append(bufKey(netId, "*server*"), from = null, text = "*** ${ev.text}", doNotify = false)
            }
            is IrcEvent.Connected -> {
                manualDisconnecting.remove(netId)
                reconnectAttempts.remove(netId)  // Reset reconnect backoff on successful connection
                runtimes[netId]?.apply { suppressMotd = _state.value.settings.hideMotdOnConnect; manualMotdAtMs = 0L }
                autoReconnectJobs.remove(netId)?.cancel()
                setNetConn(netId) {
                    it.copy(connecting = false, connected = true, status = "Connected to ${ev.server}", lagMs = null)
                }
                if (_state.value.activeNetworkId == netId) updateConnectionNotification("Connected")
            }
            is IrcEvent.LagUpdated -> {
                // Skip the startService() IPC call when backgrounded - the notification
                // text never shows lag values so there's nothing to update.
                // Still update state so the lag bar is fresh when the user returns.
                if (!AppVisibility.isForeground) {
                    _state.update { st ->
                        val old = st.connections[netId] ?: NetConnState()
                        val newConns = st.connections + (netId to old.copy(lagMs = ev.lagMs))
                        syncActiveNetworkSummary(st.copy(connections = newConns))
                    }
                } else {
                    setNetConn(netId) { it.copy(lagMs = ev.lagMs) }
                }
            }
            is IrcEvent.Disconnected -> {
                val r = ev.reason?.trim()
                val pretty = when {
                    r.isNullOrBlank() -> "Disconnected"
                    r.equals("Client disconnect", ignoreCase = true) -> "Disconnected"
                    r.equals("EOF", ignoreCase = true) -> "Disconnected"
                    r.equals("socket closed", ignoreCase = true) -> "Disconnected"
                    else -> "Disconnected: $r"
                }
                append(bufKey(netId, "*server*"), from = null, text = "*** $pretty", doNotify = false)
                setNetConn(netId) { it.copy(connecting = false, connected = false, status = pretty, lagMs = null) }
                if (_state.value.activeNetworkId == netId) clearConnectionNotification()
                // Clear away state for this network on disconnect (stale after reconnect).
                nickAwayState.remove(netId)

                // If this disconnect was manual (Disconnect button), don't auto-reconnect.
                // If it was a manual *reconnect* attempt that failed, we still want to retry.
                val wasManual = manualDisconnecting.remove(netId)
                if (wasManual && !desiredConnected.contains(netId)) return

                if (desiredConnected.contains(netId)) scheduleAutoReconnect(netId)
            }
            is IrcEvent.Error -> {
                val msg = ev.message
                val isConnectFail = msg.startsWith("Connect failed", ignoreCase = true) || msg.startsWith("Connection failed", ignoreCase = true)
                append(bufKey(netId, "*server*"), from = "ERROR", text = msg, isHighlight = !isConnectFail, doNotify = !isConnectFail)
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

            is IrcEvent.TlsFingerprintChanged -> {
                // The server is presenting a DIFFERENT certificate than the one we pinned.
                // This is a serious warning - could be a certificate rotation or a MITM attack.
                append(
                    bufKey(netId, "*server*"), from = "TLS WARNING", isHighlight = true,
                    text = "⚠️  Server certificate fingerprint has CHANGED! " +
                           "Expected: ${ev.stored}  •  Got: ${ev.actual}  — " +
                           "Connection refused. If this is a legitimate cert renewal, go to " +
                           "Network Settings → Allow invalid certificates and reconnect once to re-pin."
                )
            }
            is IrcEvent.ServerLine -> {
                val stNow = _state.value
                if (stNow.settings.loggingEnabled && stNow.settings.logServerBuffer) {
                    val netName = stNow.networks.firstOrNull { it.id == netId }?.name ?: netId
                    val ts = System.currentTimeMillis()
                    val line = ev.line
                    val logLine = formatLogLine(ts, from = null, text = line, isAction = false)
                    logs.append(netName, "*server*", logLine, stNow.settings.logFolderUri)
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
                        chanModes = ev.chanModes
                    )
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
                setNetConn(netId) { it.copy(myNick = ev.nick) }
                append(bufKey(netId, "*server*"), from = null, text = "*** Registered as ${ev.nick}", doNotify = false)

                val rt = runtimes[netId] ?: return
                val profile = _state.value.networks.firstOrNull { it.id == netId }

                viewModelScope.launch {
                    // 1. soju bouncer: bind to a specific upstream network after registration.
                    //    BOUNCER BIND <networkId> tells soju to route subsequent traffic through
                    //    that upstream, enabling per-network connection from a single soju endpoint.
                    val bindId = rt.client.config.bouncerNetworkId
                    if (rt.client.config.isBouncer && !bindId.isNullOrBlank()) {
                        rt.client.sendRaw("BOUNCER BIND $bindId")
                    }

                    // 2. Service auth command (e.g. /msg NickServ IDENTIFY password)
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
                    profile?.autoCommandsText?.takeIf { it.isNotBlank() }?.let { text ->
                        // Trim each line so that accidental leading/trailing whitespace does not
                        // cause commands to be sent verbatim with a leading space (silent failure).
                        val commands = text.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        for (cmd in commands) {
                            if (cmd.startsWith("/")) {
                                // Client command - expand aliases
                                rt.client.handleSlashCommand(cmd.drop(1), "*server*")
                            } else {
                                // Raw IRC line (e.g. "MODE #chan +o nick")
                                rt.client.sendRaw(cmd)
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

                // Show nick changes in-channel (like mIRC):
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

                for (k in targets) {
                    append(
                        k,
                        from = null,
                        text = line,
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
        text = line,
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
                        text = "*** Incoming DCC CHAT from ${offer0.from} — tap 'DCCCHAT:${offer0.from}' buffer, or open Transfers to accept",
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
                val my = _state.value.connections[netId]?.myNick ?: runtimes[netId]?.myNick ?: _state.value.myNick
                val fromMe = ev.from.equals(my, ignoreCase = true)
                if (!fromMe && isNickIgnored(netId, ev.from)) return
                val st = _state.value
                val suppressUnread = ev.isHistory && !st.settings.ircHistoryCountsAsUnread
                val allowNotify = if (ev.isHistory) st.settings.ircHistoryTriggersNotifications else true
                val targetKey = resolveIncomingBufferKey(netId, ev.target)

                if (!ev.isHistory && fromMe && consumeEchoIfMatch(netId, targetKey, ev.text, ev.isAction)) return

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
                    msgId = ev.msgId
                )
            }
            is IrcEvent.Notice -> {
                val st = _state.value
                val suppressUnread = ev.isHistory && !st.settings.ircHistoryCountsAsUnread
                if (!ev.isServer && isNickIgnored(netId, ev.from)) return
                val normTarget0 = normalizeIncomingBufferName(netId, ev.target)
                val normTarget = stripStatusMsgPrefix(netId, normTarget0)
                val isChanTarget = isChannelOnNet(netId, normTarget)

                // Route notices to the current buffer on this network (or *server*)
                // instead of spawning a new buffer for services like NickServ.
                val destKey = when {
                    ev.isServer -> bufKey(netId, "*server*")
                    isChanTarget -> resolveBufferKey(netId, normTarget)
                    else -> {
                        // If the sender is in exactly one channel we're in, route there.
                        // This handles bots that send a notice to our nick on join (e.g. ChanServ
                        // greeting), which arrive before or just after the buffer is selected.
                        val senderFold = casefoldText(netId, ev.from)
                        val sharedChannels = st.nicklists.entries
                            .filter { (key, list) ->
                                key.startsWith("$netId::") &&
                                list.any { entry ->
                                    casefoldText(netId, parseNickWithPrefixes(netId, entry).first) == senderFold
                                }
                            }
                            .map { it.key }

                        when {
                            // Sender is in exactly one channel - route there unambiguously.
                            sharedChannels.size == 1 -> sharedChannels.first()
                            // Sender is in multiple shared channels - prefer the currently
                            // selected buffer if it's one of them, otherwise *server*.
                            sharedChannels.size > 1 -> {
                                val sel = st.selectedBuffer
                                if (sel in sharedChannels) sel else bufKey(netId, "*server*")
                            }
                            // Sender not in any known channel - use current selected channel
                            // buffer on this network if available, otherwise *server*.
                            else -> {
                                val sel = st.selectedBuffer
                                val (selNet, selBuf) = splitKey(sel)
                                if (sel.isNotBlank() && selNet == netId && isChannelOnNet(netId, selBuf)) sel
                                else bufKey(netId, "*server*")
                            }
                        }
                    }
                }

                ensureBuffer(destKey)
                append(
                    destKey,
                    from = null,
                    text = "* <${ev.from}> ${ev.text}",
                    isLocal = suppressUnread,
                    timeMs = ev.timeMs,
                    doNotify = false,
                    msgId = ev.msgId
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
                        text = msg,
                        isLocal = suppressUnread,
                        timeMs = ev.timeMs,
                        doNotify = false
                    )
                }

                
                val myNickNow = st0.connections[netId]?.myNick ?: st0.myNick
                val isMeNow = casefoldText(netId, ev.nick) == casefoldText(netId, myNickNow)

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
                    val shouldSwitch =
                        isMe &&
                            st1.activeNetworkId == netId &&
                            (st1.screen == AppScreen.CHAT || st1.screen == AppScreen.NETWORKS)

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
                        text = msg,
                        isLocal = suppressUnread,
                        timeMs = ev.timeMs,
                        doNotify = false
                    )
                }

                if (shouldAffectLiveState(ev.isHistory, ev.timeMs)) {
                    // Re-read state after append so we don't overwrite the message we just appended.
                    val st1 = _state.value
                    removeNickFromChannel(netId, chanKey, ev.nick)
                    val updated = rebuildNicklist(netId, chanKey)
                    // Clear any pending typing indicator for the parted nick.
                    val bufAfterPart = st1.buffers[chanKey]
                    val clearedBuf = if (bufAfterPart != null && ev.nick in bufAfterPart.typingNicks)
                        bufAfterPart.copy(typingNicks = bufAfterPart.typingNicks - ev.nick) else bufAfterPart
                    val newBufs = if (clearedBuf != null) st1.buffers + (chanKey to clearedBuf) else st1.buffers
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
                        text = msg,
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
}
            }

            is IrcEvent.Quit -> {
                val st0 = _state.value
                val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread
                val reason = ev.reason?.takeIf { it.isNotBlank() }

                val affectLive = shouldAffectLiveState(ev.isHistory, ev.timeMs)

                val affected = if (!affectLive) {
                    emptyList()
                } else {
                    st0.nicklists
                        .filterKeys { it.startsWith("$netId::") }
                        .filterValues { list -> list.any { parseNickWithPrefixes(netId, it).first.let { b -> casefoldText(netId, b) == casefoldText(netId, ev.nick) } } }
                        .keys
                        .toList()
                }

                val allChannelTargets = st0.buffers.keys
                    .filter { it.startsWith("$netId::") }
                    .filter { key ->
                        val (_, name) = splitKey(key)
                        isChannelOnNet(netId, name)
                    }

                val targets = when {
                    affected.isNotEmpty() -> affected
                    allChannelTargets.isNotEmpty() -> allChannelTargets
                    else -> listOf(bufKey(netId, "*server*"))
                }
                if (!st0.settings.hideJoinPartQuit) {
                    val host = ev.userHost ?: "*!*@*"
                    val msg = "* ${ev.nick} ($host) has quit" + (reason?.let { " [$it]" } ?: "")
                    for (k in targets) {
                        append(
                            k,
                            from = null,
                            text = msg,
                            isLocal = suppressUnread,
                            timeMs = ev.timeMs,
                            doNotify = false
                        )
                    }
                }


if (affectLive) {
    // Remove the quitter from all nicklists we have for this network.
    // Re-read state after appends so we don't overwrite message updates.
    val st1 = _state.value
    val keys = st1.nicklists.keys.filter { it.startsWith("$netId::") }
    for (k in keys) {
        removeNickFromChannel(netId, k, ev.nick)
    }
    val newNicklists = st1.nicklists.mapValues { (k, list) ->
        val (kid, _) = splitKey(k)
        if (kid != netId) list else rebuildNicklist(netId, k)
    }
    // Also remove from away state map.
    nickAwayState[netId]?.remove(casefoldText(netId, ev.nick))
    // Clear any pending typing indicator for the quitting nick across all buffers on this network.
    val newBufs = st1.buffers.mapValues { (k, buf) ->
        if (k.startsWith("$netId::") && ev.nick in buf.typingNicks)
            buf.copy(typingNicks = buf.typingNicks - ev.nick)
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
            is IrcEvent.TopicWhoTime -> {
                val st0 = _state.value
                val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread

                val chanKey = resolveBufferKey(netId, ev.channel)
                ensureBuffer(chanKey)

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
            is IrcEvent.Topic -> {
                val chanKey = resolveBufferKey(netId, ev.channel)
                ensureBuffer(chanKey)
                if (!ev.isHistory) setTopic(chanKey, ev.topic)
            }
            is IrcEvent.ChannelUserMode -> {
                if (!ev.isHistory) {
                    val chanKey = resolveBufferKey(netId, ev.channel)
                    updateUserMode(netId, chanKey, ev.nick, ev.prefix, ev.adding)
                }
            }
            is IrcEvent.ChannelListStart -> _state.value = _state.value.copy(listInProgress = true, channelDirectory = emptyList())
            is IrcEvent.ChannelListItem -> {
                val st = _state.value
                val updated = if (st.channelDirectory.size < 5000) st.channelDirectory + ChannelListEntry(ev.channel, ev.users, ev.topic) else st.channelDirectory
                _state.value = st.copy(channelDirectory = updated)
            }
            is IrcEvent.ChannelListEnd -> _state.value = _state.value.copy(listInProgress = false)

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
                val serverKey = bufKey(netId, "*server*")
                append(serverKey, from = null, text = "*** Server error: ${ev.message}", doNotify = false, isLocal = false)
            }

            // AWAY status change for another user (away-notify CAP).
            // Track away state per-nick so the nicklist can reflect it.
            is IrcEvent.AwayChanged -> {
                val awayMap = nickAwayState.getOrPut(netId) { mutableMapOf() }
                val fold = casefoldText(netId, ev.nick)
                val wasAway = awayMap.containsKey(fold)
                if (ev.awayMessage != null) {
                    // Nick set or changed away message.
                    awayMap[fold] = ev.awayMessage
                    if (!wasAway) {
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
                // Surface a one-line status in the server buffer so the user can see which
                // upstream networks the bouncer reports.  Full per-network buffer trees are
                // deferred to a future feature; for now this gives useful diagnostic info.
                val serverKey = bufKey(netId, "*server*")
                val stateStr = ev.state ?: "unknown"
                val nameStr  = ev.name  ?: ev.networkId
                val hostStr  = if (ev.host != null) " (${ev.host})" else ""
                append(serverKey, from = null, text = "*** Bouncer network: $nameStr$hostStr [$stateStr]", doNotify = false, isLocal = true)
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

            // ChannelModeChanged: live MODE change string (for future UI display of channel modes).
            // Currently surfaced as a status line; the modeString in UiBuffer is updated separately
            // by ChannelModeIs (324) when explicitly requested.
            is IrcEvent.ChannelModeChanged -> {
                // Already handled as a ChannelModeLine via the MODE command handler in IrcCore.
                // This event exists for UI components that want a structured mode-change signal.
                Unit
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
        _state.update { st: UiState ->
            val old = st.connections[netId] ?: NetConnState()
            val newConns = st.connections + (netId to f(old))
            val updated = syncActiveNetworkSummary(st.copy(connections = newConns))
            shouldRefresh = updated.settings.showConnectionStatusNotification || updated.settings.keepAliveInBackground
            updated
        }
        if (shouldRefresh) {
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
        val st = _state.value
        val buf0 = st.buffers[key] ?: return
        if (!scrollbackRequested.add(key)) return

        val (netId, bufferName) = splitKey(key)
        val netName = st.networks.firstOrNull { it.id == netId }?.name ?: "network"
        val maxLines = st.settings.maxScrollbackLines.coerceIn(100, 5000)

        val loadStartMs = System.currentTimeMillis()
        scrollbackLoadStartedAtMs[key] = loadStartMs

        viewModelScope.launch(Dispatchers.IO) {
            val lines = logs.readTail(netName, bufferName, maxLines, st.settings.logFolderUri)
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

                // Build a set of live message signatures for deduplication.
                // Primary: use msgid when available (IRCv3 message-ids cap) - exact, no false positives.
                // Fallback: fuzzy (timeMs rounded to seconds, from, text prefix) for servers without msgid.
                val liveMsgIds = liveDuringLoad.mapNotNull { it.msgId }.toHashSet()
                val liveSignatures = liveDuringLoad.map { msg ->
                    Triple(
                        msg.timeMs / 1000,  // Round to seconds
                        msg.from?.lowercase(),
                        msg.text.take(100).lowercase()
                    )
                }.toSet()

                // Filter loaded messages: must be older than first live, and not a duplicate.
                // Also filter out messages that are too close to the load start time (within 2 seconds)
                // to avoid showing messages from the current session as "scrollback".
                val olderLoaded = loaded.filter { msg ->
                    val isOlder = msg.timeMs < (firstLiveTime - 500L)
                    val isTooRecent = msg.timeMs > (startedAt - 2000L)  // Within 2 seconds of buffer creation
                    // Prefer msgid-based dedup; fall back to fuzzy only when neither message has a msgid.
                    val isDupe = if (msg.msgId != null) {
                        liveMsgIds.contains(msg.msgId)
                    } else {
                        val sig = Triple(
                            msg.timeMs / 1000,
                            msg.from?.lowercase(),
                            msg.text.take(100).lowercase()
                        )
                        liveSignatures.contains(sig)
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

                // Use atomic update to prevent race conditions
                _state.update { currentState: UiState ->
                    val currentBuf = currentState.buffers[key] ?: return@update currentState
                    val newBuf = currentBuf.copy(messages = merged)
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

        // Common IRC log line styles:
        //   "<nick> hello"
        //   "* nick does something"   (/me)
        //   "*** server text"
        if (body.startsWith("<") && body.contains("> ")) {
            val end = body.indexOf("> ")
            if (end > 1) {
                from = body.substring(1, end)
                text = body.substring(end + 2)
            }
        } else if (body.startsWith("* ") && body.length > 2) {
            val rest = body.substring(2)
            val sp = rest.indexOf(' ')
            if (sp > 0) {
                from = rest.substring(0, sp)
                text = rest.substring(sp + 1)
                isAction = true
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
        msgId: String? = null
    ) {
        val ts = timeMs ?: System.currentTimeMillis()
        val msg = UiMessage(
            id = nextUiMsgId.getAndIncrement(),
            timeMs = ts,
            from = from,
            text = text,
            isAction = isAction,
            isMotd = isMotd,
            msgId = msgId
        )

        // Atomic update, then read the committed state for logging/notifications.
        var msgWasDuplicate = false
        _state.update { st: UiState ->
            val buf = st.buffers[bufferKey] ?: UiBuffer(bufferKey)

            // Deduplicate by msgid: if we've already displayed this message (e.g. via
            // echo-message and chathistory both delivering the same message), drop it.
            if (msgId != null && buf.messages.any { it.msgId == msgId }) {
                msgWasDuplicate = true
                return@update st
            }

            val isSelected = (bufferKey == st.selectedBuffer && st.screen == AppScreen.CHAT)
            val unreadInc = if (!isSelected && !isLocal) 1 else 0
            val highlightInc = if (!isSelected && isHighlight && !isLocal) 1 else 0

            val maxLines = st.settings.maxScrollbackLines.coerceIn(100, 5000)
            val newMessages = (buf.messages + msg).takeLast(maxLines)
            // Advance lastReadTimestamp for every message on the selected buffer so the
            // unread separator never appears for messages the user is actively watching.
            val newLastRead = if (isSelected)
                java.time.Instant.ofEpochMilli(ts + 1L).toString()
            else
                buf.lastReadTimestamp
            val newBuf = buf.copy(
                messages = newMessages,
                unread = buf.unread + unreadInc,
                highlights = buf.highlights + highlightInc,
                lastReadTimestamp = newLastRead
            )
            st.copy(buffers = st.buffers + (bufferKey to newBuf))
        }
        val st = _state.value
        if (msgWasDuplicate) return

        // logging
        if (st.settings.loggingEnabled) {
            val (netId, bufferName) = splitKey(bufferKey)
            if (bufferName != "*server*" || st.settings.logServerBuffer) {
                val netName = st.networks.firstOrNull { it.id == netId }?.name ?: "network"
                logs.append(netName, bufferName, formatLogLine(ts, from, text, isAction), st.settings.logFolderUri)
            }
        }

        // notifications
        val isSelected = (bufferKey == st.selectedBuffer && st.screen == AppScreen.CHAT)
        if (doNotify && !isSelected && !isLocal && st.settings.notificationsEnabled) {
            val (netId, bufferName) = splitKey(bufferKey)
            val cleanText = stripIrcFormatting(text)
            val preview = when {
                from == null -> cleanText
                isAction -> "* $from $cleanText"
                else -> "<$from> $cleanText"
            }
            // Use the human-readable network name instead of the internal "*server*" sentinel.
            val notifTitle = if (bufferName == "*server*") {
                st.networks.firstOrNull { it.id == netId }?.name ?: "Server"
            } else bufferName
            if (isPrivate && st.settings.notifyOnPrivateMessages) {
                runCatching { notifier.notifyPm(netId, notifTitle, preview) }
                if (st.settings.vibrateOnHighlight) {
                    runCatching { vibrateForHighlight(st.settings.vibrateIntensity) }
                }
            } else if (isHighlight && st.settings.notifyOnHighlights) {
                runCatching { notifier.notifyHighlight(netId, notifTitle, preview, st.settings.playSoundOnHighlight) }
                if (st.settings.vibrateOnHighlight) {
                    runCatching { vibrateForHighlight(st.settings.vibrateIntensity) }
                }
            }
        }
    }

    /**
     * Determine whether a message should be highlighted for a specific network.
     *
     * Important: nicks can differ per network, so we must NOT use the global UiState.myNick.
     */
/**
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
 * rfc1459 / strict-rfc1459 — map the four extended ASCII special-char pairs.
 * ascii                     — ASCII A-Z only.
 * anything else             — full Unicode lowercase + RFC1459 special-char pairs.
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
        val st = _state.value
        _state.value = st.copy(dccOffers = st.dccOffers.filterNot { it == offer })

        val incoming = DccTransferState.Incoming(offer)
        _state.value = _state.value.copy(dccTransfers = _state.value.dccTransfers + incoming)

        // route the transfer through the network where the offer was received.
        val netId = offer.netId.takeIf { it.isNotBlank() } ?: _state.value.activeNetworkId ?: return
        val rt = runtimes[netId] ?: return
        val c = rt.client
        val minP = st.settings.dccIncomingPortMin
        val maxP = st.settings.dccIncomingPortMax
        val customFolder = st.settings.dccDownloadFolderUri

        viewModelScope.launch {
            try {
                val savedPath = if (offer.isPassive) {
                    // Passive/reverse DCC: we open a port and tell the sender to connect.
                    dcc.receivePassive(
                        offer = offer,
                        portMin = minP,
                        portMax = maxP,
                        customFolderUri = customFolder,
                        onListening = { ipAsInt, port, size, token ->
                            val name = quoteDccFilenameIfNeeded(offer.filename)
                            val tokenStr = if (offer.turbo) "${token}T" else token.toString()
                            val payload = "DCC SEND $name $ipAsInt $port $size $tokenStr"
                            c.ctcp(offer.from, payload)
                            append(bufKey(netId, "*server*"), from = null, text = "*** Accepted passive DCC offer: ${offer.filename} (listening on $port)", doNotify = false)
                        }
                    ) { got, _ ->
                        updateIncoming(offer) { it.copy(received = got) }
                    }
                } else {
                    dcc.receive(offer, customFolder) { got, _ ->
                        updateIncoming(offer) { it.copy(received = got) }
                    }
                }
                updateIncoming(offer) { it.copy(done = true, savedPath = savedPath) }
                val displayPath = if (savedPath.startsWith("content://")) "Downloads" else savedPath.substringAfterLast('/')
                notifier.notifyFileDone(netId, offer.filename, displayPath)
            } catch (t: Throwable) {
                updateIncoming(offer) { it.copy(error = t.message ?: "error") }
            }
        }
    }

    fun rejectDcc(offer: DccOffer) {
        _state.value = _state.value.copy(dccOffers = _state.value.dccOffers.filterNot { it == offer })
        val netId = offer.netId.takeIf { it.isNotBlank() } ?: _state.value.activeNetworkId ?: return
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

        viewModelScope.launch {
            try {
                append(key, from = null, text = "*** Connecting DCC CHAT to ${offer.from} (${offer.ip}:${offer.port})…", doNotify = false)
                val socket = dcc.connectChat(offer)
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

        val key = dccChatBufferKey(netId, peer)
        ensureBuffer(key)
        _state.value = _state.value.copy(selectedBuffer = key)

        val minP = st.settings.dccIncomingPortMin
        val maxP = st.settings.dccIncomingPortMax

        viewModelScope.launch {
            try {
                append(key, from = null, text = "*** Offering DCC CHAT to $peer…", doNotify = false)
                val socket = dcc.startChat(
                    portMin = minP,
                    portMax = maxP,
                    onClient = { ipAsInt, port ->
                        val payload = "DCC CHAT chat $ipAsInt $port"
                        c.ctcp(peer, payload)
                        append(bufKey(netId, "*server*"), from = null, text = "*** Sent DCC CHAT offer to $peer (port $port)", doNotify = false)
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

                val outgoing = DccTransferState.Outgoing(target = target, filename = offerName, fileSize = fileSize)
                _state.value = st.copy(dccTransfers = st.dccTransfers + outgoing)

                fun updateOutgoing(sent: Long) {
                    val st2 = _state.value
                    _state.value = st2.copy(dccTransfers = st2.dccTransfers.map {
                        if (it is DccTransferState.Outgoing && it.target == target && it.filename == offerName) it.copy(bytesSent = sent) else it
                    })
                }

                suspend fun doActiveSend() {
                    dcc.sendFile(
                        file = file,
                        portMin = minP,
                        portMax = maxP,
                        onClient = { ipAsInt, port, size ->
                            val payload = "DCC SEND $offerNamePayload $ipAsInt $port $size"
                            c.ctcp(target, payload)
                            append(statusKey, from = null, text = "*** Offering $offerName to $target via DCC (active, port $port)…", doNotify = false)
                        },
                        onProgress = { sent, _ -> updateOutgoing(sent) }
                    )
                }

                suspend fun doPassiveSend(timeoutMs: Long = 120_000L) {
                    val token = Random.nextLong(1L, 0x7FFFFFFFL)
                    val def = CompletableDeferred<DccOffer>()
                    pendingPassiveDccSends[token] = PendingPassiveDccSend(target, offerName.substringAfterLast('/').substringAfterLast('\\'), fileSize, def)
                    try {
                        val ipInt = dcc.localIpv4AsInt()
                        val payload = "DCC SEND $offerNamePayload $ipInt 0 $fileSize $token"
                        c.ctcp(target, payload)
                        append(statusKey, from = null, text = "*** Offering $offerName to $target via DCC (passive)…", doNotify = false)

                        val reply = withTimeout(timeoutMs) { def.await() }
                        if (reply.port <= 0) throw IOException("Invalid passive DCC reply")
                        append(statusKey, from = null, text = "*** $target accepted; connecting…", doNotify = false)

                        dcc.sendFileConnect(
                            file = file,
                            host = reply.ip,
                            port = reply.port,
                            onProgress = { sent, _ -> updateOutgoing(sent) }
                        )
                    } finally {
                        pendingPassiveDccSends.remove(token)
                    }
                }

                when (mode) {
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
                            throw IOException("No response from $target — DCC timed out")
                        }
                    }
                }

                val st3 = _state.value
                _state.value = st3.copy(dccTransfers = st3.dccTransfers.map {
                    if (it is DccTransferState.Outgoing && it.target == target && it.filename == offerName) it.copy(done = true) else it
                })
                outgoingSendJobs.remove(jobKey)
                append(statusKey, from = null, text = "*** DCC send complete: $offerName → $target", doNotify = false)

            } catch (t: Throwable) {
                val cancelled = t is kotlinx.coroutines.CancellationException
                val msg = if (cancelled) "Cancelled" else (t.message ?: t::class.java.simpleName).trim()
                val stErr = _state.value
                offerNameForState?.let { fn ->
                    outgoingSendJobs.remove("$target/$fn")
                    _state.value = stErr.copy(dccTransfers = stErr.dccTransfers.map {
                        if (it is DccTransferState.Outgoing && it.target == target && it.filename == fn)
                            it.copy(done = true, error = if (cancelled) null else msg)
                        else it
                    })
                    if (!cancelled) append(statusKey, from = "DCC", text = "*** DCC send failed: $msg", isHighlight = true)
                    else append(statusKey, from = null, text = "*** DCC send cancelled: $fn", doNotify = false)
                } ?: run {
                    _state.value = stErr.copy(dccTransfers = stErr.dccTransfers + DccTransferState.Outgoing(target = target, filename = "(unknown)", done = true, error = msg))
                    if (!cancelled) append(statusKey, from = "DCC", text = "*** DCC send failed: $msg", isHighlight = true)
                }
                if (cancelled) throw t   // re-throw so coroutine completes correctly
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
        val f = File(path)
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(appContext, appContext.packageName + ".fileprovider", f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (f.isDirectory) "application/octet-stream" else "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // /SYSINFO

	private var cachedGpu: String? = null

	private fun readGpuRendererBestEffort(): String {
		return try {
			val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
			if (display == EGL14.EGL_NO_DISPLAY) return "Unknown"

			val vers = IntArray(2)
			if (!EGL14.eglInitialize(display, vers, 0, vers, 1)) return "Unknown"

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
				EGL14.eglTerminate(display)
				return "Unknown"
			}
			val config = configs[0] ?: run {
				EGL14.eglTerminate(display)
				return "Unknown"
			}

			val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
			val ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
			val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
			val surf = EGL14.eglCreatePbufferSurface(display, config, surfAttribs, 0)

			EGL14.eglMakeCurrent(display, surf, surf, ctx)

			val vendor = GLES20.glGetString(GLES20.GL_VENDOR)?.trim().orEmpty()
			val renderer = GLES20.glGetString(GLES20.GL_RENDERER)?.trim().orEmpty()

			// cleanup
			EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
			EGL14.eglDestroySurface(display, surf)
			EGL14.eglDestroyContext(display, ctx)
			EGL14.eglTerminate(display)

			val joined = listOf(vendor, renderer).filter { it.isNotBlank() }.joinToString(" ")
			if (joined.isBlank()) "Unknown" else joined
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

        return "Device: $device running Android $release $codename (API $api), CPU: ${cpuCores}-core $cpuModel, " +
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
    }
}