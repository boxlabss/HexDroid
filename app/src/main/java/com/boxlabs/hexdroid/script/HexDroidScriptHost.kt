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

import android.util.Log
import com.boxlabs.hexdroid.IrcViewModel
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Bridges the script [ScriptEngine] to the running client.
 */
class HexDroidScriptHost(
    private val vm: IrcViewModel,
) : ScriptHost {

    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "hexdroid-scripts").apply { isDaemon = true }
    }

    /**
     * HTTP runs here, never on [worker]: [worker] is the single thread all script state is
     * serialised onto, and a fetch there stalls every script until its timeouts expire. Results are
     * marshalled back via runOnScriptThread, so script state is still single-threaded.
     */
    private val httpWorker = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "hexdroid-script-http").apply { isDaemon = true }
    }

    // ── IRC surface (delegated to the VM bridge) ────────────────────────────────
    override fun echo(network: String?, buffer: String?, from: String?, text: String) =
        vm.scriptEcho(network, buffer, from, text)

    override fun sendMessage(network: String?, buffer: String, text: String) =
        vm.scriptSendMessage(network, buffer, text)

    override fun sendRaw(network: String?, line: String) =
        vm.scriptSendRaw(network, line)

    override fun getSetting(key: String): String? = vm.scriptSetting(key)

    override fun nick(network: String?): String? = vm.scriptNick(network)

    override fun activeNetwork(): String? = vm.scriptActiveNetwork()

    override fun activeBuffer(): String? = vm.scriptActiveBuffer()

    override fun appCommand(network: String?, buffer: String?, line: String) =
        vm.scriptAppCommand(network, buffer, line)

    // ── Policy ──────────────────────────────────────────────────────────────────
    override fun isNetworkAllowed(url: String): Boolean = vm.scriptNetworkAllowed(url)

    // ── Threading ────────────────────────────────────────────────────────────────
    override fun runOnScriptThread(block: () -> Unit) { worker.execute { safe(block) } }

    override fun postDelayed(delayMs: Long, block: () -> Unit) {
        worker.schedule({ safe(block) }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private inline fun safe(block: () -> Unit) =
        try { block() } catch (t: Throwable) { Log.w(TAG, "script task failed", t) }

    // ── Logging ──────────────────────────────────────────────────────────────────
    override fun logDebug(scriptName: String?, message: String) {
        Log.d(TAG, if (scriptName != null) "[$scriptName] $message" else message)
    }

    // ── HTTP (self-contained; honours the allow-list) ─────────────────────────────

    private val maxRedirects = 5

    /**
     * Follows redirects by hand. instanceFollowRedirects must stay false: HttpURLConnection walks
     * the chain internally, so the policy check would only ever see the URL the script asked for
     * and any allowed host could 302 us onto a loopback or LAN address unchecked.
     */
    override fun httpRequest(req: ScriptHttpRequest, onResult: (ScriptHttpResponse) -> Unit) {
        httpWorker.execute {
            var url = req.url
            var method = req.method.uppercase()
            var body: String? = req.body
            var hops = 0
            while (true) {
                if (!vm.scriptNetworkAllowedResolved(url)) {
                    onResult(ScriptHttpResponse(ok = false, status = 0, body = "", error = "blocked by policy"))
                    return@execute
                }
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = method
                        connectTimeout = 15_000
                        readTimeout = 20_000
                        instanceFollowRedirects = false
                        req.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                        if (method == "POST" && body != null) {
                            doOutput = true
                            // Pick the content-type from the body shape: a JSON object/array stays JSON,
                            // but a `key=val&key=val` body (e.g. translate.hex's LibreTranslate call) must
                            // go out as form-urlencoded or the server rejects it as malformed JSON.
                            val trimmed = body!!.trimStart()
                            val ct = when {
                                trimmed.startsWith("{") || trimmed.startsWith("[") -> "application/json; charset=utf-8"
                                trimmed.contains("=") && !trimmed.contains(' ') -> "application/x-www-form-urlencoded; charset=utf-8"
                                else -> req.contentType
                            }
                            setRequestProperty("Content-Type", ct)
                            outputStream.use { it.write(body!!.toByteArray(Charsets.UTF_8)) }
                        }
                    }
                    val status = conn.responseCode
                    val location = if (status in 300..399) conn.getHeaderField("Location") else null
                    if (location != null && hops < maxRedirects) {
                        // Relative Locations resolve against the current URL; loop re-checks policy.
                        val next = runCatching { URL(URL(url), location).toString() }.getOrNull()
                            ?: run {
                                onResult(ScriptHttpResponse(ok = false, status = status, body = "", error = "bad redirect"))
                                return@execute
                            }
                        // 303, and 301/302 answering a POST, become GET. 307/308 keep method + body.
                        if (status == 303 || (method == "POST" && (status == 301 || status == 302))) {
                            method = "GET"
                            body = null
                        }
                        url = next
                        hops++
                        continue
                    }
                    if (location != null) {
                        onResult(ScriptHttpResponse(ok = false, status = status, body = "", error = "too many redirects"))
                        return@execute
                    }
                    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                    onResult(ScriptHttpResponse(ok = status in 200..299, status = status, body = text))
                    return@execute
                } catch (t: Throwable) {
                    onResult(ScriptHttpResponse(ok = false, status = 0, body = "", error = t.message ?: "http error"))
                    return@execute
                } finally {
                    conn?.disconnect()
                }
            }
        }
    }

    // ── UI surface ────────────────────────────────────────────────────────────────
    override fun mountView(view: ScriptView) { vm.scriptMountView(view) }

    override fun uiIntent(kind: String, args: List<String>) {
        when (kind) {
            // `toast <text…>`: transient on-screen feedback. Scripts (poker/blackjack/dice) use this
            // for almost all their user-facing status ("Joined the table", "Table keyed…", "Only the
            // host deals"), so leaving it unhandled made those flows look dead. Rejoin the split words.
            "toast" -> args.joinToString(" ").takeIf { it.isNotBlank() }?.let { vm.scriptToast(it) }
            // `sidebar add <id> <command> <label words…>` a script-contributed launcher.
            // `sidebar remove <id>` — drop it.
            "sidebar" -> when (args.getOrNull(0)) {
                "add" -> {
                    val id = args.getOrNull(1) ?: return
                    val command = args.getOrNull(2) ?: return
                    val label = args.drop(3).joinToString(" ").ifBlank { id }
                    vm.scriptRegisterLauncher(id, label, command)
                }
                "remove" -> args.getOrNull(1)?.let { vm.scriptUnregisterLauncher(it) }
                else -> Log.d(TAG, "sidebar intent ignored: $args")
            }
            else -> Log.d(TAG, "uiIntent ignored: $kind $args")
        }
    }

    // ── Capabilities ──────────────────────────────────────────────────────────────
    // age.* is handled by the VM: local loopback always (so solo/practice works), plus the real
    // encrypted +AGE transport when the channel is keyed. Everything else is unhandled (-> "").
    override fun capability(name: String, args: List<String>): String =
        if (name.startsWith("age.")) vm.scriptAgeCapability(name, args) else ""

    fun shutdown() { worker.shutdownNow() }

    private companion object { const val TAG = "HexScript" }
}
