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
 * The bridge between [ScriptEngine] and the rest of the app.
 *
 * The engine deliberately knows nothing about [com.boxlabs.hexdroid.IrcViewModel],
 * Android, OkHttp, or Compose. Everything a script can *do* to the outside world
 * goes through this interface, which the viewmodel implements as a thin adapter
 * over its existing private methods (append, privmsg, sendRaw, settings, …).
 */
interface ScriptHost {

    /**
     * Print a local-only line into a buffer (never sent to the network). This is the
     * script equivalent of the viewmodel's internal `append(key, from = null, …)`.
     *
     * @param network network id, or null to use the active network
     * @param buffer  buffer name (channel / query / "*server*"), or null for the
     *                currently-selected buffer
     * @param from    optional nick to attribute the line to; null renders it as a
     *                system line (the "*** …" style)
     */
    fun echo(network: String?, buffer: String?, from: String?, text: String)

    /** Send a PRIVMSG. Mirrors the user typing into [buffer]; honours per-target E2E keys. */
    fun sendMessage(network: String?, buffer: String, text: String)

    /** Send a raw IRC line (already-formed, no CRLF). Power-user escape hatch. */
    fun sendRaw(network: String?, line: String)

    /** Read a string-valued client setting by key (see [ScriptEngine.SETTING_KEYS]). Null if unknown. */
    fun getSetting(key: String): String?

    /** Our current nick on [network] (or the active network when null). */
    fun nick(network: String?): String?

    /** Active network id, or null when nothing is connected. */
    fun activeNetwork(): String?

    /** Currently-selected buffer name, or null. */
    fun activeBuffer(): String?

    /**
     * Permission gate for outbound HTTP from a script. Return false to deny.
     */
    fun isNetworkAllowed(url: String): Boolean

    /**
     * Perform an HTTP request off the Main thread (OkHttp) and deliver the result via
     * [onResult]. [onResult] may be invoked on any thread; the engine re-marshals onto
     * Main itself, but you may also wrap it in [runOnScriptThread] for clarity.
     */
    fun httpRequest(req: ScriptHttpRequest, onResult: (ScriptHttpResponse) -> Unit)

    /**
     * Run [block] on the Main/script thread. Used by the engine to deliver async
     * callbacks (HTTP completions, timers) back onto the single thread where Rhino
     * and _state live. Implement as
     *     viewModelScope.launch(Dispatchers.Main.immediate) { block() }
     */
    fun runOnScriptThread(block: () -> Unit)

    /** Diagnostic logging from a script's log()/console — route to Logcat and/or a script console buffer. */
    fun logDebug(scriptName: String?, message: String)

    // --- added for the .hex backend ---

    /** Forward [line] to the app's slash-command pipeline (sendInputInternal) on [network] or active. */
    fun appCommand(network: String?, buffer: String?, line: String)

    /** Apply a script UI-intent (decorate/action/sidebar/toast) into _state for ChatScreen to render. */
    fun uiIntent(kind: String, args: List<String>)

    /** Run [block] on the Main/script thread after [delayMs] (host owns the coroutine/timer). */
    fun postDelayed(delayMs: Long, block: () -> Unit)

    /** Put a script-built [ScriptView] tree into _state so ScriptSurface renders it. */
    fun mountView(view: ScriptView)

    /** Implement host capabilities (age.*, media.*, …). The VM maps names to native code. */
    fun capability(name: String, args: List<String>): String
}

/** Minimal HTTP request model handed to [ScriptHost.httpRequest]. */
data class ScriptHttpRequest(
    val url: String,
    val method: String,          // "GET" or "POST"
    val body: String? = null,
    val contentType: String = "application/json; charset=utf-8",
    val headers: Map<String, String> = emptyMap(),
)

/** Result of [ScriptHost.httpRequest]. On transport failure, [ok] is false and [error] is set. */
data class ScriptHttpResponse(
    val ok: Boolean,
    val status: Int,
    val body: String,
    val error: String? = null,
)
