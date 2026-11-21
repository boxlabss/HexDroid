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

import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.viewModelScope
import com.boxlabs.hexdroid.IrcViewModel.IrcUri
import com.boxlabs.hexdroid.data.AutoJoinChannel
import com.boxlabs.hexdroid.data.NetworkProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

internal fun IrcViewModel.launchExpandedNetworkIdsSync() {
    viewModelScope.launch {
        _collapsedNetworkIds.collect { ids ->
            _state.update { it.copy(collapsedNetworkIds = ids) }
        }
    }
}

/**
 * Parse irc://, ircs:// and irc+ssl:// URIs into an [IrcUri]:
 *   irc://host/channel            plain, port 6667
 *   irc://host:+6697/channel      TLS via +port
 *   ircs:// or irc+ssl://host/... TLS via scheme
 *   irc://host/#channel           # recovered from the fragment
 *   irc://host/%23channel         percent-encoded
 *   irc://host/chan?key=secret    channel key
 */
internal fun IrcViewModel.parseIrcUri(raw: String): IrcUri? {
    // Detect the +port TLS flag before Uri.parse() silently drops the '+'.
    val plusPortTls = Regex("""://[^/]*:\+\d+""").containsMatchIn(raw)
    // Normalise +port → plain port so android.net.Uri can parse correctly.
    val normalised = raw.replace(Regex("""(://[^/]*):(\+)(\d+)"""), "$1:$3")

    val uri = Uri.parse(normalised) ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "irc" && scheme != "ircs" && scheme != "irc+ssl") return null

    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val useTls = scheme == "ircs" || scheme == "irc+ssl" || plusPortTls
    val port = uri.port.takeIf { it in 1..65535 } ?: if (useTls) 6697 else 6667

    // Channel in path segments (irc://host/channel or irc://host/%23channel).
    // If '#' was unencoded, android.net.Uri puts it in the fragment instead.
    val rawChannel = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: uri.fragment?.takeIf { it.isNotBlank() }

    val channels = rawChannel
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.map { ch -> if (ch[0] in "#&+!") ch else "#$ch" }
        ?: emptyList()

    val channelKey = uri.getQueryParameter("key") ?: uri.getQueryParameter("pass")
    val serverPassword = uri.userInfo?.split(":", limit = 2)?.getOrNull(1)
        ?.takeIf { it.isNotEmpty() }

    return IrcUri(host, port, useTls, channels, channelKey, serverPassword)
}

/**
 * Open the network matching an IRC URI: an exact host, port and TLS match, else a host match,
 * else a new pre-filled profile opened in the editor with the URI's channels in its auto-join.
 * A matched network connects if needed, joins the URI's channels and opens the first.
 */
internal fun IrcViewModel.handleIrcUri(ircUri: IrcUri) {
    viewModelScope.launch {
        val st = _state.value

        // Inherit the nick from an existing network, or fall back to app default.
        val defaultNick = st.networks.firstOrNull()?.nick
            ?: st.myNick.takeIf { it != "me" }
            ?: "HexDroidUser"

        val existing = st.networks.firstOrNull { n ->
            n.host.equals(ircUri.host, ignoreCase = true) &&
            n.port == ircUri.port &&
            n.useTls == ircUri.useTls
        } ?: st.networks.firstOrNull { n ->
            n.host.equals(ircUri.host, ignoreCase = true)
        }

        val newAutoJoin = ircUri.channels.map { ch ->
            AutoJoinChannel(ch, ircUri.channelKey)
        }

        if (existing != null) {
            val netId = existing.id
            setActiveNetwork(netId)
            val rt = runtimes[netId]
            if (rt != null && rt.client.registered && hasLiveConnection(netId)) {
                for (c in newAutoJoin) {
                    rt.pendingUserJoinSwitch.add(casefoldText(netId, c.channel))
                    rt.client.sendRaw(if (c.key.isNullOrBlank()) "JOIN ${c.channel}" else "JOIN ${c.channel} ${c.key}")
                }
            } else {
                if (newAutoJoin.isNotEmpty()) {
                    pendingUriJoins.merge(netId, newAutoJoin) { old, add ->
                        (old + add).distinctBy { casefoldText(netId, it.channel) }
                    }
                }
                connectNetwork(netId)
            }
            if (ircUri.channels.isNotEmpty()) {
                openBuffer(resolveBufferKey(netId, ircUri.channels.first()))
            } else {
                backToChat()
            }
        } else {
            // New server - pre-fill from URI and open the edit screen for review.
            val n = NetworkProfile(
                id = "net_" + java.util.UUID.randomUUID().toString().replace("-", ""),
                name = ircUri.host,
                host = ircUri.host,
                port = ircUri.port,
                useTls = ircUri.useTls,
                allowInvalidCerts = false,
                nick = defaultNick,
                altNick = "${defaultNick}_",
                username = defaultNick.lowercase(),
                realname = "HexDroid IRC",
                serverPassword = ircUri.serverPassword,
                saslEnabled = false,
                saslMechanism = SaslMechanism.PLAIN,
                caps = CapPrefs(),
                autoJoin = newAutoJoin,
            )
            _state.value = _state.value.copy(
                screen = AppScreen.NETWORK_EDIT,
                editingNetwork = n,
                networkEditError = null,
            )
        }
    }
}

