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
 * Relies on Android's native resource system to fall back to default English
 * strings.xml if a specific localization is missing.
 */
fun buildIntroTour(context: Context): List<IntroTourStep> {
    return listOf(
        IntroTourStep(
            screen = AppScreen.NETWORKS,
            target = TourTarget.NETWORKS_ADD_FAB,
            title = context.getString(R.string.tour_add_network_title),
            body = context.getString(R.string.tour_add_network_body)
        ),
        IntroTourStep(
            screen = AppScreen.NETWORKS,
            target = TourTarget.NETWORKS_CONNECT_BUTTON,
            title = context.getString(R.string.tour_connect_title),
            body = context.getString(R.string.tour_connect_body)
        ),
        IntroTourStep(
            screen = AppScreen.SETTINGS,
            target = TourTarget.SETTINGS_APPEARANCE_SECTION,
            title = context.getString(R.string.tour_settings_title),
            body = context.getString(R.string.tour_settings_body)
        ),
        IntroTourStep(
            screen = AppScreen.CHAT,
            target = TourTarget.CHAT_BUFFER_DRAWER,
            title = context.getString(R.string.tour_switcher_title),
            body = context.getString(R.string.tour_switcher_body)
        ),
        IntroTourStep(
            screen = AppScreen.CHAT,
            target = TourTarget.CHAT_OVERFLOW_BUTTON,
            title = context.getString(R.string.tour_more_title),
            body = context.getString(R.string.tour_more_body)
        ),
        IntroTourStep(
            screen = AppScreen.CHAT,
            target = TourTarget.CHAT_INPUT,
            title = context.getString(R.string.tour_send_title),
            body = context.getString(R.string.tour_send_body)
        ),
        IntroTourStep(
            screen = AppScreen.TRANSFERS,
            target = TourTarget.TRANSFERS_ENABLE_DCC,
            title = context.getString(R.string.tour_dcc_title),
            body = context.getString(R.string.tour_dcc_body)
        ),
        IntroTourStep(
            screen = AppScreen.TRANSFERS,
            target = TourTarget.TRANSFERS_PICK_FILE,
            title = context.getString(R.string.tour_send_file_title),
            body = context.getString(R.string.tour_send_file_body)
        ),
        IntroTourStep(
            screen = AppScreen.NETWORKS,
            target = TourTarget.NETWORKS_AFTERNET_ITEM,
            fallbackTarget = TourTarget.NETWORKS_ADD_FAB,
            title = context.getString(R.string.tour_support_title),
            body = context.getString(R.string.tour_support_body),
            fallbackBody = context.getString(R.string.tour_support_fallback),
            action = IntroTourAction(IntroTourActionId.ADD_AFTERNET, context.getString(R.string.tour_support_action), fallbackOnly = true),
        ),
        IntroTourStep(
            screen = AppScreen.SETTINGS,
            target = TourTarget.SETTINGS_RUN_TOUR,
            title = context.getString(R.string.tour_replay_title),
            body = context.getString(R.string.tour_replay_body)
        )
    )
}
