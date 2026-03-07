package com.debanshu777.caraml

import android.app.Application
import com.debanshu777.caraml.core.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.logger.Level

class CaraMLApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@CaraMLApplication)
            androidLogger(Level.DEBUG)
        }
    }
}
