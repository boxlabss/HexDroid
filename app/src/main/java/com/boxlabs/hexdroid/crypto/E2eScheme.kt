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

package com.boxlabs.hexdroid.crypto

/**
 * End-to-end encryption schemes supported by HexDroid.
 *
 * Each scheme has a 3-letter wire prefix that follows the `+` sigil already used by
 * FiSH (`+OK`). Receivers dispatch by prefix so multiple schemes can coexist in a
 * channel during migration.
 *
 *   +OK  <base64>     FiSH Blowfish-CBC.    Legacy. Read-only by default.
 *   +AGM <base64>     HexDroid AES-256-GCM. Modern. PSK-based. v1 of this scheme.
 *   +AGE: identity/forward-secret group scheme. Appended LAST so the AGM/BLOWFISH
 *   ordinals (used in E2eFingerprint) never shift. Recognised here (detect/fromName,
 *   wire prefix, lock icon) but the message path is AgeChannel/AgeWire, not the 1:1
 *   E2eCodec cipher route, see cipherFor.
 */
enum class E2eScheme(val wirePrefix: String, val displayName: String) {
    AGM("+AGM", "AES-256-GCM"),
    BLOWFISH("+OK", "FiSH"),
    AGE("+AGE", "Authenticated Group Exchange");

    companion object {
        /** Returns the scheme whose wire prefix matches the start of [text], or null. */
        fun detect(text: String): E2eScheme? = entries.firstOrNull { text.startsWith("${it.wirePrefix} ") }

        fun fromName(name: String?): E2eScheme? = when (name?.uppercase()) {
            "AGM"      -> AGM
            "BLOWFISH",
            "FISH",
            "OK"       -> BLOWFISH
            "AGE"      -> AGE
            else       -> null
        }
    }
}
