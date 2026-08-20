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

package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive

/** Distance from the viewport edge at which a drag starts scrolling the list, in pixels. */
private const val AUTO_SCROLL_EDGE = 96f

/** Pixels per frame at the very edge of the viewport. */
private const val AUTO_SCROLL_MAX_STEP = 24f

/**
 * Drag-to-reorder for a list whose entries may span several rows: one entry is a
 * single row in the networks list, and a header plus its channels in the network sidebar.
 */
@Stable
class GroupReorderState internal constructor(
    private val listState: LazyListState,
) {
    /** Maps a lazy item key to the entry it belongs to. Refreshed on every composition. */
    internal var groupIdForKey: (Any) -> String? = { null }

    /** Entry currently held by the finger, or null when no drag is in progress. */
    var draggedId by mutableStateOf<String?>(null)
        private set

    /** Entry order to render, or null to use the stored order. */
    var previewOrder by mutableStateOf<List<String>?>(null)
        private set

    /** Offset to draw on the dragged entry's rows, in pixels. */
    var translation by mutableFloatStateOf(0f)
        private set

    internal var autoScrollStep by mutableFloatStateOf(0f)
        private set

    private var fingerTop = 0f
    private var anchored = false

    /** True when [id] is the entry being dragged. */
    fun isDragging(id: String?): Boolean = id != null && id == draggedId

    /** Begins a drag of [id] within [order]. */
    fun start(id: String, order: List<String>) {
        draggedId = id
        previewOrder = order
        translation = 0f
        autoScrollStep = 0f
        val span = spanOf(id)
        fingerTop = span?.first ?: 0f
        anchored = span != null
    }

    /**
     * Updates the drag. [totalOffsetY] is the cumulative finger travel since the drag
     * started; [canSwap] rejects a neighbour the dragged entry may not pass.
     */
    fun drag(
        totalOffsetY: Float,
        canSwap: (movedId: String, otherId: String) -> Boolean = { _, _ -> true },
    ) {
        val id = draggedId ?: return
        val order = previewOrder ?: return
        val span = spanOf(id) ?: return
        if (!anchored) {
            fingerTop = span.first - totalOffsetY
            anchored = true
        }

        val height = span.second - span.first
        val top = fingerTop + totalOffsetY
        val bottom = top + height
        val index = order.indexOf(id)
        if (index < 0) return

        // An entry has to clear its neighbour completely before the two change place.
        // A neighbour taller than the viewport is clipped and its far edge may never be
        // on screen, so the viewport edge stands in for it and the swap stays reachable.
        val info = listState.layoutInfo
        val viewStart = info.viewportStartOffset.toFloat()
        val viewEnd = info.viewportEndOffset.toFloat()

        // One swap per event. The list has not been laid out again yet, so neighbour
        // positions are a frame old; taking a single step keeps the decision honest and
        // the next event picks up where this one left off.
        val nextId = order.getOrNull(index + 1)
        val prevId = if (index > 0) order[index - 1] else null
        val next = nextId?.let { spanOf(it) }
        val prev = prevId?.let { spanOf(it) }

        var newTop: Float? = null
        if (nextId != null && next != null &&
            bottom > minOf(next.second, viewEnd - 1f) && canSwap(id, nextId)
        ) {
            previewOrder = order.toMutableList().also {
                it[index] = nextId
                it[index + 1] = id
            }
            newTop = span.first + (next.second - next.first)
        } else if (prevId != null && prev != null &&
            top < maxOf(prev.first, viewStart + 1f) && canSwap(id, prevId)
        ) {
            previewOrder = order.toMutableList().also {
                it[index] = prevId
                it[index - 1] = id
            }
            newTop = span.first - (prev.second - prev.first)
        }

        // Against the slot the entry is moving into, so the row stays under the finger
        // on the frame a swap happens instead of snapping and catching up.
        translation = top - (newTop ?: span.first)
        autoScrollStep = autoScrollStep(top, bottom)
    }

    /**
     * Ends the drag and returns the order the user dropped the entry into, or null when
     * nothing was dragged. The preview stays on screen until [clearPreview].
     */
    fun end(): List<String>? {
        val order = previewOrder
        draggedId = null
        translation = 0f
        autoScrollStep = 0f
        anchored = false
        return order
    }

    /** Drops the preview so the list goes back to the stored order. */
    fun clearPreview() {
        previewOrder = null
    }

    /** Abandons the drag without reordering anything. */
    fun cancel() {
        draggedId = null
        previewOrder = null
        translation = 0f
        autoScrollStep = 0f
        anchored = false
    }

    /** Keeps the finger anchored to the content when the list scrolls under it. */
    internal fun onAutoScrolled(pixels: Float) {
        fingerTop -= pixels
    }

    /** Top and bottom of every laid-out row belonging to [id], in viewport pixels. */
    private fun spanOf(id: String): Pair<Float, Float>? {
        var top = Float.MAX_VALUE
        var bottom = Float.MIN_VALUE
        for (item in listState.layoutInfo.visibleItemsInfo) {
            if (groupIdForKey(item.key) != id) continue
            val itemTop = item.offset.toFloat()
            val itemBottom = itemTop + item.size
            if (itemTop < top) top = itemTop
            if (itemBottom > bottom) bottom = itemBottom
        }
        return if (bottom < top) null else top to bottom
    }

    private fun autoScrollStep(top: Float, bottom: Float): Float {
        val info = listState.layoutInfo
        val start = info.viewportStartOffset.toFloat()
        val end = info.viewportEndOffset.toFloat()
        if (end - start <= AUTO_SCROLL_EDGE * 2) return 0f
        return when {
            top < start + AUTO_SCROLL_EDGE ->
                -ramp((start + AUTO_SCROLL_EDGE - top) / AUTO_SCROLL_EDGE)
            bottom > end - AUTO_SCROLL_EDGE ->
                ramp((bottom - (end - AUTO_SCROLL_EDGE)) / AUTO_SCROLL_EDGE)
            else -> 0f
        }
    }

    private fun ramp(fraction: Float): Float =
        AUTO_SCROLL_MAX_STEP * fraction.coerceIn(0f, 1f)
}

