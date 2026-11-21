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
import com.boxlabs.hexdroid.ui.FloatingWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the background idle time is checked. */
private const val AUTO_AWAY_TICK_MS = 60_000L

/** True while HexDroid is on screen: the app, an open bubble, or the floating window. */
private fun IrcViewModel.inUseForAutoAway(): Boolean =
    AppVisibility.isForeground || bubblesShowing.isNotEmpty() || FloatingWindow.isShowing

/**
 * The app came to the front or went to the background. Going to the background starts the
 * auto-away timer; coming back marks back every network that auto-away set away.
 */
internal fun IrcViewModel.onAutoAwayAppActive(active: Boolean) {
    if (active) {
        autoAwayJob?.cancel()
        autoAwayJob = null
        endAutoAway()
    } else {
        startAutoAwayTimer()
    }
}

/**
 * The user used a bubble or the floating window: marks back what auto-away set away and, with
 * the app in the background, starts the idle time again.
 */
internal fun IrcViewModel.onAutoAwayInteraction() {
    endAutoAway()
    if (!AppVisibility.isForeground) startAutoAwayTimer()
}

/** Waits until HexDroid has been off screen for the set time, then marks every network away. */
private fun IrcViewModel.startAutoAwayTimer() {
    autoAwayJob?.cancel()
    autoAwayJob = null
    val s = _state.value.settings
    if (!s.autoAwayEnabled) return
    val waitMs = s.autoAwayMinutes.coerceIn(1, 240) * 60_000L
    autoAwayJob = viewModelScope.launch {
        var idleSince = System.currentTimeMillis()
        while (true) {
            delay(minOf(AUTO_AWAY_TICK_MS, waitMs))
            if (AppVisibility.isForeground) return@launch
            val now = System.currentTimeMillis()
            if (inUseForAutoAway()) idleSince = now
            else if (now - idleSince >= waitMs) break
        }
        autoAwayActive = true
        for (netId in runtimes.keys.toList()) maybeApplyAutoAway(netId)
    }
}

/** Ends auto-away, marking back the networks it set away. */
private fun IrcViewModel.endAutoAway() {
    if (!autoAwayActive) return
    autoAwayActive = false
    returnFromAutoAway()
}

/**
 * Marks [netId] away while auto-away is in effect, unless the user set away themselves or it
 * is a bouncer connection and bouncers are excluded. Also called when a network registers.
 */
internal fun IrcViewModel.maybeApplyAutoAway(netId: String) {
    if (!autoAwayActive) return
    val s = _state.value.settings
    if (!s.autoAwayEnabled) return
    if (awayMessages.containsKey(netId)) return
    val rt = runtimes[netId] ?: return
    if (!rt.client.registered) return
    if (rt.client.config.isBouncer && !s.autoAwayOnBouncers) return
    val msg = s.autoAwayMessage.trim().ifBlank { appContext.getString(R.string.auto_away_default) }
    autoAwayNets.add(netId)
    viewModelScope.launch { runCatching { rt.client.handleSlashCommand("away $msg", "*server*") } }
}

/** Marks back the networks auto-away set away. One no longer connected keeps no away message. */
private fun IrcViewModel.returnFromAutoAway() {
    for (netId in autoAwayNets.toList()) {
        autoAwayNets.remove(netId)
        val c = runtimes[netId]?.client
        if (c != null && c.registered && c.isConnectedNow()) {
            viewModelScope.launch { runCatching { c.handleSlashCommand("back", "*server*") } }
        } else {
            awayMessages.remove(netId)
        }
    }
}
