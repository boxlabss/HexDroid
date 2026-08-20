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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import com.boxlabs.hexdroid.ChatFontStyle
import com.boxlabs.hexdroid.FontChoice
import com.boxlabs.hexdroid.NickStyle
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.TimestampStyle
import com.boxlabs.hexdroid.UiSettings
import com.boxlabs.hexdroid.UiState
import com.boxlabs.hexdroid.VibrateIntensity
import com.boxlabs.hexdroid.data.ThemeMode
import com.boxlabs.hexdroid.ui.tour.TourTarget
import com.boxlabs.hexdroid.ui.tour.tourTarget
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Find index of element minimising the selector
private fun <T> List<T>.indexOfMinBy(selector: (T) -> Float): Int {
    if (isEmpty()) return -1
    var minIdx = 0
    var minVal = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (v < minVal) { minVal = v; minIdx = i }
    }
    return minIdx
}

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

/**
 * A full-colour HSV wheel + value (lightness) slider for picking a custom nick colour.
 * Shows a preview swatch next to the current colour. Confirm saves, dismiss cancels.
 */
@Composable
private fun NickColourPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    // Decompose to HSV
    val initHsv = FloatArray(3)
    android.graphics.Color.colorToHSV(initial.toArgb(), initHsv)

    var hue   by remember { mutableStateOf(initHsv[0]) }
    var sat   by remember { mutableStateOf(initHsv[1]) }
    var value by remember { mutableStateOf(initHsv[2]) }

    val picked = Color.hsv(hue, sat, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onConfirm(picked) }, modifier = Modifier.tvInitialFocus().focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(android.R.string.cancel)) }
        },
        title = { Text(stringResource(R.string.setting_own_nick_colour_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Colour preview swatch
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(picked)
                )

                // Hue wheel
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        // D-pad path (Android TV / hardware keyboards): select engages
                        // adjust mode, then left/right = hue, up/down = saturation.
                        // Touch drag below is unaffected.
                        .dpadColourWheel(
                            onHue = { d -> hue = (hue + d + 360f) % 360f },
                            onSat = { d -> sat = (sat + d).coerceIn(0f, 1f) },
                        )
                        .pointerInput(Unit) {
                            val piF = Math.PI.toFloat()
                            detectDragGestures(
                                onDragStart = { offset: androidx.compose.ui.geometry.Offset ->
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    val dx = offset.x - cx
                                    val dy = offset.y - cy
                                    val r  = size.width.coerceAtMost(size.height) / 2f
                                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                    if (dist <= r) {
                                        hue = ((kotlin.math.atan2(dy, dx) * 180f / piF + 360f) % 360f)
                                        sat = (dist / r).coerceIn(0f, 1f)
                                    }
                                },
                                onDrag = { change: PointerInputChange, _: androidx.compose.ui.geometry.Offset ->
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    val dx = change.position.x - cx
                                    val dy = change.position.y - cy
                                    val r  = size.width.coerceAtMost(size.height) / 2f
                                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                    hue = ((kotlin.math.atan2(dy, dx) * 180f / piF + 360f) % 360f)
                                    sat = (dist / r).coerceIn(0f, 1f)
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val r  = size.width.coerceAtMost(size.height) / 2f
                        // Draw hue wheel: sweep + radial gradients stacked
                        drawCircle(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    Color.Red, Color.Yellow, Color.Green,
                                    Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                                ),
                                center = Offset(cx, cy),
                            ),
                            radius = r,
                        )
                        // White radial fade (centre = white)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White, Color.Transparent),
                                center = Offset(cx, cy),
                                radius = r,
                            ),
                            radius = r,
                        )
                        // Selection indicator
                        val piF2 = Math.PI.toFloat()
                        val angle = hue * piF2 / 180f
                        val iX = cx + sat * r * kotlin.math.cos(angle.toDouble()).toFloat()
                        val iY = cy + sat * r * kotlin.math.sin(angle.toDouble()).toFloat()
                        drawCircle(Color.White, radius = 10f, center = Offset(iX, iY))
                        drawCircle(Color.Black, radius = 10f, center = Offset(iX, iY), style = Stroke(2f))
                    }
                }

                // Value (brightness) slider
                Text(
                    "Brightness",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0.1f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    )
}

/**
 * One page of the settings screen. Order here is the order shown in the rail and hub.
 */
