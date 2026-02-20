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

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.ChatFontStyle
import com.boxlabs.hexdroid.FontChoice
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.UiSettings
import com.boxlabs.hexdroid.UiState
import com.boxlabs.hexdroid.VibrateIntensity
import com.boxlabs.hexdroid.data.ThemeMode
import com.boxlabs.hexdroid.ui.tour.TourTarget
import com.boxlabs.hexdroid.ui.tour.tourTarget
import java.io.File

// Copy a font file from a content URI to internal storage
private fun copyFontToInternal(ctx: android.content.Context, uri: Uri, prefix: String): String? {
    return try {
        val inputStream = ctx.contentResolver.openInputStream(uri) ?: return null
        val fontsDir = File(ctx.filesDir, "fonts").apply { mkdirs() }

        // Get original filename or use a default
        val cursor = ctx.contentResolver.query(uri, null, null, null, null)
        val fileName = cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) it.getString(idx) else null
            } else null
        } ?: "custom_font.ttf"

        val destFile = File(fontsDir, "${prefix}_$fileName")
        destFile.outputStream().use { out ->
            inputStream.copyTo(out)
        }
        inputStream.close()
        destFile.absolutePath
    } catch (e: Exception) {
        null
    }
}

// Get just the filename from a path
private fun getCustomFontName(path: String?): String? {
    if (path.isNullOrBlank()) return null
    return File(path).name.removePrefix("ui_").removePrefix("chat_")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onUpdate: (UiSettings.() -> UiSettings) -> Unit,
    onRunTour: () -> Unit,
    onOpenNetworks: () -> Unit,
    onOpenIgnoreList: () -> Unit,
    tourActive: Boolean = false,
    tourTarget: TourTarget? = null,
) {
    val s = state.settings
    val ctx = LocalContext.current

    var showBatteryHelpDialog by remember { mutableStateOf(false) }
    val isOnePlus = remember { Build.MANUFACTURER.equals("OnePlus", ignoreCase = true) }


    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
            onUpdate { copy(logFolderUri = uri.toString()) }
        }
    }

    val dccFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
            onUpdate { copy(dccDownloadFolderUri = uri.toString()) }
        }
    }

    // UI font file picker
    val uiFontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val path = copyFontToInternal(ctx, uri, "ui")
            if (path != null) {
                onUpdate { copy(fontChoice = FontChoice.CUSTOM, customFontPath = path) }
            }
        }
    }

    // Chat font file picker
    val chatFontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val path = copyFontToInternal(ctx, uri, "chat")
            if (path != null) {
                onUpdate { copy(chatFontChoice = FontChoice.CUSTOM, customChatFontPath = path) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
                actions = { IconButton(onClick = onOpenNetworks) { Text("🌐") } }
            )
        }
    ) { padding ->
        val listState = rememberLazyListState()

        // Tour: scroll so the highlighted element is actually on-screen.
        LaunchedEffect(tourActive, tourTarget) {
            if (!tourActive) return@LaunchedEffect
            when (tourTarget) {
                TourTarget.SETTINGS_APPEARANCE_SECTION -> {
                    // Second item after the "Intro tour" card.
                    try { listState.animateScrollToItem(1) } catch (_: Throwable) { }
                }
                TourTarget.SETTINGS_RUN_TOUR -> {
                    try { listState.animateScrollToItem(0) } catch (_: Throwable) { }
                }
                else -> Unit
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)

) {
    item {
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Intro tour", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "A quick walkthrough of the main screens and shortcuts.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(
                    onClick = onRunTour,
                    modifier = Modifier.tourTarget(TourTarget.SETTINGS_RUN_TOUR)
                ) { Text("Run") }
            }
        }
    }

    item { SectionTitle(stringResource(R.string.section_appearance), modifier = Modifier.tourTarget(TourTarget.SETTINGS_APPEARANCE_SECTION)) }

            item {
                LanguagePicker(
                    currentCode = s.appLanguage,
                    onPick = { code ->
                        onUpdate { copy(appLanguage = code) }
                        com.boxlabs.hexdroid.ui.applyLocale(ctx, code)
                    }
                )
            }

            item {
                ThemePicker(s.themeMode) { mode -> onUpdate { copy(themeMode = mode) } }
            }

            item {
                FontPicker(
                    fieldLabel = "UI font",
                    current = s.fontChoice,
                    customFontName = getCustomFontName(s.customFontPath),
                    onPick = { choice ->
                        onUpdate { copy(fontChoice = choice) }
                    },
                    onPickCustom = {
                        uiFontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf"))
                    }
                )
            }

            item {
                FontPicker(
                    fieldLabel = "Chat font",
                    current = s.chatFontChoice,
                    customFontName = getCustomFontName(s.customChatFontPath),
                    onPick = { choice ->
                        onUpdate { copy(chatFontChoice = choice) }
                    },
                    onPickCustom = {
                        chatFontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf"))
                    }
                )
            }

            item {
                ChatFontStylePicker(current = s.chatFontStyle) { style ->
                    onUpdate { copy(chatFontStyle = style) }
                }
            }

            item {
                SettingToggle(stringResource(R.string.setting_compact_mode), s.compactMode) { onUpdate { copy(compactMode = !compactMode) } }
            }

            item {
                Text("Font size", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = s.fontScale,
                    onValueChange = { v -> onUpdate { copy(fontScale = v.coerceIn(0.85f, 1.35f)) } },
                    valueRange = 0.85f..1.35f
                )
                Text("${(s.fontScale * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }

            item { HorizontalDivider() }

            item { SectionTitle(stringResource(R.string.section_ui)) }
            item {
                SettingToggle(stringResource(R.string.setting_colorise_nicks), s.colorizeNicks) { onUpdate { copy(colorizeNicks = !colorizeNicks) } }
            }

            item {
                SettingToggle(stringResource(R.string.setting_mirc_colours), s.mircColorsEnabled) { onUpdate { copy(mircColorsEnabled = !mircColorsEnabled) } }
            }

            item { SettingToggle(stringResource(R.string.setting_show_topic_bar), s.showTopicBar) { onUpdate { copy(showTopicBar = !showTopicBar) } } }
            item { SettingToggle(stringResource(R.string.setting_show_timestamps), s.showTimestamps) { onUpdate { copy(showTimestamps = !showTimestamps) } } }
            item {
                OutlinedTextField(
                    value = s.timestampFormat,
                    onValueChange = { v -> onUpdate { copy(timestampFormat = v) } },
                    label = { Text("Timestamp format (Java)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item { SettingToggle(stringResource(R.string.setting_hide_motd), s.hideMotdOnConnect) { onUpdate { copy(hideMotdOnConnect = !hideMotdOnConnect) } } }
            item { SettingToggle(stringResource(R.string.setting_hide_joinpartquit), s.hideJoinPartQuit) { onUpdate { copy(hideJoinPartQuit = !hideJoinPartQuit) } } }
            item { SectionTitle(stringResource(R.string.section_landscape)) }
            item { SettingToggle(stringResource(R.string.setting_show_buffers_default), s.defaultShowBufferList) { onUpdate { copy(defaultShowBufferList = !defaultShowBufferList) } } }
            item { SettingToggle(stringResource(R.string.setting_show_nicklist_default), s.defaultShowNickList) { onUpdate { copy(defaultShowNickList = !defaultShowNickList) } } }

            item { SectionTitle(stringResource(R.string.section_portrait)) }
            item {
                Column {
                    SettingToggle(stringResource(R.string.setting_portrait_nicklist_overlay), s.portraitNicklistOverlay) {
                        onUpdate { copy(portraitNicklistOverlay = !portraitNicklistOverlay) }
                    }
                    Text(
                        stringResource(R.string.setting_portrait_nicklist_overlay_desc),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                    )
                }
            }

            item { HorizontalDivider() }

            item { SectionTitle(stringResource(R.string.section_highlights)) }

            item { SettingToggle(stringResource(R.string.setting_highlight_on_nick), s.highlightOnNick) { onUpdate { copy(highlightOnNick = !highlightOnNick) } } }

            item {
                var wordsText by remember(s.extraHighlightWords) { mutableStateOf(s.extraHighlightWords.joinToString("\n")) }
                OutlinedTextField(
                    value = wordsText,
                    onValueChange = { v ->
                        wordsText = v
                        val words = v.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
                        onUpdate { copy(extraHighlightWords = words) }
                    },
                    label = { Text("Extra highlight words (one per line)") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item { HorizontalDivider() }

            item { SectionTitle(stringResource(R.string.section_irc)) }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Ignore list", style = MaterialTheme.typography.titleSmall)
                            Text("Hide messages and DCC offers from specific nicks (per network).", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = onOpenIgnoreList) { Text("Manage") }
                    }
                }
            }

            item {
                Text("Quit message.", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = s.quitMessage,
                    onValueChange = { v -> onUpdate { copy(quitMessage = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            item {
                Text("Part message", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = s.partMessage,
                    onValueChange = { v -> onUpdate { copy(partMessage = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item { SettingToggle(stringResource(R.string.setting_connection_status), s.showConnectionStatusNotification) { onUpdate { copy(showConnectionStatusNotification = !showConnectionStatusNotification) } } }
            item {
                SettingToggle(
                    "Keep connection alive",
                    s.keepAliveInBackground
                ) {
                    val newValue = !s.keepAliveInBackground
                    onUpdate { copy(keepAliveInBackground = newValue) }

                    // When enabling, guide user to disable battery optimizations (user-driven, Play-safe)
                    if (newValue && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val pm = ctx.getSystemService(PowerManager::class.java)
                        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                            showBatteryHelpDialog = true
                        }
                    }
                }
            }

            item { SettingToggle(stringResource(R.string.setting_auto_reconnect), s.autoReconnectEnabled) { onUpdate { copy(autoReconnectEnabled = !autoReconnectEnabled) } } }

            item {
                Column(Modifier.fillMaxWidth()) {
                    Text("Reconnect interval (seconds)", style = MaterialTheme.typography.titleSmall)
                    Text("How often to retry when the connection drops.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = s.autoReconnectDelaySec.toString(),
                        enabled = s.autoReconnectEnabled,
                        onValueChange = { v ->
                            val n = v.filter { it.isDigit() }.toIntOrNull() ?: return@OutlinedTextField
                            onUpdate { copy(autoReconnectDelaySec = n.coerceIn(5, 600)) }
                        },
                        label = { Text("Seconds") },
                        singleLine = true,
                        modifier = Modifier.widthIn(max = 180.dp)
                    )
                }
            }
            item { HorizontalDivider() }

            item { SectionTitle(stringResource(R.string.section_notifications)) }

            item { SettingToggle(stringResource(R.string.setting_enable_notifications), s.notificationsEnabled) { onUpdate { copy(notificationsEnabled = !notificationsEnabled) } } }
            item { SettingToggle(stringResource(R.string.setting_notify_highlights), s.notifyOnHighlights) { onUpdate { copy(notifyOnHighlights = !notifyOnHighlights) } } }
            item { SettingToggle(stringResource(R.string.setting_notify_pm), s.notifyOnPrivateMessages) { onUpdate { copy(notifyOnPrivateMessages = !notifyOnPrivateMessages) } } }
            item { SettingToggle(stringResource(R.string.setting_sound_highlight), s.playSoundOnHighlight) { onUpdate { copy(playSoundOnHighlight = !playSoundOnHighlight) } } }
            item { SettingToggle(stringResource(R.string.setting_vibrate_highlight), s.vibrateOnHighlight) { onUpdate { copy(vibrateOnHighlight = !vibrateOnHighlight) } } }
            if (s.vibrateOnHighlight) {
                item {
                    VibrateIntensityPicker(current = s.vibrateIntensity) { picked ->
                        onUpdate { copy(vibrateIntensity = picked) }
                    }
                }
            }

            item { HorizontalDivider() }

            item { SectionTitle(stringResource(R.string.section_logging)) }

            item { SettingToggle(stringResource(R.string.setting_enable_logging), s.loggingEnabled) { onUpdate { copy(loggingEnabled = !loggingEnabled) } } }
            item { SettingToggle(stringResource(R.string.setting_log_server), s.logServerBuffer) { onUpdate { copy(logServerBuffer = !logServerBuffer) } } }

            item {
                val label = if (s.logFolderUri.isNullOrBlank()) "Internal storage" else "Custom folder selected"
                Column(Modifier.fillMaxWidth()) {
                    Text("Log folder", style = MaterialTheme.typography.titleSmall)
                    Text(label, style = MaterialTheme.typography.bodySmall)
                    if (!s.logFolderUri.isNullOrBlank()) {
                        Text(s.logFolderUri, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { folderPicker.launch(null) }) { Text("Choose folder") }
                        if (!s.logFolderUri.isNullOrBlank()) {
                            OutlinedButton(onClick = {
                                runCatching {
                                    ctx.contentResolver.releasePersistableUriPermission(
                                        Uri.parse(s.logFolderUri),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    )
                                }
                                onUpdate { copy(logFolderUri = null) }
                            }) {
                                Text("Reset")
                            }
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = s.retentionDays.toString(),
                    onValueChange = { v ->
                        val n = v.filter { it.isDigit() }.toIntOrNull() ?: return@OutlinedTextField
                        onUpdate { copy(retentionDays = n.coerceIn(1, 365)) }
                    },
                    label = { Text("Retention days") },
                    singleLine = true
                )
            }

            item {
                OutlinedTextField(
                    value = s.maxScrollbackLines.toString(),
                    onValueChange = { v ->
                        val n = v.filter { it.isDigit() }.toIntOrNull() ?: return@OutlinedTextField
                        onUpdate { copy(maxScrollbackLines = n.coerceIn(200, 5000)) }
                    },
                    label = { Text("Max scrollback lines") },
                    singleLine = true
                )
            }

            item { HorizontalDivider() }

            item { SectionTitle(stringResource(R.string.section_ircv3_history)) }

            item {
                Column(Modifier.fillMaxWidth()) {
                    Text("History fetch limit", style = MaterialTheme.typography.titleSmall)
                    Text("Number of messages to request per channel on join (CHATHISTORY LATEST). Set to 0 to disable.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = s.ircHistoryLimit.toString(),
                        onValueChange = { v ->
                            val n = v.filter { it.isDigit() }.toIntOrNull() ?: return@OutlinedTextField
                            onUpdate { copy(ircHistoryLimit = n.coerceIn(0, 500)) }
                        },
                        label = { Text("Messages") },
                        singleLine = true
                    )
                }
            }

            item { SettingToggle(stringResource(R.string.setting_count_unread), s.ircHistoryCountsAsUnread) { onUpdate { copy(ircHistoryCountsAsUnread = !ircHistoryCountsAsUnread) } } }
            item { SettingToggle(stringResource(R.string.setting_trigger_notif), s.ircHistoryTriggersNotifications) { onUpdate { copy(ircHistoryTriggersNotifications = !ircHistoryTriggersNotifications) } } }

            item { HorizontalDivider() }

            item { SectionTitle(stringResource(R.string.section_file_transfers)) }

            item { SettingToggle(stringResource(R.string.setting_enable_dcc), s.dccEnabled) { onUpdate { copy(dccEnabled = !dccEnabled) } } }

            item {
                val dccFolderLabel = if (s.dccDownloadFolderUri.isNullOrBlank()) "Downloads (default)" else "Custom folder"
                Column(Modifier.fillMaxWidth()) {
                    Text("Download folder", style = MaterialTheme.typography.titleSmall)
                    Text(dccFolderLabel, style = MaterialTheme.typography.bodySmall)
                    if (!s.dccDownloadFolderUri.isNullOrBlank()) {
                        Text(s.dccDownloadFolderUri, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { dccFolderPicker.launch(null) }) { Text("Choose folder") }
                        if (!s.dccDownloadFolderUri.isNullOrBlank()) {
                            OutlinedButton(onClick = {
                                runCatching {
                                    ctx.contentResolver.releasePersistableUriPermission(
                                        Uri.parse(s.dccDownloadFolderUri),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    )
                                }
                                onUpdate { copy(dccDownloadFolderUri = null) }
                            }) {
                                Text("Reset")
                            }
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = s.dccIncomingPortMin.toString(),
                    onValueChange = { v ->
                        val n = v.filter { it.isDigit() }.toIntOrNull() ?: return@OutlinedTextField
                        onUpdate { copy(dccIncomingPortMin = n.coerceIn(1, 65535)) }
                    },
                    label = { Text("Incoming port min") },
                    singleLine = true
                )
            }

            item {
                OutlinedTextField(
                    value = s.dccIncomingPortMax.toString(),
                    onValueChange = { v ->
                        val n = v.filter { it.isDigit() }.toIntOrNull() ?: return@OutlinedTextField
                        onUpdate { copy(dccIncomingPortMax = n.coerceIn(1, 65535)) }
                    },
                    label = { Text("Incoming port max") },
                    singleLine = true
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    if (showBatteryHelpDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryHelpDialog = false },
            title = { Text("Keep the connection alive") },
            text = {
                Column {
                    Text(
                        "To stay connected overnight, please disable Battery Optimization for this app " +
                            "and allow background activity."
                    )
                    if (isOnePlus) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "OnePlus tip: also lock the app in Recents (padlock) and ensure " +
                                "background activity / auto-launch is allowed in battery settings."
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryHelpDialog = false
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${ctx.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }) {
                    Text("Open app settings")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showBatteryHelpDialog = false
                        ctx.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }) {
                        Text("Battery optimization list")
                    }
                    TextButton(onClick = { showBatteryHelpDialog = false }) {
                        Text("Not now")
                    }
                }
            }
        )
    }

}

@Composable
private fun SectionTitle(t: String, modifier: Modifier = Modifier) {
    Text(t, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePicker(currentCode: String?, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val languages = com.boxlabs.hexdroid.ui.SUPPORTED_LANGUAGES
    val currentLabel = languages.firstOrNull { it.code == currentCode }?.nativeName ?: "System"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Language") },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (lang in languages) {
                DropdownMenuItem(
                    text = { Text(lang.nativeName) },
                    onClick = { onPick(lang.code); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onClick() })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemePicker(current: ThemeMode, onPick: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (current) {
        ThemeMode.DARK -> "Dark"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.SYSTEM -> "System"
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Theme") },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Dark") }, onClick = { onPick(ThemeMode.DARK); expanded = false })
            DropdownMenuItem(text = { Text("Light") }, onClick = { onPick(ThemeMode.LIGHT); expanded = false })
            DropdownMenuItem(text = { Text("System") }, onClick = { onPick(ThemeMode.SYSTEM); expanded = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontPicker(
    fieldLabel: String,
    current: FontChoice,
    customFontName: String? = null,
    onPick: (FontChoice) -> Unit,
    onPickCustom: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = when (current) {
        FontChoice.OPEN_SANS -> "Open Sans"
        FontChoice.INTER -> "Inter"
        FontChoice.MONOSPACE -> "Monospace"
        FontChoice.CUSTOM -> customFontName ?: "Custom"
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(fieldLabel) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Open Sans is the default UI font.
            DropdownMenuItem(text = { Text("Open Sans") }, onClick = { onPick(FontChoice.OPEN_SANS); expanded = false })
            DropdownMenuItem(text = { Text("Inter") }, onClick = { onPick(FontChoice.INTER); expanded = false })
            DropdownMenuItem(text = { Text("Monospace") }, onClick = { onPick(FontChoice.MONOSPACE); expanded = false })
            if (onPickCustom != null) {
                DropdownMenuItem(
                    text = { Text("Custom font file...") },
                    onClick = {
                        expanded = false
                        onPickCustom()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatFontStylePicker(current: ChatFontStyle, onPick: (ChatFontStyle) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = when (current) {
        ChatFontStyle.REGULAR -> "Regular"
        ChatFontStyle.BOLD -> "Bold"
        ChatFontStyle.ITALIC -> "Italic"
        ChatFontStyle.BOLD_ITALIC -> "Bold + Italic"
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Chat font style") },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Regular") }, onClick = { onPick(ChatFontStyle.REGULAR); expanded = false })
            DropdownMenuItem(text = { Text("Bold") }, onClick = { onPick(ChatFontStyle.BOLD); expanded = false })
            DropdownMenuItem(text = { Text("Italic") }, onClick = { onPick(ChatFontStyle.ITALIC); expanded = false })
            DropdownMenuItem(text = { Text("Bold + Italic") }, onClick = { onPick(ChatFontStyle.BOLD_ITALIC); expanded = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VibrateIntensityPicker(current: VibrateIntensity, onPick: (VibrateIntensity) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (current) {
        VibrateIntensity.LOW -> "Low"
        VibrateIntensity.MEDIUM -> "Medium"
        VibrateIntensity.HIGH -> "High"
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Vibration intensity") },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Low") }, onClick = { onPick(VibrateIntensity.LOW); expanded = false })
            DropdownMenuItem(text = { Text("Medium") }, onClick = { onPick(VibrateIntensity.MEDIUM); expanded = false })
            DropdownMenuItem(text = { Text("High") }, onClick = { onPick(VibrateIntensity.HIGH); expanded = false })
        }
    }
}
