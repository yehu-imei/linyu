package com.hualala.linyu.utils

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 凯路设备域 AES 加解密——**纯 Kotlin 实现，不再依赖 `libklcxkjencry.so`**。
 *
 * ## 来源
 *
 * 逆向自 `libklcxkjencry.so`（等价实现参考 Funnyass_school 的 `KcxCrypto`），
 * 只覆盖本项目实际用到的接口：签名已由 [KlcxkjSigner] 承担，这里只管
 * **设备域 AES 解密**（结算写回 `clData`）。
 *
 * ## AES 参数（已逆向确认）
 *
 * - 算法：AES-128-ECB，密钥 `"20210118klcx@002"`
 * - 密文：标准 Base64（`=` 填充，无换行）
 * - 加密填充：PKCS#7；解密兼容 PKCS#7 与零填充（末字节为 0 按 C 字符串截断）
 *
 * ## 为什么必须保留这个能力
 *
 * 结算写回（`0x86`）的数据来自服务端 `clData` 的解密。缺了它，设备侧的离线
 * 记录清不掉，设备会停在 `state=3`（有遗留数据），**下次开阀报「设备正在使用中」**。
 * 早期版本误以为这一步「可选」，把它降级成恒 null，结果设备卡死无法再开阀。
 * 纯 Kotlin 实现后，既去掉了第三方 `.so`，又保住了完整结算闭环。
 */
object KlcxkjCrypto {

    private const val AES_KEY = "20210118klcx@002"

    /** `.so` 已移除，改用纯 Kotlin 实现，恒为 true */
    val isAvailable: Boolean get() = true

    /** 失败描述；纯 Kotlin 实现下恒为 null */
    val failureReason: String? get() = null

    /**
     * AES 解密（设备域 `clData`）。
     *
     * @return 解密后的明文；失败返回 null。
     *   ⚠️ 明文格式是 `<前缀>-<dataHex>`，取 `-` 之后那段才是要写回设备的 0x86 数据。
     */
    fun decryptByAes(cipher: String): String? {
        if (cipher.isBlank()) return null
        return try {
            val decoded = base64Decode(cipher)
            // native 行为：长度不是 16 倍数时解密直接失败，去填充作用在密文上。
            val decrypted = if (decoded.size % 16 == 0 && decoded.isNotEmpty()) {
                aesEcbDecrypt(decoded) ?: decoded
            } else decoded
            stripPaddingToText(decrypted).takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            AppLogger.e("AES 解密失败", t)
            null
        }
    }

    // ── 内部实现 ──

    private fun aesEcbDecrypt(data: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(Charsets.US_ASCII), "AES"))
        cipher.doFinal(data)
    } catch (_: Exception) {
        null
    }

    /**
     * 去填充 + 转文本（对齐 native decryptByAES 语义）：
     * - 末字节 last 满足 1..n 时校验末尾 last 字节是否都等于 last（PKCS#7），
     *   不满足返回空串，满足则去掉；last==0 或 >n 视为无填充；
     * - 最后按 C 字符串截断（到第一个 0x00）。
     */
    private fun stripPaddingToText(decrypted: ByteArray): String {
        val n = decrypted.size
        if (n == 0) return ""
        val last = decrypted[n - 1].toInt() and 0xFF
        var effective = decrypted
        if (last in 1..n) {
            val start = n - last
            for (i in n - 1 downTo start) {
                if ((decrypted[i].toInt() and 0xFF) != last) return ""
            }
            effective = decrypted.copyOf(n - last)
        }
        val zeroIndex = effective.indexOfFirst { it.toInt() == 0 }
        val end = if (zeroIndex >= 0) zeroIndex else effective.size
        return String(effective, 0, end, Charsets.UTF_8)
    }

    // ── Base64（对齐 native 所用公开实现：标准字母表、'=' 填充、遇 '=' 或非法字符停止） ──

    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun isBase64Char(c: Int): Boolean =
        (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) ||
            c == '+'.code || c == '/'.code

    private fun base64Decode(input: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val quad = IntArray(4)
        var cnt = 0
        for (ch in input) {
            val c = ch.code
            if (c == '='.code || !isBase64Char(c)) break
            quad[cnt++] = B64_ALPHABET.indexOf(ch)
            if (cnt == 4) {
                out.write(((quad[0] shl 2) or ((quad[1] and 0x30) shr 4)) and 0xFF)
                out.write((((quad[1] and 0x0F) shl 4) or ((quad[2] and 0x3C) shr 2)) and 0xFF)
                out.write((((quad[2] and 0x03) shl 6) or quad[3]) and 0xFF)
                cnt = 0
            }
        }
        if (cnt == 2) {
            out.write(((quad[0] shl 2) or ((quad[1] and 0x30) shr 4)) and 0xFF)
        } else if (cnt == 3) {
            out.write(((quad[0] shl 2) or ((quad[1] and 0x30) shr 4)) and 0xFF)
            out.write((((quad[1] and 0x0F) shl 4) or ((quad[2] and 0x3C) shr 2)) and 0xFF)
        }
        return out.toByteArray()
    }
}
