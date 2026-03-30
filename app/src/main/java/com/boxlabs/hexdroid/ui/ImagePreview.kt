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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

// Twitter / X

private val twitterRegex = Regex(
    """(?:https?://)?(?:www\.)?(?:twitter\.com|x\.com)/([A-Za-z0-9_]{1,50})/status/(\d{1,20})"""
)

private data class TwitterUrlData(
    val username: String,
    val tweetId: String,
    val originalUrl: String,
)

private fun extractTwitterData(url: String): TwitterUrlData? {
    val m = twitterRegex.find(url) ?: return null
    val user = m.groupValues[1]
    // Filter out Twitter UI path segments that aren't usernames
    if (user.equals("i", ignoreCase = true) || user.equals("intent", ignoreCase = true)) return null
    return TwitterUrlData(username = user, tweetId = m.groupValues[2], originalUrl = url)
}

private data class TwitterMeta(
    val thumbnailUrl: String,
    val hasVideo: Boolean,
    val tweetUrl: String,
)

private suspend fun fetchTwitterMeta(data: TwitterUrlData, ctx: Context): TwitterMeta? = withContext(Dispatchers.IO) {
    runCatching {
        val apiUrl = "https://api.fxtwitter.com/${data.username}/status/${data.tweetId}"
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", "HexDroid IRC")
            .build()
        httpClient(ctx).newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val json = org.json.JSONObject(response.body.string())
            val tweet = json.optJSONObject("tweet") ?: return@use null
            val media = tweet.optJSONObject("media")

            // Prefer video thumbnail
            val videos = media?.optJSONArray("videos")
            if (videos != null && videos.length() > 0) {
                val thumb = videos.getJSONObject(0).optString("thumbnail_url").takeIf { it.isNotBlank() }
                if (thumb != null) return@use TwitterMeta(thumb, true, data.originalUrl)
            }

            // Fall back to first photo
            val photos = media?.optJSONArray("photos")
            if (photos != null && photos.length() > 0) {
                val photoUrl = photos.getJSONObject(0).optString("url").takeIf { it.isNotBlank() }
                if (photoUrl != null) return@use TwitterMeta(photoUrl, false, data.originalUrl)
            }

            null // text-only tweet — nothing to preview
        }
    }.getOrNull()
}

// YouTube

private val ytRegex = Regex(
    """(?:(?:[a-z]+\.)?youtube\.com/watch\?(?:[^&]*&)*v=|youtu\.be/|(?:[a-z]+\.)?youtube\.com/embed/|(?:[a-z]+\.)?youtube\.com/shorts/)([A-Za-z0-9_-]{11})"""
)

fun extractYouTubeId(url: String): String? =
    ytRegex.find(url)?.groupValues?.get(1)?.takeIf { it.length == 11 }

private fun youtubeThumbnailUrl(videoId: String) =
    "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

// Image URL detection

fun isPreviewableImageUrl(url: String): Boolean {
    val lower = url.lowercase()
    if (lower.contains(".svg") || lower.startsWith("data:")) return false
    val path = lower.substringBefore("?").substringBefore("#")
    return path.endsWith(".jpg") || path.endsWith(".jpeg") ||
           path.endsWith(".png") || path.endsWith(".gif") ||
           path.endsWith(".webp") || path.endsWith(".bmp") ||
           path.endsWith(".avif")
}

// Wifi only option

