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

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.ui.SUPPORTED_LANGUAGES
import com.boxlabs.hexdroid.ui.SettingsCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

/**
 * One searchable setting: the label shown on its own row, the category page that
 * holds it, an optional description line, and extra terms it can be found by.
 */
data class SettingsSearchEntry(
    val titleRes: Int,
    val category: SettingsCategory,
    val descRes: Int? = null,
    val keywords: String = "",
)

/**
 * Every setting the search can reach, in the order the categories are listed.
 */
val SETTINGS_SEARCH_INDEX: List<SettingsSearchEntry> = listOf(
    // Appearance
    SettingsSearchEntry(R.string.welcome_language_label, SettingsCategory.APPEARANCE,
        keywords = "language locale translation"),
    SettingsSearchEntry(R.string.theme_label, SettingsCategory.APPEARANCE,
        keywords = "theme dark light matrix terminal colour color"),
    SettingsSearchEntry(R.string.setting_ui_font, SettingsCategory.APPEARANCE,
        keywords = "font typeface custom"),
    SettingsSearchEntry(R.string.setting_chat_font, SettingsCategory.APPEARANCE,
        keywords = "font typeface custom monospace"),
    SettingsSearchEntry(R.string.chat_font_style, SettingsCategory.APPEARANCE,
        keywords = "bold italic"),
    SettingsSearchEntry(R.string.setting_compact_mode, SettingsCategory.APPEARANCE,
        keywords = "density compact"),
    SettingsSearchEntry(R.string.setting_network_tabs, SettingsCategory.APPEARANCE,
        descRes = R.string.setting_network_tabs_desc, keywords = "tabs"),
    SettingsSearchEntry(R.string.setting_network_tabs_bottom, SettingsCategory.APPEARANCE,
        descRes = R.string.setting_network_tabs_bottom_desc, keywords = "tabs bottom"),
    SettingsSearchEntry(R.string.setting_font_size_label, SettingsCategory.APPEARANCE,
        keywords = "text size scale zoom bigger smaller"),
    SettingsSearchEntry(R.string.setting_line_spacing_label, SettingsCategory.APPEARANCE,
        descRes = R.string.setting_line_spacing_desc, keywords = "spacing gap"),
    SettingsSearchEntry(R.string.setting_font_line_height_label, SettingsCategory.APPEARANCE,
        descRes = R.string.setting_font_line_height_desc, keywords = "leading line height"),
    SettingsSearchEntry(R.string.setting_nicklist_font_label, SettingsCategory.APPEARANCE,
        descRes = R.string.setting_nicklist_font_desc, keywords = "nicklist font size"),

    // Chat and messages
    SettingsSearchEntry(R.string.setting_colorise_nicks, SettingsCategory.CHAT,
        keywords = "nick colour color"),
    SettingsSearchEntry(R.string.setting_own_nick_colour_title, SettingsCategory.CHAT,
        keywords = "own nick colour color"),
    SettingsSearchEntry(R.string.setting_mirc_colours, SettingsCategory.CHAT,
        keywords = "mirc colour color codes"),
    SettingsSearchEntry(R.string.setting_ansi_colours, SettingsCategory.CHAT,
        keywords = "ansi escape colour color"),
    SettingsSearchEntry(R.string.setting_art_detection, SettingsCategory.CHAT,
        keywords = "ascii art"),
    SettingsSearchEntry(R.string.setting_color_channel_events, SettingsCategory.CHAT,
        keywords = "join part quit colour color"),
    SettingsSearchEntry(R.string.setting_show_topic_bar, SettingsCategory.CHAT,
        keywords = "topic bar"),
    SettingsSearchEntry(R.string.setting_show_timestamps, SettingsCategory.CHAT,
        keywords = "timestamp time clock"),
    SettingsSearchEntry(R.string.setting_timestamp_format, SettingsCategory.CHAT,
        keywords = "timestamp time format seconds"),
    SettingsSearchEntry(R.string.setting_timestamp_style, SettingsCategory.CHAT,
        keywords = "timestamp brackets"),
    SettingsSearchEntry(R.string.setting_timestamp_colour, SettingsCategory.CHAT,
        keywords = "timestamp colour color"),
    SettingsSearchEntry(R.string.setting_nick_style, SettingsCategory.CHAT,
        keywords = "nick brackets"),
    SettingsSearchEntry(R.string.setting_hide_motd, SettingsCategory.CHAT,
        keywords = "motd hide"),
    SettingsSearchEntry(R.string.setting_hide_joinpartquit, SettingsCategory.CHAT,
        keywords = "join part quit hide"),
    SettingsSearchEntry(R.string.setting_hide_away_notify, SettingsCategory.CHAT,
        keywords = "away hide"),
    SettingsSearchEntry(R.string.setting_hide_topic_on_entry, SettingsCategory.CHAT,
        keywords = "topic hide"),
    SettingsSearchEntry(R.string.setting_show_buffers_default, SettingsCategory.CHAT,
        keywords = "buffer list sidebar landscape"),
    SettingsSearchEntry(R.string.setting_show_nicklist_default, SettingsCategory.CHAT,
        keywords = "nicklist sidebar landscape"),
    SettingsSearchEntry(R.string.setting_portrait_nicklist_overlay, SettingsCategory.CHAT,
        descRes = R.string.setting_portrait_nicklist_overlay_desc, keywords = "nicklist portrait overlay"),

    // Media previews
    SettingsSearchEntry(R.string.setting_image_previews, SettingsCategory.MEDIA,
        descRes = R.string.setting_image_previews_desc, keywords = "image preview thumbnail"),
    SettingsSearchEntry(R.string.setting_previews_wifi_only, SettingsCategory.MEDIA,
        descRes = R.string.setting_previews_wifi_only_desc, keywords = "wifi mobile data preview"),

    // Aliases
    SettingsSearchEntry(R.string.settings_custom_aliases, SettingsCategory.ALIASES,
        keywords = "alias command shortcut slash"),

    // Highlights
    SettingsSearchEntry(R.string.setting_highlight_on_nick, SettingsCategory.HIGHLIGHTS,
        keywords = "highlight nick ping mention"),
    SettingsSearchEntry(R.string.setting_extra_highlights, SettingsCategory.HIGHLIGHTS,
        keywords = "highlight words ping mention"),

    // IRC
    SettingsSearchEntry(R.string.setting_ignore_list, SettingsCategory.IRC,
        descRes = R.string.setting_ignore_list_desc, keywords = "ignore block mute"),
    SettingsSearchEntry(R.string.settings_scripts, SettingsCategory.IRC,
        descRes = R.string.settings_scripts_desc, keywords = "script hex automation"),
    SettingsSearchEntry(R.string.settings_raw_log, SettingsCategory.IRC,
        descRes = R.string.settings_raw_log_desc, keywords = "raw log debug protocol"),
    SettingsSearchEntry(R.string.setting_quit_message, SettingsCategory.IRC,
        keywords = "quit message"),
    SettingsSearchEntry(R.string.setting_part_message, SettingsCategory.IRC,
        keywords = "part leave message"),
    SettingsSearchEntry(R.string.setting_connection_status, SettingsCategory.IRC,
        keywords = "connection status notification"),
    SettingsSearchEntry(R.string.setting_keep_alive, SettingsCategory.IRC,
        keywords = "keep alive background service battery"),
    SettingsSearchEntry(R.string.setting_connect_on_boot, SettingsCategory.IRC,
        descRes = R.string.setting_connect_on_boot_desc, keywords = "boot startup reconnect"),
    SettingsSearchEntry(R.string.setting_boot_wifi_only, SettingsCategory.IRC,
        descRes = R.string.setting_boot_wifi_only_desc, keywords = "wifi boot mobile data"),
    SettingsSearchEntry(R.string.setting_auto_reconnect, SettingsCategory.IRC,
        keywords = "reconnect automatic"),
    SettingsSearchEntry(R.string.setting_webpush, SettingsCategory.IRC,
        descRes = R.string.setting_webpush_desc,
        keywords = "push notifications unifiedpush distributor webpush background wake"),
    SettingsSearchEntry(R.string.setting_reconnect_interval, SettingsCategory.IRC,
        descRes = R.string.setting_reconnect_interval_desc, keywords = "reconnect delay seconds"),
    SettingsSearchEntry(R.string.setting_rejoin_on_kick, SettingsCategory.IRC,
        descRes = R.string.setting_rejoin_on_kick_desc, keywords = "kick rejoin"),

    // Notifications
    SettingsSearchEntry(R.string.setting_enable_notifications, SettingsCategory.NOTIFICATIONS,
        keywords = "notification alert"),
    SettingsSearchEntry(R.string.setting_notify_highlights, SettingsCategory.NOTIFICATIONS,
        keywords = "notification highlight ping mention"),
    SettingsSearchEntry(R.string.setting_notify_pm, SettingsCategory.NOTIFICATIONS,
        keywords = "notification private message query pm"),
    SettingsSearchEntry(R.string.setting_sound_highlight, SettingsCategory.NOTIFICATIONS,
        keywords = "sound audio alert"),
    SettingsSearchEntry(R.string.setting_vibrate_highlight, SettingsCategory.NOTIFICATIONS,
        keywords = "vibrate haptic buzz"),
    SettingsSearchEntry(R.string.vibration_intensity, SettingsCategory.NOTIFICATIONS,
        keywords = "vibrate haptic strength"),

    // Logging
    SettingsSearchEntry(R.string.setting_enable_logging, SettingsCategory.LOGGING,
        keywords = "log history file"),
    SettingsSearchEntry(R.string.setting_log_server, SettingsCategory.LOGGING,
        keywords = "log server buffer"),
    SettingsSearchEntry(R.string.setting_log_folder, SettingsCategory.LOGGING,
        keywords = "log folder directory storage"),
    SettingsSearchEntry(R.string.setting_retention_days, SettingsCategory.LOGGING,
        descRes = R.string.setting_retention_days_hint, keywords = "retention purge delete days"),
    SettingsSearchEntry(R.string.setting_max_scrollback, SettingsCategory.LOGGING,
        keywords = "scrollback lines memory"),

    // Privacy
    SettingsSearchEntry(R.string.setting_send_typing, SettingsCategory.PRIVACY,
        descRes = R.string.setting_send_typing_desc, keywords = "typing indicator"),
    SettingsSearchEntry(R.string.setting_receive_typing, SettingsCategory.PRIVACY,
        descRes = R.string.setting_receive_typing_desc, keywords = "typing indicator"),

    // Server-side history
    SettingsSearchEntry(R.string.setting_history_limit, SettingsCategory.HISTORY,
        descRes = R.string.setting_history_limit_desc, keywords = "chathistory playback backlog"),
    SettingsSearchEntry(R.string.setting_count_unread, SettingsCategory.HISTORY,
        keywords = "unread badge history"),
    SettingsSearchEntry(R.string.setting_trigger_notif, SettingsCategory.HISTORY,
        keywords = "notification history playback"),

    // File transfers
    SettingsSearchEntry(R.string.setting_enable_dcc, SettingsCategory.TRANSFERS,
        keywords = "dcc file transfer"),
    SettingsSearchEntry(R.string.setting_dcc_secure, SettingsCategory.TRANSFERS,
        descRes = R.string.setting_dcc_secure_desc, keywords = "dcc tls secure ssl"),
    SettingsSearchEntry(R.string.setting_download_folder, SettingsCategory.TRANSFERS,
        keywords = "dcc download folder directory"),
    SettingsSearchEntry(R.string.setting_incoming_port_min, SettingsCategory.TRANSFERS,
        keywords = "dcc port range incoming"),
    SettingsSearchEntry(R.string.setting_incoming_port_max, SettingsCategory.TRANSFERS,
        keywords = "dcc port range incoming"),

    // Backup and restore
    SettingsSearchEntry(R.string.settings_export_backup, SettingsCategory.BACKUP,
        descRes = R.string.setting_backup_export_desc, keywords = "backup export save json"),
    SettingsSearchEntry(R.string.settings_restore_backup, SettingsCategory.BACKUP,
        descRes = R.string.setting_restore_desc, keywords = "restore import backup json"),
)

