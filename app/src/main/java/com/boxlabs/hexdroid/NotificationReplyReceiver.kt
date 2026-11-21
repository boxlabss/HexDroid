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
import kotlinx.coroutines.launch

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
            when (intent.action) {
                NotificationHelper.ACTION_MARK_READ -> handleMarkRead(ctx, intent)
                NotificationHelper.ACTION_MUTE_FOR -> handleMute(ctx, intent)
                else -> handleReply(ctx, intent)
            }
        } catch (t: Throwable) {
            android.util.Log.e("NotificationReplyReceiver", "Notification action handler crashed", t)
        }
    }

    /** Marks the conversation read, here and on the server, and clears its notification. */
    private fun handleMarkRead(ctx: Context, intent: Intent) {
        val netId = intent.getStringExtra(NotificationHelper.EXTRA_NETWORK_ID) ?: return
        val buffer = intent.getStringExtra(NotificationHelper.EXTRA_BUFFER) ?: return
        val vm = (ctx.applicationContext as? HexDroidApp)?.ircViewModelOrNull
        if (vm != null) vm.markReadFromNotification(netId, buffer)
        else NotificationHelper(ctx).cancelBuffer(netId, buffer)
    }

    /** Silences the conversation for [NotificationHelper.MUTE_FOR_MS] and clears its notification. */
    private fun handleMute(ctx: Context, intent: Intent) {
        val netId = intent.getStringExtra(NotificationHelper.EXTRA_NETWORK_ID) ?: return
        val buffer = intent.getStringExtra(NotificationHelper.EXTRA_BUFFER) ?: return
        val until = System.currentTimeMillis() + NotificationHelper.MUTE_FOR_MS
        NotificationHelper(ctx).cancelBuffer(netId, buffer)
        val app = ctx.applicationContext as? HexDroidApp ?: return
        val vm = app.ircViewModelOrNull
        if (vm != null) {
            vm.muteBufferUntil(vm.bufferKeyFor(netId, buffer), until)
            return
        }
        // No ViewModel in this process: write the setting directly so it holds when one starts.
        val key = "$netId::$buffer"
        com.boxlabs.hexdroid.push.PushNotifyPolicy.setMutedUntil(ctx, key, until)
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                runCatching { app.repo.updateChannelPrefs(key) { it.copy(mutedUntilMs = until) } }
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleReply(ctx: Context, intent: Intent) {
        val netId        = intent.getStringExtra(NotificationHelper.EXTRA_NETWORK_ID)    ?: return
        val buffer       = intent.getStringExtra(NotificationHelper.EXTRA_BUFFER)         ?: return
        val notifId      = intent.getIntExtra(NotificationHelper.EXTRA_NOTIF_ID, -1)
        val from         = intent.getStringExtra(NotificationHelper.EXTRA_FROM)           ?: ""
        val originalText = intent.getStringExtra(NotificationHelper.EXTRA_ORIGINAL_TEXT) ?: ""
        val replyMsgId   = intent.getStringExtra(NotificationHelper.EXTRA_REPLY_MSGID)

        val bundle = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = bundle.getCharSequence(NotificationHelper.EXTRA_REPLY_TEXT)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return

        val app = ctx.applicationContext as? HexDroidApp
        val vm  = app?.ircViewModel

        // Check whether there is actually a live connection for this network before
        // attempting to send. update the notification to prompt the user to
        // reconnect rather than leaving them thinking the reply was delivered.
        val hasLiveConnection = if (buffer.startsWith("DCCCHAT:")) {
            vm?.hasDccChatSession(netId, buffer) == true
        } else {
            vm?.hasLiveConnection(netId) == true
        }

        if (hasLiveConnection && vm != null) {
            vm.sendToBuffer(netId, buffer, replyText, from = from, originalText = originalText, msgId = replyMsgId)
            // Cancel by tag as well as id: message notifications are posted under a
            // per-buffer tag, and an untagged cancel matches nothing.
            if (notifId >= 0) runCatching {
                NotificationManagerCompat.from(ctx)
                    .cancel(NotificationHelper.notifTagFor(netId, buffer), notifId)
            }
        } else {
            // Not connected: keep the text in the conversation's message box and say so.
            runCatching { vm?.keepFailedReply(netId, buffer, replyText) }
            runCatching { NotificationHelper(ctx).notifyReplyNotSent(netId, buffer) }
        }
    }
}