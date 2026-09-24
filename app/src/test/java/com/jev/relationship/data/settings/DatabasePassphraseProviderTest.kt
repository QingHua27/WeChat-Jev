package com.jev.relationship.data.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DatabasePassphraseProviderTest {
    @Test
    fun firstReadCreatesAndSecondReadReusesTheSamePassphrase() {
        val store = InMemorySecretStore()
        val provider = DatabasePassphraseProvider(
            protector = ReversingProtector,
            store = store,
            generator = { "per-install-secret" },
        )

        val first = provider.passphrase()
        val second = provider.passphrase()

        assertArrayEquals("per-install-secret".toByteArray(), first)
        assertArrayEquals(first, second)
        assertEquals("terces-llatsni-rep", store.encrypted)
    }

    @Test
    fun corruptedStoredPassphraseFailsClosed() {
        val store = InMemorySecretStore().apply { encrypted = "corrupted" }
        val provider = DatabasePassphraseProvider(
            protector = ReversingProtector,
            store = store,
            generator = { "must-not-be-used" },
        )

        assertThrows(IllegalStateException::class.java) { provider.passphrase() }
    }

    private class InMemorySecretStore : SecretValueStore {
        var encrypted: String? = null

        override fun read(): String? = encrypted

        override fun write(value: String) {
            encrypted = value
        }
    }

    private object ReversingProtector : SecretProtector {
        override fun encrypt(value: String): String = value.reversed()

        override fun decrypt(value: String): String =
            if (value == "corrupted") error("cannot decrypt") else value.reversed()
    }
}

