package com.jev.relationship.data.settings

import java.security.SecureRandom
import java.util.Base64

data class XposedIntegrationSettings(
    val enabled: Boolean = false,
    val pairingToken: String? = null,
) {
    val isPaired: Boolean
        get() = !pairingToken.isNullOrBlank()

    fun disabled(): XposedIntegrationSettings = XposedIntegrationSettings()
}

object XposedPairingTokenGenerator {
    const val MIN_LENGTH = 32

    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

interface XposedIntegrationRepository {
    val settings: kotlinx.coroutines.flow.Flow<XposedIntegrationSettings>

    suspend fun current(): XposedIntegrationSettings

    suspend fun activateWithPairingToken(token: String)

    suspend fun disable()
}
