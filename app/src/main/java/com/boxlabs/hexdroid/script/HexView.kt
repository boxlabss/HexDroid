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

/**
 * A generic, immutable view-tree that a script emits and the client's ONE renderer
 * paints (see ScriptSurface).
 */
sealed class ScriptView {
    abstract val props: ViewProps

    data class Column(val children: List<ScriptView>, override val props: ViewProps = ViewProps()) : ScriptView()
    data class Row(val children: List<ScriptView>, override val props: ViewProps = ViewProps()) : ScriptView()
    /** Overlapping stack (z-order); children aligned per their props.align, offset by props.offset*. */
    data class Stack(val children: List<ScriptView>, override val props: ViewProps = ViewProps()) : ScriptView()
    /** Arrange children evenly around an ellipse (child 0 at the bottom). General radial layout —
     *  players around a table, a radial menu, a clock. props.sizeDp = horizontal radius. */
    data class Ring(val children: List<ScriptView>, override val props: ViewProps = ViewProps()) : ScriptView()
    /** A rounded container with a background (props.bg) — panels, pills, table felt. */
    data class Surface(val child: ScriptView?, override val props: ViewProps = ViewProps()) : ScriptView()
    data class Text(val text: String, override val props: ViewProps = ViewProps()) : ScriptView()
    data class Button(val label: String, val actionId: String, val args: List<String>, override val props: ViewProps = ViewProps()) : ScriptView()
    /** Convenience playing-card primitive (common enough across card games to be worth one node). */
    data class Card(val face: String, val red: Boolean, val back: Boolean = false, override val props: ViewProps = ViewProps()) : ScriptView()
    data class Image(val url: String, override val props: ViewProps = ViewProps()) : ScriptView()
    data class Spacer(override val props: ViewProps = ViewProps()) : ScriptView()
}

/** Styling. All null/zero = renderer default. Keeps the tree terse. */
data class ViewProps(
    val sizeDp: Int? = null,        // element size (spacer size, image/card width)
    val textSp: Int? = null,        // text size
    val bold: Boolean = false,
    val color: String? = null,      // "#rrggbb" / "#aarrggbb"
    val bg: String? = null,         // surface background hex
    val weight: Float? = null,      // row/column weight
    val padDp: Int = 0,
    val align: String? = null,      // "center","start","end","bottom","top"
    val fillWidth: Boolean = false,
    val wrap: Boolean = false,      // row: flow children onto extra lines instead of overflowing
    val gapDp: Int = 0,             // spacing between row/column children
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val offsetXDp: Int = 0,         // absolute offset inside a Stack
    val offsetYDp: Int = 0,
    val circle: Boolean = false,    // circular shape (avatars)
    val borderColor: String? = null,
    val borderWidthDp: Int = 2,     // used when borderColor is set (to-act ring, outlines)
    val gradient: String? = null,   // "radial:#aarrggbb:#aarrggbb" or "linear:#..:#.." (felt, rail)
    val elevationDp: Int = 0,       // drop shadow (cards/pods lifting off the felt)
    val scale: String? = null,      // image content scale: "crop" (default) | "fit" | "stretch"
    val ellipse: Boolean = false,   // ellipse/oval shape (table felt) instead of a rounded rect
    val bgImage: String? = null,    // surface background image URL, painted behind the children
    /**
     * ring: vertical squash, as a multiple of the horizontal radius. 1.0 (the default) is a true
     * circle; 0.62 reproduces the flattened ellipse .
     *
     * A float, like [weight], because it is a unitless multiplier. Ints are for dp measurements;
     * a ratio is not a measurement. Worth tuning on device: a flatter ring buys vertical room but
     * pushes the left and right children together, and they overlap once the squash drops below
     * their own height.
     */
    val ratio: Float? = null,
    /**
     * ring: lay children out on the perimeter of a stadium (a rounded rect with semicircular
     * caps) instead of an ellipse. ratio picks the axis: >= 1 is a VERTICAL capsule (caps top
     * and bottom, seats flanking the straight sides - the portrait poker table), < 1 is a
     * HORIZONTAL capsule (caps left and right, seats along the top edge - the landscape/TV
     * table). Child 0 (the hero) stays anchored at the bottom either way, and the renderer
     * grows the table as the seat count does so seats can never overlap.
     */
    val stadium: Boolean = false,
    /**
     * ring child only: this child is the table FELT, not a seat. The renderer sizes it to the
     * exact path the seats sit on (2rx by 2ry) and centres it, so seats straddle its rail by
     * construction. The script keeps authoring the felt's shape, colours, and inner content
     * (pot, board); it just stops guessing the size.
     */
    val felt: Boolean = false,
)

