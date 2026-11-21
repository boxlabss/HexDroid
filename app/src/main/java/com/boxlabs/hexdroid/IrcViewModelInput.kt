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
import com.boxlabs.hexdroid.IrcViewModel.Companion.ALIAS_VAR
import com.boxlabs.hexdroid.IrcViewModel.Companion.AWAY_POLL_INTERVAL_MS
import com.boxlabs.hexdroid.IrcViewModel.Companion.AWAY_POLL_MAX_MEMBERS
import com.boxlabs.hexdroid.IrcViewModel.Companion.DCC_MIME_FILTER
import com.boxlabs.hexdroid.IrcViewModel.Companion.MAX_ALIAS_DEPTH
import com.boxlabs.hexdroid.IrcViewModel.Companion.SLAP_FISH
import com.boxlabs.hexdroid.IrcViewModel.NamesRequest
import com.boxlabs.hexdroid.data.IgnoreRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Called when the app goes to background: flush log buffers to disk. */
fun IrcViewModel.flushLogs() {
    if (_state.value.settings.loggingEnabled) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { logs.flushAll() } }
    }
}

/**
 * Called when the app goes to background: sends "done" so we don't appear to be typing
 * forever, and cancels the pending paused/done timer.
 */
fun IrcViewModel.cancelTypingOnBackground() {
    typingDoneJob?.cancel()
    typingDoneJob = null
    val prevKey = typingLastKey ?: return
    typingLastKey = null
    sendTypingDone(prevKey)
}

/** Sends typing [state] to buffer [key] now and records the time. */
internal fun IrcViewModel.sendTypingNow(key: String, state: String) {
    val (netId, bufferName) = splitKey(key)
    typingSentAtMs[key] = System.currentTimeMillis()
    viewModelScope.launch { runCatching { runtimes[netId]?.client?.sendTypingStatus(bufferName, state) } }
}

/** Sends "done" to buffer [key], waiting out the 3 s spacing if a notification went out more recently. */
internal fun IrcViewModel.sendTypingDone(key: String) {
    pendingTypingDone.remove(key)?.cancel()
    val wait = (typingSentAtMs[key] ?: 0L) + TYPING_MIN_INTERVAL_MS - System.currentTimeMillis()
    if (wait <= 0) {
        sendTypingNow(key, "done")
        return
    }
    pendingTypingDone[key] = viewModelScope.launch {
        delay(wait)
        pendingTypingDone.remove(key)
        sendTypingNow(key, "done")
    }
}

/**
 * Called from [HexDroidApp.onActivityStarted] on returning to the foreground. Clears unread and
 * highlights on the selected buffer (anchoring lastReadTimestamp so the separator stays put),
 * and clears [appExitRequested] so auto-reconnect works again.
 */
fun IrcViewModel.onAppForegrounded() {
    appExitRequested = false
}

fun IrcViewModel.consumeUnreadOnForeground() {
    // Returning to the app with a conversation already on screen is reading it, so its
    // notifications go too. Same condition as the unread reset below: only on the chat
    // screen, since Settings or Networks means the messages have not been seen.
    _state.value.let { st ->
        val key = st.selectedBuffer
        if (key.isNotBlank() && st.screen == AppScreen.CHAT) {
            val (netId, bufName) = splitKey(key)
            notifier.cancelBuffer(netId, bufName)
        }
    }
    _state.update { st ->
        val key = st.selectedBuffer
        if (key.isBlank()) return@update st
        // Only reset when actually on the chat screen - foregrounding while on
        // Settings or Networks shouldn't clear unread counters because the
        // user isn't seeing the chat content yet.
        if (st.screen != AppScreen.CHAT) return@update st
        val buf = st.buffers[key] ?: return@update st
        if (buf.unread == 0 && buf.highlights == 0) return@update st
        val updated = buf.copy(unread = 0, highlights = 0)
        st.copy(buffers = st.buffers + (key to updated))
    }
    _state.value.let { st ->
        if (st.selectedBuffer.isNotBlank() && st.screen == AppScreen.CHAT) sendReadReceipt(st.selectedBuffer)
    }
}

/**
 * Poll the visible channel's member list so away status stays current where the server lacks
 * away-notify. Only while the member list is open, in the foreground, on the chat screen, for
 * the channel being viewed.
 */
internal fun IrcViewModel.startAwayPolling() {
    viewModelScope.launch {
        while (true) {
            delay(AWAY_POLL_INTERVAL_MS)
            if (!AppVisibility.isForeground) continue
            val st = _state.value
            if (st.screen != AppScreen.CHAT) continue
            // The presence dots are the only reader, so nothing to do when hidden.
            if (!st.showNickList || !st.settings.showNickIcons) continue
            val key = st.selectedBuffer.takeIf { it.isNotBlank() } ?: continue
            val (netId, chan) = splitKey(key)
            if (!isChannelOnNet(netId, chan)) continue
            if (st.connections[netId]?.connected != true) continue
            // A sweep of a very large channel is a lot of traffic for a status column.
            if ((st.nicklists[key]?.size ?: 0) > AWAY_POLL_MAX_MEMBERS) continue
            val rt = runtimes[netId] ?: continue
            runCatching { rt.client.refreshChannelWho(chan) }
        }
    }
}

/**
 * Empty [bufferKey] and mark its history exhausted, so the emptied view doesn't
 * immediately fetch back what was cleared.
 */
fun IrcViewModel.clearBuffer(bufferKey: String) {
    chatHistory.forget(bufferKey)
    historyAnchors.remove(bufferKey)
    sessionFirstLive.remove(bufferKey)
    catchupAnchorMs.remove(bufferKey)
    catchupCursor.remove(bufferKey)
    gapMarkers.remove(bufferKey)
    _state.update { s ->
        val buf = s.buffers[bufferKey] ?: return@update s
        s.copy(buffers = s.buffers + (bufferKey to buf.copy(
            log = buf.log.cleared(),
            unread = 0,
            highlights = 0,
            historyLoading = false,
            historyExhausted = true,
            historyGapOpen = false,
        )))
    }
}

/**
 * Expand a user alias template (see [UiSettings.commandAliases]). Substitutes $channel/$chan
 * (current buffer), $network/$net (network name), $server (server host), $1..$9 (positional
 * args), $* (all args), $me/$nick (own nick), and $$ (a literal dollar).
 */
internal fun IrcViewModel.expandAlias(
    template: String,
    channel: String,
    argString: String,
    myNick: String,
    network: String,
    server: String,
): String {
    val args = if (argString.isBlank()) emptyList() else argString.split(Regex("\\s+"))
    return ALIAS_VAR.replace(template) { m ->
        val tok = m.groupValues[1]
        if (tok.isEmpty()) "\$"
        else when (tok.lowercase()) {
            "channel", "chan" -> channel
            "network", "net" -> network
            "server" -> server
            "nick", "me" -> myNick
            "*" -> argString
            else -> args.getOrNull(tok.toInt() - 1) ?: ""
        }
    }.trim()
}

