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

        /** Added at the end in place of the earlier copies it matched. */
        data class Replaced(override val log: BufferLog) : Insert
    }

    /** The outcome of merging a block of messages into the log. */
    data class Merge(
        val log: BufferLog,
        val added: Int,
        /** Where the block was inserted, for a caller placing a divider after it. */
        val at: Int = 0,
    )

    /**
     * Add [msg] unless its msgid or signature is already known or [knownDuplicate] is set; a
     * rejected message still records its identity.
     *
     * [floorMs]: newest timestamp of the block read from disk; an out-of-order message is never
     * placed at or before it.
     * [skewSeconds]: how far apart two copies may be stamped and still match
     * ([OWN_SIGNATURE_SKEW_SECONDS] for our own lines).
     * [repeatsOnJoin]: for lines resent on every join; earlier copies are removed and [msg] is
     * appended once.
     */
    fun insert(
        msg: UiMessage,
        origin: MessageOrigin,
        cap: Int,
        knownDuplicate: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        floorMs: Long? = null,
        skewSeconds: Long = SIGNATURE_SKEW_SECONDS,
        repeatsOnJoin: Boolean = false,
    ): Insert {
        val sig = signatureOf(msg)
        // Stamped well in the past means a replay whatever the caller called it: a server
        // that re-sends on reconnect without a batch is not distinguishable from live
        // traffic at the point of classification.
        val couldBeReplay =
            origin == MessageOrigin.REPLAY || msg.timeMs < nowMs - REPLAY_SUSPICION_MS
        val duplicateById = knownDuplicate || (msg.msgId != null && seenIds.contains(msg.msgId))
        val duplicateBySig = !duplicateById && couldBeReplay && matchesKnownSignature(msg, seenSigs, skewSeconds)

        if (repeatsOnJoin && !knownDuplicate) {
            val copies = indicesOfCopies(msg, skewSeconds = null)
            val builder = messages.builder()
            for (i in copies.asReversed()) builder.removeAt(i)
            builder.add(msg)
            val next = copy(
                messages = builder.build(),
                seenIds = if (msg.msgId != null) seenIds.adding(msg.msgId) else seenIds,
                seenSigs = seenSigs.adding(sig),
            ).trimmed(cap)
            return if (copies.isEmpty()) Insert.Added(next) else Insert.Replaced(next)
        }

        if (duplicateById || duplicateBySig) {
            val base = if (duplicateBySig) adoptingId(msg, skewSeconds) else this
            return Insert.Duplicate(base.remembering(msg.msgId, sig))
        }

        val placed = place(messages, msg, origin, floorMs)
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
     * Merge a block delivery (finished backfill, disk preload) into the log, deduplicated and
     * placed by timestamp. [growCapacity] raises [extraCapacity] by the number added, up to
     * [maxExtra], so the trim cannot evict them; a disk preload passes false. [isOwn] selects
     * messages matched with [OWN_SIGNATURE_SKEW_SECONDS].
     */
    fun merge(
        incoming: List<UiMessage>,
        baseCap: Int,
        maxExtra: Int,
        growCapacity: Boolean,
        isOwn: (UiMessage) -> Boolean = { false },
    ): Merge {
        if (incoming.isEmpty()) return Merge(this, 0)

        val ordered = incoming.sortedWith(compareBy<UiMessage> { it.timeMs }.thenBy { it.id })

        var ids = seenIds
        var sigs = seenSigs
        val fresh = ArrayList<UiMessage>(ordered.size)
        var adopted = this
        for (m in ordered) {
            val sig = signatureOf(m)
            val skew = skewFor(m, isOwn, SIGNATURE_SKEW_SECONDS)
            val knownById = m.msgId != null && ids.contains(m.msgId)
            val knownBySig = !knownById && matchesKnownSignature(m, sigs, skew)
            if (knownBySig) adopted = adopted.adoptingId(m, skew)
            if (m.msgId != null) ids = ids.adding(m.msgId)
            sigs = sigs.adding(sig)
            if (!knownById && !knownBySig) fresh.add(m)
        }
        val held = adopted.messages

        if (fresh.isEmpty()) {
            return Merge(copy(messages = held, seenIds = ids, seenSigs = sigs), 0)
        }

        val extra = if (growCapacity) (extraCapacity + fresh.size).coerceAtMost(maxExtra) else extraCapacity
        val merged = placeBlock(held, fresh)

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
     * Trim the front of the buffer to [baseCap] plus [extraCapacity], removing evicted identities
     * one by one. Two messages sharing a signature lose it when the first is evicted.
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
     * Insert [incoming] as one block in the given order, at the position its newest message belongs
     * to by time. The block is not interleaved, so disk logs and server history stay on either side
     * of the divider.
     */
    fun insertBlock(
        incoming: List<UiMessage>,
        skewSeconds: Long = SIGNATURE_SKEW_SECONDS,
        isOwn: (UiMessage) -> Boolean = { false },
    ): Merge {
        if (incoming.isEmpty()) return Merge(this, 0)

        var ids = seenIds
        var sigs = seenSigs
        val fresh = ArrayList<UiMessage>(incoming.size)
        for (m in incoming) {
            val known = (m.msgId != null && ids.contains(m.msgId)) ||
                matchesKnownSignature(m, sigs, skewFor(m, isOwn, skewSeconds))
            if (m.msgId != null) ids = ids.adding(m.msgId)
            sigs = sigs.adding(signatureOf(m))
            if (!known) fresh.add(m)
        }
        if (fresh.isEmpty()) return Merge(copy(seenIds = ids, seenSigs = sigs), 0)

        val newest = fresh.maxOf { it.timeMs }
        var at = messages.size
        while (at > 0 && messages[at - 1].timeMs > newest) at--

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

    /**
     * Give [msg]'s msgid to the newest held copy of it that has none, such as a line read from
     * the disk log, so replies and reactions naming that msgid can find it.
     */
    private fun adoptingId(msg: UiMessage, skewSeconds: Long): BufferLog {
        val id = msg.msgId ?: return this
        val at = indicesOfCopies(msg, skewSeconds).lastOrNull { messages[it].msgId == null } ?: return this
        return replaceAt(at, messages[at].copy(msgId = id))
    }

    /**
     * Ascending indices of the messages that are copies of [msg], by msgid or by sender and
     * text stamped within [skewSeconds], or at any time when that is null.
     */
    private fun indicesOfCopies(msg: UiMessage, skewSeconds: Long?): List<Int> {
        val sec = msg.timeMs / 1000
        val who = normaliseSender(msg)
        val body = normaliseBody(msg)
        val out = ArrayList<Int>()
        for (i in messages.indices) {
            val m = messages[i]
            val sameId = msg.msgId != null && m.msgId == msg.msgId
            val sameSig = (skewSeconds == null || kotlin.math.abs(m.timeMs / 1000 - sec) <= skewSeconds) &&
                normaliseSender(m) == who && normaliseBody(m) == body
            if (sameId || sameSig) out.add(i)
        }
        return out
    }

    private fun skewFor(m: UiMessage, isOwn: (UiMessage) -> Boolean, default: Long): Long =
        if (m.from != null && isOwn(m)) maxOf(default, OWN_SIGNATURE_SKEW_SECONDS) else default

    /** True when any signature within the clock-skew window is already known. */
    private fun matchesKnownSignature(
        msg: UiMessage,
        sigs: PersistentSet<String> = seenSigs,
        skewSeconds: Long = SIGNATURE_SKEW_SECONDS,
    ): Boolean {
        val sec = msg.timeMs / 1000
        val body = normaliseBody(msg)
        val who = normaliseSender(msg)
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

        /** The skew window for our own messages. See [insert]. */
        const val OWN_SIGNATURE_SKEW_SECONDS = 60L

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
            signatureAt(msg.timeMs / 1000, normaliseSender(msg), normaliseBody(msg))

        private fun signatureAt(sec: Long, sender: String, body: String): String = "$sec|$sender|$body"

        /**
         * Normalise a message body for comparison across delivery routes: formatting stripped,
         * whitespace collapsed and trimmed, truncated to bound the key size.
         */
        private fun normaliseText(text: String): String =
            stripIrcFormatting(text)
                .replace(WHITESPACE_RUN, " ")
                .trim()
                .take(120)
                .lowercase()

        /**
         * [msg]'s body in the form every delivery route shares. Actions and system lines compare as
         * "* nick text", with bracketed details removed, since a replay carries less (no host,
         * account or reason) than the live line.
         */
        private fun normaliseBody(msg: UiMessage): String = when {
            msg.from == null -> normaliseText(stripIrcFormatting(msg.text).replace(BRACKETED, " "))
            msg.isAction -> normaliseText(
                stripIrcFormatting("* ${msg.from} ${msg.text}").replace(BRACKETED, " ")
            )
            else -> normaliseText(msg.text)
        }

        /** Any run of spaces, tabs or line breaks. */
        private val WHITESPACE_RUN = Regex("\\s+")

        /** A parenthesised or square-bracketed group with no nesting. */
        private val BRACKETED = Regex("\\([^()]*\\)|\\[[^\\[\\]]*\\]")

        /**
         * System lines and actions share the sender "*" (see [normaliseBody]), and nick case is
         * not significant.
         */
        private fun normaliseSender(msg: UiMessage): String =
            if (msg.from == null || msg.isAction) "*" else msg.from.lowercase()

        /**
         * Insert [msg] at the position its timestamp asks for. Live messages append unless
         * stamped well before the tail, which means an unrecognised replay. Scans backwards
         * because both cases almost always belong near the end. The scan stops at the first
         * line stamped at or before [floorMs].
         */
        private fun place(
            list: PersistentList<UiMessage>,
            msg: UiMessage,
            origin: MessageOrigin,
            floorMs: Long? = null,
        ): PersistentList<UiMessage> {
            if (list.isEmpty()) return list.adding(msg)
            val newest = list[list.size - 1].timeMs
            if (msg.timeMs >= newest) return list.adding(msg)
            if (origin == MessageOrigin.LIVE && msg.timeMs > newest - LIVE_REORDER_TOLERANCE_MS) {
                return list.adding(msg)
            }
            val floor = floorMs ?: Long.MIN_VALUE
            var at = list.size
            while (at > 0 && list[at - 1].timeMs > msg.timeMs && list[at - 1].timeMs > floor) at--
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
