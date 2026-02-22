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

import androidx.compose.ui.res.stringResource
import com.boxlabs.hexdroid.R
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
                title = { Text(stringResource(R.string.transfers_title)) },
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
                    Text(stringResource(R.string.setting_enable_dcc))
                    Switch(checked = state.settings.dccEnabled, onCheckedChange = { onSetDccEnabled(it) }, modifier = Modifier.tourTarget(TourTarget.TRANSFERS_ENABLE_DCC))
                }
            }

            item {
                if (!state.settings.dccEnabled) {
                    Text(
                        stringResource(R.string.transfers_dcc_disabled_warning),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    var modeMenu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.transfers_dcc_send_mode))
                        Box {
                            OutlinedButton(onClick = { modeMenu = true }) {
                                Text(when (state.settings.dccSendMode) {
                                    DccSendMode.AUTO -> stringResource(R.string.transfers_mode_auto)
                                    DccSendMode.ACTIVE -> stringResource(R.string.transfers_mode_active)
                                    DccSendMode.PASSIVE -> stringResource(R.string.transfers_mode_passive)
                                })
                            }
                            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.transfers_mode_auto)) },
                                    onClick = { modeMenu = false; onSetDccSendMode(DccSendMode.AUTO) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.transfers_mode_active)) },
                                    onClick = { modeMenu = false; onSetDccSendMode(DccSendMode.ACTIVE) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.transfers_mode_passive)) },
                                    onClick = { modeMenu = false; onSetDccSendMode(DccSendMode.PASSIVE) }
                                )
                            }
                        }
                    }
                    Text(
                        when (state.settings.dccSendMode) {
                            DccSendMode.AUTO -> stringResource(R.string.transfers_mode_auto_desc)
                            DccSendMode.ACTIVE -> stringResource(R.string.transfers_mode_active_desc)
                            DccSendMode.PASSIVE -> stringResource(R.string.transfers_mode_passive_desc)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            item { Text(stringResource(R.string.transfers_send_file), style = MaterialTheme.typography.titleMedium) }

            item {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text(stringResource(R.string.transfers_target_nick)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = state.settings.dccEnabled && target.trim().isNotBlank(),
                    modifier = Modifier.tourTarget(TourTarget.TRANSFERS_PICK_FILE)
                ) { Text(stringResource(R.string.transfers_pick_file)) }
            }

            item { Spacer(Modifier.height(12.dp)) }

            item { Text(stringResource(R.string.transfers_dcc_chat), style = MaterialTheme.typography.titleMedium) }

            item {
                OutlinedTextField(
                    value = chatTarget,
                    onValueChange = { chatTarget = it },
                    label = { Text(stringResource(R.string.transfers_target_nick)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(
                    onClick = { onStartChat(chatTarget.trim()) },
                    enabled = state.settings.dccEnabled && chatTarget.trim().isNotBlank()
                ) { Text(stringResource(R.string.transfers_start_dcc_chat)) }
            }

            item { HorizontalDivider() }

            item { Text(stringResource(R.string.transfers_incoming_chat_offers), style = MaterialTheme.typography.titleMedium) }

            if (state.dccChatOffers.isEmpty()) {
                item { Text(stringResource(R.string.transfers_no_chat_offers)) }
            } else {
                items(state.dccChatOffers) { o ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.transfers_from_chat_offer, o.from, o.protocol))
                            Text("${o.ip}:${o.port}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onAcceptChat(o) }, enabled = state.settings.dccEnabled) { Text(stringResource(R.string.action_accept)) }
                                OutlinedButton(onClick = { onRejectChat(o) }) { Text(stringResource(R.string.action_reject)) }
                            }
                        }
                    }
                }
            }

            item { HorizontalDivider() }

            item { Text(stringResource(R.string.transfers_incoming_offers), style = MaterialTheme.typography.titleMedium) }

            if (state.dccOffers.isEmpty()) {
                item { Text(stringResource(R.string.transfers_no_offers)) }
            } else {
                items(state.dccOffers) { o ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val passiveSuffix = if (o.isPassive) stringResource(R.string.transfers_passive_suffix) else ""
                            Text(stringResource(R.string.transfers_from_file_offer, o.from, o.filename, passiveSuffix))
                            val ep = if (o.port > 0) "${o.ip}:${o.port}" else stringResource(R.string.transfers_reply_port_waiting)
                            Text(stringResource(R.string.transfers_size_bytes_format, ep, o.size), style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onAccept(o) }, enabled = state.settings.dccEnabled) { Text(stringResource(R.string.action_accept)) }
                                OutlinedButton(onClick = { onReject(o) }) { Text(stringResource(R.string.action_reject)) }
                            }
                        }
                    }
                }
            }

            item { HorizontalDivider() }

            item { Text(stringResource(R.string.transfers_section_title), style = MaterialTheme.typography.titleMedium) }

            if (state.dccTransfers.isEmpty()) {
                item { Text(stringResource(R.string.transfers_no_transfers)) }
            } else {
                items(state.dccTransfers) { t ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            when (t) {
                                is DccTransferState.Incoming -> {
                                    Text(stringResource(R.string.transfers_incoming_format, t.offer.filename, t.offer.from))
                                    val pct =
                                        if (t.offer.size > 0) (t.received.toDouble() / t.offer.size.toDouble() * 100.0)
                                            .coerceIn(0.0, 100.0) else 0.0
                                    LinearProgressIndicator(progress = { (pct / 100.0).toFloat() })
                                    Text(
                                        stringResource(R.string.transfers_progress_format, t.received, t.offer.size, "%.1f".format(pct)),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (t.done && t.savedPath != null) {
                                        Button(onClick = { onShareFile(t.savedPath) }) { Text(stringResource(R.string.transfers_share_file)) }
                                    }
                                    if (t.error != null) Text(stringResource(R.string.transfers_error_format, t.error), color = MaterialTheme.colorScheme.error)
                                }

                                is DccTransferState.Outgoing -> {
                                    Text(stringResource(R.string.transfers_outgoing_format, t.filename, t.target))
                                    Text(stringResource(R.string.transfers_bytes_sent_format, t.bytesSent), style = MaterialTheme.typography.bodySmall)
                                    if (t.done) Text(stringResource(R.string.done))
                                    if (t.error != null) Text(stringResource(R.string.transfers_error_format, t.error), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
