package site.whitezaak.wearpod.offline

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Test

class QrCodeGeneratorTest {

    @Test
    fun `page url payload is a plain http url`() {
        assertEquals("http://192.168.43.1:8080/", QrPayloads.pageUrl("192.168.43.1", 8080))
    }

    @Test
    fun `wifi payload without passphrase uses nopass`() {
        assertEquals("WIFI:T:nopass;S:WearPod;;", QrPayloads.wifi("WearPod", null))
        assertEquals("WIFI:T:nopass;S:WearPod;;", QrPayloads.wifi("WearPod", ""))
    }

    @Test
    fun `wifi payload escapes reserved characters`() {
        assertEquals("a\\;b", QrPayloads.escape("a;b"))
        assertEquals("a\\:b", QrPayloads.escape("a:b"))
        assertEquals("a\\,b", QrPayloads.escape("a,b"))
        assertEquals("a\\\\b", QrPayloads.escape("a\\b"))
        assertEquals("a\\\"b", QrPayloads.escape("a\"b"))

        val payload = QrPayloads.wifi("WearPod;1", "p:a,ss")
        assertEquals("WIFI:T:WPA;S:WearPod\\;1;P:p\\:a\\,ss;;", payload)
    }

    @Test
    fun `matrix decodes back to the original payload`() {
        val payload = QrPayloads.wifi("WearPod;1", "p:a,ss")
        val matrix = QrCodeGenerator.matrix(payload, 384)

        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val decoded = MultiFormatReader()
            .decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels))))
            .text

        assertEquals(payload, decoded)
    }

    @Test
    fun `matrix honours the requested size`() {
        val matrix = QrCodeGenerator.matrix("http://192.168.43.1:8080/", 256)
        assertEquals(256, matrix.width)
        assertEquals(256, matrix.height)
    }
}