fun IrcViewModel.handleIntent(intent: Intent?) {
    if (intent == null) return

    // Text or files shared from another app, directly to a conversation or through the chooser.
    if (handleShareIntent(intent)) return

    // Handle irc:// and ircs:// deep links before any notification extras.
    if (intent.action == Intent.ACTION_VIEW) {
        val uriString = intent.dataString
        if (!uriString.isNullOrBlank()) {
            val scheme = Uri.parse(uriString)?.scheme?.lowercase()
            if (scheme == "irc" || scheme == "ircs" || scheme == "irc+ssl") {
                parseIrcUri(uriString)?.let { handleIrcUri(it) }
                return
            }
        }
    }

    val netId = intent.getStringExtra(NotificationHelper.EXTRA_NETWORK_ID)
    val buf = intent.getStringExtra(NotificationHelper.EXTRA_BUFFER)
    val action = intent.getStringExtra(NotificationHelper.EXTRA_ACTION)
    val highlightMsgId = intent.getLongExtra(NotificationHelper.EXTRA_MSG_ID, -1L)
        .takeIf { it >= 0L }
    val highlightAnchor = intent.getStringExtra(NotificationHelper.EXTRA_MSG_ANCHOR)

    if (action == NotificationHelper.ACTION_OPEN_TRANSFERS) {
        if (!netId.isNullOrBlank()) setActiveNetwork(netId)
        _state.value = _state.value.copy(screen = AppScreen.TRANSFERS)
        return
    }

    if (action == NotificationHelper.ACTION_ACCEPT_DCC && NotificationHelper.hasActionToken(intent)) {
        val from     = intent.getStringExtra(NotificationHelper.EXTRA_DCC_FROM)     ?: ""
        val filename = intent.getStringExtra(NotificationHelper.EXTRA_DCC_FILENAME) ?: ""
        val notifId  = intent.getIntExtra(NotificationHelper.EXTRA_NOTIF_ID, -1)
        if (!netId.isNullOrBlank()) setActiveNetwork(netId)
        _state.value = _state.value.copy(screen = AppScreen.TRANSFERS)
        // Dismiss the incoming-file notification immediately so the user
        // doesn't see a stale "incoming file" banner after accepting.
        if (notifId >= 0) NotificationManagerCompat.from(appContext).cancel(notifId)
        // Find the matching pending offer and accept it automatically.
        val offer = _state.value.dccOffers.firstOrNull { o ->
            (netId.isNullOrBlank() || o.netId == netId) &&
            o.from.equals(from, ignoreCase = true) &&
            o.filename == filename
        }
        if (offer != null) acceptDcc(offer)
        return
    }

    if (netId.isNullOrBlank() && buf.isNullOrBlank()) return

    if (!netId.isNullOrBlank()) setActiveNetwork(netId)

    val key = if (!netId.isNullOrBlank() && !buf.isNullOrBlank()) resolveBufferKey(netId, buf) else null
    if (key != null) openBuffer(key) else _state.value = _state.value.copy(screen = AppScreen.CHAT)
    if (highlightAnchor != null) {
        _state.value = _state.value.copy(
            pendingHighlightAnchor = highlightAnchor,
            pendingHighlightSetAtMs = System.currentTimeMillis(),
            pendingHighlightBufferKey = key,
        )
    } else if (highlightMsgId != null) {
        // old notifications stored a Long id, keep compat for stale notifs
        _state.value = _state.value.copy(
            pendingHighlightAnchor = "uiid:$highlightMsgId",
            pendingHighlightSetAtMs = System.currentTimeMillis(),
            pendingHighlightBufferKey = key,
        )
    }
}

