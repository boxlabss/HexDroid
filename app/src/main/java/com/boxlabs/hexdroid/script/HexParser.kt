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

package com.boxlabs.hexdroid.script

/** A top-level .hex block: an event handler or an alias. The opaque handler token the
 *  engine stores in its registry IS one of these. */
/** The start of an mIRC event header: a level, then the event name. */
private val MIRC_HEAD = Regex("^[@!+&^=$]*(\\*|\\d+)?:[A-Za-z]+(:|$)")

/** An mIRC access level: `*`, a number, or either with prefixes such as `@` and `!`. */
private val MIRC_LEVEL = Regex("^[@!+&^=$]*(\\*|\\d+)?$")

class HexBlock(
    val kind: Kind,
    val name: String,        // event key (e.g. "TEXT", "SIGNAL:TR_DONE") or alias name
    val filter: String?,     // optional glob filter for `on TEXT:*help*`
    val body: List<HexStmt>,
    /** mIRC-style target: `#` channels, `?` private, or a comma list of channel globs. */
    val target: String? = null,
    /** Match text given as a regex (mIRC's `$` prefix); used instead of [filter] when set. */
    val regex: Regex? = null,
) {
    enum class Kind { EVENT, ALIAS }
}

/** A statement in a block body. */
sealed class HexStmt {
    /** A verb + its raw (un-substituted) argument string. Expanded at execution. */
    class Command(val verb: String, val rawArgs: String) : HexStmt()
    /** if / elseif* / else? — branches are (conditionExpr, body); elseBody may be null. */
    class If(val branches: List<Pair<String, List<HexStmt>>>, val elseBody: List<HexStmt>?) : HexStmt()
    class While(val cond: String, val body: List<HexStmt>) : HexStmt()
    /** `foreach %item %coll { ... }` — iterate a list (items) or map (keys). */
    class ForEach(val itemVar: String, val collExpr: String, val body: List<HexStmt>) : HexStmt()
    /** A `view { ... }` block: raw body, expanded then parsed to a ScriptView at run time. */
    class ViewStmt(val rawBody: String) : HexStmt()
    object Break : HexStmt()
    object Continue : HexStmt()
    object Halt : HexStmt()
    class Return(val expr: String?) : HexStmt()
}

/** Parse-time error (shared by parser + backend). */
internal class HexError(message: String) : RuntimeException(message)

/**
 * .hex parser: source -> List<HexBlock>. Line-oriented with `{ }` grouping. Comments
 * (`;` to end of line) are stripped first. Statements within a body are separated by
 * newline or top-level `|`; control structures consume balanced `( )` and `{ }`.
 */
class HexParser(source: String) {

    // Strip comments line-by-line (`;` to EOL), then work on one flat string.
    private val src: String = source.lineSequence()
        .joinToString("\n") { line -> val i = line.indexOf(';'); if (i >= 0) line.substring(0, i) else line }
    private var pos = 0

    /** 1-based source line containing offset [p] (comments are blanked but newlines preserved). */
    private fun lineAt(p: Int): Int = src.take(p.coerceIn(0, src.length)).count { it == '\n' } + 1

    fun parse(): List<HexBlock> {
        val blocks = ArrayList<HexBlock>()
        skipWs()
        while (pos < src.length) {
            val kw = readWord()
            when (kw) {
                "on" -> blocks.add(parseEvent())
                "alias" -> blocks.add(parseAlias())
                // A brace outside any block. readWord stops in front of it without moving.
                "" -> throw HexError("line ${lineAt(pos)}: unexpected '${src[pos]}' outside a block")
                else -> throw HexError("line ${lineAt(pos)}: expected 'on' or 'alias', got '$kw'")
            }
            skipWs()
        }
        return blocks
    }

