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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.R
import java.util.Locale

/** Colour of the presence dot for someone MONITOR reports online. */
internal val PresenceOnline = Color(0xFF3FB950)

/**
 * A presence dot: filled green when online, filled grey when offline, hollow when unknown.
 * [describe] gives it a spoken status where no text beside it says the same.
 */
@Composable
internal fun PresenceDot(online: Boolean?, modifier: Modifier = Modifier, describe: Boolean = false) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val status = stringResource(
        when (online) {
            true -> R.string.notify_online
            false -> R.string.notify_offline
            null -> R.string.notify_unknown
        }
    )
    Box(
        modifier
            .then(if (describe) Modifier.semantics { contentDescription = status } else Modifier)
            .size(8.dp)
            .clip(CircleShape)
            .then(
                when (online) {
                    true -> Modifier.background(PresenceOnline)
                    false -> Modifier.background(outline.copy(alpha = 0.45f))
                    null -> Modifier.border(1.dp, outline.copy(alpha = 0.6f), CircleShape)
                }
            )
    )
}

/**
 * The notify list of one network: who is watched with MONITOR, whether each is online, and
 * fields to add and remove nicks.
 */
@Composable
fun NotifyListDialog(
    networkName: String,
    nicks: List<String>,
    online: Set<String>,
    offline: Set<String>,
    connected: Boolean,
    monitorAvailable: Boolean?,
    alertsOn: Boolean,
    onToggleAlerts: (Boolean) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onOpenQuery: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newNick by remember { mutableStateOf("") }
    fun add() {
        val n = newNick.trim()
        if (n.isNotEmpty() && ' ' !in n && ',' !in n) {
            onAdd(n)
            newNick = ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notify_list_title, networkName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.notify_list_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (monitorAvailable == false) {
                    Text(
                        stringResource(R.string.notify_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newNick,
                        onValueChange = { newNick = it.replace(" ", "") },
                        singleLine = true,
                        label = { Text(stringResource(R.string.notify_add_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { add() },
                        enabled = newNick.isNotBlank(),
                        modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                    ) { Text(stringResource(R.string.notify_add)) }
                }
                if (nicks.isEmpty()) {
                    Text(
                        stringResource(R.string.notify_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(nicks.distinctBy { it.lowercase(Locale.ROOT) }, key = { it.lowercase(Locale.ROOT) }) { nick ->
                            val fold = nick.lowercase(Locale.ROOT)
                            val state: Boolean? = when {
                                !connected -> null
                                fold in online -> true
                                fold in offline -> false
                                else -> null
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .focusHighlight(RoundedCornerShape(8.dp))
                                    .clickable { onOpenQuery(nick) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                PresenceDot(state)
                                Column(Modifier.weight(1f)) {
                                    Text(nick, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        when (state) {
                                            true -> stringResource(R.string.notify_online)
                                            false -> stringResource(R.string.notify_offline)
                                            null -> stringResource(R.string.notify_unknown)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = { onRemove(nick) },
                                    modifier = Modifier.size(32.dp).focusHighlight(CircleShape),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.notify_remove, nick),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.setting_notify_list_alerts),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = alertsOn,
                        onCheckedChange = onToggleAlerts,
                        modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.tvInitialFocus().focusHighlight(RoundedCornerShape(50))) {
                Text(stringResource(R.string.close))
            }
        },
    )
}
