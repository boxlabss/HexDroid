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

@file:OptIn(ExperimentalMaterial3Api::class)
package com.boxlabs.hexdroid.ui
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.UiState

@Composable
fun ListScreen(
    state: UiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onFilterChange: (String) -> Unit,
    onJoin: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val filter = state.listFilter.trim()
    val items = if (filter.isBlank()) state.channelDirectory else state.channelDirectory.filter {
        it.channel.contains(filter, ignoreCase = true) || it.topic.contains(filter, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Channels") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
                actions = {
                    IconButton(onClick = onRefresh) { Text("⟳") }
                    IconButton(onClick = onOpenSettings) { Text("⚙") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = state.listFilter, onValueChange = onFilterChange, label = { Text("Search channels / topic") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.listInProgress) { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp); Text("Loading… (${state.channelDirectory.size})", style = MaterialTheme.typography.bodySmall) }
                else Text("${items.size} channels", style = MaterialTheme.typography.bodySmall)
            }
            Divider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(items.take(3000)) { ch ->
                    Column(Modifier.fillMaxWidth().clickable { onJoin(ch.channel) }.padding(vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(ch.channel, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${ch.users}", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(8.dp))
                            Text("Join", style = MaterialTheme.typography.bodySmall)
                        }
                        if (ch.topic.isNotBlank()) Text(ch.topic, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    Divider()
                }
            }
        }
    }
}
