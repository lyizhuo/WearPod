package site.whitezaak.wearpod.presentation.rotary

import kotlin.math.abs

internal enum class RotaryHapticFeedback { NONE, TICK, LIMIT }

/**
 * 表冠触感的纯逻辑累加器（无 Android 依赖，可在 JVM 单测覆盖）。
 *
 * 语义对齐小米系统实现：
 * - 转轴累计到 [pixelsPerTick] 就发一次 tick，并把余数留到下一次（慢转也能每格一次）。
 * - 已到列表端且仍朝该方向旋转时只发 limit；同一方向的 limit 在
 *   [limitGuardMs] 内不重复，避免持续顶住时连续震动。
 * - 反向旋转从 0 重新累计，且立即允许一次 limit（前端顶住后反向往回拧的手感）。
 */
internal class RotaryHapticsAccumulator(
    private val tickGuardMs: Long = 30L,
    private val limitGuardMs: Long = 500L,
) {
    private var accumulatedPixels = 0f
    private var lastDirection = 0
    private var lastTickAtMs: Long? = null
    private var lastLimitAtMs: Long? = null

    /**
     * @param deltaPixels 本次旋转折算的滚动像素（Compose `RotaryScrollEvent.verticalScrollPixels`）
     * @param pixelsPerTick 每 tick 需要的像素数
     * @param canScrollInDirection 该方向是否还能继续滚动（false 表示已到列表端）
     * @param nowMs 事件时间戳（`RotaryScrollEvent.uptimeMillis`）
     */
    fun onScroll(
        deltaPixels: Float,
        pixelsPerTick: Float,
        canScrollInDirection: Boolean,
        nowMs: Long,
    ): RotaryHapticFeedback {
        if (!deltaPixels.isFinite() || deltaPixels == 0f) return RotaryHapticFeedback.NONE
        if (!pixelsPerTick.isFinite() || pixelsPerTick <= 0f) return RotaryHapticFeedback.NONE

        val direction = if (deltaPixels > 0f) 1 else -1
        if (direction != lastDirection) {
            accumulatedPixels = 0f
            lastDirection = direction
            lastLimitAtMs = null
        }

        if (!canScrollInDirection) {
            accumulatedPixels = 0f
            val allowed = lastLimitAtMs?.let { nowMs - it >= limitGuardMs } ?: true
            return if (allowed) {
                lastLimitAtMs = nowMs
                RotaryHapticFeedback.LIMIT
            } else {
                RotaryHapticFeedback.NONE
            }
        }

        accumulatedPixels += deltaPixels
        if (abs(accumulatedPixels) < pixelsPerTick) return RotaryHapticFeedback.NONE
        accumulatedPixels %= pixelsPerTick

        val allowed = lastTickAtMs?.let { nowMs - it >= tickGuardMs } ?: true
        return if (allowed) {
            lastTickAtMs = nowMs
            RotaryHapticFeedback.TICK
        } else {
            RotaryHapticFeedback.NONE
        }
    }
}
