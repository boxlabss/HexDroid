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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.PasteChoice
import com.boxlabs.hexdroid.PasteGuardRequest
import com.boxlabs.hexdroid.PendingShare
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.UiState
import com.boxlabs.hexdroid.filehostUsable
import com.boxlabs.hexdroid.stripIrcFormatting

/**
 * Asks how to send text that would go out as many lines: as they are, joined into one, uploaded
 * as a paste with only its link sent, or not at all (the text goes back to the message box).
 */
@Composable
fun PasteGuardDialog(req: PasteGuardRequest, onChoice: (PasteChoice) -> Unit) {
    val preview = remember(req.text) {
        req.text.lines().filter { it.isNotBlank() }.take(4).joinToString("\n") { stripIrcFormatting(it).take(120) }
    }
    val seconds = ((req.waitMs + 999) / 1000).toInt()
    AlertDialog(
        onDismissRequest = { if (!req.uploading) onChoice(PasteChoice.CANCEL) },
        title = { Text(pluralStringResource(R.plurals.paste_guard_title, req.lines, req.lines)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when {
                        req.batched -> stringResource(R.string.paste_guard_batched)
                        seconds >= 5 -> pluralStringResource(R.plurals.paste_guard_slow, seconds, seconds)
                        else -> stringResource(R.string.paste_guard_body)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (req.uploading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.paste_uploading), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    if (req.canUpload) {
                        Button(
                            onClick = { onChoice(PasteChoice.UPLOAD) },
                            modifier = Modifier.fillMaxWidth().tvInitialFocus().focusHighlight(RoundedCornerShape(50)),
                        ) { Text(stringResource(R.string.paste_upload)) }
                    }
                    OutlinedButton(
                        onClick = { onChoice(PasteChoice.SEND_LINES) },
                        modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(50)),
                    ) { Text(pluralStringResource(R.plurals.paste_send_lines, req.lines, req.lines)) }
                    OutlinedButton(
                        onClick = { onChoice(PasteChoice.ONE_LINE) },
                        modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(50)),
                    ) { Text(stringResource(R.string.paste_one_line)) }
                    if (req.suggestPasteService) {
                        Text(
                            stringResource(R.string.paste_no_upload_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onChoice(PasteChoice.CANCEL) },
                enabled = !req.uploading,
                modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
            ) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Picks the conversation something shared from another app goes to. The open conversation is
 * listed first. Files need the network's upload service, so others are marked.
 */
@Composable
fun ShareTargetDialog(
    state: UiState,
    share: PendingShare,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val netNames = remember(state.networks) { state.networks.associate { it.id to it.name } }
    val order = remember(state.networks) {
        state.networks.sortedWith(compareBy({ !it.isFavourite }, { it.sortOrder }, { it.name }))
            .mapIndexed { i, n -> n.id to i }.toMap()
    }
    val targets = remember(state.buffers.keys, state.selectedBuffer, order) {
        state.buffers.keys
            .filter { k ->
                val name = k.substringAfter("::", "")
                name.isNotBlank() && !name.startsWith("*") && !name.startsWith("DCCCHAT:")
            }
            .sortedWith(
                compareBy<String>({ it != state.selectedBuffer }, { order[it.substringBefore("::")] ?: Int.MAX_VALUE }, { it.substringAfter("::").lowercase() })
            )
    }
    val summary = when {
        share.uris.isNotEmpty() -> pluralStringResource(R.plurals.share_files, share.uris.size, share.uris.size)
        else -> share.text.orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_to_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (targets.isEmpty()) {
                    Text(stringResource(R.string.share_no_targets), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(targets, key = { it }) { key ->
                            val netId = key.substringBefore("::")
                            val name = key.substringAfter("::")
                            val noUpload = share.uris.isNotEmpty() && !filehostUsable(state, netId)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .focusHighlight(RoundedCornerShape(8.dp))
                                    .clickable { onPick(key) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (noUpload) "${netNames[netId] ?: netId} · ${stringResource(R.string.share_no_upload_short)}"
                                    else netNames[netId] ?: netId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
