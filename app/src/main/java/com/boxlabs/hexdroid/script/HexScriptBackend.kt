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
 *     ScriptEngine(host, HexScriptBackend())
 *
 * See the .hex language spec.
 */
class HexScriptBackend : ScriptBackend {

    override val scriptExtension: String = "hex"

    private lateinit var cb: EngineCallbacks
    private var budget: Budget = Budget(200_000, 200)

    /** Global %vars persist across events within a session; cleared on reset(). */
    private val globals = HashMap<String, HexVal>()
    /** User-defined aliases, kept locally so they can be invoked as value functions $name(...). */
    private val aliases = HashMap<String, HexBlock>()

    override fun start(callbacks: EngineCallbacks, budget: Budget) {
        cb = callbacks; this.budget = budget
    }

    override fun reset() { globals.clear(); aliases.clear() }
    override fun shutdown() { globals.clear() }

    override fun loadScript(name: String, source: String): String? {
        val blocks = try {
            HexParser(source).parse()
        } catch (e: HexError) {
            val msg = "parse error: ${e.message}"; cb.log("[$name] $msg"); return msg
        } catch (e: Throwable) {
            val msg = "load error: ${e.message ?: e.javaClass.simpleName}"; cb.log("[$name] $msg"); return msg
        }
        for (b in blocks) when (b.kind) {
            HexBlock.Kind.EVENT -> cb.registerEvent(b.name, b)              // engine uppercases the key
            HexBlock.Kind.ALIAS -> { aliases[b.name.lowercase()] = b; cb.registerCommand(b.name.lowercase(), b) }
        }
        return null
    }

    // ---- ScriptBackend dispatch --------------------------------------------

    override fun dispatchTransform(handlers: List<Any>, event: EventData): TextResult {
        var text = event.text
        var halted = false
        for (h in handlers) {
            val block = h as? HexBlock ?: continue
            if (!filterMatches(block, text)) continue
            val env = envForEvent(event.copy(text = text), cb)
            val flow = runBody(block.body, env)
            text = env.fields["text"] ?: text         // `rewrite` updates env text
            if (flow == Flow.HALT) { halted = true; break }
        }
        return TextResult(text, halted)
    }

    override fun dispatchNotify(handlers: List<Any>, event: EventData) {
        for (h in handlers) {
            val block = h as? HexBlock ?: continue
            if (!filterMatches(block, event.text)) continue
            runBody(block.body, envForEvent(event, cb))
        }
    }

    override fun runCommand(handler: Any, args: String, network: String?, buffer: String?) {
        val block = handler as? HexBlock ?: return
        val event = EventData(
            network = network ?: "", buffer = buffer ?: "", text = args,
            args = if (args.isBlank()) emptyList() else args.trim().split(Regex("\\s+")),
        )
        runBody(block.body, envForEvent(event, cb))
    }

    private fun filterMatches(block: HexBlock, text: String): Boolean {
        val f = block.filter ?: return true
        return glob(f, text)
    }

    // ---- evaluator ----------------------------------------------------------

    private enum class Flow { NORMAL, BREAK, CONTINUE, HALT, RETURN }

    /** Shared per-top-level-dispatch budget/recursion state (one Frame across nested calls). */
    private inner class Frame {
        var steps = 0
        val deadline = System.currentTimeMillis() + budget.maxMillis
        var depth = 0
    }

    /** Per-call scope. Nested function calls share the [frame] (so one budget bounds the whole tree). */
    private inner class Env(
        val frame: Frame,
        val fields: HashMap<String, String>,
        val args: List<String>,
        val locals: HashMap<String, HexVal>,
    ) {
        var returnValue: HexVal? = null

        fun tick() {
            if (++frame.steps > budget.maxInstructions) throw HexAbort("step budget exceeded")
            if (System.currentTimeMillis() > frame.deadline) throw HexAbort("time budget exceeded")
        }

        /** A child scope for a function call: fresh locals + bound args, shared frame/fields. */
        fun childForCall(callArgs: List<String>): Env = Env(frame, fields, callArgs, HashMap())
    }

    private fun runBody(body: List<HexStmt>, env: Env): Flow {
        for (s in body) {
            env.tick()
            when (s) {
                is HexStmt.Command -> if (execCommand(s, env) == Flow.HALT) return Flow.HALT
                is HexStmt.If -> {
                    var ran = false
                    for ((cond, b) in s.branches) {
                        if (evalCond(cond, env)) { val fl = runBody(b, env); if (fl != Flow.NORMAL) return fl; ran = true; break }
                    }
                    if (!ran && s.elseBody != null) { val fl = runBody(s.elseBody, env); if (fl != Flow.NORMAL) return fl }
                }
                is HexStmt.While -> {
                    while (evalCond(s.cond, env)) {
                        env.tick()
                        when (runBody(s.body, env)) {
                            Flow.BREAK -> break
                            Flow.CONTINUE, Flow.NORMAL -> {}
                            Flow.HALT -> return Flow.HALT
                            Flow.RETURN -> return Flow.RETURN
                        }
                    }
                }
                is HexStmt.ViewStmt -> {
                    val tree = try { HexViewParser(renderViewBody(s.rawBody, env)).parse() }
                    catch (e: Throwable) { cb.log("view error: ${e.message ?: e.javaClass.simpleName}"); null }
                    if (tree != null) cb.mountView(tree)
                }
                is HexStmt.ForEach -> {
                    val coll = evalVal(s.collExpr, env)
                    val items: List<HexVal> = when (coll) {
                        is HexVal.Lst -> coll.items.toList()
                        is HexVal.Mp -> coll.map.keys.map { HexVal.Str(it) }
                        is HexVal.Str -> if (coll.s.isEmpty()) emptyList() else coll.s.split(" ").map { HexVal.Str(it) }
                    }
                    val nm = varName(s.itemVar)
                    loop@ for (it in items) {
                        env.tick(); env.locals[nm] = it
                        when (runBody(s.body, env)) {
                            Flow.BREAK -> break@loop
                            Flow.CONTINUE, Flow.NORMAL -> {}
                            Flow.HALT -> return Flow.HALT
                            Flow.RETURN -> return Flow.RETURN
                        }
                    }
                }
                HexStmt.Break -> return Flow.BREAK
                HexStmt.Continue -> return Flow.CONTINUE
                HexStmt.Halt -> return Flow.HALT
                is HexStmt.Return -> { if (s.expr != null) env.returnValue = evalVal(s.expr, env); return Flow.RETURN }
            }
        }
        return Flow.NORMAL
    }

