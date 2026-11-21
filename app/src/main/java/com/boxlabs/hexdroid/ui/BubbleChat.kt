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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.IrcViewModel
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.UiMessage
import com.boxlabs.hexdroid.UiState
import com.boxlabs.hexdroid.stripIrcFormatting

/** A single conversation, compact enough for a notification bubble. */
@Composable
fun BubbleChat(
    vm: IrcViewModel,
    state: UiState,
    bufferKey: String,
    onOpenApp: () -> Unit,
) {
    val messages = state.buffers[bufferKey]?.messages.orEmpty()
    val listState = rememberLazyListState()
    // The list is reversed, so item 0 is the newest. New messages are followed only while the
    // newest is in view.
    // Keyed on the newest message: a buffer at its scrollback cap stays the same size.
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
            runCatching { listState.scrollToItem(0) }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    bufferLabel(bufferKey, state.networks),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                IconButton(onClick = onOpenApp) {
                    Icon(Icons.Filled.OpenInFull, contentDescription = stringResource(R.string.bubble_open_app))
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messages.asReversed(), key = { it.id }) { m -> BubbleLine(m) }
            }
            var text by rememberSaveable { mutableStateOf("") }
            fun send() {
                val t = text.trim()
                if (t.isNotEmpty()) {
                    vm.sendUserInput(t, bufferKey, pasteGuard = false)
                    text = ""
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.replace("\n", "") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.bubble_message_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                IconButton(onClick = { send() }, enabled = text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.bubble_send))
                }
            }
        }
    }
}

@Composable
private fun BubbleLine(m: UiMessage) {
    val body = remember(m.text) { stripIrcFormatting(m.text) }
    val from = m.from
    if (from == null) {
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        val line = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) {
                append(if (m.isAction) "* $from " else "$from ")
            }
            append(body)
        }
        Text(line, style = MaterialTheme.typography.bodyMedium)
    }
}
