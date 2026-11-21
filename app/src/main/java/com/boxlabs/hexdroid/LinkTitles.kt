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

import java.util.Locale

/** What a web page says about itself, for the link preview under a message. */
data class LinkTitle(
    val title: String,
    val description: String? = null,
    val siteName: String? = null,
)

/** Reads a page's title, description and site name from the start of its HTML. Pure JVM. */
internal object LinkTitleParser {

    private const val MAX_TITLE = 200
    private const val MAX_DESCRIPTION = 300

    private val META_TAG = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([a-zA-Z:_-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")
    private val TITLE_TAG = Regex("<title\\b[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CHARSET_META = Regex("<meta\\b[^>]*charset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]+)", RegexOption.IGNORE_CASE)
    private val NUMERIC_ENTITY = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
    private val WHITESPACE = Regex("\\s+")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "\u2013", "mdash" to "\u2014", "hellip" to "\u2026", "laquo" to "\u00ab",
        "raquo" to "\u00bb", "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201c",
        "rdquo" to "\u201d", "middot" to "\u00b7", "bull" to "\u2022", "copy" to "\u00a9",
        "reg" to "\u00ae", "trade" to "\u2122",
    )
    private val NAMED_ENTITY = Regex("&([a-zA-Z]+);")

    /** The charset a page names in a meta tag, or null. */
    fun declaredCharset(headAscii: String): String? = CHARSET_META.find(headAscii)?.groupValues?.get(1)

    /** The page's title, description and site name, or null when it has no title. */
    fun parse(html: String): LinkTitle? {
        val meta = HashMap<String, String>()
        for (tag in META_TAG.findAll(html)) {
            val attrs = HashMap<String, String>()
            for (a in ATTR.findAll(tag.value)) {
                val v = a.groupValues[3].ifEmpty { a.groupValues[4] }.ifEmpty { a.groupValues[5] }
                attrs[a.groupValues[1].lowercase(Locale.ROOT)] = v
            }
            val key = (attrs["property"] ?: attrs["name"])?.lowercase(Locale.ROOT) ?: continue
            val content = attrs["content"] ?: continue
            meta.putIfAbsent(key, content)
        }
        val title = sequenceOf(meta["og:title"], meta["twitter:title"], TITLE_TAG.find(html)?.groupValues?.get(1))
            .mapNotNull { it?.let(::clean)?.takeIf { t -> t.isNotEmpty() } }
            .firstOrNull() ?: return null
        val description = sequenceOf(meta["og:description"], meta["twitter:description"], meta["description"])
            .mapNotNull { it?.let(::clean)?.takeIf { t -> t.isNotEmpty() } }
            .firstOrNull()
        val site = meta["og:site_name"]?.let(::clean)?.takeIf { it.isNotEmpty() }
        return LinkTitle(
            title = title.take(MAX_TITLE),
            description = description?.take(MAX_DESCRIPTION)?.takeIf { it != title },
            siteName = site?.take(80)?.takeIf { it != title },
        )
    }

    /** Entities decoded, tags dropped and whitespace collapsed. */
    fun clean(raw: String): String {
        val noTags = raw.replace(Regex("<[^>]*>"), " ")
        val numeric = NUMERIC_ENTITY.replace(noTags) { m ->
            val v = m.groupValues[1]
            val code = if (v.startsWith("x") || v.startsWith("X")) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
            if (code == null || code !in 1..0x10FFFF || code in 0xD800..0xDFFF) "" else String(Character.toChars(code))
        }
        val named = NAMED_ENTITY.replace(numeric) { m -> NAMED_ENTITIES[m.groupValues[1].lowercase(Locale.ROOT)] ?: m.value }
        return named.filter { it >= ' ' || it == '\t' }.replace(WHITESPACE, " ").trim()
    }
}
