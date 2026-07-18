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
class HexBlock(
    val kind: Kind,
    val name: String,        // event key (e.g. "TEXT", "SIGNAL:TR_DONE") or alias name
    val filter: String?,     // optional glob filter for `on TEXT:*help*`
    val body: List<HexStmt>,
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
                "" -> {}
                else -> throw HexError("line ${lineAt(pos)}: expected 'on' or 'alias', got '$kw'")
            }
            skipWs()
        }
        return blocks
    }

    private fun parseEvent(): HexBlock {
        skipWs()
        val header = readWord()                     // e.g. TEXT, ACTION, SIGNAL:tr_done, TEXT:*help*
        val body = parseBraceBody()
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
