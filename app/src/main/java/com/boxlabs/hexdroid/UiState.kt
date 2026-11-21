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

import com.boxlabs.hexdroid.data.ChannelListEntry
import com.boxlabs.hexdroid.data.ChannelPrefs
import com.boxlabs.hexdroid.data.NetworkProfile
import com.boxlabs.hexdroid.data.ThemeMode
import kotlinx.collections.immutable.PersistentList

enum class AppScreen { CHAT, LIST, SETTINGS, NETWORKS, NETWORK_EDIT, TRANSFERS, ABOUT, IGNORE, SCRIPTS, DCC_TRUSTED, MENTIONS }

/**
 * UI-level message model. [id] must be unique within a buffer: it is the LazyColumn key, and
 * timestamps alone collide when several lines arrive in the same millisecond.
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
    /**
     * True while this is a locally-echoed +AGE message being HELD pending key agreement (the peer's
     * handshake hasn't completed, so it hasn't gone on the wire yet). Cleared to false once the bridge
     * flushes the held queue. The UI dims such lines and shows a small clock so an optimistic echo is
     * never mistaken for a delivered message, the exact "looks sent but wasn't" trap this guards against.
     */
    val pending: Boolean = false,
    /**
     * True when the server rejected the send after we had already echoed it locally (currently
     * only a FAIL BATCH MULTILINE_*). The UI dims the line and captions it so a message that
     * never reached the channel is not left looking delivered.
     */
    val failed: Boolean = false,
    /**
     * Marks the separator drawn where this session begins, so a second scrollback load for
     * the same buffer does not draw another one.
     */
    val isSessionDivider: Boolean = false,
    /** True for a line read back from the on-disk log rather than received from a server. */
    val fromLog: Boolean = false,
    /** True for a line the server replayed rather than one that happened while we watched. */
    val fromHistory: Boolean = false,
    /**
     * Reactions attached to this message, keyed by the reaction text and holding the nicks
     * who gave it.
     *
     * The text is whatever the sender chose. draft/react does not require an emoji and a
     * short word is a normal thing to send, so nothing here assumes a single glyph.
     */
    val reactions: Map<String, Set<String>> = emptyMap(),
    /**
     * True when the message arrived as (or was sent as) a single draft/multiline BATCH.
     * Only these fold behind "show more": a long MOTD or ASCII art is naturally multi-line
     * and folding it would hide content the sender laid out deliberately.
     */
    val multiline: Boolean = false,
    /** draft/oper-tag: the sender was marked as an IRC operator; rendered as a star before the nick. */
    val fromOper: Boolean = false,
    /** Bot Mode `bot` tag: the sender is a bot; rendered as a badge before the nick. */
    val fromBot: Boolean = false,
)
data class UiBuffer(
    val name: String,
    /**
     * The buffer's messages together with the indices that recognise a repeat delivery.
     *
     * Every change to the message list goes through [BufferLog], which owns ordering,
     * deduplication and trimming as one unit. Reads still use [messages].
     */
    val log: BufferLog = BufferLog(),
    val unread: Int = 0,
    val highlights: Int = 0,
    val topic: String? = null,
    /** Channel mode LETTERS only, from 324 or a live delta, e.g. "+nst". Drives the ops toggles. */
    val modeString: String? = null,
    /**
     * Same modes but with their parameters, e.g. "+ntl 59", for the title bar. Kept apart
     * from [modeString] because the toggles want letters and only 324 is authoritative
     * about parameter values.
     */
    val modeDisplay: String? = null,
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
    /** Time of our newest message the other person has confirmed reading (read receipts, queries only). */
    val peerReadAtMs: Long? = null,
    /** True while a CHATHISTORY BEFORE request for this buffer is in flight. Drives the spinner. */
    val historyLoading: Boolean = false,

    /**
     * True once the server answered a backfill with a short page, so the history has run out; hides
     * "load older". A full page that deduplicates to nothing doesn't set it.
     */
    val historyExhausted: Boolean = false,

    /**
     * True when a catch-up stopped with messages still missing between what this buffer
     * holds and the live conversation. Resumed the next time the buffer is opened.
     */
    val historyGapOpen: Boolean = false,
) {
    /** The buffer's messages, oldest first. */
    val messages: PersistentList<UiMessage> get() = log.messages
}

enum class FontChoice { OPEN_SANS, INTER, MONOSPACE, CUSTOM }

