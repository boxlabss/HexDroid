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

data class IrcMessage(
    val tags: Map<String, String?>,
    val prefix: String?,
    val command: String,
    val params: List<String>,
    val trailing: String?
) {
    fun prefixNick(): String? = prefix?.substringBefore('!')?.takeIf { it.isNotBlank() }
}

class IrcParser {
    fun parse(line: String): IrcMessage? {
        var i = 0
        val tags = mutableMapOf<String, String?>()

        if (line.startsWith("@")) {
            val end = line.indexOf(' ')
            if (end == -1) return null
            val rawTags = line.substring(1, end)
            rawTags.split(';').forEach { kv ->
                val eq = kv.indexOf('=')
                if (eq >= 0) tags[kv.substring(0, eq)] = unescapeTagValue(kv.substring(eq + 1))
                else tags[kv] = null
            }
            i = end + 1
        }

        var prefix: String? = null
        if (i < line.length && line[i] == ':') {
            val end = line.indexOf(' ', i)
            if (end == -1) return null
            prefix = line.substring(i + 1, end)
            i = end + 1
        }

        val cmdEnd = line.indexOf(' ', i).let { if (it == -1) line.length else it }
        val command = line.substring(i, cmdEnd)
        i = cmdEnd

        val params = mutableListOf<String>()
        var trailing: String? = null

        while (i < line.length) {
            while (i < line.length && line[i] == ' ') i++
            if (i >= line.length) break
            if (line[i] == ':') { trailing = line.substring(i + 1); break }
            val next = line.indexOf(' ', i).let { if (it == -1) line.length else it }
            params.add(line.substring(i, next))
            i = next
        }

        return IrcMessage(tags, prefix, command, params, trailing)
    }

    private fun unescapeTagValue(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    ':' -> out.append(';')
                    's' -> out.append(' ')
                    '\\' -> out.append('\\')
                    'r' -> out.append('\r')
                    'n' -> out.append('\n')
                    else -> out.append(n)
                }
                i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
