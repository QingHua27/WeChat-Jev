package com.jev.relationship.data.settings

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

internal val Context.settingsDataStore by preferencesDataStore(name = "jev_settings")
