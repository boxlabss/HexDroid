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

import com.boxlabs.hexdroid.data.SecretStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-(network, target) keystore for end-to-end encryption keys.
 *
 * Stores raw key bytes plus the scheme that applies to that target. Keys live in
 * [SecretStore] (Android-Keystore-wrapped AES-GCM, same envelope used for SASL
 * passwords), and a fast in-memory map mirrors them so encrypt/decrypt on the IRC
 * hot path doesn't touch disk per message.
 *
 * Target casefolding: IRC channel names are ASCII-case-insensitive but with the
 * "RFC1459 plus" rule that `{|}^` map to `[]\~`. We use a simple lowercase() here
 * which is correct for the common ASCII range and matches the casefolding the rest
 * of HexDroid uses for its own internal maps (chanNickCase etc.). The wire format's
 * AAD uses the same lowercased target so the byte sequence matches across clients
 * regardless of which casing the user typed.
 */
class E2eKeyStore(private val secretStore: SecretStore) {

    data class Entry(val scheme: E2eScheme, val key: ByteArray) {
        // Custom equals/hashCode because of the ByteArray field. Two entries with
        // different key bytes must compare unequal even if their schemes match,
        // and the hashCode must mix the key bytes for set/map containment.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Entry) return false
            return scheme == other.scheme && key.contentEquals(other.key)
        }

        override fun hashCode(): Int = 31 * scheme.hashCode() + key.contentHashCode()
    }

    private val cache = ConcurrentHashMap<String, Entry>()
    @Volatile private var hydratedNetworks: Set<String> = emptySet()

    private fun cacheKey(networkId: String, target: String): String =
        "$networkId\u0000${target.lowercase(java.util.Locale.ROOT)}"

    /**
     * Look up the key for ([networkId], [target]). Returns null if no key is set.
     * Lazily hydrates from SecretStore on first network access so apps that never
     * use E2E pay zero startup cost.
     */
    fun get(networkId: String, target: String): Entry? {
        hydrateIfNeeded(networkId)
        return cache[cacheKey(networkId, target)]
    }

    fun set(networkId: String, target: String, entry: Entry) {
        hydrateIfNeeded(networkId)
        cache[cacheKey(networkId, target)] = entry
        secretStore.setE2eKey(networkId, target.lowercase(java.util.Locale.ROOT), entry.scheme.name, entry.key)
    }

    fun clear(networkId: String, target: String) {
        hydrateIfNeeded(networkId)
        cache.remove(cacheKey(networkId, target))
        secretStore.clearE2eKey(networkId, target.lowercase(java.util.Locale.ROOT))
    }

    /** All keys configured for [networkId], for the management UI. */
    fun listForNetwork(networkId: String): List<Pair<String, Entry>> {
        hydrateIfNeeded(networkId)
        val prefix = "$networkId\u0000"
        return cache.entries
            .filter { it.key.startsWith(prefix) }
            .map { it.key.removePrefix(prefix) to it.value }
            .sortedBy { it.first }
    }

    /** Drop all in-memory state for [networkId]; called from cleanupNetworkMaps. */
    fun forgetNetwork(networkId: String) {
        val prefix = "$networkId\u0000"
        cache.keys.removeAll { it.startsWith(prefix) }
        hydratedNetworks = hydratedNetworks - networkId
    }

    private fun hydrateIfNeeded(networkId: String) {
        if (networkId in hydratedNetworks) return
        synchronized(this) {
            if (networkId in hydratedNetworks) return
            // First read for this network in this process: load every stored key.
            // listE2eKeys is a single SharedPreferences scan, so even for a user
            // with many configured targets this is sub-millisecond and only runs
            // once per network per process lifetime.
            for (stored in secretStore.listE2eKeys(networkId)) {
                val scheme = E2eScheme.fromName(stored.schemeName) ?: continue
                cache[cacheKey(networkId, stored.target)] = Entry(scheme, stored.keyBytes)
            }
            hydratedNetworks = hydratedNetworks + networkId
        }
    }
}
