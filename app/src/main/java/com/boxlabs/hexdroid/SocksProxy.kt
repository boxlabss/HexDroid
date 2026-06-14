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

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Proxy protocol families HexDroid can tunnel an IRC connection through.
 *
 * Both SOCKS variants resolve the *destination* hostname at the proxy rather than on the
 * device. This is essential for two reasons:
 *  - Tor: `.onion` addresses have no DNS record and can ONLY be resolved by the Tor daemon
 *    behind the SOCKS port, so local resolution is impossible by construction.
 *  - Privacy: even for clearnet hosts, resolving locally would leak the IRC server's
 *    hostname to the device's DNS resolver (and thus the network operator/ISP),
 *    defeating much of the point of proxying. Remote resolution keeps the lookup inside
 *    the tunnel.
 */
enum class ProxyType {
    /** No proxy; connect directly. */
    NONE,

    /**
     * SOCKS5 (RFC 1928) with optional username/password auth (RFC 1929). Sends the
     * destination as a DOMAINNAME address so the proxy performs resolution.
     */
    SOCKS5,

    /**
     * SOCKS4a (the 'a' extension to SOCKS4). Also supports remote DNS, by sending a
     * sentinel destination IP of 0.0.0.x followed by the literal hostname.
     */
    SOCKS4A,
}

/**
 * Immutable description of the proxy to route a connection through. A [type] of
 * [ProxyType.NONE] means "no proxy"; the other fields are then ignored.
 *
 * [username] / [password] apply only to [ProxyType.SOCKS5] (RFC 1929 user/pass auth). For
 * [ProxyType.SOCKS4A], [username] is sent verbatim as the SOCKS4 USERID field and
 * [password] is ignored.
 */
data class ProxyConfig(
    val type: ProxyType = ProxyType.NONE,
    val host: String = "",
    val port: Int = 0,
    val username: String? = null,
    val password: String? = null,
) {
    val enabled: Boolean get() = type != ProxyType.NONE && host.isNotBlank() && port in 1..65535

    /** True when this proxy carries auth credentials (SOCKS5 only). */
    val hasAuth: Boolean get() = !username.isNullOrEmpty()

    companion object {
        const val TOR_ORBOT_PORT = 9050
        const val TOR_BROWSER_PORT = 9150

        /** Convenience preset for a local Tor SOCKS proxy (Orbot). */
        fun tor(host: String = "127.0.0.1", port: Int = TOR_ORBOT_PORT): ProxyConfig =
            ProxyConfig(type = ProxyType.SOCKS5, host = host, port = port)
    }
}

/** Thrown when the proxy refuses or fails to establish the tunnel to the destination. */
class ProxyException(message: String) : IOException(message)

/**
 * Establishes TCP connections through a SOCKS proxy, performing the handshake by hand so
 * the destination hostname is resolved *at the proxy* (remote DNS) rather than on-device.
 *
 * The returned [Socket] is connected end-to-end to the destination: callers can read/write
 * it directly, or hand it to an [javax.net.ssl.SSLSocketFactory.createSocket] overload to
 * layer TLS on top exactly as they would a direct socket. From the TLS layer's perspective
 * the proxied socket is indistinguishable from a direct one, so certificate validation,
 * SNI, and hostname checks all continue to work against the real destination host.
 */
object SocksProxy {

