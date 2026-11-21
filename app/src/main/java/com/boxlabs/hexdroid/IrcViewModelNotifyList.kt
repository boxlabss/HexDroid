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
import kotlinx.coroutines.launch
import java.util.Locale

/** Builds MONITOR lines for a watch list, each under the IRC line limit. */
internal object MonitorLines {

    /** Longest target list per line, leaving room for "MONITOR + " and CRLF. */
    private const val MAX_TARGETS_CHARS = 400

    /** "MONITOR [sign] a,b,c" lines covering [nicks], at most [limit] nicks in total. */
    fun build(sign: Char, nicks: List<String>, limit: Int = Int.MAX_VALUE): List<String> {
        val wanted = nicks.map { it.trim() }.filter { it.isNotEmpty() && ',' !in it && ' ' !in it }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(limit.coerceAtLeast(0))
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (n in wanted) {
            if (cur.isNotEmpty() && cur.length + 1 + n.length > MAX_TARGETS_CHARS) {
                out += "MONITOR $sign $cur"
                cur.setLength(0)
            }
            if (cur.isNotEmpty()) cur.append(',')
            cur.append(n)
        }
        if (cur.isNotEmpty()) out += "MONITOR $sign $cur"
        return out
    }
}

/** The notify list of [netId], as saved on its profile. */
fun IrcViewModel.notifyListFor(netId: String): List<String> =
    _state.value.networks.firstOrNull { it.id == netId }?.notifyList.orEmpty()

/** True when [nick] is on [netId]'s notify list. */
fun IrcViewModel.isOnNotifyList(netId: String, nick: String): Boolean {
    val fold = nick.lowercase(Locale.ROOT)
    return notifyListFor(netId).any { it.lowercase(Locale.ROOT) == fold }
}

/**
 * Whether [netId]'s server offers MONITOR: null when not registered or not yet known.
 */
fun IrcViewModel.monitorAvailable(netId: String): Boolean? {
    val c = runtimes[netId]?.client ?: return null
    if (!c.registered) return null
    return c.monitorLimit != -1
}

/** Adds [nick] to [netId]'s notify list and starts watching it on a live connection. */
fun IrcViewModel.addToNotifyList(netId: String, nick: String) {
    val clean = nick.trim().trimStart('~', '&', '@', '%', '+')
    if (clean.isEmpty() || ' ' in clean || ',' in clean) return
    if (isOnNotifyList(netId, clean)) return
    viewModelScope.launch {
        runCatching {
            repo.updateNetworkProfile(netId) { p ->
                if (p.notifyList.any { it.equals(clean, ignoreCase = true) }) p else p.copy(notifyList = p.notifyList + clean)
            }
        }
    }
    val c = runtimes[netId]?.client ?: return
    if (c.registered && c.monitorLimit != -1) {
        viewModelScope.launch { MonitorLines.build('+', listOf(clean)).forEach { runCatching { c.sendRaw(it) } } }
    }
}

/** Removes [nick] from [netId]'s notify list and stops watching it. */
fun IrcViewModel.removeFromNotifyList(netId: String, nick: String) {
    val fold = nick.trim().lowercase(Locale.ROOT)
    viewModelScope.launch {
        runCatching {
            repo.updateNetworkProfile(netId) { p -> p.copy(notifyList = p.notifyList.filter { it.lowercase(Locale.ROOT) != fold }) }
        }
    }
    setNetConn(netId) { it.copy(watchOnline = it.watchOnline - fold, watchOffline = it.watchOffline - fold) }
    val c = runtimes[netId]?.client ?: return
    if (c.registered && c.monitorLimit != -1) {
        viewModelScope.launch { MonitorLines.build('-', listOf(nick.trim())).forEach { runCatching { c.sendRaw(it) } } }
    }
}

/**
 * Sends [netId]'s notify list to the server once per connection, after MONITOR support is
 * known. Called on registration and on each ISUPPORT line.
 */
