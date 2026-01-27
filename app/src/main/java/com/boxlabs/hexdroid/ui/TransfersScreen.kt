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

import com.boxlabs.hexdroid.ui.tour.TourTarget
import com.boxlabs.hexdroid.ui.tour.tourTarget

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.DccOffer
import com.boxlabs.hexdroid.DccChatOffer
import com.boxlabs.hexdroid.DccSendMode
import com.boxlabs.hexdroid.DccTransferState
import com.boxlabs.hexdroid.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    state: UiState,
    onBack: () -> Unit,
    onAccept: (DccOffer) -> Unit,
    onReject: (DccOffer) -> Unit,
    onAcceptChat: (DccChatOffer) -> Unit,
    onRejectChat: (DccChatOffer) -> Unit,
    onStartChat: (String) -> Unit,
    onSend: (android.net.Uri, String) -> Unit,
    onShareFile: (String) -> Unit,
    onSetDccEnabled: (Boolean) -> Unit,
    onSetDccSendMode: (DccSendMode) -> Unit
) {
    var target by remember { mutableStateOf("") }
    var chatTarget by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSend(uri, target.trim())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("File transfers") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Enable DCC")
                    Switch(checked = state.settings.dccEnabled, onCheckedChange = { onSetDccEnabled(it) }, modifier = Modifier.tourTarget(TourTarget.TRANSFERS_ENABLE_DCC))
                }
            }

            item {
                if (!state.settings.dccEnabled) {
                    Text(
                        "DCC is currently disabled. Turn it on to send/accept files.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    var modeMenu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("DCC send mode")
                        Box {
                            OutlinedButton(onClick = { modeMenu = true }) {
                                Text(state.settings.dccSendMode.name.lowercase().replaceFirstChar { it.titlecase() })
                            }
                            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Auto") },
                                    onClick = { modeMenu = false; onSetDccSendMode(DccSendMode.AUTO) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Active") },
                                    onClick = { modeMenu = false; onSetDccSendMode(DccSendMode.ACTIVE) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Passive") },
                                    onClick = { modeMenu = false; onSetDccSendMode(DccSendMode.PASSIVE) }
                                )
                            }
                        }
                    }
                    Text(
                        when (state.settings.dccSendMode) {
                            DccSendMode.AUTO -> "Auto: try Passive DCC or fall back to Active if there is no reply."
                            DccSendMode.ACTIVE -> "Active: classic DCC SEND (the receiver connects to you)."
                            DccSendMode.PASSIVE -> "Passive: request the receiver to open a port, then you connect to them."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            item { Text("Send file", style = MaterialTheme.typography.titleMedium) }

            item {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Target nick") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = state.settings.dccEnabled && target.trim().isNotBlank(),
                    modifier = Modifier.tourTarget(TourTarget.TRANSFERS_PICK_FILE)
                ) { Text("Pick file to send") }
            }

            item { Spacer(Modifier.height(12.dp)) }

            item { Text("DCC CHAT", style = MaterialTheme.typography.titleMedium) }

            item {
                OutlinedTextField(
                    value = chatTarget,
                    onValueChange = { chatTarget = it },
                    label = { Text("Target nick") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(
                    onClick = { onStartChat(chatTarget.trim()) },
                    enabled = state.settings.dccEnabled && chatTarget.trim().isNotBlank()
                ) { Text("Start DCC CHAT") }
            }

            item { Divider() }

            item { Text("Incoming chat offers", style = MaterialTheme.typography.titleMedium) }

            if (state.dccChatOffers.isEmpty()) {
                item { Text("No chat offers yet.") }
            } else {
                items(state.dccChatOffers) { o ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("From ${o.from}: DCC CHAT (${o.protocol})")
                            Text("${o.ip}:${o.port}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onAcceptChat(o) }, enabled = state.settings.dccEnabled) { Text("Accept") }
                                OutlinedButton(onClick = { onRejectChat(o) }) { Text("Reject") }
                            }
                        }
                    }
                }
            }

            item { Divider() }

            item { Text("Incoming offers", style = MaterialTheme.typography.titleMedium) }

            if (state.dccOffers.isEmpty()) {
                item { Text("No offers yet.") }
            } else {
                items(state.dccOffers) { o ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("From ${o.from}: ${o.filename}${if (o.isPassive) " (passive)" else ""}")
                            val ep = if (o.port > 0) "${o.ip}:${o.port}" else "reply-port: (waiting)"
                            Text("$ep • ${o.size} bytes", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onAccept(o) }, enabled = state.settings.dccEnabled) { Text("Accept") }
                                OutlinedButton(onClick = { onReject(o) }) { Text("Reject") }
                            }
                        }
                    }
                }
            }

            item { Divider() }

            item { Text("Transfers", style = MaterialTheme.typography.titleMedium) }

            if (state.dccTransfers.isEmpty()) {
                item { Text("No transfers in progress.") }
            } else {
                items(state.dccTransfers) { t ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            when (t) {
                                is DccTransferState.Incoming -> {
                                    Text("Incoming: ${t.offer.filename} from ${t.offer.from}")
                                    val pct =
                                        if (t.offer.size > 0) (t.received.toDouble() / t.offer.size.toDouble() * 100.0)
                                            .coerceIn(0.0, 100.0) else 0.0
                                    LinearProgressIndicator(progress = (pct / 100.0).toFloat())
                                    Text(
                                        "${t.received}/${t.offer.size} bytes • ${"%.1f".format(pct)}%",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (t.done && t.savedPath != null) {
                                        Button(onClick = { onShareFile(t.savedPath) }) { Text("Share file") }
                                    }
                                    if (t.error != null) Text("Error: ${t.error}", color = MaterialTheme.colorScheme.error)
                                }

                                is DccTransferState.Outgoing -> {
                                    Text("Outgoing: ${t.filename} to ${t.target}")
                                    Text("${t.bytesSent} bytes sent", style = MaterialTheme.typography.bodySmall)
                                    if (t.done) Text("Done")
                                    if (t.error != null) Text("Error: ${t.error}", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
