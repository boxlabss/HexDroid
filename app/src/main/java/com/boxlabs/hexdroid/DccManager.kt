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
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

// ---------------------------------------------------------------------------
// DCC receive pump (F1 read/write overlap + F3 SO_RCVBUF + F4 buffer sizing)
//
// These are top-level, file-internal declarations so the core pump is JVM-unit-testable
// with ByteArrayInputStream / ByteArrayOutputStream and a capturing AckSink — no Android
// classes, no real Socket, no Context, no Robolectric (see DccPumpReceiveTest).
// ---------------------------------------------------------------------------

/** F4: socket read buffer. 64 KB halves syscalls/iterations vs the old 32 KB. */
internal const val DCC_READ_BUF_SIZE = 64 * 1024

/**
 * F1: bounded reader->writer hand-off depth. Peak in-flight memory is roughly
 * (DCC_QUEUE_CAPACITY + 2) * DCC_READ_BUF_SIZE — the queue, plus the one chunk in the reader's
 * hand while it blocks on a full queue and the one the writer is actively writing — plus the
 * caller's 256 KB BufferedOutputStream: ~1.1 MB total. Fixed and independent of file size, so
 * 4 GB+ transfers never OOM. Deep enough to ride out a disk write burst / fsync hiccup.
 */
internal const val DCC_QUEUE_CAPACITY = 12

/**
 * Back-pressure / liveness poll granularity: how long the reader waits on a full queue
 * before re-checking whether the writer died (so a dead writer can't wedge it), and how
 * long the writer waits on an empty queue before re-checking whether to stop.
 */
internal const val DCC_QUEUE_POLL_MS = 250L

/**
 * F3: requested SO_RCVBUF for incoming DCC. Clamped by net.core.rmem_max; best-effort.
 * Must be set BEFORE connect/accept so TCP window scaling negotiates against it.
 */
internal const val DCC_SO_RCVBUF = 4 * 1024 * 1024   // 4 MB

/** Throttle floor for onProgress emissions from the receive pump (first + final always emit). */
internal const val DCC_PROGRESS_MIN_INTERVAL_MS = 100L

/** Teardown stall-watchdog tick: how often the join loop re-checks the writer's durable progress. */
internal const val DCC_WRITER_JOIN_TICK_MS = 100L

/**
 * Clean-completion stall ceiling: if the disk writer commits NO bytes for this long, treat the write
 * as wedged (a non-interruptible SAF/cloud/SD-card stall) and fail, rather than hang the transfer
 * "in progress" forever. Progress is measured at whole-chunk granularity (writtenTotal only advances
 * after each write() returns), so this CANNOT distinguish a wedged write from a single write that is
 * merely very slow. It is therefore set deliberately large: a single buffered write that blocks this
 * long (2 min of zero progress) and then succeeds is vanishingly unlikely on real storage, while a
 * genuinely wedged write stays bounded. (The only alternative — an unbounded join — hangs forever.)
 * Note this is per-write: a slow-but-progressing writer resets the timer on every committed chunk,
 * so a long total drain never trips it; only a single write exceeding this window does.
 */
internal const val DCC_WRITER_STALL_TIMEOUT_MS = 120_000L

/** Abnormal-teardown (socket error / user cancel) stall ceiling — short, since the partial file is discarded. */
internal const val DCC_WRITER_ABORT_TIMEOUT_MS = 3_000L

/**
 * Sink for cumulative DCC ACKs. A `fun interface` so [pumpReceive] is unit-testable on the JVM
 * without a real [Socket]: production passes a lambda that writes the big-endian count to the
 * upstream socket; tests pass a capturing lambda. Turbo mode passes `null` (no ACKs).
 */
internal fun interface AckSink {
    /** Emit a cumulative ACK for [totalBytesReceived] (absolute running total, not a delta). */
    fun ack(totalBytesReceived: Long)
}

