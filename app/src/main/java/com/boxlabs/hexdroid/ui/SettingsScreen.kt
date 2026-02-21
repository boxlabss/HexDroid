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
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
    onExportBackup: (Uri) -> Unit = {},
    onImportBackup: (Uri) -> Unit = {},
    onClearBackupMessage: () -> Unit = {},
) {
    val s = state.settings
    val ctx = LocalContext.current

    var showBatteryHelpDialog by remember { mutableStateOf(false) }
    val isOnePlus = remember { Build.MANUFACTURER.equals("OnePlus", ignoreCase = true) }

    // Backup / restore state
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var showBackupResultDialog by remember { mutableStateOf(false) }

    // Show result dialog whenever a backup message arrives
    LaunchedEffect(state.backupMessage) {
        if (state.backupMessage != null) showBackupResultDialog = true
    }

    // Filename for the backup uses a timestamp so files don't collide
    val backupFileName = remember {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        "hexdroid_backup_$ts.json"
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) onExportBackup(uri)
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingRestoreUri = uri
            showRestoreConfirmDialog = true
        }
    }


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
                title = { Text(stringResource(R.string.settings_title)) },
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
                    Text(stringResource(R.string.settings_intro_tour), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_intro_tour_desc),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(
                    onClick = onRunTour,
                    modifier = Modifier.tourTarget(TourTarget.SETTINGS_RUN_TOUR)
                ) { Text(stringResource(R.string.run)) }
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
                    fieldLabel = stringResource(R.string.setting_ui_font),
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
                Text(stringResource(R.string.setting_font_size), style = MaterialTheme.typography.titleSmall)
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
                    label = { Text(stringResource(R.string.setting_timestamp_format)) },
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
                    label = { Text(stringResource(R.string.setting_extra_highlights)) },
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
                            Text(stringResource(R.string.setting_ignore_list), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.setting_ignore_list_desc), style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = onOpenIgnoreList) { Text(stringResource(R.string.manage)) }
                    }
                }
            }
			
            item {
                Text(stringResource(R.string.setting_quit_message), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = s.quitMessage,
                    onValueChange = { v -> onUpdate { copy(quitMessage = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
			
            item {
                Text(stringResource(R.string.setting_part_message), style = MaterialTheme.typography.titleSmall)
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
                    stringResource(R.string.setting_keep_alive),
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
                    Text(stringResource(R.string.setting_reconnect_interval), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.setting_reconnect_interval_desc), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = s.autoReconnectDelaySec.toString(),
                        enabled = s.autoReconnectEnabled,
                        onValueChange = { v ->
                            val n = v.filter { it.isDigit() }.toIntOrNull() ?: return@OutlinedTextField
                            onUpdate { copy(autoReconnectDelaySec = n.coerceIn(5, 600)) }
                        },
                        label = { Text(stringResource(R.string.setting_seconds)) },
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
                val label = if (s.logFolderUri.isNullOrBlank()) stringResource(R.string.setting_storage_internal) else stringResource(R.string.setting_storage_custom_folder)
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setting_log_folder), style = MaterialTheme.typography.titleSmall)
                    Text(label, style = MaterialTheme.typography.bodySmall)
                    if (!s.logFolderUri.isNullOrBlank()) {
                        Text(s.logFolderUri!!, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { folderPicker.launch(null) }) { Text(stringResource(R.string.setting_choose_folder)) }
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
                                Text(stringResource(R.string.reset))
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
                    label = { Text(stringResource(R.string.setting_retention_days)) },
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
                    label = { Text(stringResource(R.string.setting_max_scrollback)) },
                    singleLine = true
                )
            }

            item { HorizontalDivider() }

            item { SectionTitle(stringResource(R.string.section_ircv3_history)) }

            item {
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setting_history_limit), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.setting_history_limit_desc), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = s.ircHistoryLimit.toString(),
                        onValueChange = { v ->
                            val n = v.filter { it.isDigit() }.toIntOrNull() ?: return@OutlinedTextField
                            onUpdate { copy(ircHistoryLimit = n.coerceIn(0, 500)) }
                        },
                        label = { Text(stringResource(R.string.setting_messages)) },
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
                val dccFolderLabel = if (s.dccDownloadFolderUri.isNullOrBlank()) stringResource(R.string.setting_download_folder_default) else stringResource(R.string.setting_download_folder_custom)
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setting_download_folder), style = MaterialTheme.typography.titleSmall)
                    Text(dccFolderLabel, style = MaterialTheme.typography.bodySmall)
                    if (!s.dccDownloadFolderUri.isNullOrBlank()) {
                        Text(s.dccDownloadFolderUri!!, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { dccFolderPicker.launch(null) }) { Text(stringResource(R.string.setting_choose_folder)) }
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
                                Text(stringResource(R.string.reset))
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
                    label = { Text(stringResource(R.string.setting_incoming_port_min)) },
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
                    label = { Text(stringResource(R.string.setting_incoming_port_max)) },
                    singleLine = true
                )
            }

            item { Spacer(Modifier.height(16.dp)) }

            // ----- Backup & Restore -----
            item { HorizontalDivider() }
            item { SectionTitle("Backup & Restore") }

            item {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "Export your network configurations and app settings to a JSON file. " +
                            "Passwords and TLS certificates are not included.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { exportBackupLauncher.launch(backupFileName) }) {
                            Text("Export backup")
                        }
                        OutlinedButton(onClick = {
                            importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        }) {
                            Text("Restore backup")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Restoring replaces all current networks and settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    // Restore confirmation dialog
    if (showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showRestoreConfirmDialog = false
                pendingRestoreUri = null
            },
            title = { Text("Restore backup?") },
            text = {
                Text(
                    "This will replace all current networks and settings with those in the backup file. " +
                        "Passwords were not included in the backup and will need to be re-entered.\n\n" +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showRestoreConfirmDialog = false
                    pendingRestoreUri?.let { uri -> onImportBackup(uri) }
                    pendingRestoreUri = null
                }) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestoreConfirmDialog = false
                    pendingRestoreUri = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Backup / restore result dialog
    if (showBackupResultDialog && state.backupMessage != null) {
        AlertDialog(
            onDismissRequest = {
                showBackupResultDialog = false
                onClearBackupMessage()
            },
            title = {
                val isError = state.backupMessage.startsWith("Backup failed") ||
                    state.backupMessage.startsWith("Restore failed")
                Text(if (isError) "Error" else "Done")
            },
            text = { Text(state.backupMessage) },
            confirmButton = {
                TextButton(onClick = {
                    showBackupResultDialog = false
                    onClearBackupMessage()
                }) {
                    Text("OK")
                }
            }
        )
    }

    if (showBatteryHelpDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryHelpDialog = false },
            title = { Text(stringResource(R.string.setting_keep_alive)) },
            text = {
                Column {
                    Text(stringResource(R.string.battery_dialog_text))
                    if (isOnePlus) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.battery_dialog_oneplus_tip))
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
                    Text(stringResource(R.string.setting_open_app_settings))
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
                        Text(stringResource(R.string.setting_battery_optimization))
                    }
                    TextButton(onClick = { showBatteryHelpDialog = false }) {
                        Text(stringResource(R.string.not_now))
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
    val systemLabel = stringResource(R.string.theme_system)
    val currentLabel = languages.firstOrNull { it.code == currentCode }?.nativeName ?: systemLabel

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.welcome_language_label)) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
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
        ThemeMode.DARK -> stringResource(R.string.theme_dark)
        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
        ThemeMode.MATRIX -> stringResource(R.string.theme_matrix)
        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.theme_label)) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.theme_dark)) }, onClick = { onPick(ThemeMode.DARK); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.theme_light)) }, onClick = { onPick(ThemeMode.LIGHT); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.theme_matrix)) }, onClick = { onPick(ThemeMode.MATRIX); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.theme_system)) }, onClick = { onPick(ThemeMode.SYSTEM); expanded = false })
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
    val customLabel = stringResource(R.string.custom)
    val currentLabel = when (current) {
        FontChoice.OPEN_SANS -> stringResource(R.string.font_open_sans)
        FontChoice.INTER -> stringResource(R.string.font_inter)
        FontChoice.MONOSPACE -> stringResource(R.string.font_monospace)
        FontChoice.CUSTOM -> customFontName ?: customLabel
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(fieldLabel) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Open Sans is the default UI font.
            DropdownMenuItem(text = { Text(stringResource(R.string.font_open_sans)) }, onClick = { onPick(FontChoice.OPEN_SANS); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.font_inter)) }, onClick = { onPick(FontChoice.INTER); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.font_monospace)) }, onClick = { onPick(FontChoice.MONOSPACE); expanded = false })
            if (onPickCustom != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.font_custom)) },
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
        ChatFontStyle.REGULAR -> stringResource(R.string.style_regular)
        ChatFontStyle.BOLD -> stringResource(R.string.style_bold)
        ChatFontStyle.ITALIC -> stringResource(R.string.style_italic)
        ChatFontStyle.BOLD_ITALIC -> stringResource(R.string.style_bold_italic)
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.chat_font_style)) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.style_regular)) }, onClick = { onPick(ChatFontStyle.REGULAR); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.style_bold)) }, onClick = { onPick(ChatFontStyle.BOLD); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.style_italic)) }, onClick = { onPick(ChatFontStyle.ITALIC); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.style_bold_italic)) }, onClick = { onPick(ChatFontStyle.BOLD_ITALIC); expanded = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VibrateIntensityPicker(current: VibrateIntensity, onPick: (VibrateIntensity) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (current) {
        VibrateIntensity.LOW -> stringResource(R.string.vibration_low)
        VibrateIntensity.MEDIUM -> stringResource(R.string.vibration_medium)
        VibrateIntensity.HIGH -> stringResource(R.string.vibration_high)
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.vibration_intensity)) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.vibration_low)) }, onClick = { onPick(VibrateIntensity.LOW); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.vibration_medium)) }, onClick = { onPick(VibrateIntensity.MEDIUM); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.vibration_high)) }, onClick = { onPick(VibrateIntensity.HIGH); expanded = false })
        }
    }
}
