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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.automirrored.filled.SendToMobile
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.boxlabs.hexdroid.R

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
    query: String,           // text after the leading '/' - must be non-empty
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
                    items(matches, key = { it }) { nick ->
                        val isHighlighted = highlighted == nick
                        val baseNickText = base(nick)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isHighlighted)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable {
                                highlighted = nick
                                // "nick: " if cursor is at start of blank input, "@nick " otherwise
                                val completion = if (inputText.trimStart().startsWith("@$prefix", ignoreCase = true) &&
                                                     inputText.trimStart().length <= prefix.length + 1)
                                    "$baseNickText: "
                                else
                                    "@$baseNickText "
                                onPick(completion)
                            }
                        ) {
                            Text(
                                text = nick, // show with mode prefix (e.g. "@admin")
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
                            .clickable {
                                val baseNickText = base(nick)
                                val completion = if (inputText.trimStart().startsWith("@$prefix", ignoreCase = true) &&
                                                     inputText.trimStart().length <= prefix.length + 1)
                                    "$baseNickText: "
                                else
                                    "@$baseNickText "
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
                            text = "Tap to mention",
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
            contentDescription = stringResource(R.string.chat_drag_reorder),
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
/**
 * Horizontal divider shown above the first unread message in a buffer.
 * Driven by the server's draft/read-marker or soju.im/read timestamp.
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
            text = "  unread  ",
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
    /** Called when user taps "DCC Send File" in nick actions. Opens file picker then calls /dcc send. */
    onDccSendFile: ((targetNick: String) -> Unit)? = null,
    /** Called when user taps "DCC Chat" in nick actions. */
    onDccChat: ((targetNick: String) -> Unit)? = null,
    onOpenList: () -> Unit,
    onOpenSettings: () -> Unit,
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
    tourActive: Boolean = false,
    tourTarget: TourTarget? = null,
) {
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
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
    val typingNicks = if (state.settings.receiveTypingIndicator) buf?.typingNicks.orEmpty() else emptySet()

    // Separator position. Advances with each message on the active buffer; cleared by the scroll-to-bottom button.
    val firstUnreadIndex = remember(messages, buf?.lastReadTimestamp, buf?.unread) {
        val lastReadTs = buf?.lastReadTimestamp
        if (lastReadTs != null) {
            val lastReadMs = runCatching {
                java.time.Instant.parse(lastReadTs).toEpochMilli()
            }.getOrNull() ?: return@remember -1
            val idx = messages.indexOfFirst { it.timeMs > lastReadMs }
            if (idx < 0 || idx >= messages.size) -1 else idx
        } else {
            val unread = buf?.unread ?: 0
            if (unread <= 0 || messages.size < unread) -1
            else messages.size - unread
        }
    }

    var input by remember { mutableStateOf(TextFieldValue("")) }
    var inputHasFocus by remember { mutableStateOf(false) }

    // Per-buffer input history. -1 = fresh message; inputSnapshot holds the draft for restoring.
    val inputHistory = remember(selected) { mutableListOf<String>() }
    var historyIndex by remember(selected) { mutableStateOf(-1) }
    var inputSnapshot by remember(selected) { mutableStateOf("") }

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

        if (inputHistory.lastOrNull() != t) inputHistory.add(t)
        if (inputHistory.size > 50) inputHistory.removeAt(0)
        historyIndex = -1
        inputSnapshot = ""

        input = TextFieldValue("")
        onSend(formattedText)
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
				// Reorder according to live drag state - nets not in the drag list fall back to end
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
			// Drag state - index-based swap approach:
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
					// Derive root netId directly from item properties - no index lookup needed
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

    @OptIn(ExperimentalMaterial3Api::class)
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


    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var isTouchingMessages by remember(selected) { mutableStateOf(false) }

    val isAtBottom by remember(selected) {
        derivedStateOf { !listState.canScrollForward }
    }

    var followTail by remember(selected) { mutableStateOf(true) }

    // Track whether the IME is currently animating so we don't flip followTail mid-animation.
    var imeAnimating by remember(selected) { mutableStateOf(false) }
    LaunchedEffect(selected, imeBottomPx) {
        imeAnimating = true
        // Give the keyboard animation time to settle before re-evaluating isAtBottom.
        delay(350)
        imeAnimating = false
    }

    // Stop following when the user manually scrolls up; resume when they reach the bottom.
    // Never flip followTail while the keyboard is animating or the user is touching the list.
    LaunchedEffect(selected) {
        snapshotFlow { isAtBottom }
            .collect { atBottom ->
                if (!isTouchingMessages && !listState.isScrollInProgress && !imeAnimating) {
                    followTail = atBottom
                }
            }
    }

    // Disable tail-follow immediately when the user touches and scrolls up.
    LaunchedEffect(selected) {
        snapshotFlow { isTouchingMessages to isAtBottom }
            .collect { (touching, atBottom) ->
                if (touching && !atBottom) followTail = false
            }
    }

    val lastMsgId = messages.lastOrNull()?.id

    // Scroll to bottom when a new message arrives and we are following the tail.
    LaunchedEffect(selected, lastMsgId, messages.size) {
        if (lastMsgId == null) return@LaunchedEffect
        if (followTail && !isTouchingMessages) {
            runCatching { listState.scrollToItem(messages.lastIndex) }
        }
    }

    // Scroll to bottom on resume (e.g. app comes back to foreground).
    LaunchedEffect(resumeTick, selected) {
        if (messages.isNotEmpty() && followTail) {
            runCatching { listState.scrollToItem(messages.size - 1) }
        }
    }

    // When the keyboard opens, scroll to bottom once the animation settles.
    // Keyed only on inputHasFocus (stable bool) not imeBottomPx (changes every animation frame).
    LaunchedEffect(inputHasFocus, selected) {
        if (inputHasFocus && messages.isNotEmpty()) {
            if (followTail || isAtBottom) {
                followTail = true
                // Wait for keyboard animation to finish before scrolling.
                delay(350)
                runCatching { listState.scrollToItem(messages.lastIndex) }
            }
        }
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
                                    contentDescription = stringResource(R.string.chat_text_formatting),
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
                                contentDescription = stringResource(R.string.chat_user_list),
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
                                text = { Text(stringResource(R.string.menu_channel_list)) },
                                onClick = { overflowExpanded = false; onOpenList() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_file_transfers)) },
                                onClick = { overflowExpanded = false; onOpenTransfers() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_settings)) },
                                onClick = { overflowExpanded = false; onOpenSettings() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_networks)) },
                                onClick = { overflowExpanded = false; onOpenNetworks() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_system_info)) },
                                onClick = { overflowExpanded = false; onSysInfo() }
                            )
                            if (isIrcOper) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_ircop_tools)) },
                                    onClick = { overflowExpanded = false; showIrcOpTools = true }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_about)) },
                                onClick = { overflowExpanded = false; onAbout() }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_reconnect)) },
                                enabled = state.networks.isNotEmpty() && !state.connecting,
                                onClick = { overflowExpanded = false; onReconnect() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_disconnect)) },
                                onClick = { overflowExpanded = false; onDisconnect() }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_exit)) },
                                onClick = { overflowExpanded = false; onExit() }
                            )
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
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

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(selected) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                isTouchingMessages = true
                                waitForUpOrCancellation()
                                isTouchingMessages = false
                                // User scrolled up - disable tail-follow.
                                if (!isAtBottom) {
                                    followTail = false
                                }
                            }
                        },

                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(items = messages, key = { _, m -> m.id }) { msgIndex, m ->
                        // Unread separator: shown above the first unread message.
                        if (msgIndex == firstUnreadIndex) {
                            UnreadSeparator()
                        }
                        val ts =
                            if (state.settings.showTimestamps) "[${timeFmt.format(Date(m.timeMs))}] " else ""

                        val plainLine = buildString {
                            append(ts)
                            val from = m.from
                            when {
                                from == null -> append(m.text)
                                m.isAction  -> append("* $from ${m.text}")
                                else        -> append("<$from> ${m.text}")
                            }
                        }
                        val fromNick = m.from
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        clipboardManager.setText(AnnotatedString(plainLine))
                                    }
                                )
                        ) {
                        if (fromNick == null) {
                            if (m.isMotd && selBufName == "*server*") {
                                // MOTD lines: auto-shrink font so they always fit on one line,
                                // preserving ASCII art that depends on monospace column alignment.
                                // lineHeight = fontSize so there's no extra internal leading.
                                AutoSizedMotdLine(
                                    text = m.text,  // timestamps omitted - would skew auto-sizing and look odd
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
                        } // end Box
                        // No spacing between MOTD lines - preserves ASCII art layout.
                        if (!m.isMotd || selBufName != "*server*") {
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }

            // Scroll-to-bottom button: shown when user has scrolled up (not at tail).
            // Plain if-visibility avoids AnimatedVisibility scope-receiver ambiguity in BoxScope.
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
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false)
                        ) {
                            followTail = true
                            onMarkRead(selected)
                            scope.launch {
                                if (messages.isNotEmpty())
                                    listState.animateScrollToItem(messages.lastIndex)
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Scroll to bottom",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            } // end Box
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
        // (bare "/" alone doesn't trigger - it would show all 68 commands at once)
        val cmdQuery = remember(input.text) {
            val t = input.text
            if (t.length >= 2 && t.startsWith("/") && !t.contains(" ")) t.drop(1) else null
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

        Column(modifier = Modifier.fillMaxWidth()) {
            // Nick hints - shown when user types @prefix in a channel; takes priority over command hints
            if (nickQuery != null && cmdQuery == null) {
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
            // Command hints popup - rendered above the input row inside a Column
            if (cmdQuery != null) {
                CommandHints(
                    query = cmdQuery,
                    onPick = { completion ->
                        input = TextFieldValue(completion, TextRange(completion.length))
                    }
                )
            }

        // draft/typing: show who is currently typing, if any.
        if (typingNicks.isNotEmpty()) {
            val typingText = when (typingNicks.size) {
                1 -> "${typingNicks.first()} is typing…"
                2 -> {
                    val (a, b) = typingNicks.toList()
                    "$a and $b are typing…"
                }
                else -> "Several people are typing…"
            }
            Text(
                text = typingText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 2.dp)
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
					onValueChange = { new ->
                        input = new
                        onTypingChanged(new.text)
                    },
					modifier = Modifier
						.weight(1f)
						.heightIn(min = 40.dp)
						.tourTarget(TourTarget.CHAT_INPUT)
						.onFocusChanged { inputHasFocus = it.isFocused }
						.onKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (ev.key) {
                                Key.Enter, Key.NumPadEnter -> {

                                    sendNow()
                                    true
                                }
                                Key.DirectionUp -> {

                                    if (inputHistory.isEmpty()) return@onKeyEvent false
                                    if (historyIndex == -1) inputSnapshot = input.text
                                    val next = (if (historyIndex == -1) inputHistory.lastIndex
                                                else (historyIndex - 1).coerceAtLeast(0))
                                    historyIndex = next
                                    val recalled = inputHistory[next]
                                    input = TextFieldValue(recalled, androidx.compose.ui.text.TextRange(recalled.length))
                                    true
                                }
                                Key.DirectionDown -> {

                                    if (historyIndex == -1) return@onKeyEvent false
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
                            contentDescription = stringResource(R.string.chat_channel_tools),
                            tint = Color.White.copy(alpha = if (opsPressed) 0.7f else 1f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }


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
                            contentDescription = stringResource(R.string.chat_send_message),
                            tint = Color.White.copy(alpha = if (sendPressed) 0.7f else 1f),
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
                Text(stringResource(R.string.chat_channel_tools), style = MaterialTheme.typography.titleLarge)
                Text("$selNetName • $selBufName", style = MaterialTheme.typography.bodySmall)
                if (currentModeString != null) {
                    Text(
                        text = stringResource(R.string.chat_current_modes, currentModeString),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedButton(
                        onClick = { onSend("/mode $selBufName") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.chat_fetch_modes)) }
                }
                HorizontalDivider()

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
                        }) { Text(stringResource(R.string.set)) }
                        OutlinedButton(onClick = { opsTopic = topic ?: "" }) { Text(stringResource(R.string.reset)) }
                    }
                    HorizontalDivider()
                }

                // Channel mode toggles
                if (canMode) {
                    Text(stringResource(R.string.chat_modes_panel), fontWeight = FontWeight.Bold)

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
                        }, enabled = keyInput.isNotBlank()) { Text(stringResource(R.string.set)) }
                        OutlinedButton(onClick = { onSend("/mode $selBufName -k *") }) { Text(stringResource(R.string.ignore_remove)) }
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
                        }, enabled = limitInput.isNotBlank()) { Text(stringResource(R.string.set)) }
                        OutlinedButton(onClick = { onSend("/mode $selBufName -l"); limitInput = "" }) { Text(stringResource(R.string.ignore_remove)) }
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
                                }
                            ) { Text(stringResource(R.string.chat_ban)) }
                            OutlinedButton(
                                onClick = {
                                    val n = opsNick.trim()
                                    if (n.isNotBlank()) {
                                        val r = opsReason.trim()
                                        onSend(if (r.isBlank()) "/kb $selBufName $n" else "/kb $selBufName $n $r")
                                        showChanOps = false
                                    }
                                }
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
                            }
                        ) { Text(stringResource(R.string.chat_channel_lists)) }
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
                    Text(stringResource(R.string.chat_ircop_tools), style = MaterialTheme.typography.titleLarge)
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

                // Kill / K-line / Z-line
                Text(stringResource(R.string.chat_punishments), fontWeight = FontWeight.Bold)
                val noReasonStr = stringResource(R.string.chat_no_reason)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val t = opTarget.trim()
                            if (t.isNotBlank()) {
                                val r = opReason.trim().ifBlank { noReasonStr }
                                onSend("/kill $t $r")
                                showIrcOpTools = false
                            }
                        },
                        enabled = opTarget.isNotBlank()
                    ) { Text(stringResource(R.string.ircop_kill)) }
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim()
                            if (t.isNotBlank()) {
                                val r = opReason.trim().ifBlank { noReasonStr }
                                onSend("/kline $t $r")
                                showIrcOpTools = false
                            }
                        },
                        enabled = opTarget.isNotBlank()
                    ) { Text(stringResource(R.string.ircop_kline)) }
                    OutlinedButton(
                        onClick = {
                            val t = opMask.trim().ifBlank { opTarget.trim() }
                            if (t.isNotBlank()) {
                                val r = opReason.trim().ifBlank { noReasonStr }
                                onSend("/zline $t $r")
                                showIrcOpTools = false
                            }
                        },
                        enabled = opTarget.isNotBlank()
                    ) { Text(stringResource(R.string.ircop_zline)) }
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim()
                            if (t.isNotBlank()) {
                                val r = opReason.trim().ifBlank { noReasonStr }
                                onSend("/gline $t $r")
                                showIrcOpTools = false
                            }
                        },
                        enabled = opTarget.isNotBlank()
                    ) { Text(stringResource(R.string.ircop_gline)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim()
                            if (t.isNotBlank()) {
                                val r = opReason.trim().ifBlank { noReasonStr }
                                onSend("/shun $t $r")
                            }
                        },
                        enabled = opTarget.isNotBlank()
                    ) { Text(stringResource(R.string.ircop_shun)) }
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim()
                            if (t.isNotBlank()) {
                                val r = opReason.trim().ifBlank { noReasonStr }
                                onSend("/dline $t $r")
                            }
                        },
                        enabled = opTarget.isNotBlank()
                    ) { Text(stringResource(R.string.ircop_dline)) }
                }

                HorizontalDivider()

                // Force join/part
                Text(stringResource(R.string.chat_force_joinpart), fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = opServer, onValueChange = { opServer = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text(stringResource(R.string.chat_channel_label)) }
                    )
                    Button(
                        onClick = {
                            val t = opTarget.trim(); val ch = opServer.trim()
                            if (t.isNotBlank() && ch.isNotBlank()) onSend("/sajoin $t $ch")
                        },
                        enabled = opTarget.isNotBlank() && opServer.isNotBlank()
                    ) { Text(stringResource(R.string.ircop_sajoin)) }
                    OutlinedButton(
                        onClick = {
                            val t = opTarget.trim(); val ch = opServer.trim()
                            if (t.isNotBlank() && ch.isNotBlank()) onSend("/sapart $t $ch")
                        },
                        enabled = opTarget.isNotBlank() && opServer.isNotBlank()
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (opMessage.isNotBlank()) onSend("/wallops ${opMessage.trim()}") },
                        enabled = opMessage.isNotBlank()
                    ) { Text(stringResource(R.string.ircop_wallops)) }
                    OutlinedButton(
                        onClick = { if (opMessage.isNotBlank()) onSend("/globops ${opMessage.trim()}") },
                        enabled = opMessage.isNotBlank()
                    ) { Text(stringResource(R.string.ircop_globops)) }
                    OutlinedButton(
                        onClick = { if (opMessage.isNotBlank()) onSend("/locops ${opMessage.trim()}") },
                        enabled = opMessage.isNotBlank()
                    ) { Text(stringResource(R.string.ircop_locops)) }
                }

                HorizontalDivider()

                // Server queries
                Text(stringResource(R.string.chat_server_queries), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSend("/motd"); showIrcOpTools = false }) { Text(stringResource(R.string.ircop_motd)) }
                    OutlinedButton(onClick = { onSend("/admin"); showIrcOpTools = false }) { Text(stringResource(R.string.ircop_admin)) }
                    OutlinedButton(onClick = { onSend("/stats u"); showIrcOpTools = false }) { Text(stringResource(R.string.ircop_uptime)) }
                    OutlinedButton(onClick = { onSend("/stats l"); showIrcOpTools = false }) { Text(stringResource(R.string.ircop_links)) }
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

        ModalBottomSheet(
            onDismissRequest = { showColorPicker = false },
            sheetMaxWidth = 600.dp,
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
                        append("The quick brown fox jumps over the lazy dog")
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
                        label = { Text("B", fontWeight = FontWeight.Bold) }
                    )
                    FilterChip(
                        selected = italicActive,
                        onClick = { italicActive = !italicActive },
                        label = { Text("I", fontStyle = FontStyle.Italic) }
                    )
                    FilterChip(
                        selected = underlineActive,
                        onClick = { underlineActive = !underlineActive },
                        label = { Text("U", textDecoration = TextDecoration.Underline) }
                    )
                    FilterChip(
                        selected = reverseActive,
                        onClick = { reverseActive = !reverseActive },
                        label = { Text("Rev") }
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
                                .clickable { selectedFgColor = null }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("A", style = MaterialTheme.typography.labelSmall,
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
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.chat_clear_all)) }

                    Button(
                        onClick = { showColorPicker = false },
                        modifier = Modifier.weight(1f)
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

                val noQuietSupportStr = stringResource(R.string.chat_no_quiet_support)
                val noExceptSupportStr = stringResource(R.string.chat_no_except_support)
                val noInvexSupportStr = stringResource(R.string.chat_no_invex_support)


                TabRow(selectedTabIndex = chanListTab) {
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
                    OutlinedButton(enabled = canRefresh, onClick = { refreshCurrentList() }) {
                        Text(stringResource(R.string.chat_refresh))
                    }
                    OutlinedButton(onClick = { showChanListSheet = false }) { Text(stringResource(R.string.close)) }
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
        val dccEnabled = state.settings.dccEnabled
        ModalBottomSheet(onDismissRequest = { showNickActions = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
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
                    Text(selectedNick, style = MaterialTheme.typography.titleLarge)
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
                    subtitle = if (dccEnabled) stringResource(R.string.nick_send_file_desc) else stringResource(R.string.nick_dcc_disabled),
                    enabled = dccEnabled && onDccSendFile != null,
                    tint = if (dccEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        showNickActions = false
                        onDccSendFile?.invoke(selectedNick)
                    }
                )
                ActionRow(
                    icon = Icons.Default.Terminal,
                    label = stringResource(R.string.nick_dcc_chat),
                    subtitle = if (dccEnabled) stringResource(R.string.nick_dcc_chat_desc) else stringResource(R.string.nick_dcc_disabled),
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
            repeat(8) {  // 8 iterations -> ~0.4% precision, plenty for font sizes
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
