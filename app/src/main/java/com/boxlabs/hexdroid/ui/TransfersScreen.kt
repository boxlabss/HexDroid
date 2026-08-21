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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.DccChatOffer
import com.boxlabs.hexdroid.DccOffer
import com.boxlabs.hexdroid.DccSendMode
import com.boxlabs.hexdroid.DccTransferState
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.UiState

/** Green used for the completed state, matching the connected dot elsewhere. */
private val DONE_GREEN = Color(0xFF4CAF50)

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

/** True while a transfer is neither finished nor failed. */
private fun DccTransferState.isRunning(): Boolean = when (this) {
    is DccTransferState.Incoming -> !done && error == null
    is DccTransferState.Outgoing -> !done && error == null
}

/** Stable identity for a transfer, so the list keeps its place as bytes tick up. */
private fun DccTransferState.listKey(): String = when (this) {
    is DccTransferState.Incoming -> "in:${offer.from}:${offer.filename}:$startTimeMs"
    is DccTransferState.Outgoing -> "out:$target:$filename:$startTimeMs"
}

/**
 * One page of the transfers screen. Ordered.
 */
enum class TransfersSection(val titleRes: Int, val icon: ImageVector) {
    ACTIVE(R.string.transfers_tab_active, Icons.Filled.SwapVert),
    OFFERS(R.string.transfers_tab_offers, Icons.Filled.Inbox),
    HISTORY(R.string.transfers_tab_history, Icons.Filled.History),
    SEND(R.string.transfers_tab_send, Icons.Filled.Upload),
    SETTINGS(R.string.transfers_tab_settings, Icons.Filled.Tune),
}

