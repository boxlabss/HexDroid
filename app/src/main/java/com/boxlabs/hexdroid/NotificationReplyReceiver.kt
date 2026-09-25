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
 * Handles the inline reply on highlight and PM notifications, without bringing the app forward. If
 * the process was restarted and has no live connection, the send fails, and the notification is
 * updated to say so rather than dropping the reply silently.
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        // Outer guard: a throw here shows the system crash dialog. Expected cases (missing extras,
        // no live connection) are handled above; this catches the unexpected.
        try {
            handleReply(ctx, intent)
        } catch (t: Throwable) {
            android.util.Log.e("NotificationReplyReceiver", "Reply broadcast handler crashed", t)
        }
    }

    private fun handleReply(ctx: Context, intent: Intent) {
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
            vm.sendToBuffer(netId, buffer, replyText, from = from, originalText = originalText)
            // Cancel by tag as well as id: message notifications are posted under a
            // per-buffer tag, and an untagged cancel matches nothing.
            if (notifId >= 0) runCatching {
                NotificationManagerCompat.from(ctx)
                    .cancel(NotificationHelper.notifTagFor(netId, buffer), notifId)
            }
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
                // Same tag as the notification it replaces, so opening the conversation
                // clears the failure alongside the message that prompted it.
                runCatching {
                    NotificationManagerCompat.from(ctx)
                        .notify(NotificationHelper.notifTagFor(netId, buffer), notifId, errorNotif)
                }
            }
        }
    }
}