    private fun parseEvent(): HexBlock {
        skipWs()
        skipWs()
        val headerStart = pos
        var header = readWord()                     // e.g. TEXT, ACTION, SIGNAL:tr_done, TEXT:*help*
        // An mIRC header's match text may contain spaces (`on *:TEXT:!seen *:#:{`), so it runs to
        // the `:{` that opens the body, or to the first `{` on the line.
        if (MIRC_HEAD.containsMatchIn(header) && (pos >= src.length || src[pos] != '{')) {
            val eol = src.indexOf('\n', headerStart).let { if (it < 0) src.length else it }
            val colonBrace = src.indexOf(":{", headerStart).takeIf { it in headerStart until eol }
            val end = if (colonBrace != null) colonBrace + 1
                else src.indexOf('{', headerStart).takeIf { it in headerStart until eol }
            if (end != null && end > pos) {
                header = src.substring(headerStart, end).trim()
                pos = end
            }
        }
        val body = parseBraceBody()
        mircHeader(header, body)?.let { return it }
        val colon = header.indexOf(':')
        return if (colon < 0) {
            HexBlock(HexBlock.Kind.EVENT, header.uppercase(), null, body)
        } else {
            val head = header.substring(0, colon).uppercase()
            val rest = header.substring(colon + 1)
            if (head == "SIGNAL") HexBlock(HexBlock.Kind.EVENT, "SIGNAL:${rest.uppercase()}", null, body)
            else HexBlock(HexBlock.Kind.EVENT, head, rest, body)     // rest = glob filter
        }
    }

    private fun parseAlias(): HexBlock {
        skipWs()
        val name = readWord()
        val body = parseBraceBody()
        return HexBlock(HexBlock.Kind.ALIAS, name, null, body)
    }

    /** Expect `{ ... }`, return the parsed statement list. */
    private fun parseBraceBody(): List<HexStmt> {
        skipWs()
        if (pos >= src.length || src[pos] != '{') {
            val near = src.substring(pos.coerceAtMost(src.length), (pos + 40).coerceAtMost(src.length)).replace("\n", "⏎")
            throw HexError("line ${lineAt(pos)}: expected '{' to open a block, near: \"$near\"")
        }
        val openPos = pos
        val inner = readBalanced('{', '}')
        return parseStmts(inner, lineAt(openPos + 1))
    }

    private fun parseStmts(body: String, baseLine: Int = 1): List<HexStmt> {
        val sc = StmtScanner(body, baseLine)
        val out = ArrayList<HexStmt>()
        while (true) {
            sc.skipSep()
            if (sc.atEnd()) break
            val w = sc.peekWord()
            when (w) {
                "if" -> out.add(parseIf(sc))
                "while" -> { sc.readWord(); val cond = sc.readParens(); val b = sc.readBraces(); out.add(HexStmt.While(cond, parseStmts(b, sc.lastGroupBaseLine))) }
                "view" -> { sc.readWord(); out.add(HexStmt.ViewStmt(sc.readBraces())) }
                "foreach" -> { sc.readWord(); val item = sc.readToken(); val coll = sc.readToken(); val fb = sc.readBraces(); out.add(HexStmt.ForEach(item, coll, parseStmts(fb, sc.lastGroupBaseLine))) }
                "break" -> { sc.readWord(); out.add(HexStmt.Break) }
                "continue" -> { sc.readWord(); out.add(HexStmt.Continue) }
                "halt" -> { sc.readWord(); out.add(HexStmt.Halt) }
                "return" -> { sc.readWord(); val e = sc.readRest(); out.add(HexStmt.Return(if (e.isBlank()) null else e)) }
                else -> { val (verb, rest) = sc.readCommand(); if (verb.isNotEmpty()) out.add(HexStmt.Command(verb, rest)) }
            }
        }
        return out
    }