/**
 * [targetKey] dispatches the command against a specific buffer instead of the selected one, so a
 * script command runs where it originated rather than wherever the user is looking. Null keeps
 * the composer's behaviour (the selected buffer). [fromUser] input passes through the paste guard
 * unless [pasteConfirmed].
 */
internal fun IrcViewModel.sendInputInternal(
    raw: String,
    aliasDepth: Int,
    targetKey: String? = null,
    fromUser: Boolean = false,
    pasteConfirmed: Boolean = false,
) {
    viewModelScope.launch { runInput(raw, aliasDepth, targetKey, fromUser, pasteConfirmed) }
}

/**
 * Runs an input: a command, or text for the buffer. A command followed by more lines runs
 * first and the remaining lines follow it, in the same buffer, as the next input. "//" sends
 * a line starting with a slash as text.
 */
internal suspend fun IrcViewModel.runInput(
    raw: String,
    aliasDepth: Int,
    targetKey: String?,
    fromUser: Boolean,
    pasteConfirmed: Boolean,
) {
    var pending: String? = raw
    var key = targetKey
    while (true) {
        val input = pending ?: break
        pending = null
        runSingleInput(input, aliasDepth, key, fromUser, pasteConfirmed) { rest, restKey ->
            pending = rest
            key = restKey
        }
    }
}

