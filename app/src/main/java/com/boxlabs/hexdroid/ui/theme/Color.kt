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

import androidx.compose.ui.graphics.Color

// accent colours used when Dynamic Colour isn't available or is disabled
val HexBlueLight = Color(0xFF356AFF)
val HexBlueDark = Color(0xFF9AB1FF)

val HexPurpleLight = Color(0xFF7B61FF)
val HexPurpleDark = Color(0xFFB6A6FF)

val HexTealLight = Color(0xFF00BFA6)
val HexTealDark = Color(0xFF66E6D6)

// App surfaces
val LightBackground = Color(0xFFF7F8FF)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE9ECF7)
val LightOutline = Color(0xFFB9C0D9)

val DarkBackground = Color(0xFF0F111A)
val DarkSurface = Color(0xFF141827)
val DarkSurfaceVariant = Color(0xFF1B2133)
val DarkOutline = Color(0xFF3A425E)

// Matrix / "old school terminal" green theme palette
val MatrixGreen          = Color(0xFF00FF41)   // classic bright phosphor green
val MatrixGreenDim       = Color(0xFF00B32A)   // dimmer green for secondary elements
val MatrixGreenMuted     = Color(0xFF007A1C)   // subtle green for outlines / containers
val MatrixBackground     = Color(0xFF000000)   // true black — optimal on OLED
val MatrixSurface        = Color(0xFF030D03)   // near-black with faint green tint
val MatrixSurfaceVariant = Color(0xFF0A1A0A)   // slightly lighter surface for cards
val MatrixOutline        = Color(0xFF0F2B0F)   // dark green border
