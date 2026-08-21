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

package com.boxlabs.hexdroid.ui

import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.SendToMobile
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.RecentActors
import androidx.compose.material.icons.filled.IntegrationInstructions
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Badge
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ripple
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.boxlabs.hexdroid.ChatFontStyle
import com.boxlabs.hexdroid.NickStyle
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.UiMessage
import com.boxlabs.hexdroid.UiSettings
import com.boxlabs.hexdroid.UiState
import com.boxlabs.hexdroid.parseAnsiRuns
import com.boxlabs.hexdroid.stripIrcFormatting
import com.boxlabs.hexdroid.ui.components.LagBar
import com.boxlabs.hexdroid.ui.theme.LocalAccentColors
import com.boxlabs.hexdroid.ui.theme.fontFamilyForChoice
import com.boxlabs.hexdroid.ui.tour.TourTarget
import com.boxlabs.hexdroid.ui.tour.tourTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.boxlabs.hexdroid.toSpanStyle as ansiToSpanStyle

/** Commands with a short description shown in the hint popup. */
private data class IrcCommand(
    val name: String,
    val usage: String,
    /** Localized description for built-in commands. */
    @param:androidx.annotation.StringRes val descriptionRes: Int? = null,
    /** Runtime description for commands supplied by .hex scripts. */
    val description: String? = null,
)

private val IRC_COMMANDS = listOf(
    // Messaging
    IrcCommand("me", "/me <action>", R.string.cmd_me),
    IrcCommand("msg", "/msg <nick> <message>", R.string.cmd_msg),
    IrcCommand("notice", "/notice <target> <text>", R.string.cmd_notice),
    IrcCommand("react", "/react <emoji> [n]", R.string.cmd_react),
    IrcCommand("unreact", "/unreact <emoji> [n]", R.string.cmd_unreact),
    IrcCommand("amsg", "/amsg <message>", R.string.cmd_amsg),
    IrcCommand("ame", "/ame <action>", R.string.cmd_ame),

    // Channels
    IrcCommand("join", "/join <channel> [key]", R.string.cmd_join),
    IrcCommand("part", "/part [channel] [reason]", R.string.cmd_part),
    IrcCommand("cycle", "/cycle [channel]", R.string.cmd_cycle),
    IrcCommand("topic", "/topic [new topic]", R.string.cmd_topic),
    IrcCommand("invite", "/invite <nick> [channel]", R.string.cmd_invite),
    IrcCommand("knock", "/knock <channel> [reason]", R.string.cmd_knock),
    IrcCommand("list", "/list", R.string.cmd_list),
    IrcCommand("names", "/names [channel]", R.string.cmd_names),

    // Buffer management
    IrcCommand("clear", "/clear", R.string.cmd_clear),
    IrcCommand("close", "/close", R.string.cmd_close),
    IrcCommand("closekey", "/closekey <net::buffer>", R.string.cmd_closekey),
    IrcCommand("find", "/find <text>", R.string.cmd_find),
    IrcCommand("grep", "/grep <text>", R.string.cmd_grep),
    IrcCommand("search", "/search <text>", R.string.cmd_search),
    IrcCommand("gsearch", "/gsearch <text>", R.string.cmd_gsearch),
    IrcCommand("gfind", "/gfind <text>", R.string.cmd_gfind),

    // User & nick
    IrcCommand("nick", "/nick <new nick>", R.string.cmd_nick),
    IrcCommand("away", "/away [message]", R.string.cmd_away),
    IrcCommand("whois", "/whois <nick>", R.string.cmd_whois),
    IrcCommand("who", "/who <mask>", R.string.cmd_who),
    IrcCommand("ignore", "/ignore [nick]", R.string.cmd_ignore),
    IrcCommand("unignore", "/unignore <nick>", R.string.cmd_unignore),
    IrcCommand("quit", "/quit [reason]", R.string.cmd_quit),

    // Moderation
    IrcCommand("kick", "/kick <nick> [reason]", R.string.cmd_kick),
    IrcCommand("ban", "/ban <nick|mask> [n|u|h|d|a]", R.string.cmd_ban),
    IrcCommand("unban", "/unban <nick|mask>", R.string.cmd_unban),
    IrcCommand("kb", "/kb <nick> [n|u|h|d|a] [reason]", R.string.cmd_kb),
    IrcCommand("kickban", "/kickban <nick> [type] [reason]", R.string.cmd_kickban),
    IrcCommand("mute", "/mute <nick|mask> [n|u|h|d|a]", R.string.cmd_mute),
    IrcCommand("quiet", "/quiet <nick|mask> [type]", R.string.cmd_quiet),
    IrcCommand("unmute", "/unmute <nick|mask>", R.string.cmd_unmute),
    IrcCommand("unquiet", "/unquiet <nick|mask>", R.string.cmd_unquiet),
    IrcCommand("op", "/op <nick> [channel]", R.string.cmd_op),
    IrcCommand("deop", "/deop <nick> [channel]", R.string.cmd_deop),
    IrcCommand("voice", "/voice <nick> [channel]", R.string.cmd_voice),
    IrcCommand("devoice", "/devoice <nick> [channel]", R.string.cmd_devoice),
    IrcCommand("mode", "/mode [target] <modes>", R.string.cmd_mode),

    // Mode lists
    IrcCommand("banlist", "/banlist", R.string.cmd_banlist),
    IrcCommand("quietlist", "/quietlist", R.string.cmd_quietlist),
    IrcCommand("exceptlist", "/exceptlist", R.string.cmd_exceptlist),
    IrcCommand("invexlist", "/invexlist", R.string.cmd_invexlist),

    // CTCP
    IrcCommand("ctcp", "/ctcp <nick> <command>", R.string.cmd_ctcp),
    IrcCommand("ping", "/ping <nick>", R.string.cmd_ping),
    IrcCommand("ctcpping", "/ctcpping <nick>", R.string.cmd_ctcpping),
    IrcCommand("version", "/version [nick]", R.string.cmd_version),
    IrcCommand("time", "/time [server]", R.string.cmd_time),
    IrcCommand("finger", "/finger <nick>", R.string.cmd_finger),
    IrcCommand("userinfo", "/userinfo <nick>", R.string.cmd_userinfo),
    IrcCommand("clientinfo", "/clientinfo <nick>", R.string.cmd_clientinfo),

    // Server queries
    IrcCommand("motd", "/motd [server]", R.string.cmd_motd),
    IrcCommand("admin", "/admin [server]", R.string.cmd_admin),
    IrcCommand("info", "/info [server]", R.string.cmd_info),
    IrcCommand("dns", "/dns <host|ip>", R.string.cmd_dns),

    // DCC
    IrcCommand("dcc", "/dcc chat <nick>", R.string.cmd_dcc),

    // IRC operator
    IrcCommand("oper", "/oper <user> <password>", R.string.cmd_oper),
    IrcCommand("sajoin", "/sajoin <nick> <channel>", R.string.cmd_sajoin),
    IrcCommand("sapart", "/sapart <nick> [channel]", R.string.cmd_sapart),
    IrcCommand("kill", "/kill <nick> [reason]", R.string.cmd_kill),
    IrcCommand("kline", "/kline <mask> <duration> [reason]", R.string.cmd_kline),
    IrcCommand("gline", "/gline <mask> <duration> [reason]", R.string.cmd_gline),
    IrcCommand("zline", "/zline <ip> <duration> [reason]", R.string.cmd_zline),
    IrcCommand("dline", "/dline <ip> <duration> [reason]", R.string.cmd_dline),
    IrcCommand("eline", "/eline <mask> <duration> [reason]", R.string.cmd_eline),
    IrcCommand("qline", "/qline <mask> <duration> [reason]", R.string.cmd_qline),
    IrcCommand("shun", "/shun <mask> <duration> [reason]", R.string.cmd_shun),
    IrcCommand("wallops", "/wallops <message>", R.string.cmd_wallops),
    IrcCommand("globops", "/globops <message>", R.string.cmd_globops),
    IrcCommand("locops", "/locops <message>", R.string.cmd_locops),
    IrcCommand("operwall", "/operwall <message>", R.string.cmd_operwall),

    // Misc
    IrcCommand("alias", "/alias [list | add <name> <expansion> | remove <name>]", R.string.cmd_alias),
    IrcCommand("slap", "/slap <nick>", R.string.cmd_slap),
    IrcCommand("raw", "/raw <command>", R.string.cmd_raw),
    IrcCommand("quote", "/quote <command>", R.string.cmd_quote),
    IrcCommand("sysinfo", "/sysinfo", R.string.cmd_sysinfo),

    // Query / services shorthands
    IrcCommand("query", "/query <nick> [message]", R.string.cmd_query),
    IrcCommand("ns", "/ns <command>", R.string.cmd_ns),
    IrcCommand("cs", "/cs <command>", R.string.cmd_cs),
    IrcCommand("as", "/as <command>", R.string.cmd_as),
    IrcCommand("hs", "/hs <command>", R.string.cmd_hs),
    IrcCommand("ms", "/ms <command>", R.string.cmd_ms),
    IrcCommand("bs", "/bs <command>", R.string.cmd_bs),

    // Bouncer shortcuts
    IrcCommand("znc", "/znc <command>", R.string.cmd_znc),
    IrcCommand("bouncerserv", "/bouncerserv <command>", R.string.cmd_bouncerserv),
    IrcCommand("bnc", "/bnc <command>", R.string.cmd_bnc),

    // IRCv3
    IrcCommand("setname", "/setname <realname>", R.string.cmd_setname),
    IrcCommand("markread", "/markread [target] [timestamp]", R.string.cmd_markread),
    IrcCommand("monitor", "/monitor +nick | -nick | C | L | S", R.string.cmd_monitor),
    IrcCommand("register", "/register [account] [email] <password>", R.string.cmd_register),
    IrcCommand("verify", "/verify [account] <code>", R.string.cmd_verify),
    IrcCommand("metadata", "/metadata [target] <sub> | <key> [value]", R.string.cmd_metadata),
    IrcCommand("redact", "/redact [target] <msgid> [reason]", R.string.cmd_redact),
)

/**
 * Subcommand hints shown after the user types a command that takes a well-known
 * verb as its first argument — services aliases (/ns IDENTIFY, /cs ACCESS, …),
 * ZNC's *status module (/znc ListNetworks), soju's BouncerServ (/bouncerserv
 * network status). The second-word hint fires when the user has typed the parent
 * command plus a space, and narrows as they type the sub-verb.
 *
 * Keys are the parent command's short name (case-insensitive match against the
 * IRC_COMMANDS name). /bnc and /bouncerserv share the soju set since /bnc is
 * just an alias.
 *
 * Lists are intentionally curated, not every verb every services bot or module
 * supports, but the ones users actually reach for. Full reference lives at:
	 X3
 *   NickServ/ChanServ: network docs (Atheme/Anope command set, stable across forks)
 *   ZNC *status:       https://wiki.znc.in/Using_commands
 *   BouncerServ:       https://soju.im/doc/soju.1.html  (commands under "SERVICE COMMANDS")
 *
 * Sub-verbs are stored in their canonical display casing so we can render them
 * directly in the chip; matching against [query] is case-insensitive.
 */
private val SUB_COMMANDS: Map<String, List<IrcCommand>> = mapOf(
    // NickServ — Atheme/Anope-compatible verbs
    "ns" to listOf(
        IrcCommand("IDENTIFY", "IDENTIFY [account] <password>", R.string.cmd_ns_identify),
        IrcCommand("LOGIN", "LOGIN [account] <password>", R.string.cmd_ns_login),
        IrcCommand("REGISTER", "REGISTER <password> <email>", R.string.cmd_ns_register),
        IrcCommand("GHOST", "GHOST <nick> [password]", R.string.cmd_ns_ghost),
        IrcCommand("RECOVER", "RECOVER <nick> [password]", R.string.cmd_ns_recover),
        IrcCommand("RELEASE", "RELEASE <nick> [password]", R.string.cmd_ns_release),
        IrcCommand("GROUP", "GROUP <nick> <password>", R.string.cmd_ns_group),
        IrcCommand("UNGROUP", "UNGROUP <nick>", R.string.cmd_ns_ungroup),
        IrcCommand("SET", "SET <option> <value>", R.string.cmd_ns_set),
        IrcCommand("CERT", "CERT [ADD|DEL|LIST] [fingerprint]", R.string.cmd_ns_cert),
        IrcCommand("INFO", "INFO [nick]", R.string.cmd_ns_info),
        IrcCommand("LIST", "LIST <pattern>", R.string.cmd_ns_list),
        IrcCommand("DROP", "DROP <nick> [password]", R.string.cmd_ns_drop),
        IrcCommand("LOGOUT", "LOGOUT", R.string.cmd_ns_logout),
        IrcCommand("HELP", "HELP [command]", R.string.cmd_ns_help),
    ),
    // ChanServ — Atheme/Anope-compatible verbs
    "cs" to listOf(
        IrcCommand("REGISTER", "REGISTER <channel>", R.string.cmd_cs_register),
        IrcCommand("DROP", "DROP <channel>", R.string.cmd_cs_drop),
        IrcCommand("INFO", "INFO <channel>", R.string.cmd_cs_info),
        IrcCommand("ACCESS", "ACCESS <channel> [LIST|ADD|DEL] [mask] [level]", R.string.cmd_cs_access),
        IrcCommand("FLAGS", "FLAGS <channel> [target] [flags]", R.string.cmd_cs_flags),
        IrcCommand("OP", "OP <channel> [nick]", R.string.cmd_cs_op),
        IrcCommand("DEOP", "DEOP <channel> [nick]", R.string.cmd_cs_deop),
        IrcCommand("VOICE", "VOICE <channel> [nick]", R.string.cmd_cs_voice),
        IrcCommand("DEVOICE", "DEVOICE <channel> [nick]", R.string.cmd_cs_devoice),
        IrcCommand("HALFOP", "HALFOP <channel> [nick]", R.string.cmd_cs_halfop),
        IrcCommand("PROTECT", "PROTECT <channel> [nick]", R.string.cmd_cs_protect),
        IrcCommand("OWNER", "OWNER <channel> [nick]", R.string.cmd_cs_owner),
        IrcCommand("INVITE", "INVITE <channel> [nick]", R.string.cmd_cs_invite),
        IrcCommand("UNBAN", "UNBAN <channel> [nick]", R.string.cmd_cs_unban),
        IrcCommand("KICK", "KICK <channel> <nick> [reason]", R.string.cmd_cs_kick),
        IrcCommand("BAN", "BAN <channel> <mask>", R.string.cmd_cs_ban),
        IrcCommand("QUIET", "QUIET <channel> <mask>", R.string.cmd_cs_quiet),
        IrcCommand("TOPIC", "TOPIC <channel> [topic]", R.string.cmd_cs_topic),
        IrcCommand("CLEAR", "CLEAR <channel> <what>", R.string.cmd_cs_clear),
        IrcCommand("RECOVER", "RECOVER <channel>", R.string.cmd_cs_recover),
        IrcCommand("SET", "SET <channel> <option> <value>", R.string.cmd_cs_set),
        IrcCommand("HELP", "HELP [command]", R.string.cmd_cs_help),
    ),
    // MemoServ — same verbs on Atheme/Anope
    "ms" to listOf(
        IrcCommand("SEND", "SEND <nick> <message>", R.string.cmd_ms_send),
        IrcCommand("LIST", "LIST", R.string.cmd_ms_list),
        IrcCommand("READ", "READ <number>", R.string.cmd_ms_read),
        IrcCommand("DELETE", "DELETE <number|ALL>", R.string.cmd_ms_delete),
        IrcCommand("FORWARD", "FORWARD <number> <nick>", R.string.cmd_ms_forward),
        IrcCommand("IGNORE", "IGNORE [ADD|DEL|LIST] [mask]", R.string.cmd_ms_ignore),
        IrcCommand("SET", "SET <option> <value>", R.string.cmd_ms_set),
        IrcCommand("HELP", "HELP [command]", R.string.cmd_ms_help),
    ),
    // HostServ
    "hs" to listOf(
        IrcCommand("REQUEST", "REQUEST <vhost>", R.string.cmd_hs_request),
        IrcCommand("ON", "ON", R.string.cmd_hs_on),
        IrcCommand("OFF", "OFF", R.string.cmd_hs_off),
        IrcCommand("TAKE", "TAKE <vhost>", R.string.cmd_hs_take),
        IrcCommand("DROP", "DROP", R.string.cmd_hs_drop),
        IrcCommand("INFO", "INFO [nick]", R.string.cmd_hs_info),
        IrcCommand("HELP", "HELP [command]", R.string.cmd_hs_help),
    ),
    // BotServ — Anope; Atheme has a similar but narrower set
    "bs" to listOf(
        IrcCommand("ASSIGN", "ASSIGN <channel> <bot>", R.string.cmd_bs_assign),
        IrcCommand("UNASSIGN", "UNASSIGN <channel>", R.string.cmd_bs_unassign),
        IrcCommand("BOTLIST", "BOTLIST", R.string.cmd_bs_botlist),
        IrcCommand("INFO", "INFO <channel|bot>", R.string.cmd_bs_info),
        IrcCommand("KICK", "KICK <channel> <option> [args]", R.string.cmd_bs_kick),
        IrcCommand("SET", "SET <channel> <option> <value>", R.string.cmd_bs_set),
        IrcCommand("SAY", "SAY <channel> <text>", R.string.cmd_bs_say),
        IrcCommand("ACT", "ACT <channel> <action>", R.string.cmd_bs_act),
        IrcCommand("HELP", "HELP [command]", R.string.cmd_bs_help),
    ),
    // AuthServ/X3
    "as" to listOf(
        IrcCommand("AUTH", "AUTH <account> <password>", R.string.cmd_as_auth),
        IrcCommand("REGISTER", "REGISTER <account> <password> <email>", R.string.cmd_as_register),
        IrcCommand("HELP", "HELP [command]", R.string.cmd_as_help),
    ),
    "x3" to listOf(
        IrcCommand("REGISTER", "REGISTER <channel>", R.string.cmd_as_register_2),
        IrcCommand("DROP", "DROP <channel>", R.string.cmd_as_drop),
        IrcCommand("INFO", "INFO <channel|user>", R.string.cmd_as_info),
        IrcCommand("OP", "OP <channel> <user>", R.string.cmd_as_op),
        IrcCommand("DEOP", "DEOP <channel> <user>", R.string.cmd_as_deop),
        IrcCommand("VOICE", "VOICE <channel> <user>", R.string.cmd_as_voice),
        IrcCommand("DEVOICE", "DEVOICE <channel> <user>", R.string.cmd_as_devoice),
        IrcCommand("HALFOP", "HALFOP <channel> <user>", R.string.cmd_as_halfop),
        IrcCommand("INVITE", "INVITE <channel> [user]", R.string.cmd_as_invite),
        IrcCommand("KICK", "KICK <channel> <user> [reason]", R.string.cmd_as_kick),
        IrcCommand("BAN", "BAN <channel> <mask>", R.string.cmd_as_ban),
        IrcCommand("UNBAN", "UNBAN <channel> [mask]", R.string.cmd_as_unban),
        IrcCommand("TOPIC", "TOPIC <channel> [topic]", R.string.cmd_as_topic),
        IrcCommand("FLAGS", "FLAGS <channel> <user> [flags]", R.string.cmd_as_flags),
        IrcCommand("ACCESS", "ACCESS <channel> [LIST|ADD|DEL] [user] [level]", R.string.cmd_as_access),
        IrcCommand("HELP", "HELP [command]", R.string.cmd_as_help),
    ),
    // ZNC *status — curated from https://wiki.znc.in/Using_commands
    "znc" to listOf(
        IrcCommand("Help", "Help [filter]", R.string.cmd_znc_help),
        IrcCommand("Version", "Version", R.string.cmd_znc_version),
        IrcCommand("ListNetworks", "ListNetworks", R.string.cmd_znc_listnetworks),
        IrcCommand("JumpNetwork", "JumpNetwork <network>", R.string.cmd_znc_jumpnetwork),
        IrcCommand("AddNetwork", "AddNetwork <name>", R.string.cmd_znc_addnetwork),
        IrcCommand("DelNetwork", "DelNetwork <name>", R.string.cmd_znc_delnetwork),
        IrcCommand("ListServers", "ListServers", R.string.cmd_znc_listservers),
        IrcCommand("AddServer", "AddServer <host> [+port] [pass]", R.string.cmd_znc_addserver),
        IrcCommand("DelServer", "DelServer <host> [port] [pass]", R.string.cmd_znc_delserver),
        IrcCommand("Connect", "Connect", R.string.cmd_znc_connect),
        IrcCommand("Disconnect", "Disconnect [message]", R.string.cmd_znc_disconnect),
        IrcCommand("Jump", "Jump [server]", R.string.cmd_znc_jump),
        IrcCommand("ListChans", "ListChans", R.string.cmd_znc_listchans),
        IrcCommand("ListClients", "ListClients", R.string.cmd_znc_listclients),
        IrcCommand("ListMods", "ListMods", R.string.cmd_znc_listmods),
        IrcCommand("ListAvailMods", "ListAvailMods", R.string.cmd_znc_listavailmods),
        IrcCommand("LoadMod", "LoadMod <module> [args]", R.string.cmd_znc_loadmod),
        IrcCommand("UnloadMod", "UnloadMod <module>", R.string.cmd_znc_unloadmod),
        IrcCommand("ReloadMod", "ReloadMod <module> [args]", R.string.cmd_znc_reloadmod),
        IrcCommand("Attach", "Attach <#chan>", R.string.cmd_znc_attach),
        IrcCommand("Detach", "Detach <#chan>", R.string.cmd_znc_detach),
        IrcCommand("PlayBuffer", "PlayBuffer <#chan>", R.string.cmd_znc_playbuffer),
        IrcCommand("ClearBuffer", "ClearBuffer <#chan>", R.string.cmd_znc_clearbuffer),
        IrcCommand("ClearAllChannelBuffers", "ClearAllChannelBuffers", R.string.cmd_znc_clearallchannelbuffers),
        IrcCommand("ClearAllQueryBuffers", "ClearAllQueryBuffers", R.string.cmd_znc_clearallquerybuffers),
        IrcCommand("SetBuffer", "SetBuffer <#chan> [lines]", R.string.cmd_znc_setbuffer),
        IrcCommand("Topics", "Topics", R.string.cmd_znc_topics),
        IrcCommand("Uptime", "Uptime", R.string.cmd_znc_uptime),
        IrcCommand("Traffic", "Traffic", R.string.cmd_znc_traffic),
        IrcCommand("Rehash", "Rehash", R.string.cmd_znc_rehash),
        IrcCommand("SaveConfig", "SaveConfig", R.string.cmd_znc_saveconfig),
        IrcCommand("ShowMOTD", "ShowMOTD", R.string.cmd_znc_showmotd),
    ),
    // soju BouncerServ — curated from soju(1) manpage; commands parsed as shell tokens
    "bouncerserv" to listOf(
        IrcCommand("help", "help [command]", R.string.cmd_soju_help),
        IrcCommand("network create", "network create -addr <uri> -name <name> [-nick <nick>] [-pass <pw>]", R.string.cmd_soju_network_create),
        IrcCommand("network update", "network update <name> [options]", R.string.cmd_soju_network_update),
        IrcCommand("network delete", "network delete <name>", R.string.cmd_soju_network_delete),
        IrcCommand("network status", "network status", R.string.cmd_soju_network_status),
        IrcCommand("network quote", "network quote <name> <raw line>", R.string.cmd_soju_network_quote),
        IrcCommand("channel status", "channel status [-network <name>]", R.string.cmd_soju_channel_status),
        IrcCommand("channel update", "channel update <name> [options]", R.string.cmd_soju_channel_update),
        IrcCommand("channel delete", "channel delete <name>", R.string.cmd_soju_channel_delete),
        IrcCommand("certfp generate", "certfp generate [-network <name>]", R.string.cmd_soju_certfp_generate),
        IrcCommand("certfp fingerprint", "certfp fingerprint [-network <name>]", R.string.cmd_soju_certfp_fingerprint),
        IrcCommand("sasl status", "sasl status [-network <name>]", R.string.cmd_soju_sasl_status),
        IrcCommand("sasl set-plain", "sasl set-plain [-network <name>] <user> <pass>", R.string.cmd_soju_sasl_set_plain),
        IrcCommand("sasl reset", "sasl reset [-network <name>]", R.string.cmd_soju_sasl_reset),
        IrcCommand("user update", "user update [options]", R.string.cmd_soju_user_update),
        IrcCommand("server status", "server status", R.string.cmd_soju_server_status),
    ),
)

/**
 * Parent commands that share a subcommand set. /bnc delegates to the soju
 * BouncerServ set because /bnc is just an alias for /bouncerserv.
 */
private val SUB_COMMAND_ALIASES: Map<String, String> = mapOf(
    "bnc" to "bouncerserv",
)

/**
 * For each parent command, the maximum number of spaces that can appear in any
 * of its sub-verb names. Used by the query detector to decide when the user has
 * typed past the sub-verb into its own arguments: if the user's partial sub-verb
 * already contains more spaces than the longest sub-verb for this parent, the
 * hint bar hides itself because no further match is possible.
 *
 * Cached at class load — SUB_COMMANDS is a static map, so the values never change.
 */
private val SUB_COMMAND_MAX_SPACES: Map<String, Int> =
    SUB_COMMANDS.mapValues { (_, verbs) ->
        verbs.maxOfOrNull { it.name.count { c -> c == ' ' } } ?: 0
    }

/**
 * Resolve the subcommand list for [parentCmd], following aliases. Returns null
 * if the parent command has no subcommand hints configured.
 */
private fun subCommandsFor(parentCmd: String): List<IrcCommand>? {
    val key = parentCmd.lowercase()
    val resolved = SUB_COMMAND_ALIASES[key] ?: key
    return SUB_COMMANDS[resolved]
}

/**
 * Maximum number of spaces permitted in a partial sub-verb for [parentCmd]
 * before we give up and hide the bar. Returns 0 when the parent has no entry.
 */
private fun subCommandMaxSpaces(parentCmd: String): Int {
    val key = parentCmd.lowercase()
    val resolved = SUB_COMMAND_ALIASES[key] ?: key
    return SUB_COMMAND_MAX_SPACES[resolved] ?: 0
}

/**
 * Command-completion bar shown above the input field when the user starts /typing
 *
 *   ┌───────────────────────────────────────────────────────────────┐
 *   │  /close  /closekey  /cycle  /ctcp   ....						 |
 *   ├───────────────────────────────────────────────────────────────┤
 *   │  /close                   Close the current buffer            │
 *   └───────────────────────────────────────────────────────────────┘
 *
 * Tapping a tab completes the command name (+ trailing space) into the input field.
 */
