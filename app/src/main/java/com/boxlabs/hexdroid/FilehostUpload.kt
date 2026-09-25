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

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Upload client for soju.im/FILEHOST (draft/FILEHOST): POST the file to the advertised URL with the
 * connection's credentials as HTTP Basic, and read the public URL from the 201 response's Location
 * header. Pure JVM so it can be unit tested.
 */
internal object FilehostUpload {

    data class Result(val url: String?, val error: String?) {
        val ok: Boolean get() = url != null
    }

    /** Marker on [Result.error] for a body that did not match the declared Content-Length. */
    private const val LENGTH_MISMATCH = "content-length mismatch"

    /**
     * True when [error] is the declared length disagreeing with the bytes the file actually
     * yielded, which the caller can retry without a declared length.
     */
    fun isLengthMismatch(error: String?): Boolean = error != null && error.contains(LENGTH_MISMATCH)

    /**
     * Sanitise a display name for use inside a Content-Disposition filename
     * parameter: strip path components, quotes, backslashes and control
     * characters, replace non-ASCII with '_' so the header stays a valid
     * ISO-8859-1 token. Falls back to "file" when nothing survives.
     */
    fun sanitizeFileName(raw: String?): String {
        val base = (raw ?: "")
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringAfterLast(':')
        val cleaned = buildString {
            for (ch in base) {
                append(
                    when {
                        ch == '"' || ch == '\\' -> '_'
                        ch.code < 0x20 || ch.code == 0x7f -> '_'
                        ch.code > 0x7e -> '_'
                        else -> ch
                    }
                )
            }
        }.trim()
        return cleaned.ifBlank { "file" }
    }

