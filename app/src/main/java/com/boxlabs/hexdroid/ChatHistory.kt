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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * What a history request was for.
 *
 * [OLDER] walks backwards a page at a time and is what moves the paging anchor and decides
 * whether the control retires. [CONTEXT] and [GAP] fetch a specific stretch on their own
 * account, so neither should disturb where paging had got to.
 */
enum class BackfillKind { OLDER, CONTEXT, GAP }

/** Why a backfill stopped. Only [COMPLETE] says anything about how much history remains. */
enum class BackfillOutcome {
    /** The reply batch opened and closed normally. */
    COMPLETE,

    /** Nothing arrived in time, or the request could not be sent. */
    ABANDONED,
}

/** A finished backfill handed back to the caller for merging. */
data class BackfillResult(
    val bufferKey: String,
    val kind: BackfillKind,
    /** The messages to show, in the order the server sent them. */
    val messages: List<UiMessage>,
    /**
     * Page contents recognised on arrival and not to be shown. Reported rather than dropped
     * so their identities are recorded and they count toward the page length.
     */
    val suppressed: List<UiMessage>,
    /** Ids within [messages] that were volunteered as context rather than asked for. */
    val contextIds: Set<Long> = emptySet(),
    val outcome: BackfillOutcome,
    /**
     * True when the server marked the batch draft/chathistory-end, or returned nothing at
     * all. Page length is not a signal: the spec allows a server to return more or fewer
     * messages than the limit asked for.
     */
    val exhausted: Boolean,
)

/**
 * A finished catch-up page, reported so the caller can decide whether the gap it was
 * filling is closed.
 *
 * A catch-up's messages are not collected here: they belong at the bottom of the buffer and
 * arrive one at a time like live traffic, which is what arms the history divider. Only the
 * shape of the reply is reported.
 */
data class CatchupPage(
    val bufferKey: String,
    val target: String,
    /** Messages the request asked for. A reply this long means there is probably more. */
    val requested: Int,
    /** Lines the reply batch carried on the wire. */
    val lines: Int,
    /** True when the server marked the batch as the end of what it holds. */
    val complete: Boolean,
    /** Which request of this catch-up it answers, counting from zero. */
    val round: Int,
)

/**
 * Tracks CHATHISTORY requests in flight and the history divider.
 *
 * One backfill per buffer. While its reply batch arrives, messages are captured here rather
 * than going into the buffer one at a time, so the page is merged in a single state update.
 * Replies are correlated by labeled-response label where available, by target otherwise: a
 * bouncer can have a catch-up and a backfill open for one target at once.
 */
