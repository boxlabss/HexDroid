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

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxlabs.hexdroid.ui.BubbleChat
import com.boxlabs.hexdroid.ui.theme.HexDroidIRCTheme

/**
 * One conversation shown in a notification bubble. It shares the app's ViewModel, so the
 * conversation is the same one the main window shows.
 */
class BubbleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val netId = intent.getStringExtra(NotificationHelper.EXTRA_NETWORK_ID).orEmpty()
        val buffer = intent.getStringExtra(NotificationHelper.EXTRA_BUFFER).orEmpty()
        if (netId.isBlank() || buffer.isBlank()) {
            finish()
            return
        }
        val vm = (application as HexDroidApp).ircViewModel
        val bufferKey = vm.bufferKeyFor(netId, buffer)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            HexDroidIRCTheme(
                themeMode = state.settings.themeMode,
                themeColor = state.settings.themeColor,
                themeColorCustomInt = state.settings.themeColorCustomInt,
                fontChoice = state.settings.fontChoice,
                customFontPath = state.settings.customFontPath,
            ) {
                BubbleChat(
                    vm = vm,
                    state = state,
                    bufferKey = bufferKey,
                    onOpenApp = {
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                action = NotificationHelper.ACTION_OPEN_CONVERSATION
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra(NotificationHelper.EXTRA_NETWORK_ID, netId)
                                putExtra(NotificationHelper.EXTRA_BUFFER, buffer)
                            }
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bufferKey()?.let { (application as HexDroidApp).ircViewModel.setBubbleShowing(it, true) }
    }

    override fun onPause() {
        bufferKey()?.let { (application as HexDroidApp).ircViewModel.setBubbleShowing(it, false) }
        super.onPause()
    }

    private fun bufferKey(): String? {
        val netId = intent.getStringExtra(NotificationHelper.EXTRA_NETWORK_ID).orEmpty()
        val buffer = intent.getStringExtra(NotificationHelper.EXTRA_BUFFER).orEmpty()
        if (netId.isBlank() || buffer.isBlank()) return null
        return (application as HexDroidApp).ircViewModel.bufferKeyFor(netId, buffer)
    }
}
