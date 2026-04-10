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

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple line-based log writer.
 *
 * Storage layout:
 *   Internal:  <filesDir>/logs/<network>/<buffer>.txt
 *   SAF:       <treeUri>/<network>/<buffer>.txt
 *
 * One file per buffer
 * Examples: #afternet, ##channel, server, Nick
 *
 * Internal log files are kept open via a per-file [BufferedWriter] cache
 * instead of reopening on every message. Call [closeAll] when the app exits or logging
 * is toggled off to flush and release file handles.
 */
class LogWriter(private val ctx: Context) {

    // Cache open BufferedWriters for internal log files to avoid open/close on every line.
    private val openWriters = ConcurrentHashMap<String, BufferedWriter>()

    // Per-key write locks so two coroutines writing to the same buffer file cannot
    // interleave lines. ConcurrentHashMap.getOrPut is NOT atomic for the writer
    // itself — two threads can both pass the "not present" check and try to create
    // a writer for the same file, leading to a double-open and interleaved writes.
    // A striped lock (one per cache key) gives fine-grained protection with low
    // contention: buffers on different networks/channels lock independently.
    private val writeLocks = ConcurrentHashMap<String, Any>()
    // computeIfAbsent is used here rather than Kotlin's getOrPut extension because
    // getOrPut on ConcurrentHashMap is NOT atomic — two threads can both pass the
    // "key absent" check and each receive a different Any() object, defeating the lock.
    // computeIfAbsent is guaranteed by ConcurrentHashMap to call the lambda at most once
    // and return the same object to all concurrent callers for the same key.
    private fun writeLockFor(key: String): Any = writeLocks.computeIfAbsent(key) { Any() }

    // Track the last time each log file was explicitly flushed to disk.
    // We flush eagerly on background transition and periodically (every FLUSH_INTERVAL_MS)
    // rather than after every single line — one fewer kernel write call per message.
    private val lastFlushMs = ConcurrentHashMap<String, Long>()
    private val FLUSH_INTERVAL_MS = 5_000L

    // Cache resolved SAF file URIs to avoid repeated directory scans on every message.
    // Key is "$treeUri|$netDir|$fileName". Cleared when a write to that URI fails, so a
    // user-deleted file is re-resolved on the next write instead of failing forever.
    private val safFileCache = ConcurrentHashMap<String, Uri>()

    // Cache open SAF OutputStreams (wrapped in BufferedOutputStream) to avoid reopening
    // the document on every message — mirrors the openWriters strategy for internal files.
    // Key matches safFileCache: "$treeUri|$netDir|$fileName".
    // Evicted (closed) on any write failure so a user-deleted file is re-created on the next call.
    private val safWriters = ConcurrentHashMap<String, java.io.BufferedOutputStream>()

    private fun internalRoot(): File = File(ctx.filesDir, "logs").apply { mkdirs() }

