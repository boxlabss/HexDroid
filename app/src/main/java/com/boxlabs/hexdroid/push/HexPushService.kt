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

package com.boxlabs.hexdroid.push

import com.boxlabs.hexdroid.IrcParser
import com.boxlabs.hexdroid.NotificationHelper
import com.boxlabs.hexdroid.stripIrcFormatting
import com.boxlabs.hexdroid.vibrateForHighlight
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Receives Web Push messages from the UnifiedPush distributor.
 *
 * This runs whether or not the app has a live IRC connection, which is the whole point:
 * with the bouncer pushing to us, "keep connection alive" stops being the only way to
 * hear about a message, and the foreground service can be turned off entirely.
 *
 * The payload is exactly one IRC message per the webpush spec, already decrypted by the
 * connector. It is deliberately treated as notification content only and never fed into
 * the ViewModel's buffers: this callback can arrive with the app in any state, including
 * a fresh process with no networks connected, and a push is a summary the server chose to
 * send rather than an authoritative view of the buffer. The real messages arrive over the
 * connection and are deduplicated there by msgid as usual.
 */
class HexPushService : PushService() {

    private val parser = IrcParser()

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        // A distributor with no key set cannot receive encrypted pushes, and the spec
        // requires encryption, so an endpoint without keys is not usable.
        val keys = endpoint.pubKeySet ?: return
        WebPushManager.storeSubscription(
            ctx = applicationContext,
            endpoint = endpoint.url,
            auth = keys.auth,
            p256dh = keys.pubKey,
        )
        // Networks pick the new endpoint up on their next registration; nothing is sent
        // from here because this service has no connections of its own.
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // A payload that failed to decrypt is either from a sender without our keys or a
        // corrupted delivery. Either way the bytes are meaningless, and notifying on them
        // would surface ciphertext to the user.
        if (!message.decrypted) return

        val line = runCatching { String(message.content, Charsets.UTF_8) }
            .getOrNull()
            ?.trimEnd('\r', '\n')
            ?.takeIf { it.isNotBlank() }
            ?: return
        val msg = parser.parse(line) ?: return

        val from = msg.prefixNick() ?: return
        val target = msg.params.getOrNull(0) ?: return
        val body = msg.trailing?.takeIf { it.isNotBlank() } ?: return

        // The spec allows servers to strip tags to fit the payload but never the msgid,
        // Correlate a pushed message with the same message seen over a connection.
        // null when a server sends one anyway, in which case the message simply is not deduped.
        val anchor = msg.tags["msgid"]?.takeIf { it.isNotBlank() }?.let { "msgid:$it" }

        // Notification settings
        val policy = PushNotifyPolicy.read(applicationContext)
        if (!policy.notificationsEnabled) return

        when (msg.command.uppercase()) {
            "PRIVMSG", "NOTICE" -> notifyMessage(from, target, body, anchor, policy)
            "INVITE" -> notifyInvite(from, body)
            else -> Unit
        }
    }

    private fun notifyMessage(
        from: String,
        target: String,
        rawBody: String,
        msgAnchor: String?,
        policy: PushNotifyPolicy.Policy,
    ) {
        val text = stripIrcFormatting(rawBody)
        val helper = NotificationHelper(applicationContext)

        // A channel target means we were highlighted (the server only pushes messages of
        // interest), so the buffer to open is the channel. For a PM the target is our own
        // nick and the conversation lives under the sender's name.
        val isChannel = target.firstOrNull()?.let { it == '#' || it == '&' || it == '!' || it == '+' } == true
        val buffer = if (isChannel) target else from

        // A push carries no indication of which of the user's networks it came from, so
        // it is resolved against the live connections when this process still has them.
        val (netId, netName) = resolvePushNetwork(buffer)

        // The user is looking at this conversation, so the message is already on screen.
        if (isBufferOnScreen(buffer)) return

        // Sender on the highlight-ignore list: the message still reaches the buffer over
        // the connection, it just raises no alert.
        if (policy.mutesNick(netId, from)) return

        val posted = if (isChannel) {
            if (!policy.notifyHighlights) return
            helper.notifyHighlight(
                networkId = netId,
                buffer = buffer,
                text = "$from: $text",
                playSound = policy.playSound,
                displayTitle = buffer,
                from = from,
                originalText = text,
                msgAnchor = msgAnchor,
                networkName = netName,
            )
        } else {
            if (!policy.notifyPrivateMessages) return
            helper.notifyPm(
                networkId = netId,
                buffer = buffer,
                text = text,
                displayTitle = from,
                from = from,
                originalText = text,
                msgAnchor = msgAnchor,
                networkName = netName,
            )
        }

        // Nothing was posted when the connection had already notified for this message,
        // and buzzing for it would be the second alert the dedupe exists to prevent.
        if (posted && policy.vibrate) {
            vibrateForHighlight(applicationContext, policy.vibrateIntensity)
        }
    }

    private fun notifyInvite(from: String, channel: String) {
        // An invite names a channel we are not in, so there is no buffer to resolve
        // against and the notification is deliberately network-less. Only the master
        // notification switch applies; the highlight and PM toggles are about
        // conversations the user is already in.
        NotificationHelper(applicationContext).notifyHighlight(
            networkId = "",
            buffer = channel,
            text = getString(com.boxlabs.hexdroid.R.string.push_invite, from, channel),
            playSound = false,
            displayTitle = channel,
            from = from,
        )
    }

    /**
     * The network id and display name holding [buffer], or a pair of empty strings.
     *
     * Reads the ViewModel only if the process already has one
     */
    private fun resolvePushNetwork(buffer: String): Pair<String, String> {
        val app = applicationContext as? com.boxlabs.hexdroid.HexDroidApp ?: return "" to ""
        val vm = app.ircViewModelOrNull ?: return "" to ""
        return runCatching { vm.networkForPushTarget(buffer) }.getOrNull() ?: ("" to "")
    }

    /**
     * True when [buffer] is the conversation on screen in the foreground app.
     *
     * False whenever this process has no ViewModel
     */
    private fun isBufferOnScreen(buffer: String): Boolean {
        val app = applicationContext as? com.boxlabs.hexdroid.HexDroidApp ?: return false
        val vm = app.ircViewModelOrNull ?: return false
        return runCatching { vm.isBufferActivelyVisible(buffer) }.getOrDefault(false)
    }

    override fun onUnregistered(instance: String) {
        // The distributor has dropped us. Clearing the stored subscription stops networks
        // re-sending a dead endpoint; the servers still holding it will find their pushes
        // rejected and expire it themselves.
        WebPushManager.clearSubscription(applicationContext)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        // Nothing to retry here. Resolves on the next register()  attempt
        WebPushManager.clearSubscription(applicationContext)
    }
}
