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

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Mark a composable as an intro-tour target.
 *
 * We record bounds in the root coordinate space so the overlay (hosted at AppRoot)
 * can accurately draw the spotlight.
 */
@Composable
fun Modifier.tourTarget(id: TourTarget): Modifier {
    val registry = LocalTourRegistry.current
    return this.then(
        Modifier.onGloballyPositioned { coords ->
            registry?.targets?.set(id, coords.boundsInRoot())
        }
    )
}