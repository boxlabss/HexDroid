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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.R

/** Commands with a short description shown in the hint popup. */
internal data class IrcCommand(
    val name: String,
    val usage: String,
    /** Localized description for built-in commands. */
    @param:androidx.annotation.StringRes val descriptionRes: Int? = null,
    /** Runtime description for commands supplied by .hex scripts. */
    val description: String? = null,
)

internal val IRC_COMMANDS = listOf(
    // Messaging
    IrcCommand("me", "/me <action>", R.string.cmd_me),
    IrcCommand("say", "/say <text>", R.string.cmd_say),
    IrcCommand("msg", "/msg <nick> <message>", R.string.cmd_msg),
    IrcCommand("notice", "/notice <target> <text>", R.string.cmd_notice),
    IrcCommand("react", "/react <emoji> [n]", R.string.cmd_react),
    IrcCommand("unreact", "/unreact <emoji> [n]", R.string.cmd_unreact),
    IrcCommand("amsg", "/amsg <message>", R.string.cmd_amsg),
    IrcCommand("ame", "/ame <action>", R.string.cmd_ame),

    // Channels
    IrcCommand("join", "/join <channel> [key]", R.string.cmd_join),
    IrcCommand("part", "/part [channel] [reason]", R.string.cmd_part),
    IrcCommand("cycle", "/cycle [channel]", R.string.cmd_cycle),
    IrcCommand("topic", "/topic [new topic]", R.string.cmd_topic),
    IrcCommand("invite", "/invite <nick> [channel]", R.string.cmd_invite),
    IrcCommand("knock", "/knock <channel> [reason]", R.string.cmd_knock),
    IrcCommand("list", "/list", R.string.cmd_list),
    IrcCommand("names", "/names [channel]", R.string.cmd_names),

    // Buffer management
    IrcCommand("clear", "/clear", R.string.cmd_clear),
    IrcCommand("close", "/close", R.string.cmd_close),
    IrcCommand("closekey", "/closekey <net::buffer>", R.string.cmd_closekey),
    IrcCommand("find", "/find <text>", R.string.cmd_find),
    IrcCommand("grep", "/grep <text>", R.string.cmd_grep),
    IrcCommand("search", "/search <text>", R.string.cmd_search),
    IrcCommand("gsearch", "/gsearch <text>", R.string.cmd_gsearch),
    IrcCommand("gfind", "/gfind <text>", R.string.cmd_gfind),

    // User & nick
    IrcCommand("nick", "/nick <new nick>", R.string.cmd_nick),
    IrcCommand("away", "/away [message]", R.string.cmd_away),
    IrcCommand("back", "/back", R.string.cmd_back),
    IrcCommand("whois", "/whois <nick>", R.string.cmd_whois),
    IrcCommand("who", "/who <mask>", R.string.cmd_who),
    IrcCommand("ignore", "/ignore [nick]", R.string.cmd_ignore),
    IrcCommand("unignore", "/unignore <nick>", R.string.cmd_unignore),
    IrcCommand("quit", "/quit [reason]", R.string.cmd_quit),

    // Moderation
    IrcCommand("kick", "/kick <nick> [reason]", R.string.cmd_kick),
    IrcCommand("ban", "/ban <nick|mask> [n|u|h|d|a]", R.string.cmd_ban),
    IrcCommand("unban", "/unban <nick|mask>", R.string.cmd_unban),
    IrcCommand("kb", "/kb <nick> [n|u|h|d|a] [reason]", R.string.cmd_kb),
    IrcCommand("kickban", "/kickban <nick> [type] [reason]", R.string.cmd_kickban),
    IrcCommand("mute", "/mute <nick|mask> [n|u|h|d|a]", R.string.cmd_mute),
    IrcCommand("quiet", "/quiet <nick|mask> [type]", R.string.cmd_quiet),
    IrcCommand("unmute", "/unmute <nick|mask>", R.string.cmd_unmute),
    IrcCommand("unquiet", "/unquiet <nick|mask>", R.string.cmd_unquiet),
    IrcCommand("op", "/op <nick> [channel]", R.string.cmd_op),
    IrcCommand("deop", "/deop <nick> [channel]", R.string.cmd_deop),
    IrcCommand("voice", "/voice <nick> [channel]", R.string.cmd_voice),
    IrcCommand("devoice", "/devoice <nick> [channel]", R.string.cmd_devoice),
    IrcCommand("mode", "/mode [target] <modes>", R.string.cmd_mode),

    // Mode lists
    IrcCommand("banlist", "/banlist", R.string.cmd_banlist),
    IrcCommand("quietlist", "/quietlist", R.string.cmd_quietlist),
    IrcCommand("exceptlist", "/exceptlist", R.string.cmd_exceptlist),
    IrcCommand("invexlist", "/invexlist", R.string.cmd_invexlist),

    // CTCP
    IrcCommand("ctcp", "/ctcp <nick> <command>", R.string.cmd_ctcp),
    IrcCommand("ping", "/ping <nick>", R.string.cmd_ping),
    IrcCommand("ctcpping", "/ctcpping <nick>", R.string.cmd_ctcpping),
    IrcCommand("version", "/version [nick]", R.string.cmd_version),
    IrcCommand("time", "/time [server]", R.string.cmd_time),
    IrcCommand("finger", "/finger <nick>", R.string.cmd_finger),
    IrcCommand("userinfo", "/userinfo <nick>", R.string.cmd_userinfo),
    IrcCommand("clientinfo", "/clientinfo <nick>", R.string.cmd_clientinfo),

    // Server queries
    IrcCommand("motd", "/motd [server]", R.string.cmd_motd),
    IrcCommand("admin", "/admin [server]", R.string.cmd_admin),
    IrcCommand("info", "/info [server]", R.string.cmd_info),
    IrcCommand("dns", "/dns <host|ip>", R.string.cmd_dns),

    // DCC
    IrcCommand("dcc", "/dcc chat|schat|send <nick>", R.string.cmd_dcc),

    // IRC operator
    IrcCommand("oper", "/oper <user> <password>", R.string.cmd_oper),
    IrcCommand("sajoin", "/sajoin <nick> <channel>", R.string.cmd_sajoin),
    IrcCommand("sapart", "/sapart <nick> [channel]", R.string.cmd_sapart),
    IrcCommand("kill", "/kill <nick> [reason]", R.string.cmd_kill),
    IrcCommand("kline", "/kline <mask> <duration> [reason]", R.string.cmd_kline),
    IrcCommand("gline", "/gline <mask> <duration> [reason]", R.string.cmd_gline),
    IrcCommand("zline", "/zline <ip> <duration> [reason]", R.string.cmd_zline),
    IrcCommand("dline", "/dline <ip> <duration> [reason]", R.string.cmd_dline),
    IrcCommand("eline", "/eline <mask> <duration> [reason]", R.string.cmd_eline),
    IrcCommand("qline", "/qline <mask> <duration> [reason]", R.string.cmd_qline),
    IrcCommand("shun", "/shun <mask> <duration> [reason]", R.string.cmd_shun),
    IrcCommand("wallops", "/wallops <message>", R.string.cmd_wallops),
    IrcCommand("globops", "/globops <message>", R.string.cmd_globops),
    IrcCommand("locops", "/locops <message>", R.string.cmd_locops),
    IrcCommand("operwall", "/operwall <message>", R.string.cmd_operwall),

    // Misc
    IrcCommand("alias", "/alias [list | add <name> <expansion> | remove <name>]", R.string.cmd_alias),
    IrcCommand("slap", "/slap <nick>", R.string.cmd_slap),
    IrcCommand("raw", "/raw <command>", R.string.cmd_raw),
    IrcCommand("quote", "/quote <command>", R.string.cmd_quote),
    IrcCommand("sysinfo", "/sysinfo", R.string.cmd_sysinfo),

    // Query / services shorthands
    IrcCommand("query", "/query <nick> [message]", R.string.cmd_query),
    IrcCommand("ns", "/ns <command>", R.string.cmd_ns),
    IrcCommand("cs", "/cs <command>", R.string.cmd_cs),
    IrcCommand("as", "/as <command>", R.string.cmd_as),
    IrcCommand("hs", "/hs <command>", R.string.cmd_hs),
    IrcCommand("ms", "/ms <command>", R.string.cmd_ms),
    IrcCommand("bs", "/bs <command>", R.string.cmd_bs),

    // Bouncer shortcuts
    IrcCommand("znc", "/znc <command>", R.string.cmd_znc),
    IrcCommand("bouncerserv", "/bouncerserv <command>", R.string.cmd_bouncerserv),
    IrcCommand("bnc", "/bnc <command>", R.string.cmd_bnc),

    // IRCv3
    IrcCommand("setname", "/setname <realname>", R.string.cmd_setname),
    IrcCommand("markread", "/markread [target] [timestamp]", R.string.cmd_markread),
    IrcCommand("monitor", "/monitor +nick | -nick | C | L | S", R.string.cmd_monitor),
    IrcCommand("notify", "/notify [nick]", R.string.cmd_notify),
    IrcCommand("unnotify", "/unnotify <nick>", R.string.cmd_unnotify),
    IrcCommand("mentions", "/mentions", R.string.cmd_mentions),
    IrcCommand("register", "/register [account] [email] <password>", R.string.cmd_register),
    IrcCommand("verify", "/verify [account] <code>", R.string.cmd_verify),
    IrcCommand("metadata", "/metadata [target] <sub> | <key> [value]", R.string.cmd_metadata),
    IrcCommand("redact", "/redact [target] <msgid> [reason]", R.string.cmd_redact),
)

