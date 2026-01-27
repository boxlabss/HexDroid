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

import com.boxlabs.hexdroid.ui.tour.TourTarget
import com.boxlabs.hexdroid.ui.tour.tourTarget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.UiState

@Composable
fun NetworksScreen(
    state: UiState,
	onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSetAutoConnect: (String, Boolean) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onAllowPlaintextConnect: (String) -> Unit,
    onDismissPlaintextWarning: () -> Unit,
    onOpenSettings: () -> Unit,
    tourActive: Boolean = false,
    tourTarget: TourTarget? = null,
) {
    val active = state.activeNetworkId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Networks") },
				navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
                actions = { IconButton(onClick = onOpenSettings, modifier = Modifier.tourTarget(TourTarget.NETWORKS_SETTINGS)) { Text("⚙") } }
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onAdd, modifier = Modifier.tourTarget(TourTarget.NETWORKS_ADD_FAB)) { Text("+") } }
    ) { padding ->
        val listState = rememberLazyListState()

                // Tour: if we are highlighting the AfterNET entry (or its Connect button), scroll it into view.
        LaunchedEffect(tourActive, tourTarget, state.networks) {
            if (!tourActive) return@LaunchedEffect
            if (tourTarget == TourTarget.NETWORKS_AFTERNET_ITEM || tourTarget == TourTarget.NETWORKS_CONNECT_BUTTON) {
                val idx = state.networks.indexOfFirst {
                    it.id.equals("AfterNET", ignoreCase = true) || it.name.equals("AfterNET", ignoreCase = true)
                }
                if (idx >= 0) {
                    try {
                        // Keep a little top breathing room so the highlighted control isn't under the app bar.
                        listState.animateScrollToItem(idx)
                    } catch (_: Throwable) { }
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            // Proper spacing between cards is controlled by LazyColumn's verticalArrangement,
            // and contentPadding ensures the last item isn't obscured by the FAB.
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 2.dp, bottom = 96.dp)
            ) {
                items(state.networks, key = { it.id }) { n ->
                    val conn = state.connections[n.id]
                    val isConn = conn?.connected == true
                    val isConnecting = conn?.connecting == true
                    val status = conn?.status ?: "Disconnected"

                    val isAfterNet = n.id.equals("AfterNET", ignoreCase = true) || n.name.equals("AfterNET", ignoreCase = true)
                    val cardMod = if (isAfterNet) {
                        Modifier
                            .fillMaxWidth()
                            .tourTarget(TourTarget.NETWORKS_AFTERNET_ITEM)
                    } else {
                        Modifier.fillMaxWidth()
                    }

                    Card(
                        modifier = cardMod,
                        onClick = { onSelect(n.id) }
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    n.name,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (n.id == active) Badge { Text("Selected") }
                            }

                            Text(
                                "${n.host}:${n.port}  •  TLS ${if (n.useTls) "on" else "off"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Nick: ${n.nick}${n.altNick?.let { " (alt: $it)" } ?: ""}",
                                style = MaterialTheme.typography.bodySmall
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Auto-connect",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = n.autoConnect,
                                    onCheckedChange = { onSetAutoConnect(n.id, it) }
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isConnecting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(if (isConn) "●" else "○")
                                }
                                Text(status, style = MaterialTheme.typography.bodySmall)
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedButton(onClick = { onEdit(n.id) }) { Text("Edit") }
                                OutlinedButton(onClick = { onDelete(n.id) }) { Text("Delete") }

                                Spacer(Modifier.weight(1f))

                                val connectMod = if (n.id == active) Modifier.tourTarget(TourTarget.NETWORKS_CONNECT_BUTTON) else Modifier

                                if (isConn || isConnecting) {
                                    Button(onClick = { onSelect(n.id); onDisconnect(n.id) }, modifier = connectMod) { Text("Disconnect") }
                                } else {
                                    Button(onClick = { onSelect(n.id); onConnect(n.id) }, modifier = connectMod) { Text("Connect") }
                                }
                            }
                        }
                    }
                }
            }

            if (active != null) {
                val c = state.connections[active]
                val label = state.networks.firstOrNull { it.id == active }?.name ?: "Active"
                val status = c?.status ?: state.status
                Spacer(Modifier.height(8.dp))
                Text("$label: $status", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    // Warn when user attempts to connect without TLS and without explicit opt-in.
    val warnNetId = state.plaintextWarningNetworkId
    if (warnNetId != null) {
        val prof = state.networks.firstOrNull { it.id == warnNetId }
        val hostPort = if (prof != null) "${prof.host}:${prof.port}" else "this network"
        AlertDialog(
            onDismissRequest = onDismissPlaintextWarning,
            title = { Text("Insecure connection blocked") },
            text = {
                Column {
                    Text("Plaintext IRC connections are not encrypted and can expose your messages and password.")
                    Spacer(Modifier.height(8.dp))
                    Text("To connect to $hostPort without TLS, you must explicitly allow insecure plaintext connections for this network.")
                }
            },
            confirmButton = {
                TextButton(onClick = { onAllowPlaintextConnect(warnNetId) }) {
                    Text("Allow & Connect")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onEdit(warnNetId); onDismissPlaintextWarning() }) { Text("Edit network") }
                    TextButton(onClick = onDismissPlaintextWarning) { Text("Cancel") }
                }
            }
        )
    }

}