/** The buffer key for [bufferName] on [netId], matching an existing buffer under CASEMAPPING. */
fun IrcViewModel.bufferKeyFor(netId: String, bufferName: String): String = resolveBufferKey(netId, bufferName)

/** Called as a bubble showing [key] comes into view ([showing]) or leaves it. */
fun IrcViewModel.setBubbleShowing(key: String, showing: Boolean) {
    if (showing) {
        bubblesShowing.add(key)
        markBufferSeen(key)
        onAutoAwayInteraction()
    } else {
        bubblesShowing.remove(key)
    }
}

/**
 * Clears [key]'s unread and highlight counts without selecting it, for a conversation being
 * read outside the main window (a bubble).
 */
fun IrcViewModel.markBufferSeen(key: String) {
    _state.update { st ->
        val buf = st.buffers[key] ?: return@update st
        if (buf.unread == 0 && buf.highlights == 0) st
        else st.copy(buffers = st.buffers + (key to buf.copy(unread = 0, highlights = 0)))
    }
}

internal fun IrcViewModel.openBuffer(key: String, switchToChat: Boolean) {
    ensureBuffer(key)
    val (netId, bufName) = splitKey(key)

    // Opening the conversation is the user reading it, so anything pending in the
    // shade for it has served its purpose.
    notifier.cancelBuffer(netId, bufName)

    val actualConnected = runtimes[netId]?.client?.isConnectedNow() == true
    val conn0 = _state.value.connections[netId]
    if (conn0?.connected == true && !actualConnected) {
        setNetConn(netId) { it.copy(connected = false, connecting = false, status = appContext.getString(R.string.vm_status_disconnected)) }
    } else if (conn0?.connected != true && actualConnected) {
        setNetConn(netId) { it.copy(connected = true, connecting = false, status = appContext.getString(R.string.vm_status_connected)) }
    }
    if (_state.value.activeNetworkId != netId) setActiveNetwork(netId)

    // Collect MARKREAD params here so we can fire the coroutine after _state.update returns.
    // Launching inside update {} is wrong: the CAS loop can retry, sending MARKREAD multiple times.
    var markReadNet: String? = null
    var markReadName: String? = null
    var markReadTs: String? = null

    // One atomic update: stamp leaving buffer, anchor separator, switch buffer, clear badge.
    _state.update { st ->
        // Stamp the leaving buffer so new messages appear after the separator on return.
        val leaving = st.selectedBuffer
        var buffers = st.buffers
        if (leaving.isNotBlank() && leaving != key) {
            val leavingBuf = buffers[leaving]
            if (leavingBuf != null) {
                val lastMsg = leavingBuf.messages.lastOrNull()
                if (lastMsg != null) {
                    val ts = java.time.Instant.ofEpochMilli(lastMsg.timeMs + 1L).toString()
                    buffers = buffers + (leaving to leavingBuf.copy(lastReadTimestamp = ts))

                    val (leavingNet, leavingName) = splitKey(leaving)
                    val rt = runtimes[leavingNet]
                    if (rt != null && rt.client.hasCap("draft/read-marker")) {
                        markReadNet = leavingNet
                        markReadName = leavingName
                        markReadTs = ts
                    }
                }
            }
        }

        // Anchor a read marker if there isn't one yet, so the separator shows in the right place.
        val openingBuf = buffers[key]
        if (openingBuf != null && openingBuf.lastReadTimestamp == null && openingBuf.unread > 0) {
            val msgs = openingBuf.messages
            val firstUnreadPos = msgs.size - openingBuf.unread
            val anchorMs = if (firstUnreadPos > 0) msgs[firstUnreadPos - 1].timeMs + 1L else 0L
            val anchorTs = java.time.Instant.ofEpochMilli(anchorMs).toString()
            buffers = buffers + (key to openingBuf.copy(lastReadTimestamp = anchorTs))
        }

        val afterOpen = buffers[key]
        if (afterOpen != null && (afterOpen.unread > 0 || afterOpen.highlights > 0)) {
            buffers = buffers + (key to afterOpen.copy(unread = 0, highlights = 0))
        }

        st.copy(
            buffers = buffers,
            selectedBuffer = key,
            screen = if (switchToChat) AppScreen.CHAT else st.screen,
        )
    }

    // Send MARKREAD once, after the state update has settled.
    val mrNet = markReadNet; val mrName = markReadName; val mrTs = markReadTs
    // MARKREAD targets must be a real channel or query; the server buffer ("*server*")
    // is not a valid target and draws FAIL MARKREAD INVALID_PARAMS.
    if (mrNet != null && mrName != null && !isPseudoBuffer(mrName) && !isDccChatBufferName(mrName) && mrTs != null) {
        val rt = runtimes[mrNet]
        if (rt != null) {
            viewModelScope.launch { runCatching { rt.client.sendBackground("MARKREAD $mrName timestamp=$mrTs") } }
        }
    }
    // The read marker for the opening buffer is stamped when the user reaches the bottom, not here.
    resumeHistoryGap(key)
}

