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
import com.boxlabs.hexdroid.IrcViewModel.ScriptFilePick
import com.boxlabs.hexdroid.IrcViewModel.ScriptLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun IrcViewModel.ageKey(networkId: String, target: String) = "$networkId\u0000${target.lowercase()}"

internal fun IrcViewModel.isChannelTarget(target: String) = target.firstOrNull() in setOf('#', '&', '+', '!')

internal fun IrcViewModel.refreshScripts() { runCatching { _scriptsState.value = scriptManager.state() } }

// ---- Scripts UI plumbing (every mutation refreshes the StateFlow) ----
fun IrcViewModel.scriptsUiState(): com.boxlabs.hexdroid.script.ScriptsUiState = scriptManager.state()

/** Every reload drops the scripts that registered launchers and held file tokens. */
internal fun IrcViewModel.beforeScriptReload() {
    _scriptLaunchers.value = emptyList()
    scriptClearMediaTokens()
}

/** "#chan (Network)", or the network name alone for its server buffer. */
internal fun IrcViewModel.scriptPickPlace(network: String?, buffer: String?): String? {
    val netId = network ?: splitKey(_state.value.selectedBuffer).first
    val netName = _state.value.networks.firstOrNull { it.id == netId }?.name
    val name = buffer?.takeIf { it.isNotBlank() }
        ?: splitKey(_state.value.selectedBuffer).second.takeIf { network == null }
    return when {
        name.isNullOrBlank() || name == "*server*" -> netName
        netName == null -> name
        else -> "$name ($netName)"
    }
}

/** Forget every handed-out file token (script reload drops the scripts that held them). */
fun IrcViewModel.scriptClearMediaTokens() {
    scriptMediaTokens.clear()
    scriptPickQuietUntil.clear()
    val cb = scriptPickCallback
    scriptPickCallback = null
    scriptPickOwner = null
    _scriptFilePick.value = null
    cb?.invoke(null)
}

/**
 * Run a script-defined action by name. View-button actions and launchers may be written
 * either as an `alias` (a command) or as an `on SIGNAL:<name>` handler, so try the command
 * first and fall back to raising the signal.
 */
internal fun IrcViewModel.runScriptAction(name: String, args: List<String>) {
    viewModelScope.launch(Dispatchers.Main.immediate) {
        val net = _state.value.activeNetworkId
        val buf = splitKey(_state.value.selectedBuffer).second
        val ran = runCatching {
            scriptEngine.runCommand(name, args.joinToString(" "), net, buf)
        }.getOrDefault(false)
        if (!ran) runCatching {
            scriptEngine.dispatch(
                "SIGNAL:${name.uppercase()}",
                com.boxlabs.hexdroid.script.EventData(network = net ?: "", buffer = buf, args = args),
            )
        }
    }
}

/**
 * Raise a live IRC event to scripts (`on JOIN`, `on PART`, ...). Only once the script engine
 * exists: an event never starts it. Handlers run synchronously, like `on TEXT`.
 */
internal fun IrcViewModel.scriptEvent(
    name: String,
    netId: String,
    buffer: String,
    nick: String? = null,
    text: String = "",
    isMe: Boolean = false,
    isPrivate: Boolean = false,
    fields: Map<String, String> = emptyMap(),
) {
    if (!scriptEngineDelegate.isInitialized()) return
    runCatching {
        scriptEngine.dispatch(
            name,
            com.boxlabs.hexdroid.script.EventData(
                network = netId, buffer = buffer, from = nick, text = text,
                isPrivate = isPrivate, isMine = isMe, fields = fields,
            ),
        )
    }
}

/** True when [nick] is our current nick on [netId], under the network's case mapping. */
internal fun IrcViewModel.isMyNick(netId: String, nick: String?): Boolean {
    if (nick.isNullOrEmpty()) return false
    val st = _state.value
    val my = st.connections[netId]?.myNick ?: runtimes[netId]?.myNick ?: st.myNick
    return casefoldText(netId, nick) == casefoldText(netId, my)
}

