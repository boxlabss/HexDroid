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

package com.boxlabs.hexdroid

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Helper to get and set the per-app locale.
 *
 * Uses the platform API on Android 13+ (TIRAMISU) and
 * AppCompatDelegate on older versions.
 */
object LocaleHelper {

    /** Return the current per-app language tag or null if following system default. */
    fun getCurrentLanguage(context: Context): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val locales = localeManager?.applicationLocales
            if (locales != null && !locales.isEmpty) locales.get(0)?.language else null
        } else {
            val locales = AppCompatDelegate.getApplicationLocales()
            if (!locales.isEmpty) locales.get(0)?.language else null
        }
    }

    /** Set the per-app locale. Pass null or empty to revert to system default. */
    fun setLanguage(context: Context, langCode: String?) {
        if (langCode.isNullOrBlank()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val localeManager = context.getSystemService(LocaleManager::class.java)
                localeManager?.applicationLocales = LocaleList.getEmptyLocaleList()
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val localeManager = context.getSystemService(LocaleManager::class.java)
                localeManager?.applicationLocales = LocaleList.forLanguageTags(langCode)
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langCode))
            }
        }
    }
}
