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

private const val MAX_OPEN_WRITERS = 32

/**
 * Access-ordered cache of open handles, holding at most [max] of them.
 *
 * A handle is marked in use for the length of a write and is not evicted while it is, so a
 * write cannot have its handle closed underneath it. [onEvict] runs for each key dropped.
 */
private class HandleCache<T : java.io.Closeable>(
    private val max: Int,
    private val onEvict: (String) -> Unit = {},
) {
    private val open = LinkedHashMap<String, T>(64, 0.75f, true)
    private val inUse = HashSet<String>()
    private val lock = Any()

    /** The handle for [key], created by [create] if absent, marked in use. */
    fun acquire(key: String, create: () -> T): T = synchronized(lock) {
        val handle = open.getOrPut(key, create)
        inUse.add(key)
        evictIdle()
        handle
    }

    /** The handle for [key] if one is open, marked in use. */
    fun acquireExisting(key: String): T? = synchronized(lock) {
        val handle = open[key] ?: return@synchronized null
        inUse.add(key)
        handle
    }

    /** Mark the handle for [key] no longer in use. */
    fun release(key: String) {
        synchronized(lock) { inUse.remove(key) }
    }

    /** The handle for [key] if one is open, without marking it. */
    fun peek(key: String): T? = synchronized(lock) { open[key] }

    /** Remove the handle for [key] and return it. The caller closes it. */
    fun take(key: String): T? = synchronized(lock) {
        inUse.remove(key)
        open.remove(key)
    }

    /** A snapshot of every open handle. */
    fun snapshot(): List<Pair<String, T>> = synchronized(lock) { open.map { it.key to it.value } }

    /** A snapshot of the open keys. */
    fun keys(): List<String> = synchronized(lock) { open.keys.toList() }

    /** Remove every handle and return them. The caller closes them. */
    fun drain(): List<Pair<String, T>> = synchronized(lock) {
        val all = open.map { it.key to it.value }
        open.clear()
        inUse.clear()
        all
    }

    private fun evictIdle() {
        if (open.size <= max) return
        val entries = open.entries.iterator()
        while (open.size > max && entries.hasNext()) {
            val entry = entries.next()
            val key = entry.key
            if (key in inUse) continue
            runCatching { entry.value.close() }
            entries.remove()
            onEvict(key)
        }
    }
}

/**
 * Line-based log writer, one file per buffer.
 *
 * Storage layout:
 *   Internal:  <filesDir>/logs/<network>/<buffer>.txt
 *   SAF:       <treeUri>/<network>/<buffer>.txt
 *
 * Both paths keep a bounded set of handles open rather than reopening per message. Call
 * [closeAll] when the app exits or logging is turned off to flush and release them.
 */
class LogWriter(private val ctx: Context) {

    // Last time each log file was flushed to disk. Flushing periodically rather than per line
    // costs one fewer write call per message; flushAll() covers the process being killed.
    private val lastFlushMs = ConcurrentHashMap<String, Long>()
    private val FLUSH_INTERVAL_MS = 5_000L

    // Open handles for internal log files, so a line does not reopen the file.
    private val openWriters = HandleCache<BufferedWriter>(MAX_OPEN_WRITERS) { lastFlushMs.remove(it) }

    private fun closeWriter(cacheKey: String) {
        openWriters.take(cacheKey)?.runCatching { close() }
        lastFlushMs.remove(cacheKey)
    }

    /** Close and forget the writer for [key]'s log file, if one is open. */
    fun releaseBuffer(networkName: String, buffer: String) {
        closeWriter(logFileInternal(networkName, buffer).absolutePath)
    }

    // One lock per log file, so two coroutines writing the same buffer cannot interleave
    // lines. computeIfAbsent, not getOrPut, because only the former is atomic here.
    private val writeLocks = ConcurrentHashMap<String, Any>()
    private fun writeLockFor(key: String): Any = writeLocks.computeIfAbsent(key) { Any() }

    // Resolved SAF file URIs, keyed "$treeUri|$netDir|$fileName", so a message does not
    // rescan the directory tree. Dropped when a write to that URI fails.
    private val safFileCache = ConcurrentHashMap<String, Uri>()