/**
 * Set [UiBuffer.lastReadTimestamp] to the timestamp of the last message currently in [key],
 * then send MARKREAD to the server if the cap is available.
 * No-op if the buffer has no messages.
 */
internal fun IrcViewModel.stampReadMarker(key: String) {
    val st = _state.value
    val buf = st.buffers[key] ?: return
    // The spec requires the timestamp to "correspond to a previous message time tag", so
    // it is a message's own time, not that time nudged forward. Locally generated system
    // lines have no server timestamp to quote, so the newest real message is used.
    val lastMsg = buf.messages.lastOrNull { it.from != null } ?: buf.messages.lastOrNull() ?: return
    val ts = markReadStamp(lastMsg.timeMs)

    // "The last read timestamp of a target MUST only ever increase." Sending a value at
    // or below what the server already holds makes it answer with its own stored value,
    // which is a wasted exchange that also drags the local marker backwards.
    val known = buf.lastReadTimestamp?.let { parseMarkReadMs(it) }
    if (known != null && lastMsg.timeMs <= known) return

    _state.value = st.copy(buffers = st.buffers + (key to buf.copy(lastReadTimestamp = ts)))
    val (netId, bufferName) = splitKey(key)
    val rt = runtimes[netId] ?: return
    if (rt.client.hasCap("draft/read-marker") && !isPseudoBuffer(bufferName) && !isDccChatBufferName(bufferName)) {
        viewModelScope.launch { rt.client.sendBackground("MARKREAD $bufferName timestamp=$ts") }
    }
}

/**
 * The server echoed a message we sent to [bufferKey]. The echo is dropped as a duplicate of
 * our local copy, but it is the only one carrying the msgid, so give that id to the newest
 * unidentified line of ours with the same content. With no such line the id is still
 * recorded, so a second echo of the same message is dropped.
 */
internal fun IrcViewModel.attachEchoMsgId(bufferKey: String, text: String, isAction: Boolean, msgId: String?, myNick: String) {
    val mid = msgId?.takeIf { it.isNotBlank() } ?: return
    _state.update { s ->
        // Only a buffer that already exists: creating one here would open an empty
        // conversation for anything sent as a PRIVMSG under the hood, /ctcp included.
        val buf = s.buffers[bufferKey] ?: return@update s
        val idx = buf.messages.indexOfLast {
            it.msgId == null && it.isAction == isAction && echoTextMatches(it.text, text) &&
                it.from != null && it.from.equals(myNick, ignoreCase = true)
        }
        if (idx >= 0) {
            val newLog = buf.log.replaceAt(idx, buf.messages[idx].copy(msgId = mid))
            return@update s.copy(buffers = s.buffers + (bufferKey to buf.copy(log = newLog)))
        }
        val seen = buf.log.seenIds.adding(mid)
        if (seen === buf.log.seenIds) return@update s
        s.copy(buffers = s.buffers + (bufferKey to buf.copy(log = buf.log.copy(seenIds = seen))))
    }
}

