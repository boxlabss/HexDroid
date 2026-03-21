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

private suspend fun fetchTwitterMeta(data: TwitterUrlData): TwitterMeta? = withContext(Dispatchers.IO) {
    runCatching {
        val apiUrl = "https://api.fxtwitter.com/${data.username}/status/${data.tweetId}"
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", "HexDroid IRC")
            .build()
        httpClient.newCall(request).execute().use { response ->
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
    data class Success(val bitmap: Bitmap) : FetchResult
    data object TooLarge : FetchResult
    data object Error : FetchResult   // network, 404, decode failure, timeout, etc.
}

private val httpClient = OkHttpClient()

private suspend fun fetchBitmap(url: String): FetchResult = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "HexDroid IRC")
            .build()

        httpClient.newCall(request).execute().use { response ->
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
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                    FetchResult.Success(it)
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
                when (val result = fetchBitmap(youtubeThumbnailUrl(youtubeId))) {
                    is FetchResult.Success -> state = PreviewState.Ready(
                        bitmap = result.bitmap, isYouTube = true, videoId = youtubeId
                    )
                    FetchResult.TooLarge -> state = PreviewState.Failed("Preview too large (max 5 MB)")
                    FetchResult.Error    -> state = PreviewState.Failed("Failed to load preview")
                }
            }
            twitterData != null -> {
                val meta = fetchTwitterMeta(twitterData)
                if (meta == null) {
                    // Text-only tweet or API failure — nothing to show.
                    state = PreviewState.Failed("No media in this tweet")
                } else {
                    when (val result = fetchBitmap(meta.thumbnailUrl)) {
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
                when (val result = fetchBitmap(url)) {
                    is FetchResult.Success -> state = PreviewState.Ready(bitmap = result.bitmap)
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