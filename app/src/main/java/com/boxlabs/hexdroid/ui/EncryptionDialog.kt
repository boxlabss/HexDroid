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
 * Lets users:
 *   - Pick the cipher scheme (AES-GCM = HexDroid's +AGM', Blowfish =
 *     legacy FiSH)
 *   - Generate or import a key for that scheme
 *   - View the safety number (fingerprint) for out-of-band verification
 *   - Share / copy the key bytes
 *   - Clear the key
 *
 * Two-step "reveal key" gate stays the default-hidden state for sensitive bytes.
 *
 * Scheme switching: when the user changes the toggle, any existing key for the
 * previous scheme stays in the keystore (the toggle is "what would you set", not
 * "what's currently active"). Only when the user actually generates or imports
 * does the active scheme change. This avoids accidental clobbering when someone
 * is just inspecting their config.
 */
@Composable
fun EncryptionDialog(
    networkId: String,
    target: String,
    viewModel: IrcViewModel,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current

    // Current state of the key for this target. Re-read on each viewModel
    // mutation via the state.e2eKeyVersion-bumped invalidation; the LaunchedEffect
    // below resyncs after generate/import/clear.
    var current by remember { mutableStateOf(viewModel.getE2eKeyInfo(networkId, target)) }

    // Scheme the user has selected for the next action. Defaults to the active
    // scheme if one is configured, otherwise AGM (the recommended default).
    var pickedScheme by remember { mutableStateOf(current?.scheme ?: E2eScheme.AGM) }

    var revealKey by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var blowfishPassphrase by remember { mutableStateOf("") }
    var pendingClear by remember { mutableStateOf(false) }
    var pendingRegen by remember { mutableStateOf(false) }

    // If an action mutates the keystore through the ViewModel, re-pull the
    // current snapshot so the dialog reflects it immediately without waiting
    // for the next compose pass to read state.e2eKeyVersion.
    fun resync() {
        current = viewModel.getE2eKeyInfo(networkId, target)
        revealKey = false
        importText = ""
        importError = null
        blowfishPassphrase = ""
        pendingClear = false
        pendingRegen = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (current != null) Icons.Default.Lock else Icons.Default.LockOpen,
                     contentDescription = null,
                     tint = if (current != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text("Encryption", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                   // Without this, the AlertDialog's text slot clips its content on
                   // short screens (and grows past the buttons after an import/generate
                   // reveals the share section). Scrolling keeps every control reachable.
                   modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text("for $target", style = MaterialTheme.typography.labelMedium)

                // ── Current state ─────────────────────────────────────────────────
                val cur = current
                if (cur == null) {
                    Text(
                        "Messages in this ${targetTypeLabel(target)} are sent and received in cleartext.",
                         style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Active: ", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            cur.scheme.displayName,
                             style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.primary,
                        )
                    }
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

                HorizontalDivider()

                // ── Scheme picker ─────────────────────────────────────────────────
                Text("Cipher", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = pickedScheme == E2eScheme.AGM,
                        onClick = { pickedScheme = E2eScheme.AGM; importError = null },
                        label = { Text("AES-256-GCM") },
                               leadingIcon = if (pickedScheme == E2eScheme.AGM) {
                                   { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                               } else null,
                    )
                    FilterChip(
                        selected = pickedScheme == E2eScheme.BLOWFISH,
                        onClick = { pickedScheme = E2eScheme.BLOWFISH; importError = null },
                        label = { Text("Blowfish") },
                    )
                }
                when (pickedScheme) {
                    E2eScheme.AGM -> {
                        Text(
                            "HexDroid's +AGM'. 256-bit random key, per-message authenticated encryption.",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    E2eScheme.BLOWFISH -> {
                        Text(
                            "Only use FiSH when the other side can't speak +AGM.",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
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
                                                         is IrcViewModel.E2eImportResult.Success -> {
                                                             resync()
                                                             // resync() re-reads from the ViewModel so current
                                                             // is already the new key info; nothing else to do.
                                                         }
                                                         is IrcViewModel.E2eImportResult.Failure -> { importError = r.reason }
                                                     }
                                                 },
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

                // ── Manage existing key (regenerate / clear) ──────────────────────
                // These live inside the scrollable body rather than in the dialog's
                // button row. The AlertDialog button row can only lay out a single
                // confirm + dismiss before it overflows on narrow screens; packing
                // "Regenerate" + "Clear" + "Close" there clipped the labels. Keeping
                // them here lets them be full-width, wrap cleanly, and stay reachable.
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
            // Only the "first key" action lives in the dialog button row, so the row
            // never holds more than this one confirm plus the Close dismiss. All
            // management actions (regenerate / clear) are inline in the body above.
            val cur = current
            if (cur == null && pickedScheme == E2eScheme.AGM) {
                Button(onClick = {
                    val info = viewModel.generateE2eKey(networkId, target)
                    current = info
                    revealKey = true
                }) { Text("Generate key") }
            }
            // Blowfish has no "Generate" affordance: its keys are user-typed
            // passphrases set via the inline "Set" button in BlowfishControls.
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
        // Reveal-and-share section (only relevant when an AGM key is active).
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

    // Import (paste a key from the other party).
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
        // Show the active passphrase reveal section. For Blowfish the stored
        // bytes ARE the passphrase, so a "reveal" shows the original string the
        // user typed (after a base64 round-trip through E2eKeyInfo, which is
        // why we decode it here).
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
        "Type the same passphrase your HexChat/fishlim contact will use.",
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

/**
 * Returns "channel" or "query" based on whether [target] looks like a channel name.
 */
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
