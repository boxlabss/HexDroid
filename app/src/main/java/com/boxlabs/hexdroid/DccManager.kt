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

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteOrder
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * Parsed CTCP DCC SEND offer.
 *
 * Supports active DCC and passive/reverse DCC (token + port 0 handshake).
 */
data class DccOffer(
    /** Network id this offer belongs to (filled by the ViewModel). */
    val netId: String = "",
    val from: String,
    val filename: String,
    val ip: String,
    val port: Int,
    val size: Long,
    /** Passive/reverse DCC token (HexChat "Passive DCC"). */
    val token: Long? = null,
    /** Turbo DCC / TSEND: do not send ACKs back to the sender. */
    val turbo: Boolean = false,
    /** SDCC / SSEND: transfer is TLS-wrapped. */
    val secure: Boolean = false,
) {
    val isPassive: Boolean get() = port == 0 && token != null
}

/** Parsed CTCP DCC CHAT offer. */
data class DccChatOffer(
    /** Network id this offer belongs to (filled by the ViewModel). */
    val netId: String = "",
    val from: String,
    val protocol: String = "chat",
    val ip: String,
    val port: Int,
    /** Passive/reverse token (rare; included for forward compatibility). */
    val token: Long? = null,
    /** SDCC / SCHAT: session is TLS-wrapped. */
    val secure: Boolean = false,
) {
    val isPassive: Boolean get() = port == 0 && token != null
}

sealed class DccTransferState {
    data class Incoming(
        val offer: DccOffer,
        val received: Long = 0,
        val startTimeMs: Long = System.currentTimeMillis(),
        val done: Boolean = false,
        val error: String? = null,
        val savedPath: String? = null,
        /** Epoch ms when the transfer finished; null while still in progress.
         *  Stored so the completed avg-speed display uses the actual transfer
         *  duration rather than ever-growing wall-clock time after completion. */
        val endTimeMs: Long? = null
    ) : DccTransferState()

    data class Outgoing(
        val target: String,
        val filename: String,
        val fileSize: Long = 0,     // Total bytes; 0 = unknown (e.g. stream source)
        val bytesSent: Long = 0,
        val startTimeMs: Long = System.currentTimeMillis(),
        val done: Boolean = false,
        val error: String? = null,
        /** Epoch ms when the transfer finished; null while in progress. See [Incoming.endTimeMs]. */
        val endTimeMs: Long? = null
    ) : DccTransferState()

}

/**
 * DCC Manager handles file send/receive and DCC CHAT socket lifecycle.
 *
 * Security notes:
 *  - [bindFirstAvailable] iterates ports min..max; each failed `ServerSocket()` call cleans
 *    up its own OS resources before throwing, so there is no leak on the error path.
 *  - Passive DCC offers are validated: port must be 0 AND token must be present. Malformed
 *    offers (port=0, no token) throw [IllegalArgumentException] before any socket is opened.
 *  - Turbo DCC (TSEND) skips per-chunk ACKs; the caller is responsible for confirming file
 *    integrity out-of-band (e.g. hash check).
 *  - Remote IPs are validated before connecting: loopback, link-local, wildcard, and multicast
 *    addresses are rejected. RFC-1918 private addresses are intentionally allowed to support
 *    LAN DCC between devices on the same network.
 */
class DccManager(ctx: Context) {

    // Avoid leaking an Activity context.
    private val ctx: Context = ctx.applicationContext

    /**
     * Wraps a plain socket with TLS for SDCC (Secure DCC).
     * Uses the default SSLSocketFactory which trusts system CAs.
     * SDCC peers are often self-signed; we intentionally skip hostname verification
     * since DCC is IP-addressed, not hostname-addressed. The encryption still provides
     * confidentiality against passive eavesdroppers.
     */
    private fun wrapTls(sock: Socket, host: String): Socket {
        val sf = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        val ssl = sf.createSocket(sock, host, sock.port, true) as javax.net.ssl.SSLSocket
        // Disable hostname verification; DCC uses raw IPs.
        ssl.sslParameters = ssl.sslParameters.also { it.endpointIdentificationAlgorithm = null }
        ssl.startHandshake()
        return ssl
    }