fun isOnWifi(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

private val allowedMimeTypes = setOf(
    "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp"
)

private sealed interface FetchResult {
    data class Success(val bitmap: Bitmap, val rawBytes: ByteArray? = null, val isGif: Boolean = false) : FetchResult
    data object TooLarge : FetchResult
    data object Error : FetchResult   // network, 404, decode failure, timeout, etc.
}

// Explicit timeouts prevent a stalled image server from hanging an IO coroutine
// indefinitely. OkHttp's defaults (10 s connect, 10 s read) are intentionally not relied
// upon here; spelling them out makes the intended behaviour clear and easy to tune.
//
// The client is a process-wide singleton built lazily on first use so we can pass in a
// Context for the disk cache directory. A 20 MB LRU cache means YouTube thumbnails and
// inline images are served from disk on subsequent views (e.g. scrolling back through
// history) without any network round-trip.
@Volatile private var _httpClient: OkHttpClient? = null

private fun httpClient(ctx: Context): OkHttpClient =
    _httpClient ?: synchronized(OkHttpClient::class.java) {
        _httpClient ?: OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .cache(
                okhttp3.Cache(
                    java.io.File(ctx.applicationContext.cacheDir, "image_preview_cache"),
                    20L * 1024 * 1024  // 20 MB
                )
            )
            .build()
            .also { _httpClient = it }
    }

private suspend fun fetchBitmap(url: String, ctx: Context): FetchResult = withContext(Dispatchers.IO) {
    // Validate the scheme before handing the URL to OkHttp. The Twitter/fxtwitter
    // API returns a thumbnail URL from a third-party service; if that API were ever to return
    // a non-https URL (e.g. file://, http://, or a redirect to a private IP range), we could
    // inadvertently expose internal resources or send unencrypted traffic. Only https:// is
    // a legitimate source for image previews in a chat client.
    if (!url.startsWith("https://", ignoreCase = true)) return@withContext FetchResult.Error

    runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "HexDroid IRC")
            .build()

        httpClient(ctx).newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching FetchResult.Error

            val mime = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
            if (mime == null || mime !in allowedMimeTypes) return@runCatching FetchResult.Error

            val cap = 5 * 1024 * 1024L
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
            if (contentLength > cap) return@runCatching FetchResult.TooLarge

            // Secure hard-capped read
            response.body.byteStream().use { input ->
                val output = ByteArrayOutputStream(8192)
                val buffer = ByteArray(8192)
                var total = 0L
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    total += bytesRead
                    if (total > cap) return@runCatching FetchResult.TooLarge // reject
                    output.write(buffer, 0, bytesRead)
                }

                val bytes = output.toByteArray()
                val isGif = mime == "image/gif"
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                    // For GIFs keep the raw bytes so the animated drawable can be created later.
                    FetchResult.Success(it, rawBytes = if (isGif) bytes else null, isGif = isGif)
                } ?: FetchResult.Error
            }
        }
    }.getOrDefault(FetchResult.Error)
}

// Preview states

private sealed interface PreviewState {
    data object Idle    : PreviewState
    data object Loading : PreviewState
    data class  Ready(
        val bitmap: Bitmap,
        val rawBytes: ByteArray? = null,
        val isGif: Boolean = false,
        val isYouTube: Boolean = false,
        val videoId: String = "",
        val isTwitterVideo: Boolean = false,
        val twitterUrl: String = "",
    ) : PreviewState
    data class  Failed(val message: String) : PreviewState   // a friendly message
}

// Saver for rememberSaveable: Bitmap isn't Parcelable so we can't persist the image across
// process death. We save the logical state as a string and restore Ready/Loading so the
// bitmap is re-fetched once on restore (not on every recomposition)
private val previewStateSaver = Saver<PreviewState, String>(
    save    = { state -> when (state) {
        is PreviewState.Idle    -> "idle"
        is PreviewState.Loading -> "loading"
        is PreviewState.Failed  -> "failed"   // message is not persisted (process death is rare)
        is PreviewState.Ready   -> "ready" // bitmap can't cross process boundary; re-fetch
    }},
    restore = { saved -> when (saved) {
        "ready"  -> PreviewState.Loading // trigger a re-fetch; loadRequested will be true
        "failed" -> PreviewState.Failed("Failed to load preview")
        else     -> PreviewState.Idle
    }}
)

// Embedded YouTube player

