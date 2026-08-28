package site.whitezaak.wearpod.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PubDateNormalizerTest {

    @Test
    fun `already canonical date passes through`() {
        assertEquals("20 Aug 2026", PubDateNormalizer.toCanonicalDate("20 Aug 2026"))
    }

    @Test
    fun `RFC 1123 GMT parses`() {
        assertEquals("20 Aug 2026", PubDateNormalizer.toCanonicalDate("Thu, 20 Aug 2026 12:00:00 GMT"))
    }

    @Test
    fun `RFC 1123 with numeric offset parses`() {
        assertEquals("20 Aug 2026", PubDateNormalizer.toCanonicalDate("Thu, 20 Aug 2026 20:00:00 +0800"))
    }

    @Test
    fun `single digit day parses`() {
        assertEquals("01 Jan 2026", PubDateNormalizer.toCanonicalDate("Thu, 1 Jan 2026 00:00:00 +0000"))
    }

    @Test
    fun `ISO offset datetime parses`() {
        assertEquals("20 Aug 2026", PubDateNormalizer.toCanonicalDate("2026-08-20T12:00:00+08:00"))
    }

    @Test
    fun `trailing parenthetical zone stripped`() {
        assertEquals("20 Aug 2026", PubDateNormalizer.toCanonicalDate("Thu, 20 Aug 2026 12:00:00 GMT (UTC)"))
    }

    @Test
    fun `blank returns null`() {
        assertNull(PubDateNormalizer.toCanonicalDate(""))
        assertNull(PubDateNormalizer.toCanonicalDate("   "))
    }

    @Test
    fun `garbage returns null`() {
        assertNull(PubDateNormalizer.toCanonicalDate("not-a-date"))
        assertNull(PubDateNormalizer.toCanonicalDate("2026年8月20日"))
    }

    @Test
    fun `UTC equivalent date preserved`() {
        // 归一化基于 UTC 日期：23:00 UTC 仍是 20 号
        assertEquals("20 Aug 2026", PubDateNormalizer.toCanonicalDate("Thu, 20 Aug 2026 23:00:00 +0000"))
    }
}