    fun append(networkName: String, buffer: String, line: String, logFolderUri: String?): String? {
        return runCatching {
            if (logFolderUri.isNullOrBlank()) {
                appendInternal(networkName, buffer, line)
            } else {
                appendSaf(Uri.parse(logFolderUri), networkName, buffer, line)
            }
            null
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }

    // Keep a BufferedWriter open per log file
    private fun appendInternal(networkName: String, buffer: String, line: String) {
        val dir = File(internalRoot(), safeNetworkDirName(networkName))
        dir.mkdirs()
        val f = File(dir, safeBufferFileName(buffer))
        val cacheKey = f.absolutePath
        // Synchronise on a per-file lock so two coroutines writing to the same buffer
        // cannot interleave lines. ConcurrentHashMap.getOrPut is not atomic for the
        // writer itself, so we need an explicit guard around the get-or-create + write.
        synchronized(writeLockFor(cacheKey)) {
            val writer = openWriters.getOrPut(cacheKey) {
                BufferedWriter(FileWriter(f, /* append = */ true), 8192)
            }
            writer.write(line)
            writer.newLine()
            // Throttled flush: flush immediately if we haven't flushed this file within
            // FLUSH_INTERVAL_MS, otherwise let the BufferedWriter accumulate more data.
            // flushAll() is called on background transition to ensure no lines are lost
            // when the process might be killed, so crash safety is preserved.
            val now = System.currentTimeMillis()
            if (now - (lastFlushMs[cacheKey] ?: 0L) >= FLUSH_INTERVAL_MS) {
                writer.flush()
                lastFlushMs[cacheKey] = now
            }
        }
    }

    /**
     * Flush all open log file handles without closing them.
     * Call when the app goes to background so buffered lines reach disk before
     * the process might be killed by the OS.
     */
    fun flushAll() {
        val now = System.currentTimeMillis()
        for ((key, writer) in openWriters) {
            synchronized(writeLockFor(key)) {
                runCatching { writer.flush() }
                lastFlushMs[key] = now
            }
        }
        for ((_, stream) in safWriters) runCatching { stream.flush() }
    }

    /** Flush and close all open log file handles (internal and SAF). Call when logging is disabled or app exits. */
    fun closeAll() {
        // Snapshot both maps before clearing them so a concurrent appendInternal() that
        // slips in after clear() but before close() cannot create a writer that is then
        // immediately orphaned.  The snapshot holds the only remaining references, so
        // close() below is guaranteed to run on every writer that existed at call time.
        val internalSnapshot = openWriters.entries.toList()
        openWriters.clear()
        lastFlushMs.clear()
        writeLocks.clear()
        // Close after clearing so appendInternal() racing here finds an empty map and
        // creates a new, tracked writer rather than a leaked one.
        for ((_, writer) in internalSnapshot) runCatching { writer.close() }

        val safSnapshot = safWriters.entries.toList()
        safWriters.clear()
        safFileCache.clear()
        for ((_, stream) in safSnapshot) runCatching { stream.close() }
    }

    fun readTail(networkName: String, buffer: String, maxLines: Int, logFolderUri: String?): List<String> {
        val n = maxLines.coerceIn(1, 5000)
        return if (logFolderUri.isNullOrBlank()) {
            readTailInternal(networkName, buffer, n)
        } else {
            readTailSaf(Uri.parse(logFolderUri), networkName, buffer, n)
        }
    }

    private fun readTailInternal(networkName: String, buffer: String, maxLines: Int): List<String> {
        val f = File(
            File(internalRoot(), safeNetworkDirName(networkName)),
            safeBufferFileName(buffer)
        )
        if (!f.exists() || !f.isFile) return emptyList()
        // Flush any buffered writer for this file before reading so the tail is up-to-date.
        openWriters[f.absolutePath]?.flush()
        return f.inputStream().use { readTailFromStream(it, maxLines) }
    }

    private fun readTailSaf(treeUri: Uri, networkName: String, buffer: String, maxLines: Int): List<String> {
        // Flush any buffered SAF writer for this file so the tail includes the latest lines.
        val netDirName = safeNetworkDirName(networkName)
        val fileName = safBufferFileName(buffer)
        val cacheKey = "$treeUri|$netDirName|$fileName"
        safWriters[cacheKey]?.runCatching { flush() }

        val resolver = ctx.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val net = findChild(resolver, treeUri, rootDocId, safeNetworkDirName(networkName)) ?: return emptyList()
        if (net.second != Document.MIME_TYPE_DIR) return emptyList()
        val file = findChild(resolver, treeUri, net.first, safBufferFileName(buffer)) ?: return emptyList()
        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, file.first)
        return runCatching {
            resolver.openInputStream(fileUri)?.use { readTailFromStream(it, maxLines) } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun readTailFromStream(input: java.io.InputStream, maxLines: Int): List<String> {
        val dq = java.util.ArrayDeque<String>(maxLines)
        input.bufferedReader(Charsets.UTF_8).useLines { seq ->
            seq.forEach { line ->
                if (dq.size >= maxLines) dq.removeFirst()
                dq.addLast(line)
            }
        }
        return dq.toList()
    }

    // Purge logs according to the retention policy
    fun purgeOlderThan(days: Int, logFolderUri: String?) {
        val cutoff = System.currentTimeMillis() - days.coerceIn(1, 365) * 24L * 60L * 60L * 1000L
        if (logFolderUri.isNullOrBlank()) {
            // Internal storage: walk the file tree and delete old files.
            internalRoot().walkTopDown().forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) {
                    // Close the cached writer for this file before deleting it.
                    openWriters.remove(f.absolutePath)?.runCatching { close() }
                    runCatching { f.delete() }
                }
            }
        } else {
            // SAF storage: query children and delete documents older than the cutoff.
            runCatching { purgeOlderThanSaf(Uri.parse(logFolderUri), cutoff) }
        }
    }

