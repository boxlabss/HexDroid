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

package com.boxlabs.hexdroid.data

import com.boxlabs.hexdroid.UiSettings
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Which channel events are left out of chat, and whether the smart filter is on. */
data class EventPrefs(
    val hideJoins: Boolean = false,
    val hideParts: Boolean = false,
    val hideQuits: Boolean = false,
    val hideNickChanges: Boolean = false,
    val hideTopicChanges: Boolean = false,
    val hideKicks: Boolean = false,
    val hideModeChanges: Boolean = false,
    /** Joins, parts, quits and nick changes show only for people who spoke recently. */
    val smartFilter: Boolean = false,
)

/** Settings for one channel or query that apply on top of the app-wide ones. */
data class ChannelPrefs(
    /** No notifications from this buffer. Unread counts and highlight colours still show. */
    val muted: Boolean = false,
    /** Words that highlight here, in addition to the app-wide ones. */
    val highlightWords: List<String> = emptyList(),
    /** Event visibility for this channel, or null to follow the app-wide setting. */
    val events: EventPrefs? = null,
    /**
     * soju had this buffer muted (soju.im/muted) when the two last agreed. Tells which side
     * changed while they were apart: a server value that differs from it was changed by
     * another client, a local one by this device.
     */
    val syncedMute: Boolean = false,
    /** Notifications are silenced until this time (epoch ms), as from "Mute for 1 hour"; 0 when not. */
    val mutedUntilMs: Long = 0L,
) {
    val isDefault: Boolean get() = !muted && highlightWords.isEmpty() && events == null && !syncedMute && mutedUntilMs <= 0L

    /** True when notifications from this buffer are silenced at [nowMs]. */
    fun isMutedAt(nowMs: Long): Boolean = muted || mutedUntilMs > nowMs
}

fun UiSettings.eventPrefs(): EventPrefs = EventPrefs(
    hideJoins = hideJoins,
    hideParts = hideParts,
    hideQuits = hideQuits,
    hideNickChanges = hideNickChanges,
    hideTopicChanges = hideTopicChanges,
    hideKicks = hideKicks,
    hideModeChanges = hideModeChanges,
    smartFilter = smartFilter,
)

fun UiSettings.withEventPrefs(p: EventPrefs): UiSettings = copy(
    hideJoins = p.hideJoins,
    hideParts = p.hideParts,
    hideQuits = p.hideQuits,
    hideNickChanges = p.hideNickChanges,
    hideTopicChanges = p.hideTopicChanges,
    hideKicks = p.hideKicks,
    hideModeChanges = p.hideModeChanges,
    smartFilter = p.smartFilter,
)

object ChannelPrefsCodec {

    /** The key a buffer's settings are stored under: its buffer key, lowercased. */
    fun keyFor(bufferKey: String): String = bufferKey.lowercase(Locale.ROOT)

    fun encode(all: Map<String, ChannelPrefs>): String {
        val root = JSONObject()
        val now = System.currentTimeMillis()
        for ((key, stored) in all) {
            // A timed mute that has ended is not kept.
            val p = if (stored.mutedUntilMs in 1..now) stored.copy(mutedUntilMs = 0L) else stored
            if (p.isDefault) continue
            val o = JSONObject()
            if (p.muted) o.put("muted", true)
            if (p.syncedMute) o.put("syncedMute", true)
            if (p.mutedUntilMs > 0L) o.put("mutedUntilMs", p.mutedUntilMs)
            if (p.highlightWords.isNotEmpty()) o.put("highlightWords", JSONArray(p.highlightWords))
            p.events?.let { e ->
                o.put("events", JSONObject().apply {
                    put("hideJoins", e.hideJoins)
                    put("hideParts", e.hideParts)
                    put("hideQuits", e.hideQuits)
                    put("hideNickChanges", e.hideNickChanges)
                    put("hideTopicChanges", e.hideTopicChanges)
                    put("hideKicks", e.hideKicks)
                    put("hideModeChanges", e.hideModeChanges)
                    put("smartFilter", e.smartFilter)
                })
            }
            root.put(key, o)
        }
        return root.toString()
    }

    fun decode(json: String?): Map<String, ChannelPrefs> {
        if (json.isNullOrBlank()) return emptyMap()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, ChannelPrefs>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val o = root.optJSONObject(key) ?: continue
            val words = o.optJSONArray("highlightWords")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).trim().takeIf { it.isNotEmpty() } }
            }.orEmpty()
            val events = o.optJSONObject("events")?.let { e ->
                EventPrefs(
                    hideJoins = e.optBoolean("hideJoins", false),
                    hideParts = e.optBoolean("hideParts", false),
                    hideQuits = e.optBoolean("hideQuits", false),
                    hideNickChanges = e.optBoolean("hideNickChanges", false),
                    hideTopicChanges = e.optBoolean("hideTopicChanges", false),
                    hideKicks = e.optBoolean("hideKicks", false),
                    hideModeChanges = e.optBoolean("hideModeChanges", false),
                    smartFilter = e.optBoolean("smartFilter", false),
                )
            }
            val p = ChannelPrefs(
                muted = o.optBoolean("muted", false),
                highlightWords = words,
                events = events,
                syncedMute = o.optBoolean("syncedMute", false),
                mutedUntilMs = o.optLong("mutedUntilMs", 0L),
            )
            if (!p.isDefault) out[keyFor(key)] = p
        }
        return out
    }
}
