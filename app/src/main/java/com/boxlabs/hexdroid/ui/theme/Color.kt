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

// Single blue accent
val HexBlueLight = Color(0xFF2563EB)   // Tailwind blue-600
val HexBlueDark  = Color(0xFF93C5FD)   // blue-300legible on dark without neon
 
// Neutral slate: used for secondary roles (chips, selected state, etc.)
val HexSlateLight = Color(0xFF475569)  // slate-600
val HexSlateDark  = Color(0xFF94A3B8)  // slate-400
 
// Muted steel: used for tertiary / "scroll to unread" / find highlights.
val HexSteelLight = Color(0xFF64748B)  // slate-500
val HexSteelDark  = Color(0xFF8B98A8)  // desaturated blue-grey
 
// App surfaces: very slightly blue-tinted neutrals, not pure grey
val LightBackground    = Color(0xFFF8FAFC)  // near-white
val LightSurface       = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEFF2F7) // subtle card background
val LightOutline       = Color(0xFFCBD5E1)  // slate-300

val DarkBackground     = Color(0xFF0D1117)  // GitHub-dark styledark navy
val DarkSurface        = Color(0xFF161B22)  // slightly lighter panel
val DarkSurfaceVariant = Color(0xFF1C2330)  // card / sidebar
val DarkOutline        = Color(0xFF526070)  // steel-blue mid-grey
val DarkOutlineVariant = Color(0xFF2D3748)  // subtle dividers

// Matrix / "old school terminal" green theme palette
val MatrixGreen          = Color(0xFF00FF41)   // classic bright phosphor green
val MatrixGreenDim       = Color(0xFF00B32A)   // dimmer green for secondary elements
val MatrixGreenMuted     = Color(0xFF007A1C)   // subtle green for outlines / containers
val MatrixBackground     = Color(0xFF000000)   // true black optimal on OLED
val MatrixSurface        = Color(0xFF030D03)   // near-black with faint green tint
val MatrixSurfaceVariant = Color(0xFF0A1A0A)   // slightly lighter surface for cards
val MatrixOutline        = Color(0xFF0F2B0F)   // dark green border

// Terminal / amber phosphor theme palette
val TerminalAmber          = Color(0xFFFFB300)   // warm amber phosphor glow
val TerminalAmberDim       = Color(0xFFB37A00)   // dimmer amber for secondary elements
val TerminalAmberMuted     = Color(0xFF7A5200)   // subtle amber for outlines / containers
val TerminalBackground     = Color(0xFF000000)   // true black: OLED optimal
val TerminalSurface        = Color(0xFF0D0900)   // near-black with faint amber tint
val TerminalSurfaceVariant = Color(0xFF1A1100)   // slightly lighter surface for sidebars / cards
val TerminalOutline        = Color(0xFF2B1E00)   // dark amber border