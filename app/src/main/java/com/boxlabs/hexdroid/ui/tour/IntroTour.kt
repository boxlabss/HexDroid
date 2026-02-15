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

package com.boxlabs.hexdroid.ui.tour

import android.content.Context
import com.boxlabs.hexdroid.AppScreen
import com.boxlabs.hexdroid.R


enum class IntroTourActionId { ADD_AFTERNET }

data class IntroTourAction(
    val id: IntroTourActionId,
    val label: String,
    val fallbackOnly: Boolean = false,
)
data class IntroTourStep(
    val screen: AppScreen,
    val target: TourTarget? = null,
    // Used when [target] is not currently present (e.g. user removed a default item)
    val fallbackTarget: TourTarget? = null,
    val title: String,
    val body: String,
    // copy to show when we fall back away from [target].
    val fallbackBody: String? = null,
    val action: IntroTourAction? = null,
)

/**
 * Build the intro tour with localised strings.
 * Falls back to English defaults when called without a context.
 */
fun buildIntroTour(context: Context? = null): List<IntroTourStep> {
    // Helper that resolves a string resource, or returns the English fallback.
    fun s(resId: Int, fallback: String): String = context?.getString(resId) ?: fallback

    return listOf(
        IntroTourStep(
            screen = AppScreen.NETWORKS,
            target = TourTarget.NETWORKS_ADD_FAB,
            title = s(R.string.tour_add_network_title, "Add a network"),
            body = s(R.string.tour_add_network_body, "Tap + to add a new network, or edit an existing one. HexDroid ships with a few defaults so you can get going quickly.")
        ),
        IntroTourStep(
            screen = AppScreen.NETWORKS,
            target = TourTarget.NETWORKS_CONNECT_BUTTON,
            title = s(R.string.tour_connect_title, "Connect"),
            body = s(R.string.tour_connect_body, "Connect to the selected network. Once connected, open chat to join channels and talk.")
        ),
        IntroTourStep(
            screen = AppScreen.SETTINGS,
            target = TourTarget.SETTINGS_APPEARANCE_SECTION,
            title = s(R.string.tour_settings_title, "Settings"),
            body = s(R.string.tour_settings_body, "Tweak settings such as appearance, fonts and other preferences here.")
        ),
        IntroTourStep(
            screen = AppScreen.CHAT,
            target = TourTarget.CHAT_BUFFER_DRAWER,
            title = s(R.string.tour_switcher_title, "Switcher"),
            body = s(R.string.tour_switcher_body, "This sidebar shows your server, channels, and private messages. Tap any item to switch between them.")
        ),
        IntroTourStep(
            screen = AppScreen.CHAT,
            target = TourTarget.CHAT_OVERFLOW_BUTTON,
            title = s(R.string.tour_more_title, "More actions"),
            body = s(R.string.tour_more_body, "This menu contains channel list, file transfers, settings, networks, and more.")
        ),
        IntroTourStep(
            screen = AppScreen.CHAT,
            target = TourTarget.CHAT_INPUT,
            title = s(R.string.tour_send_title, "Send messages"),
            body = s(R.string.tour_send_body, "Type here to chat. You can also use slash commands like /join #channel, /msg nick hi, /whois nick, etc.")
        ),
        IntroTourStep(
            screen = AppScreen.TRANSFERS,
            target = TourTarget.TRANSFERS_ENABLE_DCC,
            title = s(R.string.tour_dcc_title, "Enable DCC"),
            body = s(R.string.tour_dcc_body, "Turn on DCC to send/receive files. If you're behind NAT, Passive mode can help.")
        ),
        IntroTourStep(
            screen = AppScreen.TRANSFERS,
            target = TourTarget.TRANSFERS_PICK_FILE,
            title = s(R.string.tour_send_file_title, "Send a file"),
            body = s(R.string.tour_send_file_body, "Enter a target nick, then pick a file to send. (Requires DCC to be enabled.) Incoming offers appear on this screen too.")
        ),
        IntroTourStep(
            screen = AppScreen.NETWORKS,
            target = TourTarget.NETWORKS_AFTERNET_ITEM,
            fallbackTarget = TourTarget.NETWORKS_ADD_FAB,
            title = s(R.string.tour_support_title, "Need support?"),
            body = s(R.string.tour_support_body, "You can connect here if you need support in #HexDroid."),
            fallbackBody = s(R.string.tour_support_fallback, "If you don't see AfterNET in your list, tap Add AfterNET (or +) to add it again. You can connect there for support in #HexDroid."),
            action = IntroTourAction(IntroTourActionId.ADD_AFTERNET, s(R.string.tour_support_action, "Add AfterNET"), fallbackOnly = true),
        ),
        IntroTourStep(
            screen = AppScreen.SETTINGS,
            target = TourTarget.SETTINGS_RUN_TOUR,
            title = s(R.string.tour_replay_title, "Run this tour again"),
            body = s(R.string.tour_replay_body, "You can replay the walkthrough any time from Settings.")
        )
    )
}