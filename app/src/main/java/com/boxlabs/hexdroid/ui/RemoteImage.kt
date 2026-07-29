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

package com.boxlabs.hexdroid.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Small bounded image fetcher shared by the two places that render a
 * server-supplied image: the ICON / draft/ICON network icon (Networks screen,
 * sidebar, switcher) and draft/metadata-2 avatars (nick list).
 *
 * Callers are responsible for the policy gate before calling: HTTPS only, the
 * user's image-previews opt-in enabled, and an unproxied profile. That last one
 * matters most - HttpURLConnection does not route through SocksProxy.kt, so
 * fetching a server-supplied URL from a Tor/SOCKS profile would leave the proxy
 * and leak the user's IP. Same fail-closed rule as filehost uploads.
 *
 * Everything else is defence in depth: a hard byte cap so a hostile URL cannot
 * exhaust memory, short timeouts, no redirect following (which also means an
 * https URL can never be bounced to plaintext http), and failures that resolve
 * to "no image" rather than an error.
 */
object RemoteImage {

    /** Process-lifetime cache keyed by resolved URL. Entries are small and few. */
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    /** Hard ceiling on a downloaded image, applied while streaming. */
    private const val MAX_BYTES = 262_144

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    /** Cached bitmap for [url], or null when it has not been fetched yet. */
    fun cached(url: String): ImageBitmap? = cache[url]

    /**
     * Fetch and decode [url]. Returns null on any failure, oversize response, or
     * undecodable payload. Safe to call repeatedly; a cached result short-circuits.
     */
    suspend fun fetch(url: String): ImageBitmap? {
        cache[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    if (conn.contentLengthLong > MAX_BYTES) return@runCatching null
                    val buf = ByteArrayOutputStream()
                    conn.inputStream.use { input ->
                        // Manual copy loop rather than readNBytes: minSdk is 26 and
                        // that API landed in Android 13 (API 33).
                        val chunk = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            buf.write(chunk, 0, n)
                            if (buf.size() > MAX_BYTES) return@runCatching null
                        }
                    }
                    val bytes = buf.toByteArray()
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?.asImageBitmap()
                        ?.also { cache[url] = it }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
    }
}
