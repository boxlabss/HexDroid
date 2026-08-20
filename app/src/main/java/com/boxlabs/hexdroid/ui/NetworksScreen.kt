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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.boxlabs.hexdroid.BouncerKind
import com.boxlabs.hexdroid.BouncerUpstreamInfo
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.UiState
import com.boxlabs.hexdroid.data.NetworkProfile
import kotlinx.coroutines.delay

/** Green status dot */
private val CONNECTED_DOT = Color(0xFF4CAF50)

/**
 * Label plus a switch, used for the per-network toggles in the detail pane.
 */
@Composable
private fun DetailSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .focusHighlight(shape)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp)
            .heightIn(min = 44.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Label plus a scaled-down switch, for the per-network toggles on a list row.
 */
@Composable
private fun CompactSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(shape)
            .focusHighlight(shape)
            .clickable { onCheckedChange(!checked) }
            .padding(start = 4.dp)
            .heightIn(min = 36.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.graphicsLayer { scaleX = 0.8f; scaleY = 0.8f }
        )
    }
}

/**
 * Status dot, or a spinner while a connection attempt is in flight.
 */
@Composable
private fun StatusDot(connected: Boolean, connecting: Boolean) {
    if (connecting) {
        CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
    } else {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    color = if (connected) CONNECTED_DOT
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(50)
                )
        )
    }
}

/**
 * Small outlined label used for the auto-connect and favourite markers on a list row.
 */
