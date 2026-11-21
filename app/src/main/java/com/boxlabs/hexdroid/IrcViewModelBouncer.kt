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
import com.boxlabs.hexdroid.data.ChannelPrefsCodec
import com.boxlabs.hexdroid.data.NetworkProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

// Bouncer networks: what soju and ZNC report, importing upstreams as profiles, soju's network
// management, mutes shared through soju.im/muted, and searching soju's history.

/** A bouncer network edit awaiting the bouncer's answer. */
internal class BouncerOp(
    val subcommand: String,
    /** The network acted on; null for ADDNETWORK until the reply names it. */
    val upstreamId: String?,
    /** The network's name, for messages. */
    val label: String,
    /** ADDNETWORK: import the new network as a profile once it exists. */
    val importAfter: Boolean = false,
    /** DELNETWORK: profiles to delete once the bouncer has removed the network. */
    val profileIds: List<String> = emptyList(),
)

/** soju.im/muted values a connection listed before its subscription was confirmed. */
internal class MuteSync {
    @Volatile var done = false
    /** Server value per casefolded target, with the target as sent. */
    val listed = HashMap<String, Pair<String, Boolean>>()
}

/** A bouncer search that a log search is waiting on. */
internal class ServerSearch(
    val netId: String,
    val buffer: String,
    val id: String,
    val reply: CompletableDeferred<IrcEvent.SearchResults> = CompletableDeferred(),
)

internal const val SOJU_MUTED_KEY = "soju.im/muted"

private const val MAX_BOUNCER_OPS = 16

/** How long a log search waits for the bouncer's answers. */
private const val SERVER_SEARCH_TIMEOUT_MS = 15_000L

/** Most conversations one log search asks the bouncer about; soju answers each separately. */
private const val MAX_SERVER_SEARCH_TARGETS = 25

/** Messages asked for per conversation; soju's own ceiling. */
private const val SERVER_SEARCH_LIMIT = 100

/** Hits a log search keeps, the same as a search of the log files. */
private const val LOG_SEARCH_LIMIT = 300

/** A bouncer hit within this of a log line with the same sender and text is that line. */
private const val SEARCH_DEDUP_WINDOW_MS = 5_000L

/**
 * The account this profile logs in to its bouncer as: the SASL account when set, else the
 * username, without a network or client suffix.
 */
internal fun NetworkProfile.bouncerAccount(): String {
    val raw = saslAuthcid?.takeIf { saslEnabled && it.isNotBlank() } ?: username
    return raw.substringBefore('/').substringBefore('@').lowercase(Locale.ROOT)
}

/** True when [other] is a profile for the same bouncer account: same kind, host, port and account. */
private fun NetworkProfile.sameBouncerAs(other: NetworkProfile): Boolean =
    bouncerKind == other.bouncerKind && host.equals(other.host, ignoreCase = true) && port == other.port &&
        bouncerAccount() == other.bouncerAccount()

/**
 * True when this profile reaches [upstream] through [parent]'s bouncer: by soju netid, or by
 * network name.
 */
fun NetworkProfile.importsUpstream(parent: NetworkProfile, upstream: BouncerUpstreamInfo): Boolean {
    if (!sameBouncerAs(parent)) return false
    if (bouncerKind == BouncerKind.SOJU && bouncerNetworkId != null && bouncerNetworkId == upstream.id) return true
    val name = upstream.name?.takeIf { it.isNotBlank() } ?: return false
    return bouncerNetworkName.equals(name, ignoreCase = true)
}

/** The attributes for `BOUNCER ADDNETWORK`: only the fields filled in. */
internal fun BouncerNetworkDraft.toAddAttrs(): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    out["host"] = host.trim()
    port.trim().toIntOrNull()?.let { out["port"] = it.toString() }
    out["tls"] = if (tls) "1" else "0"
    name.trim().takeIf { it.isNotEmpty() }?.let { out["name"] = it }
    nickname.trim().takeIf { it.isNotEmpty() }?.let { out["nickname"] = it }
    username.trim().takeIf { it.isNotEmpty() }?.let { out["username"] = it }
    realname.trim().takeIf { it.isNotEmpty() }?.let { out["realname"] = it }
    if (pass.isNotEmpty()) out["pass"] = pass
    return out
}

