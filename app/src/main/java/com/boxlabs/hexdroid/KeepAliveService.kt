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
 * Foreground service keeping the process alive for "Always connected". Uses the specialUse FGS
 * type, since dataSync is time-limited on Android 15+. No permanent wake lock: a scoped one covers
 * the connect and TLS handshake, and [withWakeLock] covers each reconnect. A WifiLock
 * (WIFI_MODE_FULL, not HIGH_PERF) is held so Wi-Fi keeps its association with normal power saving.
 */
class KeepAliveService : Service() {

    companion object {
        @Volatile var isRunning: Boolean = false

        /**
         * Hook the app process registers (from IrcViewModel) so the service can request a
         * graceful QUIT when the user swipes the app away ([onTaskRemoved]).
         */
        @Volatile var gracefulQuitOnSwipe: ((onDone: () -> Unit) -> Boolean)? = null

        const val ACTION_UPDATE = "com.boxlabs.hexdroid.action.UPDATE"
        const val ACTION_STOP = "com.boxlabs.hexdroid.action.STOP"

        const val EXTRA_NETWORK_ID = NotificationHelper.EXTRA_NETWORK_ID
        const val EXTRA_SERVER_LABEL = "extra_server_label"
        const val EXTRA_STATUS = "extra_status"

        // Use a per-netId map ensuring each network's
        // WakeLock is tracked and released independently.
        private val scopedWakeLocks = java.util.concurrent.ConcurrentHashMap<String, PowerManager.WakeLock>()

        /**
         * Acquire a PARTIAL_WAKE_LOCK scoped to the connect/handshake burst for [netId].
         * Must be paired with [releaseScopedWakeLock] using the same [netId].
         * The lock has a 60-second safety timeout so it is released automatically if the
         * caller forgets (e.g. due to an exception path).
         */
        fun acquireScopedWakeLock(context: Context, netId: String) {
            val pm = context.getSystemService(POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HexDroid:ConnectBurst:$netId")
            lock.acquire(60_000L) // 60 s safety timeout
            // Release any stale lock for this netId before storing the new one.
            scopedWakeLocks.put(netId, lock)?.let { old -> if (old.isHeld) old.release() }
        }

        /**
         * Release the scoped wake lock for [netId] acquired by [acquireScopedWakeLock].
         * Safe to call even if no lock is held for [netId].
         */
        fun releaseScopedWakeLock(netId: String) {
            scopedWakeLocks.remove(netId)?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        }

        /** Release ALL scoped wake locks (called from [onDestroy] as a last-resort cleanup). */
        fun releaseAllScopedWakeLocks() {
            val snapshot = scopedWakeLocks.entries.toList()
            scopedWakeLocks.clear()
            for ((_, lock) in snapshot) runCatching { if (lock.isHeld) lock.release() }
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

        // startForeground() can be refused on Android 12+ when the service was created without a
        // background-FGS exemption, most often when the OS restarts it (START_STICKY) in the
        // background, which bypasses the guarded call sites in IrcViewModel. If it is refused, stop
        // cleanly: the exception is swallowed, and stopSelf() avoids
        // ForegroundServiceDidNotStartInTimeException. The app starts the service again when it's
        // visible or has an exemption.
        val startedForeground = runCatching {
            val initialNotification = NotificationHelper(applicationContext)
                .buildConnectionNotification("", "HexDroid IRC", "Connecting...")
            val fgsType = if (android.os.Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else 0
            ServiceCompat.startForeground(this, NotificationHelper.NOTIF_ID_CONNECTION, initialNotification, fgsType)
        }.isSuccess

        if (!startedForeground) {
            isRunning = false
            stopSelf()
            return
        }

        // WifiLock to prevent Wi-Fi from going to sleep completely. Only acquired once we
        // are genuinely a foreground service. Guarded because createWifiLock / acquire can
        // throw on some OEM ROMs; onDestroy's ::wifiLock.isInitialized check tolerates the
        // lock never being created.
        //
        // Using WIFI_MODE_FULL (not HIGH_PERF) allows Wi-Fi power saving between packets,
        // which significantly improves battery life while still keeping the TCP connection alive.
        runCatching {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "HexDroid:IRCWifiLock")
            wifiLock.acquire()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** The network, label and status the foreground notification currently shows. */
    private var postedKey: String? = null

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

        // Already showing exactly this: nothing to do. Building and reposting costs several calls
        // into the system on the main thread, which slow ROMs can't afford on every update.
        val key = "$networkId\u0000$serverLabel\u0000$status"
        if (key == postedKey) return START_STICKY

        // Building the notification can throw (a PendingIntent rate limit on some Samsung firmware,
        // OOM, system-server errors). On failure keep the current notification; START_STICKY brings
        // another onStartCommand later.
        val n = runCatching {
            NotificationHelper(applicationContext)
                .buildConnectionNotification(networkId, serverLabel, status)
        }.getOrNull() ?: return START_STICKY

        val fgsType = if (android.os.Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        val foregroundOk = runCatching {
            ServiceCompat.startForeground(this, NotificationHelper.NOTIF_ID_CONNECTION, n, fgsType)
        }.isSuccess
        if (!foregroundOk) {
            // Same background-FGS restriction as onCreate: stop rather than linger as a started,
            // non-foreground service, and return START_NOT_STICKY so the OS doesn't keep recreating
            // it. The connection layer restarts it through the guarded path.
            stopSelf()
            return START_NOT_STICKY
        }
        postedKey = key
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The user swiped the app away from recents. If background persistence is OFF, treat
        // this as "I'm done": ask the connection layer to send a clean QUIT (TLS close_notify)
        // so the server sees an orderly disconnect.
        // The QUIT runs off the main thread; the service stays up until it reports back.
        runCatching {
            gracefulQuitOnSwipe?.invoke {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (::wifiLock.isInitialized && wifiLock.isHeld) wifiLock.release()
        releaseAllScopedWakeLocks() // clean up any stale per-netId locks

        isRunning = false
        super.onDestroy()
        NotificationManagerCompat.from(applicationContext).cancel(NotificationHelper.NOTIF_ID_CONNECTION)
    }
}
