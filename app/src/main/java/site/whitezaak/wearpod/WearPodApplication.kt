package site.whitezaak.wearpod

import android.app.Application
import android.content.Context
import site.whitezaak.wearpod.settings.AppLanguageManager
import site.whitezaak.wearpod.util.ConnectivityObserver

class WearPodApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppLanguageManager.applyToResources(this)
        ConnectivityObserver.register(this)
    }
}