    /**
     * Open a TCP connection to [destHost]:[destPort] through the proxy described by [cfg].
     *
     * @param connectTimeoutMs applied to the TCP connect to the *proxy* and as the
     *        socket's read timeout during the handshake, so a black-holed or wrong-protocol
     *        proxy can't hang the connect coroutine forever.
     * @param soTimeoutMs the read timeout to leave on the socket once the tunnel is up
     *        (the handshake uses [connectTimeoutMs] internally and restores this after).
     * @param tcpNoDelay/[keepAlive] applied to the underlying socket to match the
     *        direct-connection socket options.
     *
     * @throws ProxyException if the proxy rejects the request or speaks an unexpected
     *         protocol; the caller treats this like any other connect failure (it flows
     *         into the error mapper and the reconnect loop).
     */
    @Throws(IOException::class)
    fun connect(
        cfg: ProxyConfig,
        destHost: String,
        destPort: Int,
        connectTimeoutMs: Int,
        soTimeoutMs: Int,
        tcpNoDelay: Boolean,
        keepAlive: Boolean,
        pinnedNetwork: android.net.Network? = null,
    ): Socket {
        require(cfg.enabled) { "SocksProxy.connect called with a disabled proxy config" }

        val socket = Socket().apply {
            this.tcpNoDelay = tcpNoDelay
            this.keepAlive = keepAlive
        }
        try {
            // Pin the socket-to-proxy hop to the chosen interface before connect, mirroring
            // the direct path in IrcCore.openSocket. For a localhost Tor/SOCKS proxy the bind
            // is a no-op on loopback traffic; for a remote proxy host it ensures the hop rides
            // the same network the rest of the connection is pinned to. runCatching: degrade
            // to default routing if the network vanished between selection and now.
            pinnedNetwork?.let { net -> runCatching { net.bindSocket(socket) } }
            // Connect to the PROXY (not the destination). The proxy address is a literal
            // host:port the user configured, so a local resolve here is fine and expected;
            // it's usually 127.0.0.1 for Tor anyway.
            socket.connect(InetSocketAddress(cfg.host, cfg.port), connectTimeoutMs)
            // Bound the handshake so a silent/wrong-protocol proxy can't stall us. Restored
            // to soTimeoutMs once the tunnel is established.
            socket.soTimeout = connectTimeoutMs

            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            when (cfg.type) {
                ProxyType.SOCKS5 -> socks5Handshake(cfg, input, output, destHost, destPort)
                ProxyType.SOCKS4A -> socks4aHandshake(cfg, input, output, destHost, destPort)
                ProxyType.NONE -> error("unreachable: disabled config already rejected")
            }

            socket.soTimeout = soTimeoutMs
            return socket
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    // SOCKS5 (RFC 1928/1929)

    private const val SOCKS5_VERSION = 0x05
    private const val SOCKS5_CMD_CONNECT = 0x01
    private const val SOCKS5_ATYP_IPV4 = 0x01
    private const val SOCKS5_ATYP_DOMAIN = 0x03
    private const val SOCKS5_ATYP_IPV6 = 0x04
    private const val SOCKS5_AUTH_NONE = 0x00
    private const val SOCKS5_AUTH_USERPASS = 0x02
    private const val SOCKS5_AUTH_NO_ACCEPTABLE = 0xFF

    private fun socks5Handshake(
        cfg: ProxyConfig,
        input: DataInputStream,
        output: OutputStream,
        destHost: String,
        destPort: Int,
    ) {
        // Advertise the auth methods we support
        // Always offer "no auth"; additionally offer user/pass when credentials are set, so
        // a proxy that doesn't need auth still works even if the user filled the fields.
        val methods = if (cfg.hasAuth) {
            byteArrayOf(SOCKS5_AUTH_NONE.toByte(), SOCKS5_AUTH_USERPASS.toByte())
        } else {
            byteArrayOf(SOCKS5_AUTH_NONE.toByte())
        }
        val greeting = ByteArray(2 + methods.size)
        greeting[0] = SOCKS5_VERSION.toByte()
        greeting[1] = methods.size.toByte()
        System.arraycopy(methods, 0, greeting, 2, methods.size)
        output.write(greeting)
        output.flush()

        // Method selection reply: [VER, METHOD].
        val ver = input.readUnsignedByte()
        if (ver != SOCKS5_VERSION) {
            throw ProxyException("SOCKS5: proxy returned version $ver (expected 5); is the proxy really SOCKS5?")
        }
        when (val chosen = input.readUnsignedByte()) {
            SOCKS5_AUTH_NONE -> { /* proceed straight to CONNECT */ }
            SOCKS5_AUTH_USERPASS -> {
                if (!cfg.hasAuth) {
                    throw ProxyException("SOCKS5: proxy requires username/password but none was configured")
                }
                socks5UserPassAuth(cfg, input, output)
            }
            SOCKS5_AUTH_NO_ACCEPTABLE ->
                throw ProxyException("SOCKS5: proxy rejected all offered auth methods (needs credentials?)")
            else ->
                throw ProxyException("SOCKS5: proxy selected unsupported auth method 0x${chosen.toString(16)}")
        }

        // CONNECT request. When the destination is a numeric IP literal we send it as an
        // ATYP_IPV4 / ATYP_IPV6 address (nothing to resolve); otherwise we send a DOMAINNAME
        // so a PROXY resolves it remotely
        val (atyp, addr) = encodeSocks5Dest(destHost)
        val request = ByteArray(4 + addr.size + 2)
        var i = 0
        request[i++] = SOCKS5_VERSION.toByte()
        request[i++] = SOCKS5_CMD_CONNECT.toByte()
        request[i++] = 0x00 // reserved
        request[i++] = atyp.toByte()
        System.arraycopy(addr, 0, request, i, addr.size)
        i += addr.size
        request[i++] = ((destPort ushr 8) and 0xFF).toByte()
        request[i] = (destPort and 0xFF).toByte()
        output.write(request)
        output.flush()

        // CONNECT reply: [VER, REP, RSV, ATYP, BND.ADDR..., BND.PORT(2)].
        val rVer = input.readUnsignedByte()
        if (rVer != SOCKS5_VERSION) {
            throw ProxyException("SOCKS5: malformed CONNECT reply (version $rVer)")
        }
        val rep = input.readUnsignedByte()
        if (rep != 0x00) {
            throw ProxyException("SOCKS5: ${socks5ReplyMessage(rep)}")
        }
        input.readUnsignedByte() // reserved
        // Consume the bound address so the stream is positioned exactly at the start of the
        // tunnelled application data. We don't use the bound address for anything.
        when (val atyp = input.readUnsignedByte()) {
            SOCKS5_ATYP_IPV4 -> input.skipFully(4)
            SOCKS5_ATYP_IPV6 -> input.skipFully(16)
            SOCKS5_ATYP_DOMAIN -> {
                val len = input.readUnsignedByte()
                input.skipFully(len)
            }
            else -> throw ProxyException("SOCKS5: CONNECT reply has unknown address type 0x${atyp.toString(16)}")
        }
        input.skipFully(2) // bound port
        // Tunnel is open; subsequent reads/writes on the socket are end-to-end with dest.
    }

    private fun socks5UserPassAuth(
        cfg: ProxyConfig,
        input: DataInputStream,
        output: OutputStream,
    ) {
        // RFC 1929: [VER=0x01, ULEN, UNAME, PLEN, PASSWD]. Note VER here is the auth
        // sub-negotiation version (1), NOT the SOCKS version.
        val user = (cfg.username ?: "").toByteArray(Charsets.UTF_8)
        val pass = (cfg.password ?: "").toByteArray(Charsets.UTF_8)
        if (user.size > 255 || pass.size > 255) {
            throw ProxyException("SOCKS5: username/password too long (max 255 bytes each)")
        }
        val msg = ByteArray(3 + user.size + pass.size)
        var i = 0
        msg[i++] = 0x01 // auth sub-negotiation version
        msg[i++] = user.size.toByte()
        System.arraycopy(user, 0, msg, i, user.size); i += user.size
        msg[i++] = pass.size.toByte()
        System.arraycopy(pass, 0, msg, i, pass.size)
        output.write(msg)
        output.flush()

        // Reply: [VER, STATUS]; STATUS 0x00 == success.
        input.readUnsignedByte() // sub-negotiation version echo
        val status = input.readUnsignedByte()
        if (status != 0x00) {
            throw ProxyException("SOCKS5: proxy rejected the username/password (status 0x${status.toString(16)})")
        }
    }

    /**
     * Encode the SOCKS5 CONNECT destination. Returns (ATYP, payload) where payload is:
     * IPv4 literal  -> ATYP 0x01, 4 raw bytes
     * IPv6 literal  -> ATYP 0x04, 16 raw bytes
     * anything else -> ATYP 0x03 (DOMAINNAME), [len][ASCII host] for resolution AT THE
     * PROXY (remote DNS; required for .onion and to avoid on-device lookups).
     * [parseIpv4Literal]/[parseIpv6Literal] are purely textual and never resolve, so a real
     * hostname always falls through to the DOMAINNAME branch.
     */
    private fun encodeSocks5Dest(destHost: String): Pair<Int, ByteArray> {
        parseIpv4Literal(destHost)?.let { return SOCKS5_ATYP_IPV4 to it }
        // IPv6 literals may arrive bracketed (e.g. a "[2001:db8::1]" entry); strip the brackets.
        parseIpv6Literal(destHost.removePrefix("[").removeSuffix("]"))?.let { return SOCKS5_ATYP_IPV6 to it }
        val hostBytes = destHost.toByteArray(Charsets.US_ASCII)
        if (hostBytes.size > 255) {
            throw ProxyException("SOCKS5: destination host name too long (${hostBytes.size} > 255 bytes)")
        }
        val payload = ByteArray(1 + hostBytes.size)
        payload[0] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, payload, 1, hostBytes.size)
        return SOCKS5_ATYP_DOMAIN to payload
    }

    /** Parse a dotted-quad IPv4 literal to 4 bytes, or null if [s] isn't one. Never resolves. */
    private fun parseIpv4Literal(s: String): ByteArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (idx in 0 until 4) {
            val p = parts[idx]
            if (p.isEmpty() || p.length > 3 || p.any { !it.isDigit() }) return null
            val n = p.toInt()
            if (n !in 0..255) return null
            out[idx] = n.toByte()
        }
        return out
    }

