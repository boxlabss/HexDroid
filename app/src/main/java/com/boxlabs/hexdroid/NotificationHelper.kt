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
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

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
            val safe = channelSafeName(networkName)
            return if (sound) "hexdroid_net_${safe}_sound" else "hexdroid_net_${safe}_silent"
        }
        fun networkPmChannelId(networkName: String): String =
            "hexdroid_net_${channelSafeName(networkName)}_pm"

        /**
         * [networkName] reduced to channel-id characters. A name with non-ASCII characters also
         * gets a hash of the original, so names in other scripts don't all reduce to underscores
         * and share one channel.
         */
        private fun channelSafeName(networkName: String): String {
            val safe = networkName.replace(Regex("[^A-Za-z0-9_]"), "_").take(40)
            return if (networkName.all { it.code < 0x80 }) safe
            else safe + "_" + Integer.toHexString(networkName.hashCode())
        }

        const val NOTIF_ID_CONNECTION = 1001
        /** Id of a conversation's notification. The buffer tag tells conversations apart. */
        const val NOTIF_ID_CONVERSATION = 1002
        /** Id of a notify list alert. The buffer tag tells people apart, so a newer alert replaces an older one. */
        const val NOTIF_ID_WATCHED = 1003
        private const val MAX_CONVERSATION_MESSAGES = 12
        private const val SHORTCUT_CATEGORY_CONVERSATION = "android.shortcut.conversation"
        /** Category of the share target declared in res/xml/shortcuts.xml. */
        const val SHORTCUT_CATEGORY_SHARE = "com.boxlabs.hexdroid.category.SHARE_TARGET"

        /** Shortcut ids pushed by this process, with the key of the icon each was pushed with. */
        private val publishedShortcuts = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** What each conversation notification was last posted with, by tag. */
        private val lastPosts = java.util.concurrent.ConcurrentHashMap<String, ConversationPost>()

        /** Serialises conversation posts, so a refresh cannot overwrite a newer message. */
        private val postLock = Any()

        /** When each avatar was last fetched for a conversation, by tag and URL. */
        private val avatarFetches = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private const val AVATAR_FETCH_GAP_MS = 60_000L

        /** The id of a conversation's shortcut, also used as its locus id. */
        fun conversationShortcutId(networkId: String, buffer: String): String =
            "conv:" + notifTagFor(networkId, buffer).lowercase(java.util.Locale.ROOT)

        const val EXTRA_NETWORK_ID = "extra_network_id"
        const val EXTRA_BUFFER     = "extra_buffer"
        const val EXTRA_ACTION     = "extra_action"
        const val EXTRA_MSG_ID     = "extra_msg_id"   // kept for compat; unused for scroll
        /** Stable cross-session anchor for scrolling to the notified message.
         *  Format: "msgid:<ircMsgId>" when the server provides one,
         *  otherwise "ts:<epochMs>|<nick>|<textPrefix80>". */
        const val EXTRA_MSG_ANCHOR = "extra_msg_anchor"
        /** Server msgid of the message a notification is for, so an inline reply can quote it. */
        const val EXTRA_REPLY_MSGID = "extra_reply_msgid"
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
        /** Mark a conversation read from its notification. */
        const val ACTION_MARK_READ        = "com.boxlabs.hexdroid.MARK_READ"
        /** Silence a conversation's notifications for [MUTE_FOR_MS]. */
        const val ACTION_MUTE_FOR         = "com.boxlabs.hexdroid.MUTE_FOR"
        const val MUTE_FOR_MS             = 60 * 60_000L
        /** Intent action of a conversation shortcut. */
        const val ACTION_OPEN_CONVERSATION = "com.boxlabs.hexdroid.OPEN_CONVERSATION"
        /** Accept the DCC file offer identified by [EXTRA_DCC_FROM] + [EXTRA_DCC_FILENAME]. */
        const val ACTION_ACCEPT_DCC       = "action_accept_dcc"
        const val EXTRA_DCC_FROM          = "extra_dcc_from"
        const val EXTRA_DCC_FILENAME      = "extra_dcc_filename"

        /**
         * Carried by the quit, exit and accept-DCC intents. MainActivity is exported, so an
         * action is honoured only with this process's token.
         */
        const val EXTRA_ACTION_TOKEN = "extra_action_token"
        private val actionToken: String = java.util.UUID.randomUUID().toString()

        /** True when [intent] came from one of this process's notifications. */
        fun hasActionToken(intent: Intent): Boolean =
            runCatching { intent.getStringExtra(EXTRA_ACTION_TOKEN) }.getOrNull() == actionToken

        fun cancelAll(ctx: Context) {
            lastPosts.clear()
            NotificationManagerCompat.from(ctx).cancelAll()
        }

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
        // intent overwrite another's. The connection notification uses a stable code instead, and
        // the counter starts well clear of those codes. Its random start keeps a new process from
        // reusing the codes of notifications an earlier one left in the tray.
        private val piRequestCounter = java.util.concurrent.atomic.AtomicInteger(
            PI_COUNTER_BASE + kotlin.random.Random.nextInt(1_000_000_000)
        )
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

        /** Lowest request code [nextPiRequestCode] hands out. */
        private const val PI_COUNTER_BASE = 1000

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
        val silent = NotificationChannel(silentId, ctx.getString(R.string.notif_channel_net_highlight_silent, networkName), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(false)
            group = "hexdroid_networks"
        }
        val sound = NotificationChannel(soundId, ctx.getString(R.string.notif_channel_net_highlight_sound, networkName), NotificationManager.IMPORTANCE_DEFAULT).apply {
            group = "hexdroid_networks"
        }
        val pm = NotificationChannel(pmId, ctx.getString(R.string.notif_channel_net_pm, networkName), NotificationManager.IMPORTANCE_DEFAULT).apply {
            group = "hexdroid_networks"
        }
        runCatching {
            nm.createNotificationChannelGroup(android.app.NotificationChannelGroup("hexdroid_networks", ctx.getString(R.string.notif_channel_group_networks)))
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

        val conn = NotificationChannel(CH_CONNECTION, ctx.getString(R.string.notif_channel_connection), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            description = ctx.getString(R.string.notif_channel_connection_desc)
        }
        val highlightSilent = NotificationChannel(CH_HIGHLIGHT_SILENT, ctx.getString(R.string.notif_channel_highlight_silent), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            enableVibration(false)
        }
        val highlightSound = NotificationChannel(CH_HIGHLIGHT_SOUND, ctx.getString(R.string.notif_channel_highlight_sound), NotificationManager.IMPORTANCE_DEFAULT)
        val pm  = NotificationChannel(CH_PM, ctx.getString(R.string.notif_channel_pm), NotificationManager.IMPORTANCE_DEFAULT)
        val dcc = NotificationChannel(CH_DCC, ctx.getString(R.string.notif_channel_dcc), NotificationManager.IMPORTANCE_HIGH)
        val error = NotificationChannel(CH_ERROR, ctx.getString(R.string.notif_channel_errors), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = ctx.getString(R.string.notif_channel_errors_desc)
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
            .putExtra(EXTRA_ACTION_TOKEN, actionToken)
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
    private fun replyPendingIntent(networkId: String, buffer: String, notifId: Int, from: String = "", originalText: String = "", replyMsgId: String? = null): PendingIntent? {
        val i = Intent(ctx, NotificationReplyReceiver::class.java).apply {
            action = ACTION_INLINE_REPLY
            putExtra(EXTRA_NETWORK_ID, networkId)
            putExtra(EXTRA_BUFFER, buffer)
            putExtra(EXTRA_NOTIF_ID, notifId)
            if (from.isNotBlank()) putExtra(EXTRA_FROM, from)
            if (originalText.isNotBlank()) putExtra(EXTRA_ORIGINAL_TEXT, originalText)
            if (!replyMsgId.isNullOrBlank()) putExtra(EXTRA_REPLY_MSGID, replyMsgId)
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

    private fun buildReplyAction(networkId: String, buffer: String, notifId: Int, from: String = "", originalText: String = "", msgAnchor: String? = null): NotificationCompat.Action? {
        return runCatching {
            val remoteInput = RemoteInput.Builder(EXTRA_REPLY_TEXT)
                .setLabel(ctx.getString(R.string.notif_reply_hint))
                .build()
            val replyMsgId = msgAnchor?.takeIf { it.startsWith("msgid:") }?.removePrefix("msgid:")
            NotificationCompat.Action.Builder(
                0,  // no icon
                ctx.getString(R.string.notif_reply),
                replyPendingIntent(networkId, buffer, notifId, from, originalText, replyMsgId),
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
            .setContentTitle(serverLabel)
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
            ?.let { b.addAction(0, ctx.getString(R.string.notif_quit), it) }
        actionPendingIntent(networkId, ACTION_EXIT, stableRequestCode = CONNECTION_EXIT_PI_REQUEST_CODE)
            ?.let { b.addAction(0, ctx.getString(R.string.menu_exit), it) }
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
        // A pushed message whose network was not known is tagged with an empty network id.
        val pushTag = notifTagFor("", buffer)
        lastPosts.remove(tag)
        lastPosts.remove(pushTag)
        val mgr = NotificationManagerCompat.from(ctx)
        runCatching {
            for (sbn in mgr.activeNotifications) {
                val t = sbn.tag ?: continue
                if (t == tag || t.equals(pushTag, ignoreCase = true)) mgr.cancel(t, sbn.id)
            }
        }
    }

    /** A broadcast to [NotificationReplyReceiver] that runs [action] on [buffer]. */
    private fun conversationActionIntent(action: String, networkId: String, buffer: String): PendingIntent? {
        val i = Intent(ctx, NotificationReplyReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_NETWORK_ID, networkId)
            putExtra(EXTRA_BUFFER, buffer)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return safePi { PendingIntent.getBroadcast(ctx, nextPiRequestCode(), i, flags) }
    }

    /** "Notify list" alert: [nick] on [networkId] came online. Tapping opens a query with them. */
    fun notifyWatchedOnline(networkId: String, nick: String, networkName: String) {
        ensureChannels()
        val builder = NotificationCompat.Builder(ctx, CH_HIGHLIGHT_SILENT)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(ctx.getString(R.string.notif_watched_online_title, nick))
            .setContentText(ctx.getString(R.string.notif_watched_online_text, networkName))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
        openBufferPendingIntent(networkId, nick)?.let { builder.setContentIntent(it) }
        runCatching { NotificationManagerCompat.from(ctx).notify(notifTagFor(networkId, nick), NOTIF_ID_WATCHED, builder.build()) }
    }

    /**
     * Post a highlight notification. Returns false when this message has already been notified.
     * [body] is the message without the sender, for the conversation view; [text] is the one-line
     * fallback. A channel highlight is shown as a group conversation.
     */
    fun notifyHighlight(networkId: String, buffer: String, text: String, playSound: Boolean, msgId: Long = -1L, displayTitle: String = buffer, from: String = "", originalText: String = "", msgAnchor: String? = null, networkName: String = "", body: String? = null, timeMs: Long = System.currentTimeMillis(), selfName: String? = null): Boolean {
        // One message, one ping: a Web Push and the live connection can both surface the
        // same message, and which of them fires depends on the bouncer's configuration.
        if (!com.boxlabs.hexdroid.data.NotifiedMessages.claim(ctx, msgAnchor)) return false
        ensureChannels()
        postConversation(
            channelId = highlightChannelFor(networkName, playSound),
            networkId = networkId,
            buffer = buffer,
            title = displayTitle,
            from = from,
            text = text,
            body = body ?: text,
            timeMs = timeMs,
            isGroup = true,
            msgId = msgId,
            originalText = originalText,
            msgAnchor = msgAnchor,
            selfName = selfName,
        )
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
    fun notifyPm(networkId: String, buffer: String, text: String, msgId: Long = -1L, displayTitle: String = buffer, from: String = "", originalText: String = "", msgAnchor: String? = null, networkName: String = "", body: String? = null, timeMs: Long = System.currentTimeMillis(), selfName: String? = null): Boolean {
        // See notifyHighlight: the same message can arrive by push and by connection.
        if (!com.boxlabs.hexdroid.data.NotifiedMessages.claim(ctx, msgAnchor)) return false
        ensureChannels()
        postConversation(
            channelId = pmChannelFor(networkName),
            networkId = networkId,
            buffer = buffer,
            title = displayTitle,
            from = from,
            text = text,
            body = body ?: text,
            timeMs = timeMs,
            isGroup = false,
            msgId = msgId,
            originalText = originalText,
            msgAnchor = msgAnchor,
            selfName = selfName,
        )
        return true
    }

    /** A notification that is not part of a conversation, such as an invite. */
    fun notifySimple(networkId: String, buffer: String, title: String, text: String) {
        ensureChannels()
        val builder = NotificationCompat.Builder(ctx, CH_HIGHLIGHT_SILENT)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
        openBufferPendingIntent(networkId, buffer)?.let { builder.setContentIntent(it) }
        NotificationManagerCompat.from(ctx).notify(notifTagFor(networkId, buffer), nextNotifId(), builder.build())
    }

    /** A conversation notification as last posted. */
    private data class ConversationPost(
        val channelId: String,
        val networkId: String,
        val buffer: String,
        val title: String,
        val from: String,
        val text: String,
        val timeMs: Long,
        val isGroup: Boolean,
        val msgId: Long,
        val originalText: String,
        val msgAnchor: String?,
        val selfName: String?,
        val messages: List<PostedMessage>,
    )

    /** One message of a conversation notification, without its sender's picture. */
    private data class PostedMessage(
        val text: CharSequence?,
        val timeMs: Long,
        val sender: String?,
        val dataMimeType: String? = null,
        val dataUri: android.net.Uri? = null,
    )

    /** A conversation's icon, with a key that changes when the picture does. */
    private class ConversationIcon(val key: String, val icon: IconCompat)

    /**
     * One notification per conversation, in MessagingStyle, carrying the messages still in the
     * shade for it. With a network to open it on, it is tied to a long-lived conversation
     * shortcut, so Android lists it with conversations, allows it to be made a priority
     * conversation, and can show it as a bubble.
     */
    private fun postConversation(
        channelId: String,
        networkId: String,
        buffer: String,
        title: String,
        from: String,
        text: String,
        body: String,
        timeMs: Long,
        isGroup: Boolean,
        msgId: Long,
        originalText: String,
        msgAnchor: String?,
        selfName: String?,
    ) {
        val tag = notifTagFor(networkId, buffer)
        val missing = HashSet<String>()
        synchronized(postLock) {
            // Messages already in the shade for this conversation stay in it.
            val previous = runCatching {
                NotificationManagerCompat.from(ctx).activeNotifications
                    .firstOrNull { it.tag == tag && it.id == NOTIF_ID_CONVERSATION }
                    ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification) }
            }.getOrNull()
            val messages = previous?.messages.orEmpty()
                .takeLast(MAX_CONVERSATION_MESSAGES - 1)
                .map { m ->
                    PostedMessage(m.text, m.timestamp, m.person?.name?.toString(), m.dataMimeType, m.dataUri)
                } +
                // A message with no sender would read as one of ours.
                PostedMessage(body, timeMs, from.ifBlank { title })
            show(
                ConversationPost(
                    channelId, networkId, buffer, title, from, text, timeMs, isGroup,
                    msgId, originalText, msgAnchor, selfName, messages,
                ),
                alertOnce = false,
                missing = missing,
            )
        }
        fetchAvatars(tag, missing)
    }

    /**
     * Repost [tag]'s conversation silently with the avatars fetched since, if it is still in the
     * shade. Returns false when there was nothing to repost.
     */
    private fun refreshConversation(tag: String): Boolean {
        val missing = HashSet<String>()
        synchronized(postLock) {
            val post = lastPosts[tag] ?: return false
            val showing = runCatching {
                NotificationManagerCompat.from(ctx).activeNotifications
                    .any { it.tag == tag && it.id == NOTIF_ID_CONVERSATION }
            }.getOrDefault(false)
            if (!showing) {
                lastPosts.remove(tag)
                return false
            }
            show(post, alertOnce = true, missing)
        }
        fetchAvatars(tag, missing)
        return true
    }

    /**
     * Report an inline reply to [buffer] that could not be sent. The conversation is reposted so
     * it stops showing the reply as sending, or removed when this process never posted it.
     */
    fun notifyReplyNotSent(networkId: String, buffer: String) {
        ensureChannels()
        val tag = notifTagFor(networkId, buffer)
        val mgr = NotificationManagerCompat.from(ctx)
        if (!refreshConversation(tag)) runCatching { mgr.cancel(tag, NOTIF_ID_CONVERSATION) }
        val builder = NotificationCompat.Builder(ctx, CH_HIGHLIGHT_SILENT)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(ctx.getString(R.string.notif_reply_not_sent_title))
            .setContentText(ctx.getString(R.string.notif_reply_not_sent_text, buffer))
            .setAutoCancel(true)
        openBufferPendingIntent(networkId, buffer)?.let { builder.setContentIntent(it) }
        runCatching { mgr.notify(tag, nextNotifId(), builder.build()) }
    }

    private fun show(post: ConversationPost, alertOnce: Boolean, missing: MutableSet<String>) {
        val tag = notifTagFor(post.networkId, post.buffer)
        val self = Person.Builder()
            .setName(post.selfName?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.notif_self_name))
            .setKey("self")
            .build()
        val style = NotificationCompat.MessagingStyle(self)
            .setConversationTitle(if (post.isGroup) post.title else null)
            .setGroupConversation(post.isGroup)
        for (m in post.messages) {
            val sender = m.sender?.let { personFor(post.networkId, it, missing) }
            val message = NotificationCompat.MessagingStyle.Message(m.text, m.timeMs, sender)
            m.dataMimeType?.let { message.setData(it, m.dataUri) }
            style.addMessage(message)
        }

        val builder = NotificationCompat.Builder(ctx, post.channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(post.title)
            .setContentText(post.text)
            .setStyle(style)
            .setWhen(post.timeMs)
            .setAutoCancel(true)
            .setOnlyAlertOnce(alertOnce)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        openBufferPendingIntent(post.networkId, post.buffer, post.msgId, post.msgAnchor)?.let { builder.setContentIntent(it) }
        // Without a network the reply has nowhere to go: the receiver looks the connection
        // up by id and would report the reply as undeliverable however well connected the
        // user actually is. Offering no Reply button is better than offering one that always fails.
        if (post.networkId.isNotBlank()) {
            buildReplyAction(post.networkId, post.buffer, NOTIF_ID_CONVERSATION, post.from, post.originalText, post.msgAnchor)
                ?.let { builder.addAction(it) }
            conversationActionIntent(ACTION_MARK_READ, post.networkId, post.buffer)?.let {
                builder.addAction(
                    NotificationCompat.Action.Builder(0, ctx.getString(R.string.notif_mark_read), it)
                        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                        .setShowsUserInterface(false)
                        .build()
                )
            }
            conversationActionIntent(ACTION_MUTE_FOR, post.networkId, post.buffer)?.let {
                builder.addAction(
                    NotificationCompat.Action.Builder(0, ctx.getString(R.string.notif_mute_hour), it)
                        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MUTE)
                        .setShowsUserInterface(false)
                        .build()
                )
            }
            // The latest sender's face, so a channel highlight shows who it is from.
            val icon = conversationIcon(post.networkId, post.from.ifBlank { post.title }, missing)
            val person = if (post.isGroup) null else post.from.takeIf { it.isNotBlank() }?.let { personFor(post.networkId, it, missing) }
            val shortcutId = publishConversationShortcut(post.networkId, post.buffer, post.title, person, icon)
            if (shortcutId != null) {
                builder.setShortcutId(shortcutId)
                builder.setLocusId(LocusIdCompat(shortcutId))
                bubbleFor(post.networkId, post.buffer, shortcutId, icon.icon)?.let { builder.setBubbleMetadata(it) }
            }
        }
        NotificationManagerCompat.from(ctx).notify(tag, NOTIF_ID_CONVERSATION, builder.build())
        lastPosts[tag] = post
    }

    /** Fetch the avatars [urls] in the background, reposting [tag]'s conversation as each arrives. */
    private fun fetchAvatars(tag: String, urls: Set<String>) {
        val now = System.currentTimeMillis()
        if (avatarFetches.size > 256) avatarFetches.clear()
        for (url in urls) {
            val last = avatarFetches.put("$tag\u0000$url", now)
            if (last != null && now - last < AVATAR_FETCH_GAP_MS) continue
            RemoteBitmaps.load(url) { refreshConversation(tag) }
        }
    }

    private fun personKey(networkId: String, nick: String): String =
        "$networkId::${nick.lowercase(java.util.Locale.ROOT)}"

    /** The person behind [nick] on [networkId]. */
    private fun personFor(networkId: String, nick: String, missing: MutableSet<String>): Person =
        Person.Builder()
            .setName(nick)
            .setKey(personKey(networkId, nick))
            .setIcon(IconCompat.createWithBitmap(avatarFor(networkId, nick, missing)))
            .build()

    /**
     * [nick]'s metadata avatar once fetched, else a lettered one. A URL not fetched yet is
     * added to [missing].
     */
    private fun avatarFor(networkId: String, nick: String, missing: MutableSet<String>): android.graphics.Bitmap {
        val url = Avatars.urlFor(networkId, nick) ?: return Avatars.letterAvatar(nick)
        val photo = RemoteBitmaps.cached(url)
        if (photo == null) {
            missing += url
            return Avatars.letterAvatar(nick)
        }
        return Avatars.circlePhoto(url, photo)
    }

    /** The conversation icon for [name]: its metadata avatar once fetched, else a lettered one. */
    private fun conversationIcon(networkId: String, name: String, missing: MutableSet<String>): ConversationIcon {
        val url = Avatars.urlFor(networkId, name)
        val photo = url?.let { RemoteBitmaps.cached(it) }
        if (url != null && photo != null) {
            return ConversationIcon("url:$url", IconCompat.createWithAdaptiveBitmap(Avatars.adaptivePhoto(url, photo)))
        }
        if (url != null) missing += url
        return ConversationIcon(
            "letter:" + name.lowercase(java.util.Locale.ROOT),
            IconCompat.createWithAdaptiveBitmap(Avatars.letterAvatar(name, adaptive = true)),
        )
    }

    /**
     * Publish the long-lived shortcut for a conversation and return its id. It is pushed again
     * when its icon changes, on Android 11 and later only, since older releases rate-limit
     * shortcut updates. Returns null when the launcher refuses a new shortcut, in which case
     * the notification is posted without one.
     */
    private fun publishConversationShortcut(
        networkId: String,
        buffer: String,
        title: String,
        person: Person?,
        icon: ConversationIcon,
    ): String? {
        val id = conversationShortcutId(networkId, buffer)
        val published = publishedShortcuts[id]
        if (published == icon.key) return id
        if (published != null && Build.VERSION.SDK_INT < 30) return id
        return runCatching {
            val intent = Intent(ctx, MainActivity::class.java).apply {
                action = ACTION_OPEN_CONVERSATION
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_NETWORK_ID, networkId)
                putExtra(EXTRA_BUFFER, buffer)
            }
            val shortcut = ShortcutInfoCompat.Builder(ctx, id)
                .setShortLabel(title)
                .setLongLabel(title)
                .setIntent(intent)
                .setLongLived(true)
                .setCategories(setOf(SHORTCUT_CATEGORY_CONVERSATION, SHORTCUT_CATEGORY_SHARE))
                .setLocusId(LocusIdCompat(id))
                .setIcon(icon.icon)
                .apply { if (person != null) setPerson(person) }
                .build()
            if (!ShortcutManagerCompat.pushDynamicShortcut(ctx, shortcut)) return if (published != null) id else null
            publishedShortcuts[id] = icon.key
            id
        }.getOrElse { if (published != null) id else null }
    }

    /** Bubble metadata opening [BubbleActivity] on the conversation, or null below Android 11. */
    private fun bubbleFor(networkId: String, buffer: String, shortcutId: String, icon: IconCompat): NotificationCompat.BubbleMetadata? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val intent = Intent(ctx, BubbleActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = android.net.Uri.parse("hexdroid-bubble://" + android.net.Uri.encode(shortcutId))
                putExtra(EXTRA_NETWORK_ID, networkId)
                putExtra(EXTRA_BUFFER, buffer)
            }
            // A bubble's intent must be mutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = safePi { PendingIntent.getActivity(ctx, shortcutId.hashCode(), intent, flags) } ?: return null
            NotificationCompat.BubbleMetadata.Builder(pi, icon)
                .setDesiredHeight(600)
                .setAutoExpandBubble(false)
                .setSuppressNotification(false)
                .build()
        }.getOrNull()
    }

    /** Remove a closed conversation's shortcut and anything in the shade for it. */
    fun removeConversation(networkId: String, buffer: String) {
        cancelBuffer(networkId, buffer)
        val id = conversationShortcutId(networkId, buffer)
        publishedShortcuts.remove(id)
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(ctx, listOf(id)) }
    }

    fun notifyFileDone(networkId: String, filename: String, where: String) {
        ensureChannels()
        val builder = NotificationCompat.Builder(ctx, CH_DCC)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(ctx.getString(R.string.notif_dcc_file_done_title))
            .setContentText(ctx.getString(R.string.notif_dcc_file_done_text, filename, where))
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
            putExtra(EXTRA_ACTION_TOKEN, actionToken)
            putExtra(EXTRA_DCC_FROM, from)
            putExtra(EXTRA_DCC_FILENAME, filename)
            putExtra(EXTRA_NOTIF_ID, notifId)
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val acceptPi = safePi { PendingIntent.getActivity(ctx, nextPiRequestCode(), acceptIntent, piFlags) }
        val builder = NotificationCompat.Builder(ctx, CH_DCC)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(ctx.getString(R.string.notif_dcc_incoming_file_title, from))
            .setContentText(filename)
            .setAutoCancel(true)
        openTransfersPendingIntent(networkId)?.let { builder.setContentIntent(it) }
        if (acceptPi != null) builder.addAction(0, ctx.getString(R.string.transfers_accept), acceptPi)
        NotificationManagerCompat.from(ctx).notify(notifId, builder.build())
    }

    /** Incoming DCC chat offer; tapping opens [dccBuffer] (a buffer name such as DCCCHAT:nick). */
    fun notifyDccIncomingChat(networkId: String, from: String, dccBuffer: String? = null) {
        ensureChannels()
        val contentIntent = if (dccBuffer != null)
            openBufferPendingIntent(networkId, dccBuffer)
        else
            openTransfersPendingIntent(networkId)

        val builder = NotificationCompat.Builder(ctx, CH_DCC)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(ctx.getString(R.string.notif_dcc_incoming_chat_title, from))
            .setContentText(ctx.getString(R.string.notif_dcc_incoming_chat_text))
            .setAutoCancel(true)
        contentIntent?.let { builder.setContentIntent(it) }
        openTransfersPendingIntent(networkId)?.let { builder.addAction(0, ctx.getString(R.string.notif_open_transfers), it) }
        NotificationManagerCompat.from(ctx).notify(nextNotifId(), builder.build())
    }
}
