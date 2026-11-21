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
import com.boxlabs.hexdroid.IrcViewModel.Companion.DEFERRED_REPLAY_TIMEOUT_MS
import com.boxlabs.hexdroid.IrcViewModel.Companion.LOG_REORDER_WINDOW_MS
import com.boxlabs.hexdroid.IrcViewModel.Companion.MAX_DEFERRED_REPLAYS
import com.boxlabs.hexdroid.IrcViewModel.Companion.MAX_PENDING_OWN_ECHOES
import com.boxlabs.hexdroid.IrcViewModel.Companion.REPLAY_SUMMARY_OPEN_MS
import com.boxlabs.hexdroid.IrcViewModel.Companion.REPLAY_SUMMARY_QUIET_MS
import com.boxlabs.hexdroid.IrcViewModel.Companion.SELF_SEND_RETAIN_MS
import com.boxlabs.hexdroid.IrcViewModel.Companion.STALE_LIVE_LINE_MS
import com.boxlabs.hexdroid.IrcViewModel.Companion.STATUS_LINE_BANNERS
import com.boxlabs.hexdroid.IrcViewModel.HeldOwnLine
import com.boxlabs.hexdroid.IrcViewModel.OwnSendAnswer
import com.boxlabs.hexdroid.IrcViewModel.PendingOwnEcho
import com.boxlabs.hexdroid.IrcViewModel.ReplayTally
import com.boxlabs.hexdroid.IrcViewModel.ScrollbackLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

internal fun IrcViewModel.formatLogLine(timeMs: Long, from: String?, text: String, isAction: Boolean): String {
    val ts = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).format(logTimeFormatter)
    // One log line per message. A multiline message otherwise spills across several
    // physical lines, and everything after the first is read back as a fragment with no
    // timestamp and no sender, which can never be matched against the server's copy.
    val t = stripIrcFormatting(text).replace("\r\n", "\n").replace('\r', '\n').replace("\n", " ")
    val body = when {
        from == null -> t
        // *nick* text - asterisk-wrapped nick is unambiguous: server-status lines always
        // use "* word …" (asterisk-space) and can never produce this pattern.
        // Old logs used "* nick text"; the parser below handles both for backward compat.
        isAction -> "*$from* $t"
        else -> "<$from> $t"
    }
    return "$ts\t$body"
}

internal fun IrcViewModel.selfSendSig(text: String, isAction: Boolean): String = "${if (isAction) "A" else "M"}\u0000$text"

internal fun IrcViewModel.recordSelfSend(bufferKey: String, text: String, isAction: Boolean) {
    val now = System.currentTimeMillis()
    val dq = recentSelfSends.getOrPut(bufferKey) { ArrayDeque(16) }
    synchronized(dq) {
        dq.addLast(now to selfSendSig(text, isAction))
        // Prune by age and cap. The cap is generous (one entry per message we've
        // sent in the retention window); a human can't realistically send 64
        // messages to one buffer inside the window and then reconnect, but the
        // cap guarantees boundedness regardless.
        while (dq.isNotEmpty() && now - dq.first().first > SELF_SEND_RETAIN_MS) dq.removeFirst()
        while (dq.size > 64) dq.removeFirst()
    }
}

/**
 * Returns true (and consumes the matching entry) if [text]/[isAction] matches a
 * message we locally echoed to [bufferKey] within the retention window. Used to
 * recognise the bouncer replaying our own message back to us after a reconnect.
 */
internal fun IrcViewModel.consumeSelfSendIfMatch(bufferKey: String, text: String, isAction: Boolean): Boolean {
    val dq = recentSelfSends[bufferKey] ?: return false
    val now = System.currentTimeMillis()
    val sig = selfSendSig(text, isAction)
    synchronized(dq) {
        while (dq.isNotEmpty() && now - dq.first().first > SELF_SEND_RETAIN_MS) dq.removeFirst()
        // Last match wins, so the most recent copy of a repeated message is reconciled first.
        val idx = dq.indexOfLast { it.second == sig }
        if (idx < 0) return false
        dq.removeAt(idx)
        return true
    }
}

/** Start counting the replay for a connection that has just registered. */
internal fun IrcViewModel.openReplayTally(netId: String) {
    val tally = ReplayTally(previousDisconnect.remove(netId))
    replayTallies.put(netId, tally)?.job?.cancel()
    viewModelScope.launch {
        delay(REPLAY_SUMMARY_OPEN_MS)
        if (synchronized(tally) { tally.lines == 0 }) replayTallies.remove(netId, tally)
    }
}

/** Count one replayed line, posting the summary once the replay goes quiet. */
internal fun IrcViewModel.noteReplayedLine(netId: String, bufferKey: String) {
    val tally = replayTallies[netId] ?: return
    synchronized(tally) {
        tally.lines++
        tally.buffers.add(bufferKey)
        tally.lastAtMs = System.currentTimeMillis()
        if (tally.job == null) tally.job = viewModelScope.launch { postReplaySummary(netId, tally) }
    }
}

/** Wait for the replay to go quiet, then post how much it brought in. */
internal suspend fun IrcViewModel.postReplaySummary(netId: String, tally: ReplayTally) {
    while (true) {
        val wait = synchronized(tally) { tally.lastAtMs + REPLAY_SUMMARY_QUIET_MS } - System.currentTimeMillis()
        if (wait <= 0) break
        delay(wait)
    }
    if (!replayTallies.remove(netId, tally)) return
    val (lines, buffers) = synchronized(tally) { tally.lines to tally.buffers.size }
    val res = appContext.resources
    val serverKey = bufKey(netId, "*server*")
    append(serverKey, from = null, doNotify = false, text = "*** " + appContext.getString(
        R.string.vm_replay_summary,
        res.getQuantityString(R.plurals.vm_replay_messages, lines, lines),
        res.getQuantityString(R.plurals.vm_replay_conversations, buffers, buffers),
    ))
    tally.previousDisconnect?.let {
        append(serverKey, from = null, doNotify = false,
            text = "*** " + appContext.getString(R.string.vm_replay_previous_disconnect, it))
    }
}

/**
 * True for buffers that exist only locally and are not a valid target.
 */
internal fun IrcViewModel.isPseudoBuffer(bufferName: String): Boolean =
    bufferName == "*server*" || bufferName == RAW_BUFFER

internal fun IrcViewModel.bufKey(netId: String, bufferName: String): String = "$netId::$bufferName"

// Keyed by casefoldText, not lowercase(): a nick like "foo[bar]" that the server
// echoes back as "foo{bar}" on an rfc1459 network is the same nick, and a raw
// lowercase key would fail the self-send dedup and duplicate the local echo.
internal fun IrcViewModel.recordOwnNick(netId: String, nick: String?) {
    if (nick.isNullOrBlank()) return
    val now = System.currentTimeMillis()
    val m = recentOwnNicks.getOrPut(netId) { java.util.concurrent.ConcurrentHashMap() }
    m[casefoldText(netId, nick)] = now
    if (m.size > 16) m.entries.removeAll { now - it.value > SELF_SEND_RETAIN_MS }
}

/** Whether a message on [netId] was sent under our current, a recent, or a configured nick. */
internal fun IrcViewModel.ownLinePredicate(netId: String): (UiMessage) -> Boolean {
    val myNick = _state.value.connections[netId]?.myNick ?: runtimes[netId]?.myNick
    return { m ->
        val f = m.from
        f != null && ((myNick != null && f.equals(myNick, ignoreCase = true)) ||
            isRecentOwnNick(netId, f) || isProfileNick(netId, f))
    }
}

/** True when a replayed line from [nick] was one of ours. */
internal fun IrcViewModel.isOwnNickForReplay(netId: String, nick: String, myNick: String?): Boolean =
    (myNick != null && casefoldText(netId, nick) == casefoldText(netId, myNick)) ||
        isRecentOwnNick(netId, nick) || isProfileNick(netId, nick)

