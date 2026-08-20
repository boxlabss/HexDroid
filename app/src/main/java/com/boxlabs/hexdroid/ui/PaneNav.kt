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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Width of the section rail shown beside the content pane on TV and tablets. */
val RAIL_WIDTH = 260.dp

/**
 * True when the screen is wide enough to show a section rail beside the content,
 * or when running on a TV, where a persistent rail is the expected pattern and
 * removes one D-pad step from every section change.
 */
@Composable
fun useSideRailNav(): Boolean {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return isTvDevice() || widthDp >= 720
}

/**
 * One entry in a section rail or hub list: icon, label, optional summary line.
 * A single focus stop, so D-pad up/down walks the sections one at a time.
 */
@Composable
fun SectionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    selected: Boolean = false,
) {
    val shape = RoundedCornerShape(12.dp)
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface

    Surface(color = bg, contentColor = fg, shape = shape, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(shape)
                .focusHighlight(shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.fillMaxWidth()) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Vertical list of sections shown beside the content pane. [header] renders above
 * the entries and scrolls with them.
 */
@Composable
fun <T> SectionRail(
    entries: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable (T) -> ImageVector?)? = null,
    summary: (@Composable (T) -> String?)? = null,
    entryModifier: (@Composable (T) -> Modifier)? = null,
    header: @Composable (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (header != null) {
            item { header() }
        }
        items(entries.size) { index ->
            val entry = entries[index]
            val isSelected = entry == selected
            SectionRow(
                label = label(entry),
                summary = summary?.invoke(entry),
                icon = icon?.invoke(entry),
                selected = isSelected,
                onClick = { onSelect(entry) },
                modifier = (entryModifier?.invoke(entry) ?: Modifier)
                    .then(if (isSelected) Modifier.tvInitialFocus() else Modifier),
            )
        }
    }
}

/**
 * Scrollable tab strip used to switch sections on narrow screens.
 */
@Composable
fun <T> SectionTabs(
    entries: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = entries.indexOf(selected).coerceAtLeast(0)
    SecondaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier,
        edgePadding = 12.dp,
    ) {
        entries.forEachIndexed { index, entry ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSelect(entry) },
                modifier = Modifier.focusHighlight(RoundedCornerShape(8.dp)),
                text = {
                    Text(
                        label(entry),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }
    }
}
