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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.boxlabs.hexdroid.ChatFontStyle
import com.boxlabs.hexdroid.UiSettings
import com.boxlabs.hexdroid.UiState
import com.boxlabs.hexdroid.stripIrcFormatting
import com.boxlabs.hexdroid.ui.components.HexGradientButton
import com.boxlabs.hexdroid.ui.components.LagBar
import com.boxlabs.hexdroid.ui.theme.fontFamilyForChoice
import com.boxlabs.hexdroid.ui.tour.TourTarget
import com.boxlabs.hexdroid.ui.tour.tourTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed class SidebarItem(val stableKey: String) {
    data class Header(val netId: String, val title: String) : SidebarItem("h:$netId")
    data class Buffer(val key: String, val label: String, val indent: androidx.compose.ui.unit.Dp) : SidebarItem("b:$key")
    data class DividerItem(val netId: String) : SidebarItem("d:$netId")
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
    onOpenList: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNetworks: () -> Unit,
    onOpenTransfers: () -> Unit,
    onSysInfo: () -> Unit,
    onAbout: () -> Unit,
    onUpdateSettings: (UiSettings.() -> UiSettings) -> Unit,
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
    val tourWantsBuffers = tourActive && (tourTarget == TourTarget.CHAT_BUFFER_DRAWER || tourTarget == TourTarget.CHAT_DRAWER_BUTTON)

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
        return if (idx <= 0) ("unknown" to key) else (key.substring(0, idx) to key.substring(idx + 2))
    }

    fun baseNick(display: String): String = display.trimStart('~', '&', '@', '%', '+')

    fun nickPrefix(display: String): Char? = display.firstOrNull()?.takeIf { it in listOf('~','&','@','%','+') }

    fun netName(netId: String): String =
        state.networks.firstOrNull { it.id == netId }?.name ?: netId

    // Pre-group buffer keys by network to avoid expensive per-network filtering on every recomposition.
    data class NetBuffers(val serverKey: String, val others: List<String>)

    val buffersByNet = remember(state.buffers, state.channelsOnly) {
        val groups = mutableMapOf<String, MutableList<String>>()
        for (k in state.buffers.keys) {
            val idx = k.indexOf("::")
            if (idx <= 0) continue
            val netId = k.substring(0, idx)
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
        indent: androidx.compose.ui.unit.Dp,
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
                    Text(lagLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        try { SimpleDateFormat(state.settings.timestampFormat, Locale.getDefault()) }
        catch (_: Throwable) { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
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
    val canKick = isChannel && myPrefix in listOf('~','&','@','%')
    val canBan = isChannel && myPrefix in listOf('~','&','@')
    val canTopic = isChannel && myPrefix in listOf('~','&','@','%')

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
    var showChanListSheet by remember { mutableStateOf(false) }
    var chanListTab by remember { mutableStateOf(0) } // 0=bans,1=quiets,2=excepts,3=invex
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
        input = if (input.text.isBlank()) TextFieldValue("$nick: ") else TextFieldValue(input.text + " $nick")
    }

    @Composable
    fun BufferDrawer(mod: Modifier = Modifier) {
        val sidebarItems = remember(state.networks, buffersByNet, state.channelsOnly, selected) {
            val out = mutableListOf<SidebarItem>()
            for (net in state.networks) {
                val nId = net.id
                val header = net.name
                val grouped = buffersByNet[nId]
                val serverKey = grouped?.serverKey ?: "$nId::*server*"
                val otherKeys = grouped?.others ?: emptyList()

                if (state.channelsOnly) {
                    out.add(SidebarItem.Header(nId, header))
                } else {
                    // Use the server buffer row as the network "header" to avoid showing the network name twice.
                    out.add(SidebarItem.Buffer(serverKey, header, 0.dp))
                }
                for (k in otherKeys) {
                    val (_, name) = splitKey(k)
                    out.add(SidebarItem.Buffer(k, name, 14.dp))
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

Column(mod.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            LazyColumn(
                modifier = Modifier.fillMaxSize().tourTarget(TourTarget.CHAT_BUFFER_DRAWER),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                items(sidebarItems, key = { it.stableKey }) { item ->
                    when (item) {
                        is SidebarItem.Header -> {
                            val (lagLabel, lagProgress) = lagInfoByNet[item.netId] ?: ("—" to 0f)
                            Column(Modifier.padding(start = 6.dp, top = 12.dp, bottom = 8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text(lagLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                LagBar(progress = lagProgress, modifier = Modifier.fillMaxWidth(), height = 5.dp)
                            }
                        }
                        is SidebarItem.Buffer -> {
                            val (netId, name) = splitKey(item.key)
                            val closable = name != "*server*"
                            val lag = if (name == "*server*") lagInfoByNet[netId] else null
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
                        is SidebarItem.DividerItem -> {
                            Divider(Modifier.padding(top = 12.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun NicklistContent(mod: Modifier = Modifier) {
        Column(mod.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Nicklist", fontWeight = FontWeight.Bold)
            Text("$selBufName • ${nicklist.size} users", style = MaterialTheme.typography.bodySmall)
            Divider()
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
    var resumeTick by remember { mutableStateOf(0) }
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
	LaunchedEffect(selected, isAtBottom, listState.isScrollInProgress, isTouchingMessages, suppressFollowUpdate, inputHasFocus, imeBottomPx) {
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
            else -> 56.dp
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
                        IconButton(onClick = onToggleBufferList, modifier = Modifier.tourTarget(TourTarget.CHAT_DRAWER_BUTTON)) { Text("☰") }
                    } else {
                        IconButton(onClick = { scope.launch { drawerState.open() } }, modifier = Modifier.tourTarget(TourTarget.CHAT_DRAWER_BUTTON)) { Text("☰") }
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
                        val hasActiveFormatting = selectedFgColor != null || selectedBgColor != null || 
                            boldActive || italicActive || underlineActive || reverseActive
                        
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .scale(if (colorPressed) 0.92f else 1f)
                                .background(
                                    brush = if (hasActiveFormatting) {
                                        // Show the active foreground color or a gradient if formatting is active
                                        val fgCol = selectedFgColor?.let { mircColor(it) } ?: Color(0xFFFF6B6B)
                                        Brush.linearGradient(listOf(fgCol, fgCol.copy(alpha = 0.7f)))
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
                                        Modifier.border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
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

                    // Nicklist button with gradient styling (matches color picker button)
                    run {
                        val nicklistInteraction = remember { MutableInteractionSource() }
                        val nicklistPressed by nicklistInteraction.collectIsPressedAsState()
                        val nicklistEnabled = isChannel
                        
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .scale(if (nicklistPressed) 0.92f else 1f)
                                .alpha(if (nicklistEnabled) 1f else 0.4f)
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
                                    if (nicklistEnabled) {
                                        Modifier.clickable(
                                            interactionSource = nicklistInteraction,
                                            indication = ripple(bounded = false),
                                            onClick = {
                                                if (isWide) {
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
                        IconButton(onClick = { overflowExpanded = true }, modifier = Modifier.tourTarget(TourTarget.CHAT_OVERFLOW_BUTTON)) { Text("⋮") }
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
                            DropdownMenuItem(
                                text = { Text("About") },
                                onClick = { overflowExpanded = false; onAbout() }
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Reconnect") },
                                enabled = state.networks.isNotEmpty() && !state.connecting,
                                onClick = { overflowExpanded = false; onReconnect() }
                            )
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                onClick = { overflowExpanded = false; onDisconnect() }
                            )
                            Divider()
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
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Topic:", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
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
                Divider()
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
                        val ts = if (state.settings.showTimestamps) "[${timeFmt.format(Date(m.timeMs))}] " else ""
                        val fromNick = m.from
                        if (fromNick == null) {
                            IrcLinkifiedText(
                                text = ts + m.text,
                                mircColorsEnabled = state.settings.mircColorsEnabled,
                                linkStyle = linkStyle,
                                onAnnotationClick = onAnnotationClick,
                                style = chatTextStyle
                            )
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
                                appendIrcStyledLinkified(m.text, linkStyle, state.settings.mircColorsEnabled)
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
                                appendIrcStyledLinkified(m.text, linkStyle, state.settings.mircColorsEnabled)
                            }
                            AnnotatedClickableText(
                                text = annotated,
                                onAnnotationClick = onAnnotationClick,
                                style = chatTextStyle
                            )
                        }
                        Spacer(Modifier.height(4.dp))
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
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .padding(bottom = bottomInset),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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

				OutlinedTextField(
					value = input,
					onValueChange = { input = it },
					modifier = Modifier.weight(1f).tourTarget(TourTarget.CHAT_INPUT).onFocusChanged { inputHasFocus = it.isFocused },
					singleLine = false, // Set to false to enable multiline input, allowing the return key to insert newlines ('\n') instead of treating the field as a single line that ignores or replaces newlines with spaces.
					maxLines = 2, // Limits the maximum number of visible lines to 5 (adjust as needed for your UI; this prevents unlimited vertical growth while allowing multiple lines). The field will scroll vertically if content exceeds this.
					minLines = 1, // Minimum number of lines (default is 1, but explicitly set for clarity; ensures the field starts at least 1 line high).
					placeholder = { Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant) },
					textStyle = inputTextStyle,
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default), // Keeps Default, which for multiline fields instructs the keyboard to show a standard return/enter key that inserts a newline ('\n') automatically without triggering any special actions like closing the keyboard or sending.
					keyboardActions = KeyboardActions(onSend = { sendNow() }), // This remains as-is, but note that with imeAction=Default and multiline enabled, pressing the return key will not trigger the onSend callback (which is intended for ImeAction.Send). Instead, it inserts a newline. If you have a separate send button in your UI (common in chat inputs), use that for sending; otherwise, if you want return to send the message, change imeAction to ImeAction.Send and keep singleLine=true, but that would revert to no newlines.
				)

                // Channel ops button - only shown when user has permissions
                if (isChannel && (canKick || canBan || canTopic)) {
                    val opsInteraction = remember { MutableInteractionSource() }
                    val opsPressed by opsInteraction.collectIsPressedAsState()

                    Box(
                        modifier = Modifier
                            .size(36.dp)
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
                            .size(36.dp)
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
                            imageVector = Icons.Filled.Send,
                            contentDescription = "Send message",
                            tint = Color.White.copy(alpha = if (sendPressed) 0.7f else 1f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    val scaffoldContent: @Composable (PaddingValues) -> Unit = { padding ->
        if (!isWide) {
            MessagesPane(Modifier.fillMaxSize().padding(padding))
        } else {

            val density = LocalDensity.current
            val screenWdp = cfg.screenWidthDp.toFloat().coerceAtLeast(1f)
            val screenW = cfg.screenWidthDp.dp
            val screenWpx = with(density) { screenW.toPx().coerceAtLeast(1f) }

            // Persisted fractions (updated on drag end).
            var bufferFrac by remember(state.settings.bufferPaneFracLandscape) {
                mutableStateOf(state.settings.bufferPaneFracLandscape)
            }
            var nickFrac by remember(state.settings.nickPaneFracLandscape) {
                mutableStateOf(state.settings.nickPaneFracLandscape)
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
            var showResizeHint by remember(isWide) { mutableStateOf(false) }
            LaunchedEffect(isWide) {
                if (!isWide) return@LaunchedEffect
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
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (state.showBufferList || tourWantsBuffers) {
                    Surface(tonalElevation = 1.dp) {
                        BufferDrawer(Modifier.width(bufferPaneW).fillMaxHeight())
                    }
                    SplitHandle(
                        onDragDeltaPx = { dxPx ->
                            val dxFrac = dxPx / screenWpx
                            bufferFrac = (bufferFrac + dxFrac).coerceIn(minBufferFrac, maxBufferFrac)
                        },
                        onDragEnd = {
                            val clamped = bufferFrac.coerceIn(minBufferFrac, maxBufferFrac)
                            bufferFrac = clamped
                            onUpdateSettings { copy(bufferPaneFracLandscape = clamped) }
                        }
                    )
                }

                MessagesPane(Modifier.weight(1f).fillMaxHeight())

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
                        NicklistContent(Modifier.width(nickPaneW).fillMaxHeight())
                    }
                }
            }
        }
    }

    val scaffold: @Composable () -> Unit = {
        Scaffold(
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
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

        if (showNickSheet && isChannel) {
            ModalBottomSheet(
                onDismissRequest = {
                    showNickSheet = false
                }
            ) {
                NicklistContent(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 520.dp))
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Channel tools", style = MaterialTheme.typography.titleLarge)
                Text("$selNetName • $selBufName", style = MaterialTheme.typography.bodySmall)
                Divider()

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
                    Divider()
                }

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
                                // Close the tools sheet and open the ban list popup.
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
								.clickable { selectedFgColor = if (selectedFgColor == code) null else code }
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
								.clickable { selectedBgColor = if (selectedBgColor == code) null else code }
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
        val banTimeFmt = remember { SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.getDefault()) }

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
            0 -> ListUi("Ban list (+b)", state.banlists[selected].orEmpty(), state.banlistLoading[selected] == true, "Unban", "b", "/banlist")
            1 -> ListUi("Quiet list (+q)", state.quietlists[selected].orEmpty(), state.quietlistLoading[selected] == true, "Unquiet", "q", "/quietlist")
            2 -> ListUi("Except list (+e)", state.exceptlists[selected].orEmpty(), state.exceptlistLoading[selected] == true, "Remove", "e", "/exceptlist")
            else -> ListUi("Invex list (+I)", state.invexlists[selected].orEmpty(), state.invexlistLoading[selected] == true, "Remove", "I", "/invexlist")
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
                    Text(ui.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (ui.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
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
                    OutlinedButton(enabled = canRefresh, onClick = { refreshCurrentList() }) { Text("Refresh") }
                    OutlinedButton(onClick = { showChanListSheet = false }) { Text("Close") }
                }

                Divider()

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
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(ui.entries, key = { it.mask }) { e ->
                            Surface(
                                tonalElevation = 1.dp,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
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
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(selectedNick, style = MaterialTheme.typography.titleLarge)
                Divider()
                Button(
                    onClick = {
                        onSelectBuffer("$selNetId::$selectedNick")
                        showNickActions = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open query") }
                Button(onClick = { onWhois(selectedNick); showNickActions = false }, modifier = Modifier.fillMaxWidth()) { Text("Whois") }
                Button(onClick = { mention(selectedNick); showNickActions = false }, modifier = Modifier.fillMaxWidth()) { Text("Mention") }
                val ignored = state.networks.firstOrNull { it.id == selNetId }?.ignoredNicks.orEmpty()
                val isIgnored = ignored.any { it.equals(selectedNick, ignoreCase = true) }
                val canIgnore = !selectedNick.equals(myNick, ignoreCase = true)
                Button(
                    enabled = canIgnore,
                    onClick = {
                        if (isIgnored) onUnignoreNick(selNetId, selectedNick) else onIgnoreNick(selNetId, selectedNick)
                        showNickActions = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (isIgnored) "Unignore" else "Ignore") }
                if (isChannel && (canKick || canBan) && !selectedNick.equals(myNick, ignoreCase = true)) {
                    Divider()
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

private val urlRegex = Regex("https?://[^\\s]+")
private val chanRegex = Regex("#[^\\s]+")
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
        val (token, trailing) = splitTrailingPunctuation(raw)
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
        val (token, trailing) = splitTrailingPunctuation(raw)
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

// ─────────────────────────────────────────────────────────────────────────────
// mIRC colour/style rendering (optional)
// ─────────────────────────────────────────────────────────────────────────────

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