/** True when [nick] is the nick or alternate nick configured for [netId]. */
internal fun IrcViewModel.isProfileNick(netId: String, nick: String): Boolean {
    val profile = _state.value.networks.firstOrNull { it.id == netId } ?: return false
    return nick.equals(profile.nick, ignoreCase = true) ||
        (profile.altNick?.let { nick.equals(it, ignoreCase = true) } == true)
}

internal fun IrcViewModel.isRecentOwnNick(netId: String, nick: String?): Boolean {
    if (nick == null) return false
    val t = recentOwnNicks[netId]?.get(casefoldText(netId, nick)) ?: return false
    return System.currentTimeMillis() - t <= SELF_SEND_RETAIN_MS
}

/**
 * Wrap [text] in the mIRC colour [code] when colorChannelEvents is on: 3 green joins, 7 orange
 * parts, 5 brown quits, 4 red kicks, 10 cyan nick changes. Display only; formatting-stripping
 * sinks see plain text.
 */
internal fun IrcViewModel.colorEvent(text: String, code: Int): String {
    if (!_state.value.settings.colorChannelEvents) return text
    return "\u0003$code$text\u0003"
}

// Case-fold aware lookup; merges duplicate buffers if the server changes name casing.
internal fun IrcViewModel.resolveBufferKey(netId: String, bufferName: String): String {
    val name = bufferName.trim().ifBlank { "*server*" }
    val fold = casefoldText(netId, name)

    val st0 = _state.value
    val candidates = st0.buffers.keys.filter { k ->
        val (nid, bn) = splitKey(k)
        nid == netId && casefoldText(netId, bn) == fold
    }

    if (candidates.isEmpty()) return bufKey(netId, name)

    if (candidates.size == 1) return candidates[0]

    // Deterministic, and deliberately not influenced by which buffer is on screen.
    // Preferring the selected one made resolution depend on the user's position: two
    // buffers differing only by case resolved to whichever was in front, so each switch
    // flipped the winner and merged the other into it, moving the selection again.
    val chosen = candidates.sortedWith(
        compareByDescending<String> { st0.buffers[it]?.messages?.size ?: 0 }.thenBy { it }
    ).first()

    mergeDuplicateBuffers(chosen, candidates.filter { it != chosen })
    return chosen
}

internal fun IrcViewModel.resolveIncomingBufferKey(netId: String, raw: String?): String {
    val name = normalizeIncomingBufferName(netId, raw)
    return resolveBufferKey(netId, name)
}

internal fun IrcViewModel.mergeDuplicateBuffers(keepKey: String, dropKeys: List<String>) {
    if (dropKeys.isEmpty()) return
    val st0 = _state.value
    val keepBuf0 = st0.buffers[keepKey] ?: return

    val maxLines = st0.settings.maxScrollbackLines.coerceIn(100, 5000)

    var mergedLog: BufferLog = keepBuf0.log
    var unread = keepBuf0.unread
    var highlights = keepBuf0.highlights
    var topic = keepBuf0.topic

    for (k in dropKeys) {
        val b = st0.buffers[k] ?: continue
        mergedLog = mergedLog.mergedWith(b.log, maxLines, ChatHistoryController.MAX_BACKFILL_EXTRA)
        unread += b.unread
        highlights += b.highlights
        if (topic == null) topic = b.topic

        chanNickCase.remove(k)?.let { other ->
            val keep = chanNickCase.getOrPut(keepKey) { mutableMapOf() }
            keep.putAll(other)
        }
        chanNickStatus.remove(k)?.let { other ->
            val keep = chanNickStatus.getOrPut(keepKey) { mutableMapOf() }
            for ((fold, modes) in other) {
                val mm = keep.getOrPut(fold) { mutableSetOf() }
                mm.addAll(modes)
            }
        }
    }

    val keepBuf = keepBuf0.copy(log = mergedLog, unread = unread, highlights = highlights, topic = topic)

    fun <T> adoptIfMissing(map: Map<String, T>): Map<String, T> {
        var out = map
        if (!out.containsKey(keepKey)) {
            val adopt = dropKeys.firstNotNullOfOrNull { out[it] }
            if (adopt != null) out = out + (keepKey to adopt)
        }
        for (k in dropKeys) out = out - k
        return out
    }

    if (pendingCloseAfterPart.any { it == keepKey || dropKeys.contains(it) }) {
        pendingCloseAfterPart.removeAll(dropKeys.toSet())
        pendingCloseAfterPart.add(keepKey)
    }

    scrollbackRequested.removeAll(dropKeys.toSet())
    for (k in dropKeys) {
        chatHistory.rename(k, keepKey)
        draftStore.rename(k, keepKey)
    }

    var newBuffers = st0.buffers + (keepKey to keepBuf)
    for (k in dropKeys) newBuffers = newBuffers - k

    val newSelected = if (dropKeys.contains(st0.selectedBuffer)) keepKey else st0.selectedBuffer

    val next = st0.copy(
        buffers = newBuffers,
        selectedBuffer = newSelected,
        nicklists = adoptIfMissing(st0.nicklists),
        banlists = adoptIfMissing(st0.banlists),
        banlistLoading = adoptIfMissing(st0.banlistLoading),
        quietlists = adoptIfMissing(st0.quietlists),
        quietlistLoading = adoptIfMissing(st0.quietlistLoading),
        exceptlists = adoptIfMissing(st0.exceptlists),
        exceptlistLoading = adoptIfMissing(st0.exceptlistLoading),
        invexlists = adoptIfMissing(st0.invexlists),
        invexlistLoading = adoptIfMissing(st0.invexlistLoading)
    )
    _state.value = syncActiveNetworkSummary(next)
}

/**
 * Pending-close tracking is keyed by the *exact* UI buffer key the user closed.
 * Server replies may use a different case for the channel name, so match using CASEMAPPING-aware
 * case-folding.
 */
internal fun IrcViewModel.popPendingCloseForChannel(netId: String, channel: String): String? {
    val fold = casefoldText(netId, channel)
    val match = pendingCloseAfterPart.firstOrNull { k ->
        val (nid, bn) = splitKey(k)
        nid == netId && casefoldText(netId, bn) == fold
    }
    if (match != null) pendingCloseAfterPart.remove(match)
    return match
}

internal fun IrcViewModel.splitKey(key: String): Pair<String, String> {
    val idx = key.indexOf("::")
    return if (idx <= 0) ("unknown" to key) else (key.take(idx) to key.drop(idx + 2))
}

internal fun IrcViewModel.normalizeIncomingBufferName(netId: String, raw: String?): String {
    val t = raw?.trim().orEmpty()
    if (t.isBlank() || t == "?" || t == "*" || t.equals("AUTH", ignoreCase = true)) return "*server*"
    return t
}

internal fun IrcViewModel.isChannelOnNet(netId: String, name: String): Boolean {
    val chantypes = runtimes[netId]?.support?.chantypes ?: "#&"
    return name.isNotBlank() && chantypes.any { name.startsWith(it) }
}

internal fun IrcViewModel.stripStatusMsgPrefix(netId: String, name: String): String {
    val support = runtimes[netId]?.support ?: return name
    val sm = support.statusMsg ?: return name
    val chantypes = support.chantypes
    return if (name.length >= 2 && sm.contains(name[0]) && chantypes.contains(name[1])) {
        name.substring(1)
    } else {
        name
    }
}