/**
 * Parses the body of a .hex `view { ... }` block into a [ScriptView] tree. Runs AFTER
 * the backend has substituted $/% into the body, so values are already concrete.
 *
 * DSL (quote-aware, brace-grouped):
 *   column [<mods>] { ... }      row [<mods>] { ... }     stack [<mods>] { ... }
 *   surface [<mods>] { ... }
 *   text "<string>" [<mods>]
 *   button "<label>" <actionId> [<arg>...]
 *   card "<face>" [red] [<mods>]
 *   image <url> [<mods>]
 *   spacer [<mods>]
 * mods: bold | size <n> | textsize <n> | color #hex | bg #hex | weight <f> | pad <n>
 *       | align <center|start|end|top|bottom> | fill | gap <n>
 */
class HexViewParser(body: String) {
    private val s = body
    private var i = 0

    fun parse(): ScriptView {
        val items = parseItems()
        return if (items.size == 1) items[0] else ScriptView.Column(items)
    }

    private fun parseItems(): List<ScriptView> {
        val out = ArrayList<ScriptView>()
        while (true) {
            skipSep()
            if (i >= s.length || s[i] == '}') break
            out.add(parseElement())
        }
        return out
    }

    private fun parseElement(): ScriptView {
        val kind = readWord()
        return when (kind) {
            "column", "row", "stack", "ring", "surface" -> {
                val mods = readModsUntilBraceOrEnd()
                val body = if (peek() == '{') readBraced() else ""
                val children = HexViewParser(body).parseItems()
                when (kind) {
                    "column" -> ScriptView.Column(children, mods)
                    "row" -> ScriptView.Row(children, mods)
                    "stack" -> ScriptView.Stack(children, mods)
                    "ring" -> ScriptView.Ring(children, mods)
                    else -> ScriptView.Surface(children.firstOrNull() ?: ScriptView.Column(children), mods)
                }
            }
            "text" -> { val t = readArg(); ScriptView.Text(t, readMods()) }
            "button" -> {
                val label = readArg(); val toks = readTokens()
                val actionId = toks.firstOrNull() ?: ""
                val (args, props) = splitButtonTokens(toks.drop(1))
                ScriptView.Button(label, actionId, args, props)
            }
            "card" -> { val face = readArg(); val toks = readTokens(); ScriptView.Card(face, toks.contains("red"), toks.contains("back"), modsFrom(toks)) }
            "image" -> { val url = readArg(); ScriptView.Image(url, readMods()) }
            "spacer" -> ScriptView.Spacer(readMods())
            else -> ScriptView.Text(kind, ViewProps())   // tolerate stray words as text
        }
    }

    // ---- token/arg reading ----

    private fun readArg(): String {
        skipSpaces()
        if (peek() == '"') {
            i++; val start = i
            while (i < s.length && s[i] != '"') { if (s[i] == '\\') i++; i++ }
            val v = s.substring(start, i); if (i < s.length) i++
            return v.replace("\\\"", "\"")
        }
        return readWord()
    }

    private fun readTokens(): List<String> {
        val out = ArrayList<String>()
        while (true) {
            skipSpaces()
            if (i >= s.length || s[i] == '\n' || s[i] == '|' || s[i] == '{' || s[i] == '}') break
            out.add(if (peek() == '"') readArg() else readWord())
        }
        return out
    }

    private fun readMods(): ViewProps = modsFrom(readTokens())
    private fun readModsUntilBraceOrEnd(): ViewProps {
        val toks = ArrayList<String>()
        while (true) { skipSpaces(); if (i >= s.length || s[i] == '{' || s[i] == '\n' || s[i] == '}') break; toks.add(readWord()) }
        return modsFrom(toks)
    }

