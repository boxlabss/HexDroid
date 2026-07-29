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
import androidx.compose.ui.res.stringResource
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
import com.boxlabs.hexdroid.R

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
                Text(stringResource(R.string.enc_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                   modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.enc_for_target, target), style = MaterialTheme.typography.labelMedium)

                // ── Current state ─────────────────────────────────────────────────
                val cur = current
                if (!anythingActive) {
                    Text(
                        stringResource(R.string.enc_cleartext_desc, targetKind(target)),
                         style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.enc_active), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            activeLabel,
                             style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    when {
                        // Both configured: +AGE is what the send path uses, so the keyed scheme's key is
                        // dormant. Say so plainly instead of showing that key's safety number as if it were
                        // the active one (the +AGE safety number lives in the +AGE panel below).
                        ageInfo.enabled && cur != null -> Text(
                            stringResource(R.string.enc_age_dormant_key, cur.scheme.displayName),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.tertiary,
                        )
                        // A keyed scheme is active (no +AGE): show its safety number to compare out of band.
                        cur != null -> {
                            Text(stringResource(R.string.enc_safety_number), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer {
                                Text(
                                    cur.fingerprint,
                                     style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                )
                            }
                            Text(
                                stringResource(R.string.enc_verify_match),
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider()

                // ── Scheme picker ─────────────────────────────────────────────────
                Text(stringResource(R.string.enc_cipher), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        modifier = Modifier.focusHighlight(),
                        selected = pickedScheme == E2eScheme.AGM,
                        onClick = { pickedScheme = E2eScheme.AGM; importError = null },
                        label = { Text("AES-GCM") },
                               leadingIcon = if (pickedScheme == E2eScheme.AGM) {
                                   { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                               } else null,
                    )
                    FilterChip(
                        modifier = Modifier.focusHighlight(),
                        selected = pickedScheme == E2eScheme.AGE,
                        onClick = { pickedScheme = E2eScheme.AGE; importError = null },
                        label = { Text("+AGE") },
                               leadingIcon = if (pickedScheme == E2eScheme.AGE) {
                                   { Text("🛡") }
                               } else null,
                    )
                    FilterChip(
                        modifier = Modifier.focusHighlight(),
                        selected = pickedScheme == E2eScheme.BLOWFISH,
                        onClick = { pickedScheme = E2eScheme.BLOWFISH; importError = null },
                        label = { Text("FiSH") },
                    )
                }
                when (pickedScheme) {
                    E2eScheme.AGM -> Text(
                        stringResource(R.string.enc_agm_desc),
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    E2eScheme.AGE -> Text(
                        stringResource(R.string.enc_age_desc),
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    E2eScheme.BLOWFISH -> Text(
                        stringResource(R.string.enc_fish_desc),
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
                            OutlinedButton(onClick = { pendingRegen = true }, modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50))) {
                                Text(stringResource(R.string.enc_regen))
                            }
                        }
                        OutlinedButton(
                            onClick = { pendingClear = true },
                            modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50)),
                        ) { Text(stringResource(R.string.clear), color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (pendingRegen) {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.enc_regen_confirm),
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { pendingRegen = false }, modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.cancel)) }
                        Button(
                            onClick = {
                                val info = viewModel.generateE2eKey(networkId, target)
                                current = info
                                revealKey = true
                                pendingRegen = false
                            },
                            modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50)),
                        ) { Text(stringResource(R.string.confirm)) }
                    }
                }
                if (pendingClear) {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.enc_clear_confirm, targetKind(target)),
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { pendingClear = false }, modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.cancel)) }
                        Button(
                            onClick = {
                                viewModel.clearE2eKeyForTarget(networkId, target)
                                resync()
                            },
                            modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50)),
                        ) { Text(stringResource(R.string.confirm)) }
                    }
                }
            }
        },
        confirmButton = {
            // Only the AGM "first key" action lives in the button row.
            val cur = current
            if (cur == null && pickedScheme == E2eScheme.AGM) {
                Button(modifier = Modifier.focusHighlight(RoundedCornerShape(50)), onClick = {
                    val info = viewModel.generateE2eKey(networkId, target)
                    current = info
                    revealKey = true
                }) { Text(stringResource(R.string.enc_generate_key)) }
            }
        },
        dismissButton = {
            TextButton(modifier = Modifier.focusHighlight(RoundedCornerShape(50)), onClick = {
                pendingClear = false
                pendingRegen = false
                onDismiss()
            }) { Text(stringResource(R.string.close)) }
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
    Text(stringResource(R.string.enc_age_safety_number), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
           modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(50)),
    ) { Text(stringResource(R.string.enc_copy_safety_number)) }
    Text(
        stringResource(R.string.enc_share_safety_desc),
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    HorizontalDivider()

    if (info.isChannel) {
        Text(stringResource(R.string.enc_this_channel), style = MaterialTheme.typography.labelMedium)
        Text(
            "+AGE encrypts to a per-channel group key shared by invite; every member's messages are signed with their own identity, and the key is rotated when a member leaves.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(stringResource(R.string.enc_this_contact), style = MaterialTheme.typography.labelMedium)
        Text(
            "+AGE runs a short handshake, then seals each message with its own key (a double ratchet), so earlier messages stay protected even if a later key is exposed.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!info.peerKnown || info.peerFingerprint == null) {
            Text(
                stringResource(R.string.enc_no_identity, target),
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
                    stringResource(R.string.enc_verified),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    stringResource(R.string.enc_pinned_unverified, target),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.tertiary,
                )
                OutlinedButton(onClick = onVerify, modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.enc_mark_verified)) }
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
        OutlinedButton(onClick = onDisable, modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(50))) {
            Text(stringResource(R.string.enc_turn_off_age), color = MaterialTheme.colorScheme.error)
        }
    } else {
        Text(
            stringResource(R.string.enc_age_enable_desc),
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onEnable, modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.enc_turn_on_age)) }
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
            OutlinedButton(onClick = onRevealKey, modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(50))) {
                Text(stringResource(R.string.enc_reveal_key))
            }
        } else {
            Text(stringResource(R.string.enc_key_bytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                               modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.copy)) }
                OutlinedButton(
                    onClick = { shareAgmKey(ctx, cur.keyB64, cur.fingerprint) },
                               modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.share)) }
            }
            Text(
                stringResource(R.string.enc_key_leak_warn),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error,
            )
        }
        HorizontalDivider()
    }

    Text(stringResource(R.string.enc_paste_key), style = MaterialTheme.typography.labelMedium)
    OutlinedTextField(
        value = importText,
        onValueChange = onImportTextChange,
        placeholder = { Text(stringResource(R.string.enc_key_placeholder)) },
                      isError = importError != null,
                      singleLine = false,
                      modifier = Modifier.fillMaxWidth(),
    )
    if (importError != null) {
        Text(importError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onPasteFromClipboard, modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.paste)) }
        Button(onClick = onImport, enabled = importText.isNotBlank(), modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.action_import)) }
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
            OutlinedButton(onClick = onRevealKey, modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(50))) {
                Text(stringResource(R.string.enc_reveal_passphrase))
            }
        } else {
            val decoded = remember(cur.keyB64) {
                try {
                    String(android.util.Base64.decode(cur.keyB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: Throwable) { "" }
            }
            Text(stringResource(R.string.enc_passphrase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                               modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.copy)) }
            }
        }
        HorizontalDivider()
    }

    Text(
        stringResource(R.string.enc_passphrase_desc),
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = passphrase,
        onValueChange = onPassphraseChange,
        placeholder = { Text(stringResource(R.string.enc_passphrase_placeholder)) },
                      isError = importError != null,
                      singleLine = true,
                      modifier = Modifier.fillMaxWidth(),
    )
    if (importError != null) {
        Text(importError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    } else if (passphrase.isNotEmpty() && passphrase.length < 8) {
        Text(
            stringResource(R.string.enc_passphrase_weak),
             color = MaterialTheme.colorScheme.tertiary,
             style = MaterialTheme.typography.bodySmall,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onPasteFromClipboard, modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.paste)) }
        Button(
            onClick = onSetPassphrase,
            enabled = passphrase.isNotBlank(),
               modifier = Modifier.weight(1f).focusHighlight(RoundedCornerShape(50)),
        ) { Text(if (cur == null) stringResource(R.string.set) else stringResource(R.string.replace)) }
    }
}

/** Localized "channel"/"query" word for [target], for use in composable text. */
@androidx.compose.runtime.Composable
private fun targetKind(target: String): String =
    stringResource(if (target.firstOrNull() in setOf('#', '&', '+', '!')) R.string.enc_kind_channel else R.string.enc_kind_query)

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
