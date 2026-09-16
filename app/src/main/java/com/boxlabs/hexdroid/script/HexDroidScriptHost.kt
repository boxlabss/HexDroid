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

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.boxlabs.hexdroid.IrcViewModel
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Bridges the script [ScriptEngine] to the running client.
 *
 * Script state lives on the main thread: events and commands already call in from there, so
 * async results and timers are posted there too.
 */
class HexDroidScriptHost(
    private val vm: IrcViewModel,
) : ScriptHost {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var closed = false

    /** Blocking HTTP and uploads. Results are posted back to the main thread. */
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
    override fun runOnScriptThread(block: () -> Unit) {
        if (closed) return
        main.post { if (!closed) safe(block) }
    }

    override fun postDelayed(delayMs: Long, block: () -> Unit) {
        if (closed) return
        main.postDelayed({ if (!closed) safe(block) }, delayMs.coerceAtLeast(MIN_TIMER_MS))
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
                            // An explicit type from the script wins. Otherwise pick from the body
                            // shape: a JSON object/array stays JSON, but a `key=val&key=val` body
                            // (e.g. translate.hex's LibreTranslate call) must go out as
                            // form-urlencoded or the server rejects it as malformed JSON.
                            val trimmed = body.trimStart()
                            val ct = req.contentType ?: when {
                                trimmed.startsWith("{") || trimmed.startsWith("[") -> "application/json; charset=utf-8"
                                trimmed.contains("=") && !trimmed.contains(' ') -> "application/x-www-form-urlencoded; charset=utf-8"
                                else -> "text/plain; charset=utf-8"
                            }
                            setRequestProperty("Content-Type", ct)
                            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
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
                    val text = readBody(stream)
                    // Upload endpoints answer 201 with the new URL in Location and often an empty
                    // body, so it is carried through rather than read off the body.
                    onResult(
                        ScriptHttpResponse(
                            ok = status in 200..299,
                            status = status,
                            body = text,
                            location = conn.getHeaderField("Location"),
                        )
                    )
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
    override fun capability(name: String, args: List<String>): String = when {
        name.startsWith("age.") -> vm.scriptAgeCapability(name, args)
        // Read-only display metrics so scripts can pick a layout: $screen.landscape,
        // $screen.width / $screen.height (dp), $screen.tv. Values are read at call time,
        // so a re-render after rotation (see SIGNAL:screenchange) always sees fresh ones.
        name.startsWith("screen.") -> vm.scriptScreenInfo(name.removePrefix("screen."))
        else -> ""
    }

    // ── media ─────────────────────────────────────────────────────────────────────
    override fun mediaPick(
        network: String?,
        buffer: String?,
        mimeFilter: String,
        owner: String,
        userInitiated: Boolean,
        onResult: (ScriptMediaRef?) -> Unit,
    ) {
        vm.scriptMediaPick(network, buffer, mimeFilter, owner, userInitiated, onResult)
    }

    /**
     * Upload a picked file to [ScriptUploadRequest.url]. The bytes never pass through the
     * script: it holds a token, and the URI behind it stays in the VM. Policy is re-checked
     * here because the token may have been issued long before this call.
     */
    override fun mediaUpload(req: ScriptUploadRequest, onResult: (ScriptHttpResponse) -> Unit) {
        httpWorker.execute {
            if (!vm.scriptNetworkAllowedResolved(req.url)) {
                onResult(ScriptHttpResponse(ok = false, status = 0, body = "", error = "blocked by policy"))
                return@execute
            }
            val source = vm.scriptMediaSource(req.token, req.owner)
            if (source == null) {
                onResult(ScriptHttpResponse(ok = false, status = 0, body = "", error = "unknown file token"))
                return@execute
            }
            val (uri, name, mime) = source
            val input = vm.scriptOpenMedia(uri)
            if (input == null) {
                onResult(ScriptHttpResponse(ok = false, status = 0, body = "", error = "cannot open file"))
                return@execute
            }
            var conn: HttpURLConnection? = null
            var staged: java.io.File? = null
            try {
                val boundary = "hexdroid" + java.util.UUID.randomUUID().toString().replace("-", "")

                // The request body is assembled on disk first so its exact length is known.
                // Chunked encoding is what a streaming upload would need, and a server that
                // parses multipart from CONTENT_LENGTH (PHP's does) sees no parts at all in a
                // chunked request and reports an empty upload. The provider's reported size is
                // not usable for the length either, since it can disagree with what the stream
                // yields; measuring the staged copy cannot.
                val file = try {
                    java.io.File.createTempFile("body", null, vm.scriptUploadCacheDir())
                } catch (t: Throwable) {
                    input.close()
                    throw t
                }
                staged = file
                input.use { raw ->
                    val inp = CappedInputStream(raw, MAX_UPLOAD_BYTES)
                    java.io.FileOutputStream(file).buffered().use { out ->
                        if (req.field != null) {
                            val safeName = com.boxlabs.hexdroid.FilehostUpload.sanitizeFileName(name)
                            // Text fields first: an endpoint reading an option out of $_POST
                            // needs it in the same body as the file.
                            for ((k, v) in req.formFields) {
                                out.write(
                                    ("--$boundary\r\n" +
                                        "Content-Disposition: form-data; name=\"${headerToken(k)}\"\r\n\r\n" +
                                        v + "\r\n").toByteArray(Charsets.UTF_8)
                                )
                            }
                            out.write(
                                ("--$boundary\r\n" +
                                    "Content-Disposition: form-data; name=\"${headerToken(req.field)}\"; " +
                                    "filename=\"$safeName\"\r\n" +
                                    "Content-Type: ${headerToken(mime)}\r\n\r\n").toByteArray(Charsets.UTF_8)
                            )
                            inp.copyTo(out)
                            out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                        } else {
                            inp.copyTo(out)
                        }
                    }
                }

                val bodyLength = file.length()
                conn = (URL(req.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    // Redirects are not followed for an upload: a 3xx to another host would
                    // send the file somewhere the policy check never saw.
                    instanceFollowRedirects = false
                    setFixedLengthStreamingMode(bodyLength)
                    req.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    setRequestProperty(
                        "Content-Type",
                        if (req.field != null) "multipart/form-data; boundary=$boundary" else mime,
                    )
                }
                conn.outputStream.use { out ->
                    java.io.FileInputStream(file).buffered().use { it.copyTo(out) }
                }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = readBody(stream)
                onResult(
                    ScriptHttpResponse(
                        ok = status in 200..299,
                        status = status,
                        body = text,
                        location = conn.getHeaderField("Location"),
                    )
                )
            } catch (_: CappedInputStream.LimitExceeded) {
                onResult(ScriptHttpResponse(ok = false, status = 0, body = "", error = "file too large (limit ${MAX_UPLOAD_BYTES / (1024 * 1024)} MB)"))
            } catch (t: Throwable) {
                onResult(ScriptHttpResponse(ok = false, status = 0, body = "", error = t.message ?: "upload failed"))
            } finally {
                staged?.delete()
                conn?.disconnect()
            }
        }
    }

    /** A response body as text, cut off at [MAX_RESPONSE_CHARS]. */
    private fun readBody(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return stream.bufferedReader().use { reader ->
            val out = StringBuilder()
            val buf = CharArray(8192)
            while (out.length < MAX_RESPONSE_CHARS) {
                val n = reader.read(buf, 0, minOf(buf.size, MAX_RESPONSE_CHARS - out.length))
                if (n < 0) break
                out.append(buf, 0, n)
            }
            out.toString()
        }
    }

    /**
     * Strip anything that could end a header or a quoted string. The form field name comes from
     * the script and the MIME type from the content resolver, and both land inside a header.
     */
    private fun headerToken(raw: String): String =
        raw.filter { it.code in 0x20..0x7e && it != '"' && it != ';' }.take(64).ifBlank { "file" }

    override fun shutdown() {
        closed = true
        main.removeCallbacksAndMessages(null)
        httpWorker.shutdownNow()
    }

    /** Fails once more than [limit] bytes have been read. */
    private class CappedInputStream(
        inner: java.io.InputStream,
        private val limit: Long,
    ) : java.io.FilterInputStream(inner) {
        class LimitExceeded : java.io.IOException("upload size limit exceeded")

        private var count = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) add(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) add(n.toLong())
            return n
        }

        private fun add(n: Long) {
            count += n
            if (count > limit) throw LimitExceeded()
        }
    }

    private companion object {
        const val TAG = "HexScript"
        const val MAX_UPLOAD_BYTES = 64L * 1024 * 1024
        const val MAX_RESPONSE_CHARS = 1024 * 1024
        /** Floor on timer delays, so a script re-arming a zero timer cannot spin the main thread. */
        const val MIN_TIMER_MS = 20L
    }
}