/** The app came to the front or went to the background: scripts get APPACTIVE. */
fun IrcViewModel.onAppActiveChanged(active: Boolean) {
    if (appActiveForScripts == active) return
    appActiveForScripts = active
    onAutoAwayAppActive(active)
    scriptEvent("APPACTIVE", _state.value.activeNetworkId.orEmpty(), "", fields = mapOf("appactive" to active.toString()))
}

// ---- script `age.*` transport (loopback) -------------------------------------------------
// Makes scripted games actually play: a script's own `age.send`/`age.seal` are
// delivered back into the script as `age_msg` / `age_deal` events, so the local client's moves
// take effect immediately. `age.local <from> ..` injects an event as another seat (practice bots).
// Real encrypted multiplayer routes the same events through AgeScriptCapabilities + IRC instead
internal fun IrcViewModel.scriptMe(): String {
    val p = ageP
    if (p != null) {
        val me = runCatching { ageIdentityStore.loadOrCreate(p) }.getOrNull()
        if (me != null) return runCatching {
            com.boxlabs.hexdroid.crypto.AgeFingerprint.hex(
                com.boxlabs.hexdroid.crypto.AgeFingerprint.of(p, me.publicBundle()))
        }.getOrDefault(_state.value.myNick)
    }
    return _state.value.myNick
}

fun IrcViewModel.scriptAgeMe(): String = scriptMe()

/**
 * True if [channel] is a channel buffer the user is actually in on some network. This is the
 * gate for the +AGE bridge: real (joined) channels transmit over IRC; the practice loopback
 * channel (e.g. #practice, never joined) is skipped so the local game still runs via age.local.
 */
internal fun IrcViewModel.scriptInChannel(channel: String): Boolean {
    if (channel.isBlank()) return false
    return _state.value.buffers.keys.any { k ->
        val (net, name) = splitKey(k)
        name.equals(channel, ignoreCase = true) && isChannelOnNet(net, name)
    }
}

fun IrcViewModel.scriptAgeRand(n: Int): String {
    val bytes = ageP?.randomBytes(n)
        ?: ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }
    return bytes.joinToString("") { "%02x".format(it) }
}

fun IrcViewModel.scriptAgeSha(s: String): String {
    val d = ageP?.sha256(s.encodeToByteArray())
        ?: java.security.MessageDigest.getInstance("SHA-256").digest(s.encodeToByteArray())
    return d.joinToString("") { "%02x".format(it) }
}

/** Host a real encrypted table for [members] = (nick, fpHex) on [channel] (mints + shares K_G). */
fun IrcViewModel.scriptHostTable(channel: String, members: List<Pair<String, String>>) =
    ageBridge?.hostTable(channel, members) ?: Unit

/** Inbound tap: route an `AGE …` wire line to [netId]'s bridge; returns true if consumed. */
fun IrcViewModel.onAgeWireLine(netId: String?, channel: String, from: String, line: String): Boolean =
    ageBridgeFor(netId)?.onAgeLine(channel, from, line) ?: false

/** True if [text] is one of the bridge's wire verbs, so it must never be shown as chat. */
internal fun IrcViewModel.isAgeProtocolVerb(text: String): Boolean =
    text.substringAfter("AGE ", "").substringBefore(' ') in
        setOf("IDENT", "MSG", "CHAT", "DEAL", "INVITE", "REKEY", "HELLO", "ACK", "PM", "FRAG")

internal fun IrcViewModel.raiseAgeMsg(from: String, tokens: List<String>, chan: String = "") =
    dispatchAge(
        "SIGNAL:AGE_MSG",
        if (chan.isNotEmpty()) mapOf("from" to from, "chan" to chan) else mapOf("from" to from),
        tokens,
    )

internal fun IrcViewModel.raiseAgeDeal(from: String, data: String) =
    dispatchAge("SIGNAL:AGE_DEAL", mapOf("from" to from, "data" to data), emptyList())

internal fun IrcViewModel.dispatchAge(event: String, fields: Map<String, String>, args: List<String>, netId: String? = null) {
    runCatching {
        scriptEngine.dispatch(
            event,
            com.boxlabs.hexdroid.script.EventData(
                network = netId ?: _state.value.activeNetworkId ?: "",
                buffer = _state.value.selectedBuffer,
                fields = fields, args = args,
            ),
        )
    }
}

