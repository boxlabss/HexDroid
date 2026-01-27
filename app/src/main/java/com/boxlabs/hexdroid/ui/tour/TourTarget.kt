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

package com.boxlabs.hexdroid.ui.tour

/**
 * Stable IDs for UI elements we can highlight in the intro tour.
 */
enum class TourTarget {
    // Networks screen
    NETWORKS_ADD_FAB,
    NETWORKS_SETTINGS,
    NETWORKS_CONNECT_BUTTON,
    NETWORKS_AFTERNET_ITEM,

    // Chat screen
    CHAT_DRAWER_BUTTON,
    CHAT_BUFFER_DRAWER,
    CHAT_OVERFLOW_BUTTON,
    CHAT_INPUT,

    // Transfers screen
    TRANSFERS_ENABLE_DCC,
    TRANSFERS_PICK_FILE,

    // Settings screen
    SETTINGS_APPEARANCE_SECTION,
    SETTINGS_RUN_TOUR
}