    // Open handles for SAF log documents, keyed as safFileCache is.
    private val safWriters = HandleCache<java.io.BufferedOutputStream>(MAX_OPEN_WRITERS)

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
        synchronized(writeLockFor(cacheKey)) {
            // A file deleted while the app runs is only unlinked, so the old writer keeps
            // succeeding into an orphaned inode. Drop it and let the path be recreated.
            if (!f.exists()) {
                closeWriter(cacheKey)
                dir.mkdirs()
            }
            val writer = openWriters.acquire(cacheKey) { BufferedWriter(FileWriter(f, /* append = */ true), 8192) }
            try {
                writer.write(line)
                writer.newLine()
                val now = System.currentTimeMillis()
                if (now - (lastFlushMs[cacheKey] ?: 0L) >= FLUSH_INTERVAL_MS) {
                    writer.flush()
                    lastFlushMs[cacheKey] = now
                }
            } finally {
                openWriters.release(cacheKey)
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
        for ((key, writer) in openWriters.snapshot()) {
            synchronized(writeLockFor(key)) {
                runCatching { writer.flush() }
                lastFlushMs[key] = now
            }
        }
        // Locked as appendSaf is, so a flush cannot race a write on the same stream.
        for ((key, stream) in safWriters.snapshot()) {
            synchronized(writeLockFor(key)) {
                runCatching { stream.flush() }
            }
        }
    }

    /** Flush and close all open log file handles (internal and SAF). Call when logging is disabled or app exits. */
    fun closeAll() {
        // Take the handles out of the caches before closing them, so a write racing this
        // call finds an empty cache and opens a tracked handle rather than a leaked one.
        // writeLocks is deliberately kept: a write in flight holds a lock from it, and a
        // fresh object for the same key would let two threads interleave a line.
        val internalSnapshot = openWriters.drain()
        lastFlushMs.clear()
        for ((_, writer) in internalSnapshot) runCatching { writer.close() }

        val safSnapshot = safWriters.drain()
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
            // Flush this file's writer first, under the write lock, so the tail is current.
            val absPath = f.absolutePath
            openWriters.peek(absPath)?.let { w ->
                synchronized(writeLockFor(absPath)) { runCatching { w.flush() } }
            }
            return f.inputStream().use { readTailFromStream(it, maxLines) }
    }

    private fun readTailSaf(treeUri: Uri, networkName: String, buffer: String, maxLines: Int): List<String> {
        // Flush any buffered SAF writer for this file so the tail includes the latest lines.
        val netDirName = safeNetworkDirName(networkName)
        val fileName = safBufferFileName(buffer)
        val cacheKey = "$treeUri|$netDirName|$fileName"
        safWriters.peek(cacheKey)?.let { s ->
            synchronized(writeLockFor(cacheKey)) { runCatching { s.flush() } }
        }

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
        // 0 means "keep logs forever"
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days.coerceIn(1, 365) * 24L * 60L * 60L * 1000L
        if (logFolderUri.isNullOrBlank()) {
            // Internal storage: walk the file tree and delete old files.
            internalRoot().walkTopDown().forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) {
                    val absPath = f.absolutePath
                    // Close the cached writer for this file before deleting it.
                    closeWriter(absPath)
                    lastFlushMs.remove(absPath)
                    writeLocks.remove(absPath)
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
                // Per file, so one document the provider objects to does not end the sweep.
                runCatching {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, fileDocId)
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
                        // Resolve the writer key before dropping the URI from safFileCache,
                        // which is what the lookup matches on. Most expired files have no
                        // open handle, so no key is the normal case.
                        val writerKey = safWriters.keys().firstOrNull { safFileCache[it] == fileUri }
                        if (writerKey != null) {
                            safWriters.take(writerKey)?.runCatching { close() }
                            writeLocks.remove(writerKey)
                        }
                        safFileCache.entries.removeIf { it.value == fileUri }
                        runCatching { DocumentsContract.deleteDocument(resolver, fileUri) }
                    }
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

        // Per-file lock, shared with the internal path. The two key namespaces are distinct
        // (absolute paths against "$treeUri|…" composites) so they cannot collide.
        synchronized(writeLockFor(cacheKey)) {
            // Fast path: write through the open stream.
            val cached = safWriters.acquireExisting(cacheKey)
            if (cached != null) {
                val writeOk = try {
                    runCatching {
                        cached.write((line + "\n").toByteArray(Charsets.UTF_8))
                        cached.flush()
                    }.isSuccess
                } finally {
                    safWriters.release(cacheKey)
                }
                if (writeOk) return
                // The document was probably deleted externally. Drop it and re-resolve, which
                // recreates the file.
                safWriters.take(cacheKey)?.runCatching { close() }
                safFileCache.remove(cacheKey)
            }

            // Slow path: resolve (or create) the document URI, open and cache a new stream.
            val fileUri = resolveOrCreateSafFile(resolver, treeUri, netDirName, fileName, cacheKey)
            ?: return   // provider refused to create; silently drop this line

            val stream = runCatching {
                resolver.openOutputStream(fileUri, "wa")
                ?.let { java.io.BufferedOutputStream(it, 8192) }
            }.getOrNull() ?: return  // couldn't open; drop this line

            safWriters.acquire(cacheKey) { stream }
            val writeOk = try {
                runCatching {
                    stream.write((line + "\n").toByteArray(Charsets.UTF_8))
                    stream.flush()
                }.isSuccess
            } finally {
                safWriters.release(cacheKey)
            }
            if (!writeOk) {
                // Opened but the first write failed, so drop it and let the next call retry.
                safWriters.take(cacheKey)?.runCatching { close() }
                safFileCache.remove(cacheKey)
            }
        }
    }

    /**
     * Resolve the SAF document URI for [fileName] inside the [netDirName] subdirectory of
     * [treeUri], creating the directory and/or file if they do not yet exist.
     * Updates [safFileCache] on success. Returns null if the provider rejects creation,
     * or if the tree URI has been flagged unreadable for this install (e.g. after a
     * backup-restore that brought over the URI string without its persisted permission).
     */
    private fun resolveOrCreateSafFile(
        resolver: ContentResolver,
        treeUri: Uri,
        netDirName: String,
        fileName: String,
        cacheKey: String,
    ): Uri? {
        if (isTreeUriUnreadable(treeUri)) return null
            val rootDocId  = DocumentsContract.getTreeDocumentId(treeUri)
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)

            val netDirUri  = findOrCreateChildDir(resolver, treeUri, rootDocUri, rootDocId, netDirName)
            ?: return null
            val netDirDocId = DocumentsContract.getDocumentId(netDirUri)

            val fileUri = findChild(resolver, treeUri, netDirDocId, fileName)
            ?.let { (docId, _) -> DocumentsContract.buildDocumentUriUsingTree(treeUri, docId) }
            ?: findOrCreateChildFile(resolver, treeUri, netDirUri, netDirDocId, fileName)
            ?: return null

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
    ): Uri? {
        if (isTreeUriUnreadable(treeUri)) return null
            findChild(resolver, treeUri, parentDocId, displayName)?.let { (docId, _) ->
                return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            }
            // createDocument throws SecurityException for the same reason findChild does
            // (the persisted SAF grant isn't valid for this process). Treat the URI as
            // unreadable from now on so we don't keep hammering the provider.
            return try {
                DocumentsContract.createDocument(resolver, parentDocUri, Document.MIME_TYPE_DIR, displayName)
                ?: parentDocUri
            } catch (_: SecurityException) {
                markTreeUriUnreadable(treeUri)
                null
            } catch (_: IllegalArgumentException) {
                markTreeUriUnreadable(treeUri)
                null
            }
    }

