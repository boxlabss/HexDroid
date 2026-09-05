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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxlabs.hexdroid.IrcViewModel
import com.boxlabs.hexdroid.data.NetworkProfile
import com.boxlabs.hexdroid.UiMessage
import com.boxlabs.hexdroid.stripIrcFormatting

/**
 * The conversation in a floating window: readable, tappable and typable.
 *
 * Everything that will not fit at 300dp goes, but unlike the picture-in-picture view the
 * contents receive touches, so the tabs switch buffers and the composer sends.
 */
@Composable
fun FloatingChat(
    vm: IrcViewModel,
    onDrag: (Float, Float) -> Unit,
    onResize: (Float, Float) -> Unit,
    onKeyboard: (Boolean) -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val selected = state.selectedBuffer
    val messages = state.buffers[selected]?.messages.orEmpty()
    val listState = rememberLazyListState()

    // The list is reversed, so item 0 is the newest. A buffer switch lands at the newest
    // message; an arriving message only follows when the newest is already in view, so
    // reading back is not interrupted.
    LaunchedEffect(selected) {
        if (messages.isNotEmpty()) runCatching { listState.scrollToItem(0) }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
            runCatching { listState.scrollToItem(0) }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            FloatingHeader(
                title = bufferLabel(selected, state.networks),
                onDrag = onDrag,
                onOpenApp = onOpenApp,
                onClose = onClose,
            )
            FloatingTabs(
                buffers = orderedBuffers(state.buffers.keys, state.networks),
                selected = selected,
                labelOf = { bufferLabel(it, state.networks) },
                unreadOf = { state.buffers[it]?.unread ?: 0 },
                onSelect = { vm.openBufferInBackground(it) },
            )
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(messages.asReversed(), key = { it.id }) { m -> FloatingLine(m) }
            }
            FloatingComposer(
                // Same path as the main composer, so slash commands and aliases work here too.
                onSend = { vm.sendInput(it, selected) },
                onKeyboard = onKeyboard,
                onResize = onResize,
            )
        }
    }
}

/** Title bar: drag anywhere on it, plus restore and close. */
@Composable
private fun FloatingHeader(
    title: String,
    onDrag: (Float, Float) -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            }
            .padding(horizontal = 6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.OpenInFull,
            contentDescription = null,
            modifier = Modifier.size(16.dp).clickable(onClick = onOpenApp),
        )
        Box(Modifier.size(8.dp))
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = null,
            modifier = Modifier.size(16.dp).clickable(onClick = onClose),
        )
    }
}

/** Tabs, tappable here because the window takes touches. */
@Composable
private fun FloatingTabs(
    buffers: List<String>,
    selected: String,
    labelOf: (String) -> String,
    unreadOf: (String) -> Int,
    onSelect: (String) -> Unit,
) {
    if (buffers.size < 2) return
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(buffers, key = { it }) { key ->
            val name = labelOf(key)
            val isSelected = key == selected
            val unread = unreadOf(key)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                    .clickable { onSelect(key) }
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = if (unread > 0) "$name ($unread)" else name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected || unread > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** One message, nick and text on a line. */
@Composable
private fun FloatingLine(m: UiMessage) {
    val body = remember(m.text) { stripIrcFormatting(m.text) }
    Text(
        text = if (m.from != null) "${m.from}: $body" else body,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        color = if (m.from == null) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * The composer.
 *
 * Focus is reported upward so the window can become focusable while typing: an overlay
 * cannot raise the keyboard otherwise, and cannot let touches reach what is behind it while
 * it stays focusable.
 */
@Composable
private fun FloatingComposer(
    onSend: (String) -> Unit,
    onKeyboard: (Boolean) -> Unit,
    onResize: (Float, Float) -> Unit,
) {
  Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.replace("\n", "") },
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .onFocusChanged { onKeyboard(it.isFocused) },
        singleLine = true,
        textStyle = TextStyle(fontSize = 13.sp),
        placeholder = { Text("…", style = MaterialTheme.typography.labelSmall) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = {
            val t = text.trim()
            if (t.isNotEmpty()) { onSend(t); text = "" }
        }),
    )
    // Resize grip, in the corner where one is expected.
    Box(
        modifier = Modifier
            .size(20.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onResize(drag.x, drag.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "\u21F2",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
  }
}

/**
 * Buffers in the order the drawer shows them: grouped by network, each network's status
 * window first, then its conversations by name.
 *
 * Sorting the keys as plain text put the status window last, because "*" sorts after "#".
 */
internal fun orderedBuffers(keys: Set<String>, networks: List<NetworkProfile>): List<String> {
    val netOrder = networks.withIndex().associate { (i, n) -> n.id to i }
    return keys.sortedWith(
        compareBy<String> { netOrder[it.substringBefore("::", "")] ?: Int.MAX_VALUE }
            .thenBy { if (it.substringAfter("::", "") == "*server*") 0 else 1 }
            .thenBy { it.substringAfter("::", it).lowercase() }
    )
}

/**
 * What to call a buffer in a space this narrow.
 *
 * The status window is stored as "*server*", which means nothing to anyone reading it, so it
 * shows the network's name instead.
 */
internal fun bufferLabel(key: String, networks: List<NetworkProfile>): String {
    val netId = key.substringBefore("::", "")
    val name = key.substringAfter("::", key)
    return when (name) {
        "*server*" -> networks.firstOrNull { it.id == netId }?.name?.ifBlank { null } ?: netId
        "*raw*" -> "raw"
        else -> name
    }
}
