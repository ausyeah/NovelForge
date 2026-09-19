package com.novelforge.app.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import kotlinx.coroutines.flow.first

private val Context.apiKeyDataStore by preferencesDataStore(name = "secure_api_key")

/**
 * Stores the user-provided key without putting the plaintext into logs or a
 * normal preferences file. Some older/vendor Android Keystore providers do
 * not implement AES-GCM correctly, so writes use GCM first and fall back to
 * Keystore AES-CBC plus a Keystore HMAC integrity tag.
 */
class KeystoreApiKeyStore(
    private val context: Context,
    private val alias: String = DEFAULT_ALIAS
) : ApiKeyStore {

    override suspend fun read(): String? {
        return try {
            val preferences = context.apiKeyDataStore.data.first()
            val encodedCiphertext = preferences[CIPHERTEXT] ?: return null
            val encodedIv = preferences[IV] ?: return null
            val ciphertext = android.util.Base64.decode(encodedCiphertext, android.util.Base64.NO_WRAP)
            val iv = android.util.Base64.decode(encodedIv, android.util.Base64.NO_WRAP)
            when (preferences[FORMAT_VERSION]) {
                CBC_FORMAT_VERSION -> decryptCbc(preferences[TAG], ciphertext, iv)
                else -> decryptGcm(ciphertext, iv)
            }
        } catch (error: Exception) {
            if (error is SecureStorageException) throw error
            throw SecureStorageException("无法解密 API Key，Keystore 密钥可能已失效", error)
        }
    }

    override suspend fun write(apiKey: String) {
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
        try {
            writeGcm(apiKey)
            return
        } catch (error: Exception) {
            if (!isRecoverableKeystoreFailure(error)) {
                throw SecureStorageException("无法安全保存 API Key", error)
            }
            // The user is explicitly replacing the value, so stale ciphertext
            // can be discarded while rotating an invalid/incompatible key.
            deleteKey(gcmAlias())
            clearStoredData()
        }

        try {
            writeCbc(apiKey)
        } catch (error: Exception) {
            throw SecureStorageException("设备不支持当前 API Key 安全存储方式", error)
        }
    }

    override suspend fun clear() {
        clearStoredData()
        deleteKey(gcmAlias())
        deleteKey(LEGACY_GCM_ALIAS)
        deleteKey(cbcAlias())
        deleteKey(hmacAlias())
    }

    private suspend fun writeGcm(apiKey: String) {
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateAesKey(gcmAlias(), KeyProperties.BLOCK_MODE_GCM, KeyProperties.ENCRYPTION_PADDING_NONE)
        )
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))
        context.apiKeyDataStore.edit { preferences ->
            preferences[CIPHERTEXT] = encode(ciphertext)
            preferences[IV] = encode(iv)
            preferences[FORMAT_VERSION] = GCM_FORMAT_VERSION
            preferences.remove(TAG)
        }
    }

    private suspend fun writeCbc(apiKey: String) {
        val cipher = Cipher.getInstance(CBC_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateAesKey(cbcAlias(), KeyProperties.BLOCK_MODE_CBC, KeyProperties.ENCRYPTION_PADDING_PKCS7)
        )
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))
        val tag = hmac(iv, ciphertext)
        context.apiKeyDataStore.edit { preferences ->
            preferences[CIPHERTEXT] = encode(ciphertext)
            preferences[IV] = encode(iv)
            preferences[TAG] = encode(tag)
            preferences[FORMAT_VERSION] = CBC_FORMAT_VERSION
        }
    }

    private fun decryptGcm(ciphertext: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = (keyStore.getKey(gcmAlias(), null) as? SecretKey)
            ?: (keyStore.getKey(LEGACY_GCM_ALIAS, null) as? SecretKey)
            ?: getOrCreateAesKey(
                gcmAlias(),
                KeyProperties.BLOCK_MODE_GCM,
                KeyProperties.ENCRYPTION_PADDING_NONE
            )
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_LENGTH_BITS, iv)
        )
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun decryptCbc(encodedTag: String?, ciphertext: ByteArray, iv: ByteArray): String {
        val storedTag = encodedTag?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
            ?: throw SecureStorageException("API Key 完整性校验信息缺失")
        val expectedTag = hmac(iv, ciphertext)
        if (!MessageDigest.isEqual(storedTag, expectedTag)) {
            throw SecureStorageException("API Key 完整性校验失败")
        }
        val cipher = Cipher.getInstance(CBC_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateAesKey(cbcAlias(), KeyProperties.BLOCK_MODE_CBC, KeyProperties.ENCRYPTION_PADDING_PKCS7),
            IvParameterSpec(iv)
        )
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun hmac(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_TRANSFORMATION)
        mac.init(getOrCreateHmacKey())
        return mac.doFinal(iv + ciphertext)
    }

    private fun getOrCreateAesKey(alias: String, blockMode: String, padding: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(blockMode)
                    .setEncryptionPaddings(padding)
                    .setKeySize(KEY_SIZE_BITS)
                    .build()
            )
        }.generateKey()
    }

    private fun getOrCreateHmacKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val hmacAlias = hmacAlias()
        val existing = keyStore.getKey(hmacAlias, null) as? SecretKey
        if (existing != null) return existing

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    hmacAlias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
        }.generateKey()
    }

    private suspend fun clearStoredData() {
        context.apiKeyDataStore.edit { preferences ->
            preferences.remove(CIPHERTEXT)
            preferences.remove(IV)
            preferences.remove(TAG)
            preferences.remove(FORMAT_VERSION)
        }
    }

    private fun deleteKey(keyAlias: String) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
    }

    private fun encode(value: ByteArray) =
        android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)

    private fun gcmAlias() = "$alias-gcm"

    private fun cbcAlias() = "$alias-cbc"

    private fun hmacAlias() = "$alias-hmac"

    private fun isRecoverableKeystoreFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is GeneralSecurityException || current is ProviderException) return true
            current = current.cause
        }
        return false
    }

    companion object {
        private const val DEFAULT_ALIAS = "novelforge_api_key_v2"
        private const val LEGACY_GCM_ALIAS = "novelforge_api_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val CBC_TRANSFORMATION = "AES/CBC/PKCS7Padding"
        private const val HMAC_TRANSFORMATION = "HmacSHA256"
        private const val KEY_SIZE_BITS = 128
        private const val TAG_LENGTH_BITS = 128
        private const val GCM_FORMAT_VERSION = "1"
        private const val CBC_FORMAT_VERSION = "2"
        private val CIPHERTEXT = stringPreferencesKey("ciphertext")
        private val IV = stringPreferencesKey("iv")
        private val TAG = stringPreferencesKey("tag")
        private val FORMAT_VERSION = stringPreferencesKey("format_version")
    }
}

class SecureStorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