@Composable
private fun CommandHints(
    query: String,           // text after the leading '/' - must be non-empty
    scriptCommands: List<IrcCommand> = emptyList(), // user-facing commands from loaded .hex scripts
    onPick: (String) -> Unit // called with "/command " ready to type args
) {
    val matches = remember(query, scriptCommands) {
        (IRC_COMMANDS + scriptCommands).filter { it.name.startsWith(query, ignoreCase = true) }
    }

    // Track which chip the user has highlighted (defaults to first match)
    var highlighted by remember(matches) { mutableStateOf(matches.firstOrNull()) }

    AnimatedVisibility(
        visible = matches.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit  = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // Tabs
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(matches.distinctBy { it.name }, key = { it.name }) { cmd ->
                        val isHighlighted = highlighted?.name == cmd.name
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isHighlighted)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.focusHighlight(RoundedCornerShape(50)).clickable {
                                highlighted = cmd
                                onPick("/${cmd.name} ")
                            }
                        ) {
                            Text(
                                text = "/${cmd.name}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                color = if (isHighlighted)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                // Detail row for the highlighted command
                highlighted?.let { cmd ->
                    HorizontalDivider(thickness = 0.5.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight(RoundedCornerShape(8.dp))
                            .clickable { onPick("/${cmd.name} ") }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Usage signature (args portion after the command name)
                        val argsText = cmd.usage.removePrefix("/${cmd.name}").trim()
                        Text(
                            text = "/${cmd.name}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (argsText.isNotEmpty()) {
                            Text(
                                text = argsText,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        Text(
                            text = cmd.descriptionRes?.let { stringResource(it) } ?: cmd.description.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Subcommand-completion bar. Fires after the user has typed a parent command that
 * has a curated sub-verb list — /ns, /cs, /ms, /hs, /bs, /as, /znc, /bouncerserv, /bnc —
 * followed by a space and (optionally) a prefix of the sub-verb.
 *
 * Tapping a chip replaces the input with "/parent subverb " so the cursor lands
 * ready for the sub-verb's own arguments. The parent command and any prefix the
 * user typed are both resolved; typing "/ns id" narrows to IDENTIFY, and tapping
 * it produces "/ns IDENTIFY ".
 *
 * A separate composable from [CommandHints] rather than a generalised one: the
 * chip label, detail rendering, and onPick behaviour all differ in small but
 * non-parametric ways (sub-verbs render bare, not with a leading /; the detail
 * row shows the parent-command prefix; onPick preserves the parent). Shared
 * styling via Material3 tokens keeps the two visually consistent.
 */
@Composable
private fun SubCommandHints(
    parentCmd: String,          // e.g. "ns", "znc", "bouncerserv" — must have a SUB_COMMANDS entry
    query: String,              // text typed after the first space (may be empty)
    onPick: (String) -> Unit    // called with "/parent subverb " ready for args
) {
    val subCmds = remember(parentCmd) { subCommandsFor(parentCmd) ?: emptyList() }
    val matches = remember(subCmds, query) {
        if (query.isEmpty()) subCmds
        else subCmds.filter { it.name.startsWith(query, ignoreCase = true) }
    }

    // Track which chip the user has highlighted (defaults to first match)
    var highlighted by remember(matches) { mutableStateOf(matches.firstOrNull()) }

    AnimatedVisibility(
        visible = matches.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit  = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // Chip row
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(matches.distinctBy { it.name }, key = { it.name }) { cmd ->
                        val isHighlighted = highlighted?.name == cmd.name
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isHighlighted)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.focusHighlight(RoundedCornerShape(50)).clickable {
                                highlighted = cmd
                                onPick("/$parentCmd ${cmd.name} ")
                            }
                        ) {
                            Text(
                                // Bare sub-verb — no leading slash — since it's
                                // rendered as a second-token suggestion.
                                text = cmd.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                color = if (isHighlighted)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                // Detail row for the highlighted sub-verb
                highlighted?.let { cmd ->
                    HorizontalDivider(thickness = 0.5.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight()
                            .clickable { onPick("/$parentCmd ${cmd.name} ") }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Show "/parent SUBVERB" as the label so the user sees
                        // the whole command they'd send.
                        Text(
                            text = "/$parentCmd ${cmd.name}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        // Args portion of the usage string, stripped of the
                        // sub-verb prefix so it reads naturally.
                        val argsText = cmd.usage.removePrefix(cmd.name).trim()
                        if (argsText.isNotEmpty()) {
                            Text(
                                text = argsText,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        Text(
                            text = cmd.descriptionRes?.let { stringResource(it) } ?: cmd.description.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Nick-mention completion bar shown above the input field when the user types @prefix
 * (or just a word prefix in a channel that matches a nick in the nicklist).
 *
 * Trigger: user types "@" followed by ≥1 characters in a channel buffer.
 * On tap, replaces the @prefix token at the cursor with "@nick " (or "nick: " if at start).
 *
 * Layout mirrors CommandHints for a consistent look.
 */
@Composable
private fun NickHints(
    prefix: String,          // characters typed after "@" - must be non-empty
    nicks: List<String>,     // full nicklist for this buffer (may include mode prefixes like @/+)
    inputText: String,       // current raw input text (to decide "nick: " vs "@nick ")
    onPick: (String) -> Unit // called with the replacement text (already stripped of @-prefix)
) {
    // Strip mode-prefix characters for matching; preserve original for display.
    fun base(n: String) = n.trimStart('~', '&', '@', '%', '+')

    val matches = remember(prefix, nicks) {
        nicks.filter { base(it).startsWith(prefix, ignoreCase = true) }
             .sortedWith(compareBy { base(it).lowercase() })
             .take(16)
    }

    var highlighted by remember(matches) { mutableStateOf(matches.firstOrNull()) }

    AnimatedVisibility(
        visible = matches.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit  = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(matches.distinct(), key = { it }) { nick ->
                        val isHighlighted = highlighted == nick
                        val baseNickText = base(nick)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isHighlighted)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.focusHighlight().clickable {
                                highlighted = nick
                                // "nick: " if cursor is at start of blank input, "nick " otherwise
                                val completion = if (inputText.trimStart().startsWith("@$prefix", ignoreCase = true) &&
                                                     inputText.trimStart().length <= prefix.length + 1)
                                    "$baseNickText: "
                                else
                                    "$baseNickText "
                                onPick(completion)
                            }
                        ) {
                            Text(
                                text = nick,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                color = if (isHighlighted)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                highlighted?.let { nick ->
                    HorizontalDivider(thickness = 0.5.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight()
                            .clickable {
                                val baseNickText = base(nick)
                                val completion = if (inputText.trimStart().startsWith("@$prefix", ignoreCase = true) &&
                                                     inputText.trimStart().length <= prefix.length + 1)
                                    "$baseNickText: "
                                else
                                    "$baseNickText "
                                onPick(completion)
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "@${base(nick)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.nick_hint_tap_to_mention),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}


private sealed class SidebarItem(val stableKey: String) {
    data class Header(val netId: String, val title: String, val expanded: Boolean = true) : SidebarItem("h:$netId")
    data class Buffer(
        val key: String,
        val label: String,
        val indent: Dp,
        val isNetworkHeader: Boolean = false,
        val netId: String? = null,
        val expanded: Boolean = true,
    ) : SidebarItem("b:$key")
    data class DividerItem(val netId: String) : SidebarItem("d:$netId")
}


/**
 * A tab-completion in progress, so a second Tab cycles to the next match rather than
 * completing the word the first one produced.
 *
 * [text] and [cursor] are what the last completion left in the field: the cycle is only
 * continued when the field still holds exactly that, so any edit in between starts over.
 */
private data class NickCycle(
    val start: Int,
    val hadAt: Boolean,
    val matches: List<String>,
    val index: Int,
    val text: String,
    val cursor: Int,
)

/** Drag handle for sidebar network rows. Long-press to start drag.
 *  onDrag receives the CUMULATIVE y offset from the drag start position. */
@Composable
private fun SidebarDragHandle(
    onStart: () -> Unit,
    onDrag: (totalOffsetY: Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit = onEnd,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
) {
    // The gesture block is set up once and then outlives every recomposition, so it
    // reads the callbacks through state rather than capturing the first ones it saw,
    // which would leave a drag acting on the network order as it was at first draw.
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val cancel by rememberUpdatedState(onCancel)
    Box(
        modifier = Modifier
            .size(24.dp)
            .dpadReorder(onMoveUp = onMoveUp, onMoveDown = onMoveDown)
            .pointerInput(Unit) {
                var accumulated = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { accumulated = 0f; start() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumulated += dragAmount.y
                        drag(accumulated)
                    },
                    onDragEnd = { end() },
                    onDragCancel = { cancel() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.DragHandle,
            contentDescription = stringResource(R.string.chat_drag_reorder),
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

/**
 * Small quoted preview shown above a message that carries a +reply tag.
 *
 * Resolves the parent via [msgStrToDisplayIdx] (O(1)) rather than scanning
 * the message list on every recomposition.  [msgIdToText] carries the
 * (from, text) pair for each known msgId so the label can be rendered without
 * a second lookup.  Both maps are built once per displayItems change.
 *
 * [canScroll] is true when [msgStrToDisplayIdx] contains the parent's msgId,
 * meaning it is currently visible in the buffer window and the user can tap
 * to jump to it.  When false (parent outside window), the quote is shown as
 * a non-tappable placeholder so threading intent is still visible.
 */
@Composable
private fun ReplyQuote(
    replyToMsgId: String,
    msgIdToText: Map<String, Pair<String?, String>>,
    canScroll: Boolean,
    onTap: () -> Unit,
) {
    val entry = msgIdToText[replyToMsgId]  // O(1)
    val label = when {
        entry == null          -> "↩ (original message not in window)"
        entry.first != null    -> "↩ ${entry.first}: ${stripIrcFormatting(entry.second).take(80)}"
        else                   -> "↩ ${stripIrcFormatting(entry.second).take(80)}"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .focusHighlight(RoundedCornerShape(4.dp))
            .clickable(enabled = canScroll, onClick = onTap)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
/**
 * Top-of-scrollback row offering the next page of stored history.
 *
 * Shown only for buffers whose server has chathistory and hasn't reported itself out of
 * older messages.
 */
@Composable
private fun LoadOlderHistoryRow(
    loading: Boolean,
    onLoad: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.chat_history_loading),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TextButton(
                onClick = onLoad,
                modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
            ) {
                Text(
                    text = stringResource(R.string.chat_history_load_older),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * Horizontal divider shown above the first unread message in a buffer.
 * Driven by the server's draft/read-marker or soju.im/read timestamp.
 * Also integrated into the app.
 */
@Composable
private fun UnreadSeparator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            thickness = 1.dp
        )
        Text(
            text = stringResource(R.string.chat_unread_divider),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            thickness = 1.dp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: UiState,
    onSelectBuffer: (String) -> Unit,
    onSend: (String) -> Unit,
    /** Send a threaded reply to [msgId]. Falls back to quote-prefix on servers without draft/reply. */
    onSendReply: (networkId: String, buffer: String, text: String, from: String, originalText: String, msgId: String?) -> Unit = { _, _, _, _, _, _ -> },
    onSendReaction: (msgId: String, emoji: String, remove: Boolean) -> Unit = { _, _, _ -> },
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onExit: () -> Unit,
    onToggleBufferList: () -> Unit,
    onToggleNickList: () -> Unit,
    onToggleChannelsOnly: () -> Unit,
    onWhois: (String) -> Unit,
    onIgnoreNick: (String, String) -> Unit,
    onUnignoreNick: (String, String) -> Unit,
    onSetDccAutoAccept: (String, String, Boolean) -> Unit = { _, _, _ -> },
    /** Mute/unmute highlight & PM notifications from a nick (adds/removes a bare-nick entry in highlightIgnoreMasks). */
    onIgnoreNotifications: (String, String) -> Unit,
    onUnignoreNotifications: (String, String) -> Unit,
    onRefreshNicklist: () -> Unit,
    /** Silent MODE refresh for the ops drawer; does not echo numerics to any buffer. */
    onRefreshChannelModes: () -> Unit,
    /** Called when user taps "DCC Send File" in nick actions. Opens file picker then calls /dcc send. */
    onDccSendFile: ((targetNick: String) -> Unit)? = null,
    /** Called when user taps "DCC Chat" in nick actions. */
    onDccChat: ((targetNick: String) -> Unit)? = null,
    onOpenList: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScripts: () -> Unit = {},
    scriptLaunchers: List<Pair<String, String>> = emptyList(),
    onRunLauncher: (command: String) -> Unit = {},
    onOpenNetworks: () -> Unit,
    onOpenTransfers: () -> Unit,
    onSysInfo: () -> Unit,
    onAbout: () -> Unit,
    onUpdateSettings: (UiSettings.() -> UiSettings) -> Unit,
    onReorderNetworks: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onToggleNetworkExpanded: (netId: String) -> Unit = {},
    /** Called on every input text change so the ViewModel can send draft/typing TAGMSGs. */
    onTypingChanged: (String) -> Unit = {},
    /**
     * Called when the user has read up to the latest message in a buffer (at bottom or buffer switch).
     * The ViewModel forwards this as MARKREAD / READ to the server when the cap is active.
     */
    onMarkRead: (bufferKey: String) -> Unit = {},
    onHighlightConsumed: () -> Unit = {},
    onCloseFindOverlay: () -> Unit = {},
    onFindNavigate: (Int) -> Unit = {},
    onShareTextConsumed: () -> Unit = {},
    /** Buffer-list toolbar actions. */
    onCollapseAllNetworks: () -> Unit = {},
    onMarkAllBuffersRead: () -> Unit = {},
    onSearchFromToolbar: (query: String, global: Boolean) -> Unit = { _, _ -> },
    /** Fetch the page of messages older than the top of [bufferKey]'s scrollback. */
    onLoadOlderHistory: (bufferKey: String) -> Unit = {},
    /** Unsent composer text and caret for [bufferKey], restored when the buffer is selected. */
    draftFor: (bufferKey: String) -> com.boxlabs.hexdroid.data.Draft =
        { com.boxlabs.hexdroid.data.Draft.EMPTY },
    /** Park the composer's current text and caret against [bufferKey]. Blank text discards the draft. */
    onDraftChanged: (bufferKey: String, text: String, cursor: Int) -> Unit = { _, _, _ -> },
    /**
     * Optional reference to the ViewModel for features that need a richer API than
     * fits in a callback list — currently the EncryptionDialog, which exposes
     * generate/import/clear/snapshot calls. Nullable so previews and tests can
     * construct ChatScreen without an IrcViewModel; the encryption menu entry is
     * hidden when this is null.
     */
    viewModel: com.boxlabs.hexdroid.IrcViewModel? = null,
    tourActive: Boolean = false,
    tourTarget: TourTarget? = null,
) {
    val scope = rememberCoroutineScope()
    val viewConfiguration = LocalViewConfiguration.current
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val cfg = LocalConfiguration.current
    val isWide = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE || cfg.screenWidthDp >= 840

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val tourWantsBuffers =
        tourActive && (tourTarget == TourTarget.CHAT_BUFFER_DRAWER || tourTarget == TourTarget.CHAT_DRAWER_BUTTON)

    LaunchedEffect(tourWantsBuffers, tourActive, isWide) {
        if (!tourActive) return@LaunchedEffect
        if (!isWide) {
            if (tourWantsBuffers) drawerState.open() else drawerState.close()
        }
    }

    fun splitKey(key: String): Pair<String, String> {
        val idx = key.indexOf("::")
        return if (idx <= 0) ("unknown" to key) else (key.take(idx) to key.drop(idx + 2))
    }

    fun baseNick(display: String): String = display.trimStart('~', '&', '@', '%', '+')

    fun nickPrefix(display: String): Char? =
        display.firstOrNull()?.takeIf { it in listOf('~', '&', '@', '%', '+') }

    fun netName(netId: String): String =
        state.networks.firstOrNull { it.id == netId }?.name ?: netId

    data class NetBuffers(val serverKey: String, val others: List<String>)

    // Keyed on the *set of buffer keys* (not the full buffers map) so this only recomputes
    // when buffers are added or removed — not on every incoming message. Unread/highlight
    // counts are read directly in BufferRow/sidebarItems below, not cached here.
    val bufferKeySet = state.buffers.keys
    val buffersByNet = remember(bufferKeySet, state.channelsOnly) {
        val groups = mutableMapOf<String, MutableList<String>>()
        for (k in bufferKeySet) {
            val idx = k.indexOf("::")
            if (idx <= 0) continue
            val netId = k.take(idx)
            groups.getOrPut(netId) { mutableListOf() }.add(k)
        }

        groups.mapValues { (netId, keys) ->
            val serverKey = "$netId::*server*"
            val others = keys.asSequence()
                .filter { it != serverKey }
                .filter { key ->
                    val (_, name) = splitKey(key)
                    when {
                        state.channelsOnly -> name.startsWith("#") || name.startsWith("&")
                        else -> true
                    }
                }
                .sortedBy { splitKey(it).second.lowercase() }
                .toList()

            NetBuffers(serverKey, others)
        }
    }

    @Composable
    fun BufferRow(
        key: String,
        label: String,
        selected: String,
        meta: com.boxlabs.hexdroid.UiBuffer?,
        indent: Dp,
        closable: Boolean,
        onClose: () -> Unit,
        lagLabel: String? = null,
        lagProgress: Float? = null,
        networkIconUrl: String? = null,
    ) {
        val unread = meta?.unread ?: 0
        val hi = meta?.highlights ?: 0

        Column(
            Modifier
                .fillMaxWidth()
                .focusHighlight()
                .clickable {
                    scope.launch { if (!isWide) drawerState.close() }
                    onSelectBuffer(key)
                }
                .padding(start = indent, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (networkIconUrl != null) {
                    var iconBmp by remember(networkIconUrl) { mutableStateOf(RemoteImage.cached(networkIconUrl)) }
                    LaunchedEffect(networkIconUrl) { if (iconBmp == null) iconBmp = RemoteImage.fetch(networkIconUrl) }
                    iconBmp?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(18.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }
                Text(
                    text = label,
                    fontWeight = if (key == selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!lagLabel.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        lagLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (hi > 0) {
                    Spacer(Modifier.width(8.dp))
                    Badge(containerColor = MaterialTheme.colorScheme.error) { Text("$hi") }
                } else if (unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Badge { Text("$unread") }
                }

                if (closable) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "✕",
                        modifier = Modifier
                            .focusHighlight(RoundedCornerShape(50))
                            .clickable { onClose() }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (lagProgress != null) {
                LagBar(progress = lagProgress, modifier = Modifier.fillMaxWidth(), height = 4.dp)
            }
        }
    }

    val selected = state.selectedBuffer
    val (selNetId, selBufName) = splitKey(selected)
    val selNetName = netName(selNetId)

    val buf = state.buffers[selected]
    val messages = buf?.messages ?: emptyList()
    val topic = buf?.topic
    val typingNicks = if (state.settings.receiveTypingIndicator) buf?.typingNicks.orEmpty() else emptySet()

    // Separator position. Advances with each message on the active buffer; cleared by the scroll-to-bottom button.
    val firstUnreadIndex = remember(messages, buf?.lastReadTimestamp, buf?.unread) {
        val unread = buf?.unread ?: 0
        val lastReadTs = buf?.lastReadTimestamp
        if (lastReadTs != null) {
            val lastReadMs = runCatching {
                java.time.Instant.parse(lastReadTs).toEpochMilli()
            }.getOrNull() ?: return@remember if (unread > 0 && messages.size >= unread) messages.size - unread else -1
            val idx = messages.indexOfFirst { it.timeMs > lastReadMs }
            if (idx >= 0 && idx < messages.size) {
                idx
            } else if (unread > 0 && messages.size >= unread) {
                // Timestamp search found no messages newer than the read marker, but the
                // bouncer still reports unread messages.
                messages.size - unread
            } else {
                -1
            }
        } else {
            if (unread <= 0 || messages.size < unread) -1
            else messages.size - unread
        }
    }

    var input by remember(selected) {
        val restored = selected?.let { draftFor(it) } ?: com.boxlabs.hexdroid.data.Draft.EMPTY
        mutableStateOf(
            TextFieldValue(
                restored.text,
                TextRange(restored.cursor.coerceIn(0, restored.text.length)),
            )
        )
    }

    // Keyed on the selected buffer because the composer state above is too
    LaunchedEffect(selected) {
        val key = selected ?: return@LaunchedEffect
        snapshotFlow { input }
            .collect { onDraftChanged(key, it.text, it.selection.start) }
    }
    /** Non-null when the user has tapped Reply on a message: cleared after send or cancel. */
    var pendingReply by remember(selected) { mutableStateOf<UiMessage?>(null) }
    var inputHasFocus by remember { mutableStateOf(false) }
    val inputFocus = remember { FocusRequester() }

    // A physical keyboard is attached (Chromebooks, tablets with a keyboard).
    // Typing then goes to the message field wherever focus happens to be.
    val hardwareKeyboard = cfg.keyboard == Configuration.KEYBOARD_QWERTY

    // Pre-fill input with text shared from another app via the system share sheet.
    LaunchedEffect(state.pendingShareText) {
        val shared = state.pendingShareText ?: return@LaunchedEffect
        input = TextFieldValue(shared, androidx.compose.ui.text.TextRange(shared.length))
        onShareTextConsumed()
    }

    val inputHistory = remember(selected) { mutableListOf<String>() }
    var historyIndex by remember(selected) { mutableStateOf(-1) }
    var inputSnapshot by remember(selected) { mutableStateOf("") }

    var showColorPicker by rememberSaveable { mutableStateOf(false) }
    var selectedFgColor by rememberSaveable { mutableStateOf<Int?>(null) }   // 0-15 or null
    var selectedBgColor by rememberSaveable { mutableStateOf<Int?>(null) }   // 0-15 or null
    var boldActive by rememberSaveable { mutableStateOf(false) }
    var italicActive by rememberSaveable { mutableStateOf(false) }
    var underlineActive by rememberSaveable { mutableStateOf(false) }
    var reverseActive by rememberSaveable { mutableStateOf(false) }


    val timeFmt = remember(state.settings.timestampFormat) {
        try {
            SimpleDateFormat(state.settings.timestampFormat, Locale.getDefault())
        } catch (_: Throwable) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        }
    }

    val isChannel = selBufName.startsWith("#") || selBufName.startsWith("&")

    // "Harden" the nicklist: whenever the nicklist becomes visible, ask the server for a fresh
    // snapshot (throttled in the ViewModel to avoid spamming).
    LaunchedEffect(isWide, state.showNickList, state.selectedBuffer, isChannel) {
        if (isWide && state.showNickList && isChannel) {
            onRefreshNicklist()
        }
    }
    val nicklist = state.nicklists[selected].orEmpty()

    // Map base nick -> display nick (including any mode prefix like @/+/%/&/~)
    // Used for rendering message prefixes like <@User>.
    val nickDisplayByBase = remember(nicklist) {
        nicklist
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .associateBy(
                keySelector = { baseNick(it).lowercase() },
                valueTransform = { it }
            )
    }

    fun displayNick(nick: String): String {
        if (!isChannel) return nick
        return nickDisplayByBase[nick.lowercase()] ?: nick
    }

    val myNick = state.connections[selNetId]?.myNick ?: state.myNick
    val myDisplay = nicklist.firstOrNull { baseNick(it).equals(myNick, ignoreCase = true) }
    val myPrefix = myDisplay?.let { nickPrefix(it) }
    val canKick  = isChannel && myPrefix in listOf('~', '&', '@', '%')
    val canBan   = isChannel && myPrefix in listOf('~', '&', '@')
    val canTopic = isChannel && myPrefix in listOf('~', '&', '@', '%')
    val canMode  = isChannel && myPrefix in listOf('~', '&', '@')
    val isIrcOper = state.connections[selNetId]?.isIrcOper == true
    val hasReactionSupport = state.connections[selNetId]?.hasReactionSupport == true
    // draft/metadata-2 display names for this network, keyed by lowercased nick.
    val metadataDisplayNames = state.connections[selNetId]?.displayNames ?: emptyMap()
    // draft/metadata-2 avatars, same keying. Rendered in the nick list only, and only
    // when the user has opted into image previews AND this profile is unproxied - an
    // avatar URL is chosen by the remote user, so fetching it from a Tor/SOCKS profile
    // would bypass the proxy and leak the user's IP (same rule as filehost uploads).
    val metadataAvatars: Map<String, String> =
        if (state.settings.imagePreviewsEnabled &&
            state.networks.firstOrNull { it.id == selNetId }?.proxyType ==
                com.boxlabs.hexdroid.connection.ProxyType.NONE
        ) state.connections[selNetId]?.avatarUrls ?: emptyMap()
        else emptyMap()
    // draft/metadata-2 nick colours (6 hex digits, no #), keyed by lowercased nick.
    // Applied below a manual own-nick override, above the hash colour.
    val metadataColors = state.connections[selNetId]?.nickColors ?: emptyMap()
    // draft/metadata-2 status text, keyed by lowercased nick. Shown as secondary
    // text in the nick list.
    val metadataStatuses = state.connections[selNetId]?.statuses ?: emptyMap()
    // Casefolded nicks currently marked away (from away-notify / WHOX), used to dim
    // their rows in the member list.
    val awayNicks = state.connections[selNetId]?.awayNicks ?: emptySet()
    // Lowercased nicks known to be bots (BOT mode letter in WHO/WHOX, or a bot tag),
    // badged in the member list and nick sheet.
    val botNicks = state.connections[selNetId]?.botNicks ?: emptySet()

    // ICON / draft/ICON ISUPPORT token: the per-network icon URL, gated the same way
    // as avatars - only over https, only with image previews enabled, and only on an
    // unproxied profile (an icon URL fetch would otherwise bypass a SOCKS/Tor proxy and
    // leak the user's IP). Returns null when any gate fails, hiding the icon.
    fun networkIconOf(netId: String): String? {
        val raw = state.connections[netId]?.networkIconUrl ?: return null
        // Some servers advertise a {size} template; substitute a concrete pixel size
        // so the URL is fetchable (a literal {size} would 404).
        val url = raw.replace("{size}", "64")
        if (!url.startsWith("https://")) return null
        if (!state.settings.imagePreviewsEnabled) return null
        val unproxied = state.networks.firstOrNull { it.id == netId }?.proxyType ==
            com.boxlabs.hexdroid.connection.ProxyType.NONE
        return if (unproxied) url else null
    }
    val currentModeString = if (isChannel) state.buffers[selected]?.modeString else null

    val bgLum = MaterialTheme.colorScheme.background.luminance()

    // Collect all own nicks across every connected network so the custom colour
    // fires for your nick regardless of which buffer is active.
    val myNickBase = baseNick(myNick).lowercase()
    val allMyNicks = remember(state.connections) {
        state.connections.values
            .mapTo(mutableSetOf()) { baseNick(it.myNick).lowercase() }
            .also { it.add(myNickBase) }  // include the global fallback nick too
    }

    // Sorted base nicks used by nicklistColorMap inside NicklistContent and by
    // the adjacency-nudge logic. Nick colours in messages use a stable hash so
    // they never shift when someone joins or leaves.
    val sortedBaseNicks = remember(nicklist) {
        nicklist
            .map { baseNick(it).lowercase() }
            .filter { it.isNotBlank() }
            .sorted()
    }

    // draft/metadata-2 `color`: 6 hex digits, already validated at ingest. Parsed to
    // an opaque Color, or null if somehow unparseable (belt-and-braces). A colour too
    // close to the theme background (e.g. #000000 on a dark theme) is rejected so the
    // nick doesn't vanish; the caller then falls back to the readable hash colour.
    fun metadataNickColor(base: String): Color? {
        val hex = metadataColors[base] ?: return null
        val c = runCatching { Color("ff$hex".toLong(16)) }.getOrNull() ?: return null
        val lum = c.luminance()
        val lighter = maxOf(lum, bgLum)
        val darker = minOf(lum, bgLum)
        // WCAG-style contrast ratio; below ~2.5 the nick is hard to read on this theme.
        val contrast = (lighter + 0.05f) / (darker + 0.05f)
        return if (contrast < 2.5f) null else c
    }

    fun nickColor(nick: String): Color {
        if (!state.settings.colorizeNicks) return Color.Unspecified
        val base = baseNick(nick).lowercase()
        val custom = state.settings.ownNickColorInt
        if (custom != null && base in allMyNicks) return Color(custom)
        // A user's self-chosen colour (via metadata) wins over the hash, but never
        // over your own manual override for your own nick, handled above.
        metadataNickColor(base)?.let { return it }
        // Stable hash-based colour for messages — never changes regardless of who
        // else is in the channel. The nicklist panel uses nicklistColorMap
        // (pre-computed with adjacency nudge) instead.
        return NickColors.colorForNick(base, bgLum)
    }

    var showNickSheet by remember { mutableStateOf(false) }

    var showNickActions by remember { mutableStateOf(false) }
    /** Nick awaiting confirmation before DCC auto-accept is switched on; null when idle. */
    var confirmAutoAcceptFor by remember { mutableStateOf<String?>(null) }
    var selectedNick by remember { mutableStateOf("") }
    /** Message long-pressed: shown in a small context sheet with Copy / Reply options. */
    var longPressedMessage by remember { mutableStateOf<UiMessage?>(null) }

    /** When true, message rows show checkboxes for multi-message copy selection. */
    var copyRangeMode by remember { mutableStateOf(false) }
    /** IDs of messages checked in copy-range mode. */
    var selectedMsgIds by remember { mutableStateOf(emptySet<Long>()) }
    // Ids of long messages the user has expanded. Hoisted rather than held inside the row
    // so the choice survives the row being recycled out of the LazyColumn and back.
    var expandedMsgIds by remember { mutableStateOf(emptySet<Long>()) }

    var showChanOps by remember { mutableStateOf(false) }
    var showIrcOpTools by remember { mutableStateOf(false) }
    var showChanListSheet by remember { mutableStateOf(false) }
    var chanListTab by remember { mutableIntStateOf(0) } // 0=bans,1=quiets,2=excepts,3=invex
    var opsNick by remember { mutableStateOf("") }
    var opsReason by remember { mutableStateOf("") }
    var opsTopic by remember(selected, topic) { mutableStateOf(topic ?: "") }
    var showTopicQuickEdit by remember { mutableStateOf(false) }
    /**
     * Whether the EncryptionDialog is currently open. Owned at this scope so the
     * dropdown menu item can flip it, and so the dialog auto-closes when the user
     * switches buffer (the `remember(selected)` resets it to false).
     */
    var showEncryptionDialog by remember(selected) { mutableStateOf(false) }
    // Metadata editor and guided account-registration dialogs. Keyed to the selected
    // network so switching networks dismisses a stale dialog.
    var showMetadataEditor by remember(selNetId) { mutableStateOf(false) }
    var showRegistration by remember(selNetId) { mutableStateOf(false) }
    var topicExpanded by remember(selected, topic) { mutableStateOf(false) }
    var topicHasOverflow by remember(selected, topic) { mutableStateOf(false) }

    var overflowExpanded by remember { mutableStateOf(false) }
    var launcherExpanded by remember { mutableStateOf(false) }

    // Tour: on the "More actions" step, open the overflow menu so users can see what's inside.
    LaunchedEffect(tourActive, tourTarget) {
        if (!tourActive) return@LaunchedEffect
        overflowExpanded = (tourTarget == TourTarget.CHAT_OVERFLOW_BUTTON)
    }

    // Nick list default settings should only apply in landscape (split pane).
    // In portrait we show the nick list as a temporary bottom sheet when the user taps the icon.
    LaunchedEffect(isWide, selected, isChannel) {
        if (isWide || !isChannel) showNickSheet = false
    }

    fun sendNow() {
        val t = input.text.trim()
        if (t.isEmpty()) return

        // Build IRC formatting prefix based on active formatting state
        // Strip any leading IRC formatting bytes from the raw input for command detection.
        // This mirrors the ViewModel's strippedForCommandCheck so we agree on what's a command.
        val isCommand = t.trimStart('\u0002', '\u0003', '\u000f', '\u0016', '\u001d', '\u001e', '\u001f')
            .replace(Regex("^\u0003\\d{0,2}(?:,\\d{0,2})?"), "")
            .startsWith("/")

        val formattedText = if (isCommand) {
            // Commands: never prepend formatting — the slash must be the first character
            // so the ViewModel can detect and parse it correctly without stripping.
            t
        } else buildString {
            if (boldActive) append("\u0002")
            if (italicActive) append("\u001D")
            if (underlineActive) append("\u001F")
            if (reverseActive) append("\u0016")

            if (selectedFgColor != null) {
                append("\u0003")
                append(selectedFgColor.toString().padStart(2, '0'))
                if (selectedBgColor != null) {
                    append(",")
                    append(selectedBgColor.toString().padStart(2, '0'))
                }
            }

            append(t)
        }

        if (inputHistory.lastOrNull() != t) inputHistory.add(t)
        if (inputHistory.size > 50) inputHistory.removeAt(0)
        historyIndex = -1
        inputSnapshot = ""

        val reply = pendingReply
        input = TextFieldValue("")

        if (reply != null && !isCommand) {
            // Route through reply path so the ViewModel can attach draft/reply tag if supported.
            // Only clear pendingReply once it has actually been consumed, so a slash command
            // typed while a reply is pending doesn't silently discard the reply context.
            pendingReply = null
            onSendReply(
                selNetId,
                selBufName,
                formattedText,
                reply.from ?: "",
                stripIrcFormatting(reply.text).take(100),
                reply.msgId,
            )
        } else {
            // Clear reply context only when there is none pending, or when the user typed
            // a plain message (not a command with a pending reply still active).
            if (reply == null || !isCommand) pendingReply = null
            onSend(formattedText)
        }
    }

    fun openNickActions(nickDisplay: String) {
        selectedNick = baseNick(nickDisplay)
        showNickActions = true
    }

    fun mention(nick: String) {
        val newText = if (input.text.isBlank()) "$nick: " else input.text + " $nick"
        input = TextFieldValue(newText, TextRange(newText.length))
    }

	@Composable
	fun BufferDrawer(mod: Modifier = Modifier) {
		// Sidebar list plus its drag-to-reorder engine.
		val listState = rememberLazyListState()
		val reorder = rememberGroupReorderState(listState) { key ->
			val k = key as? String
			when {
				k == null -> null
				k.startsWith("h:") -> k.removePrefix("h:")
				k.startsWith("d:") -> k.removePrefix("d:")
				k.startsWith("b:") -> splitKey(k.removePrefix("b:")).first
				else -> null
			}
		}

		// While a drag is in progress the drawer follows this order instead of the stored
		// one, so channels travel with their network. null means use the sort order.
		val dragNetworkOrder = reorder.previewOrder

		// Network of the buffer currently open, so it's never hidden and the tab strip can
		// follow what the user is viewing.
		val openNetId = remember(selected) { splitKey(selected).first }

		// The networks shown in the drawer, after applying the sort order, any live drag
		// reorder, and the per-network "Show in channel switcher" flag. Shared by tree and tabs.
		val visibleNets = remember(state.networks, openNetId, dragNetworkOrder) {
			val naturalOrder = state.networks
				.sortedWith(compareBy({ !it.isFavourite }, { it.sortOrder }, { it.name }))
			val sortedNets = if (dragNetworkOrder != null) {
				val map = naturalOrder.associateBy { it.id }
				dragNetworkOrder.mapNotNull { map[it] } +
					naturalOrder.filter { it.id !in dragNetworkOrder }
			} else naturalOrder
			// Show networks the user keeps in the switcher, plus whichever network holds the
			// buffer you're currently viewing (so opening a hidden network never loses your place).
			sortedNets.filter { it.showInSidebar || it.id == openNetId }
		}

		val sidebarItems = remember(visibleNets, buffersByNet, state.channelsOnly, selected, state.collapsedNetworkIds) {
			val out = mutableListOf<SidebarItem>()
			for (net in visibleNets) {
				val nId = net.id
				val header = net.name
				val grouped = buffersByNet[nId]
				val serverKey = grouped?.serverKey ?: "$nId::*server*"
				val otherKeys = grouped?.others ?: emptyList()

				// A network is expanded unless its id is in the collapsed set.
				// Empty set (default) = all expanded, matching HexChat behaviour.
				val expanded = nId !in state.collapsedNetworkIds

				if (state.channelsOnly) {
					out.add(SidebarItem.Header(nId, header, expanded))
				} else {
					// Use the server buffer row as the network "header" to avoid showing the network name twice.
					out.add(SidebarItem.Buffer(serverKey, header, 0.dp, isNetworkHeader = true, netId = nId, expanded = expanded))
				}
				if (expanded) {
					for (k in otherKeys) {
						val (_, name) = splitKey(k)
						out.add(SidebarItem.Buffer(k, name, 14.dp))
					}
				}
				out.add(SidebarItem.DividerItem(nId))
			}
			out
		}

		val lagInfoByNet = remember(state.networks, state.connections) {
			state.networks.associate { net ->
				val con = state.connections[net.id]
				val lagMs = con?.lagMs
				val lagS = if (lagMs == null) null else (lagMs / 1000f)
				val label = when {
					con == null -> "—"
					con.connecting -> "connecting"
					!con.connected -> "disconnected"
					lagS == null -> "…"
					else -> String.format(Locale.getDefault(), "%.1fs", lagS)
				}
				val progress = when {
					lagMs == null -> 0f
					else -> (lagMs / 10_000f).coerceIn(0f, 1f)
				}
				net.id to (label to progress)
			}
		}

		Column(
			mod.padding(horizontal = 16.dp, vertical = 8.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp)
		) {
			// Sidepanel toolbar: collapse-all, mark-all-read, search-current-buffer.
			// HexDroid logo sits absolute-left in the same toolbar row.
			//
			// The control Row is anchored to CenterEnd, NOT to the Box's default
			// contentAlignment = Center. Reason: when this drawer renders in landscape
			// on a small phone, the buffer pane can drag down to a 130 dp min width,
			// which after the 16 dp horizontal padding on each side leaves only ~98 dp
			// of inner space. A Row of three 28 dp IconButtons + 4 dp spacings is ~92 dp
			// wide; centering it in 98 dp puts its LEFT edge at ~3 dp, which is INSIDE
			// the logo's 0-24 dp footprint at CenterStart. Anchoring the Row to
			// CenterEnd keeps the controls glued to the right edge regardless of
			// drawer width, so the gap between logo and controls only shrinks (and
			// eventually disappears) but they never overlap.
			var showSearchDialog by remember { mutableStateOf(false) }
			Box(
				modifier = Modifier.fillMaxWidth(),
				contentAlignment = Alignment.Center
			) {
				Image(
					painter = painterResource(R.drawable.hexdroid_logo),
					contentDescription = null,
					modifier = Modifier
						.size(24.dp)
						.align(Alignment.CenterStart)
				)
				Row(
					modifier = Modifier.align(Alignment.CenterEnd),
					horizontalArrangement = Arrangement.spacedBy(4.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					IconButton(
						onClick = onCollapseAllNetworks,
						modifier = Modifier.size(28.dp).focusHighlight(CircleShape)
					) {
						Icon(
							imageVector = Icons.Default.UnfoldLess,
							contentDescription = stringResource(R.string.buffer_toolbar_collapse_all),
							modifier = Modifier.size(16.dp),
							tint = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
					IconButton(
						onClick = onMarkAllBuffersRead,
						modifier = Modifier.size(28.dp).focusHighlight(CircleShape)
					) {
						Icon(
							imageVector = Icons.Default.MarkChatRead,
							contentDescription = stringResource(R.string.buffer_toolbar_mark_all_read),
							modifier = Modifier.size(16.dp),
							tint = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
					IconButton(
						onClick = { showSearchDialog = true },
						modifier = Modifier.size(28.dp).focusHighlight(CircleShape)
					) {
						Icon(
							imageVector = Icons.Default.Search,
							contentDescription = stringResource(R.string.buffer_toolbar_search),
							modifier = Modifier.size(16.dp),
							tint = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}
			}
			HorizontalDivider(modifier = Modifier.padding(top = 2.dp), thickness = 0.5.dp)

			if (showSearchDialog) {
				var query by remember { mutableStateOf("") }
				// Default to global search
				var globalSearch by remember { mutableStateOf(true) }
				androidx.compose.material3.AlertDialog(
					onDismissRequest = { showSearchDialog = false },
					title = { Text(stringResource(R.string.buffer_toolbar_search_dialog_title)) },
					text = {
						Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
							OutlinedTextField(
								value = query,
								onValueChange = { query = it },
								placeholder = { Text(stringResource(R.string.buffer_toolbar_search_placeholder)) },
								singleLine = true,
								modifier = Modifier.fillMaxWidth()
							)
							Row(verticalAlignment = Alignment.CenterVertically) {
								androidx.compose.material3.Checkbox(
									checked = globalSearch,
									onCheckedChange = { globalSearch = it }
								)
								Text(
									stringResource(R.string.buffer_toolbar_search_global),
									style = MaterialTheme.typography.bodySmall
								)
							}
						}
					},
					confirmButton = {
						TextButton(
							enabled = query.isNotBlank(),
							onClick = {
								onSearchFromToolbar(query, globalSearch)
								showSearchDialog = false
								// Close the drawer so the search-result navigation lands the
								// user directly on the matched message in the chat view rather
								// than leaving the sidebar open obscuring it. Mirrors what
								// happens when the user picks a buffer from the sidebar.
								scope.launch { if (!isWide) drawerState.close() }
							}, modifier = Modifier.tvInitialFocus().focusHighlight(RoundedCornerShape(50))
						) { Text(stringResource(R.string.buffer_toolbar_search_action)) }
					},
					dismissButton = {
						TextButton(onClick = { showSearchDialog = false }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.cancel)) }
					}
				)
			}

			// Current display order of root netIds - kept in sync with sidebarItems
			val netOrder = remember(sidebarItems) {
				sidebarItems.mapNotNull { item ->
					when {
						item is SidebarItem.Header -> item.netId
						item is SidebarItem.Buffer && item.isNetworkHeader -> item.netId
						else -> null
					}
				}
			}
			// Stored order of every network, including the ones hidden from the drawer.
			val fullNetIds = remember(state.networks) {
				state.networks
					.sortedWith(compareBy({ !it.isFavourite }, { it.sortOrder }, { it.name }))
					.map { it.id }
			}
			val favouriteById = remember(state.networks) {
				state.networks.associate { it.id to it.isFavourite }
			}
			// The gesture handler outlives a recomposition, so it reads these rather than
			// capturing the lists and acting on a stale order.
			val currentNetOrder by rememberUpdatedState(netOrder)
			val currentFullNetIds by rememberUpdatedState(fullNetIds)
			val currentFavourites by rememberUpdatedState(favouriteById)

			// Saves the order the network was dropped into.
			val commitNetDrag: (String) -> Unit = { movedId ->
				val dropped = reorder.end()
				if (dropped != null) {
					reorderIndices(currentFullNetIds, dropped, movedId)?.let { (from, to) ->
						onReorderNetworks(from, to)
					}
				}
			}

			// One position up or down, for the D-pad
			val stepNet: (String, Int) -> Unit = { movedId, delta ->
				val order = currentNetOrder
				val idx = order.indexOf(movedId)
				val target = idx + delta
				if (idx >= 0 && target in order.indices &&
					currentFavourites[movedId] == currentFavourites[order[target]]
				) {
					val moved = order.toMutableList().also { it.add(target, it.removeAt(idx)) }
					reorderIndices(currentFullNetIds, moved, movedId)?.let { (from, to) ->
						onReorderNetworks(from, to)
					}
				}
			}

			// Keep the dropped order on screen until the saved order matches it, so the
			// drawer does not flick back to the old positions while the save is in flight.
			LaunchedEffect(fullNetIds, reorder.previewOrder, reorder.draggedId) {
				if (reorder.draggedId != null) return@LaunchedEffect
				val preview = reorder.previewOrder ?: return@LaunchedEffect
				val settled = fullNetIds.filter { it in preview } == preview.filter { it in fullNetIds }
				if (settled) {
					reorder.clearPreview()
				} else {
					delay(1000)
					reorder.clearPreview()
				}
			}

			if (state.settings.networkTabs) {
				// Tabs mode: a horizontal channel switcher.
				// Avoids a long vertical tree that scrolls poorly, and pairs naturally with the
				// hide-disconnected filter above.
				if (visibleNets.isNotEmpty()) {
					// The active tab follows the open buffer's network (remember re-seeds when
					// openNetId changes); tapping another tab browses it without forcing a switch.
					var pickedNet by remember(openNetId) { mutableStateOf(openNetId) }
					val activeNet = if (visibleNets.any { it.id == pickedNet }) pickedNet
						else visibleNets.first().id
					val selIdx = visibleNets.indexOfFirst { it.id == activeNet }.coerceIn(0, visibleNets.lastIndex)

					// key() on the visible-network set: same ScrollableTabRow count-change guard as the bottom
					// bar, a shrinking tab list can leave the cached selected index one past the new list during
					// layout and crash. A fresh identity per set discards that stale state.
					key(visibleNets.map { it.id }) {
					SecondaryScrollableTabRow(
						selectedTabIndex = selIdx,
						edgePadding = 0.dp,
						containerColor = Color.Transparent,
					) {
						visibleNets.forEachIndexed { i, net ->
							val con = state.connections[net.id]
							val dot = when {
								con?.connected == true  -> MaterialTheme.colorScheme.primary
								con?.connecting == true -> Color(0xFFE0A030)
								else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
							}
							val grouped = buffersByNet[net.id]
							val keys = listOfNotNull(grouped?.serverKey) + (grouped?.others ?: emptyList())
							var unread = 0; var hi = 0
							for (k in keys) { val b = state.buffers[k] ?: continue; unread += b.unread; hi += b.highlights }
							Tab(
								selected = i == selIdx,
								onClick = { pickedNet = net.id },
								modifier = Modifier.focusHighlight(),
								text = {
									Row(
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.spacedBy(6.dp)
									) {
										Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
										networkIconOf(net.id)?.let { iconUrl ->
											var tbmp by remember(iconUrl) { mutableStateOf(RemoteImage.cached(iconUrl)) }
											LaunchedEffect(iconUrl) { if (tbmp == null) tbmp = RemoteImage.fetch(iconUrl) }
											tbmp?.let {
												Image(
													bitmap = it,
													contentDescription = null,
													contentScale = ContentScale.Crop,
													modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)),
												)
											}
										}
										Text(net.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
										if (unread > 0) {
											Badge(
												containerColor = if (hi > 0) MaterialTheme.colorScheme.error
												                 else MaterialTheme.colorScheme.secondary
											) { Text(if (unread > 99) "99+" else "$unread") }
										}
									}
								},
							)
						}
					}
					}

					val grouped = buffersByNet[activeNet]
					val serverKey = grouped?.serverKey ?: "$activeNet::*server*"
					val otherKeys = grouped?.others ?: emptyList()
					val netName = visibleNets.first { it.id == activeNet }.name
					val tabLag = lagInfoByNet[activeNet]

					LazyColumn(
						state = listState,
						modifier = Modifier
							.fillMaxSize()
							.tourTarget(TourTarget.CHAT_BUFFER_DRAWER),
						contentPadding = PaddingValues(vertical = 6.dp)
					) {
						item(key = "tabsrv:$serverKey") {
							BufferRow(
								key = serverKey, label = netName, selected = selected,
								meta = state.buffers[serverKey], indent = 0.dp,
								closable = false, onClose = {},
								lagLabel = tabLag?.first, lagProgress = tabLag?.second,
							)
						}
						items(otherKeys, key = { "tabbuf:$it" }) { k ->
							val (_, nm) = splitKey(k)
							BufferRow(
								key = k, label = nm, selected = selected,
								meta = state.buffers[k], indent = 14.dp,
								closable = true, onClose = { onSend("/closekey $k") },
							)
						}
					}
				}
			} else {
			LazyColumn(
				state = listState,
				modifier = Modifier
					.fillMaxSize()
					.tourTarget(TourTarget.CHAT_BUFFER_DRAWER),
				contentPadding = PaddingValues(vertical = 6.dp)
			) {
				items(sidebarItems.distinctBy { it.stableKey }, key = { it.stableKey }) { item ->
					// Derive root netId directly from item properties - no index lookup needed
					val rootNetId: String? = when {
						item is SidebarItem.Header -> item.netId
						item is SidebarItem.Buffer && item.isNetworkHeader -> item.netId
						else -> null
					}
					val isRoot    = rootNetId != null
					// The whole group travels with the finger, not just its header row.
					val itemNetId: String? = when (item) {
						is SidebarItem.Header -> item.netId
						is SidebarItem.Buffer -> item.netId ?: splitKey(item.key).first
						is SidebarItem.DividerItem -> item.netId
					}
					val isDragging = reorder.isDragging(itemNetId)

					Box(modifier = Modifier
						// The dragged group is placed by the finger, so it opts out of the
						// item animation the rows it passes use to slide aside.
						.then(if (isDragging) Modifier else Modifier.animateItem())
						.graphicsLayer { translationY = if (isDragging) reorder.translation else 0f }
						.then(
							if (isDragging)
								Modifier
									.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
									.zIndex(1f)
							else Modifier
						)) {
						when (item) {
							is SidebarItem.Header -> {
								val (lagLabel, lagProgress) = lagInfoByNet[item.netId] ?: ("—" to 0f)
								Column(
									Modifier.padding(start = 6.dp, top = 12.dp, bottom = 8.dp)
								) {
									Row(
										Modifier
											.fillMaxWidth()
											.focusHighlight()
											.clickable { onToggleNetworkExpanded(item.netId) },
										verticalAlignment = Alignment.CenterVertically
									) {
										Icon(
											imageVector = if (item.expanded) Icons.Default.KeyboardArrowDown
														  else Icons.AutoMirrored.Filled.KeyboardArrowRight,
											contentDescription = if (item.expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
											modifier = Modifier.size(16.dp),
											tint = MaterialTheme.colorScheme.onSurfaceVariant
										)
										Text(
											item.title,
											fontWeight = FontWeight.Bold,
											modifier = Modifier.weight(1f)
										)
										Text(
											lagLabel,
											style = MaterialTheme.typography.bodySmall,
											color = MaterialTheme.colorScheme.onSurfaceVariant
										)
										if (rootNetId != null) {
											SidebarDragHandle(
												onStart = { reorder.start(rootNetId, currentNetOrder) },
												onDrag = { dy ->
													reorder.drag(dy) { movedId, otherId ->
														currentFavourites[movedId] == currentFavourites[otherId]
													}
												},
												onEnd = { commitNetDrag(rootNetId) },
												onCancel = { reorder.cancel() },
												onMoveUp = { stepNet(rootNetId, -1) },
												onMoveDown = { stepNet(rootNetId, 1) },
											)
										}
									}
									LagBar(
										progress = lagProgress,
										modifier = Modifier.fillMaxWidth(),
										height = 5.dp
									)
								}
							}
							is SidebarItem.Buffer -> {
								val (netId, name) = splitKey(item.key)
								val closable = name != "*server*"
								val lag = if (name == "*server*") lagInfoByNet[netId] else null
								Row(verticalAlignment = Alignment.CenterVertically) {
									// Chevron for network header rows (server buffer acting as header)
									if (item.isNetworkHeader && item.netId != null) {
										Icon(
											imageVector = if (item.expanded) Icons.Default.KeyboardArrowDown
														  else Icons.AutoMirrored.Filled.KeyboardArrowRight,
											contentDescription = if (item.expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
											modifier = Modifier
												.size(16.dp)
												.focusHighlight()
												.clickable { onToggleNetworkExpanded(item.netId) },
											tint = MaterialTheme.colorScheme.onSurfaceVariant
										)
									}
									Box(modifier = Modifier.weight(1f)) {
										// Roll the whole network's unread + highlights up into the header while collapsed. When expanded,
										// each child shows its own badge, so the header stays the server buffer's own count.
										val rowMeta = if (item.isNetworkHeader && item.netId != null && !item.expanded) {
											val serverBuf = state.buffers[item.key]
											val childKeys = buffersByNet[item.netId]?.others ?: emptyList()
											var uSum = serverBuf?.unread ?: 0
											var hSum = serverBuf?.highlights ?: 0
											for (ck in childKeys) {
												val cb = state.buffers[ck] ?: continue
												uSum += cb.unread
												hSum += cb.highlights
											}
											(serverBuf ?: state.buffers[item.key])?.copy(unread = uSum, highlights = hSum)
										} else state.buffers[item.key]
										BufferRow(
											key = item.key,
											label = item.label,
											selected = selected,
											meta = rowMeta,
											indent = item.indent,
											closable = closable,
											onClose = { onSend("/closekey ${item.key}") },
											lagLabel = lag?.first,
											lagProgress = lag?.second,
											networkIconUrl = if (item.isNetworkHeader && item.netId != null)
												networkIconOf(item.netId) else null,
										)
									}
									if (isRoot) {
										SidebarDragHandle(
											onStart = { reorder.start(rootNetId, currentNetOrder) },
											onDrag = { dy ->
												reorder.drag(dy) { movedId, otherId ->
													currentFavourites[movedId] == currentFavourites[otherId]
												}
											},
											onEnd = { commitNetDrag(rootNetId) },
											onCancel = { reorder.cancel() },
											onMoveUp = { stepNet(rootNetId, -1) },
											onMoveDown = { stepNet(rootNetId, 1) },
										)
									}
								}
							}
							is SidebarItem.DividerItem -> {
								HorizontalDivider(Modifier.padding(top = 12.dp))
							}
						}
					}
				}
			}
			}
		}
	}

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun NicklistContent(mod: Modifier = Modifier, nickPaneDp: Dp = Dp.Unspecified) {
        // Linear ramp from 10 sp at the narrowest pane (70 dp) to 20 sp at the widest (280 dp)
        //   70 dp  → 10.0 sp (portrait narrowest)
        //  110 dp  → 11.9 sp (landscape min)
        //  180 dp  → 15.2 sp
        //  280 dp  → 20.0 sp (landscape max)
        // The user offset shifts the whole ramp rather than replacing it, so widening
        // the pane still enlarges the text at any offset.
        val nickFontOffset = state.settings.nicklistFontOffset.coerceIn(-3, 4).toFloat()
        val nickFontSp = (
            if (nickPaneDp == Dp.Unspecified) 14f
            else (10f + (nickPaneDp.value - 70f) * (10f / 210f)).coerceIn(10f, 20f)
        ).plus(nickFontOffset).coerceAtLeast(8f)
        Column(mod.padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(
                text = pluralStringResource(R.plurals.chat_nicklist_users, nicklist.size, nicklist.size),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = (nickFontSp - 2f).coerceAtLeast(8f).sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            HorizontalDivider()
            // Pre-computed sidebar colours with adjacency-conflict nudge.
            // Declared here (inside NicklistContent) so it captures sortedBaseNicks
            // and bgLum from the outer composable scope without needing to be passed
            // as a parameter. Rebuilds only when the nicklist or theme changes.
            val nicklistColorMap = remember(sortedBaseNicks, bgLum) {
                val colors = NickColors.nicklistColors(sortedBaseNicks, bgLum)
                sortedBaseNicks.zip(colors).toMap<String, Color>()
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(nicklist) { n ->
                    val cleaned = baseNick(n)
                    val nickDisplayName = metadataDisplayNames[cleaned.lowercase()]
                        ?.takeIf { !it.equals(cleaned, ignoreCase = true) }
                    val isAway = awayNicks.contains(cleaned.lowercase())
                    val isBot = botNicks.contains(cleaned.lowercase())
                    val nickAvatar = metadataAvatars[cleaned.lowercase()]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight()
                            // Away users get a dimmed background tint so they read as
                            // "present but not at the keyboard" without hiding them.
                            .background(
                                if (isAway) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                else Color.Transparent
                            )
                            .combinedClickable(
                                onClick = { openNickActions(n) },
                                onLongClick = { openNickActions(n) },
                            )
                            // Scales with the nick font. A fixed 2.dp looked proportionally
                            // huge once the font shrank to its 10sp minimum.
                            .padding(vertical = (nickFontSp * 0.08f).dp)
                    ) {
                        // draft/metadata-2 avatar: a small circle before the nick that scales
                        // with the nick font, so it grows when the member pane is widened.
                        // Gated by the metadataAvatars map (previews on, https, unproxied).
                        // A row without an avatar reserves the same width when ANY nick in
                        // the channel has one, so the nicks stay left-aligned with each other
                        // instead of stepping in and out as avatars load.
                        val avatarSize = (nickFontSp + 4f).dp
                        val avatarGap = (nickFontSp * 0.3f).dp
                        if (nickAvatar != null) {
                            var avatarBmp by remember(nickAvatar) { mutableStateOf(RemoteImage.cached(nickAvatar)) }
                            LaunchedEffect(nickAvatar) { if (avatarBmp == null) avatarBmp = RemoteImage.fetch(nickAvatar) }
                            avatarBmp?.let { bmp ->
                                Image(
                                    bitmap = bmp,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .padding(end = avatarGap)
                                        .size(avatarSize)
                                        .clip(CircleShape)
                                        .alpha(if (isAway) 0.6f else 1f),
                                )
                            } ?: Spacer(Modifier.width(avatarSize + avatarGap))
                        } else if (metadataAvatars.isNotEmpty()) {
                            Spacer(Modifier.width(avatarSize + avatarGap))
                        }
                        Text(
                            n,
                            color = if (state.settings.colorizeNicks)
                                metadataNickColor(cleaned.lowercase())
                                    ?: nicklistColorMap[cleaned.lowercase()]
                                    ?: NickColors.colorForNick(cleaned.lowercase(), bgLum)
                            else Color.Unspecified,
                            fontSize = nickFontSp.sp,
                            lineHeight = (nickFontSp * 1.15f).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Fade an away nick a touch further, on top of the row tint.
                            modifier = Modifier.alpha(if (isAway) 0.6f else 1f),
                        )
                        // Display name never replaces the nick here, for the same
                        // impersonation reason as the message rows.
                        if (nickDisplayName != null) {
                            Text(
                                " ($nickDisplayName)",
                                color = Color.Gray,
                                fontSize = (nickFontSp - 2f).coerceAtLeast(8f).sp,
                                // Same reason as the nick above: an unset lineHeight here
                                // would set the row's height on its own.
                                lineHeight = ((nickFontSp - 2f).coerceAtLeast(8f) * 1.15f).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // Bot Mode: a small chip marks bot users.
                        if (isBot) {
                            Text(
                                " [bot]",
                                color = Color(0xFF7E9CD8),
                                fontSize = (nickFontSp - 3f).coerceAtLeast(8f).sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }

    // Fresh listState per buffer — each buffer independently remembers its scroll position.
    val listState = remember(selected) { LazyListState() }

    // Track whether the user has deliberately scrolled up to read history.
    // Reset to false on every buffer switch so new buffers start in auto-scroll mode.
    // Set to true when the list is scrolling AND the user is the one driving it
    // (isScrollInProgress catches both flings and active drags).
    // We detect "scrolled away from bottom" by watching firstVisibleItemIndex: once it
    // goes above 0 during a user-initiated scroll, we latch userScrolledUp = true.
    // Pressing the scroll-to-bottom button resets it.
    var userScrolledUp by remember(selected) { mutableStateOf(false) }
    // Hoisted out of BoxWithConstraints so the "jump to unread" buttiobn can reference it.
    // -1 means no unread messages to scroll to.
    var unreadScrollTarget by remember(selected) { mutableStateOf(-1) }

    // isAtBottom is still needed to drive the scroll-to-bottom button visibility.
    val isAtBottom by remember(selected) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // Older-history state for the selected buffer. The control is offered only when the
    // server can actually answer (chathistory negotiated) and hasn't already told us it
    // has nothing older, so the user is never shown a button that can only fail.
    val selectedBufferHistoryLoading = selected?.let { state.buffers[it]?.historyLoading } ?: false
    val historyBackfillAvailable = run {
        val buf = selected?.let { state.buffers[it] }
        buf != null &&
            !buf.historyExhausted &&
            buf.messages.any { it.from != null } &&
            viewModel?.supportsChatHistory(selected) == true
    }

    // Auto-load when the top of the list comes into view, so scrolling back simply keeps
    // going rather than stopping at a button. The header item is the last index in the
    // reversed layout; requesting is idempotent, so a repeated trigger while a request is
    // already in flight is harmless.
    LaunchedEffect(selected, historyBackfillAvailable) {
        if (!historyBackfillAvailable) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.key }
            .collect { lastKey ->
                if (lastKey == "history-load-older") {
                    selected?.let { onLoadOlderHistory(it) }
                }
            }
    }

    // True once the user has jumped/scrolled to the unread marker this session.
    // Distinct from userScrolledUp, the jump-to-unread button should stay visible
    // while the user is scrolling up toward unread messages, not disappear the moment
    // scrolling starts.
    var hasReachedUnread by remember(selected) { mutableStateOf(false) }

    // Watch for user-driven scrolls away from the bottom.
    LaunchedEffect(selected) {
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .collect { (scrolling, idx) ->
                if (scrolling && idx > 0) userScrolledUp = true
                // Dismiss the jump-to-unread button once the user has manually scrolled
                // at or beyond the unread marker (idx >= unreadScrollTarget in reversed layout).
                val target = unreadScrollTarget
                if (scrolling && target >= 0 && idx >= target) hasReachedUnread = true
                // Only clear the flag once the fling/scroll has fully come to rest at index 0.
                if (!scrolling && idx == 0) {
                    userScrolledUp = false
                    hasReachedUnread = false  // reset on return to bottom so new unreads show button again
                }
            }
    }

    // On buffer switch: scroll to newest message.
	// Advance readmarker by the ViewModel whenever new
    // messages arrive on a selected buffer (append() > newLastRead when isSelected),
    // and explicitly when the user taps scroll-to-bottom
     LaunchedEffect(selected) {
         // Skip the scroll-to-bottom if a highlight notification is targeting this buffer.
         // Both this effect and the highlight LaunchedEffect fire concurrently on buffer switch;
         // if we scroll to 0 here the buffer-switch scroll can land after the highlight scroll
         // and silently undo it, leaving the user at the bottom instead of the flagged message.
         val highlightTargetsHere = state.pendingHighlightAnchor != null &&
             state.pendingHighlightBufferKey == selected
         if (!highlightTargetsHere) {
             listState.scrollToItem(0)
         }
         // Close the /find overlay when switching to a different buffer (local search only).
         // Global search spans all buffers so stays open while navigating.
         val fo = state.findOverlay
         if (fo != null && !fo.bufferKey.startsWith("GLOBAL:") &&
             fo.bufferKey != selected) {
             onCloseFindOverlay()
         }
     }

    // Auto-scroll to newest when a new message arrives, unless the user has scrolled up.
    // baselineMsgId is captured at buffer-switch time so the first LaunchedEffect execution
    // (same buffer, same lastMsgId) is always a no-op and never races with the switch above.
    val lastMsgId = messages.lastOrNull()?.id
    val baselineMsgId = remember(selected) { lastMsgId }
    LaunchedEffect(selected, lastMsgId) {
        if (lastMsgId != baselineMsgId && !userScrolledUp) listState.scrollToItem(0)
    }

    val reversedMessages = remember(messages) { messages.reversed() }

    // ── Highlight scroll & flicker ────────────────────────────────────────────
    // When the user taps a notification, state.pendingHighlightAnchor is set to a
    // stable cross-session anchor.  Format: "msgid:<ircId>", "ts:<sec>|<nick>|<text>",
    // or legacy "uiid:<Long>".  We resolve it against the current buffer, scroll to
    // the message and pulse its background 3× so the eye is drawn to it.
    var flickerMsgId by remember { mutableStateOf<Long?>(null) }
    val flickerAlpha = remember { Animatable(0f) }

    // Hoisted map from UiMessage.id → displayItems index.
    // Populated by BoxWithConstraints once displayItems is computed (below).
    // The LaunchedEffect that drives highlight scrolling uses this to scroll to
    // the correct LazyColumn item index rather than the reversedMessages index —
    // the two diverge whenever art blocks collapse multiple messages into one item.
    // msgId → displayIdx (forward) used by resolveAnchor and /find scroll.
    // displayIdx → msgId (reverse) used by the highlight flicker to identify which
    // message to pulse after scrollToItem. Both populated together by BoxWithConstraints.
    // Starts empty; LaunchedEffect keys include this map so effects retry once it arrives.
    var msgIdToDisplayIdxHoisted by remember { mutableStateOf(emptyMap<Long, Int>()) }
    var displayIdxToMsgIdHoisted  by remember { mutableStateOf(emptyMap<Int, Long>()) }
    /** IRCv3 msgid String → displayItems index; populated together with [msgIdToDisplayIdxHoisted]. */
    var msgStrToDisplayIdxHoisted by remember { mutableStateOf(emptyMap<String, Int>()) }
    /** IRCv3 msgid String → (from, text) for O(1) reply-quote label rendering. */
    var msgIdToTextHoisted by remember { mutableStateOf(emptyMap<String, Pair<String?, String>>()) }

    // Resolve an anchor string to (displayItems index, exact) where exact=true means the
    // index came from the hoisted display map and is safe to flicker/consume against.
    // When msgIdToDisplayIdxHoisted is already populated (buffer was already visible),
    // we use it for an exact display-index lookup. When it is still empty (first frame
    // before BoxWithConstraints has fired its sync), we fall back to the reversedMessages
    // index - without art blocks these are identical, and even with art blocks a near-
    // correct scroll is better than returning -1 and triggering the isAtBottom race.
    // The fallback is reported as exact=false so the caller keeps the anchor alive and
    // finishes with an exact scroll once the map lands, instead of consuming a near-miss.
    fun resolveAnchor(anchor: String): Pair<Int, Boolean> {
        fun msgToDisplayIdx(msg: UiMessage?): Pair<Int, Boolean> {
            if (msg == null) return -1 to false
            val mapIdx = msgIdToDisplayIdxHoisted[msg.id]
            if (mapIdx != null) return mapIdx to true
            // Map not yet populated (or this message postdates its last sync) -
            // fall back to the reversedMessages index.
            return reversedMessages.indexOf(msg) to false
        }

        if (anchor.startsWith("msgid:")) {
            val ircId = anchor.removePrefix("msgid:")
            return msgToDisplayIdx(reversedMessages.firstOrNull { it.msgId == ircId })
        }
        if (anchor.startsWith("ts:")) {
            val rest = anchor.removePrefix("ts:")
            val parts = rest.split("|", limit = 3)
            val anchorSec = parts.getOrNull(0)?.toLongOrNull() ?: return -1 to false
            val anchorNick = parts.getOrNull(1)?.lowercase()
            val anchorText = parts.getOrNull(2)?.lowercase().orEmpty()
            val msg = reversedMessages.firstOrNull { m ->
                val deltaSec = kotlin.math.abs(m.timeMs / 1000 - anchorSec)
                // The anchor stored stripIrcFormatting(text); UiMessage.text is RAW (codes intact,
                // stripping happens at render time), so strip here too or any colour/format code in
                // the first 80 chars makes startsWith fail and the tap silently times out.
                deltaSec <= 3 &&
                    m.from?.lowercase() == anchorNick &&
                    stripIrcFormatting(m.text).take(80).lowercase().startsWith(anchorText.take(80))
            }
            return msgToDisplayIdx(msg)
        }
        if (anchor.startsWith("uiid:")) {
            val id = anchor.removePrefix("uiid:").toLongOrNull() ?: return -1 to false
            return msgToDisplayIdx(reversedMessages.firstOrNull { it.id == id })
        }
        return -1 to false
    }

    // Clear pending anchor only when the user manually navigates to a DIFFERENT buffer
    // than the one the notification pointed to. If selected == the anchor's buffer (the
    // normal notification-tap path), do NOT clear. the scroll effect needs it.
    LaunchedEffect(selected) {
        val anchorBuf = state.pendingHighlightBufferKey
        if (state.pendingHighlightAnchor != null &&
            anchorBuf != null && selected != anchorBuf) {
            flickerMsgId = null
            flickerAlpha.snapTo(0f)
            onHighlightConsumed()
        }
    }

    // Exit copy-range mode whenever the user switches buffer so that stale
    // selectedMsgIds from the previous buffer don't carry over to the new one.
    LaunchedEffect(selected) {
        if (copyRangeMode) {
            copyRangeMode = false
            selectedMsgIds = emptySet()
        }
    }

    // Drive scroll + flicker. Re-runs when anchor changes, the message list grows
    // (so we retry once scrollback finishes loading from disk), or the display map
    // arrives. Two-phase behaviour: while msgIdToDisplayIdxHoisted is empty (cold
    // notification tap, first frame of the buffer) resolveAnchor can only offer the
    // reversedMessages index, which drifts from the LazyColumn index whenever art
    // blocks collapse multiple messages into one item. In that phase we scroll to the
    // approximate index for responsiveness but keep the anchor ALIVE; keying on the
    // map makes the effect re-run when BoxWithConstraints syncs it, and only that
    // exact pass flickers and consumes. Consuming on the approximate pass was the
    // "tapped a highlight but it landed on the wrong message" bug: the near-miss got
    // cemented and the exact map had nothing left to correct.
    LaunchedEffect(state.pendingHighlightAnchor, reversedMessages.size, msgIdToDisplayIdxHoisted) {
        val anchor = state.pendingHighlightAnchor ?: return@LaunchedEffect
        val (displayIdx, exact) = resolveAnchor(anchor)
        val age = System.currentTimeMillis() - state.pendingHighlightSetAtMs
        if (displayIdx < 0) {
            if (age > 8_000L) onHighlightConsumed()
            return@LaunchedEffect
        }
        if (displayIdx == 0 && isAtBottom) {
            onHighlightConsumed()
            return@LaunchedEffect
        }
        if (!exact && age <= 8_000L) {
            // Approximate phase: get the user near the target now, finish when the
            // map lands. If the map never arrives (shouldn't happen, but the 8s
            // guard mirrors the unresolvable-anchor timeout), the next pass falls
            // through and completes with the approximate index rather than leaving
            // the anchor to interfere with future taps.
            listState.animateScrollToItem(displayIdx)
            return@LaunchedEffect
        }
        listState.animateScrollToItem(displayIdx)
        // Use the pre-built reverse map for O(1) msgId lookup instead of a linear scan.
        flickerMsgId = displayIdxToMsgIdHoisted[displayIdx]
        repeat(3) {
            flickerAlpha.animateTo(0.38f, animationSpec = tween(durationMillis = 130))
            flickerAlpha.animateTo(0f,    animationSpec = tween(durationMillis = 220))
        }
        flickerMsgId = null
        onHighlightConsumed()
    }

    // Once the user scrolls back to bottom after reading highlighted history,
    // clear any lingering anchor so it doesn't interfere with future taps.
    // skipFirstIsAtBottom guards against the initial composition where isAtBottom
    // is already true — we must not consume pendingHighlightAnchor before the
    // highlight LaunchedEffect has had a chance to scroll and flicker.
    val skipFirstIsAtBottom = remember { mutableStateOf(true) }
    LaunchedEffect(isAtBottom) {
        if (skipFirstIsAtBottom.value) {
            skipFirstIsAtBottom.value = false
            return@LaunchedEffect
        }
        if (isAtBottom && state.pendingHighlightAnchor != null) {
            flickerMsgId = null
            flickerAlpha.snapTo(0f)
            onHighlightConsumed()
        }
    }

    // Scroll when the find overlay navigates to a new result.
    val findOverlay = state.findOverlay
    LaunchedEffect(findOverlay?.currentIndex, findOverlay?.bufferKey, selected) {
        val ov = findOverlay ?: return@LaunchedEffect
        val isGlobal = ov.bufferKey.startsWith("GLOBAL:")
        if (!isGlobal && ov.bufferKey != selected) return@LaunchedEffect
        val targetId = ov.matchIds.getOrNull(ov.currentIndex) ?: return@LaunchedEffect
        if (isGlobal) {
            // Find which buffer contains this message.
            val targetKey = state.buffers.entries.firstOrNull { (_, buf) ->
                buf.messages.any { it.id == targetId }
            }?.key
            if (targetKey != null && targetKey != selected) {
                // Navigate to the buffer — scroll will happen on next recompose once
                // selected == targetKey and reversedMessages is updated.
                onSelectBuffer(targetKey)
            } else if (targetKey == selected) {
                val displayIdx = msgIdToDisplayIdxHoisted[targetId]
                    ?: reversedMessages.indexOfFirst { it.id == targetId }
                if (displayIdx >= 0) listState.animateScrollToItem(displayIdx)
            }
        } else {
            val displayIdx = msgIdToDisplayIdxHoisted[targetId]
                ?: reversedMessages.indexOfFirst { it.id == targetId }
            if (displayIdx >= 0) listState.animateScrollToItem(displayIdx)
        }
    }
    // Only show the separator when the user has scrolled up to read history.
    // At the bottom they can already see new messages, so rendering it there is just noise
    // (and it always appears at the very bottom when there's only one new message).
    val reversedUnreadIndex = if (firstUnreadIndex >= 0 && userScrolledUp) messages.size - 1 - firstUnreadIndex else -1

    val density = LocalDensity.current

    val uriHandler = LocalUriHandler.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val (baseWeight, baseStyle) = when (state.settings.chatFontStyle) {
        ChatFontStyle.REGULAR -> FontWeight.Normal to FontStyle.Normal
        ChatFontStyle.BOLD -> FontWeight.Bold to FontStyle.Normal
        ChatFontStyle.ITALIC -> FontWeight.Normal to FontStyle.Italic
        ChatFontStyle.BOLD_ITALIC -> FontWeight.Bold to FontStyle.Italic
    }

    // Held across recompositions: resolving this hits the filesystem for a custom font,
    // and ChatScreen recomposes on every incoming line.
    val chatFontFamily = remember(state.settings.chatFontChoice, state.settings.customChatFontPath) {
        fontFamilyForChoice(state.settings.chatFontChoice, state.settings.customChatFontPath)
    }

    // Held across recompositions so every message Text sees the same instance. A style
    // rebuilt each time is only skippable while it stays equal to the last one, and a
    // freshly built font family breaks that equality, which re-lays-out the whole
    // scrollback on every recomposition.
    val bodyMedium = MaterialTheme.typography.bodyMedium
    val chatLineHeightFactor = state.settings.chatFontLineHeight.coerceIn(0.9f, 2.0f)
    val chatTextStyle = remember(bodyMedium, chatFontFamily, baseWeight, baseStyle, chatLineHeightFactor) {
        bodyMedium.copy(
            fontFamily = chatFontFamily,
            fontWeight = baseWeight,
            fontStyle = baseStyle,
            // Font line height: the leading between the wrapped lines of ONE message.
            lineHeight = bodyMedium.fontSize * chatLineHeightFactor,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
        )
    }

    // Chat line spacing: the gap between one message and the next, and nothing else.
    // Stored as a fraction of the font size so the steps scale with the font size slider.
    val chatItemGap = with(density) {
        MaterialTheme.typography.bodyMedium.fontSize.toDp() *
            state.settings.chatLineSpacing.coerceIn(0f, 1.5f)
    }

    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline
    )

    val onAnnotationClick: (String, String) -> Unit = { tag, value ->
        when (tag) {
            ANN_URL -> runCatching { uriHandler.openUri(value) }
            ANN_CHAN -> {
                // Option A: treat #channel as a channel on the currently active network.
                val netId = selNetId.ifBlank { state.activeNetworkId ?: selNetId }
                onSend("/join $value")
                if (netId.isNotBlank()) onSelectBuffer("$netId::$value")
            }

            ANN_NICK -> {
                if (value.isNotBlank()) openNickActions(value)
            }
        }
    }

    val topBarTitle = if (selBufName == "*server*") selNetName else "$selNetName:$selBufName"

    // Show the channel's modes next to the name. Rendered
    // as a subtitle under the title rather than appended to it.
    val topBarSubtitle = if (isChannel && selBufName != "*server*") {
        state.buffers[selected]?.modeDisplay?.takeIf { it.isNotBlank() }
    } else null

    val topBar: @Composable () -> Unit = {
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val compact = state.settings.compactMode
        val barHeight = when {
            cfg.orientation == Configuration.ORIENTATION_LANDSCAPE -> if (compact) 40.dp else 44.dp
            compact -> 40.dp
            else -> 50.dp
        }
        // Compact mode slims the bar: smaller icon-button touch targets, smaller
        // accent buttons + glyphs, tighter spacing and a smaller title.
        val iconBtnSize  = if (compact) 36.dp else 40.dp   // ☰ and ⋮ buttons
        val accentBtnSize = if (compact) 22.dp else 25.dp  // colour + nicklist buttons
        val accentGlyphSize = if (compact) 16.dp else 20.dp
        val barHPadding  = if (compact) 2.dp else 4.dp
        val barSpacing   = if (compact) 2.dp else 4.dp
        val titleStyle   = if (compact) MaterialTheme.typography.titleSmall
                           else MaterialTheme.typography.titleMedium

        val cs = MaterialTheme.colorScheme
        val topBarBrush = remember(cs) {
            Brush.verticalGradient(
                listOf(
                    cs.surfaceColorAtElevation(6.dp),
                    cs.surface
                )
            )
        }

        Surface(
            tonalElevation = 2.dp,
            color = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .background(topBarBrush)
        ) {
            Column(Modifier.fillMaxWidth()) {
                // Keep content below the system status bar without making the app bar itself overly tall.
                Spacer(Modifier.height(topInset))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .padding(horizontal = barHPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(barSpacing)
                ) {
                    if (isWide) {
                        IconButton(
                            onClick = onToggleBufferList,
                            modifier = Modifier
                                .size(iconBtnSize)
                                .tvInitialFocus()
                                .focusHighlight()
                                .tourTarget(TourTarget.CHAT_DRAWER_BUTTON)
                        ) { Text("☰") }
                    } else if (!state.settings.networkTabsAtBottom) {
                        // The bottom bar replaces the drawer in this mode, so drop the opener.
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier
                                .size(iconBtnSize)
                                .tvInitialFocus()
                                .focusHighlight()
                                .tourTarget(TourTarget.CHAT_DRAWER_BUTTON)
                        ) { Text("☰") }
                    }

                    Column(Modifier.weight(1f)) {
                        Text(
                            text = topBarTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Line height tightened to its own font size: the default leading
                            // would push the two-line stack past the fixed bar height at
                            // larger system font scales.
                            style = titleStyle.copy(lineHeight = titleStyle.fontSize * 1.1f),
                        )
                        if (topBarSubtitle != null) {
                            Text(
                                text = "Modes: $topBarSubtitle",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp),
                                color = LocalContentColor.current.copy(alpha = 0.7f),
                            )
                        }
                    }

                    // Colour/formatting picker button. No background: the sweep gradient
                    // is painted onto the FormatColorText glyph itself (SrcAtop over an
                    // offscreen layer masks it to the icon shape). When formatting is
                    // active, the B/I/U/A indicator is tinted with the selected foreground
                    // colour if one is set, else the same sweep brush.
                    run {
                        val colorInteraction = remember { MutableInteractionSource() }
                        val colorPressed by colorInteraction.collectIsPressedAsState()
                        val hasActiveFormatting =
                            selectedFgColor != null || selectedBgColor != null ||
                                    boldActive || italicActive || underlineActive || reverseActive
                        val fontSweep = remember {
                            Brush.sweepGradient(
                                colors = listOf(
                                    Color(0xFFFF6B6B),
                                    Color(0xFFFFE66D),
                                    Color(0xFF4ECDC4),
                                    Color(0xFF45B7D1),
                                    Color(0xFFDDA0DD),
                                    Color(0xFFFF6B6B)
                                )
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(accentBtnSize)
                                .scale(if (colorPressed) 0.92f else 1f)
                                .focusHighlight(CircleShape)
                                .clickable(
                                    interactionSource = colorInteraction,
                                    indication = ripple(bounded = false),
                                    onClick = { showColorPicker = true }
                                )
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (hasActiveFormatting) {
                                val activeFg = selectedFgColor?.let { mircColor(it) }
                                Text(
                                    text = buildString {
                                        if (boldActive) append("B")
                                        if (italicActive) append("I")
                                        if (underlineActive) append("U")
                                    }.ifEmpty { "A" },
                                    color = activeFg ?: MaterialTheme.colorScheme.primary,
                                    fontWeight = if (boldActive) FontWeight.Bold else FontWeight.Medium,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontStyle = if (italicActive) FontStyle.Italic else FontStyle.Normal,
                                        textDecoration = if (underlineActive) TextDecoration.Underline else TextDecoration.None
                                    )
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.FormatColorText,
                                    contentDescription = stringResource(R.string.chat_text_formatting),
                                    tint = Color.Black,   // opaque base; replaced by the gradient below
                                    modifier = Modifier
                                        .size(accentGlyphSize)
                                        .graphicsLayer {
                                            compositingStrategy = CompositingStrategy.Offscreen
                                            alpha = if (colorPressed) 0.7f else 1f
                                        }
                                        .drawWithContent {
                                            drawContent()
                                            drawRect(brush = fontSweep, blendMode = BlendMode.SrcAtop)
                                        }
                                )
                            }
                        }
                    }

                    // Nicklist button. No background: flat theme-tinted icon (dimmed to
                    // 40% when the current buffer isn't a channel, so it still reads as
                    // unavailable now that the coloured pill is gone).
                    run {
                        val nicklistInteraction = remember { MutableInteractionSource() }
                        val nicklistPressed by nicklistInteraction.collectIsPressedAsState()
                        Box(
                            modifier = Modifier
                                .size(accentBtnSize)
                                .scale(if (nicklistPressed) 0.92f else 1f)
                                .alpha(if (isChannel) 1f else 0.4f)
                                .then(
                                    if (isChannel) {
                                        Modifier.focusHighlight(CircleShape).clickable(
                                            interactionSource = nicklistInteraction,
                                            indication = ripple(bounded = false),
                                            onClick = {
                                                if (isWide || state.settings.portraitNicklistOverlay) {
                                                    onToggleNickList()
                                                } else {
                                                    val next = !showNickSheet
                                                    showNickSheet = next
                                                    if (next) onRefreshNicklist()
                                                }
                                            }
                                        )
                                    } else Modifier
                                )
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.RecentActors,
                                contentDescription = stringResource(R.string.chat_user_list),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = if (nicklistPressed) 0.6f else 1f),
                                modifier = Modifier.size(accentGlyphSize)
                            )
                        }
                    }

                    if (scriptLaunchers.isNotEmpty()) {
                        Box {
                            IconButton(
                                onClick = { launcherExpanded = true },
                                modifier = Modifier.size(iconBtnSize).focusHighlight()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.IntegrationInstructions,
                                     contentDescription = stringResource(R.string.chat_cd_launch_scripts),
                                     tint = MaterialTheme.colorScheme.onSurfaceVariant
                                     .copy(alpha = 0.6f),

                                     modifier = Modifier.size(accentGlyphSize)
                                )
                            }
                            DropdownMenu(
                                expanded = launcherExpanded,
                                onDismissRequest = { launcherExpanded = false }
                            ) {
                                scriptLaunchers.forEach { (label, command) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                                     onClick = { launcherExpanded = false; onRunLauncher(command) }
                                    )
                                }
                            }
                        }
                    }

                    Box {
                        IconButton(
                            onClick = { overflowExpanded = true },
                            modifier = Modifier
                                .size(iconBtnSize)
                                .focusHighlight()
                                .tourTarget(TourTarget.CHAT_OVERFLOW_BUTTON)
                        ) { Text("⋮") }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false }
                        ) {
                            // The menu
                            data class MenuEntry(
                                val label: String,
                                val enabled: Boolean = true,
                                val onClick: () -> Unit,
                            )

                            val entries = buildList {
                                add(MenuEntry(stringResource(R.string.menu_channel_list)) { overflowExpanded = false; onOpenList() })
                                add(MenuEntry(stringResource(R.string.menu_file_transfers)) { overflowExpanded = false; onOpenTransfers() })
                                // Encryption is only meaningful for channel/query buffers, not the
                                // *server*/*status* tab. selNetId can be blank if no networks exist
                                // yet; viewModel can be null in preview/test.
                                if (viewModel != null &&
                                    selNetId.isNotBlank() &&
                                    selBufName.isNotBlank() &&
                                    selBufName != "*server*" &&
                                    selBufName != "*status*"
                                ) {
                                    add(MenuEntry("Secure Chat") { overflowExpanded = false; showEncryptionDialog = true })
                                }
                                // draft/metadata-2 editor and draft/account-registration dialog,
                                // shown only when the connected server negotiated the cap.
                                if (viewModel != null && selNetId.isNotBlank() &&
                                    viewModel.serverSupportsMetadata(selNetId)) {
                                    add(MenuEntry(stringResource(R.string.menu_edit_metadata)) {
                                        overflowExpanded = false; showMetadataEditor = true
                                    })
                                }
                                if (viewModel != null && selNetId.isNotBlank() &&
                                    viewModel.serverSupportsAccountReg(selNetId) &&
                                    state.connections[selNetId]?.myAccount == null) {
                                    add(MenuEntry(stringResource(R.string.menu_register_account)) {
                                        overflowExpanded = false; showRegistration = true
                                    })
                                }
                                add(MenuEntry(stringResource(R.string.menu_settings)) { overflowExpanded = false; onOpenSettings() })
                                add(MenuEntry("Scripts") { overflowExpanded = false; onOpenScripts() })
                                add(MenuEntry(stringResource(R.string.menu_networks)) { overflowExpanded = false; onOpenNetworks() })
                                if (isIrcOper) {
                                    add(MenuEntry(stringResource(R.string.menu_ircop_tools)) { overflowExpanded = false; showIrcOpTools = true })
                                }
                                add(MenuEntry(stringResource(R.string.menu_about)) { overflowExpanded = false; onAbout() })
                                add(MenuEntry(
                                    stringResource(R.string.menu_reconnect),
                                    enabled = state.networks.isNotEmpty() && !state.connecting
                                ) { overflowExpanded = false; onReconnect() })
                                add(MenuEntry(stringResource(R.string.menu_disconnect)) { overflowExpanded = false; onDisconnect() })
                                add(MenuEntry(stringResource(R.string.menu_exit)) { overflowExpanded = false; onExit() })
                            }
                            // Dividers sit before Reconnect and before Exit, i.e. after
                            // About (size-4) and after Disconnect (size-2).
                            val dividerAfter = setOf(entries.size - 4, entries.size - 2)

                            // Budget: screen height less the top bar the menu hangs from,
                            // the system bars, the menu's own 8dp top/bottom padding, and
                            // the two dividers.
                            val menuBudgetDp = (cfg.screenHeightDp - 96 - 16 - 2).toFloat()
                            val rowHeightDp = (menuBudgetDp / entries.size.coerceAtLeast(1))
                                .coerceAtMost(34f)
                                .coerceAtLeast(28f)
                                .dp
                            // Padding tracks the row height so the label stays centred and
                            // the text never touches the divider above it.
                            val rowPadV = ((rowHeightDp.value - 20f) / 2f).coerceIn(2f, 7f).dp

                            @Composable
                            fun MenuRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = rowHeightDp)
                                        .focusHighlight()
                                        .clickable(enabled = enabled, onClick = onClick)
                                        .padding(horizontal = 16.dp, vertical = rowPadV),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                }
                            }

                            entries.forEachIndexed { idx, e ->
                                MenuRow(e.label, enabled = e.enabled, onClick = e.onClick)
                                if (idx in dividerAfter) HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MessagesPane(mod: Modifier = Modifier) {
        Box(mod.clipToBounds()) {
        Column(Modifier.fillMaxSize()) {
            if (state.settings.showTopicBar && isChannel && !topic.isNullOrBlank()) {
                Surface(
                    tonalElevation = 1.dp,
                    modifier = if (canTopic) Modifier.focusHighlight().combinedClickable(
                        onClick = { topicExpanded = !topicExpanded },
                        onLongClick = { showTopicQuickEdit = true },
                    ) else Modifier
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 30.dp)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IrcLinkifiedText(
                            text = topic,
                            mircColorsEnabled = state.settings.mircColorsEnabled,
                            ansiColorsEnabled = state.settings.ansiColorsEnabled,
                            linkStyle = linkStyle,
                            onAnnotationClick = onAnnotationClick,
                            maxLines = if (topicExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            onTextLayout = { topicHasOverflow = it.hasVisualOverflow },
                        )
                        val showToggle = topicExpanded || topicHasOverflow
                        if (showToggle) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = { topicExpanded = !topicExpanded },
                                contentPadding = PaddingValues(0.dp), modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                            ) { Text(stringResource(if (topicExpanded) R.string.chat_topic_less else R.string.chat_topic_more)) }
                        }
                        if (canTopic) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.chat_cd_edit_topic),
                                modifier = Modifier
                                    .size(16.dp)
                                    .alpha(0.5f),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Tap anywhere in the chat to close the soft keyboard.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial
                            )
                            var dragged = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!dragged &&
                                    change.positionChange().getDistance() > viewConfiguration.touchSlop
                                ) dragged = true
                                if (!change.pressed) break
                            }
                            if (!dragged) {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        }
                    }
            ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Compute a single shared font size for all MOTD lines so that every line
                // renders at the same size. ASCII art depends on all columns being equal-width —
                // if each line were sized independently, short lines would be large and long
                // lines small, breaking the grid alignment.
                val motdAvailableWidthPx = constraints.maxWidth.toFloat() - with(LocalDensity.current) { 16.dp.toPx() } // subtract 8.dp padding each side
                val motdStyle = chatTextStyle.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)
                val motdLines = remember(messages, selBufName) {
                    if (selBufName == "*server*") messages.filter { it.isMotd }.map { it.text }
                    else emptyList()
                }
                val motdFontSizeSp = rememberMotdFontSizeSp(
                    motdLines = motdLines,
                    style = motdStyle,
                    availableWidthPx = motdAvailableWidthPx,
                )

                // Build display items: each art block becomes a single LazyColumn item
                // (all its lines in one Column) so there are zero inter-line gaps.
                // Font sizing uses one measurement + direct scale factor instead of
                // per-line binary search — O(1) per block instead of O(N×8).
                val rawDisplayItems = rememberDisplayItems(
                    reversedMessages = reversedMessages,
                    availableWidthPx = motdAvailableWidthPx,
                    style = motdStyle,
                    artDetectionEnabled = state.settings.artDetectionEnabled,
                )
                // Final guard: drop any item whose key duplicates an earlier one. This
                // should already be impossible (UiMessage ids are AtomicLong-generated and
                // keys are type-tagged) but Compose throws an unrecoverable
                // IllegalArgumentException from inside its measure pass if a duplicate
                // ever slips through, so a one-line .distinctBy in front of it is much
                // cheaper than a crash. List traversal here is O(n) and runs only when
                // displayItems changes (i.e. once per buffer mutation), not per frame.
                val displayItems = remember(rawDisplayItems) {
                    rawDisplayItems.distinctBy { it.key }
                }
                // Map message ID → display item index for unread separator placement
                // and highlight scrolling. Also synced to msgIdToDisplayIdxHoisted so
                // resolveAnchor() (which lives outside BoxWithConstraints) can use it.
                val msgIdToDisplayIdx = remember(displayItems) {
                    val map = HashMap<Long, Int>(displayItems.size * 2)
                    for ((i, item) in displayItems.withIndex()) {
                        when (item) {
                            is DisplayItem.Single -> map[item.msg.id] = i
                            is DisplayItem.Art    -> item.msgs.forEach { map[it.id] = i }
                        }
                    }
                    map
                }
                // IRCv3 msgid (String) → display index for O(1) reply-quote scroll.
                // Built alongside the Long-keyed map so both stay in sync.
                val msgStrToDisplayIdx = remember(displayItems) {
                    val map = HashMap<String, Int>(displayItems.size * 2)
                    for ((i, item) in displayItems.withIndex()) {
                        when (item) {
                            is DisplayItem.Single ->
                                item.msg.msgId?.let { map[it] = i }
                            is DisplayItem.Art    ->
                                item.msgs.forEach { m -> m.msgId?.let { map[it] = i } }
                        }
                    }
                    map
                }
                // IRCv3 msgid String → (from, text) for O(1) reply-quote label rendering.
                val msgIdToText = remember(displayItems) {
                    val map = HashMap<String, Pair<String?, String>>(displayItems.size * 2)
                    for (item in displayItems) {
                        val msgs = when (item) {
                            is DisplayItem.Single -> listOf(item.msg)
                            is DisplayItem.Art    -> item.msgs
                        }
                        for (m in msgs) m.msgId?.let { map[it] = m.from to m.text }
                    }
                    map
                }
                // Keep both maps in sync whenever displayItems changes so the
                // highlight LaunchedEffect always resolves anchors to the correct index.
                LaunchedEffect(msgIdToDisplayIdx) {
                    msgIdToDisplayIdxHoisted = msgIdToDisplayIdx
                    displayIdxToMsgIdHoisted = msgIdToDisplayIdx.entries
                        .associateTo(HashMap(msgIdToDisplayIdx.size)) { (id, idx) -> idx to id }
                    msgStrToDisplayIdxHoisted = msgStrToDisplayIdx
                    msgIdToTextHoisted = msgIdToText
                }
                val unreadDisplayIdx = if (reversedUnreadIndex in reversedMessages.indices)
                    msgIdToDisplayIdx[reversedMessages[reversedUnreadIndex].id] ?: -1
                else -1

                // Keep the hoisted state in sync so the FAB outside BoxWithConstraints
                // always knows the current display-item index of the first unread message.
                // firstUnreadIndex >= 0 means there are unread messages; show button even
                // when the user hasn't scrolled up yet (that's exactly when they need it).
                unreadScrollTarget = if (firstUnreadIndex >= 0)
                    if (unreadDisplayIdx >= 0) unreadDisplayIdx
                    else if (reversedUnreadIndex in reversedMessages.indices) reversedUnreadIndex
                    else -1
                else -1

            // Note: SelectionContainer is intentionally NOT used here. Wrapping a LazyColumn
            // in SelectionContainer causes NPE crashes when items are recycled mid-drag (see
            // the long comment that was here before). Text copying is handled entirely through
            // the long-press bottom sheet (single message) and the copy-range mode above
            // (multiple messages). This also fixes the bug where text was getting selected
            // during long-press instead of the bottom sheet appearing.
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)
            ) {
                    itemsIndexed(items = displayItems, key = { _, item -> item.key }) { displayIdx, item ->
                        if (displayIdx == unreadDisplayIdx) {
                            UnreadSeparator()
                        }

                        when (item) {

                        // ── Art block ─────────────────────────────────────────────────────────
                        // All lines of the block are rendered in one Column, so there are
                        // zero inter-line gaps — no LazyColumn item boundaries, no font-metric
                        // padding between rows.  Nick badges overlay each sender's first line.
                        is DisplayItem.Art -> {
                            // Strip IRC colour/style codes so the clipboard receives clean text.
                            // Art blocks render without a per-line nick prefix (the attribution
                            // row at the top handles that), so the copy should match what the
                            // user sees — plain art text only, no embedded "<nick>" labels.
                            val blockText = item.msgs.joinToString("\n") { stripIrcFormatting(it.text) }
                            // Collect the unique senders for the attribution line.
                            // Preserve encounter order so the list reads chronologically.
                            val senders = item.msgs.mapNotNull { it.from }
                                .distinct()
                            androidx.compose.foundation.layout.Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clipToBounds()
                                    .focusHighlight()
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = {
                                            scope.launch {
                                                clipboard.setClipEntry(
                                                    android.content.ClipData.newPlainText("", blockText).toClipEntry()
                                                )
                                            }
                                        }
                                    )
                            ) {
                                // Single attribution row above the block: "▸ bort  brot  boat  snot"
                                // One line, zero height impact on the art itself.
                                if (senders.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.padding(bottom = 1.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "▸",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        )
                                        for (sender in senders) {
                                            Text(
                                                text = sender,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (state.settings.colorizeNicks)
                                                    nickColor(sender)
                                                else
                                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                                // Art lines: tightly packed, no gaps between rows.
                                for (msg in item.msgs) {
                                    MotdLine(
                                        text = msg.text,
                                        fontSizeSp = item.fontSizeSp,
                                        style = motdStyle,
                                        mircColorsEnabled = state.settings.mircColorsEnabled,
                                        ansiColorsEnabled = state.settings.ansiColorsEnabled,
                                        linkStyle = linkStyle,
                                        onAnnotationClick = onAnnotationClick,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }

                        // ── Normal / single message ───────────────────────────────────────────
                        is DisplayItem.Single -> {
                        val m = item.msg
                        val ts =
                            if (state.settings.showTimestamps) {
                                val style = state.settings.timestampStyle
                                "${style.open}${timeFmt.format(Date(m.timeMs))}${style.close} "
                            } else ""
                        // The per-scheme encryption badge is now rendered inside
                        // SingleMessageItem as a Material icon (InlineTextContent) from
                        // m.encryption, so nothing scheme-specific is baked into `ts` here.
                        val isFindMatch = findOverlay != null &&
                            (findOverlay.bufferKey == selected || findOverlay.bufferKey.startsWith("GLOBAL:")) &&
                            findOverlay.matchIds.contains(m.id)
                        SingleMessageItem(
                            m = m,
                            ts = ts,
                            nickStyle = state.settings.nickStyle,
                            timestampColor = state.settings.timestampColorInt?.let { Color(it) },
                            isFlickering = flickerMsgId == m.id,
                            flickerAlphaValue = flickerAlpha.value,
                            isFindMatch = isFindMatch,
                            isFindCurrent = isFindMatch &&
                                findOverlay.run { matchIds.getOrNull(currentIndex) } == m.id,
                            findHighlight = if (isFindMatch) findOverlay.query else null,
                            isSelectedForCopy = m.id in selectedMsgIds,
                            isExpanded = m.id in expandedMsgIds,
                            onToggleExpanded = {
                                expandedMsgIds = if (m.id in expandedMsgIds) {
                                    expandedMsgIds - m.id
                                } else {
                                    expandedMsgIds + m.id
                                }
                            },
                            copyRangeMode = copyRangeMode,
                            selBufName = selBufName,
                            messages = messages,
                            reversedMessages = reversedMessages,
                            listState = listState,
                            msgIdToDisplayIdx = msgIdToDisplayIdxHoisted,
                            msgStrToDisplayIdx = msgStrToDisplayIdxHoisted,
                            msgIdToText = msgIdToTextHoisted,
                            scope = scope,
                            chatTextStyle = chatTextStyle,
                            itemGap = chatItemGap,
                            motdStyle = motdStyle,
                            motdFontSizeSp = motdFontSizeSp,
                            linkStyle = linkStyle,
                            onAnnotationClick = onAnnotationClick,
                            colorizeNicks = state.settings.colorizeNicks,
                            mircColorsEnabled = state.settings.mircColorsEnabled,
                            ansiColorsEnabled = state.settings.ansiColorsEnabled,
                            imagePreviewsEnabled = state.settings.imagePreviewsEnabled,
                            imagePreviewsWifiOnly = state.settings.imagePreviewsWifiOnly,
                            nickColor = ::nickColor,
                            displayNick = ::displayNick,
                            onToggleSelected = {
                                selectedMsgIds = if (m.id in selectedMsgIds)
                                    selectedMsgIds - m.id else selectedMsgIds + m.id
                            },
                            onLongPress = { longPressedMessage = m },
                            onSwipeReply = { pendingReply = m },
                            // draft/metadata-2: shown after the nick, never instead of it.
                            // Suppressed when it merely repeats the nick.
                            displayName = m.from
                                ?.let { metadataDisplayNames[baseNick(it).lowercase()] }
                                ?.takeIf { !it.equals(baseNick(m.from), ignoreCase = true) },
                        )
                        } // end DisplayItem.Single

                        } // end when
                    }

                    // Older-history control.
                    if (historyBackfillAvailable) {
                        item(key = "history-load-older") {
                            LoadOlderHistoryRow(
                                loading = selectedBufferHistoryLoading,
                                onLoad = { selected?.let { onLoadOlderHistory(it) } },
                            )
                        }
                    }
                }
            } // end BoxWithConstraints

            // Copy-range action bar
            if (copyRangeMode) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .zIndex(2f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (selectedMsgIds.isEmpty()) stringResource(R.string.chat_select_hint)
                                   else "${selectedMsgIds.size} selected",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                val toCopy = messages
                                    .filter { it.id in selectedMsgIds }
                                    .sortedBy { it.timeMs }
                                    .joinToString("\n") { msg ->
                                        buildString {
                                            if (msg.from != null) append("<${msg.from}> ")
                                            append(stripIrcFormatting(msg.text))
                                        }
                                    }
                                if (toCopy.isNotBlank()) {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            android.content.ClipData.newPlainText("", toCopy).toClipEntry()
                                        )
                                    }
                                }
                                copyRangeMode = false
                                selectedMsgIds = emptySet()
                            },
                            enabled = selectedMsgIds.isNotEmpty(),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.38f),
                            ), modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                        ) { Text(stringResource(R.string.copy)) }
                        TextButton(
                            onClick = {
                                copyRangeMode = false
                                selectedMsgIds = emptySet()
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ), modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                        ) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }

            // Jump-to-unread button: shown when there are unread messages and the user
            // hasn't already scrolled up to them. Stacked above the scroll-to-bottom FAB.
            if (unreadScrollTarget >= 0 && !hasReachedUnread && !isAtBottom) {
                val unreadCount = buf?.unread ?: 0
                // Find the timestamp of the oldest unread message for the "since HH:mm" label.
                val firstUnreadMsg = reversedMessages.getOrNull(unreadScrollTarget)
                val sinceLabel = firstUnreadMsg?.let { msg ->
                    runCatching {
                        val fmt = java.text.SimpleDateFormat(
                            if (state.settings.timestampFormat.contains("ss")) "HH:mm:ss" else "HH:mm",
                            java.util.Locale.getDefault()
                        )
                        " since ${fmt.format(java.util.Date(msg.timeMs))}"
                    }.getOrElse { "" }
                } ?: ""
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = if (!isAtBottom) 56.dp else 8.dp)
                        .zIndex(1f)
                        .focusHighlight(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true)
                        ) {
                            // Read once
                            val target = unreadScrollTarget
                            if (target >= 0) {
                                scope.launch {
                                    runCatching { listState.animateScrollToItem(target) }
                                    userScrolledUp = true
                                    hasReachedUnread = true
                                }
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.chat_cd_jump_unread),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (unreadCount > 0)
                                pluralStringResource(R.plurals.chat_unread_count, unreadCount, unreadCount) + sinceLabel
                            else stringResource(R.string.chat_cd_jump_unread),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            // Scroll-to-bottom button: shown when user has scrolled up (not at tail).
            // Must be a direct child of the outer Box to use .align(Alignment.BottomEnd).
            if (!isAtBottom) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 8.dp)
                        .zIndex(1f)
                        .size(40.dp)
                        .focusHighlight(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false)
                        ) {
                            onMarkRead(selected)
                            userScrolledUp = false
                            scope.launch { listState.scrollToItem(0) }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.chat_cd_scroll_bottom),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            } // end Box
        } // end inner Column

        // /find overlay pill floats over messages at bottom of MessagesPane Box
        val showFindPill = findOverlay != null &&
            (findOverlay.bufferKey == selected || findOverlay.bufferKey.startsWith("GLOBAL:"))
        if (showFindPill) {
            val matchCount = findOverlay.matchIds.size
            val cur = findOverlay.currentIndex
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp, start = 12.dp, end = 12.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "\"${findOverlay.query}\"",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 180.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${cur + 1} / $matchCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = { onFindNavigate(-1) }, enabled = cur > 0,
                        modifier = Modifier.size(32.dp).focusHighlight(CircleShape)) {
                        Icon(Icons.Default.KeyboardArrowUp, "Previous match",
                            modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { onFindNavigate(1) }, enabled = cur < matchCount - 1,
                        modifier = Modifier.size(32.dp).focusHighlight(CircleShape)) {
                        Icon(Icons.Default.KeyboardArrowDown, "Next match",
                            modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onCloseFindOverlay, modifier = Modifier.size(32.dp).focusHighlight(CircleShape)) {
                        Icon(Icons.Default.Close, "Close search", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    } // end outer Box
    } // end MessagesPane

	val bottomBar: @Composable () -> Unit = {
        val cs = MaterialTheme.colorScheme
        val bottomBarBrush = remember(cs) {
            Brush.verticalGradient(
                listOf(
                    cs.surfaceColorAtElevation(6.dp),
                    cs.surface
                )
            )
        }

        // Command-hint query: non-null only when user has typed at least one letter after /
        // (bare "/" alone doesn't trigger - it would show all 68 commands at once)
        val cmdQuery = remember(input.text) {
            val t = input.text
            if (t.length >= 2 && t.startsWith("/") && !t.contains(" ")) t.drop(1) else null
        }

        // Subcommand-hint query: non-null when the user has typed a known parent
        // command followed by a space and is (optionally) partway through a sub-verb.
        // E.g. "/ns " → Pair("ns", ""); "/ns ID" → Pair("ns", "ID"); "/znc list" →
        // Pair("znc", "list"). Unknown parent commands (no SUB_COMMANDS entry)
        // and any input with a *second* space — meaning the user has moved past
        // the sub-verb into its args — return null so the bar dismisses cleanly.
        val subCmdQuery: Pair<String, String>? = remember(input.text) {
            val t = input.text
            if (!t.startsWith("/") || t.length < 2) return@remember null
            val firstSpace = t.indexOf(' ')
            if (firstSpace < 0) return@remember null  // still typing the parent command
            val parent = t.substring(1, firstSpace).lowercase()
            if (subCommandsFor(parent) == null) return@remember null
            val rest = t.substring(firstSpace + 1)
            // Stop hinting once the user has moved past the sub-verb into its arguments.
            // For BouncerServ-style two-word sub-verbs ("network status") we do allow
            // one embedded space, but only while the second token is still a prefix
            // match — this is handled naturally because the subcommand names in the
            // SUB_COMMANDS entry already contain that space.
            if (rest.count { it == ' ' } > subCommandMaxSpaces(parent)) return@remember null
            parent to rest
        }

        // Tab completion of nicks, for anyone with a physical keyboard.
        var nickCycle by remember { mutableStateOf<NickCycle?>(null) }

        /**
         * Completes the word before the cursor against the nicklist.
         * A repeat press cycles forward through the matches, Shift+Tab backwards.
         * Returns false when there is nothing to complete.
         */
        fun completeNick(backwards: Boolean): Boolean {
            if (!isChannel || nicklist.isEmpty()) return false

            fun bare(n: String) = n.trimStart('~', '&', '@', '%', '+')

            // Non-null only while the field still holds exactly what the last completion
            // left, so an edit in between starts a fresh completion rather than cycling
            // matches for a word that is no longer there.
            val cycle = nickCycle?.takeIf {
                input.text == it.text &&
                    input.selection.start == it.cursor &&
                    input.selection.collapsed
            }

            val start: Int
            val hadAt: Boolean
            val matches: List<String>
            val index: Int

            if (cycle != null) {
                start = cycle.start
                hadAt = cycle.hadAt
                matches = cycle.matches
                index = if (backwards) {
                    (cycle.index - 1 + matches.size) % matches.size
                } else {
                    (cycle.index + 1) % matches.size
                }
            } else {
                val cursor = input.selection.start
                if (!input.selection.collapsed) return false
                var wordStart = cursor
                while (wordStart > 0 && !input.text[wordStart - 1].isWhitespace()) wordStart--
                val word = input.text.substring(wordStart, cursor)
                if (word.isEmpty()) return false
                hadAt = word.startsWith("@")
                val prefix = if (hadAt) word.substring(1) else word
                if (prefix.isEmpty()) return false
                matches = nicklist
                    .map { bare(it) }
                    .filter { it.startsWith(prefix, ignoreCase = true) }
                    .distinct()
                    .sortedBy { it.lowercase() }
                if (matches.isEmpty()) return false
                start = wordStart
                index = 0
            }
            val nick = matches[index]
            val suffix = if (start == 0) ": " else " "
            val replacement = (if (hadAt) "@" else "") + nick + suffix
            val end = input.selection.start
            val newText = input.text.substring(0, start) + replacement + input.text.substring(end)
            val newCursor = start + replacement.length
            input = TextFieldValue(newText, TextRange(newCursor))
            nickCycle = NickCycle(start, hadAt, matches, index, newText, newCursor)
            return true
        }

        // Nick-hint query: non-null when the word at cursor starts with "@" and has ≥1 char after it.
        // Only active in channel buffers (not server buffers or DCC chat).
        val nickQuery = remember(input.text, isChannel) {
            if (!isChannel) return@remember null
            val t = input.text
            // Find the last "@" that starts a word token before the cursor
            val atIdx = t.lastIndexOf('@')
            if (atIdx < 0) return@remember null
            val token = t.substring(atIdx + 1)
            // Only trigger if the token after "@" is non-empty and contains no spaces (still typing)
            if (token.isNotEmpty() && !token.contains(' ')) token else null
        }

        Column(modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
        ) {
            // Conversation switcher pinned just above the input bar (Settings > Network tabs > Tabs at
            // bottom). It lists EVERY network and all of its buffers in one scrollable row: each
            // network's server tab (network name + connection dot) followed by that network's channels
            // and PMs. It replaces the drawer, which is disabled while this mode is on.
            if (state.settings.networkTabsAtBottom) {
                // Same network ordering/visibility the drawer uses (favourites first, then sort order,
                // then name; only networks kept in the switcher, plus whichever one you're viewing),
                // recomputed here from state because visibleNets is scoped to the drawer.
                val barOpenNet = splitKey(selected).first
                val barNets = state.networks
                    .sortedWith(compareBy({ !it.isFavourite }, { it.sortOrder }, { it.name }))
                    .filter { it.showInSidebar || it.id == barOpenNet }
                val allTabKeys = barNets.flatMap { net ->
                    val g = buffersByNet[net.id]
                    listOf(g?.serverKey ?: "${net.id}::*server*") + (g?.others ?: emptyList())
                }
                if (allTabKeys.isNotEmpty()) {
                    val barSelIdx = allTabKeys.indexOf(selected).coerceIn(0, allTabKeys.lastIndex)
                    // key() on the tab set. ScrollableTabRow caches its subcomposition and reads
                    // tabPositions[selectedTabIndex] during layout; when the set shrinks (a network
                    // disconnects, a buffer closes) that cached index can momentarily point one past the
                    // new, shorter list and throw IndexOutOfBounds. A fresh identity per set discards the
                    // stale layout state and sidesteps the crash.
                    key(allTabKeys) {
                    SecondaryScrollableTabRow(
                        selectedTabIndex = barSelIdx,
                        edgePadding = 0.dp,
                        containerColor = Color.Transparent,
                    ) {
                        allTabKeys.forEachIndexed { i, key ->
                            val (keyNet, keyName) = splitKey(key)
                            val isServerTab = keyName == "*server*"
                            // server tab shows the network name; channel/PM tabs show the buffer name
                            // (channels already carry their #/& sigil, so PMs read as bare nicks).
                            val label = if (isServerTab) netName(keyNet) else keyName
                            val con = state.connections[keyNet]
                            val dot = when {
                                con?.connected == true  -> MaterialTheme.colorScheme.primary
                                con?.connecting == true -> Color(0xFFE0A030)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            }
                            val b = state.buffers[key]
                            val unread = b?.unread ?: 0
                            val hi = b?.highlights ?: 0
                            Tab(
                                selected = i == barSelIdx,
                                onClick = { onSelectBuffer(key) },
                                modifier = Modifier.focusHighlight(),
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // The connection dot rides each network's server tab; the
                                        // conversation tabs under it are named alone.
                                        if (isServerTab) {
                                            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                                            networkIconOf(keyNet)?.let { iconUrl ->
                                                var sbmp by remember(iconUrl) { mutableStateOf(RemoteImage.cached(iconUrl)) }
                                                LaunchedEffect(iconUrl) { if (sbmp == null) sbmp = RemoteImage.fetch(iconUrl) }
                                                sbmp?.let {
                                                    Image(
                                                        bitmap = it,
                                                        contentDescription = null,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)),
                                                    )
                                                }
                                            }
                                        }
                                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (unread > 0) {
                                            Badge(
                                                containerColor = if (hi > 0) MaterialTheme.colorScheme.error
                                                                 else MaterialTheme.colorScheme.secondary
                                            ) { Text(if (unread > 99) "99+" else "$unread") }
                                        }
                                        // Close button on conversation tabs.
                                        if (!isServerTab) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = stringResource(R.string.chat_cd_close_label, label),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    .copy(alpha = 0.6f),
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clip(CircleShape)
                                                    .focusHighlight(CircleShape)
                                                    .clickable { onSend("/closekey $key") }
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                    }
                }
            }
            // Priority: nick hints > subcommand hints > command hints. Nick hints
            // win on channels (user is mid-@mention). Subcommand hints win when
            // we're past the parent-command space. Command hints are the baseline.
            if (nickQuery != null && cmdQuery == null && subCmdQuery == null) {
                NickHints(
                    prefix = nickQuery,
                    nicks = nicklist,
                    inputText = input.text,
                    onPick = { completion ->
                        // Replace the @prefix token at the end of input with the chosen completion
                        val t = input.text
                        val atIdx = t.lastIndexOf('@')
                        val newText = if (atIdx >= 0) t.substring(0, atIdx) + completion else completion
                        input = TextFieldValue(newText, TextRange(newText.length))
                    }
                )
            }
            // Subcommand hints — shown after the user has typed a parent with a
            // known sub-verb set (e.g. "/ns ", "/znc list", "/bouncerserv network ").
            else if (subCmdQuery != null) {
                val (parent, verbPrefix) = subCmdQuery
                SubCommandHints(
                    parentCmd = parent,
                    query = verbPrefix,
                    onPick = { completion ->
                        input = TextFieldValue(completion, TextRange(completion.length))
                    }
                )
            }
            // Command hints popup - rendered above the input row inside a Column
            else if (cmdQuery != null) {
                // User-facing commands from loaded .hex scripts (e.g. /tr). Every script alias becomes
                // a command, so drop internal helpers (which contain '_') and anything that duplicates
                // a built-in. This surfaces script commands in the chips without the helper noise.
                val scriptCmdLabel = stringResource(R.string.cmd_script_command)
                val scriptCmdHints = remember(viewModel, scriptCmdLabel) {
                    (viewModel?.scriptEngine?.commandNames() ?: emptyList())
                        .filter { '_' !in it && IRC_COMMANDS.none { c -> c.name.equals(it, ignoreCase = true) } }
                        .sorted()
                        .map { IrcCommand(it, "/$it", description = scriptCmdLabel) }
                }
                CommandHints(
                    query = cmdQuery,
                    scriptCommands = scriptCmdHints,
                    onPick = { completion ->
                        input = TextFieldValue(completion, TextRange(completion.length))
                    }
                )
            }

        Surface(
            tonalElevation = 2.dp,
            color = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .background(bottomBarBrush)
        ) {
            Column(Modifier.fillMaxWidth()) {
            // Typing indicator: shown above the input row whenever someone is composing.
            // Visible even while the user is typing their own message.
            AnimatedVisibility(
                visible = typingNicks.isNotEmpty(),
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
            ) {
                val typingLabel = when (typingNicks.size) {
                    1 -> "${typingNicks.first()} is typing"
                    2 -> {
                        val (a, b) = typingNicks.toList()
                        "$a and $b are typing"
                    }
                    else -> "Several people are typing"
                }
                // Animate three dots cycling 0→1→2→3 dots every 500ms.
                val dotCount by produceState(initialValue = 0) {
                    while (true) {
                        kotlinx.coroutines.delay(500L)
                        value = (value + 1) % 4
                    }
                }
                val dots = ".".repeat(dotCount).padEnd(3, ' ')  // keeps width stable
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = typingLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                    )
                    Text(
                        text = dots,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                    )
                }
            }
            // Reply bar: shown when the user long-pressed a message to reply to it.
            // Must be in the Column (not the Row) so it stacks vertically above the input.
            val replyTarget = pendingReply
            if (replyTarget != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Reply,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = buildString {
                            if (replyTarget.from != null) append("${replyTarget.from}: ")
                            append(stripIrcFormatting(replyTarget.text).take(80))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { pendingReply = null },
                        modifier = Modifier.size(20.dp).focusHighlight(CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.chat_cd_cancel_reply),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // E2E lock badge. Shown at the start of the input row whenever the
                // currently-selected buffer has a key configured. The badge is a
                // single Icon, tapping it opens the EncryptionDialog so the user
                // can verify the safety number / regenerate / clear without
                // having to reach for the overflow menu. The check derives from
                // state.e2eKeyVersion so a key add/remove triggers recomposition
                // automatically. We re-derive via getE2eKeyInfo on each render -
                // it's a single ConcurrentHashMap lookup, no measurable cost.
                if (viewModel != null && selNetId.isNotBlank() &&
                    selBufName.isNotBlank() && selBufName != "*server*" && selBufName != "*status*"
                ) {
                    val keyInfo = remember(selNetId, selBufName, state.e2eKeyVersion) {
                        viewModel.getE2eKeyInfo(selNetId, selBufName)
                    }
                    if (keyInfo != null) {
                        IconButton(
                            onClick = { showEncryptionDialog = true },
                            modifier = Modifier.size(28.dp).focusHighlight(CircleShape),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = stringResource(R.string.chat_cd_encryption_configured, keyInfo.scheme.displayName, keyInfo.fingerprint),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                // Filehost attach button: shown when the server advertises a
                // soju.im/FILEHOST upload endpoint (soju, Ergo, standalone filehost
                // servers). Picks a document, uploads it, and appends the resulting
                // URL to the input so the user can add text before sending.
                val filehostUrl = state.connections[selNetId]?.filehostUrl
                if (viewModel != null && filehostUrl != null) {
                    var uploading by remember { mutableStateOf(false) }
                    val ctxUpload = LocalContext.current
                    val filePicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri != null && !uploading) {
                            uploading = true
                            viewModel.uploadFileToFilehost(selNetId, uri) { url, err ->
                                uploading = false
                                if (url != null) {
                                    val sep = if (input.text.isEmpty() || input.text.endsWith(" ")) "" else " "
                                    val newText = input.text + sep + url
                                    input = input.copy(
                                        text = newText,
                                        selection = TextRange(newText.length)
                                    )
                                } else {
                                    Toast.makeText(ctxUpload, err ?: "Upload failed", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    if (uploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.size(28.dp).focusHighlight(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = stringResource(R.string.chat_cd_upload_file),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                // Build the text style for the input based on active formatting
                val defaultTextColor = MaterialTheme.colorScheme.onSurface
                val inputTextStyle = chatTextStyle.copy(
                    color = selectedFgColor?.let { mircColor(it) } ?: defaultTextColor,
                    fontWeight = if (boldActive) FontWeight.Bold else chatTextStyle.fontWeight,
                    fontStyle = if (italicActive) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (underlineActive) TextDecoration.Underline else TextDecoration.None,
                    background = selectedBgColor?.let { mircColor(it) } ?: Color.Unspecified,
                    // Chat line spacing applies to the backlog, not the composer.
                    // Trim.Both here clamps back to the font's own metrics, so the input
                    // box is the same height on Tight, Normal and Relaxed.
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
                )

				val interactionSource = remember { MutableInteractionSource() }
				val tfColors = OutlinedTextFieldDefaults.colors(
					focusedBorderColor = MaterialTheme.colorScheme.primary,
					unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
					focusedTextColor = MaterialTheme.colorScheme.onSurface,
					unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
					cursorColor = MaterialTheme.colorScheme.primary
				)
				BasicTextField(
					value = input,
					onValueChange = { new ->
                        input = new
                        onTypingChanged(new.text)
                    },
					cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
					modifier = Modifier
						.weight(1f)
						.heightIn(min = 40.dp)
						.tourTarget(TourTarget.CHAT_INPUT)
						.focusRequester(inputFocus)
						.onFocusChanged { inputHasFocus = it.isFocused }
						.onPreviewKeyEvent { ev ->
                            // onPreviewKeyEvent rather than onKeyEvent because the text field's
                            // own key handling consumes Enter for newline insertion before
                            // onKeyEvent fires — particularly on ChromeOS and physical keyboards
                            // attached to Android tablets, where pressing Enter would otherwise
                            // insert a newline rather than send. Preview catches the key first.
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (ev.key) {
                                Key.Enter, Key.NumPadEnter -> {
                                    // Shift+Enter inserts a newline (multiline editing); plain
                                    // Enter sends. Matches Discord, Slack, modern chat conventions.
                                    if (ev.isShiftPressed) return@onPreviewKeyEvent false
                                    sendNow()
                                    true
                                }
                                Key.Tab -> {
                                    // Consumed in a channel even when nothing matched
                                    completeNick(backwards = ev.isShiftPressed) || isChannel
                                }
                                Key.DirectionUp -> {
                                    // Up-arrow recalls input history. Only intercepted when the
                                    // input is empty or the cursor is on the first line — otherwise
                                    // it would steal cursor movement during multi-line editing.
                                    if (inputHistory.isEmpty()) return@onPreviewKeyEvent false
                                    if (input.text.contains('\n') &&
                                        input.selection.start > input.text.indexOf('\n')) {
                                        return@onPreviewKeyEvent false
                                    }
                                    if (historyIndex == -1) inputSnapshot = input.text
                                    val next = (if (historyIndex == -1) inputHistory.lastIndex
                                                else (historyIndex - 1).coerceAtLeast(0))
                                    historyIndex = next
                                    val recalled = inputHistory[next]
                                    input = TextFieldValue(recalled, androidx.compose.ui.text.TextRange(recalled.length))
                                    true
                                }
                                Key.DirectionDown -> {
                                    // Down-arrow walks forward through history; same first-line
                                    // guard as Up so cursor movement still works while editing.
                                    if (historyIndex == -1) return@onPreviewKeyEvent false
                                    if (input.text.contains('\n') &&
                                        input.selection.start <= input.text.lastIndexOf('\n')) {
                                        return@onPreviewKeyEvent false
                                    }
                                    val next = historyIndex + 1
                                    if (next > inputHistory.lastIndex) {
                                        historyIndex = -1
                                        val snap = inputSnapshot
                                        input = TextFieldValue(snap, androidx.compose.ui.text.TextRange(snap.length))
                                    } else {
                                        historyIndex = next
                                        val recalled = inputHistory[next]
                                        input = TextFieldValue(recalled, androidx.compose.ui.text.TextRange(recalled.length))
                                    }
                                    true
                                }
                                else -> false
                            }
                        },
					textStyle = inputTextStyle,
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
					keyboardActions = KeyboardActions(onSend = { sendNow() }),
					singleLine = false,
					// Grow with content up to 6 visible lines before scrolling internally.
					// The old cap of 2 forced any longer draft into a two-line window,
					// which made cursor placement and line changes needlessly fiddly.
					maxLines = 6,
					minLines = 1,
					interactionSource = interactionSource,
					decorationBox = { innerTextField ->
						OutlinedTextFieldDefaults.DecorationBox(
							value = input.text,
							innerTextField = innerTextField,
							enabled = true,
							singleLine = false,
							visualTransformation = VisualTransformation.None,
							interactionSource = interactionSource,
							placeholder = {
								Text(
									text = stringResource(R.string.chat_message_label),
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									style = inputTextStyle
								)
							},
							contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
							colors = tfColors,
							container = {
								OutlinedTextFieldDefaults.Container(
									enabled = true,
									isError = false,
									interactionSource = interactionSource,
									colors = tfColors,
									shape = RoundedCornerShape(4.dp)
								)
							}
						)
					}
				)


                if (isChannel && (canKick || canBan || canTopic)) {
                    val opsInteraction = remember { MutableInteractionSource() }
                    val opsPressed by opsInteraction.collectIsPressedAsState()
                    val accents = LocalAccentColors.current

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .scale(if (opsPressed) 0.92f else 1f)
                            .background(
                                brush = Brush.linearGradient(colors = accents.channelTools),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .focusHighlight(RoundedCornerShape(4.dp))
                            .clickable(
                                interactionSource = opsInteraction,
                                indication = ripple(bounded = false),
                                onClick = { showChanOps = true }
                            )
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Build,
                            contentDescription = stringResource(R.string.chat_channel_tools),
                            tint = accents.onAccent.copy(alpha = if (opsPressed) 0.7f else 1f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }


                run {
                    val sendInteraction = remember { MutableInteractionSource() }
                    val sendPressed by sendInteraction.collectIsPressedAsState()
                    val accents = LocalAccentColors.current

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .scale(if (sendPressed) 0.92f else 1f)
                            .background(
                                brush = Brush.linearGradient(colors = accents.send),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .focusHighlight(RoundedCornerShape(4.dp))
                            .clickable(
                                interactionSource = sendInteraction,
                                indication = ripple(bounded = false),
                                onClick = ::sendNow
                            )
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.chat_send_message),
                            tint = accents.onAccent.copy(alpha = if (sendPressed) 0.7f else 1f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        } // closes Row
        } // closes Column(Modifier.fillMaxWidth) wrapping typing indicator + Row
        } // closes Surface
        } // closes Column wrapper for CommandHints + Surface

    val scaffoldContent: @Composable (PaddingValues) -> Unit = { padding ->
        if (!isWide) {
            // Portrait: either full-width messages, or split messages + nicklist pane
            // When overlay mode is on, use the persisted showNickList (same as landscape)
            if (state.settings.portraitNicklistOverlay && state.showNickList && isChannel) {
                val density = LocalDensity.current
                val portraitScreenW = cfg.screenWidthDp.dp
                val portraitScreenWpx = with(density) { portraitScreenW.toPx().coerceAtLeast(1f) }

                val minPortraitNickFrac = 0.20f
                val maxPortraitNickFrac = 0.55f
                var portraitNickFrac by remember(state.settings.portraitNickPaneFrac) {
                    mutableFloatStateOf(
                        state.settings.portraitNickPaneFrac.coerceIn(
                            minPortraitNickFrac,
                            maxPortraitNickFrac
                        )
                    )
                }
                val nickPaneW = (portraitScreenW * portraitNickFrac).coerceIn(
                    70.dp,
                    portraitScreenW * maxPortraitNickFrac
                )

                var portraitDragging by remember { mutableStateOf(false) }
                val portraitDragSt = rememberDraggableState { dxPx ->
                    val dxFrac = dxPx / portraitScreenWpx
                    portraitNickFrac = (portraitNickFrac - dxFrac).coerceIn(
                        minPortraitNickFrac,
                        maxPortraitNickFrac
                    )
                }

                Row(Modifier
                    .fillMaxSize()
                    .padding(padding)) {
                    MessagesPane(Modifier
                        .weight(1f)
                        .fillMaxHeight())

                    // Thin drag handle
                    val portraitStepPx = with(LocalDensity.current) { 16.dp.toPx() }
                    val savePortraitFrac = {
                        val clamped = portraitNickFrac.coerceIn(
                            minPortraitNickFrac,
                            maxPortraitNickFrac
                        )
                        portraitNickFrac = clamped
                        onUpdateSettings { copy(portraitNickPaneFrac = clamped) }
                    }
                    Box(
                        modifier = Modifier
                            .width(10.dp)
                            .fillMaxHeight()
                            .dpadResize(
                                onLeft = {
                                    portraitNickFrac = (portraitNickFrac + portraitStepPx / portraitScreenWpx)
                                        .coerceIn(minPortraitNickFrac, maxPortraitNickFrac)
                                },
                                onRight = {
                                    portraitNickFrac = (portraitNickFrac - portraitStepPx / portraitScreenWpx)
                                        .coerceIn(minPortraitNickFrac, maxPortraitNickFrac)
                                },
                                onEnd = { savePortraitFrac() },
                            )
                            .draggable(
                                orientation = Orientation.Horizontal,
                                state = portraitDragSt,
                                startDragImmediately = true,
                                onDragStarted = { portraitDragging = true },
                                onDragStopped = {
                                    portraitDragging = false
                                    savePortraitFrac()
                                },
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        VerticalDivider(
                            modifier = Modifier.fillMaxHeight(),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(
                                alpha = if (portraitDragging) 0.8f else 0.3f
                            )
                        )
                    }

                    NicklistContent(
                        mod = Modifier.width(nickPaneW).fillMaxHeight(),
                        nickPaneDp = nickPaneW
                    )
                }
            } else {
                MessagesPane(Modifier
                    .fillMaxSize()
                    .padding(padding))
            }
        } else {

            val density = LocalDensity.current
            val screenWdp = cfg.screenWidthDp.toFloat().coerceAtLeast(1f)
            val screenW = cfg.screenWidthDp.dp
            val screenWpx = with(density) { screenW.toPx().coerceAtLeast(1f) }

            val minBufferDp = 130.dp
            val maxBufferDp = 320.dp
            val minNickDp = 110.dp
            val maxNickDp = 280.dp

            val minBufferFrac = (minBufferDp.value / screenWdp).coerceIn(0.10f, 0.60f)
            val maxBufferFrac = (maxBufferDp.value / screenWdp).coerceIn(0.10f, 0.60f)
            val minNickFrac = (minNickDp.value / screenWdp).coerceIn(0.08f, 0.55f)
            val maxNickFrac = (maxNickDp.value / screenWdp).coerceIn(0.08f, 0.55f)

            // Persisted fractions (updated on drag end). Clamped on the way in: the
            // default sits below minNickFrac on purpose (so the pane opens at its
            // narrowest on any screen)
            var bufferFrac by remember(state.settings.bufferPaneFracLandscape, screenWdp) {
                mutableFloatStateOf(
                    state.settings.bufferPaneFracLandscape.coerceIn(minBufferFrac, maxBufferFrac)
                )
            }
            var nickFrac by remember(state.settings.nickPaneFracLandscape, screenWdp) {
                mutableFloatStateOf(
                    state.settings.nickPaneFracLandscape.coerceIn(minNickFrac, maxNickFrac)
                )
            }

            // Subtle "hint" pulse to make split handles discoverable.
            var showResizeHint by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                showResizeHint = true
                delay(1600)
                showResizeHint = false
            }
            val inf = rememberInfiniteTransition(label = "splitHint")
            val pulseAlpha by inf.animateFloat(
                initialValue = 0.25f,
                targetValue = 0.85f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )
            val handleAlpha = if (showResizeHint) pulseAlpha else 0.25f

            @Composable
            fun SplitHandle(
                onDragDeltaPx: (Float) -> Unit,
                onDragEnd: () -> Unit,
            ) {
                var dragging by remember { mutableStateOf(false) }

                val dragState = rememberDraggableState { deltaPx ->
                    onDragDeltaPx(deltaPx)
                }

                // One D-pad step
                val stepPx = with(LocalDensity.current) { 16.dp.toPx() }

                Box(
                    modifier = Modifier
                        // Bigger touch target helps a lot in landscape / gesture navigation.
                        .width(15.dp)
                        .fillMaxHeight()
                        .dpadResize(
                            onLeft = { onDragDeltaPx(-stepPx) },
                            onRight = { onDragDeltaPx(stepPx) },
                            onEnd = onDragEnd,
                        )
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = dragState,
                            startDragImmediately = true,
                            onDragStarted = { dragging = true },
                            onDragStopped = {
                                dragging = false
                                onDragEnd()
                            },
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        thickness = 2.dp,
                        color = MaterialTheme.colorScheme.outline.copy(
                            alpha = if (dragging) 0.9f else handleAlpha
                        )
                    )
                }
            }


            val bufferPaneW = (screenW * bufferFrac).coerceIn(minBufferDp, maxBufferDp)
            val nickPaneW = (screenW * nickFrac).coerceIn(minNickDp, maxNickDp)

            // In split-pane mode (landscape), keep side panes above the global input bar.
            // Scaffold's padding already accounts for top/bottom bars.
            Row(Modifier
                .fillMaxSize()
                .padding(padding)) {
                if (state.showBufferList || tourWantsBuffers) {
                    Surface(tonalElevation = 1.dp) {
                        BufferDrawer(Modifier
                            .width(bufferPaneW)
                            .fillMaxHeight())
                    }
                    SplitHandle(
                        onDragDeltaPx = { dxPx ->
                            val dxFrac = dxPx / screenWpx
                            bufferFrac =
                                (bufferFrac + dxFrac).coerceIn(minBufferFrac, maxBufferFrac)
                        },
                        onDragEnd = {
                            val clamped = bufferFrac.coerceIn(minBufferFrac, maxBufferFrac)
                            bufferFrac = clamped
                            onUpdateSettings { copy(bufferPaneFracLandscape = clamped) }
                        }
                    )
                }

                MessagesPane(Modifier
                    .weight(1f)
                    .fillMaxHeight())

                if (state.showNickList && isChannel) {
                    SplitHandle(
                        onDragDeltaPx = { dxPx ->
                            // Dragging the boundary right should shrink the nick pane.
                            val dxFrac = dxPx / screenWpx
                            nickFrac = (nickFrac - dxFrac).coerceIn(minNickFrac, maxNickFrac)
                        },
                        onDragEnd = {
                            val clamped = nickFrac.coerceIn(minNickFrac, maxNickFrac)
                            nickFrac = clamped
                            onUpdateSettings { copy(nickPaneFracLandscape = clamped) }
                        }
                    )
                    Surface(tonalElevation = 1.dp) {
                        NicklistContent(
                            mod = Modifier.width(nickPaneW).fillMaxHeight(),
                            nickPaneDp = nickPaneW
                        )
                    }
                }
            }
        }
    }

    val scaffold: @Composable () -> Unit = {
        Scaffold(
            modifier = Modifier
                .onPreviewKeyEvent { ev ->
                    // With a physical keyboard, typing anywhere in the chat goes to the
                    // message field the way a desktop client behaves.
                    if (!hardwareKeyboard || inputHasFocus) return@onPreviewKeyEvent false
                    val typed = typedCharacter(ev) ?: return@onPreviewKeyEvent false
                    val start = input.selection.min
                    val end = input.selection.max
                    val text = input.text.substring(0, start) + typed + input.text.substring(end)
                    input = TextFieldValue(text, TextRange(start + 1))
                    onTypingChanged(text)
                    runCatching { inputFocus.requestFocus() }
                    true
                }
                .windowInsetsPadding(
                    WindowInsets.navigationBars.only(
                        WindowInsetsSides.Horizontal
                    )
                ),
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets(0),
            content = scaffoldContent,
        )
    }

    if (!isWide) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // The bottom bar lists every network and buffer, so the drawer is redundant then: block the
            // swipe-to-open gesture (the drawer button is hidden to match).
            // Also block the open-swipe while the message input has focus: the drawer's
            // drag zone covers the whole screen, so a rightward drag inside the input
            // (moving the cursor, extending a selection) was opening the buffer list
            // instead. Gestures stay enabled while the drawer is OPEN so it can always
            // be swiped closed regardless of focus.
            gesturesEnabled = !state.settings.networkTabsAtBottom &&
                (drawerState.isOpen || !inputHasFocus),
            drawerContent = {
                // Match the landscape pane exactly: surface colour at 1 dp tonal elevation.
                // Without this the portrait drawer uses a different tonal surface token,
                // making it appear a different shade to the landscape buffer list.
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    drawerTonalElevation = 1.dp,
                ) { BufferDrawer() }
            }
        ) {
            scaffold()
        }

        // Bottom sheet mode (original behaviour) – only when overlay is disabled
        if (!state.settings.portraitNicklistOverlay && showNickSheet && isChannel) {
            ModalBottomSheet(
                onDismissRequest = {
                    showNickSheet = false
                }
            ) {
                NicklistContent(Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 520.dp))
            }
        }
    } else {
        scaffold()
    }

    if (showChanOps && isChannel) {
        // Refresh the channel's modes on open so the toggles reflect the live server state.
        LaunchedEffect(showChanOps, selBufName) {
            if (selBufName.isNotBlank() && selBufName != "*server*") onRefreshChannelModes()
        }
        // contentWindowInsets = WindowInsets(0) so we control insets ourselves.
        // imePadding() goes on the OUTER container, not inside the scroll
        ModalBottomSheet(
            onDismissRequest = { showChanOps = false },
            contentWindowInsets = { WindowInsets(0) },
        ) {
            val scrollState = rememberScrollState()
            // Sticky header (title + meta) — never scrolls away
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(stringResource(R.string.chat_channel_tools), style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$selNetName • $selBufName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (currentModeString != null) {
                        Text(
                            currentModeString,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        TextButton(
                            onClick = { onSend("/mode $selBufName") },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                        ) { Text(stringResource(R.string.chat_fetch_modes), style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            HorizontalDivider()
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {

                // Topic
                if (canTopic) {
                    Text(stringResource(R.string.chat_topic_panel), fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = opsTopic,
                        onValueChange = { opsTopic = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        label = { Text(stringResource(R.string.chat_new_topic)) }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val t = opsTopic.trim()
                            onSend(if (t.isBlank()) "/topic $selBufName" else "/topic $selBufName $t")
                            showChanOps = false
                        }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.set)) }
                        OutlinedButton(onClick = { opsTopic = topic ?: "" }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.reset)) }
                    }
                    HorizontalDivider()
                }

                // Channel mode toggles
                if (canMode) {
                    Text(stringResource(R.string.chat_modes_panel), fontWeight = FontWeight.Bold)

                    // Optimistic local copy of the active simple modes.
                    var activeModes by remember(currentModeString) {
                        mutableStateOf(currentModeString?.removePrefix("+") ?: "")
                    }

                    @Composable
                    fun ModeToggle(flag: Char, label: String, description: String) {
                        val active = flag in activeModes
                        fun toggle() {
                            val enable = !active
                            // Optimistic update for instant switch movement.
                            activeModes = if (enable) {
                                if (flag in activeModes) activeModes else activeModes + flag
                            } else {
                                activeModes.filter { it != flag }
                            }
                            onSend("/mode $selBufName ${if (enable) "+" else "-"}$flag")
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .focusHighlight(RoundedCornerShape(8.dp))
                                .clickable { toggle() }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Switch(checked = active, onCheckedChange = { toggle() })
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "+$flag",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    ModeToggle('n', stringResource(R.string.chat_mode_no_external), stringResource(R.string.chat_mode_no_external_desc))
                    ModeToggle('t', stringResource(R.string.chat_mode_ops_topic), stringResource(R.string.chat_mode_ops_topic_desc))
                    ModeToggle('m', stringResource(R.string.chat_mode_moderated), stringResource(R.string.chat_mode_moderated_desc))
                    ModeToggle('i', stringResource(R.string.chat_mode_invite_only), stringResource(R.string.chat_mode_invite_only_desc))
                    ModeToggle('s', stringResource(R.string.chat_mode_secret), stringResource(R.string.chat_mode_secret_desc))
                    ModeToggle('p', stringResource(R.string.chat_mode_private), stringResource(R.string.chat_mode_private_desc))
                    ModeToggle('r', stringResource(R.string.chat_mode_registered), stringResource(R.string.chat_mode_registered_desc))
                    ModeToggle('c', stringResource(R.string.chat_mode_no_colour), stringResource(R.string.chat_mode_no_colour_desc))
                    ModeToggle('C', stringResource(R.string.chat_mode_no_ctcp), stringResource(R.string.chat_mode_no_ctcp_desc))

                    // Key (password)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.chat_mode_key_label), fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium)
                    var keyInput by remember { mutableStateOf("") }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(stringResource(R.string.chat_key_password_label)) }
                        )
                        Button(onClick = {
                            val k = keyInput.trim()
                            if (k.isNotBlank()) onSend("/mode $selBufName +k $k")
                        }, enabled = keyInput.isNotBlank(), modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.set)) }
                        OutlinedButton(onClick = { onSend("/mode $selBufName -k *") }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.ignore_remove)) }
                    }

                    // Limit
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.chat_mode_limit_label), fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium)
                    var limitInput by remember { mutableStateOf("") }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = limitInput,
                            onValueChange = { if (it.all { c -> c.isDigit() }) limitInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(stringResource(R.string.chat_max_users_label)) }
                        )
                        Button(onClick = {
                            val l = limitInput.trim()
                            if (l.isNotBlank()) onSend("/mode $selBufName +l $l")
                        }, enabled = limitInput.isNotBlank(), modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.set)) }
                        OutlinedButton(onClick = { onSend("/mode $selBufName -l"); limitInput = "" }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.ignore_remove)) }
                    }

                    HorizontalDivider()
                }

                // Kick / Ban
                if (canKick || canBan) {
                    Text(stringResource(R.string.chat_mode_kick_ban), fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = opsNick,
                        onValueChange = { opsNick = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.chat_nick)) }
                    )
                    OutlinedTextField(
                        value = opsReason,
                        onValueChange = { opsReason = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.chat_reason)) }
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (canKick) {
                            Button(
                                onClick = {
                                    val n = opsNick.trim()
                                    if (n.isNotBlank()) {
                                        val r = opsReason.trim()
                                        onSend(if (r.isBlank()) "/kick $selBufName $n" else "/kick $selBufName $n $r")
                                        showChanOps = false
                                    }
                                }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                            ) { Text(stringResource(R.string.chat_kick)) }
                        }
                        if (canBan) {
                            OutlinedButton(
                                onClick = {
                                    val n = opsNick.trim()
                                    if (n.isNotBlank()) {
                                        onSend("/ban $selBufName $n")
                                        showChanOps = false
                                    }
                                }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                            ) { Text(stringResource(R.string.chat_ban)) }
                            OutlinedButton(
                                onClick = {
                                    val n = opsNick.trim()
                                    if (n.isNotBlank()) {
                                        val r = opsReason.trim()
                                        onSend(if (r.isBlank()) "/kb $selBufName $n" else "/kb $selBufName $n $r")
                                        showChanOps = false
                                    }
                                }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                            ) { Text(stringResource(R.string.chat_kick_ban_btn)) }
                        }
                    }
                    if (canBan) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                showChanOps = false
                                chanListTab = 0
                                showChanListSheet = true
                            }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                        ) { Text(stringResource(R.string.chat_channel_lists)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // IRCop tools
    if (showIrcOpTools) {
        // Insets are handled by the content, not the sheet: see the note on the channel
        // tools sheet above.
        ModalBottomSheet(
            onDismissRequest = { showIrcOpTools = false },
            contentWindowInsets = { WindowInsets(0) },
        ) {
            val scrollState = rememberScrollState()
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .padding(16.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AdminPanelSettings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.chat_ircop_tools), style = MaterialTheme.typography.titleLarge)
                }
                Text("$selNetName", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()

                var opTarget by remember { mutableStateOf("") }
                var opReason by remember { mutableStateOf("") }
                var opDuration by remember { mutableStateOf("") }
                var opServer by remember { mutableStateOf("") }
                var opMessage by remember { mutableStateOf("") }

                // Target / Reason fields
                Text(stringResource(R.string.chat_target_label), fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = opTarget, onValueChange = { opTarget = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.chat_nick_mask_label)) }
                )
                OutlinedTextField(
                    value = opReason, onValueChange = { opReason = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.chat_reason)) }
                )
                // Duration for the timed *-line / shun commands. Format is server-specific
                // (e.g. "1d", "2h", "30" minutes); "0" usually means permanent.
                OutlinedTextField(
                    value = opDuration, onValueChange = { opDuration = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.chat_duration_label)) },
                    supportingText = { Text(stringResource(R.string.chat_duration_hint)) }
                )

                // Kill / K-line / Z-line
                Text(stringResource(R.string.chat_punishments), fontWeight = FontWeight.Bold)
                val noReasonStr = stringResource(R.string.chat_no_reason)
                // The timed line/shun commands take <mask> <duration> [reason]; require both a
                // target and a duration so we never send a bare "<mask> <reason>" that the
                // server would misparse.
                val canLine = opTarget.isNotBlank() && opDuration.isNotBlank()
                // FlowRow so the buttons wrap onto extra lines on narrow screens instead of
                // overflowing off the right edge. Both rows of punishments are merged into one
                // wrapping group.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val t = opTarget.trim()
                            if (t.isNotBlank()) {
                                val r = opReason.trim().ifBlank { noReasonStr }
                                onSend("/kill $t $r")
                                showIrcOpTools = false
                            }
                        },
                        enabled = opTarget.isNotBlank(), modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(R.string.ircop_kill)) }
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim(); val d = opDuration.trim()
                            if (t.isNotBlank() && d.isNotBlank()) {
                                val r = opReason.trim().ifBlank { noReasonStr }
                                onSend("/kline $t $d $r")
                                showIrcOpTools = false
                            }
                        },
                        enabled = canLine, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(R.string.ircop_kline)) }
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim(); val d = opDuration.trim()
                            if (t.isNotBlank() && d.isNotBlank()) {
                                val r = opReason.trim().ifBlank { noReasonStr }
                                onSend("/zline $t $d $r")
                                showIrcOpTools = false
                            }
                        },
                        enabled = canLine, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(R.string.ircop_zline)) }
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim(); val d = opDuration.trim()
                            if (t.isNotBlank() && d.isNotBlank()) {
                                val r = opReason.trim().ifBlank { noReasonStr }
                                onSend("/gline $t $d $r")
                                showIrcOpTools = false
                            }
                        },
                        enabled = canLine, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(R.string.ircop_gline)) }
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim(); val d = opDuration.trim()
                            if (t.isNotBlank() && d.isNotBlank()) {
                                val r = opReason.trim().ifBlank { noReasonStr }
                                onSend("/shun $t $d $r")
                            }
                        },
                        enabled = canLine, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(R.string.ircop_shun)) }
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim(); val d = opDuration.trim()
                            if (t.isNotBlank() && d.isNotBlank()) {
                                val r = opReason.trim().ifBlank { noReasonStr }
                                onSend("/dline $t $d $r")
                            }
                        },
                        enabled = canLine, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(R.string.ircop_dline)) }
                }

                HorizontalDivider()

                // Force join/part
                Text(stringResource(R.string.chat_force_joinpart), fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = opServer, onValueChange = { opServer = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.chat_channel_label)) }
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val t = opTarget.trim(); val ch = opServer.trim()
                            if (t.isNotBlank() && ch.isNotBlank()) onSend("/sajoin $t $ch")
                        },
                        enabled = opTarget.isNotBlank() && opServer.isNotBlank(), modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(R.string.ircop_sajoin)) }
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim(); val ch = opServer.trim()
                            if (t.isNotBlank() && ch.isNotBlank()) onSend("/sapart $t $ch")
                        },
                        enabled = opTarget.isNotBlank() && opServer.isNotBlank(), modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(R.string.ircop_sapart)) }
                }

                HorizontalDivider()

                // Broadcast messages
                Text(stringResource(R.string.chat_broadcast), fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = opMessage, onValueChange = { opMessage = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.chat_message_label)) }
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { if (opMessage.isNotBlank()) onSend("/wallops ${opMessage.trim()}") },
                        enabled = opMessage.isNotBlank(), modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(R.string.ircop_wallops)) }
                    OutlinedButton(
                        onClick = { if (opMessage.isNotBlank()) onSend("/globops ${opMessage.trim()}") },
                        enabled = opMessage.isNotBlank(), modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(R.string.ircop_globops)) }
                    OutlinedButton(
                        onClick = { if (opMessage.isNotBlank()) onSend("/locops ${opMessage.trim()}") },
                        enabled = opMessage.isNotBlank(), modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(R.string.ircop_locops)) }
                }

                HorizontalDivider()

                // Server queries
                Text(stringResource(R.string.chat_server_queries), fontWeight = FontWeight.Bold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { onSend("/motd"); showIrcOpTools = false }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.ircop_motd)) }
                    OutlinedButton(onClick = { onSend("/admin"); showIrcOpTools = false }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.ircop_admin)) }
                    OutlinedButton(onClick = { onSend("/stats u"); showIrcOpTools = false }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.ircop_uptime)) }
                    OutlinedButton(onClick = { onSend("/stats l"); showIrcOpTools = false }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.ircop_links)) }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // mIRC colour/style picker sheet
    if (showColorPicker) {
        // ── IRC Text Formatting - full 99-colour mIRC grid picker ─────────────────────
        // Layout: live preview -> style chips -> colour mode tab -> 99-colour grid -> hex label
        //
        // The grid renders all 99 mIRC colour codes in the standard layout:
        //   Row 0 (cols 0-15):  legacy 16 colours
        //   Rows 1-5 (cols 16-98): extended colours, 16 per row (last row partial)
        // Selecting a swatch in "FG" mode sets the text colour; "BG" sets the highlight.
        // Tapping an active swatch deselects it.

        var colorMode by remember { mutableStateOf(0) } // 0 = FG, 1 = BG

        // Insets are handled by the content, not the sheet: see the note on the channel
        // tools sheet above.
        ModalBottomSheet(
            onDismissRequest = { showColorPicker = false },
            sheetMaxWidth = 600.dp,
            contentWindowInsets = { WindowInsets(0) },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // ── Header ────────────────────────────────────────────────────
                Text(stringResource(R.string.chat_text_formatting), style = MaterialTheme.typography.titleMedium)

                // ── Live preview ──────────────────────────────────────────────
                val previewText = buildAnnotatedString {
                    val styleState = MircStyleState(
                        fg = selectedFgColor,
                        bg = selectedBgColor,
                        bold = boldActive,
                        italic = italicActive,
                        underline = underlineActive,
                        reverse = reverseActive
                    )
                    withStyle(styleState.toSpanStyle()) {
                        append(stringResource(R.string.chat_font_preview))
                    }
                }
                Surface(
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = previewText,
                        modifier = Modifier.padding(12.dp),
                        style = chatTextStyle
                    )
                }

                // ── Style chips (B / I / U / Rev) ─────────────────────────────
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = boldActive,
                        onClick = { boldActive = !boldActive },
                        label = { Text(stringResource(R.string.chat_fmt_bold), fontWeight = FontWeight.Bold) }
                    )
                    FilterChip(
                        selected = italicActive,
                        onClick = { italicActive = !italicActive },
                        label = { Text(stringResource(R.string.chat_fmt_italic), fontStyle = FontStyle.Italic) }
                    )
                    FilterChip(
                        selected = underlineActive,
                        onClick = { underlineActive = !underlineActive },
                        label = { Text(stringResource(R.string.chat_fmt_underline), textDecoration = TextDecoration.Underline) }
                    )
                    FilterChip(
                        selected = reverseActive,
                        onClick = { reverseActive = !reverseActive },
                        label = { Text(stringResource(R.string.chat_fmt_reverse)) }
                    )
                    Spacer(Modifier.weight(1f))
                    // Active colour chips showing current selection
                    if (selectedFgColor != null) {
                        val fgCol = mircColor(selectedFgColor!!) ?: Color.Gray
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = fgCol,
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .size(28.dp)
                                .focusHighlight(CircleShape)
                                .clickable { selectedFgColor = null }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.chat_fmt_colour), style = MaterialTheme.typography.labelSmall,
                                    color = if (fgCol.luminance() > 0.4f) Color.Black else Color.White)
                            }
                        }
                    }
                    if (selectedBgColor != null) {
                        val bgCol = mircColor(selectedBgColor!!) ?: Color.Gray
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = bgCol,
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .size(28.dp)
                                .focusHighlight(CircleShape)
                                .clickable { selectedBgColor = null }
                        ) {}
                    }
                }

                // ── FG / BG mode selector ─────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // FG tab
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (colorMode == 0) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .focusHighlight(RoundedCornerShape(8.dp))
                            .clickable { colorMode = 0 }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .background(
                                        selectedFgColor?.let { mircColor(it) } ?: Color(0xFF888888),
                                        CircleShape
                                    )
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            )
                            Text(stringResource(R.string.chat_text_colour),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (colorMode == 0) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (selectedFgColor != null) {
                                Text("#${"%06X".format(MIRC_PALETTE.getOrNull(selectedFgColor!!)?.and(0xFFFFFF) ?: 0)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    // BG tab
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (colorMode == 1) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .focusHighlight(RoundedCornerShape(8.dp))
                            .clickable { colorMode = 1 }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .background(
                                        selectedBgColor?.let { mircColor(it) } ?: Color(0xFF888888),
                                        CircleShape
                                    )
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            )
                            Text(stringResource(R.string.chat_bg_colour),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (colorMode == 1) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (selectedBgColor != null) {
                                Text("#${"%06X".format(MIRC_PALETTE.getOrNull(selectedBgColor!!)?.and(0xFFFFFF) ?: 0)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // ── Colour grid ───────────────────────────────────────────────
                //
                // Layout matches the standard mIRC / HexChat / WeeChat colour picker:
                //
                //  ┌────────────────────────────────────────────────────────┐
                //  │  0–15  │ original 16-colour row (full width)           │
                //  ├────────────────────────────────────────────────────────┤
                //  │ 16–87  │ 6 rows × 12 columns colour-spectrum gradient  │
                //  ├────────────────────────────────────────────────────────┤
                //  │ 88–98  │ greyscale ramp row (11 swatches)              │
                //  └────────────────────────────────────────────────────────┘
                //
                // The 6×12 block reads top-to-bottom as darkest->lightest and
                // left-to-right as red->orange->yellow->green->cyan->blue->purple->pink,
                // producing the gradient effect familiar from desktop IRC clients.

                val activeSel = if (colorMode == 0) selectedFgColor else selectedBgColor

                @Composable
                fun ColorSwatch(code: Int, modifier: Modifier = Modifier) {
                    val col = mircColor(code) ?: return
                    val isSelected = activeSel == code
                    Box(
                        modifier = modifier
                            .background(col)
                            .then(
                                if (isSelected) Modifier.border(2.dp, Color.White)
                                else Modifier
                            )
                            .focusHighlight(RoundedCornerShape(4.dp))
                            .clickable {
                                if (colorMode == 0)
                                    selectedFgColor = if (selectedFgColor == code) null else code
                                else
                                    selectedBgColor = if (selectedBgColor == code) null else code
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            // Filled circle marker - visible on both light and dark swatches
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .background(
                                        if (col.luminance() > 0.45f) Color(0xB3000000)
                                        else Color(0xCCFFFFFF),
                                        CircleShape
                                    )
                            )
                        }
                    }
                }

                // Row 0: legacy 16 colours - square swatches, full width
                Row(Modifier.fillMaxWidth()) {
                    for (code in 0 until 16) {
                        ColorSwatch(
                            code = code,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))

                // Rows 1–6: extended colour spectrum, 12 columns × 6 rows (codes 16–87).
                // The column axis maps to hue; the row axis maps to lightness (dark->light).
                // This creates the gradient grid that looks like a mini HTML colour picker.
                val spectrumCols = 12
                for (row in 0 until 6) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until spectrumCols) {
                            val code = 16 + row * spectrumCols + col
                            ColorSwatch(
                                code = code,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(0.85f) // slightly taller than wide - better touch target
                            )
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))

                // Greyscale ramp: codes 88–98 (11 swatches - black to silver)
                Row(Modifier.fillMaxWidth()) {
                    for (code in 88 until MIRC_COLOR_COUNT) {
                        ColorSwatch(
                            code = code,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                        )
                    }
                    // Pad to full width (16 cols) with transparent spacer so swatches aren't oversized
                    val greyPad = 16 - (MIRC_COLOR_COUNT - 88)
                    if (greyPad > 0) Spacer(Modifier.weight(greyPad.toFloat()))
                }

                // ── Hex label for hovered/selected colour ─────────────────────
                val labelCode = activeSel
                if (labelCode != null) {
                    val labelColor = mircColor(labelCode) ?: Color.Gray
                    val hexStr = "#%06X".format(MIRC_PALETTE.getOrNull(labelCode)?.and(0xFFFFFF) ?: 0)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.size(18.dp).background(labelColor, RoundedCornerShape(4.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)))
                        Text(
                            "Code $labelCode  $hexStr",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ── Bottom buttons ─────────────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            selectedFgColor = null
                            selectedBgColor = null
                            boldActive = false
                            italicActive = false
                            underlineActive = false
                            reverseActive = false
                        },
                        modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(R.string.chat_clear_all)) }

                    Button(
                        onClick = { showColorPicker = false },
                        modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(R.string.done)) }
                }
            }
        }
    }

    if (showChanListSheet && isChannel) {
        val banTimeFmt =
            remember { SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.getDefault()) }

        val listModes = state.connections[selNetId]?.listModes ?: "bqeI"
        val supportsQuiet = listModes.contains('q')
        val supportsExcept = listModes.contains('e')
        val supportsInvex = listModes.contains('I')
        val extbanPrefix = state.connections[selNetId]?.extbanPrefix
        val extbanTypes = state.connections[selNetId]?.extbanTypes
        val accountExtban = state.connections[selNetId]?.accountExtban
        var banInput by remember(selected, chanListTab) { mutableStateOf("") }

        LaunchedEffect(showChanListSheet, listModes) {
            if (!showChanListSheet) return@LaunchedEffect
            // If the currently selected tab isn't supported by this ircd, fall back to bans.
            if (chanListTab == 1 && !supportsQuiet) chanListTab = 0
            if (chanListTab == 2 && !supportsExcept) chanListTab = 0
            if (chanListTab == 3 && !supportsInvex) chanListTab = 0
        }

        fun refreshCurrentList() {
            when (chanListTab) {
                0 -> onSend("/banlist")
                1 -> if (supportsQuiet) onSend("/quietlist") else onSend("/banlist")
                2 -> if (supportsExcept) onSend("/exceptlist") else onSend("/banlist")
                3 -> if (supportsInvex) onSend("/invexlist") else onSend("/banlist")
            }
        }

        LaunchedEffect(showChanListSheet, selected, chanListTab) {
            if (showChanListSheet) refreshCurrentList()
        }

        data class ListUi(
            val title: String,
            val entries: List<com.boxlabs.hexdroid.BanEntry>,
            val loading: Boolean,
            val removeLabel: String,
            val removeMode: String,
            val refreshCmd: String,
        )

        val ui = when (chanListTab) {
            0 -> ListUi(
                stringResource(R.string.chat_ban_list),
                state.banlists[selected].orEmpty(),
                state.banlistLoading[selected] == true,
                stringResource(R.string.chat_unban),
                "b",
                "/banlist"
            )

            1 -> ListUi(
                stringResource(R.string.chat_quiet_list),
                state.quietlists[selected].orEmpty(),
                state.quietlistLoading[selected] == true,
                stringResource(R.string.chat_unquiet),
                "q",
                "/quietlist"
            )

            2 -> ListUi(
                stringResource(R.string.chat_except_list),
                state.exceptlists[selected].orEmpty(),
                state.exceptlistLoading[selected] == true,
                stringResource(R.string.chat_remove),
                "e",
                "/exceptlist"
            )

            else -> ListUi(
                stringResource(R.string.chat_invex_list),
                state.invexlists[selected].orEmpty(),
                state.invexlistLoading[selected] == true,
                stringResource(R.string.chat_remove),
                "I",
                "/invexlist"
            )
        }

        // Insets are handled by the content, not the sheet: see the note on the channel
        // tools sheet above.
        ModalBottomSheet(
            onDismissRequest = { showChanListSheet = false },
            contentWindowInsets = { WindowInsets(0) },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .padding(16.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ui.title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (ui.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                Text("$selNetName • $selBufName", style = MaterialTheme.typography.bodySmall)

                // Get context once, safely inside the composable scope
                val context = LocalContext.current

                val noQuietSupportStr = stringResource(R.string.chat_no_quiet_support)
                val noExceptSupportStr = stringResource(R.string.chat_no_except_support)
                val noInvexSupportStr = stringResource(R.string.chat_no_invex_support)


                PrimaryTabRow(selectedTabIndex = chanListTab) {
                    Tab(
                        selected = chanListTab == 0,
                        onClick = { chanListTab = 0 }
                    ) { Text(stringResource(R.string.chat_bans_title)) }

                    Tab(
                        selected = chanListTab == 1,
                        onClick = {
                            if (supportsQuiet) {
                                chanListTab = 1
                            } else {
                                Toast.makeText(
                                    context,  // ← use the captured context here
                                    noQuietSupportStr,
                                    Toast.LENGTH_SHORT
                                ).show()
                                chanListTab = 0
                            }
                        }
                    ) { Text(stringResource(R.string.chat_quiets_title)) }

                    Tab(
                        selected = chanListTab == 2,
                        onClick = {
                            if (supportsExcept) {
                                chanListTab = 2
                            } else {
                                Toast.makeText(
                                    context,
                                    noExceptSupportStr,
                                    Toast.LENGTH_SHORT
                                ).show()
                                chanListTab = 0
                            }
                        }
                    ) { Text(stringResource(R.string.chat_except_title)) }

                    Tab(
                        selected = chanListTab == 3,
                        onClick = {
                            if (supportsInvex) {
                                chanListTab = 3
                            } else {
                                Toast.makeText(
                                    context,
                                    noInvexSupportStr,
                                    Toast.LENGTH_SHORT
                                ).show()
                                chanListTab = 0
                            }
                        }
                    ) { Text(stringResource(R.string.chat_invex_title)) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val canRefresh = when (chanListTab) {
                        0 -> true
                        1 -> supportsQuiet
                        2 -> supportsExcept
                        else -> supportsInvex
                    }
                    OutlinedButton(enabled = canRefresh, onClick = { refreshCurrentList() }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                        Text(stringResource(R.string.chat_refresh))
                    }
                    OutlinedButton(onClick = { showChanListSheet = false }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.close)) }
                }

                HorizontalDivider()

                if (!ui.loading && ui.entries.isEmpty()) {
                    val unsupportedMsg = when (chanListTab) {
                        1 -> if (!supportsQuiet) stringResource(R.string.chat_no_quiet_server) else null
                        2 -> if (!supportsExcept) stringResource(R.string.chat_no_except_server) else null
                        3 -> if (!supportsInvex) stringResource(R.string.chat_no_invex_server) else null
                        else -> null
                    }
                    Text(unsupportedMsg ?: stringResource(R.string.chat_no_entries), style = chatTextStyle)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(ui.entries.distinctBy { it.mask }, key = { it.mask }) { e ->
                            Surface(
                                tonalElevation = 1.dp,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(e.mask, fontWeight = FontWeight.Bold)
                                        val by = e.setBy?.takeIf { it.isNotBlank() }
                                        val at = e.setAtMs?.let { banTimeFmt.format(Date(it)) }
                                        val meta = buildList {
                                            if (by != null) add(stringResource(R.string.chat_set_by, by))
                                            if (at != null) add("at $at")
                                        }.joinToString(" ")
                                        if (meta.isNotBlank()) {
                                            Text(meta, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    OutlinedButton(
                                        enabled = canBan,
                                        onClick = {
                                            scope.launch {
                                                onSend("/mode $selBufName -${ui.removeMode} ${e.mask}")
                                                delay(250)
                                                onSend(ui.refreshCmd)
                                            }
                                        }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                                    ) { Text(ui.removeLabel) }
                                }
                            }
                        }
                    }
                }

                if (canBan) {
                    HorizontalDivider()
                    Column(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = banInput,
                                onValueChange = { banInput = it },
                                label = { Text(stringResource(R.string.chat_add_mask)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                enabled = banInput.isNotBlank(),
                                onClick = {
                                    val mask = banInput.trim()
                                    scope.launch {
                                        onSend("/mode $selBufName +${ui.removeMode} $mask")
                                        delay(250)
                                        onSend(ui.refreshCmd)
                                    }
                                    banInput = ""
                                }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                            ) { Text(stringResource(R.string.chat_add)) }
                        }
                        // Extended-ban helpers (EXTBAN / draft/account-extban). Only shown on
                        // the ban and quiet tabs, where extbans apply.
                        if (extbanTypes != null && chanListTab <= 1) {
                            if (accountExtban != null) {
                                OutlinedButton(
                                    enabled = banInput.isNotBlank(),
                                    onClick = {
                                        // Treat the field as an account name and wrap it as the
                                        // account extban, e.g. ~a:someone.
                                        val mask = "${extbanPrefix ?: ""}$accountExtban:${banInput.trim()}"
                                        scope.launch {
                                            onSend("/mode $selBufName +${ui.removeMode} $mask")
                                            delay(250)
                                            onSend(ui.refreshCmd)
                                        }
                                        banInput = ""
                                    }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                                ) { Text(stringResource(R.string.chat_ban_account)) }
                            }
                            Text(
                                stringResource(R.string.chat_extban_hint, extbanPrefix ?: "", extbanTypes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }



    // Per-target end-to-end encryption dialog. Reached from the overflow menu when
    // the active buffer is a channel or query (the menu entry hides itself on the
    // server buffer because it has no remote correspondent). The dialog itself
    // handles its full lifecycle - generate/import/clear/share/clear - and we just
    // own the open/close state here so that switching buffers auto-closes it via
    // the remember(selected) reset above.
    if (showEncryptionDialog && viewModel != null && selNetId.isNotBlank() && selBufName.isNotBlank()) {
        EncryptionDialog(
            networkId = selNetId,
            target = selBufName,
            viewModel = viewModel,
            onDismiss = { showEncryptionDialog = false },
        )
    }

    if (showMetadataEditor && viewModel != null && selNetId.isNotBlank()) {
        MetadataEditorDialog(
            networkId = selNetId,
            viewModel = viewModel,
            onDismiss = { showMetadataEditor = false },
        )
    }

    if (showRegistration && viewModel != null && selNetId.isNotBlank()) {
        RegistrationDialog(
            networkId = selNetId,
            viewModel = viewModel,
            onDismiss = { showRegistration = false },
        )
    }

    // Topic quick-edit dialog appears when an op long-presses the topic bar.
    if (showTopicQuickEdit && canTopic) {
        var editTopicText by remember(topic) { mutableStateOf(topic ?: "") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTopicQuickEdit = false },
            title = { Text(stringResource(R.string.topic_edit_title)) },
            text = {
                OutlinedTextField(
                    value = editTopicText,
                    onValueChange = { editTopicText = it },
                    label = { Text(stringResource(R.string.topic_edit_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 4,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = editTopicText.trim()
                    onSend("/topic $selBufName $t")
                    showTopicQuickEdit = false
                }, modifier = Modifier.tvInitialFocus().focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.topic_set)) }
            },
            dismissButton = {
                TextButton(onClick = { showTopicQuickEdit = false }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.cancel)) }
            }
        )
    }


    // Message long-press context sheet; Copy and Reply actions.
    val ctxMsg = longPressedMessage
    if (ctxMsg != null) {
        val plainCtx = buildString {
            if (ctxMsg.from != null) append("<${ctxMsg.from}> ")
            append(stripIrcFormatting(ctxMsg.text))
        }
        ModalBottomSheet(onDismissRequest = { longPressedMessage = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                // Preview of the message being acted on
                Text(
                    text = plainCtx,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                // Quick reactions; only for messages with a server msgId (requires message-tags)
                if (ctxMsg.msgId != null) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        for (emoji in listOf("👍", "👎", "❤️", "😂", "😮", "🎉")) {
                            Text(
                                text = emoji,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier
                                    .focusHighlight(RoundedCornerShape(24.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = false, radius = 24.dp),
                                    ) {
                                        if (hasReactionSupport) {
                                            onSendReaction(ctxMsg.msgId, emoji, false)
                                            longPressedMessage = null
                                        } else {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "This server doesn't support reactions",
                                                    duration = androidx.compose.material3.SnackbarDuration.Short,
                                                )
                                            }
                                        }
                                    }
                                    .padding(8.dp),
                            )
                        }
                    }
                }
                HorizontalDivider()
                // Reply; only for channel/PM messages that have a real sender
                if (ctxMsg.from != null && !ctxMsg.isMotd) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.chat_reply_to, ctxMsg.from)) },
                        leadingContent = {
                            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null)
                        },
                        modifier = Modifier.focusHighlight().clickable {
                            pendingReply = ctxMsg
                            longPressedMessage = null
                        }
                    )
                }
                // Delete (IRCv3 message-redaction); own messages with a server msgId only.
                // The buffer updates when the server relays the REDACT back, so a FAIL
                // leaves the message intact rather than vanishing locally but not remotely.
                if (viewModel != null && ctxMsg.msgId != null && ctxMsg.from != null &&
                    ctxMsg.from.equals(myNick, ignoreCase = true) &&
                    state.connections[selNetId]?.hasRedactionSupport == true
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.chat_delete_message)) },
                        supportingContent = { Text(stringResource(R.string.chat_delete_message_desc)) },
                        leadingContent = {
                            Icon(Icons.Default.Delete, contentDescription = null)
                        },
                        modifier = Modifier.focusHighlight().clickable {
                            viewModel.redactMessage(selNetId, selBufName, ctxMsg.msgId)
                            longPressedMessage = null
                        }
                    )
                }
                // Copy
                ListItem(
                    headlineContent = { Text(stringResource(R.string.copy)) },
                    leadingContent = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    },
                    modifier = Modifier.focusHighlight().clickable {
                        scope.launch {
                            clipboard.setClipEntry(
                                android.content.ClipData.newPlainText("", plainCtx).toClipEntry()
                            )
                        }
                        longPressedMessage = null
                    }
                )
                // Copy messages range picker
                ListItem(
                    headlineContent = { Text(stringResource(R.string.chat_copy_messages)) },
                    supportingContent = { Text(stringResource(R.string.chat_copy_messages_desc)) },
                    leadingContent = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    },
                    modifier = Modifier.focusHighlight().clickable {
                        val m = longPressedMessage
                        longPressedMessage = null
                        copyRangeMode = true
                        selectedMsgIds = if (m != null) setOf(m.id) else emptySet()
                    }
                )
                // Links contained in the message. Inline links open by tap position
                // only, which D-pad and keyboard navigation can never reach, so this
                // list is the accessible path (Android TV, ChromeOS, hardware keys).
                val ctxUrls = remember(ctxMsg.id) {
                    urlRegex.findAll(ctxMsg.text).map { it.value }.distinct().take(5).toList()
                }
                if (ctxUrls.isNotEmpty()) HorizontalDivider()
                for (url in ctxUrls) {
                    ListItem(
                        headlineContent = { Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Icon(Icons.Default.Link, contentDescription = null)
                        },
                        modifier = Modifier.focusHighlight().clickable {
                            longPressedMessage = null
                            runCatching { uriHandler.openUri(url) }
                        }
                    )
                }
            }
        }
    }

    confirmAutoAcceptFor?.let { pendingNick ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmAutoAcceptFor = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.dcc_auto_confirm_title, pendingNick)) },
            text = { Text(stringResource(R.string.dcc_auto_confirm_body, pendingNick)) },
            confirmButton = {
                TextButton(onClick = {
                    onSetDccAutoAccept(selNetId, pendingNick, true)
                    confirmAutoAcceptFor = null
                    showNickActions = false
                }, modifier = Modifier.tvInitialFocus().focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.dcc_auto_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAutoAcceptFor = null }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showNickActions && selectedNick.isNotBlank()) {
        val dccEnabled = state.settings.dccEnabled
        val dccSecure  = state.settings.dccSecure
        ModalBottomSheet(onDismissRequest = { showNickActions = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // Header
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val sheetBase = selectedNick.lowercase()
                    val sheetAvatar = metadataAvatars[sheetBase]
                    val sheetDisplayName = metadataDisplayNames[sheetBase]
                        ?.takeIf { !it.equals(selectedNick, ignoreCase = true) }
                    val sheetStatus = metadataStatuses[sheetBase]
                    val sheetAway = awayNicks.contains(sheetBase)
                    val sheetBot = botNicks.contains(sheetBase)
                    val sheetExtra = state.connections[selNetId]?.extraMetadata?.get(sheetBase) ?: emptyMap()
                    val sheetUri = androidx.compose.ui.platform.LocalUriHandler.current
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        // Avatar (draft/metadata-2), gated identically to the message rows:
                        // only when image previews are on and this profile is unproxied.
                        if (sheetAvatar != null) {
                            var bmp by remember(sheetAvatar) { mutableStateOf(RemoteImage.cached(sheetAvatar)) }
                            LaunchedEffect(sheetAvatar) { if (bmp == null) bmp = RemoteImage.fetch(sheetAvatar) }
                            bmp?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(44.dp).clip(CircleShape),
                                )
                            }
                        }
                        Column {
                            Text(selectedNick, style = MaterialTheme.typography.titleLarge)
                            if (sheetBot) {
                                Text(
                                    stringResource(R.string.nick_bot),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF7E9CD8),
                                )
                            }
                            if (sheetDisplayName != null) {
                                Text(
                                    sheetDisplayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            sheetExtra["pronouns"]?.let { pr ->
                                Text(
                                    pr,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (sheetAway) {
                                Text(
                                    stringResource(R.string.nick_away),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (sheetStatus != null) {
                                Text(
                                    sheetStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            sheetExtra["bio"]?.let { bio ->
                                Text(
                                    bio,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            sheetExtra["homepage"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { hp ->
                                Text(
                                    hp,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .focusHighlight(RoundedCornerShape(4.dp))
                                        .clickable { runCatching { sheetUri.openUri(hp) } },
                                )
                            }
                        }
                    }
                    // Show mode badge if nick has a prefix (e.g. @, +)
                    val dispNick = nickDisplayByBase[selectedNick.lowercase()]
                    val prefix = dispNick?.let { nickPrefix(it) }
                    if (prefix != null) {
                        val (badgeColor, badgeLabel) = when (prefix) {
                            '~' -> Pair(Color(0xFFFF6B35), "owner")
                            '&' -> Pair(Color(0xFFE63946), "admin")
                            '@' -> Pair(Color(0xFF2196F3), "op")
                            '%' -> Pair(Color(0xFF4CAF50), "halfop")
                            '+' -> Pair(Color(0xFF9E9E9E), "voice")
                            else -> Pair(MaterialTheme.colorScheme.surfaceVariant, prefix.toString())
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = badgeColor
                        ) {
                            Text(
                                badgeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                HorizontalDivider()

                // ── Communication ───────────────────────────────────────────
                @Composable
                fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, subtitle: String? = null, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight(RoundedCornerShape(8.dp))
                            .let { if (enabled) it.clickable(onClick = onClick) else it }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .alpha(if (enabled) 1f else 0.38f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                ActionRow(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    label = stringResource(R.string.nick_open_query),
                    subtitle = stringResource(R.string.nick_open_query_desc),
                    onClick = { onSelectBuffer("$selNetId::$selectedNick"); showNickActions = false }
                )
                ActionRow(
                    icon = Icons.Default.PersonSearch,
                    label = stringResource(R.string.nick_whois),
                    subtitle = stringResource(R.string.nick_whois_desc),
                    onClick = { onWhois(selectedNick); showNickActions = false }
                )
                ActionRow(
                    icon = Icons.Default.AlternateEmail,
                    label = stringResource(R.string.nick_mention),
                    subtitle = stringResource(R.string.nick_mention_desc, selectedNick),
                    onClick = { mention(selectedNick); showNickActions = false }
                )

                // ── DCC ─────────────────────────────────────────────────────
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    stringResource(R.string.nick_dcc_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                ActionRow(
                    icon = Icons.AutoMirrored.Filled.SendToMobile,
                    label = stringResource(R.string.nick_send_file),
                    subtitle = when {
                        !dccEnabled -> stringResource(R.string.nick_dcc_disabled)
                        dccSecure   -> stringResource(R.string.nick_send_file_desc) + " (SDCC/TLS)"
                        else        -> stringResource(R.string.nick_send_file_desc)
                    },
                    enabled = dccEnabled && onDccSendFile != null,
                    tint = if (dccEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        showNickActions = false
                        onDccSendFile?.invoke(selectedNick)
                    }
                )
                val autoAcceptList = state.networks.firstOrNull { it.id == selNetId }
                    ?.dccAutoAcceptNicks.orEmpty()
                val isAutoAccept = autoAcceptList.any { it.equals(selectedNick, ignoreCase = true) }

                ActionRow(
                    icon = if (isAutoAccept) Icons.Default.DownloadDone else Icons.Default.Download,
                    label = if (isAutoAccept) stringResource(R.string.nick_dcc_auto_off)
                            else stringResource(R.string.nick_dcc_auto_on),
                    subtitle = when {
                        !dccEnabled  -> stringResource(R.string.nick_dcc_disabled)
                        isAutoAccept -> stringResource(R.string.nick_dcc_auto_off_desc)
                        else         -> stringResource(R.string.nick_dcc_auto_on_desc)
                    },
                    enabled = dccEnabled,
                    tint = if (isAutoAccept) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        if (isAutoAccept) {
                            // Turning trust off never needs a confirmation.
                            onSetDccAutoAccept(selNetId, selectedNick, false)
                            showNickActions = false
                        } else {
                            // Turning it on does: files will land on the device with nobody
                            // watching, so the risk is spelled out before it takes effect.
                            confirmAutoAcceptFor = selectedNick
                        }
                    }
                )

                ActionRow(
                    icon = Icons.Default.Terminal,
                    label = stringResource(R.string.nick_dcc_chat),
                    subtitle = when {
                        !dccEnabled -> stringResource(R.string.nick_dcc_disabled)
                        dccSecure   -> stringResource(R.string.nick_dcc_chat_desc) + " (SDCC/TLS)"
                        else        -> stringResource(R.string.nick_dcc_chat_desc)
                    },
                    enabled = dccEnabled && onDccChat != null,
                    tint = if (dccEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        showNickActions = false
                        onDccChat?.invoke(selectedNick)
                    }
                )

                // ── User management ─────────────────────────────────────────
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                val ignored = state.networks.firstOrNull { it.id == selNetId }?.ignoredNicks.orEmpty()
                val isIgnored = ignored.any { it.equals(selectedNick, ignoreCase = true) }
                val canIgnore = !selectedNick.equals(myNick, ignoreCase = true)

                ActionRow(
                    icon = if (isIgnored) Icons.Default.VisibilityOff else Icons.Default.Block,
                    label = if (isIgnored) stringResource(R.string.nick_unignore) else stringResource(R.string.nick_ignore),
                    subtitle = if (isIgnored) stringResource(R.string.nick_unignore_desc) else stringResource(R.string.nick_ignore_desc),
                    enabled = canIgnore,
                    tint = if (isIgnored) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        if (isIgnored) onUnignoreNick(selNetId, selectedNick)
                        else onIgnoreNick(selNetId, selectedNick)
                        showNickActions = false
                    }
                )

                // Notification mute is independent of a full ignore: the user's messages still
                // appear in the buffer, only the highlight/PM alert is suppressed. State is the
                // presence of an exact bare-nick entry in highlightIgnoreMasks (the same thing
                // onIgnoreNotifications adds); hand-written glob/regex masks aren't reflected
                // here and are left untouched by the unignore path.
                val notifyMasks = state.networks.firstOrNull { it.id == selNetId }?.highlightIgnoreMasks.orEmpty()
                val isNotifyIgnored = notifyMasks.any { it.trim().equals(selectedNick, ignoreCase = true) }

                ActionRow(
                    icon = if (isNotifyIgnored) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                    label = if (isNotifyIgnored) stringResource(R.string.nick_unignore_notifs) else stringResource(R.string.nick_ignore_notifs),
                    subtitle = if (isNotifyIgnored) stringResource(R.string.nick_unignore_notifs_desc) else stringResource(R.string.nick_ignore_notifs_desc),
                    enabled = canIgnore,
                    tint = if (isNotifyIgnored) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        if (isNotifyIgnored) onUnignoreNotifications(selNetId, selectedNick)
                        else onIgnoreNotifications(selNetId, selectedNick)
                        showNickActions = false
                    }
                )

                // ── Channel privileges (ops only) ────────────────────────────
                // Surfaces the +o/-o and +v/-v actions.
                if (isChannel && (canMode || canKick) && !selectedNick.equals(myNick, ignoreCase = true)) {
                    val targetPrefix = nickDisplayByBase[selectedNick.lowercase()]?.let { nickPrefix(it) }
                    val hasOp    = targetPrefix in listOf('~', '&', '@')
                    val hasVoice = targetPrefix == '+'

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        stringResource(R.string.nick_privileges),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    // Op / deop
                    if (canMode) {
                        ActionRow(
                            icon = Icons.Default.Shield,
                            label = if (hasOp) stringResource(R.string.nick_deop) else stringResource(R.string.nick_op),
                            tint = if (hasOp) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                            onClick = {
                                val sign = if (hasOp) "-o" else "+o"
                                onSend("/mode $selBufName $sign $selectedNick")
                                showNickActions = false
                            }
                        )
                    }

                    // Voice / devoice
                    ActionRow(
                        icon = Icons.Default.RecordVoiceOver,
                        label = if (hasVoice) stringResource(R.string.nick_devoice) else stringResource(R.string.nick_voice),
                        tint = if (hasVoice) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        onClick = {
                            val sign = if (hasVoice) "-v" else "+v"
                            onSend("/mode $selBufName $sign $selectedNick")
                            showNickActions = false
                        }
                    )
                }

                // ── Moderation (ops only) ────────────────────────────────────
                if (isChannel && (canKick || canBan) && !selectedNick.equals(myNick, ignoreCase = true)) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        stringResource(R.string.nick_moderation),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    ActionRow(
                        icon = Icons.Default.Gavel,
                        label = stringResource(R.string.nick_kick_ban),
                        subtitle = stringResource(R.string.nick_kick_ban_desc, selectedNick),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = {
                            opsNick = selectedNick
                            opsReason = ""
                            showNickActions = false
                            showChanOps = true
                        }
                    )
                }
            }
        }
    }
}

/**
 * Renders one [UiMessage] in the chat list.
 *
 * Extracted from the inline [itemsIndexed] lambda so Compose's skipping optimisation can
 * avoid re-executing this entire body when none of the parameters have changed. With the
 * function inline, Compose had no stable boundary to check, so every state update
 * (input field, nicklist, scroll position, etc.) caused every visible message to
 * rebuild its [buildAnnotatedString], nick lookup, and inline preview URL scan.
 *
 * All parameters are primitives, stable data classes, or lambdas — Compose treats all
 * of these as stable, so the function is skipped whenever its inputs are identical.
 * Mutable state mutations are surfaced as callbacks so this composable itself is
 * stateless (except for the per-item [swipeOffsetX] animation).
 */
@Composable
private fun SingleMessageItem(
    m: UiMessage,
    ts: String,
    /** Brackets drawn around the sender's nick. */
    nickStyle: NickStyle,
    /** Colour for the timestamp span, or null to use the message colour. */
    timestampColor: Color?,
    // Pre-computed booleans so the item doesn't capture find/flicker state objects.
    isFlickering: Boolean,
    flickerAlphaValue: Float,       // Float, not Animatable, so Compose sees a stable param
    isFindMatch: Boolean,
    isFindCurrent: Boolean,
    findHighlight: String?,
    isSelectedForCopy: Boolean,
    /** True when the user has tapped "show more" on this message. */
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    copyRangeMode: Boolean,
    selBufName: String,
    messages: List<UiMessage>,
    reversedMessages: List<UiMessage>,
    listState: LazyListState,
    msgIdToDisplayIdx: Map<Long, Int>,
    /** IRCv3 msgid (String) → display index — used for O(1) reply-quote scroll. */
    msgStrToDisplayIdx: Map<String, Int>,
    /** IRCv3 msgid (String) → (from, text) — used for O(1) reply label rendering. */
    msgIdToText: Map<String, Pair<String?, String>>,
    scope: CoroutineScope,
    chatTextStyle: androidx.compose.ui.text.TextStyle,
    /** Space above this message, from the chat line spacing setting. 0.dp on Tight. */
    itemGap: androidx.compose.ui.unit.Dp = 0.dp,
    motdStyle: androidx.compose.ui.text.TextStyle,
    motdFontSizeSp: Float,
    linkStyle: SpanStyle,
    onAnnotationClick: (String, String) -> Unit,
    colorizeNicks: Boolean,
    mircColorsEnabled: Boolean,
    ansiColorsEnabled: Boolean,
    imagePreviewsEnabled: Boolean,
    imagePreviewsWifiOnly: Boolean,
    nickColor: (String) -> Color,
    displayNick: (String) -> String,
    // Callbacks for state mutations that live in the parent.
    onToggleSelected: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeReply: () -> Unit,
    /**
     * draft/metadata-2 display-name for this message's sender, already sanitised,
     * or null when unset / identical to the nick. Rendered AFTER the real nick
     * rather than replacing it: a display name is attacker-chosen free text, so
     * substituting it for the nick would let anyone impersonate another user.
     */
    displayName: String? = null,
) {
    val fromNick = m.from
    // Local copy of ChatScreen's baseNick — strips IRC mode prefixes from a display nick.
    fun baseNick(display: String) = display.trimStart('~', '&', '@', '%', '+')
    val swipeOffsetX = remember { Animatable(0f) }
    val canSwipeReply = fromNick != null && !m.isMotd

    // Encryption badge: a Material icon rendered inline at the very start of the message
    // line (replacing the old unicode padlock/fish/shield glyphs). Kept as an
    // InlineTextContent placeholder so the line stays a single Text node - selection,
    // copy and link-tap offsets are unaffected.
    //   AGM  -> filled Lock   (AES-256-GCM, modern PSK)      primary tint
    //   AGE  -> filled Shield (double-ratchet, forward secret) primary tint
    //   +OK  -> outlined Lock (Blowfish/FiSH, legacy compat)  amber tint (reads as "weaker")
    // Tints are intentionally simple; tweak here if you want per-scheme colour semantics.
    val encScheme = m.encryption
    val encAlt = when (encScheme) {
        com.boxlabs.hexdroid.crypto.E2eScheme.AGM      -> "\uD83D\uDD12"  // 🔒 fallback / copy text
        com.boxlabs.hexdroid.crypto.E2eScheme.AGE      -> "\uD83D\uDEE1" // 🛡
        com.boxlabs.hexdroid.crypto.E2eScheme.BLOWFISH -> "\uD83D\uDD13" // 🔓 (legacy)
        null                                           -> ""
    }
    val encInline: Map<String, InlineTextContent> = encScheme?.let { scheme ->
        mapOf(
            ENC_INLINE_ID to InlineTextContent(
                Placeholder(
                    width = 1.3.em,
                    height = 1.0.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                )
            ) {
                val (icon, tint) = when (scheme) {
                    com.boxlabs.hexdroid.crypto.E2eScheme.AGM      ->
                        Icons.Filled.Lock to MaterialTheme.colorScheme.primary
                    com.boxlabs.hexdroid.crypto.E2eScheme.AGE      ->
                        Icons.Filled.Shield to MaterialTheme.colorScheme.primary
                    com.boxlabs.hexdroid.crypto.E2eScheme.BLOWFISH ->
                        Icons.Outlined.Lock to Color(0xFFE0A030)
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                )
            }
        )
    } ?: emptyMap()

    // Collapse multiline messages to the first COLLAPSE_LINES and offer to expand.
    val bodyLineCount = remember(m.text) { m.text.count { it == '\n' } + 1 }
    val isTv = isTvDevice()
    val collapsible = m.multiline && bodyLineCount > COLLAPSE_LINES
    val hiddenLines = bodyLineCount - COLLAPSE_LINES
    val bodyText = remember(m.text, collapsible, isExpanded) {
        if (collapsible && !isExpanded) {
            m.text.lineSequence().take(COLLAPSE_LINES).joinToString("\n")
        } else {
            m.text
        }
    }

    androidx.compose.foundation.layout.Column(
        // Held +AGE echoes are dimmed until the bridge flushes them to the wire (m.pending), so an
        // optimistic echo reads clearly as "not yet delivered" rather than as a sent message.
        // m.failed is the same idea after the fact: the server rejected the send.
        modifier = Modifier
            .fillMaxWidth()
            // Chat line spacing. MOTD lines opt out: the server banner is a character
            // grid, and spacing its rows apart shears the art.
            .padding(top = if (m.isMotd) 0.dp else itemGap)
            .alpha(if (m.pending || m.failed) 0.5f else 1f)
    ) {
        if (m.failed) {
            Text(
                stringResource(R.string.chat_not_delivered),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (m.replyToMsgId != null) {
            val replyDisplayIdx = msgStrToDisplayIdx[m.replyToMsgId] ?: -1
            ReplyQuote(
                replyToMsgId = m.replyToMsgId,
                msgIdToText = msgIdToText,
                canScroll = replyDisplayIdx >= 0,
                onTap = {
                    // O(1): index already resolved above via msgStrToDisplayIdx.
                    if (replyDisplayIdx >= 0) {
                        scope.launch { listState.animateScrollToItem(replyDisplayIdx) }
                    }
                },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .focusHighlight()
                .then(
                    when {
                        isFlickering  -> Modifier.background(
                            MaterialTheme.colorScheme.primary.copy(alpha = flickerAlphaValue)
                        )
                        isFindCurrent -> Modifier.background(
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)
                        )
                        isFindMatch   -> Modifier.background(
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
                        )
                        else          -> Modifier
                    }
                )
                .then(
                    if (canSwipeReply) Modifier.pointerInput(m.id) {
                        val deadZonePx = 64.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (down.position.x < deadZonePx) return@awaitEachGesture
                            var dx = 0f
                            var dy = 0f
                            var started = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val pos = change.positionChange()
                                dx += pos.x; dy += pos.y
                                val absDx = kotlin.math.abs(dx); val absDy = kotlin.math.abs(dy)
                                if (!started) {
                                    if (absDx < viewConfiguration.touchSlop && absDy < viewConfiguration.touchSlop) continue
                                    if (absDy > absDx || dx < 0f) break
                                    started = true
                                }
                                if (started) {
                                    change.consume()
                                    val newVal = (swipeOffsetX.value + pos.x).coerceIn(0f, 200f)
                                    scope.launch { swipeOffsetX.snapTo(newVal) }
                                }
                            }
                            val triggered = swipeOffsetX.value >= 72f
                            scope.launch {
                                swipeOffsetX.animateTo(0f,
                                    animationSpec = androidx.compose.animation.core.spring())
                            }
                            if (triggered) onSwipeReply()
                        }
                    } else Modifier
                )
                // A remote has no dependable long press, so select opens the message
                // actions there. Touch keeps long press, where a select-to-open row
                // would fight with tapping links inside the message.
                .then(if (isTv) Modifier.focusHighlight(RoundedCornerShape(4.dp)) else Modifier)
                .combinedClickable(
                    onClick = {
                        when {
                            copyRangeMode -> onToggleSelected()
                            isTv -> onLongPress()
                        }
                    },
                    onLongClick = { if (copyRangeMode) onToggleSelected() else onLongPress() }
                )
        ) {
            if (copyRangeMode && isSelectedForCopy) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                )
            }
            if (canSwipeReply && swipeOffsetX.value > 8f) {
                val iconAlpha = (swipeOffsetX.value / 72f).coerceIn(0f, 1f)
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = iconAlpha),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                        .size(20.dp)
                        .offset(x = (swipeOffsetX.value * 0.3f).dp)
                )
            }

            if (fromNick == null) {
                if (m.isMotd && selBufName == "*server*") {
                    MotdLine(
                        text = bodyText,
                        fontSizeSp = motdFontSizeSp,
                        style = motdStyle,
                        mircColorsEnabled = mircColorsEnabled,
                        ansiColorsEnabled = ansiColorsEnabled,
                        linkStyle = linkStyle,
                        onAnnotationClick = onAnnotationClick,
                    )
                } else {
                    val hlBg = if (isSystemInDarkTheme())
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
                    else
                        androidx.compose.ui.graphics.Color(0xFFFFD54F)
                    val hlFg = if (isSystemInDarkTheme())
                        MaterialTheme.colorScheme.onTertiary
                    else
                        androidx.compose.ui.graphics.Color.Black
                    IrcLinkifiedText(
                        text = ts + bodyText,
                        mircColorsEnabled = mircColorsEnabled,
                        ansiColorsEnabled = ansiColorsEnabled,
                        linkStyle = linkStyle,
                        onAnnotationClick = onAnnotationClick,
                        style = chatTextStyle,
                        findHighlight = findHighlight,
                        findHighlightBg = hlBg,
                        findHighlightFg = hlFg,
                        prefixLength = ts.length,
                        prefixColor = timestampColor,
                    )
                }
            } else if (m.isAction) {
                val fromDisplay = displayNick(fromNick)
                val fromBase = baseNick(fromDisplay)
                val botPrefix = stringResource(R.string.chat_bot_prefix)
                val annotated = remember(ts, fromDisplay, fromBase, bodyText, colorizeNicks,
                    mircColorsEnabled, ansiColorsEnabled, linkStyle, encScheme, m.fromOper, m.fromBot, displayName, botPrefix,
                    timestampColor) {
                    buildAnnotatedString {
                        if (encScheme != null) { appendInlineContent(ENC_INLINE_ID, encAlt); append(" ") }
                        appendTimestamp(ts, timestampColor); append("* ")
                        // draft/oper-tag: amber star marks messages from IRC operators.
                        if (m.fromOper) withStyle(SpanStyle(color = Color(0xFFE0A030))) { append("\u2605") }
                        // Bot Mode: a muted [bot] tag marks messages from bots.
                        if (m.fromBot) withStyle(SpanStyle(color = Color(0xFF7E9CD8))) { append(botPrefix) }
                        pushStringAnnotation(tag = ANN_NICK, annotation = fromBase)
                        withStyle(SpanStyle(color = if (colorizeNicks) nickColor(fromBase) else Color.Unspecified)) {
                            append(fromDisplay)
                        }
                        pop()
                        if (displayName != null) {
                            withStyle(SpanStyle(color = Color.Gray)) { append(" ($displayName)") }
                        }
                        append(" ")
                        appendIrcStyledLinkified(bodyText, linkStyle, mircColorsEnabled, ansiColorsEnabled)
                    }
                }
                AnnotatedClickableText(text = annotated, onAnnotationClick = onAnnotationClick, style = chatTextStyle, inlineContent = encInline)
            } else {
                val fromDisplay = displayNick(fromNick)
                val fromBase = baseNick(fromDisplay)
                val botPrefix = stringResource(R.string.chat_bot_prefix)
                val annotated = remember(ts, fromDisplay, fromBase, bodyText, colorizeNicks,
                    mircColorsEnabled, ansiColorsEnabled, linkStyle, encScheme, m.fromOper, m.fromBot, displayName, botPrefix,
                    timestampColor, nickStyle) {
                    buildAnnotatedString {
                        if (encScheme != null) { appendInlineContent(ENC_INLINE_ID, encAlt); append(" ") }
                        appendTimestamp(ts, timestampColor); append(nickStyle.open)
                        // draft/oper-tag: amber star marks messages from IRC operators.
                        if (m.fromOper) withStyle(SpanStyle(color = Color(0xFFE0A030))) { append("\u2605") }
                        // Bot Mode: a muted [bot] tag marks messages from bots.
                        if (m.fromBot) withStyle(SpanStyle(color = Color(0xFF7E9CD8))) { append(botPrefix) }
                        pushStringAnnotation(tag = ANN_NICK, annotation = fromBase)
                        withStyle(SpanStyle(color = if (colorizeNicks) nickColor(fromBase) else Color.Unspecified)) {
                            append(fromDisplay)
                        }
                        pop()
                        if (displayName != null) {
                            withStyle(SpanStyle(color = Color.Gray)) { append(" ($displayName)") }
                        }
                        append(nickStyle.close); append(" ")
                        appendIrcStyledLinkified(bodyText, linkStyle, mircColorsEnabled, ansiColorsEnabled)
                    }
                }
                AnnotatedClickableText(text = annotated, onAnnotationClick = onAnnotationClick, style = chatTextStyle, inlineContent = encInline)
            }
        } // end Box

        if (collapsible) {
            Text(
                text = if (isExpanded) {
                    stringResource(R.string.chat_show_less)
                } else {
                    pluralStringResource(R.plurals.chat_show_more, hiddenLines, hiddenLines)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .focusHighlight()
                    .clickable(enabled = !copyRangeMode, onClick = onToggleExpanded)
                    .padding(top = 2.dp, bottom = 2.dp),
            )
        }

        if (imagePreviewsEnabled) {
            // m.text, not bodyText: a link below the fold is still worth previewing, and
            // the preview list must not shuffle when the user expands the message.
            val msgUrls = remember(m.id) {
                urlRegex.findAll(m.text).map { it.value }.take(3).toList()
            }
            for (previewUrl in msgUrls) {
                InlinePreview(url = previewUrl, previewsEnabled = true, wifiOnly = imagePreviewsWifiOnly)
            }
        }
        if (!m.isMotd || selBufName != "*server*") {
            Spacer(Modifier.height(4.dp))
        }
    } // end Column
}

/** Messages longer than this many lines collapse behind a "show more" toggle. */
private const val COLLAPSE_LINES = 8

private const val ANN_URL = "URL"
private const val ANN_CHAN = "CHAN"
private const val ANN_NICK = "NICK"
private const val ENC_INLINE_ID = "encbadge"

private val urlRegex = Regex("https?://\\S+")
private val chanRegex = Regex("#\\S+")
private val trailingPunct = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')

private data class LinkSpan(
    val start: Int,
    val originalEnd: Int,
    val display: String,
    val tag: String,
    val annotation: String,
)

private fun splitTrailingPunctuation(token: String): Pair<String, String> {
    var t = token
    val sb = StringBuilder()
    while (t.isNotEmpty() && trailingPunct.contains(t.last())) {
        sb.insert(0, t.last())
        t = t.dropLast(1)
    }
    return t to sb.toString()
}

private fun computeLinkSpans(text: String): List<LinkSpan> {
    // Fast path: this runs two regexes, and mIRC-coloured text (especially ASCII art) is split
    // into hundreds of short runs that each call here. If a run can't contain a URL ("://") or a
    // channel ("#"), there's nothing to find and skip the regex work entirely. Same result, far
    // less CPU when building/scrolling heavily-coloured lines.
    if (!text.contains("://") && text.indexOf('#') < 0) return emptyList()
    // Find URLs first; then find channels that are NOT inside URLs.
    val urlMatches = urlRegex.findAll(text).mapNotNull { m ->
        val raw = m.value
        val (token, _) = splitTrailingPunctuation(raw)
        if (token.isBlank()) return@mapNotNull null
        val originalEnd = m.range.last + 1
        LinkSpan(
            start = m.range.first,
            originalEnd = originalEnd,
            display = token,
            tag = ANN_URL,
            annotation = token,
        )
    }.toList()

    val urlRanges = urlMatches.map { it.start until it.originalEnd }

    val chanMatches = chanRegex.findAll(text).mapNotNull { m ->
        val start = m.range.first
        // Skip if the match is inside a URL.
        if (urlRanges.any { start in it }) return@mapNotNull null
        val raw = m.value
        val (token, _) = splitTrailingPunctuation(raw)
        if (token.isBlank()) return@mapNotNull null
        val originalEnd = m.range.last + 1
        LinkSpan(
            start = start,
            originalEnd = originalEnd,
            display = token,
            tag = ANN_CHAN,
            annotation = token,
        )
    }.toList()

    return (urlMatches + chanMatches).sortedBy { it.start }
}

private fun appendLinkified(builder: AnnotatedString.Builder, text: String, linkStyle: SpanStyle) {
    val spans = computeLinkSpans(text)
    var i = 0
    for (s in spans) {
        if (s.start < i) continue
        if (s.start > text.length) continue
        builder.append(text.substring(i, s.start))

        val displayStart = builder.length
        builder.withStyle(linkStyle) { append(s.display) }
        builder.addStringAnnotation(
            tag = s.tag,
            annotation = s.annotation,
            start = displayStart,
            end = displayStart + s.display.length
        )

        val trailingStartInSrc = s.start + s.display.length
        if (trailingStartInSrc < s.originalEnd) {
            builder.append(text.substring(trailingStartInSrc, s.originalEnd))
        }
        i = s.originalEnd
    }
    if (i < text.length) builder.append(text.substring(i))
}

// mIRC colour/style rendering
private data class MircStyleState(
    var fg: Int? = null,
    var bg: Int? = null,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var reverse: Boolean = false,
) {
    fun reset() {
        fg = null
        bg = null
        bold = false
        italic = false
        underline = false
        reverse = false
    }

    fun snapshot(): MircStyleState = MircStyleState(fg, bg, bold, italic, underline, reverse)

    fun hasAnyStyle(): Boolean = fg != null || bg != null || bold || italic || underline || reverse
}

private data class MircRun(val text: String, val style: MircStyleState)

/**
 * Full mIRC/IRCv3 colour table: 0-15 legacy + 16-98 extended (99 total).
 *
 * Codes 0-15 are the original mIRC palette used by essentially all IRC clients.
 * Codes 16-98 are the modern IRCv3 extension published at
 * https://modern.ircdocs.horse/formatting.html#color - supported by mIRC 7+,
 * WeeChat, HexChat, and most modern clients.
 *
 * Each entry is a 0xAARRGGBB value.
 */
/**
 * Canonical mIRC / IRCv3 colour palette — 99 entries (codes 0–98).
 *
 * Source: https://modern.ircdocs.horse/formatting.html#color (the "IRC Colour" specification).
 * These are the exact RGB hex values that mIRC 7+, HexChat, WeeChat, and other modern
 * clients use. Codes 0–15 are the original mIRC palette; codes 16–97 are the extended
 * IRCv3 block (6 rows of 16, laid out as a gradient grid); code 98 is the spec-defined
 * "transparent/default" entry which maps to white for rendering purposes.
 *
 * Layout of codes 16–97 in the grid (each row = 16 entries, darkest → lightest):
 *   Row 1 (16–27):  greys darkening right → pure blacks at left
 *   Row 2 (28–39):  dark shades — red, orange, yellow, green, cyan, blue, purple, pink
 *   Row 3 (40–51):  mid shades
 *   Row 4 (52–63):  bright / saturated
 *   Row 5 (64–75):  light / pastel
 *   Row 6 (76–87):  very light / near-white pastels
 *   Row 7 (88–98):  greyscale ramp (black → white), code 98 = white alias
 */
private val MIRC_PALETTE: IntArray = intArrayOf(
    // ── 0–15: classic mIRC palette ────────────────────────────────────────────
    0xFFFFFFFF.toInt(), //  0  White
    0xFF000000.toInt(), //  1  Black
    0xFF00007F.toInt(), //  2  Blue (navy)
    0xFF009300.toInt(), //  3  Green
    0xFFFF0000.toInt(), //  4  Red
    0xFF7F0000.toInt(), //  5  Brown (maroon)
    0xFF9C009C.toInt(), //  6  Purple
    0xFFFC7F00.toInt(), //  7  Orange
    0xFFFFFF00.toInt(), //  8  Yellow
    0xFF00FC00.toInt(), //  9  Light green (lime)
    0xFF009393.toInt(), // 10  Teal
    0xFF00FFFF.toInt(), // 11  Light cyan (aqua)
    0xFF0000FC.toInt(), // 12  Light blue (royal)
    0xFFFF00FF.toInt(), // 13  Pink (magenta / fuchsia)
    0xFF7F7F7F.toInt(), // 14  Grey
    0xFFD2D2D2.toInt(), // 15  Light grey
    // ── 16–27: darkest shades (row 1 of extended block) ──────────────────────
    0xFF470000.toInt(), // 16  Dark maroon
    0xFF472100.toInt(), // 17  Very dark orange
    0xFF474700.toInt(), // 18  Dark olive
    0xFF324700.toInt(), // 19  Very dark green
    0xFF004732.toInt(), // 20  Very dark teal-green
    0xFF00472C.toInt(), // 21  Very dark teal (alt)
    0xFF004747.toInt(), // 22  Very dark teal
    0xFF002747.toInt(), // 23  Very dark slate blue
    0xFF000047.toInt(), // 24  Very dark navy
    0xFF2E0047.toInt(), // 25  Very dark violet
    0xFF470047.toInt(), // 26  Very dark purple-magenta
    0xFF47002A.toInt(), // 27  Very dark crimson
    // ── 28–39: dark shades (row 2) ───────────────────────────────────────────
    0xFF740000.toInt(), // 28  Dark red
    0xFF743A00.toInt(), // 29  Dark brown-orange
    0xFF747400.toInt(), // 30  Dark yellow-olive
    0xFF517400.toInt(), // 31  Dark chartreuse
    0xFF007400.toInt(), // 32  Dark green
    0xFF007449.toInt(), // 33  Dark sea-green
    0xFF007474.toInt(), // 34  Dark teal
    0xFF004074.toInt(), // 35  Dark dodger blue
    0xFF000074.toInt(), // 36  Dark blue
    0xFF4B0074.toInt(), // 37  Dark purple-blue
    0xFF740074.toInt(), // 38  Dark magenta
    0xFF740045.toInt(), // 39  Dark hot pink
    // ── 40–51: mid shades (row 3) ────────────────────────────────────────────
    0xFFB50000.toInt(), // 40  Medium red
    0xFFB56300.toInt(), // 41  Medium orange
    0xFFB5B500.toInt(), // 42  Medium yellow-green
    0xFF7DB500.toInt(), // 43  Chartreuse
    0xFF00B500.toInt(), // 44  Medium green
    0xFF00B573.toInt(), // 45  Medium mint
    0xFF00B5B5.toInt(), // 46  Medium teal
    0xFF0063B5.toInt(), // 47  Medium dodger blue
    0xFF0000B5.toInt(), // 48  Medium blue
    0xFF7500B5.toInt(), // 49  Medium violet
    0xFFB500B5.toInt(), // 50  Medium magenta
    0xFFB5006B.toInt(), // 51  Medium hot pink
    // ── 52–63: bright / saturated (row 4) ────────────────────────────────────
    0xFFFF0000.toInt(), // 52  Bright red
    0xFFFF9200.toInt(), // 53  Bright orange / gold
    0xFFFFFF00.toInt(), // 54  Bright yellow
    0xFFB9FF00.toInt(), // 55  Bright yellow-green
    0xFF00FF00.toInt(), // 56  Bright lime green
    0xFF00FFA8.toInt(), // 57  Bright spring green
    0xFF00FFFF.toInt(), // 58  Bright cyan / aqua
    0xFF009BFF.toInt(), // 59  Bright azure / sky blue
    0xFF0000FF.toInt(), // 60  Bright blue
    0xFFAD00FF.toInt(), // 61  Bright electric purple
    0xFFFF00FF.toInt(), // 62  Bright magenta / fuchsia
    0xFFFF0092.toInt(), // 63  Bright rose / hot pink
    // ── 64–75: light / pastel (row 5) ────────────────────────────────────────
    0xFFFF6666.toInt(), // 64  Light red
    0xFFFFB466.toInt(), // 65  Light orange / peach
    0xFFFFFF66.toInt(), // 66  Light yellow
    0xFFCCFF66.toInt(), // 67  Light chartreuse
    0xFF66FF66.toInt(), // 68  Light green
    0xFF66FFB4.toInt(), // 69  Light mint
    0xFF66FFFF.toInt(), // 70  Light cyan
    0xFF66B4FF.toInt(), // 71  Light sky blue
    0xFF6666FF.toInt(), // 72  Light blue-purple
    0xFFCC66FF.toInt(), // 73  Light violet
    0xFFFF66FF.toInt(), // 74  Light magenta / orchid
    0xFFFF66B4.toInt(), // 75  Light pink
    // ── 76–87: very light / near-white pastels (row 6) ───────────────────────
    0xFFFFB4B4.toInt(), // 76  Very light red / salmon
    0xFFFFDEB4.toInt(), // 77  Very light orange / bisque
    0xFFFFFFB4.toInt(), // 78  Very light yellow / cream
    0xFFE6FFB4.toInt(), // 79  Very light chartreuse / honeydew
    0xFFB4FFB4.toInt(), // 80  Very light green / mint cream
    0xFFB4FFE6.toInt(), // 81  Very light mint / azure-mint
    0xFFB4FFFF.toInt(), // 82  Very light cyan / azure
    0xFFB4DEFF.toInt(), // 83  Very light sky blue / alice blue
    0xFFB4B4FF.toInt(), // 84  Very light lavender
    0xFFDEB4FF.toInt(), // 85  Very light violet / lavender blush
    0xFFFFB4FF.toInt(), // 86  Very light magenta / thistle
    0xFFFFB4DE.toInt(), // 87  Very light pink / lavender rose
    // ── 88–98: greyscale ramp (row 7) ────────────────────────────────────────
    0xFF000000.toInt(), // 88  Black
    0xFF141414.toInt(), // 89  Near-black
    0xFF282828.toInt(), // 90  Very dark grey
    0xFF3C3C3C.toInt(), // 91  Dark grey
    0xFF505050.toInt(), // 92  Dark-mid grey
    0xFF646464.toInt(), // 93  Mid grey
    0xFF787878.toInt(), // 94  Mid-light grey
    0xFF8C8C8C.toInt(), // 95  Light-mid grey
    0xFFA0A0A0.toInt(), // 96  Light grey
    0xFFB4B4B4.toInt(), // 97  Pale grey
    0xFFC8C8C8.toInt(), // 98  Silver / near-white (spec "default" alias)
)

private fun mircColor(code: Int): Color? =
    MIRC_PALETTE.getOrNull(code)?.let { Color(it.toLong() and 0xFFFFFFFFL) }

/** How many mIRC colour codes are defined (0-based, inclusive of 0). */
private const val MIRC_COLOR_COUNT = 99

private fun MircStyleState.toSpanStyle(): SpanStyle {
    val fgCode = if (reverse) bg else fg
    val bgCode = if (reverse) fg else bg
    val fgColor = fgCode?.let(::mircColor) ?: Color.Unspecified
    val bgColor = bgCode?.let(::mircColor) ?: Color.Unspecified

    return SpanStyle(
        color = fgColor,
        background = bgColor,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )
}

private fun parseMircRuns(input: String): List<MircRun> {
    if (input.isEmpty()) return emptyList()

    val out = mutableListOf<MircRun>()
    val buf = StringBuilder()
    val st = MircStyleState()

    fun flush() {
        if (buf.isNotEmpty()) {
            out += MircRun(buf.toString(), st.snapshot())
            buf.setLength(0)
        }
    }

    fun parseOneOrTwoDigits(startIndex: Int): Pair<Int?, Int> {
        var i = startIndex
        if (i >= input.length || !input[i].isDigit()) return (null to i)
        val first = input[i]
        i++
        if (i < input.length && input[i].isDigit()) {
            val num = ("$first${input[i]}").toIntOrNull()
            i++
            return (num to i)
        }
        return (first.toString().toIntOrNull() to i)
    }

    var i = 0
    while (i < input.length) {
        when (val c = input[i]) {
            '\u0003' -> { // colour
                flush()
                i++
                val (fg, ni) = parseOneOrTwoDigits(i)
                i = ni
                if (fg == null) {
                    // \x03 alone resets colours.
                    st.fg = null
                    st.bg = null
                } else {
                    st.fg = fg
                    // Optional ,bg; only consume the comma when at least one
                    // digit follows it. The original code consumed the comma unconditionally,
                    // so text like "\x035,word" would silently drop the comma from output,
                    // rendering "helloworld" instead of "hello,world".
                    if (i < input.length && input[i] == ',' &&
                        i + 1 < input.length && input[i + 1].isDigit()) {
                        i++ // consume comma only when a digit follows
                        val (bg, n2) = parseOneOrTwoDigits(i)
                        i = n2
                        st.bg = bg
                    }
                }
            }

            '\u000F' -> { // reset
                flush()
                st.reset()
                i++
            }

            '\u0002' -> { // bold
                flush(); st.bold = !st.bold; i++
            }

            '\u001D' -> { // italic
                flush(); st.italic = !st.italic; i++
            }

            '\u001F' -> { // underline
                flush(); st.underline = !st.underline; i++
            }

            '\u0016' -> { // reverse
                flush(); st.reverse = !st.reverse; i++
            }

            else -> {
                // Drop other C0 controls (except common whitespace).
                if (c.code < 0x20 && c != '\n' && c != '\t' && c != '\r') {
                    i++
                } else {
                    buf.append(c)
                    i++
                }
            }
        }
    }
    flush()
    return out
}

private fun AnnotatedString.Builder.appendIrcStyledLinkified(
    text: String,
    linkStyle: SpanStyle,
    mircColorsEnabled: Boolean,
    ansiColorsEnabled: Boolean = false,
) {
    val hasAnsi = ansiColorsEnabled && text.contains('\u001b')
    val hasMirc = mircColorsEnabled && !hasAnsi &&
        (text.contains('\u0003') || text.contains('\u0004') || text.contains('\u0002') ||
         text.contains('\u001D') || text.contains('\u001F') || text.contains('\u0016'))

    when {
        hasAnsi -> {
            val runs = parseAnsiRuns(text)
            if (runs.isEmpty()) return
            for (r in runs) {
                if (r.style.hasAnyStyle()) {
                    withStyle(r.style.ansiToSpanStyle()) { appendLinkified(this, r.text, linkStyle) }
                } else {
                    appendLinkified(this, r.text, linkStyle)
                }
            }
        }
        hasMirc -> {
            val runs = parseMircRuns(text)
            if (runs.isEmpty()) return
            for (r in runs) {
                if (r.style.hasAnyStyle()) {
                    withStyle(r.style.toSpanStyle()) { appendLinkified(this, r.text, linkStyle) }
                } else {
                    appendLinkified(this, r.text, linkStyle)
                }
            }
        }
        else -> appendLinkified(this, stripIrcFormatting(text), linkStyle)
    }
}

@Composable
private fun AnnotatedClickableText(
    text: AnnotatedString,
    onAnnotationClick: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    var layout: TextLayoutResult? by remember { mutableStateOf(null) }
    // rememberUpdatedState lets the gesture handler always see the latest text and
    // callback without restarting the pointerInput coroutine when they change.
    val currentText by rememberUpdatedState(text)
    val currentOnClick by rememberUpdatedState(onAnnotationClick)
    Text(
        text = text,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inlineContent,
        onTextLayout = {
            layout = it
            onTextLayout?.invoke(it)
        },
        modifier = modifier.pointerInput(Unit) {
            val vc = viewConfiguration
            awaitEachGesture {
                // Don't consume gestures: allow selection (long-press/drag) to work.
                val down = awaitFirstDown(requireUnconsumed = false)
                val downPos = down.position
                val downTime = down.uptimeMillis

                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                val dt = up.uptimeMillis - downTime
                val dist = (up.position - downPos).getDistance()

                // Treat only quick taps as clicks so selection gestures don't accidentally open links.
                if (dt <= 200 && dist <= vc.touchSlop) {
                    val l = layout ?: return@awaitEachGesture
                    val offset = l.getOffsetForPosition(up.position)
                    val ann = currentText.getStringAnnotations(start = offset, end = offset).firstOrNull()
                    if (ann != null) currentOnClick(ann.tag, ann.item)
                }
            }
        }
    )
}

/** Appends the timestamp, tinted when the user has picked a colour for it. */
private fun AnnotatedString.Builder.appendTimestamp(ts: String, color: Color?) {
    if (ts.isEmpty()) return
    if (color == null) append(ts) else withStyle(SpanStyle(color = color)) { append(ts) }
}

@Composable
private fun IrcLinkifiedText(
    text: String,
    mircColorsEnabled: Boolean,
    ansiColorsEnabled: Boolean = false,
    linkStyle: SpanStyle,
    onAnnotationClick: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    findHighlight: String? = null,  // when non-null, highlight all occurrences of this query
    findHighlightBg: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFFFD54F),
    findHighlightFg: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Black,
    /** Leading characters of [text] holding the timestamp, tinted with [prefixColor]. */
    prefixLength: Int = 0,
    prefixColor: androidx.compose.ui.graphics.Color? = null,
) {
    val annotated = remember(text, linkStyle, mircColorsEnabled, ansiColorsEnabled, findHighlight, findHighlightBg,
        prefixLength, prefixColor) {
        val styled = buildAnnotatedString { appendIrcStyledLinkified(text, linkStyle, mircColorsEnabled, ansiColorsEnabled) }
        val base = if (prefixColor != null && prefixLength > 0 && prefixLength <= styled.length) {
            buildAnnotatedString {
                append(styled)
                addStyle(SpanStyle(color = prefixColor), 0, prefixLength)
            }
        } else {
            styled
        }
        if (findHighlight.isNullOrBlank()) return@remember base
        val plain = base.text
        val query = findHighlight.lowercase()
        val hlStyle = SpanStyle(background = findHighlightBg, color = findHighlightFg)
        buildAnnotatedString {
            append(base)
            var idx = plain.lowercase().indexOf(query)
            while (idx >= 0) {
                addStyle(hlStyle, idx, idx + query.length)
                idx = plain.lowercase().indexOf(query, idx + 1)
            }
        }
    }
    AnnotatedClickableText(
        text = annotated,
        onAnnotationClick = onAnnotationClick,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = onTextLayout,
    )
}

/**
 * This exists to stop colour-heavy *text* (relay bots that paint the network
 * tag and nick, lively coloured chat) from being misclassified as ASCII/ANSI
 * art and shrunk to an unreadable size.
 */
private fun looksLikeProse(plain: String): Boolean {
    var words = 0
    var run = 0
    var letters = 0
    var nonSpace = 0
    for (ch in plain) {
        if (ch.isLetter()) {
            run++
            letters++
        } else {
            if (run >= 2) words++
                run = 0
        }
        if (!ch.isWhitespace()) nonSpace++
    }
    if (run >= 2) words++
        val letterRatio = if (nonSpace > 0) letters.toFloat() / nonSpace else 0f
        return words >= 3 && letterRatio >= 0.55f
}

/**
 * Returns true when a chat message line looks like it belongs to a bot-generated
 * ASCII/ANSI art block rather than normal coloured conversation.
 */
private fun looksLikeArt(text: String): Boolean {
    // Examine stripped content for structural shape, plus ANSI SGR and mIRC colour
    // codes as additional signals. The block-size gate (≥2 consecutive lines from
    // non-action senders) does the primary false-positive filtering.
    val plain = stripIrcFormatting(text)
    if (plain.length < 3) return false

    // Single pass: detect ANSI SGR and count mIRC colour codes simultaneously.
    // Previously two separate O(n) scans over the raw text string; merged into one.
    //
    // ANSI SGR ([…m): almost never in normal IRC text — even one is a strong signal.
    // Other ANSI escapes ([3~ = Delete, [A = cursor-up) are NOT art; we check
    // explicitly for the SGR final byte 'm'.
    //
    // mIRC colour codes (): common in normal chat so require ≥4 per line to trigger.
    var mircCount = 0
    var i = 0
    while (i < text.length) {
        when (text[i]) {
            '' -> {
                if (i + 1 < text.length && text[i + 1] == '[') {
                    var j = i + 2
                    while (j < text.length && (text[j].isDigit() || text[j] == ';')) j++
                    if (j < text.length && text[j] == 'm') return true  // ANSI SGR colour
                    i = j + 1
                } else i++
            }
            '' -> { mircCount++; i++ }
            else -> i++
        }
    }

    // Prose gate: colour-heavy but word-shaped lines (relay bots painting the network
    // tag and nick, lively coloured chat) must never be classified as art. Only the
    // unambiguous signals - ANSI SGR above and the Unicode box-drawing first char
    // below - bypass this; every heuristic that can plausibly match real sentences
    // is gated on the line NOT reading as prose.
    val isProse = looksLikeProse(plain)

    if (!isProse && mircCount >= 4) return true

    // Signal 1: ≥2 leading spaces AND the content looks like a structural/art line.
    //
    // Previous version triggered on any non-alphanumeric first char, producing false
    // positives for common IRC prose patterns like "  * list item" and "  - bullet".
    //
    // Refined rule:
    // - Unambiguous if the first non-space char is a Unicode box-drawing or block-element
    //   character (U+2500–U+2BFF): these never appear in normal prose.
    // - For ASCII structural chars, require the line to be symmetrically framed — both
    //   ends non-alphanumeric. This catches "|  text  |", "+---+", "*** header ***" while
    //   correctly rejecting "  * bullet text" and "  - list item" (which end with a letter).
    if (plain.length >= 3 && plain[0] == ' ' && plain[1] == ' ') {
        val trimmed = plain.trimStart()
        if (trimmed.isNotEmpty()) {
            val first = trimmed[0]
            if (!first.isLetterOrDigit()) {
                // Box-drawing, block elements, geometric shapes, misc symbols (U+2500–U+2BFF)
                if (first.code in 0x2500..0x2BFF) return true
                // ASCII structural: both ends non-alphanumeric → symmetrically framed line
                val lastCh = trimmed.trimEnd().lastOrNull()
                if (!isProse && lastCh != null && !lastCh.isLetterOrDigit()) return true
            }
        }
    }

    // Signal 2: structural-symbol density — tiered thresholds handle both short
    // dense lines (e.g. "_____", "|_ _|") and medium-length mixed lines (e.g. "H _|\_/|_ H"):
    //   • Short  (≥4 non-space):  density ≥ 80 % — catches pure border lines like "_____"
    //   • Medium (≥8 non-space):  density ≥ 45 % — catches "H   _|\_/|_   H" (78 %) and
    //                              "nHnn/ \___/ \nnHn" (47 %)
    //   • Long   (≥16 non-space): density ≥ 30 % — the original threshold for wider art
    // All thresholds safely reject normal prose: typical IRC chat scores < 20 % symbol density.
    var artCount = 0
    var alphaCount = 0
    for (ch in plain) {
        if (ch == ' ') continue
        if (ch.isLetterOrDigit()) alphaCount++ else artCount++
    }
    val nonSpace = artCount + alphaCount
    val density = if (nonSpace > 0) artCount.toFloat() / nonSpace else 0f
    if (!isProse) {
        if (nonSpace >= 4  && density >= 0.80f) return true   // short dense:  "_____", "|_ _|"
        if (nonSpace >= 8  && density >= 0.45f) return true   // medium mixed: "H _|\/|_ H"
        if (nonSpace >= 16 && density >= 0.30f) return true   // long:         original threshold
    }

    // Signal 3: single-space indent + border character patterns.
    // Expanded border set to include common art characters (*#@~^<>{}) beyond the
    // original "|/\_-=+[]".
    if (!isProse && plain.length >= 4 && plain[0] == ' ' && plain[1] != ' ') {
        val c1 = plain[1]; val c2 = plain[2]
        val border = "|/\\_-=+[]{}~^<>*#@"
        // 3a: border char followed immediately by space or another border char.
        if (c1 in border && (c2 == ' ' || c2 in border)) return true
        // 3b: framed label — first AND last non-space chars are both border chars.
        val lastNs = plain.trimEnd().lastOrNull()
        if (lastNs != null && c1 in border && lastNs in border) return true
    }

    return false
}

/**
 * A heterogeneous list item for the chat LazyColumn.
 *
 * [Single] wraps one normal message.
 * [Art] wraps an entire run of consecutive art lines as one item, so all
 * lines are rendered inside a single [Column] with zero inter-line gaps.
 * Keys use even/odd Long split to avoid collisions between the two types.
 */
private sealed class RawItem {
    data class Single(val msg: UiMessage) : RawItem()
    data class Art(val msgs: List<UiMessage>) : RawItem()  // chronological
}

private sealed class DisplayItem {
    abstract val key: Any
    data class Single(val msg: UiMessage) : DisplayItem() {
        // Type-tagged string keys are collision-proof by construction: every Single
        // gets "S:<id>", every Art gets "A:<firstId>:<size>". Previously the code
        // used Long arithmetic (msg.id * 2 vs first().id * 2 + 1) which is unique
        // for distinct ids but vulnerable to stale-cache scenarios where two
        // RawItem.Art blocks built from different reversedMessages snapshots could
        // briefly coexist with overlapping ids in flight; the type tag prevents
        // any cross-type collision and the size suffix on Art makes the key change
        // whenever the block grows or shrinks. Bug surfaced as
        // "Key was already used" crashes on fling-driven measure passes
        // (LayoutNodeSubcompositionsState.subcompose).
        override val key: Any = "S:${msg.id}"
    }
    data class Art(
        val msgs: List<UiMessage>,  // chronological: oldest first
        val fontSizeSp: Float,
    ) : DisplayItem() {
        override val key: Any = "A:${msgs.first().id}:${msgs.size}"
    }
}

/**
 * Builds the [DisplayItem] list consumed by the chat [LazyColumn].
 *
 * Consecutive art-like messages (from any sender) are merged into a single
 * [DisplayItem.Art] item so they render gap-free inside one [Column].
 *
 * Performance — two-phase approach:
 *
 * Phase 1 (block detection): scans [reversedMessages] with [looksLikeArt] and
 * groups consecutive art lines into [RawArt] blocks.  Pure string ops, no
 * allocation beyond the result list.  Cached by (n, newestId) so it only
 * re-runs when a message is added or removed.
 *
 * Phase 2 (font sizing): measures each [RawArt] block to find the font size
 * that fits the widest line.  Results are stored in a [HashMap] keyed by
 * (firstMsgId, blockSize) so that:
 *   - A new non-art message arriving → Phase 1 re-runs (fast), Phase 2
 *     re-iterates but every art block is a cache hit → zero [TextMeasurer]
 *     calls.
 *   - A new art message extending a block → cache key changes (blockSize++)
 *     → only that block is re-measured, all others are hits.
 *   - Layout width or font size changes → cache is cleared and all blocks
 *     are re-measured once.
 */
@Composable
private fun rememberDisplayItems(
    reversedMessages: List<UiMessage>,
    availableWidthPx: Float,
    style: androidx.compose.ui.text.TextStyle,
    minFontSp: Float = 6f,
    artDetectionEnabled: Boolean = true,
): List<DisplayItem> {
    val textMeasurer = rememberTextMeasurer()
    val naturalSizeSp = style.fontSize.value.takeIf { !it.isNaN() && it > 0f } ?: 14f

    // ── Phase 1: block detection — O(n) string ops, no measurement ───────────
    //
    // Cache key: the reversedMessages list itself, by reference identity.
    //
    // Previous versions keyed on (n, newestId, oldestId). That fails when a
    // merge or sort reorders messages while keeping those three values stable —
    // a rare but real case during case-variant channel merges (mergeDuplicateBuffers
    // sorts by (timeMs, id) after a .distinctBy { it.id }). A stale rawItems list
    // then produces DisplayItem keys that collide with live ids, and Compose's
    // LazyColumn throws "Key was already used" from a measure pass:
    //   InlineClassHelperKt.throwIllegalArgumentException
    //     → LayoutNodeSubcompositionsState.subcompose
    //     → LazyLayoutMeasureScopeImpl.compose
    // (seen in Play Console crash reports on 1.6.0.)
    //
    // Keying on reversedMessages by reference identity invalidates exactly when
    // content changes. Phase 1 is pure string ops (~microseconds at the 5000
    // scrollback cap) and Phase 2's expensive TextMeasurer calls are cached
    // separately below, so the perf cost is negligible.
    val rawItems: List<RawItem> = remember(reversedMessages, artDetectionEnabled) {
        if (reversedMessages.isEmpty()) return@remember emptyList()
        // Detection disabled: every message renders as a normal chat line. Mapping
        // straight to Singles keeps the DisplayItem pipeline (keys, unread separator
        // indexing, reply-quote scroll) identical to the enabled path.
        if (!artDetectionEnabled) {
            return@remember reversedMessages.map { RawItem.Single(it) }
        }
        val result  = mutableListOf<RawItem>()
        val artRun  = mutableListOf<UiMessage>()  // accumulated in reversed (newest-first) order

        fun flushArtRun() {
            if (artRun.size >= 2) result.add(RawItem.Art(artRun.asReversed().toList()))
            else artRun.forEach { result.add(RawItem.Single(it)) }
            artRun.clear()
        }

        // Maximum time gap between consecutive art lines before the block is split.
        // Art bots paste lines in rapid succession; a gap of ≥60 s almost certainly
        // means two separate art pastes that should not be rendered as one block.
        val ART_TIME_GAP_MS = 60_000L

        for (msg in reversedMessages) {
            // /me action messages are excluded: their text is rendered with a "* nick"
            // prefix in normal chat, so absorbing them into an art block would strip that
            // prefix and display the raw action text without context.
            // Server messages (from == null) are also excluded.
            if (msg.from != null && !msg.isAction && looksLikeArt(msg.text)) {
                // If there is a large time gap between this message and the previous art
                // line, flush the current run and start a new block. reversedMessages is
                // newest-first, so artRun.last() is the message directly preceding this
                // one in time (it is newer than msg).
                if (artRun.isNotEmpty() && artRun.last().timeMs - msg.timeMs > ART_TIME_GAP_MS) {
                    flushArtRun()
                }
                artRun.add(msg)
            } else {
                flushArtRun()
                result.add(RawItem.Single(msg))
            }
        }
        flushArtRun()
        result
    }

    // ── Phase 2: font sizing — only measures blocks missing from cache ────────
    // Key: (firstMsgId * MAX_BLOCK + blockSize) — uniquely identifies a block's
    // content since IRC message lists are append-only and blocks only grow by
    // having newer messages added at the end of the chronological order.
    // Cache is cleared when layout dimensions change so all blocks are re-sized.
    val fontSizeCache = remember { HashMap<Long, Float>() }
    val prevWidth  = remember { mutableStateOf(availableWidthPx) }
    val prevNatSp  = remember { mutableStateOf(naturalSizeSp) }
    if (prevWidth.value != availableWidthPx || prevNatSp.value != naturalSizeSp) {
        fontSizeCache.clear()
        prevWidth.value  = availableWidthPx
        prevNatSp.value  = naturalSizeSp
    }

    fun fontSizeForBlock(msgs: List<UiMessage>): Float {
        // (firstMsgId shifted left 17 bits) OR blockSize — collision-free for
        // any realistic block size (<131072 lines) and message ID space.
        val cacheKey = (msgs.first().id shl 17) or msgs.size.toLong()
        fontSizeCache[cacheKey]?.let { return it }

        // Evict stale entries when cache grows large. Art blocks trimmed by
        // maxScrollbackLines leave orphan entries that are never invalidated.
        if (fontSizeCache.size >= 500) fontSizeCache.clear()

        // Not cached — run the binary search (same algorithm as rememberMotdFontSizeSp).
        val plainLines = msgs.map { stripIrcFormatting(it.text) }.filter { it.isNotEmpty() }
        val sp = if (plainLines.isEmpty() || availableWidthPx <= 0f) {
            naturalSizeSp
        } else {
            val widestAtNatural = plainLines.maxOf { line ->
                textMeasurer.measure(
                    text = line,
                    style = style.copy(fontSize = naturalSizeSp.sp),
                    constraints = Constraints(maxWidth = Int.MAX_VALUE),
                    maxLines = 1,
                    softWrap = false,
                ).size.width.toFloat()
            }
            if (widestAtNatural <= availableWidthPx) {
                naturalSizeSp
            } else {
                var lo = minFontSp
                var hi = naturalSizeSp
                repeat(8) {
                    val mid = (lo + hi) / 2f
                    val widest = plainLines.maxOf { line ->
                        textMeasurer.measure(
                            text = line,
                            style = style.copy(fontSize = mid.sp),
                            constraints = Constraints(maxWidth = Int.MAX_VALUE),
                            maxLines = 1,
                            softWrap = false,
                        ).size.width.toFloat()
                    }
                    if (widest <= availableWidthPx) lo = mid else hi = mid
                }
                lo
            }
        }
        fontSizeCache[cacheKey] = sp
        return sp
    }

    return rawItems.map { raw ->
        when (raw) {
            is RawItem.Single -> DisplayItem.Single(raw.msg)
            is RawItem.Art    -> DisplayItem.Art(raw.msgs, fontSizeForBlock(raw.msgs))
        }
    }
}

/**
 * Computes the single font size (in sp) that makes the widest MOTD line fit within
 * [availableWidthPx] at the given [style]. Every MOTD line must be rendered at this
 * same size so that monospace ASCII art columns stay aligned.
 *
 * Returns [style]'s natural size when all lines already fit, or [minFontSp] as a floor.
 */
@Composable
private fun rememberMotdFontSizeSp(
    motdLines: List<String>,
    style: androidx.compose.ui.text.TextStyle,
    availableWidthPx: Float,
    minFontSp: Float = 6f,
): Float {
    val textMeasurer = rememberTextMeasurer()
    val naturalSizeSp = style.fontSize.value.takeIf { !it.isNaN() && it > 0f } ?: 14f

    // Strip IRC formatting from every line for measurement (formatting chars have no width).
    val plainLines = remember(motdLines) { motdLines.map { stripIrcFormatting(it) } }

    return remember(plainLines, availableWidthPx, naturalSizeSp) {
        if (availableWidthPx <= 0f || plainLines.isEmpty()) return@remember naturalSizeSp

        // Find the widest line at the natural font size.
        val widestAtNatural = plainLines.maxOf { line ->
            textMeasurer.measure(
                text = line,
                style = style.copy(fontSize = naturalSizeSp.sp),
                constraints = Constraints(maxWidth = Int.MAX_VALUE),
                maxLines = 1,
                softWrap = false,
            ).size.width
        }
        if (widestAtNatural <= availableWidthPx) return@remember naturalSizeSp

        // Binary-search a single shared size that fits even the widest line.
        var lo = minFontSp
        var hi = naturalSizeSp
        repeat(8) {
            val mid = (lo + hi) / 2f
            val widest = plainLines.maxOf { line ->
                textMeasurer.measure(
                    text = line,
                    style = style.copy(fontSize = mid.sp),
                    constraints = Constraints(maxWidth = Int.MAX_VALUE),
                    maxLines = 1,
                    softWrap = false,
                ).size.width
            }
            if (widest <= availableWidthPx) lo = mid else hi = mid
        }
        lo
    }
}

/**
 * Renders a single MOTD line at [fontSizeSp]. The caller is responsible for computing
 * a shared font size across all MOTD lines (via [rememberMotdFontSizeSp]) so that
 * monospace ASCII art columns remain aligned across lines of different lengths.
 */
@Composable
private fun MotdLine(
    text: String,
    fontSizeSp: Float,
    style: androidx.compose.ui.text.TextStyle,
    mircColorsEnabled: Boolean,
    ansiColorsEnabled: Boolean = false,
    linkStyle: SpanStyle,
    onAnnotationClick: (String, String) -> Unit,
) {
    IrcLinkifiedText(
        text = text,
        mircColorsEnabled = mircColorsEnabled,
        ansiColorsEnabled = ansiColorsEnabled,
        linkStyle = linkStyle,
        onAnnotationClick = onAnnotationClick,
        style = style.copy(fontSize = fontSizeSp.sp),
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

/**
 * The character a key event types, or null when the event is a shortcut, a
 * modifier or a control key rather than text. Space counts as null because it
 * activates whatever control has focus.
 */
private fun typedCharacter(ev: KeyEvent): Char? {
    if (ev.type != KeyEventType.KeyDown) return null
    if (ev.isCtrlPressed || ev.isAltPressed || ev.isMetaPressed) return null
    val code = ev.utf16CodePoint
    if (code == 0 || code == ' '.code || Character.isISOControl(code)) return null
    return code.toChar()
}
