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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.IrcViewModel
import com.boxlabs.hexdroid.crypto.E2eScheme

/**
 * Per-target end-to-end encryption settings dialog.
 *
 * Three schemes:
 *   - +AGM (AES-256-GCM): HexDroid's symmetric default. One shared 256-bit key you
 *     generate and hand to the other party out of band.
 *   - +AGE: identity-based and forward-secret. No key to paste, each device has a
 *     pinned identity (trust-on-first-use), verified by comparing safety numbers.
 *   - Blowfish (FiSH): legacy interop with fishlim.
 *
 * AGM/Blowfish are "set a key" flows; +AGE is an "enable + verify identities" flow,
 * so its panel shows your safety number, the contact's pin/verify status, and a
 * toggle rather than a key field.
 */
@Composable
fun EncryptionDialog(
    networkId: String,
    target: String,
    viewModel: IrcViewModel,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current

    // All dialog state is keyed to (networkId, target) so reopening the dialog for a different
    // conversation always starts from that conversation's real state instead of a stale snapshot.
    var current by remember(networkId, target) { mutableStateOf(viewModel.getE2eKeyInfo(networkId, target)) }
    var ageInfo by remember(networkId, target) { mutableStateOf(viewModel.getAgeUiInfo(networkId, target)) }

    // Scheme the user has selected for the next action. Defaults to whatever is actually active for
    // sending: +AGE wins when it is on (the send path checks it first), otherwise the configured keyed
    // scheme, otherwise AGM. Landing on the active scheme's panel avoids the dialog opening on AGM while
    // +AGE is the thing really encrypting the conversation.
    var pickedScheme by remember(networkId, target) {
        mutableStateOf(if (ageInfo.enabled) E2eScheme.AGE else current?.scheme ?: E2eScheme.AGM)
    }

    var revealKey by remember(networkId, target) { mutableStateOf(false) }
    var importText by remember(networkId, target) { mutableStateOf("") }
    var importError by remember(networkId, target) { mutableStateOf<String?>(null) }
    var blowfishPassphrase by remember(networkId, target) { mutableStateOf("") }
    var pendingClear by remember(networkId, target) { mutableStateOf(false) }
    var pendingRegen by remember(networkId, target) { mutableStateOf(false) }

    fun resync() {
        current = viewModel.getE2eKeyInfo(networkId, target)
        ageInfo = viewModel.getAgeUiInfo(networkId, target)
        revealKey = false
        importText = ""
        importError = null
        blowfishPassphrase = ""
        pendingClear = false
        pendingRegen = false
    }

    // What is actually used for sending right now, for the title + header line. +AGE takes precedence
    // over a keyed scheme because the send path checks +AGE first, so report it first too rather than
    // letting a leftover AGM/Blowfish key claim to be active while +AGE is really doing the work.
    val activeLabel: String? = if (ageInfo.enabled) E2eScheme.AGE.displayName else current?.scheme?.displayName
    val anythingActive = activeLabel != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (anythingActive) Icons.Default.Lock else Icons.Default.LockOpen,
                     contentDescription = null,
                     tint = if (anythingActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text("Encryption", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                   modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text("for $target", style = MaterialTheme.typography.labelMedium)

                // ── Current state ─────────────────────────────────────────────────
                val cur = current
                if (!anythingActive) {
                    Text(
                        "Messages in this ${targetTypeLabel(target)} are sent and received in cleartext.",
                         style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Active: ", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            activeLabel ?: "",
                             style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    when {
                        // Both configured: +AGE is what the send path uses, so the keyed scheme's key is
                        // dormant. Say so plainly instead of showing that key's safety number as if it were
                        // the active one (the +AGE safety number lives in the +AGE panel below).
                        ageInfo.enabled && cur != null -> Text(
                            "+AGE is on and encrypts messages here. Your saved ${cur.scheme.displayName} " +
                                "key is kept but stays unused until you turn +AGE off.",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.tertiary,
                        )
                        // A keyed scheme is active (no +AGE): show its safety number to compare out of band.
                        cur != null -> {
                            Text("Safety number", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer {
                                Text(
                                    cur.fingerprint,
                                     style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                )
                            }
                            Text(
                                "Verify this matches what the other party sees.",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider()

                // ── Scheme picker ─────────────────────────────────────────────────
                Text("Cipher", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = pickedScheme == E2eScheme.AGM,
                        onClick = { pickedScheme = E2eScheme.AGM; importError = null },
                        label = { Text("AES-GCM") },
                               leadingIcon = if (pickedScheme == E2eScheme.AGM) {
                                   { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                               } else null,
                    )
                    FilterChip(
                        selected = pickedScheme == E2eScheme.AGE,
                        onClick = { pickedScheme = E2eScheme.AGE; importError = null },
                        label = { Text("+AGE") },
                               leadingIcon = if (pickedScheme == E2eScheme.AGE) {
                                   { Text("🛡") }
                               } else null,
                    )
                    FilterChip(
                        selected = pickedScheme == E2eScheme.BLOWFISH,
                        onClick = { pickedScheme = E2eScheme.BLOWFISH; importError = null },
                        label = { Text("FiSH") },
                    )
                }
                when (pickedScheme) {
                    E2eScheme.AGM -> Text(
                        "HexDroid's Authenticated Group Messaging.  One 256-bit random key, per-message authenticated encryption. You share the key.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    E2eScheme.AGE -> Text(
                        "Authenticated Group Exchange.  Keys are negotiated automatically and rotate forward. Forward-secrecy for PMs.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    E2eScheme.BLOWFISH -> Text(
                        "Only use FiSH when the other side can't speak +AGM or +AGE.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                HorizontalDivider()

                // ── Per-scheme controls ───────────────────────────────────────────
                when (pickedScheme) {
                    E2eScheme.AGM -> AgmControls(
                        ctx = ctx,
                        cur = if (cur?.scheme == E2eScheme.AGM) cur else null,
                                                 revealKey = revealKey,
                                                 onRevealKey = { revealKey = true },
                                                 importText = importText,
                                                 onImportTextChange = { importText = it; importError = null },
                                                 importError = importError,
                                                 onPasteFromClipboard = {
                                                     val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                     val pasted = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                                                     if (pasted.isNotBlank()) {
                                                         importText = pasted
                                                         importError = null
                                                     }
                                                 },
                                                 onImport = {
                                                     when (val r = viewModel.importE2eKey(networkId, target, importText)) {
                                                         is IrcViewModel.E2eImportResult.Success -> resync()
                                                         is IrcViewModel.E2eImportResult.Failure -> { importError = r.reason }
                                                     }
                                                 },
                    )
                    E2eScheme.AGE -> AgeControls(
                        ctx = ctx,
                        target = target,
                        info = ageInfo,
                        onEnable = { viewModel.enableAge(networkId, target); resync() },
                        onDisable = { viewModel.disableAge(networkId, target); resync() },
                        onVerify = { viewModel.markAgeContactVerified(networkId, target); resync() },
                    )
                    E2eScheme.BLOWFISH -> BlowfishControls(
                        ctx = ctx,
                        cur = if (cur?.scheme == E2eScheme.BLOWFISH) cur else null,
                                                           passphrase = blowfishPassphrase,
                                                           onPassphraseChange = { blowfishPassphrase = it; importError = null },
                                                           revealKey = revealKey,
                                                           onRevealKey = { revealKey = true },
                                                           importError = importError,
                                                           onPasteFromClipboard = {
                                                               val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                               val pasted = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                                                               if (pasted.isNotBlank()) {
                                                                   blowfishPassphrase = pasted
                                                                   importError = null
                                                               }
                                                           },
                                                           onSetPassphrase = {
                                                               when (val r = viewModel.setE2eBlowfishPassphrase(networkId, target, blowfishPassphrase)) {
                                                                   is IrcViewModel.E2eImportResult.Success -> resync()
                                                                   is IrcViewModel.E2eImportResult.Failure -> { importError = r.reason }
                                                               }
                                                           },
                    )
                }

                // ── Manage existing keyed scheme (regenerate / clear) ─────────────
                val curManage = current
                if (curManage != null && !pendingClear && !pendingRegen) {
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (curManage.scheme == E2eScheme.AGM) {
                            OutlinedButton(onClick = { pendingRegen = true }, modifier = Modifier.weight(1f)) {
                                Text("Regen")
                            }
                        }
                        OutlinedButton(
                            onClick = { pendingClear = true },
                            modifier = Modifier.weight(1f),
                        ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (pendingRegen) {
                    HorizontalDivider()
                    Text(
                        "Generate a new key? The current key stops working immediately and the other party must import the new one.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { pendingRegen = false }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                        Button(
                            onClick = {
                                val info = viewModel.generateE2eKey(networkId, target)
                                current = info
                                revealKey = true
                                pendingRegen = false
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Confirm") }
                    }
                }
                if (pendingClear) {
                    HorizontalDivider()
                    Text(
                        "Remove encryption for this ${targetTypeLabel(target)}? Messages will be sent and received in cleartext.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { pendingClear = false }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                        Button(
                            onClick = {
                                viewModel.clearE2eKeyForTarget(networkId, target)
                                resync()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Confirm") }
                    }
                }
            }
        },
        confirmButton = {
            // Only the AGM "first key" action lives in the button row.
            val cur = current
            if (cur == null && pickedScheme == E2eScheme.AGM) {
                Button(onClick = {
                    val info = viewModel.generateE2eKey(networkId, target)
                    current = info
                    revealKey = true
                }) { Text("Generate key") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                pendingClear = false
                pendingRegen = false
                onDismiss()
            }) { Text("Close") }
        },
    )
}

@Composable
private fun AgeControls(
    ctx: Context,
    target: String,
    info: IrcViewModel.AgeUiInfo,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onVerify: () -> Unit,
) {
    if (!info.available) {
        Text(
            "+AGE isn't available on this device.",
             style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.error,
        )
        return
    }

    // Your identity safety number.
    Text("Your +AGE safety number", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                info.myFingerprint,
                 style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                 modifier = Modifier.padding(8.dp),
            )
        }
    }
    OutlinedButton(
        onClick = { copyToClipboard(ctx, "+AGE safety number", info.myFingerprint) },
           modifier = Modifier.fillMaxWidth(),
    ) { Text("Copy safety number") }
    Text(
        "Share this so contacts can confirm they're really talking to you.",
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    HorizontalDivider()

    if (info.isChannel) {
        Text("This channel", style = MaterialTheme.typography.labelMedium)
        Text(
            "+AGE encrypts to a per-channel group key shared by invite; every member's messages are signed with their own identity, and the key is rotated when a member leaves.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text("This contact", style = MaterialTheme.typography.labelMedium)
        Text(
            "+AGE runs a short handshake, then seals each message with its own key (a double ratchet), so earlier messages stay protected even if a later key is exposed.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!info.peerKnown || info.peerFingerprint == null) {
            Text(
                "No +AGE identity seen for $target yet. It pins automatically the first time they advertise one (trust-on-first-use); then you can verify it here.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        info.peerFingerprint,
                         style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                         modifier = Modifier.padding(8.dp),
                    )
                }
            }
            if (info.peerVerified) {
                Text(
                    "Verified.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "Pinned but not verified. Compare this with what $target reads out, then confirm.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.tertiary,
                )
                OutlinedButton(onClick = onVerify, modifier = Modifier.fillMaxWidth()) { Text("Mark verified") }
            }
        }
    }

    HorizontalDivider()

    if (info.enabled) {
        Text(
            "+AGE is on for this ${targetTypeLabel(target)}.",
             style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.primary,
        )
        OutlinedButton(onClick = onDisable, modifier = Modifier.fillMaxWidth()) {
            Text("Turn off +AGE", color = MaterialTheme.colorScheme.error)
        }
    } else {
        Text(
            "Turn on identity-based (Ed25519) forward-secret (X25519/X3DH) encryption here. Keys are exchanged automatically once both sides advertise +AGE.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) { Text("Turn on +AGE") }
    }
}

@Composable
private fun AgmControls(
    ctx: Context,
    cur: IrcViewModel.E2eKeyInfo?,
    revealKey: Boolean,
    onRevealKey: () -> Unit,
                        importText: String,
                        onImportTextChange: (String) -> Unit,
                        importError: String?,
                        onPasteFromClipboard: () -> Unit,
                        onImport: () -> Unit,
) {
    if (cur != null) {
        if (!revealKey) {
            OutlinedButton(onClick = onRevealKey, modifier = Modifier.fillMaxWidth()) {
                Text("Reveal key to share")
            }
        } else {
            Text("Key (32 bytes, base64)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        cur.keyB64,
                         style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                         modifier = Modifier.padding(8.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { copyToClipboard(ctx, "HexDroid encryption key", cur.keyB64) },
                               modifier = Modifier.weight(1f),
                ) { Text("Copy") }
                OutlinedButton(
                    onClick = { shareAgmKey(ctx, cur.keyB64, cur.fingerprint) },
                               modifier = Modifier.weight(1f),
                ) { Text("Share") }
            }
            Text(
                "Anyone with this string can decrypt messages for this target.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error,
            )
        }
        HorizontalDivider()
    }

    Text("Paste a key from another user", style = MaterialTheme.typography.labelMedium)
    OutlinedTextField(
        value = importText,
        onValueChange = onImportTextChange,
        placeholder = { Text("Base64-enc 32-byte key") },
                      isError = importError != null,
                      singleLine = false,
                      modifier = Modifier.fillMaxWidth(),
    )
    if (importError != null) {
        Text(importError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onPasteFromClipboard, modifier = Modifier.weight(1f)) { Text("Paste") }
        Button(onClick = onImport, enabled = importText.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Import") }
    }
}

@Composable
private fun BlowfishControls(
    ctx: Context,
    cur: IrcViewModel.E2eKeyInfo?,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
                             revealKey: Boolean,
                             onRevealKey: () -> Unit,
                             importError: String?,
                             onPasteFromClipboard: () -> Unit,
                             onSetPassphrase: () -> Unit,
) {
    if (cur != null) {
        if (!revealKey) {
            OutlinedButton(onClick = onRevealKey, modifier = Modifier.fillMaxWidth()) {
                Text("Reveal passphrase")
            }
        } else {
            val decoded = remember(cur.keyB64) {
                try {
                    String(android.util.Base64.decode(cur.keyB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: Throwable) { "" }
            }
            Text("Passphrase", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        decoded,
                         style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                         modifier = Modifier.padding(8.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { copyToClipboard(ctx, "Blowfish passphrase", decoded) },
                               modifier = Modifier.weight(1f),
                ) { Text("Copy") }
            }
        }
        HorizontalDivider()
    }

    Text(
        "Type the same passphrase your FiSH contact will use.",
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = passphrase,
        onValueChange = onPassphraseChange,
        placeholder = { Text("Passphrase (4-56 chars)…") },
                      isError = importError != null,
                      singleLine = true,
                      modifier = Modifier.fillMaxWidth(),
    )
    if (importError != null) {
        Text(importError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    } else if (passphrase.isNotEmpty() && passphrase.length < 8) {
        Text(
            "Short passphrases (<8 chars) are easy to brute-force. Use at least 12.",
             color = MaterialTheme.colorScheme.tertiary,
             style = MaterialTheme.typography.bodySmall,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onPasteFromClipboard, modifier = Modifier.weight(1f)) { Text("Paste") }
        Button(
            onClick = onSetPassphrase,
            enabled = passphrase.isNotBlank(),
               modifier = Modifier.weight(1f),
        ) { Text(if (cur == null) "Set" else "Replace") }
    }
}

/** Returns "channel" or "query" based on whether [target] looks like a channel name. */
private fun targetTypeLabel(target: String): String =
    if (target.firstOrNull() in setOf('#', '&', '+', '!')) "channel" else "query"

private fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun shareAgmKey(ctx: Context, keyB64: String, fingerprint: String) {
    val body = buildString {
        appendLine("HexDroid end-to-end encryption key")
        appendLine()
        appendLine("Scheme: AES-256-GCM (+AGM)")
        appendLine("Safety number: $fingerprint")
        appendLine()
        appendLine("Key (base64):")
        appendLine(keyB64)
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, body)
        putExtra(Intent.EXTRA_SUBJECT, "HexDroid encryption key")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(Intent.createChooser(intent, "Share encryption key").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
