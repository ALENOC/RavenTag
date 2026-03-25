package io.raventag.app.ui.screens

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Generates a QR code [Bitmap] for the given [content] string using ZXing.
 *
 * The resulting bitmap uses [Bitmap.Config.RGB_565] (16-bit color, no alpha) which is sufficient
 * for a monochrome QR code and uses half the memory of ARGB_8888.
 *
 * The margin hint is set to 1 (one module unit) to minimize whitespace around the QR code.
 * Callers that display the bitmap inside a white-background container may rely on that container
 * to provide additional visual padding.
 *
 * This function is blocking (CPU-bound) and should be called on a background dispatcher:
 *
 *     val bitmap = withContext(Dispatchers.Default) { generateQr(address) }
 *
 * @param content The string to encode (e.g. a Ravencoin address).
 * @param size The output bitmap width and height in pixels. Defaults to 512x512.
 * @return A [Bitmap] containing the rendered QR code.
 */
fun generateQr(content: String, size: Int = 512): Bitmap {
    // ZXing hint: MARGIN controls the number of quiet-zone modules around the QR code.
    val hints = mapOf(EncodeHintType.MARGIN to 1)

    // Encode the content into a BitMatrix (boolean grid of dark/light modules).
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)

    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)

    // Iterate every pixel and paint it black or white based on the ZXing matrix value.
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }

    return bmp
}
