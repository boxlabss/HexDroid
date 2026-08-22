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
    ) {
        /**
         * True when [nick] is muted for [netId].
         *
         * An empty [netId] means the push could not be attributed to one of the user's networks,
         * which is the normal case when nothing is connected. Every network's masks then apply
         * a nick the user has silenced somewhere is a nick they asked
         * not to be alerted by, and staying quiet is the failure they chose.
         */
        fun mutesNick(netId: String, nick: String?): Boolean {
            val masks = if (netId.isBlank()) muteMasks.values.flatten() else muteMasks[netId].orEmpty()
            return NotifyMasks.mutesNick(masks, nick)
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Mirror the alert-gating fields of [settings] and [networks] for the push service. */
    fun store(ctx: Context, settings: UiSettings, networks: List<NetworkProfile>) {
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
            .apply()
    }

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
        )
    }
}
