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
 * IRCv3 Strict Transport Security (STS). The `sts` capability is observed in CAP LS/NEW, never
 * requested. On a plaintext connection its `port=` value means reconnect with TLS on that port; on
 * a TLS connection `duration=` (and optional `preload`) sets or refreshes a persisted policy, and a
 * duration of 0 deletes it. Pure JVM so it can be unit tested.
 */

/** Parsed value of the `sts` capability. */
data class StsCapValue(
    val port: Int?,
    val durationSec: Long?,
    val preload: Boolean,
)

/**
 * Parse an `sts` value such as `duration=2592000,preload` or `port=6697`: comma-separated keys,
 * unknown keys ignored, first duplicate wins, an invalid value only disables its own key. Null when
 * neither a usable port nor duration is present.
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
 * A persisted STS policy for one host. [port] is the TLS port learned from a plaintext upgrade, or
 * null. [expiresAtMs] is refreshed by every secure connection that advertises a duration;
 * [durationSec] is the last one advertised, used to extend the expiry when a secure connection
 * closes.
 */
data class StsPolicyEntry(
    val port: Int?,
    val expiresAtMs: Long,
    val durationSec: Long = 0L,
) {
    fun isActive(nowMs: Long): Boolean = expiresAtMs > nowMs
}
