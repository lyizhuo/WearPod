package site.whitezaak.wearpod.presentation.rotary

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import site.whitezaak.wearpod.util.isXiaomiWatch

/**
 * 小米手表表冠旋转触感。
 *
 * 为什么不用 Wear Compose 自带的 `hapticFeedbackEnabled = true`：
 * 它的触感实现直接引用 Google Wear 私有类 `com.google.wear.input.WearHapticFeedbackConstants`，
 * 且按 `hasWearSDK` 判定常量表——小米这类改造 Android 表被判为 `DisabledHapticConstants`，
 * 要么无触感，要么在缺类的 ROM 上直接崩。这里改为自己算 tick，并调用公开的
 * [android.view.View.performHapticFeedback] 传小米使用的常量值。
 *
 * 必须用 [onPreRotaryScrollEvent]（下行阶段）而不是 `onRotaryScrollEvent`：
 * Wear Compose 的 `RotaryScrollable` 在 pre 阶段就 `return true` 消费了事件，
 * 上行阶段的回调永远不会被调用。返回 false 让滚动仍由它处理，这里只做触感。
 *
 * 阈值：Compose 的 `verticalScrollPixels = -axisValue * scaledScrollFactor`，
 * 小米系统按 `radians = axisValue * scaledScrollFactor / resolution` 每 45° 一次 tick，
 * 折算成像素即 `45 * resolution`；设备不上报 resolution 时按 45px 兜底。
 */
@Composable
fun Modifier.xiaomiRotaryHaptics(listState: ScalingLazyListState): Modifier {
    val context = LocalContext.current
    val enabled = remember(context) { context.isXiaomiWatch() }
    if (!enabled) return this

    val view = LocalView.current
    val accumulator = remember { RotaryHapticsAccumulator() }
    // 输入设备的 resolution 是固定值，按 deviceId 缓存，避免每个旋转事件都查询 InputManager。
    val pixelsPerTickCache = remember { mutableMapOf<Int, Float>() }

    return this.onPreRotaryScrollEvent { event ->
        val delta = event.verticalScrollPixels
        if (delta != 0f) {
            val pixelsPerTick = pixelsPerTickCache.getOrPut(event.inputDeviceId) {
                InputDevice.getDevice(event.inputDeviceId)
                    ?.getMotionRange(MotionEvent.AXIS_SCROLL)
                    ?.resolution
                    ?.takeIf { it > 0f }
                    ?.times(DEGREES_PER_TICK)
                    // HAL 的 resolution 没有单位约定，异常值不能把功能整个关掉，
                    // 只允许在 1/4–4 倍之间调整手感。
                    ?.coerceIn(DEGREES_PER_TICK / 4f, DEGREES_PER_TICK * 4f)
                    ?: DEGREES_PER_TICK
            }
            val canScrollInDirection =
                if (delta > 0f) listState.canScrollForward else listState.canScrollBackward

            when (accumulator.onScroll(delta, pixelsPerTick, canScrollInDirection, event.uptimeMillis)) {
                RotaryHapticFeedback.TICK -> view.performHapticFeedback(HAPTIC_SCROLL_TICK)
                RotaryHapticFeedback.LIMIT -> view.performHapticFeedback(HAPTIC_SCROLL_LIMIT)
                RotaryHapticFeedback.NONE -> Unit
            }
        }
        // 不消费事件：滚动仍交给 Wear Compose 的 rotaryScrollable。
        false
    }
}

/** 小米系统每 45°（以 axisValue * scrollFactor / resolution 计）给一次 tick。 */
private const val DEGREES_PER_TICK = 45f

/**
 * 小米/Android 隐藏的 `HapticFeedbackConstants`：
 * `SCROLL_TICK` 与 `SCROLL_LIMIT` 未进公开 SDK，只能按数值调用；
 * 数值取自 Wear Compose 对 Wear 4 使用的常量表（tick=18、limit=20）。
 */
private const val HAPTIC_SCROLL_TICK = 18
private const val HAPTIC_SCROLL_LIMIT = 20
