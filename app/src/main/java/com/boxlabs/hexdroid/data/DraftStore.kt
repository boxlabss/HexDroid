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

import java.util.concurrent.ConcurrentHashMap

/**
 * Unsent message text, kept per buffer.
 *
 * The chat composer is a single text field shared by every buffer, so without somewhere
 * to park the text, switching buffers carriess a half-written line across to the next one
 * and the next send delivered it to the wrong target.
 */
class DraftStore {

    private val drafts = ConcurrentHashMap<String, String>()

    /** The saved draft for [bufferKey], or an empty string when there is none. */
    fun get(bufferKey: String): String = drafts[bufferKey].orEmpty()

    /**
     * Save [text] as the draft for [bufferKey].
     *
     * Blank text removes the entry rather than storing an empty string, so a buffer the
     * user has typed in and then cleared doesn't sit in the map forever.
     */
    fun put(bufferKey: String, text: String) {
        if (text.isEmpty()) drafts.remove(bufferKey) else drafts[bufferKey] = text
    }

    /** Discard the draft for [bufferKey]. */
    fun clear(bufferKey: String) {
        drafts.remove(bufferKey)
    }

    /** Discard every draft belonging to [netId], for when a network's buffers go away. */
    fun clearNetwork(netId: String) {
        val prefix = "$netId::"
        drafts.keys.filter { it.startsWith(prefix) }.forEach { drafts.remove(it) }
    }

    /** Discard everything. */
    fun clearAll() {
        drafts.clear()
    }
}