    private fun parseIf(sc: StmtScanner): HexStmt {
        sc.readWord()                                   // 'if'
        val branches = ArrayList<Pair<String, List<HexStmt>>>()
        run { val c = sc.readParens(); val b = sc.readBraces(); branches.add(c to parseStmts(b, sc.lastGroupBaseLine)) }
        var elseBody: List<HexStmt>? = null
        while (true) {
            sc.skipSepNoNewlineConsumeOk()
            when (sc.peekWord()) {
                "elseif" -> { sc.readWord(); val c = sc.readParens(); val b = sc.readBraces(); branches.add(c to parseStmts(b, sc.lastGroupBaseLine)) }
                "else" -> { sc.readWord(); val b = sc.readBraces(); elseBody = parseStmts(b, sc.lastGroupBaseLine); break }
                else -> break
            }
        }
        return HexStmt.If(branches, elseBody)
    }

    // ---- top-level scanners (over the whole source) ----

    private fun skipWs() { while (pos < src.length && src[pos].isWhitespace()) pos++ }

    /**
     * The mIRC header form `<level>:<EVENT>:<match>:<target>:`, for scripts pasted from mIRC. The
     * level, and any prefix such as `@` or `!`, is accepted and ignored; HexDroid has no user
     * levels. For TEXT, ACTION and NOTICE the match text is everything between the event and the
     * last field (so it may contain colons) and the last field is the target. Channel events take
     * the target as their channel list. Null when [header] isn't in this form, so the native
     * `on EVENT:filter` is parsed as before.
     */
    private fun mircHeader(header: String, body: List<HexStmt>): HexBlock? {
        val h = header.removeSuffix(":")
        val first = h.indexOf(':')
        if (first < 0) return null
        if (!MIRC_LEVEL.matches(h.substring(0, first))) return null
        val rest = h.substring(first + 1)
        val second = rest.indexOf(':')
        val event = (if (second < 0) rest else rest.substring(0, second)).uppercase()
        if (event.isEmpty() || !event.all { it.isLetter() }) return null
        val after = if (second < 0) "" else rest.substring(second + 1)
        fun field(s: String?) = s?.takeIf { it.isNotEmpty() && it != "*" }
        val regexMatch = '$' in h.substring(0, first)
        return when (event) {
            "TEXT", "ACTION", "NOTICE" -> {
                val last = after.lastIndexOf(':')
                val match = if (last < 0) after else after.substring(0, last)
                val target = if (last < 0) null else after.substring(last + 1)
                val rx = if (regexMatch) mircRegex(match) else null
                HexBlock(HexBlock.Kind.EVENT, event, if (rx != null) null else field(match), body, field(target), rx)
            }
            // mIRC names signals in the match field, and START is its load event.
            "SIGNAL" -> HexBlock(HexBlock.Kind.EVENT, "SIGNAL:${after.substringBefore(':').uppercase()}", null, body)
            "START" -> HexBlock(HexBlock.Kind.EVENT, "LOAD", null, body)
            "JOIN", "PART", "KICK", "MODE" -> HexBlock(HexBlock.Kind.EVENT, event, null, body, field(after))
            else -> HexBlock(HexBlock.Kind.EVENT, event, null, body)
        }
    }

    /**
     * mIRC regex match text, `/pattern/flags`, with flags i, m, s and x; others are ignored.
     * Null when [match] isn't in that form.
     */
    private fun mircRegex(match: String): Regex? {
        if (!match.startsWith("/")) return null
        val end = match.lastIndexOf('/')
        if (end <= 0) return null
        val opts = mutableSetOf<RegexOption>()
        for (c in match.substring(end + 1)) when (c) {
            'i' -> opts += RegexOption.IGNORE_CASE
            'm' -> opts += RegexOption.MULTILINE
            's' -> opts += RegexOption.DOT_MATCHES_ALL
            'x' -> opts += RegexOption.COMMENTS
        }
        return try {
            Regex(match.substring(1, end), opts)
        } catch (e: Exception) {
            throw HexError("bad regex in event header: ${e.message}")
        }
    }