/** Default style applied to chat text (buffer + input). IRC formatting codes can still override this per-span. */
enum class ChatFontStyle { REGULAR, BOLD, ITALIC, BOLD_ITALIC }

/** How a timestamp is wrapped in the chat: [12:34], (12:34), 12:34 and so on. */
enum class TimestampStyle(val open: String, val close: String) {
    SQUARE("[", "]"),
    ROUND("(", ")"),
    ANGLE("<", ">"),
    CURLY("{", "}"),
    DASH("", " -"),
    NONE("", ""),
}

/** How a sender's nick is marked in the chat: <nick>, [nick], nick: and so on. */
enum class NickStyle(val open: String, val close: String) {
    ANGLE("<", ">"),
    SQUARE("[", "]"),
    ROUND("(", ")"),
    CURLY("{", "}"),
    COLON("", ":"),
    NONE("", ""),
}

enum class VibrateIntensity { LOW, MEDIUM, HIGH }

/**
 * Accent colour for the Light, Dark and System themes. [DYNAMIC] follows the wallpaper on
 * Android 12 and later; [CUSTOM] uses [UiSettings.themeColorCustomInt]. Matrix and Terminal keep
 * their own palettes.
 */
enum class ThemeColor(val seed: Long?) {
    DEFAULT(null),
    DYNAMIC(null),
    TEAL(0xFF14B8A6),
    GREEN(0xFF22C55E),
    PURPLE(0xFF8B5CF6),
    PINK(0xFFEC4899),
    ORANGE(0xFFF97316),
    RED(0xFFEF4444),
    CUSTOM(null),
}


/** How we initiate DCC SEND connections. */
enum class DccSendMode { AUTO, ACTIVE, PASSIVE }

