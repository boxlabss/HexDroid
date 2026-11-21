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

import androidx.lifecycle.viewModelScope
import com.boxlabs.hexdroid.IrcViewModel.ChannelEventKind
import com.boxlabs.hexdroid.IrcViewModel.Companion.AUTO_JOIN_SWITCH_SUPPRESS_MS
import com.boxlabs.hexdroid.IrcViewModel.NetSupport
import com.boxlabs.hexdroid.connection.ConnectionConstants
import com.boxlabs.hexdroid.data.IgnoreType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** Connection, registration, TLS and server reply events. */
internal fun IrcViewModel.handleServerEvent(netId: String, ev: IrcEvent) {
    when (ev) {
                    is IrcEvent.Status -> {
                        setNetConn(netId) { it.copy(status = ev.text) }
                        // Route through appendConnStatus so rapid retries (every "*** Connecting.."
                        // before the next failure) collapse into "(×N)" instead of stacking
                        // 7–8 deep between two error lines.
                        appendConnStatus(netId, "*** ${ev.text}", from = null, doNotify = false, isHighlight = false)
                    }
                    is IrcEvent.Connected -> {
                        manualDisconnecting.remove(netId)
                        // Do NOT reset reconnectAttempts here, the connection may be dropped
                        // immediately (Z-line, cert error, etc.).  The backoff is only cleared
                        // after STABLE_CONNECTION_MS of uptime (see IrcEvent.Registered).
                        stableConnectionJobs.remove(netId)?.cancel() // cancel any leftover timer
                        runtimes[netId]?.apply { suppressMotd = _state.value.settings.hideMotdOnConnect; manualMotdAtMs = 0L }
                        autoReconnectJobs.remove(netId)?.cancel()
                        setNetConn(netId) {
                            // Clear tlsPinMismatch: a Connected event means the TLS handshake AND
                            // the post-handshake pin check both passed (a mismatch would have thrown
                            // during openSocket and we'd never see Connected). The "Reset & re-pin"
                            // and "Trust this server too" buttons hide on the next render. Stashed
                            // actual-fp is dropped because it's now either in the trust set or has
                            // been superseded by a full reset.
                            it.copy(connecting = false, connected = true, status = appContext.getString(R.string.vm_status_connected_to, ev.server), lagMs = null, tlsPinMismatch = false, tlsPinMismatchActualFp = null, tlsHostnameMismatchIdentities = null)
                        }
                        // Arm the history divider for this network's existing query buffers: bouncer
                        // playback delivers PMs without a JOIN, so the JOIN handler's arming doesn't cover
                        // them. Channels are armed by their replayed JOINs.
                        val chantypes = runtimes[netId]?.support?.chantypes ?: "#&+!"
                        val pmKeys = _state.value.buffers.keys.filter { k ->
                            val (nid, bn) = splitKey(k)
                            nid == netId && !isPseudoBuffer(bn) && (bn.firstOrNull() !in chantypes.toSet())
                        }
                        for (k in pmKeys) chatHistory.armDivider(k, ChatHistoryController.DIVIDER_WINDOW_MS)
                        if (_state.value.activeNetworkId == netId) updateConnectionNotification("Connected")
                        // Burn the hostname upgrade grace on the first connect that gets this far, so it
                        // can only apply to the first attempt after updating and never sits armed.
                        if (_state.value.networks.firstOrNull { it.id == netId }?.tlsHostnameGrace == true) {
                            viewModelScope.launch {
                                runCatching {
                                    repo.updateNetworkProfile(netId) { p -> p.copy(tlsHostnameGrace = false) }
                                }
                            }
                        }
                    }
                    is IrcEvent.LagUpdated -> {
                        if (!AppVisibility.isForeground) {
                            // Backgrounded: skip the startService() IPC call (notification text
                            // never shows lag values) and skip the state write entirely if the
                            // lag value hasn't changed - every PING/PONG would otherwise trigger
                            // a full Compose recomposition for no visible benefit.
                            val current = _state.value.connections[netId]?.lagMs
                            if (current != ev.lagMs) {
                                _state.update { st ->
                                    val old = st.connections[netId] ?: NetConnState()
                                    val newConns = st.connections + (netId to old.copy(lagMs = ev.lagMs))
                                    syncActiveNetworkSummary(st.copy(connections = newConns))
                                }
                            }
                        } else {
                            setNetConn(netId) { it.copy(lagMs = ev.lagMs) }
                        }
                    }
                    is IrcEvent.Disconnected -> {
                        noteDebugError(netId, "Disconnected" + (ev.reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
                        // Only for a connection that was up: a failed connect attempt isn't a disconnect.
                        val wasUp = _state.value.connections[netId]?.connected == true
                        if (wasUp) {
                            scriptEvent("DISCONNECT", netId, "*server*", text = ev.reason.orEmpty(), isMe = true)
                        }
                        replayTallies.remove(netId)?.job?.cancel()
                        rescheduleStsOnClose(netId)
                        ageBridges[netId]?.onConnectionLost()
                        // A disconnect cancels the stability timer so a short-lived session
                        // (dropped before STABLE_CONNECTION_MS) never clears the backoff counter.
                        stableConnectionJobs.remove(netId)?.cancel()
                        val r = ev.reason?.trim()
                        val code = ev.code
                        // Connect failures and mid-stream connection errors render as error lines, without
                        // a tray notification: a routine failure and retry shouldn't ping the user.
                        val isConnectFailureLine = code.stylesAsError
                        val disconnectedLabel = appContext.getString(R.string.vm_status_disconnected)
                        val pretty = when {
                            code == DisconnectCode.USER_QUIT || code == DisconnectCode.EOF -> disconnectedLabel
                            r.isNullOrBlank() -> disconnectedLabel
                            isConnectFailureLine -> r
                            // UNKNOWN: the emitter gave no cause, which only happens for events built
                            // outside IrcClient. Fall back to testing the text.
                            code == DisconnectCode.UNKNOWN && (
                                r.equals("Client disconnect", ignoreCase = true) ||
                                r.equals("EOF", ignoreCase = true) ||
                                r.equals("socket closed", ignoreCase = true)
                            ) -> disconnectedLabel
                            else -> appContext.getString(R.string.vm_disconnected_reason, r)
                        }
                        if (isConnectFailureLine) {
                            appendConnStatus(netId, pretty, from = "ERROR", doNotify = false, isHighlight = false, broadcast = true)
                        } else {
                            appendConnStatus(netId, "*** $pretty", from = null, doNotify = false, isHighlight = false, broadcast = true)
                        }
                        if (wasUp) {
                            if (code == DisconnectCode.USER_QUIT) previousDisconnect.remove(netId)
                            else previousDisconnect[netId] = r?.takeIf { it.isNotBlank() && code != DisconnectCode.EOF } ?: disconnectedLabel
                        }
                        setNetConn(netId) { it.copy(connecting = false, connected = false, status = pretty, lagMs = null) }
                        if (_state.value.activeNetworkId == netId) clearConnectionNotification()
                        cleanupNetworkMaps(netId)

                        // Flap detection counts dead-socket disconnects in the window: READ_TIMEOUT
                        // (SOCKET_READ_TIMEOUT_MS, the common case on mobile), PING_TIMEOUT and
                        // CONNECTION_RESET. Connect-attempt failures don't count, so a server that is
                        // simply down doesn't trip it. A server's own "Closing Link: ... (Ping timeout)"
                        // arrives as SERVER_ERROR and isn't counted; the socket timeouts that follow it
                        // are.
                        val isPingTimeout = code.isDeadSocket
                        if (isPingTimeout) {
                            val now = System.currentTimeMillis()
                            val q = pingTimeoutTimestamps.getOrPut(netId) { ArrayDeque() }
                            q.addLast(now)
                            // Drop events older than the flap window.
                            while (q.isNotEmpty() && now - q.first() > ConnectionConstants.FLAP_WINDOW_MS) {
                                q.removeFirst()
                            }
                            if (q.size >= ConnectionConstants.FLAP_THRESHOLD && !flapPaused.contains(netId)) {
                                markFlapPaused(netId)
                                val serverKey = bufKey(netId, "*server*")
                                append(serverKey, from = null, text = "*** " + appContext.getString(
                                    R.string.vm_flap_paused,
                                    q.size,
                                    ConnectionConstants.FLAP_WINDOW_MS / 60000,
                                ), doNotify = false)
                                setNetConn(netId) { it.copy(status = appContext.getString(R.string.vm_status_unstable_paused, ConnectionConstants.FLAP_WINDOW_MS / 60000)) }
                            }
                        }

                        val wasManual = manualDisconnecting.remove(netId)
                        if (wasManual && !desiredConnected.contains(netId)) return

                        // Don't auto-reconnect if flap detection has paused this network.
                        if (flapPaused.contains(netId)) {
                            setNetConn(netId) { it.copy(status = appContext.getString(R.string.vm_status_unstable_reconnect)) }
                            return
                        }

                        // Failures that won't recover by retrying get a specific message and halt
                        // auto-reconnect via authBlockedReconnect, rather than cycling through backoff
                        // forever.
                        if (netId !in authBlockedReconnect) {
                            // Derived by IrcClient.errorCode() from the exception chain, which stays
                            // English whatever the locale.
                            val tlsUnrecoverable = code == DisconnectCode.TLS_UNRECOVERABLE
                            // "Server doesn't exist" class: the hostname doesn't resolve, or it
                            // resolves but nothing is listening on the configured port.
                            val hostUnreachable = code == DisconnectCode.HOST_UNREACHABLE
                            // Server refused the connection (typically `ERROR :Closing Link: <addr>
                            // (<reason>)` then close). "Closing Link" alone isn't enough, since ping
                            // timeouts use it too, so match reasons that would recur on reconnect: SASL
                            // required, K/G/Z/D/X-lines, bad password, connection limits, access denied.
                            // These are the server's own words, so this stays a text match.
                            val lowerR = (r ?: "").lowercase()
                            val recentServerErrorText = lastServerErrorByNet[netId]?.let { (msg, ts) ->
                                if (System.currentTimeMillis() - ts < SERVER_ERROR_DISCONNECT_CORRELATION_MS)
                                    msg.lowercase()
                                else null
                            }
                            fun anyMatches(vararg needles: String): Boolean = needles.any { n ->
                                lowerR.contains(n) || (recentServerErrorText?.contains(n) ?: false)
                            }
                            val serverRejection =
                                // SASL-required class
                                anyMatches(
                                    "sasl required", "sasl needed", "sasl is required",
                                    "you must authenticate", "authentication required",
                                ) ||
                                // *-lined: hyphenated and concatenated forms
                                anyMatches(
                                    "k-lined", "klined",
                                    "g-lined", "glined",
                                    "z-lined", "zlined",
                                    "d-lined", "dlined",
                                    "x-lined", "xlined",
                                    "you are banned",
                                    "banned from this server", "banned from server",
                                ) ||
                                // bad password via raw ERROR
                                anyMatches(
                                    "bad password", "password incorrect",
                                    "password mismatch", "invalid password",
                                ) ||
                                // connection-limit
                                anyMatches(
                                    "too many connections", "too many clients", "too many users",
                                    "connection limit", "max clients",
                                ) ||
                                // access-denied
                                anyMatches("access denied", "not authorized", "not authorised")
                            if (tlsUnrecoverable) {
                                authBlockedReconnect.add(netId)
                                appendConnStatus(
                                    netId = netId,
                                    text = "*** " + appContext.getString(R.string.vm_halted_tls),
                                    from = null,
                                    doNotify = false,
                                    isHighlight = false,
                                    broadcast = true,
                                )
                                setNetConn(netId) { it.copy(status = appContext.getString(R.string.vm_status_tls_halted)) }
                                return
                            }
                            // Only treat host-unreachable as a permanent halt if we never managed
                            // to register on this server this session. A network that DID register
                            // has a known-good host/port, so a sudden "unable to resolve host" /
                            // "connection refused" is almost always transient.
                            if (hostUnreachable && !everRegisteredThisSession.contains(netId)) {
                                authBlockedReconnect.add(netId)
                                appendConnStatus(
                                    netId = netId,
                                    text = "*** " + appContext.getString(R.string.vm_halted_unreachable),
                                    from = null,
                                    doNotify = false,
                                    isHighlight = false,
                                    broadcast = true,
                                )
                                setNetConn(netId) { it.copy(status = appContext.getString(R.string.vm_status_unreachable_halted)) }
                                return
                            }
                            if (serverRejection) {
                                authBlockedReconnect.add(netId)
                                appendConnStatus(
                                    netId = netId,
                                    text = "*** " + appContext.getString(R.string.vm_halted_rejected),
                                    from = null,
                                    doNotify = false,
                                    isHighlight = false,
                                    broadcast = true,
                                )
                                setNetConn(netId) { it.copy(status = appContext.getString(R.string.vm_status_rejected_halted)) }
                                return
                            }

                        }

                        if (desiredConnected.contains(netId)) scheduleAutoReconnect(netId)
                    }
                    is IrcEvent.RawLine -> {
                        append(
                            bufKey(netId, RAW_BUFFER),
                            from = null,
                            text = (if (ev.outgoing) ">> " else "<< ") + ev.line,
                            isLocal = true,
                            doNotify = false,
                        )
                    }
                    is IrcEvent.SendResolved -> resolveOwnSends(netId, ev.ids, ev.failed)
                    is IrcEvent.MultilineSendFailed -> {
                        val key = ev.target?.let { bufKey(netId, it) }
                        if (ev.sendIds.isNotEmpty()) {
                            resolveOwnSends(netId, ev.sendIds, failed = true)
                        } else if (key != null) {
                            _state.update { st ->
                                val buf = st.buffers[key] ?: return@update st
                                val me = st.connections[netId]?.myNick ?: st.myNick
                                val idx = buf.messages.indexOfLast { it.from == me && !it.failed }
                                if (idx < 0) return@update st
                                val updated = buf.messages[idx].copy(failed = true)
                                st.copy(buffers = st.buffers + (key to buf.copy(log = buf.log.replaceAt(idx, updated))))
                            }
                        }
                        append(
                            key ?: bufKey(netId, "*server*"),
                            from = null,
                            doNotify = false,
                            text = "*** " + appContext.getString(R.string.vm_multiline_rejected, ev.code, ev.reason),
                        )
                    }
                    is IrcEvent.Error -> {
                        val msg = ev.message
                        noteDebugError(netId, msg)
                        // Transient errors (connect failures, timeouts, resets, short Closing Link drops)
                        // don't notify or bump highlights; anything else is a real error. Either way
                        // repeats are deduplicated. Uses the emitter's transient flag where set, otherwise
                        // the text.
                        val lower = msg.lowercase()
                        val isTransient = ev.transient ?: (
                            lower.contains("read timed out") ||
                            lower.contains("ping timeout") ||
                            lower.contains("connection reset") ||
                            lower.contains("broken pipe") ||
                            lower.contains("closing link") ||
                            lower.contains("socket closed") ||
                            lower.contains("network is unreachable") ||
                            lower.contains("software caused connection abort")
                        )
                        appendConnStatus(
                            netId = netId,
                            text = msg,
                            from = "ERROR",
                            isHighlight = !isTransient,
                            doNotify = !isTransient,
                            isError = true,
                        )
                    }
                    is IrcEvent.AuthFailed -> {
                        noteDebugError(netId, "Auth failed (${ev.source}): ${ev.reason}")
                        /**
                         * Auth failures:
                         *   PASS (464): the server rejects the connection; halt auto-reconnect.
                         *   SASL on a direct server: only authentication fails and the session continues;
                         *     warn without halting.
                         *   SASL on a bouncer: the bouncer drops the connection without SASL; halt as for
                         *     PASS.
                         */
                        val profile = _state.value.networks.firstOrNull { it.id == netId }
                        val isBouncerProfile = profile?.isBouncer == true
                        val isPassFailure = ev.source.equals("PASS", ignoreCase = true)
                        val isSaslFailure = ev.source.equals("SASL", ignoreCase = true)
                        // soju's FAIL * ACCOUNT_REQUIRED: no credentials were given at all.
                        val isAccountRequired = ev.source.equals("ACCOUNT_REQUIRED", ignoreCase = true)
                        val shouldHalt = isPassFailure || isAccountRequired || (isSaslFailure && isBouncerProfile)
                        if (shouldHalt) {
                            authBlockedReconnect.add(netId)
                        }
                        val hint = when {
                            isPassFailure -> appContext.getString(R.string.vm_auth_pass_rejected)
                            isAccountRequired -> appContext.getString(R.string.vm_auth_account_required)
                            isSaslFailure && isBouncerProfile -> appContext.getString(R.string.vm_auth_sasl_bouncer)
                            isSaslFailure -> appContext.getString(R.string.vm_auth_sasl_rejected)
                            else -> appContext.getString(R.string.vm_auth_rejected)
                        }
                        val haltSuffix = if (shouldHalt)
                            " " + appContext.getString(R.string.vm_auth_halt_suffix)
                        else ""
                        // EXTERNAL with the network's own certificate: a reinstall, restore or data clear
                        // makes a new one, which the account doesn't know yet.
                        val netCertHint = if (isSaslFailure && profile != null && profile.saslMechanism == SaslMechanism.EXTERNAL &&
                            profile.tlsClientCertId == null && profile.autoClientCert
                        ) " " + appContext.getString(R.string.vm_auth_sasl_netcert) else ""
                        appendConnStatus(
                            netId = netId,
                            text = "*** ${ev.reason}: $hint$haltSuffix$netCertHint",
                            from = "AUTH",
                            isHighlight = false,
                            doNotify = false,
                            broadcast = true,
                        )
                    }

                    is IrcEvent.TlsFingerprintLearned -> {
                        // First time we see this server's TLS certificate - persist the fingerprint so
                        // future connections use TOFU pinning instead of trusting all certs blindly.
                        val fp = ev.fingerprint
                        viewModelScope.launch {
                            try {
                                repo.updateNetworkProfile(netId) { it.copy(tlsTofuFingerprint = fp) }
                                append(
                                    bufKey(netId, "*server*"), from = null,
                                    text = "*** " + appContext.getString(R.string.vm_tls_tofu_pinned, fp),
                                    doNotify = false
                                )
                            } catch (t: Throwable) {
                                append(bufKey(netId, "*server*"), from = null,
                                    text = "*** " + appContext.getString(R.string.vm_tls_persist_fail, t.message), doNotify = false)
                            }
                        }
                    }

                    is IrcEvent.TlsHostnameMismatch -> {
                        val sansStr0 = if (ev.sans.isEmpty()) "(none)" else ev.sans.joinToString(", ")
                        val profile = _state.value.networks.firstOrNull { it.id == netId }
                        // Hostname grace for profiles created before hostname checking existed
                        // (tlsHostnameGrace is set only when the stored JSON lacks the key). A mismatch is
                        // accepted once, recorded in tlsAcceptedIdentities where the user can see and
                        // revoke it, and retried, so the user gets a notice rather than a dead network. It
                        // fires at most once, is cleared by the first successful connect, and never applies
                        // to newer profiles.
                        if (profile != null && profile.tlsHostnameGrace &&
                            profile.tlsAcceptedIdentities.isEmpty() && ev.sans.isNotEmpty() &&
                            !hasActiveStsPolicy(profile.host)
                        ) {
                            viewModelScope.launch {
                                runCatching {
                                    repo.updateNetworkProfile(netId) {
                                        it.copy(tlsAcceptedIdentities = ev.sans.toSet(), tlsHostnameGrace = false)
                                    }
                                }
                                connectNetwork(netId, force = true, clearAuthBlock = true)
                            }
                            append(
                                bufKey(netId, "*server*"), from = "TLS", isHighlight = true,
                                text = "*** " + appContext.getString(R.string.vm_cert_hostname_grace, ev.expected, sansStr0)
                            )
                            return
                        }
                        // Halt auto-reconnect so a retry loop doesn't bury the alert. Cleared on the
                        // next manual reconnect.
                        authBlockedReconnect.add(netId)
                        setNetConn(netId) {
                            it.copy(tlsHostnameMismatchIdentities = ev.sans)
                        }
                        append(
                            bufKey(netId, "*server*"), from = "TLS WARNING", isHighlight = true,
                            text = "⚠️  " + appContext.getString(R.string.vm_cert_hostname_mismatch, ev.expected, sansStr0)
                        )
                    }

                    is IrcEvent.TlsFingerprintChanged -> {
                        // The server presented a certificate we don't trust: a renewal (reset and re-pin)
                        // or round-robin DNS reaching another server ("Trust this server too" adds its
                        // pin). Auto-reconnect halts either way until the next manual reconnect.
                        authBlockedReconnect.add(netId)
                        // Stash the actual fingerprint so NetworkEditScreen can offer the "Trust
                        // this server too" action without re-deriving the value from somewhere.
                        setNetConn(netId) {
                            it.copy(tlsPinMismatch = true, tlsPinMismatchActualFp = ev.actual)
                        }
                        append(
                            bufKey(netId, "*server*"), from = "TLS WARNING", isHighlight = true,
                            text = "⚠️  " + appContext.getString(R.string.vm_cert_changed, ev.stored, ev.actual)
                        )
                    }
                    is IrcEvent.ServerLine -> {
                        val stNow = _state.value
                        if (stNow.settings.loggingEnabled && stNow.settings.logServerBuffer) {
                            val netName = stNow.networks.firstOrNull { it.id == netId }?.name ?: netId
                            val ts = System.currentTimeMillis()
                            val line = ev.line
                            val logLine = formatLogLine(ts, from = null, text = line, isAction = false)
                            val logFolderUri = stNow.settings.logFolderUri
                            // Off the main thread, in the same ordered lane as other log writes.
                            viewModelScope.launch(logLane) {
                                runCatching { logs.append(netName, "*server*", logLine, logFolderUri) }
                            }
                        }
                        // PONG handling and lag measurement are done in IrcCore; LagUpdated events update the UI.
                    }
                    is IrcEvent.ServerText -> {
                        val code = ev.code
                        val rt = runtimes[netId]
                        val motdCodes = setOf("375","372","376","422")
                        val hideMotd = _state.value.settings.hideMotdOnConnect
                        val now = System.currentTimeMillis()
                        val manualMotdActive = rt?.manualMotdAtMs?.let { it != 0L && now - it < 60_000L } == true
                        // Never suppress bouncer MOTD - it contains useful status (e.g. which upstream networks are connected).
                        val isBouncer = _state.value.networks.firstOrNull { it.id == netId }?.isBouncer == true
                        if (!manualMotdActive && hideMotd && !isBouncer && code != null && code in motdCodes) {
                            // Some connect paths can build the runtime before settings are loaded; re-arm suppression here too.
                            if (rt != null && !rt.suppressMotd) rt.suppressMotd = true
                            if (rt?.suppressMotd != false) {
                                // Suppress automatic MOTD output on connect if configured
                                if (code == "376" || code == "422") rt?.suppressMotd = false
                                return
                            }
                        }
                        val targetKey = if (!ev.bufferName.isNullOrBlank() && ev.bufferName != "*server*") {
                            resolveBufferKey(netId, ev.bufferName)
                        } else {
                            bufKey(netId, "*server*")
                        }
                        val isMotdLine = code == "372"
                        // Script NUMERIC hook: lets a script rewrite or drop server output before it
                        // is printed, which is how a user filters the WHOIS numerics they don't want.
                        // Only the printing is skipped on halt; the bookkeeping below still runs.
                        val scripted = runCatching {
                            scriptEngine.onNumeric(
                                com.boxlabs.hexdroid.script.NumericEvent(
                                    network = netId,
                                    buffer = splitKey(targetKey).second,
                                    code = code.orEmpty(),
                                    text = ev.text,
                                    isWhoisReply = ev.isWhoisReply,
                                ),
                            )
                        }.getOrNull()
                        if (scripted?.halted != true) {
                            append(
                                targetKey,
                                from = null,
                                text = scripted?.text ?: ev.text,
                                doNotify = false,
                                isMotd = isMotdLine,
                            )
                        }

        if (code == "442") {
            // Not on channel. If this was triggered by the UI close-buffer flow, remove the buffer anyway.
            val chan = Regex("([#&+!][^\\s]+)").find(ev.text)?.groupValues?.getOrNull(1)
            val key = if (chan != null) {
                popPendingCloseForChannel(netId, chan)
            } else {
                pendingCloseAfterPart.firstOrNull { it.startsWith("$netId::") }?.also { pendingCloseAfterPart.remove(it) }
            }

            if (key != null) {
                chanNickCase.remove(key)
                chanNickStatus.remove(key)
                removeBuffer(key)
            }
        }
                        if (code == "376" || code == "422") {
                            // End of MOTD (or no MOTD) - stop suppressing for this session
                            if (rt != null) { rt.suppressMotd = false; rt.manualMotdAtMs = 0L }
                        }
                    }

                    is IrcEvent.JoinError -> {
                        val st = _state.value
                        val chanKey = resolveBufferKey(netId, ev.channel)
                        val dest = when {
                            st.buffers.containsKey(chanKey) -> chanKey
                            splitKey(st.selectedBuffer).first == netId -> st.selectedBuffer
                            else -> bufKey(netId, "*server*")
                        }
                        append(dest, from = null, text = "*** ${ev.message}", doNotify = false)
                    }
                    is IrcEvent.ChannelModeIs -> {
                        val st = _state.value
                        val chanKey = resolveBufferKey(netId, ev.channel)
                        // ev.silent: the ops drawer refreshes modes when it opens, and printing that
                        // reply left a "* Mode #chan +nt" line behind on every open.
                        if (!ev.silent) {
                            val dest = if (st.buffers.containsKey(chanKey)) chanKey else bufKey(netId, "*server*")
                            append(dest, from = null, text = "* " + appContext.getString(R.string.vm_mode_change, ev.channel, ev.modes), doNotify = false)
                        }
                        // Store mode string so Channel Tools can show/toggle modes, and the title bar
                        // can render the parameterised form.
                        val modeOnly = ev.modes.split(" ").firstOrNull() ?: ev.modes
                        _state.update {
                            val buf = it.buffers[chanKey] ?: return@update it
                            it.copy(buffers = it.buffers + (chanKey to buf.copy(
                                modeString = modeOnly,
                                modeDisplay = ev.modes.trim().ifBlank { null },
                            )))
                        }
                    }

                    is IrcEvent.YoureOper -> {
                        append(bufKey(netId, "*server*"), from = null, text = "*** ${ev.message}", doNotify = false)
                        setNetConn(netId) { it.copy(isIrcOper = true) }
                    }
                    is IrcEvent.YoureDeOpered -> {
                        setNetConn(netId) { it.copy(isIrcOper = false) }
                    }

                    is IrcEvent.BanListItem -> {
                        val chanKey = resolveBufferKey(netId, ev.channel)
                        ensureBuffer(chanKey)
                        _state.update { st0 ->
                            val nextList = (st0.banlists[chanKey].orEmpty() + BanEntry(ev.mask, ev.setBy, ev.setAtMs)).distinctBy { it.mask }
                            syncActiveNetworkSummary(
                                st0.copy(
                                    banlists = st0.banlists + (chanKey to nextList),
                                    banlistLoading = st0.banlistLoading + (chanKey to true)
                                )
                            )
                        }

                        // Don't spam the channel buffer with every ban entry.
                        // (Users can view them via the Channel tools -> Ban list UI.)
                    }

                    is IrcEvent.BanListEnd -> {
                        val chanKey = resolveBufferKey(netId, ev.channel)
                        _state.update { st0 ->
                            syncActiveNetworkSummary(st0.copy(banlistLoading = st0.banlistLoading + (chanKey to false)))
                        }
                    }

                    is IrcEvent.QuietListItem -> {
                        val chanKey = resolveBufferKey(netId, ev.channel)
                        ensureBuffer(chanKey)
                        _state.update { st0 ->
                            val nextList = (st0.quietlists[chanKey].orEmpty() + BanEntry(ev.mask, ev.setBy, ev.setAtMs)).distinctBy { it.mask }
                            syncActiveNetworkSummary(
                                st0.copy(
                                    quietlists = st0.quietlists + (chanKey to nextList),
                                    quietlistLoading = st0.quietlistLoading + (chanKey to true)
                                )
                            )
                        }
                    }

                    is IrcEvent.QuietListEnd -> {
                        val chanKey = resolveBufferKey(netId, ev.channel)
                        _state.update { st0 ->
                            syncActiveNetworkSummary(st0.copy(quietlistLoading = st0.quietlistLoading + (chanKey to false)))
                        }
                    }

                    is IrcEvent.ExceptListItem -> {
                        val chanKey = resolveBufferKey(netId, ev.channel)
                        ensureBuffer(chanKey)
                        _state.update { st0 ->
                            val nextList = (st0.exceptlists[chanKey].orEmpty() + BanEntry(ev.mask, ev.setBy, ev.setAtMs)).distinctBy { it.mask }
                            syncActiveNetworkSummary(
                                st0.copy(
                                    exceptlists = st0.exceptlists + (chanKey to nextList),
                                    exceptlistLoading = st0.exceptlistLoading + (chanKey to true)
                                )
                            )
                        }
                    }

                    is IrcEvent.ExceptListEnd -> {
                        val chanKey = resolveBufferKey(netId, ev.channel)
                        _state.update { st0 ->
                            syncActiveNetworkSummary(st0.copy(exceptlistLoading = st0.exceptlistLoading + (chanKey to false)))
                        }
                    }

                    is IrcEvent.InvexListItem -> {
                        val chanKey = resolveBufferKey(netId, ev.channel)
                        ensureBuffer(chanKey)
                        _state.update { st0 ->
                            val nextList = (st0.invexlists[chanKey].orEmpty() + BanEntry(ev.mask, ev.setBy, ev.setAtMs)).distinctBy { it.mask }
                            syncActiveNetworkSummary(
                                st0.copy(
                                    invexlists = st0.invexlists + (chanKey to nextList),
                                    invexlistLoading = st0.invexlistLoading + (chanKey to true)
                                )
                            )
                        }
                    }

                    is IrcEvent.InvexListEnd -> {
                        val chanKey = resolveBufferKey(netId, ev.channel)
                        _state.update { st0 ->
                            syncActiveNetworkSummary(st0.copy(invexlistLoading = st0.invexlistLoading + (chanKey to false)))
                        }
                    }
                    is IrcEvent.ISupport -> {
                        val rt = runtimes[netId]
                        if (rt != null) {
                            rt.support = NetSupport(
                                chantypes = ev.chantypes,
                                caseMapping = ev.caseMapping,
                                prefixModes = ev.prefixModes,
                                prefixSymbols = ev.prefixSymbols,
                                statusMsg = ev.statusMsg,
                                chanModes = ev.chanModes,
                                linelen = ev.linelen,
                                elist = ev.elist
                            )
                        }

                        // Surface server-side LIST user-count filtering ("LIST >N") to the channel-list UI
                        if (netId == _state.value.activeNetworkId) {
                            _state.update { it.copy(listElistUserFilter = ev.elist?.contains('U') == true) }
                        }

                        // Expose list modes to the UI so the Channel lists sheet can adapt per-ircd.
                        val listModes = ev.chanModes
                            ?.split(',')
                            ?.getOrNull(0)
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                        if (listModes != null) {
                            setNetConn(netId) { it.copy(listModes = listModes) }
                        }

                        // EXTBAN / ACCOUNTEXTBAN: surface extended-ban syntax to the ban editor.
                        setNetConn(netId) {
                            it.copy(
                                extbanPrefix = ev.extbanPrefix,
                                extbanTypes = ev.extbanTypes,
                                accountExtban = ev.accountExtban,
                                chanTypes = ev.chantypes,
                                prefixModes = ev.prefixModes,
                                prefixSymbols = ev.prefixSymbols,
                            )
                        }

                        noteBouncerNetId(netId, ev.bouncerNetId)

                        // MONITOR support is known once ISUPPORT arrives after registration.
                        runtimes[netId]?.client?.takeIf { it.registered }?.let { cl ->
                            setNetConn(netId) { it.copy(monitorSupported = cl.monitorLimit != -1) }
                        }
                        maybeSendNotifyList(netId)

                        // Surface the filehost upload endpoint (soju.im/FILEHOST) to the UI so
                        // ChatScreen can offer the attach button. Assigned unconditionally: a
                        // later 005 without the token must not leave a stale URL behind. The
                        // per-network toggle hides it entirely when uploads are disabled.
                        val filehostAllowed = runtimes[netId]?.client?.config?.capPrefs?.filehostUploads != false
                        setNetConn(netId) { it.copy(
                            filehostUrl = if (filehostAllowed) ev.filehostUrl else null,
                            // ICON / draft/ICON ISUPPORT token: raw ICON URL; display gating (previews opt-in,
                            // https-only, unproxied profile) lives in NetworksScreen.
                            networkIconUrl = ev.networkIconUrl,
                        ) }
                    }

                    is IrcEvent.StsReceived -> viewModelScope.launch {
                        ensureStsPoliciesLoaded()
                        val hostKey = ev.host.trim().lowercase()
                        if (ev.secure) {
                            // Persist/refresh (or delete) the policy - durations are only
                            // trustworthy over TLS. A port key on a secure connection is
                            // ignored per spec. The stored TLS port is the one this secure
                            // connection is actually using, so a plaintext-configured profile
                            // knows where to go next time.
                            val dur = ev.durationSec
                            if (dur != null) {
                                if (dur == 0L) {
                                    if (stsPolicies.remove(hostKey) != null) {
                                        persistStsPolicies()
                                        append(bufKey(netId, "*server*"), from = null,
                                            text = "*** " + appContext.getString(R.string.vm_sts_cleared, hostKey),
                                            doNotify = false)
                                    }
                                } else {
                                    val prev = stsPolicies[hostKey]
                                    val securePort = runtimes[netId]?.client?.config
                                        ?.takeIf { it.useTls }?.port
                                    stsPolicies[hostKey] = StsPolicyEntry(
                                        port = prev?.port ?: securePort,
                                        expiresAtMs = System.currentTimeMillis() + dur * 1000L,
                                        durationSec = dur,
                                    )
                                    persistStsPolicies()
                                    if (prev == null) {
                                        append(bufKey(netId, "*server*"), from = null,
                                            text = "*** " + appContext.getString(R.string.vm_sts_stored, hostKey, dur),
                                            doNotify = false)
                                    }
                                }
                            }
                        } else if (ev.port != null) {
                            // Insecure connection advertising an upgrade port: abandon this
                            // plaintext attempt immediately and retry over TLS on that port.
                            // The policy itself is persisted only once the secure connection
                            // confirms it with a duration.
                            val upgradePort: Int = ev.port
                            append(bufKey(netId, "*server*"), from = null,
                                text = "*** " + appContext.getString(R.string.vm_sts_upgrade, upgradePort),
                                doNotify = false)
                            stsUpgradePorts[netId] = upgradePort
                            withNetLock(netId) {
                                val oldRt = runtimes.remove(netId)
                                runCatching { oldRt?.client?.forceClose() }
                                runCatching { oldRt?.job?.cancel() }
                            }
                            connectNetwork(netId, force = true)
                        }
                        Unit
                    }

                    is IrcEvent.Registered -> {
                        scriptEvent("CONNECT", netId, "*server*", ev.nick, isMe = true)
                        runtimes[netId]?.myNick = ev.nick
                        recordOwnNick(netId, ev.nick)
                        val rt0 = runtimes[netId]
                        val hasReact = rt0 != null &&
                            (rt0.client.hasCap("message-tags") || rt0.client.hasCap("draft/message-reactions"))
                        val hasRedact = rt0 != null &&
                            (rt0.client.hasCap("draft/message-redaction") || rt0.client.hasCap("message-redaction"))
                        setNetConn(netId) { it.copy(myNick = ev.nick, hasReactionSupport = hasReact, hasRedactionSupport = hasRedact) }
                        _state.value.buffers.keys.filter { it.startsWith("$netId::") }
                            .forEach { requestQueryReadMarker(netId, splitKey(it).second) }
                        // Re-send our own metadata so display name / avatar / colour etc. survive a
                        // reconnect even on servers that don't persist metadata across sessions.
                        reapplyOwnMetadata(netId)
                        maybeSendNotifyList(netId)
                        maybeApplyAutoAway(netId)
                        append(bufKey(netId, "*server*"), from = null, text = "*** " + appContext.getString(R.string.vm_registered_as, ev.nick), doNotify = false)
                        // After a reconnect (backoff attempts > 0), announce it in every channel and query
                        // buffer. Done before the dedup reset.
                        val wasReconnect = (reconnectAttempts[netId] ?: 0) > 0
                        if (wasReconnect) {
                            appendConnStatus(
                                netId = netId,
                                text = "*** " + appContext.getString(R.string.vm_reconnected),
                                from = null,
                                doNotify = false,
                                isHighlight = false,
                                broadcast = true,
                            )
                        }

                        openReplayTally(netId)

                        // Successful registration is the natural reset point for connection-status
                        // dedup: if the connection drops AGAIN for the same reason after this, the
                        // user has just been online long enough to care about seeing it logged.
                        resetConnStatusDedup(netId)

                        // Start the stability timer.  Only once this fires do we consider the
                        // connection stable enough to reset the exponential backoff counter.
                        stableConnectionJobs.remove(netId)?.cancel()
                        stableConnectionJobs[netId] = viewModelScope.launch {
                            delay(ConnectionConstants.STABLE_CONNECTION_MS)
                            reconnectAttempts.remove(netId)
                            stableConnectionJobs.remove(netId)
                        }

                        val rt = runtimes[netId] ?: return
                        val profile = _state.value.networks.firstOrNull { it.id == netId }

                        // On any reconnect (automatic, manual, or disconnect then connect), the rejoins and
                        // any bouncer-replayed JOINs arrive as self-JOIN echoes; suppress the automatic
                        // buffer switch for that window so the user stays where they were. Keyed off
                        // "registered before in this session" rather than the backoff counter, which a
                        // manual reconnect clears. A first connect doesn't arm it, so autojoin still opens
                        // a channel.
                        val firstRegistrationThisSession = everRegisteredThisSession.add(netId)
                        if (!firstRegistrationThisSession) {
                            rt.suppressAutoJoinSwitchUntilMs =
                                System.currentTimeMillis() + AUTO_JOIN_SWITCH_SUPPRESS_MS
                        }

                        // soju bouncer-networks: proactively request the upstream network list on every
                        // registration when we actually negotiated soju.im/bouncer-networks when not bound to an upstream
                        if (profile?.bouncerKind == BouncerKind.SOJU &&
                            rt.client.hasCap("soju.im/bouncer-networks")) {
                            refreshBouncerNetworks(netId)
                        }

                        // A reconnect is a new session for backfill purposes: the buffers we hold may
                        // have gaps the server can fill, and the exhausted flags were about the
                        // previous connection's view of history.
                        clearHistoryStateFor(netId)

                        // Ask which buffers the server has stored history for
                        if (rt.client.supportsChatHistory()) {
                            viewModelScope.launch {
                                runCatching { rt.client.requestChatHistoryTargets() }
                            }
                        }

                        // Hand this server our push endpoint, so it can reach us once this
                        // connection goes away.
                        maybeRegisterWebPush(netId)

                        viewModelScope.launch {
                            // Service auth command (e.g. /msg NickServ IDENTIFY password)
                            //    Runs first, before autojoin, so channels with +r can be joined.
                            profile?.serviceAuthCommand?.takeIf { it.isNotBlank() }?.let { cmd ->
                                val trimmed = cmd.trim()
                                if (trimmed.startsWith("/")) {
                                    // Client command aliases
                                    rt.client.handleSlashCommand(trimmed.drop(1), "*server*")
                                } else {
                                    // Raw IRC line
                                    rt.client.sendRaw(trimmed)
                                }
                            }

                            // 3. Optional delay before autojoin & commands
                            //    Gives services time to identify/cloak before joining channels.
                            val delaySec = profile?.autoCommandDelaySeconds ?: 0
                            if (delaySec > 0) {
                                append(bufKey(netId, "*server*"), from = null,
                                    text = "*** " + appContext.getString(R.string.vm_waiting_autojoin, delaySec), doNotify = false)
                                delay(delaySec * 1000L)
                            }

                            // 4. Autojoin channels (skipped for bouncers - they keep you joined server-side)
                            if (!rt.client.config.isBouncer) {
                                val aj = rt.client.config.autoJoin
                                for (c in aj) {
                                    val join = if (c.key.isNullOrBlank()) "JOIN ${c.channel}" else "JOIN ${c.channel} ${c.key}"
                                    rt.client.sendRaw(join)
                                }

                                // Rejoin channels the user joined manually (outside autoJoin) that were
                                // lost when the connection dropped.
                                for ((chan, key) in rt.manuallyJoinedChannels.toMap()) {
                                    val join = if (key.isNullOrBlank()) "JOIN $chan" else "JOIN $chan $key"
                                    rt.client.sendRaw(join)
                                }
                            }
                            pendingUriJoins.remove(netId)?.forEach { c ->
                                rt.client.sendRaw(if (c.key.isNullOrBlank()) "JOIN ${c.channel}" else "JOIN ${c.channel} ${c.key}")
                            }

                            // 5. Post-connect commands (one per line, like mIRC's Perform)
                            //    Supports both /slash commands and raw IRC lines.
                            //    Per-command delay: append  ;wait N  or  ;wait Ns  to a line
                            //    (e.g. "/msg NickServ identify pass ;wait 3") to pause N seconds
                            //    after that command before sending the next one.
                            profile?.autoCommandsText?.takeIf { it.isNotBlank() }?.let { text ->
                                // Trim each line so that accidental leading/trailing whitespace does not
                                // cause commands to be sent verbatim with a leading space (silent failure).
                                val waitRegex = Regex("""\s*;wait\s+(\d+)s?\s*$""", RegexOption.IGNORE_CASE)
                                val commands = text.lines()
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                for (rawLine in commands) {
                                    // Extract optional ;wait N suffix before sending.
                                    val waitMatch = waitRegex.find(rawLine)
                                    val waitMs = waitMatch?.groupValues?.get(1)?.toLongOrNull()
                                        ?.coerceIn(1L, 300L)?.times(1000L) ?: 0L
                                    val cmd = if (waitMatch != null) rawLine.substring(0, waitMatch.range.first).trim()
                                              else rawLine

                                    if (cmd.isNotEmpty()) {
                                        if (cmd.startsWith("/")) {
                                            rt.client.handleSlashCommand(cmd.drop(1), "*server*")
                                        } else {
                                            rt.client.sendRaw(cmd)
                                        }
                                    }

                                    if (waitMs > 0) {
                                        append(bufKey(netId, "*server*"), from = null,
                                            text = "*** " + appContext.getString(R.string.vm_waiting, waitMs / 1000), doNotify = false)
                                        delay(waitMs)
                                    }
                                }
                            }
                        }
                    }
                    is IrcEvent.NickChanged -> {
                        if (!ev.isHistory) scriptEvent(
                            "NICK", netId, "", ev.oldNick,
                            isMe = isMyNick(netId, ev.oldNick) || isMyNick(netId, ev.newNick),
                            fields = mapOf("newnick" to ev.newNick),
                        )
                        val st0 = _state.value
                        val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread

                        val my = st0.connections[netId]?.myNick ?: runtimes[netId]?.myNick ?: st0.myNick
                        val isMe = casefoldText(netId, ev.oldNick) == casefoldText(netId, my)
                        if (!ev.isHistory && !isMe) ageBridges[netId]?.onNickChange(ev.oldNick, ev.newNick)

                        // Show nick changes in-channel:
                        //   * old is now known as new
                        //   * You are now known as new
                        val line = if (isMe) "* " + appContext.getString(R.string.vm_ev_you_now_known_as, ev.newNick)
                        else "* " + appContext.getString(R.string.vm_ev_now_known_as, ev.oldNick, ev.newNick)

                        // Determine which channel buffers to print to.
                        // Prefer channels where we currently see the old nick in the nicklist; otherwise
                        // fall back to all joined channels for this network.
                        val affectedChannels = st0.nicklists
                            .filterKeys { it.startsWith("$netId::") }
                            .filter { (k, list) ->
                                val (_, name) = splitKey(k)
                                isChannelOnNet(netId, name) &&
                                    list.any { parseNickWithPrefixes(netId, it).first.let { b -> casefoldText(netId, b) == casefoldText(netId, ev.oldNick) } }
                            }
                            .map { it.key }

                        val allChannelTargets = st0.buffers.keys
                            .filter { it.startsWith("$netId::") }
                            .filter { key ->
                                val (_, name) = splitKey(key)
                                isChannelOnNet(netId, name)
                            }

                        val targets = when {
                            affectedChannels.isNotEmpty() -> affectedChannels
                            // Member lists describe where they are now, not where they were, so a
                            // replay uses the channel its batch named and nothing else.
                            ev.historyChannel != null -> listOf(resolveBufferKey(netId, ev.historyChannel))
                            ev.isHistory -> emptyList()
                            allChannelTargets.isNotEmpty() -> allChannelTargets
                            else -> emptyList()
                        }

                        val lineColoured = colorEvent(line, 10)  // cyan
                        val nickAt = ev.timeMs ?: System.currentTimeMillis()
                        val oldNickFold = casefoldText(netId, ev.oldNick)
                        // Where the smart filter left out the line but holds their join, the line is
                        // held with it.
                        val shownTargets = targets.filter { k ->
                            val shown = if (isMe) showsEvent(k, ChannelEventKind.NICK)
                                else showsOthersEvent(netId, k, ChannelEventKind.NICK, ev.oldNick, ev.timeMs)
                            if (!shown && !isMe && showsEvent(k, ChannelEventKind.NICK) && smartFilterOn(k) &&
                                !isIgnoredSender(netId, ev.oldNick, IgnoreType.EVENTS)) {
                                smartFilter.holdFollowUp(oldNickFold, SmartFilter.HeldEvent(k, lineColoured, nickAt, ev.isHistory))
                            }
                            shown
                        }
                        smartFilter.renamed("$netId::", oldNickFold, casefoldText(netId, ev.newNick))
                        for (k in shownTargets) {
                            append(
                                k,
                                from = null,
                                text = lineColoured,
                                isLocal = suppressUnread,
                                timeMs = ev.timeMs,
                                isHistory = ev.isHistory,
                                doNotify = false
                            )
                        }

                        // If we couldn't attribute this nick to any channel buffers, surface it in the server buffer.
                        if (targets.isEmpty() && (isMe || (!st0.settings.hideNickChanges && !isIgnoredSender(netId, ev.oldNick, IgnoreType.EVENTS)))) {
                            append(
                                bufKey(netId, "*server*"),
                                from = null,
                                text = lineColoured,
                                isLocal = suppressUnread,
                                timeMs = ev.timeMs,
                                isHistory = ev.isHistory,
                                doNotify = false
                            )
                        }

                        if (!ev.isHistory) {
                            // If it's our nick, update runtime + UI connection state first.
                            if (isMe) {
                                runtimes[netId]?.myNick = ev.newNick
                                recordOwnNick(netId, ev.newNick)
                                setNetConn(netId) { it.copy(myNick = ev.newNick) }
                            }

                            // Re-read state after appends/setNetConn so we don't overwrite newer state.
                            val st1 = _state.value


                            // Update nicklists for this network (multi-status safe).
                            moveNickAcrossChannels(netId, ev.oldNick, ev.newNick)

                            // Transfer away state from old nick to new nick.
                            val awayMap = nickAwayState[netId]
                            if (awayMap != null) {
                                val oldFold = casefoldText(netId, ev.oldNick)
                                val newFold = casefoldText(netId, ev.newNick)
                                awayMap.remove(oldFold)?.let { awayMap[newFold] = it }
                            }

                            // Rebuild from the membership maps, not only the lists already built, so every
                            // channel the nick is in shows the new nick.
                            val rebuilt = chanNickCase.keys
                                .filter { it.startsWith("$netId::") }
                                .associateWith { rebuildNicklist(netId, it) }
                            val updatedNicklists = st1.nicklists + rebuilt

                            // Drop the old nick's typing indicator from all channel buffers on this network.
                            // The new nick hasn't sent a TAGMSG typing event yet, so don't carry it over.
                            val updatedBufs = st1.buffers.mapValues { (k, buf) ->
                                if (k.startsWith("$netId::") && ev.oldNick in buf.typingNicks)
                                    buf.copy(typingNicks = buf.typingNicks - ev.oldNick)
                                else buf
                            }

                            // Metadata belongs to the person, not the name, so it follows them.
                            // Folded into the value being assembled rather than written separately:
                            // this handler ends with a plain assignment, which would drop it.
                            val movedConns = st1.connections[netId]
                                ?.let { withMetadataMovedForNick(it, ev.oldNick, ev.newNick) }
                                ?.let { c ->
                                    val a = ev.oldNick.lowercase()
                                    val b = ev.newNick.lowercase()
                                    if (a != b && a in c.awayNicks) c.copy(awayNicks = c.awayNicks - a + b) else c
                                }
                            val updatedConns =
                                if (movedConns == null) st1.connections
                                else st1.connections + (netId to movedConns)

                            var next = st1.copy(
                                nicklists = updatedNicklists,
                                buffers = updatedBufs,
                                connections = updatedConns,
                            )

                            // Rename private-message buffer key if present.
                            val oldKey = bufKey(netId, ev.oldNick)
                            val newKey = bufKey(netId, ev.newNick)
                            val hadOld = next.buffers.containsKey(oldKey)
                            val collides = next.buffers.containsKey(newKey)
                            if (hadOld && !collides) {
                                val b = next.buffers[oldKey]
                                if (b != null) next = next.copy(
                                    buffers = (next.buffers - oldKey) + (newKey to b.copy(name = newKey)),
                                    selectedBuffer = if (next.selectedBuffer == oldKey) newKey else next.selectedBuffer
                                )
                            }

                            _state.value = syncActiveNetworkSummary(next)

                            if (hadOld) {
                                renameBufferState(oldKey, newKey)
                                // A conversation with the new nick already open means two buffers for
                                // one person, so fold them together.
                                if (collides) mergeDuplicateBuffers(newKey, listOf(oldKey))
                            }
                        }
                    }
        else -> Unit
    }
}
