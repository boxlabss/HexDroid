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

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent record of an interrupted DCC SEND we were on the receiving end of,
 * so we can offer DCC RESUME if the same offer (or a matching one) arrives later.
 *
 * The natural key is (from, filename baseName, size): a sender + file identity that
 * a remote peer would re-offer with the exact same DCC SEND payload. We deliberately
 * don't key on IP or port because those change between sessions of the sender.
 */
data class PartialTransfer(
    val from: String,
    val filename: String,
    val size: Long,
    /** file:// path or content:// URI of the partial. Same semantics as `savedPath` elsewhere. */
    val savedPath: String,
    val receivedBytes: Long,
    val lastUpdatedMs: Long = System.currentTimeMillis(),
    val secure: Boolean = false,
    val turbo: Boolean = false,
) {
    fun key(): String = makeKey(from, filename, size)

    companion object {
        fun makeKey(from: String, filename: String, size: Long): String {
            val base = filename.substringAfterLast('/').substringAfterLast('\\')
            return "${from.lowercase()}|$base|$size"
        }
    }
}

/**
 * Persists [PartialTransfer] entries to a small JSON file in the app's private storage.
 *
 * Writes are atomic (tmp-file rename). Reads are tolerant of corruption: a malformed
 * file is treated as "no partials" and replaced on the next write rather than throwing
 * an error to the user, losing the ability to resume a transfer is annoying but not
 * data loss, since the underlying partial files are still on disk.
 *
 * Thread-safety: an in-memory [ConcurrentHashMap] serves all reads; writes are
 * serialized via a single intrinsic lock so we never produce a half-written JSON file.
 */
class DccResumeStore(ctx: Context) {

    private val ctx: Context = ctx.applicationContext
    private val file: File = File(ctx.filesDir, FILE_NAME)
    private val writeLock = Any()

    private val cache: ConcurrentHashMap<String, PartialTransfer> = ConcurrentHashMap()

    init {
        loadFromDisk()
        pruneExpired()
    }

    fun get(from: String, filename: String, size: Long): PartialTransfer? {
        val key = PartialTransfer.makeKey(from, filename, size)
        val p = cache[key] ?: return null
        // Be defensive: an entry with zero received bytes is useless for resume.
        // We deliberately do NOT check that the underlying saved file still exists
        // here, because `get` is called from UI composition and shouldn't block on
        // disk I/O. If the file is gone when the user actually clicks Resume, the
        // open call will fail and the receive coroutine will surface an error.
        if (p.receivedBytes <= 0L) return null
        return p
    }

    /** All current entries (not filtered by liveness). */
    fun all(): List<PartialTransfer> = cache.values.toList()

    fun put(p: PartialTransfer) {
        cache[p.key()] = p.copy(lastUpdatedMs = System.currentTimeMillis())
        persist()
    }

    fun remove(from: String, filename: String, size: Long) {
        val key = PartialTransfer.makeKey(from, filename, size)
        if (cache.remove(key) != null) persist()
    }

    /**
     * Remove the entry AND attempt to delete the underlying partial file. Called when the
     * user explicitly rejects the offer, reclaim the bytes.
     */
    fun removeAndDeleteFile(from: String, filename: String, size: Long) {
        val key = PartialTransfer.makeKey(from, filename, size)
        val p = cache.remove(key)
        if (p != null) {
            deleteSavedPath(p.savedPath)
            persist()
        }
    }

    /** Drop entries older than [maxAgeMs]. Underlying files are also unlinked. */
    fun pruneExpired(maxAgeMs: Long = DEFAULT_MAX_AGE_MS) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val expired = cache.values.filter { it.lastUpdatedMs < cutoff }
        if (expired.isEmpty()) return
        for (p in expired) {
            cache.remove(p.key())
            deleteSavedPath(p.savedPath)
        }
        persist()
    }

    private fun deleteSavedPath(savedPath: String) {
        runCatching {
            if (savedPath.startsWith("content://")) {
                ctx.contentResolver.delete(Uri.parse(savedPath), null, null)
            } else {
                File(savedPath).delete()
            }
        }
    }

    private fun loadFromDisk() {
        if (!file.exists()) return
        try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = fromJson(o) ?: continue
                cache[p.key()] = p
            }
        } catch (_: Throwable) {
            // Corrupt file. start fresh. The bad file gets overwritten on the next persist().
            cache.clear()
        }
    }

    private fun persist() {
        synchronized(writeLock) {
            val arr = JSONArray()
            for (p in cache.values) arr.put(toJson(p))
            val tmp = File(file.parentFile, "${file.name}.tmp")
            try {
                tmp.writeText(arr.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    // Rename failed (rare on internal storage, but possible if `file` is locked).
                    // Fall back to a non-atomic overwrite; better to risk a partial write than to
                    // silently drop all partial-transfer state.
                    file.writeText(arr.toString(), Charsets.UTF_8)
                    tmp.delete()
                }
            } catch (_: IOException) {
                runCatching { tmp.delete() }
            }
        }
    }

    private fun toJson(p: PartialTransfer): JSONObject = JSONObject().apply {
        put("from", p.from)
        put("filename", p.filename)
        put("size", p.size)
        put("savedPath", p.savedPath)
        put("receivedBytes", p.receivedBytes)
        put("lastUpdatedMs", p.lastUpdatedMs)
        put("secure", p.secure)
        put("turbo", p.turbo)
    }

    private fun fromJson(o: JSONObject): PartialTransfer? {
        val from = o.optString("from").takeIf { it.isNotBlank() } ?: return null
        val filename = o.optString("filename").takeIf { it.isNotBlank() } ?: return null
        val size = o.optLong("size", -1L).takeIf { it >= 0L } ?: return null
        val savedPath = o.optString("savedPath").takeIf { it.isNotBlank() } ?: return null
        val received = o.optLong("receivedBytes", -1L).takeIf { it >= 0L } ?: return null
        val last = o.optLong("lastUpdatedMs", System.currentTimeMillis())
        val secure = o.optBoolean("secure", false)
        val turbo = o.optBoolean("turbo", false)
        return PartialTransfer(from, filename, size, savedPath, received, last, secure, turbo)
    }

    companion object {
        private const val FILE_NAME = "dcc_partials.json"
        /** 30 days. */
        private const val DEFAULT_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
