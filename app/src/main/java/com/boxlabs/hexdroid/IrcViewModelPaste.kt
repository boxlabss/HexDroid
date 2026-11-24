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

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What to do with text the paste guard held. */
enum class PasteChoice { SEND_LINES, ONE_LINE, UPLOAD, CANCEL }

/** Holds [text] for the paste guard dialog instead of sending it. */
internal fun IrcViewModel.holdForPasteGuard(
    bufferKey: String,
    text: String,
    lines: Int,
    waitMs: Long,
    batched: Boolean,
    reply: PasteReply? = null,
) {
    val (netId, _) = splitKey(bufferKey)
    val canUpload = pasteUploadAvailable(netId)
    val st = _state.value
    val proxied = st.networks.firstOrNull { it.id == netId }?.proxyType != com.boxlabs.hexdroid.connection.ProxyType.NONE
    val suggest = !canUpload && !proxied && !PasteService.isUsable(st.settings.pasteServiceUrl)
    _state.update {
        it.copy(pasteGuard = PasteGuardRequest(bufferKey, text, lines, waitMs, batched, canUpload = canUpload,
            suggestPasteService = suggest, reply = reply))
    }
}

/**
 * Sends a reply typed in the message box. A "//" escape is undone, and a reply that would go out
 * as more lines than the paste guard allows waits for the user.
 */
fun IrcViewModel.sendComposerReply(
    networkId: String,
    buffer: String,
    text: String,
    from: String,
    originalText: String,
    msgId: String?,
) {
    onAutoAwayInteraction()
    val body = if (SlashInput.isEscaped(SlashInput.stripLeadingFormatting(text.trim()))) SlashInput.unescape(text.trim()) else text
    val st = _state.value
    if (st.settings.pasteGuardEnabled && !isDccChatBufferName(buffer)) {
        val flat = body.replace("\r", "").replace("\n", " ").trim()
        val chunks = runCatching { outgoingChunks(networkId, buffer, flat) }.getOrDefault(emptyList())
        if (chunks.size > st.settings.pasteGuardLines.coerceAtLeast(1)) {
            val waitMs = runtimes[networkId]?.client?.pacedSendEstimateMs(chunks.map { it.length + buffer.length + 10 }) ?: 0L
            holdForPasteGuard(resolveBufferKey(networkId, buffer), body, chunks.size, waitMs, batched = false,
                reply = PasteReply(from, originalText, msgId))
            return
        }
    }
    sendToBuffer(networkId, buffer, body, from, originalText, msgId)
}

/**
 * True when text can be uploaded as a paste for [netId]: to the paste service in Settings, or
 * through its FILEHOST service. Never on a proxied network, which the upload would bypass.
 */
fun IrcViewModel.pasteUploadAvailable(netId: String): Boolean {
    val st = _state.value
    val proxied = st.networks.firstOrNull { it.id == netId }?.proxyType != com.boxlabs.hexdroid.connection.ProxyType.NONE
    if (proxied) return false
    return filehostUsable(netId) || PasteService.isUsable(st.settings.pasteServiceUrl)
}

/** True when [netId] can upload through its FILEHOST service: offered, enabled and not proxied. */
internal fun IrcViewModel.filehostUsable(netId: String): Boolean =
    filehostUsable(_state.value, netId)

/** True when [netId] in [state] can upload through its FILEHOST service. */
internal fun filehostUsable(state: UiState, netId: String): Boolean {
    if (state.connections[netId]?.filehostUrl == null) return false
    return state.networks.firstOrNull { it.id == netId }?.proxyType == com.boxlabs.hexdroid.connection.ProxyType.NONE
}

/** Acts on the user's answer to the paste guard. */
fun IrcViewModel.resolvePasteGuard(choice: PasteChoice) {
    val req = _state.value.pasteGuard ?: return
    if (req.uploading) return
    // Sends [text] as the held message would have gone: as a reply, or as typed input.
    fun send(text: String, asTyped: Boolean) {
        val reply = req.reply
        if (reply != null) {
            val (netId, bufferName) = splitKey(req.bufferKey)
            sendToBuffer(netId, bufferName, text, reply.from, reply.originalText, reply.msgId)
        } else {
            sendInputInternal(if (asTyped) SlashInput.escapeIfCommand(text) else text, 0, req.bufferKey,
                fromUser = true, pasteConfirmed = true)
        }
    }
    when (choice) {
        PasteChoice.SEND_LINES -> {
            _state.update { it.copy(pasteGuard = null) }
            send(req.text, asTyped = true)
        }
        PasteChoice.ONE_LINE -> {
            _state.update { it.copy(pasteGuard = null) }
            val joined = req.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            send(joined, asTyped = true)
        }
        PasteChoice.CANCEL -> {
            _state.update { it.copy(pasteGuard = null) }
            restoreComposerText(req.bufferKey, req.text)
        }
        PasteChoice.UPLOAD -> {
            _state.update { it.copy(pasteGuard = req.copy(uploading = true)) }
            val (netId, _) = splitKey(req.bufferKey)
            // No title: the conversation's name is not shared with the paste site.
            uploadPaste(netId, req.text, title = "") { url, error ->
                _state.update { it.copy(pasteGuard = null) }
                if (url != null) {
                    send(url, asTyped = false)
                } else {
                    append(req.bufferKey, from = null, isLocal = true, doNotify = false,
                        text = "*** " + (error ?: appContext.getString(R.string.paste_upload_failed, "?")))
                    restoreComposerText(req.bufferKey, req.text)
                }
            }
        }
    }
}

/**
 * Uploads [text] as a paste: to the paste service in Settings, else to [netId]'s FILEHOST
 * service. [onDone] gets the link or an error on the main thread.
 */
fun IrcViewModel.uploadPaste(netId: String, text: String, title: String, onDone: (url: String?, error: String?) -> Unit) {
    if (!pasteUploadAvailable(netId)) {
        onDone(null, appContext.getString(R.string.paste_no_service))
        return
    }
    val service = _state.value.settings.pasteServiceUrl
    if (!PasteService.isUsable(service)) {
        uploadBytesToFilehost(netId, text.toByteArray(Charsets.UTF_8), "paste.txt", "text/plain; charset=utf-8", onDone)
        return
    }
    viewModelScope.launch(Dispatchers.IO) {
        val result = PasteService.upload(service, text, title)
        withContext(Dispatchers.Main) { onDone(result.url, result.error) }
    }
}

/** Puts [text] back in [bufferKey]'s message box, replacing what is there. */
internal fun IrcViewModel.restoreComposerText(bufferKey: String, text: String) {
    draftStore.put(bufferKey, text, text.length)
    if (_state.value.selectedBuffer == bufferKey) {
        _state.update { it.copy(pendingShareText = text, pendingShareTarget = bufferKey) }
    }
}
