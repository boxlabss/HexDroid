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

    /**
     * True while at least one Activity is in the STARTED state, updated with NO debounce.
     *
     * [isForeground] debounces the foreground > background transition by 500 ms to absorb
     * OEM overlay stop/start blips. That debounce is correct for side effects (typing/logs),
     * but it makes [isForeground] briefly stale-true while the app is actually going to the
     * background. Using that stale value to gate ContextCompat.startForegroundService() is
     * what produces ForegroundServiceDidNotStartInTimeException: we arm the ~10 s "must call
     * startForeground()" watchdog, then the service's startForeground() is refused because the
     * app is no longer foreground, and the watchdog fires a process-level crash.
     *
     * This flag flips to false the instant the last Activity stops, so it is the correct
     * predicate for "are we allowed to start a foreground service right now". Erring toward
     * false here is safe: the caller falls back to a plain notification instead of crashing.
     */
    @Volatile
    var isActivityStarted: Boolean = false
}