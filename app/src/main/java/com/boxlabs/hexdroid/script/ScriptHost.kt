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
 * The bridge from [ScriptEngine] to the app. The engine knows nothing of the ViewModel, Android,
 * OkHttp or Compose; everything a script does outside itself goes through here.
 */
interface ScriptHost {

    /**
     * Print a local-only line into a buffer; never sent.
     *
     * @param network Network id, or null for the active network.
     * @param buffer Buffer name, or null for the selected buffer.
     * @param from Nick to attribute the line to, or null for a system line.
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
     * Run [block] on the main thread, where every other call into the engine is made.
     * Used to deliver async callbacks (HTTP completions, picks) back to the script state.
     */
    fun runOnScriptThread(block: () -> Unit)

    /** Diagnostic logging from a script's log()/console — route to Logcat and/or a script console buffer. */
    fun logDebug(scriptName: String?, message: String)

    // --- added for the .hex backend ---

    /** Forward [line] to the app's slash-command pipeline (sendInputInternal) on [network] or active. */
    fun appCommand(network: String?, buffer: String?, line: String)

    /** Apply a script UI-intent (decorate/action/sidebar/toast) into _state for ChatScreen to render. */
    fun uiIntent(kind: String, args: List<String>)

    /** Run [block] on the main thread after [delayMs] (host owns the timer). */
    fun postDelayed(delayMs: Long, block: () -> Unit)

    /** Put a script-built [ScriptView] tree into _state so ScriptSurface renders it. */
    fun mountView(view: ScriptView)

    /** Implement host capabilities (age.*, media.*, …). The VM maps names to native code. */
    fun capability(name: String, args: List<String>): String

    /**
     * Ask the user to choose a file for a script, filtered to [mimeFilter]. The host shows a
     * prompt naming [network] and [buffer], the script's own context, and opens the system
     * picker only if the user agrees. The resulting token is bound to [owner]. A request not
     * [userInitiated] may be refused without a prompt after a recent decline. [onResult] gets
     * null when the user declines or picks nothing, and may be invoked on any thread.
     */
    fun mediaPick(
        network: String?,
        buffer: String?,
        mimeFilter: String,
        owner: String,
        userInitiated: Boolean,
        onResult: (ScriptMediaRef?) -> Unit,
    )

    /** Upload a file the user picked, addressed by the token from [mediaPick]. */
    fun mediaUpload(req: ScriptUploadRequest, onResult: (ScriptHttpResponse) -> Unit)

    /**
     * Release whatever the host holds for scripts: threads, timers, pending callbacks. Called
     * from [ScriptEngine.shutdown]. Default no-op for a host that holds nothing.
     */
    fun shutdown() {}
}

/**
 * A file the user chose for a script. [token] is an opaque handle the host resolves back to
 * a content URI; scripts never see a path and cannot construct one.
 */
data class ScriptMediaRef(
    val token: String,
    val name: String,
    val mime: String,
    val size: Long,
)

/** Upload of a picked file. [field] null POSTs the bytes as the raw body instead of multipart. */
data class ScriptUploadRequest(
    val url: String,
    val token: String,
    val field: String? = "file",
    val headers: Map<String, String> = emptyMap(),
    /** Extra text form fields sent alongside the file. Multipart only; ignored when raw. */
    val formFields: Map<String, String> = emptyMap(),
    /** The script making the upload; must match the script the token was issued to. */
    val owner: String = "",
)

/** Minimal HTTP request model handed to [ScriptHost.httpRequest]. */
data class ScriptHttpRequest(
    val url: String,
    val method: String,          // "GET" or "POST"
    val body: String? = null,
    /** Null lets the host infer a type from the body's shape. */
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/** Result of [ScriptHost.httpRequest]. On transport failure, [ok] is false and [error] is set. */
data class ScriptHttpResponse(
    val ok: Boolean,
    val status: Int,
    val body: String,
    val error: String? = null,
    /** Location header of the final response, which 201 Created uses to name the new resource. */
    val location: String? = null,
)
