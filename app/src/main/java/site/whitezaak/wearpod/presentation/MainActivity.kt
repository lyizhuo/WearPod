package site.whitezaak.wearpod.presentation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.core.content.edit
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import site.whitezaak.wearpod.presentation.theme.WearPodTheme
import site.whitezaak.wearpod.settings.AppLanguageManager

class MainActivity : ComponentActivity() {
    private var openPlayerRequestNonce by mutableLongStateOf(0L)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        handleIntent(intent)
        setContent {
            WearPodTheme {
                WearPodApp(
                    openPlayerRequestNonce = openPlayerRequestNonce
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PLAYER || intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            openPlayerRequestNonce = System.currentTimeMillis()
        }
    }

    /**
     * Android 13+ 通知权限：仅首次启动询问一次（拒绝后不再骚扰）。
     * 媒体通知是熄屏/蓝牙场景下的主要播放控制入口，值得申请。
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("wearpod_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("notif_permission_asked", false)) return
        prefs.edit { putBoolean("notif_permission_asked", true) }
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "site.whitezaak.wearpod.action.OPEN_PLAYER"
        const val EXTRA_OPEN_PLAYER = "open_player"
        private const val REQUEST_POST_NOTIFICATIONS = 1002
    }
}