    private fun readWord(): String {
        skipWs()
        val start = pos
        while (pos < src.length && !src[pos].isWhitespace() && src[pos] != '{' && src[pos] != '}') pos++
        return src.substring(start, pos)
    }

    private fun readBalanced(open: Char, close: Char): String {
        // assumes src[pos] == open
        var depth = 0; val start = pos
        while (pos < src.length) {
            val c = src[pos]
            if (c == open) depth++ else if (c == close) { depth--; if (depth == 0) { pos++; return src.substring(start + 1, pos - 1) } }
            pos++
        }
        throw HexError("line ${lineAt(start)}: unbalanced '$open$close' (opened here, never closed)")
    }

    /** Scanner for the statements inside a single body string. */
    private class StmtScanner(private val s: String, private val baseLine: Int = 1) {
        private var i = 0
        /** File line of the inner start of the most recent readBraces/readParens group. */
        var lastGroupBaseLine = baseLine; private set
        /** 1-based file line at the current scan position. */
        fun curLine(): Int = baseLine + s.take(i).count { it == '\n' }
        fun atEnd(): Boolean = i >= s.length

        /** Skip statement separators: whitespace, newlines, '|'. */
        fun skipSep() { while (i < s.length && (s[i].isWhitespace() || s[i] == '|')) i++ }
        /** Like skipSep but used between if-chain parts. */
        fun skipSepNoNewlineConsumeOk() { while (i < s.length && (s[i].isWhitespace() || s[i] == '|')) i++ }

        fun peekWord(): String {
            var j = i
            while (j < s.length && s[j].isWhitespace()) j++
            val start = j
            while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
            return s.substring(start, j)
        }

        fun readWord(): String {
            skipSpacesOnly()
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
            return s.substring(start, i)
        }

        /** A whitespace-delimited token, sigil-aware (reads `%fp`, `$list(2,3)`, `$1-`). */
        fun readToken(): String {
            skipSpacesOnly()
            val start = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '{' && s[i] != '|') i++
            return s.substring(start, i)
        }

        /** Read a `( ... )` group (returns inner, trims). */
        fun readParens(): String = readGroup('(', ')')
        /** Read a `{ ... }` group (returns inner). */
        fun readBraces(): String = readGroup('{', '}')

        private fun readGroup(open: Char, close: Char): String {
            skipSpacesOnly()
            if (i >= s.length || s[i] != open) throw HexError("line ${curLine()}: expected '$open' near: \"" + s.substring(i.coerceAtMost(s.length), (i + 40).coerceAtMost(s.length)).replace("\n", "⏎") + "\"")
            lastGroupBaseLine = baseLine + s.take(i + 1).count { it == '\n' }
            var depth = 0; val start = i
            while (i < s.length) {
                val c = s[i]
                if (c == open) depth++ else if (c == close) { depth--; if (depth == 0) { i++; return s.substring(start + 1, i - 1).trim() } }
                i++
            }
            throw HexError("line ${baseLine + s.take(start).count { it == '\n' }}: unbalanced '$open$close' (opened here, never closed)")
        }

        /** Read the remainder of the statement (until newline or '|'), e.g. a return expr. */
        fun readRest(): String {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
            val start = i
            while (i < s.length && s[i] != '\n' && s[i] != '|') i++
            return s.substring(start, i).trim()
        }

        /** A command: verb + remainder of the physical line (until newline or top-level '|'). */
        fun readCommand(): Pair<String, String> {
            skipSpacesOnly()
            val vs = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '|') i++
            val verb = s.substring(vs, i)
            // rest = up to newline or top-level '|'
            while (i < s.length && s[i] == ' ') i++           // single spaces after verb
            val rs = i
            while (i < s.length && s[i] != '\n' && s[i] != '|') i++
            return verb to s.substring(rs, i).trim()
        }

        private fun skipSpacesOnly() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }
    }
}