/** Deliver a decrypted inbound +AGE channel message into the chat buffer (shown with a padlock). */
internal fun IrcViewModel.appendIncomingAgeChat(channel: String, fromNick: String, text: String, netId: String? = null) {
    val net = netId ?: _state.value.activeNetworkId ?: return
    append(bufKey(net, channel), from = fromNick, text = text,
           encryption = com.boxlabs.hexdroid.crypto.E2eScheme.AGE)
}

/**
 * The bridge has flushed [peer]'s held PM queue to the wire, so any local echoes we drew as
 * pending are now genuinely sent: clear their pending flag. Keyed to [netId]'s PM buffer, since
 * each bridge is per-network. Mirrors the in-place message-edit pattern used elsewhere.
 */
internal fun IrcViewModel.markAgePmDelivered(netId: String, peer: String) {
    val key = bufKey(netId, peer)
    _state.update { st ->
        val buf = st.buffers[key] ?: return@update st
        if (buf.messages.none { it.pending }) return@update st
        val newLog = buf.log.mapMessages { if (it.pending) it.copy(pending = false) else it }
        st.copy(buffers = st.buffers + (key to buf.copy(log = newLog)))
    }
}

/**
 * Per-network +AGE bridges: one [AgeScriptBridge] per netId, sending, delivering, dispatching
 * and reading our nick on that network. A failed build isn't cached: if the identity isn't
 * ready yet (an inbound AGE line during startup or a CHATHISTORY replay), this returns null and
 * the next access retries.
 */
internal fun IrcViewModel.ageBridgeFor(netId: String?): com.boxlabs.hexdroid.script.cap.AgeScriptBridge? {
    val net = netId ?: _state.value.activeNetworkId ?: return null
    ageBridges[net]?.let { return it }
    return synchronized(ageBridges) {
        ageBridges[net] ?: run build@{
            // Each guard must bail out of THIS builder run (return@build), not the inner elvis-RHS
            // run. Without the label, return@run targets the nearest enclosing run (the little
            // logging block), so the elvis just yields null and p/store/me come out nullable.
            val p = ageP ?: run { android.util.Log.w("AGEDBG", "ageBridgeFor($net) NULL: ageP null"); return@build null }
            val store = ageStore ?: run { android.util.Log.w("AGEDBG", "ageBridgeFor($net) NULL: ageStore null"); return@build null }
            val me = runCatching { ageIdentityStore.loadOrCreate(p) }.getOrNull() ?: run { android.util.Log.w("AGEDBG", "ageBridgeFor($net) NULL: identity load failed"); return@build null }
            com.boxlabs.hexdroid.script.cap.AgeScriptBridge(
                p = p,
                me = me,
                store = store,
                sendPrivmsg = { chan, line -> scriptSendRaw(net, "PRIVMSG $chan :$line") },
                raiseSignal = { ev, fields, args -> dispatchAge(ev, fields, args, net) },
                myNick = { scriptNick(net) ?: _state.value.myNick },
                deliverChat = { chan, nick, text -> appendIncomingAgeChat(chan, nick, text, net) },
                onPmFlushed = { peer -> markAgePmDelivered(net, peer) },
                saveOutbox = { blob -> runCatching { repo.secretStore.setAgePmOutbox(net, blob.toByteArray(Charsets.UTF_8)) } },
                loadOutbox = { runCatching { repo.secretStore.getAgePmOutbox(net)?.toString(Charsets.UTF_8) }.getOrNull() },
                debug = { },
                onPmState = { peer, state, detail ->
                    val line = when (state) {
                        "NEGOTIATING" -> "*** " + appContext.getString(R.string.vm_age_negotiating, peer)
                        "ESTABLISHED" -> "*** " + appContext.getString(R.string.vm_age_established, peer)
                        "FAILED"      -> "*** " + appContext.getString(R.string.vm_age_failed, peer, detail)
                        else          -> "*** +AGE with $peer: $state"
                    }
                    append(bufKey(net, peer), from = null, text = line, isLocal = true, doNotify = false)
                },
                onPmInterest = { peer ->
                    append(
                        bufKey(net, peer), from = null,
                        text = "*** " + appContext.getString(R.string.vm_age_offer, peer),
                        isLocal = true, doNotify = false,
                    )
                },
                onIdentConflict = { target, nick, newFp, pinnedFp ->
                    // The key was not pinned and waits for the user. Say so in the buffer
                    // they're looking at, and notify, so a changed key can't pass unnoticed.
                    append(
                        bufKey(net, target), from = null,
                        text = "*** " + appContext.getString(R.string.vm_age_key_warning, nick, newFp.take(16), pinnedFp.take(16)),
                        isLocal = true, doNotify = true,
                    )
                },
            ).also { ageBridges[net] = it }
        }
    }
}

