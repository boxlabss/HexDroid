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

import com.boxlabs.hexdroid.IrcViewModel.ChannelEventKind
import com.boxlabs.hexdroid.data.EventPrefs
import com.boxlabs.hexdroid.data.IgnoreType
import com.boxlabs.hexdroid.data.eventPrefs
import kotlinx.coroutines.isActive

internal fun IrcViewModel.isBufferMuted(bufferKey: String): Boolean =
    channelPrefsFor(bufferKey).isMutedAt(System.currentTimeMillis())

/** The event settings that apply in [chanKey]: its own, or the app-wide ones. */
internal fun IrcViewModel.eventPrefsFor(chanKey: String): EventPrefs =
    channelPrefsFor(chanKey).events ?: _state.value.settings.eventPrefs()

internal fun IrcViewModel.smartFilterWindowMs(): Long =
    _state.value.settings.smartFilterMinutes.coerceIn(1, 60) * 60_000L

internal fun IrcViewModel.isHiddenEvent(p: EventPrefs, kind: ChannelEventKind): Boolean = when (kind) {
    ChannelEventKind.JOIN -> p.hideJoins
    ChannelEventKind.PART -> p.hideParts
    ChannelEventKind.QUIT -> p.hideQuits
    ChannelEventKind.NICK -> p.hideNickChanges
    ChannelEventKind.TOPIC -> p.hideTopicChanges
    ChannelEventKind.KICK -> p.hideKicks
    ChannelEventKind.MODE -> p.hideModeChanges
}

/** Whether a [kind] event shows in [chanKey], ignoring the smart filter. */
internal fun IrcViewModel.showsEvent(chanKey: String, kind: ChannelEventKind): Boolean =
    !isHiddenEvent(eventPrefsFor(chanKey), kind)

/**
 * Whether a [kind] event by someone else ([nick]) shows in [chanKey]. With the smart filter
 * on there, joins, parts, quits and nick changes show only for someone who spoke within the
 * window before [atMs].
 */
internal fun IrcViewModel.showsOthersEvent(netId: String, chanKey: String, kind: ChannelEventKind, nick: String, atMs: Long?): Boolean {
    val p = eventPrefsFor(chanKey)
    if (isHiddenEvent(p, kind)) return false
    if (isIgnoredEventKind(kind) && isIgnoredSender(netId, nick, IgnoreType.EVENTS)) return false
    if (!p.smartFilter) return true
    return when (kind) {
        ChannelEventKind.JOIN, ChannelEventKind.PART, ChannelEventKind.QUIT, ChannelEventKind.NICK ->
            smartFilter.isActive(chanKey, casefoldText(netId, nick), atMs ?: System.currentTimeMillis(), smartFilterWindowMs())
        else -> true
    }
}

internal fun IrcViewModel.isIgnoredEventKind(kind: ChannelEventKind): Boolean =
    kind == ChannelEventKind.JOIN || kind == ChannelEventKind.PART ||
        kind == ChannelEventKind.QUIT || kind == ChannelEventKind.NICK

/** True when the smart filter is on in [chanKey], so a left-out join should be held. */
internal fun IrcViewModel.smartFilterOn(chanKey: String): Boolean = eventPrefsFor(chanKey).smartFilter

/**
 * Records [nick] speaking in channel [chanKey] for the smart filter, and shows the join and
 * nick changes held for them, each at its own time.
 */
internal fun IrcViewModel.noteChannelSpeaker(netId: String, chanKey: String, nick: String, timeMs: Long?) {
    val lines = smartFilter.spoke(chanKey, casefoldText(netId, nick), timeMs ?: System.currentTimeMillis(), smartFilterWindowMs())
    if (lines.isEmpty() || !smartFilterOn(chanKey) || !showsEvent(chanKey, ChannelEventKind.JOIN)) return
    for (line in lines) {
        append(line.bufferKey, from = null, text = line.text, isLocal = true, timeMs = line.timeMs, isHistory = line.isHistory, doNotify = false)
    }
}

/**
 * Whether [text] highlights on [netId], using that network's own nick and CASEMAPPING. Private
 * messages always highlight; the nick, the extra words and [bufferKey]'s own words match as
 * whole words.
 */
internal fun IrcViewModel.isHighlight(netId: String, text: String, isPrivate: Boolean, bufferKey: String? = null): Boolean {
    if (isPrivate) return true
    val s = _state.value.settings
    val bufferWords = bufferKey?.let { channelPrefsFor(it).highlightWords }.orEmpty()
    if (!s.highlightOnNick && s.extraHighlightWords.isEmpty() && bufferWords.isEmpty()) return false

    val plain = stripIrcFormatting(text)
    val foldedText = casefoldText(netId, plain)

    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    fun containsWholeWord(needleFolded: String): Boolean {
        if (needleFolded.isBlank()) return false
        var from = 0
        while (true) {
            val idx = foldedText.indexOf(needleFolded, startIndex = from)
            if (idx < 0) return false
            val beforeIdx = idx - 1
            val afterIdx = idx + needleFolded.length
            val beforeOk = beforeIdx < 0 || !isWordChar(foldedText[beforeIdx])
            val afterOk = afterIdx >= foldedText.length || !isWordChar(foldedText[afterIdx])
            if (beforeOk && afterOk) return true
            from = idx + 1
            if (from >= foldedText.length) return false
        }
    }

    if (s.highlightOnNick) {
        val nick = _state.value.connections[netId]?.myNick ?: _state.value.myNick
        if (nick.isNotBlank() && containsWholeWord(casefoldText(netId, nick))) return true
    }

    for (w in s.extraHighlightWords + bufferWords) {
        val ww = w.trim()
        if (ww.isBlank()) continue
        if (containsWholeWord(casefoldText(netId, ww))) return true
    }

    return false
}
