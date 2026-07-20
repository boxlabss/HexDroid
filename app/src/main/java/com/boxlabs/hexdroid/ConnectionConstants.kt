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

package com.boxlabs.hexdroid.connection

/**
 * Constants for connection management.
 * Centralizes magic numbers for easier tuning and consistency.
 */
object ConnectionConstants {
    // --- Heartbeat / Health Check ---
    // IrcCore handles the single ping/pong cycle for both lag measurement and keepalive.
    // Sending PING every 60 seconds is sufficient for NAT keepalive and lag display.

    /** How long to wait for a PONG before considering the connection dead (ms). */
    const val PING_TIMEOUT_MS = 180_000L  // 180 seconds (3 missed pings at 60s interval)

    /**
     * Client PING interval used while the app is backgrounded (ms).
     *
     * Foreground uses 60 s (direct) / 90 s (bouncer) for a responsive lag readout and
     * fast stall detection. Backgrounded, nobody is watching the lag display and a dead
     * socket is already caught by [SOCKET_READ_TIMEOUT_MS] plus the ConnectivityManager
     * callback, so the only job left for our own PING is to keep inbound data flowing.
     * The IRC server itself PINGs an idle client (the read loop answers with PONG), so a
     * frequent client PING is not required to avoid an idle-drop. Lengthening this cuts
     * background CPU/radio wakeups.
     *
     * INVARIANT: this MUST stay strictly below [SOCKET_READ_TIMEOUT_MS]. Our PING elicits
     * a PONG, and that inbound PONG is what resets the socket read deadline. If the
     * interval met or exceeded the read timeout, a healthy-but-quiet connection would hit
     * the read timeout and reconnect needlessly. 120 s leaves a 30 s margin for the PONG
     * round-trip under the 150 s read timeout.
     */
    const val BACKGROUND_PING_INTERVAL_MS = 120_000L

    // --- Reconnect Backoff ---

    /** Minimum base delay for auto-reconnect (seconds). */
    const val RECONNECT_BASE_DELAY_MIN_SEC = 5

    /** Maximum base delay for auto-reconnect (seconds). */
    const val RECONNECT_BASE_DELAY_MAX_SEC = 600

    /**
     * Maximum delay after exponential backoff (seconds). Was 600 (10 minutes), which
     * users experienced as "the app stopped reconnecting". 180 s keeps retries polite
     * to the server while ensuring a recovered server is rejoined within 3 minutes
     * worst case.
     */
    const val RECONNECT_MAX_DELAY_SEC = 180L

    /** Maximum exponent for backoff (2^6 = 64x multiplier). */
    const val RECONNECT_MAX_EXPONENT = 6

    /** Jitter factor to prevent thundering herd (0.10 = ±10%). */
    const val RECONNECT_JITTER_FACTOR = 0.10

    /** Maximum number of reconnect attempts to track. */
    const val RECONNECT_MAX_ATTEMPTS = 30

    /**
     * How long a connection must stay up (ms) before the reconnect backoff counter is
     * reset to zero. Waiting for stability prevents a server that connects and immediately
     * drops the client (Z-line, cert rejection, etc.) from resetting the backoff on every
     * cycle and hammering the server with rapid retries.
     */
    const val STABLE_CONNECTION_MS = 30_000L

    // --- Flap Detection ---

    /** Number of ping-timeout disconnects within FLAP_WINDOW_MS that triggers flap detection. */
    const val FLAP_THRESHOLD = 3

    /** Time window for flap detection (ms). Ping-timeouts older than this are ignored. */
    const val FLAP_WINDOW_MS = 15 * 60 * 1000L  // 15 minutes

    // --- Connection Timeouts ---

    /** Socket connect timeout (ms). */
    const val SOCKET_CONNECT_TIMEOUT_MS = 30_000

    /**
     * Timeout for the TLS handshake (ms).
     *
     * Applied as SSLSocket.soTimeout *only* during startHandshake(), then restored to
     * SOCKET_READ_TIMEOUT_MS. This bounds the handshake on devices whose BoringSSL
     * implementation stalls or emits SSL_ERROR_SYSCALL/"Success" when the radio suspends.
     * 30 s is generous for any reachable IRC server; typical handshakes finish in < 1 s.
     */
    const val TLS_HANDSHAKE_TIMEOUT_MS = 30_000

    /** IRC registration timeout - time to receive 001 RPL_WELCOME (ms). */
    const val REGISTRATION_TIMEOUT_MS = 60_000L

    /** SASL authentication timeout (ms). */
    const val SASL_TIMEOUT_MS = 30_000L

    // --- Socket Options ---

    /** TCP keep-alive enable. */
    const val TCP_KEEPALIVE = true

    /**
     * Socket read timeout — safety net for dead sockets on mobile.
     *
     * Set to 150 s (2.5 min): safely above the 60 s PING interval so normal quiet
     * channels never trigger it, but short enough to catch sockets that Doze mode
     * has silently killed.
     *
     * Without this, InputStream.read() blocks indefinitely on a dead socket. The OS
     * may buffer the outgoing PING so writeLine() succeeds, the PONG never arrives,
     * and the 180 s ping timeout fires — meaning 4+ minutes pass before reconnect.
     * Many servers detect the dead socket sooner and close it, which is what produces
     * "Underlying socket operation returned zero" on the next read attempt.
     *
     * With 150 s soTimeout: a SocketTimeoutException is thrown after 2.5 min of
     * silence, the coroutine exits cleanly, and auto-reconnect triggers immediately.
     */
    const val SOCKET_READ_TIMEOUT_MS = 150_000

    // ---- Primary-nick reclaim (after registering on a fallback/alt nick) ----
    // When the configured nick was taken at registration (usually our own ghost session
    // after a mobile reconnect), retry NICK <primary> with exponential backoff until it
    // frees up, the user takes manual control, or we give up.
    const val NICK_RECLAIM_INITIAL_DELAY_MS = 3_000L   // let SASL/services reclaim & a fast ghost ping-timeout settle
    const val NICK_RECLAIM_RESPONSE_GRACE_MS = 2_000L  // wait for the server to confirm the NICK or reply 433/437
    // After the first attempt fails, retry quiety until the server's ping-timeout (~180s, see
    // PING_TIMEOUT_MS), so there's nothing to announce in between
    const val NICK_RECLAIM_RETRY_INTERVAL_MS = 30_000L
    // Keep retrying for ~2× the ping-timeout, comfortably past when a ghost should clear. If
    // the nick is still held after that, it's likely a real user, so stop and tell the user.
    const val NICK_RECLAIM_TOTAL_WINDOW_MS = 360_000L
}