    /**
     * IRC verbs a script may hand to the slash pipeline. An allowlist.
     *
     * Deliberately excluded: quit/disconnect/connect/server (a script should not be able to drop or
     * redirect the user's connection), and anything that mutates app state or credentials. Those
     * remain reachable through `raw` for a script that genuinely means it, which is at least explicit.
     */
    private val PASSTHROUGH_COMMANDS = setOf(
        "join", "part", "cycle", "topic", "mode", "invite", "kick", "knock",
        "names", "who", "whois", "whowas", "list", "notice", "ctcp", "nick",
        "away", "back", "op", "deop", "voice", "devoice", "ban", "unban", "ignore", "unignore",
    )

    private fun execCommand(c: HexStmt.Command, env: Env): Flow {
        val verb = c.verb.lowercase()
        val raw = c.rawArgs
        when (verb) {
            "set" -> doSet(raw, env)
            "unset" -> { val n = varName(raw.trim().substringBefore(' ')); env.locals.remove(n); globals.remove(n) }
            "inc" -> bumpVar(raw, env, +1)
            "dec" -> bumpVar(raw, env, -1)
            "push" -> { val (nm, rest) = head(raw); varRef(varName(nm), env)?.let { HexValues.push(it, evalVal(rest, env)) } }
            "setat" -> doSetAt(raw, env)
            "rewrite" -> env.fields["text"] = expand(raw, env)
            "echo" -> { val (t, txt) = splitTarget(expand(raw, env)); cb.echo(t, null, txt) }
            "msg" -> { val (t, txt) = splitTarget(expand(raw, env)); if (t != null) cb.sendMessage(t, txt) }
            "raw" -> cb.sendRaw(expand(raw, env))
            "signal" -> { val a = expand(raw, env).trim().split(' '); if (a.isNotEmpty()) cb.raiseEvent("SIGNAL:${a[0].uppercase()}", emptyMap(), a.drop(1)) }
            "timer" -> doTimer(raw, env)
            "log", "debug" -> cb.log(expand(raw, env))
            "toast", "decorate", "action", "sidebar" -> cb.uiIntent(verb, expand(raw, env).trim().split(' '))
            "http.get" -> doHttp(raw, env, post = false)
            "http.post" -> doHttp(raw, env, post = true)
            else -> when {
                verb.startsWith("age.") || verb.startsWith("media.") ->
                    cb.capability(verb, expand(raw, env).trim().split(' ').filter { it.isNotEmpty() })
                aliases.containsKey(verb) -> return runAliasCommand(verb, raw, env)
                verb in PASSTHROUGH_COMMANDS -> cb.appCommand("${c.verb} ${expand(raw, env)}".trim())
                else -> {
                    // Anything that is not a capability, an alias, or a permitted IRC verb is a bug in
                    // the script, so say so instead of forwarding it.
                    cb.echo(null, null, "*** hex: unknown command '${c.verb}' - not an alias, a capability, " +
                        "or a permitted IRC command. Ignored.")
                    cb.log("hex: unknown command '${c.verb}' ignored (not an alias/capability/IRC verb)")
                }
            }
        }
        return Flow.NORMAL
    }

    /** Invoke a user alias as a statement: bind space-separated args to $1.., run body. */
    private fun runAliasCommand(verb: String, raw: String, env: Env): Flow {
        val block = aliases[verb] ?: return Flow.NORMAL
        if (env.frame.depth >= MAX_CALL_DEPTH) throw HexAbort("call depth exceeded")
        val argExprs = splitTop(raw.trim(), " ").map { it.trim() }.filter { it.isNotEmpty() }
        val callArgs = argExprs.map { evalVal(it, env).asStr() }
        val child = env.childForCall(callArgs)
        env.frame.depth++
        val fl = try { runBody(block.body, child) } finally { env.frame.depth-- }
        return if (fl == Flow.HALT) Flow.HALT else Flow.NORMAL
    }

    private fun doSet(raw: String, env: Env) {
        var s = raw.trim(); var local = false
        if (s.startsWith("-l ")) { local = true; s = s.removePrefix("-l ").trim() }
        val sp = s.indexOf(' ')
        val name = varName(if (sp < 0) s else s.substring(0, sp))
        val value = if (sp < 0) HexVal.EMPTY else evalVal(s.substring(sp + 1), env)
        if (local) env.locals[name] = value else globals[name] = value
    }

