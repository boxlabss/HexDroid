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
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Takes text or files shared from another app. A share made to a conversation shortcut goes
 * straight there; any other share opens the chooser. Returns false when [intent] is not a share.
 */
internal fun IrcViewModel.handleShareIntent(intent: Intent): Boolean {
    val action = intent.action
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return false
    // Some apps share a CharSequence, which getStringExtra would read as null.
    val text = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) }.getOrNull()
        ?.toString()?.trim()?.takeIf { it.isNotBlank() }
    val uris: List<Uri> = runCatching {
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
    }.getOrDefault(emptyList())
        .filter { it.scheme.equals("content", ignoreCase = true) }
        .take(MAX_SHARED_FILES)
    if (text == null && uris.isEmpty()) return true

    val share = PendingShare(text = text, uris = uris.map { it.toString() }, mimeType = intent.type)
    val shortcutId = runCatching { intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID) }.getOrNull()
    if (shortcutId == null || _state.value.networks.isNotEmpty()) {
        routeShare(share, shortcutId)
    } else {
        // Started by the share: the saved networks are still loading.
        viewModelScope.launch {
            withTimeoutOrNull(SHARE_NETWORKS_WAIT_MS) { _state.first { it.networks.isNotEmpty() } }
            routeShare(share, shortcutId)
        }
    }
    return true
}

/** Longest wait for saved networks before a direct share falls back to the chooser. */
private const val SHARE_NETWORKS_WAIT_MS = 5_000L

/** Sends [share] to the conversation [shortcutId] stands for, or opens the chooser. */
private fun IrcViewModel.routeShare(share: PendingShare, shortcutId: String?) {
    val target = shortcutId?.let { bufferForShortcutId(it) }
    if (target != null) {
        deliverShare(target, share)
    } else {
        if (_state.value.screen != AppScreen.CHAT) backToChat()
        _state.update { it.copy(pendingShare = share) }
    }
}

/** Most files taken from one share. */
private const val MAX_SHARED_FILES = 10

/** The buffer a conversation shortcut id stands for, or null when no network matches. */
internal fun IrcViewModel.bufferForShortcutId(id: String): String? {
    val st = _state.value
    st.buffers.keys.firstOrNull { k ->
        val (n, b) = splitKey(k)
        NotificationHelper.conversationShortcutId(n, b) == id
    }?.let { return it }
    // Not open: the id is "conv:" and the buffer key, lowercased.
    val rest = id.removePrefix("conv:")
    for (net in st.networks) {
        val prefix = net.id.lowercase(Locale.ROOT) + "::"
        if (rest.startsWith(prefix)) return resolveBufferKey(net.id, rest.removePrefix(prefix))
    }
    return null
}

/** Drops a share the user dismissed from the chooser. */
fun IrcViewModel.cancelShare() {
    _state.update { it.copy(pendingShare = null) }
}

/**
 * Opens [targetKey] and puts the share in its message box. Files are uploaded to the network's
 * FILEHOST service first and their links added; without a usable one they are not sent.
 */
fun IrcViewModel.deliverShare(targetKey: String, share: PendingShare) {
    _state.update { it.copy(pendingShare = null) }
    openBuffer(targetKey)
    val (netId, _) = splitKey(targetKey)

    // Added after what the message box already holds, which is saved as its draft.
    fun fill(extra: List<String>) {
        val add = (listOfNotNull(share.text) + extra).joinToString(" ").trim()
        if (add.isEmpty()) return
        val existing = draftStore.get(targetKey).text
        val merged = if (existing.isBlank()) add else existing.trimEnd() + " " + add
        draftStore.put(targetKey, merged, merged.length)
        if (_state.value.selectedBuffer == targetKey) {
            _state.update { it.copy(pendingShareText = merged, pendingShareTarget = targetKey) }
        }
    }

    if (share.uris.isEmpty()) {
        fill(emptyList())
        return
    }
    if (filehostUsable(netId) || _state.value.connections[netId]?.connecting != true) {
        uploadShared(targetKey, share, ::fill)
        return
    }
    // The network is still connecting: its upload service is known once it has registered.
    viewModelScope.launch {
        withTimeoutOrNull(SHARE_CONNECT_WAIT_MS) {
            _state.first { filehostUsable(it, netId) || it.connections[netId]?.connecting != true }
        }
        if (!filehostUsable(netId) && _state.value.connections[netId]?.connected == true) {
            withTimeoutOrNull(SHARE_ISUPPORT_WAIT_MS) { _state.first { filehostUsable(it, netId) } }
        }
        uploadShared(targetKey, share, ::fill)
    }
}

/** Longest wait for a connecting network before shared files are given up on. */
private const val SHARE_CONNECT_WAIT_MS = 30_000L

/** Longest wait after connecting for the server to name its upload service. */
private const val SHARE_ISUPPORT_WAIT_MS = 5_000L

/** Uploads [share]'s files to [targetKey]'s network and hands their links to [fill]. */
private fun IrcViewModel.uploadShared(targetKey: String, share: PendingShare, fill: (List<String>) -> Unit) {
    val (netId, _) = splitKey(targetKey)
    if (!filehostUsable(netId)) {
        append(targetKey, from = null, isLocal = true, doNotify = false,
            text = "*** " + appContext.getString(R.string.share_no_upload))
        fill(emptyList())
        return
    }
    append(targetKey, from = null, isLocal = true, doNotify = false,
        text = "*** " + appContext.resources.getQuantityString(R.plurals.share_uploading, share.uris.size, share.uris.size))
    val links = ArrayList<String>()
    fun next(i: Int) {
        if (i >= share.uris.size) {
            fill(links)
            return
        }
        uploadFileToFilehost(netId, Uri.parse(share.uris[i])) { url, error ->
            if (url != null) links += url
            else append(targetKey, from = null, isLocal = true, doNotify = false,
                text = "*** " + (error ?: appContext.getString(R.string.paste_upload_failed, "?")))
            next(i + 1)
        }
    }
    next(0)
}

/** Marks [buffer] read from its notification: here, on the server, and in the shade. */
fun IrcViewModel.markReadFromNotification(netId: String, buffer: String) {
    val key = resolveBufferKey(netId, buffer)
    stampReadMarker(key)
    markBufferSeen(key)
    val (_, name) = splitKey(key)
    notifier.cancelBuffer(netId, name)
    if (name != buffer) notifier.cancelBuffer(netId, buffer)
}

/** Silences [bufferKey]'s notifications until [untilMs] and clears what is showing. */
fun IrcViewModel.muteBufferUntil(bufferKey: String, untilMs: Long) {
    storeChannelPrefs(bufferKey, channelPrefsFor(bufferKey).copy(mutedUntilMs = untilMs))
    syncPushNotifyPolicy()
    val (netId, name) = splitKey(bufferKey)
    notifier.cancelBuffer(netId, name)
}
