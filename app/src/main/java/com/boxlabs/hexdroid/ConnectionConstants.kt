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
     * Client PING interval while backgrounded (ms); the foreground uses 60 s direct and 90 s
     * through a bouncer. Must stay below [SOCKET_READ_TIMEOUT_MS]: the PONG it elicits is what
     * resets the read deadline.
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
     * TLS handshake timeout (ms), applied as soTimeout only during startHandshake() and then
     * restored to SOCKET_READ_TIMEOUT_MS, so a stalled handshake cannot hang the connect.
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
     * Socket read timeout. Above the 60 s PING interval so quiet channels never trip it, but short
     * enough to notice a socket that Doze has silently killed; the read then times out and
     * auto-reconnect starts.
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
