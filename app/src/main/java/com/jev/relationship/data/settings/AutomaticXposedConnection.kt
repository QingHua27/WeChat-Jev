package com.jev.relationship.data.settings

import android.content.SharedPreferences
import com.jev.relationship.xposed.XposedModulePreferences
import com.jev.relationship.xposed.XposedPairingProvisioner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Provisions both ends locally; model keys and chat data never enter remote preferences. */
class AutomaticXposedConnection(
    private val repository: XposedIntegrationRepository,
    private val remotePreferences: () -> SharedPreferences?,
    private val newToken: () -> String = XposedPairingTokenGenerator::generate,
) {
    private val mutex = Mutex()

    suspend fun connect(): Boolean = mutex.withLock {
        val remote = remotePreferences() ?: return@withLock false
        val current = repository.current()
        val token = current.pairingToken?.takeIf { it.isNotBlank() } ?: newToken()
        // Commit the service side first. A module that sees the remote token can authenticate.
        if (!current.enabled || current.pairingToken != token) repository.activateWithPairingToken(token)
        if (remote.getString(XposedModulePreferences.PAIRING_TOKEN, null) == token) return@withLock true
        XposedPairingProvisioner.save(remote, token)
    }
}
