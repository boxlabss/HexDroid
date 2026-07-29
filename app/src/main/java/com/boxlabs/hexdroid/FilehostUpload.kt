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
 * HTTP upload client for the soju.im/FILEHOST extension (also advertised as
 * draft/FILEHOST by the pending IRCv3 spec).
 *
 * Protocol: the server advertises an upload URL via an ISUPPORT (005) token.
 * The client POSTs the raw file bytes to that URL, authenticating with the
 * same credentials as the IRC connection (HTTP Basic with the SASL PLAIN
 * identity). On success the server answers 201 Created with a Location header
 * naming the public URL of the uploaded file, which the client then pastes
 * into a message.
 *
 * Deliberately pure JVM (no Android imports) so it can be unit-tested and
 * compiled off-device, matching IrcParser's testing story.
 */
internal object FilehostUpload {

    data class Result(val url: String?, val error: String?) {
        val ok: Boolean get() = url != null
    }

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
     * Upload [input] to [uploadUrl].
     *
     * [username]/[password]: IRC connection credentials, sent as HTTP Basic
     * when both are present. For soju this is the same user[/network][@client]
     * identity used for SASL PLAIN.
     *
     * [connectionUsesTls]: when true, an http:// filehost URL is refused so a
     * misconfigured server cannot silently downgrade credentials and file
     * contents to plaintext while the IRC connection itself is encrypted.
     *
     * [contentLength]: exact byte count when known (enables fixed-length
     * streaming); pass a value <= 0 to fall back to chunked streaming.
     *
     * Blocking: call from a background thread. The caller owns [input] and
     * should close it; this function does not.
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

            conn.outputStream.use { out ->
                input.copyTo(out, bufferSize = 64 * 1024)
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                // Drain the error stream so the connection can be reused/closed cleanly.
                runCatching { conn.errorStream?.use { it.skip(Long.MAX_VALUE) } }
                return Result(
                    null,
                    when (code) {
                        401, 403 -> "Upload rejected: authentication failed ($code)"
                        413 -> "Upload rejected: file too large for this server (413)"
                        else -> "Upload failed: HTTP $code"
                    }
                )
            }

            val location = conn.getHeaderField("Location")
                ?: return Result(null, "Upload succeeded (HTTP $code) but the server sent no Location header")
            // Location may be relative; resolve it against the upload URL.
            val resolved = try {
                URL(base, location).toString()
            } catch (e: Exception) {
                return Result(null, "Server sent an unparsable Location header")
            }
            // The public URL must not downgrade either: a TLS connection should
            // never paste an http:// link the uploader itself will then fetch.
            if (connectionUsesTls && resolved.startsWith("http://")) {
                return Result(null, "Server returned a plaintext http:// file URL; refusing on a TLS connection")
            }
            return Result(resolved, null)
        } catch (e: Exception) {
            return Result(null, "Upload failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }
}