enum class SettingsCategory(
    val titleRes: Int,
    val summaryRes: Int,
    val icon: ImageVector,
) {
    APPEARANCE(R.string.section_appearance, R.string.settings_cat_appearance_desc, Icons.Filled.Palette),
    CHAT(R.string.settings_cat_chat, R.string.settings_cat_chat_desc, Icons.AutoMirrored.Filled.Chat),
    MEDIA(R.string.section_media_previews, R.string.settings_cat_media_desc, Icons.Filled.Image),
    ALIASES(R.string.settings_custom_aliases, R.string.settings_cat_aliases_desc, Icons.Filled.Terminal),
    HIGHLIGHTS(R.string.section_highlights, R.string.settings_cat_highlights_desc, Icons.Filled.AlternateEmail),
    IRC(R.string.section_irc, R.string.settings_cat_irc_desc, Icons.Filled.Dns),
    NOTIFICATIONS(R.string.section_notifications, R.string.settings_cat_notifications_desc, Icons.Filled.Notifications),
    LOGGING(R.string.section_logging, R.string.settings_cat_logging_desc, Icons.AutoMirrored.Filled.Article),
    PRIVACY(R.string.section_privacy, R.string.settings_cat_privacy_desc, Icons.Filled.Lock),
    HISTORY(R.string.section_ircv3_history, R.string.settings_cat_history_desc, Icons.Filled.History),
    TRANSFERS(R.string.section_file_transfers, R.string.settings_cat_transfers_desc, Icons.Filled.SwapVert),
    BACKUP(R.string.section_backup_restore, R.string.settings_cat_backup_desc, Icons.Filled.Backup),
}

/**
 * Category list for the settings screen. Shown as a rail beside the content pane on
 * TV and tablets, and as the top level page on phones.
 */
