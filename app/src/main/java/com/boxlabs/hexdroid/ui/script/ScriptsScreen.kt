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

package com.boxlabs.hexdroid.ui.script

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import com.boxlabs.hexdroid.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.boxlabs.hexdroid.ui.focusHighlight
import com.boxlabs.hexdroid.ui.tvInitialFocus
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boxlabs.hexdroid.script.ScriptInfo
import com.boxlabs.hexdroid.script.ScriptsUiState

/**
 * The Scripts section: see installed scripts, enable/disable them, watch load status,
 * and add a new one (import a file or paste source). The
 * ViewModel owns the [com.boxlabs.hexdroid.script.ScriptManager] and turns these
 * callbacks into manager calls + a refreshed [ScriptsUiState].
 *
 * Two entry points share the same contract and row rendering:
 * [ScriptsScreen]: full-screen management (Settings > Scripts).
 * [ScriptsDialog]: a compact pop-up for quick toggles from the chat overflow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(
    state: ScriptsUiState,
    onBack: () -> Unit,
    onToggle: (name: String, enabled: Boolean) -> Unit,
    onRemove: (name: String) -> Unit,
    onReloadAll: () -> Unit,
    onImportFile: () -> Unit,
    onPaste: (name: String, source: String) -> Unit,
    onRead: (name: String) -> String? = { null },
    onRevert: (name: String) -> String? = { null },
) {
    var pasting by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf<String?>(null) }

    // Full-screen source editor
    val editing = editingName
    if (editing != null) {
        ScriptEditorScreen(
            name = editing,
            initialSource = onRead(editing) ?: "",
            canRevert = state.scripts.firstOrNull { it.name == editing }?.bundled == true,
            onBack = { editingName = null },
            onSave = { src -> onPaste(editing, src); editingName = null },
            onRevert = { onRevert(editing) },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scripts_title)) },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.tvInitialFocus().focusHighlight(RoundedCornerShape(50)),
                    ) { Text(stringResource(R.string.back)) }
                },
                actions = {
                    TextButton(
                        onClick = onReloadAll,
                        modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                    ) { Text(stringResource(R.string.scripts_reload)) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ScriptsHeader(state)

            if (state.scripts.isEmpty()) {
                ScriptsEmpty()
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.scripts, key = { it.name }) { s -> ScriptRow(s, onToggle, onRemove, onEdit = { editingName = it }) }
                }
            }

            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    { onImportFile() },
                    Modifier.weight(1f).focusHighlight(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.scripts_import_file)) }
                OutlinedButton(
                    { pasting = true },
                    Modifier.weight(1f).focusHighlight(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.scripts_paste)) }
            }
        }
    }

    if (pasting) PasteDialog(state.backendName, onDismiss = { pasting = false }) { name, src ->
        pasting = false; onPaste(name, src)
    }
}

/** Compact pop-up for quick access. */
@Composable
fun ScriptsDialog(
    state: ScriptsUiState,
    onDismiss: () -> Unit,
    onToggle: (name: String, enabled: Boolean) -> Unit,
    onRemove: (name: String) -> Unit,
    onReloadAll: () -> Unit,
    onImportFile: () -> Unit,
    onPaste: (name: String, source: String) -> Unit,
    onRead: (name: String) -> String? = { null },
) {
    var pasting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.scripts_title), Modifier.weight(1f))
                val on = state.scripts.count { it.enabled }
                Text(stringResource(R.string.scripts_enabled_count, on, state.scripts.size), style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.scripts_backend_hint, state.backendName),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.scripts.isEmpty()) {
                    Text(stringResource(R.string.scripts_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.scripts, key = { it.name }) { s -> ScriptRow(s, onToggle, onRemove) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        { onImportFile() },
                        Modifier.weight(1f).focusHighlight(RoundedCornerShape(50)),
                    ) { Text(stringResource(R.string.scripts_import)) }
                    OutlinedButton(
                        { pasting = true },
                        Modifier.weight(1f).focusHighlight(RoundedCornerShape(50)),
                    ) { Text(stringResource(R.string.scripts_paste)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onReloadAll,
                modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
            ) { Text(stringResource(R.string.scripts_reload)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
            ) { Text(stringResource(R.string.close)) }
        },
    )

    if (pasting) PasteDialog(state.backendName, onDismiss = { pasting = false }) { name, src ->
        pasting = false; onPaste(name, src)
    }
}

@Composable
private fun ScriptsHeader(state: ScriptsUiState) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.scripts_title), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.scripts_header_desc, state.backendName),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.scripts.isNotEmpty()) {
                val on = state.scripts.count { it.enabled }
                val bad = state.scripts.count { it.enabled && !it.ok }
                val enabledOf = stringResource(R.string.scripts_enabled_of, on, state.scripts.size)
                val withErrors = if (bad > 0) stringResource(R.string.scripts_with_errors, bad) else ""
                Text(
                    buildString {
                        append(enabledOf)
                        if (bad > 0) append(withErrors)
                    },
                     style = MaterialTheme.typography.labelMedium,
                     color = if (bad > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ScriptsEmpty() {
    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.scripts_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.scripts_none_hint),
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScriptRow(
    s: ScriptInfo,
    onToggle: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onEdit: (String) -> Unit = {},
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.name, fontWeight = FontWeight.SemiBold)
                        ExtBadge(s.ext)
                    }
                    Spacer(Modifier.height(4.dp))
                    StatusPill(s)
                }
                Switch(
                    checked = s.enabled,
                    onCheckedChange = { onToggle(s.name, it) },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
                )
            }
            // Show the load error in full
            if (s.enabled && !s.ok) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        s.error ?: stringResource(R.string.scripts_load_error),
                         style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                         color = MaterialTheme.colorScheme.onErrorContainer,
                         modifier = Modifier.padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    { onEdit(s.name) },
                    Modifier.focusHighlight(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.edit)) }
                TextButton(
                    { onRemove(s.name) },
                    Modifier.focusHighlight(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/**
 * Script editor
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScriptEditorScreen(
    name: String,
    initialSource: String,
    canRevert: Boolean,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onRevert: () -> String?,
) {
    var text by remember(name) { mutableStateOf(initialSource) }
    var confirmRevert by remember { mutableStateOf(false) }
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, fontFamily = FontFamily.Monospace, maxLines = 1) },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                    ) { Text(stringResource(R.string.cancel)) }
                },
                actions = {
                    if (canRevert) {
                        TextButton(
                            { confirmRevert = true },
                            Modifier.focusHighlight(RoundedCornerShape(50)),
                        ) { Text(stringResource(R.string.scripts_revert)) }
                    }
                    TextButton(
                        { onSave(text) },
                        Modifier.focusHighlight(RoundedCornerShape(50)),
                    ) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { pad ->
        val vscroll = rememberScrollState()
        val hscroll = rememberScrollState()
        val lineCount = remember(text) { text.count { it == '\n' } + 1 }
        Row(Modifier.fillMaxSize().padding(pad).verticalScroll(vscroll)) {
            // Line numbers
            val gutter = remember(lineCount) { (1..lineCount).joinToString("\n") }
            Text(
                text = gutter,
                style = mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .widthIn(min = 40.dp)
                    .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            )
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = mono.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .focusHighlight(RoundedCornerShape(4.dp))
                    .padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 10.dp)
                    .horizontalScroll(hscroll),
            )
        }
    }
    if (confirmRevert) {
        AlertDialog(
            onDismissRequest = { confirmRevert = false },
            title = { Text(stringResource(R.string.scripts_revert_title, name)) },
            text = { Text(stringResource(R.string.scripts_revert_body)) },
            confirmButton = {
                TextButton(
                    { onRevert()?.let { text = it }; confirmRevert = false },
                    Modifier.tvInitialFocus().focusHighlight(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.scripts_revert)) }
            },
            dismissButton = {
                TextButton(
                    { confirmRevert = false },
                    Modifier.focusHighlight(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ExtBadge(ext: String) {
    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            ".$ext",
             Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
             fontSize = 10.sp,
             fontFamily = FontFamily.Monospace,
             color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun StatusPill(s: ScriptInfo) {
    val (label, color) = when {
        !s.enabled -> stringResource(R.string.scripts_status_disabled) to MaterialTheme.colorScheme.onSurfaceVariant
        s.ok       -> stringResource(R.string.scripts_status_loaded) to MaterialTheme.colorScheme.primary
        else       -> stringResource(R.string.scripts_status_error) to MaterialTheme.colorScheme.error
    }
    Text(label, fontSize = 12.sp, color = color)
}

@Composable
private fun PasteDialog(ext: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scripts_paste_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text(stringResource(R.string.scripts_name_hint, ext)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvInitialFocus(),
                )
                OutlinedTextField(
                    body, { body = it }, label = { Text(stringResource(R.string.scripts_source)) },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank() && body.isNotBlank()) onConfirm(name.trim(), body) },
                modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
            ) { Text(stringResource(R.string.cancel)) }
        },
    )
}