    private fun findOrCreateChildFile(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocUri: Uri,
        parentDocId: String,
        displayName: String,
    ): Uri? {
        if (isTreeUriUnreadable(treeUri)) return null
            findChild(resolver, treeUri, parentDocId, displayName)?.let { (docId, _) ->
                return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            }
            return try {
                DocumentsContract.createDocument(resolver, parentDocUri, "text/plain", displayName)
                ?: parentDocUri
            } catch (_: SecurityException) {
                markTreeUriUnreadable(treeUri)
                null
            } catch (_: IllegalArgumentException) {
                markTreeUriUnreadable(treeUri)
                null
            }
    }

    /**
     * Tracks SAF tree URIs we've already discovered are unreadable in this process. When
     * a backup restore brings over the user's chosen `logFolderUri` from a previous
     * install, the new install doesn't inherit the persisted SAF permission grant
     * (those are stored in the system per-package per-install, not in app data and so
     * are not part of any backup). The first read attempt on that URI fails with a
     * SecurityException; subsequent attempts would all fail the same way and just spam
     * the log. We remember the URI here so [findChild] and [queryChildren] can
     * short-circuit cheaply on every later call without re-issuing the doomed query.
     * Bounded growth: there's only ever 1 or 2 entries (the user's old + new picks).
     *
     * Exposed publicly as [unreadableTreeUrisFlow] so the ViewModel can surface a
     * "re-pick your log folder" badge in Settings when the user's currently-saved URI
     * lands in here.
     */
    private val unreadableTreeUris = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val _unreadableTreeUrisFlow = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** Observe the set of SAF tree URIs that the current install can't read. Updated
     *  whenever [findChild] / [queryChildren] / [findOrCreateChildDir] / [findOrCreateChildFile]
     *  hit SecurityException (or IllegalArgumentException on a malformed URI). */
    val unreadableTreeUrisFlow: kotlinx.coroutines.flow.StateFlow<Set<String>>
    get() = _unreadableTreeUrisFlow

    /** Clear the unreadable flag for [treeUri]. Call when the user has re-picked the
     *  log folder in Settings so the next read attempt actually tries the provider
     *  again. Not strictly required (a re-pick almost always produces a NEW URI string
     *  even for "the same" folder, because Android mints fresh tree-doc-ids), but
     *  protects against the edge case where the URI happens to match. */
    fun clearUnreadable(treeUri: String) {
        if (unreadableTreeUris.remove(treeUri)) {
            _unreadableTreeUrisFlow.value = unreadableTreeUris.toSet()
        }
    }

