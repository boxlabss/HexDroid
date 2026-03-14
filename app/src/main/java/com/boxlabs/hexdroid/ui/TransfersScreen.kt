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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.boxlabs.hexdroid.R


/** Format a byte count into a human-readable string: "1.4 MB", "823 KB", etc. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024.0)
    else                    -> "$bytes B"
}

/** Format bytes-per-second into KB/s or MB/s. */
private fun formatSpeed(bytesPerSec: Double): String = when {
    bytesPerSec >= 1_048_576.0 -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
    bytesPerSec >= 1_024.0     -> "%.0f KB/s".format(bytesPerSec / 1_024.0)
    else                        -> "${bytesPerSec.toLong()} B/s"
}

/** Format remaining seconds as "1h 23m", "4m 30s", "<1s". */
private fun formatEta(seconds: Long): String = when {
    seconds <= 0   -> "<1s"
    seconds < 60   -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else           -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

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
    onSetDccSendMode: (DccSendMode) -> Unit,
    onCancelOutgoing: (target: String, filename: String) -> Unit = { _, _ -> },
    /** Navigate directly to a buffer (e.g. a DCC chat buffer) and close Transfers. */
    onOpenBuffer: ((String) -> Unit)? = null
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
                    Text(stringResource(R.string.transfers_enable_dcc))
                    Switch(checked = state.settings.dccEnabled, onCheckedChange = { onSetDccEnabled(it) }, modifier = Modifier.tourTarget(TourTarget.TRANSFERS_ENABLE_DCC))
                }
            }

            item {
                if (!state.settings.dccEnabled) {
                    Text(
                        stringResource(R.string.transfers_dcc_disabled),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    var modeMenu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.transfers_dcc_send_mode))
                        Box {
                            OutlinedButton(onClick = { modeMenu = true }) {
                                Text(state.settings.dccSendMode.name.lowercase().replaceFirstChar { it.titlecase() })
                            }
                            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.transfers_dcc_auto)) },
                                    onClick = { modeMenu = false; onSetDccSendMode(DccSendMode.AUTO) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.transfers_dcc_active)) },
                                    onClick = { modeMenu = false; onSetDccSendMode(DccSendMode.ACTIVE) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.transfers_dcc_passive)) },
                                    onClick = { modeMenu = false; onSetDccSendMode(DccSendMode.PASSIVE) }
                                )
                            }
                        }
                    }
                    Text(
                        when (state.settings.dccSendMode) {
                            DccSendMode.AUTO -> stringResource(R.string.transfers_dcc_auto_desc)
                            DccSendMode.ACTIVE -> stringResource(R.string.transfers_dcc_active_desc)
                            DccSendMode.PASSIVE -> stringResource(R.string.transfers_dcc_passive_desc)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            item { Text(stringResource(R.string.transfers_send_file_section), style = MaterialTheme.typography.titleMedium) }

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

            item { Text(stringResource(R.string.transfers_dcc_chat_section), style = MaterialTheme.typography.titleMedium) }

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

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.transfers_chat_offers_section), style = MaterialTheme.typography.titleMedium)
                    if (state.dccChatOffers.isNotEmpty()) {
                        androidx.compose.material3.Badge {
                            Text("${state.dccChatOffers.size}")
                        }
                    }
                }
            }

            if (state.dccChatOffers.isEmpty()) {
                item { Text(stringResource(R.string.transfers_no_chat_offers), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.dccChatOffers) { o ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Text(
                                    "DCC CHAT from ${o.from}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                )
                                // Badge showing the protocol (chat / ssl-chat)
                                if (o.protocol != "chat") {
                                    androidx.compose.material3.SuggestionChip(
                                        onClick = {},
                                        label = { Text(o.protocol, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                            Text("${o.ip}:${o.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Button(
                                    onClick = { onAcceptChat(o) },
                                    enabled = state.settings.dccEnabled
                                ) { Text(stringResource(R.string.transfers_accept)) }
                                OutlinedButton(onClick = { onRejectChat(o) }) { Text(stringResource(R.string.transfers_reject)) }
                                // "Open buffer" shortcut - navigates to the pre-created DCCCHAT buffer
                                if (onOpenBuffer != null) {
                                    val bufKey = "${o.netId}::DCCCHAT:${o.from}"
                                    TextButton(onClick = {
                                        onAcceptChat(o)  // accept and then navigate
                                    }) { Text(stringResource(R.string.transfers_accept_open)) }
                                }
                            }
                        }
                    }
                }
            }

            item { HorizontalDivider() }

            item { Text(stringResource(R.string.transfers_offers_section), style = MaterialTheme.typography.titleMedium) }

            if (state.dccOffers.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.transfers_no_offers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(state.dccOffers) { o ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Header: icon + filename + size chip
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        o.filename,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        "from ${o.from}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                                // File size chip
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.secondary
                                ) {
                                    Text(
                                        if (o.size > 0) formatBytes(o.size) else stringResource(R.string.transfers_unknown_size),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // Connection details + passive badge
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val ep = if (o.port > 0) "${o.ip}:${o.port}" else stringResource(R.string.transfers_passive_port)
                                Text(
                                    ep,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier.weight(1f)
                                )
                                if (o.isPassive) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Text(
                                            "passive",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (o.turbo) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Text(
                                            "turbo",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            // Action buttons
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onAccept(o) },
                                    enabled = state.settings.dccEnabled
                                ) { Text(stringResource(R.string.transfers_accept)) }
                                OutlinedButton(
                                    onClick = { onReject(o) }
                                ) { Text(stringResource(R.string.transfers_reject)) }
                            }
                        }
                    }
                }
            }

            item { HorizontalDivider() }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.transfers_in_progress_section), style = MaterialTheme.typography.titleMedium)
                    val active = state.dccTransfers.count { !(it as? DccTransferState.Incoming)?.done.also {} .let { false } }
                    val inProgress = state.dccTransfers.count {
                        when (it) {
                            is DccTransferState.Incoming -> !it.done
                            is DccTransferState.Outgoing -> !it.done
                        }
                    }
                    if (inProgress > 0) {
                        androidx.compose.material3.Badge(containerColor = MaterialTheme.colorScheme.primary) {
                            Text("$inProgress active")
                        }
                    }
                }
            }

            if (state.dccTransfers.isEmpty()) {
                item { Text(stringResource(R.string.transfers_no_transfers), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.dccTransfers) { t ->
                    val now = System.currentTimeMillis()
                    when (t) {
                        is DccTransferState.Incoming -> {
                            val totalBytes = t.offer.size
                            val pct = if (totalBytes > 0)
                                (t.received.toDouble() / totalBytes * 100.0).coerceIn(0.0, 100.0)
                            else 0.0
                            val elapsedSec = ((now - t.startTimeMs) / 1000.0).coerceAtLeast(0.1)
                            val speedBps = t.received / elapsedSec
                            val etaSec = if (speedBps > 0 && totalBytes > 0)
                                ((totalBytes - t.received) / speedBps).toLong() else -1L

                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        t.error != null -> MaterialTheme.colorScheme.errorContainer
                                        t.done -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.surface
                                    }
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = if (t.done || t.error != null) 0.dp else 2.dp)
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Header row: icon + filename + status badge
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = when {
                                                t.error != null -> Icons.Default.Error
                                                t.done -> Icons.Default.CheckCircle
                                                else -> Icons.Default.Download
                                            },
                                            contentDescription = null,
                                            tint = when {
                                                t.error != null -> MaterialTheme.colorScheme.error
                                                t.done -> Color(0xFF4CAF50)
                                                else -> MaterialTheme.colorScheme.primary
                                            },
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                t.offer.filename,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "from ${t.offer.from}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        // Status / size chip
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                if (totalBytes > 0) formatBytes(totalBytes) else "unknown size",
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    // Progress bar (only when in-progress)
                                    if (!t.done && t.error == null) {
                                        LinearProgressIndicator(
                                            progress = { (pct / 100.0).toFloat() },
                                            modifier = Modifier.fillMaxWidth(),
                                            strokeCap = StrokeCap.Round
                                        )

                                        // Bytes received / speed / ETA
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                if (totalBytes > 0)
                                                    "${formatBytes(t.received)} / ${formatBytes(totalBytes)}  (${"%.1f".format(pct)}%)"
                                                else
                                                    "${formatBytes(t.received)} received",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (speedBps > 0) {
                                                Text(
                                                    buildString {
                                                        append(formatSpeed(speedBps))
                                                        if (etaSec >= 0) append("  ETA ${formatEta(etaSec)}")
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    // Done state
                                    if (t.done && t.error == null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                "Complete — ${formatBytes(t.received)} in ${formatEta(((now - t.startTimeMs) / 1000).coerceAtLeast(1L))}  (${formatSpeed(t.received / ((now - t.startTimeMs) / 1000.0).coerceAtLeast(1.0))} avg)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF4CAF50)
                                            )
                                        }
                                        if (t.savedPath != null) {
                                            Button(
                                                onClick = { onShareFile(t.savedPath) },
                                                modifier = Modifier.fillMaxWidth()
                                            ) { Text(stringResource(R.string.transfers_share_open)) }
                                        }
                                    }

                                    // Error state
                                    if (t.error != null) {
                                        Text(
                                            "Error: ${t.error}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }

                        is DccTransferState.Outgoing -> {
                            val totalBytes = t.fileSize
                            val pct = if (totalBytes > 0)
                                (t.bytesSent.toDouble() / totalBytes * 100.0).coerceIn(0.0, 100.0)
                            else 0.0
                            val elapsedSec = ((now - t.startTimeMs) / 1000.0).coerceAtLeast(0.1)
                            val speedBps = t.bytesSent / elapsedSec
                            val etaSec = if (speedBps > 0 && totalBytes > 0)
                                ((totalBytes - t.bytesSent) / speedBps).toLong() else -1L

                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        t.error != null -> MaterialTheme.colorScheme.errorContainer
                                        t.done -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.surface
                                    }
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = if (t.done || t.error != null) 0.dp else 2.dp)
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = when {
                                                t.error != null -> Icons.Default.Error
                                                t.done -> Icons.Default.CheckCircle
                                                else -> Icons.Default.Upload
                                            },
                                            contentDescription = null,
                                            tint = when {
                                                t.error != null -> MaterialTheme.colorScheme.error
                                                t.done -> Color(0xFF4CAF50)
                                                else -> Color(0xFF9C27B0) // purple for upload
                                            },
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                t.filename,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "to ${t.target}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (totalBytes > 0) {
                                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                                Text(formatBytes(totalBytes),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }

                                    if (!t.done && t.error == null) {
                                        if (totalBytes > 0) {
                                            LinearProgressIndicator(
                                                progress = { (pct / 100.0).toFloat() },
                                                modifier = Modifier.fillMaxWidth(),
                                                strokeCap = StrokeCap.Round
                                            )
                                        } else {
                                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        }
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                if (totalBytes > 0)
                                                    "${formatBytes(t.bytesSent)} / ${formatBytes(totalBytes)}  (${"%.1f".format(pct)}%)"
                                                else
                                                    "${formatBytes(t.bytesSent)} sent",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (speedBps > 0) {
                                                Text(
                                                    buildString {
                                                        append(formatSpeed(speedBps))
                                                        if (etaSec >= 0) append("  ETA ${formatEta(etaSec)}")
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        TextButton(
                                            onClick = { onCancelOutgoing(t.target, t.filename) },
                                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error
                                            ),
                                            modifier = Modifier.align(Alignment.End)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.size(4.dp))
                                            Text("Cancel")
                                        }
                                    }

                                    if (t.done && t.error == null) {
                                        Text(
                                            "Complete — ${formatBytes(t.bytesSent)} in ${formatEta(((now - t.startTimeMs) / 1000).coerceAtLeast(1L))}  (${formatSpeed(t.bytesSent / ((now - t.startTimeMs) / 1000.0).coerceAtLeast(1.0))} avg)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF4CAF50)
                                        )
                                    }
                                    if (t.error != null) {
                                        Text(stringResource(R.string.transfers_error, t.error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