/** Initialises a mode-list buffer and marks it as loading. */
internal fun IrcViewModel.startModeList(
    netId: String,
    channel: String,
    getList: (UiState) -> Map<String, List<BanEntry>>,
    getLoading: (UiState) -> Map<String, Boolean>,
    setList: UiState.(Map<String, List<BanEntry>>) -> UiState,
    setLoading: UiState.(Map<String, Boolean>) -> UiState
) {
    val key = resolveBufferKey(netId, channel)
    ensureBuffer(key)
    val st = _state.value
    _state.value = syncActiveNetworkSummary(
        st.setList(getList(st) + (key to emptyList()))
            .setLoading(getLoading(st) + (key to true))
    )
}

internal fun IrcViewModel.startBanList(netId: String, channel: String) = startModeList(
    netId, channel,
    { it.banlists }, { it.banlistLoading },
    { copy(banlists = it) }, { copy(banlistLoading = it) }
)

internal fun IrcViewModel.startQuietList(netId: String, channel: String) = startModeList(
    netId, channel,
    { it.quietlists }, { it.quietlistLoading },
    { copy(quietlists = it) }, { copy(quietlistLoading = it) }
)

internal fun IrcViewModel.startExceptList(netId: String, channel: String) = startModeList(
    netId, channel,
    { it.exceptlists }, { it.exceptlistLoading },
    { copy(exceptlists = it) }, { copy(exceptlistLoading = it) }
)

internal fun IrcViewModel.startInvexList(netId: String, channel: String) = startModeList(
    netId, channel,
    { it.invexlists }, { it.invexlistLoading },
    { copy(invexlists = it) }, { copy(invexlistLoading = it) }
)

/**
 * Remember a line we are sending, so its echo is recognised and its log entry waits for it.
 * Call before showing the line. [sendIds] are what the send returned; with none (no
 * echo-message, or nothing sent) no answer is coming and nothing is recorded.
 */
internal fun IrcViewModel.recordLocalSend(
    netId: String,
    bufferKey: String,
    text: String,
    isAction: Boolean,
    sendIds: List<Long?>,
) {
    val ids = sendIds.filterNotNull()
    if (ids.isEmpty()) return
    val dq = pendingOwnEchoes.getOrPut(netId) { ArrayDeque(32) }
    val evicted = synchronized(dq) {
        dq.addLast(PendingOwnEcho(bufferKey, text, isAction, ids.toMutableList()))
        buildList { while (dq.size > MAX_PENDING_OWN_ECHOES) add(dq.removeFirst()) }
    }
    for (line in evicted) logOwnLine(line, line.text, null)
}

/**
 * True when [echoed] is the server's version of [sent]. echo-message lets a server modify a
 * message (e.g. strip formatting) before echoing it, so the comparison ignores formatting.
 */
internal fun IrcViewModel.echoTextMatches(sent: String, echoed: String): Boolean =
    sent == echoed || stripIrcFormatting(sent) == stripIrcFormatting(echoed)

/** Takes the answer to send [sendId] off the line it belongs to, or null when it isn't one of ours. */
internal fun IrcViewModel.takeOwnSend(netId: String, sendId: Long): OwnSendAnswer? {
    val dq = pendingOwnEchoes[netId] ?: return null
    synchronized(dq) {
        val line = dq.firstOrNull { sendId in it.sendIds } ?: return null
        line.sendIds.remove(sendId)
        val finished = line.sendIds.isEmpty()
        if (finished) dq.remove(line)
        return OwnSendAnswer(line, finished)
    }
}

/**
 * Sends the server answered without their echo: a refusal or error when [failed], which marks the
 * line undelivered, otherwise a reply showing the server dealt with them.
 */
internal fun IrcViewModel.resolveOwnSends(netId: String, ids: List<Long>, failed: Boolean) {
    for (id in ids) {
        val answer = takeOwnSend(netId, id) ?: continue
        if (failed) markOwnLineFailed(netId, answer.line)
        if (answer.finished) logOwnLine(answer.line, answer.line.text, null)
    }
}

/** Marks the newest delivered-looking copy of [sent] in its buffer as undelivered. */
internal fun IrcViewModel.markOwnLineFailed(netId: String, sent: PendingOwnEcho) {
    _state.update { st ->
        val buf = st.buffers[sent.bufferKey] ?: return@update st
        // The nick it was shown under, which a nick change since doesn't alter.
        val shownAs = sent.heldLog?.from ?: st.connections[netId]?.myNick ?: st.myNick
        val idx = buf.messages.indexOfLast {
            !it.failed && it.isAction == sent.isAction && it.text == sent.text &&
                it.from?.equals(shownAs, ignoreCase = true) == true
        }
        if (idx < 0) return@update st
        val updated = buf.messages[idx].copy(failed = true)
        st.copy(buffers = st.buffers + (sent.bufferKey to buf.copy(log = buf.log.replaceAt(idx, updated))))
    }
}

/** Releases [sent]'s held log entry, if any, worded as [text] and stamped [serverTs] when given. */
internal fun IrcViewModel.logOwnLine(sent: PendingOwnEcho, text: String, serverTs: Long?, scope: kotlinx.coroutines.CoroutineScope? = null) {
    val held = sent.heldLog ?: return
    sent.heldLog = null
    if (scope != null) {
        writeLogLine(held.bufferKey, serverTs ?: held.localTs, held.from, text, held.isAction, scope = scope)
    } else {
        writeLogLine(held.bufferKey, serverTs ?: held.localTs, held.from, text, held.isAction)
    }
}

/** Logs every line on [netId] still awaiting an answer as it stands; none will come now. */
internal fun IrcViewModel.flushOwnSends(netId: String, scope: kotlinx.coroutines.CoroutineScope? = null) {
    val dq = pendingOwnEchoes.remove(netId) ?: return
    val lines = synchronized(dq) { dq.toList().also { dq.clear() } }
    for (line in lines) logOwnLine(line, line.text, null, scope)
}

internal fun IrcViewModel.setNetConn(netId: String, f: (NetConnState) -> NetConnState) {
    var shouldRefresh = false
    var changed = false
    _state.update { st: UiState ->
        val old = st.connections[netId] ?: NetConnState()
        val next = f(old)
        if (next == old) return@update st  // no-op: skip the state copy and notification refresh
        changed = true
        val newConns = st.connections + (netId to next)
        val updated = syncActiveNetworkSummary(st.copy(connections = newConns))
        shouldRefresh = updated.settings.showConnectionStatusNotification || updated.settings.keepAliveInBackground
        updated
    }
    if (changed && shouldRefresh) {
        refreshConnectionNotification()
    }
}

// Buffer + message helpers

internal fun IrcViewModel.ensureServerBuffer(netId: String) {
    ensureBuffer(bufKey(netId, "*server*"))
}