/**
 * Build the DCC ACK payload for [total] bytes received.
 *
 * Width is governed by the advertised [expectedSize] (matching the sender's ACK reader):
 *  - 8-byte (64-bit) big-endian when the transfer exceeds a 32-bit value (> 4 GiB),
 *  - 4-byte (32-bit) big-endian otherwise, with [total] clamped to 0xFFFFFFFF so a lying
 *    sender cannot overflow the field.
 */
internal fun buildAck(total: Long, expectedSize: Long): ByteArray {
    val ack64 = expectedSize > 0xFFFFFFFFL
    return if (ack64) {
        byteArrayOf(
            (total ushr 56).toByte(), (total ushr 48).toByte(),
            (total ushr 40).toByte(), (total ushr 32).toByte(),
            (total ushr 24).toByte(), (total ushr 16).toByte(),
            (total ushr  8).toByte(),  total.toByte()
        )
    } else {
        val a = total.coerceAtMost(0xFFFFFFFFL)
        byteArrayOf(
            (a ushr 24).toByte(), (a ushr 16).toByte(),
            (a ushr  8).toByte(),  a.toByte()
        )
    }
}

/**
 * A unit of data handed from the socket-reader to the disk-writer: [buf] holds [len] valid
 * bytes (len < 0 is the POISON stop sentinel). File-private so [pumpReceive] stays clean.
 */
private class DccChunk(val buf: ByteArray, val len: Int)

/**
 * Core receive pump (F1). Pure with respect to Android/Socket: it operates on a plain
 * [InputStream] (the wire), a plain [OutputStream] (the disk), and an [AckSink], so it is
 * JVM-unit-testable with ByteArrayInputStream / ByteArrayOutputStream and a capturing AckSink
 * (JUnit4 only — no Android, no Socket, no coroutines).
 *
 * Threading model: the CALLING thread is the reader — it drains [data], emits ACKs, and
 * throttles [onProgress]. A single daemon "dcc-disk-writer" thread is the writer — it drains a
 * bounded queue to [disk]. Reading and writing therefore overlap, so disk latency no longer
 * stalls draining the wire. The reader stays on the calling thread so the existing
 * `invokeOnCompletion { sock.close() }` cancel contract keeps working unchanged: a close makes
 * [data].read() throw, the reader exits, and the writer is joined before this returns.
 *
 * Contract (the report's mandatory caveats):
 *  - BOUNDED + BACK-PRESSURE: [queueCapacity] slots of [bufSize] bytes cap in-flight memory
 *    regardless of file size. When the disk lags, the reader blocks enqueuing, stops draining
 *    the socket, and TCP flow control throttles the sender.
 *  - JOIN BEFORE RETURN: the writer is joined (via a progress watchdog) before this returns/throws,
 *    so the caller's flush/close/delete and integrity gate run after the writer stops touching
 *    [disk]. The writer is a daemon; on a normal/slow finish it is fully joined. If it WEDGES on a
 *    non-interruptible disk/SAF write (no durable progress for the stall window) the call still
 *    completes — it records a stall failure and stops waiting; the daemon may briefly outlive the
 *    call until the syscall returns, but can neither block process exit nor grow memory (queue is
 *    cleared on its exit).
 *  - EXCEPTION PROPAGATION: a writer error (disk full, IOException) or a stall is captured and
 *    rethrown after join, so a truncated write surfaces as a FAILURE, never a silent clean short read.
 *  - NO DEADLOCK / NO HANG: the reader uses offer(timeout) (never an unbounded put) and re-checks
 *    writer liveness each tick; the writer uses poll(timeout); the teardown bounds the join with a
 *    progress watchdog. Either side always makes progress or gives up.
 *  - NO ALIASING: each read allocates a fresh exact-sized buffer the writer owns; the reader
 *    never mutates a queued buffer.
 *  - ACK SEMANTICS: ACK on bytes-read-off-socket (cumulative), on the reader; a TERMINAL
 *    cumulative ACK == total is emitted unconditionally at EOF (non-turbo) so the sender's
 *    final-ACK wait is satisfied. Turbo → ackSink == null → no ACKs.
 *  - CAP + SHORT READS: preserves maxAccept (advertised size, else 8 GB), shrinks the final read
 *    window to land exactly on the cap, treats read() < buffer as normal.
 *
 * @param ackSink null == turbo (no ACKs).
 * @return total bytes DURABLY written to [disk] by the writer (the integrity-gate input). On a
 *   clean run this equals bytes read, because the writer is joined before return; if the writer
 *   failed, this function throws instead of returning.
 */