/** Runs one input. Lines after a leading command are handed to [onRest] with the buffer key. */
private suspend fun IrcViewModel.runSingleInput(
    raw: String,
    aliasDepth: Int,
    targetKey: String?,
    fromUser: Boolean,
    pasteConfirmed: Boolean,
    onRest: (String, String) -> Unit,
) {
    val st = _state.value
    val currentKey = targetKey?.takeIf { it.isNotBlank() && st.buffers.containsKey(it) } ?: st.selectedBuffer
    if (currentKey.isBlank()) return
    val (netId, bufferName) = splitKey(currentKey)
    // Some commands (/SYSINFO) should work even when disconnected.
    // For network-bound commands, we'll surface a friendly "Not connected" message.
    val rt = runtimes[netId]
    val c = rt?.client

    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return

    // Formatting codes lead the input when bold or a colour is active in the composer.
    val strippedForCommandCheck = SlashInput.stripLeadingFormatting(trimmed)

    if (SlashInput.isCommand(strippedForCommandCheck)) {
        val (firstLine, restLines) = SlashInput.splitFirstLine(trimmed)
        if (restLines != null) {
            runInput(firstLine, aliasDepth, currentKey, fromUser, pasteConfirmed)
            onRest(restLines, currentKey)
            return
        }
        val cmdLine = strippedForCommandCheck.drop(1).trim()
        val cmd = cmdLine.substringBefore(' ').lowercase()

        // Records a typed /join so the JOIN handler switches to the channel even during
        // post-reconnect suppression. Only the channel list of /join #a,#b [key] is used.
        if (cmd == "join") {
            cmdLine.substringAfter(' ', "").trim().substringBefore(' ')
                .split(",")
                .forEach { ch ->
                    ch.trim().takeIf { it.isNotBlank() }
                        ?.let { rt?.pendingUserJoinSwitch?.add(casefoldText(netId, it)) }
                }
        }

        when (cmd) {
            "age" -> {
                // /age trust <nick> | /age reject <nick> | /age pending
                // Resolves an identity conflict that onIdentConflict parked. Deliberately a
                // typed command: accepting a new key for a known nick is a trust decision, so
                // it should be deliberate rather than a tap someone can fire off reflexively.
                val rest = cmdLine.substringAfter(' ', "").trim()
                val sub = rest.substringBefore(' ').lowercase()
                val who = rest.substringAfter(' ', "").trim()
                val br = ageBridgeFor(netId)
                val say = { t: String -> append(currentKey, from = null, text = t, isLocal = true, doNotify = false) }
                when {
                    br == null -> say("*** " + appContext.getString(R.string.vm_age_unavailable))
                    sub == "pending" -> {
                        val ps = br.pendingIdentNicks()
                        say(if (ps.isEmpty()) "*** " + appContext.getString(R.string.vm_age_none_pending)
                            else "*** " + appContext.getString(R.string.vm_age_pending_list) + " " +
                                ps.joinToString(", ") { n -> "$n (${br.pendingIdentFp(n)?.take(16)})" })
                    }
                    who.isEmpty() -> say("*** " + appContext.getString(R.string.vm_age_usage))
                    sub == "trust" -> say(
                        if (br.trustPendingIdent(who)) "*** " + appContext.getString(R.string.vm_age_trusted, who)
                        else "*** " + appContext.getString(R.string.vm_age_nothing_pending, who)
                    )
                    sub == "reject" -> say(
                        if (br.rejectPendingIdent(who)) "*** " + appContext.getString(R.string.vm_age_rejected, who)
                        else "*** " + appContext.getString(R.string.vm_age_nothing_pending, who)
                    )
                    else -> say("*** " + appContext.getString(R.string.vm_age_usage))
                }
            }
            "quit", "disconnect" -> {
                // User-initiated disconnect: send QUIT and mark the network as manually
                // disconnecting before the socket closes, so the Disconnected handler
                // doesn't treat it as a drop and reconnect.
                //
                // Everything after the verb is the quit message, with its original casing:
                //   /quit                -> settings.quitMessage
                //   /quit gone for tea   -> "gone for tea"
                val reason = cmdLine.substringAfter(' ', missingDelimiterValue = "")
                    .trim()
                    .takeIf { it.isNotBlank() }
                disconnectNetwork(netId, reasonOverride = reason)
                return
            }
            "agm-key" -> {
                // Per-target AES-256-GCM keys:
                //   /agm-key gen [target]        generate, store and print a 32-byte key
                //   /agm-key set <target> <b64>  install a base64 key (padding optional)
                //   /agm-key clear <target>      remove the key
                //   /agm-key info [target]       show scheme and fingerprint
                // The target defaults to the current buffer.
                val parts = cmdLine.split(Regex("\\s+"), limit = 4)
                val sub = parts.getOrNull(1)?.lowercase() ?: ""
                fun usage() {
                    append(currentKey, from = null, isLocal = true, doNotify = false,
                        text = "*** " + appContext.getString(R.string.vm_agmkey_usage))
                }
                val defaultTarget = if (bufferName == "*server*") null else bufferName
                when (sub) {
                    "gen" -> {
                        val target = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: defaultTarget
                        if (target == null) {
                            append(currentKey, from = null, isLocal = true, doNotify = false,
                                text = "*** " + appContext.getString(R.string.vm_agmkey_gen_needs_target))
                            return
                        }
                        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                        e2eKeyStore.set(netId, target, com.boxlabs.hexdroid.crypto.E2eKeyStore.Entry(
                            com.boxlabs.hexdroid.crypto.E2eScheme.AGM, key))
                        bumpE2eKeyVersion()
                        val b64 = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                        val fp = com.boxlabs.hexdroid.crypto.E2eFingerprint.compute(com.boxlabs.hexdroid.crypto.E2eScheme.AGM, key)
                        append(currentKey, from = null, isLocal = true, doNotify = false,
                            text = "*** " + appContext.getString(R.string.vm_agmkey_generated, target, fp))
                        append(currentKey, from = null, isLocal = true, doNotify = false,
                            text = "*** " + appContext.getString(R.string.vm_agmkey_run_other, target, b64))
                        append(currentKey, from = null, isLocal = true, doNotify = false,
                            text = "*** " + appContext.getString(R.string.vm_agmkey_verify))
                        return
                    }
                    "set" -> {
                        val target = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                        val b64 = parts.getOrNull(3)?.trim()
                        if (target == null || b64.isNullOrBlank()) {
                            append(currentKey, from = null, isLocal = true, doNotify = false,
                                text = "*** " + appContext.getString(R.string.vm_agmkey_set_usage))
                            return
                        }
                        val keyBytes = try {
                            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                        } catch (_: IllegalArgumentException) {
                            append(currentKey, from = null, isLocal = true, doNotify = false,
                                text = "*** " + appContext.getString(R.string.vm_agmkey_bad_base64))
                            return
                        }
                        if (keyBytes.size != 32) {
                            append(currentKey, from = null, isLocal = true, doNotify = false,
                                text = "*** " + appContext.getString(R.string.vm_agmkey_bad_len, keyBytes.size))
                            return
                        }
                        try {
                            e2eKeyStore.set(netId, target, com.boxlabs.hexdroid.crypto.E2eKeyStore.Entry(
                                com.boxlabs.hexdroid.crypto.E2eScheme.AGM, keyBytes))
                            bumpE2eKeyVersion()
                            val fp = com.boxlabs.hexdroid.crypto.E2eFingerprint.compute(com.boxlabs.hexdroid.crypto.E2eScheme.AGM, keyBytes)
                            append(currentKey, from = null, isLocal = true, doNotify = false,
                                text = "*** " + appContext.getString(R.string.vm_agmkey_installed, target, fp))
                        } catch (t: Throwable) {
                            append(currentKey, from = null, isLocal = true, doNotify = false,
                                text = "*** " + appContext.getString(R.string.vm_agmkey_store_fail, t.message ?: t.javaClass.simpleName))
                        }
                        return
                    }
                    "clear" -> {
                        val target = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: defaultTarget
                        if (target == null) {
                            append(currentKey, from = null, isLocal = true, doNotify = false,
                                text = "*** " + appContext.getString(R.string.vm_agmkey_clear_needs_target))
                            return
                        }
                        e2eKeyStore.clear(netId, target)
                        bumpE2eKeyVersion()
                        append(currentKey, from = null, isLocal = true, doNotify = false,
                            text = "*** " + appContext.getString(R.string.vm_agmkey_cleared, target))
                        return
                    }
                    "info" -> {
                        val target = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: defaultTarget
                        if (target == null) {
                            append(currentKey, from = null, isLocal = true, doNotify = false,
                                text = "*** " + appContext.getString(R.string.vm_agmkey_info_needs_target))
                            return
                        }
                        val entry = e2eKeyStore.get(netId, target)
                        if (entry == null) {
                            append(currentKey, from = null, isLocal = true, doNotify = false,
                                text = "*** " + appContext.getString(R.string.vm_agmkey_none, target))
                        } else {
                            val fp = com.boxlabs.hexdroid.crypto.E2eFingerprint.compute(entry.scheme, entry.key)
                            append(currentKey, from = null, isLocal = true, doNotify = false,
                                text = "*** " + appContext.getString(R.string.vm_agmkey_info, target, entry.scheme.displayName, fp))
                        }
                        return
                    }
                    else -> {
                        usage()
                        return
                    }
                }
            }
            "list" -> {
                goTo(AppScreen.LIST)
                return
            }

            "say" -> {
                // /say <text>: send text as typed, even when it starts with a slash.
                val text = cmdLine.substringAfter(' ', "").trim()
                if (text.isEmpty()) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_say_usage), isLocal = true, doNotify = false)
                    return
                }
                runInput(if (text.startsWith("/")) "/$text" else text, aliasDepth, currentKey, fromUser, pasteConfirmed)
                return
            }

            "notify", "unnotify" -> {
                handleNotifyCommand(netId, currentKey, cmd, cmdLine.substringAfter(' ', "").trim())
                return
            }

            "mentions" -> {
                openMentions()
                return
            }

            "clear" -> {
                clearBuffer(currentKey)
                return
            }

            "alias" -> {
                // /alias                         - list aliases
                // /alias add <name> <expansion>  - define (expansion is a command line)
                // /alias remove <name>           - delete
                fun info(text: String) = append(currentKey, from = null, text = text, doNotify = false)
                val rest = cmdLine.substringAfter(' ', "").trim()
                val sub = rest.substringBefore(' ').lowercase()
                val subArg = rest.substringAfter(' ', "").trim()
                when (sub) {
                    "", "list" -> {
                        val aliases = st.settings.commandAliases
                        if (aliases.isEmpty()) {
                            info("*** " + appContext.getString(R.string.vm_alias_none))
                        } else {
                            info("*** Aliases (${aliases.size}):")
                            aliases.toSortedMap().forEach { (k, v) -> info("***   /$k  >  $v") }
                        }
                    }
                    "add", "set" -> {
                        val name = subArg.substringBefore(' ').trim().removePrefix("/").lowercase()
                        val expansion = subArg.substringAfter(' ', "").trim()
                        when {
                            name.isBlank() || expansion.isBlank() ->
                                info("*** " + appContext.getString(R.string.vm_alias_usage_add))
                            name.any { it.isWhitespace() || it == '/' } ->
                                info("*** " + appContext.getString(R.string.vm_alias_bad_name))
                            else -> {
                                updateSettings { copy(commandAliases = commandAliases + (name to expansion)) }
                                info("*** " + appContext.getString(R.string.vm_alias_saved, name, expansion))
                            }
                        }
                    }
                    "remove", "rm", "del", "delete" -> {
                        val name = subArg.substringBefore(' ').trim().removePrefix("/").lowercase()
                        when {
                            name.isBlank() -> info("*** " + appContext.getString(R.string.vm_alias_usage_remove))
                            !st.settings.commandAliases.containsKey(name) -> info("*** " + appContext.getString(R.string.vm_alias_no_such, name))
                            else -> {
                                updateSettings { copy(commandAliases = commandAliases - name) }
                                info("*** " + appContext.getString(R.string.vm_alias_removed, name))
                            }
                        }
                    }
                    else -> info("*** " + appContext.getString(R.string.vm_alias_usage))
                }
                return
            }
            "sysinfo" -> {
                val line = withContext(Dispatchers.Default) { buildSysInfoLine() }
                val fromNick = st.connections[netId]?.myNick ?: st.myNick
                // If we're in a channel/query and connected, send it as a normal message.
                if (isDccChatBufferName(bufferName)) {
                    sendDccChatLine(currentKey, line, isAction = false)
                } else if (ageOn(netId, bufferName)) {
                    if (c != null) sendAgeText(netId, currentKey, bufferName, line)
                    else append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_not_connected), doNotify = false)
                } else if (!isPseudoBuffer(bufferName) && c != null) {
                    val enc = e2eKeyStore.get(netId, bufferName)?.scheme
                    for (chunk in outgoingChunks(netId, bufferName, line)) {
                        val siId = c.privmsg(bufferName, chunk)
                        recordLocalSend(netId, currentKey, chunk, isAction = false, sendIds = listOf(siId))
                        append(currentKey, from = fromNick, text = chunk, isLocal = true, encryption = enc)
                    }
                } else {
                    append(currentKey, from = fromNick, text = line, isLocal = true)
                }
                return
            }

            "react", "unreact" -> {
                // /react <emoji> [n] and /unreact <emoji> [n]: react to, or remove a
                // reaction from, the n-th most recent message (default 1). Only messages
                // with a server msgid count.
                val remove = (cmd == "unreact")
                val args = cmdLine.substringAfter(' ', "").trim().split(Regex("\\s+"))
                val emoji = args.getOrNull(0)?.takeIf { it.isNotBlank() }
                if (emoji == null) {
                    append(currentKey, from = null, isLocal = true, doNotify = false,
                        text = "*** " + appContext.getString(R.string.vm_react_usage, cmd))
                    return
                }
                if (bufferName == "*server*" || c == null) {
                    append(currentKey, from = null, isLocal = true, doNotify = false,
                        text = "*** " + appContext.getString(R.string.vm_react_needs_target, cmd))
                    return
                }
                // Match the long-press UI's friendly fail mode: surface a clear message
                // when the server doesn't support reactions rather than silently no-op.
                // hasReactionSupport is set on CAP-negotiated -> ack of message-tags.
                if (st.connections[netId]?.hasReactionSupport != true) {
                    append(currentKey, from = null, isLocal = true, doNotify = false,
                        text = "*** " + appContext.getString(R.string.vm_react_no_tags))
                    return
                }
                val nBack = args.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 100) ?: 1
                val msgs = _state.value.buffers[currentKey]?.messages ?: emptyList()
                // Walk newest-first, collect messages with msgIds, pick the nBack-th one.
                val withMsgId = msgs.asReversed().asSequence()
                    .filter { !it.msgId.isNullOrBlank() }
                    .take(nBack)
                    .toList()
                val target = withMsgId.getOrNull(nBack - 1)
                if (target?.msgId == null) {
                    val noun = if (nBack == 1) appContext.getString(R.string.vm_react_latest) else appContext.getString(R.string.vm_react_nback, nBack)
                    append(currentKey, from = null, isLocal = true, doNotify = false,
                        text = "*** " + appContext.getString(R.string.vm_react_no_msgid, cmd, noun))
                    return
                }
                c.sendReaction(bufferName, target.msgId, emoji, remove = remove)
                return
            }

            "find", "grep", "search" -> {
                val query = cmdLine.substringAfter(' ', "").trim()
                if (query.isBlank()) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_find_usage), isLocal = true, doNotify = false)
                    return
                }
                val msgs = _state.value.buffers[currentKey]?.messages.orEmpty()
                val matches = msgs.filter {
                    it.text.contains(query, ignoreCase = true) ||
                        it.from?.contains(query, ignoreCase = true) == true
                }
                if (matches.isEmpty()) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_no_matches, query), isLocal = true, doNotify = false)
                    return
                }
                _state.value = _state.value.copy(
                    findOverlay = FindOverlay(
                        query = query,
                        matchIds = matches.map { it.id },
                        currentIndex = matches.lastIndex,
                        bufferKey = currentKey,
                    )
                )
                return
            }

            "gsearch", "gfind" -> {
                // Global search across all loaded buffers on the current network.
                val query = cmdLine.substringAfter(' ', "").trim()
                if (query.isBlank()) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_gsearch_usage), isLocal = true, doNotify = false)
                    return
                }
                val allMatches = _state.value.buffers
                    .filter { (k, _) -> splitKey(k).first == netId }
                    .flatMap { (_, buf) ->
                        buf.messages.filter {
                            it.text.contains(query, ignoreCase = true) ||
                                it.from?.contains(query, ignoreCase = true) == true
                        }
                    }
                    .sortedBy { it.timeMs }
                if (allMatches.isEmpty()) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_no_matches_buffers, query, _state.value.buffers.count { splitKey(it.key).first == netId }), isLocal = true, doNotify = false)
                    return
                }
                _state.value = _state.value.copy(
                    findOverlay = FindOverlay(
                        query = query,
                        matchIds = allMatches.map { it.id },
                        currentIndex = allMatches.lastIndex,
                        bufferKey = "GLOBAL:$netId",
                    )
                )
                append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_found_matches, allMatches.size, query), isLocal = true, doNotify = false)
                return
            }

            "flip" -> {
                // secret table-flip easter egg
                if (isPseudoBuffer(bufferName)) return
                val frames = listOf("(╯°□°)╯┬─┬", "(ノ°□°)ノ┻━┻")
                if (isDccChatBufferName(bufferName)) {
                    frames.forEachIndexed { i, f ->
                        if (i > 0) delay(800L)
                        sendDccChatLine(currentKey, f, isAction = false)
                    }
                    return
                }
                val rt = runtimes[netId] ?: return
                if (ageOn(netId, bufferName)) {
                    frames.forEachIndexed { i, f ->
                        if (i > 0) delay(800L)
                        sendAgeText(netId, currentKey, bufferName, f)
                    }
                    return
                }
                val myNick = _state.value.connections[netId]?.myNick ?: _state.value.myNick
                val enc = e2eKeyStore.get(netId, bufferName)?.scheme
                frames.forEachIndexed { i, f ->
                    if (i > 0) delay(800L)
                    val id = rt.client.privmsg(bufferName, f)
                    recordLocalSend(netId, currentKey, f, isAction = false, sendIds = listOf(id))
                    append(currentKey, from = myNick, text = f, isLocal = true, doNotify = false, encryption = enc)
                }
                return
            }

            "ignore" -> {
                val arg = cmdLine.substringAfter(' ', "").trim()
                val listOnly = arg.isBlank() || arg.equals("list", ignoreCase = true) || arg.equals("ls", ignoreCase = true)
                if (listOnly) {
                    val net = _state.value.networks.firstOrNull { it.id == netId }
                    val items = net?.ignoredNicks.orEmpty()
                    if (items.isEmpty()) {
                        append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_ignore_empty), isLocal = true, doNotify = false)
                    } else {
                        val shown = items.map { m ->
                            val types = IgnoreRules.typesFor(net?.ignoreLevels.orEmpty(), m)
                            if (types == IgnoreRules.DEFAULT_TYPES) m else "$m [${IgnoreRules.describe(types)}]"
                        }
                        append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_ignored_list, items.size, shown.joinToString(", ")), isLocal = true, doNotify = false)
                    }
                    return
                }
                val words = arg.split(Regex("\\s+")).filter { it.isNotBlank() }
                val canon = canonicalIgnoreNick(words.first())
                val types = IgnoreRules.parseTypes(words.drop(1))
                if (canon == null || types == null || types.isEmpty()) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_ignore_usage) + " " + appContext.getString(R.string.vm_ignore_types_usage), isLocal = true, doNotify = false)
                    return
                }
                ignoreNick(netId, canon)
                setIgnoreTypes(netId, canon, types)
                return
            }

            "unignore" -> {
                val arg = cmdLine.substringAfter(' ', "").trim()
                if (arg.isBlank()) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_unignore_usage), isLocal = true, doNotify = false)
                    return
                }
                val nick = arg.substringBefore(' ').trim()
                val canon = canonicalIgnoreNick(nick)
                if (canon == null) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_unignore_usage), isLocal = true, doNotify = false)
                    return
                }
                unignoreNick(netId, canon)
                return
            }

            "markread" -> {
                if (c == null) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_not_connected), doNotify = false)
                    return
                }
                // Without a timestamp, mark read up to the newest message: the spec requires
                // a real message time, which only the buffer knows.
                val args = cmdLine.substringAfter(' ', "").trim().split(' ').filter { it.isNotBlank() }
                if (args.size <= 1 && c.hasCap("draft/read-marker")) {
                    stampReadMarker(args.firstOrNull()?.let { resolveBufferKey(netId, it) } ?: currentKey)
                    return
                }
                c.handleSlashCommand(cmdLine, bufferName)
                return
            }
            "motd" -> {
                if (c == null) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_not_connected), doNotify = false)
                    return
                }
                // If user explicitly requests MOTD, show it even if we hide on connect
                runtimes[netId]?.apply { manualMotdAtMs = System.currentTimeMillis(); suppressMotd = false }
                val args = cmdLine.substringAfter(' ', "").trim()
                val line = if (args.isBlank()) "MOTD" else "MOTD $args"
                c.sendRaw(line)
                return
            }
            "names" -> {
                if (c == null) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_not_connected), doNotify = false)
                    return
                }

                val arg = cmdLine.substringAfter(' ', "").trim()
                val target = when {
                    arg.isNotBlank() -> arg.substringBefore(' ')
                    bufferName != "*server*" -> bufferName
                    else -> ""
                }

                if (target.isBlank()) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_names_usage), doNotify = false)
                    return
                }

                // Track this request so we can print a clean consolidated list.
                rt.namesRequests[namesKeyFold(target)] = NamesRequest(replyBufferKey = currentKey)
                c.sendRaw("NAMES $target")
                return
            }

            "me" -> {
                val msg = cmdLine.drop(2).trim()
                if (msg.isBlank()) return

                // DCC CHAT buffer: send over the DCC socket instead of IRC.
                if (isDccChatBufferName(bufferName)) {
                    sendDccChatLine(currentKey, msg, isAction = true)
                    return
                }
                if (c == null) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_not_connected), doNotify = false)
                    return
                }

                val target = if (bufferName == "*server*") return else bufferName
                if (ageOn(netId, target)) {
                    append(currentKey, from = null, doNotify = false,
                        text = "*** " + appContext.getString(R.string.vm_age_no_action, target))
                    return
                }
                // Route through ctcp() so privmsg()'s E2E hook gets to encrypt
                // the ACTION body when a per-target key is configured. Direct
                // c.sendRaw("PRIVMSG …") would bypass that hook and ship the
                // ACTION text in clear, which would silently break encryption
                // for /me lines only - a particularly confusing partial-failure
                // for anyone debugging "why does my chat message look encrypted
                // but my /me line doesn't?".
                val actionEnc = e2eKeyStore.get(netId, target)?.scheme
                val meNick = st.connections[netId]?.myNick ?: st.myNick
                // "ACTION " and the CTCP framing ride inside the payload.
                val actionBudget = (outgoingByteBudget(netId, target) - 9).coerceAtLeast(64)
                for (chunk in splitMessageByLength(msg, actionBudget)) {
                    val id = c.ctcp(target, "ACTION $chunk")
                    recordLocalSend(netId, currentKey, chunk, isAction = true, sendIds = listOf(id))
                    append(currentKey, from = meNick, text = chunk, isAction = true, isLocal = true, encryption = actionEnc)
                }
                return
            }

            "slap" -> {
                val victim = cmdLine.substringAfter(' ', "").trim().substringBefore(' ').trim()
                if (victim.isBlank()) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_slap_usage), doNotify = false)
                    return
                }
                val msg = "slaps $victim around a bit with ${SLAP_FISH.random()}"

                // DCC CHAT buffer: send over the DCC socket instead of IRC.
                if (isDccChatBufferName(bufferName)) {
                    sendDccChatLine(currentKey, msg, isAction = true)
                    return
                }
                if (c == null) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_not_connected), doNotify = false)
                    return
                }

                val target = if (bufferName == "*server*") return else bufferName
                if (ageOn(netId, target)) {
                    append(currentKey, from = null, doNotify = false,
                        text = "*** " + appContext.getString(R.string.vm_age_no_action, target))
                    return
                }
                // Route through ctcp() so privmsg()'s E2E hook can encrypt the ACTION
                // body when a per-target key is set, exactly like /me above.
                val id = c.ctcp(target, "ACTION $msg")
                val actionEnc = e2eKeyStore.get(netId, target)?.scheme
                recordLocalSend(netId, currentKey, msg, isAction = true, sendIds = listOf(id))
                append(currentKey, from = st.connections[netId]?.myNick ?: st.myNick, text = msg, isAction = true, isLocal = true, encryption = actionEnc)
                return
            }

            "banlist" -> {
                if (c == null) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_not_connected), doNotify = false)
                    return
                }
                val arg = cmdLine.substringAfter(' ', "").trim()
                val chan = when {
                    arg.isNotBlank() -> arg.substringBefore(' ')
                    isChannelOnNet(netId, bufferName) -> bufferName
                    else -> ""
                }
                if (chan.isBlank() || !isChannelOnNet(netId, chan)) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_banlist_usage), doNotify = false)
                    return
                }
                startBanList(netId, chan)
                c.sendRaw("MODE $chan +b")
                return
            }

            "quietlist" -> {
                val arg = cmdLine.substringAfter(' ', "").trim()
                val chan = when {
                    arg.isNotBlank() -> arg.substringBefore(' ')
                    isChannelOnNet(netId, bufferName) -> bufferName
                    else -> ""
                }
                if (chan.isBlank() || !isChannelOnNet(netId, chan)) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_quietlist_usage), doNotify = false)
                    return
                }
                if (c == null) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_not_connected), doNotify = false)
                    return
                }
                startQuietList(netId, chan)
                c.sendRaw("MODE $chan +q")
                return
            }

            "exceptlist" -> {
                val arg = cmdLine.substringAfter(' ', "").trim()
                val chan = when {
                    arg.isNotBlank() -> arg.substringBefore(' ')
                    isChannelOnNet(netId, bufferName) -> bufferName
                    else -> ""
                }
                if (chan.isBlank() || !isChannelOnNet(netId, chan)) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_exceptlist_usage), doNotify = false)
                    return
                }
                if (c == null) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_not_connected), doNotify = false)
                    return
                }
                startExceptList(netId, chan)
                c.sendRaw("MODE $chan +e")
                return
            }

            "invexlist" -> {
                val arg = cmdLine.substringAfter(' ', "").trim()
                val chan = when {
                    arg.isNotBlank() -> arg.substringBefore(' ')
                    isChannelOnNet(netId, bufferName) -> bufferName
                    else -> ""
                }
                if (chan.isBlank() || !isChannelOnNet(netId, chan)) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_invexlist_usage), doNotify = false)
                    return
                }
                if (c == null) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_not_connected), doNotify = false)
                    return
                }
                startInvexList(netId, chan)
                c.sendRaw("MODE $chan +I")
                return
            }

            "close" -> {
                // Close the current buffer, or a specific buffer name on this network.
                val arg = cmdLine.substringAfter(' ', "").trim()
                val targetName = when {
                    arg.isNotBlank() -> arg.substringBefore(' ')
                    else -> bufferName
                }
                if (targetName.isBlank() || targetName == "*server*") return
                val key = if (arg.isBlank()) currentKey else resolveBufferKey(netId, targetName)
                if (isChannelOnNet(netId, targetName)) {
                    val cli = runtimes[netId]?.client
                    if (cli != null) {
                        pendingCloseAfterPart.add(key)
                        // Close immediately; we still send PART but suppress recreating the buffer on the echo.
                        removeBuffer(key)
                        cli.sendRaw("PART $targetName")
                    } else {
                        removeBuffer(key)
                    }
                } else {
                    removeBuffer(key)
                }
                return
            }

            "closekey" -> {
                // Internal helper used by the sidebar X button: /closekey <netId>::<buffer>
                val arg = cmdLine.substringAfter(' ', "").trim()
                if (arg.isBlank() || !arg.contains("::")) return
                val (targetNet, targetName) = splitKey(arg)
                if (targetNet.isBlank() || targetName.isBlank() || targetName == "*server*") return
                // IMPORTANT: keep the *exact* buffer key the user clicked.
                // (If we resolve/normalize here, we can end up closing a different buffer when
                // duplicate buffers exist due to case differences, leaving the clicked one stuck.)
                val key = arg
                if (isChannelOnNet(targetNet, targetName)) {
                    val cli = runtimes[targetNet]?.client
                    if (cli != null) {
                        pendingCloseAfterPart.add(key)
                        // Close immediately; we still send PART but suppress recreating the buffer on the echo.
                        removeBuffer(key)
                        cli.sendRaw("PART $targetName")
                    } else {
                        removeBuffer(key)
                    }
                } else {
                    removeBuffer(key)
                }
                return
            }

            "dcc" -> {
                // /dcc chat|schat <nick>, /dcc send <nick> [type], /dcc accept|reject [nick],
                // /dcc close [nick]. The nick defaults to the peer of the DCC chat or query typed in.
                val rest = cmdLine.substringAfter(' ', "").trim()
                val sub = rest.substringBefore(' ').lowercase(Locale.ROOT)
                val args = rest.substringAfter(' ', "").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                val defaultNick = when {
                    isDccChatBufferName(bufferName) -> bufferName.removePrefix("DCCCHAT:")
                    !bufferName.startsWith("*") && !isPseudoBuffer(bufferName) && !isChannelOnNet(netId, bufferName) -> bufferName
                    else -> ""
                }
                val nick = args.firstOrNull() ?: defaultNick
                val say = { t: String -> append(currentKey, from = null, text = "*** $t", doNotify = false) }
                when (sub) {
                    "send" -> {
                        // Optional MIME filter for the picker, e.g. image/*. Nicks can't
                        // contain '/', so on its own it means the default nick.
                        val mimeArg = args.getOrNull(if (args.size == 1) 0 else 1)?.takeIf { DCC_MIME_FILTER.matches(it) }
                        val target = if (args.size == 1 && mimeArg != null) defaultNick else nick
                        val mime = mimeArg ?: "*/*"
                        when {
                            target.isBlank() || isChannelOnNet(netId, target) -> say(appContext.getString(R.string.vm_dcc_chat_usage))
                            !_state.value.settings.dccEnabled -> say(appContext.getString(R.string.vm_dcc_disabled))
                            c?.isConnectedNow() != true && _state.value.connections[netId]?.connected != true ->
                                say(appContext.getString(R.string.vm_not_connected))
                            else -> requestDccSendPick(target, netIdArg = netId, mimeFilter = mime, confirm = true, statusKey = currentKey)
                        }
                    }
                    "chat", "schat" -> {
                        if (nick.isBlank()) say(appContext.getString(R.string.vm_dcc_chat_usage))
                        else startDccChatFlow(nick, secure = if (sub == "schat") true else null, netIdArg = netId)
                    }
                    "accept", "reject" -> {
                        val offer = _state.value.dccChatOffers.lastOrNull {
                            it.netId == netId && (nick.isBlank() || it.from.equals(nick, ignoreCase = true))
                        }
                        when {
                            offer == null -> say(
                                if (nick.isBlank()) appContext.getString(R.string.vm_dcc_chat_usage)
                                else appContext.getString(R.string.vm_dcc_no_chat_offer, nick)
                            )
                            sub == "accept" -> acceptDccChat(offer)
                            else -> rejectDccChat(offer)
                        }
                    }
                    "close" -> {
                        if (nick.isBlank()) {
                            say(appContext.getString(R.string.vm_dcc_chat_usage))
                        } else {
                            val key = findDccChatKey(netId, nick)
                            val pending = dccChatPending.remove(key)?.also { it.cancel() }
                            when {
                                dccChatSessions.containsKey(key) -> closeDccChatSession(key)
                                pending != null -> say(appContext.getString(R.string.vm_dcc_chat_closed))
                                else -> say(appContext.getString(R.string.vm_dcc_chat_not_connected))
                            }
                        }
                    }
                    else -> say(appContext.getString(R.string.vm_dcc_chat_usage))
                }
                return
            }

            "mode" -> {
                if (c == null) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_not_connected), doNotify = false)
                    return
                }

                // Intercept MODE list requests (e.g. +b, +q, +e, +I) so we can populate the list UI.
                val args = cmdLine.substringAfter(' ', "").trim()
                val toks = args.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (toks.isNotEmpty()) {
                    val t0 = toks.getOrNull(0).orEmpty()
                    val t1 = toks.getOrNull(1)
                    val t2 = toks.getOrNull(2)

                    val (chan, modeTok, maskTok) = if (isChannelOnNet(netId, t0)) {
                        Triple(t0, t1, t2)
                    } else if (isChannelOnNet(netId, bufferName)) {
                        Triple(bufferName, t0, t1)
                    } else {
                        Triple("", null, null)
                    }

                    val modeNorm = modeTok?.trim()
                    val isListQuery = (maskTok == null)
                    if (chan.isNotBlank() && isListQuery) {
                        when (modeNorm) {
                            "+b", "b" -> {
                                startBanList(netId, chan)
                                c.sendRaw("MODE $chan +b")
                                return
                            }
                            "+q", "q" -> {
                                startQuietList(netId, chan)
                                c.sendRaw("MODE $chan +q")
                                return
                            }
                            "+e", "e" -> {
                                startExceptList(netId, chan)
                                c.sendRaw("MODE $chan +e")
                                return
                            }
                            "+I", "I" -> {
                                startInvexList(netId, chan)
                                c.sendRaw("MODE $chan +I")
                                return
                            }
                        }
                    }
                }

                // Default MODE handling
                c.handleSlashCommand(cmdLine, bufferName)
                return
            }

            else -> {
                // Unknown command: first see if it's a user alias. Built-in commands
                // matched their own case above and never reach here, so an alias can
                // never shadow a built-in. Expand one level per re-dispatch, bounded by
                // aliasDepth so an alias-invokes-alias chain can't loop forever.
                val aliasTemplate = st.settings.commandAliases[cmd]
                if (aliasTemplate != null && aliasDepth < MAX_ALIAS_DEPTH) {
                    val aliasArgs = cmdLine.substringAfter(' ', "").trim()
                    val profile = st.networks.firstOrNull { it.id == netId }
                    val expanded = expandAlias(
                        aliasTemplate, bufferName, aliasArgs,
                        st.connections[netId]?.myNick ?: st.myNick,
                        profile?.name ?: "",
                        profile?.host ?: "",
                    )
                    // The expansion runs as a command whether or not it was written with a leading slash.
                    val asCommand = "/" + expanded.trimStart().removePrefix("/")
                    if (asCommand.length > 1) runInput(asCommand, aliasDepth + 1, currentKey, fromUser, pasteConfirmed)
                    return
                }
                // A loaded .hex script may register this as a command (every script `alias`
                // becomes one via registerCommand). Route it to the engine before handing off
                // to the server, so a script command like /tr reaches its handler. This runs
                // even when offline, since script commands (translate, tools, ...) don't need
                // an IRC connection.
                if (scriptEngine.hasCommand(cmd)) {
                    scriptEngine.runCommand(cmd, cmdLine.substringAfter(' ', "").trim(), netId, bufferName)
                    return
                }
                if (c == null) {
                    append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_not_connected), doNotify = false)
                    return
                }
                // Let the IRC client handle it
                c.handleSlashCommand(cmdLine, bufferName)
                return
            }
        }
    }

    // Text for the buffer. "//" sends a line that starts with a slash.
    val textBody = if (SlashInput.isEscaped(strippedForCommandCheck)) SlashInput.unescape(trimmed) else trimmed

    // Keep the user's line structure: the send path below sends one PRIVMSG per line, or
    // one multiline BATCH where the server supports it. DCC CHAT and +AGE still use the
    // flattened form.
    val messageLines = textBody
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
        .dropWhile { it.isBlank() }
        .dropLastWhile { it.isBlank() }
    val fullMessage = messageLines.joinToString(" ") { it.trim() }
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    if (fullMessage.isEmpty()) return

    // The server window and the raw log are not message targets.
    if (isPseudoBuffer(bufferName)) {
        append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_server_buffer_text), isLocal = true, doNotify = false)
        return
    }

    if (isDccChatBufferName(bufferName)) {
        sendDccChatLine(currentKey, fullMessage, isAction = false)
        return
    }
    // Refuses to send when neither the socket nor the UI state reports a connection,
    // since a stale client can remain in `runtimes` after a failed reconnect.
    val liveConnected = c?.isConnectedNow() == true
    val stateConnected = _state.value.connections[netId]?.connected == true
    if (c == null || (!liveConnected && !stateConnected)) {
        append(currentKey, from = null, text = "*** " + appContext.getString(R.string.vm_not_connected), doNotify = false)
        return
    }
    if (ageOn(netId, bufferName)) {
        sendAgeText(netId, currentKey, bufferName, fullMessage)
        return
    }

    val myNick = st.connections[netId]?.myNick ?: st.myNick
    val sendEncryption = e2eKeyStore.get(netId, bufferName)?.scheme
    val maxMsgLen = outgoingByteBudget(netId, bufferName)

    // Paste guard: text that would go out as more lines than the limit waits for the user.
    if (fromUser && !pasteConfirmed && st.settings.pasteGuardEnabled) {
        val textLines = messageLines.filter { it.isNotBlank() }
        val chunks = textLines.flatMap { splitMessageByLength(it, maxMsgLen) }
        if (chunks.size > st.settings.pasteGuardLines.coerceAtLeast(1)) {
            val batched = textLines.size > 1 && c.multilineSendAvailable(bufferName)
            val waitMs = if (batched) 0L else c.pacedSendEstimateMs(chunks.map { it.length + bufferName.length + 10 })
            holdForPasteGuard(currentKey, textBody, chunks.size, waitMs, batched)
            return
        }
    }

    // Sending the message ends our typing state for receivers, so no "done" follows it.
    typingDoneJob?.cancel()
    typingDoneJob = null
    pendingTypingDone.remove(currentKey)?.cancel()
    if (typingLastKey == currentKey) typingLastKey = null

    // Multi-line input over a server that speaks IRCv3 multiline: one BATCH, so
    // the far side renders it as the single logical message the user typed
    // instead of N unrelated lines interleaved with other people's chatter.
    // multilineSendAvailable() returns false for encrypted targets, which fall
    // through to the per-line path below.
    if (messageLines.size > 1 && c.multilineSendAvailable(bufferName)) {
        // Null means nothing was sent (no usable content); fall through to the
        // per-line path. Otherwise one send id per batch, all of which need
        // recording or the echoes of batches 2..n show up as duplicates.
        val batchIds = c.privmsgMultiline(bufferName, messageLines)
        if (batchIds != null) {
            val joined = messageLines.joinToString("\n")
            recordLocalSend(netId, currentKey, joined, isAction = false, sendIds = batchIds)
            append(currentKey, from = myNick, text = joined, isLocal = true, encryption = sendEncryption, multiline = true)
            return
        }
    }

    // One PRIVMSG per input line, each split again if it exceeds the wire limit.
    for (line in messageLines) {
        if (line.isBlank()) continue
        for (chunk in splitMessageByLength(line, maxMsgLen)) {
            if (chunk.isEmpty()) continue
            val id = c.privmsg(bufferName, chunk)
            recordLocalSend(netId, currentKey, chunk, isAction = false, sendIds = listOf(id))
            append(currentKey, from = myNick, text = chunk, isLocal = true, encryption = sendEncryption)
        }
    }
}