internal fun IrcViewModel.ensureBuffer(key: String) {
    // Use atomic update to prevent race conditions when multiple events create buffers.
    var created = false
    _state.update { st0: UiState ->
        if (!st0.buffers.containsKey(key)) {
            created = true
            st0.copy(buffers = st0.buffers + (key to UiBuffer(key)))
        } else {
            st0
        }
    }
    if (created) splitKey(key).let { (netId, name) -> requestQueryReadMarker(netId, name) }

    // Preload the on-disk log tail as scrollback, even with logging now off and even when the
    // server has chathistory (which usually returns far fewer messages). Later history is
    // deduplicated in append(); the merge allows ±3 s of timestamp skew.
    val st = _state.value
    val buf0 = st.buffers[key] ?: return
    if (!scrollbackRequested.add(key)) return

    val (netId, bufferName) = splitKey(key)
    val netName = st.networks.firstOrNull { it.id == netId }?.name ?: "network"
    val maxLines = st.settings.maxScrollbackLines.coerceIn(100, 5000)

    val loadStartMs = System.currentTimeMillis()
    val load = ScrollbackLoad(loadStartMs, key)
    scrollbackLoads[key] = load

    viewModelScope.launch(Dispatchers.IO) {
        try {
            // Outer try around the scrollback pipeline: reading and parsing a log can throw
            // (SAF permission lost after a restore, a partly corrupted file). A failure only
            // means no scrollback preload.
            val lines = try {
                logs.readTail(netName, bufferName, maxLines, st.settings.logFolderUri)
            } catch (t: Throwable) {
                android.util.Log.w("IrcViewModel", "Scrollback preload failed for $key", t)
                scrollbackRequested.remove(load.key)
                return@launch
            }
            if (lines.isEmpty()) {
                // Allow a later retry if a log is created after the buffer exists.
                scrollbackRequested.remove(load.key)

                // Nick tracking is live state - empty scrollback (logging off, new buffer) must not clear it.
                return@launch
            }

            // Lines whose timestamp doesn't parse inherit the last real timestamp before them
            // (or the first one found, for lines at the top), so they stay next to the line
            // they followed in the log.
            val stamps = lines.map { parseLogLineTimeMs(it) }
            val firstReal = stamps.firstOrNull { it != null }
            var carried = firstReal ?: (System.currentTimeMillis() - lines.size.toLong() * 1000L)
            val resolved = stamps.map { st -> st?.also { carried = it } ?: carried }
            val loaded = lines.mapIndexedNotNull { idx, line ->
                parseLogLineToUiMessage(line, fallbackTimeMs = resolved[idx])
            }.filterNot { isStaleTopicBanner(it) }
            if (loaded.isEmpty()) {
                // Lines were read but none survived parsing, so nothing is known about this
                // buffer's history yet. Allow a retry, as the empty-file path above does,
                // rather than marking it loaded for the rest of the session.
                scrollbackRequested.remove(load.key)
                return@launch
            }

            withContext(Dispatchers.Main) {
                val liveKey = load.key
                val newestLogged = loaded.maxOf { it.timeMs }
                loaded.filter { it.timeMs <= loadStartMs - 2_000L }.maxOfOrNull { it.timeMs }
                    ?.let { loggedBeforeConnectMs.merge(liveKey, it, ::maxOf) }
                var blockAdded = false
                _state.update { st ->
                    blockAdded = false
                    val buf = st.buffers[liveKey] ?: return@update st

                    // Lines stamped near the load start are from the session already on
                    // screen, not from a previous one.
                    val fromPreviousSession = settleLogOrder(
                        loaded.filter { it.timeMs <= loadStartMs - 2_000L }
                    )
                    if (fromPreviousSession.isEmpty()) return@update st

                    // One block, in the order it was read, placed where its newest line
                    // belongs. Not woven in among the session's messages by date, but not
                    // stacked above a page of older history already fetched either.
                    val merged = buf.log.insertBlock(fromPreviousSession, isOwn = ownLinePredicate(splitKey(liveKey).first))
                    if (merged.added == 0) return@update st
                    blockAdded = true

                    // The divider closes the block, so it goes on the end of what was added.
                    val alreadyDivided = merged.log.messages.any { it.isSessionDivider }
                    val withDivider =
                        if (st.settings.loggingEnabled && !alreadyDivided) {
                            merged.log.insertDividerAt(
                                merged.at + merged.added,
                                scrollbackDivider(newestLogged),
                            )
                        } else {
                            merged.log
                        }

                    st.copy(buffers = st.buffers + (liveKey to buf.copy(log = withDivider)))
                }
                if (blockAdded) scrollbackFloorMs.merge(liveKey, newestLogged, ::maxOf)
            }
        } finally {
            // However the read ended, the buffer is no longer waiting on it. A newer read
            // for the same buffer keeps its held lines.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                val k = load.key
                val current = scrollbackLoads[k]
                if (current === load) scrollbackLoads.remove(k)
                if (current === load || current == null) {
                    flushDeferredReplays(k)
                    runPendingJoinCatchup(k)
                }
            }
        }
    }
}

/**
 * Put log lines that were written out of order back in time order. A line of ours is
 * written when the server echoes it, which can be after lines received meanwhile, and a
 * bouncer's playback is written after the join and topic lines that came before it. A line
 * moves back past status lines however far, but past chat lines only within
 * [LOG_REORDER_WINDOW_MS], so a clock change such as the end of daylight saving, which
 * repeats an hour of local times, keeps the conversation in the order the file has.
 */
internal fun IrcViewModel.settleLogOrder(lines: List<UiMessage>): List<UiMessage> {
    val out = ArrayList<UiMessage>(lines.size)
    for (m in lines) {
        var at = out.size
        while (at > 0 && out[at - 1].timeMs > m.timeMs &&
            (out[at - 1].timeMs - m.timeMs <= LOG_REORDER_WINDOW_MS || out[at - 1].from == null)
        ) at--
        out.add(at, m)
    }
    return out
}

/**
 * Hold [deliver] until [key]'s scrollback read finishes, returning true when it was
 * held and the caller should stop. Falls through once the queue is full, so a bouncer
 * dumping a long playback is not buffered without limit.
 */
internal fun IrcViewModel.deferReplay(key: String, deliver: (String) -> Unit): Boolean {
    if (!scrollbackLoads.containsKey(key)) return false
    // A backfill collects its own page and closes on the batch, which will not wait.
    if (chatHistory.isBackfilling(key)) return false
    val queue = deferredReplays.getOrPut(key) { mutableListOf() }
    if (queue.size >= MAX_DEFERRED_REPLAYS) return false
    queue.add(deliver)
    armDeferredReplayTimeout(key)
    return true
}

/** Release [key]'s held lines after [DEFERRED_REPLAY_TIMEOUT_MS] if its read has not. */
internal fun IrcViewModel.armDeferredReplayTimeout(key: String) {
    if (deferredReplayTimeouts.containsKey(key)) return
    deferredReplayTimeouts[key] = viewModelScope.launch {
        delay(DEFERRED_REPLAY_TIMEOUT_MS)
        deferredReplayTimeouts.remove(key)
        scrollbackLoads.remove(key)
        flushDeferredReplays(key)
        runPendingJoinCatchup(key)
    }
}

/** Deliver everything held for [key], in the order it arrived. */
internal fun IrcViewModel.flushDeferredReplays(key: String) {
    deferredReplayTimeouts.remove(key)?.cancel()
    val queue = deferredReplays.remove(key)
    queue?.forEach { it(key) }
    if (dividerAfterFlush.remove(key)) flushTrailingDivider(key)
    kickReactionFetch(key)
}

/** Drop everything held for [key] without delivering it. */
internal fun IrcViewModel.dropDeferredReplays(key: String) {
    deferredReplayTimeouts.remove(key)?.cancel()
    dividerAfterFlush.remove(key)
    deferredReplays.remove(key)
}

/** Build the separator drawn where this session begins. */
internal fun IrcViewModel.scrollbackDivider(boundaryMs: Long): UiMessage {
    val newestStr = runCatching {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(boundaryMs))
    }.getOrElse { java.util.Date(boundaryMs).toString() }
    return UiMessage(
        id = nextUiMsgId.getAndIncrement(),
        timeMs = boundaryMs,
        from = null,
        isSessionDivider = true,
        text = "── " + appContext.getString(R.string.vm_scrollback_divider, newestStr) + " ──",
    )
}

// Chat history (IRCv3 CHATHISTORY)

/**
 * The buffer an unsolicited, network-wide event should be shown in.
 *
 * The selected buffer when it belongs to [netId] otherwise that network's `*server*` buffer.
 */
internal fun IrcViewModel.currentBufferOrServer(netId: String): String {
    val selected = _state.value.selectedBuffer
    if (selected.isNotBlank()) {
        val (selNet, selBuf) = splitKey(selected)
        if (selNet == netId && !isDccChatBufferName(selBuf)) return selected
    }
    return bufKey(netId, "*server*")
}

