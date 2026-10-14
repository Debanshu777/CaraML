package com.debanshu777.caraml.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath

internal const val dataStoreFileName = "app.preferences_pb"

fun getPreferencesDataStore(path: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath { path.toPath() }

expect fun createPreferencesDataStore(): DataStore<Preferences>
