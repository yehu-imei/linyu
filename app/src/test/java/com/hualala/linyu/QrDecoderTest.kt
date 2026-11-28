package com.hualala.linyu

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二维码解码的回归测试 —— 纯 JVM，不需要设备。
 *
 * 换掉 ML Kit 之后，「能不能解出码」这件事完全由 `QrDecoder` 负责，
 * 所以这里用 ZXing 自己生成的二维码做往返验证：
 * 生成 → 转成亮度数组 → 解码 → 比对原文。
 *
 * ⚠️ 这里验证的是**算法链路**，不能代替真机上的识别率（弱光/斜角/反光）。
 */
class QrDecoderTest {

    private val sn = "AABBCCDDEEFF"
    private val payload = "linyu://device?snCode=$sn"
    private val deviceCode = "klcxkj-water,M,aa:bb:cc:dd:ee:ff"

    /** 用 ZXing 生成二维码，转成「黑=0 / 白=255」的亮度数组（与相机 Y 平面同语义） */
    private fun qrLuminance(text: String = payload, size: Int = 320): Pair<ByteArray, Int> {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bytes = ByteArray(size * size) { 255.toByte() }
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (matrix.get(x, y)) bytes[y * size + x] = 0
            }
        }
        return bytes to size
    }

    private fun placeQr(target: ByteArray, stride: Int, left: Int, top: Int, size: Int, text: String) {
        val (qr, _) = qrLuminance(text, size)
        for (row in 0 until size) {
            qr.copyInto(target, (top + row) * stride + left, row * size, (row + 1) * size)
        }
    }

    private fun twoCodes(): ByteArray = ByteArray(640 * 320) { 255.toByte() }.apply {
        placeQr(this, stride = 640, left = 0, top = 0, size = 320, text = payload)
        placeQr(this, stride = 640, left = 340, top = 16, size = 280, text = deviceCode)
    }

    @Test
    fun `decodes a plain qr code`() {
        val (bytes, size) = qrLuminance()
        assertEquals(payload, QrDecoder.decodeLuminance(bytes, size, size, tryHarder = true))
    }

    /** 深色背景 + 浅色码（打印反色、或屏幕夜间模式）也要能解 */
    @Test
    fun `decodes an inverted qr code`() {
        val (bytes, size) = qrLuminance()
        val inverted = ByteArray(bytes.size) { i -> (255 - (bytes[i].toInt() and 0xFF)).toByte() }
        assertEquals(payload, QrDecoder.decodeLuminance(inverted, size, size, tryHarder = true))
    }

    @Test
    fun `blank frame returns null`() {
        val blank = ByteArray(200 * 200) { 255.toByte() }
        assertNull(QrDecoder.decodeLuminance(blank, 200, 200, tryHarder = false))
    }

    @Test
    fun `too small buffer returns null instead of crashing`() {
        assertNull(QrDecoder.decodeLuminance(ByteArray(10), 100, 100, tryHarder = false))
    }

    @Test
    fun `camera ignores an unrelated code even if it contains a serial`() {
        val (plane, side) = qrLuminance("coupon,$sn")
        assertNull(QrDecoder.decodeCameraFrame(plane, side, side, side, 0))
    }

    @Test
    fun `camera finds the device qr alongside another qr`() {
        assertEquals(sn, QrDecoder.decodeCameraFrame(twoCodes(), 640, 320, 640, 0))
    }

    @Test
    fun `album finds inverse device qr alongside another inverse qr`() {
        val plane = twoCodes()
        val pixels = IntArray(plane.size) { index ->
            if (plane[index] == 0.toByte()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        assertEquals(sn, QrDecoder.decodePixels(pixels, 640, 320))
    }

    @Test
    fun `camera decodes a rotated padded plane without trailing row padding`() {
        val width = 400
        val height = 300
        val stride = 416
        val frame = ByteArray(stride * (height - 1) + width) { 255.toByte() }
        placeQr(frame, stride, left = 58, top = 10, size = 280, text = deviceCode)
        assertEquals(sn, QrDecoder.decodeCameraFrame(frame, width, height, stride, 0))
        assertEquals(sn, QrDecoder.decodeCameraFrame(frame, width, height, stride, 90))
        assertEquals(sn, QrDecoder.decodeCameraFrame(frame, width, height, stride, 180))
        assertEquals(sn, QrDecoder.decodeCameraFrame(frame, width, height, stride, 270))
        assertNull(QrDecoder.decodeCameraFrame(frame.copyOf(frame.size - 1), width, height, stride, 90))
    }

    /** 旋转 90° 会把宽高互换 */
    @Test
    fun `rotate 90 swaps width and height`() {
        val rotated = QrDecoder.rotate(ByteArray(2 * 3), width = 2, height = 3, degrees = 90)
        assertEquals(3, rotated.width)
        assertEquals(2, rotated.height)
        assertEquals(2 * 3, rotated.bytes.size)
    }

    /** 连续转两次 90° 必须等于转 180° */
    @Test
    fun `rotate 90 twice equals 180`() {
        val w = 4
        val h = 3
        val src = ByteArray(w * h) { it.toByte() }
        val twice = QrDecoder.rotate(
            QrDecoder.rotate(src, w, h, 90).let { it.bytes },
            width = h, height = w, degrees = 90
        )
        val direct = QrDecoder.rotate(src, w, h, 180)
        assertEquals(direct.width, twice.width)
        assertEquals(direct.height, twice.height)
        assertTrue(direct.bytes.contentEquals(twice.bytes))
    }

    /** 360° 等价于不转 */
    @Test
    fun `rotate 360 is identity`() {
        val src = ByteArray(6) { it.toByte() }
        val rotated = QrDecoder.rotate(src, width = 3, height = 2, degrees = 360)
        assertSame(src, rotated.bytes)
    }

    /**
     * 相机给的是横屏帧，`rotationDegrees=90`；把「横着生成的码」旋转后仍然要能解出来
     * （这条覆盖 `decodeCameraFrame` 里「先纠正方向再解码」的意图）。
     */
    @Test
    fun `rotated frame still decodes after rotation`() {
        val (bytes, size) = qrLuminance()
        val rotated = QrDecoder.rotate(bytes, size, size, 90)
        assertEquals(
            payload,
            QrDecoder.decodeLuminance(rotated.bytes, rotated.width, rotated.height, tryHarder = true)
        )
    }
}
