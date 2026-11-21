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

package com.boxlabs.hexdroid.push

import android.content.Context
import com.boxlabs.hexdroid.UiSettings
import com.boxlabs.hexdroid.VibrateIntensity
import com.boxlabs.hexdroid.data.ChannelPrefs
import com.boxlabs.hexdroid.data.ChannelPrefsCodec
import com.boxlabs.hexdroid.data.NetworkProfile
import com.boxlabs.hexdroid.data.NotifyMasks
import org.json.JSONArray
import org.json.JSONObject

/**
 * The notification settings for pushed messages, mirrored into SharedPreferences so the push service can read them.
 */
object PushNotifyPolicy {

    private const val PREFS = "hexdroid_push_policy"
    private const val KEY_ENABLED = "notifications_enabled"
    private const val KEY_HIGHLIGHTS = "notify_highlights"
    private const val KEY_PMS = "notify_pms"
    private const val KEY_SOUND = "play_sound"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_INTENSITY = "vibrate_intensity"
    private const val KEY_MASKS = "mute_masks"
    private const val KEY_MUTED_BUFFERS = "muted_buffers"
    private const val KEY_MUTED_UNTIL = "muted_until"

    /**
     * The gate a pushed message passes through, with the highlight-ignore masks of every
     * configured network keyed by network id.
     */
    data class Policy(
        val notificationsEnabled: Boolean,
        val notifyHighlights: Boolean,
        val notifyPrivateMessages: Boolean,
        val playSound: Boolean,
        val vibrate: Boolean,
        val vibrateIntensity: VibrateIntensity,
        val muteMasks: Map<String, List<String>>,
        /** Muted buffers, as [ChannelPrefsCodec.keyFor] keys. */
        val mutedBuffers: Set<String> = emptySet(),
        /** Buffers muted for a while, with the time (epoch ms) each mute ends. */
        val mutedUntil: Map<String, Long> = emptyMap(),
    ) {
        /**
         * True when [nick] is muted for [netId]. An empty [netId] (push not attributable to a
         * network) applies every network's masks.
         */
        fun mutesNick(netId: String, nick: String?): Boolean {
            val masks = if (netId.isBlank()) muteMasks.values.flatten() else muteMasks[netId].orEmpty()
            return NotifyMasks.mutesNick(masks, nick)
        }

        /**
         * True when [buffer] is muted on [netId]. An empty [netId] matches the buffer name on
         * any network.
         */
        fun mutesBuffer(netId: String, buffer: String, nowMs: Long = System.currentTimeMillis()): Boolean {
            if (buffer.isBlank()) return false
            val muted = mutedBuffers + mutedUntil.filterValues { it > nowMs }.keys
            if (muted.isEmpty()) return false
            if (netId.isNotBlank()) return ChannelPrefsCodec.keyFor("$netId::$buffer") in muted
            val suffix = ChannelPrefsCodec.keyFor("::$buffer")
            return muted.any { it.endsWith(suffix) }
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Mirror the alert-gating fields of [settings] and [networks] for the push service. */
    fun store(
        ctx: Context,
        settings: UiSettings,
        networks: List<NetworkProfile>,
        channelPrefs: Map<String, ChannelPrefs> = emptyMap(),
    ) {
        val masks = JSONObject()
        for (net in networks) {
            val list = net.highlightIgnoreMasks.filter { it.isNotBlank() }
            if (list.isNotEmpty()) masks.put(net.id, JSONArray(list))
        }
        prefs(ctx).edit()
            .putBoolean(KEY_ENABLED, settings.notificationsEnabled)
            .putBoolean(KEY_HIGHLIGHTS, settings.notifyOnHighlights)
            .putBoolean(KEY_PMS, settings.notifyOnPrivateMessages)
            .putBoolean(KEY_SOUND, settings.playSoundOnHighlight)
            .putBoolean(KEY_VIBRATE, settings.vibrateOnHighlight)
            .putString(KEY_INTENSITY, settings.vibrateIntensity.name)
            .putString(KEY_MASKS, masks.toString())
            .putStringSet(KEY_MUTED_BUFFERS, channelPrefs.filterValues { it.muted }.keys.toSet())
            .putString(KEY_MUTED_UNTIL, encodeMutedUntil(
                channelPrefs.filterValues { it.mutedUntilMs > System.currentTimeMillis() }.mapValues { it.value.mutedUntilMs }
            ))
            .apply()
    }

    /** Record that [bufferKey] is muted until [untilMs], without the rest of the policy. */
    fun setMutedUntil(ctx: Context, bufferKey: String, untilMs: Long) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val map = decodeMutedUntil(p.getString(KEY_MUTED_UNTIL, null)).filterValues { it > now }.toMutableMap()
        map[ChannelPrefsCodec.keyFor(bufferKey)] = untilMs
        p.edit().putString(KEY_MUTED_UNTIL, encodeMutedUntil(map)).apply()
    }

    private fun encodeMutedUntil(map: Map<String, Long>): String =
        JSONObject().apply { for ((k, v) in map) put(k, v) }.toString()

    private fun decodeMutedUntil(json: String?): Map<String, Long> = runCatching {
        val obj = JSONObject(json.orEmpty().ifBlank { "{}" })
        buildMap { for (k in obj.keys()) put(k, obj.optLong(k, 0L)) }
    }.getOrDefault(emptyMap())

    /**
     * The stored policy, or the app's own defaults when nothing has been mirrored yet.
     */
    fun read(ctx: Context): Policy {
        val p = prefs(ctx)
        val defaults = UiSettings()
        val masks = runCatching {
            val obj = JSONObject(p.getString(KEY_MASKS, "{}").orEmpty().ifBlank { "{}" })
            buildMap {
                for (netId in obj.keys()) {
                    val arr = obj.optJSONArray(netId) ?: continue
                    val list = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                    if (list.isNotEmpty()) put(netId, list)
                }
            }
        }.getOrDefault(emptyMap())

        return Policy(
            notificationsEnabled = p.getBoolean(KEY_ENABLED, defaults.notificationsEnabled),
            notifyHighlights = p.getBoolean(KEY_HIGHLIGHTS, defaults.notifyOnHighlights),
            notifyPrivateMessages = p.getBoolean(KEY_PMS, defaults.notifyOnPrivateMessages),
            playSound = p.getBoolean(KEY_SOUND, defaults.playSoundOnHighlight),
            vibrate = p.getBoolean(KEY_VIBRATE, defaults.vibrateOnHighlight),
            vibrateIntensity = runCatching {
                VibrateIntensity.valueOf(p.getString(KEY_INTENSITY, defaults.vibrateIntensity.name)!!)
            }.getOrDefault(defaults.vibrateIntensity),
            muteMasks = masks,
            mutedBuffers = runCatching { p.getStringSet(KEY_MUTED_BUFFERS, null)?.toSet() }.getOrNull().orEmpty(),
            mutedUntil = decodeMutedUntil(p.getString(KEY_MUTED_UNTIL, null)),
        )
    }
}
