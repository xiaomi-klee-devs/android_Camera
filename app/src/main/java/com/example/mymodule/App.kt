package com.example.mymodule

import android.app.Application

/**
 * Module app entry point. Registers the Xposed service listener so the settings
 * UI can write into the LSPosed database (read by hooks via getRemotePreferences()).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        RemotePrefs.init(this)
    }
}