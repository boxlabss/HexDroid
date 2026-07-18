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

@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)

package com.boxlabs.hexdroid

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
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
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import com.boxlabs.hexdroid.connection.ProxyConfig
import com.boxlabs.hexdroid.connection.SocksProxy
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
    /** Passive/reverse DCC token. */
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

/**
 * Parsed CTCP `DCC RESUME <filename> <port> <position> [token]`.
 *
 * The receiver sends RESUME after detecting a partial download matching an active
 * (port>0) or passive (port=0+token) offer. The sender replies with DCC ACCEPT carrying
 * the same triple/quadruple if it agrees to resume.
 *
 * Filename may be quoted ("name with spaces"); the parser accepts either form.
 */
data class DccResume(
    val filename: String,
    val port: Int,
    val position: Long,
    /** Passive/reverse token, mirroring the original SEND offer's token. Null for active. */
    val token: Long? = null,
)

/**
 * Parsed CTCP `DCC ACCEPT <filename> <port> <position> [token]`.
 *
 * The sender's reply to a RESUME. Position MUST equal what the receiver requested;
 * the receiver should verify before opening the connection.
 */
data class DccAccept(
    val filename: String,
    val port: Int,
    val position: Long,
    val token: Long? = null,
)

/**
 * Thrown when a DCC receive ends with fewer bytes than the offer advertised. Distinct from
 * a generic [IOException] so the ViewModel can surface "incomplete" specifically (e.g. offer
 * a retry) rather than treating it like a connection error. Carries the byte counts for the
 * UI message.
 */