/**
 * The attributes for `BOUNCER CHANGENETWORK`: the fields that differ from [current]. A field
 * emptied here is sent empty, which clears it.
 */
internal fun BouncerNetworkDraft.toChangeAttrs(current: BouncerUpstreamInfo): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    fun diff(key: String, value: String) {
        val v = value.trim()
        if (v != current.attrs[key].orEmpty()) out[key] = v
    }
    diff("name", name)
    diff("host", host)
    val p = port.trim().toIntOrNull()?.toString().orEmpty()
    if (p != current.port?.toString().orEmpty()) out["port"] = p
    if (current.tls != tls) out["tls"] = if (tls) "1" else "0"
    diff("nickname", nickname)
    diff("username", username)
    diff("realname", realname)
    if (pass.isNotEmpty()) out["pass"] = pass
    return out
}

/** The edit dialog's starting values for [upstream]. */
fun BouncerUpstreamInfo.toDraft(): BouncerNetworkDraft = BouncerNetworkDraft(
    name = name.orEmpty(),
    host = host.orEmpty(),
    port = port?.toString().orEmpty(),
    tls = tls ?: true,
    nickname = nickname.orEmpty(),
    username = username.orEmpty(),
    realname = realname.orEmpty(),
)

private fun IrcViewModel.bouncerMessage(text: String) {
    _state.update { it.copy(bouncerCloneMessage = text) }
}

// ── What the bouncer reports ──

/** A `BOUNCER NETWORK` line or ZNC ListNetworks row: merge it into what [netId]'s bouncer reported. */
internal fun IrcViewModel.handleBouncerNetwork(netId: String, ev: IrcEvent.BouncerNetwork) {
    val serverKey = bufKey(netId, "*server*")
    val prev = _state.value.bouncerNetworks[netId]?.get(ev.networkId)

    if (ev.removed) {
        _state.update { st ->
            val inner = st.bouncerNetworks[netId] ?: return@update st
            st.copy(bouncerNetworks = st.bouncerNetworks + (netId to (inner - ev.networkId)))
        }
        if (prev != null) {
            append(serverKey, from = null, text = "*** " + appContext.getString(R.string.vm_bouncer_removed, prev.name ?: ev.networkId),
                doNotify = false, isLocal = true)
        }
        return
    }

    // An attribute left out keeps its value; one sent empty is cleared.
    val merged = BouncerUpstreamInfo(
        id = ev.networkId,
        attrs = (prev?.attrs.orEmpty() - ev.clearedKeys) + ev.attrs,
        lastSeenMs = System.currentTimeMillis(),
    )
    _state.update { st ->
        val inner = st.bouncerNetworks[netId] ?: emptyMap()
        st.copy(bouncerNetworks = st.bouncerNetworks + (netId to (inner + (ev.networkId to merged))))
    }

    // Report only a first sighting or a change of state: soju resends the whole list on
    // every connect.
    val nameStr = merged.name ?: ev.networkId
    val isNew = prev == null
    val stateChanged = prev != null && prev.state != merged.state && merged.state != null
    if (isNew || stateChanged) {
        val stateStr = merged.state ?: "unknown"
        val hostStr = if (merged.host != null) " (${merged.host})" else ""
        val verb = if (isNew) appContext.getString(R.string.vm_bouncer_verb_discovered) else appContext.getString(R.string.vm_bouncer_verb_state_changed)
        append(serverKey, from = null,
            text = "*** " + appContext.getString(R.string.vm_bouncer_state, verb, nameStr, hostStr, stateStr),
            doNotify = false, isLocal = true)

        // A first-seen upstream with no profile for its host gets a hint. Without a reported
        // host there is nothing to compare, so no hint.
        val host = merged.host
        if (isNew && !host.isNullOrBlank()) {
            val profileHosts = _state.value.networks.map { it.host.lowercase() }.toSet()
            if (host.lowercase() !in profileHosts) {
                append(serverKey, from = null,
                    text = "    → " + appContext.getString(R.string.vm_no_local_profile, merged.name ?: host),
                    doNotify = false, isLocal = true)
            }
        }
    }
    val error = merged.error
    if (prev != null && error != null && error != prev.error) {
        append(serverKey, from = null, text = "*** " + appContext.getString(R.string.vm_bouncer_upstream_error, nameStr, error),
            doNotify = false, isLocal = true)
    }
    followBouncerNetwork(netId, merged)
}