@Composable
private fun SettingsNavPanel(
    current: SettingsCategory?,
    rail: Boolean,
    onSelect: (SettingsCategory) -> Unit,
    onRunTour: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionRail(
        entries = SettingsCategory.values().toList(),
        selected = current,
        label = { stringResource(it.titleRes) },
        summary = if (rail) null else ({ stringResource(it.summaryRes) }),
        icon = { it.icon },
        entryModifier = {
            if (it == SettingsCategory.APPEARANCE) {
                Modifier.tourTarget(TourTarget.SETTINGS_APPEARANCE_SECTION)
            } else {
                Modifier
            }
        },
        onSelect = onSelect,
        modifier = modifier,
        header = {
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.setting_intro_tour), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.setting_intro_tour_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(
                        onClick = onRunTour,
                        modifier = Modifier
                            .tourTarget(TourTarget.SETTINGS_RUN_TOUR)
                            .focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(R.string.run)) }
                }
            }
        },
    )
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
    onOpenScripts: () -> Unit = {},
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

    val listState = rememberLazyListState()

    // Two level navigation: a rail beside the content on TV and tablets, a hub page
    // that opens one category at a time on phones.
    val railLayout = useSideRailNav()
    var picked by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    val current = if (railLayout) (picked ?: SettingsCategory.APPEARANCE) else picked

    // Search spans every category, so while it is open the field and its results
    // replace both panes. Picking a result opens the category holding that setting.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val searchResults = rememberSettingsSearchResults(searchQuery)

    fun closeSearch() {
        searchOpen = false
        searchQuery = ""
    }

    // Back closes search first, then the open category, then the screen.
    BackHandler(enabled = searchOpen || (!railLayout && picked != null)) {
        if (searchOpen) closeSearch() else picked = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (!railLayout && current != null) stringResource(current.titleRes)
                        else stringResource(R.string.settings_title)
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when {
                                searchOpen -> closeSearch()
                                !railLayout && picked != null -> picked = null
                                else -> onBack()
                            }
                        },
                        modifier = Modifier.focusHighlight()
                    ) { Text("←") }
                },
                actions = {
                    IconButton(
                        onClick = { if (searchOpen) closeSearch() else searchOpen = true },
                        modifier = Modifier.focusHighlight()
                    ) {
                        Icon(
                            if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = stringResource(
                                if (searchOpen) R.string.close else R.string.buffer_toolbar_search_action
                            )
                        )
                    }
                    IconButton(onClick = onOpenNetworks, modifier = Modifier.focusHighlight()) { Text("🌐") }
                }
            )
        }
    ) { padding ->

        // Tour: both settings steps point at the category panel, so make sure it is
        // the visible pane before the overlay measures its target.
        LaunchedEffect(tourActive, tourTarget) {
            if (!tourActive) return@LaunchedEffect
            when (tourTarget) {
                TourTarget.SETTINGS_APPEARANCE_SECTION,
                TourTarget.SETTINGS_RUN_TOUR -> {
                    closeSearch()
                    if (!railLayout) picked = null
                }
                else -> Unit
            }
        }

        // Reset the scroll position when the open category changes, otherwise a short
        // page opens scrolled past its own content.
        LaunchedEffect(current) {
            runCatching { listState.scrollToItem(0) }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

        if (searchOpen) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.focusHighlight()
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.clear)
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .focusRequester(searchFocus)
                    .focusHighlight(RoundedCornerShape(4.dp))
            )

            // Opening search puts the caret in the field, so the keyboard is up
            // and typing starts straight away.
            LaunchedEffect(Unit) {
                runCatching { searchFocus.requestFocus() }
            }

            HorizontalDivider()
        }

        if (searchOpen && searchQuery.isNotBlank()) {
            SettingsSearchResults(
                results = searchResults,
                onOpen = { entry ->
                    picked = entry.category
                    closeSearch()
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {

        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (railLayout || current == null) {
                SettingsNavPanel(
                    current = current,
                    rail = railLayout,
                    onSelect = { picked = it },
                    onRunTour = onRunTour,
                    modifier = if (railLayout) {
                        Modifier.width(RAIL_WIDTH).fillMaxHeight()
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
                if (railLayout) VerticalDivider()
            }

            if (current != null) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)

) {
            if (current == SettingsCategory.APPEARANCE) {
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
                    fieldLabel = stringResource(R.string.setting_chat_font),
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
                Column {
                    SettingToggle(stringResource(R.string.setting_network_tabs), s.networkTabs) {
                        onUpdate { copy(networkTabs = !networkTabs) }
                    }
                    Text(
                        stringResource(R.string.setting_network_tabs_desc),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                    )
                    SettingToggle(stringResource(R.string.setting_network_tabs_bottom), s.networkTabsAtBottom) {
                        onUpdate { copy(networkTabsAtBottom = !networkTabsAtBottom) }
                    }
                    Text(
                        stringResource(R.string.setting_network_tabs_bottom_desc),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                    )
                }
            }

            item {
                val fontSteps = listOf(0.60f, 0.70f, 0.75f, 0.80f, 0.85f, 0.90f, 0.95f, 1.00f,
                                       1.05f, 1.10f, 1.15f, 1.20f, 1.30f, 1.40f, 1.50f)
                val currentIdx = fontSteps.indexOfMinBy { kotlin.math.abs(it - s.fontScale) }
                    .coerceIn(0, fontSteps.lastIndex)
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.setting_font_size_label),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { if (currentIdx > 0) onUpdate { copy(fontScale = fontSteps[currentIdx - 1]) } },
                            enabled = currentIdx > 0,
                            modifier = Modifier.widthIn(min = 40.dp).focusHighlight(RoundedCornerShape(50))
                        ) { Text("−") }
                        Text(
                            "${(s.fontScale * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        )
                        OutlinedButton(
                            onClick = { if (currentIdx < fontSteps.lastIndex) onUpdate { copy(fontScale = fontSteps[currentIdx + 1]) } },
                            enabled = currentIdx < fontSteps.lastIndex,
                            modifier = Modifier.widthIn(min = 40.dp).focusHighlight(RoundedCornerShape(50))
                        ) { Text("+") }
                    }
                    Slider(
                        value = currentIdx.toFloat(),
                        onValueChange = { v ->
                            val idx = v.toInt().coerceIn(0, fontSteps.lastIndex)
                            onUpdate { copy(fontScale = fontSteps[idx]) }
                        },
                        valueRange = 0f..(fontSteps.lastIndex.toFloat()),
                        steps = fontSteps.lastIndex - 1,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("60%", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(
                            onClick = { onUpdate { copy(fontScale = 1.0f) } },
                            enabled = s.fontScale != 1.0f, modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                        ) {
                            Text(stringResource(R.string.settings_reset_100), style = MaterialTheme.typography.labelSmall)
                        }
                        Text("150%", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                // Gap between messages. Glyph size is the font-size slider above and the
                // leading within a message is the control below, so all three are
                // independent of each other.
                val spacingSteps = listOf(
                    0.0f to R.string.setting_line_spacing_tight,
                    0.15f to R.string.setting_line_spacing_normal,
                    0.45f to R.string.setting_line_spacing_relaxed,
                )
                SpacingStepRow(
                    label = stringResource(R.string.setting_line_spacing_label),
                    desc = stringResource(R.string.setting_line_spacing_desc),
                    steps = spacingSteps,
                    current = s.chatLineSpacing,
                    onSelect = { value -> onUpdate { copy(chatLineSpacing = value) } },
                )
            }

            item {
                // Leading within one message. Applied with Trim.None in ChatScreen so the
                // row pitch is identical across JetBrains Mono, Inter, Open Sans and any
                // custom font, instead of following each font's own vertical metrics.
                val lineHeightSteps = listOf(
                    1.0f to R.string.setting_line_spacing_tight,
                    1.15f to R.string.setting_line_spacing_normal,
                    1.35f to R.string.setting_line_spacing_relaxed,
                )
                SpacingStepRow(
                    label = stringResource(R.string.setting_font_line_height_label),
                    desc = stringResource(R.string.setting_font_line_height_desc),
                    steps = lineHeightSteps,
                    current = s.chatFontLineHeight,
                    onSelect = { value -> onUpdate { copy(chatFontLineHeight = value) } },
                )
            }

            item {
                // Offset, not an absolute size: the nicklist font is derived from the
                // pane width so dragging it wider still enlarges the text. This shifts
                // that whole ramp up or down.
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.setting_nicklist_font_label),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { onUpdate { copy(nicklistFontOffset = nicklistFontOffset - 1) } },
                            enabled = s.nicklistFontOffset > -3,
                            modifier = Modifier.widthIn(min = 40.dp).focusHighlight(RoundedCornerShape(50))
                        ) { Text("\u2212") }
                        Text(
                            if (s.nicklistFontOffset > 0) "+${s.nicklistFontOffset}" else "${s.nicklistFontOffset}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        )
                        OutlinedButton(
                            onClick = { onUpdate { copy(nicklistFontOffset = nicklistFontOffset + 1) } },
                            enabled = s.nicklistFontOffset < 4,
                            modifier = Modifier.widthIn(min = 40.dp).focusHighlight(RoundedCornerShape(50))
                        ) { Text("+") }
                    }
                    Text(
                        stringResource(R.string.setting_nicklist_font_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            }

            if (current == SettingsCategory.MEDIA) {

            item {
                Column(Modifier.fillMaxWidth()) {
                    SettingToggle(stringResource(R.string.setting_image_previews), s.imagePreviewsEnabled) {
                        onUpdate { copy(imagePreviewsEnabled = !imagePreviewsEnabled) }
                    }
                    Text(stringResource(R.string.setting_image_previews_desc), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                }
            }

            if (s.imagePreviewsEnabled) {
                item {
                    Column(Modifier.fillMaxWidth()) {
                        SettingToggle(stringResource(R.string.setting_previews_wifi_only), s.imagePreviewsWifiOnly) {
                            onUpdate { copy(imagePreviewsWifiOnly = !imagePreviewsWifiOnly) }
                        }
                        Text(stringResource(R.string.setting_previews_wifi_only_desc), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }

            }

            if (current == SettingsCategory.CHAT) {
            item {
                SettingToggle(stringResource(R.string.setting_colorise_nicks), s.colorizeNicks) { onUpdate { copy(colorizeNicks = !colorizeNicks) } }
            }

            // Own nick colour: Auto (hash-based) or Custom (colour wheel)
            item {
                var showPicker by remember { mutableStateOf(false) }
                val customArgb = s.ownNickColorInt
                val customColor = if (customArgb != null) Color(customArgb) else null

                if (showPicker) {
                    NickColourPickerDialog(
                        initial = customColor ?: Color(0xFF_FF6600.toInt()),
                        onDismiss = { showPicker = false },
                        onConfirm = { picked ->
                            onUpdate { copy(ownNickColorInt = picked.toArgb()) }
                            showPicker = false
                        },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.setting_own_nick_colour_title))
                        Text(
                            if (customColor == null)
                                stringResource(R.string.setting_own_nick_colour_auto)
                            else
                                stringResource(R.string.setting_own_nick_colour_custom),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (customColor != null) {
                            // Swatch showing current colour — tap to re-pick
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(customColor)
                                    .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    .focusHighlight(CircleShape)
                                    .clickable { showPicker = true }
                            )
                            TextButton(onClick = { onUpdate { copy(ownNickColorInt = null) } }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                                Text(stringResource(R.string.setting_own_nick_colour_reset))
                            }
                        } else {
                            OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                                Text(stringResource(R.string.setting_own_nick_colour_pick))
                            }
                        }
                    }
                }
            }

            item {
                SettingToggle(stringResource(R.string.setting_mirc_colours), s.mircColorsEnabled) { onUpdate { copy(mircColorsEnabled = !mircColorsEnabled) } }
            }

            item {
                SettingToggle(stringResource(R.string.setting_ansi_colours), s.ansiColorsEnabled) { onUpdate { copy(ansiColorsEnabled = !ansiColorsEnabled) } }
            }
            item {
                SettingToggle(stringResource(R.string.setting_art_detection), s.artDetectionEnabled) { onUpdate { copy(artDetectionEnabled = !artDetectionEnabled) } }
            }
            item { SettingToggle(stringResource(R.string.setting_color_channel_events), s.colorChannelEvents) { onUpdate { copy(colorChannelEvents = !colorChannelEvents) } } }
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
            item { TimestampStylePicker(s.timestampStyle) { v -> onUpdate { copy(timestampStyle = v) } } }
            item {
                var showPicker by remember { mutableStateOf(false) }
                val tsColor = s.timestampColorInt?.let { Color(it) }

                if (showPicker) {
                    NickColourPickerDialog(
                        initial = tsColor ?: Color(0xFF_9E9E9E.toInt()),
                        onDismiss = { showPicker = false },
                        onConfirm = { picked ->
                            onUpdate { copy(timestampColorInt = picked.toArgb()) }
                            showPicker = false
                        },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.setting_timestamp_colour))
                        Text(
                            if (tsColor == null) stringResource(R.string.setting_timestamp_colour_default)
                            else stringResource(R.string.setting_own_nick_colour_custom),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (tsColor != null) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(tsColor)
                                    .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    .focusHighlight(CircleShape)
                                    .clickable { showPicker = true }
                            )
                            TextButton(onClick = { onUpdate { copy(timestampColorInt = null) } }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                                Text(stringResource(R.string.setting_own_nick_colour_reset))
                            }
                        } else {
                            OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                                Text(stringResource(R.string.setting_own_nick_colour_pick))
                            }
                        }
                    }
                }
            }
            item { NickStylePicker(s.nickStyle) { v -> onUpdate { copy(nickStyle = v) } } }
            item {
                ChatFormatPreview(
                    timestampStyle = s.timestampStyle,
                    timestampColor = s.timestampColorInt?.let { Color(it) },
                    nickStyle = s.nickStyle,
                )
            }
            item { SettingToggle(stringResource(R.string.setting_hide_motd), s.hideMotdOnConnect) { onUpdate { copy(hideMotdOnConnect = !hideMotdOnConnect) } } }
            item { SettingToggle(stringResource(R.string.setting_hide_joinpartquit), s.hideJoinPartQuit) { onUpdate { copy(hideJoinPartQuit = !hideJoinPartQuit) } } }
            item { SettingToggle(stringResource(R.string.setting_hide_away_notify), s.hideAwayNotify) { onUpdate { copy(hideAwayNotify = !hideAwayNotify) } } }
            item { SettingToggle(stringResource(R.string.setting_hide_topic_on_entry), s.hideTopicOnEntry) { onUpdate { copy(hideTopicOnEntry = !hideTopicOnEntry) } } }
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
            }

            if (current == SettingsCategory.ALIASES) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        "Define your own commands. In the expansion use \$channel (current buffer), " +
                        "\$network/\$server, \$1–\$9 (arguments), \$* (all arguments), \$me (your nick). " +
                        "Example: name “bl”, expansion “msg *backlog \$channel \$1” lets you type /bl 50.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    val aliases = s.commandAliases.toSortedMap()
                    if (aliases.isEmpty()) {
                        Text(stringResource(R.string.settings_no_aliases), style = MaterialTheme.typography.bodySmall)
                    } else {
                        aliases.forEach { (name, expansion) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("/$name", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        expansion,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onUpdate { copy(commandAliases = commandAliases - name) } }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                                    Text(stringResource(R.string.remove))
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    var newName by remember { mutableStateOf("") }
                    var newExpansion by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.settings_alias_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = newExpansion,
                        onValueChange = { newExpansion = it },
                        label = { Text(stringResource(R.string.settings_alias_expansion)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    val cleanName = newName.trim().removePrefix("/").lowercase()
                    val valid = cleanName.isNotBlank() && newExpansion.isNotBlank() &&
                        cleanName.none { it.isWhitespace() || it == '/' }
                    Button(
                        onClick = {
                            onUpdate { copy(commandAliases = commandAliases + (cleanName to newExpansion.trim())) }
                            newName = ""
                            newExpansion = ""
                        },
                        enabled = valid, modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                    ) { Text(stringResource(R.string.settings_add_alias)) }
                }
            }

            }

            if (current == SettingsCategory.HIGHLIGHTS) {

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

            }

            if (current == SettingsCategory.IRC) {

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
                        OutlinedButton(onClick = onOpenIgnoreList, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.manage)) }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_scripts), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.settings_scripts_desc), style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = onOpenScripts, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.manage)) }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_raw_log), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.settings_raw_log_desc), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = s.rawLog,
                            onCheckedChange = { want -> onUpdate { copy(rawLog = want) } },
                            modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
                        )
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
                    // connectOnBoot is inert without the foreground service, so clear it here
                    // rather than leaving a stored true behind a disabled switch.
                    onUpdate {
                        copy(
                            keepAliveInBackground = newValue,
                            connectOnBoot = if (newValue) connectOnBoot else false,
                        )
                    }

                    // When enabling, guide user to disable battery optimizations (user-driven, Play-safe)
                    if (newValue && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val pm = ctx.getSystemService(PowerManager::class.java)
                        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                            showBatteryHelpDialog = true
                        }
                    }
                }
            }

            item {
                // Only meaningful with keep-alive on: without the foreground service a
                // boot-time connection is killed almost immediately, so the toggle is
                // disabled (and forced off) rather than doing nothing.
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.setting_connect_on_boot), modifier = Modifier.weight(1f))
                        Switch(
                            // Store what the switch shows. Deriving `checked` from
                            // keepAliveInBackground while toggling only connectOnBoot let the
                            // stored value stay true behind an unchecked switch, then snap back
                            // on when keep-alive was re-enabled.
                            checked = s.connectOnBoot,
                            enabled = s.keepAliveInBackground,
                            onCheckedChange = { want -> onUpdate { copy(connectOnBoot = want) } },
                            modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
                        )
                    }
                    Text(
                        stringResource(R.string.setting_connect_on_boot_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.setting_boot_wifi_only))
                            Text(
                                stringResource(R.string.setting_boot_wifi_only_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = s.connectOnBootWifiOnly,
                            enabled = s.keepAliveInBackground && s.connectOnBoot,
                            onCheckedChange = { want -> onUpdate { copy(connectOnBootWifiOnly = want) } },
                            modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
                        )
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

            item {
                Column(Modifier.fillMaxWidth()) {
                    SettingToggle(stringResource(R.string.setting_rejoin_on_kick), s.rejoinOnKick) {
                        onUpdate { copy(rejoinOnKick = !rejoinOnKick) }
                    }
                    Text(
                        stringResource(R.string.setting_rejoin_on_kick_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }

            if (current == SettingsCategory.NOTIFICATIONS) {

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

            }

            if (current == SettingsCategory.LOGGING) {

            item { SettingToggle(stringResource(R.string.setting_enable_logging), s.loggingEnabled) { onUpdate { copy(loggingEnabled = !loggingEnabled) } } }
            item { SettingToggle(stringResource(R.string.setting_log_server), s.logServerBuffer) { onUpdate { copy(logServerBuffer = !logServerBuffer) } } }

            item {
                val label = if (s.logFolderUri.isNullOrBlank()) stringResource(R.string.setting_log_internal) else stringResource(R.string.setting_log_custom)
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setting_log_folder), style = MaterialTheme.typography.titleSmall)
                    Text(label, style = MaterialTheme.typography.bodySmall)
                    if (!s.logFolderUri.isNullOrBlank()) {
                        Text(s.logFolderUri, style = MaterialTheme.typography.bodySmall)
                    }
                    // Permission-lost warning. Surfaces when LogWriter has flagged this
                    // tree URI as unreadable - typically after a backup-restore on a fresh
                    // install (SAF permission grants are stored per-install in the system
                    // and don't travel through any backup format, so the URI string in
                    // settings outlives the grant). Without this badge, the user sees no
                    // scrollback after restoring and has no obvious cue why; logging
                    // silently no-ops too. The "Choose folder" button right below this
                    // box is the one-tap fix - re-picking the same (or a new) folder
                    // grants a fresh persistable permission and the badge clears
                    // immediately when settings update.
                    if (state.logFolderUnreadable) {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.setting_log_permission_lost),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.setting_choose_folder)) }
                        if (!s.logFolderUri.isNullOrBlank()) {
                            OutlinedButton(onClick = {
                                runCatching {
                                    ctx.contentResolver.releasePersistableUriPermission(
                                        Uri.parse(s.logFolderUri),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    )
                                }
                                onUpdate { copy(logFolderUri = null) }
                            }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
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
                        // 0 = keep logs forever (purge disabled); otherwise clamp to 1..365.
                        onUpdate { copy(retentionDays = n.coerceIn(0, 365)) }
                    },
                    label = { Text(stringResource(R.string.setting_retention_days)) },
                    supportingText = {
                        Text(
                            if (s.retentionDays <= 0) stringResource(R.string.setting_retention_forever)
                            else stringResource(R.string.setting_retention_days_hint)
                        )
                    },
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

            }

            if (current == SettingsCategory.PRIVACY) {

            item {
                Column(Modifier.fillMaxWidth()) {
                    SettingToggle(stringResource(R.string.setting_send_typing), s.sendTypingIndicator) {
                        onUpdate { copy(sendTypingIndicator = !sendTypingIndicator) }
                    }
                    Text(stringResource(R.string.setting_send_typing_desc), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                }
            }

            item {
                Column(Modifier.fillMaxWidth()) {
                    SettingToggle(stringResource(R.string.setting_receive_typing), s.receiveTypingIndicator) {
                        onUpdate { copy(receiveTypingIndicator = !receiveTypingIndicator) }
                    }
                    Text(stringResource(R.string.setting_receive_typing_desc), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                }
            }

            }

            if (current == SettingsCategory.HISTORY) {

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

            }

            if (current == SettingsCategory.TRANSFERS) {

            item { SettingToggle(stringResource(R.string.setting_enable_dcc), s.dccEnabled) { onUpdate { copy(dccEnabled = !dccEnabled) } } }

            if (s.dccEnabled) {
                item {
                    SettingToggle(stringResource(R.string.setting_dcc_secure), s.dccSecure) {
                        onUpdate { copy(dccSecure = !dccSecure) }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.setting_dcc_secure_desc),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = androidx.compose.ui.Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            item {
                val dccFolderLabel = if (s.dccDownloadFolderUri.isNullOrBlank()) stringResource(R.string.setting_dcc_downloads_default) else stringResource(R.string.setting_dcc_downloads_custom)
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setting_download_folder), style = MaterialTheme.typography.titleSmall)
                    Text(dccFolderLabel, style = MaterialTheme.typography.bodySmall)
                    if (!s.dccDownloadFolderUri.isNullOrBlank()) {
                        Text(s.dccDownloadFolderUri, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { dccFolderPicker.launch(null) }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.setting_choose_folder)) }
                        if (!s.dccDownloadFolderUri.isNullOrBlank()) {
                            OutlinedButton(onClick = {
                                runCatching {
                                    ctx.contentResolver.releasePersistableUriPermission(
                                        Uri.parse(s.dccDownloadFolderUri),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    )
                                }
                                onUpdate { copy(dccDownloadFolderUri = null) }
                            }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
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

            }

            if (current == SettingsCategory.BACKUP) {

            item {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.setting_backup_export_desc),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { exportBackupLauncher.launch(backupFileName) }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                            Text(stringResource(R.string.settings_export_backup))
                        }
                        OutlinedButton(onClick = {
                            importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                            Text(stringResource(R.string.settings_restore_backup))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.setting_restore_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
                }
            }
            }
        }

        }
        }
    }
    // Restore confirmation dialog
    if (showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showRestoreConfirmDialog = false
                pendingRestoreUri = null
            },
            title = { Text(stringResource(R.string.settings_restore_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.setting_restore_confirm)
                )
            },
            confirmButton = {
                Button(onClick = {
                    showRestoreConfirmDialog = false
                    pendingRestoreUri?.let { uri -> onImportBackup(uri) }
                    pendingRestoreUri = null
                }, modifier = Modifier.tvInitialFocus().focusHighlight(RoundedCornerShape(50))) {
                    Text(stringResource(R.string.settings_restore_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestoreConfirmDialog = false
                    pendingRestoreUri = null
                }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                    Text(stringResource(R.string.cancel))
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
                Text(if (isError) stringResource(R.string.setting_backup_error) else stringResource(R.string.setting_backup_done))
            },
            text = { Text(state.backupMessage) },
            confirmButton = {
                TextButton(onClick = {
                    showBackupResultDialog = false
                    onClearBackupMessage()
                }, modifier = Modifier.tvInitialFocus().focusHighlight(RoundedCornerShape(50))) {
                    Text(stringResource(R.string.settings_ok))
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
                    Text(
                        stringResource(R.string.setting_battery_tip)
                    )
                    if (isOnePlus) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.setting_battery_oneplus)
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
                }, modifier = Modifier.tvInitialFocus().focusHighlight(RoundedCornerShape(50))) {
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
                    }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                        Text(stringResource(R.string.setting_battery_optimization))
                    }
                    TextButton(onClick = { showBatteryHelpDialog = false }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
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
    val systemLabel = stringResource(R.string.setting_lang_system)
    val currentLabel = languages.firstOrNull { it.code == currentCode }?.nativeName ?: systemLabel

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.welcome_language_label)) },
            modifier = Modifier
                .fillMaxWidth()
                .focusHighlight(RoundedCornerShape(4.dp))
                // D-pad path: the anchor opens its menu from a tap detector, so a
                // remote or keyboard select needs to toggle the menu explicitly.
                .dpadActivate { expanded = !expanded }
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
        Switch(checked = checked, onCheckedChange = { onClick() }, modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)))
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
        ThemeMode.TERMINAL -> stringResource(R.string.theme_terminal)
        ThemeMode.SYSTEM -> stringResource(R.string.theme_system_default)
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.theme_label)) },
            modifier = Modifier
                .fillMaxWidth()
                .focusHighlight(RoundedCornerShape(4.dp))
                // D-pad path: the anchor opens its menu from a tap detector, so a
                // remote or keyboard select needs to toggle the menu explicitly.
                .dpadActivate { expanded = !expanded }
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.theme_dark)) }, onClick = { onPick(ThemeMode.DARK); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.theme_light)) }, onClick = { onPick(ThemeMode.LIGHT); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.settings_matrix_theme)) }, onClick = { onPick(ThemeMode.MATRIX); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.theme_terminal)) }, onClick = { onPick(ThemeMode.TERMINAL); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.settings_system_default)) }, onClick = { onPick(ThemeMode.SYSTEM); expanded = false })
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
                .focusHighlight(RoundedCornerShape(4.dp))
                // D-pad path: the anchor opens its menu from a tap detector, so a
                // remote or keyboard select needs to toggle the menu explicitly.
                .dpadActivate { expanded = !expanded }
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Open Sans is the default UI font.
            DropdownMenuItem(text = { Text(stringResource(R.string.font_open_sans)) }, onClick = { onPick(FontChoice.OPEN_SANS); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.font_inter)) }, onClick = { onPick(FontChoice.INTER); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.font_monospace)) }, onClick = { onPick(FontChoice.MONOSPACE); expanded = false })
            if (onPickCustom != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_custom_font)) },
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
            label = { Text(stringResource(R.string.chat_font_style)) },
            modifier = Modifier
                .fillMaxWidth()
                .focusHighlight(RoundedCornerShape(4.dp))
                // D-pad path: the anchor opens its menu from a tap detector, so a
                // remote or keyboard select needs to toggle the menu explicitly.
                .dpadActivate { expanded = !expanded }
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.style_regular)) }, onClick = { onPick(ChatFontStyle.REGULAR); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.style_bold)) }, onClick = { onPick(ChatFontStyle.BOLD); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.style_italic)) }, onClick = { onPick(ChatFontStyle.ITALIC); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.style_bold_italic)) }, onClick = { onPick(ChatFontStyle.BOLD_ITALIC); expanded = false })
        }
    }
}

