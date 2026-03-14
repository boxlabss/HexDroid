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

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.boxlabs.hexdroid.data.SettingsRepository

/**
 * App-scoped singletons so the IRC engine survives Activity recreation.
 *
 * With keepAliveInBackground enabled, the foreground service + wake/Wi-Fi locks help keep
 * the process alive; this instance keeps the connection state/coroutines alive within that process.
 */
class HexDroidApp : Application() {

    lateinit var repo: SettingsRepository
        private set

    /**
     * Process-wide singleton for the IRC ViewModel.
     * Ensures connections/coroutines survive activity recreation and config changes.
     */
    val ircViewModel: IrcViewModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        IrcViewModel(repo, applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepository(applicationContext)

        // Foreground/background detection via activity lifecycle callbacks.
        //
        // Two defensive measures for OEM devices (OnePlus/OxygenOS, OPPO, Xiaomi):
        //
        // 1. Floor `started` at 0. On some OEM ROMs, system overlays (app-lock auth screen,
        //    notification shade, volume panel) inject onActivityStopped calls that aren't
        //    matched by a prior onActivityStarted, causing `started` to go negative and
        //    permanently locking `isForeground` to false for the session.
        //
        // 2. Debounce the isForeground > false transition by 500 ms. OEM overlays such as
        //    volume controls and the quick-settings panel fire a rapid onStop / onStart pair
        //    (often < 100 ms apart). Without the debounce, `isForeground` flips false during
        //    that gap and any service-start attempt either races with startForeground() or is
        //    skipped entirely. With debounce, transient blips are ignored.
        //    OEM app-lock screens typically put the Activity in onPause (not onStop) while
        //    the lock UI is showing, so they are unaffected by this debounce.
        val mainHandler = Handler(Looper.getMainLooper())
        val goBackgroundRunnable = Runnable {
            AppVisibility.isForeground = false
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                mainHandler.removeCallbacks(goBackgroundRunnable)
                started = (started + 1).coerceAtLeast(1)
                AppVisibility.isForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started <= 0) {
                    // Debounce: don't flip to background immediately. OEM overlays can fire
                    // a stop/start pair within milliseconds; we only go background if nothing
                    // restarts the activity within the debounce window.
                    mainHandler.postDelayed(goBackgroundRunnable, 500)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}