/**
 * Resolve +AGE PMs held for [peer] while the ratchet handshake runs: once it comes up the
 * bridge has flushed them; with no AGE IDENT within the grace the peer has no +AGE client, so
 * self-key and flush; with IDENT but a slow handshake, allow one more grace. [bridge] is
 * captured so a network switch flushes the right one.
 */
internal fun IrcViewModel.scheduleAgePmGrace(netId: String, peer: String, bridge: com.boxlabs.hexdroid.script.cap.AgeScriptBridge?) {
    bridge ?: return
    val key = "$netId\u0000$peer"
    if (!agePmGracePending.add(key)) return
    viewModelScope.launch {
        try {
            // Re-drive the handshake with exponential backoff until the ratchet is up or the
            // attempt cap is reached. A 1:1 PM is never self-keyed on timeout, since the peer
            // couldn't decrypt a key only we hold; the message stays pending until both ends
            // are ready.
            repeat(AGE_PM_MAX_RETRIES) { attempt ->
                delay((AGE_PM_GRACE_MS shl attempt).coerceAtMost(AGE_PM_RETRY_CAP_MS))
                if (bridge.pmReady(peer)) return@launch          // ratchet up: outbox already flushed
                if (!bridge.retryPmHandshake(peer)) return@launch // no longer pending (disabled / failed)
            }
        } finally {
            agePmGracePending.remove(key)
        }
    }
}

internal fun IrcViewModel.scriptNetworkAllowed(url: String, resolve: Boolean): Boolean {
    val u = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
    val scheme = u.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false           // no file:/content:/ftp:/…
    val host = u.host?.lowercase()?.trim('[', ']')?.takeIf { it.isNotEmpty() } ?: return false
    // Deny while a proxied network is live. Checked against networks that actually have a runtime,
    // so an unused proxied profile doesn't disable scripting everywhere. The engine gives us only
    // the URL, not the requesting network, so we cannot narrow this to "the script's network".
    val proxiedLive = _state.value.networks.any { n ->
        n.proxyType != com.boxlabs.hexdroid.connection.ProxyType.NONE && runtimes.containsKey(n.id)
    }
    if (proxiedLive) return false
    if (isLocalOrPrivateHost(host)) return false                       // no device-local or LAN targets
    // Resolve LAST: every check above is decidable from the text, and resolving first would put
    // a plaintext lookup on the wire for a request we refuse anyway - including while proxied.
    if (resolve) {
        val addrs = runCatching { java.net.InetAddress.getAllByName(host) }.getOrNull() ?: return false
        if (addrs.isEmpty()) return false
        if (addrs.any { isPrivateAddress(it) }) return false   // any private answer denies
    }
    return true
}

/**
 * True for hosts a script has no business reaching: the device itself and the local network.
 * Literal addresses only, deliberately: resolving a name here would mean a blocking DNS lookup on
 * the script thread. A name that resolves into private space (DNS rebinding) is therefore NOT
 * caught; closing that needs the check to move next to the socket, after resolution.
 */
internal fun IrcViewModel.isLocalOrPrivateHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
    if (host.contains(':')) {                                          // IPv6 literal
        val h = host.substringBefore('%')
        if (h == "::1" || h == "::") return true
        val p = h.removePrefix("::").lowercase()
        return p.startsWith("fc") || p.startsWith("fd") || p.startsWith("fe80")
    }
    val o = host.split('.')
    if (o.size != 4) return false                                      // not an IPv4 literal
    val b = o.map { it.toIntOrNull() ?: return false }
    if (b.any { it !in 0..255 }) return false
    return when {
        b[0] == 127 || b[0] == 0 -> true                               // loopback / this-host
        b[0] == 10 -> true                                             // 10/8
        b[0] == 192 && b[1] == 168 -> true                             // 192.168/16
        b[0] == 172 && b[1] in 16..31 -> true                          // 172.16/12
        b[0] == 169 && b[1] == 254 -> true                             // link-local (incl. metadata)
        b[0] == 100 && b[1] in 64..127 -> true                         // CGNAT 100.64/10
        else -> false
    }
}

