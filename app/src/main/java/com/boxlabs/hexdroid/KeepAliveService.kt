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

        // startForeground() must be called within ~5s of the service being created, but on
        // Android 12+ (API 31+) the system can REFUSE the foreground start with
        // ForegroundServiceStartNotAllowedException when the service was created without a
        // valid background-FGS exemption. The most common trigger is the OS itself
        // restarting this service after killing it under memory pressure / Doze
        // (START_STICKY) while the app is in the background — that restart path does NOT
        // go through our guarded call sites in IrcViewModel, so the guard has to live
        // here. An unguarded startForeground() throw in onCreate takes down the whole
        // process (the reported FATAL EXCEPTION at KeepAliveService.onCreate).
        //
        // If we can't become a foreground service right now, stop cleanly and bail:
        //   - swallowing the exception avoids the process crash, and
        //   - stopSelf() avoids the follow-on ForegroundServiceDidNotStartInTimeException
        //     that fires if a started service never reaches startForeground().
        // Our own foregrounding logic (maybeStartKeepAlive / updateConnectionNotification)
        // will start the service again, correctly, the next time the app is visible or a
        // valid FGS exemption exists.
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

        // Building the notification can throw on some Samsung firmware when the
        // ActivityManager's per-UID PendingIntent rate limit fires. We've wrapped each
        // PendingIntent.getActivity in NotificationHelper.safePi to swallow the
        // SecurityException, but a final outer guard here protects against unrelated
        // surprises (OOM during builder.build(), system-server transient errors, etc.).
        // If building fails entirely we leave the existing foreground notification in
        // place rather than crashing the service. START_STICKY ensures the OS will
        // hand us another onStartCommand later, giving the rate limit time to clear.
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
            // Same Android 12+/14+ background-FGS-start restriction as onCreate. Don't
            // linger as a started-but-not-foreground service: that risks the
            // ForegroundServiceDidNotStartInTimeException crash and, under START_STICKY,
            // a restart loop where the OS keeps recreating a service that can't go
            // foreground. Stop and return START_NOT_STICKY so the OS leaves us alone;
            // the connection layer restarts us through the guarded path when allowed.
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (::wifiLock.isInitialized && wifiLock.isHeld) wifiLock.release()
        releaseAllScopedWakeLocks() // clean up any stale per-netId locks

        isRunning = false
        super.onDestroy()
        NotificationManagerCompat.from(applicationContext).cancel(NotificationHelper.NOTIF_ID_CONNECTION)
    }
}