class DccIncompleteException(
    val received: Long,
    val expected: Long,
) : IOException("DCC transfer incomplete: received $received of $expected bytes (the sender closed the connection early)")

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
        val endTimeMs: Long? = null,
        /** Byte offset this transfer started at. 0 for a fresh transfer; non-zero
         *  when resumed via DCC RESUME. `received` is the total — including the
         *  resumed prefix — so progress UI works naturally. */
        val resumeOffset: Long = 0L
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
        val endTimeMs: Long? = null,
        /** Byte offset this send started at. Non-zero when the receiver requested DCC RESUME. */
        val resumeOffset: Long = 0L
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
     * Open an outbound TCP socket to [host]:[port], through [proxy] when it's enabled,
     * otherwise directly. The proxy is passed explicitly by each caller  so concurrent
     * transfers on different networks can't race over which proxy applies.
     *
     * Socket options match the prior direct-`Socket(host, port)` behaviour (tcpNoDelay +
     * keepAlive are set by the individual callers after this returns).
     */
    private fun openOutbound(host: String, port: Int, proxy: ProxyConfig): Socket {
        return if (proxy.enabled) {
            // The proxy resolves [host] remotely, so a literal IP or a hostname both work and
            // no on-device DNS lookup leaks. Reuse the IRC socket timeouts for parity.
            SocksProxy.connect(
                cfg = proxy,
                destHost = host,
                destPort = port,
                connectTimeoutMs = com.boxlabs.hexdroid.connection.ConnectionConstants.SOCKET_CONNECT_TIMEOUT_MS,
                soTimeoutMs = 0,  // DCC transfers are long-lived; rely on the transfer loop, not soTimeout
                tcpNoDelay = false,
                keepAlive = true,
            )
        } else {
            Socket(resolveConnectTarget(host), port)
        }
    }

    /**
     * Resolve a connect target, attaching a scope id when [host] is a bare IPv6 link-local literal
     * (fe80::...). DCC advertises link-local without a zone id (it is meaningful only on the sender's
     * own host); connecting to one requires telling the kernel which interface to egress on. On the
     * single-link networks where link-local DCC happens there is exactly one such interface, so we
     * bind the address to it.
     */
    private fun resolveConnectTarget(host: String): java.net.InetAddress {
        val addr = java.net.InetAddress.getByName(host)
        if (addr is Inet6Address && addr.isLinkLocalAddress && addr.scopeId == 0 && addr.scopedInterface == null) {
            linkLocalInterface()?.let { nif ->
                return Inet6Address.getByAddress(null, addr.address, nif)
            }
        }
        return addr
    }

    /**
     * The single up, non-loopback interface carrying an IPv6 link-local address, or null.
     *
     * Used to attach a scope id when dialing a bare fe80:: DCC target. Tries NetworkInterface first
     * (guarded per-interface so one throwing interface can't abort the scan) and, when that comes
     * back empty, resolves the active network's interface NAME via ConnectivityManager/LinkProperties
     * and re-looks-it-up with NetworkInterface.getByName.
     */
    private fun linkLocalInterface(): NetworkInterface? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            if (ifaces != null) {
                for (iface in Collections.list(ifaces)) {
                    try {
                        if (iface.isUp && !iface.isLoopback &&
                            Collections.list(iface.inetAddresses).any { it is Inet6Address && it.isLinkLocalAddress }
                        ) return iface
                    } catch (_: Throwable) {
                        // skip this interface, keep scanning the rest
                    }
                }
            }
        } catch (_: Throwable) {
            // fall through to the ConnectivityManager name fallback
        }
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val name = if (cm != null && net != null) cm.getLinkProperties(net)?.interfaceName else null
            if (!name.isNullOrEmpty()) NetworkInterface.getByName(name) else null
        } catch (_: Throwable) { null }
    }

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
        if (addr.isAnyLocalAddress)
            throw IllegalArgumentException(
                "DCC: peer advertised an unusable address ($ip). Its client could not determine a " +
                "routable local address, often an IPv6-only network with no global/ULA address. " +
                "Ask them to retry, or use a network with a routable address."
            )
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

    /**
     * Open the existing file at [savedPath] in append mode for resuming a DCC transfer.
     *
     * Returns the same kind of `OutputStream + savedPath` pair as [createDccOutputStream]
     * so the rest of the receive path doesn't need to special-case resume. The savedPath
     * is echoed back so callers don't need to remember whether it was originally a file://
     * path or a content:// URI.
     *
     * The returned stream is positioned at the existing end-of-file: subsequent writes
     * append. Callers must NOT seek or truncate it.
     */
    fun openDccOutputStreamForResume(savedPath: String): Pair<OutputStream, String> {
        val stream: OutputStream = if (savedPath.startsWith("content://")) {
            // "wa" = write-append. ContentResolver supports this on Android Q+ MediaStore
            // entries and on SAF document URIs for files we created.
            ctx.contentResolver.openOutputStream(Uri.parse(savedPath), "wa")
                ?: throw IOException("Cannot reopen partial file for resume: $savedPath")
        } else {
            val f = File(savedPath)
            if (!f.exists()) throw IOException("Partial file is gone: $savedPath")
            FileOutputStream(f, /* append = */ true)
        }
        return Pair(stream, savedPath)
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

    /**
     * Every unicast address on an up, non-loopback interface, as (interfaceName, address) pairs.
     *
     * PRIMARY source is java.net.NetworkInterface, which keeps the original enumeration order and
     * behaviour whenever it works.iterated PER INTERFACE inside its own try/catch, so a
     * single interface whose isUp()/inetAddresses access throws (seen on some Android Wi-Fi stacks)
     * can no longer abort the whole enumeration and leave us with nothing.
     *
     * FALLBACK is ConnectivityManager/LinkProperties, a public, enumeration-independent source that
     * still reports the active network's link-local / ULA / global addresses when the java.net view
     * comes back empty.
     */
    private fun enumInterfaceAddrs(): List<Pair<String, java.net.InetAddress>> {
        val out = ArrayList<Pair<String, java.net.InetAddress>>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            if (ifaces != null) {
                for (iface in Collections.list(ifaces)) {
                    try {
                        if (!iface.isUp || iface.isLoopback) continue
                        for (a in Collections.list(iface.inetAddresses)) out.add(iface.name to a)
                    } catch (_: Throwable) {
                        // skip this interface, keep the rest
                    }
                }
            }
        } catch (_: Throwable) {
            // fall through to the ConnectivityManager fallback below
        }
        if (out.isNotEmpty()) return out
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val lp = if (cm != null && net != null) cm.getLinkProperties(net) else null
            if (lp != null) {
                val ifName = lp.interfaceName ?: ""
                for (la in lp.linkAddresses) {
                    val a = la.address ?: continue
                    out.add(ifName to a)
                }
            }
        } catch (_: Throwable) {
            // nothing usable from either path
        }
        return out
    }

    // local IPv4 in dotted notation
    fun localIpv4OrNull(): String? =
        enumInterfaceAddrs().firstOrNull { (_, a) ->
            a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress
        }?.second?.hostAddress

    // local IPv4 as an unsigned 32-bit integer (decimal string in CTCP)
    fun localIpv4AsInt(): Long {
        val ip = localIpv4OrNull() ?: return 0L
        return ipv4ToLongBestEffort(ip)
    }

    /**
     * First usable global/ULA IPv6 address in dotted (colon) notation, or null when the
     * device has no routable IPv6. Loopback, link-local (fe80::), wildcard and multicast are
     * skipped. Any scope id ("%wlan0") is stripped
     *
     * Note: when several IPv6 addresses are present we return the first match, which
     * may be an RFC-4941 privacy/temporary address or a stable one depending on enumeration
     * order.
     */
    fun localIpv6OrNull(): String? =
        enumInterfaceAddrs().firstOrNull { (_, a) ->
            a is Inet6Address &&
                !a.isLoopbackAddress && !a.isLinkLocalAddress &&
                !a.isAnyLocalAddress && !a.isMulticastAddress
        }?.second?.hostAddress?.substringBefore('%')

    /**
     * First IPv6 link-local address (fe80::...) in colon notation with any scope id stripped, or
     * null. Used ONLY as a last resort for DCC on an isolated link (Wi-Fi Direct / ad-hoc LAN /
     * a switch with no router handing out ULA or global prefixes), where link-local is the sole
     * usable address. The scope is stripped for the wire because a zone id is meaningful only on
     * the originating host; the connecting side re-attaches its own scope (see resolveConnectTarget).
     */
    fun localIpv6LinkLocalOrNull(): String? =
        enumInterfaceAddrs().firstOrNull { (_, a) ->
            a is Inet6Address && a.isLinkLocalAddress && !a.isMulticastAddress
        }?.second?.hostAddress?.substringBefore('%')

    /**
     * The local address to advertise in a DCC offer's address field.
     *
     * DESIGN: classic DCC encodes IPv4 as a 32-bit integer; the de-facto IPv6 convention
     * puts the literal colon-form address in the same field, detected by the  receiver via the ':'.
     * We PREFER IPv4 (the integer form) whenever a v4 address exists,
     * because far more clients understand it, and only fall back to the IPv6 literal on a
     * v6-only network. Returns "0" when no usable address is found
     */
    fun dccAddressField(): String {
        localIpv4OrNull()?.let { return ipv4ToLongBestEffort(it).toString() }
        localIpv6OrNull()?.let { return it }
        // Isolated IPv6 link (no v4, no ULA/global v6): advertise link-local rather than "0",
        // which a peer would otherwise parse as the 0.0.0.0 wildcard and refuse. See DLC/local DCC.
        // localIpv6LinkLocalOrNull now consults ConnectivityManager/LinkProperties when the java.net
        // enumeration is empty, so an isolated IPv6 LAN (the reported "error about 0.0.0.0" case)
        // surfaces its fe80:: address here instead of falling through to "0".
        localIpv6LinkLocalOrNull()?.let { return it }
        // Last resort: interface enumeration returned nothing usable (observed on some IPv6-only
        // Android Wi-Fi setups, where it left us advertising "0" and the peer then refused the
        // resulting 0.0.0.0). Ask the OS routing table which local address it would use to reach
        // the outside world. This does not enumerate interfaces and sends no packet (a UDP
        // "connect" only pins the route), so it works where both enumeration paths fail.
        routedLocalAddress()?.let { return it }
        return "0"
    }

    /**
     * Local address the OS routing table would use to reach the internet, formatted for the DCC
     * address field (IPv4 as a 32-bit integer, IPv6 as a colon literal), or null if none.
     * Prefers IPv4 (understood by far more clients), then IPv6.
     */
    private fun routedLocalAddress(): String? {
        routedLocalFor("8.8.8.8")?.let { return it }              // IPv4 route probe
        routedLocalFor("2001:4860:4860::8888")?.let { return it } // IPv6 route probe
        return null
    }

    private fun routedLocalFor(target: String): String? {
        return try {
            java.net.DatagramSocket().use { s ->
                // No DNS (target is a literal) and no packet leaves the device; connect() only
                // resolves the route so localAddress reflects the chosen source address.
                s.connect(java.net.InetAddress.getByName(target), 9)
                val a = s.localAddress ?: return null
                if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isMulticastAddress || a.isLinkLocalAddress) return null
                when (a) {
                    is Inet4Address -> ipv4ToLongBestEffort(a.hostAddress).toString()
                    is Inet6Address -> a.hostAddress?.substringBefore('%')
                    else -> null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Standard DCC RECEIVE (we connect to sender's ip:port).
     *
     * @param customFolderUri Optional SAF URI for custom download folder (null = Downloads)
     * @param resumeOffset Byte offset to resume from. 0 = fresh transfer. When non-zero,
     *   [resumeSavedPath] MUST be supplied and point at an existing partial file; the
     *   transfer will open it in append mode rather than creating a new file. The caller
     *   is responsible for sending DCC RESUME and awaiting DCC ACCEPT before invoking
     *   this with a non-zero offset — this method does not speak the CTCP protocol itself.
     * @param resumeSavedPath The path/URI of the partial file to append to. Required iff
     *   [resumeOffset] > 0.
     * @param onSavedPath Invoked with the saved path as soon as the output file is opened
     *   (before any bytes are received). Lets the caller record the path for partial-transfer
     *   tracking even if the transfer subsequently errors out.
     * @return The path/URI where the file was saved.
     */
    suspend fun receive(
        offer: DccOffer,
        customFolderUri: String?,
        resumeOffset: Long = 0L,
        resumeSavedPath: String? = null,
        proxy: ProxyConfig = ProxyConfig(),
        onSavedPath: ((String) -> Unit)? = null,
        onProgress: (Long, Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        // Passive DCC offers carry port=0; calling Socket(ip, 0) is undefined behaviour.
        // The caller should use receivePassive() for those. Catch misrouted calls early.
        require(offer.port > 0) {
            "DCC RECEIVE: port must be > 0 for active DCC (use receivePassive() for passive offers)"
        }
        require(resumeOffset >= 0L) { "DCC RECEIVE: resumeOffset must be >= 0" }
        if (resumeOffset > 0L) {
            require(!resumeSavedPath.isNullOrBlank()) {
                "DCC RECEIVE: resumeOffset > 0 requires resumeSavedPath"
            }
            require(offer.size == 0L || resumeOffset < offer.size) {
                "DCC RECEIVE: resumeOffset ($resumeOffset) is not less than offer size (${offer.size})"
            }
        }

        // Validate the remote IP BEFORE creating the output file. createDccOutputStream
        // creates a real on-disk file (or SAF document); if validateRemoteIp threw after
        // it, the file was leaked as zero bytes. Open the socket here too for the same
        // reason — if the connect fails, no file gets created.
        validateRemoteIp(offer.ip)
        val rawSock = openOutbound(offer.ip, offer.port, proxy)
        val sock = if (offer.secure) wrapTls(rawSock, offer.ip) else rawSock

        val (outputStream, savedPath) = try {
            if (resumeOffset > 0L) openDccOutputStreamForResume(resumeSavedPath!!)
            else createDccOutputStream(offer.filename, customFolderUri)
        } catch (t: Throwable) {
            runCatching { sock.close() }
            throw t
        }
        // Surface the path immediately so the caller can record partial-transfer state
        // before any bytes arrive. Resume needs this: if the transfer fails partway,
        // we still need to know where the (now-larger) partial lives.
        onSavedPath?.invoke(savedPath)
        var receivedAnyBytes = false
        var received = resumeOffset
        val cancelHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true) { runCatching { sock.close() } }
        try {
            sock.use { s ->
                // Wrap in a BufferedOutputStream to reduce IPC round-trips for SAF/MediaStore
                // streams, but keep a reference so we can flush it explicitly before the inner
                // stream is closed. Without this flush, small files (<256 KB) are fully
                // received into the buffer but never written: the outer outputStream.use{}
                // closes the raw stream, silently discarding the buffered bytes.
                val buffered = java.io.BufferedOutputStream(outputStream, 256 * 1024)
                try {
                    received = receiveFromSocket(s, buffered, offer.size, offer.turbo, resumeOffset) { sent, total ->
                        if (sent > resumeOffset) receivedAnyBytes = true
                        onProgress(sent, total)
                    }
                } finally {
                    // surface flush failures rather than swallowing them. A silent
                    // flush failure on the last buffered chunk leaves the file shorter than
                    // `received` claims, and verifyCompleteOrCleanup would then report success
                    // on a truncated file. We promote any flush exception to a real failure.
                    var flushError: Throwable? = null
                    try { buffered.flush() } catch (t: Throwable) { flushError = t }
                    runCatching { outputStream.close() }
                    if (resumeOffset == 0L && !receivedAnyBytes) deleteSavedPathIfEmpty(savedPath)
                    if (flushError != null) throw IOException("DCC: failed flushing final bytes to storage", flushError)
                }
            }
        } finally {
            cancelHandle?.dispose()
        }

        // Integrity gate. Only reached on a clean read-loop exit (a mid-transfer socket
        // error propagates above and is reported as a failure by the caller).
        // On a resumed transfer, partial-cleanup-on-truncation is suppressed: the
        // caller should record the new (larger) partial-transfer state for a future
        // resume attempt rather than deleting bytes we just took the trouble to receive.
        verifyCompleteOrCleanup(savedPath, received, offer.size, deleteOnTruncation = resumeOffset == 0L)

        savedPath
    }

    /**
     * Passive/Reverse DCC RECEIVE.
     *
     * The remote sender offered port 0 + token. We open a listening port, reply with the port,
     * then accept the incoming connection and receive.
     *
     * @param customFolderUri Optional SAF URI for custom download folder (null = Downloads)
     * @param resumeOffset / [resumeSavedPath] / [onSavedPath] — see [receive].
     * @return The path/URI where the file was saved
     */
    suspend fun receivePassive(
        offer: DccOffer,
        portMin: Int,
        portMax: Int,
        customFolderUri: String?,
        resumeOffset: Long = 0L,
        resumeSavedPath: String? = null,
        onSavedPath: ((String) -> Unit)? = null,
        onListening: suspend (addrField: String, port: Int, size: Long, token: Long) -> Unit,
        onProgress: (Long, Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        // Validate passive offer: must have a token AND port must be 0. Some misbehaving clients
        // send port 0 without a token (malformed passive DCC) - catch this early.
        if (offer.port != 0) throw IllegalArgumentException("Passive DCC offer has non-zero port: ${offer.port}")
        val token = offer.token ?: throw IllegalArgumentException("Passive DCC offer is missing token (port=0 but no token)")
        require(resumeOffset >= 0L) { "DCC RECEIVE: resumeOffset must be >= 0" }
        if (resumeOffset > 0L) {
            require(!resumeSavedPath.isNullOrBlank()) {
                "DCC RECEIVE: resumeOffset > 0 requires resumeSavedPath"
            }
            require(offer.size == 0L || resumeOffset < offer.size) {
                "DCC RECEIVE: resumeOffset ($resumeOffset) is not less than offer size (${offer.size})"
            }
        }

        // Bind FIRST so a port-exhausted failure doesn't leave a leaked zero-byte file
        // on disk. Same rationale as the validateRemoteIp-before-createDccOutputStream
        // ordering in receive() above.
        val ss = bindFirstAvailable(portMin, portMax)
        val (outputStream, savedPath) = try {
            if (resumeOffset > 0L) openDccOutputStreamForResume(resumeSavedPath!!)
            else createDccOutputStream(offer.filename, customFolderUri)
        } catch (t: Throwable) {
            runCatching { ss.close() }
            throw t
        }
        onSavedPath?.invoke(savedPath)
        // If anything goes wrong before bytes start arriving, close+delete the file so the
        // user doesn't end up with a zero-byte placeholder cluttering Downloads. For
        // resumed transfers, the file isn't ours to delete on failure — preserve it.
        var receivedAnyBytes = false
        var received = resumeOffset
        // Close the ServerSocket on cancellation so accept() unblocks immediately.
        val ssCancelHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true) { runCatching { ss.close() } }
        try {
            ss.soTimeout = 45_000
            val addrField = dccAddressField()
            onListening(addrField, ss.localPort, offer.size, token)

            val rawSock = try {
                ss.accept()
            } catch (t: Throwable) {
                runCatching { outputStream.close() }
                if (resumeOffset == 0L) deleteSavedPathIfEmpty(savedPath)
                throw if (t is java.net.SocketTimeoutException)
                    RuntimeException("DCC RECEIVE timed out waiting for sender to connect")
                else t
            }
            val sock = try {
                if (offer.secure) wrapTls(rawSock, rawSock.inetAddress?.hostAddress ?: "0.0.0.0") else rawSock
            } catch (t: Throwable) {
                runCatching { rawSock.close() }
                runCatching { outputStream.close() }
                if (resumeOffset == 0L) deleteSavedPathIfEmpty(savedPath)
                throw t
            }

            val sockCancelHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true) { runCatching { sock.close() } }
            try {
                sock.use { s ->
                    val buffered = java.io.BufferedOutputStream(outputStream, 256 * 1024)
                    try {
                        received = receiveFromSocket(s, buffered, offer.size, offer.turbo, resumeOffset) { sent, total ->
                            if (sent > resumeOffset) receivedAnyBytes = true
                            onProgress(sent, total)
                        }
                    } finally {
                        // See receive() for why we don't swallow flush failures.
                        var flushError: Throwable? = null
                        try { buffered.flush() } catch (t: Throwable) { flushError = t }
                        runCatching { outputStream.close() }
                        if (resumeOffset == 0L && !receivedAnyBytes) deleteSavedPathIfEmpty(savedPath)
                        if (flushError != null) throw IOException("DCC: failed flushing final bytes to storage", flushError)
                    }
                }
            } finally {
                sockCancelHandle?.dispose()
            }
        } finally {
            ssCancelHandle?.dispose()
            runCatching { ss.close() }
        }

        // Integrity gate — see receive() for the rationale.
        verifyCompleteOrCleanup(savedPath, received, offer.size, deleteOnTruncation = resumeOffset == 0L)

        savedPath
    }

    /**
     * Delete of a savedPath when the transfer didn't actually receive any
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
     * Unconditional delete of a savedPath, regardless of current size. Used for
     * partial (non-empty) downloads that can't be completed.
     */
    private fun deleteSavedPath(savedPath: String) {
        runCatching {
            if (savedPath.startsWith("content://")) {
                ctx.contentResolver.delete(Uri.parse(savedPath), null, null)
            } else {
                File(savedPath).delete()
            }
        }
    }

    /**
     * Post-transfer completeness check for an incoming DCC file.
     *
     *  - 0 bytes received        -> remove the empty placeholder (mirrors the in-flight
     *                               `!receivedAnyBytes` cleanup; harmless if already gone).
     *  - received < offer size   -> the file is truncated. If [deleteOnTruncation] is true
     *                               (fresh transfer with no resume support attempted) we
     *                               delete it and throw [DccIncompleteException]. If false
     *                               (resumed transfer, OR the caller intends to offer a
     *                               future resume), we preserve the partial bytes and still
     *                               throw [DccIncompleteException] so the caller knows the
     *                               transfer didn't complete.
     *  - offer size unknown (0)  -> nothing to verify against; accept whatever arrived.
     *  - received >= offer size  -> success; leave the file in place.
     */
    private fun verifyCompleteOrCleanup(
        savedPath: String,
        received: Long,
        expected: Long,
        deleteOnTruncation: Boolean = true
    ) {
        if (received == 0L) {
            deleteSavedPathIfEmpty(savedPath)
            return
        }
        if (expected > 0L && received < expected) {
            if (deleteOnTruncation) deleteSavedPath(savedPath)
            throw DccIncompleteException(received = received, expected = expected)
        }
    }

    /**
     * Standard (active) DCC SEND: we listen on a port in portMin..portMax and send when peer connects.
     *
     * @param awaitStartOffset Called after the peer connects but before we begin reading the
     *   file. The returned offset is the byte position to seek to before sending. Use this to
     *   wait for a DCC RESUME / ACCEPT exchange, the VM emits the CTCP and completes a
     *   deferred when the peer's RESUME arrives. If you have no resume support, return 0L
     *   immediately (the default).
     */
    suspend fun sendFile(
        file: File,
        portMin: Int,
        portMax: Int,
        secure: Boolean = false,
        onClient: suspend (addrField: String, port: Int, size: Long) -> Unit,
        awaitStartOffset: suspend () -> Long = { 0L },
        onProgress: (Long, Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val size = file.length()
        val ss = bindFirstAvailable(portMin, portMax)
        val cancelHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true) { runCatching { ss.close() } }
        try {
            val addrField = dccAddressField()
            onClient(addrField, ss.localPort, size)

            ss.soTimeout = 45_000
            val rawSock = try {
                ss.accept()
            } catch (_: java.net.SocketTimeoutException) {
                throw RuntimeException("DCC SEND timed out waiting for peer to connect")
            }
            val sock = if (secure) wrapTls(rawSock, rawSock.inetAddress.hostAddress ?: "") else rawSock

            sock.use { s ->
                val sockHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true) { runCatching { s.close() } }
                try {
                    // Resolve the start offset AFTER the peer has connected. If the VM is
                    // waiting on a DCC RESUME exchange this is the natural point to settle
                    val startOffset = awaitStartOffset().also {
                        require(it in 0L..size) { "DCC SEND: awaitStartOffset returned $it; out of range for $size-byte file" }
                    }
                    sendOverSocket(s, file, size, startOffset, onProgress)
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
     *
     * See [sendFile] for the [startOffset] semantics.
     */
    suspend fun sendFileConnect(
        file: File,
        host: String,
        port: Int,
        secure: Boolean = false,
        startOffset: Long = 0L,
        proxy: ProxyConfig = ProxyConfig(),
        onProgress: (Long, Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val size = file.length()
        require(startOffset in 0L..size) { "DCC SEND: startOffset ($startOffset) out of range for $size-byte file" }
        validateRemoteIp(host)
        val rawSock = openOutbound(host, port, proxy)
        val sock = if (secure) wrapTls(rawSock, host) else rawSock
        val cancelHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true) { runCatching { sock.close() } }
        try {
            sock.use { s ->
                sendOverSocket(s, file, size, startOffset, onProgress)
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
        onClient: suspend (addrField: String, port: Int) -> Unit
    ): Socket = withContext(Dispatchers.IO) {
        val ss = bindFirstAvailable(portMin, portMax)
        try {
            val addrField = dccAddressField()
            onClient(addrField, ss.localPort)
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
    suspend fun connectChat(offer: DccChatOffer, proxy: ProxyConfig = ProxyConfig()): Socket = withContext(Dispatchers.IO) {
        validateRemoteIp(offer.ip)
        val rawSock = openOutbound(offer.ip, offer.port, proxy)
        val sock = if (offer.secure) wrapTls(rawSock, offer.ip) else rawSock
        sock.tcpNoDelay = true
        sock.keepAlive = true
        sock
    }

    /**
     * Receive bytes from [sock] into [outputStream], ACKing progress per the DCC convention.
     *
     * Throughput design:
     *  - The socket reader and the storage writer run on separate threads, communicating via
     *    a bounded queue. Without this, a slow MediaStore/SAF write stalls the socket read,
     *    the TCP receive window collapses, and the sender throttles. With it, network reads
     *    overlap with storage writes and the receive window stays open.
     *  - The queue is bounded (16 × 256 KB ≈ 4 MB) so a genuinely slow storage layer still
     *    backpressures the reader rather than ballooning RAM.
     *  - 256 KB read buffer + 1 MB SO_RCVBUF reduce per-iteration overhead and let the
     *    kernel buffer a meaningful BDP on higher-RTT links.
     *  - DCC ACKs are sent from the reader as soon as bytes are *received* (not after they
     *    hit disk). The wire-level contract is "we have these bytes" and we do — they're
     *    in our process. This unblocks lockstep senders without waiting for storage I/O.
     *  - [onProgress] is throttled to ~10× per second so we don't fire a StateFlow copy on
     *    every 256 KB chunk. A final progress callback always fires after the loop so the
     *    UI doesn't get stuck at, say, 99.7 %.
     *
     * @return the total number of bytes actually written. The caller compares this against
     *   the advertised offer size to decide success vs. truncation (see [verifyCompleteOrCleanup]).
     */
    private fun receiveFromSocket(
        sock: Socket,
        outputStream: OutputStream,
        expectedSize: Long,
        turbo: Boolean,
        startOffset: Long = 0L,
        onProgress: (Long, Long) -> Unit
    ): Long {
        sock.tcpNoDelay = true
        // Best-effort; some platforms cap or ignore this. Not fatal if it fails.
        runCatching { sock.receiveBufferSize = 1 * 1024 * 1024 }

        // ACK width selection. The peer's sender computes the same predicate from the
        // same advertised size, so as long as we agree on size we agree on width.
        // Special case: expectedSize == 0 means "unknown total". The receiver still
        // accepts up to maxAccept (8 GiB), which exceeds the 32-bit ACK range, so a
        // pure size-based predicate would silently wrap. Use 64-bit ACKs in that case;
        // mIRC-derived senders generally tolerate either width on size=0 transfers.
        val ack64 = expectedSize > 0xFFFFFFFFL || expectedSize == 0L
        val ackBuf = ByteArray(if (ack64) 8 else 4)
        val expected: Long? = expectedSize.takeIf { it > 0L }

        // Hard ceiling for transfers without an advertised size: 8 GB. Without this,
        // a malicious sender that omits the size field could keep writing forever and
        // fill the device's storage. With a known size we still cap at the advertised
        // size so a sender that lies (offers 1 KB then sends 10 GB) can't bypass.
        val maxAccept: Long = expected ?: (8L * 1024 * 1024 * 1024)

        // Producer/consumer plumbing. Locals-shared-across-threads aren't @Volatile-able
        // in Kotlin, so use the java.util.concurrent.atomic primitives.
        val queue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(16)
        val writerError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val readerDone  = java.util.concurrent.atomic.AtomicBoolean(false)

        val writerThread = thread(start = true, isDaemon = true, name = "dcc-writer") {
            try {
                // Drain until the reader signals done AND the queue is empty. Poll with a
                // short timeout so we notice `readerDone` even if the queue is empty.
                while (!readerDone.get() || queue.isNotEmpty()) {
                    val chunk = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                        ?: continue
                    outputStream.write(chunk)
                }
            } catch (t: Throwable) {
                writerError.set(t)
            }
        }

        // `total` is the absolute byte count including any resumed prefix, matching the
        // DCC ACK convention (ACK is total-bytes-of-the-FILE, not bytes-of-this-session).
        var total = startOffset
        var lastProgressMs = 0L
        // Cache once; SocketOutputStream is the same instance each call, but avoid the
        // unchecked-cast / lookup in the hot path.
        val ackOut = sock.getOutputStream()

        try {
            sock.getInputStream().use { inp ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    // Surface a writer-thread crash as soon as we notice it; don't keep
                    // reading more bytes we can't drain.
                    writerError.get()?.let { throw it }

                    val remaining = maxAccept - total
                    if (remaining <= 0L) break
                    val toRead = if (remaining < buf.size) remaining.toInt() else buf.size
                    val n = inp.read(buf, 0, toRead)
                    if (n <= 0) break

                    // Hand the chunk to the writer. Copy because `buf` is reused next iter.
                    val chunk = buf.copyOf(n)
                    // Bounded offer with timeout — backpressures the reader if storage is
                    // slow, but lets us re-check writerError instead of blocking forever
                    // if the writer thread has died.
                    while (!queue.offer(chunk, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        writerError.get()?.let { throw it }
                    }
                    total += n

                    if (!turbo) {
                        // ACK the running total of bytes RECEIVED (not bytes written to
                        // disk). The DCC ACK semantic is wire-level acknowledgment.
                        if (ack64) {
                            ackBuf[0] = (total ushr 56).toByte()
                            ackBuf[1] = (total ushr 48).toByte()
                            ackBuf[2] = (total ushr 40).toByte()
                            ackBuf[3] = (total ushr 32).toByte()
                            ackBuf[4] = (total ushr 24).toByte()
                            ackBuf[5] = (total ushr 16).toByte()
                            ackBuf[6] = (total ushr  8).toByte()
                            ackBuf[7] =  total.toByte()
                        } else {
                            val ackInt = total.coerceAtMost(0xFFFFFFFFL)
                            ackBuf[0] = (ackInt ushr 24).toByte()
                            ackBuf[1] = (ackInt ushr 16).toByte()
                            ackBuf[2] = (ackInt ushr  8).toByte()
                            ackBuf[3] =  ackInt.toByte()
                        }
                        runCatching { ackOut.write(ackBuf) }
                    }

                    // Time-gated progress so the per-chunk StateFlow copy isn't in the
                    // hot path. ~10 updates/sec is plenty for a smooth progress bar.
                    val now = System.currentTimeMillis()
                    if (now - lastProgressMs >= 100L) {
                        lastProgressMs = now
                        onProgress(total, expectedSize)
                    }

                    if (expected != null && total >= expected) break
                }
            }
        } finally {
            // Signal the writer to drain remaining chunks and exit; wait briefly. If it's
            // stuck (e.g. the storage stream blocked indefinitely), interrupt — better to
            // leak a thread once than to deadlock the receive coroutine.
            readerDone.set(true)
            writerThread.join(10_000)
            if (writerThread.isAlive) {
                writerThread.interrupt()
                writerThread.join(2_000)
            }
            // If the writer errored late (while draining), surface that as the failure
            // rather than reporting a clean total.
            writerError.get()?.let { throw it }
        }

        // Final progress beat so the UI lands on the actual total rather than the last
        // throttled sample.
        onProgress(total, expectedSize)
        return total
    }

    private fun sendOverSocket(
        sock: Socket,
        file: File,
        size: Long,
        startOffset: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        sock.tcpNoDelay = true
        // Used by the ACK reader thread.
        sock.soTimeout = 1_000

        // `acked` is the running highest ACKed byte position the peer has confirmed.
        // It starts at the resumed offset because the peer already has those bytes
        // (and would ACK from `startOffset + n` if its ACK is "total bytes of file").
        val acked = AtomicLong(startOffset)

        // ACK width must match what the receiver sends (see receiveFromSocket):
        // 8-byte (64-bit) ACKs for files larger than a 32-bit value OR for size==0
        // (unknown size, where the cap is 8 GiB and 32-bit ACKs would wrap). Reading
        // the wrong width desyncs the ACK stream and breaks completion detection.
        val ack64 = size > 0xFFFFFFFFL || size == 0L
        val ackWidth = if (ack64) 8 else 4

        fun u32be(b: ByteArray): Long =
            (ByteBuffer.wrap(b, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL)

        fun u32le(b: ByteArray): Long =
            (ByteBuffer.wrap(b, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL)

        fun u64be(b: ByteArray): Long =
            ByteBuffer.wrap(b, 0, 8).order(ByteOrder.BIG_ENDIAN).long

        fun u64le(b: ByteArray): Long =
            ByteBuffer.wrap(b, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long

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
            val b = ByteArray(ackWidth)
            var off = 0
            var last = startOffset
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val n = inp.read(b, off, ackWidth - off)
                    if (n < 0) break
                    off += n
                    if (off == ackWidth) {
                        val be = if (ack64) u64be(b) else u32be(b)
                        val le = if (ack64) u64le(b) else u32le(b)
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

        // `sent` is the absolute byte offset of the next byte to send, including the
        // resumed prefix. Mirrors how the receiver's `total` is reported.
        var sent = startOffset
        try {
            val outRaw = sock.getOutputStream()
            val out = BufferedOutputStream(outRaw, 64 * 1024)
            val buf = ByteArray(32 * 1024)

            file.inputStream().use { fin ->
                // Seek past the bytes the receiver already has. FileInputStream.skip is
                // best-effort: it may return short, so loop until we've actually skipped
                // startOffset bytes OR the file is shorter than that (unusual; require() above
                // already gated against this, but defend in depth).
                if (startOffset > 0L) {
                    var remaining = startOffset
                    while (remaining > 0L) {
                        val skipped = fin.skip(remaining)
                        if (skipped <= 0L) break
                        remaining -= skipped
                    }
                    if (remaining > 0L) {
                        throw IOException("DCC SEND: could not seek to startOffset $startOffset (file shorter than expected)")
                    }
                }
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
