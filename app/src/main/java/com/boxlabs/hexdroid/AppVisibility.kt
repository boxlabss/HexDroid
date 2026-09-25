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
 * Whether the app is in the foreground, so a foreground service is only started when Android 12+
 * allows it. Updated from HexDroidApp's ActivityLifecycleCallbacks.
 */
object AppVisibility {
    @Volatile
    var isForeground: Boolean = false

    /**
     * True while at least one Activity is STARTED, with no debounce. Use this, not [isForeground],
     * to decide whether a foreground service may be started: [isForeground] lags the switch to
     * background by its 500 ms debounce.
     */
    @Volatile
    var isActivityStarted: Boolean = false

    /**
     * Deadline (SystemClock.elapsedRealtime) until which a foreground service may be started with
     * no Activity started. [BootReceiver] opens it during Android's post-boot exemption so
     * KeepAliveService can start normally; after it closes, the fallback is a plain notification.
     */
    @Volatile
    var fgsStartExemptUntilElapsedMs: Long = 0L

    /** Open the exemption window for [durationMs] from now. */
    fun grantForegroundServiceStartWindow(durationMs: Long = 60_000L) {
        fgsStartExemptUntilElapsedMs = android.os.SystemClock.elapsedRealtime() + durationMs
    }

    /**
     * True when ContextCompat.startForegroundService() is expected to be permitted:
     * an Activity is started, or we are inside a broadcast-granted exemption window.
     */
    fun canStartForegroundService(): Boolean =
        isActivityStarted || android.os.SystemClock.elapsedRealtime() < fgsStartExemptUntilElapsedMs
}