/** Sample of how a chat line will look with the chosen brackets and colour. */
@Composable
private fun ChatFormatPreview(
    timestampStyle: TimestampStyle,
    timestampColor: Color?,
    nickStyle: NickStyle,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            buildAnnotatedString {
                val ts = "${timestampStyle.open}12:34:56${timestampStyle.close} "
                if (timestampColor != null) {
                    withStyle(SpanStyle(color = timestampColor)) { append(ts) }
                } else {
                    append(ts)
                }
                append("${nickStyle.open}nick${nickStyle.close} ")
                append(stringResource(R.string.setting_chat_format_sample))
            },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimestampStylePicker(current: TimestampStyle, onPick: (TimestampStyle) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // Show the brackets themselves rather than naming them, since the name of a
    // bracket is less recognisable than the bracket.
    fun label(style: TimestampStyle): String =
        if (style == TimestampStyle.NONE) "12:34:56" else "${style.open}12:34:56${style.close}"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = label(current),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.setting_timestamp_style)) },
            modifier = Modifier
                .fillMaxWidth()
                .focusHighlight(RoundedCornerShape(4.dp))
                .dpadActivate { expanded = !expanded }
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TimestampStyle.values().forEach { style ->
                DropdownMenuItem(
                    text = { Text(label(style)) },
                    onClick = { onPick(style); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NickStylePicker(current: NickStyle, onPick: (NickStyle) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun label(style: NickStyle): String = "${style.open}nick${style.close}"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = label(current),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.setting_nick_style)) },
            modifier = Modifier
                .fillMaxWidth()
                .focusHighlight(RoundedCornerShape(4.dp))
                .dpadActivate { expanded = !expanded }
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NickStyle.values().forEach { style ->
                DropdownMenuItem(
                    text = { Text(label(style)) },
                    onClick = { onPick(style); expanded = false },
                )
            }
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
            label = { Text(stringResource(R.string.vibration_intensity)) },
            modifier = Modifier
                .fillMaxWidth()
                .focusHighlight(RoundedCornerShape(4.dp))
                // D-pad path: the anchor opens its menu from a tap detector, so a
                // remote or keyboard select needs to toggle the menu explicitly.
                .dpadActivate { expanded = !expanded }
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.vibration_low)) }, onClick = { onPick(VibrateIntensity.LOW); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.vibration_medium)) }, onClick = { onPick(VibrateIntensity.MEDIUM); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.vibration_high)) }, onClick = { onPick(VibrateIntensity.HIGH); expanded = false })
        }
    }
}

/**
 * Three-way Tight / Normal / Relaxed selector, shared by the chat line spacing and font
 * line height settings. [steps] pairs each stored value with its label resource, and
 * [current] is matched against those values with a tolerance so a float round-trip
 * through JSON still highlights the right button.
 */
@Composable
private fun SpacingStepRow(
    label: String,
    desc: String,
    steps: List<Pair<Float, Int>>,
    current: Float,
    onSelect: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            steps.forEach { (value, labelRes) ->
                val selected = kotlin.math.abs(current - value) < 0.02f
                if (selected) {
                    Button(
                        onClick = { onSelect(value) },
                        modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge) }
                } else {
                    OutlinedButton(
                        onClick = { onSelect(value) },
                        modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50))
                    ) { Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}
