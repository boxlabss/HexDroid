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

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.CapPrefs
import com.boxlabs.hexdroid.ClientCertDraft
import com.boxlabs.hexdroid.ClientCertFormat
import com.boxlabs.hexdroid.EncodingHelper
import com.boxlabs.hexdroid.SaslMechanism
import com.boxlabs.hexdroid.UiState
import com.boxlabs.hexdroid.data.AutoJoinChannel
import com.boxlabs.hexdroid.data.NetworkProfile
import androidx.compose.ui.res.stringResource
import com.boxlabs.hexdroid.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkEditScreen(
    state: UiState,
    onCancel: () -> Unit,
    onSave: (NetworkProfile, ClientCertDraft?, Boolean) -> Unit
) {
    val n0 = state.editingNetwork ?: run {
        Text(stringResource(R.string.netedit_error_no_network))
        return
    }

    // Connection
    var name by remember(n0.id) { mutableStateOf(n0.name) }
    var host by remember(n0.id) { mutableStateOf(n0.host) }
    var port by remember(n0.id) { mutableStateOf(n0.port.toString()) }
    var tls by remember(n0.id) { mutableStateOf(n0.useTls) }
    var allowInvalidCerts by remember(n0.id) { mutableStateOf(n0.allowInvalidCerts) }
    var allowInsecurePlaintext by remember(n0.id) { mutableStateOf(n0.allowInsecurePlaintext) }

    val ctx = LocalContext.current

    // TLS client certificate selection
    var tlsClientCertId by remember(n0.id) { mutableStateOf(n0.tlsClientCertId) }
    var tlsClientCertLabel by remember(n0.id, n0.tlsClientCertId) { mutableStateOf(n0.tlsClientCertLabel ?: "") }

    var certFormat by remember(n0.id) { mutableStateOf(ClientCertFormat.PEM_BUNDLE) }
    var certFormatExpanded by remember(n0.id) { mutableStateOf(false) }

    var pendingPemUri by remember(n0.id) { mutableStateOf<Uri?>(null) }
    var pendingPemLabel by remember(n0.id) { mutableStateOf<String?>(null) }

    var pendingCertUri by remember(n0.id) { mutableStateOf<Uri?>(null) }
    var pendingCertLabel by remember(n0.id) { mutableStateOf<String?>(null) }

    var pendingKeyUri by remember(n0.id) { mutableStateOf<Uri?>(null) }
    var pendingKeyLabel by remember(n0.id) { mutableStateOf<String?>(null) }

    var pendingKeyPassword by remember(n0.id) { mutableStateOf("") }
    var removeClientCert by remember(n0.id) { mutableStateOf(false) }
    var clientCertUiError by remember(n0.id) { mutableStateOf<String?>(null) }

    fun queryDisplayName(uri: Uri): String? {
        val cr = ctx.contentResolver
        return cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }

    fun clearPendingCertSelection() {
        pendingPemUri = null
        pendingPemLabel = null
        pendingCertUri = null
        pendingCertLabel = null
        pendingKeyUri = null
        pendingKeyLabel = null
        pendingKeyPassword = ""
        clientCertUiError = null
    }

    val pickPem = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            clientCertUiError = null
            pendingPemUri = uri
            pendingPemLabel = queryDisplayName(uri) ?: "client.pem"
            removeClientCert = false
        }
    }

    val pickCrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            clientCertUiError = null
            pendingCertUri = uri
            pendingCertLabel = queryDisplayName(uri) ?: "client.crt"
            removeClientCert = false
        }
    }

    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            clientCertUiError = null
            pendingKeyUri = uri
            pendingKeyLabel = queryDisplayName(uri) ?: "client.key"
            removeClientCert = false
        }
    }

    var serverPassword by remember(n0.id, n0.serverPassword) { mutableStateOf(n0.serverPassword ?: "") }
    var autoConnect by remember(n0.id) { mutableStateOf(n0.autoConnect) }
    var autoReconnect by remember(n0.id) { mutableStateOf(n0.autoReconnect) }
    var isBouncer by remember(n0.id) { mutableStateOf(n0.isBouncer) }
    var encoding by remember(n0.id) { mutableStateOf(n0.encoding) }
    var encodingExpanded by remember { mutableStateOf(false) }

    // Identity
    var nick by remember(n0.id) { mutableStateOf(n0.nick) }
    var altNick by remember(n0.id) { mutableStateOf(n0.altNick ?: "") }
    var username by remember(n0.id) { mutableStateOf(n0.username) }
    var realname by remember(n0.id) { mutableStateOf(n0.realname) }

    // SASL
    var saslEnabled by remember(n0.id) { mutableStateOf(n0.saslEnabled) }
    var saslMechanism by remember(n0.id) { mutableStateOf(n0.saslMechanism) }
    var saslAuthcid by remember(n0.id) { mutableStateOf(n0.saslAuthcid ?: "") }
    var saslPassword by remember(n0.id, n0.saslPassword) { mutableStateOf(n0.saslPassword ?: "") }

    // IRCv3 caps
    var showAdvancedCaps by remember { mutableStateOf(false) }
    var capMessageTags by remember(n0.id) { mutableStateOf(n0.caps.messageTags) }
    var capServerTime by remember(n0.id) { mutableStateOf(n0.caps.serverTime) }
    var capEcho by remember(n0.id) { mutableStateOf(n0.caps.echoMessage) }
    var capLabeled by remember(n0.id) { mutableStateOf(n0.caps.labeledResponse) }
    var capBatch by remember(n0.id) { mutableStateOf(n0.caps.batch) }
    var capDraftHistory by remember(n0.id) { mutableStateOf(n0.caps.draftChathistory) }
    var capDraftPlayback by remember(n0.id) { mutableStateOf(n0.caps.draftEventPlayback) }
    var capUtf8Only by remember(n0.id) { mutableStateOf(n0.caps.utf8Only) }
    var capAccountNotify by remember(n0.id) { mutableStateOf(n0.caps.accountNotify) }
    var capAwayNotify by remember(n0.id) { mutableStateOf(n0.caps.awayNotify) }
    var capChghost by remember(n0.id) { mutableStateOf(n0.caps.chghost) }
    var capExtendedJoin by remember(n0.id) { mutableStateOf(n0.caps.extendedJoin) }
    var capInviteNotify by remember(n0.id) { mutableStateOf(n0.caps.inviteNotify) }
    var capMultiPrefix by remember(n0.id) { mutableStateOf(n0.caps.multiPrefix) }
    var capSasl by remember(n0.id) { mutableStateOf(n0.caps.sasl) }
    var capSetname by remember(n0.id) { mutableStateOf(n0.caps.setname) }
    var capUserhostInNames by remember(n0.id) { mutableStateOf(n0.caps.userhostInNames) }
    var capDraftRelaymsg by remember(n0.id) { mutableStateOf(n0.caps.draftRelaymsg) }
    var capDraftReadMarker by remember(n0.id) { mutableStateOf(n0.caps.draftReadMarker) }

    var autoJoinText by remember(n0.id) {
        mutableStateOf(n0.autoJoin.joinToString("\n") { it.toLine() })
    }

    // Post-connect commands
    var postDelayText by remember(n0.id) { mutableStateOf(n0.autoCommandDelaySeconds.toString()) }
    var serviceAuthCommand by remember(n0.id) { mutableStateOf(n0.serviceAuthCommand ?: "") }
    var autoCommandsText by remember(n0.id) { mutableStateOf(n0.autoCommandsText) }

    val mechLabels = mapOf(
        SaslMechanism.PLAIN to "PLAIN",
        SaslMechanism.EXTERNAL to "EXTERNAL (client cert)",
        SaslMechanism.SCRAM_SHA_256 to "SCRAM-SHA-256"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.network_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    Button(onClick = {
                        val p = port.filter { it.isDigit() }.toIntOrNull() ?: 6697

                        val aj = autoJoinText
                            .lines()
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .mapNotNull { line ->
                                val parts = line.split(Regex("\\s+"))
                                val c = parts.getOrNull(0) ?: return@mapNotNull null
                                val k = parts.getOrNull(1)
                                AutoJoinChannel(c, k?.takeIf { it.isNotBlank() })
                            }

                        val caps = CapPrefs(
                            messageTags = capMessageTags,
                            serverTime = capServerTime,
                            echoMessage = capEcho,
                            labeledResponse = capLabeled,
                            batch = capBatch,
                            draftChathistory = capDraftHistory,
                            draftEventPlayback = capDraftPlayback,
                            utf8Only = capUtf8Only,
                            accountNotify = capAccountNotify,
                            awayNotify = capAwayNotify,
                            chghost = capChghost,
                            extendedJoin = capExtendedJoin,
                            inviteNotify = capInviteNotify,
                            multiPrefix = capMultiPrefix,
                            sasl = capSasl,
                            setname = capSetname,
                            userhostInNames = capUserhostInNames,
                            draftRelaymsg = capDraftRelaymsg,
                            draftReadMarker = capDraftReadMarker
                        )

                        clientCertUiError = null
                        val keyPwd = pendingKeyPassword.trim().takeIf { it.isNotBlank() }

                        val certDraft: ClientCertDraft? = when (certFormat) {
                            ClientCertFormat.PEM_BUNDLE -> pendingPemUri?.let { uri ->
                                ClientCertDraft(
                                    format = ClientCertFormat.PEM_BUNDLE,
                                    uri = uri,
                                    password = keyPwd,
                                    displayName = pendingPemLabel
                                )
                            }
                            ClientCertFormat.CERT_AND_KEY -> when {
                                pendingCertUri != null && pendingKeyUri != null -> ClientCertDraft(
                                    format = ClientCertFormat.CERT_AND_KEY,
                                    uri = pendingCertUri!!,
                                    keyUri = pendingKeyUri!!,
                                    password = keyPwd,
                                    displayName = pendingCertLabel,
                                    keyDisplayName = pendingKeyLabel
                                )
                                pendingCertUri != null || pendingKeyUri != null -> {
                                    clientCertUiError = ctx.getString(R.string.netedit_error_cert_key_missing)
                                    return@Button
                                }
                                else -> null
                            }
                            ClientCertFormat.PKCS12 -> pendingPemUri?.let { uri ->
                                ClientCertDraft(
                                    format = ClientCertFormat.PKCS12,
                                    uri = uri,
                                    password = keyPwd,
                                    displayName = pendingPemLabel
                                )
                            }
                        }

                        onSave(
                            n0.copy(
                                name = name.trim().ifBlank { "Network" },
                                host = host.trim(),
                                port = p,
                                useTls = tls,
                                allowInvalidCerts = allowInvalidCerts,
                                allowInsecurePlaintext = allowInsecurePlaintext,
                                serverPassword = serverPassword.trim().takeIf { it.isNotBlank() },
                                tlsClientCertId = tlsClientCertId,
                                tlsClientCertLabel = tlsClientCertLabel.trim().takeIf { it.isNotBlank() },
                                nick = nick.trim().ifBlank { "HexDroidUser" },
                                altNick = altNick.trim().takeIf { it.isNotBlank() },
                                username = username.trim().ifBlank { "hexdroid" },
                                realname = realname.trim().ifBlank { "HexDroid IRC" },
                                saslEnabled = saslEnabled,
                                saslMechanism = saslMechanism,
                                saslAuthcid = saslAuthcid.trim().takeIf { it.isNotBlank() },
                                saslPassword = saslPassword.takeIf { it.isNotBlank() },
                                caps = caps,
                                autoJoin = aj,
                                autoConnect = autoConnect,
                                autoReconnect = autoReconnect,
                                isBouncer = isBouncer,
                                autoCommandDelaySeconds = postDelayText.toIntOrNull() ?: 0,
                                serviceAuthCommand = serviceAuthCommand.trim().takeIf { it.isNotBlank() },
                                autoCommandsText = autoCommandsText,
                                encoding = encoding
                            ),
                            certDraft,
                            removeClientCert
                        )
                    }) {
                        Text(stringResource(R.string.save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            state.networkEditError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            CardSection(stringResource(R.string.netedit_section_connection)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.netedit_network_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.network_host)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.network_port)) },
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.network_use_tls))
                    Switch(checked = tls, onCheckedChange = { tls = it })
                }

                AnimatedVisibility(visible = !tls) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.netedit_allow_insecure_plaintext))
                        Switch(checked = allowInsecurePlaintext, onCheckedChange = { allowInsecurePlaintext = it })
                    }
                }

                if (tls) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.network_allow_invalid_certs))
                        Switch(checked = allowInvalidCerts, onCheckedChange = { allowInvalidCerts = it })
                    }
                }
            }

            CardSection(stringResource(R.string.netedit_section_identity)) {
                OutlinedTextField(
                    value = nick,
                    onValueChange = { nick = it },
                    label = { Text(stringResource(R.string.network_nick)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = altNick,
                    onValueChange = { altNick = it },
                    label = { Text(stringResource(R.string.network_alt_nick)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.netedit_username_ident)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = realname,
                    onValueChange = { realname = it },
                    label = { Text(stringResource(R.string.network_realname)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            CardSection(stringResource(R.string.netedit_section_autoconnect)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.netedit_connect_on_start))
                    Switch(checked = autoConnect, onCheckedChange = { autoConnect = it })
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.netedit_auto_reconnect))
                    Switch(checked = autoReconnect, onCheckedChange = { autoReconnect = it })
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Bouncer (ZNC / soju)")
                        Text(
                            "Skips auto-join, requests playback of missed messages",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = isBouncer, onCheckedChange = { isBouncer = it })
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                OutlinedTextField(
                    value = serverPassword,
                    onValueChange = { serverPassword = it },
                    label = { Text(stringResource(R.string.netedit_server_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            CardSection(stringResource(R.string.netedit_section_sasl)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.netedit_enable_sasl))
                    Switch(checked = saslEnabled, onCheckedChange = { saslEnabled = it })
                }

                AnimatedVisibility(visible = saslEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        var mechExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(mechExpanded, { mechExpanded = it }) {
                            OutlinedTextField(
                                value = mechLabels[saslMechanism] ?: saslMechanism.name,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.netedit_mechanism)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mechExpanded) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                            )
                            ExposedDropdownMenu(mechExpanded, { mechExpanded = false }) {
                                SaslMechanism.entries.forEach { mech ->
                                    DropdownMenuItem(
                                        text = { Text(mechLabels[mech] ?: mech.name) },
                                        onClick = { saslMechanism = mech; mechExpanded = false }
                                    )
                                }
                            }
                        }

                        if (saslMechanism != SaslMechanism.EXTERNAL) {
                            OutlinedTextField(
                                value = saslAuthcid,
                                onValueChange = { saslAuthcid = it },
                                label = { Text(stringResource(R.string.netedit_sasl_username)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = saslPassword,
                                onValueChange = { saslPassword = it },
                                label = { Text(stringResource(R.string.netedit_password)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                stringResource(R.string.netedit_sasl_external_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = tls) {
                CardSection(stringResource(R.string.netedit_section_tls_cert)) {
                    val activeLabel = when {
                        pendingPemLabel != null -> pendingPemLabel
                        pendingCertLabel != null -> pendingCertLabel
                        tlsClientCertLabel.isNotBlank() -> tlsClientCertLabel
                        else -> null
                    }

                    if (activeLabel != null) {
                        Text(stringResource(R.string.netedit_cert_selected, activeLabel), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(stringResource(R.string.netedit_cert_none), style = MaterialTheme.typography.bodySmall)
                    }

                    if (clientCertUiError != null) {
                        Text(clientCertUiError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    ExposedDropdownMenuBox(
                        expanded = certFormatExpanded,
                        onExpandedChange = { certFormatExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val label = when (certFormat) {
                            ClientCertFormat.PEM_BUNDLE -> stringResource(R.string.netedit_format_pem)
                            ClientCertFormat.CERT_AND_KEY -> stringResource(R.string.netedit_format_crt_key)
                            ClientCertFormat.PKCS12 -> stringResource(R.string.netedit_format_p12)
                        }
                        OutlinedTextField(
                            value = label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.netedit_cert_format)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = certFormatExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = certFormatExpanded,
                            onDismissRequest = { certFormatExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.netedit_format_pem)) },
                                onClick = {
                                    certFormat = ClientCertFormat.PEM_BUNDLE
                                    clearPendingCertSelection()
                                    certFormatExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.netedit_format_crt_key)) },
                                onClick = {
                                    certFormat = ClientCertFormat.CERT_AND_KEY
                                    clearPendingCertSelection()
                                    certFormatExpanded = false
                                }
                            )
                        }
                    }

                    when (certFormat) {
                        ClientCertFormat.PEM_BUNDLE -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { pickPem.launch(arrayOf("*/*")) }) {
                                    Text(if (pendingPemUri == null) stringResource(R.string.netedit_cert_choose_pem) else stringResource(R.string.netedit_cert_replace_pem))
                                }
                                OutlinedButton(
                                    enabled = pendingPemUri != null,
                                    onClick = {
                                        pendingPemUri = null
                                        pendingPemLabel = null
                                        pendingKeyPassword = ""
                                        clientCertUiError = null
                                    }
                                ) { Text(stringResource(R.string.netedit_cert_clear)) }
                            }
                            Text(stringResource(R.string.netedit_cert_pem_desc), style = MaterialTheme.typography.bodySmall)
                        }
                        ClientCertFormat.CERT_AND_KEY -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { pickCrt.launch(arrayOf("*/*")) }) {
                                    Text(if (pendingCertUri == null) stringResource(R.string.netedit_cert_choose_crt) else stringResource(R.string.netedit_cert_replace_crt))
                                }
                                Button(onClick = { pickKey.launch(arrayOf("*/*")) }) {
                                    Text(if (pendingKeyUri == null) stringResource(R.string.netedit_cert_choose_key) else stringResource(R.string.netedit_cert_replace_key))
                                }
                            }
                            val certName = pendingCertLabel?.let { stringResource(R.string.netedit_cert_label_crt, it) }
                            val keyName = pendingKeyLabel?.let { stringResource(R.string.netedit_cert_label_key, it) }
                            if (certName != null) Text(certName, style = MaterialTheme.typography.bodySmall)
                            if (keyName != null) Text(keyName, style = MaterialTheme.typography.bodySmall)
                        }
                        ClientCertFormat.PKCS12 -> Unit
                    }

                    OutlinedTextField(
                        value = pendingKeyPassword,
                        onValueChange = { pendingKeyPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.netedit_cert_key_password)) }
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = (tlsClientCertId != null) || (activeLabel != null),
                            onClick = {
                                clearPendingCertSelection()
                                tlsClientCertLabel = ""
                                removeClientCert = true
                            }
                        ) { Text(stringResource(R.string.netedit_cert_remove)) }
                        OutlinedButton(
                            enabled = removeClientCert,
                            onClick = {
                                removeClientCert = false
                                clientCertUiError = null
                            }
                        ) { Text(stringResource(R.string.netedit_cert_undo_remove)) }
                    }
                }
            }
			
            CardSection(stringResource(R.string.netedit_section_autojoin)) {
                Text(
                    stringResource(R.string.netedit_autojoin_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = autoJoinText,
                    onValueChange = { autoJoinText = it },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            CardSection(stringResource(R.string.netedit_section_commands)) {
                OutlinedTextField(
                    value = postDelayText,
                    onValueChange = { postDelayText = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.netedit_cmd_delay)) },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(stringResource(R.string.netedit_cmd_delay_desc))
                    }
                )

                OutlinedTextField(
                    value = serviceAuthCommand,
                    onValueChange = { serviceAuthCommand = it },
                    label = { Text(stringResource(R.string.netedit_cmd_auth)) },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(stringResource(R.string.netedit_cmd_auth_desc))
                    }
                )

                Text(
                    stringResource(R.string.netedit_cmd_extra),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = autoCommandsText,
                    onValueChange = { autoCommandsText = it },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(stringResource(R.string.netedit_cmd_extra_desc))
                    }
                )
            }

            CardSection(stringResource(R.string.netedit_section_encoding)) {
                ExposedDropdownMenuBox(
                    expanded = encodingExpanded,
                    onExpandedChange = { encodingExpanded = it }
                ) {
                    OutlinedTextField(
                        value = EncodingHelper.ENCODING_DISPLAY_NAMES[encoding] ?: encoding,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        label = { Text(stringResource(R.string.netedit_encoding)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = encodingExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = encodingExpanded,
                        onDismissRequest = { encodingExpanded = false }
                    ) {
                        EncodingHelper.ENCODING_DISPLAY_NAMES.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    encoding = key
                                    encodingExpanded = false
                                }
                            )
                        }
                    }
                }
                Text(
                    when (encoding) {
                        "auto" -> stringResource(R.string.netedit_enc_auto)
                        "windows-1251" -> stringResource(R.string.netedit_enc_cp1251)
                        "UTF-8" -> stringResource(R.string.netedit_enc_utf8)
                        else -> stringResource(R.string.netedit_enc_manual)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            CardSection(stringResource(R.string.netedit_section_ircv3)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.netedit_ircv3_advanced))
                    Switch(
                        checked = showAdvancedCaps,
                        onCheckedChange = { showAdvancedCaps = it }
                    )
                }

                AnimatedVisibility(visible = showAdvancedCaps) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CapSwitch("message-tags", capMessageTags) { capMessageTags = it }
                        CapSwitch("server-time", capServerTime) { capServerTime = it }
                        CapSwitch("echo-message", capEcho) { capEcho = it }
                        CapSwitch("labeled-response", capLabeled) { capLabeled = it }
                        CapSwitch("batch", capBatch) { capBatch = it }
                        CapSwitch("utf8only", capUtf8Only) { capUtf8Only = it }

                        HorizontalDivider(Modifier.padding(vertical = 8.dp))

                        CapSwitch("account-notify", capAccountNotify) { capAccountNotify = it }
                        CapSwitch("away-notify", capAwayNotify) { capAwayNotify = it }
                        CapSwitch("chghost", capChghost) { capChghost = it }
                        CapSwitch("extended-join", capExtendedJoin) { capExtendedJoin = it }
                        CapSwitch("invite-notify", capInviteNotify) { capInviteNotify = it }
                        CapSwitch("multi-prefix", capMultiPrefix) { capMultiPrefix = it }
                        CapSwitch("userhost-in-names", capUserhostInNames) { capUserhostInNames = it }
                        CapSwitch("setname", capSetname) { capSetname = it }

                        HorizontalDivider(Modifier.padding(vertical = 8.dp))

                        CapSwitch("draft/chathistory", capDraftHistory) { capDraftHistory = it }
                        CapSwitch("draft/event-playback", capDraftPlayback) { capDraftPlayback = it }
                        CapSwitch("draft/relaymsg", capDraftRelaymsg) { capDraftRelaymsg = it }
                        CapSwitch("draft/read-marker", capDraftReadMarker) { capDraftReadMarker = it }
                    }
                }

                if (!showAdvancedCaps) {
                    Text(
                        stringResource(R.string.netedit_ircv3_basic_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun CapSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CardSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