class ChatHistoryController(
    private val scope: CoroutineScope,
    private val onFinished: (BackfillResult) -> Unit,
    private val onCatchupPage: (CatchupPage) -> Unit = {},
) {

    private class Backfill(
        val bufferKey: String,
        val kind: BackfillKind,
        val collected: MutableList<UiMessage> = mutableListOf(),
        val suppressed: MutableList<UiMessage> = mutableListOf(),
        /** Messages the batch carried, suppressed ones included. Exhaustion measures this. */
        var received: Int = 0,
        /** Lines the reply batch carried on the wire, of any type. */
        var linesInBatch: Int = 0,
        /** Ids of collected messages tagged draft/chathistory-context. */
        val contextOnly: MutableSet<Long> = mutableSetOf(),
        /** Set by [attach] once the request is on the wire and its label is known. */
        var label: String? = null,
        /** How many messages were asked for, known only after [attach]. */
        var requested: Int = PAGE_SIZE,
        /** False until [attach], during which a reply can only be matched by target. */
        var attached: Boolean = false,
        /** What the reply batch said about completeness, null when it said nothing. */
        var serverSaysComplete: Boolean? = null,
        var receiving: Boolean = false,
        /** When this request last saw a reply line, for the watchdog. */
        var lastActivityMs: Long = System.currentTimeMillis(),
        var watchdog: Job? = null,
    )

    /** In-flight backfills, keyed by buffer. */
    private val backfills = mutableMapOf<String, Backfill>()

    /** Buffers already asked for a catch-up this session, so TARGETS cannot fan out twice. */
    private val catchupRequested = mutableSetOf<String>()


    /**
     * Catch-up replies expected, so their batch does not close a backfill. A catch-up and a
     * backfill can both be open for a buffer and both answered with a batch naming it.
     */
    private val foreignBatches = mutableListOf<ForeignBatch>()

    private data class ForeignBatch(
        val bufferKey: String,
        val target: String,
        val label: String?,
        val requested: Int,
        val round: Int,
        val registeredMs: Long = System.currentTimeMillis(),
        var complete: Boolean = false,
    )

    /** What a reply batch turned out to belong to. */
    private sealed interface Match {
        data class Ours(val backfill: Backfill) : Match
        data class Theirs(val batch: ForeignBatch) : Match
        object None : Match
    }

    /**
     * Newest replayed message per buffer, awaiting a live message to divide from. Written
     * only while the divider window is armed, so a late playback burst cannot arm one.
     */
    private val pendingDivider = mutableMapOf<String, Long>()

    /** When each buffer's divider window closes. */
    private val dividerArmedUntil = mutableMapOf<String, Long>()

    // Backfill lifecycle

    /** True when a backfill is open for [bufferKey]. */
    fun isBackfilling(bufferKey: String): Boolean = backfills.containsKey(bufferKey)

    /**
     * Open a backfill for [bufferKey], returning false when one is already running. Call
     * before sending: the send suspends and a fast reply can arrive first. The label and
     * effective limit follow through [attach].
     */
    fun begin(bufferKey: String, kind: BackfillKind = BackfillKind.OLDER): Boolean {
        if (backfills.containsKey(bufferKey)) return false
        val bf = Backfill(bufferKey, kind)
        backfills[bufferKey] = bf
        armWatchdog(bf)
        return true
    }

    /**
     * Record what the request for [bufferKey] actually asked for.
     *
     * [label] is the labeled-response label it went out under, or null on a server without
     * labeled-response. [requested] is the limit after the server's advertised ceiling was
     * applied, which is what a short reply is later measured against.
     */
    fun attach(bufferKey: String, label: String?, requested: Int) {
        val bf = backfills[bufferKey] ?: return
        bf.label = label
        bf.requested = requested
        bf.attached = true
    }

    /**
     * Offer a replayed message to the backfill receiving for [bufferKey], returning true when
     * taken, in which case the caller must not add it to the buffer. Messages arriving before
     * the reply batch starts belong to something else and are left alone.
     *
     * [knownDuplicate] counts toward the page but is kept apart from what will be shown.
     */
    fun collect(
        bufferKey: String,
        msg: UiMessage,
        knownDuplicate: Boolean = false,
        isContext: Boolean = false,
    ): Boolean {
        val bf = backfills[bufferKey] ?: return false
        if (!bf.receiving) return false
        // Context messages are shown but do not count: the spec says they "MUST NOT be
        // counted towards the message limit", so a page carrying nothing but context is a
        // page with no history in it.
        bf.lastActivityMs = System.currentTimeMillis()
        if (!isContext) bf.received++
        val target = if (knownDuplicate) bf.suppressed else bf.collected
        if (target.size < MAX_COLLECTED) target.add(msg)
        if (isContext) bf.contextOnly.add(msg.id)
        return true
    }

    /**
     * Note that a CHATHISTORY reply batch has opened. Restarts the watchdog, which was armed
     * for the round trip and would otherwise be timing the transfer as well.
     */
    fun onBatchOpen(bufferKey: String?, label: String?, complete: Boolean? = null) {
        when (val match = resolve(bufferKey, label, consume = false)) {
            is Match.Ours -> {
                match.backfill.receiving = true
                if (complete != null) match.backfill.serverSaysComplete = complete
                armWatchdog(match.backfill)
            }
            is Match.Theirs -> if (complete != null) match.batch.complete = complete
            Match.None -> Unit
        }
    }

    /**
     * Note that a CHATHISTORY reply batch has closed, and finish the backfill it belongs to.
     *
     * [lines] is how many lines the batch carried on the wire, which is what decides whether
     * it was empty. Counting only the lines that became visible messages read a batch of
     * nothing but joins and parts as empty, retiring the control on the first request.
     */
    fun onBatchClose(bufferKey: String?, label: String?, lines: Int = 0) {
        when (val match = resolve(bufferKey, label, consume = true)) {
            is Match.Ours -> {
                match.backfill.linesInBatch = lines
                finish(match.backfill.bufferKey, BackfillOutcome.COMPLETE)
            }
            is Match.Theirs -> onCatchupPage(
                CatchupPage(
                    bufferKey = match.batch.bufferKey,
                    target = match.batch.target,
                    requested = match.batch.requested,
                    lines = lines,
                    complete = match.batch.complete,
                    round = match.batch.round,
                )
            )
            Match.None -> Unit
        }
    }

    /**
     * The buffer key of the only backfill open on [netId], or null when there are none or
     * more than one.
     *
     * For a rejection that names no target: something definitely failed, and leaving a
     * request open guarantees a stalled spinner, so closing the single candidate is better
     * than waiting for the watchdog. With several open there is no way to tell which.
     */
    fun soleOutstanding(netId: String): String? =
        backfills.keys.filter { it.startsWith("$netId::") }.singleOrNull()

    /** Close the backfill for [bufferKey] with [outcome], if one is open. */
    fun finish(bufferKey: String, outcome: BackfillOutcome) {
        val bf = backfills.remove(bufferKey) ?: return
        bf.watchdog?.cancel()
        // Per the chathistory spec, a server "MAY return more or fewer [than limit] due to
        // implementation constraints", so page length says nothing about whether more
        // exists. Only two things do: the draft/chathistory-end tag, and an empty batch.
        val exhausted = outcome == BackfillOutcome.COMPLETE &&
            (bf.serverSaysComplete == true || bf.linesInBatch == 0)
        onFinished(
            BackfillResult(
                bufferKey = bf.bufferKey,
                kind = bf.kind,
                messages = bf.collected.toList(),
                suppressed = bf.suppressed.toList(),
                contextIds = bf.contextOnly.toSet(),
                outcome = outcome,
                exhausted = exhausted,
            )
        )
    }

    /**
     * Note that a catch-up reply is expected for [bufferKey], so [resolve] can tell it apart
     * from a backfill's and report it as a [CatchupPage] when it closes.
     */
    fun expectCatchup(bufferKey: String, target: String, label: String?, requested: Int, round: Int) {
        val now = System.currentTimeMillis()
        // A reply that never came must not sit here waiting to match an unrelated batch.
        foreignBatches.removeAll { now - it.registeredMs > FOREIGN_BATCH_TTL_MS }
        if (foreignBatches.size >= MAX_FOREIGN_BATCHES) foreignBatches.removeAt(0)
        foreignBatches.add(ForeignBatch(bufferKey, target, label, requested, round))
    }

    /**
     * Match a reply to an open backfill, or null when it belongs to something else.
     *
     * A label decides outright. Without one, order does: a buffer with an outstanding
     * catch-up gets the first reply, since catch-ups are requested on connect and backfills
     * only when the user scrolls back. [consume] is set only by the close, so one batch's
     * open and close reach the same verdict.
     */
    private fun resolve(bufferKey: String?, label: String?, consume: Boolean): Match {
        if (label != null) {
            val foreign = foreignBatches.indexOfFirst { it.label == label }
            if (foreign >= 0) {
                val batch = foreignBatches[foreign]
                if (consume) foreignBatches.removeAt(foreign)
                return Match.Theirs(batch)
            }
            val byLabel = backfills.values.firstOrNull { it.attached && it.label == label }
            if (byLabel != null) return Match.Ours(byLabel)
            // A reply that beat its own request's bookkeeping back to us. There is nothing to
            // match the label against yet, so fall back to the target.
            val bf = bufferKey?.let { backfills[it] }?.takeIf { !it.attached }
            return if (bf != null) Match.Ours(bf) else Match.None
        }

        val key = bufferKey ?: return Match.None
        // Before the backfill lookup, so a catch-up's batch is claimed whether or not a
        // backfill happens to be open. Left here, it would still be waiting when the user
        // later scrolls back, and would take that backfill's reply instead.
        val foreign = foreignBatches.indexOfFirst { it.bufferKey == key && it.label == null }
        if (foreign >= 0) {
            val batch = foreignBatches[foreign]
            if (consume) foreignBatches.removeAt(foreign)
            return Match.Theirs(batch)
        }

        val bf = backfills[key] ?: return Match.None
        // The server sent no label. A backfill that went out with one is expecting a labelled
        // reply, so this batch is not it.
        if (bf.attached && bf.label != null) return Match.None
        return Match.Ours(bf)
    }

    /**
     * Give up on [bf] once it has been quiet for [BACKFILL_TIMEOUT_MS].
     *
     * Idle time rather than total time: a page still arriving is not a stalled request, and
     * cutting one short delivers half of it and lets the rest scatter into the buffer.
     */
    private fun armWatchdog(bf: Backfill) {
        bf.watchdog?.cancel()
        bf.lastActivityMs = System.currentTimeMillis()
        bf.watchdog = scope.launch {
            while (true) {
                val idleFor = System.currentTimeMillis() - bf.lastActivityMs
                val remaining = BACKFILL_TIMEOUT_MS - idleFor
                if (remaining <= 0) break
                delay(remaining)
            }
            finish(bf.bufferKey, BackfillOutcome.ABANDONED)
        }
    }

    // Catch-up throttling

    /**
     * Claim a catch-up slot for [bufferKey] on [netId]. CHATHISTORY TARGETS can name every
     * buffer a bouncer has ever stored, and each would otherwise become a request.
     */
    fun claimCatchup(netId: String, bufferKey: String, max: Int): Boolean {
        val prefix = "$netId::"
        if (catchupRequested.count { it.startsWith(prefix) } >= max) return false
        return catchupRequested.add(bufferKey)
    }

    // History divider

    /** Open [bufferKey]'s divider window for [forMs] from now. */
    fun armDivider(bufferKey: String, forMs: Long) {
        val now = System.currentTimeMillis()
        dividerArmedUntil[bufferKey] = now + forMs
        if (dividerArmedUntil.size > MAX_TRACKED_DIVIDERS) {
            dividerArmedUntil.entries.removeAll { it.value < now }
        }
    }

    /** Record a replayed message's timestamp, if this buffer's divider window is open. */
    fun noteReplay(bufferKey: String, timeMs: Long) {
        val armedUntil = dividerArmedUntil[bufferKey] ?: return
        if (armedUntil < System.currentTimeMillis()) return
        val prev = pendingDivider[bufferKey] ?: 0L
        if (timeMs > prev) pendingDivider[bufferKey] = timeMs
    }

    /**
     * Claim the divider timestamp for a live message at [liveTimeMs], or null. Claiming
     * closes the window, so one divider per catch-up. The gap requirement stops a boundary
     * being drawn mid-replay, which would leave the rest of it below the line.
     */
    fun claimDivider(bufferKey: String, liveTimeMs: Long): Long? {
        val pending = pendingDivider[bufferKey] ?: return null
        val armedUntil = dividerArmedUntil[bufferKey] ?: 0L
        if (armedUntil < System.currentTimeMillis()) {
            pendingDivider.remove(bufferKey)
            return null
        }
        if (liveTimeMs <= pending + DIVIDER_MIN_GAP_MS) return null
        pendingDivider.remove(bufferKey)
        dividerArmedUntil.remove(bufferKey)
        return pending
    }

    /**
     * Claim the divider timestamp with nothing live to divide from, for when a replay batch
     * closes and no message follows it.
     */
    fun claimTrailingDivider(bufferKey: String): Long? {
        val pending = pendingDivider.remove(bufferKey) ?: return null
        val armedUntil = dividerArmedUntil[bufferKey] ?: 0L
        if (armedUntil < System.currentTimeMillis()) return null
        dividerArmedUntil.remove(bufferKey)
        return pending
    }

    // Teardown

    /** Forget everything tracked for [bufferKey]. */
    fun forget(bufferKey: String) {
        backfills.remove(bufferKey)?.watchdog?.cancel()
        catchupRequested.remove(bufferKey)
        foreignBatches.removeAll { it.bufferKey == bufferKey }
        pendingDivider.remove(bufferKey)
        dividerArmedUntil.remove(bufferKey)
    }

    /** Move everything tracked for [from] onto [to], used when two buffers turn out to be one. */
    fun rename(from: String, to: String) {
        if (from == to) return
        backfills.remove(from)?.watchdog?.cancel()
        catchupRequested.remove(from)
        foreignBatches.replaceAll { if (it.bufferKey == from) it.copy(bufferKey = to) else it }
        pendingDivider.remove(from)?.let { ts ->
            val prev = pendingDivider[to] ?: 0L
            if (ts > prev) pendingDivider[to] = ts
        }
        dividerArmedUntil.remove(from)?.let { until ->
            val prev = dividerArmedUntil[to] ?: 0L
            if (until > prev) dividerArmedUntil[to] = until
        }
    }

    /**
     * Drop every request and window belonging to [netId]. Called on each registration: a
     * request in flight cannot survive a reconnect.
     */
    fun forgetNetwork(netId: String) {
        val prefix = "$netId::"
        backfills.keys.filter { it.startsWith(prefix) }.forEach { backfills.remove(it)?.watchdog?.cancel() }
        catchupRequested.removeAll { it.startsWith(prefix) }
        foreignBatches.removeAll { it.bufferKey.startsWith(prefix) }
        pendingDivider.keys.filter { it.startsWith(prefix) }.toList().forEach { pendingDivider.remove(it) }
        dividerArmedUntil.keys.filter { it.startsWith(prefix) }.toList().forEach { dividerArmedUntil.remove(it) }
    }

    /** Drop tracking for buffers that no longer exist. */
    fun retainOnly(liveKeys: Set<String>) {
        backfills.keys.filterNot { it in liveKeys }.toList()
            .forEach { backfills.remove(it)?.watchdog?.cancel() }
        catchupRequested.removeAll { it !in liveKeys }
        foreignBatches.removeAll { it.bufferKey !in liveKeys }
        pendingDivider.keys.filterNot { it in liveKeys }.toList().forEach { pendingDivider.remove(it) }
        dividerArmedUntil.keys.filterNot { it in liveKeys }.toList().forEach { dividerArmedUntil.remove(it) }
    }

    companion object {
        /** How many messages to ask for in one backfill page. */
        const val PAGE_SIZE = 50

        /**
         * How long to wait for a reply batch before giving up. Restarted when the batch
         * opens, so the round trip and the transfer are timed separately.
         */
        const val BACKFILL_TIMEOUT_MS = 12_000L


        /** Hard ceiling on one page, in case a server ignores the limit it was given. */
        const val MAX_COLLECTED = 1000

        /** How far past the configured scrollback cap repeated backfills may grow a buffer. */
        const val MAX_BACKFILL_EXTRA = 5000

        /** Ceiling on catch-up requests fired from one CHATHISTORY TARGETS reply. */
        const val MAX_CATCHUP_TARGETS = 40

        /**
         * How many pages one catch-up attempt walks forward before it stops and leaves the
         * gap marked. Resumed when the user next opens the buffer.
         */
        const val MAX_CATCHUP_ROUNDS = 6

        /** How long a divider window stays open after a join or a reconnect. */
        const val DIVIDER_WINDOW_MS = 45_000L

        /** Minimum gap between the newest replayed message and a live one to draw a divider. */
        const val DIVIDER_MIN_GAP_MS = 5_000L

        /** Bound on the divider bookkeeping for someone who joins a great many channels. */
        const val MAX_TRACKED_DIVIDERS = 64

        /** Bound on tracked non-backfill replies, for a server that never answers them. */
        const val MAX_FOREIGN_BATCHES = 128

        /** How long an expected non-backfill reply stays claimable. */
        const val FOREIGN_BATCH_TTL_MS = 60_000L

        /**
         * Selector timestamp format for CHATHISTORY anchors. Pinned rather than ISO_INSTANT,
         * which drops the fractional part when it is zero; the spec's grammar keeps it.
         */
        private val ANCHOR_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)

        /** Format [timeMs] as a CHATHISTORY selector timestamp. */
        fun anchorTimestamp(timeMs: Long): String = ANCHOR_FORMAT.format(Instant.ofEpochMilli(timeMs))
    }
}
