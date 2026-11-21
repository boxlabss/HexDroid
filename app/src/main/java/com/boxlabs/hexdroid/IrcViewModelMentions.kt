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

import kotlinx.coroutines.flow.update

/** Highlights kept for the Mentions view. */
internal const val MAX_MENTIONS = 300

/** Keeps a channel highlight for the Mentions view. A [seen] one does not add to the unseen count. */
internal fun IrcViewModel.recordMention(
    bufferKey: String,
    from: String,
    text: String,
    timeMs: Long,
    msgId: String?,
    seen: Boolean,
) {
    val (netId, _) = splitKey(bufferKey)
    _state.update { st ->
        val dup = st.mentions.any {
            it.bufferKey == bufferKey && (
                (msgId != null && it.msgId == msgId) ||
                    (it.timeMs / 1000 == timeMs / 1000 && it.from == from && it.text == text)
                )
        }
        if (dup) return@update st
        val list = (st.mentions + MentionEntry(netId, bufferKey, from, text, timeMs, msgId))
            .sortedBy { it.timeMs }
            .takeLast(MAX_MENTIONS)
        val unseen = if (seen || st.screen == AppScreen.MENTIONS) st.unseenMentions else st.unseenMentions + 1
        st.copy(mentions = list, unseenMentions = unseen)
    }
}

/** Shows the Mentions view and clears its unseen count. */
fun IrcViewModel.openMentions() {
    _state.update { it.copy(screen = AppScreen.MENTIONS, unseenMentions = 0) }
}

/** Opens the conversation [entry] came from and scrolls to it, fetching it from history if needed. */
fun IrcViewModel.openMention(entry: MentionEntry) {
    if (_state.value.networks.none { it.id == entry.netId }) return
    setActiveNetwork(entry.netId)
    openBuffer(entry.bufferKey)
    val anchor = entry.msgId?.let { "msgid:$it" }
        ?: "ts:${entry.timeMs / 1000}|${entry.from}|${stripIrcFormatting(entry.text).take(80)}"
    _state.update {
        it.copy(
            pendingHighlightAnchor = anchor,
            pendingHighlightSetAtMs = System.currentTimeMillis(),
            pendingHighlightBufferKey = entry.bufferKey,
        )
    }
    val loaded = _state.value.buffers[entry.bufferKey]?.messages?.any { m ->
        (entry.msgId != null && m.msgId == entry.msgId) ||
            (kotlin.math.abs(m.timeMs - entry.timeMs) <= 3_000L && m.from.equals(entry.from, ignoreCase = true))
    } == true
    if (!loaded && supportsChatHistory(entry.bufferKey)) {
        loadMessageContext(entry.bufferKey, entry.msgId, timeMs = entry.timeMs)
    }
}

/** Removes one mention from the list. */
fun IrcViewModel.dismissMention(entry: MentionEntry) {
    _state.update { st -> st.copy(mentions = st.mentions - entry) }
}

/** Empties the Mentions view. */
fun IrcViewModel.clearMentions() {
    _state.update { it.copy(mentions = emptyList(), unseenMentions = 0) }
}
