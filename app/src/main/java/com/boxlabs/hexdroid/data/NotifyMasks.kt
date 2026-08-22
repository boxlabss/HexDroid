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

/**
 * Matching for NetworkProfile.highlightIgnoreMasks.
 *
 * Shared because the same list decides whether a message raises an alert on the live
 * connection and whether it raises one when the same message arrives as a Web Push
 */
object NotifyMasks {

    /** [nick] with any channel status prefix removed, or null when it is blank. */
    fun bareNick(nick: String?): String? =
        nick?.trim()?.trimStart('~', '&', '@', '%', '+')?.takeIf { it.isNotBlank() }

    /** Match an IRC-style glob pattern (*, ?) against [input], case-insensitive. */
    fun matchIrcGlob(pattern: String, input: String): Boolean {
        val regex = buildString {
            append("(?i)\\A")
            for (ch in pattern) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append(".")
                    else -> append(Regex.escape(ch.toString()))
                }
            }
            append("\\z")
        }
        return Regex(regex).containsMatchIn(input)
    }

    /**
     * True when [nick] matches any of [masks], each read as a regex (`/.../`), an IRC
     * glob (`*` / `?`), or a plain case-insensitive nick.
     */
    fun mutesNick(masks: List<String>, nick: String?): Boolean {
        val base = bareNick(nick) ?: return false
        if (masks.isEmpty()) return false
        return masks.any { raw ->
            val m = raw.trim()
            when {
                m.isEmpty() -> false
                m.length >= 2 && m.startsWith("/") && m.endsWith("/") ->
                    runCatching {
                        Regex(m.substring(1, m.length - 1), RegexOption.IGNORE_CASE).containsMatchIn(base)
                    }.getOrDefault(false)
                m.contains('*') || m.contains('?') -> matchIrcGlob(m, base)
                else -> m.equals(base, ignoreCase = true)
            }
        }
    }
}
