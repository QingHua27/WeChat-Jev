package com.jev.relationship.xposed

import android.content.SharedPreferences

object XposedPairingProvisioner {
    fun save(preferences: SharedPreferences, token: String): Boolean {
        val normalizedToken = token.trim()
        if (normalizedToken.isEmpty()) return false
        return runCatching {
            preferences.edit()
                .putString(XposedModulePreferences.PAIRING_TOKEN, normalizedToken)
                .commit()
        }.getOrDefault(false)
    }

    fun clear(preferences: SharedPreferences): Boolean = runCatching {
        preferences.edit().remove(XposedModulePreferences.PAIRING_TOKEN).commit()
    }.getOrDefault(false)
}