/**
 * Payload budget in UTF-8 bytes for one PRIVMSG to [bufferName]: LINELEN minus an estimate of
 * the prefix and CRLF. For an encrypted target the budget is solved for the plaintext, since
 * splitting on the ciphertext would break decryption.
 */
internal fun IrcViewModel.outgoingByteBudget(netId: String, bufferName: String): Int {
    val serverLimit = runtimes[netId]?.support?.linelen ?: 512
    val baseBudget = (serverLimit - 100).coerceIn(200, serverLimit - 10)
    return when (e2eKeyStore.get(netId, bufferName)?.scheme) {
        com.boxlabs.hexdroid.crypto.E2eScheme.AGM ->
            ((baseBudget - 5) * 3 / 4) - 29 - 2          // "+AGM " + base64(1+12+P+16)
        com.boxlabs.hexdroid.crypto.E2eScheme.BLOWFISH ->
            ((baseBudget - 5) * 3 / 4) - 15 - 2          // "+OK *" + base64(8 IV + P + <=7 pad)
        com.boxlabs.hexdroid.crypto.E2eScheme.AGE ->
            ((baseBudget - 5) * 3 / 4) - 29 - 2          // +AGE is framed by AgeChannel and never
                                                         // reaches this path; mirrors AGM as a
                                                         // conservative fallback
        null -> baseBudget
    }.coerceAtLeast(64)
}