internal fun IrcViewModel.maybeSendNotifyList(netId: String) {
    val rt = runtimes[netId] ?: return
    val c = rt.client
    if (!c.registered || c.monitorLimit == -1) return
    if (rt.notifyListSent) return
    rt.notifyListSent = true
    setNetConn(netId) { it.copy(watchOnline = emptySet(), watchOffline = emptySet(), monitorSupported = true) }
    val nicks = notifyListFor(netId)
    if (nicks.isEmpty()) return
    val lines = MonitorLines.build('+', nicks, c.monitorLimit)
    viewModelScope.launch { for (line in lines) runCatching { c.sendBackground(line) } }
}

/**
 * A MONITOR status change: tracks presence for the notify list and alerts when someone known
 * to be offline comes online. True when this is the first status of a notify list nick on this
 * connection.
 */
internal fun IrcViewModel.onWatchedStatus(netId: String, nick: String, online: Boolean): Boolean {
    val fold = nick.lowercase(Locale.ROOT)
    val before = _state.value.connections[netId]
    val wasOffline = before?.watchOffline?.contains(fold) == true
    val known = wasOffline || before?.watchOnline?.contains(fold) == true
    setNetConn(netId) {
        if (online) it.copy(watchOnline = it.watchOnline + fold, watchOffline = it.watchOffline - fold)
        else it.copy(watchOnline = it.watchOnline - fold, watchOffline = it.watchOffline + fold)
    }
    val listed = isOnNotifyList(netId, nick)
    if (!online || !wasOffline) return listed && !known
    val st = _state.value
    if (!st.settings.notificationsEnabled || !st.settings.notifyListAlerts) return false
    if (!listed) return false
    if (isBufferMuted(bufKey(netId, nick))) return false
    val netName = st.networks.firstOrNull { it.id == netId }?.name.orEmpty()
    runCatching { notifier.notifyWatchedOnline(netId, nick, netName) }
    return false
}

/** /notify [nick] lists or adds; /unnotify nick, or /notify -nick, removes. */
internal fun IrcViewModel.handleNotifyCommand(netId: String, currentKey: String, cmd: String, arg: String) {
    fun say(text: String) = append(currentKey, from = null, text = "*** $text", isLocal = true, doNotify = false)
    val nick = arg.substringBefore(' ').trim()
    val removing = cmd == "unnotify" || nick.startsWith("-")
    val target = nick.removePrefix("-").removePrefix("+")
    if (target.isEmpty()) {
        if (removing) {
            say(appContext.getString(R.string.vm_notify_usage))
            return
        }
        val list = notifyListFor(netId)
        if (list.isEmpty()) {
            say(appContext.getString(R.string.vm_notify_empty))
            return
        }
        val conn = _state.value.connections[netId]
        val shown = list.joinToString(", ") { n ->
            val f = n.lowercase(Locale.ROOT)
            when {
                conn?.watchOnline?.contains(f) == true -> "$n (${appContext.getString(R.string.notify_online)})"
                conn?.watchOffline?.contains(f) == true -> "$n (${appContext.getString(R.string.notify_offline)})"
                else -> n
            }
        }
        say(appContext.getString(R.string.vm_notify_list, list.size, shown))
        if (monitorAvailable(netId) == false) say(appContext.getString(R.string.notify_unsupported))
        return
    }
    if (removing) {
        if (!isOnNotifyList(netId, target)) {
            say(appContext.getString(R.string.vm_notify_not_listed, target))
            return
        }
        removeFromNotifyList(netId, target)
        say(appContext.getString(R.string.vm_notify_removed, target))
    } else {
        if (isOnNotifyList(netId, target)) {
            say(appContext.getString(R.string.vm_notify_already, target))
            return
        }
        addToNotifyList(netId, target)
        say(appContext.getString(R.string.vm_notify_added, target))
        if (monitorAvailable(netId) == false) say(appContext.getString(R.string.notify_unsupported))
    }
}