/**
 * Keeps imported soju profiles pointing at [upstream]: one bound by name learns its netid, and
 * one bound by netid follows a rename, since it connects by name.
 */
private fun IrcViewModel.followBouncerNetwork(netId: String, upstream: BouncerUpstreamInfo) {
    val st = _state.value
    val reporter = st.networks.firstOrNull { it.id == netId } ?: return
    if (reporter.bouncerKind != BouncerKind.SOJU) return
    val name = upstream.name?.takeIf { it.isNotBlank() } ?: return
    for (p in st.networks) {
        val old = p.bouncerNetworkName?.takeIf { it.isNotBlank() } ?: continue
        if (!p.sameBouncerAs(reporter)) continue
        if (p.bouncerNetworkId == null && old.equals(name, ignoreCase = true)) {
            viewModelScope.launch {
                runCatching { repo.updateNetworkProfile(p.id) { it.copy(bouncerNetworkId = it.bouncerNetworkId ?: upstream.id) } }
            }
        } else if (p.bouncerNetworkId == upstream.id && old != name) {
            viewModelScope.launch {
                var renamedTo: String? = null
                runCatching {
                    repo.updateNetworkProfile(p.id) { cur ->
                        val was = cur.bouncerNetworkName
                        if (cur.bouncerNetworkId != upstream.id || was == null || was == name) return@updateNetworkProfile cur
                        // A name made at import ends with the network's; it follows the rename.
                        val display = if (cur.name.endsWith(" – $was")) cur.name.dropLast(was.length) + name else cur.name
                        renamedTo = display
                        cur.copy(bouncerNetworkName = name, name = display)
                    }
                }
                renamedTo?.let { display ->
                    append(bufKey(netId, "*server*"), from = null,
                        text = "*** " + appContext.getString(R.string.vm_bouncer_renamed, old, name, display),
                        doNotify = false, isLocal = true)
                }
            }
        }
    }
}

/** BOUNCER_NETID: the soju network [netId]'s connection is bound to, recorded on its profile. */
internal fun IrcViewModel.noteBouncerNetId(netId: String, bouncerNetId: String?) {
    if (bouncerNetId == null) return
    val p = _state.value.networks.firstOrNull { it.id == netId } ?: return
    if (p.bouncerKind != BouncerKind.SOJU || p.bouncerNetworkId == bouncerNetId) return
    viewModelScope.launch {
        runCatching { repo.updateNetworkProfile(netId) { it.copy(bouncerNetworkId = bouncerNetId) } }
    }
}

// ── Importing upstreams ──

/** See [IrcViewModel.refreshBouncerNetworks]. */
internal fun IrcViewModel.refreshBouncerNetworksImpl(parentNetId: String) {
    val rt = runtimes[parentNetId] ?: return
    val profile = _state.value.networks.firstOrNull { it.id == parentNetId } ?: return
    val cmd = when (profile.bouncerKind) {
        BouncerKind.SOJU -> "BOUNCER LISTNETWORKS"
        BouncerKind.ZNC -> "PRIVMSG *status :ListNetworks"
        BouncerKind.GENERIC, BouncerKind.NONE -> return
    }
    // Neither bouncer reports a network removed while this client was away, so the reply
    // replaces the list rather than updating it.
    _state.update { st ->
        if (!st.bouncerNetworks.containsKey(parentNetId)) st
        else st.copy(bouncerNetworks = st.bouncerNetworks + (parentNetId to emptyMap()))
    }
    viewModelScope.launch { runCatching { rt.client.sendRaw(cmd) } }
}

