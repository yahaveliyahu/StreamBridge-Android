import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import android.renderscript.*

@Suppress("DEPRECATION")
class YuvToRgbConverter(context: Context) {
    private val rs = RenderScript.create(context)
    private val scriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
    private var yuvBuffer: ByteArray? = null
    private var inputAllocation: Allocation? = null
    private var outputAllocation: Allocation? = null

    fun yuvToRgb(image: ImageProxy, output: Bitmap) {
        val yuv = imageToNv21(image)
        if (yuvBuffer == null || yuvBuffer?.size != yuv.size) {
            yuvBuffer = ByteArray(yuv.size)
        }
        System.arraycopy(yuv, 0, yuvBuffer!!, 0, yuv.size)

        val yuvType = Type.Builder(rs, Element.U8(rs)).setX(yuv.size)
        inputAllocation = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT)

        val rgbaType = Type.Builder(rs, Element.RGBA_8888(rs)).setX(output.width).setY(output.height)
        outputAllocation = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT)

        inputAllocation!!.copyFrom(yuvBuffer)
        scriptYuvToRgb.setInput(inputAllocation)
        scriptYuvToRgb.forEach(outputAllocation)
        outputAllocation!!.copyTo(output)
    }
    private fun imageToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)
        var outputIndex = 0

        // --------- Copy Y (luma) ----------
        val yRow = ByteArray(yRowStride)
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(yRow, 0, yRowStride)
            var col = 0
            while (col < width) {
                nv21[outputIndex++] = yRow[col * yPixelStride]
                col++
            }
        }

        // --------- Copy UV (chroma) as VU for NV21 ----------
        // Chroma planes are half resolution
        val chromaHeight = height / 2
        val chromaWidth = width / 2

        val uRow = ByteArray(uRowStride)
        val vRow = ByteArray(vRowStride)

        for (row in 0 until chromaHeight) {
            uBuffer.position(row * uRowStride)
            vBuffer.position(row * vRowStride)

            uBuffer.get(uRow, 0, uRowStride)
            vBuffer.get(vRow, 0, vRowStride)

            var col = 0
            while (col < chromaWidth) {
                val u = uRow[col * uPixelStride]
                val v = vRow[col * vPixelStride]

                // NV21 = V then U
                nv21[outputIndex++] = v
                nv21[outputIndex++] = u
                col++
            }
        }

        return nv21
    }


//    private fun imageToNv21(image: ImageProxy): ByteArray {
//        val yBuffer = image.planes[0].buffer
//        val uBuffer = image.planes[1].buffer
//        val vBuffer = image.planes[2].buffer
//
//        val ySize = yBuffer.remaining()
//        val uSize = uBuffer.remaining()
//        val vSize = vBuffer.remaining()
//
//        val nv21 = ByteArray(ySize + uSize + vSize)
//
//        yBuffer.get(nv21, 0, ySize)
//        // NV21 expects VU
//        vBuffer.get(nv21, ySize, vSize)
//        uBuffer.get(nv21, ySize + vSize, uSize)
//
//        return nv21
//    }
}