/**
 * Split [text] into pieces that each fit one PRIVMSG to [bufferName]. Every path sending
 * user text goes through this: an over-long line is silently truncated by the server,
 * and on a keyed target that loses the whole message rather than its tail.
 */
internal fun IrcViewModel.outgoingChunks(netId: String, bufferName: String, text: String): List<String> =
    splitMessageByLength(text, outgoingByteBudget(netId, bufferName))

/** True when +AGE is switched on for [target] on [netId]. */
internal fun IrcViewModel.ageOn(netId: String, target: String): Boolean =
    ageEnabledPrefs.getBoolean(ageKey(netId, target), false)

/**
 * Send [text] to [bufferName] over +AGE and echo it into [currentKey]: the channel group key
 * when keyed, or the PM ratchet (sent, or held until the handshake completes). Never puts
 * plaintext on the wire. If the secure session isn't running, key agreement is (re)started.
 */
internal fun IrcViewModel.sendAgeText(netId: String, currentKey: String, bufferName: String, text: String) {
    val bridge = ageBridgeFor(netId)
    if (bridge?.isActive(bufferName) != true) {
        if (isChannelTarget(bufferName)) bridge?.enableChat(bufferName) else bridge?.enablePm(bufferName)
    }
    val myNickNow = _state.value.connections[netId]?.myNick ?: _state.value.myNick
    if (isChannelTarget(bufferName) && bridge?.chatReady(bufferName) == true) {
        for (chunk in splitMessageByLength(text, 120)) {
            if (chunk.isEmpty()) continue
            if (bridge.sendChat(bufferName, chunk)) {
                append(currentKey, from = myNickNow, text = chunk, isLocal = true,
                       encryption = com.boxlabs.hexdroid.crypto.E2eScheme.AGE)
            } else append(currentKey, from = null, doNotify = false,
                       text = "*** " + appContext.getString(R.string.vm_age_send_failed))
        }
    } else if (!isChannelTarget(bufferName)) {
        // PM. sendOrHoldPm chooses: send now over the ratchet if it's up; self-key + AGE CHAT
        // if we've already decided the peer has no +AGE; otherwise HOLD the message. Held
        // messages are flushed through the ratchet the moment the handshake completes (so a
        // +AGE peer decrypts them properly), or self-keyed as garbled AGE CHAT if the grace
        // period below lapses with no AGE IDENT from the peer. We echo locally either way,
        // since it's our own text. Fail-closed throughout: nothing plaintext hits the wire.
        var anyHeldOrSent = false
        for (chunk in splitMessageByLength(text, 120)) {
            if (chunk.isEmpty()) continue
            when (bridge?.sendOrHoldPm(bufferName, chunk)) {
                com.boxlabs.hexdroid.script.cap.AgeScriptBridge.PmSend.SENT -> {
                    anyHeldOrSent = true
                    append(currentKey, from = myNickNow, text = chunk, isLocal = true,
                           encryption = com.boxlabs.hexdroid.crypto.E2eScheme.AGE)
                }
                com.boxlabs.hexdroid.script.cap.AgeScriptBridge.PmSend.HELD -> {
                    anyHeldOrSent = true
                    // Echo now, but marked pending: it's queued behind the handshake, not yet
                    // on the wire. onPmFlushed -> markAgePmDelivered clears it once it ships.
                    append(currentKey, from = myNickNow, text = chunk, isLocal = true,
                           encryption = com.boxlabs.hexdroid.crypto.E2eScheme.AGE, pending = true)
                }
                else -> append(currentKey, from = null, doNotify = false,
                           text = "*** " + appContext.getString(R.string.vm_age_send_failed))
            }
        }
        if (anyHeldOrSent) scheduleAgePmGrace(netId, bufferName, bridge)
    } else {
        // Channel that isn't keyed for us yet. With owner self-keying this only happens to a
        // non-owner during the brief window before the owner's invite arrives; it resolves on
        // its own, so keep the message short and transient rather than a hard failure.
        append(currentKey, from = null, doNotify = false, text =
            "*** " + appContext.getString(R.string.vm_age_still_keying, bufferName))
    }
}