    /**
     * True when [a] and [b] plausibly belong to the same operator: equal, one a subdomain of
     * the other, or siblings under a parent that is not a bare two-letter-TLD suffix such as
     * co.uk. A heuristic without a public suffix list, erring towards false.
     */
    fun sameSite(a: String, b: String): Boolean {
        val x = a.lowercase().trimEnd('.')
        val y = b.lowercase().trimEnd('.')
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        if (isIpLiteral(x) || isIpLiteral(y)) return false
        if (x.endsWith(".$y") || y.endsWith(".$x")) return true
        val px = x.substringAfter('.', "")
        val py = y.substringAfter('.', "")
        if (px.isEmpty() || px != py) return false
        val labels = px.split('.')
        return labels.size >= 3 || (labels.size == 2 && labels[1].length > 2)
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.all { it.isDigit() || it == '.' }

    /**
     * Upload [input] to [uploadUrl] and return the public URL. Blocking; the caller closes [input].
     *
     * @param username Credentials sent as HTTP Basic when both are present.
     * @param withheldReason Why credentials were withheld, shown if the server asks for them.
     * @param connectionUsesTls Refuse an http:// URL when the IRC connection is encrypted.
     * @param contentLength Exact size for fixed-length streaming, or <= 0 for chunked.
     */
    fun upload(
        uploadUrl: String,
        username: String?,
        password: String?,
        fileName: String?,
        mimeType: String?,
        contentLength: Long,
        input: InputStream,
        connectionUsesTls: Boolean,
        withheldReason: String? = null,
        connectTimeoutMs: Int = 30_000,
        readTimeoutMs: Int = 120_000,
    ): Result {
        val base = try {
            URL(uploadUrl)
        } catch (e: Exception) {
            return Result(null, "Invalid filehost URL: $uploadUrl")
        }
        when (base.protocol.lowercase()) {
            "https" -> Unit
            "http" -> if (connectionUsesTls) {
                return Result(
                    null,
                    "Refusing upload: server advertises a plaintext http:// filehost while this connection uses TLS"
                )
            }
            else -> return Result(null, "Unsupported filehost URL scheme: ${base.protocol}")
        }

        var conn: HttpURLConnection? = null
        var sent = 0L
        try {
            conn = base.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
            conn.setRequestProperty(
                "Content-Disposition",
                "attachment; filename=\"${sanitizeFileName(fileName)}\""
            )
            if (!username.isNullOrBlank() && password != null) {
                val cred = Base64.getEncoder()
                    .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
                conn.setRequestProperty("Authorization", "Basic $cred")
            }
            if (contentLength > 0) {
                conn.setFixedLengthStreamingMode(contentLength)
            } else {
                conn.setChunkedStreamingMode(0)
            }

            // A file longer than declared fails in write(). A shorter one is caught here and the
            // stream abandoned, so the server never sees a complete request.
            val out = conn.outputStream
            var completed = false
            var short = false
            try {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sent += n
                    out.write(buf, 0, n)
                }
                short = contentLength > 0 && sent < contentLength
                completed = true
            } finally {
                if (completed && !short) out.close() else runCatching { out.close() }
            }
            if (short) {
                return Result(null, "Upload failed: $LENGTH_MISMATCH (expected $contentLength bytes, read $sent)")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = readErrorDetail(conn)
                // Redirects are not followed: the body is a one-shot stream and cannot be
                // replayed, and a 3xx to another host would move the file somewhere the
                // scheme and credential checks above never saw. Name the destination so a
                // server that only wants a trailing slash is diagnosable rather than a
                // bare "HTTP 301".
                if (code in 300..399) {
                    val to = conn.getHeaderField("Location")
                    return Result(
                        null,
                        if (to.isNullOrBlank()) "Upload failed: server redirected (HTTP $code)"
                        else "Upload failed: server redirected to $to (HTTP $code). Set the filehost URL to that address.",
                    )
                }
                val summary = when (code) {
                    401, 403 -> if (withheldReason != null) {
                        "Upload rejected: authentication required ($code). Credentials were not sent: $withheldReason"
                    } else {
                        "Upload rejected: authentication failed ($code)"
                    }
                    413 -> "Upload rejected: file too large for this server (413)"
                    else -> "Upload failed: HTTP $code"
                }
                return Result(null, if (detail.isEmpty()) summary else "$summary: $detail")
            }

            val location = conn.getHeaderField("Location")
                ?: return Result(null, "Upload succeeded (HTTP $code) but the server sent no Location header")
            // Location may be relative; resolve it against the upload URL.
            val resolvedUrl = try {
                URL(base, location)
            } catch (e: Exception) {
                return Result(null, "Server sent an unparsable Location header")
            }
            val scheme = resolvedUrl.protocol.lowercase()
            if (scheme != "https" && scheme != "http") {
                return Result(null, "Server returned a file URL with scheme $scheme; only http and https links are accepted")
            }
            val resolved = resolvedUrl.toString()
            // The public URL must not downgrade either: a TLS connection should
            // never paste an http:// link the uploader itself will then fetch.
            if (connectionUsesTls && resolved.startsWith("http://")) {
                return Result(null, "Server returned a plaintext http:// file URL; refusing on a TLS connection")
            }
            return Result(resolved, null)
        } catch (e: java.io.IOException) {
            // Only a file that outgrew its declared length is retryable. A short one is caught
            // above without an exception, and a broken connection is not a length problem.
            if (contentLength > 0 && sent > contentLength) {
                return Result(null, "Upload failed: $LENGTH_MISMATCH (${e.message ?: "length"})")
            }
            return Result(null, "Upload failed: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            return Result(null, "Upload failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    /** The start of an error body, with control characters and runs of whitespace collapsed. */
    private fun readErrorDetail(conn: HttpURLConnection): String = runCatching {
        conn.errorStream?.use { stream ->
            val bytes = ByteArray(512)
            var total = 0
            while (total < bytes.size) {
                val n = stream.read(bytes, total, bytes.size - total)
                if (n < 0) break
                total += n
            }
            String(bytes, 0, total, StandardCharsets.UTF_8)
                .replace(Regex("[\\p{Cntrl}\\s]+"), " ")
                .trim()
                .take(200)
        }.orEmpty()
    }.getOrDefault("")
}