/**
 * Sub-command hints shown after a command whose first argument is a verb: services aliases (/ns,
 * /cs), ZNC's *status (/znc) and soju's BouncerServ (/bouncerserv, /bnc). Keys are the parent
 * command's short name; verbs are stored in display casing and matched case-insensitively. A
 * curated set of common verbs, not every one.
 */
private val SUB_COMMANDS: Map<String, List<IrcCommand>> = mapOf(
    // NickServ — Atheme/Anope-compatible verbs
    "ns" to listOf(
        IrcCommand("IDENTIFY", "IDENTIFY [account] <password>", R.string.cmd_ns_identify),
        IrcCommand("LOGIN", "LOGIN [account] <password>", R.string.cmd_ns_login),
        IrcCommand("REGISTER", "REGISTER <password> <email>", R.string.cmd_ns_register),
        IrcCommand("GHOST", "GHOST <nick> [password]", R.string.cmd_ns_ghost),
        IrcCommand("RECOVER", "RECOVER <nick> [password]", R.string.cmd_ns_recover),
        IrcCommand("RELEASE", "RELEASE <nick> [password]", R.string.cmd_ns_release),
        IrcCommand("GROUP", "GROUP <nick> <password>", R.string.cmd_ns_group),
        IrcCommand("UNGROUP", "UNGROUP <nick>", R.string.cmd_ns_ungroup),
        IrcCommand("SET", "SET <option> <value>", R.string.cmd_ns_set),
        IrcCommand("CERT", "CERT [ADD|DEL|LIST] [fingerprint]", R.string.cmd_ns_cert),
        IrcCommand("INFO", "INFO [nick]", R.string.cmd_ns_info),
        IrcCommand("LIST", "LIST <pattern>", R.string.cmd_ns_list),
        IrcCommand("DROP", "DROP <nick> [password]", R.string.cmd_ns_drop),
        IrcCommand("LOGOUT", "LOGOUT", R.string.cmd_ns_logout),
        IrcCommand("HELP", "HELP [command]", R.string.cmd_ns_help),
    ),
    // ChanServ — Atheme/Anope-compatible verbs
    "cs" to listOf(
        IrcCommand("REGISTER", "REGISTER <channel>", R.string.cmd_cs_register),
        IrcCommand("DROP", "DROP <channel>", R.string.cmd_cs_drop),
        IrcCommand("INFO", "INFO <channel>", R.string.cmd_cs_info),
        IrcCommand("ACCESS", "ACCESS <channel> [LIST|ADD|DEL] [mask] [level]", R.string.cmd_cs_access),
        IrcCommand("FLAGS", "FLAGS <channel> [target] [flags]", R.string.cmd_cs_flags),
        IrcCommand("OP", "OP <channel> [nick]", R.string.cmd_cs_op),
        IrcCommand("DEOP", "DEOP <channel> [nick]", R.string.cmd_cs_deop),
        IrcCommand("VOICE", "VOICE <channel> [nick]", R.string.cmd_cs_voice),
        IrcCommand("DEVOICE", "DEVOICE <channel> [nick]", R.string.cmd_cs_devoice),
        IrcCommand("HALFOP", "HALFOP <channel> [nick]", R.string.cmd_cs_halfop),
        IrcCommand("PROTECT", "PROTECT <channel> [nick]", R.string.cmd_cs_protect),
        IrcCommand("OWNER", "OWNER <channel> [nick]", R.string.cmd_cs_owner),
        IrcCommand("INVITE", "INVITE <channel> [nick]", R.string.cmd_cs_invite),
        IrcCommand("UNBAN", "UNBAN <channel> [nick]", R.string.cmd_cs_unban),
        IrcCommand("KICK", "KICK <channel> <nick> [reason]", R.string.cmd_cs_kick),
        IrcCommand("BAN", "BAN <channel> <mask>", R.string.cmd_cs_ban),
        IrcCommand("QUIET", "QUIET <channel> <mask>", R.string.cmd_cs_quiet),
        IrcCommand("TOPIC", "TOPIC <channel> [topic]", R.string.cmd_cs_topic),
        IrcCommand("CLEAR", "CLEAR <channel> <what>", R.string.cmd_cs_clear),
        IrcCommand("RECOVER", "RECOVER <channel>", R.string.cmd_cs_recover),
        IrcCommand("SET", "SET <channel> <option> <value>", R.string.cmd_cs_set),
        IrcCommand("HELP", "HELP [command]", R.string.cmd_cs_help),
    ),
    // MemoServ — same verbs on Atheme/Anope
    "ms" to listOf(
        IrcCommand("SEND", "SEND <nick> <message>", R.string.cmd_ms_send),
        IrcCommand("LIST", "LIST", R.string.cmd_ms_list),
        IrcCommand("READ", "READ <number>", R.string.cmd_ms_read),
        IrcCommand("DELETE", "DELETE <number|ALL>", R.string.cmd_ms_delete),
        IrcCommand("FORWARD", "FORWARD <number> <nick>", R.string.cmd_ms_forward),
        IrcCommand("IGNORE", "IGNORE [ADD|DEL|LIST] [mask]", R.string.cmd_ms_ignore),
        IrcCommand("SET", "SET <option> <value>", R.string.cmd_ms_set),
        IrcCommand("HELP", "HELP [command]", R.string.cmd_ms_help),
    ),
    // HostServ
    "hs" to listOf(
        IrcCommand("REQUEST", "REQUEST <vhost>", R.string.cmd_hs_request),
        IrcCommand("ON", "ON", R.string.cmd_hs_on),
        IrcCommand("OFF", "OFF", R.string.cmd_hs_off),
        IrcCommand("TAKE", "TAKE <vhost>", R.string.cmd_hs_take),
        IrcCommand("DROP", "DROP", R.string.cmd_hs_drop),
        IrcCommand("INFO", "INFO [nick]", R.string.cmd_hs_info),
        IrcCommand("HELP", "HELP [command]", R.string.cmd_hs_help),
    ),
    // BotServ — Anope; Atheme has a similar but narrower set
    "bs" to listOf(
        IrcCommand("ASSIGN", "ASSIGN <channel> <bot>", R.string.cmd_bs_assign),
        IrcCommand("UNASSIGN", "UNASSIGN <channel>", R.string.cmd_bs_unassign),
        IrcCommand("BOTLIST", "BOTLIST", R.string.cmd_bs_botlist),
        IrcCommand("INFO", "INFO <channel|bot>", R.string.cmd_bs_info),
        IrcCommand("KICK", "KICK <channel> <option> [args]", R.string.cmd_bs_kick),
        IrcCommand("SET", "SET <channel> <option> <value>", R.string.cmd_bs_set),
        IrcCommand("SAY", "SAY <channel> <text>", R.string.cmd_bs_say),
        IrcCommand("ACT", "ACT <channel> <action>", R.string.cmd_bs_act),
        IrcCommand("HELP", "HELP [command]", R.string.cmd_bs_help),
    ),
    // AuthServ/X3
    "as" to listOf(
        IrcCommand("AUTH", "AUTH <account> <password>", R.string.cmd_as_auth),
        IrcCommand("REGISTER", "REGISTER <account> <password> <email>", R.string.cmd_as_register),
        IrcCommand("HELP", "HELP [command]", R.string.cmd_as_help),
    ),
    "x3" to listOf(
        IrcCommand("REGISTER", "REGISTER <channel>", R.string.cmd_as_register_2),
        IrcCommand("DROP", "DROP <channel>", R.string.cmd_as_drop),
        IrcCommand("INFO", "INFO <channel|user>", R.string.cmd_as_info),
        IrcCommand("OP", "OP <channel> <user>", R.string.cmd_as_op),
        IrcCommand("DEOP", "DEOP <channel> <user>", R.string.cmd_as_deop),
        IrcCommand("VOICE", "VOICE <channel> <user>", R.string.cmd_as_voice),
        IrcCommand("DEVOICE", "DEVOICE <channel> <user>", R.string.cmd_as_devoice),
        IrcCommand("HALFOP", "HALFOP <channel> <user>", R.string.cmd_as_halfop),
        IrcCommand("INVITE", "INVITE <channel> [user]", R.string.cmd_as_invite),
        IrcCommand("KICK", "KICK <channel> <user> [reason]", R.string.cmd_as_kick),
        IrcCommand("BAN", "BAN <channel> <mask>", R.string.cmd_as_ban),
        IrcCommand("UNBAN", "UNBAN <channel> [mask]", R.string.cmd_as_unban),
        IrcCommand("TOPIC", "TOPIC <channel> [topic]", R.string.cmd_as_topic),
        IrcCommand("FLAGS", "FLAGS <channel> <user> [flags]", R.string.cmd_as_flags),
        IrcCommand("ACCESS", "ACCESS <channel> [LIST|ADD|DEL] [user] [level]", R.string.cmd_as_access),
        IrcCommand("HELP", "HELP [command]", R.string.cmd_as_help),
    ),
    // ZNC *status — curated from https://wiki.znc.in/Using_commands
    "znc" to listOf(
        IrcCommand("Help", "Help [filter]", R.string.cmd_znc_help),
        IrcCommand("Version", "Version", R.string.cmd_znc_version),
        IrcCommand("ListNetworks", "ListNetworks", R.string.cmd_znc_listnetworks),
        IrcCommand("JumpNetwork", "JumpNetwork <network>", R.string.cmd_znc_jumpnetwork),
        IrcCommand("AddNetwork", "AddNetwork <name>", R.string.cmd_znc_addnetwork),
        IrcCommand("DelNetwork", "DelNetwork <name>", R.string.cmd_znc_delnetwork),
        IrcCommand("ListServers", "ListServers", R.string.cmd_znc_listservers),
        IrcCommand("AddServer", "AddServer <host> [+port] [pass]", R.string.cmd_znc_addserver),
        IrcCommand("DelServer", "DelServer <host> [port] [pass]", R.string.cmd_znc_delserver),
        IrcCommand("Connect", "Connect", R.string.cmd_znc_connect),
        IrcCommand("Disconnect", "Disconnect [message]", R.string.cmd_znc_disconnect),
        IrcCommand("Jump", "Jump [server]", R.string.cmd_znc_jump),
        IrcCommand("ListChans", "ListChans", R.string.cmd_znc_listchans),
        IrcCommand("ListClients", "ListClients", R.string.cmd_znc_listclients),
        IrcCommand("ListMods", "ListMods", R.string.cmd_znc_listmods),
        IrcCommand("ListAvailMods", "ListAvailMods", R.string.cmd_znc_listavailmods),
        IrcCommand("LoadMod", "LoadMod <module> [args]", R.string.cmd_znc_loadmod),
        IrcCommand("UnloadMod", "UnloadMod <module>", R.string.cmd_znc_unloadmod),
        IrcCommand("ReloadMod", "ReloadMod <module> [args]", R.string.cmd_znc_reloadmod),
        IrcCommand("Attach", "Attach <#chan>", R.string.cmd_znc_attach),
        IrcCommand("Detach", "Detach <#chan>", R.string.cmd_znc_detach),
        IrcCommand("PlayBuffer", "PlayBuffer <#chan>", R.string.cmd_znc_playbuffer),
        IrcCommand("ClearBuffer", "ClearBuffer <#chan>", R.string.cmd_znc_clearbuffer),
        IrcCommand("ClearAllChannelBuffers", "ClearAllChannelBuffers", R.string.cmd_znc_clearallchannelbuffers),
        IrcCommand("ClearAllQueryBuffers", "ClearAllQueryBuffers", R.string.cmd_znc_clearallquerybuffers),
        IrcCommand("SetBuffer", "SetBuffer <#chan> [lines]", R.string.cmd_znc_setbuffer),
        IrcCommand("Topics", "Topics", R.string.cmd_znc_topics),
        IrcCommand("Uptime", "Uptime", R.string.cmd_znc_uptime),
        IrcCommand("Traffic", "Traffic", R.string.cmd_znc_traffic),
        IrcCommand("Rehash", "Rehash", R.string.cmd_znc_rehash),
        IrcCommand("SaveConfig", "SaveConfig", R.string.cmd_znc_saveconfig),
        IrcCommand("ShowMOTD", "ShowMOTD", R.string.cmd_znc_showmotd),
    ),
    // soju BouncerServ — curated from soju(1) manpage; commands parsed as shell tokens
    "bouncerserv" to listOf(
        IrcCommand("help", "help [command]", R.string.cmd_soju_help),
        IrcCommand("network create", "network create -addr <uri> -name <name> [-nick <nick>] [-pass <pw>]", R.string.cmd_soju_network_create),
        IrcCommand("network update", "network update <name> [options]", R.string.cmd_soju_network_update),
        IrcCommand("network delete", "network delete <name>", R.string.cmd_soju_network_delete),
        IrcCommand("network status", "network status", R.string.cmd_soju_network_status),
        IrcCommand("network quote", "network quote <name> <raw line>", R.string.cmd_soju_network_quote),
        IrcCommand("channel status", "channel status [-network <name>]", R.string.cmd_soju_channel_status),
        IrcCommand("channel update", "channel update <name> [options]", R.string.cmd_soju_channel_update),
        IrcCommand("channel delete", "channel delete <name>", R.string.cmd_soju_channel_delete),
        IrcCommand("certfp generate", "certfp generate [-network <name>]", R.string.cmd_soju_certfp_generate),
        IrcCommand("certfp fingerprint", "certfp fingerprint [-network <name>]", R.string.cmd_soju_certfp_fingerprint),
        IrcCommand("sasl status", "sasl status [-network <name>]", R.string.cmd_soju_sasl_status),
        IrcCommand("sasl set-plain", "sasl set-plain [-network <name>] <user> <pass>", R.string.cmd_soju_sasl_set_plain),
        IrcCommand("sasl reset", "sasl reset [-network <name>]", R.string.cmd_soju_sasl_reset),
        IrcCommand("user update", "user update [options]", R.string.cmd_soju_user_update),
        IrcCommand("server status", "server status", R.string.cmd_soju_server_status),
    ),
)

