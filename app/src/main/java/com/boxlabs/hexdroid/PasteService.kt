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

package com.boxlabs.hexdroid

import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads text to a paste service and returns its link. Two kinds are understood: the Paste API
 * (JSON, unlisted, kept for a month), and any service that takes the text as the request body and
 * answers with its link. Pure JVM so it can be unit tested.
 */
internal object PasteService {

    /** The paste service used unless the user sets another. */
    const val DEFAULT_SERVICE = "https://pastebin.cloud"

    /** Paste sites whose bare address stands for their api.php. */
    private val PASTE_HOSTS = setOf("pastebin.cloud", "paste.boxlabs.uk")

    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private val FIRST_URL = Regex("https?://[^\\s\"'<>]+")

    /** True when [url] is an https address this can post to. */
    fun isUsable(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return false
        return runCatching { URL(u).protocol.equals("https", ignoreCase = true) }.getOrDefault(false)
    }

    /** The Paste api.php address [url] stands for: an api.php itself, or a known Paste site. Else null. */
    private fun pasteApiUrl(url: String): String? {
        val u = url.trim()
        val parsed = runCatching { URL(u) }.getOrNull() ?: return null
        if (parsed.path.endsWith("api.php", ignoreCase = true)) return u
        val host = parsed.host.lowercase().removePrefix("www.")
        if (host !in PASTE_HOSTS || (parsed.path.isNotEmpty() && parsed.path != "/") || parsed.query != null) return null
        return "${parsed.protocol}://${parsed.authority}/api.php"
    }

    /** True when [url] is a Paste API endpoint or a known Paste site. */
    fun isPasteApi(url: String): Boolean = pasteApiUrl(url) != null

    /** The address to post to: a Paste API address gets action=paste unless an action is already given. */
    fun endpoint(url: String): String {
        val api = pasteApiUrl(url) ?: return url.trim()
        if (Regex("[?&]action=").containsMatchIn(api)) return api
        return api + (if ('?' in api) "&" else "?") + "action=paste"
    }

    /** The JSON body for a Paste api.php upload. */
    fun pasteApiBody(text: String, title: String): String =
        "{\"content\":${jsonString(text)},\"title\":${jsonString(title)}," +
            "\"syntax\":\"text\",\"visibility\":\"unlisted\",\"expiry\":\"1M\"}"

    /** [s] as a JSON string literal. */
    fun jsonString(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /** The first JSON "key":"value" string in [json], unescaped, or null. Enough for a small answer. */
    private fun jsonField(json: String, key: String): String? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return null
        return jsonUnescape(m.groupValues[1])
    }

    /** The text of a JSON string literal's body. */
    fun jsonUnescape(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                out.append(c)
                i++
                continue
            }
            when (val e = s[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000c')
                'u' -> {
                    val code = s.substring(i + 2, minOf(i + 6, s.length)).takeIf { it.length == 4 }?.toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                    out.append(e)
                }
                else -> out.append(e)
            }
            i += 2
        }
        return out.toString()
    }

    /** True when [json] has "key": true or false as [value]. */
    private fun jsonFlag(json: String, key: String, value: Boolean): Boolean =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*" + value).containsMatchIn(json)

    /**
     * The link in a paste service's answer: the JSON "url" or "link" field (Paste puts it in its
     * "paste" object), or else the first http(s) address in the body.
     */
    fun linkFromResponse(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            for (key in listOf("url", "link")) {
                jsonField(trimmed, key)?.takeIf { it.startsWith("http") }?.let { return it }
            }
        }
        return FIRST_URL.find(trimmed)?.value?.trimEnd('.', ',', ')')
    }

    /** The "error" field of a JSON answer, if any. */
    fun errorFromResponse(body: String): String? = jsonField(body.trim(), "error")?.takeIf { it.isNotBlank() }

    /**
     * Why a successful-looking JSON answer gives no usable link: "success": false, or a paste held
     * for moderation, whose link does not open until it is approved. Null when the answer is usable.
     */
    fun refusalFromResponse(body: String): String? {
        val t = body.trim()
        if (!t.startsWith("{")) return null
        if (jsonFlag(t, "success", false)) return errorFromResponse(t) ?: "the service refused the paste"
        if (jsonField(t, "status") == "pending_review") {
            return jsonField(t, "message")?.takeIf { it.isNotBlank() } ?: "the paste is awaiting moderator approval"
        }
        return null
    }

    /** Uploads [text] to [serviceUrl] and returns its link. Blocking. */
    fun upload(serviceUrl: String, text: String, title: String): FilehostUpload.Result {
        if (!isUsable(serviceUrl)) return FilehostUpload.Result(null, "Paste service must be an https:// address")
        val api = isPasteApi(serviceUrl)
        val body = (if (api) pasteApiBody(text, title) else text).toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(endpoint(serviceUrl)).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "HexDroid IRC")
            conn.setRequestProperty(
                "Content-Type",
                if (api) "application/json; charset=utf-8" else "text/plain; charset=utf-8",
            )
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val answer = stream?.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (buf.size() < MAX_RESPONSE_BYTES) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                buf.toString("UTF-8")
            }.orEmpty()
            if (code !in 200..299) {
                val detail = errorFromResponse(answer)
                return FilehostUpload.Result(null, "Paste failed (HTTP $code)" + (detail?.let { ": $it" } ?: ""))
            }
            refusalFromResponse(answer)?.let { return FilehostUpload.Result(null, "Paste failed: $it") }
            val link = linkFromResponse(answer)
                ?: conn.getHeaderField("Location")?.takeIf { it.startsWith("http") }
            if (link != null) FilehostUpload.Result(link, null)
            else FilehostUpload.Result(null, "Paste failed: the service did not return a link")
        } catch (t: Throwable) {
            FilehostUpload.Result(null, "Paste failed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }
}
