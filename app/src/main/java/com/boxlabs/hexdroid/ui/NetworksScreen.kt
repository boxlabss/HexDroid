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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.BouncerKind
import com.boxlabs.hexdroid.BouncerUpstreamInfo
import com.boxlabs.hexdroid.UiState
import androidx.compose.ui.res.stringResource
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.data.NetworkProfile

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

    // Sort: favourites first, then by sortOrder, then alphabetically
    val sortedNetworks = state.networks
        .sortedWith(compareBy({ !it.isFavourite }, { it.sortOrder }, { it.name }))

    val snackbarHostState = remember { SnackbarHostState() }

    // Surface bouncer-clone results as a snackbar. The viewmodel sets
    // bouncerCloneMessage on success / failure / "already imported"; we show it
    // once and then clear so re-navigating doesn't re-trigger. LaunchedEffect is
    // already a coroutine scope, so showSnackbar can be called directly — no
    // nested launch (which would otherwise survive the LaunchedEffect's cancel
    // and double-fire if the message changes mid-display).
    //
    // The clear is in `finally` so it runs even when the effect is cancelled
    // mid-display by navigation away or by a fresh message replacing the key.
    // Without that, navigating off NetworksScreen while a snackbar is up would
    // leave state.bouncerCloneMessage non-null, and the snackbar would re-appear
    // every time the user returned to the screen until the next clone message
    // happened to land. onDismissBouncerCloneMessage() ultimately calls a
    // non-suspending _state.update, so finally is safe to run during cancellation.
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
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
                actions = {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.tourTarget(TourTarget.NETWORKS_SETTINGS)
                    ) { Text("⚙") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.tourTarget(TourTarget.NETWORKS_ADD_FAB)
            ) { Text("+") }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val listState = rememberLazyListState()

        // Drag state keyed by netId - avoids index/favourites mapping issues
        var dragFromId  by remember { mutableStateOf<String?>(null) }
        var dragToId    by remember { mutableStateOf<String?>(null) }
        var dragOffsetY by remember { mutableFloatStateOf(0f) }
        val naturalTops = remember { mutableMapOf<String, Float>() }  // netId -> natural top Y (not updated during drag)
        val itemHeights = remember { mutableMapOf<String, Float>() }  // netId -> height

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
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 2.dp, bottom = 96.dp)
            ) {
                itemsIndexed(sortedNetworks, key = { _, n -> n.id }) { idx, n ->
                    val conn = state.connections[n.id]
                    val isConn = conn?.connected == true
                    val isConnecting = conn?.connecting == true
                    val status = conn?.status ?: stringResource(R.string.networks_disconnect)

                    val isAfterNet = n.id.equals("AfterNET", ignoreCase = true) ||
                                     n.name.equals("AfterNET", ignoreCase = true)

                    val isDragging = dragFromId == n.id
                    val draggedHeight = itemHeights[dragFromId] ?: 200f
                    val fromIdx = sortedNetworks.indexOfFirst { it.id == dragFromId }
                    val toIdx   = sortedNetworks.indexOfFirst { it.id == dragToId }
                    val targetRawOffset: Float = when {
                        isDragging -> dragOffsetY
                        dragFromId != null && n.id != dragFromId -> when {
                            fromIdx < toIdx && idx > fromIdx && idx <= toIdx -> -draggedHeight
                            fromIdx > toIdx && idx < fromIdx && idx >= toIdx ->  draggedHeight
                            else -> 0f
                        }
                        else -> 0f
                    }
                    val animatedOffset by animateFloatAsState(
                        targetValue = targetRawOffset,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "drag_offset_${n.id}"
                    )
                    val visualOffset = if (isDragging) dragOffsetY else animatedOffset

                    val cardMod = if (isAfterNet) {
                        Modifier.fillMaxWidth().tourTarget(TourTarget.NETWORKS_AFTERNET_ITEM)
                    } else {
                        Modifier.fillMaxWidth()
                    }

                    Box(
                        modifier = Modifier
                            .graphicsLayer { translationY = visualOffset }
                            .onGloballyPositioned { coords ->
                                if (dragFromId == null) {
                                    naturalTops[n.id] = coords.positionInParent().y
                                }
                                itemHeights[n.id] = coords.size.height.toFloat()
                            }
                            .zIndex(if (isDragging) 1f else 0f)
                    ) {
                    Card(
                        modifier = cardMod
                            .then(if (isDragging) Modifier.shadow(8.dp) else Modifier),
                        onClick = { onSelect(n.id) }
                    ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Title row: name + favourite star + selected badge + drag handle
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Favourite toggle
                                    IconButton(
                                        onClick = { onToggleFavourite(n.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        if (n.isFavourite) {
                                            Icon(
                                                Icons.Filled.Star,
                                                contentDescription = stringResource(R.string.networks_remove_favourite),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        } else {
                                            Icon(
                                                Icons.Outlined.StarOutline,
                                                contentDescription = stringResource(R.string.networks_add_favourite),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        n.name,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (n.id == active) Badge { Text(stringResource(R.string.networks_selected)) }

                                    // Drag handle with long-press detection
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .pointerInput(n.id) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        dragFromId  = n.id
                                                        dragToId    = n.id
                                                        dragOffsetY = 0f
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        dragOffsetY += dragAmount.y
                                                        val fromNatural = naturalTops[dragFromId] ?: 0f
                                                        val draggedH    = itemHeights[dragFromId] ?: 200f
                                                        val curCentreY  = fromNatural + draggedH / 2f + dragOffsetY
                                                        val snap = naturalTops.entries
                                                            .minByOrNull { (id, top) ->
                                                                val h = itemHeights[id] ?: draggedH
                                                                kotlin.math.abs((top + h / 2f) - curCentreY)
                                                            }?.key
                                                        if (snap != null) dragToId = snap
                                                    },
                                                    onDragEnd = {
                                                        val from = dragFromId
                                                        val to   = dragToId
                                                        if (from != null && to != null && from != to) {
                                                            val fromIdx = sortedNetworks.indexOfFirst { it.id == from }
                                                            val toIdx   = sortedNetworks.indexOfFirst { it.id == to }
                                                            if (fromIdx >= 0 && toIdx >= 0) onReorder(fromIdx, toIdx)
                                                        }
                                                        dragFromId  = null
                                                        dragToId    = null
                                                        dragOffsetY = 0f
                                                    },
                                                    onDragCancel = {
                                                        dragFromId  = null
                                                        dragToId    = null
                                                        dragOffsetY = 0f
                                                    }
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.DragHandle,
                                            contentDescription = stringResource(R.string.networks_drag_reorder),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
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
                                        stringResource(R.string.networks_auto_connect),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = n.autoConnect,
                                        onCheckedChange = { onSetAutoConnect(n.id, it) }
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(R.string.network_show_in_switcher_label),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = n.showInSidebar,
                                        onCheckedChange = { onSetShowInSidebar(n.id, it) }
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
                                    OutlinedButton(onClick = { onEdit(n.id) }) { Text(stringResource(R.string.networks_edit)) }
                                    OutlinedButton(onClick = { onDelete(n.id) }) { Text(stringResource(R.string.delete)) }
                                    Spacer(Modifier.weight(1f))

                                    val connectMod = if (n.id == active) {
                                        Modifier.tourTarget(TourTarget.NETWORKS_CONNECT_BUTTON)
                                    } else Modifier

                                    if (isConn || isConnecting) {
                                        Button(
                                            onClick = { onSelect(n.id); onDisconnect(n.id) },
                                            modifier = connectMod
                                        ) { Text(stringResource(R.string.networks_disconnect)) }
                                    } else {
                                        Button(
                                            onClick = { onSelect(n.id); onConnect(n.id) },
                                            modifier = connectMod
                                        ) { Text(stringResource(R.string.networks_connect)) }
                                    }
                                }
                            }
                        }
                    }
                    }

                    // Bouncer multinetwork (soju + ZNC) sections.
                    //
                    // We render one section per LIVE *root* bouncer-profile connection. A
                    // "root" connection is one that talks to the bouncer without binding to
                    // a specific upstream — i.e. bouncerNetworkName is empty/null. Those are
                    // the ones that see the global BOUNCER NETWORK / ListNetworks output.
                    //
                    // Imported per-upstream clones (e.g. parent="Bouncer" + clone="Bouncer –
                    // libera") are themselves bouncerKind=ZNC/SOJU, but they bind directly to
                    // one upstream via bouncerNetworkName, so they receive their own upstream's
                    // traffic — not the discovery list. Showing a "Bouncer networks" section
                    // for them duplicates the parent's section with the same content.
                    //
                    // Eligibility:
                    //  - bouncerKind ∈ {SOJU, ZNC} (the kinds that expose a discoverable list)
                    //  - bouncerNetworkName blank/null (the root, not an imported clone)
                    //  - connection is currently registered (without a live connection there's
                    //    no current upstream list; cached entries were cleared on disconnect).
                    val bouncerConnections = state.networks.filter { profile ->
                        (profile.bouncerKind == BouncerKind.SOJU || profile.bouncerKind == BouncerKind.ZNC) &&
                            profile.bouncerNetworkName.isNullOrBlank() &&
                            state.connections[profile.id]?.connected == true
                    }
                    items(bouncerConnections, key = { "bouncer-section:${it.id}" }) { profile ->
                        BouncerNetworksSection(
                            parentNetworkName = profile.name,
                            parentHost = profile.host,
                            parentPort = profile.port,
                            parentKind = profile.bouncerKind,
                            upstreams = state.bouncerNetworks[profile.id] ?: emptyMap(),
                            existingProfiles = state.networks,
                            onRefresh = { onRefreshBouncerNetworks(profile.id) },
                            onClone = { upstreamName ->
                                onCloneBouncerNetwork(profile.id, upstreamName)
                            }
                        )
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
            title = { Text(stringResource(R.string.networks_insecure_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.networks_insecure_body))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.networks_insecure_body2, hostPort))
                }
            },
            confirmButton = {
                TextButton(onClick = { onAllowPlaintextConnect(warnNetId) }) {
                    Text(stringResource(R.string.networks_allow_connect))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onEdit(warnNetId); onDismissPlaintextWarning() }) { Text(stringResource(R.string.network_edit_title)) }
                    TextButton(onClick = onDismissPlaintextWarning) { Text(stringResource(R.string.cancel)) }
                }
            }
        )
    }

    // Android 17+: warn when a connection to a local IP is blocked due to missing
    // ACCESS_LOCAL_NETWORK permission. The user must grant it before retrying.
    val localWarnNetId = state.localNetworkWarningNetworkId
    if (localWarnNetId != null) {
        val prof = state.networks.firstOrNull { it.id == localWarnNetId }
        val hostPort = if (prof != null) "${prof.host}:${prof.port}" else "this network"
        AlertDialog(
            onDismissRequest = onDismissLocalNetworkWarning,
            title = { Text(stringResource(R.string.networks_local_title)) },
            text = { Text(stringResource(R.string.networks_local_body, hostPort)) },
            confirmButton = {
                TextButton(onClick = { onRequestLocalNetworkPermission(localWarnNetId) }) {
                    Text(stringResource(R.string.networks_local_grant))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissLocalNetworkWarning) {
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
                IconButton(onClick = onRefresh) {
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
            OutlinedButton(onClick = onClone, enabled = canClone) {
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
