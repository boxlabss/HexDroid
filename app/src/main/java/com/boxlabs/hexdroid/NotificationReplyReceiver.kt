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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

/**
 * Handles the inline-reply action attached to highlight/PM notifications.
 *
 * Android delivers a broadcast here so the app doesn't need to come to the
 * foreground. We pull the typed text, the original sender nick, and the
 * original message snippet, then send via the ViewModel.
 *
 * If the app process was dead when the reply fired, Android restarts it and
 * delivers the broadcast but the ViewModel will have no live connections.
 * In that case [sendToBuffer] silently finds no runtime and returns without
 * sending. To prevent replies being silently dropped, we check whether the
 * send succeeded and update the notification with feedback so the user knows
 * to reopen the app and reconnect before replying.
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val netId        = intent.getStringExtra(NotificationHelper.EXTRA_NETWORK_ID)    ?: return
        val buffer       = intent.getStringExtra(NotificationHelper.EXTRA_BUFFER)         ?: return
        val notifId      = intent.getIntExtra(NotificationHelper.EXTRA_NOTIF_ID, -1)
        val from         = intent.getStringExtra(NotificationHelper.EXTRA_FROM)           ?: ""
        val originalText = intent.getStringExtra(NotificationHelper.EXTRA_ORIGINAL_TEXT) ?: ""

        val bundle = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = bundle.getCharSequence(NotificationHelper.EXTRA_REPLY_TEXT)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return

        val app = ctx.applicationContext as? HexDroidApp
        val vm  = app?.ircViewModel

        // Check whether there is actually a live connection for this network before
        // attempting to send. update the notification to prompt the user to
        // reconnect rather than leaving them thinking the reply was delivered.
        val hasLiveConnection = vm?.hasLiveConnection(netId) == true

        if (hasLiveConnection) {
            vm!!.sendToBuffer(netId, buffer, replyText, from = from, originalText = originalText)
            if (notifId >= 0) NotificationManagerCompat.from(ctx).cancel(notifId)
        } else {
            // No live connection. show an error notification so the reply is not lost silently.
            if (notifId >= 0) {
                val errorNotif = androidx.core.app.NotificationCompat.Builder(
                    ctx, NotificationHelper.CH_HIGHLIGHT_SILENT
                )
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("Reply not sent: not connected")
                    .setContentText("Open HexDroid and reconnect to send your reply to $buffer")
                    .setAutoCancel(true)
                    .build()
                NotificationManagerCompat.from(ctx).notify(notifId, errorNotif)
            }
        }
    }
}