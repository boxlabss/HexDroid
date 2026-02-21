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

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.boxlabs.hexdroid.ui.AppRoot

class MainActivity : AppCompatActivity() {

    private lateinit var vm: IrcViewModel

    private fun stopKeepAliveAndExitHard() {
        // "Exit" should fully stop background work and remove the app from Recents.
        val ctx = applicationContext

        // Stop the keep-alive foreground service (both direct stop and an explicit ACTION_STOP intent
        // for devices/ROMs that may have started it in a different mode).
        runCatching {
            ctx.stopService(android.content.Intent(ctx, KeepAliveService::class.java))
        }
        runCatching {
            ctx.startService(android.content.Intent(ctx, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_STOP
            })
        }

        // Remove lingering notifications (connection/highlights/etc.).
        runCatching { NotificationHelper.cancelAll(ctx) }

        // Finish + remove from Recents.
        finishAndRemoveTask()

        // Some devices keep the process around even after finishing. For an explicit user-initiated
        // "Exit", it's reasonable to terminate the process so the user doesn't have to Force Stop.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 200)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge
        // Android 15+ enforces edge-to-edge for apps targeting SDK 35. For older Android versions, we
        // opt-in by drawing behind system bars and letting Compose handle insets.
        configureEdgeToEdgeCompat()

        vm = (application as HexDroidApp).ircViewModel
        processIntent(intent)

        val requestNotif = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* ignore */ }

        if (Build.VERSION.SDK_INT >= 33) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AppRoot(vm, onExit = {
                vm.exitApp()
                stopKeepAliveAndExitHard()
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdgeCompat() {
        // Prefer the platform API (API 30+). Avoid WindowCompat.enableEdgeToEdge()/setDecorFitsSystemWindows()
        // to prevent Play Console's Android 15 "deprecated edge-to-edge APIs/parameters" warning.
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            // Pre-R: layout behind status/nav bars using legacy flags.
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    
    private fun processIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val action = intent.getStringExtra(NotificationHelper.EXTRA_ACTION)
        when (action) {
            NotificationHelper.ACTION_QUIT -> vm.disconnectAll()
            NotificationHelper.ACTION_EXIT -> {
                vm.exitApp()
                Handler(Looper.getMainLooper()).postDelayed({
                    stopKeepAliveAndExitHard()
                }, 350)
            }
        }
        vm.handleIntent(intent)
    }


    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }
}