/**
 * True for a RESOLVED address a script must not reach. CGNAT and IPv6 ULA are checked by hand:
 * the JDK covers loopback/any/link-local/IPv4-site-local, but Inet6Address.isSiteLocalAddress
 * only matches the deprecated fec0::/10, not fc00::/7.
 */
internal fun IrcViewModel.isPrivateAddress(a: java.net.InetAddress): Boolean {
    if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress ||
        a.isSiteLocalAddress || a.isMulticastAddress
    ) return true
    val raw = a.address
    if (raw.size == 4) {
        val o0 = raw[0].toInt() and 0xff
        val o1 = raw[1].toInt() and 0xff
        if (o0 == 100 && o1 in 64..127) return true                    // CGNAT 100.64/10
        return false
    }
    if (raw.size == 16) {
        val f = raw[0].toInt() and 0xfe
        if (f == 0xfc) return true                                      // ULA fc00::/7
    }
    return false
}

/**
 * Trivial recomposition trigger for the compose-input lock badge: bumped on
 * every key set/clear so UI observers re-read getE2eKeyInfo from a stable
 * snapshot. State-flow-driven so we don't need to plumb a separate event
 * bus or expose the keystore directly to compose.
 */
internal fun IrcViewModel.bumpE2eKeyVersion() {
    _state.update { it.copy(e2eKeyVersion = it.e2eKeyVersion + 1) }
}

fun IrcViewModel.scriptRegisterLauncher(id: String, label: String, command: String) {
    _scriptLaunchers.value = _scriptLaunchers.value.filterNot { it.id == id } + ScriptLauncher(id, label, command)
}

fun IrcViewModel.scriptUnregisterLauncher(id: String) { _scriptLaunchers.value = _scriptLaunchers.value.filterNot { it.id == id } }

/** Called by a script through the host: queue a pick for the UI to prompt about. */
fun IrcViewModel.scriptMediaPick(
    network: String?,
    buffer: String?,
    mimeFilter: String,
    owner: String,
    userInitiated: Boolean,
    onResult: (com.boxlabs.hexdroid.script.ScriptMediaRef?) -> Unit,
) {
    viewModelScope.launch(Dispatchers.Main.immediate) {
        // One at a time: a second request while a prompt is up is refused rather than
        // queued, so a looping script cannot stack dialogs over the app.
        if (_scriptFilePick.value != null) {
            onResult(null)
            return@launch
        }
        val quietUntil = scriptPickQuietUntil[owner] ?: 0L
        if (!userInitiated && System.currentTimeMillis() < quietUntil) {
            onResult(null)
            return@launch
        }
        scriptPickCallback = onResult
        scriptPickOwner = owner
        _scriptFilePick.value = ScriptFilePick(
            id = java.util.UUID.randomUUID().toString(),
            mimeFilter = mimeFilter.ifBlank { "*/*" },
            buffer = scriptPickPlace(network, buffer),
        )
    }
}

/** Resolve [owner]'s token back to what the host needs to read the file. */
fun IrcViewModel.scriptMediaSource(token: String, owner: String): Triple<android.net.Uri, String, String>? {
    val grant = scriptMediaTokens[token]?.takeIf { it.owner == owner } ?: return null
    val uri = grant.uri
    val name = queryDisplayName(uri) ?: "file"
    val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        ?: "application/octet-stream"
    return Triple(uri, name, mime)
}

/**
 * Scratch directory for script uploads, which stage their body before sending it. Emptied
 * on first use in a process, which clears anything a killed upload left behind.
 */
fun IrcViewModel.scriptUploadCacheDir(): java.io.File = synchronized(scriptUploadDirLock) {
    val dir = java.io.File(appContext.cacheDir, "script_upload")
    if (!scriptUploadDirSwept) {
        scriptUploadDirSwept = true
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }
    dir.apply { mkdirs() }
}