    private fun bumpVar(raw: String, env: Env, by: Int) {
        // The operand names a variable (e.g. `inc %count`); take the name from the token, do NOT
        // expand it to its value first (matches how set/push/setat/foreach resolve their targets).
        val name = varName(raw.trim().substringBefore(' '))
        val cur = (varRef(name, env)?.asNum() ?: 0.0)
        val nv = HexVal.num(cur + by)
        if (env.locals.containsKey(name)) env.locals[name] = nv else globals[name] = nv
    }

    private fun doTimer(raw: String, env: Env) {
        val a = expand(raw, env).trim().split(' ')
        if (a.size < 2) return
        val ms = a[0].toLongOrNull() ?: return
        cb.scheduleSignal(ms, a[1].uppercase(), a.drop(2))
    }

    /** http.get <url> <signal> [ctx...]   /   http.post <url> <body> <signal> [ctx...] */
    private fun doHttp(raw: String, env: Env, post: Boolean) {
        val parts = expand(raw, env).trim().split(' ')
        if (post && parts.size < 3) return
        if (!post && parts.size < 2) return
        val url = parts[0]
        // Capture the ORIGINATING network/buffer now; the async reply must dispatch under the
        // network the request was made on, not whatever network is active when it lands. Passed
        // as reserved __net/__buf fields; the host bridge routes echo/msg back to them.
        val srcNet = env.fields["network"] ?: ""
        val srcBuf = env.fields["buffer"] ?: ""
        val onDone: (HttpResult) -> Unit = { res ->
            // raised on Main by the engine; surface body/status + passthrough ctx as $1-
            val ctxStart = if (post) 3 else 2
            val sigName = (if (post) parts[2] else parts[1]).uppercase()
            val fields = mapOf(
                "httpok" to res.ok.toString(), "httpstatus" to res.status.toString(), "httpbody" to res.body,
                "__net" to srcNet, "__buf" to srcBuf,
            )
            cb.raiseEvent("SIGNAL:$sigName", fields, parts.drop(ctxStart))
        }
        if (post) cb.httpPost(url, parts[1], onDone) else cb.httpGet(url, onDone)
    }

    // ---- conditions ---------------------------------------------------------

    private fun evalCond(cond: String, env: Env): Boolean {
        // Left-to-right || over &&, with parenthesized grouping: (A || B) && C works, to any
        // depth, and !(...) negates a group. splitTop never splits inside parens, so a group
        // arrives at evalComparison as one term, which unwraps it and recurses here.
        for (orPart in splitTop(cond, "||")) {
            if (splitTop(orPart, "&&").all { evalComparison(it.trim(), env) }) return true
        }
        return false
    }

    private fun evalComparison(expr: String, env: Env): Boolean {
        var e = expr.trim(); var negate = false
        if (e.startsWith("!") && !e.startsWith("!=")) { negate = true; e = e.substring(1).trim() }
        // A grouped sub-condition: the whole term is one (...) pair. A comparison that merely
        // STARTS with a call, e.g. $get(%m,k) == 1, has its match before the end, so it falls
        // through to the operator scan below.
        if (e.startsWith("(")) {
            val close = runCatching { matchParen(e, 0) }.getOrNull() ?: -1
            if (close == e.length - 1) return evalCond(e.substring(1, close), env) != negate
        }
        val ops = listOf("==", "!=", "<=", ">=", "<", ">", "isin", "iswm")
        for (op in ops) {
            val pat = if (op[0].isLetter()) " $op " else op
            val i = e.indexOf(pat)
            if (i >= 0) {
                val l = expand(e.substring(0, i).trim(), env)
                val r = expand(e.substring(i + pat.length).trim(), env)
                val res = compare(l, r, op)
                return res != negate
            }
        }
        // bare value: truthy if non-empty and not "false"/"0"
        val v = expand(e, env).trim()
        val truthy = v.isNotEmpty() && v != "false" && v != "0"
        return truthy != negate
    }

    private fun compare(l: String, r: String, op: String): Boolean {
        val ln = l.toDoubleOrNull(); val rn = r.toDoubleOrNull()
        return when (op) {
            "==" -> l == r
            "!=" -> l != r
            "isin" -> r.contains(l)
            "iswm" -> glob(r, l)
            "<" -> if (ln != null && rn != null) ln < rn else l < r
            ">" -> if (ln != null && rn != null) ln > rn else l > r
            "<=" -> if (ln != null && rn != null) ln <= rn else l <= r
            ">=" -> if (ln != null && rn != null) ln >= rn else l >= r
            else -> false
        }
    }

    // ---- substitution -------------------------------------------------------

