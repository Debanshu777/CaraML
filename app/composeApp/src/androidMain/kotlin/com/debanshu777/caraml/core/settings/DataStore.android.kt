package com.debanshu777.caraml.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

private lateinit var appContext: Context

fun initPreferencesDataStore(context: Context) {
    appContext = context.applicationContext
}

private fun requireAppContext(): Context {
    check(::appContext.isInitialized) { "initPreferencesDataStore must be called before accessing DataStore" }
    return appContext
}

private fun getPreferencesDataStorePath(context: Context): String =
    context.filesDir.resolve(dataStoreFileName).absolutePath

actual fun createPreferencesDataStore(): DataStore<Preferences> {
    val context = requireAppContext()
    return getPreferencesDataStore(getPreferencesDataStorePath(context))
}
