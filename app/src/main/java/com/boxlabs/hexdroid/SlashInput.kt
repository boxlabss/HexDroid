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

/** Decides whether typed input is a command or text that happens to start with a slash. */
internal object SlashInput {

    /** IRC formatting bytes that may lead the input, apart from the colour codes. */
    private const val FORMAT_CODES = "\u0002\u000f\u0011\u0016\u001d\u001e\u001f"

    private val LEADING_COLOUR = Regex("^\u0003\\d{0,2}(?:,\\d{1,2})?")

    private val LEADING_HEX_COLOUR = Regex("^\u0004(?:[0-9a-fA-F]{6}(?:,[0-9a-fA-F]{6})?)?")

    /** A command verb: letters, digits, '-', '_' and '.'. */
    private val VERB = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$")

    /** [text] without the formatting codes, colour digits included, that may lead it. */
    fun stripLeadingFormatting(text: String): String {
        var s = text
        while (s.isNotEmpty()) {
            s = when {
                s[0] in FORMAT_CODES -> s.substring(1)
                s[0] == '\u0003' -> s.replaceFirst(LEADING_COLOUR, "")
                s[0] == '\u0004' -> s.replaceFirst(LEADING_HEX_COLOUR, "")
                else -> return s
            }
        }
        return s
    }

    /** True when [text] (formatting already stripped) starts with "//", the literal-slash escape. */
    fun isEscaped(text: String): Boolean = text.startsWith("//")

    /** True when [text] (formatting already stripped) is a command such as "/join #a". */
    fun isCommand(text: String): Boolean {
        if (!text.startsWith("/") || isEscaped(text)) return false
        val verb = text.drop(1).substringBefore('\n').substringBefore(' ').substringBefore('\t')
        return VERB.matches(verb)
    }

    /** [raw] with the first slash removed, for the "//" escape. Formatting codes before it are kept. */
    fun unescape(raw: String): String {
        val idx = raw.indexOf('/')
        return if (idx < 0) raw else raw.removeRange(idx, idx + 1)
    }

    /** [text] with "//" in front when it would otherwise run as a command, so it is sent as typed. */
    fun escapeIfCommand(text: String): String {
        if (!isCommand(stripLeadingFormatting(text))) return text
        val idx = text.indexOf('/')
        return text.substring(0, idx) + "/" + text.substring(idx)
    }

    /** The first line of [text] and the remaining lines, or null when there is only one line. */
    fun splitFirstLine(text: String): Pair<String, String?> {
        val normalised = text.replace("\r\n", "\n").replace('\r', '\n')
        val nl = normalised.indexOf('\n')
        if (nl < 0) return normalised to null
        val rest = normalised.substring(nl + 1).trim('\n')
        return normalised.substring(0, nl) to rest.takeIf { it.isNotBlank() }
    }
}
