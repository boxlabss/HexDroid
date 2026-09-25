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
        /** Set once the channels exist; they persist, so later calls can skip creating them. */
        @Volatile private var channelsReady = false

        const val CH_CONNECTION      = "hexdroid_connection"
        const val CH_HIGHLIGHT_SILENT = "hexdroid_highlight_silent"
        const val CH_HIGHLIGHT_SOUND  = "hexdroid_highlight_sound"
        const val CH_PM  = "hexdroid_pm"
        const val CH_DCC = "hexdroid_dcc"
        const val CH_ERROR = "hexdroid_error"

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
        /** Accept the DCC file offer identified by [EXTRA_DCC_FROM] + [EXTRA_DCC_FILENAME]. */
        const val ACTION_ACCEPT_DCC       = "action_accept_dcc"
        const val EXTRA_DCC_FROM          = "extra_dcc_from"
        const val EXTRA_DCC_FILENAME      = "extra_dcc_filename"

        fun cancelAll(ctx: Context) { NotificationManagerCompat.from(ctx).cancelAll() }

        /**
         * Tag identifying every notification raised for one buffer, so they can be taken
         * down together once the user has seen the conversation.
         */
        fun notifTagFor(networkId: String, buffer: String): String = "$networkId::$buffer"

        // Monotonically-increasing notification ID counter.
        // Using System.currentTimeMillis() % 100000 causes two problems:
        //   1. Two notifications within the same millisecond silently replace each other.
        //   2. Notifications 100 000 ms (~100 s) apart share the same ID and also collide.
        // An AtomicInteger counter avoids both and is safe across concurrent notify() calls.
        // Start at 2000 to leave room below for named constants like NOTIF_ID_CONNECTION.
        private val notifIdCounter = java.util.concurrent.atomic.AtomicInteger(2000)
        fun nextNotifId(): Int = notifIdCounter.incrementAndGet()

        // Request codes for notifications that must stay individually addressable (highlights, PMs,
        // inline replies). A counter rather than hashCode(), whose collisions let one buffer's tap
        // intent overwrite another's. The connection notification uses a stable code instead.
        private val piRequestCounter = java.util.concurrent.atomic.AtomicInteger(0)
        fun nextPiRequestCode(): Int = piRequestCounter.incrementAndGet()

        /**
         * Stable request code for the connection notification's tap intent. The notification is
         * rebuilt on every status change, and some Samsung firmware rate-limits PendingIntent
         * creation per UID and throws; a stable code with FLAG_UPDATE_CURRENT updates the existing
         * PendingIntent instead of creating one.
         */
        const val CONNECTION_PI_REQUEST_CODE = 100
        const val CONNECTION_TRANSFERS_PI_REQUEST_CODE = 101
        const val CONNECTION_QUIT_PI_REQUEST_CODE = 102
        const val CONNECTION_EXIT_PI_REQUEST_CODE = 103

        /**
         * Create a PendingIntent, returning null instead of throwing when some Samsung firmware's
         * per-UID rate limit refuses it.
         */
        internal inline fun safePi(block: () -> PendingIntent): PendingIntent? =
            runCatching(block).getOrNull()
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

    /**
     * Create the app's channels, once per process. Every notification path calls this, and each
     * creation is a call into the system; on some ROMs (HyperOS) those are slow enough that
     * repeating them on every status update starves the main thread.
     */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        if (channelsReady) return
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
        val error = NotificationChannel(CH_ERROR, "IRC Errors", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Server and connection errors (only when enabled per network)"
        }

        try {
            nm.createNotificationChannel(conn)
            nm.createNotificationChannel(highlightSilent)
            nm.createNotificationChannel(highlightSound)
            nm.createNotificationChannel(pm)
            nm.createNotificationChannel(dcc)
            nm.createNotificationChannel(error)
            channelsReady = true
        } catch (_: Throwable) {}
    }

    /**
     * The highlight channel to post on for [networkName].
     *
     * A blank name means the network is not known, which happens for a Web Push
     * notification: the push carries no network and the process may hold no state at all.
     * Those fall back to the shared channel rather than the per-network one.
     */
    private fun highlightChannelFor(networkName: String, sound: Boolean): String {
        if (networkName.isBlank()) return if (sound) CH_HIGHLIGHT_SOUND else CH_HIGHLIGHT_SILENT
        ensureNetworkChannels(networkName)
        return networkHighlightChannelId(networkName, sound)
    }

    /** The private-message channel for [networkName]. See [highlightChannelFor]. */
    private fun pmChannelFor(networkName: String): String {
        if (networkName.isBlank()) return CH_PM
        ensureNetworkChannels(networkName)
        return networkPmChannelId(networkName)
    }

    private fun actionPendingIntent(networkId: String, action: String, stableRequestCode: Int = -1): PendingIntent? {
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_NETWORK_ID, networkId)
            .putExtra(EXTRA_ACTION, action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val rc = if (stableRequestCode >= 0) stableRequestCode else nextPiRequestCode()
        return safePi { PendingIntent.getActivity(ctx, rc, i, flags) }
    }

    private fun openBufferPendingIntent(networkId: String, buffer: String, msgId: Long = -1L, msgAnchor: String? = null, stableRequestCode: Int = -1): PendingIntent? {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NETWORK_ID, networkId)
            putExtra(EXTRA_BUFFER, buffer)
            if (msgId >= 0L) putExtra(EXTRA_MSG_ID, msgId)
            if (msgAnchor != null) putExtra(EXTRA_MSG_ANCHOR, msgAnchor)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val rc = if (stableRequestCode >= 0) stableRequestCode else nextPiRequestCode()
        return safePi { PendingIntent.getActivity(ctx, rc, i, flags) }
    }

    private fun openTransfersPendingIntent(networkId: String, stableRequestCode: Int = -1): PendingIntent? {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NETWORK_ID, networkId)
            putExtra(EXTRA_ACTION, ACTION_OPEN_TRANSFERS)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val rc = if (stableRequestCode >= 0) stableRequestCode else nextPiRequestCode()
        return safePi { PendingIntent.getActivity(ctx, rc, i, flags) }
    }

    /**
     * A mutable PendingIntent for [NotificationReplyReceiver] carrying [networkId], [buffer] and
     * [notifId]. Must be FLAG_MUTABLE so the system can attach the RemoteInput results.
     */
    private fun replyPendingIntent(networkId: String, buffer: String, notifId: Int, from: String = "", originalText: String = ""): PendingIntent? {
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
        return safePi { PendingIntent.getBroadcast(ctx, nextPiRequestCode(), i, flags) }
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
        val b = NotificationCompat.Builder(ctx, CH_CONNECTION)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Connected to $serverLabel")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        // Stable request codes, since this notification is rebuilt on every status change (see
        // [CONNECTION_PI_REQUEST_CODE]). If PendingIntent creation still fails, the notification
        // shows without its tap targets.
        openBufferPendingIntent(networkId, "*server*", stableRequestCode = CONNECTION_PI_REQUEST_CODE)
            ?.let { b.setContentIntent(it) }
        actionPendingIntent(networkId, ACTION_QUIT, stableRequestCode = CONNECTION_QUIT_PI_REQUEST_CODE)
            ?.let { b.addAction(0, "Quit", it) }
        actionPendingIntent(networkId, ACTION_EXIT, stableRequestCode = CONNECTION_EXIT_PI_REQUEST_CODE)
            ?.let { b.addAction(0, "Exit", it) }
        return b.build()
    }

    fun showConnection(networkId: String, serverLabel: String, status: String) {
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID_CONNECTION, buildConnectionNotification(networkId, serverLabel, status))
    }

    fun cancelConnection() { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID_CONNECTION) }

    /**
     * Cancel every notification for one buffer. Ids come from a counter so messages stack, so the
     * per-buffer tag and the active list are used to find them.
     */
    fun cancelBuffer(networkId: String, buffer: String) {
        val tag = notifTagFor(networkId, buffer)
        val mgr = NotificationManagerCompat.from(ctx)
        runCatching {
            for (sbn in mgr.activeNotifications) {
                if (sbn.tag == tag) mgr.cancel(sbn.tag, sbn.id)
            }
        }
    }

    /** Post a highlight notification. Returns false when this message has already been notified. */
    fun notifyHighlight(networkId: String, buffer: String, text: String, playSound: Boolean, msgId: Long = -1L, displayTitle: String = buffer, from: String = "", originalText: String = "", msgAnchor: String? = null, networkName: String = ""): Boolean {
        // One message, one ping: a Web Push and the live connection can both surface the
        // same message, and which of them fires depends on the bouncer's configuration.
        if (!com.boxlabs.hexdroid.data.NotifiedMessages.claim(ctx, msgAnchor)) return false
        ensureChannels()
        val channelId = highlightChannelFor(networkName, playSound)
        val notifId = nextNotifId()
        val builder = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(displayTitle)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        openBufferPendingIntent(networkId, buffer, msgId, msgAnchor)?.let { builder.setContentIntent(it) }
        // Without a network the reply has nowhere to go: the receiver looks the connection
        // up by id and would report the reply as undeliverable however well connected the
        // user actually is. Offering no Reply button is better than offering one that always fails.
        if (networkId.isNotBlank()) {
            buildReplyAction(networkId, buffer, notifId, from, originalText)?.let { builder.addAction(it) }
        }
        NotificationManagerCompat.from(ctx).notify(notifTagFor(networkId, buffer), notifId, builder.build())
        return true
    }

    /** Post a server/connection error notification. Opt-in per network (NetworkProfile.notifyOnErrors).
     *  Uses a dedicated low-key channel and has no inline-reply action (you can't reply to an error). */
    fun notifyError(networkId: String, buffer: String, text: String, displayTitle: String = buffer, msgAnchor: String? = null) {
        ensureChannels()
        val builder = NotificationCompat.Builder(ctx, CH_ERROR)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(displayTitle)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
        openBufferPendingIntent(networkId, buffer, msgAnchor = msgAnchor)?.let { builder.setContentIntent(it) }
        NotificationManagerCompat.from(ctx).notify(nextNotifId(), builder.build())
    }

    /** Post a private-message notification. Returns false when this message has already been notified. */
    fun notifyPm(networkId: String, buffer: String, text: String, msgId: Long = -1L, displayTitle: String = buffer, from: String = "", originalText: String = "", msgAnchor: String? = null, networkName: String = ""): Boolean {
        // See notifyHighlight: the same message can arrive by push and by connection.
        if (!com.boxlabs.hexdroid.data.NotifiedMessages.claim(ctx, msgAnchor)) return false
        ensureChannels()
        val channelId = pmChannelFor(networkName)
        val notifId = nextNotifId()
        val builder = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(displayTitle)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        openBufferPendingIntent(networkId, buffer, msgId, msgAnchor)?.let { builder.setContentIntent(it) }
        // See notifyHighlight: no network means no deliverable reply.
        if (networkId.isNotBlank()) {
            buildReplyAction(networkId, buffer, notifId, from, originalText)?.let { builder.addAction(it) }
        }
        NotificationManagerCompat.from(ctx).notify(notifTagFor(networkId, buffer), notifId, builder.build())
        return true
    }

    fun notifyFileDone(networkId: String, filename: String, where: String) {
        ensureChannels()
        val builder = NotificationCompat.Builder(ctx, CH_DCC)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DCC complete")
            .setContentText("$filename saved to $where")
            .setAutoCancel(true)
        openTransfersPendingIntent(networkId)?.let { builder.setContentIntent(it) }
        NotificationManagerCompat.from(ctx).notify(nextNotifId(), builder.build())
    }

    fun notifyDccIncomingFile(networkId: String, from: String, filename: String) {
        ensureChannels()
        val notifId = nextNotifId()
        val acceptIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NETWORK_ID, networkId)
            putExtra(EXTRA_ACTION, ACTION_ACCEPT_DCC)
            putExtra(EXTRA_DCC_FROM, from)
            putExtra(EXTRA_DCC_FILENAME, filename)
            putExtra(EXTRA_NOTIF_ID, notifId)
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val acceptPi = safePi { PendingIntent.getActivity(ctx, nextPiRequestCode(), acceptIntent, piFlags) }
        val builder = NotificationCompat.Builder(ctx, CH_DCC)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Incoming file from $from")
            .setContentText(filename)
            .setAutoCancel(true)
        openTransfersPendingIntent(networkId)?.let { builder.setContentIntent(it) }
        if (acceptPi != null) builder.addAction(0, "Accept", acceptPi)
        NotificationManagerCompat.from(ctx).notify(notifId, builder.build())
    }

    fun notifyDccIncomingChat(networkId: String, from: String, dccBufferKey: String? = null) {
        ensureChannels()
        val contentIntent = if (dccBufferKey != null)
            openBufferPendingIntent(networkId, dccBufferKey)
        else
            openTransfersPendingIntent(networkId)

        val builder = NotificationCompat.Builder(ctx, CH_DCC)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Incoming DCC chat from $from")
            .setContentText("Tap to open — or use Transfers to accept / reject")
            .setAutoCancel(true)
        contentIntent?.let { builder.setContentIntent(it) }
        openTransfersPendingIntent(networkId)?.let { builder.addAction(0, "Open Transfers", it) }
        NotificationManagerCompat.from(ctx).notify(nextNotifId(), builder.build())
    }
}
