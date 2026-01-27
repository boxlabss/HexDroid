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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple line-based log writer.
 *
 * Default: app-private internal storage (Context.filesDir/logs)
 * Optional: user-selected folder via SAF (tree URI) for easier access
 *
 * One file per buffer (eg, #afternet.txt, user.txt)
 */
class LogWriter(private val ctx: Context) {

    // Cache resolved SAF file URIs so we don't keep creating duplicates on some providers.
    private val safFileCache = ConcurrentHashMap<String, Uri>()

    private fun internalRoot(): File = File(ctx.filesDir, "logs").apply { mkdirs() }

    fun logsRoot(): File = internalRoot()

    /**
     * Preferred log path:
     *   <root>/<network>/<buffer>
     *
     * save log file (example: #afternet.txt, Eck.txt)
     */
    fun logFileInternal(networkName: String, buffer: String): File {
        val netDir = File(internalRoot(), safeNetworkDirName(networkName)).apply { mkdirs() }
        val desired = File(netDir, safeBufferFileName(buffer))

        // if we have an older sanitized file and the new file doesn't exist,
        // rename it so scrollback works after upgrades.
        if (!desired.exists()) {
            val legacy = legacyInternalCandidates(netDir, buffer).firstOrNull { it.exists() }
            if (legacy != null) {
                runCatching {
                    legacy.renameTo(desired)
                }
            }
        }
        return desired
    }

    fun append(networkName: String, buffer: String, line: String, logFolderUri: String?) {
        runCatching {
            if (logFolderUri.isNullOrBlank()) {
                appendInternal(networkName, buffer, line)
            } else {
                appendSaf(Uri.parse(logFolderUri), networkName, buffer, line)
            }
        }
    }

    private fun appendInternal(networkName: String, buffer: String, line: String) {
        val f = logFileInternal(networkName, buffer)
        f.parentFile?.mkdirs()
        f.appendText(line + "\n", Charsets.UTF_8)
    }

    /**
     * Read the last [maxLines] lines from the most recent log file for [networkName]/[buffer].
     * Used to preload scrollback when (re)creating buffers.
     */
    fun readTail(networkName: String, buffer: String, maxLines: Int, logFolderUri: String?): List<String> {
        val n = maxLines.coerceIn(1, 5000)
        return if (logFolderUri.isNullOrBlank()) {
            readTailInternal(networkName, buffer, n)
        } else {
            readTailSaf(Uri.parse(logFolderUri), networkName, buffer, n)
        }
    }

    private fun readTailInternal(networkName: String, buffer: String, maxLines: Int): List<String> {
        val dir = File(internalRoot(), safeNetworkDirName(networkName))
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        // Prefer the new readable filename.
        val desired = File(dir, safeBufferFileName(buffer))

        // Back-compat: older versions used sanitized names and/or rotation.
        val legacyCandidates = legacyInternalCandidates(dir, buffer)

        // Choose the most recently modified candidate so scrollback works even if we end up
        // writing to a legacy file on some providers.
        val candidate: File? = sequenceOf(desired)
            .plus(legacyCandidates.asSequence())
            .filter { it.exists() && it.isFile }
            .maxByOrNull { it.lastModified() }

        val f = candidate ?: return emptyList()
        return f.inputStream().use { ins -> readTailFromStream(ins, maxLines) }
    }

    private fun readTailSaf(treeUri: Uri, networkName: String, buffer: String, maxLines: Int): List<String> {
        val resolver = ctx.contentResolver
        val netDirName = safeNetworkDirName(networkName)
        val desiredName = safeBufferFileName(buffer)

        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)

        val net = findChild(resolver, treeUri, rootDocId, netDirName) ?: return emptyList()
        val (netDocId, netMime) = net
        if (netMime != Document.MIME_TYPE_DIR) return emptyList()

        // Prefer the new readable filename, but also handle providers that auto-rename duplicates
        // to things like "#channel-1" or "#channel (1)".
        val direct = findChild(resolver, treeUri, netDocId, desiredName)?.let { (docId, _) ->
            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        } ?: findLatestChildFileByPrefix(
            resolver = resolver,
            treeUri = treeUri,
            parentDocId = netDocId,
            prefix = desiredName,
            suffix = ""
        )

        val legacyNames = legacySafDisplayNames(buffer)
        val legacyDirect = direct ?: legacyNames.firstNotNullOfOrNull { nm ->
            findChild(resolver, treeUri, netDocId, nm)?.let { (docId, _) ->
                DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            } ?: findLatestChildFileByPrefix(
                resolver = resolver,
                treeUri = treeUri,
                parentDocId = netDocId,
                prefix = nm,
                suffix = ""
            )
        }

        // Also support olkderrotation by prefix if present.
        val legacyRotated = legacyDirect ?: findLatestChildFileByPrefix(
            resolver = resolver,
            treeUri = treeUri,
            parentDocId = netDocId,
            prefix = sanitize(buffer) + "_",
            suffix = ".log"
        )

        val targetUri = legacyRotated ?: return emptyList()

        return runCatching {
            resolver.openInputStream(targetUri)?.use { ins ->
                readTailFromStream(ins, maxLines)
            } ?: emptyList()
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

    fun purgeOlderThan(days: Int, logFolderUri: String?) {
        // Retention purge is only implemented for internal storage for now.
        if (!logFolderUri.isNullOrBlank()) return

        val cutoff = System.currentTimeMillis() - days.coerceIn(1, 365) * 24L * 60L * 60L * 1000L
        internalRoot().walkTopDown().forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) {
                runCatching { f.delete() }
            }
        }
    }

    // SAF

    private fun appendSaf(treeUri: Uri, networkName: String, buffer: String, line: String) {
        val resolver = ctx.contentResolver

        val netDirName = safeNetworkDirName(networkName)
        val desiredName = safeBufferFileName(buffer)

        // Some document providers return child listings inconsistently, which can cause repeated
        // createDocument(...) calls and lots of one-line "duplicate" files. Cache the resolved file Uri.
        val cacheKey = "${treeUri}|$netDirName|$desiredName"
        safFileCache[cacheKey]?.let { cached ->
            appendToDocument(resolver, cached, line + "\n")
            return
        }

        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)

        val netDirUri = findOrCreateChildDir(resolver, treeUri, rootDocUri, rootDocId, netDirName)
        val netDirDocId = DocumentsContract.getDocumentId(netDirUri)

        // Prefer the new readable filename, but also accept provider-renamed duplicates.
        val desiredUri = findChild(resolver, treeUri, netDirDocId, desiredName)?.let { (docId, _) ->
            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        } ?: findLatestChildFileByPrefix(
            resolver = resolver,
            treeUri = treeUri,
            parentDocId = netDirDocId,
            prefix = desiredName,
            suffix = ""
        )

        // If a legacy file exists, try to rename it to the new name so users don't end up
        // with "channel-1" style provider renames.
        val legacyNames = legacySafDisplayNames(buffer)
        val legacyUri = desiredUri ?: legacyNames.firstNotNullOfOrNull { nm ->
            findChild(resolver, treeUri, netDirDocId, nm)?.let { (docId, _) ->
                DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            } ?: findLatestChildFileByPrefix(
                resolver = resolver,
                treeUri = treeUri,
                parentDocId = netDirDocId,
                prefix = nm,
                suffix = ""
            )
        }

        var fileUri = if (legacyUri != null && desiredUri == null) {
            // Attempt rename. If it fails, fall back to creating a new file with the desired name.
            val renamed = runCatching {
                DocumentsContract.renameDocument(resolver, legacyUri, desiredName)
            }.getOrNull()
            renamed ?: findOrCreateChildFile(resolver, treeUri, netDirUri, netDirDocId, desiredName)
        } else {
            desiredUri ?: findOrCreateChildFile(resolver, treeUri, netDirUri, netDirDocId, desiredName)
        }

        // If we fell back to the directory, file creation likely failed (some SAF providers reject
        // display names that start with symbols like '#'). In that case, try a few safe legacy/sanitized
        // names so channel scrollback doesn't silently stop working.
        if (fileUri == netDirUri) {
            val fallbacks = (legacySafDisplayNames(buffer) + listOf(
                "${sanitize(buffer).trimStart('_')}.log",
                sanitize(buffer).trimStart('_'),
                "${sanitize(buffer)}.log",
                sanitize(buffer)
            )).distinct()

            for (nm in fallbacks) {
                val u = findOrCreateChildFile(resolver, treeUri, netDirUri, netDirDocId, nm)
                if (u != netDirUri) {
                    fileUri = u
                    break
                }
            }
        }

        // Only cache if we actually resolved a file (not the directory fallback).
        if (fileUri != netDirUri) {
            safFileCache[cacheKey] = fileUri
        }

        appendToDocument(resolver, fileUri, line + "\n")
    }

    private fun findOrCreateChildDir(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocUri: Uri,
        parentDocId: String,
        displayName: String
    ): Uri {
        findChild(resolver, treeUri, parentDocId, displayName)?.let { (docId, _) ->
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        }

        val created = DocumentsContract.createDocument(resolver, parentDocUri, Document.MIME_TYPE_DIR, displayName)
        if (created != null) return created

        // If creation failed (provider quirks), fall back to parent.
        return parentDocUri
    }

    private fun findOrCreateChildFile(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocUri: Uri,
        parentDocId: String,
        displayName: String
    ): Uri {
        findChild(resolver, treeUri, parentDocId, displayName)?.let { (docId, _) ->
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        }

        // Some providers insist on "text/plain"; others don't care.
        val created = DocumentsContract.createDocument(resolver, parentDocUri, "text/plain", displayName)
        if (created != null) return created

        // Fallback: at least return the parent (will likely fail when writing, but avoids crashes).
        return parentDocUri
    }

    private fun findChild(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        displayName: String
    ): Pair<String, String /*mime*/>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE
        )

        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val name = c.getString(nameCol)
                if (name != null && name.trim().equals(displayName, ignoreCase = true)) {
                    val docId = c.getString(idCol)
                    val mime = c.getString(mimeCol)
                    return docId to mime
                }
            }
        }
        return null
    }

    /**
     * Find the most-recent child file in [parentDocId] whose display name matches [prefix]...[suffix].
     * Uses Document.COLUMN_LAST_MODIFIED when available.
     */
    private fun findLatestChildFileByPrefix(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        prefix: String,
        suffix: String
    ): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED
        )

        var bestDocId: String? = null
        var bestLastModified = Long.MIN_VALUE

        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(Document.COLUMN_MIME_TYPE)
            val lmCol = c.getColumnIndex(Document.COLUMN_LAST_MODIFIED)

            while (c.moveToNext()) {
                val mime = c.getString(mimeCol)
                if (mime == Document.MIME_TYPE_DIR) continue

                val name = c.getString(nameCol) ?: continue
                if (!name.startsWith(prefix) || !name.endsWith(suffix)) continue

                val lm = if (lmCol >= 0) c.getLong(lmCol) else 0L
                if (lm >= bestLastModified) {
                    bestLastModified = lm
                    bestDocId = c.getString(idCol)
                }
            }
        }

        val docId = bestDocId ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    }

    private fun appendToDocument(resolver: ContentResolver, docUri: Uri, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)

        // Try simple append mode first.
        runCatching {
            resolver.openOutputStream(docUri, "wa")?.use { os ->
                os.write(bytes)
                os.flush()
                return
            }
        }

        // Fallback: open RW and seek to end.
        resolver.openFileDescriptor(docUri, "rw")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).channel.use { ch ->
                ch.position(ch.size())
                ch.write(ByteBuffer.wrap(bytes))
                ch.force(true)
            }
        }
    }

    /**
     * sanitiser used by earlier versions.
     * Keep for back-compat when reading/migrating logs.
     */
    private fun sanitize(s: String): String =
        s.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .take(60)
            .ifBlank { "x" }

    /**
     * Only removes characters that would break filesystem paths.
     */
    private fun safeNetworkDirName(networkName: String): String {
        val trimmed = networkName.trim().ifBlank { "network" }
        val cleaned = trimmed
            .replace("\\", "_")
            .replace("/", "_")
            .replace("\u0000", "")
            .trim()
            .take(80)
        return if (cleaned == "." || cleaned == ".." || cleaned.isBlank()) "network" else cleaned
    }

    /**
     * filename for a buffer.
     * Example: "#afternet" stays "#afternet" (no suffix), server buffer becomes "server".
     */
    private fun safeBufferFileName(buffer: String): String {
        val name = when (buffer) {
            "*server*" -> "server"
            else -> buffer
        }
        val trimmed = name.trim().ifBlank { "buffer" }
        // Avoid path traversal / invalid names.
        val cleaned = trimmed
            .replace("\\", "_")
            .replace("/", "_")
            .replace("\u0000", "")
            .trim()
            .take(120)
        return if (cleaned == "." || cleaned == ".." || cleaned.isBlank()) "buffer" else cleaned
    }

    private fun legacyBufferBaseNames(buffer: String): List<String> {
        val raw = buffer.trim()
        // Some older builds stripped common channel prefix characters.
        val noChanPrefix = raw.trimStart('#', '&', '!', '+')

        // Keep order from most-likely recent -> older/looser matches.
        val out = LinkedHashSet<String>()
        out += sanitize(raw)
        out += sanitize(raw).trimStart('_')
        if (noChanPrefix.isNotBlank()) {
            out += sanitize(noChanPrefix)
            out += noChanPrefix
            out += noChanPrefix.lowercase()
        }
        // Some older builds used the raw buffer name directly.
        out += raw
        return out.filter { it.isNotBlank() }.take(12)
    }

    private fun legacyInternalCandidates(netDir: File, buffer: String): List<File> {
        val bases = legacyBufferBaseNames(buffer)
        val direct = bases.flatMap { b ->
            listOf(File(netDir, "$b.log"), File(netDir, b))
        }
        val rotated = bases.flatMap { b ->
            netDir.listFiles { f ->
                f.isFile && f.name.startsWith("${b}_") && f.name.endsWith(".log")
            }?.toList().orEmpty()
        }
        return direct + rotated
    }

    private fun legacySafDisplayNames(buffer: String): List<String> {
        val bases = legacyBufferBaseNames(buffer)
        // SAF display names are typically exact, but providers sometimes strip extensions.
        return bases.flatMap { b -> listOf("$b.log", b) }.distinct().take(24)
    }
}
