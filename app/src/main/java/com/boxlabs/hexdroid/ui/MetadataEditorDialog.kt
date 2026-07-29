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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxlabs.hexdroid.IrcViewModel
import com.boxlabs.hexdroid.R

/**
 * draft/metadata-2 editor for the user's own metadata keys.
 *
 * On open it asks the server to LIST our current metadata (populating
 * NetConnState.ownMetadata), then presents one field per registry-defined user key.
 * Fields seed from the server's values and re-seed on late arrivals only while the
 * user has not diverged from what the server last reported, so an async LIST reply
 * cannot clobber an in-progress edit.
 *
 * Saving diffs each field against the last server value and issues METADATA SET (or
 * a clear, when a field is emptied) only for changed keys.
 */
private data class MetaField(val key: String, val labelRes: Int, val singleLine: Boolean = true)

@Composable
fun MetadataEditorDialog(
    networkId: String,
    viewModel: IrcViewModel,
    onDismiss: () -> Unit,
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val ownMeta = ui.connections[networkId]?.ownMetadata ?: emptyMap()

    // Refresh our own metadata once when the dialog opens.
    LaunchedEffect(networkId) { viewModel.requestOwnMetadata(networkId) }

    val fields = remember {
        listOf(
            MetaField("display-name", R.string.metadata_display_name),
            MetaField("pronouns", R.string.metadata_pronouns),
            MetaField("status", R.string.metadata_status),
            MetaField("bio", R.string.metadata_bio, singleLine = false),
            MetaField("homepage", R.string.metadata_homepage),
            MetaField("color", R.string.metadata_color),
            MetaField("avatar", R.string.metadata_avatar),
        )
    }

    // edited = the current text in each field; serverSeen = the last value the server
    // reported for that key. When a field still matches serverSeen it is "untouched"
    // and may be re-seeded from a fresh server value; once it diverges we leave it.
    val edited = remember { mutableStateMapOf<String, String>() }
    val serverSeen = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(ownMeta) {
        for (f in fields) {
            val serverVal = ownMeta[f.key] ?: ""
            val prevSeen = serverSeen[f.key]
            val cur = edited[f.key]
            if (cur == null || cur == (prevSeen ?: "")) {
                edited[f.key] = serverVal
            }
            serverSeen[f.key] = serverVal
        }
    }

    val colorText = edited["color"]?.trim().orEmpty().removePrefix("#")
    val colorValid = colorText.isEmpty() ||
        (colorText.length == 6 && colorText.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
    val colorPreview: Color? = if (colorText.length == 6 && colorValid)
        runCatching { Color("ff$colorText".toLong(16)) }.getOrNull() else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.metadata_editor_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.metadata_editor_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (f in fields) {
                    if (f.key == "color") {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = edited[f.key] ?: "",
                                onValueChange = { edited[f.key] = it },
                                label = { Text(stringResource(f.labelRes)) },
                                singleLine = true,
                                isError = !colorValid,
                                supportingText = if (!colorValid) {
                                    { Text(stringResource(R.string.metadata_color_hint)) }
                                } else null,
                                modifier = Modifier.weight(1f),
                            )
                            if (colorPreview != null) {
                                Surface(
                                    color = colorPreview,
                                    shape = CircleShape,
                                    modifier = Modifier.size(28.dp),
                                ) { Box(Modifier) }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = edited[f.key] ?: "",
                            onValueChange = { edited[f.key] = it },
                            label = { Text(stringResource(f.labelRes)) },
                            singleLine = f.singleLine,
                            maxLines = if (f.singleLine) 1 else 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Text(
                    stringResource(R.string.metadata_editor_clear_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = colorValid,
                onClick = {
                    for (f in fields) {
                        val newVal = (edited[f.key] ?: "").trim()
                        val oldVal = (serverSeen[f.key] ?: "").trim()
                        if (newVal == oldVal) continue
                        // Empty string clears the key; setOwnMetadata sends SET with no
                        // value, which the server treats as a removal.
                        viewModel.setOwnMetadata(networkId, f.key, newVal.ifBlank { null })
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