data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** Accent colour for the Light, Dark and System themes. */
    val themeColor: ThemeColor = ThemeColor.DEFAULT,
    /** ARGB seed for [ThemeColor.CUSTOM]. */
    val themeColorCustomInt: Int? = null,
    val compactMode: Boolean = false,
    // Buffer-drawer navigation. networkTabs replaces the vertical network tree with a
    // horizontal tab strip (one tab per network, showing that network's buffers below).
    // Which networks appear is controlled per-network by NetworkProfile.showInSidebar,
    // not a global setting.
    val networkTabs: Boolean = false,
    // Pin a network quick-switcher bar at the bottom of the chat, just above the message input,
    // independent of the drawer's tree/tabs mode. Tapping a network jumps to it.
    val networkTabsAtBottom: Boolean = false,
    val showTimestamps: Boolean = true,
    val timestampFormat: String = "HH:mm:ss",
    /** Brackets drawn around the timestamp. */
    val timestampStyle: TimestampStyle = TimestampStyle.SQUARE,
    /** ARGB colour for the timestamp, or null to use the message colour. */
    val timestampColorInt: Int? = null,
    /** Brackets drawn around the sender's nick. */
    val nickStyle: NickStyle = NickStyle.ANGLE,
    val fontScale: Float = 1.0f,
    val fontChoice: FontChoice = FontChoice.OPEN_SANS,
    val chatFontChoice: FontChoice = FontChoice.MONOSPACE,
    val customFontPath: String? = null,
    val customChatFontPath: String? = null,

    val chatFontStyle: ChatFontStyle = ChatFontStyle.REGULAR,

    /**
     * Space BETWEEN messages, as a fraction of the chat font size.
     * Tight 0.0, Normal 0.15, Relaxed 0.45. Does not affect the leading inside a
     * single wrapped message: that is [chatFontLineHeight].
     */
    val chatLineSpacing: Float = 0.15f,

    /**
     * Leading INSIDE one message, as a multiple of the chat font size, applied with
     * LineHeightStyle.Trim.None so the row pitch is the same for every font rather
     * than following each font's own ascent and descent.
     * Tight 1.0, Normal 1.15, Relaxed 1.35.
     */
    val chatFontLineHeight: Float = 1.15f,

    /**
     * Sp added to (or taken off) the nicklist's width-derived font size.
     */
    val nicklistFontOffset: Int = 0,
    val showTopicBar: Boolean = true,
    val hideMotdOnConnect: Boolean = false,
    /** Channel events left out of chat. Your own nick changes and kicks always show. */
    val hideJoins: Boolean = false,
    val hideParts: Boolean = false,
    val hideQuits: Boolean = false,
    val hideNickChanges: Boolean = false,
    val hideTopicChanges: Boolean = false,
    val hideKicks: Boolean = false,
    val hideModeChanges: Boolean = false,
    /** Joins, parts, quits and nick changes show only for people who spoke in the last [smartFilterMinutes]. */
    val smartFilter: Boolean = false,
    val smartFilterMinutes: Int = 10,
    /** Join, part and quit lines leave out the user@host. */
    val hideHostnames: Boolean = false,
    /** Colour channel-event lines (join, part, quit, kick, nick, mode). Display only. */
    val colorChannelEvents: Boolean = true,
    /**
     * Hide the "is away" / "is back" lines from away-notify. Away state is still tracked for the
     * nicklist.
     */
    val hideAwayNotify: Boolean = false,
    val hideTopicOnEntry: Boolean = false,
    val defaultShowNickList: Boolean = true,
    val defaultShowBufferList: Boolean = true,

    // Landscape split-pane fractions, updated by draggable handles.
    val bufferPaneFracLandscape: Float = 0.22f,
    // Below the enforced minimum on purpose: the pane opens at its narrowest
    // on every screen width, and ChatScreen clamps this into range on read.
    val nickPaneFracLandscape: Float = 0.05f,

    val highlightOnNick: Boolean = true,
    val extraHighlightWords: List<String> = emptyList(),

    val notificationsEnabled: Boolean = true,
    val notifyOnHighlights: Boolean = true,
    val notifyOnPrivateMessages: Boolean = true,
    val showConnectionStatusNotification: Boolean = true,
    val keepAliveInBackground: Boolean = true,
    /**
     * Let servers with the webpush extension notify through a UnifiedPush distributor while no
     * connection is open. Off by default; independent of [keepAliveInBackground].
     */
    val webPushEnabled: Boolean = false,
    /**
     * Reconnect the networks marked auto-connect after the device reboots, without the
     * user opening the app. Off by default.
     */
    val connectOnBoot: Boolean = false,
    /**
     * Hold the connect-on-boot fan-out until Wi-Fi is up, rather than connecting over
     * whatever is available at boot, which is usually mobile data.
     */
    val connectOnBootWifiOnly: Boolean = false,
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
    /** Log every protocol line to a per-network Raw buffer. Credentials are redacted. */
    val rawLog: Boolean = false,
    val ircHistoryTriggersNotifications: Boolean = false,

    val dccEnabled: Boolean = false,
    val dccSendMode: DccSendMode = DccSendMode.AUTO,
    val dccSecure: Boolean = false,      // SDCC: wrap transfers in TLS
    val dccIncomingPortMin: Int = 5000,
    val dccIncomingPortMax: Int = 5010,
    val dccDownloadFolderUri: String? = null,

    val quitMessage: String = "An IRC client for Android - https://hexdroid.org",
    val partMessage: String = "Leaving",

    val colorizeNicks: Boolean = true,
    /**
     * Show a presence dot in the member list for nicks with no avatar, filling the space
     * their avatar would occupy. Red when away, green otherwise.
     */
    val showNickIcons: Boolean = true,
    /**
     * Answer CTCP VERSION, TIME, PING and the rest. Off means they are shown but never
     * replied to, so a stranger cannot learn the client, the platform or the clock.
     */
    val ctcpRepliesEnabled: Boolean = true,
    /** Switch back to the configured nick after connecting on a fallback. */
    val nickRegainEnabled: Boolean = true,
    /**
     * Custom colour for your own nick, stored as ARGB int (e.g. 0xFF_FF6600.toInt()).
     * Null means "Auto" - let [NickColors.colorForNick] pick a colour from the hash,
     * the same as any other nick.
     */
    val ownNickColorInt: Int? = null,

    val introTourSeenVersion: Int = 0,
    val mircColorsEnabled: Boolean = true,
    val ansiColorsEnabled: Boolean = true,
    /**
     * Detect consecutive ASCII/ANSI art lines and render them as a shrunk-to-fit
     * block. Off means every message renders as normal chat text.
     */
    val artDetectionEnabled: Boolean = true,

    val welcomeCompleted: Boolean = false,
    val appLanguage: String? = null,
    val portraitNicklistOverlay: Boolean = true,
    // Same intent as nickPaneFracLandscape: start at the narrowest allowed.
    val portraitNickPaneFrac: Float = 0.20f,

    /** Broadcast typing status to others (draft/typing CAP). Off by default for privacy. */
    val sendTypingIndicator: Boolean = false,
    /** Show typing indicators from others. Independent of sendTypingIndicator. */
    val receiveTypingIndicator: Boolean = true,
    /** Send and show read receipts in private messages. Off by default for privacy. */
    val readReceiptsEnabled: Boolean = false,
    /** Show Settings and the network editor as one continuous page instead of by category. */
    val settingsOnePage: Boolean = false,

    /** Show inline image and YouTube thumbnail previews in chat. */
    val imagePreviewsEnabled: Boolean = false,
    /** When true, only load previews on Wi-Fi to save mobile data. */
    val imagePreviewsWifiOnly: Boolean = true,
    /** Show the title of a linked web page under the message. Off by default. */
    val linkPreviewsEnabled: Boolean = false,

    /** Ask before sending text that would go out as more than [pasteGuardLines] lines. */
    val pasteGuardEnabled: Boolean = true,
    val pasteGuardLines: Int = 5,
    /**
     * Where "Upload as paste" sends text when the network has no upload service: a Paste site or
     * api.php address, or a service that takes the text as the request body and answers with its
     * link. Blank when none.
     */
    val pasteServiceUrl: String = PasteService.DEFAULT_SERVICE,

    /** Alert when someone on a network's notify list comes online. */
    val notifyListAlerts: Boolean = true,

    /** Mark away after the app has been in the background for [autoAwayMinutes]. */
    val autoAwayEnabled: Boolean = false,
    val autoAwayMinutes: Int = 10,
    /** Away message for auto-away; blank uses the default. */
    val autoAwayMessage: String = "",
    /** Also set auto-away on bouncer connections. */
    val autoAwayOnBouncers: Boolean = false,
    /** User-defined command aliases */
    val commandAliases: Map<String, String> = emptyMap(),
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
    /** Channel prefixes from ISUPPORT CHANTYPES. Until it arrives, every common prefix counts. */
    val chanTypes: String = "#&!+",
    /** PREFIX modes and their symbols from ISUPPORT, highest rank first. */
    val prefixModes: String = "qaohv",
    val prefixSymbols: String = "~&@%+",
    /** EXTBAN prefix (e.g. "~", or "" for no prefix); null when unsupported. */
    val extbanPrefix: String? = null,
    /** EXTBAN type letters; null when unsupported. */
    val extbanTypes: String? = null,
    /** draft/account-extban letter; null when unsupported. */
    val accountExtban: String? = null,
    /** True after 381 RPL_YOUREOPER is received for this connection */
    val isIrcOper: Boolean = false,
    /** True when the message-tags or draft/message-reactions cap is negotiated.
     *  Used by ChatScreen to decide whether to offer emoji reactions. */
    val hasReactionSupport: Boolean = false,
    /** True when draft/message-redaction (or the graduated name) is negotiated.
     *  Gates the "Delete message" context-sheet action for own messages. */
    val hasRedactionSupport: Boolean = false,
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
    /**
     * Identities the certificate claimed at the most recent hostname mismatch. Cleared on the
     * next successful connect. Null when no mismatch is active.
     */
    val tlsHostnameMismatchIdentities: List<String>? = null,
    /**
     * soju.im/FILEHOST upload endpoint from ISUPPORT, when the server offers one.
     * Non-null enables the attach button in the chat input row.
     */
    val filehostUrl: String? = null,
    /** ICON / draft/ICON ISUPPORT token: server-supplied icon URL. */
    val networkIconUrl: String? = null,
    /**
     * draft/metadata-2 display-name values, keyed by lowercased nick or channel.
     * Values are sanitised at ingest (see IrcViewModel.sanitizeMetadataValue).
     */
    val displayNames: Map<String, String> = emptyMap(),
    /** draft/metadata-2 avatar URLs, keyed by lowercased nick or channel. */
    val avatarUrls: Map<String, String> = emptyMap(),
    /**
     * draft/metadata-2 `color` values (6 hex digits, no leading #), keyed by
     * lowercased nick. Applied as the nick colour, below a manual own-nick override.
     */
    val nickColors: Map<String, String> = emptyMap(),
    /** draft/metadata-2 `status` text, keyed by lowercased nick. */
    val statuses: Map<String, String> = emptyMap(),
    /**
     * Every metadata key set on our OWN current nick, verbatim (post-sanitise),
     * keyed by lowercased key. Populated from a METADATA * LIST refresh and from
     * live updates for our nick; backs the metadata editor's pre-fill. Bounded to
     * a single target (us), so it cannot grow without limit like a per-nick store.
     */
    val ownMetadata: Map<String, String> = emptyMap(),
    /**
     * Extra text metadata (pronouns / homepage / bio) shown in the nick tap sheet,
     * keyed by lowercased nick then key. A bounded whitelist, so it stays small.
     */
    val extraMetadata: Map<String, Map<String, String>> = emptyMap(),
    /** Our own services account name when identified (RPL_LOGGEDIN / account-notify), else null. */
    val myAccount: String? = null,
    /** draft/account-registration progress for the guided registration dialog. */
    val regState: RegState = RegState(),
    /**
     * Lowercased nicks currently marked away (from away-notify / WHOX), mirrored from
     * the internal nickAwayState map so the member list can dim them reactively.
     */
    val awayNicks: Set<String> = emptySet(),
    /**
     * Lowercased nicks known to be bots, from the BOT mode letter in WHO/WHOX flags or
     * a `bot` message tag. Used to badge them in the member list and nick sheet.
     */
    val botNicks: Set<String> = emptySet(),
    /** Lowercased notify-list nicks MONITOR reports online on this connection. */
    val watchOnline: Set<String> = emptySet(),
    /** Lowercased notify-list nicks MONITOR reports offline on this connection. */
    val watchOffline: Set<String> = emptySet(),
    /** False once registered on a server that does not offer MONITOR. Null until known. */
    val monitorSupported: Boolean? = null,
)

