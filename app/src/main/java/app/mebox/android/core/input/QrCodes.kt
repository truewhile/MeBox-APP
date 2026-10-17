package app.mebox.android.core.input

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 把手机输入页地址编码成二维码。
 *
 * 电视端只显示 bitmap，不依赖 WebView 或摄像头；ZXing 的 core 模块只有
 * 纯 Java 编码器，不会把扫码/相机权限带进 APK。
 */
object QrCodes {

    fun bitmap(content: String, sizePx: Int = 512): Bitmap? {
        if (content.isBlank() || sizePx <= 0) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1,
            )
            val matrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                hints,
            )
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val row = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            }
        }.getOrNull()
    }
}