    private fun modsFrom(toks: List<String>): ViewProps {
        var p = ViewProps()
        var k = 0
        while (k < toks.size) {
            when (toks[k]) {
                "bold" -> p = p.copy(bold = true)
                "fill" -> p = p.copy(fillWidth = true)
                "wrap" -> p = p.copy(wrap = true)
                "circle" -> p = p.copy(circle = true)
                "ellipse" -> p = p.copy(ellipse = true)
                "stadium" -> p = p.copy(stadium = true)
                "felt" -> p = p.copy(felt = true)
                "crop" -> p = p.copy(scale = "crop")
                "fit" -> p = p.copy(scale = "fit")
                "stretch" -> p = p.copy(scale = "stretch")
                "red", "back" -> {}
                "radius" -> { p = p.copy(sizeDp = toks.getOrNull(++k)?.toIntOrNull()) }
                "width" -> { p = p.copy(widthDp = toks.getOrNull(++k)?.toIntOrNull()) }
                "height" -> { p = p.copy(heightDp = toks.getOrNull(++k)?.toIntOrNull()) }
                "offsetx" -> { p = p.copy(offsetXDp = toks.getOrNull(++k)?.toIntOrNull() ?: 0) }
                "offsety" -> { p = p.copy(offsetYDp = toks.getOrNull(++k)?.toIntOrNull() ?: 0) }
                "border" -> { p = p.copy(borderColor = toks.getOrNull(++k)); if (toks.getOrNull(k + 1)?.toIntOrNull() != null) p = p.copy(borderWidthDp = toks[++k].toInt()) }
                "size" -> { p = p.copy(sizeDp = toks.getOrNull(++k)?.toIntOrNull()) }
                // ring only: vertical squash as a multiple of the horizontal radius (1.0 = circle).
                "ratio" -> { p = p.copy(ratio = toks.getOrNull(++k)?.toFloatOrNull()) }
                "textsize" -> { p = p.copy(textSp = toks.getOrNull(++k)?.toIntOrNull()) }
                "color" -> { p = p.copy(color = toks.getOrNull(++k)) }
                "bg" -> { p = p.copy(bg = toks.getOrNull(++k)) }
                "bgimage" -> { p = p.copy(bgImage = toks.getOrNull(++k)) }
                "weight" -> { p = p.copy(weight = toks.getOrNull(++k)?.toFloatOrNull()) }
                "pad" -> { p = p.copy(padDp = toks.getOrNull(++k)?.toIntOrNull() ?: 0) }
                "gap" -> { p = p.copy(gapDp = toks.getOrNull(++k)?.toIntOrNull() ?: 0) }
                "align" -> { p = p.copy(align = toks.getOrNull(++k)) }
                "gradient" -> { p = p.copy(gradient = toks.getOrNull(++k)) }
                "elevation", "elev" -> { p = p.copy(elevationDp = toks.getOrNull(++k)?.toIntOrNull() ?: 0) }
            }
            k++
        }
        return p
    }

    /**
     * Split a button's post-actionId tokens into positional args (passed to the action,
     * e.g. mediapreview's `button "Open" mp_open $1`) and view mods (e.g. poker's
     * `button "FOLD" poker_fold weight 1`). Recognised mod keywords (and their value, if
     * any) become [ViewProps]; everything else stays a positional arg.
     */
    private fun splitButtonTokens(toks: List<String>): Pair<List<String>, ViewProps> {
        val flagMods = setOf("bold", "fill", "circle", "red", "back", "ellipse", "crop", "fit", "stretch")
        val valueMods = setOf("radius", "width", "height", "offsetx", "offsety",
            "size", "textsize", "color", "bg", "bgimage", "weight", "pad", "gap", "align", "gradient", "elevation", "elev")
        val modToks = ArrayList<String>()
        val args = ArrayList<String>()
        var k = 0
        while (k < toks.size) {
            val t = toks[k]
            when {
                t in flagMods -> { modToks.add(t); k++ }
                t == "border" -> {
                    modToks.add(t)
                    if (k + 1 < toks.size) { modToks.add(toks[k + 1]); k++ }
                    if (k + 1 < toks.size && toks[k + 1].toIntOrNull() != null) { modToks.add(toks[k + 1]); k++ }
                    k++
                }
                t in valueMods -> {
                    modToks.add(t)
                    if (k + 1 < toks.size) { modToks.add(toks[k + 1]); k++ }
                    k++
                }
                else -> { args.add(t); k++ }
            }
        }
        return args to modsFrom(modToks)
    }

    // ---- scanning ----

    private fun peek(): Char { skipSpaces(); return if (i < s.length) s[i] else '\u0000' }
    private fun skipSpaces() { while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++ }
    private fun skipSep() { while (i < s.length && (s[i].isWhitespace() || s[i] == '|')) i++ }

    private fun readWord(): String {
        skipSpaces(); val start = i
        while (i < s.length && !s[i].isWhitespace() && s[i] != '{' && s[i] != '}' && s[i] != '|') i++
        return s.substring(start, i)
    }

