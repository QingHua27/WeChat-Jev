package com.jev.relationship.data.settings

import android.content.Context
import java.util.UUID

interface SecretValueStore {
    fun read(): String?

    fun write(value: String)
}

class DatabasePassphraseProvider(
    private val protector: SecretProtector,
    private val store: SecretValueStore,
    private val generator: () -> String = { UUID.randomUUID().toString() + UUID.randomUUID() },
) {
    fun passphrase(): ByteArray {
        val encrypted = store.read()
        if (encrypted != null) {
            return runCatching { protector.decrypt(encrypted).toByteArray() }
                .getOrElse { error -> throw IllegalStateException("Cannot decrypt database passphrase", error) }
        }
        val clear = generator()
        store.write(protector.encrypt(clear))
        return clear.toByteArray()
    }
}

class AndroidDatabasePassphraseProvider(
    context: Context,
    protector: SecretProtector,
) {
    private val delegate = DatabasePassphraseProvider(
        protector = protector,
        store = SharedPreferencesSecretValueStore(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        ),
    )

    fun passphrase(): ByteArray = delegate.passphrase()

    private companion object {
        const val PREFERENCES_NAME = "jev_database_secrets"
    }
}

private class SharedPreferencesSecretValueStore(
    private val preferences: android.content.SharedPreferences,
) : SecretValueStore {
    override fun read(): String? = preferences.getString(KEY, null)

    override fun write(value: String) {
        check(preferences.edit().putString(KEY, value).commit()) {
            "Cannot persist database passphrase"
        }
    }

    private companion object {
        const val KEY = "encrypted_database_passphrase"
    }
}

