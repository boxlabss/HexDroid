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

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.boxlabs.hexdroid.FontChoice
import com.boxlabs.hexdroid.data.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = HexBlueDark,
    onPrimary = DarkBackground,
    primaryContainer = Color(0xFF2B3561),
    onPrimaryContainer = Color(0xFFE2E6FF),

    secondary = HexPurpleDark,
    onSecondary = DarkBackground,
    secondaryContainer = Color(0xFF3B2E63),
    onSecondaryContainer = Color(0xFFECE6FF),

    tertiary = HexTealDark,
    onTertiary = DarkBackground,
    tertiaryContainer = Color(0xFF0C3E3A),
    onTertiaryContainer = Color(0xFFC7FFF6),

    background = DarkBackground,
    onBackground = Color(0xFFE7E9F2),
    surface = DarkSurface,
    onSurface = Color(0xFFE7E9F2),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFC4C8D7),
    outline = DarkOutline,
)

private val LightColorScheme = lightColorScheme(
    primary = HexBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4FF),
    onPrimaryContainer = Color(0xFF001A43),

    secondary = HexPurpleLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E1FF),
    onSecondaryContainer = Color(0xFF23105A),

    tertiary = HexTealLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBFF2EA),
    onTertiaryContainer = Color(0xFF003731),

    background = LightBackground,
    onBackground = Color(0xFF101423),
    surface = LightSurface,
    onSurface = Color(0xFF101423),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF40465A),
    outline = LightOutline,
)

private val MatrixColorScheme = darkColorScheme(
    primary             = MatrixGreen,
    onPrimary           = MatrixBackground,
    primaryContainer    = Color(0xFF002A08),
    onPrimaryContainer  = MatrixGreen,
    secondary           = MatrixGreenDim,
    onSecondary         = MatrixBackground,
    secondaryContainer  = Color(0xFF001A06),
    onSecondaryContainer = MatrixGreenDim,
    tertiary            = Color(0xFF39FF6A),
    onTertiary          = MatrixBackground,
    tertiaryContainer   = Color(0xFF003810),
    onTertiaryContainer = Color(0xFF39FF6A),
    background          = MatrixBackground,
    onBackground        = MatrixGreen,
    surface             = MatrixSurface,
    onSurface           = MatrixGreen,
    surfaceVariant      = MatrixSurfaceVariant,
    onSurfaceVariant    = MatrixGreenDim,
    outline             = MatrixOutline,
    outlineVariant      = MatrixGreenMuted,
    error               = Color(0xFFFF4040),
    onError             = Color.Black,
    errorContainer      = Color(0xFF3D0000),
    onErrorContainer    = Color(0xFFFF4040),
    scrim               = Color(0xCC000000),
)

@Composable
fun HexDroidIRCTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    // Dynamic colour is available on Android 12+ (not applied for Matrix theme)
    dynamicColor: Boolean = true,
    fontChoice: FontChoice = FontChoice.OPEN_SANS,
    customFontPath: String? = null,
    // Deprecated parameter kept for source-compatibility with call sites that pass darkTheme directly.
    // New callers should use themeMode instead.
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val isMatrix = themeMode == ThemeMode.MATRIX
    val resolvedDark = darkTheme ?: when (themeMode) {
        ThemeMode.DARK, ThemeMode.MATRIX -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        isMatrix -> MatrixColorScheme   // Matrix always uses its own palette, never dynamic
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (resolvedDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        resolvedDark -> DarkColorScheme
        else -> LightColorScheme
    }

    // Matrix theme uses monospace font to reinforce the terminal aesthetic.
    val effectiveFont = if (isMatrix && fontChoice == FontChoice.OPEN_SANS) FontChoice.MONOSPACE else fontChoice

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typographyForFont(effectiveFont, customFontPath),
        shapes = HexShapes,
        content = content
    )
}