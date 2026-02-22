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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ripple
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.boxlabs.hexdroid.ChatFontStyle
import com.boxlabs.hexdroid.UiSettings
import com.boxlabs.hexdroid.UiState
import com.boxlabs.hexdroid.stripIrcFormatting
import com.boxlabs.hexdroid.ui.components.LagBar
import com.boxlabs.hexdroid.ui.theme.fontFamilyForChoice
import com.boxlabs.hexdroid.ui.tour.TourTarget
import com.boxlabs.hexdroid.ui.tour.tourTarget
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Commands with a short description shown in the hint popup. */
private data class IrcCommand(val name: String, val usage: String, val description: String)

private val IRC_COMMANDS = listOf(
    // Messaging
    IrcCommand("me",         "/me <action>",                   "Send a CTCP ACTION (/me waves)"),
    IrcCommand("msg",        "/msg <nick> <message>",          "Send a private message"),
    IrcCommand("notice",     "/notice <target> <text>",        "Send a NOTICE to a user or channel"),
    IrcCommand("amsg",       "/amsg <message>",                "Send a message to all open channels"),
    IrcCommand("ame",        "/ame <action>",                  "Send an action to all open channels"),

    // Channels
    IrcCommand("join",       "/join <channel> [key]",          "Join a channel"),
    IrcCommand("part",       "/part [channel] [reason]",       "Leave a channel"),
    IrcCommand("cycle",      "/cycle [channel]",               "Rejoin a channel (part then join)"),
    IrcCommand("topic",      "/topic [new topic]",             "Show or set the channel topic"),
    IrcCommand("invite",     "/invite <nick> [channel]",       "Invite a user to a channel"),
    IrcCommand("list",       "/list",                          "List all channels on the server"),
    IrcCommand("names",      "/names [channel]",               "List users in a channel"),

    // Buffer management
    IrcCommand("close",      "/close",                         "Close the current buffer"),
    IrcCommand("closekey",   "/closekey <net::buffer>",        "Close a specific buffer by key"),
    IrcCommand("find",       "/find <text>",                   "Search messages in the current buffer"),
    IrcCommand("grep",       "/grep <text>",                   "Alias for /find"),
    IrcCommand("search",     "/search <text>",                 "Alias for /find"),

    // User & nick
    IrcCommand("nick",       "/nick <new nick>",               "Change your nickname"),
    IrcCommand("away",       "/away [message]",                "Set away; /away with no args clears it"),
    IrcCommand("whois",      "/whois <nick>",                  "Query detailed info about a user"),
    IrcCommand("who",        "/who <mask>",                    "Query users matching a mask"),
    IrcCommand("ignore",     "/ignore [nick]",                 "Ignore a user (no args = list ignored)"),
    IrcCommand("unignore",   "/unignore <nick>",               "Remove a user from the ignore list"),
    IrcCommand("quit",       "/quit [reason]",                 "Quit IRC and disconnect"),

    // Moderation
    IrcCommand("kick",       "/kick <nick> [reason]",          "Kick a user from the channel"),
    IrcCommand("ban",        "/ban <nick>",                    "Ban a user from the channel"),
    IrcCommand("unban",      "/unban <nick>",                  "Remove a ban from the channel"),
    IrcCommand("kb",         "/kb <nick> [reason]",            "Kick and ban a user"),
    IrcCommand("kickban",    "/kickban <nick> [reason]",       "Alias for /kb"),
    IrcCommand("op",         "/op <nick> [channel]",           "Grant operator (+o) to a user"),
    IrcCommand("deop",       "/deop <nick> [channel]",         "Remove operator (-o) from a user"),
    IrcCommand("voice",      "/voice <nick> [channel]",        "Grant voice (+v) to a user"),
    IrcCommand("devoice",    "/devoice <nick> [channel]",      "Remove voice (-v) from a user"),
    IrcCommand("mode",       "/mode [target] <modes>",         "Set channel or user modes"),

    // Mode lists
    IrcCommand("banlist",    "/banlist",                       "Show the channel ban list (+b)"),
    IrcCommand("quietlist",  "/quietlist",                     "Show the quiet/mute list (+q)"),
    IrcCommand("exceptlist", "/exceptlist",                    "Show the ban exception list (+e)"),
    IrcCommand("invexlist",  "/invexlist",                     "Show the invite exception list (+I)"),

    // CTCP
    IrcCommand("ctcp",       "/ctcp <nick> <command>",         "Send a CTCP request"),
    IrcCommand("ping",       "/ping <nick>",                   "CTCP PING a user"),
    IrcCommand("ctcpping",   "/ctcpping <nick>",               "Alias for /ping"),
    IrcCommand("version",    "/version [nick]",                "CTCP VERSION query (no arg = server)"),
    IrcCommand("time",       "/time [server]",                 "Request server or remote time"),
    IrcCommand("finger",     "/finger <nick>",                 "CTCP FINGER a user"),
    IrcCommand("userinfo",   "/userinfo <nick>",               "CTCP USERINFO query"),
    IrcCommand("clientinfo", "/clientinfo <nick>",             "CTCP CLIENTINFO query"),

    // Server queries
    IrcCommand("motd",       "/motd [server]",                 "Request the server Message of the Day"),
    IrcCommand("admin",      "/admin [server]",                "Show server admin info"),
    IrcCommand("info",       "/info [server]",                 "Show server software info"),
    IrcCommand("dns",        "/dns <host|ip>",                 "Resolve a hostname or IP address"),

    // DCC
    IrcCommand("dcc",        "/dcc chat <nick>",               "Open a direct DCC chat with a user"),

    // IRC operator
    IrcCommand("oper",       "/oper <user> <password>",        "Authenticate as an IRC operator"),
    IrcCommand("sajoin",     "/sajoin <nick> <channel>",       "Force-join a user (IRCop only)"),
    IrcCommand("sapart",     "/sapart <nick> [channel]",       "Force-part a user (IRCop only)"),
    IrcCommand("kill",       "/kill <nick> [reason]",          "Kill (disconnect) a user (IRCop only)"),
    IrcCommand("kline",      "/kline <mask> <duration> [reason]","K-Line: ban by user@host (IRCop)"),
    IrcCommand("gline",      "/gline <mask> <duration> [reason]","G-Line: global ban (IRCop)"),
    IrcCommand("zline",      "/zline <ip> <duration> [reason]","Z-Line: ban by IP (IRCop)"),
    IrcCommand("dline",      "/dline <ip> <duration> [reason]","D-Line: deny connection by IP (IRCop)"),
    IrcCommand("eline",      "/eline <mask> <duration> [reason]","E-Line: ban exception (IRCop)"),
    IrcCommand("qline",      "/qline <mask> <duration> [reason]","Q-Line: nickname ban (IRCop)"),
    IrcCommand("shun",       "/shun <mask> <duration> [reason]","Shun: silence a user (IRCop)"),
    IrcCommand("wallops",    "/wallops <message>",             "Send a WALLOPS message (IRCop)"),
    IrcCommand("globops",    "/globops <message>",             "Send a GLOBOPS message (IRCop)"),
    IrcCommand("locops",     "/locops <message>",              "Send a LOCOPS message (IRCop)"),
    IrcCommand("operwall",   "/operwall <message>",            "Send an OPERWALL message (IRCop)"),

    // Misc
    IrcCommand("raw",        "/raw <command>",                 "Send a raw IRC line to the server"),
    IrcCommand("sysinfo",    "/sysinfo",                       "Post device system info to chat"),
)

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
    query: String,           // text after the leading '/' — must be non-empty
    onPick: (String) -> Unit // called with "/command " ready to type args
) {
    val matches = remember(query) {
        IRC_COMMANDS.filter { it.name.startsWith(query, ignoreCase = true) }
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
                    items(matches, key = { it.name }) { cmd ->
                        val isHighlighted = highlighted?.name == cmd.name
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isHighlighted)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable {
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
                            text = cmd.description,
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


/** Drag handle for sidebar network rows. Long-press to start drag.
 *  onDrag receives the CUMULATIVE y offset from the drag start position. */
@Composable
private fun SidebarDragHandle(
    onStart: () -> Unit,
    onDrag: (totalOffsetY: Float) -> Unit,
    onEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .pointerInput(Unit) {
                var accumulated = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { accumulated = 0f; onStart() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumulated += dragAmount.y
                        onDrag(accumulated)
                    },
                    onDragEnd = { onEnd() },
                    onDragCancel = { onEnd() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.DragHandle,
            contentDescription = "Drag to reorder",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    state: UiState,
    onSelectBuffer: (String) -> Unit,
    onSend: (String) -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onExit: () -> Unit,
    onToggleBufferList: () -> Unit,
    onToggleNickList: () -> Unit,
    onToggleChannelsOnly: () -> Unit,
    onWhois: (String) -> Unit,
    onIgnoreNick: (String, String) -> Unit,
    onUnignoreNick: (String, String) -> Unit,
    onRefreshNicklist: () -> Unit,
    onOpenList: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNetworks: () -> Unit,
    onOpenTransfers: () -> Unit,
    onSysInfo: () -> Unit,
    onAbout: () -> Unit,
    onUpdateSettings: (UiSettings.() -> UiSettings) -> Unit,
    onReorderNetworks: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onToggleNetworkExpanded: (netId: String) -> Unit = {},
    tourActive: Boolean = false,
    tourTarget: TourTarget? = null,
) {
    val scope = rememberCoroutineScope()
    val cfg = LocalConfiguration.current
    // Use a split-pane layout in landscape, but keep side panes proportionate so they don't dominate on phones.
    // On very large screens we also keep split panes in portrait.
    val isWide = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE || cfg.screenWidthDp >= 840

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Intro tour
    val tourWantsBuffers =
        tourActive && (tourTarget == TourTarget.CHAT_BUFFER_DRAWER || tourTarget == TourTarget.CHAT_DRAWER_BUTTON)

    // When the tour is on the "Switch buffers" step, ensure the buffer list is actually visible.
    // On narrow layouts that means opening the drawer; on wide layouts we temporarily force the split pane to show.
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

    // Pre-group buffer keys by network to avoid expensive per-network filtering on every recomposition.
    data class NetBuffers(val serverKey: String, val others: List<String>)

    val buffersByNet = remember(state.buffers, state.channelsOnly) {
        val groups = mutableMapOf<String, MutableList<String>>()
        for (k in state.buffers.keys) {
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
    ) {
        val unread = meta?.unread ?: 0
        val hi = meta?.highlights ?: 0

        Column(
            Modifier
                .fillMaxWidth()
                .clickable {
                    // Close the drawer on phones/tablets in portrait before switching buffers.
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

    var input by remember { mutableStateOf(TextFieldValue("")) }
    var inputHasFocus by remember { mutableStateOf(false) }

    var showColorPicker by remember { mutableStateOf(false) }
    var selectedFgColor by remember { mutableStateOf<Int?>(null) }   // 0-15 or null
    var selectedBgColor by remember { mutableStateOf<Int?>(null) }   // 0-15 or null
    var boldActive by remember { mutableStateOf(false) }
    var italicActive by remember { mutableStateOf(false) }
    var underlineActive by remember { mutableStateOf(false) }
    var reverseActive by remember { mutableStateOf(false) }


    // Tracks IME bottom inset (keyboard) in pixels so we can avoid disabling tail-follow during IME resize.
    val imeBottomPx = with(LocalDensity.current) {
        WindowInsets.ime.asPaddingValues().calculateBottomPadding().toPx().toInt()
    }
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
    val currentModeString = if (isChannel) state.buffers[selected]?.modeString else null

    val bgLum = MaterialTheme.colorScheme.background.luminance()

    // Precompute a perceptually-separated palette and assign unique colours within this buffer.
    val nickPalette = remember(bgLum) { NickColors.buildPalette(bgLum) }
    val nickColorMap = remember(selected, nicklist, messages, bgLum, state.settings.colorizeNicks) {
        if (!state.settings.colorizeNicks) emptyMap()
        else {
            val bases = LinkedHashSet<String>()
            for (n in nicklist) bases.add(baseNick(n).lowercase())
            for (m in messages) m.from?.let { bases.add(baseNick(it).lowercase()) }
            NickColors.assignColors(bases.toList(), nickPalette)
        }
    }

    fun nickColor(nick: String): Color {
        val base = baseNick(nick).lowercase()
        return nickColorMap[base] ?: NickColors.colorFromHash(base, nickPalette)
    }

    var showNickSheet by remember { mutableStateOf(false) }

    var showNickActions by remember { mutableStateOf(false) }
    var selectedNick by remember { mutableStateOf("") }

    var showChanOps by remember { mutableStateOf(false) }
    var showIrcOpTools by remember { mutableStateOf(false) }
    var showChanListSheet by remember { mutableStateOf(false) }
    var chanListTab by remember { mutableIntStateOf(0) } // 0=bans,1=quiets,2=excepts,3=invex
    var opsNick by remember { mutableStateOf("") }
    var opsReason by remember { mutableStateOf("") }
    var opsTopic by remember(selected, topic) { mutableStateOf(topic ?: "") }
    var topicExpanded by remember(selected, topic) { mutableStateOf(false) }
    var topicHasOverflow by remember(selected, topic) { mutableStateOf(false) }

    var overflowExpanded by remember { mutableStateOf(false) }

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
        val formattedText = buildString {
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

        input = TextFieldValue("")
        onSend(formattedText)

        // Optionally reset formatting after sending (comment out to keep it persistent)
        // selectedFgColor = null
        // selectedBgColor = null
        // boldActive = false
        // italicActive = false
        // underlineActive = false
        // reverseActive = false
    }

    fun openNickActions(nickDisplay: String) {
        selectedNick = baseNick(nickDisplay)
        showNickActions = true
    }

    fun mention(nick: String) {
        input =
            if (input.text.isBlank()) TextFieldValue("$nick: ") else TextFieldValue(input.text + " $nick")
    }

	@Composable
	fun BufferDrawer(mod: Modifier = Modifier) {
		// During a drag this holds the reordered network IDs so that child rows
		// (channels) move with their parent without any graphicsLayer hacks.
		// null means "use the natural sort order".
		var dragNetworkOrder by remember { mutableStateOf<List<String>?>(null) }

		val sidebarItems = remember(state.networks, buffersByNet, state.channelsOnly, selected, state.collapsedNetworkIds, dragNetworkOrder) {
			val out = mutableListOf<SidebarItem>()
			val naturalOrder = state.networks
				.sortedWith(compareBy({ !it.isFavourite }, { it.sortOrder }, { it.name }))
			val sortedNets = if (dragNetworkOrder != null) {
				// Reorder according to live drag state — nets not in the drag list fall back to end
				val map = naturalOrder.associateBy { it.id }
				dragNetworkOrder!!.mapNotNull { map[it] } +
					naturalOrder.filter { it.id !in dragNetworkOrder!! }
			} else naturalOrder
			for (net in sortedNets) {
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
			mod.padding(horizontal = 16.dp, vertical = 14.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp)
		) {
			val listState = rememberLazyListState()

			// Current display order of root netIds — kept in sync with sidebarItems
			val netOrder = remember(sidebarItems) {
				sidebarItems.mapNotNull { item ->
					when {
						item is SidebarItem.Header -> item.netId
						item is SidebarItem.Buffer && item.isNetworkHeader -> item.netId
						else -> null
					}
				}
			}
			// Drag state — index-based swap approach:
			// We track the dragged item's current index in netOrder and how far it has moved.
			// When the cumulative offset exceeds half the height of the next/previous slot,
			// we swap it one position and reset the offset accumulator.
			var dragNetId       by remember { mutableStateOf<String?>(null) }
			var dragOriginalIdx by remember { mutableIntStateOf(-1) }
			var dragCurrentIdx  by remember { mutableIntStateOf(-1) }
			var dragAdjustmentY by remember { mutableFloatStateOf(0f) }
			var dragTranslationY by remember { mutableFloatStateOf(0f) }
			// netId -> measured height (updated freely, used for swap threshold)
			val slotHeights = remember { mutableMapOf<String, Float>() }

			LazyColumn(
				state = listState,
				modifier = Modifier
					.fillMaxSize()
					.tourTarget(TourTarget.CHAT_BUFFER_DRAWER),
				contentPadding = PaddingValues(vertical = 6.dp)
			) {
				items(sidebarItems, key = { it.stableKey }) { item ->
					// Derive root netId directly from item properties — no index lookup needed
					val rootNetId: String? = when {
						item is SidebarItem.Header -> item.netId
						item is SidebarItem.Buffer && item.isNetworkHeader -> item.netId
						else -> null
					}
					val isRoot    = rootNetId != null
					val isDragging = isRoot && dragNetId == rootNetId

					Box(modifier = Modifier
						.animateItem()
						.graphicsLayer { if (isDragging) translationY = dragTranslationY else 0f }
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
									Modifier
										.padding(start = 6.dp, top = 12.dp, bottom = 8.dp)
										.onGloballyPositioned { coords ->
											val id = rootNetId ?: return@onGloballyPositioned
											slotHeights[id] = coords.size.height.toFloat()
										}
								) {
									Row(
										Modifier
											.fillMaxWidth()
											.clickable { onToggleNetworkExpanded(item.netId) },
										verticalAlignment = Alignment.CenterVertically
									) {
										Icon(
											imageVector = if (item.expanded) Icons.Default.KeyboardArrowDown
														  else Icons.AutoMirrored.Filled.KeyboardArrowRight,
											contentDescription = if (item.expanded) "Collapse" else "Expand",
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
												onStart = {
													val id = rootNetId ?: return@SidebarDragHandle
													val idx = netOrder.indexOf(id)
													if (idx < 0) return@SidebarDragHandle
													dragNetId       = id
													dragOriginalIdx = idx
													dragCurrentIdx  = idx
													dragAdjustmentY = 0f
													dragTranslationY = 0f
													dragNetworkOrder = netOrder.toList()
												},
												onDrag = { dy ->
													val order = dragNetworkOrder ?: return@SidebarDragHandle
													dragNetId ?: return@SidebarDragHandle
													var accum = dy - dragAdjustmentY
													var curIdx = dragCurrentIdx
													var curOrder = order.toMutableList()
													var changed = false
													while (true) {
														if (accum >= 0f && curIdx < curOrder.size - 1) {
															val nextId = curOrder[curIdx + 1]
															val h = slotHeights[nextId] ?: 60f
															val threshold = h / 2f
															if (accum >= threshold) {
																curOrder.add(curIdx + 1, curOrder.removeAt(curIdx))
																dragAdjustmentY += h
																accum -= h
																curIdx++
																changed = true
																continue
															}
														} else if (accum < 0f && curIdx > 0) {
															val prevId = curOrder[curIdx - 1]
															val h = slotHeights[prevId] ?: 60f
															val threshold = h / 2f
															if (accum < -threshold) {
																curOrder.add(curIdx - 1, curOrder.removeAt(curIdx))
																dragAdjustmentY -= h
																accum += h
																curIdx--
																changed = true
																continue
															}
														}
														break
													}
													if (changed) {
														dragCurrentIdx = curIdx
														dragNetworkOrder = curOrder
													}
													dragTranslationY = accum
												},
												onEnd = {
													val origIdx = dragOriginalIdx
													val newIdx = dragCurrentIdx
													if (dragNetId != null && origIdx >= 0 && newIdx >= 0 && origIdx != newIdx)
														onReorderNetworks(origIdx, newIdx)
													dragNetId = null
													dragOriginalIdx = -1
													dragCurrentIdx = -1
													dragAdjustmentY = 0f
													dragTranslationY = 0f
													dragNetworkOrder = null
												}
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
								val rowMod = if (isRoot) {
									Modifier.onGloballyPositioned { coords ->
										val id = rootNetId ?: return@onGloballyPositioned
										slotHeights[id] = coords.size.height.toFloat()
									}
								} else Modifier
								Row(modifier = rowMod, verticalAlignment = Alignment.CenterVertically) {
									// Chevron for network header rows (server buffer acting as header)
									if (item.isNetworkHeader && item.netId != null) {
										Icon(
											imageVector = if (item.expanded) Icons.Default.KeyboardArrowDown
														  else Icons.AutoMirrored.Filled.KeyboardArrowRight,
											contentDescription = if (item.expanded) "Collapse" else "Expand",
											modifier = Modifier
												.size(16.dp)
												.clickable { onToggleNetworkExpanded(item.netId) },
											tint = MaterialTheme.colorScheme.onSurfaceVariant
										)
									}
									Box(modifier = Modifier.weight(1f)) {
										BufferRow(
											key = item.key,
											label = item.label,
											selected = selected,
											meta = state.buffers[item.key],
											indent = item.indent,
											closable = closable,
											onClose = { onSend("/closekey ${item.key}") },
											lagLabel = lag?.first,
											lagProgress = lag?.second,
										)
									}
									if (isRoot) {
										SidebarDragHandle(
											onStart = {
												val id = rootNetId ?: return@SidebarDragHandle
												val idx = netOrder.indexOf(id)
												if (idx < 0) return@SidebarDragHandle
												dragNetId       = id
												dragOriginalIdx = idx
												dragCurrentIdx  = idx
												dragAdjustmentY = 0f
												dragTranslationY = 0f
												dragNetworkOrder = netOrder.toList()
											},
											onDrag = { dy ->
												val order = dragNetworkOrder ?: return@SidebarDragHandle
												dragNetId ?: return@SidebarDragHandle
												var accum = dy - dragAdjustmentY
												var curIdx = dragCurrentIdx
												var curOrder = order.toMutableList()
												var changed = false
												while (true) {
													if (accum >= 0f && curIdx < curOrder.size - 1) {
														val nextId = curOrder[curIdx + 1]
														val h = slotHeights[nextId] ?: 60f
														val threshold = h / 2f
														if (accum >= threshold) {
															curOrder.add(curIdx + 1, curOrder.removeAt(curIdx))
															dragAdjustmentY += h
															accum -= h
															curIdx++
															changed = true
															continue
														}
													} else if (accum < 0f && curIdx > 0) {
														val prevId = curOrder[curIdx - 1]
														val h = slotHeights[prevId] ?: 60f
														val threshold = h / 2f
														if (accum < -threshold) {
															curOrder.add(curIdx - 1, curOrder.removeAt(curIdx))
															dragAdjustmentY -= h
															accum += h
															curIdx--
															changed = true
															continue
														}
													}
													break
												}
												if (changed) {
													dragCurrentIdx = curIdx
													dragNetworkOrder = curOrder
												}
												dragTranslationY = accum
											},
											onEnd = {
												val origIdx = dragOriginalIdx
												val newIdx = dragCurrentIdx
												if (dragNetId != null && origIdx >= 0 && newIdx >= 0 && origIdx != newIdx)
													onReorderNetworks(origIdx, newIdx)
												dragNetId = null
												dragOriginalIdx = -1
												dragCurrentIdx = -1
												dragAdjustmentY = 0f
												dragTranslationY = 0f
												dragNetworkOrder = null
											}
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

    @Composable
    fun NicklistContent(mod: Modifier = Modifier) {
        Column(mod.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${nicklist.size} users",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(nicklist) { n ->
                    val cleaned = baseNick(n)
                    Text(
                        n,
                        color = if (state.settings.colorizeNicks) nickColor(cleaned) else Color.Unspecified,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openNickActions(n) }
                            .padding(vertical = 5.dp)
                    )
                }
            }
        }
    }

    val listState = rememberLazyListState()

    // Kick a tail-follow scroll when returning to the app.
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Only auto-scroll when the user is at the bottom AND they are not currently interacting
    // with the messages list (e.g. scrolling/holding for selection).
    var isTouchingMessages by remember(selected) { mutableStateOf(false) }

    // Much more reliable than inspecting visibleItemsInfo (which can lag a frame behind during fast updates).
    val isAtBottom by remember(selected) {
        derivedStateOf { !listState.canScrollForward }
    }

    var followTail by remember(selected) { mutableStateOf(true) }

    // When switching buffers, listState may still reflect the previous channel for a moment.
    // Suppress followTail updates until we've had a chance to scroll-to-bottom once.
    var suppressFollowUpdate by remember(selected) { mutableStateOf(true) }

    // If the user scrolls up, stop following. If they scroll back to the bottom, resume following.
    LaunchedEffect(
        selected,
        isAtBottom,
        listState.isScrollInProgress,
        isTouchingMessages,
        suppressFollowUpdate,
        inputHasFocus,
        imeBottomPx
    ) {
        if (suppressFollowUpdate) return@LaunchedEffect
        // When the IME opens, the viewport shrinks and isAtBottom can briefly flip false.
        // Don't drop followTail during that resize while the input is focused.
        if (inputHasFocus && imeBottomPx > 0) return@LaunchedEffect
        if (!isTouchingMessages && !listState.isScrollInProgress) {
            followTail = isAtBottom
        } else if (!isAtBottom) {
            followTail = false
        }
    }

    LaunchedEffect(resumeTick, selected) {
        if (messages.isNotEmpty() && followTail) {
            // Let layout settle after resume.
            delay(30)
            runCatching { listState.scrollToItem(messages.size - 1) }
            delay(30)
            runCatching { listState.scrollToItem(messages.size - 1) }
        }
    }

    val lastMsgId = messages.lastOrNull()?.id

    LaunchedEffect(selected, lastMsgId, messages.size) {
        // Tail-follow new messages (only if we're already following).
        if (lastMsgId == null) return@LaunchedEffect
        if (followTail && !isTouchingMessages && !listState.isScrollInProgress) {
            // Give the LazyColumn a moment to measure the new content (especially important for long wrapped lines).
            delay(20)
            runCatching { listState.scrollToItem(messages.lastIndex) }
            delay(20)
            runCatching { listState.scrollToItem(messages.lastIndex) }
            suppressFollowUpdate = false
        }
    }

    LaunchedEffect(inputHasFocus, selected, imeBottomPx) {
        if (inputHasFocus && imeBottomPx > 0 && messages.isNotEmpty() && !isTouchingMessages) {
            // When keyboard opens, the viewport shrinks; keep the tail in view if we were following.
            if (followTail || isAtBottom) {
                suppressFollowUpdate = true
                followTail = true
                // The IME animates; scroll a few times as insets settle.
                repeat(3) {
                    delay(120)
                    runCatching { listState.scrollToItem(messages.lastIndex) }
                }
                suppressFollowUpdate = false
            }
        }
    }

    LaunchedEffect(selected, isTouchingMessages) {
        if (isTouchingMessages) suppressFollowUpdate = false
    }

    val uriHandler = LocalUriHandler.current
    val (baseWeight, baseStyle) = when (state.settings.chatFontStyle) {
        ChatFontStyle.REGULAR -> FontWeight.Normal to FontStyle.Normal
        ChatFontStyle.BOLD -> FontWeight.Bold to FontStyle.Normal
        ChatFontStyle.ITALIC -> FontWeight.Normal to FontStyle.Italic
        ChatFontStyle.BOLD_ITALIC -> FontWeight.Bold to FontStyle.Italic
    }

    val chatTextStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = fontFamilyForChoice(state.settings.chatFontChoice),
        fontWeight = baseWeight,
        fontStyle = baseStyle
    )

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

    val topBar: @Composable () -> Unit = {
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val barHeight = when {
            cfg.orientation == Configuration.ORIENTATION_LANDSCAPE -> 44.dp
            state.settings.compactMode -> 48.dp
            else -> 50.dp
        }

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
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isWide) {
                        IconButton(
                            onClick = onToggleBufferList,
                            modifier = Modifier.tourTarget(TourTarget.CHAT_DRAWER_BUTTON)
                        ) { Text("☰") }
                    } else {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.tourTarget(TourTarget.CHAT_DRAWER_BUTTON)
                        ) { Text("☰") }
                    }

                    Text(
                        text = topBarTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )

                    // Colour/formatting picker button with active state indicator
                    run {
                        val colorInteraction = remember { MutableInteractionSource() }
                        val colorPressed by colorInteraction.collectIsPressedAsState()
                        val hasActiveFormatting =
                            selectedFgColor != null || selectedBgColor != null ||
                                    boldActive || italicActive || underlineActive || reverseActive

                        Box(
                            modifier = Modifier
                                .size(25.dp)
                                .scale(if (colorPressed) 0.92f else 1f)
                                .background(
                                    brush = if (hasActiveFormatting) {
                                        // Show the active foreground color or a gradient if formatting is active
                                        val fgCol = selectedFgColor?.let { mircColor(it) } ?: Color(
                                            0xFFFF6B6B
                                        )
                                        Brush.linearGradient(
                                            listOf(
                                                fgCol,
                                                fgCol.copy(alpha = 0.7f)
                                            )
                                        )
                                    } else {
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
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .then(
                                    if (hasActiveFormatting) {
                                        Modifier.border(
                                            2.dp,
                                            Color.White.copy(alpha = 0.8f),
                                            RoundedCornerShape(10.dp)
                                        )
                                    } else Modifier
                                )
                                .clickable(
                                    interactionSource = colorInteraction,
                                    indication = ripple(bounded = false),
                                    onClick = { showColorPicker = true }
                                )
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            // Show formatting indicators or the icon
                            if (hasActiveFormatting) {
                                Text(
                                    text = buildString {
                                        if (boldActive) append("B")
                                        if (italicActive) append("I")
                                        if (underlineActive) append("U")
                                    }.ifEmpty { "A" },
                                    color = Color.White,
                                    fontWeight = if (boldActive) FontWeight.Bold else FontWeight.Medium,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontStyle = if (italicActive) FontStyle.Italic else FontStyle.Normal,
                                        textDecoration = if (underlineActive) TextDecoration.Underline else TextDecoration.None
                                    )
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.FormatColorText,
                                    contentDescription = "Text formatting",
                                    tint = Color.White.copy(alpha = if (colorPressed) 0.7f else 0.9f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Nicklist button
                    run {
                        val nicklistInteraction = remember { MutableInteractionSource() }
                        val nicklistPressed by nicklistInteraction.collectIsPressedAsState()
                        Box(
                            modifier = Modifier
                                .size(25.dp)
                                .scale(if (nicklistPressed) 0.92f else 1f)
                                .alpha(if (isChannel) 1f else 0.4f)
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF5B86E5),  // Blue
                                            Color(0xFF36D1DC)   // Cyan
                                        )
                                    ),
                                    shape = MaterialTheme.shapes.small
                                )
                                .then(
                                    if (isChannel) {
                                        Modifier.clickable(
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
                                imageVector = Icons.Filled.People,
                                contentDescription = "User list",
                                tint = Color.White.copy(alpha = if (nicklistPressed) 0.7f else 0.9f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Box {
                        IconButton(
                            onClick = { overflowExpanded = true },
                            modifier = Modifier.tourTarget(TourTarget.CHAT_OVERFLOW_BUTTON)
                        ) { Text("⋮") }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Channel list") },
                                onClick = { overflowExpanded = false; onOpenList() }
                            )
                            DropdownMenuItem(
                                text = { Text("File transfers") },
                                onClick = { overflowExpanded = false; onOpenTransfers() }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = { overflowExpanded = false; onOpenSettings() }
                            )
                            DropdownMenuItem(
                                text = { Text("Networks") },
                                onClick = { overflowExpanded = false; onOpenNetworks() }
                            )
                            DropdownMenuItem(
                                text = { Text("System info") },
                                onClick = { overflowExpanded = false; onSysInfo() }
                            )
                            if (isIrcOper) {
                                DropdownMenuItem(
                                    text = { Text("IRCop tools") },
                                    onClick = { overflowExpanded = false; showIrcOpTools = true }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("About") },
                                onClick = { overflowExpanded = false; onAbout() }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Reconnect") },
                                enabled = state.networks.isNotEmpty() && !state.connecting,
                                onClick = { overflowExpanded = false; onReconnect() }
                            )
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                onClick = { overflowExpanded = false; onDisconnect() }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Exit") },
                                onClick = { overflowExpanded = false; onExit() }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun MessagesPane(mod: Modifier = Modifier) {
        Column(mod) {
            if (state.settings.showTopicBar && isChannel && !topic.isNullOrBlank()) {
                Surface(tonalElevation = 1.dp) {
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
                                contentPadding = PaddingValues(0.dp)
                            ) { Text(if (topicExpanded) "less" else "more") }
                        }
                    }
                }
                HorizontalDivider()
            }

            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(selected) {
                            // Mark that the user is touching/gesturing on the messages list so we don't auto-jump to bottom.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                isTouchingMessages = true
                                waitForUpOrCancellation()
                                isTouchingMessages = false
                            }
                        },

                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(items = messages, key = { it.id }) { m ->
                        val ts =
                            if (state.settings.showTimestamps) "[${timeFmt.format(Date(m.timeMs))}] " else ""
                        val fromNick = m.from
                        if (fromNick == null) {
                            if (m.isMotd && selBufName == "*server*") {
                                // MOTD lines: auto-shrink font so they always fit on one line,
                                // preserving ASCII art that depends on monospace column alignment.
                                // lineHeight = fontSize so there's no extra internal leading.
                                AutoSizedMotdLine(
                                    text = m.text,  // timestamps omitted — would skew auto-sizing and look odd
                                    style = chatTextStyle.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified),
                                    mircColorsEnabled = state.settings.mircColorsEnabled,
                                    linkStyle = linkStyle,
                                    onAnnotationClick = onAnnotationClick,
                                )
                            } else {
                                IrcLinkifiedText(
                                    text = ts + m.text,
                                    mircColorsEnabled = state.settings.mircColorsEnabled,
                                    linkStyle = linkStyle,
                                    onAnnotationClick = onAnnotationClick,
                                    style = chatTextStyle
                                )
                            }
                        } else if (m.isAction) {
                            val fromDisplay = displayNick(fromNick)
                            val fromBase = baseNick(fromDisplay)
                            val annotated = buildAnnotatedString {
                                append(ts)
                                append("* ")
                                pushStringAnnotation(tag = ANN_NICK, annotation = fromBase)
                                withStyle(
                                    SpanStyle(
                                        color = if (state.settings.colorizeNicks) nickColor(fromBase) else Color.Unspecified
                                    )
                                ) { append(fromDisplay) }
                                pop()
                                append(" ")
                                appendIrcStyledLinkified(
                                    m.text,
                                    linkStyle,
                                    state.settings.mircColorsEnabled
                                )
                            }
                            AnnotatedClickableText(
                                text = annotated,
                                onAnnotationClick = onAnnotationClick,
                                style = chatTextStyle
                            )
                        } else {
                            val fromDisplay = displayNick(fromNick)
                            val fromBase = baseNick(fromDisplay)
                            val annotated = buildAnnotatedString {
                                append(ts)
                                append("<")
                                pushStringAnnotation(tag = ANN_NICK, annotation = fromBase)
                                withStyle(
                                    SpanStyle(
                                        color = if (state.settings.colorizeNicks) nickColor(fromBase) else Color.Unspecified
                                    )
                                ) { append(fromDisplay) }
                                pop()
                                append("> ")
                                appendIrcStyledLinkified(
                                    m.text,
                                    linkStyle,
                                    state.settings.mircColorsEnabled
                                )
                            }
                            AnnotatedClickableText(
                                text = annotated,
                                onAnnotationClick = onAnnotationClick,
                                style = chatTextStyle
                            )
                        }
                        // No spacing between MOTD lines — preserves ASCII art layout.
                        if (!m.isMotd || selBufName != "*server*") {
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }

	val bottomBar: @Composable () -> Unit = {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val bottomInset = maxOf(navBottom, imeBottom)

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
        // (bare "/" alone doesn't trigger — it would show all 68 commands at once)
        val cmdQuery = remember(input.text) {
            val t = input.text
            if (t.length >= 2 && t.startsWith("/") && !t.contains(" ")) t.drop(1) else null
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            // Command hints popup — rendered above the input row inside a Column
            if (cmdQuery != null) {
                CommandHints(
                    query = cmdQuery,
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .padding(bottom = bottomInset),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Build the text style for the input based on active formatting
                val defaultTextColor = MaterialTheme.colorScheme.onSurface
                val inputTextStyle = chatTextStyle.copy(
                    color = selectedFgColor?.let { mircColor(it) } ?: defaultTextColor,
                    fontWeight = if (boldActive) FontWeight.Bold else chatTextStyle.fontWeight,
                    fontStyle = if (italicActive) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (underlineActive) TextDecoration.Underline else TextDecoration.None,
                    background = selectedBgColor?.let { mircColor(it) } ?: Color.Unspecified
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
					onValueChange = { input = it },
					modifier = Modifier
						.weight(1f)
						.heightIn(min = 40.dp)
						.tourTarget(TourTarget.CHAT_INPUT)
						.onFocusChanged { inputHasFocus = it.isFocused },
					textStyle = inputTextStyle,
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
					keyboardActions = KeyboardActions(onSend = { sendNow() }),
					singleLine = false,
					maxLines = 2,
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
									text = "Message",
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
									shape = RoundedCornerShape(10.dp)
								)
							}
						)
					}
				)

                // Channel ops button - only shown when user has permissions
                if (isChannel && (canKick || canBan || canTopic)) {
                    val opsInteraction = remember { MutableInteractionSource() }
                    val opsPressed by opsInteraction.collectIsPressedAsState()

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .scale(if (opsPressed) 0.92f else 1f)
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFFFF9500),  // Orange
                                        Color(0xFFFF5E3A)   // Red-orange
                                    )
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
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
                            contentDescription = "Channel tools",
                            tint = Color.White.copy(alpha = if (opsPressed) 0.7f else 1f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Send button - gradient with arrow icon
                run {
                    val sendInteraction = remember { MutableInteractionSource() }
                    val sendPressed by sendInteraction.collectIsPressedAsState()

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .scale(if (sendPressed) 0.92f else 1f)
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
										Color(0xFF5B86E5),  // Blue
										Color(0xFF36D1DC)   // Cyan
                                    )
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
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
                            contentDescription = "Send message",
                            tint = Color.White.copy(alpha = if (sendPressed) 0.7f else 1f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        } // closes Column wrapper for CommandHints + Surface
    }
	
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
                    Box(
                        modifier = Modifier
                            .width(10.dp)
                            .fillMaxHeight()
                            .draggable(
                                orientation = Orientation.Horizontal,
                                state = portraitDragSt,
                                startDragImmediately = true,
                                onDragStarted = { portraitDragging = true },
                                onDragStopped = {
                                    portraitDragging = false
                                    val clamped = portraitNickFrac.coerceIn(
                                        minPortraitNickFrac,
                                        maxPortraitNickFrac
                                    )
                                    portraitNickFrac = clamped
                                    onUpdateSettings { copy(portraitNickPaneFrac = clamped) }
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

                    NicklistContent(Modifier
                        .width(nickPaneW)
                        .fillMaxHeight())
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

            // Persisted fractions (updated on drag end).
            var bufferFrac by remember(state.settings.bufferPaneFracLandscape) {
                mutableFloatStateOf(state.settings.bufferPaneFracLandscape)
            }
            var nickFrac by remember(state.settings.nickPaneFracLandscape) {
                mutableFloatStateOf(state.settings.nickPaneFracLandscape)
            }

            val minBufferDp = 130.dp
            val maxBufferDp = 320.dp
            val minNickDp = 130.dp
            val maxNickDp = 280.dp

            val minBufferFrac = (minBufferDp.value / screenWdp).coerceIn(0.10f, 0.60f)
            val maxBufferFrac = (maxBufferDp.value / screenWdp).coerceIn(0.10f, 0.60f)
            val minNickFrac = (minNickDp.value / screenWdp).coerceIn(0.08f, 0.55f)
            val maxNickFrac = (maxNickDp.value / screenWdp).coerceIn(0.08f, 0.55f)

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

                Box(
                    modifier = Modifier
                        // Bigger touch target helps a lot in landscape / gesture navigation.
                        .width(15.dp)
                        .fillMaxHeight()
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
                        NicklistContent(Modifier
                            .width(nickPaneW)
                            .fillMaxHeight())
                    }
                }
            }
        }
    }

    val scaffold: @Composable () -> Unit = {
        Scaffold(
            modifier = Modifier.windowInsetsPadding(
                WindowInsets.navigationBars.only(
                    WindowInsetsSides.Horizontal
                )
            ),
            topBar = topBar,
            bottomBar = bottomBar,
            content = scaffoldContent,
        )
    }

    if (!isWide) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = { ModalDrawerSheet { BufferDrawer() } }
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
        ModalBottomSheet(onDismissRequest = { showChanOps = false }) {
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
                Text("Channel tools", style = MaterialTheme.typography.titleLarge)
                Text("$selNetName • $selBufName", style = MaterialTheme.typography.bodySmall)
                if (currentModeString != null) {
                    Text(
                        text = "Current modes: $currentModeString",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedButton(
                        onClick = { onSend("/mode $selBufName") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Fetch current modes") }
                }
                HorizontalDivider()

                // Topic
                if (canTopic) {
                    Text("Topic", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = opsTopic,
                        onValueChange = { opsTopic = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        label = { Text("New topic") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val t = opsTopic.trim()
                            onSend(if (t.isBlank()) "/topic $selBufName" else "/topic $selBufName $t")
                            showChanOps = false
                        }) { Text("Set") }
                        OutlinedButton(onClick = { opsTopic = topic ?: "" }) { Text("Reset") }
                    }
                    HorizontalDivider()
                }

                // Channel mode toggles
                if (canMode) {
                    Text("Channel modes", fontWeight = FontWeight.Bold)

                    // Parse active simple modes from currentModeString for toggle state
                    val activeModes = currentModeString?.removePrefix("+") ?: ""

                    @Composable
                    fun ModeToggle(flag: Char, label: String, description: String) {
                        val active = flag in activeModes
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSend("/mode $selBufName ${if (active) "-" else "+"}$flag")
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Switch(checked = active, onCheckedChange = {
                                onSend("/mode $selBufName ${if (active) "-" else "+"}$flag")
                            })
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

                    ModeToggle('n', "No external messages", "Block messages from users not in the channel")
                    ModeToggle('t', "Ops-only topic", "Only ops can change the topic")
                    ModeToggle('m', "Moderated", "Only voiced users and ops can speak")
                    ModeToggle('i', "Invite only", "Users must be invited to join")
                    ModeToggle('s', "Secret", "Channel hidden from /LIST and /WHOIS")
                    ModeToggle('p', "Private", "Channel shown as private in /LIST")
                    ModeToggle('r', "Registered only", "Only registered nicks can join")
                    ModeToggle('c', "No colour", "Strip mIRC colour codes from messages")
                    ModeToggle('C', "No CTCPs", "Block CTCP messages to the channel")

                    // Key (password)
                    Spacer(Modifier.height(4.dp))
                    Text("Channel key (+k)", fontWeight = FontWeight.SemiBold,
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
                            label = { Text("Key / password") }
                        )
                        Button(onClick = {
                            val k = keyInput.trim()
                            if (k.isNotBlank()) onSend("/mode $selBufName +k $k")
                        }, enabled = keyInput.isNotBlank()) { Text("Set") }
                        OutlinedButton(onClick = { onSend("/mode $selBufName -k *") }) { Text("Remove") }
                    }

                    // Limit
                    Spacer(Modifier.height(4.dp))
                    Text("User limit (+l)", fontWeight = FontWeight.SemiBold,
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
                            label = { Text("Max users") }
                        )
                        Button(onClick = {
                            val l = limitInput.trim()
                            if (l.isNotBlank()) onSend("/mode $selBufName +l $l")
                        }, enabled = limitInput.isNotBlank()) { Text("Set") }
                        OutlinedButton(onClick = { onSend("/mode $selBufName -l"); limitInput = "" }) { Text("Remove") }
                    }

                    HorizontalDivider()
                }

                // Kick / Ban
                if (canKick || canBan) {
                    Text("Kick / Ban", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = opsNick,
                        onValueChange = { opsNick = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Nick") }
                    )
                    OutlinedTextField(
                        value = opsReason,
                        onValueChange = { opsReason = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Reason") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canKick) {
                            Button(
                                onClick = {
                                    val n = opsNick.trim()
                                    if (n.isNotBlank()) {
                                        val r = opsReason.trim()
                                        onSend(if (r.isBlank()) "/kick $selBufName $n" else "/kick $selBufName $n $r")
                                        showChanOps = false
                                    }
                                }
                            ) { Text("Kick") }
                        }
                        if (canBan) {
                            OutlinedButton(
                                onClick = {
                                    val n = opsNick.trim()
                                    if (n.isNotBlank()) {
                                        onSend("/ban $selBufName $n")
                                        showChanOps = false
                                    }
                                }
                            ) { Text("Ban") }
                            OutlinedButton(
                                onClick = {
                                    val n = opsNick.trim()
                                    if (n.isNotBlank()) {
                                        val r = opsReason.trim()
                                        onSend(if (r.isBlank()) "/kb $selBufName $n" else "/kb $selBufName $n $r")
                                        showChanOps = false
                                    }
                                }
                            ) { Text("Kick+Ban") }
                        }
                    }
                    if (canBan) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                showChanOps = false
                                chanListTab = 0
                                showChanListSheet = true
                            }
                        ) { Text("Channel lists…") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // IRCop tools
    if (showIrcOpTools) {
        ModalBottomSheet(onDismissRequest = { showIrcOpTools = false }) {
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
                    Text("IRCop tools", style = MaterialTheme.typography.titleLarge)
                }
                Text("$selNetName", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()

                var opTarget by remember { mutableStateOf("") }
                var opReason by remember { mutableStateOf("") }
                var opMask   by remember { mutableStateOf("") }
                var opServer by remember { mutableStateOf("") }
                var opMessage by remember { mutableStateOf("") }

                // Target / Reason fields
                Text("Target", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = opTarget, onValueChange = { opTarget = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Nick or host mask") }
                )
                OutlinedTextField(
                    value = opReason, onValueChange = { opReason = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Reason") }
                )

                // Kill / K-line / Z-line
                Text("Punishments", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val t = opTarget.trim()
                            if (t.isNotBlank()) {
                                val r = opReason.trim().ifBlank { "No reason" }
                                onSend("/kill $t $r")
                                showIrcOpTools = false
                            }
                        },
                        enabled = opTarget.isNotBlank()
                    ) { Text("Kill") }
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim()
                            if (t.isNotBlank()) {
                                val r = opReason.trim().ifBlank { "No reason" }
                                onSend("/kline $t $r")
                                showIrcOpTools = false
                            }
                        },
                        enabled = opTarget.isNotBlank()
                    ) { Text("K-line") }
                    OutlinedButton(
                        onClick = {
                            val t = opMask.trim().ifBlank { opTarget.trim() }
                            if (t.isNotBlank()) {
                                val r = opReason.trim().ifBlank { "No reason" }
                                onSend("/zline $t $r")
                                showIrcOpTools = false
                            }
                        },
                        enabled = opTarget.isNotBlank()
                    ) { Text("Z-line") }
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim()
                            if (t.isNotBlank()) {
                                val r = opReason.trim().ifBlank { "No reason" }
                                onSend("/gline $t $r")
                                showIrcOpTools = false
                            }
                        },
                        enabled = opTarget.isNotBlank()
                    ) { Text("G-line") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim()
                            if (t.isNotBlank()) {
                                val r = opReason.trim().ifBlank { "No reason" }
                                onSend("/shun $t $r")
                            }
                        },
                        enabled = opTarget.isNotBlank()
                    ) { Text("Shun") }
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim()
                            if (t.isNotBlank()) {
                                val r = opReason.trim().ifBlank { "No reason" }
                                onSend("/dline $t $r")
                            }
                        },
                        enabled = opTarget.isNotBlank()
                    ) { Text("D-line") }
                }

                HorizontalDivider()

                // Force join/part
                Text("Force join / part", fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = opServer, onValueChange = { opServer = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text("Channel") }
                    )
                    Button(
                        onClick = {
                            val t = opTarget.trim(); val ch = opServer.trim()
                            if (t.isNotBlank() && ch.isNotBlank()) onSend("/sajoin $t $ch")
                        },
                        enabled = opTarget.isNotBlank() && opServer.isNotBlank()
                    ) { Text("SAJoin") }
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim(); val ch = opServer.trim()
                            if (t.isNotBlank() && ch.isNotBlank()) onSend("/sapart $t $ch")
                        },
                        enabled = opTarget.isNotBlank() && opServer.isNotBlank()
                    ) { Text("SAPart") }
                }

                HorizontalDivider()

                // Broadcast messages
                Text("Broadcast", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = opMessage, onValueChange = { opMessage = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Message") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (opMessage.isNotBlank()) onSend("/wallops ${opMessage.trim()}") },
                        enabled = opMessage.isNotBlank()
                    ) { Text("WALLOPS") }
                    OutlinedButton(
                        onClick = { if (opMessage.isNotBlank()) onSend("/globops ${opMessage.trim()}") },
                        enabled = opMessage.isNotBlank()
                    ) { Text("GLOBOPS") }
                    OutlinedButton(
                        onClick = { if (opMessage.isNotBlank()) onSend("/locops ${opMessage.trim()}") },
                        enabled = opMessage.isNotBlank()
                    ) { Text("LOCOPS") }
                }

                HorizontalDivider()

                // Server queries
                Text("Server queries", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSend("/motd"); showIrcOpTools = false }) { Text("MOTD") }
                    OutlinedButton(onClick = { onSend("/admin"); showIrcOpTools = false }) { Text("ADMIN") }
                    OutlinedButton(onClick = { onSend("/stats u"); showIrcOpTools = false }) { Text("Uptime") }
                    OutlinedButton(onClick = { onSend("/stats l"); showIrcOpTools = false }) { Text("Links") }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // mIRC colour/style picker sheet
    if (showColorPicker) {
        ModalBottomSheet(onDismissRequest = { showColorPicker = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Text Formatting", style = MaterialTheme.typography.titleLarge)

                // Live preview
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
                        append("Preview: The quick brown fox jumps over the lazy dog")
                    }
                }
                Surface(
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = previewText,
                        modifier = Modifier.padding(16.dp),
                        style = chatTextStyle
                    )
                }

                // Foreground colours
                Text("Text Colour", fontWeight = FontWeight.SemiBold)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(count = 16) { code ->
                        val col = mircColor(code) ?: Color.Gray
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(col, MaterialTheme.shapes.small)
                                .border(
                                    width = 3.dp,
                                    color = if (selectedFgColor == code) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = MaterialTheme.shapes.small
                                )
                                .clickable {
                                    selectedFgColor = if (selectedFgColor == code) null else code
                                }
                        )
                    }
                }

                // Background colours
                Text("Background Colour", fontWeight = FontWeight.SemiBold)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(count = 16) { code ->
                        val col = mircColor(code) ?: Color.Gray
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(col, MaterialTheme.shapes.small)
                                .border(
                                    width = 3.dp,
                                    color = if (selectedBgColor == code) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = MaterialTheme.shapes.small
                                )
                                .clickable {
                                    selectedBgColor = if (selectedBgColor == code) null else code
                                }
                        )
                    }
                }

                // Style toggles
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilterChip(
                        selected = boldActive,
                        onClick = { boldActive = !boldActive },
                        label = { Text("Bold") }
                    )
                    FilterChip(
                        selected = italicActive,
                        onClick = { italicActive = !italicActive },
                        label = { Text("Italic") }
                    )
                    FilterChip(
                        selected = underlineActive,
                        onClick = { underlineActive = !underlineActive },
                        label = { Text("Underline") }
                    )
                    FilterChip(
                        selected = reverseActive,
                        onClick = { reverseActive = !reverseActive },
                        label = { Text("Reverse") }
                    )
                }

                // Action buttons: Clear All | Done
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
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
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear All")
                    }

                    Button(
                        onClick = { showColorPicker = false },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Done")
                    }
                }

                Text(
                    "Formatting will be applied when you send your message",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(8.dp))
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
                "Ban list (+b)",
                state.banlists[selected].orEmpty(),
                state.banlistLoading[selected] == true,
                "Unban",
                "b",
                "/banlist"
            )

            1 -> ListUi(
                "Quiet list (+q)",
                state.quietlists[selected].orEmpty(),
                state.quietlistLoading[selected] == true,
                "Unquiet",
                "q",
                "/quietlist"
            )

            2 -> ListUi(
                "Except list (+e)",
                state.exceptlists[selected].orEmpty(),
                state.exceptlistLoading[selected] == true,
                "Remove",
                "e",
                "/exceptlist"
            )

            else -> ListUi(
                "Invex list (+I)",
                state.invexlists[selected].orEmpty(),
                state.invexlistLoading[selected] == true,
                "Remove",
                "I",
                "/invexlist"
            )
        }

        ModalBottomSheet(onDismissRequest = { showChanListSheet = false }) {
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

                TabRow(selectedTabIndex = chanListTab) {
                    Tab(
                        selected = chanListTab == 0,
                        onClick = { chanListTab = 0 }
                    ) { Text("Bans") }

                    Tab(
                        selected = chanListTab == 1,
                        onClick = {
                            if (supportsQuiet) {
                                chanListTab = 1
                            } else {
                                Toast.makeText(
                                    context,  // ← use the captured context here
                                    "Quiet lists not supported on this server",
                                    Toast.LENGTH_SHORT
                                ).show()
                                chanListTab = 0
                            }
                        }
                    ) { Text("Quiets") }

                    Tab(
                        selected = chanListTab == 2,
                        onClick = {
                            if (supportsExcept) {
                                chanListTab = 2
                            } else {
                                Toast.makeText(
                                    context,
                                    "Exception lists not supported on this server",
                                    Toast.LENGTH_SHORT
                                ).show()
                                chanListTab = 0
                            }
                        }
                    ) { Text("Except") }

                    Tab(
                        selected = chanListTab == 3,
                        onClick = {
                            if (supportsInvex) {
                                chanListTab = 3
                            } else {
                                Toast.makeText(
                                    context,
                                    "Invex lists not supported on this server",
                                    Toast.LENGTH_SHORT
                                ).show()
                                chanListTab = 0
                            }
                        }
                    ) { Text("Invex") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val canRefresh = when (chanListTab) {
                        0 -> true
                        1 -> supportsQuiet
                        2 -> supportsExcept
                        else -> supportsInvex
                    }
                    OutlinedButton(enabled = canRefresh, onClick = { refreshCurrentList() }) {
                        Text(
                            "Refresh"
                        )
                    }
                    OutlinedButton(onClick = { showChanListSheet = false }) { Text("Close") }
                }

                HorizontalDivider()

                if (!ui.loading && ui.entries.isEmpty()) {
                    val unsupportedMsg = when (chanListTab) {
                        1 -> if (!supportsQuiet) "This server doesn't advertise a +q quiet list." else null
                        2 -> if (!supportsExcept) "This server doesn't advertise a +e exception list." else null
                        3 -> if (!supportsInvex) "This server doesn't advertise a +I invite-exemption list." else null
                        else -> null
                    }
                    Text(unsupportedMsg ?: "No entries.", style = chatTextStyle)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(ui.entries, key = { it.mask }) { e ->
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
                                            if (by != null) add("set by $by")
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
                                        }
                                    ) { Text(ui.removeLabel) }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showNickActions && selectedNick.isNotBlank()) {
        ModalBottomSheet(onDismissRequest = { showNickActions = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(selectedNick, style = MaterialTheme.typography.titleLarge)
                HorizontalDivider()
                Button(
                    onClick = {
                        onSelectBuffer("$selNetId::$selectedNick")
                        showNickActions = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open query") }
                Button(
                    onClick = { onWhois(selectedNick); showNickActions = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Whois") }
                Button(
                    onClick = { mention(selectedNick); showNickActions = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Mention") }
                val ignored =
                    state.networks.firstOrNull { it.id == selNetId }?.ignoredNicks.orEmpty()
                val isIgnored = ignored.any { it.equals(selectedNick, ignoreCase = true) }
                val canIgnore = !selectedNick.equals(myNick, ignoreCase = true)
                Button(
                    enabled = canIgnore,
                    onClick = {
                        if (isIgnored) onUnignoreNick(selNetId, selectedNick) else onIgnoreNick(
                            selNetId,
                            selectedNick
                        )
                        showNickActions = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (isIgnored) "Unignore" else "Ignore") }
                if (isChannel && (canKick || canBan) && !selectedNick.equals(
                        myNick,
                        ignoreCase = true
                    )
                ) {
                    HorizontalDivider()
                    Text("Moderation", fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            opsNick = selectedNick
                            opsReason = ""
                            showNickActions = false
                            showChanOps = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Kick / Ban…") }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private const val ANN_URL = "URL"
private const val ANN_CHAN = "CHAN"
private const val ANN_NICK = "NICK"

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

private fun mircColor(code: Int): Color? {
    // Standard 0-15 mIRC palette.
    return when (code) {
        0 -> Color(0xFFFFFFFF)
        1 -> Color(0xFF000000)
        2 -> Color(0xFF00007F)
        3 -> Color(0xFF009300)
        4 -> Color(0xFFFF0000)
        5 -> Color(0xFF7F0000)
        6 -> Color(0xFF9C009C)
        7 -> Color(0xFFFC7F00)
        8 -> Color(0xFFFFFF00)
        9 -> Color(0xFF00FC00)
        10 -> Color(0xFF009393)
        11 -> Color(0xFF00FFFF)
        12 -> Color(0xFF0000FC)
        13 -> Color(0xFFFF00FF)
        14 -> Color(0xFF7F7F7F)
        15 -> Color(0xFFD2D2D2)
        else -> null
    }
}

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
                    // Optional ,bg
                    if (i < input.length && input[i] == ',') {
                        i++
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
) {
    if (!mircColorsEnabled) {
        appendLinkified(this, stripIrcFormatting(text), linkStyle)
        return
    }

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

@Composable
private fun AnnotatedClickableText(
    text: AnnotatedString,
    onAnnotationClick: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    var layout: TextLayoutResult? by remember { mutableStateOf(null) }
    Text(
        text = text,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = {
            layout = it
            onTextLayout?.invoke(it)
        },
        modifier = modifier.pointerInput(text) {
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
                    val ann = text.getStringAnnotations(start = offset, end = offset).firstOrNull()
                    if (ann != null) onAnnotationClick(ann.tag, ann.item)
                }
            }
        }
    )
}

@Composable
private fun IrcLinkifiedText(
    text: String,
    mircColorsEnabled: Boolean,
    linkStyle: SpanStyle,
    onAnnotationClick: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val annotated = remember(text, linkStyle, mircColorsEnabled) {
        buildAnnotatedString { appendIrcStyledLinkified(text, linkStyle, mircColorsEnabled) }
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
 * Renders a MOTD line (IRC 372) using the largest font size that fits the text in a
 * single line within the available width. Falls back to [minFontSp] if still too wide.
 *
 * Strategy: binary search between [minFontSp] and the style's natural size, using
 * TextMeasurer to check whether the text fits in the available pixel width at each size.
 * This avoids recomposition loops — the size is computed once in the measure phase.
 */
@Composable
private fun AutoSizedMotdLine(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    mircColorsEnabled: Boolean,
    linkStyle: SpanStyle,
    onAnnotationClick: (String, String) -> Unit,
    minFontSp: Float = 6f,
) {
    val textMeasurer = rememberTextMeasurer()
    // Strip IRC formatting for the size measurement pass (formatting chars have no width).
    val plainText = remember(text) { stripIrcFormatting(text) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availableWidthPx = constraints.maxWidth.toFloat()
        val naturalSizeSp = style.fontSize.value.takeIf { !it.isNaN() && it > 0f } ?: 14f

        // Binary-search for the largest font size (in sp) where the plain text fits
        // in one line within availableWidthPx.
        val fittedSp = remember(plainText, availableWidthPx, naturalSizeSp) {
            if (availableWidthPx <= 0f) return@remember naturalSizeSp
            // Quick check: does it fit at the natural size?
            val naturalMeasure = textMeasurer.measure(
                text = plainText,
                style = style.copy(fontSize = naturalSizeSp.sp),
                constraints = Constraints(maxWidth = Int.MAX_VALUE),
                maxLines = 1,
                softWrap = false,
            )
            if (naturalMeasure.size.width <= availableWidthPx) {
                return@remember naturalSizeSp  // No shrinking needed.
            }
            // Binary search between minFontSp and naturalSizeSp.
            var lo = minFontSp
            var hi = naturalSizeSp
            repeat(8) {  // 8 iterations → ~0.4% precision, plenty for font sizes
                val mid = (lo + hi) / 2f
                val m = textMeasurer.measure(
                    text = plainText,
                    style = style.copy(fontSize = mid.sp),
                    constraints = Constraints(maxWidth = Int.MAX_VALUE),
                    maxLines = 1,
                    softWrap = false,
                )
                if (m.size.width <= availableWidthPx) lo = mid else hi = mid
            }
            lo
        }

        IrcLinkifiedText(
            text = text,
            mircColorsEnabled = mircColorsEnabled,
            linkStyle = linkStyle,
            onAnnotationClick = onAnnotationClick,
            style = style.copy(fontSize = fittedSp.sp),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
