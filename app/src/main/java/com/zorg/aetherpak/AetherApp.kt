package com.zorg.aetherpak

import android.app.Application

class AetherApp : Application() {

    lateinit var serviceLocator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        serviceLocator = ServiceLocator(this)
    }

    companion object {
        @Volatile
        private var instance: AetherApp? = null

        fun locator(): ServiceLocator =
            instance?.serviceLocator ?: error("AetherApp not initialized")
    }
}
