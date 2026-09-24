package com.jev.relationship.ipc

import com.jev.relationship.data.settings.XposedIntegrationSettings
import kotlinx.coroutines.CompletableDeferred

class IpcSettingsReadiness {
    private val initialSettings = CompletableDeferred<XposedIntegrationSettings>()

    fun publish(settings: XposedIntegrationSettings) {
        initialSettings.complete(settings)
    }

    suspend fun awaitInitial(): XposedIntegrationSettings = initialSettings.await()
}