/** A highlight kept for the Mentions view. */
data class MentionEntry(
    val netId: String,
    val bufferKey: String,
    val from: String,
    val text: String,
    val timeMs: Long,
    /** Server msgid, when the message had one. */
    val msgId: String? = null,
)

/** Text held by the paste guard until the user decides how to send it. */
data class PasteGuardRequest(
    val bufferKey: String,
    val text: String,
    /** How many lines it would go out as. */
    val lines: Int,
    /** Estimated time to send every line under flood pacing. */
    val waitMs: Long,
    /** Sent as one multiline message, which other modern clients show as a block. */
    val batched: Boolean,
    /** An upload service is available for this network. */
    val canUpload: Boolean,
    /** Setting a paste service would make uploading possible: none is set and the network is not proxied. */
    val suggestPasteService: Boolean = false,
    /** An upload is running. */
    val uploading: Boolean = false,
    /** Set when the text is a reply to a message. */
    val reply: PasteReply? = null,
)

/** The message a held reply answers. */
data class PasteReply(val from: String, val originalText: String, val msgId: String?)

/** Something shared from another app, waiting for the user to pick where it goes. */
data class PendingShare(
    val text: String? = null,
    /** content:// URIs of shared files. */
    val uris: List<String> = emptyList(),
    val mimeType: String? = null,
)