    private fun purgeOlderThanSaf(treeUri: Uri, cutoffMs: Long) {
        val resolver = ctx.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        // Walk one level of network-name subdirectories.
        val netDirs = queryChildren(resolver, treeUri, rootDocId)
        for ((netDocId, _, netMime) in netDirs) {
            if (netMime != Document.MIME_TYPE_DIR) continue
            val files = queryChildren(resolver, treeUri, netDocId)
            for ((fileDocId, _, _) in files) {
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, fileDocId)
                // Query last-modified time for the document.
                val modMs = runCatching {
                    resolver.query(
                        fileUri,
                        arrayOf(Document.COLUMN_LAST_MODIFIED),
                        null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) c.getLong(0) else null
                    }
                }.getOrNull()
                if (modMs != null && modMs < cutoffMs) {
                    // Look up the writer key BEFORE evicting from safFileCache — once the
                    // cache entry is removed, safFileCache[key] == fileUri will never match.
                    val writerKey = safWriters.keys.firstOrNull { safFileCache[it] == fileUri }
                    safWriters.remove(writerKey)?.runCatching { close() }
                    safFileCache.entries.removeIf { it.value == fileUri }
                    runCatching { DocumentsContract.deleteDocument(resolver, fileUri) }
                }
            }
        }
    }

    // SAF helpers
    private fun appendSaf(treeUri: Uri, networkName: String, buffer: String, line: String) {
        val resolver = ctx.contentResolver
        val netDirName = safeNetworkDirName(networkName)
        val fileName = safBufferFileName(buffer)
        val cacheKey = "$treeUri|$netDirName|$fileName"

        // Fast path: use cached stream if available.
        safWriters[cacheKey]?.let { cached ->
            val writeOk = runCatching {
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                cached.write(bytes)
                cached.flush()
            }.isSuccess
            if (writeOk) return
            // Write failed (file likely deleted externally); close, evict both caches and
            // fall through to re-resolve so the file is re-created on the next call.
            safWriters.remove(cacheKey)?.runCatching { close() }
            safFileCache.remove(cacheKey)
        }

        // Slow path: resolve (or create) the document URI, open and cache a new stream.
        val fileUri = resolveOrCreateSafFile(resolver, treeUri, netDirName, fileName, cacheKey)
            ?: return   // provider refused to create; silently drop this line

        val stream = runCatching {
            resolver.openOutputStream(fileUri, "wa")
                ?.let { java.io.BufferedOutputStream(it, 8192) }
        }.getOrNull() ?: return  // couldn't open; drop this line

        safWriters[cacheKey] = stream

        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val writeOk = runCatching { stream.write(bytes); stream.flush() }.isSuccess
        if (!writeOk) {
            // Opening succeeded but the first write failed — evict so next call retries.
            safWriters.remove(cacheKey)?.runCatching { close() }
            safFileCache.remove(cacheKey)
        }
    }

    /**
     * Resolve the SAF document URI for [fileName] inside the [netDirName] subdirectory of
     * [treeUri], creating the directory and/or file if they do not yet exist.
     * Updates [safFileCache] on success. Returns null if the provider rejects creation.
     */
    private fun resolveOrCreateSafFile(
        resolver: ContentResolver,
        treeUri: Uri,
        netDirName: String,
        fileName: String,
        cacheKey: String,
    ): Uri? {
        val rootDocId  = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)

        val netDirUri  = findOrCreateChildDir(resolver, treeUri, rootDocUri, rootDocId, netDirName)
        val netDirDocId = DocumentsContract.getDocumentId(netDirUri)

        val fileUri = findChild(resolver, treeUri, netDirDocId, fileName)
            ?.let { (docId, _) -> DocumentsContract.buildDocumentUriUsingTree(treeUri, docId) }
            ?: findOrCreateChildFile(resolver, treeUri, netDirUri, netDirDocId, fileName)

        // netDirUri == fileUri only when createDocument returned null (provider error).
        if (fileUri == netDirUri) return null

        safFileCache[cacheKey] = fileUri
        return fileUri
    }

    private fun findOrCreateChildDir(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocUri: Uri,
        parentDocId: String,
        displayName: String,
    ): Uri {
        findChild(resolver, treeUri, parentDocId, displayName)?.let { (docId, _) ->
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        }
        return DocumentsContract.createDocument(resolver, parentDocUri, Document.MIME_TYPE_DIR, displayName)
            ?: parentDocUri
    }

    private fun findOrCreateChildFile(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocUri: Uri,
        parentDocId: String,
        displayName: String,
    ): Uri {
        findChild(resolver, treeUri, parentDocId, displayName)?.let { (docId, _) ->
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        }
        return DocumentsContract.createDocument(resolver, parentDocUri, "text/plain", displayName)
            ?: parentDocUri
    }

    private fun findChild(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        displayName: String,
    ): Pair<String, String>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idCol   = c.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                if (name.trim().equals(displayName, ignoreCase = true))
                    return c.getString(idCol) to c.getString(mimeCol)
            }
        }
        return null
    }

    /** Returns list of (docId, displayName, mimeType) triples for the direct children of [parentDocId]. */
    private data class DocEntry(val docId: String, val name: String, val mime: String)
    private fun queryChildren(resolver: ContentResolver, treeUri: Uri, parentDocId: String): List<DocEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val result = mutableListOf<DocEntry>()
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idCol   = c.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                result += DocEntry(
                    c.getString(idCol) ?: continue,
                    c.getString(nameCol) ?: "",
                    c.getString(mimeCol) ?: ""
                )
            }
        }
        return result
    }

    // Filename helpers
    private fun safeNetworkDirName(networkName: String): String {
        val cleaned = networkName.trim()
            .replace("\\", "_")
            .replace("/", "_")
            .replace("\u0000", "")
            .trim()
            .take(80)
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "network" else cleaned
    }

    /** Canonical filename for internal storage. '#' is valid on EXT4/F2FS. */
    private fun safeBufferFileName(buffer: String): String {
        val name = if (buffer == "*server*") "server" else buffer
        val cleaned = name.trim()
            .replace("\\", "_")
            .replace("/", "_")
            .replace("\u0000", "")
            .trim()
            .take(120)
        val base = if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "buffer" else cleaned
        return "$base.txt"
    }

    /** Filename for SAF providers. */
    private fun safBufferFileName(buffer: String): String = safeBufferFileName(buffer)

    /** Expose internal log file path for display/sharing purposes. */
    fun logFileInternal(networkName: String, buffer: String): File =
        File(File(internalRoot(), safeNetworkDirName(networkName)), safeBufferFileName(buffer))
}