/**
 * Remembers a [GroupReorderState] for [listState] and scrolls the list while a dragged
 * entry is held near the top or bottom edge, so an entry can be moved past the rows that
 * are currently off screen. [groupIdForKey] maps a lazy item key to its entry id.
 */
@Composable
fun rememberGroupReorderState(
    listState: LazyListState,
    groupIdForKey: (Any) -> String?,
): GroupReorderState {
    val state = remember(listState) { GroupReorderState(listState) }
    state.groupIdForKey = groupIdForKey

    LaunchedEffect(state.draggedId) {
        if (state.draggedId == null) return@LaunchedEffect
        while (isActive && state.draggedId != null) {
            val step = state.autoScrollStep
            if (step != 0f) state.onAutoScrolled(listState.scrollBy(step))
            withFrameNanos { }
        }
    }
    return state
}

/**
 * Turns the on-screen order the user dropped an entry into into the index pair the
 * view model expects, which are positions in the full stored order.
 */
fun reorderIndices(
    fullOrder: List<String>,
    droppedOrder: List<String>,
    movedId: String,
): Pair<Int, Int>? {
    val from = fullOrder.indexOf(movedId)
    if (from < 0) return null
    val remaining = fullOrder.toMutableList().also { it.removeAt(from) }

    val position = droppedOrder.indexOf(movedId)
    if (position < 0) return null
    val above = droppedOrder.take(position).lastOrNull { it in remaining }
    val to = if (above == null) 0 else remaining.indexOf(above) + 1
    return if (to == from) null else from to to.coerceIn(0, remaining.size)
}
