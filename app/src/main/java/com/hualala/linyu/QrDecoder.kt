package com.hualala.linyu

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader

/**
 * 使用 ZXing 解码设备贴纸。相机帧只读取亮度，相册图片允许 ZXing 做更细致的搜索。
 */
object QrDecoder {
    private const val DEVICE_PREFIX = "KLCXKJ-Water"
    private val SERIAL = Regex("[0-9A-F]{12}")
    private val FORMATS = listOf(
        BarcodeFormat.QR_CODE,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.CODE_128,
        BarcodeFormat.CODE_39
    )

    internal fun parseSnCode(raw: String?): String? = raw
        ?.substringAfterLast(',')
        ?.trim()
        ?.replace(":", "")
        ?.uppercase()
        ?.takeIf(SERIAL::matches)

    private fun matchDeviceCode(raw: String): String? =
        if (raw.contains(DEVICE_PREFIX, ignoreCase = true)) parseSnCode(raw) else null

    /** 解相机 Y 平面，返回首个符合设备码规则的序列号。 */
    fun decodeCameraFrame(
        y: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        rotationDegrees: Int
    ): String? {
        if (width <= 0 || height <= 0 || rowStride < width ||
            (height - 1L) * rowStride + width > y.size
        ) return null
        // 旋转时直接读带填充的原始行，避免先复制一份紧凑图。
        val frame = rotate(y, width, height, rotationDegrees, rowStride)
        return decode(frame.toLuminanceSource(), tryHarder = false, accept = ::matchDeviceCode)
    }

    /** 解已根据 EXIF 调整方向的相册图。 */
    fun decodeBitmap(bitmap: Bitmap): String? {
        val maxSide = 1600
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else bitmap
        try {
            val width = scaled.width
            val height = scaled.height
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            return decodePixels(pixels, width, height)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    internal fun decodePixels(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || width.toLong() * height > pixels.size) return null
        return decode(RGBLuminanceSource(width, height, pixels), tryHarder = true, accept = ::matchDeviceCode)
    }

    internal fun decodeLuminance(bytes: ByteArray, width: Int, height: Int, tryHarder: Boolean): String? {
        if (width <= 0 || height <= 0 || width.toLong() * height > bytes.size) return null
        return decode(Rotated(bytes, width, height).toLuminanceSource(), tryHarder) { it }
    }

    private fun decode(
        source: LuminanceSource,
        tryHarder: Boolean,
        accept: (String) -> String?
    ): String? {
        val hints = mutableMapOf<DecodeHintType, Any>(DecodeHintType.POSSIBLE_FORMATS to FORMATS)
        if (tryHarder) hints[DecodeHintType.TRY_HARDER] = true
        val reader = MultiFormatReader().apply { setHints(hints) }
        try {
            val normal = BinaryBitmap(HybridBinarizer(source))
            val first = decodeOrNull { reader.decodeWithState(normal) }
            first?.text?.let(accept)?.let { return it }

            val inverted = BinaryBitmap(HybridBinarizer(source.invert()))
            val firstInverted = decodeOrNull { reader.decodeWithState(inverted) }
            firstInverted?.text?.let(accept)?.let { return it }

            // 普通扫描可能把不同二维码的定位点混在一起。多码解码复用已二值化的图。
            val qrReader = QRCodeMultiReader()
            for (image in listOf(normal, inverted)) {
                decodeOrNull { qrReader.decodeMultiple(image, hints) }
                    ?.firstNotNullOfOrNull { accept(it.text) }
                    ?.let { return it }
            }

            // 通用多码解码需要反复裁图，仅在已有可解码但不匹配的候选时再用。
            if (first == null && firstInverted == null) return null
            val multipleReader = GenericMultipleBarcodeReader(reader)
            fun otherCandidate(image: BinaryBitmap, initial: Result?): String? {
                if (initial == null) return null
                return decodeOrNull { multipleReader.decodeMultiple(image, hints) }
                    ?.firstNotNullOfOrNull { accept(it.text) }
            }
            return otherCandidate(normal, first) ?: otherCandidate(inverted, firstInverted)
        } finally {
            reader.reset()
        }
    }

    private inline fun <T> decodeOrNull(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        // 单个画面无可解码结果，不中断后续候选和帧。
        null
    }

    /** 顺时针旋转亮度数组（0/90/180/270）。无需旋转时保留原始数组。 */
    internal fun rotate(
        bytes: ByteArray,
        width: Int,
        height: Int,
        degrees: Int,
        rowStride: Int = width
    ): Rotated {
        val deg = ((degrees % 360) + 360) % 360
        if (deg != 90 && deg != 180 && deg != 270) return Rotated(bytes, width, height, rowStride)
        val out = ByteArray(width * height)
        when (deg) {
            90 -> {
                for (srcY in 0 until height) {
                    val start = srcY * rowStride
                    for (srcX in 0 until width) {
                        out[srcX * height + height - 1 - srcY] = bytes[start + srcX]
                    }
                }
                return Rotated(out, height, width)
            }
            180 -> {
                for (srcY in 0 until height) {
                    val start = srcY * rowStride
                    val destination = (height - srcY) * width - 1
                    for (srcX in 0 until width) {
                        out[destination - srcX] = bytes[start + srcX]
                    }
                }
                return Rotated(out, width, height)
            }
            270 -> {
                for (srcY in 0 until height) {
                    val start = srcY * rowStride
                    for (srcX in 0 until width) {
                        out[(width - 1 - srcX) * height + srcY] = bytes[start + srcX]
                    }
                }
                return Rotated(out, height, width)
            }
        }
        return Rotated(bytes, width, height, rowStride)
    }

    internal class Rotated(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        private val rowStride: Int = width
    ) {
        fun toLuminanceSource(): LuminanceSource =
            PlanarYUVLuminanceSource(bytes, rowStride, height, 0, 0, width, height, false)
    }
}