/** label used for size and protocol markers. */
@Composable
private fun InfoChip(
    text: String,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(shape = RoundedCornerShape(4.dp), color = container) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Message shown when a section has nothing in it. */
@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/**
 * One transfer, incoming or outgoing.
 */
@Composable
private fun TransferCard(
    filename: String,
    peerLine: String,
    incoming: Boolean,
    transferred: Long,
    total: Long,
    done: Boolean,
    error: String?,
    startTimeMs: Long,
    endTimeMs: Long?,
    resumeOffset: Long,
    onCancel: (() -> Unit)?,
    onClear: () -> Unit,
    onShare: (() -> Unit)?,
) {
    val now = System.currentTimeMillis()
    val running = !done && error == null
    val pct = if (total > 0) (transferred.toDouble() / total * 100.0).coerceIn(0.0, 100.0) else 0.0
    val elapsedSec = ((now - startTimeMs) / 1000.0).coerceAtLeast(0.1)
    val speedBps = transferred / elapsedSec
    val etaSec = if (speedBps > 0 && total > 0) ((total - transferred) / speedBps).toLong() else -1L

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                error != null -> MaterialTheme.colorScheme.errorContainer
                done -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (running) 2.dp else 0.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = when {
                        error != null -> Icons.Default.Error
                        done -> Icons.Default.CheckCircle
                        incoming -> Icons.Default.Download
                        else -> Icons.Default.Upload
                    },
                    contentDescription = null,
                    tint = when {
                        error != null -> MaterialTheme.colorScheme.error
                        done -> DONE_GREEN
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(20.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        filename,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        peerLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InfoChip(
                    if (total > 0) formatBytes(total) else stringResource(R.string.transfers_unknown_size)
                )
            }

            if (running) {
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (pct / 100.0).toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                        strokeCap = StrokeCap.Round,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (total > 0) {
                            "${formatBytes(transferred)} / ${formatBytes(total)}  (${"%.1f".format(pct)}%)"
                        } else if (incoming) {
                            formatBytes(transferred)
                        } else {
                            stringResource(R.string.transfers_sent, formatBytes(transferred))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (speedBps > 0) {
                        Text(
                            buildString {
                                append(formatSpeed(speedBps))
                                if (etaSec >= 0) append(stringResource(R.string.transfers_eta, formatEta(etaSec)))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (resumeOffset > 0L) {
                    Text(
                        stringResource(R.string.transfers_resuming_from, formatBytes(resumeOffset)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (onCancel != null) {
                    TextButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .align(Alignment.End)
                            .focusHighlight(RoundedCornerShape(50)),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.cancel))
                    }
                }
            }

            if (done && error == null) {
                // endTimeMs freezes the elapsed time and average speed at completion,
                // rather than letting wall clock keep growing afterwards.
                val endMs = endTimeMs ?: now
                val elapsedDoneSec = ((endMs - startTimeMs) / 1000.0).coerceAtLeast(1.0)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(
                            R.string.transfers_complete,
                            formatBytes(transferred),
                            formatEta(elapsedDoneSec.toLong()),
                            formatSpeed(transferred / elapsedDoneSec),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = DONE_GREEN,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(32.dp).focusHighlight(),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.clear),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onShare != null) {
                    Button(
                        onClick = onShare,
                        modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(50)),
                    ) { Text(stringResource(R.string.transfers_share_open)) }
                }
            }

            if (error != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.transfers_error, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(32.dp).focusHighlight(),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.clear),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Renders one transfer from its state, mapping either direction onto [TransferCard]. */
@Composable
private fun TransferEntry(
    transfer: DccTransferState,
    onCancelIncoming: (DccOffer) -> Unit,
    onCancelOutgoing: (target: String, filename: String) -> Unit,
    onClearTransfer: (DccTransferState) -> Unit,
    onShareFile: (String) -> Unit,
) {
    when (transfer) {
        is DccTransferState.Incoming -> TransferCard(
            filename = transfer.offer.filename,
            peerLine = stringResource(R.string.transfers_from, transfer.offer.from),
            incoming = true,
            transferred = transfer.received,
            total = transfer.offer.size,
            done = transfer.done,
            error = transfer.error,
            startTimeMs = transfer.startTimeMs,
            endTimeMs = transfer.endTimeMs,
            resumeOffset = transfer.resumeOffset,
            onCancel = { onCancelIncoming(transfer.offer) },
            onClear = { onClearTransfer(transfer) },
            onShare = transfer.savedPath?.let { path -> { onShareFile(path) } },
        )

        is DccTransferState.Outgoing -> TransferCard(
            filename = transfer.filename,
            peerLine = stringResource(R.string.transfers_to, transfer.target),
            incoming = false,
            transferred = transfer.bytesSent,
            total = transfer.fileSize,
            done = transfer.done,
            error = transfer.error,
            startTimeMs = transfer.startTimeMs,
            endTimeMs = transfer.endTimeMs,
            resumeOffset = transfer.resumeOffset,
            onCancel = { onCancelOutgoing(transfer.target, transfer.filename) },
            onClear = { onClearTransfer(transfer) },
            onShare = null,
        )
    }
}

/** An incoming file offer, with accept, resume and reject. */
@Composable
private fun FileOfferCard(
    offer: DccOffer,
    dccEnabled: Boolean,
    partialBytes: Long?,
    onAccept: () -> Unit,
    onAcceptResume: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        offer.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        stringResource(R.string.transfers_from, offer.from),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                }
                InfoChip(
                    text = if (offer.size > 0) formatBytes(offer.size)
                           else stringResource(R.string.transfers_unknown_size),
                    container = MaterialTheme.colorScheme.secondary,
                    content = MaterialTheme.colorScheme.onSecondary,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (offer.port > 0) "${offer.ip}:${offer.port}"
                    else stringResource(R.string.transfers_passive_port),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                if (offer.isPassive) {
                    InfoChip(
                        text = "passive",
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                if (offer.turbo) {
                    InfoChip(
                        text = "turbo",
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                if (offer.secure) {
                    InfoChip(
                        text = "tls",
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            if (partialBytes != null) {
                Text(
                    stringResource(R.string.transfers_partial_available, formatBytes(partialBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAccept,
                    enabled = dccEnabled,
                    modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.transfers_accept)) }
                if (partialBytes != null) {
                    OutlinedButton(
                        onClick = onAcceptResume,
                        enabled = dccEnabled,
                        modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                    ) { Text(stringResource(R.string.transfers_resume)) }
                }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.transfers_reject)) }
            }
        }
    }
}

/** An incoming DCC chat offer. */
@Composable
private fun ChatOfferCard(
    offer: DccChatOffer,
    dccEnabled: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.transfers_dcc_chat_from, offer.from),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (offer.protocol != "chat") InfoChip(offer.protocol)
            }
            Text(
                "${offer.ip}:${offer.port}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAccept,
                    enabled = dccEnabled,
                    modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.transfers_accept)) }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.transfers_reject)) }
            }
        }
    }
}

