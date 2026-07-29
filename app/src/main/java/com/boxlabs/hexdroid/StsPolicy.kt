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

/**
 * IRCv3 Strict Transport Security (STS) support types.
 *
 * The `sts` capability always carries a value and is never requested with
 * CAP REQ; clients only observe it in CAP LS / CAP NEW:
 *
 *  - On an INSECURE connection the value carries `port=<tlsport>`: the client
 *    must abandon the plaintext connection and reconnect with TLS on that
 *    port. Nothing is persisted yet - the policy only becomes durable once
 *    confirmed over TLS.
 *  - On a SECURE connection the value carries `duration=<seconds>` (and
 *    optionally `preload`): the client persists/refreshes a policy that all
 *    connections to this host must use TLS until now + duration. A duration
 *    of 0 deletes the policy.
 *
 * Kept pure JVM (no Android imports) so it is unit-testable off-device,
 * matching IrcParser and FilehostUpload.
 */

/** Parsed value of the `sts` capability. */
data class StsCapValue(
    val port: Int?,
    val durationSec: Long?,
    val preload: Boolean,
)

/**
 * Parse an `sts` capability value such as `duration=2592000,preload` or
 * `port=6697`.
 *
 * Per the spec: tokens are comma-separated `key` or `key=value` pairs; keys
 * are lowercase and case-sensitive; unknown keys are ignored; on duplicate
 * keys the first occurrence wins; invalid values for a known key make that
 * key unusable but do not invalidate the rest. Returns null when neither a
 * usable `port` nor a usable `duration` is present (a valueless or empty
 * `sts` cap is meaningless and must be ignored).
 */
fun parseStsCapValue(raw: String?): StsCapValue? {
    if (raw.isNullOrBlank()) return null
    var port: Int? = null
    var duration: Long? = null
    var preload = false
    for (tok in raw.split(',')) {
        val t = tok.trim()
        if (t.isEmpty()) continue
        val k = t.substringBefore('=')
        val v = if (t.contains('=')) t.substringAfter('=') else null
        when (k) {
            "port" -> if (port == null) port = v?.toIntOrNull()?.takeIf { it in 1..65535 }
            "duration" -> if (duration == null) duration = v?.toLongOrNull()?.takeIf { it >= 0 }
            "preload" -> preload = true
        }
    }
    if (port == null && duration == null) return null
    return StsCapValue(port, duration, preload)
}

/**
 * A persisted STS policy for one hostname (keyed externally by the
 * lowercased host). [port] is the TLS port learned from an insecure
 * connection's upgrade, kept so a plaintext-configured profile knows where
 * to connect; null when the policy was only ever seen over TLS (the profile
 * port, or 6697, is used instead). [expiresAtMs] is wall-clock epoch millis;
 * the spec's continual-refresh model means every secure connection that
 * advertises a duration pushes this forward.
 */
data class StsPolicyEntry(
    val port: Int?,
    val expiresAtMs: Long,
) {
    fun isActive(nowMs: Long): Boolean = expiresAtMs > nowMs
}