    private fun readBraced(): String {
        // assumes peek()=='{'
        skipSpaces()
        var depth = 0; val start = i; var inStr = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) { if (c == '\\') i++ else if (c == '"') inStr = false }
            else when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) { i++; return s.substring(start + 1, i - 1) } } }
            i++
        }
        return s.substring(start + 1)
    }
}

/**
 * Pure geometry for ScriptView.Ring, kept Android-free so it can be unit-tested off-device.
 * All functions take/return px as Double; [childW]/[childH] are the widest/tallest SEAT child.
 *
 * Three modes:
 *  - ellipse (stadium=false): equal-angle walk starting at the bottom - the historical
 *    behaviour, unchanged, child 0 at the bottom.
 *  - vertical stadium (stadium=true, ry >= rx): hero (child 0) on the bottom cap; opponents
 *    fill slots on the two straight sides (bottom-to-top on the right, then top-to-bottom on
 *    the left), with an odd opponent taking the top-cap apex. Slots are spaced a full seat
 *    apart, and [stadiumRequiredRy] grows ry so they always fit.
 *  - horizontal stadium (stadium=true, ry < rx): hero on the bottom edge; opponents evenly
 *    spaced along the top edge, facing the hero across the felt.
 */
fun ringOffsets(n: Int, rx: Double, ry: Double, stadium: Boolean, childW: Double = 0.0, childH: Double = 0.0): List<Pair<Double, Double>> {
    if (n <= 0) return emptyList()
    if (!stadium) {
        return (0 until n).map { i ->
            val a = Math.toRadians(90.0 + i * (360.0 / n))
            Pair(Math.cos(a) * rx, Math.sin(a) * ry)
        }
    }
    val out = ArrayList<Pair<Double, Double>>(n)
    out.add(Pair(0.0, ry))                              // hero, bottom-centre
    val m = n - 1
    if (m == 0) return out
    if (ry >= rx) {
        // vertical capsule: k slots per straight side, odd opponent at the top apex.
        // +y is DOWN (screen coords, hero at +ry): right side walks bottom-to-top
        // (y descending), then the apex, then the left side top-to-bottom (y ascending).
        val k = m / 2
        val step = childH + STADIUM_PAD
        val ys = (0 until k).map { i -> (i - (k - 1) / 2.0) * step }
        for (y in ys.sortedDescending()) out.add(Pair(rx, y))
        if (m % 2 == 1) out.add(Pair(0.0, -ry))
        for (y in ys.sorted()) out.add(Pair(-rx, y))
    } else {
        // horizontal capsule: opponents across the top edge, evenly spaced
        val edge = 2.0 * (rx - ry)
        val want = childW + STADIUM_PAD
        val s = if (m > 1) minOf(want, edge / (m - 1)) else 0.0
        for (i in 0 until m) out.add(Pair((i - (m - 1) / 2.0) * s, -ry))
    }
    return out
}

/** Slot spacing margin between stadium seats, px. Keep in sync with nothing: renderer-only. */
const val STADIUM_PAD = 6.0

/**
 * Minimum vertical radius for a VERTICAL stadium so the per-side slot stacks fit the straight
 * sides. Closed form: k = floor((n-1)/2) slots per side spaced (childH + pad) apart, centred
 * on the side, and the outermost slot must stay on the straight segment (|y| <= ry - rx).
 * Returns the largest of that, [ryWant], and rx.
 */
fun stadiumRequiredRy(n: Int, rx: Double, ryWant: Double, childH: Double): Double {
    val k = (n - 1) / 2
    if (k <= 0) return maxOf(ryWant, rx)
    val need = rx + (k - 1) * (childH + STADIUM_PAD) / 2.0
    return maxOf(ryWant, rx, need)
}

/**
 * Minimum horizontal radius for a HORIZONTAL stadium so the top-edge seats sit a full seat
 * apart. The renderer clamps the result to the screen; below the clamp the seats compress
 * evenly (they can kiss on a very narrow landscape at 7-8 players, never stack).
 */
fun stadiumRequiredRx(n: Int, ry: Double, rxWant: Double, childW: Double): Double {
    val m = n - 1
    if (m <= 1) return maxOf(rxWant, ry)
    val need = ry + (m - 1) * (childW + STADIUM_PAD) / 2.0
    return maxOf(rxWant, ry, need)
}
