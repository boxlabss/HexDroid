package com.boxlabs.hexdroid.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.boxlabs.hexdroid.CapPrefs
import com.boxlabs.hexdroid.ChatFontStyle
import com.boxlabs.hexdroid.SaslConfig
import com.boxlabs.hexdroid.SaslMechanism
import com.boxlabs.hexdroid.UiSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private fun parseFontChoice(raw: String?, fallback: com.boxlabs.hexdroid.FontChoice): com.boxlabs.hexdroid.FontChoice {
    val normalized = when (raw) {
        null -> fallback.name
        "DEFAULT", "SERIF", "OPEN_SANS", "OPENSANS" -> com.boxlabs.hexdroid.FontChoice.OPEN_SANS.name
        "SANS_SERIF", "INTER" -> com.boxlabs.hexdroid.FontChoice.INTER.name
        "MONOSPACE" -> com.boxlabs.hexdroid.FontChoice.MONOSPACE.name
        else -> raw
    }
    return runCatching { com.boxlabs.hexdroid.FontChoice.valueOf(normalized) }.getOrDefault(fallback)
}


private val Context.dataStore by preferencesDataStore(name = "hexdroid_prefs")

class SettingsRepository(private val ctx: Context) {

    val secretStore: SecretStore = SecretStore(ctx.applicationContext)
    private object Keys {
        val SETTINGS_JSON = stringPreferencesKey("settings_json")
        val NETWORKS_JSON = stringPreferencesKey("networks_json")
        val LAST_NETWORK_ID = stringPreferencesKey("last_network_id")
        val DESIRED_NETWORK_IDS_JSON = stringPreferencesKey("desired_network_ids_json")
        val SECRETS_MIGRATED_V1 = booleanPreferencesKey("secrets_migrated_v1")
    
        val SECRETS_MIGRATED_V2 = booleanPreferencesKey("secrets_migrated_v2")
    }

    val settingsFlow: Flow<UiSettings> = ctx.dataStore.data.map { prefs ->
        parseSettings(prefs[Keys.SETTINGS_JSON])
    }

    val networksFlow: Flow<List<NetworkProfile>> = ctx.dataStore.data.map { prefs ->
        parseNetworks(prefs[Keys.NETWORKS_JSON])
    }

    val lastNetworkIdFlow: Flow<String?> = ctx.dataStore.data.map { prefs ->
        prefs[Keys.LAST_NETWORK_ID]
    }


