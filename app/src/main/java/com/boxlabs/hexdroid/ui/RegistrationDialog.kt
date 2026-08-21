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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxlabs.hexdroid.IrcViewModel
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.RegPhase

/**
 * Guided draft/account-registration dialog.
 *
 * Drives its UI from NetConnState.regState, which the ViewModel updates from the
 * server's REGISTER/VERIFY responses (and FAIL replies). The form adapts to the
 * cap-value flags: an account field appears only when the server allows a custom
 * account name, and email is marked required when the server demands it.
 *
 * On success the user may opt in to saving the password so SASL logs them in
 * automatically next connect.
 */
@Composable
fun RegistrationDialog(
    networkId: String,
    viewModel: IrcViewModel,
    onDismiss: () -> Unit,
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val reg = ui.connections[networkId]?.regState

    // Start each session from a clean slate so a prior attempt's SUCCESS/FAIL doesn't
    // pre-empt this one.
    LaunchedEffect(networkId) { viewModel.clearRegState(networkId) }

    val flags = remember(networkId) { viewModel.accountRegFlags(networkId) }
    val emailRequired = "email-required" in flags
    val customName = "custom-account-name" in flags

    var account by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var savePassword by remember { mutableStateOf(false) }

    val phase = reg?.phase ?: RegPhase.IDLE

    val passwordsMatch = password.isNotEmpty() && password == confirm
    val emailOk = !emailRequired || email.isNotBlank()
    val canRegister = passwordsMatch && emailOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.register_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (phase) {
                    RegPhase.SUCCESS -> {
                        val yourAccount = stringResource(R.string.register_your_account)
                        val acctLabel = reg?.account ?: account.ifBlank { yourAccount }
                        Text(
                            stringResource(R.string.register_success, acctLabel),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        reg?.message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (password.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Checkbox(
                                    checked = savePassword,
                                    onCheckedChange = { savePassword = it },
                                    modifier = Modifier.focusHighlight(RoundedCornerShape(4.dp)),
                                )
                                Text(
                                    stringResource(R.string.register_save_password),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    RegPhase.VERIFY_REQUIRED -> {
                        Text(
                            reg?.message ?: stringResource(R.string.register_verify_prompt),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            label = { Text(stringResource(R.string.register_code)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().tvInitialFocus(),
                        )
                    }

                    else -> {
                        if (phase == RegPhase.FAILED) {
                            Text(
                                reg?.message ?: stringResource(R.string.register_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            stringResource(R.string.register_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (customName) {
                            OutlinedTextField(
                                value = account,
                                onValueChange = { account = it },
                                label = { Text(stringResource(R.string.register_account_optional)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = {
                                Text(stringResource(
                                    if (emailRequired) R.string.register_email_required
                                    else R.string.register_email_optional
                                ))
                            },
                            singleLine = true,
                            isError = emailRequired && email.isBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.register_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = confirm,
                            onValueChange = { confirm = it },
                            label = { Text(stringResource(R.string.register_password_confirm)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            isError = confirm.isNotEmpty() && confirm != password,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (phase) {
                RegPhase.SUCCESS -> {
                    TextButton(
                        modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                        onClick = {
                            if (savePassword && password.isNotEmpty()) {
                                val acct = (reg?.account ?: account).trim()
                                viewModel.saveSaslCredentialsAfterRegister(networkId, acct, password.trim())
                            }
                            viewModel.clearRegState(networkId)
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.done)) }
                }

                RegPhase.VERIFY_REQUIRED -> {
                    TextButton(
                        enabled = code.isNotBlank(),
                        modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                        onClick = {
                            viewModel.verifyAccount(networkId, reg?.account ?: account, code.trim())
                        },
                    ) { Text(stringResource(R.string.register_verify_action)) }
                }

                else -> {
                    TextButton(
                        enabled = canRegister,
                        modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                        onClick = {
                            viewModel.registerAccount(
                                networkId,
                                if (customName) account.trim() else "",
                                email.trim(),
                                password.trim(),
                            )
                        },
                    ) { Text(stringResource(R.string.register_action)) }
                }
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                onClick = {
                    viewModel.clearRegState(networkId)
                    onDismiss()
                },
            ) {
                Text(stringResource(
                    if (phase == RegPhase.SUCCESS) R.string.close else R.string.cancel
                ))
            }
        },
    )
}
