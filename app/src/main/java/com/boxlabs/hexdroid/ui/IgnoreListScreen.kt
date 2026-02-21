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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IgnoreListScreen(
    state: UiState,
    onBack: () -> Unit,
    onIgnoreNick: (String, String) -> Unit,
    onUnignoreNick: (String, String) -> Unit,
) {
    val nets = state.networks

    var selectedNetId by remember { mutableStateOf(state.activeNetworkId ?: nets.firstOrNull()?.id ?: "") }
    var netMenuExpanded by remember { mutableStateOf(false) }

    // Keep selection valid if networks change.
    LaunchedEffect(state.activeNetworkId, nets) {
        if (nets.isEmpty()) {
            selectedNetId = ""
        } else if (selectedNetId.isBlank() || nets.none { it.id == selectedNetId }) {
            selectedNetId = state.activeNetworkId?.takeIf { id -> nets.any { it.id == id } } ?: nets.first().id
        }
    }

    val selNet = nets.firstOrNull { it.id == selectedNetId }
    val ignored = selNet?.ignoredNicks.orEmpty().sortedBy { it.lowercase() }

    var addNick by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ignore_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (nets.isEmpty()) {
                Text(stringResource(R.string.ignore_no_networks))
                return@Column
            }

            // Network selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.ignore_network_label), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { netMenuExpanded = true }) {
                    Text(selNet?.name ?: selectedNetId)
                }
                DropdownMenu(expanded = netMenuExpanded, onDismissRequest = { netMenuExpanded = false }) {
                    for (n in nets) {
                        DropdownMenuItem(
                            text = { Text(n.name) },
                            onClick = {
                                selectedNetId = n.id
                                netMenuExpanded = false
                            }
                        )
                    }
                }
            }

            HorizontalDivider()

            // Add nick
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = addNick,
                    onValueChange = { addNick = it },
                    label = { Text(stringResource(R.string.ignore_nick_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = addNick.trim().isNotBlank() && selectedNetId.isNotBlank(),
                    onClick = {
                        val nick = addNick.trim()
                        if (nick.isNotBlank()) {
                            onIgnoreNick(selectedNetId, nick)
                            addNick = ""
                        }
                    }
                ) { Text(stringResource(R.string.action_add)) }
            }

            Text(
                stringResource(R.string.ignore_help_text),
                style = MaterialTheme.typography.bodySmall
            )

            if (ignored.isEmpty()) {
                Surface(
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(stringResource(R.string.ignore_empty))
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.ignore_tip), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ignored, key = { it.lowercase() }) { nick ->
                        Surface(
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(nick, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = { onUnignoreNick(selectedNetId, nick) }
                                ) { Text(stringResource(R.string.action_remove)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