/**
 * Move every piece of per-buffer bookkeeping from [from] to [to].
 *
 * A conversation is keyed by the other person's nick, so their changing nick renames the
 * buffer. The message list moves with the buffer; everything keyed by the same string
 * has to move with it.
 */
internal fun IrcViewModel.renameBufferState(from: String, to: String) {
    if (from == to) return
    draftStore.rename(from, to)
    chatHistory.rename(from, to)
    if (scrollbackRequested.remove(from)) scrollbackRequested.add(to)
    // A read in progress follows the buffer, and its held lines move with it.
    deferredReplayTimeouts.remove(from)?.cancel()
    val held = deferredReplays.remove(from)
    if (dividerAfterFlush.remove(from)) dividerAfterFlush.add(to)
    val load = scrollbackLoads.remove(from)
    if (load != null) {
        load.key = to
        scrollbackLoads[to] = load
        if (held != null) {
            deferredReplays.getOrPut(to) { mutableListOf() }.addAll(held)
            armDeferredReplayTimeout(to)
        }
    } else {
        held?.forEach { it(to) }
        if (dividerAfterFlush.remove(to)) flushTrailingDivider(to)
    }
    scrollbackFloorMs.remove(from)?.let { scrollbackFloorMs.merge(to, it, ::maxOf) }
    catchupCursor.remove(from)?.let { catchupCursor[to] = it }
    joinStartId.remove(from)?.let { joinStartId[to] = it }
    leftChannelAtMs.remove(from)?.let { leftChannelAtMs[to] = it }
    pendingJoinCatchups.remove(from)?.let { pendingJoinCatchups[to] = it }
    lastLoggedServerMs.remove(from)?.let { lastLoggedServerMs.merge(to, it, ::maxOf) }
    loggedBeforeConnectMs.remove(from)?.let { loggedBeforeConnectMs.merge(to, it, ::maxOf) }
    recentSelfSends.remove(from)?.let { moving ->
        // Merge: both keys can hold pending echoes if messaged under each nick.
        val dq = recentSelfSends.getOrPut(to) { ArrayDeque(16) }
        synchronized(dq) { moving.forEach { dq.addLast(it) } }
    }
    recentJoinAtMs.remove(from)?.let { recentJoinAtMs[to] = it }
    pendingCloseAfterPart.remove(from).let { if (it) pendingCloseAfterPart.add(to) }
    // Typing jobs are keyed "$bufferKey/$nick", so they are matched by prefix.
    receivedTypingExpiryJobs.keys.filter { it.startsWith("$from/") }.toList().forEach { k ->
        receivedTypingExpiryJobs.remove(k)?.let { job ->
            receivedTypingExpiryJobs["$to/${k.substringAfter("$from/")}"] = job
        }
    }
}

internal fun IrcViewModel.removeBuffer(key: String) {
    // If this is a DCC CHAT buffer, close the underlying session.
    val (_, name) = splitKey(key)
    if (isDccChatBufferName(name)) {
        dccChatPending.remove(key)?.cancel()
        closeDccChatSession(key)
    }

    scrollbackRequested.remove(key)
    scrollbackLoads.remove(key)
    scrollbackFloorMs.remove(key)
    smartFilter.forgetBuffer(key)
    runCatching { notifier.removeConversation(splitKey(key).first, name) }
    dropPendingReactions(key)
    joinStartId.remove(key)
    leftChannelAtMs.remove(key)
    catchupCursor.remove(key)
    pendingJoinCatchups.remove(key)
    dropDeferredReplays(key)
    lastLoggedServerMs.remove(key)
    loggedBeforeConnectMs.remove(key)
    historyAnchors.remove(key)
    sessionFirstLive.remove(key)
    catchupAnchorMs.remove(key)
    gapMarkers.remove(key)
    chatHistory.forget(key)
    draftStore.clear(key)
    run {
        val (netId, bufferName) = splitKey(key)
        val netName = _state.value.networks.firstOrNull { it.id == netId }?.name ?: "network"
        viewModelScope.launch(Dispatchers.IO) { runCatching { logs.releaseBuffer(netName, bufferName) } }
    }

    // Use atomic update to prevent race conditions
    _state.update { st0: UiState ->
        if (!st0.buffers.containsKey(key)) return@update st0

        val newBuffers = st0.buffers - key
        val newNicklists = st0.nicklists - key
        val newBanlists = st0.banlists - key
        val newBanLoading = st0.banlistLoading - key
        val newQuietlists = st0.quietlists - key
        val newQuietLoading = st0.quietlistLoading - key
        val newExceptlists = st0.exceptlists - key
        val newExceptLoading = st0.exceptlistLoading - key
        val newInvexlists = st0.invexlists - key
        val newInvexLoading = st0.invexlistLoading - key

        val newSelected = if (st0.selectedBuffer == key) {
            val (netId, _) = splitKey(key)
            val serverKey = bufKey(netId, "*server*")
            when {
                newBuffers.containsKey(serverKey) -> serverKey
                newBuffers.isNotEmpty() -> newBuffers.keys.first()
                else -> ""
            }
        } else st0.selectedBuffer

        syncActiveNetworkSummary(
            st0.copy(
                buffers = newBuffers,
                nicklists = newNicklists,
                banlists = newBanlists,
                banlistLoading = newBanLoading,
                quietlists = newQuietlists,
                quietlistLoading = newQuietLoading,
                exceptlists = newExceptlists,
                exceptlistLoading = newExceptLoading,
                invexlists = newInvexlists,
                invexlistLoading = newInvexLoading,
                selectedBuffer = newSelected
            )
        )
    }
}