    /**
     * Parse an IPv6 literal to 16 bytes, or null if [s] isn't a syntactically valid literal.
     * Handles "::" zero-compression and a trailing embedded IPv4 quad (e.g. ::ffff:192.0.2.1).
     * Performs NO name resolution; scope ids ("%eth0") are rejected
     */
    private fun parseIpv6Literal(s: String): ByteArray? {
        if (s.isEmpty() || s.length > 45 || s.contains('%')) return null

        // Fold a trailing embedded IPv4 quad into two synthetic hex groups so the remainder
        // parses as plain 16-bit groups below.
        var work = s
        val lastColon = s.lastIndexOf(':')
        if (lastColon >= 0 && s.substring(lastColon + 1).contains('.')) {
            val v4 = parseIpv4Literal(s.substring(lastColon + 1)) ?: return null
            val g6 = ((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF)
            val g7 = ((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF)
            work = s.substring(0, lastColon) + ":" + g6.toString(16) + ":" + g7.toString(16)
        }

        // At most one "::".
        val dc = work.indexOf("::")
        if (dc != work.lastIndexOf("::")) return null

        fun groups(part: String): IntArray? {
            if (part.isEmpty()) return IntArray(0)
            val gs = part.split(':')
            val res = IntArray(gs.size)
            for (idx in gs.indices) {
                val g = gs[idx]
                if (g.isEmpty() || g.length > 4 || g.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
                res[idx] = g.toInt(16)
            }
            return res
        }

        val all: IntArray = if (dc >= 0) {
            val left = groups(work.substring(0, dc)) ?: return null
            val right = groups(work.substring(dc + 2)) ?: return null
            val total = left.size + right.size
            if (total > 7) return null // "::" must stand in for >= 1 zero group
            left + IntArray(8 - total) + right
        } else {
            val g = groups(work) ?: return null
            if (g.size != 8) return null
            g
        }

        val out = ByteArray(16)
        for (idx in 0 until 8) {
            out[2 * idx] = ((all[idx] ushr 8) and 0xFF).toByte()
            out[2 * idx + 1] = (all[idx] and 0xFF).toByte()
        }
        return out
    }

    private fun socks5ReplyMessage(rep: Int): String = when (rep) {
        0x01 -> "general proxy failure"
        0x02 -> "connection not allowed by ruleset"
        0x03 -> "network unreachable"
        0x04 -> "host unreachable"
        0x05 -> "connection refused by destination"
        0x06 -> "TTL expired"
        0x07 -> "command not supported by proxy"
        0x08 -> "address type not supported by proxy"
        else -> "CONNECT failed (reply code 0x${rep.toString(16)})"
    }

    // SOCKS4a

    private fun socks4aHandshake(
        cfg: ProxyConfig,
        input: DataInputStream,
        output: OutputStream,
        destHost: String,
        destPort: Int,
    ) {
        // SOCKS4/4a has no IPv6 address form. Rather than emit a request the proxy rejects
        // opaquely, fail early with a clear, actionable message.
        if (parseIpv6Literal(destHost.removePrefix("[").removeSuffix("]")) != null) {
            throw ProxyException("SOCKS4a can't carry IPv6 destinations ($destHost); use a SOCKS5 proxy for IPv6.")
        }
        // SOCKS4a request:
        //   VN=0x04, CD=0x01(CONNECT), DSTPORT(2), DSTIP(4)=0.0.0.x (x != 0), USERID, 0x00,
        //   then the literal hostname + 0x00 (this trailing host is the 'a' extension that
        //   instructs the proxy to resolve remotely).
        val userId = (cfg.username ?: "").toByteArray(Charsets.US_ASCII)
        val hostBytes = destHost.toByteArray(Charsets.US_ASCII)
        val out = java.io.ByteArrayOutputStream(9 + userId.size + hostBytes.size)
        out.write(0x04)
        out.write(0x01)
        out.write((destPort ushr 8) and 0xFF)
        out.write(destPort and 0xFF)
        // Sentinel 0.0.0.1 — any address of the form 0.0.0.x with x non-zero signals SOCKS4a.
        out.write(0); out.write(0); out.write(0); out.write(1)
        out.write(userId); out.write(0x00)
        out.write(hostBytes); out.write(0x00)
        output.write(out.toByteArray())
        output.flush()

        // Reply: [VN=0x00, CD, DSTPORT(2), DSTIP(4)]; CD 0x5A (90) == granted.
        input.readUnsignedByte() // version (should be 0)
        val cd = input.readUnsignedByte()
        input.skipFully(6) // DSTPORT(2) + DSTIP(4)
        if (cd != 0x5A) {
            throw ProxyException("SOCKS4a: ${socks4ReplyMessage(cd)}")
        }
    }

    private fun socks4ReplyMessage(cd: Int): String = when (cd) {
        0x5B -> "request rejected or failed"
        0x5C -> "request failed: proxy cannot reach identd on the client"
        0x5D -> "request failed: identd could not confirm the user id"
        else -> "CONNECT failed (reply code 0x${cd.toString(16)})"
    }

    /**
     * Read exactly [n] bytes' worth of skips, looping because [InputStream.skip] may skip
     * fewer than requested. Throws EOF if the stream ends early (a truncated reply means a
     * broken proxy, which we surface rather than silently desync the stream).
     */
    private fun InputStream.skipFully(n: Int) {
        var remaining = n.toLong()
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                // skip() can legitimately return 0; fall back to a read to make progress
                // (or detect EOF).
                if (read() < 0) throw IOException("SOCKS: unexpected end of stream in proxy reply")
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }
}
