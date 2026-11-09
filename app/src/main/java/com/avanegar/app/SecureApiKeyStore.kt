package com.avanegar.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the personal-use API key encrypted at rest using a non-exportable Android Keystore key.
 * Never add any key string to a source file, build configuration, crash log, or GitHub secret.
 */
class SecureApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("avanegar_secret", Context.MODE_PRIVATE)
    private val alias = "avanegar_openai_aes_key_v1"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    fun save(value: String) {
        require(value.isNotBlank()) { "کلید API خالی است" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
    }

    fun load(): String? {
        val iv64 = prefs.getString("iv", null) ?: return null
        val data64 = prefs.getString("ciphertext", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv64, Base64.DEFAULT)))
        }
        return String(cipher.doFinal(Base64.decode(data64, Base64.DEFAULT)), Charsets.UTF_8)
    }

    fun clear() {
        prefs.edit().clear().commit()
    }
}