/** See [IrcViewModel.cloneBouncerNetwork]. */
internal fun IrcViewModel.cloneBouncerNetworkImpl(parentNetId: String, bouncerNetworkName: String, upstreamId: String?) {
    viewModelScope.launch {
        val st = _state.value
        val parent = st.networks.firstOrNull { it.id == parentNetId } ?: run {
            bouncerMessage(appContext.getString(R.string.vm_clone_no_parent))
            return@launch
        }
        // The UI offers this only for soju and ZNC; any other kind would get the wrong authcid syntax.
        if (parent.bouncerKind != BouncerKind.SOJU && parent.bouncerKind != BouncerKind.ZNC) {
            bouncerMessage(appContext.getString(R.string.vm_clone_unsupported))
            return@launch
        }
        val targetName = bouncerNetworkName.trim()
        if (targetName.isEmpty()) {
            bouncerMessage(appContext.getString(R.string.vm_clone_no_name))
            return@launch
        }
        val upstream = st.bouncerNetworks[parentNetId]?.let { nets ->
            upstreamId?.let { nets[it] } ?: nets.values.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
        } ?: BouncerUpstreamInfo(id = upstreamId.orEmpty(), attrs = mapOf("name" to targetName))
        val existing = st.networks.firstOrNull { it.importsUpstream(parent, upstream) }
        if (existing != null) {
            bouncerMessage(appContext.getString(R.string.vm_clone_already, existing.name))
            return@launch
        }
        val sortOrder = (st.networks.maxOfOrNull { it.sortOrder } ?: -1) + 1
        bouncerMessage(cloneUpstream(parent, targetName, upstream.id.takeIf { it.isNotEmpty() }, sortOrder))
    }
}

/** See [IrcViewModel.importAllBouncerNetworks]. */
internal fun IrcViewModel.importAllBouncerNetworksImpl(parentNetId: String) {
    viewModelScope.launch {
        val st = _state.value
        val parent = st.networks.firstOrNull { it.id == parentNetId } ?: return@launch
        if (parent.bouncerKind != BouncerKind.SOJU && parent.bouncerKind != BouncerKind.ZNC) return@launch
        val pending = st.bouncerNetworks[parentNetId]?.values.orEmpty()
            .filter { u -> !u.name.isNullOrBlank() && st.networks.none { it.importsUpstream(parent, u) } }
            .sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }
        if (pending.isEmpty()) {
            bouncerMessage(appContext.getString(R.string.vm_bouncer_import_none))
            return@launch
        }
        // One at a time, numbering as we go: the profile list updates only after each write lands.
        var sortOrder = (st.networks.maxOfOrNull { it.sortOrder } ?: -1) + 1
        for (u in pending) cloneUpstream(parent, u.name.orEmpty(), u.id, sortOrder++)
        bouncerMessage(appContext.getString(R.string.vm_bouncer_import_all, pending.size))
    }
}

/**
 * Create the profile for upstream [targetName] of [parent]'s bouncer and return the message
 * describing it. The clone copies the parent's connection (host, port, TLS, capabilities,
 * credentials, bouncerKind) with [targetName] as its bouncer network; its client id and
 * autojoin are cleared and auto-connect is off.
 */
