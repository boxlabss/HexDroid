/*
* HexDroidIRC - An IRC Client for Android
* Copyright (C) 2026 boxlabs
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
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
import androidx.core.app.RemoteInput

class NotificationHelper(private val ctx: Context) {

    companion object {
        const val CH_CONNECTION      = "hexdroid_connection"
        const val CH_HIGHLIGHT_SILENT = "hexdroid_highlight_silent"
        const val CH_HIGHLIGHT_SOUND  = "hexdroid_highlight_sound"
        const val CH_PM  = "hexdroid_pm"
        const val CH_DCC = "hexdroid_dcc"

        /** Per-network highlight channel ID. Each network gets its own channel so the user
         *  can set a distinct sound in Android system settings. Falls back to the global
         *  channel on API < 26 where channels don't exist. */
        fun networkHighlightChannelId(networkName: String, sound: Boolean): String {
            val safe = networkName.replace(Regex("[^A-Za-z0-9_]"), "_").take(40)
            return if (sound) "hexdroid_net_${safe}_sound" else "hexdroid_net_${safe}_silent"
        }
        fun networkPmChannelId(networkName: String): String {
            val safe = networkName.replace(Regex("[^A-Za-z0-9_]"), "_").take(40)
            return "hexdroid_net_${safe}_pm"
        }

        const val NOTIF_ID_CONNECTION = 1001

        const val EXTRA_NETWORK_ID = "extra_network_id"
        const val EXTRA_BUFFER     = "extra_buffer"
        const val EXTRA_ACTION     = "extra_action"
        const val EXTRA_MSG_ID     = "extra_msg_id"   // kept for compat; unused for scroll
        /** Stable cross-session anchor for scrolling to the notified message.
         *  Format: "msgid:<ircMsgId>" when the server provides one,
         *  otherwise "ts:<epochMs>|<nick>|<textPrefix80>". */
        const val EXTRA_MSG_ANCHOR = "extra_msg_anchor"
        /** The notification's own ID, included so the reply receiver can cancel it. */
        const val EXTRA_NOTIF_ID   = "extra_notif_id"
        /** RemoteInput key for the inline-reply text typed in the notification drawer. */
        const val EXTRA_REPLY_TEXT    = "extra_reply_text"
        /** Nick of the message sender being replied to. */
        const val EXTRA_FROM          = "extra_from"
        /** Short snippet of the original message for quote-fallback. Max 100 chars. */
        const val EXTRA_ORIGINAL_TEXT = "extra_original_text"

        const val ACTION_QUIT             = "action_quit"
        const val ACTION_EXIT             = "action_exit"
        const val ACTION_OPEN_TRANSFERS   = "action_open_transfers"
        const val ACTION_OPEN_DCC_CHAT    = "action_open_dcc_chat"
        const val ACTION_INLINE_REPLY     = "action_inline_reply"

        fun cancelAll(ctx: Context) { NotificationManagerCompat.from(ctx).cancelAll() }
    }

    /** Create (or no-op if already exists) per-network notification channels for [networkName].
     *  Called lazily when the first notification for that network fires.
     *  Android deduplicates channel creation so repeated calls are cheap. */
    fun ensureNetworkChannels(networkName: String) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val silentId = networkHighlightChannelId(networkName, sound = false)
        val soundId  = networkHighlightChannelId(networkName, sound = true)
        val pmId     = networkPmChannelId(networkName)
        if (nm.getNotificationChannel(soundId) != null) return  // already created
        val silent = NotificationChannel(silentId, "$networkName Highlights (Silent)", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(false)
            group = "hexdroid_networks"
        }
        val sound = NotificationChannel(soundId, "$networkName Highlights", NotificationManager.IMPORTANCE_DEFAULT).apply {
            group = "hexdroid_networks"
        }
        val pm = NotificationChannel(pmId, "$networkName Private Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
            group = "hexdroid_networks"
        }
        runCatching {
            nm.createNotificationChannelGroup(android.app.NotificationChannelGroup("hexdroid_networks", "IRC Networks"))
            nm.createNotificationChannel(silent)
            nm.createNotificationChannel(sound)
            nm.createNotificationChannel(pm)
        }
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = try {
            ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        } catch (_: Throwable) { return }

        val conn = NotificationChannel(CH_CONNECTION, "IRC Connection", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            description = "Connection status while HexDroid IRC is connected"
        }
        val highlightSilent = NotificationChannel(CH_HIGHLIGHT_SILENT, "IRC Highlights (Silent)", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            enableVibration(false)
        }
        val highlightSound = NotificationChannel(CH_HIGHLIGHT_SOUND, "IRC Highlights", NotificationManager.IMPORTANCE_DEFAULT)
        val pm  = NotificationChannel(CH_PM,  "IRC Private Messages", NotificationManager.IMPORTANCE_DEFAULT)
        val dcc = NotificationChannel(CH_DCC, "DCC Requests",         NotificationManager.IMPORTANCE_HIGH)

        try {
            nm.createNotificationChannel(conn)
            nm.createNotificationChannel(highlightSilent)
            nm.createNotificationChannel(highlightSound)
            nm.createNotificationChannel(pm)
            nm.createNotificationChannel(dcc)
        } catch (_: Throwable) {}
    }

    private fun actionPendingIntent(networkId: String, action: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_NETWORK_ID, networkId)
            .putExtra(EXTRA_ACTION, action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(ctx, (networkId + "|" + action).hashCode(), i, flags)
    }

    private fun openBufferPendingIntent(networkId: String, buffer: String, msgId: Long = -1L, msgAnchor: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NETWORK_ID, networkId)
            putExtra(EXTRA_BUFFER, buffer)
            if (msgId >= 0L) putExtra(EXTRA_MSG_ID, msgId)
            if (msgAnchor != null) putExtra(EXTRA_MSG_ANCHOR, msgAnchor)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(ctx, (networkId + "|" + buffer + "|" + msgId).hashCode(), i, flags)
    }

    private fun openTransfersPendingIntent(networkId: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NETWORK_ID, networkId)
            putExtra(EXTRA_ACTION, ACTION_OPEN_TRANSFERS)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(ctx, (networkId + "|open_transfers").hashCode(), i, flags)
    }

    /**
     * Builds a mutable [PendingIntent] targeting [NotificationReplyReceiver].
     *
     * The intent carries [networkId], [buffer], and [notifId] so the receiver can
     * send the message to the right place and then cancel the notification.
     *
     * Must be FLAG_MUTABLE: Android requires RemoteInput reply intents to be mutable
     * so the system can attach the RemoteInput results bundle before delivery.
     */
    private fun replyPendingIntent(networkId: String, buffer: String, notifId: Int, from: String = "", originalText: String = ""): PendingIntent {
        val i = Intent(ctx, NotificationReplyReceiver::class.java).apply {
            action = ACTION_INLINE_REPLY
            putExtra(EXTRA_NETWORK_ID, networkId)
            putExtra(EXTRA_BUFFER, buffer)
            putExtra(EXTRA_NOTIF_ID, notifId)
            if (from.isNotBlank()) putExtra(EXTRA_FROM, from)
            if (originalText.isNotBlank()) putExtra(EXTRA_ORIGINAL_TEXT, originalText)
        }
        // FLAG_MUTABLE is required  - Android needs to attach the RemoteInput results bundle
        // to the intent before delivery. On API < 31 the constant doesn't exist yet but
        // the value (0x02000000) is still accepted; use the raw constant defensively.
        // Do NOT add FLAG_IMMUTABLE here; that would prevent the system from mutating it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0x02000000
        // Use a unique request code so different buffers get independent pending intents.
        return PendingIntent.getBroadcast(ctx, (networkId + "|reply|" + buffer).hashCode(), i, flags)
    }

    private fun buildReplyAction(networkId: String, buffer: String, notifId: Int, from: String = "", originalText: String = ""): NotificationCompat.Action? {
        return runCatching {
            val remoteInput = RemoteInput.Builder(EXTRA_REPLY_TEXT)
                .setLabel("Reply…")
                .build()
            NotificationCompat.Action.Builder(
                0,  // no icon
                "Reply",
                replyPendingIntent(networkId, buffer, notifId, from, originalText),
            )
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .build()
        }.getOrNull()
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
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID_CONNECTION, buildConnectionNotification(networkId, serverLabel, status))
    }

    fun cancelConnection() { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID_CONNECTION) }

    fun notifyHighlight(networkId: String, buffer: String, text: String, playSound: Boolean, msgId: Long = -1L, displayTitle: String = buffer, from: String = "", originalText: String = "", msgAnchor: String? = null, networkName: String = "") {
        ensureChannels()
        val netName = networkName.ifBlank { displayTitle }
        ensureNetworkChannels(netName)
        val channelId = networkHighlightChannelId(netName, playSound)
        val notifId = (System.currentTimeMillis() % 100000).toInt()
        val n = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(displayTitle)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openBufferPendingIntent(networkId, buffer, msgId, msgAnchor))
            .apply { buildReplyAction(networkId, buffer, notifId, from, originalText)?.let { addAction(it) } }
            .build()
        NotificationManagerCompat.from(ctx).notify(notifId, n)
    }

    fun notifyPm(networkId: String, buffer: String, text: String, msgId: Long = -1L, displayTitle: String = buffer, from: String = "", originalText: String = "", msgAnchor: String? = null, networkName: String = "") {
        ensureChannels()
        val netName = networkName.ifBlank { displayTitle }
        ensureNetworkChannels(netName)
        val channelId = networkPmChannelId(netName)
        val notifId = (System.currentTimeMillis() % 100000).toInt()
        val n = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(displayTitle)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openBufferPendingIntent(networkId, buffer, msgId, msgAnchor))
            .apply { buildReplyAction(networkId, buffer, notifId, from, originalText)?.let { addAction(it) } }
            .build()
        NotificationManagerCompat.from(ctx).notify(notifId, n)
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

    fun notifyDccIncomingChat(networkId: String, from: String, dccBufferKey: String? = null) {
        ensureChannels()
        val contentIntent = if (dccBufferKey != null)
            openBufferPendingIntent(networkId, dccBufferKey)
        else
            openTransfersPendingIntent(networkId)

        val n = NotificationCompat.Builder(ctx, CH_DCC)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Incoming DCC chat from $from")
            .setContentText("Tap to open — or use Transfers to accept / reject")
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Open Transfers", openTransfersPendingIntent(networkId))
            .build()
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt(), n)
    }
}