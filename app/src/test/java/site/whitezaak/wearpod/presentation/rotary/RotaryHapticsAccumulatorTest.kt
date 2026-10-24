package site.whitezaak.wearpod.presentation.rotary

import org.junit.Assert.assertEquals
import org.junit.Test

class RotaryHapticsAccumulatorTest {

    private val pixelsPerTick = 45f

    @Test
    fun `slow rotation fires one tick per accumulated step`() {
        val accumulator = RotaryHapticsAccumulator()

        assertEquals(
            RotaryHapticFeedback.NONE,
            accumulator.onScroll(20f, pixelsPerTick, true, 0L),
        )
        assertEquals(
            RotaryHapticFeedback.NONE,
            accumulator.onScroll(20f, pixelsPerTick, true, 10L),
        )
        assertEquals(
            RotaryHapticFeedback.TICK,
            accumulator.onScroll(5f, pixelsPerTick, true, 20L),
        )
        // 45px 正好一格，余数归零：再转 44px 不应触发。
        assertEquals(
            RotaryHapticFeedback.NONE,
            accumulator.onScroll(44f, pixelsPerTick, true, 100L),
        )
        assertEquals(
            RotaryHapticFeedback.TICK,
            accumulator.onScroll(1f, pixelsPerTick, true, 110L),
        )
    }

    @Test
    fun `direction change resets accumulation`() {
        val accumulator = RotaryHapticsAccumulator()

        assertEquals(
            RotaryHapticFeedback.NONE,
            accumulator.onScroll(40f, pixelsPerTick, true, 0L),
        )
        // 反向：累计清零，-40 不足一格。
        assertEquals(
            RotaryHapticFeedback.NONE,
            accumulator.onScroll(-40f, pixelsPerTick, true, 10L),
        )
        // 继续反向累计到 -45。
        assertEquals(
            RotaryHapticFeedback.TICK,
            accumulator.onScroll(-5f, pixelsPerTick, true, 20L),
        )
    }

    @Test
    fun `tick is throttled by guard interval`() {
        val accumulator = RotaryHapticsAccumulator(tickGuardMs = 30L)

        assertEquals(
            RotaryHapticFeedback.TICK,
            accumulator.onScroll(45f, pixelsPerTick, true, 0L),
        )
        // 10ms 内第二次满格：不触发。
        assertEquals(
            RotaryHapticFeedback.NONE,
            accumulator.onScroll(45f, pixelsPerTick, true, 10L),
        )
        // 超过 guard 之后恢复。
        assertEquals(
            RotaryHapticFeedback.TICK,
            accumulator.onScroll(45f, pixelsPerTick, true, 40L),
        )
    }

    @Test
    fun `limit is throttled by guard interval`() {
        val accumulator = RotaryHapticsAccumulator(limitGuardMs = 500L)

        assertEquals(
            RotaryHapticFeedback.LIMIT,
            accumulator.onScroll(10f, pixelsPerTick, false, 0L),
        )
        assertEquals(
            RotaryHapticFeedback.NONE,
            accumulator.onScroll(10f, pixelsPerTick, false, 100L),
        )
        assertEquals(
            RotaryHapticFeedback.LIMIT,
            accumulator.onScroll(10f, pixelsPerTick, false, 500L),
        )
    }

    @Test
    fun `limit does not accumulate towards tick`() {
        val accumulator = RotaryHapticsAccumulator()

        assertEquals(
            RotaryHapticFeedback.LIMIT,
            accumulator.onScroll(40f, pixelsPerTick, false, 0L),
        )
        // 边界事件不累计：紧接着可滚动时从 0 开始，需要完整一格才 tick。
        assertEquals(
            RotaryHapticFeedback.NONE,
            accumulator.onScroll(40f, pixelsPerTick, true, 10L),
        )
        assertEquals(
            RotaryHapticFeedback.TICK,
            accumulator.onScroll(5f, pixelsPerTick, true, 20L),
        )
    }

    @Test
    fun `reverse rotation at boundary fires limit immediately`() {
        val accumulator = RotaryHapticsAccumulator(limitGuardMs = 500L)

        assertEquals(
            RotaryHapticFeedback.LIMIT,
            accumulator.onScroll(10f, pixelsPerTick, false, 0L),
        )
        // 反向旋转到另一端顶住：方向变化后 limit 立即可用，不必等 500ms。
        assertEquals(
            RotaryHapticFeedback.LIMIT,
            accumulator.onScroll(-10f, pixelsPerTick, false, 100L),
        )
    }

    @Test
    fun `degenerate input never fires`() {
        val accumulator = RotaryHapticsAccumulator()

        assertEquals(
            RotaryHapticFeedback.NONE,
            accumulator.onScroll(0f, pixelsPerTick, true, 0L),
        )
        assertEquals(
            RotaryHapticFeedback.NONE,
            accumulator.onScroll(Float.NaN, pixelsPerTick, true, 10L),
        )
        assertEquals(
            RotaryHapticFeedback.NONE,
            accumulator.onScroll(Float.POSITIVE_INFINITY, pixelsPerTick, true, 20L),
        )
        assertEquals(
            RotaryHapticFeedback.NONE,
            accumulator.onScroll(45f, 0f, true, 30L),
        )
        assertEquals(
            RotaryHapticFeedback.NONE,
            accumulator.onScroll(45f, Float.NaN, true, 40L),
        )
    }
}
