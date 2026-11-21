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

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boxlabs.hexdroid.R

/** One page of the emoji picker: the glyph on its tab, its name and its emoji. */
internal class EmojiCategory(val tab: String, val labelRes: Int, list: String) {
    val emoji: List<String> = list.trim().split(Regex("\\s+"))
}

/** The emoji offered, by category. Kept to emoji Android 8 and later draw. */
internal val EMOJI_CATEGORIES: List<EmojiCategory> = listOf(
    EmojiCategory("😀", R.string.emoji_smileys, """
        😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐
        🤨 😐 😑 😶 😏 😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 😵 🤯 🤠 😎 🤓 🧐 😕 😟 🙁 ☹️
        😮 😯 😲 😳 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 😤 😡 😠 🤬 😈 👿 💀 ☠️ 💩 🤡 👹 👺
        👻 👽 👾 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾 🙈 🙉 🙊 💋 💌 💘 💝 💖 💗 💓 💞 💕 💟 ❣️ 💔 ❤️ 🧡
        💛 💚 💙 💜 🖤 💯 💢 💥 💫 💦 💨 💬 💭 💤
    """),
    EmojiCategory("👋", R.string.emoji_people, """
        👋 🤚 🖐️ ✋ 🖖 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️
        💅 🤳 💪 👀 👁️ 👅 👄 🧠 👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🙇 🤦 🤷 👮
        🕵️ 💂 👷 🤴 👸 👳 👲 🧕 🤵 👰 🤰 🤱 👼 🎅 🤶 🧙 🧚 🧛 🧜 🧝 🧞 🧟 💆 💇 🚶 🏃 💃 🕺 👯 🧖
        🧗 🤺 🏂 🏄 🚣 🏊 🚴 🚵 🤸 🤼 🤽 🤾 🤹 🧘 🛀 🛌 👭 👫 👬 💏 💑 👪
    """),
    EmojiCategory("🐶", R.string.emoji_nature, """
        🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋
        🐌 🐞 🐜 🕷️ 🦂 🐢 🐍 🦎 🦖 🦕 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🐘 🦏 🐪 🐫
        🦒 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🐐 🦌 🐕 🐩 🐈 🐓 🦃 🕊️ 🐇 🐁 🐀 🐿️ 🦔 🐾 🐉 🐲 🌵 🎄 🌲 🌳 🌴 🌱
        🌿 ☘️ 🍀 🍃 🍂 🍁 🍄 🌾 💐 🌷 🌹 🥀 🌺 🌸 🌼 🌻 🌞 🌝 🌛 🌜 🌚 🌕 🌙 🌎 ⭐ 🌟 ✨ ⚡ ☄️ 🔥
        🌪️ 🌈 ☀️ 🌤️ ⛅ ☁️ 🌦️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️ ⛄ 🌬️ 💧 ☔ 🌊 🌫️
    """),
    EmojiCategory("🍔", R.string.emoji_food, """
        🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍈 🍒 🍑 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥒 🌶️ 🌽 🥕 🥔 🍠 🥐 🍞 🥖 🥨 🧀
        🥚 🍳 🥞 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🥙 🌮 🌯 🥗 🥘 🥫 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍥
        🥠 🍢 🍡 🍧 🍨 🍦 🥧 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜 🍯 🥛 🍼 ☕ 🍵 🥤 🍶 🍺 🍻 🥂 🍷 🥃
        🍸 🍹 🍾 🥄 🍴 🍽️ 🥣 🥡 🥢
    """),
    EmojiCategory("⚽", R.string.emoji_activities, """
        ⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🏒 🏑 🏏 🥅 ⛳ 🏹 🎣 🥊 🥋 🎽 🛷 ⛸️ 🥌 🎿 🏆 🥇 🥈 🥉 🏅 🎖️
        🏵️ 🎗️ 🎫 🎟️ 🎪 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 🎯 🎳 🎮 🕹️ 🎰 🎉 🎊 🎈 🎁 🎀 🎃 🎆
        🎇
    """),
    EmojiCategory("🚗", R.string.emoji_travel, """
        🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛴 🚲 🛵 🏍️ 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄
        🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🛰️ 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ ⛽ 🚧 🚦 🚥 🗺️ 🗿
        🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🏠 🏡 🏘️ 🏚️ 🏗️ 🏭 🏢 🏬 🏣 🏤 🏥
        🏦 🏨 🏪 🏫 🏩 💒 🏛️ ⛪ 🕌 🕍 🕋 ⛩️ 🌅 🌄 🌠 🌇 🌆 🏙️ 🌃 🌌 🌉 🌁
    """),
    EmojiCategory("💡", R.string.emoji_objects, """
        ⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💽 💾 💿 📀 📼 📷 📸 📹 🎥 📽️ 🎞️ 📞 ☎️ 📟 📠 📺 📻 🎙️ ⏱️ ⏰ 🕰️ ⌛ ⏳
        📡 🔋 🔌 💡 🔦 🕯️ 💸 💵 💰 💳 💎 ⚖️ 🔧 🔨 ⚒️ 🛠️ ⛏️ 🔩 ⚙️ ⛓️ 🔫 💣 🔪 🗡️ ⚔️ 🛡️ 🚬 ⚰️ ⚱️ 🏺
        🔮 📿 💈 ⚗️ 🔭 🔬 🕳️ 💊 💉 🌡️ 🚽 🚿 🛁 🔑 🗝️ 🚪 🛋️ 🛏️ 🖼️ 🛍️ 🛒 ✉️ 📩 📨 📧 📥 📤 📦 🏷️ 📪
        📫 📬 📭 📮 📯 📜 📃 📄 📑 📊 📈 📉 🗒️ 🗓️ 📆 📅 🗑️ 📇 🗃️ 🗳️ 🗄️ 📋 📁 📂 🗂️ 🗞️ 📰 📓 📔 📒
        📕 📗 📘 📙 📚 📖 🔖 🔗 📎 🖇️ 📐 📏 📌 📍 ✂️ 🖊️ 🖋️ ✒️ 🖌️ 🖍️ 📝 ✏️ 🔍 🔎 🔏 🔐 🔒 🔓
    """),
    EmojiCategory("❤️", R.string.emoji_symbols, """
        ☮️ ✝️ ☪️ 🕉️ ☸️ ✡️ 🔯 🕎 ☯️ ☦️ 🛐 ⛎ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🆔 ⚛️ ☢️ ☣️ 📴 📳
        🆚 💮 🅰️ 🅱️ 🆎 🆑 🅾️ 🆘 ❌ ⭕ 🛑 ⛔ 📛 🚫 ♨️ 🚷 🚯 🚳 🚱 🔞 📵 🚭 ❗ ❕ ❓ ❔ ‼️ ⁉️ 🔅 🔆
        〽️ ⚠️ 🚸 🔱 ⚜️ 🔰 ♻️ ✅ 💹 ❇️ ✳️ ❎ 🌐 💠 Ⓜ️ 🌀 🏧 🚾 ♿ 🅿️ 🛂 🛃 🛄 🛅 🚹 🚺 🚼 🚻 🚮 🎦
        📶 🔣 ℹ️ 🔤 🔡 🔠 🆖 🆗 🆙 🆒 🆕 🆓 0️⃣ 1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣ 6️⃣ 7️⃣ 8️⃣ 9️⃣ 🔟 🔢 #️⃣ *️⃣ ▶️ ⏸️ ⏹️ ⏺️
        ⏭️ ⏮️ ⏩ ⏪ ⏫ ⏬ ◀️ 🔼 🔽 ➡️ ⬅️ ⬆️ ⬇️ ↗️ ↘️ ↙️ ↖️ ↕️ ↔️ ↪️ ↩️ ⤴️ ⤵️ 🔀 🔁 🔂 🔄 🔃 🎵 🎶
        ➕ ➖ ➗ ✖️ 💲 💱 ™️ ©️ ®️ 〰️ ➰ ➿ 🔚 🔙 🔛 🔝 🔜 ✔️ ☑️ 🔘 🔴 ⚪ ⚫ 🔵 🔺 🔻 🔸 🔹 🔶 🔷
        🔳 🔲 ▪️ ▫️ ◾ ◽ ◼️ ◻️ ⬛ ⬜ 🔈 🔇 🔉 🔊 🔔 🔕 📣 📢 🗯️ ♠️ ♣️ ♥️ ♦️ 🃏 🎴 🀄
    """),
    EmojiCategory("🏁", R.string.emoji_flags, """
        🏁 🚩 🎌 🏴 🏳️ 🏳️‍🌈 🏴‍☠️ 🇬🇧 🇺🇸 🇨🇦 🇦🇺 🇮🇪 🇳🇿 🇫🇷 🇩🇪 🇪🇸 🇮🇹 🇳🇱 🇧🇪 🇵🇹 🇸🇪 🇳🇴 🇩🇰 🇫🇮 🇮🇸 🇵🇱 🇨🇿 🇭🇺 🇦🇹 🇨🇭
        🇬🇷 🇹🇷 🇺🇦 🇷🇺 🇮🇳 🇨🇳 🇯🇵 🇰🇷 🇧🇷 🇦🇷 🇲🇽 🇿🇦 🇳🇬 🇪🇬 🇸🇦 🇦🇪 🇮🇱 🇪🇺 🇺🇳
    """),
)