private suspend fun IrcViewModel.cloneUpstream(
    parent: NetworkProfile,
    targetName: String,
    upstreamId: String?,
    sortOrder: Int,
): String {
    // Credentials live in the SecretStore. Without copying them the clone would fall back to
    // a plaintext PASS or abort SASL on first connect.
    val (parentServerPass, parentSaslPass, parentProxyPass) = withContext(Dispatchers.IO) {
        Triple(
            runCatching { repo.secretStore.getServerPassword(parent.id) }.getOrNull(),
            runCatching { repo.secretStore.getSaslPassword(parent.id) }.getOrNull(),
            runCatching { repo.secretStore.getProxyPassword(parent.id) }.getOrNull(),
        )
    }

    val newId = "net_" + java.util.UUID.randomUUID().toString().replace("-", "")

    // Use SASL for the clone: inherit it when the parent has it, otherwise upgrade a parent
    // server password to SASL with the same credential; with neither, leave SASL off.
    val parentHasSasl = parent.saslEnabled && !parentSaslPass.isNullOrEmpty()
    // Don't upgrade a hand-formatted PASS value (a '/' before the first ':', like
    // "alice/libera:secret") to SASL, which would send that whole string as the password.
    val parentPassLooksHandFormatted = parentServerPass?.let { pw ->
        val firstColon = pw.indexOf(':')
        firstColon > 0 && pw.substring(0, firstColon).contains('/')
    } ?: false
    val parentHasUsablePassForSasl = !parentServerPass.isNullOrEmpty() && !parentPassLooksHandFormatted
    val cloneShouldUseSasl = parentHasSasl || parentHasUsablePassForSasl
    val cloneSaslPassword = when {
        parentHasSasl -> parentSaslPass
        parentHasUsablePassForSasl -> parentServerPass
        else -> null
    }
    // Mechanism for the clone: the parent's when it used SASL; PLAIN when upgrading from a
    // server password, since the stored credential may not suit SCRAM and the parent's
    // mechanism may be a stale leftover.
    val cloneSaslMechanism = if (parentHasSasl) parent.saslMechanism else SaslMechanism.PLAIN

    val clone = parent.copy(
        id = newId,
        name = "${parent.name} – $targetName",
        isBouncer = true,
        bouncerNetworkName = targetName,
        bouncerNetworkId = upstreamId?.takeIf { parent.bouncerKind == BouncerKind.SOJU },
        bouncerClientId = null,
        autoJoin = emptyList(),
        notifyList = emptyList(),
        autoConnect = false,
        isFavourite = false,
        sortOrder = sortOrder,
        // saslAuthcid is copied only when the parent already used SASL. On an upgrade from
        // PASS it is cleared, so effectiveAuthIdentity builds it from `username` and the
        // clone's bouncerNetworkName rather than keeping a parent value like "alice/libera"
        // that names another network.
        saslEnabled = cloneShouldUseSasl,
        saslMechanism = cloneSaslMechanism,
        saslAuthcid = if (parentHasSasl) parent.saslAuthcid else null,
        // Secrets live in the SecretStore, written below for the clone's own id.
        serverPassword = null,
        saslPassword = null,
        proxyPassword = null,
    )
    repo.upsertNetwork(clone)
    if (clone.proxyType != com.boxlabs.hexdroid.connection.ProxyType.NONE && !parentProxyPass.isNullOrEmpty()) {
        runCatching { repo.secretStore.setProxyPassword(newId, parentProxyPass) }
    }
    // With SASL in use the clone gets no server password: a stray one can trip the bouncer's
    // warning about two authentication attempts.
    if (cloneShouldUseSasl && !cloneSaslPassword.isNullOrEmpty()) {
        runCatching { repo.secretStore.setSaslPassword(newId, cloneSaslPassword) }
    } else if (!parentServerPass.isNullOrEmpty()) {
        // No SASL upgrade (a hand-formatted PASS, or nothing usable): copy the server password
        // as it is, for the user to adjust on the clone if its format doesn't fit.
        runCatching { repo.secretStore.setServerPassword(newId, parentServerPass) }
    }
    return when {
        parentHasSasl -> appContext.getString(R.string.vm_clone_imported_sasl, targetName)
        parentHasUsablePassForSasl -> appContext.getString(R.string.vm_clone_imported_plain, targetName)
        parentPassLooksHandFormatted -> appContext.getString(R.string.vm_clone_imported_preformatted, targetName)
        else -> appContext.getString(R.string.vm_clone_imported_plain_none, targetName)
    }
}

// ── soju network management ──

/** The client of [parentNetId]'s live soju connection, or null after saying why there is none. */
private fun IrcViewModel.sojuControl(parentNetId: String): IrcClient? {
    val profile = _state.value.networks.firstOrNull { it.id == parentNetId }
    val rt = runtimes[parentNetId]
    if (profile?.bouncerKind != BouncerKind.SOJU || rt == null ||
        _state.value.connections[parentNetId]?.connected != true ||
        !rt.client.hasCap("soju.im/bouncer-networks")
    ) {
        bouncerMessage(appContext.getString(R.string.vm_bouncer_not_connected))
        return null
    }
    return rt.client
}

private fun IrcViewModel.queueBouncerOp(netId: String, op: BouncerOp) {
    val queue = runtimes[netId]?.bouncerOps ?: return
    synchronized(queue) {
        if (queue.size >= MAX_BOUNCER_OPS) queue.removeFirst()
        queue.addLast(op)
    }
}

