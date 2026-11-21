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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.MentionEntry
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.UiState
import com.boxlabs.hexdroid.stripIrcFormatting
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Every highlight kept this session across all networks, newest first. Tapping one opens its
 * conversation at that message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MentionsScreen(
    state: UiState,
    onBack: () -> Unit,
    onOpen: (MentionEntry) -> Unit,
    onDismiss: (MentionEntry) -> Unit,
    onClearAll: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val entries = remember(state.mentions) { state.mentions.asReversed() }
    val netNames = remember(state.networks) { state.networks.associate { it.id to it.name } }
    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val dateFmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val startOfToday = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.mentions_clear_title)) },
            text = { Text(stringResource(R.string.mentions_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmClear = false; onClearAll() },
                    modifier = Modifier.tvInitialFocus().focusHighlight(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mentions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.tvInitialFocus().focusHighlight()) { Text("←") }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        TextButton(
                            onClick = { confirmClear = true },
                            modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                        ) { Text(stringResource(R.string.mentions_clear)) }
                    }
                },
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.Default.AlternateEmail,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        stringResource(R.string.mentions_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(entries, key = { "${it.bufferKey}|${it.timeMs}|${it.from}|${it.msgId}|${it.text.hashCode()}" }) { m ->
                val buffer = m.bufferKey.substringAfter("::")
                val net = netNames[m.netId] ?: m.netId
                val time = if (m.timeMs >= startOfToday) timeFmt.format(Date(m.timeMs))
                    else dateFmt.format(Date(m.timeMs)) + " " + timeFmt.format(Date(m.timeMs))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .focusHighlight()
                        .clickable { onOpen(m) }
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$buffer · $net",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                time,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            m.from,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stripIrcFormatting(m.text),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = { onDismiss(m) },
                        modifier = Modifier.size(40.dp).focusHighlight(CircleShape),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.mentions_dismiss),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp)
            }
        }
    }
}
