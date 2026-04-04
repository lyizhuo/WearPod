package site.whitezaak.wearpod.util

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import coil.size.Size
import coil.transform.Transformation

class BlurTransformation(
    private val context: Context,
    private val radius: Float = 10f,
    private val sampling: Float = 2f
) : Transformation {

    override val cacheKey: String = "${BlurTransformation::class.java.name}-$radius-$sampling"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val scaledWidth = (input.width / sampling).toInt().coerceAtLeast(1)
        val scaledHeight = (input.height / sampling).toInt().coerceAtLeast(1)
        
        // Return original if too small
        if (scaledWidth == 1 && scaledHeight == 1) {
            return input
        }

        val bitmap = Bitmap.createScaledBitmap(input, scaledWidth, scaledHeight, false)
        
        var rs: RenderScript? = null
        try {
            rs = RenderScript.create(context)
            val allocationInput = Allocation.createFromBitmap(
                rs, 
                bitmap, 
                Allocation.MipmapControl.MIPMAP_NONE, 
                Allocation.USAGE_SCRIPT
            )
            val allocationOutput = Allocation.createTyped(rs, allocationInput.type)
            val blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            
            blur.setRadius(radius.coerceIn(0.1f, 25f))
            blur.setInput(allocationInput)
            blur.forEach(allocationOutput)
            allocationOutput.copyTo(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            return input
        } finally {
            rs?.destroy()
        }
        
        return bitmap
    }
}