/** Banner shown on the action pages while DCC is switched off. */
@Composable
private fun DccOffBanner(onEnable: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.transfers_dcc_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onEnable,
                modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
            ) { Text(stringResource(R.string.transfers_enable)) }
        }
    }
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
    onCancelIncoming: (DccOffer) -> Unit = {},
    onClearTransfer: (DccTransferState) -> Unit = {},
    /** Accept an incoming offer with DCC RESUME, picking up from a stored partial. */
    onAcceptResume: (DccOffer) -> Unit = {},
    /** Look up the partial bytes available for an offer (null = none / not resumable). */
    partialFor: (DccOffer) -> com.boxlabs.hexdroid.PartialTransfer? = { null },
    /** Navigate directly to a buffer (e.g. a DCC chat buffer) and close Transfers. */
    onOpenBuffer: ((String) -> Unit)? = null,
    /** Open the list of nicks whose file offers are auto-accepted. */
    onOpenTrusted: (() -> Unit)? = null,
    tourActive: Boolean = false,
    tourTarget: TourTarget? = null,
) {
    var target by remember { mutableStateOf("") }
    var chatTarget by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSend(uri, target.trim())
    }

    val dccEnabled = state.settings.dccEnabled
    val running = state.dccTransfers.filter { it.isRunning() }
    val finished = state.dccTransfers.filterNot { it.isRunning() }
    val offerCount = state.dccOffers.size + state.dccChatOffers.size

    // One page at a time: a rail beside the content on TV and tablets, a tab strip on phones.
    val railLayout = useSideRailNav()
    var picked by rememberSaveable { mutableStateOf<TransfersSection?>(null) }
    val section = picked ?: if (offerCount > 0) TransfersSection.OFFERS else TransfersSection.ACTIVE
    val sections = TransfersSection.values().toList()

    // The tour points at controls on two different pages, so open the page holding the
    // step's target before the overlay measures it.
    LaunchedEffect(tourActive, tourTarget) {
        if (!tourActive) return@LaunchedEffect
        when (tourTarget) {
            TourTarget.TRANSFERS_ENABLE_DCC -> picked = TransfersSection.SETTINGS
            TourTarget.TRANSFERS_PICK_FILE -> picked = TransfersSection.SEND
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transfers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.focusHighlight()) { Text("←") }
                }
            )
        }
    ) { padding ->
        // Counts ride along in the label so a waiting offer is visible from any page.
        val label: @Composable (TransfersSection) -> String = {
            val base = stringResource(it.titleRes)
            when {
                it == TransfersSection.OFFERS && offerCount > 0 -> "$base ($offerCount)"
                it == TransfersSection.ACTIVE && running.isNotEmpty() -> "$base (${running.size})"
                else -> base
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!railLayout) {
                SectionTabs(
                    entries = sections,
                    selected = section,
                    label = label,
                    onSelect = { picked = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (railLayout) {
                    SectionRail(
                        entries = sections,
                        selected = section,
                        label = label,
                        icon = { it.icon },
                        onSelect = { picked = it },
                        modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight(),
                    )
                    VerticalDivider()
                }

                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (section == TransfersSection.ACTIVE) {
                        if (running.isEmpty()) {
                            item { EmptyNote(stringResource(R.string.transfers_no_active)) }
                        } else {
                            items(running, key = { it.listKey() }) { t ->
                                TransferEntry(
                                    transfer = t,
                                    onCancelIncoming = onCancelIncoming,
                                    onCancelOutgoing = onCancelOutgoing,
                                    onClearTransfer = onClearTransfer,
                                    onShareFile = onShareFile,
                                )
                            }
                        }
                    }

                    if (section == TransfersSection.OFFERS) {
                        if (!dccEnabled) {
                            item { DccOffBanner(onEnable = { onSetDccEnabled(true) }) }
                        }
                        if (offerCount == 0) {
                            item { EmptyNote(stringResource(R.string.transfers_no_offers)) }
                        }
                        items(state.dccOffers) { o ->
                            FileOfferCard(
                                offer = o,
                                dccEnabled = dccEnabled,
                                partialBytes = partialFor(o)?.receivedBytes,
                                onAccept = { onAccept(o) },
                                onAcceptResume = { onAcceptResume(o) },
                                onReject = { onReject(o) },
                            )
                        }
                        if (state.dccChatOffers.isNotEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.transfers_chat_offers_section),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                            items(state.dccChatOffers) { o ->
                                ChatOfferCard(
                                    offer = o,
                                    dccEnabled = dccEnabled,
                                    onAccept = {
                                        onAcceptChat(o)
                                        onOpenBuffer?.invoke("${o.netId}::DCCCHAT:${o.from}")
                                    },
                                    onReject = { onRejectChat(o) },
                                )
                            }
                        }
                    }

                    if (section == TransfersSection.HISTORY) {
                        if (finished.isEmpty()) {
                            item { EmptyNote(stringResource(R.string.transfers_no_completed)) }
                        } else {
                            item {
                                OutlinedButton(
                                    onClick = { finished.forEach(onClearTransfer) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusHighlight(RoundedCornerShape(50)),
                                ) { Text(stringResource(R.string.transfers_clear_completed)) }
                            }
                            items(finished, key = { it.listKey() }) { t ->
                                TransferEntry(
                                    transfer = t,
                                    onCancelIncoming = onCancelIncoming,
                                    onCancelOutgoing = onCancelOutgoing,
                                    onClearTransfer = onClearTransfer,
                                    onShareFile = onShareFile,
                                )
                            }
                        }
                    }

                    if (section == TransfersSection.SEND) {
                        if (!dccEnabled) {
                            item { DccOffBanner(onEnable = { onSetDccEnabled(true) }) }
                        }
                        item {
                            Text(
                                stringResource(R.string.transfers_send_file_section),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = target,
                                onValueChange = { target = it },
                                label = { Text(stringResource(R.string.transfers_target_nick)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            Button(
                                onClick = { picker.launch(arrayOf("*/*")) },
                                enabled = dccEnabled && target.trim().isNotBlank(),
                                modifier = Modifier
                                    .tourTarget(TourTarget.TRANSFERS_PICK_FILE)
                                    .focusHighlight(RoundedCornerShape(50)),
                            ) { Text(stringResource(R.string.transfers_pick_file)) }
                        }

                        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }

                        item {
                            Text(
                                stringResource(R.string.transfers_dcc_chat_section),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = chatTarget,
                                onValueChange = { chatTarget = it },
                                label = { Text(stringResource(R.string.transfers_target_nick)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            Button(
                                onClick = { onStartChat(chatTarget.trim()) },
                                enabled = dccEnabled && chatTarget.trim().isNotBlank(),
                                modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                            ) { Text(stringResource(R.string.transfers_start_dcc_chat)) }
                        }
                    }

                    if (section == TransfersSection.SETTINGS) {
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.transfers_enable_dcc))
                                Switch(
                                    checked = dccEnabled,
                                    onCheckedChange = { onSetDccEnabled(it) },
                                    modifier = Modifier
                                        .tourTarget(TourTarget.TRANSFERS_ENABLE_DCC)
                                        .focusHighlight(RoundedCornerShape(16.dp)),
                                )
                            }
                        }

                        if (dccEnabled) {
                            item {
                                var modeMenu by remember { mutableStateOf(false) }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(stringResource(R.string.transfers_dcc_send_mode))
                                        Box {
                                            OutlinedButton(
                                                onClick = { modeMenu = true },
                                                modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                                            ) {
                                                Text(
                                                    state.settings.dccSendMode.name.lowercase()
                                                        .replaceFirstChar { it.titlecase() }
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = modeMenu,
                                                onDismissRequest = { modeMenu = false },
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.transfers_dcc_auto)) },
                                                    onClick = { modeMenu = false; onSetDccSendMode(DccSendMode.AUTO) },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.transfers_dcc_active)) },
                                                    onClick = { modeMenu = false; onSetDccSendMode(DccSendMode.ACTIVE) },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.transfers_dcc_passive)) },
                                                    onClick = { modeMenu = false; onSetDccSendMode(DccSendMode.PASSIVE) },
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
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        // The auto-accept list only has an effect while DCC is on, so it
                        // sits with the toggle. The count shows the state without opening it.
                        if (onOpenTrusted != null) {
                            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                            item {
                                val trustedCount = state.networks.sumOf { it.dccAutoAcceptNicks.size }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .focusHighlight(RoundedCornerShape(12.dp))
                                        .clickable { onOpenTrusted() }
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.dcc_trusted_title))
                                        Text(
                                            if (trustedCount == 0) {
                                                stringResource(R.string.dcc_trusted_entry_none)
                                            } else {
                                                pluralStringResource(
                                                    R.plurals.dcc_trusted_entry_count,
                                                    trustedCount,
                                                    trustedCount,
                                                )
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text("›", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}
