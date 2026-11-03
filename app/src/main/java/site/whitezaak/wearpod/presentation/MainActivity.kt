package site.whitezaak.wearpod.presentation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import site.whitezaak.wearpod.R
import site.whitezaak.wearpod.presentation.theme.WearPodTheme
import site.whitezaak.wearpod.settings.AppLanguageManager
import site.whitezaak.wearpod.util.isTrueWearOs

class MainActivity : ComponentActivity() {
    private var openPlayerRequestNonce by mutableLongStateOf(0L)
    private var suppressNextLaunchAnimation = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onResume() {
        super.onResume()
        // 冷启动/带回前台时 handleIntent 先注册一次；若当时系统侧 ActivityRecord 尚未
        // 进入 RESUMED 态（overridePendingTransition 的服务端前置条件），这里在过渡
        // 仍在收集期间补一次。对同一过渡重复注册是幂等的。
        suppressLaunchAnimation()
    }

    override fun onPause() {
        super.onPause()
        // 时机已过：清掉标记，避免污染之后无关的过渡（如从其它应用返回时的动画）。
        suppressNextLaunchAnimation = false
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PLAYER || intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            requestInstantOpen()
            openPlayerRequestNonce = System.currentTimeMillis()
        }
    }

    /**
     * 非真 Wear OS（小米/OPPO 等安卓表）：点击表盘持续活动/媒体通知把任务带回前台时，
     * 过渡不带动画参数（opt=null），小米的过渡 shell 会按默认「从图标位置方块展开」的
     * 启动动画处理——回到亮色页面（播放页整屏封面）时肉眼可见。这里给本次过渡注册
     * CUSTOM 动画选项，让 shell 与 WM 两侧都拿到一个 0 时长的真实动画资源而不再回退
     * 到各自的默认动画（网易云手表版在 RedirectActivity 里的同款做法；注意必须传真实
     * 资源而不是 0——传 0 会被系统当成「自定义动画加载失败」，回退成框架默认的
     * rounded_window_enter 缩放）。真 Wear OS 保持系统默认动画不变。
     */
    private fun requestInstantOpen() {
        if (isTrueWearOs()) return
        suppressNextLaunchAnimation = true
        suppressLaunchAnimation()
    }

    private fun suppressLaunchAnimation() {
        if (!suppressNextLaunchAnimation) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                R.anim.wearpod_no_anim,
                R.anim.wearpod_no_anim,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.wearpod_no_anim, R.anim.wearpod_no_anim)
        }
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "site.whitezaak.wearpod.action.OPEN_PLAYER"
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