@Composable
private fun MetaTag(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun NetworkRow(
    profile: NetworkProfile,
    connected: Boolean,
    connecting: Boolean,
    status: String,
    isSelected: Boolean,
    iconUrl: String?,
    onClick: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSetAutoConnect: (Boolean) -> Unit,
    onSetShowInSidebar: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    connectModifier: Modifier = Modifier,
    showControls: Boolean = true,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    val bg = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val fg = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface

    Surface(color = bg, contentColor = fg, shape = shape, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(bottom = if (showControls) 8.dp else 0.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(shape)
                        .focusHighlight(shape)
                        .clickable(onClick = onClick)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusDot(connected = connected, connecting = connecting)

                    // ICON / draft/ICON ISUPPORT token: server-advertised icon (ICON=<url>,
                    // optional literal {size} template). Gated on the global image-previews
                    // opt-in, https only, and never fetched for proxied profiles - the fetch
                    // would bypass the SOCKS proxy and leak the user's IP, same fail-closed
                    // rule as filehost uploads.
                    if (iconUrl != null) {
                        var iconBmp by remember(iconUrl) { mutableStateOf(RemoteImage.cached(iconUrl)) }
                        LaunchedEffect(iconUrl) {
                            if (iconBmp == null) iconBmp = RemoteImage.fetch(iconUrl)
                        }
                        iconBmp?.let { bmp ->
                            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    }

                    Column(Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (profile.isFavourite) {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                            Text(
                                profile.name,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        Text(
                            "${profile.host}:${profile.port}  •  $status",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!profile.useTls) {
                            Spacer(Modifier.height(4.dp))
                            MetaTag(stringResource(R.string.networks_tag_no_tls))
                        }
                    }
                    // Nothing else on the row hints that the name opens anything, and
                    // edit, delete and reorder all live behind it.
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.networks_open_details),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (dragHandle != null) dragHandle()
            }

            if (showControls) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (connected || connecting) {
                        FilledTonalButton(
                            onClick = onDisconnect,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = connectModifier.focusHighlight(RoundedCornerShape(50)),
                        ) { Text(stringResource(R.string.networks_disconnect), maxLines = 1) }
                    } else {
                        Button(
                            onClick = onConnect,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = connectModifier.focusHighlight(RoundedCornerShape(50)),
                        ) { Text(stringResource(R.string.networks_connect), maxLines = 1) }
                    }

                    Column(Modifier.weight(1f)) {
                        CompactSwitch(
                            label = stringResource(R.string.networks_auto_connect),
                            checked = profile.autoConnect,
                            onCheckedChange = onSetAutoConnect,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CompactSwitch(
                            label = stringResource(R.string.network_show_in_switcher_label),
                            checked = profile.showInSidebar,
                            onCheckedChange = onSetShowInSidebar,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Everything you can do to one network: connect, edit, reorder, delete and the
 * per-network toggles. Shown in the right pane on TV and tablets, and in a bottom
 * sheet on phones, so the list itself stays a plain scrollable column of names.
 */
@Composable
private fun NetworkDetailPane(
    state: UiState,
    profile: NetworkProfile,
    orderedIds: List<String>,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onSetAutoConnect: (String, Boolean) -> Unit,
    onSetShowInSidebar: (String, Boolean) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onRefreshBouncerNetworks: (String) -> Unit,
    onCloneBouncerNetwork: (String, String) -> Unit,
    onActionTaken: () -> Unit,
    modifier: Modifier = Modifier,
    connectModifier: Modifier = Modifier,
) {
    val conn = state.connections[profile.id]
    val isConn = conn?.connected == true
    val isConnecting = conn?.connecting == true
    val status = conn?.status ?: stringResource(R.string.networks_disconnect)
    val index = orderedIds.indexOf(profile.id)

    // Bouncer discovery only applies to a live root bouncer profile: an imported
    // per-upstream clone binds to one upstream and never sees the discovery list.
    val showBouncer = (profile.bouncerKind == BouncerKind.SOJU || profile.bouncerKind == BouncerKind.ZNC) &&
        profile.bouncerNetworkName.isNullOrBlank() &&
        isConn

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(profile.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(connected = isConn, connecting = isConnecting)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Text(
            "${profile.host}:${profile.port}  •  TLS ${if (profile.useTls) "on" else "off"}  •  " +
                "${profile.nick}${profile.altNick?.let { " / $it" } ?: ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val connectMod = connectModifier
            .fillMaxWidth()
            .focusHighlight(RoundedCornerShape(50))

        if (isConn || isConnecting) {
            FilledTonalButton(
                onClick = { onSelect(profile.id); onDisconnect(profile.id); onActionTaken() },
                modifier = connectMod,
            ) { Text(stringResource(R.string.networks_disconnect)) }
        } else {
            Button(
                onClick = { onSelect(profile.id); onConnect(profile.id); onActionTaken() },
                modifier = connectMod,
            ) { Text(stringResource(R.string.networks_connect)) }
        }

        OutlinedButton(
            onClick = { onEdit(profile.id); onActionTaken() },
            modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(50)),
        ) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.networks_edit))
        }

        HorizontalDivider()

        // A switch rather than a button: the button label ran to "Remove from
        // favourites", which had nowhere to go on a phone and clipped mid-word.
        DetailSwitch(
            label = stringResource(R.string.networks_favourite),
            checked = profile.isFavourite,
            onCheckedChange = { onToggleFavourite(profile.id) },
        )
        DetailSwitch(
            label = stringResource(R.string.networks_auto_connect),
            checked = profile.autoConnect,
            onCheckedChange = { onSetAutoConnect(profile.id, it) },
        )
        DetailSwitch(
            label = stringResource(R.string.network_show_in_switcher_label),
            checked = profile.showInSidebar,
            onCheckedChange = { onSetShowInSidebar(profile.id, it) },
        )

        HorizontalDivider()

        // Reorder without dragging, which is the only way to do it from a remote.
        Text(stringResource(R.string.networks_order), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { if (index > 0) onReorder(index, index - 1) },
                enabled = index > 0,
                modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50)),
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.networks_move_up))
            }
            OutlinedButton(
                onClick = { if (index >= 0 && index < orderedIds.lastIndex) onReorder(index, index + 1) },
                enabled = index >= 0 && index < orderedIds.lastIndex,
                modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50)),
            ) {
                Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.networks_move_down))
            }
        }

        if (showBouncer) {
            HorizontalDivider()
            BouncerNetworksSection(
                parentNetworkName = profile.name,
                parentHost = profile.host,
                parentPort = profile.port,
                parentKind = profile.bouncerKind,
                upstreams = state.bouncerNetworks[profile.id] ?: emptyMap(),
                existingProfiles = state.networks,
                onRefresh = { onRefreshBouncerNetworks(profile.id) },
                onClone = { upstreamName -> onCloneBouncerNetwork(profile.id, upstreamName) },
            )
        }

        HorizontalDivider()

        OutlinedButton(
            onClick = { onDelete(profile.id); onActionTaken() },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(50)),
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.delete))
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun NetworksScreen(
    state: UiState,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSetAutoConnect: (String, Boolean) -> Unit,
    onSetShowInSidebar: (String, Boolean) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onAllowPlaintextConnect: (String) -> Unit,
    onDismissPlaintextWarning: () -> Unit,
    onRequestLocalNetworkPermission: (String) -> Unit = {},
    onDismissLocalNetworkWarning: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onToggleFavourite: (String) -> Unit = {},
    /** Re-request the bouncer's upstream-network list (sends BOUNCER LISTNETWORKS). */
    onRefreshBouncerNetworks: (parentNetId: String) -> Unit = {},
    /** Clone a discovered upstream into a new local profile bound to that upstream. */
    onCloneBouncerNetwork: (parentNetId: String, bouncerNetworkName: String) -> Unit = { _, _ -> },
    /** Acknowledge the transient clone-result message so the snackbar can dismiss. */
    onDismissBouncerCloneMessage: () -> Unit = {},
    tourActive: Boolean = false,
    tourTarget: TourTarget? = null,
) {
    val active = state.activeNetworkId

    val listState = rememberLazyListState()
    // Rows are keyed by network id, so the key is the entry id the drag engine needs.
    val reorder = rememberGroupReorderState(listState) { key -> key as? String }

    // Sort: favourites first, then by sortOrder, then alphabetically
    val naturalNetworks = state.networks
        .sortedWith(compareBy({ !it.isFavourite }, { it.sortOrder }, { it.name }))
    val naturalIds = naturalNetworks.map { it.id }

    // While a drag is in progress the list follows the preview order instead of the
    // stored one, so the row under the finger is the only thing that moves with it.
    val sortedNetworks = remember(naturalNetworks, reorder.previewOrder) {
        val preview = reorder.previewOrder
        if (preview == null) {
            naturalNetworks
        } else {
            val byId = naturalNetworks.associateBy { it.id }
            preview.mapNotNull { byId[it] } + naturalNetworks.filter { it.id !in preview }
        }
    }
    val orderedIds = sortedNetworks.map { it.id }

    // Values the drag gesture reads. The gesture modifier outlives a recomposition, so
    // it must not capture the lists directly or it would act on a stale order.
    val currentOrder by rememberUpdatedState(orderedIds)
    val currentNaturalIds by rememberUpdatedState(naturalIds)
    val favouriteById by rememberUpdatedState(state.networks.associate { it.id to it.isFavourite })

    // Hold the dropped order on screen until the saved order matches it, otherwise the
    // list flicks back to the old positions for the frame before the save lands.
    LaunchedEffect(naturalIds, reorder.previewOrder, reorder.draggedId) {
        if (reorder.draggedId != null) return@LaunchedEffect
        val preview = reorder.previewOrder ?: return@LaunchedEffect
        if (naturalIds == preview) {
            reorder.clearPreview()
        } else {
            delay(1000)
            reorder.clearPreview()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // Two panes on TV and tablets: names on the left, everything you can do to the
    // highlighted network on the right. Phones keep a plain list and open the same
    // detail content as a sheet.
    val railLayout = useSideRailNav()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetId by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState()

    val detailProfile = sortedNetworks.firstOrNull { it.id == selectedId }
        ?: sortedNetworks.firstOrNull { it.id == active }
        ?: sortedNetworks.firstOrNull()
    val sheetProfile = sortedNetworks.firstOrNull { it.id == sheetId }

    BackHandler(enabled = sheetId != null) { sheetId = null }

    // Surface bouncer-clone results as a snackbar. The viewmodel sets
    // bouncerCloneMessage on success / failure / "already imported"; we show it
    // once and then clear so re-navigating doesn't re-trigger. LaunchedEffect is
    // already a coroutine scope, so showSnackbar can be called directly — no
    // nested launch (which would otherwise survive the LaunchedEffect's cancel
    // and double-fire if the message changes mid-display).
    //
    // The clear is in `finally` so it runs even when the effect is cancelled
    // mid-display by navigation away or by a fresh message replacing the key.
    LaunchedEffect(state.bouncerCloneMessage) {
        val msg = state.bouncerCloneMessage ?: return@LaunchedEffect
        try {
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
        } finally {
            onDismissBouncerCloneMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.networks_title)) },
                navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.focusHighlight()) { Text("←") } },
                actions = {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.tourTarget(TourTarget.NETWORKS_SETTINGS).focusHighlight()
                    ) { Text("⚙") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .tourTarget(TourTarget.NETWORKS_ADD_FAB)
                    .focusHighlight(RoundedCornerShape(16.dp))
                    .then(if (sortedNetworks.isEmpty()) Modifier.tvInitialFocus() else Modifier)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.networks_add),
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        // Tour: scroll AfterNET into view when highlighted
        LaunchedEffect(tourActive, tourTarget, state.networks) {
            if (!tourActive) return@LaunchedEffect
            if (tourTarget == TourTarget.NETWORKS_AFTERNET_ITEM ||
                tourTarget == TourTarget.NETWORKS_CONNECT_BUTTON
            ) {
                val idx = sortedNetworks.indexOfFirst {
                    it.id.equals("AfterNET", ignoreCase = true) ||
                    it.name.equals("AfterNET", ignoreCase = true)
                }
                if (idx >= 0) {
                    runCatching { listState.animateScrollToItem(idx) }
                    // The connect button lives in the detail pane, so the tour step
                    // only has a target once that network is the selected one.
                    selectedId = sortedNetworks[idx].id
                }
            }
        }

        Row(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                Modifier
                    .then(if (railLayout) Modifier.width(360.dp) else Modifier.weight(1f))
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                if (sortedNetworks.isEmpty()) {
                    // Previously an empty screen with an unlabelled "+" in the corner.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.networks_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.networks_empty_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                if (railLayout && sortedNetworks.isNotEmpty()) {
                    // A remote can reach the FAB, but a labelled button at the top of
                    // the list is the first thing a D-pad lands on.
                    OutlinedButton(
                        onClick = onAdd,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .focusHighlight(RoundedCornerShape(50)),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.networks_add))
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 96.dp)
                ) {
                    itemsIndexed(sortedNetworks, key = { _, n -> n.id }) { idx, n ->
                        val conn = state.connections[n.id]
                        val isConn = conn?.connected == true
                        val isConnecting = conn?.connecting == true
                        val status = conn?.status ?: stringResource(R.string.networks_disconnect)

                        val isAfterNet = n.id.equals("AfterNET", ignoreCase = true) ||
                                         n.name.equals("AfterNET", ignoreCase = true)

                        val isDragging = reorder.isDragging(n.id)

                        val iconUrl = state.connections[n.id]?.networkIconUrl
                            ?.replace("{size}", "64")
                            ?.takeIf {
                                it.startsWith("https://") &&
                                    state.settings.imagePreviewsEnabled &&
                                    n.proxyType == com.boxlabs.hexdroid.connection.ProxyType.NONE
                            }

                        Box(
                            modifier = Modifier
                                // The dragged row is placed by the finger, so it opts out
                                // of the item animation the other rows use to slide aside.
                                .then(if (isDragging) Modifier else Modifier.animateItem())
                                .graphicsLayer { translationY = if (isDragging) reorder.translation else 0f }
                                .zIndex(if (isDragging) 1f else 0f)
                        ) {
                            NetworkRow(
                                profile = n,
                                connected = isConn,
                                connecting = isConnecting,
                                status = status,
                                isSelected = railLayout && n.id == detailProfile?.id,
                                iconUrl = iconUrl,
                                onClick = {
                                    selectedId = n.id
                                    if (!railLayout) sheetId = n.id
                                },
                                onConnect = { onSelect(n.id); onConnect(n.id) },
                                onDisconnect = { onSelect(n.id); onDisconnect(n.id) },
                                onSetAutoConnect = { onSetAutoConnect(n.id, it) },
                                onSetShowInSidebar = { onSetShowInSidebar(n.id, it) },
                                showControls = !railLayout,
                                // Beside a detail pane the connect button lives there, so
                                // the tour target follows it rather than being claimed twice.
                                connectModifier = if (!railLayout && n.id == active) {
                                    Modifier.tourTarget(TourTarget.NETWORKS_CONNECT_BUTTON)
                                } else {
                                    Modifier
                                },
                                modifier = (if (isAfterNet) {
                                    Modifier.tourTarget(TourTarget.NETWORKS_AFTERNET_ITEM)
                                } else {
                                    Modifier
                                })
                                    .then(if (idx == 0) Modifier.tvInitialFocus() else Modifier)
                                    .then(if (isDragging) Modifier.shadow(8.dp) else Modifier),
                                // Touch reorder lives on its own handle so the row body
                                // stays a single focus stop for a remote. The detail
                                // pane's move buttons are the D-pad equivalent.
                                dragHandle = if (railLayout) null else ({
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .pointerInput(n.id) {
                                                var accumulated = 0f
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        accumulated = 0f
                                                        reorder.start(n.id, currentOrder)
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        accumulated += dragAmount.y
                                                        reorder.drag(accumulated) { movedId, otherId ->
                                                            // Favourites always sort above the
                                                            // rest, so a drag across that line
                                                            // would be undone by the next sort.
                                                            favouriteById[movedId] == favouriteById[otherId]
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        val dropped = reorder.end()
                                                        if (dropped != null) {
                                                            reorderIndices(currentNaturalIds, dropped, n.id)
                                                                ?.let { (from, to) -> onReorder(from, to) }
                                                        }
                                                    },
                                                    onDragCancel = { reorder.cancel() },
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Default.DragHandle,
                                            contentDescription = stringResource(R.string.networks_drag_reorder),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }),
                            )
                        }
                    }
                }
            }

            if (railLayout) {
                VerticalDivider()
                if (detailProfile != null) {
                    NetworkDetailPane(
                        state = state,
                        profile = detailProfile,
                        orderedIds = orderedIds,
                        onSelect = onSelect,
                        onEdit = onEdit,
                        onDelete = onDelete,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        onSetAutoConnect = onSetAutoConnect,
                        onSetShowInSidebar = onSetShowInSidebar,
                        onToggleFavourite = onToggleFavourite,
                        onReorder = onReorder,
                        onRefreshBouncerNetworks = onRefreshBouncerNetworks,
                        onCloneBouncerNetwork = onCloneBouncerNetwork,
                        onActionTaken = { },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        connectModifier = if (detailProfile.id == active) {
                            Modifier.tourTarget(TourTarget.NETWORKS_CONNECT_BUTTON)
                        } else {
                            Modifier
                        },
                    )
                } else {
                    Box(
                        Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.networks_empty_detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (!railLayout && sheetProfile != null) {
        ModalBottomSheet(
            onDismissRequest = { sheetId = null },
            sheetState = sheetState,
            contentWindowInsets = { WindowInsets(0) },
        ) {
            NetworkDetailPane(
                state = state,
                profile = sheetProfile,
                orderedIds = orderedIds,
                onSelect = onSelect,
                onEdit = onEdit,
                onDelete = onDelete,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onSetAutoConnect = onSetAutoConnect,
                onSetShowInSidebar = onSetShowInSidebar,
                onToggleFavourite = onToggleFavourite,
                onReorder = onReorder,
                onRefreshBouncerNetworks = onRefreshBouncerNetworks,
                onCloneBouncerNetwork = onCloneBouncerNetwork,
                onActionTaken = { sheetId = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
            )
        }
    }

    // Warn when user attempts to connect without TLS and without explicit opt-in.
    val warnNetId = state.plaintextWarningNetworkId
    if (warnNetId != null) {
        val prof = state.networks.firstOrNull { it.id == warnNetId }
        val hostPort = if (prof != null) "${prof.host}:${prof.port}" else stringResource(R.string.net_this_network)
        AlertDialog(
            onDismissRequest = onDismissPlaintextWarning,
            title = { Text(stringResource(R.string.networks_insecure_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.networks_insecure_body))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.networks_insecure_body2, hostPort))
                }
            },
            confirmButton = {
                TextButton(onClick = { onAllowPlaintextConnect(warnNetId) }, modifier = Modifier.tvInitialFocus().focusHighlight(RoundedCornerShape(50))) {
                    Text(stringResource(R.string.networks_allow_connect))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onEdit(warnNetId); onDismissPlaintextWarning() }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.network_edit_title)) }
                    TextButton(onClick = onDismissPlaintextWarning, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.cancel)) }
                }
            }
        )
    }

    // Android 17+: warn when a connection to a local IP is blocked due to missing
    // ACCESS_LOCAL_NETWORK permission. The user must grant it before retrying.
    val localWarnNetId = state.localNetworkWarningNetworkId
    if (localWarnNetId != null) {
        val prof = state.networks.firstOrNull { it.id == localWarnNetId }
        val hostPort = if (prof != null) "${prof.host}:${prof.port}" else stringResource(R.string.net_this_network)
        AlertDialog(
            onDismissRequest = onDismissLocalNetworkWarning,
            title = { Text(stringResource(R.string.networks_local_title)) },
            text = { Text(stringResource(R.string.networks_local_body, hostPort)) },
            confirmButton = {
                TextButton(onClick = { onRequestLocalNetworkPermission(localWarnNetId) }, modifier = Modifier.tvInitialFocus().focusHighlight(RoundedCornerShape(50))) {
                    Text(stringResource(R.string.networks_local_grant))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissLocalNetworkWarning, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * One bouncer multinetwork (soju) section per live SOJU profile.
 *
 * Lists every upstream the bouncer has reported via the soju.im/bouncer-networks extension,
 * with name + host + state pill + Import (or "Already imported" if a local profile already
 * targets this upstream on this bouncer host:port).
 *
 * The empty case (cap negotiated but no BOUNCER NETWORK frames received yet) is normal for
 * very fresh connections and for soju versions that send the list lazily — show a hint
 * pointing at the refresh button rather than hiding the section, so the user knows the
 * machinery exists.
 *
 * Idempotency for "already imported": the predicate must match cloneBouncerNetwork's own
 * dedupe predicate exactly (host + port + bouncerNetworkName, scoped to SOJU profiles),
 * otherwise the button label and the action's behaviour can disagree.
 */
@Composable
private fun BouncerNetworksSection(
    parentNetworkName: String,
    parentHost: String,
    parentPort: Int,
    parentKind: BouncerKind,
    upstreams: Map<String, BouncerUpstreamInfo>,
    existingProfiles: List<NetworkProfile>,
    onRefresh: () -> Unit,
    onClone: (bouncerNetworkName: String) -> Unit,
) {
    // Stable display order: by name (case-insensitive) then by id, so re-renders don't
    // reshuffle entries when the bouncer re-emits push frames in a different order.
    //
    // Treat blank names the same way the dedupe predicate below does (.takeIf
    // { isNotBlank() }) so the two definitions of "no name" stay consistent - otherwise
    // an upstream with name = "" sorts under the empty-string key (first alphabetically)
    // but renders via the unnamed-string-resource fallback, making blank-named entries
    // cluster at the top of the list for unrelated reasons. lowercase(Locale.ROOT) for
    // locale-independent ordering: lowercase() defers to default locale, which on a
    // Turkish locale folds "I" → "ı" and produces user-surprising ordering that depends
    // on the OS language setting.
    val sortedUpstreams = upstreams.values.sortedWith(
        compareBy(
            { (it.name?.takeIf { n -> n.isNotBlank() } ?: it.id).lowercase(java.util.Locale.ROOT) },
            { it.id }
        )
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.bouncer_networks_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.bouncer_networks_section_subtitle, parentNetworkName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh, modifier = Modifier.focusHighlight()) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.bouncer_networks_refresh)
                    )
                }
            }

            if (sortedUpstreams.isEmpty()) {
                Text(
                    stringResource(R.string.bouncer_networks_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                sortedUpstreams.forEach { upstream ->
                    // Compute "already imported" only when the upstream actually has a name —
                    // a nameless upstream can't be cloned (cloneBouncerNetwork rejects empty
                    // bouncerNetworkName) and there's no meaningful identity to dedupe against.
                    // Without this gate, two nameless upstreams would compare equal via
                    // String?.equals(null, null) = true and falsely show "Already imported".
                    //
                    // The dedupe matches cloneBouncerNetwork's idempotency predicate exactly:
                    // host + port + bouncerKind + bouncerNetworkName. Scoping by parentKind
                    // means a soju-imported "libera" and a ZNC-imported "libera" on the same
                    // bouncer host (rare but possible during migrations) are treated as
                    // distinct profiles, which is correct.
                    val upstreamName = upstream.name?.takeIf { it.isNotBlank() }
                    val alreadyImported = upstreamName != null && existingProfiles.any {
                        it.bouncerKind == parentKind &&
                            it.host.equals(parentHost, ignoreCase = true) &&
                            it.port == parentPort &&
                            it.bouncerNetworkName.equals(upstreamName, ignoreCase = true)
                    }
                    BouncerUpstreamRow(
                        upstream = upstream,
                        alreadyImported = alreadyImported,
                        onClone = {
                            // Pass the bouncer-reported name; the upstream id is opaque and
                            // not what soju/ZNC expect on the client→bouncer authcid suffix.
                            if (upstreamName != null) onClone(upstreamName)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BouncerUpstreamRow(
    upstream: BouncerUpstreamInfo,
    alreadyImported: Boolean,
    onClone: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                upstream.name?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.bouncer_networks_unnamed),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            if (!upstream.host.isNullOrBlank()) {
                Text(
                    upstream.host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        BouncerStatePill(state = upstream.state)
        // The clone action requires a non-empty bouncer-reported name (it becomes
        // bouncerNetworkName on the cloned profile and the soju authcid suffix). When
        // soju reports an unnamed upstream, rare but possible during a transient
        // BOUNCER ADDNETWORK race. disable the button to surface the constraint.
        val canClone = !upstream.name.isNullOrBlank()
        if (alreadyImported) {
            Text(
                stringResource(R.string.bouncer_networks_already_imported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            OutlinedButton(onClick = onClone, enabled = canClone, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                Text(stringResource(R.string.bouncer_networks_import))
            }
        }
    }
}

/**
 * Coloured pill rendering of an upstream's connection state. The colour mapping mirrors
 * what users expect from familiar dashboards: green = up, amber = working, grey = down,
 * neutral = unknown / not yet announced. We deliberately don't use Material's error red
 * for "disconnected" because the bouncer being intentionally detached from an upstream
 * is normal operation, not an error.
 */
@Composable
private fun BouncerStatePill(state: String?) {
    val (label, bg, fg) = when (state?.lowercase()) {
        "connected" -> Triple(
            stringResource(R.string.bouncer_networks_state_connected),
            Color(0xFF1B5E20),
            Color.White
        )
        "connecting" -> Triple(
            stringResource(R.string.bouncer_networks_state_connecting),
            Color(0xFFF9A825),
            Color.Black
        )
        "disconnected" -> Triple(
            stringResource(R.string.bouncer_networks_state_disconnected),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> Triple(
            stringResource(R.string.bouncer_networks_state_unknown),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}
