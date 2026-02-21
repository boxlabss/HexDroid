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
    
    // --- Reconnect Backoff ---
    
    /** Minimum base delay for auto-reconnect (seconds). */
    const val RECONNECT_BASE_DELAY_MIN_SEC = 5
    
    /** Maximum base delay for auto-reconnect (seconds). */
    const val RECONNECT_BASE_DELAY_MAX_SEC = 600
    
    /** Maximum delay after exponential backoff (seconds). */
    const val RECONNECT_MAX_DELAY_SEC = 600L
    
    /** Maximum exponent for backoff (2^6 = 64x multiplier). */
    const val RECONNECT_MAX_EXPONENT = 6
    
    /** Jitter factor to prevent thundering herd (0.10 = ±10%). */
    const val RECONNECT_JITTER_FACTOR = 0.10
    
    /** Maximum number of reconnect attempts to track. */
    const val RECONNECT_MAX_ATTEMPTS = 30
    
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
    
    /** Socket read timeout (0 = infinite, relies on PING/PONG for liveness).
     *
     * We keep this at 0 (infinite) and rely exclusively on the PING/PONG loop in IrcCore
     * for mid-session liveness detection.  Using a non-zero soTimeout here would cause
     * SocketTimeoutException on every quiet period longer than the timeout, producing
     * false disconnects on low-traffic channels.  The PING loop fires every 60-90 s and
     * closes the socket after PING_TIMEOUT_MS (180 s) of silence — that is the correct
     * mechanism for detecting half-open connections on mobile.
     */
    const val SOCKET_READ_TIMEOUT_MS = 0
}