/** One indexed setting paired with the folded text it is matched against. */
private typealias SearchRow = Pair<SettingsSearchEntry, String>

/** Combining marks left behind after decomposing accented characters. */
private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** Lower-cases */
private fun foldForSearch(text: String): String =
    Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")

/** Every language the app ships strings for. */
private val SEARCH_LOCALES: List<Locale> =
    SUPPORTED_LANGUAGES.map { Locale.forLanguageTag(it.code) }

/**
 * Strings for one locale, regardless of the language the app is displaying.
 */
private fun localisedResources(context: Context, locale: Locale): Resources {
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    return context.createConfigurationContext(config).resources
}

/**
 * Folds every indexed setting into one searchable string per entry, holding its
 * label, description and category name in each of [resources] plus its extra
 * terms.
 */
private fun buildHaystack(resources: List<Resources>): List<SearchRow> =
    SETTINGS_SEARCH_INDEX.map { entry ->
        val text = buildString {
            for (res in resources) {
                append(res.getString(entry.titleRes))
                append(' ')
                if (entry.descRes != null) {
                    append(res.getString(entry.descRes))
                    append(' ')
                }
                append(res.getString(entry.category.titleRes))
                append(' ')
            }
            append(entry.keywords)
        }
        entry to foldForSearch(text)
    }