/**
 * Parent commands that share a subcommand set. /bnc delegates to the soju
 * BouncerServ set because /bnc is just an alias for /bouncerserv.
 */
private val SUB_COMMAND_ALIASES: Map<String, String> = mapOf(
    "bnc" to "bouncerserv",
)

/**
 * For each parent command, the most spaces any of its sub-verbs contains, so the hint bar hides
 * once the typed sub-verb has more and can't match.
 */
private val SUB_COMMAND_MAX_SPACES: Map<String, Int> =
    SUB_COMMANDS.mapValues { (_, verbs) ->
        verbs.maxOfOrNull { it.name.count { c -> c == ' ' } } ?: 0
    }

/**
 * Resolve the subcommand list for [parentCmd], following aliases. Returns null
 * if the parent command has no subcommand hints configured.
 */
internal fun subCommandsFor(parentCmd: String): List<IrcCommand>? {
    val key = parentCmd.lowercase()
    val resolved = SUB_COMMAND_ALIASES[key] ?: key
    return SUB_COMMANDS[resolved]
}

/**
 * Maximum number of spaces permitted in a partial sub-verb for [parentCmd]
 * before we give up and hide the bar. Returns 0 when the parent has no entry.
 */
internal fun subCommandMaxSpaces(parentCmd: String): Int {
    val key = parentCmd.lowercase()
    val resolved = SUB_COMMAND_ALIASES[key] ?: key
    return SUB_COMMAND_MAX_SPACES[resolved] ?: 0
}

