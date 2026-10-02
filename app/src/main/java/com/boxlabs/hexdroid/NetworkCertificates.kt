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

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

/**
 * Per-network TLS client certificates held in Android Keystore. Each network gets its own key
 * pair and self-signed certificate, so the fingerprint stays the same across nicks and IPs on that
 * network but differs between networks, and networks can't link a user across them. The private
 * key never leaves the Keystore and isn't included in backups, so a reinstall makes a new one.
 */
object NetworkCertificates {
    private const val ALIAS_PREFIX = "hexdroid_netcert_"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun aliasFor(networkId: String): String = ALIAS_PREFIX + networkId

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /**
     * Create the key pair and certificate for [alias] unless they exist; true when one was made.
     * The Keystore makes the certificate itself, signed with the new key. Slow (hardware-backed):
     * never on the main thread.
     */
    @Synchronized
    fun ensure(alias: String): Boolean {
        if (keystore().containsAlias(alias)) return false
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // NONE lets TLS sign a digest it computed itself, as TLS 1.2 client auth can.
            .setDigests(
                KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512,
            )
            // Nothing identifying in the subject: the fingerprint is the identity.
            .setCertificateSubject(X500Principal("CN=IRC client"))
            .setCertificateSerialNumber(BigInteger(63, SecureRandom()).add(BigInteger.ONE))
            .setCertificateNotBefore(Date(now - DAY_MS))
            .setCertificateNotAfter(Date(now + 30L * 365 * DAY_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(spec) }
            .generateKeyPair()
        return true
    }

    /**
     * The SHA-256 fingerprint of [alias]'s certificate as lowercase hex, the form servers show in
     * WHOIS and services take in CERT ADD; null when there is none.
     */
    fun fingerprint(alias: String): String? = runCatching {
        val cert = keystore().getCertificate(alias) ?: return null
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }
    }.getOrNull()

    fun delete(alias: String) {
        runCatching { keystore().deleteEntry(alias) }
    }
}

/** Presents the Keystore certificate under [alias] for TLS client authentication. */
internal class KeystoreKeyManager(private val alias: String) : X509KeyManager {
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    // The key is EC, so only offer it when the server accepts an EC certificate.
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (keyType == null || keyType.any { it.startsWith("EC", ignoreCase = true) }) alias else null

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(alias)

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        (ks.getCertificate(this.alias) as? X509Certificate)?.let { arrayOf(it) }

    override fun getPrivateKey(alias: String?): PrivateKey? = ks.getKey(this.alias, null) as? PrivateKey
}