/** See [IrcViewModel.addBouncerNetwork]. */
internal fun IrcViewModel.addBouncerNetworkImpl(parentNetId: String, draft: BouncerNetworkDraft, importAfter: Boolean) {
    if (draft.host.isBlank()) return
    val client = sojuControl(parentNetId) ?: return
    val attrs = draft.toAddAttrs()
    queueBouncerOp(parentNetId, BouncerOp("ADDNETWORK", null, draft.name.trim().ifEmpty { draft.host.trim() }, importAfter = importAfter))
    viewModelScope.launch { runCatching { client.bouncerAddNetwork(attrs) } }
}

/** See [IrcViewModel.editBouncerNetwork]. */
internal fun IrcViewModel.editBouncerNetworkImpl(parentNetId: String, upstreamId: String, draft: BouncerNetworkDraft) {
    val current = _state.value.bouncerNetworks[parentNetId]?.get(upstreamId) ?: return
    val attrs = draft.toChangeAttrs(current)
    if (attrs.isEmpty()) return
    val client = sojuControl(parentNetId) ?: return
    queueBouncerOp(parentNetId, BouncerOp("CHANGENETWORK", upstreamId, current.name ?: upstreamId))
    viewModelScope.launch { runCatching { client.bouncerChangeNetwork(upstreamId, attrs) } }
}

/** See [IrcViewModel.deleteBouncerNetwork]. */
internal fun IrcViewModel.deleteBouncerNetworkImpl(parentNetId: String, upstreamId: String, deleteProfiles: Boolean) {
    val st = _state.value
    val parent = st.networks.firstOrNull { it.id == parentNetId } ?: return
    val upstream = st.bouncerNetworks[parentNetId]?.get(upstreamId) ?: return
    val client = sojuControl(parentNetId) ?: return
    val profileIds = if (deleteProfiles) {
        st.networks.filter { it.id != parentNetId && it.importsUpstream(parent, upstream) }.map { it.id }
    } else emptyList()
    queueBouncerOp(parentNetId, BouncerOp("DELNETWORK", upstreamId, upstream.name ?: upstreamId, profileIds = profileIds))
    viewModelScope.launch { runCatching { client.bouncerDeleteNetwork(upstreamId) } }
}

/** The bouncer answered one of our network edits; they are answered in the order they went out. */
internal fun IrcViewModel.handleBouncerCommandResult(netId: String, ev: IrcEvent.BouncerCommandResult) {
    val queue = runtimes[netId]?.bouncerOps ?: return
    val op = synchronized(queue) {
        val idx = queue.indexOfFirst { it.subcommand == ev.subcommand }
        if (idx >= 0) queue.removeAt(idx) else null
    } ?: return
    if (ev.failed) {
        val detail = ev.description ?: ev.code.orEmpty()
        bouncerMessage(
            if (ev.attribute != null) appContext.getString(R.string.vm_bouncer_refused_attr, ev.attribute, detail)
            else appContext.getString(R.string.vm_bouncer_refused, detail)
        )
        return
    }
    val upstreams = _state.value.bouncerNetworks[netId].orEmpty()
    when (ev.subcommand) {
        "ADDNETWORK" -> {
            // soju announces the new network before answering, so its name is known by now.
            val name = ev.networkId?.let { upstreams[it]?.name } ?: op.label
            if (op.importAfter) cloneBouncerNetworkImpl(netId, name, ev.networkId)
            else bouncerMessage(appContext.getString(R.string.vm_bouncer_added, name))
        }
        "CHANGENETWORK" -> {
            val name = op.upstreamId?.let { upstreams[it]?.name } ?: op.label
            bouncerMessage(appContext.getString(R.string.vm_bouncer_changed, name))
        }
        "DELNETWORK" -> {
            op.profileIds.forEach { deleteNetwork(it) }
            bouncerMessage(
                if (op.profileIds.isEmpty()) appContext.getString(R.string.vm_bouncer_deleted, op.label)
                else appContext.getString(R.string.vm_bouncer_deleted_profiles, op.label)
            )
        }
    }
}

// ── Shared mutes (soju.im/muted) ──
//
// ChannelPrefs.syncedMute holds what soju last said. When the two meet again, a server value
// that differs from it was changed by another client and is taken; otherwise a local value that
// differs from it was changed here and is sent.