/** Timestamp of a log line, or null when the line carries no parseable one. */
internal fun IrcViewModel.parseLogLineTimeMs(line: String): Long? {
    val parts = line.trimEnd().split('\t', limit = 2)
    if (parts.size != 2) return null
    return runCatching {
        LocalDateTime
            .parse(parts[0], logTimeFormatter)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}


@Volatile private var topicBannerPrefixesCache: Set<String>? = null

/** Literal lead-ins of the channel-entry topic banners, in every shipped language. */
internal fun IrcViewModel.topicBannerPrefixes(): Set<String> {
    topicBannerPrefixesCache?.let { return it }
    val ids = intArrayOf(R.string.vm_ev_topic_is, R.string.vm_ev_topic_set_by)
    val out = mutableSetOf<String>()
    for (lang in com.boxlabs.hexdroid.ui.SUPPORTED_LANGUAGES) {
        val res = runCatching {
            val cfg = android.content.res.Configuration(appContext.resources.configuration)
            cfg.setLocale(java.util.Locale.forLanguageTag(lang.code))
            appContext.createConfigurationContext(cfg).resources
        }.getOrNull() ?: continue
        for (id in ids) {
            runCatching { res.getString(id) }.getOrNull()
                ?.substringBefore('%')
                ?.trim()
                // vm_ev_topic_changed_by starts with the nick, so its prefix is empty and it
                // is skipped here: a mid-session topic change is real history and stays.
                ?.takeIf { it.length >= 3 }
                ?.let { out.add(it) }
        }
    }
    return out.also { topicBannerPrefixesCache = it }
}

/**
 * True for a channel-entry topic banner this client wrote in an earlier session. Kept in the
 * log as a record of what the topic was then, but not replayed into scrollback: the live
 * banner at the top of this session is the current one.
 */
internal fun IrcViewModel.isStaleTopicBanner(m: UiMessage): Boolean =
    m.from == null && topicBannerPrefixes().any { m.text.startsWith("* $it") }

@Volatile private var statusLineFirstWordsCache: Set<String>? = null

/**
 * First words of the server-status lines this client writes, in every shipped language, so
 * those lines aren't read back from a log as actions. Every banner id is passed in, and ids
 * whose translation starts with a format specifier drop out on their own, so new banners and
 * reordered translations need no list maintained.
 */
internal fun IrcViewModel.statusLineFirstWords(): Set<String> {
    statusLineFirstWordsCache?.let { return it }
    // Reached from the scrollback reader, where a throw would abort the whole load and
    // lose the buffer's history. Whatever was collected before the failure still beats
    // nothing, so the result is cached either way.
    val out = mutableSetOf<String>()
    runCatching {
        for (lang in com.boxlabs.hexdroid.ui.SUPPORTED_LANGUAGES) {
            val res = runCatching {
                val cfg = android.content.res.Configuration(appContext.resources.configuration)
                cfg.setLocale(java.util.Locale.forLanguageTag(lang.code))
                appContext.createConfigurationContext(cfg).resources
            }.getOrNull() ?: continue
            for (id in STATUS_LINE_BANNERS) {
                runCatching { res.getString(id) }.getOrNull()
                    ?.substringBefore(' ')
                    ?.takeIf { it.isNotBlank() && !it.startsWith("%") }
                    ?.let { out.add(it) }
            }
        }
    }
    return out.also { statusLineFirstWordsCache = it }
}

internal fun IrcViewModel.parseLogLineToUiMessage(line: String, fallbackTimeMs: Long): UiMessage? {
    val trimmed = line.trimEnd()
    if (trimmed.isBlank()) return null

    var timeMs = fallbackTimeMs
    var body = trimmed

    // New log format: "yyyy-MM-dd HH:mm:ss	<message>"
    val parts = trimmed.split('	', limit = 2)
    if (parts.size == 2) {
        val maybeTs = parts[0]
        val maybeBody = parts[1]
        val parsed = runCatching {
            LocalDateTime
                .parse(maybeTs, logTimeFormatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
        if (parsed != null) {
            timeMs = parsed
            body = maybeBody
        }
    }

    var from: String? = null
    var text = body
    var isAction = false

    // Log line styles, in priority order:
    //   "*nick* action text"   action (written by this client)
    //   "<nick> hello"         chat message
    //   "* nick action text"   older action format, only when the first word is a valid nick
    //     and not a status word
    //   anything else          status line (from = null)

    // IRC nick validation: may start with letter or _\[]{}|`^ and contain only those
    // characters plus digits and -.  Crucially excludes <, (, #, @, !, digits as first char.
    fun isValidNickChar(c: Char, first: Boolean): Boolean = when {
        c.isLetter() -> true
        c.isDigit() -> !first
        c == '-' -> !first
        c in "_\\[]{}|`^" -> true
        else -> false
    }
    fun looksLikeNick(s: String): Boolean =
        s.isNotEmpty() && s.length <= 32 &&
        s[0].let { isValidNickChar(it, first = true) } &&
        s.all { isValidNickChar(it, first = false) }

    // Resolved in every shipped language, not hardcoded in English: a translated banner's
    // first word passes the nick test and would parse as an action nick.
    val serverStatusFirstWords = statusLineFirstWords()

    if (body.startsWith("*") && body.length > 2 && body[1] != ' ' && body[1] != '*') {
        // New format: *nick* text
        val closeAst = body.indexOf('*', 1)
        if (closeAst > 1 && closeAst + 2 <= body.length) {
            val nick = body.substring(1, closeAst)
            if (looksLikeNick(nick)) {
                from = nick
                text = if (closeAst + 1 < body.length && body[closeAst + 1] == ' ')
                    body.substring(closeAst + 2)
                else
                    body.substring(closeAst + 1)
                isAction = true
            }
        }
    } else if (body.startsWith("<") && body.contains("> ")) {
        val end = body.indexOf("> ")
        if (end > 1) {
            from = body.substring(1, end)
            text = body.substring(end + 2)
        }
    } else if (body.startsWith("* ") && body.length > 2) {
        // Old action format - guard against server-status lines.
        val rest = body.substring(2)
        val sp = rest.indexOf(' ')
        if (sp > 0) {
            val nick = rest.substring(0, sp)
            // "* nick (user@host) has joined ...": a join, part or quit, not an action.
            val eventLine = rest.substring(sp + 1).let { after ->
                after.startsWith("(") && after.substringBefore(')').contains('@')
            }
            if (looksLikeNick(nick) && nick !in serverStatusFirstWords && !eventLine) {
                from = nick
                text = rest.substring(sp + 1)
                isAction = true
            }
            // else: server-status line - leave from=null, text=body (full line)
        }
    }

    return UiMessage(
        id = nextUiMsgId.getAndIncrement(),
        timeMs = timeMs,
        from = from,
        text = text,
        isAction = isAction,
        fromLog = true,
    )
}

internal fun IrcViewModel.setTopic(key: String, topic: String?) {
    val st = _state.value
    val buf = st.buffers[key] ?: UiBuffer(key)
    _state.value = st.copy(buffers = st.buffers + (key to buf.copy(topic = topic)))
}

internal fun IrcViewModel.appendNamesList(bufferKey: String, channel: String, names: List<String>) {
    if (names.isEmpty()) {
        append(bufferKey, from = null, text = "*** " + appContext.getString(R.string.vm_names_none, channel), doNotify = false)
        return
    }

    append(bufferKey, from = null, text = "*** " + appContext.getString(R.string.vm_names_header, channel, names.size), doNotify = false)

    val maxLen = 380
    var sb = StringBuilder()
    for (n in names) {
        if (sb.isEmpty()) {
            sb.append(n)
        } else if (sb.length + 1 + n.length > maxLen) {
            append(bufferKey, from = null, text = "***   ${sb}", doNotify = false)
            sb = StringBuilder(n)
        } else {
            sb.append(' ').append(n)
        }
    }
    if (sb.isNotEmpty()) {
        append(bufferKey, from = null, text = "***   ${sb}", doNotify = false)
    }
}

internal fun IrcViewModel.append(
    bufferKey: String,
    from: String?,
    text: String,
    isAction: Boolean = false,
    isHighlight: Boolean = false,
    isPrivate: Boolean = false,
    isLocal: Boolean = false,
    timeMs: Long? = null,
    doNotify: Boolean = true,
    isMotd: Boolean = false,
    multiline: Boolean = false,
    /**
     * True for genuine server/connection error lines (from = "ERROR"). When set, the
     * notification (if any) is routed through NotificationHelper.notifyError and gated
     * on the per-network NetworkProfile.notifyOnErrors flag instead of the highlight/PM
     * settings, so errors stay out of the tray unless the user opted in for that network.
     */
    isError: Boolean = false,
    msgId: String? = null,
    replyToMsgId: String? = null,
    /**
     * True when this line is being replayed from a server- or bouncer-side history
     * source (chathistory, znc.in/playback, soju buffer playback). Drives the content-
     * fingerprint dedup path that catches duplicate replays without a msgid; live
     * messages skip that path entirely.
     */
    isHistory: Boolean = false,
    /** draft/chathistory-context: volunteered alongside the history that was asked for. */
    isChathistoryContext: Boolean = false,
    /**
     * E2E scheme the wire payload arrived under. Propagated into the resulting
     * UiMessage so the chat renderer can draw a per-scheme padlock icon. Null
     * for messages that never had an encryption prefix on the wire (the common
     * case for system lines, server numerics, etc.).
     */
    encryption: com.boxlabs.hexdroid.crypto.E2eScheme? = null,
    /** True for a locally-echoed +AGE message still being held pending key agreement (see UiMessage.pending). */
    pending: Boolean = false,
    /** draft/oper-tag: sender is an IRC operator (see UiMessage.fromOper). */
    fromOper: Boolean = false,
    /** Bot Mode: sender is a bot (see UiMessage.fromBot). */
    fromBot: Boolean = false,
) {
    val nowMs = System.currentTimeMillis()
    val ts = timeMs ?: nowMs

    // A server-stamped line this old is a replay however it arrived. The short threshold
    // only affects ordering; the long one also governs logging, unread and alerts.
    val suspectReplay = isHistory ||
        (timeMs != null && timeMs < nowMs - BufferLog.REPLAY_SUSPICION_MS)
    val serverNow = if (!isHistory && timeMs != null) {
        newestLiveServerTimeMs.merge(splitKey(bufferKey).first, timeMs, ::maxOf)
    } else {
        null
    }
    val staleLive = serverNow != null && timeMs != null &&
        timeMs < serverNow - STALE_LIVE_LINE_MS

    // Playback for a buffer still reading its log waits for it, so the logged lines land
    // above and this line deduplicates and sorts against them.
    if (suspectReplay && deferReplay(bufferKey) { target ->
            append(
                bufferKey = target,
                from = from,
                text = text,
                isAction = isAction,
                isHighlight = isHighlight,
                isPrivate = isPrivate,
                isLocal = isLocal,
                timeMs = ts,
                doNotify = doNotify,
                isMotd = isMotd,
                multiline = multiline,
                isError = isError,
                msgId = msgId,
                replyToMsgId = replyToMsgId,
                isHistory = isHistory,
                isChathistoryContext = isChathistoryContext,
                encryption = encryption,
                pending = pending,
                fromOper = fromOper,
                fromBot = fromBot,
            )
        }
    ) return

    // A sender on this network's highlight-ignore list never highlights or alerts; the
    // message still gets appended. effectiveHighlight is used everywhere below in place
    // of the raw isHighlight so the badge, colour intent, and tray alert all agree.
    val senderNotifyIgnored = from != null && isNotifyIgnoredSender(splitKey(bufferKey).first, from)
    val effectiveHighlight = isHighlight && !senderNotifyIgnored
    val msg = UiMessage(
        id = nextUiMsgId.getAndIncrement(),
        timeMs = ts,
        fromHistory = isHistory,
        from = from,
        text = text,
        isAction = isAction,
        isMotd = isMotd,
        multiline = multiline,
        msgId = msgId,
        replyToMsgId = replyToMsgId,
        encryption = encryption,
        pending = pending,
        fromOper = fromOper,
        fromBot = fromBot,
    )

    val origin = if (isHistory) MessageOrigin.REPLAY else MessageOrigin.LIVE

    // Self-echo replay dedup. Signatures key on the timestamp, so a local echo (device
    // clock) never matches its own replay (server clock). Matched on content and sender
    // instead. Outside the state update: a match consumes a tracker entry.
    val (selfNetId, _) = splitKey(bufferKey)
    val myNick = _state.value.connections[selfNetId]?.myNick ?: runtimes[selfNetId]?.myNick
    val isFromMe = from != null && (
        (myNick != null && from.equals(myNick, ignoreCase = true)) || isRecentOwnNick(selfNetId, from)
    )
    val isSelfEchoReplayDuplicate =
        isFromMe && isHistory && consumeSelfSendIfMatch(bufferKey, text, isAction)
    // Our own line as far as matching a replay goes. A replay may carry the nick we used
    // in an earlier session.
    val ownForDedup = isFromMe || (suspectReplay && from != null && isProfileNick(selfNetId, from))

    // A backfill captures replayed messages so the page merges at once; they take no
    // part in unread counts, logging or notifications. After the self-echo check, not
    // before: a page reaching back to the user's own recent lines needs the tracker.
    // Suppressed messages still count toward the page length.
    if (isHistory) chatHistory.noteCatchupLine(bufferKey, msg)
    if (isHistory && chatHistory.collect(bufferKey, msg, isSelfEchoReplayDuplicate, isChathistoryContext)) return

    // A stale live line is treated as history is: quiet unless the user asked otherwise.
    val settingsNow = _state.value.settings
    val quiet = isLocal || (staleLive && !settingsNow.ircHistoryCountsAsUnread)
    val mayNotify = doNotify && (!staleLive || settingsNow.ircHistoryTriggersNotifications)
    val floorMs = scrollbackFloorMs[bufferKey]

    var msgWasDuplicate = false
    _state.update { st: UiState ->
        msgWasDuplicate = false
        val buf = st.buffers[bufferKey] ?: UiBuffer(bufferKey)
        val cap = st.settings.maxScrollbackLines.coerceIn(100, 5000)

        val result = buf.log.insert(
            msg = msg,
            origin = origin,
            cap = cap,
            knownDuplicate = isSelfEchoReplayDuplicate,
            nowMs = nowMs,
            floorMs = floorMs,
            skewSeconds = if (ownForDedup) BufferLog.OWN_SIGNATURE_SKEW_SECONDS else BufferLog.SIGNATURE_SKEW_SECONDS,
        )

        // A rejected message leaves its identity behind for a third-route copy.
        if (result is BufferLog.Insert.Duplicate) {
            msgWasDuplicate = true
            if (result.log === buf.log) return@update st
            return@update st.copy(buffers = st.buffers + (bufferKey to buf.copy(log = result.log)))
        }


        // A buffer only counts as seen when the user can actually see it: right screen,
        // right buffer, and the app in the foreground. Without the foreground check a
        // message arriving in the background marks itself read and the unread bar never
        // appears, even though a notification fired for it.
        val isSelected = (bufferKey == st.selectedBuffer
            && st.screen == AppScreen.CHAT
            && AppVisibility.isForeground) || bufferKey in bubblesShowing
        val unreadInc = if (!isSelected && !quiet) 1 else 0
        val highlightInc = if (!isSelected && effectiveHighlight && !quiet) 1 else 0

        // Only ever forward: a replayed line is older than what the user has already seen.
        val newLastRead = if (isSelected) {
            val known = buf.lastReadTimestamp?.let { parseMarkReadMs(it) }
            if (known != null && known > ts) buf.lastReadTimestamp
            else java.time.Instant.ofEpochMilli(ts + 1L).toString()
        } else {
            buf.lastReadTimestamp
        }

        st.copy(
            buffers = st.buffers + (bufferKey to buf.copy(
                log = result.log,
                unread = buf.unread + unreadInc,
                highlights = buf.highlights + highlightInc,
                lastReadTimestamp = newLastRead,
            ))
        )
    }
    val st = _state.value
    if (msgWasDuplicate) return

    // Record genuine local echoes of our own outgoing messages so a later bouncer
    // history-replay of the same line (after a reconnect) can be recognised and
    // dropped. Gate: it's our nick, it's NOT history (a real live echo, not a
    // replay), and it's a local echo (isLocal). The isHistory guard prevents a
    // replayed own-message that slipped past dedup from re-arming the tracker, and
    // the !isSelfEchoReplayDuplicate guard is implicit since duplicates already
    // returned above.
    if (isFromMe && !isHistory && isLocal) {
        recordSelfSend(bufferKey, text, isAction)
        recordOwnNick(selfNetId, from)
    }

    // Advance the boundary only for a committed replay: a deduplicated one would
    // describe a message the user cannot see.
    if (isHistory) chatHistory.noteReplay(bufferKey, ts)
    if ((isHistory || staleLive) && !isLocal && !isPseudoBuffer(splitKey(bufferKey).second)) {
        noteReplayedLine(selfNetId, bufferKey)
    }

    // The upper end of any gap this session has to fill. Only a message the server sent
    // will do: a local line has no msgid and a clock the server never saw.
    if (!isHistory && !staleLive && !isLocal && from != null) sessionFirstLive.putIfAbsent(bufferKey, msg)

    // Logging. A replay is written only when newer than what was logged before this
    // connection; in-memory dedup cannot cover this alone, since the scrollback load runs on
    // IO and a fast replay can beat it. Lines logged since the connection began, such as
    // our join, don't count: a bouncer plays back what it held after sending the JOIN.
    val alreadyOnDisk =
        (isHistory || staleLive) && (loggedBeforeConnectMs[bufferKey]?.let { ts <= it } == true)
    if (!alreadyOnDisk) {
        val held = isFromMe && isLocal && !isHistory &&
            holdOwnLogLine(bufferKey, from, text, isAction, ts, encryption)
        if (!held) {
            writeLogLine(bufferKey, ts, from, text, isAction)
            if (timeMs != null) lastLoggedServerMs.merge(bufferKey, ts, ::maxOf)
        }
    }

    // notifications
    // Suppress only when the buffer is actively visible to the user - i.e. it's the
    // selected buffer on the CHAT screen AND the app is in the foreground.
    // If the app is backgrounded, always notify regardless of which buffer is "selected",
    // because the user can't see the message.
    val isActivelyVisible = (bufferKey == st.selectedBuffer
        && st.screen == AppScreen.CHAT
        && AppVisibility.isForeground) || bufferKey in bubblesShowing
    if (isActivelyVisible && !isHistory && from != null && !isFromMe) sendReadReceipt(bufferKey)
    if (effectiveHighlight && !isPrivate && !isLocal && !isFromMe && from != null &&
        splitKey(bufferKey).let { (n, b) -> isChannelOnNet(n, b) }) {
        recordMention(bufferKey, from, text, ts, msgId, seen = isActivelyVisible || isHistory)
    }
    if (mayNotify && !isActivelyVisible && !quiet && st.settings.notificationsEnabled) {
        val (netId, bufferName) = splitKey(bufferKey)
        val cleanText = stripIrcFormatting(text)
        val preview = when {
            from == null -> cleanText
            isAction -> "* $from $cleanText"
            else -> "<$from> $cleanText"
        }
        // notifTitle is what the user sees; bufferForNotif is the key used to
        // route the tap back to the correct buffer.  Keep them separate so that
        // the human-readable network name can be shown without being mistaken
        // for a channel name when the intent is handled.
        val notifTitle = if (bufferName == "*server*") {
            st.networks.firstOrNull { it.id == netId }?.name ?: "Server"
        } else bufferName
        val netDisplayName = st.networks.firstOrNull { it.id == netId }?.name ?: ""
        val bufferForNotif = bufferName  // always the real buffer key segment
        // Snippet for quote-fallback when server lacks +reply cap.
        val originalSnippet = stripIrcFormatting(text).take(100)
        val senderNick = from ?: ""
        // The message as the conversation view shows it, under the sender's name.
        val notifBody = if (isAction && from != null) "* $from $cleanText" else cleanText
        val selfNick = st.connections[netId]?.myNick
        // Build a stable cross-session anchor for notification → scroll.
        // Prefer the server-assigned IRC msgid (survives process restarts).
        // Fall back to epoch-seconds|nick|textPrefix which survives chathistory reload.
        val msgAnchor = when {
            msgId != null -> "msgid:$msgId"
            else -> "ts:${ts / 1000}|${(from ?: "")}|${stripIrcFormatting(text).take(80)}"
        }
        val net = st.networks.firstOrNull { it.id == netId }
        when {
            // Errors: opt-in per network, dedicated error notification, never a chat ping.
            isError -> {
                if (net?.notifyOnErrors == true) {
                    runCatching { notifier.notifyError(netId, bufferForNotif, cleanText, notifTitle, msgAnchor = msgAnchor) }
                }
            }
            // Sender on the highlight-ignore list, or a muted buffer: the message is visible
            // but raises no alert.
            senderNotifyIgnored || isBufferMuted(bufferKey) -> { /* intentionally silent */ }
            isPrivate && st.settings.notifyOnPrivateMessages -> {
                // A false return means a Web Push already alerted for this message
                val posted = runCatching { notifier.notifyPm(netId, bufferForNotif, preview, msg.id, notifTitle, from = senderNick, originalText = originalSnippet, msgAnchor = msgAnchor, networkName = netDisplayName, body = notifBody, timeMs = ts, selfName = selfNick) }
                    .getOrDefault(false)
                if (posted && st.settings.vibrateOnHighlight) {
                    runCatching { vibrateForHighlight(st.settings.vibrateIntensity) }
                }
            }
            effectiveHighlight && st.settings.notifyOnHighlights -> {
                val posted = runCatching { notifier.notifyHighlight(netId, bufferForNotif, preview, st.settings.playSoundOnHighlight, msg.id, notifTitle, from = senderNick, originalText = originalSnippet, msgAnchor = msgAnchor, networkName = netDisplayName, body = notifBody, timeMs = ts, selfName = selfNick) }
                    .getOrDefault(false)
                if (posted && st.settings.vibrateOnHighlight) {
                    runCatching { vibrateForHighlight(st.settings.vibrateIntensity) }
                }
            }
        }
    }
}

/**
 * Write one line to [bufferKey]'s log, if logging covers that buffer. The write runs on IO,
 * so the line reaches the screen first and the file a moment later.
 */
internal fun IrcViewModel.writeLogLine(
    bufferKey: String,
    ts: Long,
    from: String?,
    text: String,
    isAction: Boolean,
    scope: kotlinx.coroutines.CoroutineScope = viewModelScope,
) {
    val st = _state.value
    if (!st.settings.loggingEnabled) return
    val (netId, bufferName) = splitKey(bufferKey)
    if (bufferName == "*server*" && !st.settings.logServerBuffer) return
    val netName = st.networks.firstOrNull { it.id == netId }?.name ?: "network"
    val logLine = formatLogLine(ts, from, text, isAction)
    val logFolderUri = st.settings.logFolderUri
    scope.launch(logLane) {
        val err = runCatching {
            logs.append(netName, bufferName, logLine, logFolderUri)
        }.getOrElse { t -> t.message ?: t::class.java.simpleName }
        // A failure is reported in the server buffer. That report is logged in turn only
        // when the server buffer is logged, so a failing folder cannot loop.
        if (err != null && bufferName != "*server*") {
            withContext(Dispatchers.Main.immediate) {
                append(bufKey(netId, "*server*"), from = null, text = "*** " + appContext.getString(R.string.vm_log_write_failed, bufferName, err), isLocal = true, doNotify = false)
            }
        }
    }
}

/**
 * Hold our own locally echoed line until the server echoes it, so the log records the
 * server's timestamp and text: those are what a later CHATHISTORY replay carries, and a
 * copy logged at the device's send time can be too far off to be recognised. Returns
 * false, and nothing is held, when no answer is coming for this line.
 */
internal fun IrcViewModel.holdOwnLogLine(
    bufferKey: String,
    from: String?,
    text: String,
    isAction: Boolean,
    localTs: Long,
    encryption: com.boxlabs.hexdroid.crypto.E2eScheme?,
): Boolean {
    if (encryption == com.boxlabs.hexdroid.crypto.E2eScheme.AGE) return false
    val dq = pendingOwnEchoes[splitKey(bufferKey).first] ?: return false
    synchronized(dq) {
        val sent = dq.firstOrNull {
            it.heldLog == null && it.bufferKey == bufferKey && it.isAction == isAction && it.text == text
        } ?: return false
        sent.heldLog = HeldOwnLine(bufferKey, from, text, isAction, localTs)
    }
    return true
}

/** Log every held line as it stands, for teardown. */
internal fun IrcViewModel.flushHeldOwnLines() {
    for (netId in pendingOwnEchoes.keys.toList()) flushOwnSends(netId, scope = appScope)
}