fun IrcViewModel.scriptOpenMedia(uri: android.net.Uri): java.io.InputStream? =
    runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()

fun IrcViewModel.scriptMountView(view: com.boxlabs.hexdroid.script.ScriptView) { _scriptView.value = view }

/**
 * Show a transient toast for a script's `toast <text>` statement. Script work runs on the
 * engine thread, so hop to the main thread; [appContext] keeps it safe if no Activity is up.
 */
fun IrcViewModel.scriptToast(message: String) {
    if (message.isBlank()) return
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Read-only display metrics for the script `$screen.*` capability (see HexDroidScriptHost). */
fun IrcViewModel.scriptScreenInfo(key: String): String {
    val cfg = appContext.resources.configuration
    return when (key) {
        "landscape" -> if (cfg.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "1" else "0"
        "width" -> cfg.screenWidthDp.toString()
        "height" -> cfg.screenHeightDp.toString()
        "tv" -> if ((cfg.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) "1" else "0"
        else -> ""
    }
}

/**
 * Single entry point for every script `age.*` call (the host delegates here)
 * encrypts and transmits to peers through [ageBridge]. Solo practice needs no bridge at all.
 */
fun IrcViewModel.scriptAgeCapability(name: String, args: List<String>): String = when (name) {
    "age.me"   -> scriptAgeMe()
    "age.rand" -> scriptAgeRand(args.getOrNull(0)?.toIntOrNull() ?: 16)
    "age.sha"  -> scriptAgeSha(args.getOrNull(0) ?: "")
    "age.join" -> { if (scriptInChannel(args.getOrNull(0) ?: "")) ageBridge?.let { it.announceIdent(args.getOrNull(0) ?: ""); it.call("age.join", args) }; "" }
    "age.host" -> { if (scriptInChannel(args.getOrNull(0) ?: "")) ageBridge?.hostTable(args.getOrNull(0) ?: ""); "" }
    "age.send" -> {                                                          // [chan, tokens…]
        // local echo of my own move lands now, tagged with the channel it belongs to so a script
        // (or a game framework) can route it by $chan exactly like an inbound peer message.
        raiseAgeMsg(scriptMe(), args.drop(1), args.getOrNull(0) ?: "")
        if (scriptInChannel(args.getOrNull(0) ?: "")) ageBridge?.call("age.send", args)  // + peers (fail-closed until keyed)
        ""
    }
    "age.close" -> { args.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { ageBridge?.closeGame(it) }; "" }  // drop keys/roster for a closed table
    "age.purge" -> {                                                        // [chan] drop queued flood for a closed table
        val chan = args.getOrNull(0)?.takeIf { it.isNotEmpty() }
        val net = _state.value.activeNetworkId
        if (chan != null && net != null) {
            // Match by PRIVMSG target, not the AGE payload: FRAG hides its gameId inside a base64
            // wrapper, but the outbound line is `PRIVMSG <chan> :AGE (MSG|FRAG) …`, so the target
            // scopes the purge to this channel and never touches another game's / chat's fragments.
            val prefix = "PRIVMSG $chan :AGE "
            runtimes[net]?.client?.purgeOutbound { line ->
                line.startsWith(prefix) &&
                    line.substring(prefix.length).substringBefore(' ').let { it == "MSG" || it == "FRAG" }
            }
        }
        ""
    }
    "age.local" -> { args.firstOrNull()?.let { raiseAgeMsg(it, args.drop(1)) }; "" }  // practice bots (local only)
    "age.seal" -> {                                                         // [fp, c1, c2]
        val fp = args.firstOrNull()
        if (fp == scriptMe()) raiseAgeDeal(fp, args.drop(1).joinToString(" "))         // my hole, locally
        else ageBridge?.takeIf { it.ready(it.activeChannel()) }?.call("age.seal", args) // seal to a peer
        ""
    }
    else -> ageBridge?.call(name, args) ?: ""
}

fun IrcViewModel.scriptEcho(network: String?, buffer: String?, from: String?, text: String) {
    val net = network ?: _state.value.activeNetworkId ?: return
    val buf = buffer ?: _state.value.selectedBuffer.takeIf { it.isNotBlank() } ?: return
    append(bufKey(net, buf), from = from, text = text, doNotify = false)
}

fun IrcViewModel.scriptSendMessage(network: String?, buffer: String, text: String) {
    val net = network ?: _state.value.activeNetworkId ?: return
    val rt = runtimes[net] ?: return
    // Script output is generated rather than typed, so it often runs long.
    viewModelScope.launch {
        runCatching {
            // With echo-message the server's copy shows the line; without it, it is shown here.
            // An +AGE target refuses plain sends, so nothing is shown for one.
            val showLocally = !rt.client.hasCap("echo-message") && !ageOn(net, buffer)
            val key = resolveBufferKey(net, buffer)
            val myNick = _state.value.connections[net]?.myNick ?: _state.value.myNick
            val enc = e2eKeyStore.get(net, buffer)?.scheme
            for (chunk in outgoingChunks(net, buffer, text)) {
                rt.client.privmsg(buffer, chunk)
                if (showLocally) append(key, from = myNick, text = chunk, isLocal = true, encryption = enc)
            }
        }
    }
}

fun IrcViewModel.scriptSendRaw(network: String?, line: String) {
    val net = network ?: _state.value.activeNetworkId
    if (net == null) {
        return
    }
    val rt = runtimes[net]
    if (rt == null) {
        return
    }
    viewModelScope.launch { runCatching { rt.client.sendRaw(line) } }
}

fun IrcViewModel.scriptNick(network: String?): String? {
    val net = network ?: _state.value.activeNetworkId ?: return _state.value.myNick
    return _state.value.connections[net]?.myNick ?: _state.value.myNick
}

fun IrcViewModel.scriptActiveNetwork(): String? = _state.value.activeNetworkId

fun IrcViewModel.scriptActiveBuffer(): String? = _state.value.selectedBuffer.takeIf { it.isNotBlank() }

fun IrcViewModel.scriptSetting(key: String): String? = when (key) {
    "applang", "lang" -> java.util.Locale.getDefault().language.ifBlank { "en" }
    else -> null
}

/**
 * Egress policy for script HTTP (`http.get` / `http.post`). Fails closed. Script HTTP connects
 * directly with local DNS, so while any live network uses a proxy (e.g. Tor), scripts may not
 * make requests at all.
 */
fun IrcViewModel.scriptNetworkAllowed(url: String): Boolean = scriptNetworkAllowed(url, resolve = false)

/**
 * Enforcing check for the HTTP path: resolves the host and tests every address, closing the
 * literal-form gaps in [isLocalOrPrivateHost]. Blocking. Known gap: HttpURLConnection resolves
 * again when connecting.
 */
fun IrcViewModel.scriptNetworkAllowedResolved(url: String): Boolean = scriptNetworkAllowed(url, resolve = true)

fun IrcViewModel.scriptAppCommand(network: String?, buffer: String?, line: String) {
    val cmd = line.trim()
    if (cmd.isEmpty()) return
    // The .hex backend forwards permitted bare verbs here (e.g. "join #chan", "mode #chan +m").
    // sendInput treats a leading '/' as a command and anything else as literal chat, so restore
    // the slash the script author omitted. An explicit '/' from the script is kept as-is.
    val raw = if (cmd.startsWith("/")) cmd else "/$cmd"
    // Run the command against the network and buffer the dispatch came from, not whichever
    // buffer is selected.
    val originKey = if (!network.isNullOrBlank() && !buffer.isNullOrBlank()) bufKey(network, buffer) else null
    if (originKey != null && _state.value.buffers.containsKey(originKey)) {
        sendInput(raw, originKey)
        return
    }
    // No resolvable origin buffer (e.g. a signal raised with no buffer context). Falling back to
    // the selected buffer is only safe when the command belongs to the network being looked at;
    // otherwise drop it rather than fire it into an unrelated network, and say why.
    val active = _state.value.activeNetworkId
    if (!network.isNullOrBlank() && active != null && network != active) {
        android.util.Log.w(
            "IrcViewModel",
            "scriptAppCommand dropped: network=$network active=$active and no origin buffer for '$cmd'"
        )
        return
    }
    sendInput(raw)
}
