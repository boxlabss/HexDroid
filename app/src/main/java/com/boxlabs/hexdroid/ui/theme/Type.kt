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

package com.boxlabs.hexdroid.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.boxlabs.hexdroid.FontChoice
import com.boxlabs.hexdroid.R
import java.io.File

private fun TextStyle.withFamily(f: FontFamily): TextStyle = copy(fontFamily = f)

/**
 * Bundled fonts for UI/chat.
 *
 * src/main/res/font/:
 *  - opensans_regular.ttf, opensans_italic.ttf, opensans_bold.ttf, opensans_bold_italic.ttf
 *  - inter_regular.ttf, inter_italic.ttf, inter_bold.ttf, inter_bold_italic.ttf
 *  - jetbrains_mono_regular.ttf, jetbrains_mono_italic.ttf, jetbrains_mono_bold.ttf, jetbrains_mono_bold_italic.ttf
 */
private val AppDefault = FontFamily(
    Font(R.font.opensans_regular, weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(R.font.opensans_italic, weight = FontWeight.Normal, style = FontStyle.Italic),
    Font(R.font.opensans_bold, weight = FontWeight.Bold, style = FontStyle.Normal),
    Font(R.font.opensans_bold_italic, weight = FontWeight.Bold, style = FontStyle.Italic),
)

private val AppAltSans = FontFamily(
    Font(R.font.inter_regular, weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(R.font.inter_italic, weight = FontWeight.Normal, style = FontStyle.Italic),
    Font(R.font.inter_bold, weight = FontWeight.Bold, style = FontStyle.Normal),
    Font(R.font.inter_bold_italic, weight = FontWeight.Bold, style = FontStyle.Italic),
)

private val AppMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(R.font.jetbrains_mono_italic, weight = FontWeight.Normal, style = FontStyle.Italic),
    Font(R.font.jetbrains_mono_bold, weight = FontWeight.Bold, style = FontStyle.Normal),
    Font(R.font.jetbrains_mono_bold_italic, weight = FontWeight.Bold, style = FontStyle.Italic),
)

/**
 * Load a custom font from a file path.
 * Returns null if the file doesn't exist or can't be loaded.
 */
fun loadCustomFontFamily(path: String?): FontFamily? {
    if (path.isNullOrBlank()) return null
    val file = File(path)
    if (!file.exists()) return null
    return try {
        FontFamily(
            Font(file, weight = FontWeight.Normal, style = FontStyle.Normal),
            // Use the same file for all weights/styles - the font will be synthesized
            Font(file, weight = FontWeight.Bold, style = FontStyle.Normal),
            Font(file, weight = FontWeight.Normal, style = FontStyle.Italic),
            Font(file, weight = FontWeight.Bold, style = FontStyle.Italic),
        )
    } catch (e: Exception) {
        null
    }
}

fun fontFamilyForChoice(choice: FontChoice, customFontPath: String? = null): FontFamily = when (choice) {
    FontChoice.OPEN_SANS -> AppDefault
    FontChoice.INTER -> AppAltSans
    FontChoice.MONOSPACE -> AppMono
    FontChoice.CUSTOM -> loadCustomFontFamily(customFontPath) ?: AppDefault
}

fun typographyForFont(choice: FontChoice, customFontPath: String? = null): Typography {
    val base = Typography()
    val fam = fontFamilyForChoice(choice, customFontPath)
    return base.copy(
        displayLarge = base.displayLarge.withFamily(fam),
        displayMedium = base.displayMedium.withFamily(fam),
        displaySmall = base.displaySmall.withFamily(fam),
        headlineLarge = base.headlineLarge.withFamily(fam),
        headlineMedium = base.headlineMedium.withFamily(fam),
        headlineSmall = base.headlineSmall.withFamily(fam),
        titleLarge = base.titleLarge.withFamily(fam),
        titleMedium = base.titleMedium.withFamily(fam),
        titleSmall = base.titleSmall.withFamily(fam),
        bodyLarge = base.bodyLarge.withFamily(fam).copy(fontSize = 16.sp),
        bodyMedium = base.bodyMedium.withFamily(fam),
        bodySmall = base.bodySmall.withFamily(fam),
        labelLarge = base.labelLarge.withFamily(fam),
        labelMedium = base.labelMedium.withFamily(fam),
        labelSmall = base.labelSmall.withFamily(fam)
    )
}
