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
    fun s(resId: Int, fallback: String): String = context?.getString(resId) ?: fallback

    return listOf(
        // 1. Networks list — the FAB to add a server
        IntroTourStep(
            screen = AppScreen.NETWORKS,
            target = TourTarget.NETWORKS_ADD_FAB,
            title  = s(R.string.tour_add_network_title, "Your networks"),
            body   = s(R.string.tour_add_network_body,
                "Tap + to add a server, or edit the ones already here. " +
                "HexDroid ships with a few defaults — Libera, Freenode, and AfterNET — " +
                "so you can connect straight away."),
        ),
        // 2. Networks list — the Connect button
        IntroTourStep(
            screen = AppScreen.NETWORKS,
            target = TourTarget.NETWORKS_CONNECT_BUTTON,
            title  = s(R.string.tour_connect_title, "Connect"),
            body   = s(R.string.tour_connect_body,
                "Tap Connect to open a connection. The button turns green when you're online. " +
                "You can have multiple networks connected at once."),
        ),
        // 3. Chat — buffer/channel switcher sidebar
        IntroTourStep(
            screen = AppScreen.CHAT,
            target = TourTarget.CHAT_BUFFER_DRAWER,
            title  = s(R.string.tour_switcher_title, "Buffer list"),
            body   = s(R.string.tour_switcher_body,
                "This panel lists every server, channel, and private message you have open. " +
                "Swipe or tap the ☰ button to show it. " +
                "Tap any item to switch to it — unread counts are shown here too."),
        ),
        // 4. Chat — input box
        IntroTourStep(
            screen = AppScreen.CHAT,
            target = TourTarget.CHAT_INPUT,
            title  = s(R.string.tour_send_title, "Send messages"),
            body   = s(R.string.tour_send_body,
                "Type here to chat. Slash commands work too: " +
                "/join #channel, /msg nick text, /part, /whois nick, /me action text, and more."),
        ),
        // 5. Chat — overflow menu
        IntroTourStep(
            screen = AppScreen.CHAT,
            target = TourTarget.CHAT_OVERFLOW_BUTTON,
            title  = s(R.string.tour_more_title, "More actions"),
            body   = s(R.string.tour_more_body,
                "This menu reaches channel list, file transfers, ignore list, network settings, " +
                "and more. It also shows channel mode and topic management when you're an op."),
        ),
        // 6. Settings — appearance section
        IntroTourStep(
            screen = AppScreen.SETTINGS,
            target = TourTarget.SETTINGS_APPEARANCE_SECTION,
            title  = s(R.string.tour_settings_title, "Appearance & settings"),
            body   = s(R.string.tour_settings_body,
                "Choose a theme, adjust font size, tweak notification sounds, privacy options, " +
                "and much more. Changes apply instantly — no restart needed."),
        ),
        // 7. Transfers — enable DCC toggle
        IntroTourStep(
            screen = AppScreen.TRANSFERS,
            target = TourTarget.TRANSFERS_ENABLE_DCC,
            title  = s(R.string.tour_dcc_title, "File transfers (DCC)"),
            body   = s(R.string.tour_dcc_body,
                "Enable DCC to send and receive files directly between users. " +
                "If you're behind a router, Passive DCC avoids port-forwarding headaches."),
        ),
        // 8. Transfers — pick file button
        IntroTourStep(
            screen = AppScreen.TRANSFERS,
            target = TourTarget.TRANSFERS_PICK_FILE,
            title  = s(R.string.tour_send_file_title, "Send a file"),
            body   = s(R.string.tour_send_file_body,
                "Enter the target nick, then pick a file from storage. " +
                "Incoming DCC offers also arrive here — accept or reject them from this screen."),
        ),
        // 9. Networks — AfterNET support channel
        IntroTourStep(
            screen = AppScreen.NETWORKS,
            target = TourTarget.NETWORKS_AFTERNET_ITEM,
            fallbackTarget = TourTarget.NETWORKS_ADD_FAB,
            title  = s(R.string.tour_support_title, "Need help?"),
            body   = s(R.string.tour_support_body,
                "Connect to AfterNET and join #HexDroid for support, feature requests, " +
                "or just to chat with other HexDroid users."),
            fallbackBody = s(R.string.tour_support_fallback,
                "AfterNET isn't in your list yet — tap Add AfterNET to add it, " +
                "then connect and join #HexDroid for support."),
            action = IntroTourAction(
                IntroTourActionId.ADD_AFTERNET,
                s(R.string.tour_support_action, "Add AfterNET"),
                fallbackOnly = true,
            ),
        ),
        // 10. Settings — replay tour button
        IntroTourStep(
            screen = AppScreen.SETTINGS,
            target = TourTarget.SETTINGS_RUN_TOUR,
            title  = s(R.string.tour_replay_title, "Replay this tour"),
            body   = s(R.string.tour_replay_body,
                "You can run the walkthrough again any time from Settings → About. Tap Done to finish."),
        ),
    )
}