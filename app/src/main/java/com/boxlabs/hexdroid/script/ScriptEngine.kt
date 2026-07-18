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

class ScriptEngine(
    private val host: ScriptHost,
    private val backend: ScriptBackend,
) {
    var budget: Budget = Budget(maxInstructions = 2_000_000, maxMillis = 250)

    /** event name (upper-case) -> ordered opaque handler tokens. Owned here, run by the backend. */
    private val eventHandlers = LinkedHashMap<String, MutableList<Any>>()
    /** lower-case command name -> handler token. */
    private val commandHandlers = LinkedHashMap<String, Any>()

    private var started = false

    // The network/buffer of the event currently being dispatched. echo/msg/raw and raised
    // signals route to THIS context instead of the active window, so a handler triggered by a
    // message on network A always writes back to network A even if the user is looking at B.
    // Scripts run on a single worker thread, so plain fields are safe here.
    private var curNet: String? = null
    private var curBuf: String? = null

    private inline fun <T> withCtx(network: String?, buffer: String?, block: () -> T): T {
        val pn = curNet; val pb = curBuf
        curNet = network?.ifBlank { null } ?: pn
        curBuf = buffer?.ifBlank { null } ?: pb
        try { return block() } finally { curNet = pn; curBuf = pb }
    }

    /** File extension scripts use for this backend (so ScriptStore can filter). */
    val scriptExtension: String get() = backend.scriptExtension

    // ---- lifecycle ----------------------------------------------------------

    fun start() {
        if (started) return
        backend.start(callbacks, budget)
        started = true
    }

    fun shutdown() {
        eventHandlers.clear()
        commandHandlers.clear()
        backend.shutdown()
        started = false
    }

    /** Load a script; returns null on success or an error message for the Scripts UI. */
    fun loadScript(name: String, source: String): String? {
        if (!started) start()
        return backend.loadScript(name, source)
    }

    /** Full reload: drop handlers + the interpreter scope, then re-init. Caller re-loads scripts after. */
    fun resetHandlers() {
        eventHandlers.clear()
        commandHandlers.clear()
        backend.reset()
    }

    // ---- dispatch entry points (called from IrcViewModel) -------------------

    fun onText(ev: TextEvent): TextResult {
        if (!started) return TextResult(ev.text, false)
        val name = if (ev.isAction) "ACTION" else "TEXT"
        val handlers = eventHandlers[name] ?: return TextResult(ev.text, false)
        if (handlers.isEmpty()) return TextResult(ev.text, false)
        return withCtx(ev.network, ev.buffer) {
            backend.dispatchTransform(
                handlers,
                EventData(
                    network = ev.network, buffer = ev.buffer, from = ev.from, text = ev.text,
                    isAction = ev.isAction, isPrivate = ev.isPrivate, isMine = ev.isMine,
                ),
            )
        }
    }

    fun onInput(ev: InputEvent): TextResult {
        if (!started) return TextResult(ev.text, false)
        val handlers = eventHandlers["INPUT"] ?: return TextResult(ev.text, false)
        if (handlers.isEmpty()) return TextResult(ev.text, false)
        return withCtx(ev.network, ev.buffer) {
            backend.dispatchTransform(
                handlers,
                EventData(network = ev.network, buffer = ev.buffer, text = ev.text),
            )
        }
    }

    fun hasCommand(name: String): Boolean = commandHandlers.containsKey(name.lowercase())

    /**
     * Names of every command a loaded script registered (each script `alias` becomes one). Used to
     * surface user-facing script commands (e.g. /tr) in the command-hint chips. Internal helper
     * aliases conventionally contain '_' and are filtered out by the caller.
     */
    fun commandNames(): List<String> = commandHandlers.keys.toList()

    fun runCommand(name: String, args: String, network: String?, buffer: String?): Boolean {
        val h = commandHandlers[name.lowercase()] ?: return false
        withCtx(network, buffer) { backend.runCommand(h, args, network, buffer) }
        return true
    }

    /** Generic notify event (JOIN/PART/QUIT/NOTICE/...). Wire from the matching handleEvent branches. */
    fun dispatch(eventName: String, event: EventData) {
        if (!started) return
        val handlers = eventHandlers[eventName.uppercase()] ?: return
        if (handlers.isEmpty()) return
        withCtx(event.network, event.buffer) { backend.dispatchNotify(handlers, event) }
    }

    // ---- the host API as the backend sees it --------------------------------

    private val callbacks = object : EngineCallbacks {
        override fun registerEvent(eventName: String, handler: Any) {
            eventHandlers.getOrPut(eventName.uppercase()) { mutableListOf() }.add(handler)
        }
        override fun registerCommand(name: String, handler: Any) {
            commandHandlers[name.trimStart('/').lowercase()] = handler
        }

        override fun echo(buffer: String?, from: String?, text: String) =
            host.echo(curNet, buffer ?: curBuf, from, text)
        override fun sendMessage(target: String, text: String) =
            host.sendMessage(curNet, target, text)
        override fun sendRaw(line: String) = host.sendRaw(curNet, line)
        override fun setting(key: String): String? = host.getSetting(key)
        override fun nick(): String? = host.nick(curNet)
        override fun log(message: String) = host.logDebug(curNet, message)

        override fun httpGet(url: String, onResult: (HttpResult) -> Unit) =
            doHttp(ScriptHttpRequest(url = url, method = "GET"), onResult)
        override fun httpPost(url: String, body: String, onResult: (HttpResult) -> Unit) =
            doHttp(ScriptHttpRequest(url = url, method = "POST", body = body), onResult)

        override fun raiseEvent(eventName: String, fields: Map<String, String>, args: List<String>) {
            // Re-enter the normal dispatch path so SIGNAL handlers run like any event. The
            // originating network/buffer travels in reserved __net/__buf (set by async callbacks
            // and timers); otherwise inherit the current dispatch context; finally fall back to the
            // active window. This keeps a translation/reply on the network its request came from.
            val net = fields["__net"]?.ifBlank { null } ?: curNet ?: host.activeNetwork()
            val buf = fields["__buf"]?.ifBlank { null } ?: curBuf ?: host.activeBuffer()
            val clean = if (fields.keys.any { it.startsWith("__") }) fields.filterKeys { !it.startsWith("__") } else fields
            dispatch(
                eventName,
                EventData(
                    network = net.orEmpty(),
                    buffer = buf.orEmpty(),
                    fields = clean,
                    args = args,
                ),
            )
        }

        // curNet/curBuf, not null: every other callback here is already origin-scoped, and passing
        // null made the host treat every script command as "the active buffer". A handler firing on
        // network A then executed against whatever the user happened to be looking at on network B,
        // which is exactly what the class comment above says must never happen. It also meant the
        // host's own cross-network guard could never fire, because network was always null.
        override fun appCommand(line: String) = host.appCommand(curNet, curBuf, line)

        override fun uiIntent(kind: String, args: List<String>) = host.uiIntent(kind, args)

        override fun mountView(view: ScriptView) = host.mountView(view)

        override fun capability(name: String, args: List<String>): String = host.capability(name, args)

        override fun scheduleSignal(delayMs: Long, signal: String, args: List<String>) {
            // Host owns the timer; capture the scheduling context now so the delayed signal fires
            // back on the same network/buffer even if the active window has changed by then.
            val net = curNet; val buf = curBuf
            host.postDelayed(delayMs) {
                val f = HashMap<String, String>(2)
                net?.let { f["__net"] = it }; buf?.let { f["__buf"] = it }
                raiseEvent("SIGNAL:${signal.uppercase()}", f, args)
            }
        }
    }

    /**
     * Permission check + async request + Main-thread marshalling. The backend's HTTP
     * host function passes a callback that re-enters the interpreter; we guarantee that
     * callback runs on Main (via host.runOnScriptThread) and never before the request
     * actually completes.
     */
    private fun doHttp(req: ScriptHttpRequest, onResult: (HttpResult) -> Unit) {
        if (!host.isNetworkAllowed(req.url)) {
            host.runOnScriptThread { onResult(HttpResult(false, 0, "", "network not permitted: ${req.url}")) }
            return
        }
        host.httpRequest(req) { resp ->
            host.runOnScriptThread {
                onResult(HttpResult(resp.ok, resp.status, resp.body, resp.error))
            }
        }
    }
}

/** Incoming line presented to TEXT/ACTION handlers. */
data class TextEvent(
    val network: String,
    val buffer: String,
    val from: String,
    val text: String,
    val isAction: Boolean,
    val isPrivate: Boolean,
    val isMine: Boolean,
)

/** Outgoing line presented to INPUT handlers. */
data class InputEvent(
    val network: String,
    val buffer: String,
    val text: String,
)