    /**
     * Rejects IPs that are structurally invalid targets for a DCC connection.
     *
     * RFC-1918 private addresses (192.168.x.x, 10.x.x.x, 172.16-31.x.x) are intentionally
     * allowed here — LAN DCC between devices on the same network is a normal and common use
     * case (e.g. sharing files with someone on the same Wi-Fi). The SSRF risk for these
     * addresses is low because DCC is a raw TCP connection, not an HTTP request, so it cannot
     * be easily weaponised to exfiltrate data from a local HTTP service.
     *
     * We still block:
     *  - Loopback (127.x.x.x / ::1): no legitimate DCC offer uses localhost.
     *  - Link-local (169.254.x.x / fe80::): APIPA / router-link addresses; not routable.
     *  - Wildcard (0.0.0.0 / ::): meaningless as a connect target.
     *  - Multicast: not a unicast endpoint.
     */
    private fun validateRemoteIp(ip: String) {
        val addr = try {
            java.net.InetAddress.getByName(ip)
        } catch (_: Throwable) {
            throw IllegalArgumentException("DCC: invalid IP address: $ip")
        }
        if (addr.isLoopbackAddress)
            throw IllegalArgumentException("DCC: refusing connection to loopback address $ip")
        if (addr.isLinkLocalAddress)
            throw IllegalArgumentException("DCC: refusing connection to link-local address $ip")
        if (addr.isAnyLocalAddress)
            throw IllegalArgumentException("DCC: refusing connection to wildcard address $ip")
        if (addr.isMulticastAddress)
            throw IllegalArgumentException("DCC: refusing connection to multicast address $ip")
    }

    /**
     * Internal fallback directory for DCC files (used when external storage is unavailable).
     */
    private fun internalDccDir(): File = File(ctx.filesDir, "dcc").apply { mkdirs() }

    /**
     * Get the default Downloads directory.
     */
    @Suppress("DEPRECATION")
    private fun defaultDownloadsDir(): File {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    /**
     * Create a safe output file for an incoming DCC transfer.
     *
     * @param displayName The filename from the DCC offer
     * @param customFolderUri Optional SAF URI for custom download folder
     * @return Pair of (OutputStream to write to, display path for UI)
     */
    fun createDccOutputStream(displayName: String, customFolderUri: String?): Pair<OutputStream, String> {
        val safeName = sanitizeFilename(displayName)
        
        // If custom folder is set, use SAF
        if (!customFolderUri.isNullOrBlank()) {
            return createSafOutputStream(safeName, customFolderUri)
        }
        
        // On Android 10+, use MediaStore for Downloads
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return createMediaStoreOutputStream(safeName)
        }
        
        // On older Android, write directly to Downloads folder
        return createLegacyOutputStream(safeName)
    }

    private fun sanitizeFilename(displayName: String): String {
        val base = displayName.substringAfterLast('/').substringAfterLast('\\').trim()
        return base
            .replace(Regex("[\\/:\\*?\"<>|]"), "_")
            .replace("\u0000", "")
            .let { if (it == "." || it == ".." || it.isBlank()) "dcc_download.bin" else it }
            .take(120)
    }

    private fun createSafOutputStream(filename: String, folderUri: String): Pair<OutputStream, String> {
        val treeUri = Uri.parse(folderUri)
        val tree = DocumentFile.fromTreeUri(ctx, treeUri)
            ?: throw IOException("Cannot access folder: $folderUri")
        
        // Find unique filename
        var finalName = filename
        var counter = 1
        while (tree.findFile(finalName) != null) {
            val stem = filename.substringBeforeLast('.', filename)
            val ext = filename.substringAfterLast('.', "")
            finalName = if (ext.isNotBlank()) "$stem ($counter).$ext" else "$stem ($counter)"
            counter++
            if (counter > 999) {
                finalName = "${stem}_${System.currentTimeMillis()}.${ext.ifBlank { "bin" }}"
                break
            }
        }
        
        val mimeType = "application/octet-stream"
        val newFile = tree.createFile(mimeType, finalName)
            ?: throw IOException("Failed to create file: $finalName")
        
        val outputStream = ctx.contentResolver.openOutputStream(newFile.uri)
            ?: throw IOException("Failed to open output stream for: $finalName")
        
        return Pair(outputStream, newFile.uri.toString())
    }