/**
 * Split a message into chunks that don't exceed [maxLen] bytes (UTF-8).
 *
 * Uses a single-pass byte-slice strategy instead of repeated toByteArray() calls,
 * keeping allocations O(n) regardless of how many chunks the message produces.
 * Tries to split on a word boundary (space) when one falls in the back half of a chunk.
 */
internal fun IrcViewModel.splitMessageByLength(text: String, maxLen: Int): List<String> {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxLen) return listOf(text)

    val chunks = mutableListOf<String>()
    var byteOffset = 0

    while (byteOffset < bytes.size) {
        val remaining = bytes.size - byteOffset
        if (remaining <= maxLen) {
            chunks.add(String(bytes, byteOffset, remaining, Charsets.UTF_8))
            break
        }

        // End candidate: maxLen bytes from current offset.
        var end = byteOffset + maxLen

        // Walk back to a UTF-8 character boundary (continuation bytes start with 10xxxxxx).
        while (end > byteOffset && (bytes[end].toInt() and 0xC0) == 0x80) end--

        // Decode the candidate slice to find a word-boundary split point.
        val slice = String(bytes, byteOffset, end - byteOffset, Charsets.UTF_8)
        val lastSpace = slice.lastIndexOf(' ')
        val chunk = if (lastSpace > slice.length / 2) slice.substring(0, lastSpace) else slice

        chunks.add(chunk.trim())
        // Advance byte offset by the exact byte count of the chunk we actually kept.
        byteOffset += chunk.toByteArray(Charsets.UTF_8).size
        // Skip any leading whitespace at the new offset to avoid empty chunks.
        while (byteOffset < bytes.size && bytes[byteOffset] == ' '.code.toByte()) byteOffset++
    }

    return chunks.filter { it.isNotEmpty() }
}
