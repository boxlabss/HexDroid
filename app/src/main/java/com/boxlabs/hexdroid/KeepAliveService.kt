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

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service used to keep the process alive for "Always connected".
 *
 * On Android 15+, "dataSync" foreground services can be timed out after 6 hours in a 24-hour
 * window, so this service uses the "specialUse" FGS type in the manifest.
 *
 * WakeLock strategy
 *
 * We do NOT hold a permanent PARTIAL_WAKE_LOCK for the lifetime of this service.
 * The foreground service itself prevents the process from being killed, and the OS network
 * stack keeps TCP sockets alive independently of whether our CPU is spinning. A permanent
 * wake lock would keep the CPU running at full speed all night — a significant battery drain.
 *
 * Instead we use two targeted patterns:
 *
 * 1. [acquireScopedWakeLock] / [releaseScopedWakeLock] - for the connect/TLS handshake burst.
 *    The lock is held from the start of the coroutine until the `events()` flow returns, covering
 *    the CPU-intensive initial handshake so Android can't suspend us mid-handshake.
 *
 * 2. [withWakeLock] - wrapper for the auto-reconnect loop. Acquires a
 *    short-timeout lock, runs the block, then releases it.
 *
 * The WifiLock IS held permanently: it prevents Wi-Fi from powering down the association
 * (which would kill the TCP socket) while keeping the normal Wi-Fi power-saving mode active
 * between packets. WIFI_MODE_FULL (not HIGH_PERF) is intentional — HIGH_PERF disables
 * power-save and roughly doubles Wi-Fi current draw with no benefit for an idle IRC client.
 */
class KeepAliveService : Service() {

    companion object {
        @Volatile var isRunning: Boolean = false

        const val ACTION_UPDATE = "com.boxlabs.hexdroid.action.UPDATE"
        const val ACTION_STOP = "com.boxlabs.hexdroid.action.STOP"

        const val EXTRA_NETWORK_ID = NotificationHelper.EXTRA_NETWORK_ID
        const val EXTRA_SERVER_LABEL = "extra_server_label"
        const val EXTRA_STATUS = "extra_status"

        // Scoped wake lock for the connect/handshake burst.
        @Volatile private var scopedWakeLock: PowerManager.WakeLock? = null

        /**
         * Acquire a PARTIAL_WAKE_LOCK scoped to the connect/handshake burst.
         * Must be paired with [releaseScopedWakeLock].
         * The lock has a 60-second safety timeout so it is released automatically if the
         * caller forgets (e.g. due to an exception path).
         */
        fun acquireScopedWakeLock(context: Context) {
            val pm = context.getSystemService(POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HexDroid:ConnectBurst")
            lock.acquire(60_000L) // 60 s safety timeout
            scopedWakeLock = lock
        }

        /**
         * Release the scoped wake lock acquired by [acquireScopedWakeLock].
         * Safe to call even if no lock is held.
         */
        fun releaseScopedWakeLock() {
            scopedWakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
                scopedWakeLock = null
            }
        }

        /**
         * Acquire a short-timeout PARTIAL_WAKE_LOCK, run [block], then release it.
         * Use in the auto-reconnect loop so coroutines can run while the screen is off.
         * The timeout guards against stalls: the lock is released after [timeoutMs] even if
         * [block] has not returned.
         */
        suspend fun <T> withWakeLock(context: Context, timeoutMs: Long = 30_000L, block: suspend () -> T): T {
            val pm = context.getSystemService(POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HexDroid:Reconnect")
            lock.acquire(timeoutMs)
            return try {
                block()
            } finally {
                if (lock.isHeld) lock.release()
            }
        }
    }

    private lateinit var wifiLock: WifiManager.WifiLock

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // Call startForeground() immediately to avoid ForegroundServiceDidNotStartInTimeException.
        // Android requires startForeground() within 5 seconds of startForegroundService().
        val initialNotification = NotificationHelper(applicationContext)
            .buildConnectionNotification("", "HexDroid IRC", "Connecting...")
        val fgsType = if (android.os.Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NotificationHelper.NOTIF_ID_CONNECTION, initialNotification, fgsType)

        // WifiLock to prevent Wi-Fi from going to sleep completely.
        // Using WIFI_MODE_FULL (not HIGH_PERF) allows Wi-Fi power saving between packets,
        // which significantly improves battery life while still keeping the TCP connection alive.
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "HexDroid:IRCWifiLock")
        wifiLock.acquire()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_UPDATE
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val networkId = intent?.getStringExtra(EXTRA_NETWORK_ID) ?: ""
        val serverLabel = intent?.getStringExtra(EXTRA_SERVER_LABEL) ?: "HexDroid IRC"
        val status = intent?.getStringExtra(EXTRA_STATUS) ?: "Connected"

        val n = NotificationHelper(applicationContext)
            .buildConnectionNotification(networkId, serverLabel, status)

        val fgsType = if (android.os.Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NotificationHelper.NOTIF_ID_CONNECTION, n, fgsType)
        return START_STICKY
    }

    override fun onDestroy() {
        if (::wifiLock.isInitialized && wifiLock.isHeld) wifiLock.release()
        releaseScopedWakeLock() // clean up if still held

        isRunning = false
        super.onDestroy()
        NotificationManagerCompat.from(applicationContext).cancel(NotificationHelper.NOTIF_ID_CONNECTION)
    }
}