    /**
     * Networks the user wants to stay connected to ("Always connected").
     *
     * This is deliberately separate from NetworkProfile.autoConnect (startup behavior) so that
     * once a user hits "Connect", we can restore and keep trying across process death / service restarts.
     */
    val desiredNetworkIdsFlow: Flow<Set<String>> = ctx.dataStore.data.map { prefs ->
        parseDesiredNetworkIds(prefs[Keys.DESIRED_NETWORK_IDS_JSON])
    }

    
    /**
     * One-time migration:
     * - Move any legacy plaintext SASL passwords from networks_json into [secretStore]
     * - Remove saslPassword from persisted JSON so it is not backed up / copied in cleartext.
     */
    suspend fun migrateLegacySecretsIfNeeded() {
    ctx.dataStore.edit { prefs ->
        val v1Done = prefs[Keys.SECRETS_MIGRATED_V1] == true
        val v2Done = prefs[Keys.SECRETS_MIGRATED_V2] == true
        if (v1Done && v2Done) return@edit

        val raw = prefs[Keys.NETWORKS_JSON]
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                var changed = false
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (id.isBlank()) continue

                    if (!v1Done) {
                        val legacySasl = o.optString("saslPassword", "")
                        if (legacySasl.isNotBlank()) {
                            secretStore.setSaslPassword(id, legacySasl)
                            o.remove("saslPassword")
                            changed = true
                        }
                    }

                    if (!v2Done) {
                        val legacyServerPass = o.optString("serverPassword", "")
                        if (legacyServerPass.isNotBlank()) {
                            secretStore.setServerPassword(id, legacyServerPass)
                            o.remove("serverPassword")
                            changed = true
                        }
                    }
                }

                if (changed) {
                    prefs[Keys.NETWORKS_JSON] = arr.toString()
                }
            } catch (_: Throwable) {
                // If parsing fails, don't block app start. We'll try again next launch.
            }
        }

        if (!v1Done) prefs[Keys.SECRETS_MIGRATED_V1] = true
        if (!v2Done) prefs[Keys.SECRETS_MIGRATED_V2] = true
    }
}

	suspend fun updateSettings(update: (UiSettings) -> UiSettings) {
        ctx.dataStore.edit { prefs ->
            val current = parseSettings(prefs[Keys.SETTINGS_JSON])
            val next = update(current)
            prefs[Keys.SETTINGS_JSON] = toSettingsJson(next).toString()
        }
    }

    suspend fun upsertNetwork(profile: NetworkProfile) {
        ctx.dataStore.edit { prefs ->
            val list = parseNetworks(prefs[Keys.NETWORKS_JSON]).toMutableList()
            val idx = list.indexOfFirst { it.id == profile.id }
            if (idx >= 0) list[idx] = profile else list.add(profile)
            prefs[Keys.NETWORKS_JSON] = toNetworksJson(list).toString()
        }
    }

    suspend fun deleteNetwork(id: String) {
        ctx.dataStore.edit { prefs ->
            val list = parseNetworks(prefs[Keys.NETWORKS_JSON]).filterNot { it.id == id }
            prefs[Keys.NETWORKS_JSON] = toNetworksJson(list).toString()
            if (prefs[Keys.LAST_NETWORK_ID] == id) prefs.remove(Keys.LAST_NETWORK_ID)
        }
    }

    suspend fun setLastNetworkId(id: String?) {
        ctx.dataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.LAST_NETWORK_ID) else prefs[Keys.LAST_NETWORK_ID] = id
        }
    }


    suspend fun setDesiredNetworkIds(ids: Set<String>) {
        ctx.dataStore.edit { prefs ->
            prefs[Keys.DESIRED_NETWORK_IDS_JSON] = toDesiredNetworkIdsJson(ids).toString()
        }
    }
    private fun parseDesiredNetworkIds(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return try {
            val arr = JSONArray(json)
            buildSet {
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i).takeIf { it.isNotBlank() } ?: continue
                    add(id)
                }
            }
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun toDesiredNetworkIdsJson(ids: Set<String>): JSONArray {
        val arr = JSONArray()
        // Keep stable ordering for diffs / easier debugging.
        ids.toList().sorted().forEach { arr.put(it) }
        return arr
    }


    // Settings JSON
    private fun parseSettings(json: String?): UiSettings {
        if (json.isNullOrBlank()) return UiSettings()
        return try {
            val o = JSONObject(json)
            UiSettings(
                themeMode = runCatching {
                    ThemeMode.valueOf(o.optString("themeMode", ThemeMode.DARK.name))
                }.getOrDefault(ThemeMode.SYSTEM),
                compactMode = o.optBoolean("compactMode", false),

                showTimestamps = o.optBoolean("showTimestamps", true),
                timestampFormat = o.optString("timestampFormat", "HH:mm:ss"),
                fontScale = o.optDouble("fontScale", 1.0).toFloat(),
                fontChoice = parseFontChoice(o.optString("fontChoice", com.boxlabs.hexdroid.FontChoice.OPEN_SANS.name), com.boxlabs.hexdroid.FontChoice.OPEN_SANS),

                chatFontChoice = parseFontChoice(o.optString("chatFontChoice", com.boxlabs.hexdroid.FontChoice.MONOSPACE.name), com.boxlabs.hexdroid.FontChoice.MONOSPACE),

                chatFontStyle = runCatching {
                    ChatFontStyle.valueOf(o.optString("chatFontStyle", ChatFontStyle.REGULAR.name))
                }.getOrDefault(ChatFontStyle.REGULAR),

                showTopicBar = o.optBoolean("showTopicBar", true),
                hideMotdOnConnect = o.optBoolean("hideMotdOnConnect", false),
                defaultShowNickList = o.optBoolean("defaultShowNickList", true),
                defaultShowBufferList = o.optBoolean("defaultShowBufferList", true),

                bufferPaneFracLandscape = o.optDouble("bufferPaneFracLandscape", 0.22).toFloat(),
                nickPaneFracLandscape = o.optDouble("nickPaneFracLandscape", 0.18).toFloat(),

                hideJoinPartQuit = o.optBoolean("hideJoinPartQuit", false),

                highlightOnNick = o.optBoolean("highlightOnNick", true),
                extraHighlightWords = o.optJSONArray("extraHighlightWords")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                } ?: emptyList(),

                notificationsEnabled = o.optBoolean("notificationsEnabled", true),
                notifyOnHighlights = o.optBoolean("notifyOnHighlights", true),
                notifyOnPrivateMessages = o.optBoolean("notifyOnPrivateMessages", true),
                showConnectionStatusNotification = o.optBoolean("showConnectionStatusNotification", true),
                keepAliveInBackground = o.optBoolean("keepAliveInBackground", true),
                autoReconnectEnabled = o.optBoolean("autoReconnectEnabled", true),
                autoReconnectDelaySec = o.optInt("autoReconnectDelaySec", 10),
                autoConnectOnStartup = o.optBoolean("autoConnectOnStartup", false),
                playSoundOnHighlight = o.optBoolean("playSoundOnHighlight", false),
                vibrateOnHighlight = o.optBoolean("vibrateOnHighlight", false),
                vibrateIntensity = runCatching {
                    com.boxlabs.hexdroid.VibrateIntensity.valueOf(
                        o.optString("vibrateIntensity", com.boxlabs.hexdroid.VibrateIntensity.MEDIUM.name)
                    )
                }.getOrDefault(com.boxlabs.hexdroid.VibrateIntensity.MEDIUM),

                loggingEnabled = o.optBoolean("loggingEnabled", false),
                logServerBuffer = o.optBoolean("logServerBuffer", false),
                retentionDays = o.optInt("retentionDays", 14),
                maxScrollbackLines = o.optInt("maxScrollbackLines", 800),
                logFolderUri = o.optString("logFolderUri", "").takeIf { it.isNotBlank() },

                ircHistoryLimit = o.optInt("ircHistoryLimit", 50),
                ircHistoryCountsAsUnread = o.optBoolean("ircHistoryCountsAsUnread", false),
                ircHistoryTriggersNotifications = o.optBoolean("ircHistoryTriggersNotifications", false),

                dccEnabled = o.optBoolean("dccEnabled", UiSettings().dccEnabled),
                dccSendMode = runCatching {
                    com.boxlabs.hexdroid.DccSendMode.valueOf(o.optString("dccSendMode", com.boxlabs.hexdroid.DccSendMode.AUTO.name))
                }.getOrDefault(com.boxlabs.hexdroid.DccSendMode.AUTO),
                dccIncomingPortMin = o.optInt("dccIncomingPortMin", 5000),
                dccIncomingPortMax = o.optInt("dccIncomingPortMax", 5010),
                dccDownloadFolderUri = o.optString("dccDownloadFolderUri", "").takeIf { it.isNotBlank() },

                ctcpVersionReply = o.optString("ctcpVersionReply", UiSettings().ctcpVersionReply),
                quitMessage = o.optString("quitMessage", UiSettings().quitMessage),
                partMessage = o.optString("partMessage", UiSettings().partMessage),
                colorizeNicks = o.optBoolean("colorizeNicks", true),
                mircColorsEnabled = o.optBoolean("mircColorsEnabled", true),
                introTourSeenVersion = o.optInt("introTourSeenVersion", 0),
                welcomeCompleted = o.optBoolean("welcomeCompleted", false),
                appLanguage = o.optString("appLanguage", "").takeIf { it.isNotBlank() },
                portraitNicklistOverlay = o.optBoolean("portraitNicklistOverlay", true),
                portraitNickPaneFrac = o.optDouble("portraitNickPaneFrac", 0.35).toFloat(),
            )
        } catch (_: Throwable) {
            UiSettings()
        }
    }

    private fun toSettingsJson(s: UiSettings): JSONObject {
        val o = JSONObject()
        o.put("themeMode", s.themeMode.name)
        o.put("compactMode", s.compactMode)

        o.put("showTimestamps", s.showTimestamps)
        o.put("timestampFormat", s.timestampFormat)
        o.put("fontScale", s.fontScale.toDouble())
        o.put("fontChoice", s.fontChoice.name)
        o.put("chatFontChoice", s.chatFontChoice.name)
        o.put("chatFontStyle", s.chatFontStyle.name)

        o.put("showTopicBar", s.showTopicBar)
        o.put("hideMotdOnConnect", s.hideMotdOnConnect)
        o.put("defaultShowNickList", s.defaultShowNickList)
        o.put("defaultShowBufferList", s.defaultShowBufferList)

        o.put("bufferPaneFracLandscape", s.bufferPaneFracLandscape.toDouble())
        o.put("nickPaneFracLandscape", s.nickPaneFracLandscape.toDouble())

        o.put("hideJoinPartQuit", s.hideJoinPartQuit)

        o.put("highlightOnNick", s.highlightOnNick)
        o.put("extraHighlightWords", JSONArray(s.extraHighlightWords))

        o.put("notificationsEnabled", s.notificationsEnabled)
        o.put("notifyOnHighlights", s.notifyOnHighlights)
        o.put("notifyOnPrivateMessages", s.notifyOnPrivateMessages)
        o.put("showConnectionStatusNotification", s.showConnectionStatusNotification)
        o.put("keepAliveInBackground", s.keepAliveInBackground)
        o.put("autoReconnectEnabled", s.autoReconnectEnabled)
        o.put("autoReconnectDelaySec", s.autoReconnectDelaySec)
        o.put("autoConnectOnStartup", s.autoConnectOnStartup)
        o.put("playSoundOnHighlight", s.playSoundOnHighlight)
        o.put("vibrateOnHighlight", s.vibrateOnHighlight)
        o.put("vibrateIntensity", s.vibrateIntensity.name)

        o.put("loggingEnabled", s.loggingEnabled)
        o.put("logServerBuffer", s.logServerBuffer)
        o.put("retentionDays", s.retentionDays)
        o.put("maxScrollbackLines", s.maxScrollbackLines)
        o.put("logFolderUri", s.logFolderUri ?: "")

        o.put("ircHistoryLimit", s.ircHistoryLimit)
        o.put("ircHistoryCountsAsUnread", s.ircHistoryCountsAsUnread)
        o.put("ircHistoryTriggersNotifications", s.ircHistoryTriggersNotifications)

        o.put("dccEnabled", s.dccEnabled)
        o.put("dccSendMode", s.dccSendMode.name)
        o.put("dccIncomingPortMin", s.dccIncomingPortMin)
        o.put("dccIncomingPortMax", s.dccIncomingPortMax)
        o.put("dccDownloadFolderUri", s.dccDownloadFolderUri ?: "")

        o.put("ctcpVersionReply", s.ctcpVersionReply)
        o.put("quitMessage", s.quitMessage)
        o.put("partMessage", s.partMessage)
        o.put("colorizeNicks", s.colorizeNicks)
        o.put("mircColorsEnabled", s.mircColorsEnabled)
        o.put("introTourSeenVersion", s.introTourSeenVersion)
        o.put("welcomeCompleted", s.welcomeCompleted)
        o.put("appLanguage", s.appLanguage ?: "")
        o.put("portraitNicklistOverlay", s.portraitNicklistOverlay)
        o.put("portraitNickPaneFrac", s.portraitNickPaneFrac.toDouble())

        return o
    }

    // Networks JSON
    private fun parseNetworks(json: String?): List<NetworkProfile> {
        if (json.isNullOrBlank()) return defaultNetworks()
        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<NetworkProfile>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += NetworkProfile(
                    id = o.optString("id"),
                    name = o.optString("name", "Network"),
                    host = o.optString("host"),
                    port = o.optInt("port", 6697),
                    useTls = o.optBoolean("useTls", true),
                    allowInsecurePlaintext = o.optBoolean("allowInsecurePlaintext", false),
                    allowInvalidCerts = o.optBoolean("allowInvalidCerts", false),
                    serverPassword = o.optString("serverPassword", "").takeIf { it.isNotBlank() },

                    tlsClientCertId = o.optString("tlsClientCertId", "").takeIf { it.isNotBlank() },
                    tlsClientCertLabel = o.optString("tlsClientCertLabel", "").takeIf { it.isNotBlank() },

                    nick = o.optString("nick", "HexDroidUser"),
                    altNick = o.optString("altNick", "").takeIf { it.isNotBlank() },
                    username = o.optString("username", "hexdroid"),
                    realname = o.optString("realname", "HexDroid IRC"),

                    saslEnabled = o.optBoolean("saslEnabled", false),
                    saslMechanism = SaslMechanism.valueOf(o.optString("saslMechanism", SaslMechanism.PLAIN.name)),
                    saslAuthcid = o.optString("saslAuthcid", "").takeIf { it.isNotBlank() },
                    saslPassword = null,

                    caps = CapPrefs(
                        messageTags = o.optBoolean("cap_messageTags", true),
                        serverTime = o.optBoolean("cap_serverTime", true),
                        echoMessage = o.optBoolean("cap_echoMessage", true),
                        labeledResponse = o.optBoolean("cap_labeledResponse", true),
                        batch = o.optBoolean("cap_batch", true),
                        draftChathistory = o.optBoolean("cap_draftChathistory", true),
                        draftEventPlayback = o.optBoolean("cap_draftEventPlayback", true),
                        utf8Only = o.optBoolean("cap_utf8Only", true),
						accountNotify = o.optBoolean("cap_accountNotify", true),
						awayNotify = o.optBoolean("cap_awayNotify", true),
						chghost = o.optBoolean("cap_chghost", true),
						extendedJoin = o.optBoolean("cap_extendedJoin", true),
						inviteNotify = o.optBoolean("cap_inviteNotify", true),
						multiPrefix = o.optBoolean("cap_multiPrefix", true),
						sasl = o.optBoolean("cap_sasl", true),
						setname = o.optBoolean("cap_setname", false),
						userhostInNames = o.optBoolean("cap_userhostInNames", false),
						draftRelaymsg = o.optBoolean("cap_draftRelaymsg", false),
						draftReadMarker = o.optBoolean("cap_draftReadMarker", false)
                    ),

                    autoJoin = o.optJSONArray("autoJoin")?.let { aj ->
                        (0 until aj.length()).mapNotNull { j ->
                            val line = aj.optString(j)
                            parseAutoJoinLine(line)
                        }
                    } ?: emptyList(),

                    autoConnect = o.optBoolean("autoConnect", false),
                    autoReconnect = o.optBoolean("autoReconnect", true),

                    ignoredNicks = o.optJSONArray("ignoreList")?.let { ig ->
                        (0 until ig.length()).mapNotNull { j ->
                            ig.optString(j)?.trim()?.takeIf { it.isNotBlank() }
                        }.distinctBy { it.lowercase() }
                    } ?: emptyList(),

                    autoCommandDelaySeconds = o.optInt("autoCommandDelaySeconds", 0),
                    serviceAuthCommand = o.optString("serviceAuthCommand", "").takeIf { it.isNotBlank() },
                    autoCommandsText = o.optString("autoCommandsText", ""),

                    encoding = o.optString("encoding", "auto"),
                )
            }
            out
        } catch (_: Throwable) { defaultNetworks() }
    }

    private fun toNetworksJson(list: List<NetworkProfile>): JSONArray {
        val arr = JSONArray()
        for (n in list) {
            val o = JSONObject()
            o.put("id", n.id)
            o.put("name", n.name)
            o.put("host", n.host)
            o.put("port", n.port)
            o.put("useTls", n.useTls)
            o.put("allowInsecurePlaintext", n.allowInsecurePlaintext)
            o.put("allowInvalidCerts", n.allowInvalidCerts)
            o.put("tlsClientCertId", n.tlsClientCertId ?: "")
            o.put("tlsClientCertLabel", n.tlsClientCertLabel ?: "")

            o.put("nick", n.nick)
            o.put("altNick", n.altNick ?: "")
            o.put("username", n.username)
            o.put("realname", n.realname)

            o.put("saslEnabled", n.saslEnabled)
            o.put("saslMechanism", n.saslMechanism.name)
            o.put("saslAuthcid", n.saslAuthcid ?: "")
            // saslPassword is stored encrypted via SecretStore

            o.put("cap_messageTags", n.caps.messageTags)
            o.put("cap_serverTime", n.caps.serverTime)
            o.put("cap_echoMessage", n.caps.echoMessage)
            o.put("cap_labeledResponse", n.caps.labeledResponse)
            o.put("cap_batch", n.caps.batch)
            o.put("cap_draftChathistory", n.caps.draftChathistory)
            o.put("cap_draftEventPlayback", n.caps.draftEventPlayback)
            o.put("cap_utf8Only", n.caps.utf8Only)
			o.put("cap_accountNotify", n.caps.accountNotify)
			o.put("cap_awayNotify", n.caps.awayNotify)
			o.put("cap_chghost", n.caps.chghost)
			o.put("cap_extendedJoin", n.caps.extendedJoin)
			o.put("cap_inviteNotify", n.caps.inviteNotify)
			o.put("cap_multiPrefix", n.caps.multiPrefix)
			o.put("cap_sasl", n.caps.sasl)
			o.put("cap_setname", n.caps.setname)
			o.put("cap_userhostInNames", n.caps.userhostInNames)
			o.put("cap_draftRelaymsg", n.caps.draftRelaymsg)
			o.put("cap_draftReadMarker", n.caps.draftReadMarker)

            o.put("autoJoin", JSONArray(n.autoJoin.map { it.toLine() }))
            o.put("autoConnect", n.autoConnect)
            o.put("autoReconnect", n.autoReconnect)
            o.put("ignoreList", JSONArray(n.ignoredNicks))

            o.put("autoCommandDelaySeconds", n.autoCommandDelaySeconds)
            o.put("serviceAuthCommand", n.serviceAuthCommand ?: "")
            o.put("autoCommandsText", n.autoCommandsText)

            o.put("encoding", n.encoding)
            arr.put(o)
        }
        return arr
    }

    private fun defaultNetworks(): List<NetworkProfile> = listOf(
        NetworkProfile(
            id = "AfterNET",
            name = "AfterNET",
            host = "irc.afternet.org",
            port = 6697,
            useTls = true,
            allowInvalidCerts = false,
            serverPassword = null,
            nick = "HexDroidUser",
            altNick = "HexDroidUser",
            username = "hexdroid",
            realname = "HexDroid IRC for Android",
            saslEnabled = false,
            saslMechanism = SaslMechanism.PLAIN,
            saslAuthcid = null,
            saslPassword = null,
            caps = CapPrefs(),
            autoJoin = listOf(AutoJoinChannel("#afternet,#hexdroid", null))
        ),
        NetworkProfile(
            id = "Libera",
            name = "Libera.Chat",
            host = "irc.libera.chat",
            port = 6697,
            useTls = true,
            allowInvalidCerts = false,
            serverPassword = null,
            nick = "HexDroidUser",
            altNick = "HexDroidUser",
            username = "hexdroid",
            realname = "HexDroid IRC for Android",
            saslEnabled = false,
            saslMechanism = SaslMechanism.PLAIN,
            saslAuthcid = null,
            saslPassword = null,
            caps = CapPrefs(),
            autoJoin = emptyList()
        ),
        NetworkProfile(
            id = "Rizon",
            name = "Rizon",
            host = "irc.rizon.net",
            port = 6697,
            useTls = true,
            allowInvalidCerts = false,
            serverPassword = null,
            nick = "HexDroid",
            altNick = "HexDroidUser",
            username = "hexdroid",
            realname = "HexDroid IRC for Android",
            saslEnabled = false,
            saslMechanism = SaslMechanism.PLAIN,
            saslAuthcid = null,
            saslPassword = null,
            caps = CapPrefs(),
            autoJoin = emptyList()
        ),
        NetworkProfile(
            id = "Undernet",
            name = "Undernet",
            host = "irc.undernet.org",
            port = 6697,
            useTls = true,
            allowInvalidCerts = false,
            serverPassword = null,
            nick = "HexDroid",
            altNick = "HexDroidUser",
            username = "hexdroid",
            realname = "HexDroid IRC for Android",
            saslEnabled = false,
            saslMechanism = SaslMechanism.PLAIN,
            saslAuthcid = null,
            saslPassword = null,
            caps = CapPrefs(),
            autoJoin = emptyList()
        ),
        NetworkProfile(
            id = "EFnet",
            name = "EFnet",
            host = "irc.efnet.org",
            port = 6697,
            useTls = true,
            allowInvalidCerts = false,
            serverPassword = null,
            nick = "HexDroid",
            altNick = "HexDroidUser",
            username = "hexdroid",
            realname = "HexDroid IRC for Android",
            saslEnabled = false,
            saslMechanism = SaslMechanism.PLAIN,
            saslAuthcid = null,
            saslPassword = null,
            caps = CapPrefs(),
            autoJoin = emptyList()
        ),
        NetworkProfile(
            id = "QuakeNet",
            name = "QuakeNet",
            host = "irc.quakenet.org",
            port = 6697,
            useTls = true,
            allowInvalidCerts = false,
            serverPassword = null,
            nick = "HexDroid",
            altNick = "HexDroidUser",
            username = "hexdroid",
            realname = "HexDroid IRC for Android",
            saslEnabled = false,
            saslMechanism = SaslMechanism.PLAIN,
            saslAuthcid = null,
            saslPassword = null,
            caps = CapPrefs(),
            autoJoin = emptyList()
        ),
        NetworkProfile(
            id = "DALnet",
            name = "DALnet",
            host = "irc.dal.net",
            port = 6697,
            useTls = true,
            allowInvalidCerts = false,
            serverPassword = null,
            nick = "HexDroid",
            altNick = "HexDroidUser",
            username = "hexdroid",
            realname = "HexDroid IRC for Android",
            saslEnabled = false,
            saslMechanism = SaslMechanism.PLAIN,
            saslAuthcid = null,
            saslPassword = null,
            caps = CapPrefs(),
            autoJoin = emptyList()
        )
    )

    private fun parseAutoJoinLine(line: String): AutoJoinChannel? {
        val t = line.trim()
        if (t.isBlank()) return null
        val parts = t.split(Regex("\\s+"))
        val chan = parts.getOrNull(0) ?: return null
        val key = parts.getOrNull(1)
        return AutoJoinChannel(chan, key?.takeIf { it.isNotBlank() })
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AutoJoinChannel(val channel: String, val key: String? = null) {
    fun toLine(): String = if (key.isNullOrBlank()) channel else "$channel $key"
}

data class NetworkProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val useTls: Boolean,
    // If true, allow insecure plaintext IRC connections (no TLS).
    val allowInsecurePlaintext: Boolean = false,
    val allowInvalidCerts: Boolean,
    val serverPassword: String? = null,

    // Optional TLS client certificate (PKCS#12) stored encrypted on-device.
    val tlsClientCertId: String? = null,
    val tlsClientCertLabel: String? = null,

    val nick: String,
    val altNick: String?,
    val username: String,
    val realname: String,

    val saslEnabled: Boolean,
    val saslMechanism: SaslMechanism,
    val saslAuthcid: String? = null,
    val saslPassword: String? = null,

    val caps: CapPrefs,
    val autoJoin: List<AutoJoinChannel>,

    // Ignored nicknames (case-insensitive match).
    val ignoredNicks: List<String> = emptyList(),

    // If enabled, the app will attempt to auto-connect this network on startup.
    val autoConnect: Boolean = false,

    // If enabled, the app will retry connections for this network when disconnected.
    val autoReconnect: Boolean = true,

    // Post-connect commands
    val autoCommandDelaySeconds: Int = 0,
    val serviceAuthCommand: String? = null,
    val autoCommandsText: String = "",

    /**
     * Character encoding for this network.
     * - "auto" = try UTF-8, auto-detect non-UTF-8 encodings
     * - Or explicit: "UTF-8", "windows-1251", "ISO-8859-1", etc.
     */
    val encoding: String = "auto",
) {
    fun toIrcConfig(
        saslPasswordOverride: String? = null,
        serverPasswordOverride: String? = null,
        tlsClientCert: com.boxlabs.hexdroid.TlsClientCert? = null
    ): com.boxlabs.hexdroid.IrcConfig {
        val effectivePassword = saslPasswordOverride ?: saslPassword
        val sasl = if (!saslEnabled) SaslConfig.Disabled else SaslConfig.Enabled(
            mechanism = saslMechanism,
            authcid = saslAuthcid,
            password = if (saslMechanism == SaslMechanism.EXTERNAL) null else effectivePassword
        )
        return com.boxlabs.hexdroid.IrcConfig(
            host = host,
            port = port,
            useTls = useTls,
            allowInvalidCerts = allowInvalidCerts,
            nick = nick,
            altNick = altNick,
            username = username,
            realname = realname,
            serverPassword = serverPasswordOverride ?: serverPassword,
            sasl = sasl,
            clientCert = tlsClientCert,
            capPrefs = caps,
            autoJoin = autoJoin,
            encoding = encoding
        )
    }
}