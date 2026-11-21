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

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.boxlabs.hexdroid.NickStyle
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Renders one [UiMessage] in the chat list. A separate composable so Compose can skip it when its
 * parameters are unchanged; they are all primitives, stable data classes or lambdas. State changes
 * surface as callbacks, so it holds no state beyond the per-item [swipeOffsetX] animation.
 */
@Composable
internal fun SingleMessageItem(
    m: UiMessage,
    /** Our message has been read by the other person (read receipts). */
    readByPeer: Boolean = false,
    ts: String,
    /** Brackets drawn around the sender's nick. */
    nickStyle: NickStyle,
    /** Colour for the timestamp span, or null to use the message colour. */
    timestampColor: Color?,
    // Pre-computed booleans so the item doesn't capture find/flicker state objects.
    isFlickering: Boolean,
    flickerAlphaValue: Float,       // Float, not Animatable, so Compose sees a stable param
    isFindMatch: Boolean,
    isFindCurrent: Boolean,
    findHighlight: String?,
    isSelectedForCopy: Boolean,
    /** True when the user has tapped "show more" on this message. */
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    copyRangeMode: Boolean,
    selBufName: String,
    messages: List<UiMessage>,
    reversedMessages: List<UiMessage>,
    listState: LazyListState,
    msgIdToDisplayIdx: Map<Long, Int>,
    /** IRCv3 msgid (String) → display index — used for O(1) reply-quote scroll. */
    msgStrToDisplayIdx: Map<String, Int>,
    /** Fetch the messages around a reply's parent when it is older than the loaded window. */
    onFetchReplyContext: ((String) -> Unit)? = null,
    /** Our nick, so a reaction we are part of is drawn as ours. */
    myNick: String? = null,
    /** Add or remove our own reaction: msgid, reaction text, whether it is currently ours. */
    onToggleReaction: ((String, String, Boolean) -> Unit)? = null,
    /** Open the typed-reaction prompt for a message that already has reactions. */
    onAddReaction: ((String) -> Unit)? = null,
    /** Show who reacted, starting on the given reaction. */
    onShowReactions: ((String) -> Unit)? = null,
    /** IRCv3 msgid (String) → (from, text) — used for O(1) reply label rendering. */
    msgIdToText: Map<String, Pair<String?, String>>,
    scope: CoroutineScope,
    chatTextStyle: androidx.compose.ui.text.TextStyle,
    /** Space above this message, from the chat line spacing setting. 0.dp on Tight. */
    itemGap: androidx.compose.ui.unit.Dp = 0.dp,
    motdStyle: androidx.compose.ui.text.TextStyle,
    motdFontSizeSp: Float,
    linkStyle: SpanStyle,
    onAnnotationClick: (String, String) -> Unit,
    colorizeNicks: Boolean,
    mircColorsEnabled: Boolean,
    ansiColorsEnabled: Boolean,
    imagePreviewsEnabled: Boolean,
    imagePreviewsWifiOnly: Boolean,
    /** Show the linked page's title under the message. */
    linkPreviewsEnabled: Boolean = false,
    nickColor: (String) -> Color,
    displayNick: (String) -> String,
    // Callbacks for state mutations that live in the parent.
    onToggleSelected: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeReply: () -> Unit,
    /**
     * draft/metadata-2 display-name for this message's sender, already sanitised,
     * or null when unset / identical to the nick. Rendered AFTER the real nick
     * rather than replacing it: a display name is attacker-chosen free text, so
     * substituting it for the nick would let anyone impersonate another user.
     */
    displayName: String? = null,
) {
    val fromNick = m.from
    // Local copy of ChatScreen's baseNick — strips IRC mode prefixes from a display nick.
    fun baseNick(display: String) = display.trimStart('~', '&', '@', '%', '+')
    val swipeOffsetX = remember { Animatable(0f) }
    val canSwipeReply = fromNick != null && !m.isMotd

    // Encryption badge: a Material icon at the start of the line, as an InlineTextContent
    // placeholder so the line stays one Text node and selection, copy and link offsets are
    // unaffected.
    //   AGM  -> filled Lock    (AES-256-GCM, PSK)                primary tint
    //   AGE  -> filled Shield  (double ratchet, forward secret)  primary tint
    //   +OK  -> outlined Lock  (Blowfish/FiSH, legacy)           amber tint
    val encScheme = m.encryption
    val encAlt = when (encScheme) {
        com.boxlabs.hexdroid.crypto.E2eScheme.AGM      -> "\uD83D\uDD12"  // 🔒 fallback / copy text
        com.boxlabs.hexdroid.crypto.E2eScheme.AGE      -> "\uD83D\uDEE1" // 🛡
        com.boxlabs.hexdroid.crypto.E2eScheme.BLOWFISH -> "\uD83D\uDD13" // 🔓 (legacy)
        null                                           -> ""
    }
    val botInline: Map<String, InlineTextContent> = if (!m.fromBot) emptyMap() else mapOf(
        BOT_INLINE_ID to InlineTextContent(
            Placeholder(
                width = 1.3.em,
                height = 1.0.em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_robot_2),
                contentDescription = null,
                tint = Color(0xFF7E9CD8),
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
            )
        }
    )

    val encInline: Map<String, InlineTextContent> = encScheme?.let { scheme ->
        mapOf(
            ENC_INLINE_ID to InlineTextContent(
                Placeholder(
                    width = 1.3.em,
                    height = 1.0.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                )
            ) {
                val (icon, tint) = when (scheme) {
                    com.boxlabs.hexdroid.crypto.E2eScheme.AGM      ->
                        Icons.Filled.Lock to MaterialTheme.colorScheme.primary
                    com.boxlabs.hexdroid.crypto.E2eScheme.AGE      ->
                        Icons.Filled.Shield to MaterialTheme.colorScheme.primary
                    com.boxlabs.hexdroid.crypto.E2eScheme.BLOWFISH ->
                        Icons.Outlined.Lock to Color(0xFFE0A030)
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                )
            }
        )
    } ?: emptyMap()

    // Read receipt: a double tick after our message. Its alternate text is what screen readers announce.
    val readLabel = stringResource(R.string.chat_status_read)
    val statusInline: Map<String, InlineTextContent> = if (!readByPeer) emptyMap() else mapOf(
        STATUS_INLINE_ID to InlineTextContent(
            Placeholder(
                width = 1.2.em,
                height = 1.0.em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
            )
        ) {
            Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
            )
        }
    )

    val inlineBadges = encInline + botInline + statusInline

    // Collapse multiline messages to the first COLLAPSE_LINES and offer to expand.
    val bodyLineCount = remember(m.text) { m.text.count { it == '\n' } + 1 }
    val isTv = isTvDevice()
    val collapsible = m.multiline && bodyLineCount > COLLAPSE_LINES
    val hiddenLines = bodyLineCount - COLLAPSE_LINES
    val bodyText = remember(m.text, collapsible, isExpanded) {
        if (collapsible && !isExpanded) {
            m.text.lineSequence().take(COLLAPSE_LINES).joinToString("\n")
        } else {
            m.text
        }
    }

    androidx.compose.foundation.layout.Column(
        // Held +AGE echoes are dimmed until the bridge flushes them to the wire (m.pending), so an
        // optimistic echo reads clearly as "not yet delivered" rather than as a sent message.
        // m.failed is the same idea after the fact: the server rejected the send.
        modifier = Modifier
            .fillMaxWidth()
            // Chat line spacing. MOTD lines opt out: the server banner is a character
            // grid, and spacing its rows apart shears the art.
            .padding(top = if (m.isMotd) 0.dp else itemGap)
            .alpha(if (m.pending || m.failed) 0.5f else 1f)
    ) {
        if (m.failed) {
            Text(
                stringResource(R.string.chat_not_delivered),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (m.replyToMsgId != null) {
            val replyDisplayIdx = msgStrToDisplayIdx[m.replyToMsgId] ?: -1
            ReplyQuote(
                replyToMsgId = m.replyToMsgId,
                msgIdToText = msgIdToText,
                canScroll = replyDisplayIdx >= 0,
                onFetch = onFetchReplyContext?.let { fetch -> { fetch(m.replyToMsgId) } },
                onTap = {
                    // O(1): index already resolved above via msgStrToDisplayIdx.
                    if (replyDisplayIdx >= 0) {
                        scope.launch { listState.animateScrollToItem(replyDisplayIdx) }
                    }
                },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .focusHighlight()
                .then(
                    when {
                        isFlickering  -> Modifier.background(
                            MaterialTheme.colorScheme.primary.copy(alpha = flickerAlphaValue)
                        )
                        isFindCurrent -> Modifier.background(
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)
                        )
                        isFindMatch   -> Modifier.background(
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
                        )
                        else          -> Modifier
                    }
                )
                .then(
                    if (canSwipeReply) Modifier.pointerInput(m.id) {
                        val deadZonePx = 64.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (down.position.x < deadZonePx) return@awaitEachGesture
                            var dx = 0f
                            var dy = 0f
                            var started = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val pos = change.positionChange()
                                dx += pos.x; dy += pos.y
                                val absDx = kotlin.math.abs(dx); val absDy = kotlin.math.abs(dy)
                                if (!started) {
                                    if (absDx < viewConfiguration.touchSlop && absDy < viewConfiguration.touchSlop) continue
                                    if (absDy > absDx || dx < 0f) break
                                    started = true
                                }
                                if (started) {
                                    change.consume()
                                    val newVal = (swipeOffsetX.value + pos.x).coerceIn(0f, 200f)
                                    scope.launch { swipeOffsetX.snapTo(newVal) }
                                }
                            }
                            val triggered = swipeOffsetX.value >= 72f
                            scope.launch {
                                swipeOffsetX.animateTo(0f,
                                    animationSpec = androidx.compose.animation.core.spring())
                            }
                            if (triggered) onSwipeReply()
                        }
                    } else Modifier
                )
                // A remote has no dependable long press, so select opens the message
                // actions there. Touch keeps long press, where a select-to-open row
                // would fight with tapping links inside the message.
                .then(if (isTv) Modifier.focusHighlight(RoundedCornerShape(4.dp)) else Modifier)
                .combinedClickable(
                    onClick = {
                        when {
                            copyRangeMode -> onToggleSelected()
                            isTv -> onLongPress()
                        }
                    },
                    onLongClick = { if (copyRangeMode) onToggleSelected() else onLongPress() }
                )
        ) {
            if (copyRangeMode && isSelectedForCopy) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                )
            }
            if (canSwipeReply && swipeOffsetX.value > 8f) {
                val iconAlpha = (swipeOffsetX.value / 72f).coerceIn(0f, 1f)
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = iconAlpha),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                        .size(20.dp)
                        .offset(x = (swipeOffsetX.value * 0.3f).dp)
                )
            }

            if (fromNick == null) {
                if (m.isMotd && selBufName == "*server*") {
                    MotdLine(
                        text = bodyText,
                        fontSizeSp = motdFontSizeSp,
                        style = motdStyle,
                        mircColorsEnabled = mircColorsEnabled,
                        ansiColorsEnabled = ansiColorsEnabled,
                        linkStyle = linkStyle,
                        onAnnotationClick = onAnnotationClick,
                    )
                } else {
                    val hlBg = if (isSystemInDarkTheme())
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
                    else
                        androidx.compose.ui.graphics.Color(0xFFFFD54F)
                    val hlFg = if (isSystemInDarkTheme())
                        MaterialTheme.colorScheme.onTertiary
                    else
                        androidx.compose.ui.graphics.Color.Black
                    IrcLinkifiedText(
                        text = ts + bodyText,
                        mircColorsEnabled = mircColorsEnabled,
                        ansiColorsEnabled = ansiColorsEnabled,
                        linkStyle = linkStyle,
                        onAnnotationClick = onAnnotationClick,
                        style = chatTextStyle,
                        findHighlight = findHighlight,
                        findHighlightBg = hlBg,
                        findHighlightFg = hlFg,
                        prefixLength = ts.length,
                        prefixColor = timestampColor,
                    )
                }
            } else if (m.isAction) {
                val fromDisplay = displayNick(fromNick)
                val fromBase = baseNick(fromDisplay)
                val botPrefix = stringResource(R.string.chat_bot_prefix)
                val annotated = remember(ts, fromDisplay, fromBase, bodyText, colorizeNicks,
                    mircColorsEnabled, ansiColorsEnabled, linkStyle, encScheme, m.fromOper, m.fromBot, displayName, botPrefix,
                    timestampColor, readByPeer, readLabel) {
                    buildAnnotatedString {
                        if (encScheme != null) { appendInlineContent(ENC_INLINE_ID, encAlt); append(" ") }
                        appendTimestamp(ts, timestampColor); append("* ")
                        // draft/oper-tag: amber star marks messages from IRC operators.
                        if (m.fromOper) withStyle(SpanStyle(color = Color(0xFFE0A030))) { append("\u2605") }
                        // Bot Mode: a robot icon marks messages from bots.
                        if (m.fromBot) { appendInlineContent(BOT_INLINE_ID, botPrefix); }
                        pushStringAnnotation(tag = ANN_NICK, annotation = fromBase)
                        withStyle(SpanStyle(color = if (colorizeNicks) nickColor(fromBase) else Color.Unspecified)) {
                            append(fromDisplay)
                        }
                        pop()
                        if (displayName != null) {
                            withStyle(SpanStyle(color = Color.Gray)) { append(" ($displayName)") }
                        }
                        append(" ")
                        appendIrcStyledLinkified(bodyText, linkStyle, mircColorsEnabled, ansiColorsEnabled)
                        if (readByPeer) { append(" "); appendInlineContent(STATUS_INLINE_ID, readLabel) }
                    }
                }
                AnnotatedClickableText(text = annotated, onAnnotationClick = onAnnotationClick, style = chatTextStyle, inlineContent = inlineBadges)
            } else {
                val fromDisplay = displayNick(fromNick)
                val fromBase = baseNick(fromDisplay)
                val botPrefix = stringResource(R.string.chat_bot_prefix)
                val annotated = remember(ts, fromDisplay, fromBase, bodyText, colorizeNicks,
                    mircColorsEnabled, ansiColorsEnabled, linkStyle, encScheme, m.fromOper, m.fromBot, displayName, botPrefix,
                    timestampColor, nickStyle, readByPeer, readLabel) {
                    buildAnnotatedString {
                        if (encScheme != null) { appendInlineContent(ENC_INLINE_ID, encAlt); append(" ") }
                        appendTimestamp(ts, timestampColor); append(nickStyle.open)
                        // draft/oper-tag: amber star marks messages from IRC operators.
                        if (m.fromOper) withStyle(SpanStyle(color = Color(0xFFE0A030))) { append("\u2605") }
                        // Bot Mode: a robot icon marks messages from bots.
                        if (m.fromBot) { appendInlineContent(BOT_INLINE_ID, botPrefix); }
                        pushStringAnnotation(tag = ANN_NICK, annotation = fromBase)
                        withStyle(SpanStyle(color = if (colorizeNicks) nickColor(fromBase) else Color.Unspecified)) {
                            append(fromDisplay)
                        }
                        pop()
                        if (displayName != null) {
                            withStyle(SpanStyle(color = Color.Gray)) { append(" ($displayName)") }
                        }
                        append(nickStyle.close); append(" ")
                        appendIrcStyledLinkified(bodyText, linkStyle, mircColorsEnabled, ansiColorsEnabled)
                        if (readByPeer) { append(" "); appendInlineContent(STATUS_INLINE_ID, readLabel) }
                    }
                }
                AnnotatedClickableText(text = annotated, onAnnotationClick = onAnnotationClick, style = chatTextStyle, inlineContent = inlineBadges)
            }
        } // end Box

        if (collapsible) {
            Text(
                text = if (isExpanded) {
                    stringResource(R.string.chat_show_less)
                } else {
                    pluralStringResource(R.plurals.chat_show_more, hiddenLines, hiddenLines)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .focusHighlight()
                    .clickable(enabled = !copyRangeMode, onClick = onToggleExpanded)
                    .padding(top = 2.dp, bottom = 2.dp),
            )
        }

        if (imagePreviewsEnabled) {
            // m.text, not bodyText: a link below the fold is still worth previewing, and
            // the preview list must not shuffle when the user expands the message.
            val msgUrls = remember(m.id, m.text) {
                messageUrls(m.text).take(3).toList()
            }
            for (previewUrl in msgUrls) {
                InlinePreview(url = previewUrl, previewsEnabled = true, wifiOnly = imagePreviewsWifiOnly)
            }
        }
        if (linkPreviewsEnabled) {
            val titleUrl = remember(m.id, m.text, imagePreviewsEnabled) {
                linkPreviewUrl(messageUrls(m.text).take(3).toList(), mediaPreviewsShown = imagePreviewsEnabled)
            }
            if (titleUrl != null) LinkPreviewCard(url = titleUrl, wifiOnly = imagePreviewsWifiOnly)
        }
        if (m.reactions.isNotEmpty()) {
            ReactionRow(
                reactions = m.reactions,
                myNick = myNick,
                onToggle = m.msgId?.let { id -> { text: String, mine: Boolean -> onToggleReaction?.invoke(id, text, mine) } },
                onAdd = m.msgId?.let { id -> onAddReaction?.let { add -> { add(id) } } },
                onShowWho = onShowReactions,
            )
        }

        if (!m.isMotd || selBufName != "*server*") {
            Spacer(Modifier.height(4.dp))
        }
    } // end Column
}

