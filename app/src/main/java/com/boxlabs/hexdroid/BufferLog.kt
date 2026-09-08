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

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList

/** Whether a message is happening now or being re-delivered from storage. */
enum class MessageOrigin { LIVE, REPLAY }

/**
 * One buffer's messages plus the indices that recognise a repeat delivery.
 *
 * Every mutation goes through here. Invariants: replayed messages sit in timestamp order
 * among the messages they were sent between, live messages keep arrival order, and the
 * indices cover everything accepted or rejected since the last trim.
 */
data class BufferLog(
    val messages: PersistentList<UiMessage> = persistentListOf(),
    /** IRCv3 msgid values seen for this buffer. */
    val seenIds: PersistentSet<String> = persistentSetOf(),
    /** Content signatures seen for this buffer. See [signatureOf]. */
    val seenSigs: PersistentSet<String> = persistentSetOf(),
    /** Messages beyond the scrollback cap this buffer may hold, earned by backfilling. */
    val extraCapacity: Int = 0,
) {

    /** The outcome of offering a single message to the log. */
    sealed interface Insert {
        val log: BufferLog

        /** The message was new and is now in [log]. */
        data class Added(override val log: BufferLog) : Insert

        /** Already known and not added. [log] still records its identity. */
        data class Duplicate(override val log: BufferLog) : Insert
    }

    /** The outcome of merging a block of messages into the log. */
    data class Merge(
        val log: BufferLog,
        val added: Int,
        /** Where the block was inserted, for a caller placing a divider after it. */
        val at: Int = 0,
    )

    /**
     * Offer [msg] to the log, rejecting it when its msgid or signature is already known or
     * [knownDuplicate] is set. A rejected message still contributes its identity.
     *
     * Signatures are second-resolution, so they are only checked for a message that could be
     * a replay: two identical live lines in one second are two messages, not one seen twice.
     */
    fun insert(
        msg: UiMessage,
        origin: MessageOrigin,
        cap: Int,
        knownDuplicate: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): Insert {
        val sig = signatureOf(msg)
        // Stamped well in the past means a replay whatever the caller called it: a server
        // that re-sends on reconnect without a batch is not distinguishable from live
        // traffic at the point of classification.
        val couldBeReplay =
            origin == MessageOrigin.REPLAY || msg.timeMs < nowMs - REPLAY_SUSPICION_MS
        val duplicate = knownDuplicate ||
            (msg.msgId != null && seenIds.contains(msg.msgId)) ||
            (couldBeReplay && matchesKnownSignature(msg))

        if (duplicate) {
            return Insert.Duplicate(remembering(msg.msgId, sig))
        }

        val placed = place(messages, msg, origin)
        return Insert.Added(
            copy(
                messages = placed,
                seenIds = if (msg.msgId != null) seenIds.adding(msg.msgId) else seenIds,
                seenSigs = seenSigs.adding(sig),
            ).trimmed(cap)
        )
    }

    /**
     * Insert a locally generated separator line at the position its timestamp asks for.
     * Markers take no part in deduplication and contribute nothing to the indices.
     */
    fun insertMarker(marker: UiMessage, cap: Int): BufferLog =
        copy(messages = place(messages, marker, MessageOrigin.REPLAY)).trimmed(cap)

    /**
     * Merge a block delivery (a finished backfill, a disk preload) into the log, dropping
     * anything already known and placing the rest by timestamp. Incoming messages are
     * deduplicated against the log and against each other.
     *
     * [growCapacity] raises [extraCapacity] by the number added, bounded by [maxExtra], so
     * the trim cannot evict them. A disk preload passes false: it fills the buffer to its
     * normal depth rather than past it.
     */
    fun merge(
        incoming: List<UiMessage>,
        baseCap: Int,
        maxExtra: Int,
        growCapacity: Boolean,
    ): Merge {
        if (incoming.isEmpty()) return Merge(this, 0)

        val ordered = incoming.sortedWith(compareBy<UiMessage> { it.timeMs }.thenBy { it.id })

        var ids = seenIds
        var sigs = seenSigs
        val fresh = ArrayList<UiMessage>(ordered.size)
        for (m in ordered) {
            val sig = signatureOf(m)
            val known = (m.msgId != null && ids.contains(m.msgId)) || matchesKnownSignature(m, sigs)
            if (m.msgId != null) ids = ids.adding(m.msgId)
            sigs = sigs.adding(sig)
            if (!known) fresh.add(m)
        }

        if (fresh.isEmpty()) {
            return Merge(copy(seenIds = ids, seenSigs = sigs), 0)
        }

        val extra = if (growCapacity) (extraCapacity + fresh.size).coerceAtMost(maxExtra) else extraCapacity
        val merged = placeBlock(messages, fresh)

        return Merge(
            copy(
                messages = merged,
                seenIds = ids,
                seenSigs = sigs,
                extraCapacity = extra,
            ).trimmed(baseCap),
            fresh.size,
        )
    }

    /**
     * Replace the message at [index], keeping its position and refreshing its identity.
     * For edits that do not change membership: a tombstone, a failed send, a learned msgid.
     */
    fun replaceAt(index: Int, msg: UiMessage): BufferLog {
        if (index !in messages.indices) return this
        val old = messages[index]
        val oldSig = signatureOf(old)
        val newSig = signatureOf(msg)
        return copy(
            messages = messages.replacingAt(index, msg),
            seenIds = if (msg.msgId != null) seenIds.adding(msg.msgId) else seenIds,
            seenSigs = if (oldSig == newSig) seenSigs else seenSigs.removing(oldSig).adding(newSig),
        )
    }

    /** Apply [transform] to every message, keeping positions and indices as they are. */
    fun mapMessages(transform: (UiMessage) -> UiMessage): BufferLog =
        copy(messages = messages.map(transform).toPersistentList())

    /** Index of the last message matching [predicate], or -1. */
    fun indexOfLast(predicate: (UiMessage) -> Boolean): Int = messages.indexOfLast(predicate)

    /**
     * Drop every message and forget every identity, so a later replay of the same lines is
     * visible again rather than suppressed as a duplicate of something no longer on screen.
     */
    fun cleared(): BufferLog = BufferLog()

    /** Note that this buffer's history has been walked back by [count] more messages. */
    fun grownBy(count: Int, maxExtra: Int): BufferLog =
        copy(extraCapacity = (extraCapacity + count).coerceAtMost(maxExtra))

    /**
     * Trim the front of the buffer down to [baseCap] plus [extraCapacity].
     *
     * Evicted identities are removed one by one rather than rebuilt from what is retained,
     * which would cost a full pass per message once the buffer sits at its cap. Two messages
     * sharing a signature lose it when the first is evicted, letting a later duplicate
     * through; that is the cheaper failure.
     */
    fun trimmed(baseCap: Int): BufferLog {
        val cap = (baseCap + extraCapacity).coerceAtLeast(1)
        if (messages.size <= cap) return this

        val drop = messages.size - cap
        var ids = seenIds
        var sigs = seenSigs
        for (i in 0 until drop) {
            val m = messages[i]
            if (m.msgId != null) ids = ids.removing(m.msgId)
            sigs = sigs.removing(signatureOf(m))
        }
        return copy(
            messages = messages.subList(drop, messages.size).toPersistentList(),
            seenIds = ids,
            seenSigs = sigs,
        )
    }

    /**
     * Adopt [other]'s messages and identities, for when two buffers turn out to be one.
     *
     * Shared messages are matched on content, not [UiMessage.id]: each buffer appended its
     * own copy so the ids differ. Indices are unioned after merging, since doing it first
     * would make every message in [other] a duplicate of its own recorded signature.
     */
    fun mergedWith(other: BufferLog, baseCap: Int, maxExtra: Int): BufferLog {
        if (other.messages.isEmpty()) {
            return copy(
                seenIds = seenIds.addingAll(other.seenIds),
                seenSigs = seenSigs.addingAll(other.seenSigs),
                extraCapacity = maxOf(extraCapacity, other.extraCapacity),
            )
        }
        val widened = copy(extraCapacity = maxOf(extraCapacity, other.extraCapacity))
        val merged = widened.merge(other.messages, baseCap, maxExtra, growCapacity = false).log
        return merged.copy(
            seenIds = merged.seenIds.addingAll(other.seenIds),
            seenSigs = merged.seenSigs.addingAll(other.seenSigs),
        )
    }

    /** Insert [divider] at [index], taking no part in deduplication. */
    fun insertDividerAt(index: Int, divider: UiMessage): BufferLog =
        copy(messages = messages.addingAt(index.coerceIn(0, messages.size), divider))

    /**
     * Insert [incoming] as one block, keeping the order given, at the position its newest
     * message belongs to.
     *
     * The block itself is not woven in by timestamp: disk logs and server history overlap,
     * and interleaving them by date leaves the divider between them with nothing to divide.
     * Where the block sits is still decided by time, so a log read that finishes after a
     * page of older history was fetched lands below that page rather than on top of it,
     * while a replay covering the same stretch as the block ends up below the block.
     */
    fun insertBlock(incoming: List<UiMessage>, skewSeconds: Long = SIGNATURE_SKEW_SECONDS): Merge {
        if (incoming.isEmpty()) return Merge(this, 0)

        var ids = seenIds
        var sigs = seenSigs
        val fresh = ArrayList<UiMessage>(incoming.size)
        for (m in incoming) {
            val known = (m.msgId != null && ids.contains(m.msgId)) ||
                matchesKnownSignature(m, sigs, skewSeconds)
            if (m.msgId != null) ids = ids.adding(m.msgId)
            sigs = sigs.adding(signatureOf(m))
            if (!known) fresh.add(m)
        }
        if (fresh.isEmpty()) return Merge(copy(seenIds = ids, seenSigs = sigs), 0)

        val newest = fresh.maxOf { it.timeMs }
        val oldest = fresh.minOf { it.timeMs }
        var at = messages.size
        while (at > 0 && messages[at - 1].timeMs > newest) at--
        // Server playback that landed before the read finished covers ground this block
        // covers, so the block goes above it rather than below. Bounded by the block's own
        // span, which leaves a page of genuinely older history where it is.
        while (at > 0 && messages[at - 1].fromHistory && messages[at - 1].timeMs >= oldest) at--

        val builder = messages.builder()
        builder.addAll(at, fresh)

        return Merge(
            copy(
                messages = builder.build(),
                seenIds = ids,
                seenSigs = sigs,
                // Outside the normal depth, so the next message does not trim it away.
                extraCapacity = extraCapacity + fresh.size,
            ),
            fresh.size,
            at,
        )
    }

    /**
     * Keep only the messages whose ids are in [ids], in their existing order. The indices are
     * left alone, so a copy of a removed message is still recognised.
     */
    fun retaining(ids: Set<Long>): BufferLog {
        if (messages.size == ids.size) return this
        return copy(messages = messages.filter { it.id in ids }.toPersistentList())
    }

    /**
     * Record the identities of [messages] without adding any of them, so a further copy
     * arriving by another route is recognised.
     */
    fun remembering(messages: List<UiMessage>): BufferLog {
        if (messages.isEmpty()) return this
        var ids = seenIds
        var sigs = seenSigs
        for (m in messages) {
            if (m.msgId != null) ids = ids.adding(m.msgId)
            sigs = sigs.adding(signatureOf(m))
        }
        if (ids === seenIds && sigs === seenSigs) return this
        return copy(seenIds = ids, seenSigs = sigs)
    }

    /**
     * Record an identity without adding a message, returning this instance unchanged when
     * both were already known. Callers rely on that to avoid a state update per duplicate.
     */
    private fun remembering(msgId: String?, sig: String): BufferLog {
        val newIds = if (msgId != null) seenIds.adding(msgId) else seenIds
        val newSigs = seenSigs.adding(sig)
        if (newIds === seenIds && newSigs === seenSigs) return this
        return copy(seenIds = newIds, seenSigs = newSigs)
    }

    /** True when any signature within the clock-skew window is already known. */
    private fun matchesKnownSignature(
        msg: UiMessage,
        sigs: PersistentSet<String> = seenSigs,
        skewSeconds: Long = SIGNATURE_SKEW_SECONDS,
    ): Boolean {
        val sec = msg.timeMs / 1000
        val body = normaliseText(msg.text)
        val who = normaliseSender(msg.from)
        for (delta in -skewSeconds..skewSeconds) {
            if (sigs.contains(signatureAt(sec + delta, who, body))) return true
        }
        return false
    }

    companion object {
        /**
         * How far either side of a message's own second to look for a copy of it. Disk logs
         * store whole seconds, server-time stores milliseconds, and bouncers restamp.
         */
        const val SIGNATURE_SKEW_SECONDS = 3L

        /**
         * How far out of order a live message may be and still keep its arrival position.
         * Reordering ordinary clock jitter would make lines jump around for no benefit.
         */
        const val LIVE_REORDER_TOLERANCE_MS = 30_000L

        /**
         * How far in the past a message must be stamped before it is treated as a possible
         * replay regardless of how it arrived.
         */
        const val REPLAY_SUSPICION_MS = 15_000L


        /** The content signature identifying [msg] regardless of which route delivered it. */
        fun signatureOf(msg: UiMessage): String =
            signatureAt(msg.timeMs / 1000, normaliseSender(msg.from), normaliseText(msg.text))

        private fun signatureAt(sec: Long, sender: String, body: String): String = "$sec|$sender|$body"

        /**
         * Reduce a message body to the form shared by every delivery route.
         *
         * Disk logs hold it already stripped of formatting codes; the wire copy does not.
         * Whitespace is collapsed and trimmed because the two routes disagree about it: a
         * line ending in a space keeps it one way and loses it the other, and comparing the
         * text verbatim then read the pair as two different messages. Truncated to bound the
         * key size.
         */
        private fun normaliseText(text: String): String =
            stripIrcFormatting(text)
                .replace(WHITESPACE_RUN, " ")
                .trim()
                .take(120)
                .lowercase()

        /** Any run of spaces, tabs or line breaks. */
        private val WHITESPACE_RUN = Regex("\\s+")

        /** System lines have no sender, and nick case is not significant. */
        private fun normaliseSender(from: String?): String = from?.lowercase() ?: "*"

        /**
         * Insert [msg] at the position its timestamp asks for. Live messages append unless
         * stamped well before the tail, which means an unrecognised replay. Scans backwards
         * because both cases almost always belong near the end.
         */
        private fun place(
            list: PersistentList<UiMessage>,
            msg: UiMessage,
            origin: MessageOrigin,
        ): PersistentList<UiMessage> {
            if (list.isEmpty()) return list.adding(msg)
            val newest = list[list.size - 1].timeMs
            if (msg.timeMs >= newest) return list.adding(msg)
            if (origin == MessageOrigin.LIVE && msg.timeMs > newest - LIVE_REORDER_TOLERANCE_MS) {
                return list.adding(msg)
            }
            var at = list.size
            while (at > 0 && list[at - 1].timeMs > msg.timeMs) at--
            return list.addingAt(at, msg)
        }

        /**
         * Insert a block of already-sorted messages by timestamp. Walking it newest first
         * lets each insertion resume the previous scan, so the merge costs one pass total.
         */
        private fun placeBlock(
            list: PersistentList<UiMessage>,
            sortedAscending: List<UiMessage>,
        ): PersistentList<UiMessage> {
            val builder = list.builder()
            var hint = builder.size
            for (i in sortedAscending.indices.reversed()) {
                val m = sortedAscending[i]
                while (hint > 0 && builder[hint - 1].timeMs > m.timeMs) hint--
                builder.add(hint, m)
            }
            return builder.build()
        }
    }
}
