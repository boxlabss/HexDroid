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

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.boxlabs.hexdroid.R
import java.util.Locale

/**
 * Data class for supported languages.
 * [code] is the BCP-47 tag (e.g. "en", "es").
 * [nativeName] is shown to users in their own language so they can always find it.
 */
data class SupportedLanguage(
    val code: String,
    val nativeName: String,
)

/** All languages that have a values-xx/strings.xml translation. */
val SUPPORTED_LANGUAGES = listOf(
    SupportedLanguage("en", "English"),
    SupportedLanguage("de", "Deutsch"),
    SupportedLanguage("es", "Español"),
    SupportedLanguage("fr", "Français"),
    SupportedLanguage("it", "Italiano"),
    SupportedLanguage("nl", "Nederlands"),
    SupportedLanguage("pl", "Polski"),
    SupportedLanguage("pt", "Português"),
    SupportedLanguage("ru", "Русский"),
    SupportedLanguage("tr", "Türkçe"),
    SupportedLanguage("ar", "العربية"),
    SupportedLanguage("ja", "日本語"),
    SupportedLanguage("ko", "한국어"),
    SupportedLanguage("zh", "中文"),
)

/** Regex that matches valid IRC nicknames (RFC 2812 plus common extensions). */
private val NICK_REGEX = Regex("^[A-Za-z_\\\\\\[\\]{}^`|][A-Za-z0-9_\\\\\\[\\]{}^`|\\-]{0,15}$")

/**
 * Welcome / setup screen displayed before the intro tour on first launch.
 *
 * Lets the user pick a display language and set their IRC nickname, which is then applied
 * to all default server profiles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeScreen(
    onContinue: (languageCode: String, nick: String) -> Unit,
) {
    val context = LocalContext.current

    // Detect the device's current locale to pre-select.
    val deviceLang = remember {
        val tag = Locale.getDefault().language
        SUPPORTED_LANGUAGES.firstOrNull { it.code == tag }?.code ?: "en"
    }

    var selectedLang by rememberSaveable { mutableStateOf(deviceLang) }
    var nick by rememberSaveable { mutableStateOf("") }
    var nickError by rememberSaveable { mutableStateOf<String?>(null) }
    var showContent by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { showContent = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        AnimatedVisibility(
            visible = showContent,
            enter = fadeIn() + slideInVertically { it / 4 }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(48.dp))

                Text(
                    text = stringResource(R.string.welcome_title),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.welcome_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(32.dp))

                // Language picker
                Text(
                    text = stringResource(R.string.welcome_language_label),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                Surface(
                    tonalElevation = 2.dp,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .fillMaxWidth()
                    ) {
                        items(SUPPORTED_LANGUAGES) { lang ->
                            val isSelected = lang.code == selectedLang
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedLang = lang.code
                                        applyLocale(context, lang.code)
                                        (context as? android.app.Activity)?.recreate()
                                    }
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lang.nativeName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Nickname input
                Text(
                    text = stringResource(R.string.welcome_nick_label),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = nick,
                    onValueChange = { v ->
                        nick = v.trim().take(16)
                        nickError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.welcome_nick_hint)) },
                    supportingText = {
                        val currentError = nickError
                        if (currentError != null) {
                            Text(currentError, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(stringResource(R.string.welcome_nick_helper))
                        }
                    },
                    isError = nickError != null,
                )

                Spacer(Modifier.weight(1f))

                // Continue button
                Button(
                    onClick = {
                        // Validate nick
                        val trimmed = nick.trim()
                        when {
                            trimmed.isEmpty() -> {
                                nickError = context.getString(R.string.welcome_nick_error_empty)
                            }
                            trimmed.length > 16 -> {
                                nickError = context.getString(R.string.welcome_nick_error_long)
                            }
                            !NICK_REGEX.matches(trimmed) -> {
                                nickError = context.getString(R.string.welcome_nick_error_invalid)
                            }
                            else -> {
                                onContinue(selectedLang, trimmed)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        stringResource(R.string.welcome_continue),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/**
 * Apply the selected locale using the per-app language API (Android 13+) or the
 * AppCompat fallback for older versions.
 */
fun applyLocale(context: Context, langCode: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val localeManager = context.getSystemService(LocaleManager::class.java)
        localeManager?.applicationLocales = LocaleList.forLanguageTags(langCode)
    } else {
        val appLocale = LocaleListCompat.forLanguageTags(langCode)
        AppCompatDelegate.setApplicationLocales(appLocale)
    }
}
