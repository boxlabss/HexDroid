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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.data.ChannelPrefs
import com.boxlabs.hexdroid.data.EventPrefs

/**
 * Settings for one channel or query: muting its notifications, extra highlight words, and (for a
 * channel) its own event visibility. Changes apply on Save.
 */
@Composable
fun ChannelSettingsDialog(
    name: String,
    isChannel: Boolean,
    prefs: ChannelPrefs,
    globalEvents: EventPrefs,
    onSave: (ChannelPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    var muted by remember { mutableStateOf(prefs.muted) }
    var mutedUntil by remember { mutableStateOf(prefs.mutedUntilMs.takeIf { it > System.currentTimeMillis() } ?: 0L) }
    var words by remember { mutableStateOf(prefs.highlightWords.joinToString(", ")) }
    var custom by remember { mutableStateOf(prefs.events != null) }
    var events by remember { mutableStateOf(prefs.events ?: globalEvents) }

    fun current() = ChannelPrefs(
        muted = muted,
        highlightWords = words.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        events = if (isChannel && custom) events else null,
        mutedUntilMs = mutedUntil,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_settings_title, name)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.channel_settings_mute))
                        Text(
                            stringResource(R.string.channel_settings_mute_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = muted,
                        onCheckedChange = { muted = it },
                        modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
                    )
                }
                if (mutedUntil > 0L && !muted) {
                    val untilText = remember(mutedUntil) {
                        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(mutedUntil))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.channel_settings_muted_until, untilText),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { mutedUntil = 0L },
                            modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                        ) { Text(stringResource(R.string.channel_settings_unmute_now)) }
                    }
                }
                if (isChannel) {
                    OutlinedTextField(
                        value = words,
                        onValueChange = { words = it },
                        label = { Text(stringResource(R.string.channel_settings_words)) },
                        supportingText = { Text(stringResource(R.string.channel_settings_words_desc)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HorizontalDivider()
                    Text(stringResource(R.string.setting_channel_events), style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(stringResource(R.string.channel_settings_events_follow), selected = !custom) { custom = false }
                    ChoiceRow(stringResource(R.string.channel_settings_events_custom), selected = custom) {
                        if (!custom) events = globalEvents
                        custom = true
                    }
                    if (custom) {
                        ChannelEventChips(
                            prefs = events,
                            onChange = { transform -> events = transform(events) },
                            title = stringResource(R.string.channel_settings_events_custom),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(current()) }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                if (!prefs.copy(syncedMute = false).isDefault) {
                    TextButton(
                        onClick = { onSave(ChannelPrefs()) },
                        modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                    ) { Text(stringResource(R.string.channel_settings_reset)) }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .focusHighlight(RoundedCornerShape(8.dp))
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
