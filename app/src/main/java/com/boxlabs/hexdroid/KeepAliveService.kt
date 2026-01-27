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
 */
class KeepAliveService : Service() {

    companion object {
        @Volatile var isRunning: Boolean = false

        const val ACTION_UPDATE = "com.boxlabs.hexdroid.action.UPDATE"
        const val ACTION_STOP = "com.boxlabs.hexdroid.action.STOP"

        const val EXTRA_NETWORK_ID = NotificationHelper.EXTRA_NETWORK_ID
        const val EXTRA_SERVER_LABEL = "extra_server_label"
        const val EXTRA_STATUS = "extra_status"
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // CRITICAL: Call startForeground() immediately to avoid ForegroundServiceDidNotStartInTimeException.
        // Android requires startForeground() within 5 seconds of startForegroundService().
        val initialNotification = NotificationHelper(applicationContext)
            .buildConnectionNotification("", "HexDroid IRC", "Connecting...")
        val fgsType = if (android.os.Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NotificationHelper.NOTIF_ID_CONNECTION, initialNotification, fgsType)

        // Explicit partial wake lock (CPU keep-alive)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HexDroid:IRCKeepAlive")
        wakeLock.acquire()

        // WifiLock to prevent Wi-Fi from going to sleep completely.
        // Using WIFI_MODE_FULL (not HIGH_PERF) allows WiFi power saving between packets,
        // which significantly improves battery life while still keeping the connection alive.
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
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        if (::wifiLock.isInitialized && wifiLock.isHeld) wifiLock.release()

        isRunning = false
        super.onDestroy()
        NotificationManagerCompat.from(applicationContext).cancel(NotificationHelper.NOTIF_ID_CONNECTION)
    }
}