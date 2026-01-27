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

        // Lightweight foreground/background detection without extra lifecycle dependencies
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started += 1
                AppVisibility.isForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                started -= 1
                if (started <= 0) AppVisibility.isForeground = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}