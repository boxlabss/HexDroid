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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Set by [BootReceiver] before the ViewModel is constructed, and read once by it.
 *
 * A separate object rather than a ViewModel field because the decision is made before
 * the ViewModel exists, and it applies to the boot path only: a connect the user asks
 * for, on mobile data, is a connect they meant.
 */
object BootConnectGate {
    @Volatile
    var waitForWifi: Boolean = false
}

/**
 * Brings "Always connected" back after a device restart when enabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            // Some OEM ROMs (older HTC/Asus builds) send only this one.
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            // Re-arm after an app update replaces the package, which also kills the
            // process and its connections.
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }

        val app = context.applicationContext as? HexDroidApp ?: return

        // BOOT_COMPLETED grants a short background-FGS-start exemption on Android 12+.
        // Open the window before anything can try to foreground the service; if the
        // settings read below decides not to connect, an unused window costs nothing and
        // lapses on its own.
        AppVisibility.grantForegroundServiceStartWindow()

        // onReceive runs on the main thread with a ~10 s budget and DataStore reads are
        // suspending, so hold the broadcast open while we check. The work after the
        // check is only object construction; the connect fan-out proceeds on the
        // ViewModel's own scope, which outlives this receiver.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = app.repo.settingsFlow.first()
                if (!settings.connectOnBoot) return@launch
                if (!settings.keepAliveInBackground) return@launch

                val networks = app.repo.networksFlow.first()
                if (networks.none { it.autoConnect }) return@launch

                // Wi-Fi is usually still associating when BOOT_COMPLETED lands, so
                // connecting straight away means connecting over mobile data. Arm the
                // gate instead of waiting here: the ViewModel marks the profiles as
                // wanted, which starts the keep-alive service inside the window this
                // broadcast granted, and holds the connections until Wi-Fi arrives. The
                // service keeps the process alive, so the wait has no deadline.
                BootConnectGate.waitForWifi = settings.connectOnBootWifiOnly

                // Constructing the ViewModel is what starts everything. It must happen on
                // the main thread: its init builds Android objects (Handler, notification
                // helpers) that expect a Looper.
                withContext(Dispatchers.Main) { app.ircViewModel }
            } catch (_: Throwable) {
                // A boot receiver must never crash the process. connections resume when they next open the app.
            } finally {
                pending.finish()
            }
        }
    }

}