@Volatile
private var allLanguagesHaystack: List<SearchRow>? = null

/**
 * Matches [query] against every indexed setting and returns the entries whose
 * label, description, category name or extra terms contain all of the words
 * typed, in any of the app's languages.
 */
@Composable
fun rememberSettingsSearchResults(query: String): List<SettingsSearchEntry> {
    val context = LocalContext.current
    // Rebuilt on a configuration change so the labels follow the app language.
    val config = LocalConfiguration.current

    // The displayed language is matched straight away, so the first keystroke
    // never waits. The other translations load off the main thread and replace
    // this once ready.
    val displayed = remember(config) { buildHaystack(listOf(context.resources)) }

    val allLanguages by produceState(allLanguagesHaystack, context) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                runCatching { buildHaystack(SEARCH_LOCALES.map { localisedResources(context, it) }) }
                    .getOrNull()
                    ?.also { allLanguagesHaystack = it }
            }
        }
    }

    val haystack = allLanguages ?: displayed

    return remember(haystack, query) {
        val words = foldForSearch(query.trim()).split(' ', '\t').filter { it.isNotBlank() }
        if (words.isEmpty()) {
            emptyList()
        } else {
            haystack.filter { (_, text) -> words.all { text.contains(it) } }.map { it.first }
        }
    }
}

/**
 * Flat list of search hits, each showing the setting and the category it lives
 * on. Selecting a row opens that category. A query with no hits shows a struck
 * through magnifier rather than a message.
 */
@Composable
fun SettingsSearchResults(
    results: List<SettingsSearchEntry>,
    onOpen: (SettingsSearchEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (results.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.SearchOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(results.size) { index ->
            val entry = results[index]
            SettingsSearchRow(entry = entry, onOpen = onOpen)
        }
    }
}

/**
 * One search hit. Split out so the label and category name are resolved per row
 * rather than inside the list scope.
 */
@Composable
private fun SettingsSearchRow(
    entry: SettingsSearchEntry,
    onOpen: (SettingsSearchEntry) -> Unit,
) {
    SectionRow(
        label = stringResource(entry.titleRes),
        summary = stringResource(entry.category.titleRes),
        icon = entry.category.icon,
        onClick = { onOpen(entry) },
    )
}