    private fun createMediaStoreOutputStream(filename: String): Pair<OutputStream, String> {
        val resolver = ctx.contentResolver
        
        // Find unique filename
        var finalName = filename
        var counter = 1
        while (true) {
            val exists = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(finalName),
                null
            )?.use { it.count > 0 } ?: false
            
            if (!exists) break
            
            val stem = filename.substringBeforeLast('.', filename)
            val ext = filename.substringAfterLast('.', "")
            finalName = if (ext.isNotBlank()) "$stem ($counter).$ext" else "$stem ($counter)"
            counter++
            if (counter > 999) {
                finalName = "${stem}_${System.currentTimeMillis()}.${ext.ifBlank { "bin" }}"
                break
            }
        }
        
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, finalName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry for: $finalName")
        
        val outputStream = resolver.openOutputStream(uri)
            ?: throw IOException("Failed to open output stream for: $finalName")
        
        // Return a wrapper that marks IS_PENDING = 0 when closed
        val wrappedStream = object : OutputStream() {
            override fun write(b: Int) = outputStream.write(b)
            override fun write(b: ByteArray) = outputStream.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = outputStream.write(b, off, len)
            override fun flush() = outputStream.flush()
            override fun close() {
                outputStream.close()
                val updateValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, updateValues, null, null)
            }
        }
        
        // Return the content:// URI string (not a display path) so shareFile can
        // resolve and open the file via ContentResolver on Android 10+.
        return Pair(wrappedStream, uri.toString())
    }

    @Suppress("DEPRECATION")
    private fun createLegacyOutputStream(filename: String): Pair<OutputStream, String> {
        val downloadsDir = defaultDownloadsDir()
        downloadsDir.mkdirs()
        
        // Find unique filename
        var file = File(downloadsDir, filename)
        var counter = 1
        while (file.exists()) {
            val stem = filename.substringBeforeLast('.', filename)
            val ext = filename.substringAfterLast('.', "")
            val newName = if (ext.isNotBlank()) "$stem ($counter).$ext" else "$stem ($counter)"
            file = File(downloadsDir, newName)
            counter++
            if (counter > 999) {
                file = File(downloadsDir, "${stem}_${System.currentTimeMillis()}.${ext.ifBlank { "bin" }}")
                break
            }
        }
        
        return Pair(FileOutputStream(file), file.absolutePath)
    }

    // local IPv4 in dotted notation
    fun localIpv4OrNull(): String? {
        return try {
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = Collections.list(iface.inetAddresses)
                for (a in addrs) {
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        return a.hostAddress
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    // local IPv4 as an unsigned 32-bit integer (decimal string in CTCP)
    fun localIpv4AsInt(): Long {
        val ip = localIpv4OrNull() ?: return 0L
        return ipv4ToLongBestEffort(ip)
    }

    /**
     * Standard DCC RECEIVE (we connect to sender's ip:port).
     * 
     * @param customFolderUri Optional SAF URI for custom download folder (null = Downloads)
     * @return The path/URI where the file was saved
     */
    suspend fun receive(
        offer: DccOffer,
        customFolderUri: String?,
        onProgress: (Long, Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        // Passive DCC offers carry port=0; calling Socket(ip, 0) is undefined behaviour.
        // The caller should use receivePassive() for those. Catch misrouted calls early.
        require(offer.port > 0) {
            "DCC RECEIVE: port must be > 0 for active DCC (use receivePassive() for passive offers)"
        }

        // Validate the remote IP BEFORE creating the output file. createDccOutputStream
        // creates a real on-disk file (or SAF document); if validateRemoteIp threw after
        // it, the file was leaked as zero bytes. Open the socket here too for the same
        // reason — if the connect fails, no file gets created.
        validateRemoteIp(offer.ip)
        val rawSock = Socket(offer.ip, offer.port)
        val sock = if (offer.secure) wrapTls(rawSock, offer.ip) else rawSock

        val (outputStream, savedPath) = try {
            createDccOutputStream(offer.filename, customFolderUri)
        } catch (t: Throwable) {
            runCatching { sock.close() }
            throw t
        }
        var receivedAnyBytes = false
        val cancelHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { runCatching { sock.close() } }
        try {
            sock.use { s ->
                // Wrap in a BufferedOutputStream to reduce IPC round-trips for SAF/MediaStore
                // streams, but keep a reference so we can flush it explicitly before the inner
                // stream is closed. Without this flush, small files (<256 KB) are fully
                // received into the buffer but never written: the outer outputStream.use{}
                // closes the raw stream, silently discarding the buffered bytes.
                val buffered = java.io.BufferedOutputStream(outputStream, 256 * 1024)
                try {
                    receiveFromSocket(s, buffered, offer.size, offer.turbo) { sent, total ->
                        if (sent > 0) receivedAnyBytes = true
                        onProgress(sent, total)
                    }
                } finally {
                    runCatching { buffered.flush() }
                    outputStream.close()
                    if (!receivedAnyBytes) deleteSavedPathIfEmpty(savedPath)
                }
            }
        } finally {
            cancelHandle?.dispose()
        }

        savedPath
    }

    /**
     * Passive/Reverse DCC RECEIVE.
     *
     * The remote sender offered port 0 + token. We open a listening port, reply with the port,
     * then accept the incoming connection and receive.
     * 
     * @param customFolderUri Optional SAF URI for custom download folder (null = Downloads)
     * @return The path/URI where the file was saved
     */
    suspend fun receivePassive(
        offer: DccOffer,
        portMin: Int,
        portMax: Int,
        customFolderUri: String?,
        onListening: suspend (ipAsInt: Long, port: Int, size: Long, token: Long) -> Unit,
        onProgress: (Long, Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        // Validate passive offer: must have a token AND port must be 0. Some misbehaving clients
        // send port 0 without a token (malformed passive DCC) - catch this early.
        if (offer.port != 0) throw IllegalArgumentException("Passive DCC offer has non-zero port: ${offer.port}")
        val token = offer.token ?: throw IllegalArgumentException("Passive DCC offer is missing token (port=0 but no token)")

        // Bind FIRST so a port-exhausted failure doesn't leave a leaked zero-byte file
        // on disk. Same rationale as the validateRemoteIp-before-createDccOutputStream
        // ordering in receive() above.
        val ss = bindFirstAvailable(portMin, portMax)
        val (outputStream, savedPath) = try {
            createDccOutputStream(offer.filename, customFolderUri)
        } catch (t: Throwable) {
            runCatching { ss.close() }
            throw t
        }
        // If anything goes wrong before bytes start arriving, close+delete the file so the
        // user doesn't end up with a zero-byte placeholder cluttering Downloads.
        var receivedAnyBytes = false
        // Close the ServerSocket on cancellation so accept() unblocks immediately.
        val ssCancelHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { runCatching { ss.close() } }
        try {
            ss.soTimeout = 45_000
            val ipInt = localIpv4AsInt()
            onListening(ipInt, ss.localPort, offer.size, token)

            val rawSock = try {
                ss.accept()
            } catch (t: Throwable) {
                outputStream.close()
                deleteSavedPathIfEmpty(savedPath)
                throw if (t is java.net.SocketTimeoutException)
                    RuntimeException("DCC RECEIVE timed out waiting for sender to connect")
                else t
            }
            val sock = try {
                if (offer.secure) wrapTls(rawSock, rawSock.inetAddress?.hostAddress ?: "0.0.0.0") else rawSock
            } catch (t: Throwable) {
                runCatching { rawSock.close() }
                outputStream.close()
                deleteSavedPathIfEmpty(savedPath)
                throw t
            }

            val sockCancelHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { runCatching { sock.close() } }
            try {
                sock.use { s ->
                    val buffered = java.io.BufferedOutputStream(outputStream, 256 * 1024)
                    try {
                        receiveFromSocket(s, buffered, offer.size, offer.turbo) { sent, total ->
                            if (sent > 0) receivedAnyBytes = true
                            onProgress(sent, total)
                        }
                    } finally {
                        runCatching { buffered.flush() }
                        outputStream.close()
                        if (!receivedAnyBytes) deleteSavedPathIfEmpty(savedPath)
                    }
                }
            } finally {
                sockCancelHandle?.dispose()
            }
        } finally {
            ssCancelHandle?.dispose()
            runCatching { ss.close() }
        }

        savedPath
    }

    /**
     * Best-effort delete of a savedPath when the transfer didn't actually receive any
     * bytes. Handles both file:// paths and SAF/MediaStore content:// URIs. Failures are
     * swallowed: a leaked zero-byte file is annoying but not a correctness issue, so
     * "delete failed" is not worth bubbling up over the original cause.
     */
    private fun deleteSavedPathIfEmpty(savedPath: String) {
        runCatching {
            if (savedPath.startsWith("content://")) {
                val uri = Uri.parse(savedPath)
                ctx.contentResolver.delete(uri, null, null)
            } else {
                File(savedPath).takeIf { it.exists() && it.length() == 0L }?.delete()
            }
        }
    }

    /**
     * Standard (active) DCC SEND: we listen on a port in portMin..portMax and send when peer connects.
     */
    suspend fun sendFile(
        file: File,
        portMin: Int,
        portMax: Int,
        secure: Boolean = false,
        onClient: suspend (ipAsInt: Long, port: Int, size: Long) -> Unit,
        onProgress: (Long, Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val size = file.length()
        val ss = bindFirstAvailable(portMin, portMax)
        val cancelHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { runCatching { ss.close() } }
        try {
            val ipInt = localIpv4AsInt()
            onClient(ipInt, ss.localPort, size)

            ss.soTimeout = 45_000
            val rawSock = try {
                ss.accept()
            } catch (_: java.net.SocketTimeoutException) {
                throw RuntimeException("DCC SEND timed out waiting for peer to connect")
            }
            val sock = if (secure) wrapTls(rawSock, rawSock.inetAddress.hostAddress ?: "") else rawSock

            sock.use { s ->
                val sockHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { runCatching { s.close() } }
                try {
                    sendOverSocket(s, file, size, onProgress)
                } finally {
                    sockHandle?.dispose()
                }
            }
        } finally {
            cancelHandle?.dispose()
            runCatching { ss.close() }
        }
    }

    /**
     * Passive/Reverse DCC SEND: peer opened a port; we connect out and send.
     */
    suspend fun sendFileConnect(
        file: File,
        host: String,
        port: Int,
        secure: Boolean = false,
        onProgress: (Long, Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val size = file.length()
        validateRemoteIp(host)
        val rawSock = Socket(host, port)
        val sock = if (secure) wrapTls(rawSock, host) else rawSock
        val cancelHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { runCatching { sock.close() } }
        try {
            sock.use { s ->
                sendOverSocket(s, file, size, onProgress)
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    /**
     * Standard (active) DCC CHAT: we listen on a port in portMin..portMax and accept when peer connects.
     * Returns the connected socket.
     */
    suspend fun startChat(
        portMin: Int,
        portMax: Int,
        onClient: suspend (ipAsInt: Long, port: Int) -> Unit
    ): Socket = withContext(Dispatchers.IO) {
        val ss = bindFirstAvailable(portMin, portMax)
        try {
            val ipInt = localIpv4AsInt()
            onClient(ipInt, ss.localPort)
            ss.soTimeout = 45_000
            val sock = try {
                ss.accept()
            } catch (_: java.net.SocketTimeoutException) {
                throw RuntimeException("DCC CHAT timed out waiting for peer to connect")
            }
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock
        } finally {
            runCatching { ss.close() }
        }
    }

    /** Standard (active) DCC CHAT: connect to the offered ip:port and return the connected socket. */
    suspend fun connectChat(offer: DccChatOffer): Socket = withContext(Dispatchers.IO) {
        val rawSock = Socket(offer.ip, offer.port)
        val sock = if (offer.secure) wrapTls(rawSock, offer.ip) else rawSock
        sock.tcpNoDelay = true
        sock.keepAlive = true
        sock
    }

    private fun receiveFromSocket(
        sock: Socket,
        outputStream: OutputStream,
        expectedSize: Long,
        turbo: Boolean,
        onProgress: (Long, Long) -> Unit
    ) {
        sock.tcpNoDelay = true
        sock.getInputStream().use { inp ->
            val buf = ByteArray(32 * 1024)
            // Reuse a single 4-byte array for ACKs instead of allocating via
            // ByteBuffer.allocate(4) on every chunk (~3200 allocations per 100 MB transfer).
            val ackBuf = ByteArray(4)
            var total = 0L
            val expected: Long? = expectedSize.takeIf { it > 0L }

            // Hard ceiling for transfers without an advertised size: 8 GB. Without this,
            // a malicious sender that omits the size field could keep writing forever and
            // fill the device's storage, causing the OS to start killing apps — including
            // ours. With a known size we still cap at the advertised size so a sender that
            // lies (offers 1 KB then sends 10 GB) can't bypass the limit either.
            val maxAccept: Long = expected ?: (8L * 1024 * 1024 * 1024)

            while (true) {
                // Don't read more than we're prepared to keep. If the next read would
                // exceed maxAccept, shrink the read window so we stop precisely at the cap
                // rather than discarding the overshoot.
                val remaining = maxAccept - total
                if (remaining <= 0L) break
                val toRead = if (remaining < buf.size) remaining.toInt() else buf.size
                val n = inp.read(buf, 0, toRead)
                if (n <= 0) break

                outputStream.write(buf, 0, n)
                total += n
                onProgress(total, expectedSize)
                if (!turbo) {
                    // DCC SEND expects an ACK of total bytes received (4-byte unsigned int, network byte order).
                    val ackInt = total.coerceAtMost(0xFFFFFFFFL).toInt()
                    ackBuf[0] = (ackInt ushr 24).toByte()
                    ackBuf[1] = (ackInt ushr 16).toByte()
                    ackBuf[2] = (ackInt ushr  8).toByte()
                    ackBuf[3] =  ackInt.toByte()
                    runCatching { sock.getOutputStream().write(ackBuf) }
                }

                if (expected != null && total >= expected) break
            }
        }
    }

    private fun sendOverSocket(
        sock: Socket,
        file: File,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        sock.tcpNoDelay = true
        // Used by the ACK reader thread.
        sock.soTimeout = 1_000

        val acked = AtomicLong(0L)

        fun u32be(b: ByteArray): Long =
            (ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL)

        fun u32le(b: ByteArray): Long =
            (ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL)

        fun chooseAck(be: Long, le: Long, last: Long): Long? {
            // DCC ACKs are commonly network byte order, but some clients historically send host order.
            // We choose the value that is monotonic and plausible for this transfer size.
            val cands = sequenceOf(be, le).distinct().filter { it >= last }.toList()
            if (cands.isEmpty()) return null
            if (size > 0L) {
                // Prefer candidates <= size.
                val inRange = cands.filter { it <= size }
                if (inRange.isNotEmpty()) return inRange.maxOrNull()

                // Some clients may overshoot slightly; clamp.
                val near = cands.filter { it <= size + 1024 * 1024L }
                if (near.isNotEmpty()) return size
            }
            return cands.maxOrNull()
        }

        val ackThread = thread(start = true, isDaemon = true, name = "dcc-ack-reader") {
            val inp = sock.getInputStream()
            val b = ByteArray(4)
            var off = 0
            var last = 0L
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val n = inp.read(b, off, 4 - off)
                    if (n < 0) break
                    off += n
                    if (off == 4) {
                        val be = u32be(b)
                        val le = u32le(b)
                        val chosen = chooseAck(be, le, last)
                        if (chosen != null) {
                            last = chosen
                            acked.set(chosen)
                        }
                        off = 0
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // keep polling
                } catch (_: Throwable) {
                    break
                }
            }
        }

        var sent = 0L
        try {
            val outRaw = sock.getOutputStream()
            val out = BufferedOutputStream(outRaw, 64 * 1024)
            val buf = ByteArray(32 * 1024)

            file.inputStream().use { fin ->
                while (true) {
                    val n = fin.read(buf)
                    if (n <= 0) break
                    try {
                        out.write(buf, 0, n)
                        sent += n
                        onProgress(sent, size)
                    } catch (io: IOException) {
                        // If the peer already ACKed the full size, treat as success.
                        if (size > 0L && acked.get() >= size) break
                        throw io
                    }
                }
            }
            out.flush()

            // Half-close so receiver sees EOF; then wait briefly for final ACK/peer close.
            runCatching { sock.shutdownOutput() }

            val deadline = System.currentTimeMillis() + 10_000L
            while (size > 0L && acked.get() < size && System.currentTimeMillis() < deadline) {
                // If the receiver closed, the ACK thread will stop.
                if (!ackThread.isAlive) break
                Thread.sleep(50)
            }
        } finally {
            runCatching { ackThread.interrupt() }
        }
}

    private fun bindFirstAvailable(min: Int, max: Int): ServerSocket {
        val a = min.coerceIn(1, 65535)
        val b = max.coerceIn(1, 65535)
        for (p in a..b) {
            try {
                return ServerSocket(p)
            } catch (_: Throwable) {
                // try next
            }
        }
        throw IllegalStateException("No free port in $a..$b")
    }

    private fun ipv4ToLongBestEffort(ip: String): Long {
        val parts = ip.split(".")
        if (parts.size != 4) return 0L
        return try {
            var out = 0L
            for (p in parts) out = (out shl 8) or (p.toLong() and 0xFFL)
            out
        } catch (_: Throwable) {
            0L
        }
    }

}