@Composable
private fun YouTubePlayer(videoId: String, onClose: () -> Unit) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView(ctx).apply {
                    lifecycleOwner.lifecycle.addObserver(this)
                    addYouTubePlayerListener(object :
                        com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener() {
                        override fun onReady(
                            youTubePlayer: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
                        ) {
                            youTubePlayer.loadVideo(videoId, 0f)
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.release() },
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(36.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(18.dp)),
        ) {
            Icon(
                imageVector        = Icons.Default.Close,
                contentDescription = "Close player",
                tint               = Color.White,
                modifier           = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Renders an inline image preview, YouTube thumbnail+player, or Twitter/X media preview for [url].
 *
 * Image:
 *   Shows a small "Load preview" button. Nothing is downloaded until tapped.
 *   Once loaded, the image is shown. State is saved across recompositions so
 *   switching buffers and back doesn't re-download.
 *
 * YouTube:
 *   1. Thumbnail auto-loads from img.youtube.com
 *   2. Pressing play launches an inline player (using https://github.com/PierfrancescoSoffritti/android-youtube-player library)
 *
 * Twitter/X:
 *   1. Thumbnail auto-loads via the open api.fxtwitter.com metadata API (no auth needed)
 *   2. If the tweet has a video, a play icon is shown; tapping opens the tweet URL in the browser.
 *      Twitter video cannot be embedded directly (no public iframe API), so we open externally.
 *   3. Photo-only tweets show the image inline with no play overlay.
 *
 * SVGs blocked by extension check AND Content-Type validation.
 * Image downloads capped at 5 MB with MIME-type allow-list.
 */

/**
 * Displays an animated GIF using an [android.widget.ImageView].
 * On API 28+ uses [android.graphics.ImageDecoder] for hardware-accelerated decoding.
 * On API 26/27 falls back to [android.graphics.drawable.AnimationDrawable] via [android.graphics.Movie].
 */
@Composable
private fun AnimatedGif(bytes: ByteArray, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            android.widget.ImageView(ctx).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    val source = android.graphics.ImageDecoder.createSource(
                        java.nio.ByteBuffer.wrap(bytes)
                    )
                    runCatching {
                        val drawable = android.graphics.ImageDecoder.decodeDrawable(source)
                        setImageDrawable(drawable)
                        (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.start()
                    }
                } else {
                    // API 26/27: use Movie for GIF playback via a custom drawable.
                    // Movie is deprecated in API 29 but is the only option below API 28.
                    @Suppress("DEPRECATION")
                    val movie = android.graphics.Movie.decodeByteArray(bytes, 0, bytes.size)
                    if (movie != null) {
                        setImageDrawable(MovieDrawable(movie))
                    } else {
                        setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    }
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * Drawable wrapper for [android.graphics.Movie] to animate GIFs on API < 28.
 * Movie is deprecated at API 29 but remains the only GIF animation option on API 26/27.
 */
@Suppress("DEPRECATION")
private class MovieDrawable(private val movie: android.graphics.Movie) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val startMs = android.os.SystemClock.uptimeMillis()

    override fun draw(canvas: android.graphics.Canvas) {
        val now = android.os.SystemClock.uptimeMillis()
        val duration = movie.duration().takeIf { it > 0 } ?: 1000
        val relTime = ((now - startMs) % duration).toInt()
        movie.setTime(relTime)
        val scaleX = bounds.width().toFloat() / movie.width().coerceAtLeast(1)
        val scaleY = bounds.height().toFloat() / movie.height().coerceAtLeast(1)
        val scale = minOf(scaleX, scaleY)
        canvas.save()
        canvas.scale(scale, scale)
        movie.draw(canvas, 0f, 0f, paint)
        canvas.restore()
        invalidateSelf()  // request next frame
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in API 29")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

@Composable
fun InlinePreview(
    url: String,
    previewsEnabled: Boolean,
    wifiOnly: Boolean,
) {
    if (!previewsEnabled) return

    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    val youtubeId   = remember(url) { extractYouTubeId(url) }
    val twitterData = remember(url) { extractTwitterData(url) }
    val isImage     = remember(url) { isPreviewableImageUrl(url) }

    if (youtubeId == null && twitterData == null && !isImage) return

    // Auto-load thumbnails for YouTube only; Twitter/X requires an explicit tap.
    // Twitter images can be sensitive or high-bandwidth, and the fxtwitter API
    // call leaks the URL to a third party — opt-in is the right default.
    val autoLoad = youtubeId != null

    var state        by rememberSaveable(url, stateSaver = previewStateSaver) {
        mutableStateOf(if (autoLoad) PreviewState.Loading else PreviewState.Idle)
    }
    var loadRequested by rememberSaveable(url) { mutableStateOf(autoLoad) }
    // Not rememberSaveable: playing state must reset when scrolled away.
    var isPlaying     by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, loadRequested) {
        if (!loadRequested) return@LaunchedEffect
        if (state is PreviewState.Ready) return@LaunchedEffect
        if (wifiOnly && !isOnWifi(context)) {
            state = PreviewState.Failed("Wi-Fi only – connect to Wi-Fi")
            return@LaunchedEffect
        }

        state = PreviewState.Loading

        when {
            youtubeId != null -> {
                when (val result = fetchBitmap(youtubeThumbnailUrl(youtubeId), context)) {
                    is FetchResult.Success -> state = PreviewState.Ready(
                        bitmap = result.bitmap, isYouTube = true, videoId = youtubeId
                    )
                    FetchResult.TooLarge -> state = PreviewState.Failed("Preview too large (max 5 MB)")
                    FetchResult.Error    -> state = PreviewState.Failed("Failed to load preview")
                }
            }
            twitterData != null -> {
                val meta = fetchTwitterMeta(twitterData, context)
                if (meta == null) {
                    // Text-only tweet or API failure — nothing to show.
                    state = PreviewState.Failed("No media in this tweet")
                } else {
                    when (val result = fetchBitmap(meta.thumbnailUrl, context)) {
                        is FetchResult.Success -> state = PreviewState.Ready(
                            bitmap = result.bitmap,
                            isTwitterVideo = meta.hasVideo,
                            twitterUrl = meta.tweetUrl,
                        )
                        FetchResult.TooLarge -> state = PreviewState.Failed("Preview too large (max 5 MB)")
                        FetchResult.Error    -> state = PreviewState.Failed("Failed to load preview")
                    }
                }
            }
            else -> {
                when (val result = fetchBitmap(url, context)) {
                    is FetchResult.Success -> state = PreviewState.Ready(
                        bitmap = result.bitmap,
                        rawBytes = result.rawBytes,
                        isGif = result.isGif,
                    )
                    FetchResult.TooLarge   -> state = PreviewState.Failed("Preview too large (max 5 MB)")
                    FetchResult.Error      -> state = PreviewState.Failed("Failed to load preview")
                }
            }
        }
    }

    when (val s = state) {
        is PreviewState.Idle, is PreviewState.Failed -> {
            androidx.compose.material3.OutlinedButton(
                onClick = { loadRequested = true },
                modifier = Modifier.padding(top = 2.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PlayCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                androidx.compose.material3.Text(
                    text = when {
                        s is PreviewState.Failed -> s.message
                        else -> "Load preview"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        is PreviewState.Loading -> {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth(0.85f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        }

        is PreviewState.Ready -> {
            val hasPlayOverlay = s.isYouTube || s.isTwitterVideo
            AnimatedContent(
                targetState    = isPlaying,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label          = "preview_toggle",
                modifier       = Modifier.padding(top = 4.dp).fillMaxWidth(0.85f),
            ) { playing ->
                if (playing && s.isYouTube) {
                    YouTubePlayer(
                        videoId = s.videoId,
                        onClose = { isPlaying = false },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .then(when {
                                s.isYouTube       -> Modifier.clickable { isPlaying = true }
                                s.isTwitterVideo  -> Modifier.clickable {
                                    runCatching { uriHandler.openUri(s.twitterUrl) }
                                }
                                else -> Modifier
                            }),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (s.isGif && s.rawBytes != null) {
                            AnimatedGif(
                                bytes = s.rawBytes,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                            )
                        } else {
                            Image(
                                bitmap             = s.bitmap.asImageBitmap(),
                                contentDescription = when {
                                    s.isYouTube      -> "YouTube thumbnail"
                                    s.isTwitterVideo -> "Twitter/X video thumbnail"
                                    else             -> "Image preview"
                                },
                                contentScale = ContentScale.Crop,
                                modifier     = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                            )
                        }
                        if (hasPlayOverlay) {
                            Icon(
                                imageVector        = Icons.Default.PlayCircleOutline,
                                contentDescription = if (s.isYouTube) "Play video" else "Open tweet video",
                                tint               = Color.White,
                                modifier           = Modifier
                                    .size(64.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(32.dp)),
                            )
                        }
                        IconButton(
                            onClick = { state = PreviewState.Idle; loadRequested = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(28.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(14.dp)),
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Close,
                                contentDescription = "Dismiss preview",
                                tint               = Color.White,
                                modifier           = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}