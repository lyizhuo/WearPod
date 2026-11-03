package site.whitezaak.wearpod.util

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 13 起 `getPackageInfo(String, Int)` 被 `PackageInfoFlags` 取代；
 * 统一走这里，避免每个调用点重复版本分支。
 */
object PackageInfoCompat {
    fun get(packageManager: PackageManager, packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
    }
}
