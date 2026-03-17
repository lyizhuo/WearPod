package com.example.wearpod

import android.app.Application
import android.content.Context
import com.example.wearpod.settings.AppLanguageManager

class WearPodApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppLanguageManager.applyToResources(this)
    }
}