/** Phase of a draft/account-registration flow, for the registration dialog. */
enum class RegPhase { IDLE, VERIFY_REQUIRED, SUCCESS, FAILED }

/** Structured snapshot of a draft/account-registration exchange. */
data class RegState(
    val phase: RegPhase = RegPhase.IDLE,
    val account: String? = null,
    val message: String? = null,
)

data class BanEntry(
    val mask: String,
    val setBy: String? = null,
    val setAtMs: Long? = null
)

/**
 * One upstream network reported by a bouncer (soju.im/bouncer-networks, or a row of ZNC's
 * ListNetworks), as opposed to a configured [NetworkProfile]. [id] is the bouncer's stable netid.
 */
data class BouncerUpstreamInfo(
    val id: String,
    /** Attributes as last reported, keyed by lower-cased name. */
    val attrs: Map<String, String> = emptyMap(),
    /** Epoch-ms of the most recent BOUNCER NETWORK update for this upstream. */
    val lastSeenMs: Long = 0L,
) {
    val name: String? get() = attrs["name"]
    val host: String? get() = attrs["host"]
    /** "connected", "connecting" or "disconnected", or null when unknown or cleared. */
    val state: String? get() = attrs["state"]
    val port: Int? get() = attrs["port"]?.toIntOrNull()
    /** Null when the bouncer didn't say. */
    val tls: Boolean? get() = when (attrs["tls"]) { "1" -> true; "0" -> false; else -> null }
    val nickname: String? get() = attrs["nickname"]
    val username: String? get() = attrs["username"]
    val realname: String? get() = attrs["realname"]
    /** Why the bouncer's last connection to this network failed, if it did. */
    val error: String? get() = attrs["error"]
}

