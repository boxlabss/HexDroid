package com.boxlabs.hexdroid.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.boxlabs.hexdroid.ClientCertDraft
import com.boxlabs.hexdroid.ClientCertFormat
import com.boxlabs.hexdroid.TlsClientCert
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.EncryptedPrivateKeyInfo
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException

/**
 * Stores SASL passwords, TLS client certificate password and encrypted PKCS#12 blob
 * outside of SettingsRepository's JSON so they are not persisted in plaintext.
 *
 * This implementation uses an AES-GCM key stored in Android Keystore.
 */
class SecretStore(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("hexdroid_secrets", Context.MODE_PRIVATE)
    private val rng = SecureRandom()

    private val keyAlias = "hexdroid_secrets_aesgcm_v1"

    private fun deleteKey() {
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(keyAlias)) ks.deleteEntry(keyAlias)
        }
    }

    private fun createKey(): SecretKey {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        kg.init(spec)
        return kg.generateKey()
    }

    private fun isKeyPermanentlyInvalidated(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            if (e is KeyPermanentlyInvalidatedException) return true
            e = e.cause
        }
        return false
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = runCatching { ks.getKey(keyAlias, null) }.getOrNull()
        if (existing is SecretKey) return existing
        return createKey()
    }

    private fun encryptToB64(plain: ByteArray): String {
        repeat(2) { attempt ->
            val key = getOrCreateKey()
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val iv = cipher.iv
                val ct = cipher.doFinal(plain)
                val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
                val ctB64 = Base64.encodeToString(ct, Base64.NO_WRAP)
                return "$ivB64:$ctB64"
            } catch (t: Throwable) {
                // Keystore keys can be invalidated (e.g., lock screen changes). If that happens,
                // regenerate the key and let callers re-enter secrets.
                if (attempt == 0 && (t is InvalidKeyException || isKeyPermanentlyInvalidated(t))) {
                    deleteKey()
                    return@repeat
                }
                throw t
            }
        }
        // Should be unreachable; the loop either returns or throws.
        throw IllegalStateException("Unable to encrypt secret")
    }

    private fun decryptFromB64(enc: String): ByteArray? {
        val parts = enc.split(":", limit = 2)
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)

        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            cipher.doFinal(ct)
        } catch (t: Throwable) {
            // If the key was invalidated, drop it so subsequent stores regenerate cleanly.
            if (t is InvalidKeyException || isKeyPermanentlyInvalidated(t)) {
                deleteKey()
            }
            null
        }
    }

    fun getSaslPassword(networkId: String): String? {
        val enc = prefs.getString("sasl:$networkId", null) ?: return null
        val bytes = decryptFromB64(enc) ?: run {
            // Ciphertext without a usable key (restores / invalidation) would otherwise fail forever.
            clearSaslPassword(networkId)
            return null
        }
        return bytes.toString(Charsets.UTF_8)
    }

    fun setSaslPassword(networkId: String, password: String) {
        prefs.edit().putString("sasl:$networkId", encryptToB64(password.toByteArray(Charsets.UTF_8))).apply()
    }

    fun clearSaslPassword(networkId: String) {
        prefs.edit().remove("sasl:$networkId").apply()
    }

    fun getServerPassword(networkId: String): String? {
        val enc = prefs.getString("pass:$networkId", null) ?: return null
        val bytes = decryptFromB64(enc) ?: run {
            clearServerPassword(networkId)
            return null
        }
        return bytes.toString(Charsets.UTF_8)
    }

    fun setServerPassword(networkId: String, password: String) {
        prefs.edit().putString("pass:$networkId", encryptToB64(password.toByteArray(Charsets.UTF_8))).apply()
    }

    fun clearServerPassword(networkId: String) {
        prefs.edit().remove("pass:$networkId").apply()
    }


    /**
     * Import a client certificate/key selection from the Network Edit screen.
     *
     * Internally this stores an encrypted PKCS#12 blob plus (optionally) a password.
     */
    fun importClientCert(networkId: String, draft: ClientCertDraft): StoredClientCert {
        val label = draft.displayName?.takeIf { it.isNotBlank() }

        val (pkcs12Bytes, storePassword, effectiveLabel) = when (draft.format) {
            ClientCertFormat.PKCS12 -> {
                val bytes = readBytesFromUri(draft.uri)
                Triple(bytes, draft.password, label)
            }
            ClientCertFormat.PEM_BUNDLE -> {
                val pem = readBytesFromUri(draft.uri)
                val pkcs12 = buildPkcs12FromPem(
                    certBytes = pem,
                    keyBytes = pem,
                    keyPassword = draft.password
                )
                // We always store our generated PKCS#12 with an empty password for compatibility.
                Triple(pkcs12, "", label ?: "Client certificate")
            }
            ClientCertFormat.CERT_AND_KEY -> {
                val cert = readBytesFromUri(draft.uri)
                val keyUri = draft.keyUri ?: throw IllegalArgumentException("Please select both a certificate and a key")
                val key = readBytesFromUri(keyUri)
                val pkcs12 = buildPkcs12FromPem(
                    certBytes = cert,
                    keyBytes = key,
                    keyPassword = draft.password
                )
                Triple(pkcs12, "", label ?: "Client certificate")
            }
        }

        val certId = "p12_" + java.util.UUID.randomUUID().toString()
        val dir = File(ctx.filesDir, "client_certs").apply { mkdirs() }
        val outFile = File(dir, "$certId.bin")
        outFile.writeBytes(encryptToB64(pkcs12Bytes).toByteArray(StandardCharsets.UTF_8))

        // If the PKCS#12 we imported/created requires a password, store it. For our generated
        // PKCS#12 (from PEM), we intentionally store an empty password so later loads use a
        // non-null char[] (some providers treat null differently).
        if (storePassword != null) {
            prefs.edit().putString(
                "p12pwd:$networkId:$certId",
                encryptToB64(storePassword.toByteArray(StandardCharsets.UTF_8))
            ).apply()
        } else {
            prefs.edit().remove("p12pwd:$networkId:$certId").apply()
        }

        return StoredClientCert(certId = certId, label = effectiveLabel)
    }

    /** Back-compat helper for older call sites. */
    fun importClientCert(networkId: String, uri: Uri, displayLabel: String?, password: String?): StoredClientCert {
        return importClientCert(
            networkId,
            ClientCertDraft(
                format = ClientCertFormat.PKCS12,
                uri = uri,
                password = password,
                displayName = displayLabel
            )
        )
    }

    fun removeClientCert(networkId: String, certId: String?) {
        if (certId.isNullOrBlank()) return
        val dir = File(ctx.filesDir, "client_certs")
        File(dir, "$certId.bin").delete()
        prefs.edit().remove("p12pwd:$networkId:$certId").apply()
    }

    fun loadTlsClientCert(networkId: String, certId: String?): TlsClientCert? {
        if (certId.isNullOrBlank()) return null
        val dir = File(ctx.filesDir, "client_certs")
        val f = File(dir, "$certId.bin")
        if (!f.exists()) return null
        val enc = f.readText()
        val pkcs12 = try { decryptFromB64(enc) } catch (_: Throwable) { null } ?: return null

        val pwdEnc = prefs.getString("p12pwd:$networkId:$certId", null)
        val pwd = if (pwdEnc != null) {
            try { decryptFromB64(pwdEnc)?.toString(Charsets.UTF_8) } catch (_: Throwable) { null }
        } else null

        return TlsClientCert(pkcs12 = pkcs12, password = pwd)
    }

    private fun readBytesFromUri(uri: Uri): ByteArray {
        return ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Unable to open file")
    }

    private data class PemBlock(val type: String, val der: ByteArray)

    private fun parsePemBlocks(bytes: ByteArray): List<PemBlock> {
        val text = try {
            bytes.toString(Charsets.UTF_8)
        } catch (_: Throwable) {
            return emptyList()
        }
        val rx = Regex("-----BEGIN ([^-]+)-----([\\s\\S]*?)-----END \\1-----")
        return rx.findAll(text).mapNotNull { m ->
            val type = m.groupValues.getOrNull(1)?.trim().orEmpty()
            val b64 = m.groupValues.getOrNull(2)?.replace(Regex("\\s"), "") ?: return@mapNotNull null
            val der = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return@mapNotNull null
            PemBlock(type = type, der = der)
        }.toList()
    }

    private fun parseCertificates(certBytes: ByteArray): List<X509Certificate> {
        val blocks = parsePemBlocks(certBytes)
        val cf = CertificateFactory.getInstance("X.509")
        if (blocks.isNotEmpty()) {
            val certs = blocks.filter { it.type.equals("CERTIFICATE", ignoreCase = true) }
                .mapNotNull { b ->
                    runCatching {
                        cf.generateCertificate(ByteArrayInputStream(b.der)) as X509Certificate
                    }.getOrNull()
                }
            if (certs.isNotEmpty()) return certs
        }

        // DER fallback
        return listOf(
            cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        )
    }

    private fun parsePrivateKey(keyBytes: ByteArray, password: String?): PrivateKey {
        val blocks = parsePemBlocks(keyBytes)
        if (blocks.isNotEmpty()) {
            // Prefer an unencrypted PKCS#8 key if present.
            blocks.firstOrNull { it.type.equals("PRIVATE KEY", ignoreCase = true) }?.let { b ->
                return decodePkcs8PrivateKey(b.der)
            }

            // PKCS#8 encrypted key.
            blocks.firstOrNull { it.type.equals("ENCRYPTED PRIVATE KEY", ignoreCase = true) }?.let { b ->
                val pwd = password ?: throw IllegalArgumentException("This private key is encrypted; please enter the key password")
                val epi = EncryptedPrivateKeyInfo(b.der)
                val skf = SecretKeyFactory.getInstance(epi.algName)
                val pbeKey = skf.generateSecret(PBEKeySpec(pwd.toCharArray()))
                val cipher = Cipher.getInstance(epi.algName)
                val params = epi.algParameters
                if (params != null) cipher.init(Cipher.DECRYPT_MODE, pbeKey, params) else cipher.init(Cipher.DECRYPT_MODE, pbeKey)
                val ks = epi.getKeySpec(cipher)
                return decodePkcs8PrivateKey(ks.encoded)
            }

            // RSA PKCS#1 key (common in client.key PEMs).
            blocks.firstOrNull { it.type.equals("RSA PRIVATE KEY", ignoreCase = true) }?.let { b ->
                val pkcs8 = wrapRsaPkcs1ToPkcs8(b.der)
                val kf = KeyFactory.getInstance("RSA")
                return kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            }

            // If we got here, it's a PEM we don't support yet.
            val t = blocks.first().type
            throw IllegalArgumentException("Unsupported PEM key type: $t. Please use a PKCS#8 PRIVATE KEY.")
        }

        // DER fallback: treat as PKCS#8
        return decodePkcs8PrivateKey(keyBytes)
    }

    private fun decodePkcs8PrivateKey(pkcs8: ByteArray): PrivateKey {
        val spec = PKCS8EncodedKeySpec(pkcs8)
        val algs = listOf("RSA", "EC", "DSA")
        var last: Throwable? = null
        for (a in algs) {
            try {
                return KeyFactory.getInstance(a).generatePrivate(spec)
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalArgumentException("Unable to parse private key", last)
    }

    private fun buildPkcs12FromPem(certBytes: ByteArray, keyBytes: ByteArray, keyPassword: String?): ByteArray {
        val certs = parseCertificates(certBytes)
        val key = parsePrivateKey(keyBytes, keyPassword)

        val pwdChars = charArrayOf() // empty
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, pwdChars)
        ks.setKeyEntry("client", key, pwdChars, certs.toTypedArray())

        val out = ByteArrayOutputStream()
        ks.store(out, pwdChars)
        return out.toByteArray()
    }

    // Minimal DER encoder utilities (for wrapping RSA PKCS#1 into PKCS#8).
    private fun derLen(len: Int): ByteArray {
        if (len < 128) return byteArrayOf(len.toByte())
        var v = len
        val tmp = ArrayList<Byte>()
        while (v > 0) {
            tmp.add(0, (v and 0xFF).toByte())
            v = v ushr 8
        }
        val out = ByteArray(1 + tmp.size)
        out[0] = (0x80 or tmp.size).toByte()
        for (i in tmp.indices) out[i + 1] = tmp[i]
        return out
    }

    private fun derTlv(tag: Int, value: ByteArray): ByteArray {
        val len = derLen(value.size)
        val out = ByteArray(1 + len.size + value.size)
        out[0] = tag.toByte()
        System.arraycopy(len, 0, out, 1, len.size)
        System.arraycopy(value, 0, out, 1 + len.size, value.size)
        return out
    }

    private fun derSeq(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val buf = ByteArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, buf, pos, p.size)
            pos += p.size
        }
        return derTlv(0x30, buf)
    }

    private fun derIntZero(): ByteArray = derTlv(0x02, byteArrayOf(0x00))

    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun derOidRsaEncryption(): ByteArray = byteArrayOf(
        0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01
    )

    private fun derOctetString(bytes: ByteArray): ByteArray = derTlv(0x04, bytes)

    private fun wrapRsaPkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        val algId = derSeq(derOidRsaEncryption(), derNull())
        return derSeq(derIntZero(), algId, derOctetString(pkcs1))
    }

    data class StoredClientCert(val certId: String, val label: String?)
}