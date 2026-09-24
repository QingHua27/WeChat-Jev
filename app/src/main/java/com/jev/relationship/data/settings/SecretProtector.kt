package com.jev.relationship.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretProtector {
    fun encrypt(value: String): String

    fun decrypt(value: String): String
}

class AndroidKeyStoreSecretProtector : SecretProtector {
    override fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return "${encode(cipher.iv)}:${encode(encrypted)}"
    }

    override fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2) { "Invalid encrypted setting" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_LENGTH_BITS, decode(parts[0])),
        )
        return cipher.doFinal(decode(parts[1])).toString(StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "jev_provider_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}