/** True for a buffer soju keeps metadata for: a channel or a query. */
private fun IrcViewModel.isMuteSyncTarget(name: String): Boolean =
    name.isNotBlank() && !name.startsWith("*") && !isPseudoBuffer(name) && !isDccChatBufferName(name)

private fun IrcViewModel.pushSojuMute(netId: String, target: String, muted: Boolean) {
    val client = runtimes[netId]?.client ?: return
    viewModelScope.launch {
        runCatching { client.sendMetadata(target, "SET", "$SOJU_MUTED_KEY ${if (muted) "1" else "0"}") }
    }
}

/** The user changed the mute on [bufferKey]: tell soju, once the two are in step. */
internal fun IrcViewModel.shareSojuMute(bufferKey: String, muted: Boolean) {
    val (netId, name) = splitKey(bufferKey)
    if (runtimes[netId]?.muteSync?.done != true || !isMuteSyncTarget(name)) return
    pushSojuMute(netId, name, muted)
}

/** A soju.im/muted value for [target]: collected until the subscription is confirmed, then applied. */
internal fun IrcViewModel.noteSojuMuted(netId: String, target: String, value: String?) {
    val sync = runtimes[netId]?.muteSync ?: return
    val muted = value == "1"
    if (!sync.done) {
        sync.listed[casefoldText(netId, target)] = target to muted
        return
    }
    if (!isMuteSyncTarget(target)) return
    val key = resolveBufferKey(netId, target)
    val p = channelPrefsFor(key)
    if (p.muted != muted || p.syncedMute != muted) storeChannelPrefs(key, p.copy(muted = muted, syncedMute = muted))
}

/**
 * soju confirmed the soju.im/muted subscription after listing every target it holds a value
 * for. Reconcile those with the mutes kept here.
 */
internal fun IrcViewModel.finishMuteSync(netId: String) {
    val sync = runtimes[netId]?.muteSync ?: return
    if (sync.done) return
    sync.done = true
    val targets = LinkedHashMap<String, String>()
    for ((fold, entry) in sync.listed) targets[fold] = entry.first
    val prefix = ChannelPrefsCodec.keyFor("$netId::")
    for ((key, p) in _state.value.channelPrefs) {
        if (!key.startsWith(prefix) || !(p.muted || p.syncedMute)) continue
        val name = key.substring(prefix.length)
        targets.putIfAbsent(casefoldText(netId, name), name)
    }
    for ((fold, name) in targets) {
        if (!isMuteSyncTarget(name)) continue
        // soju lists only targets with something set, so one it left out is unmuted there.
        val server = sync.listed[fold]?.second ?: false
        val key = resolveBufferKey(netId, name)
        val p = channelPrefsFor(key)
        when {
            server != p.syncedMute -> if (p.muted != server || p.syncedMute != server) {
                storeChannelPrefs(key, p.copy(muted = server, syncedMute = server))
            }
            p.muted != p.syncedMute -> pushSojuMute(netId, name, p.muted)
        }
    }
    sync.listed.clear()
}

// ── Searching soju's history (soju.im/search) ──

/**
 * Ask each bouncer that keeps history to search the conversations [scope] covers for [query],
 * and return the searches to wait on. A connection takes part when it is bound to a soju network
 * that offers soju.im/search; its open channels and queries are searched, most recently active
 * first, up to [MAX_SERVER_SEARCH_TARGETS] in all.
 */
