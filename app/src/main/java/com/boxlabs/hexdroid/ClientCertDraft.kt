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

package com.boxlabs.hexdroid

import android.net.Uri

/**
 * A certificate/key selection made by the user (via SAF) that should be imported into encrypted storage.
 *
 * Internally the app stores a PKCS#12 blob, but the UI accepts common PEM inputs.
 */
enum class ClientCertFormat {
    /** One PEM file containing CERTIFICATE and PRIVATE KEY blocks (and optionally a chain). */
    PEM_BUNDLE,

    /** Separate certificate and private key files (e.g., client.crt + client.key). */
    CERT_AND_KEY,

    /** A PKCS#12 file (.p12/.pfx). Kept for compatibility/advanced use. */
    PKCS12
}

/**
 * @param uri For PEM_BUNDLE this is the .pem. For CERT_AND_KEY this is the certificate file. For PKCS12 this is the .p12/.pfx.
 * @param keyUri Only used for CERT_AND_KEY.
 * @param password Optional password for the private key / PKCS#12 (when applicable).
 */
data class ClientCertDraft(
    val format: ClientCertFormat,
    val uri: Uri,
    val keyUri: Uri? = null,
    val password: String? = null,
    val displayName: String? = null,
    val keyDisplayName: String? = null
)
