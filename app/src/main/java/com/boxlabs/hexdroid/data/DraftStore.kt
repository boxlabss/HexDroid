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
 * Unsent composer text and the caret position it was left at.
 */
data class Draft(val text: String, val cursor: Int) {
    companion object {
        val EMPTY = Draft("", 0)
    }
}

/**
 * Unsent message text, kept per buffer.
 *
 * The chat composer is a single text field shared by every buffer, so without somewhere
 * to park the text, switching buffers carries a half-written line across to the next one
 * and the next send delivers it to the wrong target.
 */
class DraftStore {

    private val drafts = ConcurrentHashMap<String, Draft>()

    /** The saved draft for [bufferKey], or an empty one when there is none. */
    fun get(bufferKey: String): Draft = drafts[bufferKey] ?: Draft.EMPTY

    /**
     * Save [text] and the caret offset [cursor] as the draft for [bufferKey].
     *
     * Blank text removes the entry rather than storing an empty string, so a buffer the
     * user has typed in and then cleared doesn't sit in the map forever.
     */
    fun put(bufferKey: String, text: String, cursor: Int) {
        if (text.isEmpty()) {
            drafts.remove(bufferKey)
        } else {
            drafts[bufferKey] = Draft(text, cursor.coerceIn(0, text.length))
        }
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