internal suspend fun IrcViewModel.startServerSearches(
    query: LogSearch.Query,
    scope: LogSearchScope,
    bufferKey: String?,
): List<ServerSearch> {
    val st = _state.value
    val (scopeNet, scopeBuffer) = bufferKey?.takeIf { it.contains("::") }?.let { splitKey(it) } ?: ("" to "")
    // soju matches one text, so it is sent the longest term and each hit is checked against
    // the whole query here. Its sender match is exact, so the nick goes as typed and only when
    // there is no text to narrow by.
    val text = query.terms.maxByOrNull { it.length }
    val from = if (text == null) query.fromTyped else null
    val targets = ArrayList<Pair<String, String>>()
    for ((netId, rt) in runtimes) {
        if (scope != LogSearchScope.ALL && netId != scopeNet) continue
        if (st.connections[netId]?.connected != true) continue
        if (!rt.client.hasCap("soju.im/search") || rt.client.bouncerNetId == null) continue
        if (scope == LogSearchScope.BUFFER) {
            if (isMuteSyncTarget(scopeBuffer)) targets += netId to scopeBuffer
            continue
        }
        st.buffers.entries
            .mapNotNull { (key, buf) ->
                val (nid, name) = splitKey(key)
                if (nid != netId || !isMuteSyncTarget(name)) null
                else Triple(netId, name, buf.messages.lastOrNull()?.timeMs ?: 0L)
            }
            .sortedByDescending { it.third }
            .forEach { targets += it.first to it.second }
    }
    val out = ArrayList<ServerSearch>()
    for ((netId, buffer) in targets.take(MAX_SERVER_SEARCH_TARGETS)) {
        val client = runtimes[netId]?.client ?: continue
        val search = ServerSearch(netId, buffer, client.newSearchId())
        serverSearchWaits[search.id] = search.reply
        val sent = runCatching { client.searchMessages(buffer, text, from, SERVER_SEARCH_LIMIT, search.id) }.getOrNull()
        if (sent == null) serverSearchWaits.remove(search.id) else out += search
    }
    return out
}

/** A bouncer answered a search: hand it to the log search waiting for it. */
internal fun IrcViewModel.handleSearchResults(ev: IrcEvent.SearchResults) {
    serverSearchWaits.remove(ev.requestId)?.complete(ev)
}

/**
 * Wait for [searches], then return [local] with the bouncer's hits added, newest first, with how
 * many conversations answered and whether more matched than are kept. A bouncer hit already in
 * the logs is left out.
 */
internal suspend fun IrcViewModel.finishServerSearches(
    query: LogSearch.Query,
    searches: List<ServerSearch>,
    local: List<LogSearchHit>,
    localTruncated: Boolean,
): Triple<List<LogSearchHit>, Int, Boolean> {
    withTimeoutOrNull(SERVER_SEARCH_TIMEOUT_MS) { searches.forEach { it.reply.await() } }
    val answered = searches.filter { it.reply.isCompleted }.map { it to it.reply.await() }.filter { !it.second.failed }

    // A log hit's buffer is read back from its file name, so a bouncer hit's goes the same way.
    fun sig(dir: String, buffer: String, body: String) = listOf(
        dir.lowercase(Locale.ROOT),
        buffer.lowercase(Locale.ROOT),
        LogSearch.senderOf(body)?.lowercase(Locale.ROOT).orEmpty(),
        LogSearch.textOf(body),
    ).joinToString("\u0000")
    val logged = HashMap<String, MutableList<Long>>()
    for (h in local) {
        val at = parseLogLineTimeMs(h.line) ?: continue
        logged.getOrPut(sig(h.networkDir, h.buffer, h.body)) { ArrayList() } += at
    }
    val found = ArrayList<LogSearchHit>()
    for ((search, results) in answered) {
        val netName = _state.value.networks.firstOrNull { it.id == search.netId }?.name ?: continue
        val dir = logs.networkDirName(netName)
        val fileBuffer = LogSearch.bufferForFileName(logs.bufferFileName(search.buffer))
        for (h in results.hits) {
            val at = h.timeMs ?: continue
            val line = formatLogLine(at, h.from.ifEmpty { null }, h.text, h.isAction)
            if (!LogSearch.matches(query, line)) continue
            // The log keeps whole seconds, so allow for that and a little clock drift.
            val seen = logged[sig(dir, fileBuffer, LogSearch.bodyOf(line))]
                ?.any { kotlin.math.abs(it - at) <= SEARCH_DEDUP_WINDOW_MS } == true
            if (!seen) {
                found += LogSearchHit(dir, search.buffer, 0, line, emptyList(), emptyList(),
                    fromServer = true, netId = search.netId, msgId = h.msgId)
            }
        }
    }
    val all = local + found
    return Triple(LogSearch.newestFirst(all, LOG_SEARCH_LIMIT), answered.size, localTruncated || all.size > LOG_SEARCH_LIMIT)
}