/** A message whose reactions are being shown, and the one picked, or null for all of them. */
internal data class ReactionDetailsTarget(val messageId: Long, val reaction: String?)

/**
 * Who reacted to a message. A chip per reaction filters the list, with All first; All lists each
 * person once with the reactions they used. Tapping a person opens their actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReactionDetailsSheet(
    reactions: Map<String, Set<String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
    myNick: String?,
    nickLabel: (String) -> String,
    nickColor: (String) -> Color,
    displayName: (String) -> String?,
    onNickClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Same order as the chips under the message.
    val ordered = remember(reactions) {
        reactions.entries.sortedWith(
            compareByDescending<Map.Entry<String, Set<String>>> { it.value.size }.thenBy { it.key }
        )
    }
    val everyone = remember(ordered) {
        val byNick = LinkedHashMap<String, Pair<String, MutableList<String>>>()
        for ((text, nicks) in ordered) {
            for (n in nicks) byNick.getOrPut(n.lowercase()) { n to mutableListOf() }.second.add(text)
        }
        byNick.values.map { (nick, used) -> nick to used.toList() }
    }
    val rows: List<Pair<String, List<String>>> =
        if (selected == null) everyone else reactions[selected].orEmpty().map { it to emptyList() }
    val youLabel = stringResource(R.string.chat_reaction_you)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_reactions_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = selected == null,
                    onClick = { onSelect(null) },
                    label = { Text("${stringResource(R.string.chat_reactions_all)} ${everyone.size}") },
                    modifier = Modifier
                        .then(if (selected == null) Modifier.tvInitialFocus() else Modifier)
                        .focusHighlight(RoundedCornerShape(8.dp)),
                )
                for ((text, nicks) in ordered) {
                    FilterChip(
                        selected = selected == text,
                        onClick = { onSelect(text) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 120.dp),
                                )
                                Text(" ${nicks.size}")
                            }
                        },
                        modifier = Modifier
                            .then(if (selected == text) Modifier.tvInitialFocus() else Modifier)
                            .focusHighlight(RoundedCornerShape(8.dp)),
                    )
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(rows) { (nick, used) ->
                    val isMe = myNick != null && nick.equals(myNick, ignoreCase = true)
                    val shownName = displayName(nick)
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = nickLabel(nick),
                                    color = nickColor(nick),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (isMe) {
                                    Text(
                                        text = " ($youLabel)",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        },
                        supportingContent = if (shownName == null) null else {
                            { Text(shownName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                        trailingContent = if (used.isEmpty()) null else {
                            {
                                Text(
                                    text = used.joinToString(" "),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 160.dp),
                                )
                            }
                        },
                        modifier = Modifier.focusHighlight().clickable { onNickClick(nick) },
                    )
                }
            }
        }
    }
}

/**
 * The reactions on a message, as chips beneath it. Sized to their content, since draft/react
 * takes a short word as readily as an emoji, and truncated so one cannot take the row. A chip
 * the user is part of is filled. Tapping toggles their own reaction; a long press shows who
 * reacted.
 */
