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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
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
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onToggleFavourite: (String) -> Unit = {},
    tourActive: Boolean = false,
    tourTarget: TourTarget? = null,
) {
    val active = state.activeNetworkId

    // Sort: favourites first, then by sortOrder, then alphabetically
    val sortedNetworks = state.networks
        .sortedWith(compareBy({ !it.isFavourite }, { it.sortOrder }, { it.name }))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Networks") },
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
        }
    ) { padding ->
        val listState = rememberLazyListState()

        // Drag state keyed by netId — avoids index/favourites mapping issues
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
                    val status = conn?.status ?: "Disconnected"

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
                                                contentDescription = "Remove from favourites",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        } else {
                                            Icon(
                                                Icons.Outlined.StarOutline,
                                                contentDescription = "Add to favourites",
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
                                    if (n.id == active) Badge { Text("Selected") }

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
                                            contentDescription = "Drag to reorder",
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

                                    val connectMod = if (n.id == active) {
                                        Modifier.tourTarget(TourTarget.NETWORKS_CONNECT_BUTTON)
                                    } else Modifier

                                    if (isConn || isConnecting) {
                                        Button(
                                            onClick = { onSelect(n.id); onDisconnect(n.id) },
                                            modifier = connectMod
                                        ) { Text("Disconnect") }
                                    } else {
                                        Button(
                                            onClick = { onSelect(n.id); onConnect(n.id) },
                                            modifier = connectMod
                                        ) { Text("Connect") }
                                    }
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
