package com.hualala.linyu.utils

import java.security.MessageDigest

/**
 * 蓝牙水表的**请求签名**。
 *
 * ## 算法（已完整逆出并交叉验证）
 *
 * ```
 * 内层 = MD5( raw + "&key=" + sessionKey )
 * 签名 = MD5( 内层 + "&key=sign-kailu-855c74a88b8b494187c99b08b8c9a744" )
 * ```
 *
 * - `raw` 是按 key 升序拼好的键值串（见 [com.hualala.linyu.data.BleSignBuilder]）
 * - `sessionKey` 是服务端下发的 `loginCode`
 * - 外层那串是**固定常量**
 *
 * ## 这个算法是怎么来的
 *
 * 官方把签名放在 native 库 `libklcxkjencry.so` 里，逆向花了很久：
 * 静态分析只挖到「函数里引用了 `&key=` 和 `sign-kailu=` 两个字符串」，
 * 但穷举约 18 万种拼接组合都算不出来——因为它是**嵌套 MD5**，
 * 而且 `&key=` 出现两次。
 *
 * 最后由社区同学（issue #7）从 native 里逆出完整结构，本实现用 **12 组**
 * (输入, 输出) 样本交叉验证，**全部命中**。
 *
 * ## 所以现在**不需要 `.so` 了**
 *
 * 以前必须靠 `libklcxkjencry.so` 才能算签名，代价是：
 * 包体积 +5.4MB、只支持 arm64、要给 JNI 类名加混淆保护、以及第三方二进制的合规问题。
 * 换成纯 Kotlin 之后这些全部消失。
 *
 * ⚠️ **本类纯 Kotlin，不依赖任何 native 库**。原 `libklcxkjencry.so` 已于 v3.0.4
 * 移除；AES 写回那部分由 [KlcxkjCrypto] 承担，现在同样降级为跳过
 * （不影响开阀与扣费）。
 */
object KlcxkjSigner {

    /**
     * 外层固定密钥。
     *
     * 注意它自带 `&key=` 前缀——这不是笔误，是算法的一部分。
     */
    private const val OUTER_KEY = "&key=sign-kailu-855c74a88b8b494187c99b08b8c9a744"

    /**
     * 计算签名。
     *
     * @param sessionKey 会话密钥（服务端下发的 `loginCode`）
     * @param raw        按 key 升序拼好的键值串
     * @return 32 位小写 hex 签名；参数不合法时返回 null
     */
    fun signParams(sessionKey: String, raw: String): String? {
        if (sessionKey.isEmpty()) {
            AppLogger.w("签名被拒：sessionKey 为空")
            return null
        }
        return try {
            val inner = md5Hex(raw + "&key=" + sessionKey)
            md5Hex(inner + OUTER_KEY)
        } catch (t: Throwable) {
            AppLogger.e("签名失败", t)
            null
        }
    }

    /**
     * 是否可用。
     *
     * 纯 Kotlin 实现，**恒为 true**——保留这个属性只是为了让调用方的
     * `if (!isAvailable) ...` 判断不用改（将来若又出现外部依赖，这里有地方挂）。
     */
    val isAvailable: Boolean get() = true

    /** 失败原因；纯 Kotlin 实现下恒为 null */
    val failureReason: String? get() = null

    fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