/**
 * Command-completion bar shown above the input while typing a /command. Tapping an entry completes
 * the command name and a trailing space.
 */
@Composable
internal fun CommandHints(
    query: String,           // text after the leading '/' - must be non-empty
    scriptCommands: List<IrcCommand> = emptyList(), // user-facing commands from loaded .hex scripts
    onPick: (String) -> Unit // called with "/command " ready to type args
) {
    val matches = remember(query, scriptCommands) {
        (IRC_COMMANDS + scriptCommands).filter { it.name.startsWith(query, ignoreCase = true) }
    }

    // Track which chip the user has highlighted (defaults to first match)
    var highlighted by remember(matches) { mutableStateOf(matches.firstOrNull()) }

    AnimatedVisibility(
        visible = matches.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit  = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // Tabs
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(matches.distinctBy { it.name }, key = { it.name }) { cmd ->
                        val isHighlighted = highlighted?.name == cmd.name
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isHighlighted)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.focusHighlight(RoundedCornerShape(50)).clickable {
                                highlighted = cmd
                                onPick("/${cmd.name} ")
                            }
                        ) {
                            Text(
                                text = "/${cmd.name}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                color = if (isHighlighted)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                // Detail row for the highlighted command
                highlighted?.let { cmd ->
                    HorizontalDivider(thickness = 0.5.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight(RoundedCornerShape(8.dp))
                            .clickable { onPick("/${cmd.name} ") }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Usage signature (args portion after the command name)
                        val argsText = cmd.usage.removePrefix("/${cmd.name}").trim()
                        Text(
                            text = "/${cmd.name}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (argsText.isNotEmpty()) {
                            Text(
                                text = argsText,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        Text(
                            text = cmd.descriptionRes?.let { stringResource(it) } ?: cmd.description.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sub-command completion bar, shown after a parent command with a curated verb list (/ns, /cs, /ms,
 * /hs, /bs, /as, /znc, /bouncerserv, /bnc) and a space. Typing narrows the list ("/ns id" to
 * IDENTIFY); tapping produces "/parent VERB ".
 */
@Composable
internal fun SubCommandHints(
    parentCmd: String,          // e.g. "ns", "znc", "bouncerserv" — must have a SUB_COMMANDS entry
    query: String,              // text typed after the first space (may be empty)
    onPick: (String) -> Unit    // called with "/parent subverb " ready for args
) {
    val subCmds = remember(parentCmd) { subCommandsFor(parentCmd) ?: emptyList() }
    val matches = remember(subCmds, query) {
        if (query.isEmpty()) subCmds
        else subCmds.filter { it.name.startsWith(query, ignoreCase = true) }
    }

    // Track which chip the user has highlighted (defaults to first match)
    var highlighted by remember(matches) { mutableStateOf(matches.firstOrNull()) }

    AnimatedVisibility(
        visible = matches.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit  = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // Chip row
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(matches.distinctBy { it.name }, key = { it.name }) { cmd ->
                        val isHighlighted = highlighted?.name == cmd.name
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isHighlighted)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.focusHighlight(RoundedCornerShape(50)).clickable {
                                highlighted = cmd
                                onPick("/$parentCmd ${cmd.name} ")
                            }
                        ) {
                            Text(
                                // Bare sub-verb — no leading slash — since it's
                                // rendered as a second-token suggestion.
                                text = cmd.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                color = if (isHighlighted)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                // Detail row for the highlighted sub-verb
                highlighted?.let { cmd ->
                    HorizontalDivider(thickness = 0.5.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight()
                            .clickable { onPick("/$parentCmd ${cmd.name} ") }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Show "/parent SUBVERB" as the label so the user sees
                        // the whole command they'd send.
                        Text(
                            text = "/$parentCmd ${cmd.name}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        // Args portion of the usage string, stripped of the
                        // sub-verb prefix so it reads naturally.
                        val argsText = cmd.usage.removePrefix(cmd.name).trim()
                        if (argsText.isNotEmpty()) {
                            Text(
                                text = argsText,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        Text(
                            text = cmd.descriptionRes?.let { stringResource(it) } ?: cmd.description.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Nick-completion bar shown above the input when "@" plus at least one character is typed in a
 * channel. Tapping replaces the token with "@nick " (or "nick: " at the start of the line).
 */
@Composable
internal fun NickHints(
    prefix: String,          // characters typed after "@" - must be non-empty
    nicks: List<String>,     // full nicklist for this buffer (may include mode prefixes like @/+)
    inputText: String,       // current raw input text (to decide "nick: " vs "@nick ")
    onPick: (String) -> Unit // called with the replacement text (already stripped of @-prefix)
) {
    // Strip mode-prefix characters for matching; preserve original for display.
    fun base(n: String) = n.trimStart('~', '&', '@', '%', '+')

    val matches = remember(prefix, nicks) {
        nicks.filter { base(it).startsWith(prefix, ignoreCase = true) }
             .sortedWith(compareBy { base(it).lowercase() })
             .take(16)
    }

    var highlighted by remember(matches) { mutableStateOf(matches.firstOrNull()) }

    AnimatedVisibility(
        visible = matches.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit  = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(matches.distinct(), key = { it }) { nick ->
                        val isHighlighted = highlighted == nick
                        val baseNickText = base(nick)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isHighlighted)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.focusHighlight().clickable {
                                highlighted = nick
                                // "nick: " if cursor is at start of blank input, "nick " otherwise
                                val completion = if (inputText.trimStart().startsWith("@$prefix", ignoreCase = true) &&
                                                     inputText.trimStart().length <= prefix.length + 1)
                                    "$baseNickText: "
                                else
                                    "$baseNickText "
                                onPick(completion)
                            }
                        ) {
                            Text(
                                text = nick,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                color = if (isHighlighted)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                highlighted?.let { nick ->
                    HorizontalDivider(thickness = 0.5.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight()
                            .clickable {
                                val baseNickText = base(nick)
                                val completion = if (inputText.trimStart().startsWith("@$prefix", ignoreCase = true) &&
                                                     inputText.trimStart().length <= prefix.length + 1)
                                    "$baseNickText: "
                                else
                                    "$baseNickText "
                                onPick(completion)
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "@${base(nick)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.nick_hint_tap_to_mention),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
