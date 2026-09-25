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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bounded image fetcher for server-supplied images (network icons, metadata avatars). Callers must
 * first check HTTPS, the image-previews opt-in and an unproxied profile, since this bypasses any
 * proxy. Byte and pixel caps, short timeouts, no redirects; failures mean no image.
 */
object RemoteImage {

    /** Decoded images keyed by resolved URL, least-recently-used. */
    private val cache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) =
            size > MAX_CACHED
    }

    /** Fetches in progress, so several rows asking for one URL make one request. */
    private val inFlight = HashMap<String, Deferred<ImageBitmap?>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How many decoded images to hold. Covers a member list several times over. */
    private const val MAX_CACHED = 64

    /** Hard ceiling on a downloaded image, applied while streaming. */
    private const val MAX_BYTES = 262_144

    /**
     * Longest edge to decode to, in pixels.
     *
     * Nothing draws these larger than 24dp, and the byte cap alone does not bound memory:
     * a flat image compresses to a few kilobytes and decodes to hundreds of megabytes.
     */
    private const val MAX_EDGE_PX = 256

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    /** Cached bitmap for [url], or null when it has not been fetched yet. */
    fun cached(url: String): ImageBitmap? = synchronized(cache) { cache[url] }

    /**
     * Fetch and decode [url], sampled down to [MAX_EDGE_PX]. Returns null on any failure,
     * oversize response, or undecodable payload. Safe to call repeatedly: a cached result
     * short-circuits and concurrent calls for one URL share a single request.
     */
    suspend fun fetch(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        val request = synchronized(inFlight) {
            inFlight[url] ?: scope.async { download(url) }.also { job ->
                inFlight[url] = job
                job.invokeOnCompletion { synchronized(inFlight) { inFlight.remove(url) } }
            }
        }
        return request.await()
    }

    private fun download(url: String): ImageBitmap? = runCatching {
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
            decodeSampled(bytes)?.asImageBitmap()?.also { bmp ->
                synchronized(cache) { cache[url] = bmp }
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** Decode [bytes] with the longest edge no larger than [MAX_EDGE_PX]. */
    private fun decodeSampled(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight))
        }
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** The power of two that brings [edgePx] within [MAX_EDGE_PX]. */
    private fun sampleSizeFor(edgePx: Int): Int {
        var sample = 1
        while (edgePx / sample > MAX_EDGE_PX) sample *= 2
        return sample
    }
}
