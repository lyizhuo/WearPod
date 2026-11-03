package site.whitezaak.wearpod.offline

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/** 二维码内容构造（纯字符串处理，可单测）。 */
object QrPayloads {
    fun pageUrl(ip: String, port: Int): String = "http://$ip:$port/"
}

object QrCodeGenerator {

    private const val MARGIN_MODULES = 1

    /** 生成二维码矩阵（纯解码逻辑，可在 JVM 单测里用 MultiFormatReader 反解验证）。 */
    fun matrix(content: String, sizePx: Int): BitMatrix {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.MARGIN, MARGIN_MODULES)
        }
        return MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }

    fun bitmap(content: String, sizePx: Int): Bitmap {
        val matrix = matrix(content, sizePx)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
