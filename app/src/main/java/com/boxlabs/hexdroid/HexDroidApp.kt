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
    private val ircViewModelDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        IrcViewModel(repo, applicationContext)
    }

    val ircViewModel: IrcViewModel by ircViewModelDelegate

    /**
     * The ViewModel only if something has already built it, without building it.
     *
     * A background entry point that merely wants to read connection state asks through here
     */
    val ircViewModelOrNull: IrcViewModel?
        get() = if (ircViewModelDelegate.isInitialized()) ircViewModel else null

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepository(applicationContext)

        // Smart selection off: the platform TextClassifier throws on some selection ranges (block
        // art, emoji), and nothing here uses its word-boundary suggestions.
        androidx.compose.foundation.ComposeFoundationFlags.isSmartSelectionEnabled = false

        // Foreground/background tracking from activity lifecycle callbacks. `started` is floored at
        // 0, since some OEM overlays send unmatched onActivityStopped calls. Going to background is
        // debounced by 500 ms so overlay blips (volume panel, quick settings) don't count;
        // background-only work (typing "done", log flush) runs in the debounced runnable for the
        // same reason.
        val mainHandler = Handler(Looper.getMainLooper())
        val goBackgroundRunnable = Runnable {
            AppVisibility.isForeground = false
            // Cancel any in-progress typing indicator so remote users don't see a stale
            // "typing" state, and so the 30-second paused/done timer coroutine doesn't
            // keep the CPU awake.
            ircViewModel.cancelTypingOnBackground()
            // Flush log file buffers so lines written since the last periodic flush
            // reach disk before the OS might kill the process.
            ircViewModel.flushLogs()
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                mainHandler.removeCallbacks(goBackgroundRunnable)
                started = (started + 1).coerceAtLeast(1)
                AppVisibility.isForeground = true
                // Non-debounced signal used to gate startForegroundService(). Set true the
                // instant an activity is started so the FGS-start gate matches reality.
                AppVisibility.isActivityStarted = true
                // If we suppressed auto-reconnect for a swipe-away teardown but the process
                // outlived it and the user came back, clear that suppression so connections
                // resume normally.
                ircViewModel.onAppForegrounded()
                // Clear unread on the selected buffer when the app comes back to the foreground:
                // messages that arrived in the background counted as unread, but this is the buffer
                // on screen.
                ircViewModel.consumeUnreadOnForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started <= 0) {
                    // Flip the FGS-start gate to false IMMEDIATELY (no debounce). Once no
                    // activity is started we can no longer call startForeground(), so
                    // we must stop arming the watchdog right away.
                    AppVisibility.isActivityStarted = false
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
