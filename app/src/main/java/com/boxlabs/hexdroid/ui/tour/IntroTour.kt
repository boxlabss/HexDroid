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

import com.boxlabs.hexdroid.AppScreen


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


fun buildIntroTour(): List<IntroTourStep> {
    return listOf(
        IntroTourStep(
            screen = AppScreen.NETWORKS,
            target = TourTarget.NETWORKS_ADD_FAB,
            title = "Add a network",
            body = "Tap + to add a new network, or edit an existing one. HexDroid ships with a few defaults so you can get going quickly."
        ),
        IntroTourStep(
            screen = AppScreen.NETWORKS,
            target = TourTarget.NETWORKS_CONNECT_BUTTON,
            title = "Connect",
            body = "Connect to the selected network. Once connected, open chat to join channels and talk."
        ),
        IntroTourStep(
            screen = AppScreen.SETTINGS,
            target = TourTarget.SETTINGS_APPEARANCE_SECTION,
            title = "Settings",
            body = "Tweak settings such as appearance, fonts and other preferences here."
        ),
        IntroTourStep(
            screen = AppScreen.CHAT,
            target = TourTarget.CHAT_BUFFER_DRAWER,
            title = "Switcher",
            body = "This sidebar shows your server, channels, and private messages. Tap any item to switch between them."
        ),
        IntroTourStep(
            screen = AppScreen.CHAT,
            target = TourTarget.CHAT_OVERFLOW_BUTTON,
            title = "More actions",
            body = "This menu contains channel list, file transfers, settings, networks, and more."
        ),
        IntroTourStep(
            screen = AppScreen.CHAT,
            target = TourTarget.CHAT_INPUT,
            title = "Send messages",
            body = "Type here to chat. You can also use slash commands like /join #channel, /msg nick hi, /whois nick, etc."
        ),
        IntroTourStep(
            screen = AppScreen.TRANSFERS,
            target = TourTarget.TRANSFERS_ENABLE_DCC,
            title = "Enable DCC",
            body = "Turn on DCC to send/receive files. If you're behind NAT, Passive mode can help."
        ),
        IntroTourStep(
            screen = AppScreen.TRANSFERS,
            target = TourTarget.TRANSFERS_PICK_FILE,
            title = "Send a file",
            body = "Enter a target nick, then pick a file to send. (Requires DCC to be enabled.) Incoming offers appear on this screen too."
        ),
        IntroTourStep(
            screen = AppScreen.NETWORKS,
            target = TourTarget.NETWORKS_AFTERNET_ITEM,
            fallbackTarget = TourTarget.NETWORKS_ADD_FAB,
            title = "Need support?",
            body = "You can connect here if you need support in #HexDroid.",
            fallbackBody = "If you don't see AfterNET in your list, tap Add AfterNET (or +) to add it again. You can connect there for support in #HexDroid.",
            action = IntroTourAction(IntroTourActionId.ADD_AFTERNET, "Add AfterNET", fallbackOnly = true),
        ),
        IntroTourStep(
            screen = AppScreen.SETTINGS,
            target = TourTarget.SETTINGS_RUN_TOUR,
            title = "Run this tour again",
            body = "You can replay the walkthrough any time from Settings."
        )
    )
}