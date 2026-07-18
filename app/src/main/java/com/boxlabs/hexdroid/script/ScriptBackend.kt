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

interface ScriptBackend {

    /** File extension this backend's scripts use, without the dot ("js", "lua"). */
    val scriptExtension: String

    /**
     * Stand up a fresh, sandboxed interpreter scope and install the host API by
     * calling into [callbacks]. Must be safe to call again after [reset].
     *
     * @param callbacks the engine-side implementation of the host API (registerEvent,
     *                  echo, msg, http, …). The backend wires its native function
     *                  objects to these.
     * @param budget    per-call instruction/time ceiling the backend must enforce by
     *                  whatever mechanism it has (Rhino: observeInstructionCount;
     *                  luak: a DebugLib count hook).
     */
    fun start(callbacks: EngineCallbacks, budget: Budget)

    /** Evaluate one script's source under [name]. Errors must NOT escape, log and swallow. */
    /** Load a script. Returns null on success, or an error message for the UI. */
    fun loadScript(name: String, source: String): String?

    /**
     * Discard the current scope and all script-defined globals, then re-create a fresh
     * one (equivalent to [start] with the same callbacks/budget). Used before a full
     * reload so a removed `on()` registration doesn't survive in the interpreter.
     */
    fun reset()

    fun shutdown()

    /**
     * Run a transforming dispatch (TEXT/INPUT). The backend builds an event object
     * from [event], exposes a writable `text` field and a `halt()` method, calls each
     * handler in order (budget-guarded, errors swallowed), stops early if halted, and
     * returns the resolved text + halted flag.
     */
    fun dispatchTransform(handlers: List<Any>, event: EventData): TextResult

    /** Run a notify-only dispatch (JOIN / PART / …). No return-value protocol. */
    fun dispatchNotify(handlers: List<Any>, event: EventData)

    /** Invoke a script-registered /command handler with its argument string. */
    fun runCommand(handler: Any, args: String, network: String?, buffer: String?)
}

/**
 * The host API as the engine implements it, handed to a backend in [ScriptBackend.start].
 * The backend installs thin native functions (`on`, `echo`, `http.get`, …) that forward
 * here. The engine owns permission checks, the registry, and the Main-thread marshalling
 * of async results.
 */
interface EngineCallbacks {
    /** A script called `on(event, fn)` — record the (opaque) handler token. */
    fun registerEvent(eventName: String, handler: Any)
    /** A script called `command(name, fn)`. */
    fun registerCommand(name: String, handler: Any)

    fun echo(buffer: String?, from: String?, text: String)
    fun sendMessage(target: String, text: String)
    fun sendRaw(line: String)
    fun setting(key: String): String?
    fun nick(): String?
    fun log(message: String)

    /**
     * Permission-checked async GET. The engine validates the URL, runs the request off
     * the Main thread, and guarantees [onResult] is invoked back **on Main**, so the
     * backend can re-enter its interpreter safely to call the script callback.
     */
    fun httpGet(url: String, onResult: (HttpResult) -> Unit)
    fun httpPost(url: String, body: String, onResult: (HttpResult) -> Unit)

    // --- added for the .hex backend (generally useful; backends may call them) ---

    /**
     * Raise an event back into the engine, used to fire `SIGNAL:<name>` when an async
     * op (http/timer) completes or a script calls `signal`. The engine looks up the
     * handlers and runs them via [ScriptBackend.dispatchNotify], threading [fields] and
     * [args] into the [EventData] so handlers can read `$httpbody` / `$1-`.
     */
    fun raiseEvent(eventName: String, fields: Map<String, String>, args: List<String>)

    /** Forward an unrecognised verb line to the app's slash-command pipeline. */
    fun appCommand(line: String)

    /** Data-intent to the UI (decorate/action/sidebar/toast) rendered by ChatScreen. */
    fun uiIntent(kind: String, args: List<String>)

    /** Schedule a one-shot `SIGNAL:<signal>` after [delayMs] (host-side timer; fires on Main). */
    fun scheduleSignal(delayMs: Long, signal: String, args: List<String>)

    /** Mount a script-built view tree onto the active surface (rendered by ScriptSurface). */
    fun mountView(view: ScriptView)

    /** Call a host capability namespace (e.g. `age.*`, `media.*`). Sync, returns a string
     *  (lists come back space-joined). Async results arrive back as SIGNAL events. */
    fun capability(name: String, args: List<String>): String
}

/** Per-call execution budget the backend enforces. */
data class Budget(val maxInstructions: Int, val maxMillis: Long)

/** Neutral event payload the backend turns into its native event object. */
data class EventData(
    val network: String,
    val buffer: String,
    val from: String? = null,
    val text: String = "",
    val isAction: Boolean = false,
    val isPrivate: Boolean = false,
    val isMine: Boolean = false,
    /** Extra named fields for SIGNAL/notify dispatches (e.g. httpbody/httpstatus). */
    val fields: Map<String, String> = emptyMap(),
    /** Positional args carried by a raised event (becomes `$1-` in .hex). */
    val args: List<String> = emptyList(),
)

/** Neutral HTTP result handed to a script callback. */
data class HttpResult(val ok: Boolean, val status: Int, val body: String, val error: String?)

/** Result of a transforming dispatch. */
data class TextResult(val text: String, val halted: Boolean)
