/*
 * HexDroidIRC - An IRC Client for Android
 * Copyright (C) 2026 boxlabs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.boxlabs.hexdroid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * JVM unit tests for the DCC receive pump (F1 producer/consumer overlap). These run on the host
 * with JUnit4 only — no Android, no real [java.net.Socket], no Context — because [pumpReceive] was
 * extracted to operate on a plain InputStream (wire), OutputStream (disk), and an [AckSink].
 *
 * Each test pins one of the report's mandatory caveats so a regression that re-introduces the bug
 * fails loudly. The slow/cancel/back-pressure tests carry a `timeout` so a deadlock-class regression
 * fails fast instead of hanging the suite.
 *
 * Note: the "wedged writer ignores interrupt" stall-watchdog path is exercised in the standalone
 * harness (see the implementation work), not here — it deliberately leaves a stuck daemon thread,
 * which would break [noWriterThreadAlive] in the shared JUnit JVM.
 */
class DccPumpReceiveTest {

    private fun bytes(n: Int): ByteArray = ByteArray(n) { (it % 251).toByte() }

    private fun noProgress(): (Long, Long) -> Unit = { _, _ -> }

    /** InputStream that returns at most [maxPerRead] bytes per read (simulates partial socket reads). */
    private class ChunkedInputStream(data: ByteArray, private val maxPerRead: Int) :
        FilterInputStream(ByteArrayInputStream(data)) {
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, minOf(len, maxPerRead))
    }

    /** OutputStream that sleeps per write to simulate a slow disk and exercise back-pressure. */
    private class SlowOutputStream(private val sink: ByteArrayOutputStream, private val sleepMs: Long) :
        OutputStream() {
        override fun write(b: Int) = sink.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) {
            Thread.sleep(sleepMs)
            sink.write(b, off, len)
        }
        fun bytes(): ByteArray = sink.toByteArray()
    }

    private fun noWriterThreadAlive(): Boolean =
        Thread.getAllStackTraces().keys.none { it.name == "dcc-disk-writer" && it.isAlive }

    // --- happy path -------------------------------------------------------------------------

    @Test(timeout = 30_000)
    fun roundTrip_writesAllBytes_returnsDurableTotal() {
        val payload = bytes(1_000_000)
        val disk = ByteArrayOutputStream()
        val written = pumpReceive(
            data = ByteArrayInputStream(payload),
            disk = disk,
            expectedSize = payload.size.toLong(),
            ackSink = null,
            onProgress = noProgress(),
        )
        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, disk.toByteArray())
    }

    @Test(timeout = 30_000)
    fun shortReads_oneBytePerRead_transfersExactly() {
        val payload = bytes(5000)
        val disk = ByteArrayOutputStream()
        val written = pumpReceive(
            data = ChunkedInputStream(payload, maxPerRead = 1),
            disk = disk,
            expectedSize = payload.size.toLong(),
            ackSink = null,
            onProgress = noProgress(),
        )
        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, disk.toByteArray())
    }

    // --- cap / size handling (caveat g) ----------------------------------------------------

    @Test(timeout = 30_000)
    fun expectedSizeSmallerThanPayload_stopsAtCap() {
        val payload = bytes(10_000)
        val cap = 4096L
        val disk = ByteArrayOutputStream()
        val written = pumpReceive(
            data = ByteArrayInputStream(payload),
            disk = disk,
            expectedSize = cap,
            ackSink = null,
            onProgress = noProgress(),
        )
        assertEquals(cap, written)
        assertArrayEquals(payload.copyOfRange(0, cap.toInt()), disk.toByteArray())
    }

    @Test(timeout = 30_000)
    fun noExpectedSize_acceptsPayload() {
        val payload = bytes(8192)
        val disk = ByteArrayOutputStream()
        val written = pumpReceive(
            data = ByteArrayInputStream(payload),
            disk = disk,
            expectedSize = 0L,
            ackSink = null,
            onProgress = noProgress(),
        )
        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, disk.toByteArray())
    }

    @Test(timeout = 30_000)
    fun shortPayload_belowExpectedSize_returnsShortDurableTotal() {
        // Peer closed early (sent fewer bytes than advertised). pumpReceive must return the short
        // DURABLE total so the caller's verifyCompleteOrCleanup gate fails it as incomplete (caveat c).
        val payload = bytes(4000)
        val disk = ByteArrayOutputStream()
        val written = pumpReceive(
            data = ByteArrayInputStream(payload),
            disk = disk,
            expectedSize = 10_000L,
            ackSink = null,
            onProgress = noProgress(),
        )
        assertEquals(4000L, written)
        assertArrayEquals(payload, disk.toByteArray())
    }

    // --- ACK semantics (caveat f) ----------------------------------------------------------

    @Test(timeout = 30_000)
    fun acks_areCumulative_andTerminalEqualsTotal() {
        val payload = bytes(200_000)
        val acks = ArrayList<Long>()
        pumpReceive(
            data = ChunkedInputStream(payload, maxPerRead = 8192),
            disk = ByteArrayOutputStream(),
            expectedSize = payload.size.toLong(),
            ackSink = AckSink { acks.add(it) },
            onProgress = noProgress(),
            bufSize = 16384,
        )
        assertTrue("expected at least one ACK", acks.isNotEmpty())
        for (i in 1 until acks.size) assertTrue("ACKs must be monotonic at index $i", acks[i] >= acks[i - 1])
        assertEquals("terminal ACK must equal total bytes", payload.size.toLong(), acks.last())
    }

    @Test(timeout = 30_000)
    fun terminalAck_isReEmittedEvenWhenItDuplicatesTheLastPerChunkAck() {
        // The unconditional post-loop terminal ACK must fire EVEN WHEN the last per-chunk ACK already
        // equalled total — otherwise the sender spins to its 10s deadline and mis-reports success as
        // failure. A single-chunk transfer makes the per-chunk and terminal ACKs both == total, so the
        // total must appear at least twice. (Deleting the terminal ACK leaves it appearing once.)
        val payload = bytes(100)
        val acks = ArrayList<Long>()
        pumpReceive(
            data = ByteArrayInputStream(payload),
            disk = ByteArrayOutputStream(),
            expectedSize = payload.size.toLong(),
            ackSink = AckSink { acks.add(it) },
            onProgress = noProgress(),
        )
        assertEquals("last ACK must equal total", 100L, acks.last())
        assertTrue("terminal ACK must re-emit the cumulative total", acks.count { it == 100L } >= 2)
    }

    @Test(timeout = 30_000)
    fun turbo_noAcks_transfersFully() {
        val payload = bytes(50_000)
        val disk = ByteArrayOutputStream()
        val written = pumpReceive(
            data = ByteArrayInputStream(payload),
            disk = disk,
            expectedSize = payload.size.toLong(),
            ackSink = null, // turbo: no ACKs
            onProgress = noProgress(),
        )
        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, disk.toByteArray())
    }

    // --- exception propagation + no leaked threads (caveats b, c) --------------------------

    @Test(timeout = 30_000)
    fun diskWriteThrowsIOException_propagates_andJoinsWriter() {
        val payload = bytes(500_000)
        val failAfter = 64 * 1024
        val disk = object : OutputStream() {
            var total = 0
            override fun write(b: Int) { /* unused by the pump */ }
            override fun write(b: ByteArray, off: Int, len: Int) {
                total += len
                if (total > failAfter) throw IOException("disk full")
            }
        }
        var thrown: Throwable? = null
        try {
            pumpReceive(
                data = ByteArrayInputStream(payload),
                disk = disk,
                expectedSize = payload.size.toLong(),
                ackSink = null,
                onProgress = noProgress(),
                queueCapacity = 2,
            )
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected IOException but got $thrown", thrown is IOException)
        assertTrue("the writer's IOException must propagate unchanged", (thrown as IOException).message?.contains("disk full") == true)
        assertTrue("writer thread must be joined before pumpReceive returns", noWriterThreadAlive())
    }

    @Test(timeout = 30_000)
    fun diskWriteThrowsRuntimeException_wrappedAsIOException() {
        val payload = bytes(200_000)
        val disk = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) = throw IllegalStateException("boom")
        }
        var thrown: Throwable? = null
        try {
            pumpReceive(
                data = ByteArrayInputStream(payload),
                disk = disk,
                expectedSize = payload.size.toLong(),
                ackSink = null,
                onProgress = noProgress(),
                queueCapacity = 2,
            )
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected IOException but got $thrown", thrown is IOException)
        assertTrue("original cause must be preserved", (thrown as IOException).cause is IllegalStateException)
        assertEquals("cause message must be preserved", "boom", thrown.cause?.message)
        assertTrue(noWriterThreadAlive())
    }

    // --- cancellation (caveat b) -----------------------------------------------------------

    @Test(timeout = 30_000)
    fun readerThrowsMidTransfer_propagates_andJoinsWriter() {
        // Simulates sock.close() mid-transfer (the cancel contract): data.read() throws, the reader
        // exits, the writer is poisoned/interrupted and joined, and the failure propagates — no leak.
        val failAfter = 50_000
        val data = object : InputStream() {
            private val backing = ByteArrayInputStream(bytes(500_000))
            private var read = 0
            override fun read(): Int = backing.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (read >= failAfter) throw IOException("socket closed")
                val n = backing.read(b, off, minOf(len, 8192))
                if (n > 0) read += n
                return n
            }
        }
        var thrown: Throwable? = null
        try {
            pumpReceive(
                data = data,
                disk = ByteArrayOutputStream(),
                expectedSize = 500_000L,
                ackSink = null,
                onProgress = noProgress(),
                queueCapacity = 2,
                joinTickMs = 20,
            )
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("a mid-transfer read failure must propagate", thrown is IOException)
        assertTrue("writer thread must not leak after a cancelled transfer", noWriterThreadAlive())
    }

    // --- durability + back-pressure + bounded memory (caveats a, b, d) ---------------------

    @Test(timeout = 30_000)
    fun durableCount_withSlowWriter_equalsPayload() {
        // Proves join-before-return makes "durable written" == "bytes read" on the clean path, even
        // when the disk is much slower than the wire. (This is the exact case the earlier interrupt
        // bug corrupted: a fast reader finishing before a slow writer drains the bounded queue.)
        val payload = bytes(300_000)
        val disk = SlowOutputStream(ByteArrayOutputStream(), sleepMs = 2)
        val written = pumpReceive(
            data = ByteArrayInputStream(payload), // fast producer
            disk = disk,                          // slow consumer
            expectedSize = payload.size.toLong(),
            ackSink = null,
            onProgress = noProgress(),
            bufSize = 16384,
            queueCapacity = 4,
            joinTickMs = 20,
        )
        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, disk.bytes())
    }

    @Test(timeout = 30_000)
    fun slowSingleWrite_underStallTimeout_doesNotFalseFail() {
        // A single write() that is slow but well under the stall window must NOT be killed by the
        // watchdog. (Guards the false-fail Codex flagged: the watchdog measures progress at whole-
        // chunk granularity, so it must give a slow in-flight write enough headroom to return.)
        val payload = bytes(40_000) // one default-buffer read -> a single slow write() on the writer
        val disk = SlowOutputStream(ByteArrayOutputStream(), sleepMs = 800)
        val written = pumpReceive(
            data = ByteArrayInputStream(payload),
            disk = disk,
            expectedSize = payload.size.toLong(),
            ackSink = null,
            onProgress = noProgress(),
            joinTickMs = 50,
            cleanStallMs = 4000, // 800ms write << 4000ms stall window -> must succeed
        )
        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, disk.bytes())
    }

    @Test(timeout = 30_000)
    fun manySlowWrites_totalDrainExceedsStall_butPerWriteUnder_doesNotFalseFail() {
        // The stall window is PER-WRITE, not for the whole drain: a writer whose total drain time
        // far exceeds the stall window must still succeed as long as each individual write returns
        // within it (each committed chunk resets the timer).
        val payload = bytes(160_000)
        val disk = SlowOutputStream(ByteArrayOutputStream(), sleepMs = 300)
        val written = pumpReceive(
            data = ByteArrayInputStream(payload),
            disk = disk,
            expectedSize = payload.size.toLong(),
            ackSink = null,
            onProgress = noProgress(),
            bufSize = 16384,     // ~10 chunks => ~3s total drain
            queueCapacity = 4,
            joinTickMs = 50,
            cleanStallMs = 1500, // total drain (~3s) > stall window, but each 300ms write is under it
        )
        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, disk.bytes())
    }

    @Test(timeout = 30_000)
    fun backPressure_boundsPeakInFlightMemory_andDeliversEveryByte() {
        // A fast producer + slow consumer + tiny bounded queue must NOT deadlock or OOM, must deliver
        // every byte, AND must keep peak in-flight memory bounded regardless of file size. We measure
        // peak (produced - consumed): an unbounded-queue regression would let it grow toward the whole
        // payload and fail the bound; the bounded queue caps it near queueCapacity * bufSize.
        val payload = bytes(2_000_000)
        val bufSize = 32_768
        val queueCapacity = 4
        val produced = AtomicLong(0)
        val peakInFlight = AtomicLong(0)

        val data = object : InputStream() {
            private val backing = ByteArrayInputStream(payload)
            override fun read(): Int = backing.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = backing.read(b, off, len)
                if (n > 0) produced.addAndGet(n.toLong())
                return n
            }
        }
        val sink = ByteArrayOutputStream()
        val disk = object : OutputStream() {
            private var consumed = 0L
            override fun write(b: Int) = sink.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) {
                Thread.sleep(1) // slow consumer: an unbounded queue would let the producer race ahead
                consumed += len
                val inFlight = produced.get() - consumed
                var cur = peakInFlight.get()
                while (inFlight > cur && !peakInFlight.compareAndSet(cur, inFlight)) cur = peakInFlight.get()
                sink.write(b, off, len)
            }
        }

        val written = pumpReceive(
            data = data,
            disk = disk,
            expectedSize = payload.size.toLong(),
            ackSink = null,
            onProgress = noProgress(),
            bufSize = bufSize,
            queueCapacity = queueCapacity,
            pollMs = 50,
            joinTickMs = 20,
        )
        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, sink.toByteArray())
        // queue + one chunk in the reader's hand + one being written, plus slack:
        val bound = (queueCapacity + 3).toLong() * bufSize
        assertTrue(
            "peak in-flight ${peakInFlight.get()} must stay bounded (<= $bound), not grow with file size",
            peakInFlight.get() <= bound,
        )
        assertTrue(noWriterThreadAlive())
    }

    // --- progress throttle (the confirmed wildcard) ----------------------------------------

    @Test(timeout = 30_000)
    fun progressThrottle_emitsFirstAndFinalOnly_whenIntervalHuge() {
        val payload = bytes(2000)
        val emits = ArrayList<Pair<Long, Long>>()
        pumpReceive(
            data = ChunkedInputStream(payload, maxPerRead = 1), // 2000 reads
            disk = ByteArrayOutputStream(),
            expectedSize = payload.size.toLong(),
            ackSink = null,
            onProgress = { sent, total -> emits.add(sent to total) },
            progressIntervalMs = Long.MAX_VALUE, // throttle everything between first and final
        )
        assertEquals("only the first and final progress ticks should be emitted", 2, emits.size)
        assertTrue("first emitted progress must be > 0 (preserves receivedAnyBytes)", emits.first().first > 0)
        assertEquals("final emitted progress must equal total", payload.size.toLong(), emits.last().first)
    }

    // --- ACK encoding (buildAck) -----------------------------------------------------------

    @Test
    fun buildAck_4byteBigEndian_belowThreshold_andClamps() {
        val ack = buildAck(0x01020304L, expectedSize = 1000L)
        assertEquals(4, ack.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), ack)

        val clamped = buildAck(0x1_0000_0000L, expectedSize = 1000L)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            clamped,
        )
    }

    @Test
    fun buildAck_8byteBigEndian_aboveThreshold() {
        val total = 0x0000_0001_0000_0005L
        val ack = buildAck(total, expectedSize = 0x1_0000_0001L) // > 4 GiB ⇒ 64-bit ACK
        assertEquals(8, ack.size)
        val roundTrip = ByteBuffer.wrap(ack).order(ByteOrder.BIG_ENDIAN).long
        assertEquals(total, roundTrip)
    }
}