    /** Expand $... and %... in [s]. */
    private fun expand(s: String, env: Env): String {
        val out = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '$' && i + 1 < s.length && s[i + 1] == '$' -> { out.append('$'); i += 2 }
                ch == '%' && i + 1 < s.length && s[i + 1] == '%' -> { out.append('%'); i += 2 }
                // A sigil introduces a variable/expression only when a name character follows it;
                // a bare sigil (before a space, operator, punctuation, or end of string) is a literal.
                // This keeps "50%" and "cost $5" intact and, crucially, lets `%` mean modulo inside
                // $calc(...) — e.g. $calc(10 % 3) — instead of being swallowed as an empty %var.
                ch == '%' && i + 1 < s.length && isSigilNameChar(s[i + 1]) -> { val (name, n) = readIdent(s, i + 1); out.append(varRef(name, env)?.asStr() ?: ""); i = n }
                ch == '$' && i + 1 < s.length && isSigilNameChar(s[i + 1]) -> { val (val0, n) = readDollar(s, i + 1, env); out.append(val0); i = n }
                else -> { out.append(ch); i++ }
            }
        }
        return out.toString()
    }

    /** A character that may begin a `$`/`%` name (so the sigil is an expression, not a literal). */
    private fun isSigilNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun readIdent(s: String, start: Int): Pair<String, Int> {
        var i = start
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
        return s.substring(start, i) to i
    }

    private fun readDollar(s: String, start: Int, env: Env): Pair<String, Int> {
        // positional: $0, $N, $N-, $N-M
        if (start < s.length && s[start].isDigit()) {
            var i = start
            while (i < s.length && s[i].isDigit()) i++
            val first = s.substring(start, i).toInt()
            if (i < s.length && s[i] == '-') {
                i++
                if (i < s.length && s[i].isDigit()) {
                    val js = i; while (i < s.length && s[i].isDigit()) i++
                    val last = s.substring(js, i).toInt()
                    return positional(env, first, last) to i
                }
                return positional(env, first, env.args.size) to i   // $N-
            }
            return positional(env, first, first) to i               // $N
        }
        // identifier, optionally with (args)
        val (name, after) = readIdent(s, start)
        if (after < s.length && s[after] == '(') {
            val close = matchParen(s, after)
            val inner = s.substring(after + 1, close)
            val argExprs = if (inner.isBlank()) emptyList() else splitTop(inner, ",").map { it.trim() }
            return callIdVal(name, argExprs, env).asStr() to close + 1
        }
        if (name.startsWith("age.") || name.startsWith("media.")) return cb.capability(name, emptyList()) to after
        if (aliases.containsKey(name.lowercase())) return (callUser(name, emptyList(), env)?.asStr() ?: "") to after
        if (name == "0") return env.args.size.toString() to after
        return (env.fields[name] ?: "") to after
    }

    private fun positional(env: Env, from: Int, to: Int): String {
        if (from == 0) return env.args.size.toString()
        if (from < 1 || from > env.args.size) return ""
        val end = minOf(to, env.args.size)
        if (end < from) return ""                       // reversed / empty range (e.g. $3-1)
        return env.args.subList(from - 1, end).joinToString(" ")
    }

    /** Closed built-in identifier set (spec §2). */
    private fun builtin(name: String, a: List<String>, env: Env): String = when (name) {
        "len" -> (a.getOrNull(0)?.length ?: 0).toString()
        "lower" -> a.getOrNull(0)?.lowercase() ?: ""
        "upper" -> a.getOrNull(0)?.uppercase() ?: ""
        "left" -> a.getOrNull(0)?.take(a.getOrNull(1)?.toIntOrNull() ?: 0) ?: ""
        "right" -> a.getOrNull(0)?.takeLast(a.getOrNull(1)?.toIntOrNull() ?: 0) ?: ""
        "replace" -> (a.getOrNull(0) ?: "").replace(a.getOrNull(1) ?: "", a.getOrNull(2) ?: "")
        "trim" -> (a.getOrNull(0) ?: "").trim()
        "contains" -> (a.getOrNull(0) ?: "").contains(a.getOrNull(1) ?: "").toString()
        "indexof" -> (a.getOrNull(0) ?: "").indexOf(a.getOrNull(1) ?: "").toString()
        "substr" -> { val t = a.getOrNull(0) ?: ""; val st = (a.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, t.length); val ln = a.getOrNull(2)?.toIntOrNull() ?: (t.length - st); t.substring(st, (st + ln).coerceIn(st, t.length)) }
        "repeat" -> (a.getOrNull(0) ?: "").repeat((a.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 1000))
        "abs" -> HexVal.num(kotlin.math.abs(a.getOrNull(0)?.toDoubleOrNull() ?: 0.0)).asStr()
        "setting" -> cb.setting(a.getOrNull(0) ?: "") ?: ""
        "json" -> jsonGet(a.getOrNull(0) ?: "", a.getOrNull(1) ?: "")
        "urlencode" -> runCatching { java.net.URLEncoder.encode(a.getOrNull(0) ?: "", "UTF-8") }.getOrDefault(a.getOrNull(0) ?: "")
        "rand" -> {                                   // $rand(n) -> 0..n-1 ; $rand(lo,hi) -> lo..hi inclusive
            val lo: Int; val hi: Int
            if (a.size >= 2) { lo = a[0].toIntOrNull() ?: 0; hi = a[1].toIntOrNull() ?: 0 }
            else { lo = 0; hi = (a.getOrNull(0)?.toIntOrNull() ?: 1) - 1 }
            if (hi < lo) lo.toString() else (lo..hi).random().toString()
        }
        "pad" -> { val s = a.getOrNull(0) ?: ""; val n = (a.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 4096); val c = a.getOrNull(2)?.firstOrNull() ?: ' '; if (s.length >= n) s else s + c.toString().repeat(n - s.length) }
        "padleft" -> { val s = a.getOrNull(0) ?: ""; val n = (a.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 4096); val c = a.getOrNull(2)?.firstOrNull() ?: ' '; if (s.length >= n) s else c.toString().repeat(n - s.length) + s }
        "capitalize" -> (a.getOrNull(0) ?: "").replaceFirstChar { it.uppercase() }
        "title" -> (a.getOrNull(0) ?: "").split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        "round" -> { val x = a.getOrNull(0)?.toDoubleOrNull() ?: 0.0; val dp = (a.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 12); val f = Math.pow(10.0, dp.toDouble()); HexVal.num(Math.round(x * f) / f).asStr() }
        "ceil" -> HexVal.num(kotlin.math.ceil(a.getOrNull(0)?.toDoubleOrNull() ?: 0.0)).asStr()
        "pow" -> HexVal.num(Math.pow(a.getOrNull(0)?.toDoubleOrNull() ?: 0.0, a.getOrNull(1)?.toDoubleOrNull() ?: 0.0)).asStr()
        "clamp" -> { val x = a.getOrNull(0)?.toDoubleOrNull() ?: 0.0; val lo = a.getOrNull(1)?.toDoubleOrNull() ?: 0.0; val hi = a.getOrNull(2)?.toDoubleOrNull() ?: 0.0; HexVal.num(x.coerceIn(minOf(lo, hi), maxOf(lo, hi))).asStr() }
        "re_match" -> { val r = reOf(a.getOrNull(1) ?: ""); (r != null && r.containsMatchIn((a.getOrNull(0) ?: "").take(RE_MAX))).toString() }
        "re_find" -> { val r = reOf(a.getOrNull(1) ?: ""); r?.find((a.getOrNull(0) ?: "").take(RE_MAX))?.value ?: "" }
        "re_group" -> { val r = reOf(a.getOrNull(1) ?: ""); val n = a.getOrNull(2)?.toIntOrNull() ?: 0; r?.find((a.getOrNull(0) ?: "").take(RE_MAX))?.groupValues?.getOrNull(n) ?: "" }
        "re_replace" -> { val r = reOf(a.getOrNull(1) ?: ""); val src = (a.getOrNull(0) ?: "").take(RE_MAX); val rep = a.getOrNull(2) ?: ""; if (r == null) src else r.replace(src) { rep } }   // literal replacement (no $1 refs; $ is the script sigil)
        // Unknown identifier => empty (the function set is closed). Log it: a call reaches here only
        // when it looks like a function ($name(...)) but no built-in/alias matched
        else -> { cb.log("hex: unknown function \$$name()"); "" }
    }

    /** Flat top-level JSON string extractor: $json(body, key). v1 = flat keys only. */
    /**
     * $json(body, path): extract a value by dotted path (e.g. "data.items.0.name"). Supports nested
     * objects, array indices, and non-string leaves (numbers/bools). For a plain (dot-free) key that
     * isn't found at the root, falls back to the legacy find-anywhere string match for compatibility.
     */
    private fun jsonGet(body: String, path: String): String {
        val viaPath = JsonLite.path(body, path)
        if (viaPath.isNotEmpty()) return viaPath
        if (path.contains('.')) return ""
        return jsonFlat(body, path)
    }

    /** Compile a regex safely (invalid pattern -> null, caller falls back). */
    private val reCache = HashMap<String, Regex?>()
    private fun reOf(pattern: String): Regex? = reCache.getOrPut(pattern) { runCatching { Regex(pattern) }.getOrNull() }
    private val RE_MAX = 20000   // cap regex input length to bound pathological patterns

    private fun jsonFlat(body: String, key: String): String {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
        return m?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: ""
    }

    /** Minimal dependency-free JSON reader: parse to Map/List/String/Double/Boolean and walk a path. */
    private object JsonLite {
        /** Serialize a HexVal tree to JSON. Number-looking strings emit unquoted so they round-trip. */
        fun write(v: HexVal): String = when (v) {
            is HexVal.Mp -> v.map.entries.joinToString(",", "{", "}") { (k, vv) -> "\"${esc(k)}\":${write(vv)}" }
            is HexVal.Lst -> v.items.joinToString(",", "[", "]") { write(it) }
            is HexVal.Str -> if (isNum(v.s)) v.s else "\"${esc(v.s)}\""
        }
        private fun isNum(s: String): Boolean = s.matches(Regex("-?\\d+(\\.\\d+)?"))
        private fun esc(s: String): String = buildString {
            for (c in s) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> append(c)
            }
        }
        fun path(body: String, path: String): String {
            var cur: Any? = try { P(body).value() } catch (_: Throwable) { return "" }
            if (path.isNotEmpty()) for (seg in path.split('.')) {
                cur = when (cur) {
                    is Map<*, *> -> cur[seg]
                    is List<*> -> seg.toIntOrNull()?.let { if (it in cur.indices) cur[it] else null }
                    else -> null
                }
                if (cur == null) return ""
            }
            return str(cur)
        }
        private fun str(v: Any?): String = when (v) {
            null -> ""
            is String -> v
            is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
            else -> v.toString()
        }
        private class P(val s: String) {
            var i = 0
            fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
            fun value(): Any? {
                ws()
                if (i >= s.length) return null
                return when (s[i]) {
                    '{' -> obj(); '[' -> arr(); '"' -> string()
                    't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
                    else -> num()
                }
            }
            fun obj(): Map<String, Any?> {
                val m = LinkedHashMap<String, Any?>(); i++; ws()
                if (i < s.length && s[i] == '}') { i++; return m }
                while (i < s.length) {
                    ws(); val k = string(); ws(); if (i < s.length && s[i] == ':') i++
                    m[k] = value(); ws()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    if (i < s.length && s[i] == '}') { i++; break }
                    break
                }
                return m
            }
            fun arr(): List<Any?> {
                val l = ArrayList<Any?>(); i++; ws()
                if (i < s.length && s[i] == ']') { i++; return l }
                while (i < s.length) {
                    l.add(value()); ws()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    if (i < s.length && s[i] == ']') { i++; break }
                    break
                }
                return l
            }
            fun string(): String {
                val sb = StringBuilder(); if (i < s.length && s[i] == '"') i++
                while (i < s.length && s[i] != '"') {
                    val c = s[i]
                    if (c == '\\' && i + 1 < s.length) {
                        i++
                        when (s[i]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); '/' -> sb.append('/')
                            '"' -> sb.append('"'); '\\' -> sb.append('\\')
                            'u' -> { if (i + 4 < s.length) { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 } }
                            else -> sb.append(s[i])
                        }
                    } else sb.append(c)
                    i++
                }
                if (i < s.length && s[i] == '"') i++
                return sb.toString()
            }
            fun num(): Double {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '+' || s[i] == '-' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
                return s.substring(start, i).toDoubleOrNull() ?: 0.0
            }
        }
    }

    private val MAX_CALL_DEPTH = 64

    /** Invoke a user alias as a value function: bind args to $1.., run body, yield `return`. */
    private fun callUser(name: String, argExprs: List<String>, env: Env): HexVal? {
        val block = aliases[name.lowercase()] ?: return null
        if (env.frame.depth >= MAX_CALL_DEPTH) throw HexAbort("call depth exceeded")
        val callArgs = argExprs.map { evalVal(it, env).asStr() }
        val child = env.childForCall(callArgs)
        env.frame.depth++
        try { runBody(block.body, child) } finally { env.frame.depth-- }
        return child.returnValue ?: HexVal.EMPTY
    }

    /** Build the root scope for an event dispatch (fresh Frame). */
    private fun envForEvent(e: EventData, cb: EngineCallbacks): Env {
        val f = HashMap<String, String>()
        f["network"] = e.network; f["buffer"] = e.buffer; f["chan"] = e.buffer
        f["target"] = e.buffer; f["text"] = e.text
        e.from?.let { f["nick"] = it }
        f["me"] = cb.nick().orEmpty()
        f["isme"] = e.isMine.toString()
        f.putAll(e.fields)
        val args = if (e.args.isNotEmpty()) e.args
        else if (e.text.isBlank()) emptyList() else e.text.split(' ')
        return Env(Frame(), f, args, HashMap())
    }

    /**
     * Render a `view{}` body to text. Expands `foreach %x %coll { ... }`
     * by repetition (so a script can lay out N seats/a data-driven list) and applies
     * $/% substitution per iteration. Other text is substituted as-is.
     */
    private fun renderViewBody(s: String, env: Env): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ctl = nextControl(s, i)
            if (ctl == null) { sb.append(expand(s.substring(i), env)); break }
            val (at, kw) = ctl
            sb.append(expand(s.substring(i, at), env))
            i = if (kw == "foreach") renderForeach(s, at, env, sb) else renderIf(s, at, env, sb)
        }
        return sb.toString()
    }

    /** Earliest word-boundary `foreach %x …` or `if (…)` at/after [from]. */
    private fun nextControl(s: String, from: Int): Pair<Int, String>? {
        var best = -1; var kw = ""
        for (k in listOf("foreach", "if")) {
            var p = from
            while (true) {
                val idx = s.indexOf(k, p); if (idx < 0) break
                val before = if (idx == 0) ' ' else s[idx - 1]
                var a = idx + k.length
                while (a < s.length && s[a].isWhitespace()) a++
                val nextCh = if (a < s.length) s[a] else ' '
                val ok = before.isWhitespace() && (if (k == "if") nextCh == '(' else nextCh == '%')
                if (ok) { if (best < 0 || idx < best) { best = idx; kw = k }; break }
                p = idx + k.length
            }
        }
        return if (best < 0) null else best to kw
    }

    /** Inner of `{ … }` from the next `{` at/after [from]; returns (inner, indexAfterClose). */
    private fun readBraceBlock(s: String, from: Int): Pair<String, Int> {
        var j = from
        while (j < s.length && s[j] != '{') j++
        if (j >= s.length) return "" to s.length
        var depth = 0; val open = j
        while (j < s.length) { if (s[j] == '{') depth++ else if (s[j] == '}') { depth--; if (depth == 0) { j++; break } }; j++ }
        return s.substring(open + 1, j - 1) to j
    }

    /** Inner of `( … )` from the next `(` at/after [from]; returns (inner, indexAfterClose). */
    private fun readParenGroup(s: String, from: Int): Pair<String, Int> {
        var j = from
        while (j < s.length && s[j] != '(') j++
        if (j >= s.length) return "" to s.length
        var depth = 0; val open = j
        while (j < s.length) { if (s[j] == '(') depth++ else if (s[j] == ')') { depth--; if (depth == 0) { j++; break } }; j++ }
        return s.substring(open + 1, j - 1) to j
    }

    private fun renderForeach(s: String, at: Int, env: Env, sb: StringBuilder): Int {
        var j = at + "foreach".length
        fun skip() { while (j < s.length && s[j].isWhitespace()) j++ }
        fun word(): String { skip(); val st = j; while (j < s.length && !s[j].isWhitespace() && s[j] != '{') j++; return s.substring(st, j) }
        val itemVar = varName(word())
        val collTok = word()
        val (inner, after) = readBraceBlock(s, j)
        val coll = evalVal(collTok, env)
        val items: List<HexVal> = when (coll) {
            is HexVal.Lst -> coll.items.toList()
            is HexVal.Mp -> coll.map.keys.map { HexVal.Str(it) }
            is HexVal.Str -> if (coll.s.isBlank()) emptyList() else coll.s.split(" ").map { HexVal.Str(it) }
        }
        val saved = env.locals[itemVar]
        for (it in items) { env.tick(); env.locals[itemVar] = it; sb.append(renderViewBody(inner, env)); sb.append('\n') }
        if (saved != null) env.locals[itemVar] = saved else env.locals.remove(itemVar)
        return after
    }

    /** `if (cond) { … } [elseif (cond) { … }]* [else { … }]` — emit the first matching branch. */
    private fun renderIf(s: String, at: Int, env: Env, sb: StringBuilder): Int {
        val (cond, afterCond) = readParenGroup(s, at + "if".length)
        val (body, afterBody) = readBraceBlock(s, afterCond)
        var chosen: String? = if (evalCond(cond, env)) body else null
        var j = afterBody
        while (true) {
            var k = j
            while (k < s.length && s[k].isWhitespace()) k++
            if (s.startsWith("elseif", k)) {
                val (c2, ac2) = readParenGroup(s, k + "elseif".length)
                val (b2, ab2) = readBraceBlock(s, ac2)
                if (chosen == null && evalCond(c2, env)) chosen = b2
                j = ab2
            } else if (s.startsWith("else", k)) {
                val (b3, ab3) = readBraceBlock(s, k + "else".length)
                if (chosen == null) chosen = b3
                j = ab3
            } else break
        }
        chosen?.let { sb.append(renderViewBody(it, env)); sb.append('\n') }
        return j
    }

    // ---- value layer (v2: lists / maps / arithmetic) ------------------------

    private fun varRef(name: String, env: Env): HexVal? = env.locals[name] ?: globals[name]

    /** Evaluate a value expression to a [HexVal]: a bare %var (container-preserving), a
     *  whole $id(...) call, a "quoted string" (quotes stripped, inside expanded), or
     *  otherwise a stringified scalar. */
    private fun evalVal(expr: String, env: Env): HexVal {
        val t = expr.trim()
        if (isQuoted(t)) return HexVal.Str(expand(t.substring(1, t.length - 1), env))
        if (t.length > 1 && t[0] == '%' && t.drop(1).all { it.isLetterOrDigit() || it == '_' })
            return varRef(t.substring(1), env) ?: HexVal.EMPTY
        if (t.startsWith("$")) {
            val (name, after) = readIdent(t, 1)
            if (after < t.length && t[after] == '(') {
                val close = runCatching { matchParen(t, after) }.getOrNull() ?: -1
                if (close == t.length - 1) {
                    val inner2 = t.substring(after + 1, close)
                    val argExprs = if (inner2.isBlank()) emptyList() else splitTop(inner2, ",").map { it.trim() }
                    return callIdVal(name, argExprs, env)
                }
            }
        }
        return HexVal.Str(expand(t, env))
    }

    /** A function argument wrapped in double quotes: the quotes delimit a string literal.
     *  `$split(%s, " ")` therefore splits on a space (the intuitive reading), and a
     *  delimiter that needs leading/trailing spaces or a comma is finally expressible:
     *  `$join(%l, ", ")`. */
    private fun isQuoted(t: String): Boolean =
        t.length >= 2 && t[0] == '"' && t[t.length - 1] == '"'

    /** Expand a function argument to its string form, stripping one layer of quotes. */
    private fun expandArg(expr: String, env: Env): String {
        val t = expr.trim()
        return if (isQuoted(t)) expand(t.substring(1, t.length - 1), env) else expand(t, env)
    }

    private fun doSetAt(raw: String, env: Env) {
        val nm = raw.trim().substringBefore(' ')
        val afterColl = raw.trim().substringAfter(' ', "").trim()
        val keyTok = afterColl.substringBefore(' ')
        val valExpr = afterColl.substringAfter(' ', "")
        val coll = varRef(varName(nm), env) ?: return
        HexValues.setAt(coll, evalVal(keyTok, env), evalVal(valExpr, env))
    }

    private fun head(raw: String): Pair<String, String> {
        val t = raw.trim(); val sp = t.indexOf(' ')
        return if (sp < 0) t to "" else t.substring(0, sp) to t.substring(sp + 1)
    }

    /** Value-returning built-ins. Container ops evaluate args as HexVal; scalar string
     *  built-ins fall through to [builtin]. */
    private fun callIdVal(name: String, argExprs: List<String>, env: Env): HexVal {
        fun v(i: Int) = if (i < argExprs.size) evalVal(argExprs[i], env) else HexVal.EMPTY
        fun str(i: Int, def: String = "") = if (i < argExprs.size) expandArg(argExprs[i], env) else def
        if (name.startsWith("age.") || name.startsWith("media."))
            return HexVal.Str(cb.capability(name, argExprs.map { expandArg(it, env) }))
        if (aliases.containsKey(name.lowercase()))
            return callUser(name, argExprs, env) ?: HexVal.EMPTY
        return when (name) {
            "list" -> HexValues.list(argExprs.map { evalVal(it, env) })
            "map" -> {
                val a = argExprs.map { evalVal(it, env) }
                val pairs = ArrayList<Pair<String, HexVal>>()
                var i = 0; while (i + 1 < a.size) { pairs.add(a[i].asStr() to a[i + 1]); i += 2 }
                HexValues.map(pairs)
            }
            "get" -> HexValues.get(v(0), v(1))
            "len" -> HexVal.num(HexValues.len(v(0)).toDouble())
            "keys" -> HexValues.keys(v(0))
            "has" -> HexVal.Str(HexValues.has(v(0), v(1)).toString())
            "sort" -> HexValues.sort(v(0))
            "join" -> HexVal.Str(HexValues.join(v(0), str(1, " ")))
            "split" -> HexValues.split(str(0), str(1, " "))
            "slice" -> HexValues.slice(v(0), str(1, "0").toIntOrNull() ?: 0, str(2, "0").toIntOrNull() ?: 0)
            // pure value-semantics derivations (no in-place equivalent; mutation is the
            // push/setat statements). $concat doubles as copy-on-write append.
            "concat" -> HexValues.concat(argExprs.map { evalVal(it, env) })
            "reverse" -> HexValues.reversed(v(0))
            "find" -> HexVal.num(HexValues.find(v(0), v(1)).toDouble())
            "count" -> HexVal.num(HexValues.count(v(0), v(1)).toDouble())
            "sum" -> HexVal.num(HexValues.sum(v(0)))
            // min/max accept either varargs — $max(3,9,2) — or a single list — $max(%scores) —
            // so they compose with the collection layer.
            "max", "min" -> {
                val nums = ArrayList<Double>()
                for (ex in argExprs) when (val vv = evalVal(ex, env)) {
                    is HexVal.Lst -> vv.items.forEach { it.asStr().toDoubleOrNull()?.let(nums::add) }
                    else -> vv.asStr().toDoubleOrNull()?.let(nums::add)
                }
                HexVal.num((if (name == "max") nums.maxOrNull() else nums.minOrNull()) ?: 0.0)
            }
            "calc" -> HexVal.num(HexValues.calc(str(0, "0")))
            "mod" -> { val b = str(1, "1").toDoubleOrNull() ?: 1.0; HexVal.num(if (b == 0.0) 0.0 else (str(0, "0").toDoubleOrNull() ?: 0.0) % b) }
            "int" -> HexVal.num(kotlin.math.floor(str(0, "0").toDoubleOrNull() ?: 0.0))
            "idiv" -> { val b = str(1, "1").toDoubleOrNull() ?: 1.0; HexVal.num(if (b == 0.0) 0.0 else kotlin.math.floor((str(0, "0").toDoubleOrNull() ?: 0.0) / b)) }
            "range" -> { val lo = str(0, "0").toIntOrNull() ?: 0; val hiRaw = str(1, "0").toIntOrNull() ?: 0; val hi = if (hiRaw - lo > 10000) lo + 10000 else hiRaw; HexVal.Lst((lo..hi).map { HexVal.Str(it.toString()) }.toMutableList()) }
            "values" -> (v(0) as? HexVal.Mp)?.let { HexVal.Lst(it.map.values.toMutableList()) } ?: HexVal.Lst()
            "pick" -> { val l = (v(0) as? HexVal.Lst)?.items; if (l.isNullOrEmpty()) HexVal.Str("") else l[kotlin.random.Random.Default.nextInt(l.size)] }
            "shuffle" -> HexVal.Lst(((v(0) as? HexVal.Lst)?.items?.toMutableList() ?: mutableListOf()).also { it.shuffle() })
            "tojson" -> HexVal.Str(JsonLite.write(v(0)))
            else -> HexVal.Str(builtin(name, argExprs.map { expandArg(it, env) }, env))
        }
    }

    // ---- small helpers ------------------------------------------------------

    private fun varName(token: String): String = token.removePrefix("%").trim()

    private fun splitTarget(s: String): Pair<String?, String> {
        val t = s.trim()
        val sp = t.indexOf(' ')
        if (sp < 0) return null to t
        return t.substring(0, sp) to t.substring(sp + 1)
    }

    private fun matchParen(s: String, open: Int): Int {
        var depth = 0
        var i = open
        var inStr = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) { if (c == '"') inStr = false }
            else if (c == '"') inStr = true
            else if (c == '(') depth++
            else if (c == ')') { depth--; if (depth == 0) return i }
            i++
        }
        throw HexError("unbalanced ( )")
    }

    /** Split [s] on top-level [sep] (not inside ()/{} or "quoted strings"). */
    private fun splitTop(s: String, sep: String): List<String> {
        val parts = ArrayList<String>(); var depth = 0; var i = 0; var last = 0; var inStr = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) { if (c == '"') inStr = false }
            else if (c == '"') inStr = true
            else if (c == '(' || c == '{') depth++
            else if (c == ')' || c == '}') depth--
            else if (depth == 0 && s.startsWith(sep, i)) { parts.add(s.substring(last, i)); i += sep.length; last = i; continue }
            i++
        }
        parts.add(s.substring(last))
        return parts
    }

    private fun glob(pattern: String, text: String): Boolean {
        val rx = StringBuilder("(?i)^")
        for (c in pattern) when (c) {
            '*' -> rx.append(".*"); '?' -> rx.append('.')
            else -> rx.append(Regex.escape(c.toString()))
        }
        rx.append('$')
        return Regex(rx.toString()).matches(text)
    }
}

/** Abort for the step/time budget (uncatchable by scripts since they have no try/catch). */
private class HexAbort(message: String) : RuntimeException(message)