/** Recently picked emoji, newest first, kept on this device only. */
internal object EmojiRecents {
    private const val PREFS = "hexdroid_emoji"
    private const val KEY = "recent"
    private const val MAX = 32
    private const val SEP = "\u001f"

    fun load(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.split(SEP)?.filter { it.isNotEmpty() }.orEmpty()

    fun add(ctx: Context, emoji: String): List<String> {
        val next = (listOf(emoji) + load(ctx).filter { it != emoji }).take(MAX)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next.joinToString(SEP)).apply()
        return next
    }
}

/**
 * Emoji picker in a bottom sheet: recently used first, then a page per category. Each pick goes
 * to [onPick] and the sheet stays open for more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var recents by remember { mutableStateOf(EmojiRecents.load(ctx)) }
    var tab by remember { mutableIntStateOf(if (recents.isEmpty()) 1 else 0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val recentLabel = stringResource(R.string.emoji_recent)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            SecondaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    modifier = Modifier
                        .focusHighlight()
                        .semantics { contentDescription = recentLabel },
                    text = { Text("🕘", fontSize = 20.sp) },
                )
                EMOJI_CATEGORIES.forEachIndexed { i, cat ->
                    val label = stringResource(cat.labelRes)
                    Tab(
                        selected = tab == i + 1,
                        onClick = { tab = i + 1 },
                        modifier = Modifier
                            .focusHighlight()
                            .semantics { contentDescription = label },
                        text = { Text(cat.tab, fontSize = 20.sp) },
                    )
                }
            }
            val shown = if (tab == 0) recents else EMOJI_CATEGORIES[tab - 1].emoji
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.emoji_recent_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(46.dp),
                    modifier = Modifier.fillMaxWidth().height(280.dp).padding(horizontal = 8.dp),
                ) {
                    items(shown, key = { it }) { e ->
                        Box(
                            Modifier
                                .size(46.dp)
                                .focusHighlight(RoundedCornerShape(8.dp))
                                .clickable {
                                    recents = EmojiRecents.add(ctx, e)
                                    onPick(e)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(e, fontSize = 26.sp)
                        }
                    }
                }
            }
        }
    }
}
