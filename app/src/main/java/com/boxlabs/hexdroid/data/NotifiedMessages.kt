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

import android.content.Context

/**
 * Remembers which messages have already produced a notification, so one message never
 * pings twice.
 *
 * The same message can reach the notification code by two independent routes: a Web Push
 * delivered while no connection was open, and the live connection or its CHATHISTORY
 * catch-up once the app is running.
 */
object NotifiedMessages {

    private const val PREFS = "hexdroid_notified_messages"
    private const val KEY_RECENT = "recent"

    /**
     * How many anchors to retain.
     */
    private const val MAX_ENTRIES = 64

    private val lock = Any()

    /**
     * Claim [anchor] for notification: true when this caller should notify, false when
     * something already has.
     *
     * A null or blank anchor is always claimable.
     */
    fun claim(ctx: Context, anchor: String?): Boolean {
        val key = anchor?.replace('\n', ' ')?.trim()
        if (key.isNullOrEmpty()) return true

        synchronized(lock) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_RECENT, "").orEmpty()
            val seen = stored.split('\n').filter { it.isNotEmpty() }
            if (key in seen) return false

            val kept = (seen + key).takeLast(MAX_ENTRIES)
            // commit() rather than apply(): a push notification is frequently the last
            // thing this process does before the system reclaims it, and an unflushed
            // write would let the live connection notify for the same message again.
            prefs.edit().putString(KEY_RECENT, kept.joinToString("\n")).commit()
            return true
        }
    }

    /** Forget every claim. Used when the user clears app data from settings. */
    fun clear(ctx: Context) {
        synchronized(lock) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
