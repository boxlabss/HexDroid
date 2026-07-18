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

sealed class HexVal {
    data class Str(val s: String) : HexVal()
    data class Lst(val items: MutableList<HexVal> = mutableListOf()) : HexVal()
    data class Mp(val map: LinkedHashMap<String, HexVal> = LinkedHashMap()) : HexVal()

    fun asStr(): String = when (this) {
        is Str -> s
        is Lst -> items.joinToString(" ") { it.asStr() }
        is Mp -> map.entries.joinToString(" ") { "${it.key}=${it.value.asStr()}" }
    }

    fun asNum(): Double = when (this) {
        is Str -> s.trim().toDoubleOrNull() ?: 0.0
        is Lst -> items.size.toDouble()
        is Mp -> map.size.toDouble()
    }

    companion object {
        val EMPTY = Str("")
        fun of(s: String) = Str(s)
        /** Trim a trailing .0 so integer arithmetic reads as an integer. */
        fun num(d: Double): Str = Str(if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString())
    }
}

/** Pure operations on [HexVal]s; `$id()`. */
object HexValues {
    fun list(items: List<HexVal>) = HexVal.Lst(items.toMutableList())
    fun map(pairs: List<Pair<String, HexVal>>) = HexVal.Mp(LinkedHashMap<String, HexVal>().apply { pairs.forEach { put(it.first, it.second) } })

    fun get(coll: HexVal, key: HexVal): HexVal = when (coll) {
        is HexVal.Lst -> coll.items.getOrNull(key.asNum().toInt()) ?: HexVal.EMPTY
        is HexVal.Mp -> coll.map[key.asStr()] ?: HexVal.EMPTY
        else -> HexVal.EMPTY
    }

    fun len(coll: HexVal): Int = when (coll) {
        is HexVal.Lst -> coll.items.size
        is HexVal.Mp -> coll.map.size
        is HexVal.Str -> coll.s.length
    }

    fun keys(coll: HexVal): HexVal.Lst = when (coll) {
        is HexVal.Mp -> HexVal.Lst(coll.map.keys.map { HexVal.Str(it) as HexVal }.toMutableList())
        else -> HexVal.Lst()
    }

    fun has(coll: HexVal, key: HexVal): Boolean = when (coll) {
        is HexVal.Mp -> coll.map.containsKey(key.asStr())
        is HexVal.Lst -> coll.items.any { it.asStr() == key.asStr() }
        else -> false
    }

    fun push(list: HexVal, v: HexVal) { if (list is HexVal.Lst) list.items.add(v) }

    fun setAt(coll: HexVal, key: HexVal, v: HexVal) {
        when (coll) {
            is HexVal.Lst -> { val i = key.asNum().toInt(); if (i in 0..coll.items.size) { if (i == coll.items.size) coll.items.add(v) else coll.items[i] = v } }
            is HexVal.Mp -> coll.map[key.asStr()] = v
            else -> {}
        }
    }

    fun sort(coll: HexVal): HexVal.Lst {
        if (coll !is HexVal.Lst) return HexVal.Lst()
        val numeric = coll.items.all { it.asStr().toDoubleOrNull() != null }
        val sorted = if (numeric) coll.items.sortedBy { it.asNum() } else coll.items.sortedBy { it.asStr() }
        return HexVal.Lst(sorted.toMutableList())
    }

    fun join(coll: HexVal, sep: String): String =
        if (coll is HexVal.Lst) coll.items.joinToString(sep) { it.asStr() } else coll.asStr()

    fun split(s: String, sep: String): HexVal.Lst =
        HexVal.Lst((if (sep.isEmpty()) s.map { it.toString() } else s.split(sep)).map { HexVal.Str(it) as HexVal }.toMutableList())

    fun slice(coll: HexVal, from: Int, to: Int): HexVal.Lst {
        if (coll !is HexVal.Lst) return HexVal.Lst()
        val a = from.coerceIn(0, coll.items.size); val b = to.coerceIn(a, coll.items.size)
        return HexVal.Lst(coll.items.subList(a, b).toMutableList())
    }

    // ---- pure value-semantics collection derivations ----
    // These never mutate their arguments and return a value; in-place mutation is the
    // push/setat statements. concat doubles as copy-on-write append:
    // `"set %deck $concat(%deck, %card)" extends %deck without aliasing surprises.

    /** Concatenate lists/map-values/scalars into one new list. */
    fun concat(parts: List<HexVal>): HexVal.Lst {
        val out = ArrayList<HexVal>()
        for (p in parts) when (p) {
            is HexVal.Lst -> out.addAll(p.items)
            is HexVal.Mp -> out.addAll(p.map.values)
            else -> out.add(p)
        }
        return HexVal.Lst(out)
    }

    /** New list with a list's items reversed. */
    fun reversed(coll: HexVal): HexVal.Lst =
        if (coll is HexVal.Lst) HexVal.Lst(coll.items.reversed().toMutableList()) else HexVal.Lst()

    /** First index of [v] (by string value) in a list, or -1. */
    fun find(coll: HexVal, v: HexVal): Int =
        if (coll is HexVal.Lst) coll.items.indexOfFirst { it.asStr() == v.asStr() } else -1

    /** Occurrences of [v] in a list (by string), or 1/0 for a map key. */
    fun count(coll: HexVal, v: HexVal): Int = when (coll) {
        is HexVal.Lst -> coll.items.count { it.asStr() == v.asStr() }
        is HexVal.Mp -> if (coll.map.containsKey(v.asStr())) 1 else 0
        else -> 0
    }

    /** Numeric sum of a list's items (non-numbers contribute 0). */
    fun sum(coll: HexVal): Double =
        if (coll is HexVal.Lst) coll.items.sumOf { it.asNum() } else coll.asNum()

    /** Arithmetic: + - * / % with precedence and parens. Returns a number. */
    fun calc(expr: String): Double = CalcParser(expr).parse()

    private class CalcParser(private val s: String) {
        private var i = 0
        fun parse(): Double { val v = expr(); return v }
        private fun expr(): Double {            // + -
            var v = term()
            while (true) { skip(); when (peek()) { '+' -> { i++; v += term() }; '-' -> { i++; v -= term() }; else -> return v } }
        }
        private fun term(): Double {            // * / %
            var v = factor()
            while (true) {
                skip()
                when (peek()) {
                    '*' -> { i++; v *= factor() }
                    '/' -> { i++; val d = factor(); v = if (d == 0.0) 0.0 else v / d }
                    '%' -> { i++; val d = factor(); v = if (d == 0.0) 0.0 else v % d }
                    else -> return v
                }
            }
        }
        private fun factor(): Double {
            skip()
            if (peek() == '(') { i++; val v = expr(); skip(); if (peek() == ')') i++; return v }
            if (peek() == '-') { i++; return -factor() }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDoubleOrNull() ?: 0.0
        }
        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        private fun skip() { while (i < s.length && s[i] == ' ') i++ }
    }
}
