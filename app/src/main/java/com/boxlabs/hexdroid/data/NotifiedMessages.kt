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
 * Messages that already produced a notification, so one message never notifies twice when it
 * arrives by both Web Push and the live connection or its catch-up.
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
            // Written synchronously only off the main thread, where it can't stall the UI. On the
            // main thread apply() is enough: Android flushes pending writes when a service finishes
            // handling a start, which covers the push service, the case that needs it durable.
            val edit = prefs.edit().putString(KEY_RECENT, kept.joinToString("\n"))
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) edit.apply() else edit.commit()
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
