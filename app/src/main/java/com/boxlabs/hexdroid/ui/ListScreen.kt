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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.UiState
import androidx.compose.ui.res.stringResource
import com.boxlabs.hexdroid.R

@Composable
fun ListScreen(
    state: UiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onFilterChange: (String) -> Unit,
    onSortChange: (String) -> Unit,
    onJoin: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val filter = state.listFilter.trim()
    val sort   = state.listSort

    // Local user-count range filter
    var minUsersText by remember { mutableStateOf("") }
    var maxUsersText by remember { mutableStateOf("") }
    val minUsers = minUsersText.trim().toIntOrNull()?.coerceAtLeast(0)
    val maxUsers = maxUsersText.trim().toIntOrNull()?.coerceAtLeast(0)

    // Apply filter then sort.
    // The original key was `remember(state.channelDirectory, ...)`. Because
    // `channelDirectory` is a List<ChannelListEntry>, any state update anywhere in the
    // ViewModel (e.g. a message arriving in another buffer) creates a new list reference
    // even if the channel data hasn't changed, causing an O(n·log n) sort to run on every
    // recomposition. Using the list size as a proxy key means the sort only re-runs when
    // channels are actually added or removed.
    // The filter/sort/range inputs are still included so user-driven changes re-run immediately.
    val directorySize = state.channelDirectory.size
    val items = remember(directorySize, filter, sort, minUsers, maxUsers) {
        val filtered = state.channelDirectory.filter { ch ->
            val nameMatch = filter.isBlank() ||
                ch.channel.contains(filter, ignoreCase = true) ||
                ch.topic.contains(filter, ignoreCase = true)
            val minOk = minUsers == null || ch.users >= minUsers
            val maxOk = maxUsers == null || ch.users <= maxUsers
            nameMatch && minOk && maxOk
        }
        when (sort) {
            "size_asc"  -> filtered.sortedBy   { it.users }
            "name_asc"  -> filtered.sortedBy   { it.channel.lowercase() }
            "name_desc" -> filtered.sortedByDescending { it.channel.lowercase() }
            else        -> filtered.sortedByDescending { it.users }  // size_desc (default)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.list_channels_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
                actions = {
                    IconButton(onClick = onRefresh) { Text("⟳") }
                    IconButton(onClick = onOpenSettings) { Text("⚙") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Search field
            OutlinedTextField(
                value = state.listFilter,
                onValueChange = onFilterChange,
                label = { Text(stringResource(R.string.list_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // User count range filter
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.list_users_filter_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = minUsersText,
                    onValueChange = { if (it.all(Char::isDigit) && it.length <= 6) minUsersText = it },
                    label = { Text(stringResource(R.string.list_users_min)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                )
                Text("–", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = maxUsersText,
                    onValueChange = { if (it.all(Char::isDigit) && it.length <= 6) maxUsersText = it },
                    label = { Text(stringResource(R.string.list_users_max)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                )
            }

            // Sort chips row
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Sort:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SortChip(
                    label = "Size",
                    activeAsc  = sort == "size_asc",
                    activeDesc = sort == "size_desc",
                    onClickDesc = { onSortChange("size_desc") },
                    onClickAsc  = { onSortChange("size_asc")  },
                )
                SortChip(
                    label = "Name",
                    activeAsc  = sort == "name_asc",
                    activeDesc = sort == "name_desc",
                    onClickDesc = { onSortChange("name_desc") },
                    onClickAsc  = { onSortChange("name_asc")  },
                )
            }

            // Status row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.listInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.list_loading, state.channelDirectory.size),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "${items.size} / ${state.channelDirectory.size} channels",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.channel }) { ch ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onJoin(ch.channel) }
                            .padding(vertical = 10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                ch.channel,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${ch.users}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.list_join_button),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (ch.topic.isNotBlank()) {
                            Text(
                                ch.topic,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * A pair of sort controls for one dimension (e.g. "Size").
 *
 * When neither direction is active: shows a single outlined chip labelled with
 * the dimension name — tapping selects descending (the more useful default).
 *
 * When one direction is active: shows a filled chip with an arrow icon.
 * Tapping the active chip toggles to the opposite direction.
 */
@Composable
private fun SortChip(
    label: String,
    activeAsc: Boolean,
    activeDesc: Boolean,
    onClickAsc: () -> Unit,
    onClickDesc: () -> Unit,
) {
    val isActive = activeAsc || activeDesc
    FilterChip(
        selected = isActive,
        onClick = {
            when {
                activeDesc -> onClickAsc()   // flip to ascending
                activeAsc  -> onClickDesc()  // flip to descending
                else       -> onClickDesc()  // first tap → descending
            }
        },
        label = { Text(label) },
        trailingIcon = if (isActive) {
            {
                Icon(
                    imageVector = if (activeDesc) Icons.Default.ArrowDownward
                                  else            Icons.Default.ArrowUpward,
                    contentDescription = if (activeDesc) "Descending" else "Ascending",
                    modifier = Modifier.size(14.dp),
                )
            }
        } else null,
    )
}