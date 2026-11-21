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

import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.LinkTitle
import com.boxlabs.hexdroid.LinkTitleParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fetches page titles for link previews. Only https pages on public addresses are fetched, without
 * cookies, reading at most the first [MAX_BYTES] of the page. Results, misses included, are cached.
 */
internal object LinkTitleFetcher {

    private const val MAX_BYTES = 256 * 1024
    private val MISSING = LinkTitle("")

    private val cache = LruCache<String, LinkTitle>(256)
    private val inFlight = ConcurrentHashMap<String, Deferred<LinkTitle?>>()

    /** Fetches run here, so one finishes and is cached even when the message that asked scrolls away. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True for an address on the public internet. */
    fun isPublic(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
            a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        return when (a) {
            // 0.0.0.0/8, 100.64.0.0/10 carrier-grade NAT, 198.18.0.0/15 benchmarking, 240.0.0.0/4 reserved.
            is Inet4Address -> {
                val o0 = b[0].toInt() and 0xff
                val o1 = b[1].toInt() and 0xff
                !(o0 == 0 || o0 >= 240 || (o0 == 100 && (o1 and 0xc0) == 64) || (o0 == 198 && (o1 and 0xfe) == 18))
            }
            // fc00::/7, unique local.
            is Inet6Address -> (b[0].toInt() and 0xfe) != 0xfc
            else -> true
        }
    }

    private object PublicDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val found = Dns.SYSTEM.lookup(hostname).filter { isPublic(it) }
            if (found.isEmpty()) throw UnknownHostException("$hostname has no public address")
            return found
        }
    }

    /** Refuses a connection to a non-public address, which an IP literal reaches without DNS. */
    private val publicOnly = Interceptor { chain ->
        val addr = chain.connection()?.route()?.socketAddress?.address
        if (addr != null && !isPublic(addr)) throw IOException("Refusing a non-public address")
        chain.proceed(chain.request())
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .dns(PublicDns)
            .addNetworkInterceptor(publicOnly)
            .build()
    }

    /** True when [url] is one this fetches: https, with no user info. */
    fun accepts(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val authority = url.substring(8).substringBefore('/').substringBefore('?').substringBefore('#')
        return authority.isNotEmpty() && '@' !in authority
    }

    /** The cached result for [url]: a title, [MISSING] for a known miss, or null when not fetched. */
    fun cached(url: String): LinkTitle? = cache.get(url)

    fun isMiss(result: LinkTitle?): Boolean = result === MISSING

    /** The title of [url], fetched once however many messages show it. */
    suspend fun fetch(url: String): LinkTitle? {
        cache.get(url)?.let { return it.takeIf { r -> r !== MISSING } }
        val created = scope.async(start = CoroutineStart.LAZY) {
            val result = runCatching { load(url) }.getOrNull()
            cache.put(url, result ?: MISSING)
            inFlight.remove(url)
            result
        }
        val existing = inFlight.putIfAbsent(url, created)
        if (existing != null) {
            created.cancel()
            return existing.await()
        }
        created.start()
        return created.await()
    }

    private fun load(url: String): LinkTitle? {
        if (!accepts(url)) return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; HexDroid IRC link preview)")
            .header("Accept", "text/html,application/xhtml+xml;q=0.9")
            .header("Accept-Language", Locale.getDefault().toLanguageTag())
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val type = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            if ("html" !in type) return null
            val bytes = response.body.byteStream().use { readUpTo(it, MAX_BYTES) }
            val headerCharset = Regex("charset=([A-Za-z0-9_.:-]+)").find(type)?.groupValues?.get(1)
            val declared = headerCharset ?: LinkTitleParser.declaredCharset(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))
            val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            return LinkTitleParser.parse(String(bytes, charset))
        }
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/** The first link in a message worth a title preview: https, and not one shown as media. */
internal fun linkPreviewUrl(urls: List<String>, mediaPreviewsShown: Boolean): String? =
    urls.firstOrNull { url ->
        LinkTitleFetcher.accepts(url) && !(mediaPreviewsShown && hasInlineMediaPreview(url)) && !isPreviewableImageUrl(url)
    }

/** A small card with the linked page's site, title and description. Tapping opens the link. */
@Composable
fun LinkPreviewCard(url: String, wifiOnly: Boolean) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var result by remember(url) { mutableStateOf(LinkTitleFetcher.cached(url)) }
    LaunchedEffect(url) {
        if (result != null) return@LaunchedEffect
        if (wifiOnly && !isOnWifi(context)) return@LaunchedEffect
        result = LinkTitleFetcher.fetch(url)
    }
    val t = result?.takeIf { !LinkTitleFetcher.isMiss(it) } ?: return
    val host = remember(url) { runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth(0.85f)
            .focusHighlight(RoundedCornerShape(8.dp))
            .clickable { runCatching { uriHandler.openUri(url) } },
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                val site = t.siteName ?: host
                if (!site.isNullOrBlank()) {
                    Text(
                        site,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    t.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                t.description?.let { d ->
                    Text(
                        d,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