@Composable
private fun ReactionRow(
    reactions: Map<String, Set<String>>,
    myNick: String?,
    onToggle: ((String, Boolean) -> Unit)?,
    onAdd: (() -> Unit)? = null,
    onShowWho: ((String) -> Unit)? = null,
) {
    val whoLabel = stringResource(R.string.chat_reactions_who)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 2.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Stable order, so a chip does not move under the user's finger.
        val ordered = reactions.entries.sortedWith(
            compareByDescending<Map.Entry<String, Set<String>>> { it.value.size }.thenBy { it.key }
        )
        for ((text, nicks) in ordered) {
            val mine = myNick != null && nicks.any { it.equals(myNick, ignoreCase = true) }
            val shape = RoundedCornerShape(10.dp)
            val description = stringResource(R.string.chat_reaction_cd, text, nicks.joinToString(", "))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(shape)
                    .background(
                        if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    )
                    .then(
                        if (onToggle == null && onShowWho == null) Modifier
                        else Modifier
                            .focusHighlight(shape)
                            .combinedClickable(
                                onLongClickLabel = whoLabel,
                                onLongClick = onShowWho?.let { show -> { show(text) } },
                                onClick = {
                                    if (onToggle != null) onToggle(text, mine) else onShowWho?.invoke(text)
                                },
                            )
                    )
                    .clearAndSetSemantics { contentDescription = description }
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.widthIn(max = 120.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Always shown, so a chip does not resize when someone joins it.
                Text(
                    text = " ${nicks.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Add another without going through the message menu.
        if (onAdd != null) {
            val shape = RoundedCornerShape(10.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .focusHighlight(shape)
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.chat_react_custom),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Messages longer than this many lines collapse behind a "show more" toggle. */
private const val COLLAPSE_LINES = 8

internal const val ANN_URL = "URL"

internal const val ANN_CHAN = "CHAN"

internal const val ANN_NICK = "NICK"

private const val ENC_INLINE_ID = "encbadge"
