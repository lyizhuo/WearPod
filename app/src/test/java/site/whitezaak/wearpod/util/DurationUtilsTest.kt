package site.whitezaak.wearpod.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationUtilsTest {

    @Test
    fun `HH MM SS parses correctly`() {
        assertEquals(3_723_000L, DurationUtils.parseDurationToMs("01:02:03"))
    }

    @Test
    fun `MM SS parses correctly`() {
        assertEquals(123_000L, DurationUtils.parseDurationToMs("02:03"))
    }

    @Test
    fun `plain seconds parses correctly`() {
        assertEquals(3_600_000L, DurationUtils.parseDurationToMs("3600"))
    }

    @Test
    fun `empty and blank return zero`() {
        assertEquals(0L, DurationUtils.parseDurationToMs(""))
        assertEquals(0L, DurationUtils.parseDurationToMs("   "))
        assertEquals(0L, DurationUtils.parseDurationToMs(null))
    }

    @Test
    fun `non numeric input returns zero`() {
        assertEquals(0L, DurationUtils.parseDurationToMs("abc"))
        assertEquals(0L, DurationUtils.parseDurationToMs("12:ab"))
    }

    @Test
    fun `more than three segments returns zero`() {
        assertEquals(0L, DurationUtils.parseDurationToMs("01:02:03:04"))
    }

    @Test
    fun `negative seconds coerced to zero`() {
        assertEquals(0L, DurationUtils.parseDurationToMs("-100"))
    }

    @Test
    fun `hours only two digit format`() {
        assertEquals(3_661_000L, DurationUtils.parseDurationToMs("1:01:01"))
    }
}
