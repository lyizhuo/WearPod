package site.whitezaak.wearpod.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 只有内置了 Google Wear OS 服务框架（`com.google.android.wearable.app`）的系统才是
 * "真 Wear OS"（Pixel Watch、Galaxy Watch 4+ 等）。小米/OPPO 等全水桶安卓表虽然也是圆表，
 * 但走的是系统自带 Launcher + 原生导航，媒体通知与触感需要按厂商定制处理。
 */
fun Context.isTrueWearOs(): Boolean =
    try {
        PackageInfoCompat.get(packageManager, "com.google.android.wearable.app")
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

/**
 * 小米手表（HyperOS / Vela 系）：表冠旋转触感走小米私有 HapticFeedbackConstants，
 * Wear Compose 自带的触感实现在这类设备上被判定为 Disabled，因此需要单独适配。
 */
fun Context.isXiaomiWatch(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH) &&
        (Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) ||
            Build.BRAND.contains("Xiaomi", ignoreCase = true))
