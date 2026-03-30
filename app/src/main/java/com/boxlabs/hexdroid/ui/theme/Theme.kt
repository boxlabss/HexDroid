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
    // Single blue accent
    primary = HexBlueDark,
    onPrimary = Color(0xFF0D1117),
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = Color(0xFFBFDBFE),

    // Neutral slate:  secondary roles only, no competing hue
    secondary = HexSlateDark,
    onSecondary = Color(0xFF0D1117),
    secondaryContainer = Color(0xFF1E2533),
    onSecondaryContainer = Color(0xFFCDD5E0),

    // Muted steel: informational surfaces (unread pill, find highlight)
    tertiary = HexSteelDark,
    onTertiary = Color(0xFF0D1117),
    tertiaryContainer = Color(0xFF1A2030),
    onTertiaryContainer = Color(0xFFB8C5D6),

    background = DarkBackground,
    onBackground = Color(0xFFE6EDF3),
    surface = DarkSurface,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFACB8C8),
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
)

private val LightColorScheme = lightColorScheme(
    primary = HexBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A5F),

    secondary = HexSlateLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF1E293B),

    tertiary = HexSteelLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8EEF4),
    onTertiaryContainer = Color(0xFF334155),

    background = LightBackground,
    onBackground = Color(0xFF0F172A),
    surface = LightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF475569),
    outline = LightOutline,
    outlineVariant = Color(0xFFE2E8F0),
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

private val TerminalColorScheme = darkColorScheme(
    primary             = TerminalAmber,
    onPrimary           = TerminalBackground,
    primaryContainer    = Color(0xFF2A1A00),
    onPrimaryContainer  = TerminalAmber,
    secondary           = TerminalAmberDim,
    onSecondary         = TerminalBackground,
    secondaryContainer  = Color(0xFF1A0F00),
    onSecondaryContainer = TerminalAmberDim,
    tertiary            = Color(0xFFFFD966),
    onTertiary          = TerminalBackground,
    tertiaryContainer   = Color(0xFF332500),
    onTertiaryContainer = Color(0xFFFFD966),
    background          = TerminalBackground,
    onBackground        = TerminalAmber,
    surface             = TerminalSurface,
    onSurface           = TerminalAmber,
    surfaceVariant      = TerminalSurfaceVariant,
    onSurfaceVariant    = TerminalAmberDim,
    outline             = TerminalOutline,
    outlineVariant      = TerminalAmberMuted,
    error               = Color(0xFFFF4040),
    onError             = Color.Black,
    errorContainer      = Color(0xFF3D0000),
    onErrorContainer    = Color(0xFFFF4040),
    scrim               = Color(0xCC000000),
)

@Composable
fun HexDroidIRCTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    // dynamicColor parameter retained for call-site compatibility but is no longer
    // used directly. dynamic colour is now controlled exclusively by themeMode.
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    fontChoice: FontChoice = FontChoice.OPEN_SANS,
    customFontPath: String? = null,
    // Deprecated parameter kept for source-compatibility with call sites that pass darkTheme directly.
    // New callers should use themeMode instead.
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val isMatrix = themeMode == ThemeMode.MATRIX
    val isTerminal = themeMode == ThemeMode.TERMINAL
    val resolvedDark = darkTheme ?: when (themeMode) {
        ThemeMode.DARK, ThemeMode.MATRIX, ThemeMode.TERMINAL -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        isMatrix   -> MatrixColorScheme
        isTerminal -> TerminalColorScheme
        // SYSTEM theme: honour the wallpaper-derived palette on Android 12+ so the app
        // feels integrated with the device's own look. The user has explicitly chosen
        // "Follow system" so a pink or green button is intentional. it matches their
        // wallpaper. All other explicit themes (LIGHT, DARK) use the fixed HexDroid
        // palette so semantic colours (unread pill, find highlight, etc.) are predictable.
        themeMode == ThemeMode.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (resolvedDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        resolvedDark -> DarkColorScheme
        else -> LightColorScheme
    }

    // Matrix and Terminal themes use monospace font to reinforce the terminal aesthetic.
    val effectiveFont = if ((isMatrix || isTerminal) && fontChoice == FontChoice.OPEN_SANS) FontChoice.MONOSPACE else fontChoice

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typographyForFont(effectiveFont, customFontPath),
        shapes = HexShapes,
        content = content
    )
}