internal fun pumpReceive(
    data: InputStream,
    disk: OutputStream,
    expectedSize: Long,
    ackSink: AckSink?,
    onProgress: (Long, Long) -> Unit,
    bufSize: Int = DCC_READ_BUF_SIZE,
    queueCapacity: Int = DCC_QUEUE_CAPACITY,
    pollMs: Long = DCC_QUEUE_POLL_MS,
    progressIntervalMs: Long = DCC_PROGRESS_MIN_INTERVAL_MS,
    joinTickMs: Long = DCC_WRITER_JOIN_TICK_MS,
    cleanStallMs: Long = DCC_WRITER_STALL_TIMEOUT_MS,
    abortStallMs: Long = DCC_WRITER_ABORT_TIMEOUT_MS,
): Long {
    // EOF/stop is signalled by the POISON sentinel (len < 0), so a legitimate empty read can
    // never be confused with end-of-stream.
    val poison = DccChunk(ByteArray(0), -1)

    val queue = ArrayBlockingQueue<DccChunk>(queueCapacity)
    // First writer-thread failure (disk full, IOException), surfaced to the reader.
    val writerError = AtomicReference<Throwable?>(null)
    // Set when the writer has stopped consuming, so the reader's bounded offer() can't spin
    // forever waiting for space that will never free.
    val writerDone = AtomicBoolean(false)
    // Total bytes the writer has DURABLY committed to disk. An AtomicLong (not a plain var) because
    // the teardown stall watchdog reads it from the reader thread WHILE the writer is still running,
    // to distinguish "slow but progressing" from "wedged"; the final read after join is safe too.
    val writtenTotal = AtomicLong(0L)

    val writer = thread(start = true, isDaemon = true, name = "dcc-disk-writer") {
        try {
            while (true) {
                // Bounded wait so a dead reader can never strand this thread.
                val c = queue.poll(pollMs, TimeUnit.MILLISECONDS) ?: continue
                if (c.len < 0) break                 // poison: clean stop
                disk.write(c.buf, 0, c.len)
                writtenTotal.addAndGet(c.len.toLong())
            }
        } catch (_: InterruptedException) {
            // Interrupted by teardown (user cancel or the stall watchdog) — a requested stop, not a
            // disk failure. Leave writerError as the teardown set it (the stall error, or null on cancel).
        } catch (t: Throwable) {
            // A real write error (e.g. disk full).
            writerError.compareAndSet(null, t)
        } finally {
            // Mark stopped and clear so a reader blocked in offer() gets free slots and can
            // observe writerDone / writerError and bail instead of hanging.
            writerDone.set(true)
            queue.clear()
        }
    }

    var readTotal = 0L
    var lastProgressMs = 0L
    var emittedFirst = false

    fun emitProgress(force: Boolean) {
        val now = System.currentTimeMillis()
        if (force || !emittedFirst || now - lastProgressMs >= progressIntervalMs) {
            emittedFirst = true
            lastProgressMs = now
            onProgress(readTotal, expectedSize)
        }
    }

    var readerFailed = false
    try {
        val expected: Long? = expectedSize.takeIf { it > 0L }
        // Hard ceiling for transfers without an advertised size: 8 GB (unchanged). With a known
        // size we stop exactly at the advertised size so a lying sender can't bypass the cap.
        val maxAccept: Long = expected ?: (8L * 1024 * 1024 * 1024)

        while (true) {
            // If the writer already stopped (e.g. disk full) stop reading — don't pull bytes we
            // can't persist, and don't block on a queue that will never drain. The real error is
            // rethrown exactly once, after the writer is joined (below), so wrapping stays uniform.
            if (writerDone.get()) break

            val remaining = maxAccept - readTotal
            if (remaining <= 0L) break                          // maxAccept cap
            val toRead = if (remaining < bufSize) remaining.toInt() else bufSize

            val buf = ByteArray(toRead)                         // fresh buffer (no aliasing)
            val n = data.read(buf, 0, toRead)                   // may return < toRead
            if (n <= 0) break                                   // EOF / peer close

            readTotal += n

            // Hand off with back-pressure: a timed offer (never an unbounded put) that bails if the
            // writer dies and — crucially — gives up if the writer makes NO durable progress for the
            // stall window. Without that, a wedged non-interruptible disk/SAF write would spin the
            // reader here forever once the queue fills (writerDone stays false because the writer is
            // stuck, not dead). A merely-slow writer keeps committing bytes, which resets the stall
            // timer, so it is never false-failed; back-pressure still throttles the sender via TCP.
            val item = DccChunk(buf, n)
            var handed = false
            var bpIdleMs = 0L
            var bpLastWritten = writtenTotal.get()
            while (!handed && !writerDone.get()) {
                handed = queue.offer(item, pollMs, TimeUnit.MILLISECONDS)
                if (handed) break
                val now = writtenTotal.get()
                if (now != bpLastWritten) {
                    bpLastWritten = now
                    bpIdleMs = 0L
                } else {
                    bpIdleMs += pollMs
                    if (bpIdleMs >= cleanStallMs) {
                        writerError.compareAndSet(
                            null,
                            IOException("DCC disk write stalled: no progress for $cleanStallMs ms")
                        )
                        break
                    }
                }
            }
            if (!handed) break    // writer dead or stalled; finally + post-finally rethrow handle it

            // ACK on bytes-read-off-socket, cumulative, on the reader. Full cadence.
            ackSink?.ack(readTotal)
            emitProgress(force = false)

            if (expected != null && readTotal >= expected) break   // stop at size
        }
    } catch (t: Throwable) {
        readerFailed = true
        throw t
    } finally {
        // Stop the writer and join it before returning — but NEVER hang forever on a wedged,
        // non-interruptible disk/SAF write (a cloud DocumentsProvider doing network I/O, a removed
        // SD card, a wedged filesystem — none honour Thread.interrupt()). Strategy:
        //  - Hand off the POISON stop sentinel. On a clean finish keep re-offering it until the
        //    writer (FIFO) has drained every queued chunk and accepts it, so a fast-reader/slow-
        //    writer transfer still writes every byte and is NEVER corrupted/interrupted mid-write.
        //    On an abnormal finish (socket error / cancel) the partial file is discarded, so also
        //    interrupt to stop ASAP.
        //  - Join with a progress watchdog: while the writer keeps committing bytes we wait (a
        //    legitimately slow target must never be false-failed); if it makes NO durable progress
        //    for the stall window, record a stall error, interrupt, and stop waiting. The daemon may
        //    briefly outlive the call until the syscall returns, but the queue is cleared on its exit
        //    so it cannot block process exit or grow memory — and the caller always completes.
        // If the reader bailed (socket error / cancel) OR a stall was already recorded (e.g. the
        // back-pressure loop above gave up on a wedged writer), stop promptly with the short ceiling
        // and interrupt now; otherwise wait patiently for a slow-but-progressing writer to finish.
        val alreadyFailed = writerError.get() != null
        val stallMs = if (readerFailed || alreadyFailed) abortStallMs else cleanStallMs
        var poisonQueued = queue.offer(poison)
        if (readerFailed || alreadyFailed) writer.interrupt()
        var idleMs = 0L
        var lastWritten = writtenTotal.get()
        while (writer.isAlive) {
            writer.join(joinTickMs)
            if (!writer.isAlive) break
            if (!poisonQueued) poisonQueued = queue.offer(poison)   // keep trying until it lands
            val now = writtenTotal.get()
            if (now != lastWritten) {
                lastWritten = now
                idleMs = 0L
            } else {
                idleMs += joinTickMs
                if (idleMs >= stallMs) {
                    writerError.compareAndSet(
                        null,
                        IOException("DCC disk write stalled: no progress for $stallMs ms")
                    )
                    writer.interrupt()
                    break
                }
            }
        }
    }

    // Propagate a writer failure (disk full, etc.) as a FAILURE so the integrity gate never sees
    // a "clean" short read. Wrap non-IOExceptions so the caller's IOException handling applies
    // uniformly. Thrown AFTER join so cleanup ordering holds.
    writerError.get()?.let { e ->
        throw if (e is IOException) e else IOException("DCC disk write failed: ${e.message}", e)
    }

    // Terminal cumulative ACK == bytes received, emitted unconditionally at EOF (non-turbo), even
    // if the final per-chunk ACK held the same value, so the sender's 10s final-ACK wait is always
    // satisfied and a successful transfer is never mis-reported as failed.
    if (readTotal > 0L) ackSink?.ack(readTotal)

    // Final progress report (the throttle may have skipped the last tick).
    emitProgress(force = true)

    // Return DURABLE bytes (what the writer committed), which is what the integrity gate compares
    // to the offer size. On a clean run this equals bytes read; if the writer failed or stalled, the
    // rethrow above fired instead of reaching here. AtomicLong makes this read safe regardless.
    return writtenTotal.get()
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
                        // F3: set SO_RCVBUF BEFORE connect so TCP window scaling negotiates the larger
                        // window. The one-arg Socket(ip,port) ctor connects immediately, which would set
                        // the buffer too late; use an unconnected socket + explicit connect. Clamped by
                        // net.core.rmem_max; best-effort (never abort a transfer over a buffer hint).
                        val rawSock = Socket().apply {
                            runCatching { receiveBufferSize = DCC_SO_RCVBUF }
                            try {
                                connect(InetSocketAddress(offer.ip, offer.port))
                            } catch (t: Throwable) {
                                // The one-arg Socket(ip,port) ctor closed its fd on a failed connect;
                                // preserve that (an unconnected Socket() leaks its fd otherwise).
                                runCatching { close() }
                                throw t
                            }
                        }
                        val sock = if (offer.secure) wrapTls(rawSock, offer.ip) else rawSock

                        val (outputStream, savedPath) = try {
                            createDccOutputStream(offer.filename, customFolderUri)
                        } catch (t: Throwable) {
                            runCatching { sock.close() }
                            throw t
                        }
                        var receivedAnyBytes = false
                        var received = 0L
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
                                    received = receiveFromSocket(s, buffered, offer.size, offer.turbo) { sent, total ->
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

                        // Integrity gate. Only reached on a clean read-loop exit (a mid-transfer socket
                        // error propagates above and is reported as a failure by the caller).
                        verifyCompleteOrCleanup(savedPath, received, offer.size)

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
                            // ordering in receive() above. F3: bindFirstAvailable sets SO_RCVBUF on the
                            // listen socket BEFORE bind (setting it post-bind would not affect the TCP
                            // window of accepted connections); accepted sockets then inherit the window.
                            val ss = bindFirstAvailable(portMin, portMax, DCC_SO_RCVBUF)
                            val (outputStream, savedPath) = try {
                                createDccOutputStream(offer.filename, customFolderUri)
                            } catch (t: Throwable) {
                                runCatching { ss.close() }
                                throw t
                            }
                            // If anything goes wrong before bytes start arriving, close+delete the file so the
                            // user doesn't end up with a zero-byte placeholder cluttering Downloads.
                            var receivedAnyBytes = false
                            var received = 0L
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
                                            received = receiveFromSocket(s, buffered, offer.size, offer.turbo) { sent, total ->
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

                            // Integrity gate.
                            verifyCompleteOrCleanup(savedPath, received, offer.size)

                            savedPath
                    }

                    /**
                     * Delete of a savedPath when the transfer didn't actually receive any
                     * bytes.
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
                     *  - received < offer size   -> the file is truncated and (with no RESUME support)
                     *                               unrecoverable; delete it and throw [DccIncompleteException]
                     *                               so the transfer is reported as failed, not complete.
                     *  - offer size unknown (0)  -> nothing to verify against; accept whatever arrived.
                     *  - received >= offer size  -> success; leave the file in place.
                     */
                    private fun verifyCompleteOrCleanup(savedPath: String, received: Long, expected: Long) {
                        if (received == 0L) {
                            deleteSavedPathIfEmpty(savedPath)
                            return
                        }
                        if (expected > 0L && received < expected) {
                            deleteSavedPath(savedPath)
                            throw DccIncompleteException(received = received, expected = expected)
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

                    /**
                     * Receive bytes from [sock] into [outputStream], ACKing progress per the DCC convention.
                     *
                     * Thin adapter over [pumpReceive] (F1): it wires the socket's input/output streams and
                     * the DCC ACK encoding around the pure pump. All overlap, back-pressure, cancellation,
                     * and exception-propagation logic lives in [pumpReceive].
                     *
                     * @return the total number of bytes DURABLY written. The caller compares this against
                     *   the advertised offer size to decide success vs. truncation (see [verifyCompleteOrCleanup]).
                     */
                    private fun receiveFromSocket(
                        sock: Socket,
                        outputStream: OutputStream,
                        expectedSize: Long,
                        turbo: Boolean,
                        onProgress: (Long, Long) -> Unit
                    ): Long {
                        sock.tcpNoDelay = true
                        // Cache the upstream output stream once; the ACK sink writes the cumulative total to
                        // it. runCatching mirrors the original inline-ACK behaviour: a failed ACK write must
                        // never abort a successful inbound transfer (the 4/8-byte write returns immediately
                        // into a near-empty upstream socket, so it does not stall draining the wire).
                        val ackOut: OutputStream? =
                            if (turbo) null else runCatching { sock.getOutputStream() }.getOrNull()
                        val ackSink: AckSink? =
                            if (turbo) null else AckSink { total ->
                                ackOut?.let { runCatching { it.write(buildAck(total, expectedSize)) } }
                            }

                        return sock.getInputStream().use { inp ->
                            pumpReceive(
                                data = inp,
                                disk = outputStream,
                                expectedSize = expectedSize,
                                ackSink = ackSink,
                                onProgress = onProgress,
                            )
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

                        // ACK width must match what the receiver sends (see receiveFromSocket):
                        // 8-byte (64-bit) ACKs for files larger than a 32-bit value, else 4-byte.
                        // Reading the wrong width desyncs the ACK stream and breaks completion
                        // detection for transfers above 4 GiB.
                        val ack64 = size > 0xFFFFFFFFL
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
                            var last = 0L
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

                    private fun bindFirstAvailable(
                        min: Int,
                        max: Int,
                        receiveBufferSize: Int? = null
                    ): ServerSocket {
                        val a = min.coerceIn(1, 65535)
                        val b = max.coerceIn(1, 65535)
                        for (p in a..b) {
                            // Create the socket UNBOUND so SO_RCVBUF can be applied BEFORE bind (F3): the
                            // TCP receive window (>64 KB) only takes effect on accepted connections when
                            // the buffer is set pre-bind — window scaling is negotiated at bind/accept
                            // time. Setting it on an already-bound ServerSocket is silently ineffective.
                            // Best-effort: an OS-rejected size must not abort binding.
                            val ss = ServerSocket()
                            if (receiveBufferSize != null) {
                                runCatching { ss.receiveBufferSize = receiveBufferSize }
                            }
                            try {
                                ss.bind(InetSocketAddress(p))
                                return ss
                            } catch (_: Throwable) {
                                runCatching { ss.close() }   // free it before trying the next port
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
