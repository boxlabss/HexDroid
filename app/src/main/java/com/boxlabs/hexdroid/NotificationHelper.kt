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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class NotificationHelper(private val ctx: Context) {

    companion object {
        const val CH_CONNECTION = "hexdroid_connection"
        // Android 8+ notification sounds/vibration are controlled by channels and can't be changed
        // per-notification. Use separate highlight channels so we can do silent highlights.
        const val CH_HIGHLIGHT_SILENT = "hexdroid_highlight_silent"
        const val CH_HIGHLIGHT_SOUND = "hexdroid_highlight_sound"
        const val CH_PM = "hexdroid_pm"
        const val CH_DCC = "hexdroid_dcc"

        const val NOTIF_ID_CONNECTION = 1001

        const val EXTRA_NETWORK_ID = "extra_network_id"
        const val EXTRA_BUFFER = "extra_buffer"
        const val EXTRA_ACTION = "extra_action"
        const val ACTION_QUIT = "action_quit"
        const val ACTION_EXIT = "action_exit"
        const val ACTION_OPEN_TRANSFERS = "action_open_transfers"

        fun cancelAll(ctx: Context) {
            NotificationManagerCompat.from(ctx).cancelAll()
        }
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        // Be defensive: some OEM ROMs can throw unexpected exceptions when creating channels.
        // Channel creation should never take the whole app down.
        val nm = try {
            ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        } catch (_: Throwable) {
            return
        }

        val conn = NotificationChannel(
            CH_CONNECTION,
            "IRC Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            description = "Connection status while HexDroid IRC is connected"
        }

        val highlightSilent = NotificationChannel(
            CH_HIGHLIGHT_SILENT,
            "IRC Highlights (Silent)",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            // Some Android builds can throw if audio attributes are null.
            // Explicitly provide attributes while disabling the sound URI.
            setSound(
                null,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(false)
        }

        val highlightSound = NotificationChannel(
            CH_HIGHLIGHT_SOUND,
            "IRC Highlights",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        val pm = NotificationChannel(
            CH_PM,
            "IRC Private Messages",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        val dcc = NotificationChannel(
            CH_DCC,
            "DCC Requests",
            NotificationManager.IMPORTANCE_HIGH
        )

        try {
            nm.createNotificationChannel(conn)
            nm.createNotificationChannel(highlightSilent)
            nm.createNotificationChannel(highlightSound)
            nm.createNotificationChannel(pm)
            nm.createNotificationChannel(dcc)
        } catch (_: Throwable) {
            // Ignore channel creation failures.
        }
    }

    private fun actionPendingIntent(networkId: String, action: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_NETWORK_ID, networkId)
            .putExtra(EXTRA_ACTION, action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val requestCode = (networkId + "|" + action).hashCode()
        return PendingIntent.getActivity(ctx, requestCode, i, flags)
    }

    private fun openBufferPendingIntent(networkId: String, buffer: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NETWORK_ID, networkId)
            putExtra(EXTRA_BUFFER, buffer)
        }
        val flags = (PendingIntent.FLAG_UPDATE_CURRENT) or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val requestCode = (networkId + "|" + buffer).hashCode()
        return PendingIntent.getActivity(ctx, requestCode, i, flags)
    }

    private fun openTransfersPendingIntent(networkId: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NETWORK_ID, networkId)
            putExtra(EXTRA_ACTION, ACTION_OPEN_TRANSFERS)
        }
        val flags = (PendingIntent.FLAG_UPDATE_CURRENT) or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val requestCode = (networkId + "|open_transfers").hashCode()
        return PendingIntent.getActivity(ctx, requestCode, i, flags)
    }


    fun buildConnectionNotification(networkId: String, serverLabel: String, status: String): Notification {
        ensureChannels()
        return NotificationCompat.Builder(ctx, CH_CONNECTION)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Connected to $serverLabel")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openBufferPendingIntent(networkId, "*server*"))
            .addAction(0, "Quit", actionPendingIntent(networkId, ACTION_QUIT))
            .addAction(0, "Exit", actionPendingIntent(networkId, ACTION_EXIT))
            .build()
    }

    fun showConnection(networkId: String, serverLabel: String, status: String) {
        val n = buildConnectionNotification(networkId, serverLabel, status)
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID_CONNECTION, n)
    }

    fun cancelConnection() {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID_CONNECTION)
    }

    fun notifyHighlight(networkId: String, buffer: String, text: String, playSound: Boolean) {
        ensureChannels()
        val n = NotificationCompat.Builder(ctx, if (playSound) CH_HIGHLIGHT_SOUND else CH_HIGHLIGHT_SILENT)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(buffer)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openBufferPendingIntent(networkId, buffer))
            .build()
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt(), n)
    }

    fun notifyPm(networkId: String, buffer: String, text: String) {
        ensureChannels()
        val n = NotificationCompat.Builder(ctx, CH_PM)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(buffer)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openBufferPendingIntent(networkId, buffer))
            .build()
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt(), n)
    }

    fun notifyFileDone(networkId: String, filename: String, where: String) {
        ensureChannels()
        val n = NotificationCompat.Builder(ctx, CH_DCC)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DCC complete")
            .setContentText("$filename saved to $where")
            .setAutoCancel(true)
            .setContentIntent(openBufferPendingIntent(networkId, "*server*"))
            .addAction(0, "Quit", actionPendingIntent(networkId, ACTION_QUIT))
            .addAction(0, "Exit", actionPendingIntent(networkId, ACTION_EXIT))
            .build()
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt(), n)
    }

    fun notifyDccIncomingFile(networkId: String, from: String, filename: String) {
        ensureChannels()
        val n = NotificationCompat.Builder(ctx, CH_DCC)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Incoming file from $from")
            .setContentText(filename)
            .setAutoCancel(true)
            .setContentIntent(openTransfersPendingIntent(networkId))
            .build()
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt(), n)
    }

    fun notifyDccIncomingChat(networkId: String, from: String) {
        ensureChannels()
        val n = NotificationCompat.Builder(ctx, CH_DCC)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Incoming DCC chat from $from")
            .setContentText("Tap to view and accept")
            .setAutoCancel(true)
            .setContentIntent(openTransfersPendingIntent(networkId))
            .build()
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt(), n)
    }

}