    private fun isTreeUriUnreadable(treeUri: Uri): Boolean =
        unreadableTreeUris.contains(treeUri.toString())

        private fun markTreeUriUnreadable(treeUri: Uri) {
            if (unreadableTreeUris.add(treeUri.toString())) {
                android.util.Log.w(
                    "LogWriter",
                    "SAF tree URI is not readable by this install (probably a stale logFolderUri " +
                    "after backup-restore - SAF permissions don't transfer across reinstalls). " +
                    "Will silently skip log reads/writes for this URI: $treeUri"
                )
                _unreadableTreeUrisFlow.value = unreadableTreeUris.toSet()
            }
        }

        private fun findChild(
            resolver: ContentResolver,
            treeUri: Uri,
            parentDocId: String,
            displayName: String,
        ): Pair<String, String>? {
            if (isTreeUriUnreadable(treeUri)) return null
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
                val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
                // ContentResolver.query can throw SecurityException ("Permission Denial: opening
                // provider ... requires that you obtain access using ACTION_OPEN_DOCUMENT") when
                // the URI's persisted permission grant isn't valid for this process. The most
                // common trigger is a backup-restore on a fresh install: the saved tree URI is in
                // settings but the SAF grant isn't (those don't travel through Android Auto Backup
                // / D2D transfer). Without this catch the throw propagates up through readTailSaf,
                // through ensureBuffer's IO-dispatched scrollback launch, and crashes the whole
                // process - the user sees the app close as soon as they tap Connect because
                // ensureServerBuffer fires right at the start of every connect.
                try {
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
                } catch (_: SecurityException) {
                    markTreeUriUnreadable(treeUri)
                    return null
                } catch (_: IllegalArgumentException) {
                    // Provider rejected the URI shape (very rarely seen with malformed tree URIs
                    // after a corrupt restore). Treat the same as a missing folder.
                    markTreeUriUnreadable(treeUri)
                    return null
                }
                return null
        }

        /** Returns list of (docId, displayName, mimeType) triples for the direct children of [parentDocId]. */
        private data class DocEntry(val docId: String, val name: String, val mime: String)
            private fun queryChildren(resolver: ContentResolver, treeUri: Uri, parentDocId: String): List<DocEntry> {
                if (isTreeUriUnreadable(treeUri)) return emptyList()
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
                    val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
                    val result = mutableListOf<DocEntry>()
                    // Same SecurityException-catch rationale as findChild above.
                    try {
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
                    } catch (_: SecurityException) {
                        markTreeUriUnreadable(treeUri)
                        return emptyList()
                    } catch (_: IllegalArgumentException) {
                        markTreeUriUnreadable(treeUri)
                        return emptyList()
                    }
                    return result
            }

            // Filename helpers
            private fun safeNetworkDirName(networkName: String): String {
                // Network names usually come from the user's profile-name field which is hand-typed
                // and rarely contains FAT/NTFS-illegal characters, but a paranoid pass costs nothing.
                // See safeBufferFileName below for the rationale on the wider character set.
                val cleaned = networkName.trim()
                .replace("\\", "_")
                .replace("/", "_")
                .replace(":", "_")
                .replace("*", "_")
                .replace("?", "_")
                .replace("\"", "_")
                .replace("<", "_")
                .replace(">", "_")
                .replace("|", "_")
                .replace("\u0000", "")
                .trim()
                .take(80)
                return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "network" else cleaned
            }

            /** Canonical filename for internal storage. '#' is valid on EXT4/F2FS. */
            private fun safeBufferFileName(buffer: String): String {
                val name = if (buffer == "*server*") "server" else buffer
                // Strip characters that are illegal on FAT/NTFS-based filesystems. Android internal
                // storage is ext4/f2fs and accepts almost anything except '/' and NUL, but the same
                // sanitiser path serves SAF-backed external storage too — and SAF providers backed
                // by removable SD cards (vfat) or by Windows-hosted cloud sync (Google Drive's
                // Windows client, OneDrive, Dropbox) reject these. Buffer names commonly contain
                // them: ZNC pseudo-users like `*status`, `*controlpanel`, BouncerServ; soju queries
                // with `?` in nicks; punctuation in PM nicks. Stripping makes the same sanitiser
                // safe across every storage backend so a user who later switches log location
                // doesn't suddenly start losing lines.
                val cleaned = name.trim()
                .replace("\\", "_")
                .replace("/", "_")
                .replace(":", "_")
                .replace("*", "_")
                .replace("?", "_")
                .replace("\"", "_")
                .replace("<", "_")
                .replace(">", "_")
                .replace("|", "_")
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
