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
import com.boxlabs.hexdroid.IrcViewModel.PendingReaction
import com.boxlabs.hexdroid.IrcViewModel.ReactionResult
import com.boxlabs.hexdroid.data.IgnoreType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** User state, history, push, metadata, reactions and redactions. */
internal fun IrcViewModel.handleStateEvent(netId: String, ev: IrcEvent) {
    when (ev) {
        // IRCv3 CHGHOST: update user@host for nick in all shared channel nicklists.
        // The nicklist stores raw "prefix+nick" strings, not full masks, so we don't need
        // to update the display - just surface an info line in channels where the nick is present.
        is IrcEvent.Chghost -> {
            if (ev.isHistory) return
            val myNick = _state.value.connections[netId]?.myNick ?: return
            val isMe = casefoldText(netId, ev.nick) == casefoldText(netId, myNick)
            // Find channels where this nick is present
            val affectedChannels = _state.value.nicklists
                .filterKeys { it.startsWith("$netId::") }
                .filter { (_, list) ->
                    list.any { parseNickWithPrefixes(netId, it).first.let { b ->
                        casefoldText(netId, b) == casefoldText(netId, ev.nick) } }
                }
                .map { it.key }
            val line = if (isMe) "* " + appContext.getString(R.string.vm_ev_your_host_now, ev.newUser, ev.newHost)
                       else "* " + appContext.getString(R.string.vm_ev_host_now, ev.nick, ev.newUser, ev.newHost)
            for (k in affectedChannels) {
                append(k, from = null, text = line, timeMs = ev.timeMs, doNotify = false, isLocal = true)
            }
            if (affectedChannels.isEmpty()) {
                append(bufKey(netId, "*server*"), from = null, text = line, timeMs = ev.timeMs, doNotify = false, isLocal = true)
            }
        }

        // IRCv3 ACCOUNT: services account login/logout notification.
        is IrcEvent.AccountChanged -> {
            if (ev.isHistory) return
            val myNick = _state.value.connections[netId]?.myNick ?: return
            val isMe = casefoldText(netId, ev.nick) == casefoldText(netId, myNick)
            if (isMe) {
                val acct = ev.account.takeIf { it != "*" && it.isNotBlank() }
                setNetConn(netId) { it.copy(myAccount = acct) }
            }
            val line = when {
                ev.account == "*" -> if (isMe) "* " + appContext.getString(R.string.vm_ev_you_logged_out)
                                     else "* " + appContext.getString(R.string.vm_ev_logged_out, ev.nick)
                isMe -> "* " + appContext.getString(R.string.vm_ev_you_logged_in_as, ev.account)
                else -> "* " + appContext.getString(R.string.vm_ev_nick_logged_in_as, ev.nick, ev.account)
            }
            // Surface in channels where this nick is visible, or server buffer
            val affected = _state.value.nicklists
                .filterKeys { it.startsWith("$netId::") }
                .filter { (_, list) ->
                    list.any { parseNickWithPrefixes(netId, it).first.let { b ->
                        casefoldText(netId, b) == casefoldText(netId, ev.nick) } }
                }
                .map { it.key }
            val targets = if (affected.isNotEmpty()) affected else listOf(bufKey(netId, "*server*"))
            for (k in targets) {
                append(k, from = null, text = line, timeMs = ev.timeMs, doNotify = false, isLocal = true)
            }
        }

        // IRCv3 SETNAME: user changed their realname.
        is IrcEvent.Setname -> {
            if (ev.isHistory) return
            val myNick = _state.value.connections[netId]?.myNick ?: return
            val isMe = casefoldText(netId, ev.nick) == casefoldText(netId, myNick)
            val line = if (isMe) "* " + appContext.getString(R.string.vm_ev_your_realname_now, ev.newRealname)
                       else "* " + appContext.getString(R.string.vm_ev_realname_changed, ev.nick, ev.newRealname)
            val affected = _state.value.nicklists
                .filterKeys { it.startsWith("$netId::") }
                .filter { (_, list) ->
                    list.any { parseNickWithPrefixes(netId, it).first.let { b ->
                        casefoldText(netId, b) == casefoldText(netId, ev.nick) } }
                }
                .map { it.key }
            val targets = if (affected.isNotEmpty()) affected else listOf(bufKey(netId, "*server*"))
            for (k in targets) {
                append(k, from = null, text = line, timeMs = ev.timeMs, doNotify = false, isLocal = true)
            }
        }

        // Incoming channel invite.
        is IrcEvent.InviteReceived -> {
            if (isIgnoredSender(netId, ev.from, IgnoreType.INVITES)) return
            scriptEvent("INVITE", netId, "", ev.from, fields = mapOf("chan" to ev.channel))
            val serverKey = bufKey(netId, "*server*")
            val line = "* " + appContext.getString(R.string.vm_ev_invited_you, ev.from, ev.channel)
            append(serverKey, from = null, text = line, timeMs = ev.timeMs, doNotify = false, isLocal = false, isHighlight = true)
            // Also surface in the channel buffer if it already exists (e.g. we were kicked)
            val chanKey = resolveBufferKey(netId, ev.channel)
            if (_state.value.buffers.containsKey(chanKey)) {
                append(chanKey, from = null, text = "* " + appContext.getString(R.string.vm_invited_here, ev.from), timeMs = ev.timeMs, doNotify = false, isLocal = false)
            }
        }

        // Server-sent ERROR (fatal). IrcCore already emits Disconnected afterwards.
        is IrcEvent.ServerError -> {
            // Stash for the imminent Disconnected handler. IrcCore may report the
            // disconnect reason as generic "socket closed" while the actual rejection
            // text (SASL required, K-Lined, etc.) only came in via this ERROR frame.
            lastServerErrorByNet[netId] = ev.message to System.currentTimeMillis()
            val serverKey = bufKey(netId, "*server*")
            append(serverKey, from = null, text = "*** " + appContext.getString(R.string.vm_server_error, ev.message), doNotify = false, isLocal = false)
        }

        // Another user's away state (away-notify). The state is always tracked for the
        // nicklist; the inline line is skipped with hideAwayNotify, which has to be applied
        // here because bouncers forward away-notify regardless of our capabilities.
        is IrcEvent.AwayChanged -> {
            val awayMap = nickAwayState.getOrPut(netId) { mutableMapOf() }
            // Entries go on QUIT, and on PART or KICK once we share no channel. Anything else
            // left over, such as a nick change seen nowhere we are, is dropped at the cap.
            if (awayMap.size >= IrcViewModel.MAX_TRACKED_AWAY) {
                pruneAwayState(netId)
                if (awayMap.size >= IrcViewModel.MAX_TRACKED_AWAY) { awayMap.clear(); clearAwayNicksInState(netId) }
            }
            val fold = casefoldText(netId, ev.nick)
            val wasAway = awayMap.containsKey(fold)
            val suppressAnnouncement = _state.value.settings.hideAwayNotify
            if (ev.awayMessage != null) {
                // Nick set or changed away message.
                awayMap[fold] = ev.awayMessage
                markAwayInState(netId, ev.nick, true)
                if (!wasAway && !suppressAnnouncement) {
                    // Only print "went away" on transition (not on away-message updates).
                    val noReason = ev.awayMessage.isBlank() || ev.awayMessage == "*"
                    val msg = if (noReason) "* " + appContext.getString(R.string.vm_ev_now_away, ev.nick)
                              else "* " + appContext.getString(R.string.vm_ev_now_away_msg, ev.nick, ev.awayMessage)
                    val affected = _state.value.nicklists
                        .filterKeys { it.startsWith("$netId::") }
                        .filter { (_, list) ->
                            list.any { parseNickWithPrefixes(netId, it).first
                                .let { b -> casefoldText(netId, b) == fold } }
                        }.map { it.key }
                    for (k in affected) {
                        append(k, from = null, text = msg, timeMs = ev.timeMs, doNotify = false, isLocal = true)
                    }
                }
            } else {
                // Nick returned from away. The state is cleared whether or not we had
                // recorded them as away: an AWAY with no message means "not away", and
                // gating the clear on our own bookkeeping left the marker stuck on
                // whenever the two disagreed. Only the announcement needs a transition.
                awayMap.remove(fold)
                markAwayInState(netId, ev.nick, false)
                if (wasAway) {
                    if (!suppressAnnouncement) {
                        val msg = "* " + appContext.getString(R.string.vm_ev_back, ev.nick)
                        val affected = _state.value.nicklists
                            .filterKeys { it.startsWith("$netId::") }
                            .filter { (_, list) ->
                                list.any { parseNickWithPrefixes(netId, it).first
                                    .let { b -> casefoldText(netId, b) == fold } }
                            }.map { it.key }
                        for (k in affected) {
                            append(k, from = null, text = msg, timeMs = ev.timeMs, doNotify = false, isLocal = true)
                        }
                    }
                }
            }
        }

        // CAP NEW / CAP DEL - already logged by IrcSession via EmitStatus; just re-surface as server text.
        is IrcEvent.CapNew -> {
            val serverKey = bufKey(netId, "*server*")
            append(serverKey, from = null, text = "*** " + appContext.getString(R.string.vm_caps_added, ev.caps.joinToString(" ")), doNotify = false, isLocal = true)
        }
        is IrcEvent.CapDel -> {
            val serverKey = bufKey(netId, "*server*")
            append(serverKey, from = null, text = "*** " + appContext.getString(R.string.vm_caps_removed, ev.caps.joinToString(" ")), doNotify = false, isLocal = true)
        }

        is IrcEvent.BouncerNetwork -> handleBouncerNetwork(netId, ev)
        is IrcEvent.BouncerCommandResult -> handleBouncerCommandResult(netId, ev)
        is IrcEvent.SearchResults -> handleSearchResults(ev)
        is IrcEvent.MetadataSubscribed -> if (ev.key == SOJU_MUTED_KEY) finishMuteSync(netId)

        is IrcEvent.MonitorStatus -> {
            scriptEvent(if (ev.online) "NOTIFY" else "UNOTIFY", netId, "", ev.nick)
            // The first status of a notify list nick on a connection only fills in its presence.
            if (onWatchedStatus(netId, ev.nick, ev.online)) return
            // MONITOR: a watched nick came online or went offline.
            // Show a brief status line in the server buffer (and PM buffer if open).
            val statusLine = if (ev.online) "*** " + appContext.getString(R.string.vm_ev_monitor_online, ev.nick) else "*** " + appContext.getString(R.string.vm_ev_monitor_offline, ev.nick)
            val serverKey = bufKey(netId, "*server*")
            append(serverKey, from = null, text = statusLine, doNotify = false, isLocal = true, timeMs = ev.timeMs)
            // Also show in the PM buffer for that nick, if it exists.
            val pmKey = resolveBufferKey(netId, ev.nick)
            if (_state.value.buffers.containsKey(pmKey)) {
                append(pmKey, from = null, text = statusLine, doNotify = false, isLocal = true, timeMs = ev.timeMs)
            }
        }

        is IrcEvent.ReadMarker -> {
            // Server's view of the read marker, stored so the unread separator survives a
            // reconnect. A literal "*" means the server has none, which is not a
            // timestamp and must not be stored as one. The value only ever moves forward,
            // so a stale reply cannot drag the separator back over messages already read.
            val targetKey = resolveBufferKey(netId, ev.target)
            val incoming = ev.timestamp.takeIf { it != "*" }?.let { parseMarkReadMs(it) }
            if (incoming != null) {
                var readEverything = false
                _state.update { st ->
                    readEverything = false
                    val buf = st.buffers[targetKey] ?: return@update st
                    val known = buf.lastReadTimestamp?.let { parseMarkReadMs(it) }
                    if (known != null && incoming <= known) return@update st
                    // Read up to the newest message from someone else: nothing is unread.
                    val newest = buf.messages.lastOrNull { it.from != null && !isMyNick(netId, it.from) }?.timeMs
                    readEverything = newest == null || incoming >= newest
                    st.copy(buffers = st.buffers + (targetKey to buf.copy(
                        lastReadTimestamp = ev.timestamp,
                        unread = if (readEverything) 0 else buf.unread,
                        highlights = if (readEverything) 0 else buf.highlights,
                    )))
                }
                if (readEverything) {
                    val (_, bufName) = splitKey(targetKey)
                    notifier.cancelBuffer(netId, bufName)
                }
            }
        }

        is IrcEvent.HistoryRequestFailed -> {
            // No reply batch follows a rejection, so the request is closed here rather
            // than left for its watchdog. INVALID_TARGET means this buffer cannot be
            // queried at all, so the control is retired; the other codes may be
            // transient or specific to the selector used, and stay retryable.
            // A label names the request outright. Without a match, a backfill that went out
            // under a different label is not a candidate.
            val failed = ev.label?.let { chatHistory.failedRequest(it) }
            val key = when (failed) {
                is FailedHistoryRequest.Backfill -> failed.bufferKey
                FailedHistoryRequest.Catchup -> null
                else -> ev.target?.let { resolveBufferKey(netId, it) }
                    ?: chatHistory.soleOutstanding(netId, unlabelledOnly = ev.label != null)
            }
            if (key != null) {
                chatHistory.finish(key, BackfillOutcome.ABANDONED)
                if (ev.code == "INVALID_TARGET") {
                    setHistoryFlags(key, loading = false, exhausted = true)
                }
            }
        }

        is IrcEvent.SelfAwayChanged -> {
            if (ev.message == null) awayMessages.remove(netId)
            else awayMessages[netId] = ev.message
        }

        is IrcEvent.HistoryBatchStart -> {
            // Messages inside this batch are captured by the controller rather than added
            // one at a time, so the page can be merged once its full extent is known.
            chatHistory.onBatchOpen(resolveBufferKey(netId, ev.target), ev.label, ev.complete)
        }

        is IrcEvent.HistoryBatchEnd -> {
            val key = resolveBufferKey(netId, ev.target)
            chatHistory.onBatchClose(key, ev.label, ev.lines)
            // A replay that nothing followed still gets its boundary drawn. When this
            // batch was a backfill the merge does it instead, so nothing is pending here.
            // Lines still held for the saved log are not in the buffer yet, so the
            // boundary waits for them.
            if (deferredReplays[key].isNullOrEmpty()) {
                flushTrailingDivider(key)
                kickReactionFetch(key)
            } else {
                dividerAfterFlush.add(key)
            }
        }

        is IrcEvent.JoinHistoryWanted -> {
            val chanKey = resolveBufferKey(netId, ev.channel)
            ensureBuffer(chanKey)
            requestJoinCatchup(netId, chanKey, ev.channel, ev.limit)
        }

        is IrcEvent.HistoryTarget -> {
            // CHATHISTORY TARGETS: the server holds stored history for this buffer.
            if (isChannelOnNet(netId, ev.target)) return
            val targetKey = resolveBufferKey(netId, ev.target)
            ensureBuffer(targetKey)
            requestHistoryCatchup(netId, targetKey, ev.target)
        }

        is IrcEvent.OutgoingEcho -> {
            // notice format (`* <nick> text`) with the brackets reversed to mark it outgoing
            val destKey = resolveBufferKey(netId, ev.originBuffer)
            ensureBuffer(destKey)
            append(
                destKey,
                from = null,
                text = "* >${ev.target}< ${ev.text}",
                isLocal = true,
                doNotify = false,
            )
        }

        is IrcEvent.CtcpExchange -> {
            // Request and reply are shown as a pair, in the buffer the user is looking at
            val destKey = currentBufferOrServer(netId)
            ensureBuffer(destKey)
            val myNick = _state.value.connections[netId]?.myNick ?: _state.value.myNick
            append(
                destKey,
                from = null,
                text = "* <${ev.fromNick}> ${ev.request}",
                isLocal = true,
                doNotify = false,
            )
            if (ev.reply != null) {
                append(
                    destKey,
                    from = null,
                    text = "* <$myNick> ${ev.reply}",
                    isLocal = true,
                    doNotify = false,
                )
            }
        }

        is IrcEvent.WebPushVapidReady -> {
            // The key needed to bind the subscription is only now known.
            maybeRegisterWebPush(netId)
        }

        is IrcEvent.WebPushRegistered -> {
            // Re-arm the failure notice: a later rejection is news again now that the
            // server has accepted a subscription at least once.
            webPushFailureNotice.remove(netId)
        }

        is IrcEvent.WebPushUnregistered -> {
            webPushFailureNotice.remove(netId)
        }

        is IrcEvent.WebPushFailed -> {
            if (ev.subcommand.equals("REGISTER", true) && webPushFailureNotice.add(netId)) {
                append(
                    bufKey(netId, "*server*"),
                    from = null,
                    text = "*** " + appContext.getString(
                        R.string.vm_webpush_failed, ev.code, ev.message,
                    ),
                    doNotify = false,
                )
            }
        }

        is IrcEvent.TypingStatus -> {
            // draft/typing: update per-buffer typing indicator set.
            // Silently ignore if user has opted out of receiving typing indicators.
            if (!_state.value.settings.receiveTypingIndicator) return
            // Never show OURSELVES as typing.
            val selfNick = _state.value.connections[netId]?.myNick ?: runtimes[netId]?.myNick
            if (ev.nick.equals(selfNick, ignoreCase = true) || isRecentOwnNick(netId, ev.nick)) return
            // ev.target is the conversation, the channel or the other party, since the
            // core resolves a PM's recipient to the sender. Kept as a fallback for a
            // target that is a nick but not the sender.
            val bufferName = if (isChannelOnNet(netId, ev.target)) ev.target else ev.nick
            val targetKey = resolveBufferKey(netId, bufferName)
            _state.update { st ->
                val buf = st.buffers[targetKey] ?: return@update st
                val updatedTyping = when (ev.state) {
                    "active", "paused" -> buf.typingNicks + ev.nick
                    else /* "done" */  -> buf.typingNicks - ev.nick
                }
                st.copy(buffers = st.buffers + (targetKey to buf.copy(typingNicks = updatedTyping)))
            }
            // Expire the indicator if no "done" arrives: 6 s after "active", 30 s after "paused".
            val expiryKey = "$targetKey/${ev.nick}"
            receivedTypingExpiryJobs[expiryKey]?.cancel()
            if (ev.state == "active" || ev.state == "paused") {
                receivedTypingExpiryJobs[expiryKey] = viewModelScope.launch {
                    delay(if (ev.state == "active") 6_000L else 30_000L)
                    receivedTypingExpiryJobs.remove(expiryKey)
                    _state.update { st ->
                        val buf = st.buffers[targetKey] ?: return@update st
                        st.copy(buffers = st.buffers + (targetKey to buf.copy(typingNicks = buf.typingNicks - ev.nick)))
                    }
                }
            } else {
                receivedTypingExpiryJobs.remove(expiryKey)
            }
        }

        is IrcEvent.WhoxReply -> {
            // WHOX 354 reply: update away status from flags field.
            val fold = casefoldText(netId, ev.nick)

            // Track away state from WHOX flags ('G'=gone/away, 'H'=here).
            if (ev.isAway != null) {
                val awayMap = nickAwayState.getOrPut(netId) { mutableMapOf() }
                if (ev.isAway) {
                    awayMap.putIfAbsent(fold, "")  // Set away without overwriting a known message.
                    markAwayInState(netId, ev.nick, true)
                } else {
                    awayMap.remove(fold)
                    markAwayInState(netId, ev.nick, false)
                }
            }
            // WhoxReply account field currently informational; full account enrichment can
            // be added to the nicklist display in a future UI pass.
            if (ev.isBot) markBotInState(netId, ev.nick)
        }

        // draft/channel-rename: server renamed a channel we're in.
        // Update all buffer keys and nicklist keys that use the old name.
        is IrcEvent.ChannelRenamed -> {
            val oldKey = resolveBufferKey(netId, ev.oldName)
            val newKey = resolveBufferKey(netId, ev.newName)
            // Mutate in-memory nick maps BEFORE _state.value assignment (not inside update{} to
            // avoid double-execution on CAS retry).
            chanNickCase.remove(oldKey)?.let { chanNickCase[newKey] = it }
            chanNickStatus.remove(oldKey)?.let { chanNickStatus[newKey] = it }
            val st = _state.value
            val bufs = st.buffers.toMutableMap()
            val oldBuf = bufs.remove(oldKey)
            if (oldBuf != null) bufs[newKey] = oldBuf.copy(name = newKey)
            val nickLists = st.nicklists.toMutableMap()
            val oldNicks = nickLists.remove(oldKey)
            if (oldNicks != null) nickLists[newKey] = oldNicks
            val selectedBuf = if (st.selectedBuffer == oldKey) newKey else st.selectedBuffer
            _state.value = syncActiveNetworkSummary(st.copy(
                buffers = bufs,
                nicklists = nickLists,
                selectedBuffer = selectedBuf
            ))
            if (oldBuf != null) renameBufferState(oldKey, newKey)
        }

        // draft/message-reactions: an emoji reaction was added or removed.
        // Surface as a brief status line in the target buffer.
        is IrcEvent.MessageReaction -> {
            val bufKey = resolveBufferKey(netId, ev.target)
            val reaction = PendingReaction(
                parentId = ev.msgId,
                reaction = ev.reaction,
                fromNick = ev.fromNick,
                adding = ev.adding,
                timeMs = ev.timeMs,
                isHistory = ev.isHistory,
            )
            if (applyReaction(bufKey, reaction) != ReactionResult.MISSING) return
            // The message is not loaded. Fetch it, then attach; say it in words only if
            // it cannot be found.
            if (reaction.parentId != null && queueReaction(bufKey, reaction)) return
            reactionFallbackLine(bufKey, reaction)
        }

        is IrcEvent.MetadataChanged -> {
            if (ev.key == SOJU_MUTED_KEY) {
                noteSojuMuted(netId, ev.target, ev.value)
                return
            }
            // draft/metadata-2. Only the keys this client actually renders are
            // stored: an unbounded key-value store per nick would grow without
            // limit on a busy network, and nothing else reads it.
            val mdKey = ev.target.trim().lowercase()
            if (mdKey.isNotEmpty()) {
                when (ev.key) {
                    "display-name" -> setNetConn(netId) { st ->
                        val clean = sanitizeMetadataValue(ev.value, maxLen = 40)
                        st.copy(displayNames =
                            if (clean == null) st.displayNames - mdKey
                            else st.displayNames + (mdKey to clean))
                    }
                    "avatar" -> setNetConn(netId) { st ->
                        // Only https URLs are retained; the renderer additionally
                        // gates on the image-previews opt-in and an unproxied profile.
                        val url = sanitizeMetadataValue(ev.value, maxLen = 512)
                            ?.takeIf { it.startsWith("https://") }
                        st.copy(avatarUrls =
                            if (url == null) st.avatarUrls - mdKey
                            else st.avatarUrls + (mdKey to url))
                    }
                    "color" -> setNetConn(netId) { st ->
                        // Registry format is exactly 6 hex digits (no leading #).
                        // Anything else is ignored so a malformed value can't crash
                        // Colour parsing at render time.
                        val hex = ev.value?.trim()?.removePrefix("#")
                            ?.takeIf { s -> s.length == 6 && s.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
                            ?.lowercase()
                        st.copy(nickColors =
                            if (hex == null) st.nickColors - mdKey
                            else st.nickColors + (mdKey to hex))
                    }
                    "status" -> setNetConn(netId) { st ->
                        val clean = sanitizeMetadataValue(ev.value, maxLen = 60)
                        st.copy(statuses =
                            if (clean == null) st.statuses - mdKey
                            else st.statuses + (mdKey to clean))
                    }
                    "pronouns", "homepage", "bio" -> setNetConn(netId) { st ->
                        // Bounded whitelist of extra keys shown only in the tap sheet.
                        val clean = sanitizeMetadataValue(ev.value, maxLen = if (ev.key == "bio") 300 else 100)
                        val forNick = (st.extraMetadata[mdKey] ?: emptyMap())
                        val updated = if (clean == null) forNick - ev.key else forNick + (ev.key to clean)
                        st.copy(extraMetadata =
                            if (updated.isEmpty()) st.extraMetadata - mdKey
                            else st.extraMetadata + (mdKey to updated))
                    }
                    else -> { /* not a key we render for other users */ }
                }
                // Independently, mirror ANY key set on our own current nick into
                // ownMetadata so the editor can pre-fill every field, not just the
                // rendered ones. Single-target, so it stays bounded.
                setNetConn(netId) { st ->
                    if (!mdKey.equals(st.myNick, ignoreCase = true)) st
                    else {
                        val clean = sanitizeMetadataValue(ev.value, maxLen = 512)
                        st.copy(ownMetadata =
                            if (clean == null) st.ownMetadata - ev.key
                            else st.ownMetadata + (ev.key to clean))
                    }
                }
            }
        }

        is IrcEvent.AccountRegUpdate -> {
            val phase = when {
                ev.stage.startsWith("FAIL:") -> RegPhase.FAILED
                ev.stage == "SUCCESS" -> RegPhase.SUCCESS
                ev.stage == "VERIFICATION_REQUIRED" -> RegPhase.VERIFY_REQUIRED
                else -> RegPhase.IDLE
            }
            if (phase != RegPhase.IDLE) {
                setNetConn(netId) { st ->
                    st.copy(regState = RegState(
                        phase = phase,
                        // Keep a previously-known account when the server omits it
                        // (failures carry no account name).
                        account = ev.account ?: st.regState.account,
                        message = ev.message,
                    ))
                }
            }
        }

        is IrcEvent.MessageRedacted -> {
            // Replace the deleted message's text
            val chanKey = resolveBufferKey(netId, ev.target)
            val tombstone = if (ev.reason.isNullOrBlank())
                appContext.getString(R.string.vm_ev_message_deleted, ev.fromNick)
            else
                appContext.getString(R.string.vm_ev_message_deleted_reason, ev.fromNick, ev.reason)
            var replaced = false
            _state.update { st ->
                val buf = st.buffers[chanKey]
                val idx = buf?.messages?.indexOfLast { it.msgId != null && it.msgId == ev.msgId } ?: -1
                replaced = buf != null && idx >= 0
                if (buf == null || idx < 0) st
                else st.copy(buffers = st.buffers + (chanKey to buf.copy(log = buf.log.replaceAt(idx, buf.messages[idx].copy(text = tombstone)))))
            }
            if (!replaced) {
                append(chanKey, from = null,
                    text = "* " + appContext.getString(R.string.vm_deleted_message, ev.fromNick),
                    timeMs = ev.timeMs, doNotify = false, isLocal = true,
                    isHistory = ev.isHistory)
            }
        }

        // ChannelModeChanged: live simple-mode delta on a channel. We merge it into the
        // buffer's stored modeString so the Channel Tools toggles reflect the change
        // immediately, instead of only updating on a 324 snapshot. The readable status line
        // is emitted separately via ChannelModeLine in the MODE command handler.
        is IrcEvent.ChannelModeChanged -> {
            val chanKey = resolveBufferKey(netId, ev.channel)
            val buf = _state.value.buffers[chanKey] ?: return
            val support = runtimes[netId]?.support
            // Modes to ignore when tracking the channel's simple-mode letters:
            //  - prefix modes (o/v/h/q/a): per-user rank, handled by the nicklist, not the
            //    channel mode string.
            //  - type-A list modes (b/e/I/q…): per-mask lists, not channel-wide flags.
            val prefixModes = (support?.prefixModes ?: "qaohv").toSet()
            val listModes = support?.chanModes?.split(",")?.getOrNull(0)?.toSet()
                ?: setOf('b', 'e', 'I')
            val ignore = prefixModes + listModes

            // Apply the +/- delta to the existing letter set. Args (keys/limits/nicks) are
            // separate params and never appear in ev.modes, so only letters are processed.
            // LinkedHashSet keeps a stable order so the rebuilt string is deterministic.
            val current = buf.modeString ?: ""
            val letters = LinkedHashSet<Char>(current.removePrefix("+").toList())
            var adding = true
            for (c in ev.modes) {
                when (c) {
                    '+' -> adding = true
                    '-' -> adding = false
                    in ignore -> { /* not a channel-wide flag */ }
                    else -> if (adding) letters.add(c) else letters.remove(c)
                }
            }
            val merged = if (letters.isEmpty()) "" else "+" + letters.joinToString("")

            // modeDisplay carries parameters, and this event doesn't. A change to a
            // parameterised mode (type B/C: keys, limits) makes the stored parameters
            // wrong, so re-query rather than guess. Type-D changes can't invalidate the
            // parameter tail, so reuse it and just swap the letters.
            val paramModes = support?.chanModes?.split(",")?.let {
                (it.getOrNull(1).orEmpty() + it.getOrNull(2).orEmpty()).toSet()
            } ?: setOf('k', 'l')
            val touchesParams = ev.modes.any { it in paramModes }
            if (touchesParams) {
                // Deliberately NOT a re-query on every delta: a channel handing out
                // +o/-o would then issue a MODE per change and queue behind flood pacing.
                runtimes[netId]?.let { rt ->
                    viewModelScope.launch { runCatching { rt.client.querySilentModes(ev.channel) } }
                }
            }
            if (merged != current) {
                val tail = buf.modeDisplay?.substringAfter(' ', "").orEmpty()
                val nextDisplay = when {
                    merged.isEmpty() -> null
                    touchesParams -> buf.modeDisplay   // stale until the re-query lands
                    tail.isBlank() -> merged
                    else -> "$merged $tail"
                }
                _state.update {
                    val cur = it.buffers[chanKey] ?: return@update it
                    it.copy(buffers = it.buffers + (chanKey to cur.copy(
                        modeString = merged,
                        modeDisplay = nextDisplay,
                    )))
                }
            }
        }

        is IrcEvent.ReadReceipt -> {
            // Receipts are shown only to users who send them.
            if (!_state.value.settings.readReceiptsEnabled) return
            val key = resolveBufferKey(netId, ev.nick)
            _state.update { s ->
                val buf = s.buffers[key] ?: return@update s
                val read = buf.messages.lastOrNull { it.msgId == ev.msgId } ?: return@update s
                if ((buf.peerReadAtMs ?: Long.MIN_VALUE) >= read.timeMs) return@update s
                s.copy(buffers = s.buffers + (key to buf.copy(peerReadAtMs = read.timeMs)))
            }
        }

        is IrcEvent.ChannelModeChanges -> {
            // Scripts: RAWMODE once per line, BAN/UNBAN for each ban change.
            if (!ev.isHistory) {
                scriptEvent("RAWMODE", netId, ev.channel, ev.byNick, ev.modes, isMyNick(netId, ev.byNick))
                for (c in ev.changes) {
                    if (c.mode != 'b' || c.arg == null) continue
                    scriptEvent(
                        if (c.adding) "BAN" else "UNBAN", netId, ev.channel, ev.byNick,
                        text = c.arg, isMe = isMyNick(netId, ev.byNick), fields = mapOf("banmask" to c.arg),
                    )
                }
            }
        }

        is IrcEvent.CtcpRequest -> {
            // Scripts only: HexDroid answers the CTCPs it knows itself. Ignored nicks never
            // reach scripts, as for messages and notices.
            if (!ev.isHistory && !isIgnoredSender(netId, ev.from, IgnoreType.CTCP)) {
                val inChannel = isChannelOnNet(netId, ev.target)
                scriptEvent(
                    "CTCP", netId, if (inChannel) ev.target else "", ev.from,
                    text = listOf(ev.command, ev.args).filter { it.isNotEmpty() }.joinToString(" "),
                    isMe = isMyNick(netId, ev.from), isPrivate = !inChannel,
                )
            }
        }

        is IrcEvent.OpenQueryBuffer -> {
            // /query <nick> - open a PM buffer and switch to it.
            val key = resolveBufferKey(netId, ev.nick)
            ensureBuffer(key)
            openBuffer(key)
        }
        else -> Unit
    }
}