/** A soju upstream network as edited in the add and edit dialog. Blank fields are left unset. */
data class BouncerNetworkDraft(
    val name: String = "",
    val host: String = "",
    val port: String = "",
    val tls: Boolean = true,
    val nickname: String = "",
    val username: String = "",
    val realname: String = "",
    /** The upstream server password. soju never reports it, so blank keeps the current one. */
    val pass: String = "",
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

/** Where a log search looks. */
enum class LogSearchScope { BUFFER, NETWORK, ALL }

/** The log search in progress or last finished. */
data class LogSearchState(
    val running: Boolean = false,
    val query: String = "",
    val hits: List<LogSearchHit> = emptyList(),
    /** More matched than [hits] holds; only the newest are kept. */
    val truncated: Boolean = false,
    /** A search has finished for [query]. */
    val searched: Boolean = false,
    /** The bouncer's history (soju.im/search) is still being searched. */
    val serverRunning: Boolean = false,
    /** Conversations whose bouncer history was searched and answered. */
    val serverSearched: Int = 0,
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
    /** The buffer [pendingShareText] must go to, or null for whichever is open. */
    val pendingShareTarget: String? = null,
    /** A share from another app with no target yet; the chooser is shown while set. */
    val pendingShare: PendingShare? = null,
    /** Highlights for the Mentions view, newest last. */
    val mentions: List<MentionEntry> = emptyList(),
    /** Highlights since the Mentions view was last opened. */
    val unseenMentions: Int = 0,
    /** Text held by the paste guard, or null. */
    val pasteGuard: PasteGuardRequest? = null,
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
    /** RPL_TRYAGAIN notice for the channel list (e.g. SECURELIST cooldown), else null. */
    val listTryAgainMessage: String? = null,
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
    /** Per-buffer settings, keyed by [com.boxlabs.hexdroid.data.ChannelPrefsCodec.keyFor]. */
    val channelPrefs: Map<String, com.boxlabs.hexdroid.data.ChannelPrefs> = emptyMap(),
    // Prevents a one-frame default-value flicker before DataStore loads.
    val settingsLoaded: Boolean = false,
    val networks: List<NetworkProfile> = emptyList(),
    val activeNetworkId: String? = null,
    val editingNetwork: NetworkProfile? = null,

    val networkEditError: String? = null,

    /**
     * Upstream networks reported by each bouncer connection, keyed by our network id and then the
     * bouncer's netid. Kept in sync with BOUNCER NETWORK and cleared when the connection drops.
     */
    val bouncerNetworks: Map<String, Map<String, BouncerUpstreamInfo>> = emptyMap(),

    val plaintextWarningNetworkId: String? = null,
    /** Non-null when a connect attempt was blocked because ACCESS_LOCAL_NETWORK is not granted (API 37+). */
    val localNetworkWarningNetworkId: String? = null,

    val dccOffers: List<DccOffer> = emptyList(),
    val dccChatOffers: List<DccChatOffer> = emptyList(),
    val dccTransfers: List<DccTransferState> = emptyList(),

    val backupMessage: String? = null,
    /** True when [backupMessage] reports a failed backup or restore. */
    val backupIsError: Boolean = false,
    /** The backup being restored while its passwords' passphrase is asked for. */
    val restorePassphraseUri: String? = null,
    /** The passphrase last given for [restorePassphraseUri] was wrong. */
    val restorePassphraseWrong: Boolean = false,

    /**
     * Transient toast/feedback after a bouncer-network discover-and-clone import attempt.
     * Set by [IrcViewModel.cloneBouncerNetwork] / [IrcViewModel.refreshBouncerNetworks];
     * cleared by the screen via [IrcViewModel.clearBouncerCloneMessage] once shown.
     */
    val bouncerCloneMessage: String? = null,

    /**
     * True when the saved log folder has become unreadable (SecurityException), so log I/O for it
     * is skipped and Settings shows a re-pick warning. Usually a backup restored on a fresh
     * install, since SAF grants don't survive a reinstall.
     */
    val logFolderUnreadable: Boolean = false,

    /**
     * Incremented whenever an E2E key changes, so the chat screen can re-derive the buffer's
     * encryption state without keys ever entering UI state.
     */
    val e2eKeyVersion: Int = 0,
)
