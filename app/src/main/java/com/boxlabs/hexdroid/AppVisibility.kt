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

/**
 * Tracks whether the app process is currently in the foreground.
 *
 * Used to safely decide when we can call ContextCompat.startForegroundService()
 * without hitting Android 12+ restrictions (ForegroundServiceStartNotAllowedException)
 * when the app is backgrounded.
 *
 * Updated via ActivityLifecycleCallbacks in HexDroidApp.
 */
object AppVisibility {
    @Volatile
    var isForeground: Boolean = false
}