/** Schedules a read receipt for query [key], replacing one already pending for it. */
internal fun IrcViewModel.sendReadReceipt(key: String) {
    if (!_state.value.settings.readReceiptsEnabled) return
    val (netId, name) = splitKey(key)
    if (name == "*server*" || isPseudoBuffer(name) || isDccChatBufferName(name)) return
    if (isChannelOnNet(netId, name)) return
    readReceiptJobs.remove(key)?.cancel()
    readReceiptJobs[key] = viewModelScope.launch {
        delay(READ_RECEIPT_DELAY_MS)
        readReceiptJobs.remove(key)
        sendReadReceiptNow(key)
    }
}

/**
 * Tells the other person in query [key] we've read up to their newest message, if it is on
 * screen now: this buffer, scrolled to its newest message, with the app in front. At most
 * once per message.
 */
internal fun IrcViewModel.sendReadReceiptNow(key: String) {
    val st = _state.value
    if (!st.settings.readReceiptsEnabled) return
    if (viewingLatestKey != key || st.selectedBuffer != key) return
    if (st.screen != AppScreen.CHAT || !AppVisibility.isForeground) return
    val (netId, name) = splitKey(key)
    val rt = runtimes[netId] ?: return
    val me = st.connections[netId]?.myNick ?: rt.myNick
    val newest = st.buffers[key]?.messages?.lastOrNull {
        it.from != null && !it.from.equals(me, ignoreCase = true) && !it.msgId.isNullOrBlank()
    } ?: return
    val id = newest.msgId ?: return
    if (readReceiptSentFor[key] == id) return
    readReceiptSentFor[key] = id
    viewModelScope.launch { runCatching { rt.client.sendReadReceipt(name, id) } }
}

/**
 * Ask the server for a query's read marker once per connection. draft/read-marker pushes
 * markers for channels on join, but a client has to request them for user targets.
 */
internal fun IrcViewModel.requestQueryReadMarker(netId: String, name: String) {
    val rt = runtimes[netId] ?: return
    if (!rt.client.hasCap("draft/read-marker")) return
    if (name == "*server*" || isPseudoBuffer(name) || isDccChatBufferName(name)) return
    if (isChannelOnNet(netId, name)) return
    if (!rt.queryReadMarkersRequested.add(casefoldText(netId, name))) return
    viewModelScope.launch { runCatching { rt.client.sendBackground("MARKREAD $name") } }
}

/**
 * Format [timeMs] the way the read-marker spec asks for: the server-time format, to
 * millisecond precision, in UTC.
 */
internal fun IrcViewModel.markReadStamp(timeMs: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(java.time.ZoneOffset.UTC)
        .format(Instant.ofEpochMilli(timeMs))

/** Parse a read-marker timestamp back to epoch millis, or null if it is "*" or malformed. */
internal fun IrcViewModel.parseMarkReadMs(ts: String): Long? =
    runCatching { Instant.parse(ts).toEpochMilli() }.getOrNull()

/**
 * Returns true when there is a live, registered IRC connection for [networkId].
 * Used by [NotificationReplyReceiver] to detect the dead-process restart case where
 * the ViewModel exists but has no active runtime, and suppress silent reply drops.
 */
fun IrcViewModel.hasLiveConnection(networkId: String): Boolean =
    runtimes[networkId]?.client?.isConnectedNow() == true

/** True when [buffer] (DCCCHAT:nick) on [networkId] has an open DCC chat. */
fun IrcViewModel.hasDccChatSession(networkId: String, buffer: String): Boolean =
    dccChatSessions.containsKey(findDccChatKey(networkId, buffer.removePrefix("DCCCHAT:")))

/**
 * Copy the notification settings that gate an alert to where the push service can
 * read them without a coroutine or a ViewModel.
 */
internal fun IrcViewModel.syncPushNotifyPolicy() {
    val st = _state.value
    runCatching {
        com.boxlabs.hexdroid.push.PushNotifyPolicy.store(appContext, st.settings, st.networks, st.channelPrefs)
    }
}
