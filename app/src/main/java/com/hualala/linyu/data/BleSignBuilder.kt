package com.hualala.linyu.data

import com.hualala.linyu.utils.KlcxkjSigner

/**
 * 蓝牙水表通道的**签名构造**。
 *
 * ## 签名怎么算出来的
 *
 * 三步：
 *
 * 1. 把参与签名的字段按 **key 的字典序升序** 拼成 `key1value1key2value2…`
 *    （只拼 key 和 value，中间无分隔符；value 为 null 时**整个字段跳过**）
 * 2. 交给 [KlcxkjSigner] 做嵌套 MD5（内层 `raw&key=sessionKey` + 外层固定 key）
 * 3. 得到 32 位小写 hex 的签名
 *
 * 第 2 步是**纯 Kotlin** 实现（算法见 [KlcxkjSigner]，已用 12 组样本交叉验证），
 * 不依赖任何 native 库。
 *
 * ## ⚠️ 2026-09-29 定案：170「签名错误」的根因是 **xfData 大小写不一致**
 *
 * 签名时 `xfData` 转了小写参与 MD5，但上传请求体里的 `xfData` 字段传了**大写**原值
 * （`BleFrame.toHex` 返回大写），服务端按收到的 xfData 重算签名对不上 → 170。
 * 修复：签名与上传字段统一用小写（`BleFrame.toHex(payload).lowercase()`）。
 * 与令牌、算法、payload/整包格式、遗留帧误吃全无关。
 *
 * ## ⚠️ 各接口的签名字段是**不一样**的
 *
 * 这不是随便定的，是协议规定，写错就会被服务端判为签名无效：
 *
 * | 接口 | 参与签名的字段 |
 * |---|---|
 * | [bluetoothRateOrder][signRateOrder] | `telephone`、`deviceId`、`xfModel`、`randomNumber` |
 * | [bluetoothUploadData][signUpload] | `loginCode`、`telephone`、`xfData` |
 * | [bluetoothFailOrder][signFail] | `consumeDate`、`loginCode`、`telephone` |
 */
object BleSignBuilder {

    /**
     * key 升序拼接成签名原文。
     *
     * ⚠️ `value == null` 时**跳过整个字段**（连 key 一起不拼），
     * 不是当成空串——这两者得到的原文不同，签名也就不同。
     */
    private fun rawOf(params: Map<String, String?>): String =
        params.keys.sorted().joinToString("") { k -> k + (params[k] ?: return@joinToString "") }

    /**
     * `macType` 参数：两个字节的十六进制拼接（**小写**，各补足 2 位）。
     *
     * 第一个字节来自设备状态帧的 `p[19]`（协议版本），第二个是 `a1`。
     */
    fun macType(type: Int, a1: Int): String =
        String.format("%02x%02x", type and 0xFF, a1 and 0xFF)

    /**
     * 下费率（开阀第一步）的签名。
     *
     * @param randomNumber 从**设备状态帧**里取到的 8 位 hex（不是本地生成的）
     */
    fun signRateOrder(
        loginCode: String,
        telephone: String,
        deviceId: String,
        xfModel: String,
        randomNumber: String
    ): String? = KlcxkjSigner.signParams(
        loginCode,
        rawOf(
            mapOf(
                "telephone" to telephone,
                "deviceId" to deviceId,
                "xfModel" to xfModel,
                "randomNumber" to randomNumber.lowercase()
            )
        )
    )

    /**
     * 上传消费数据（结算）的签名。
     *
     * @param xfData 从设备采集到的消费数据（hex，**转小写**后参与签名）
     */
    fun signUpload(
        loginCode: String,
        telephone: String,
        xfData: String
    ): String? = KlcxkjSigner.signParams(
        loginCode,
        rawOf(
            mapOf(
                "loginCode" to loginCode,
                "telephone" to telephone,
                "xfData" to xfData.lowercase()
            )
        )
    )

    /**
     * 上报失败订单的签名。
     *
     * @param consumeDate 来自下费率响应，**开阀成功时就要存下来**
     */
    fun signFail(
        loginCode: String,
        telephone: String,
        consumeDate: String
    ): String? = KlcxkjSigner.signParams(
        loginCode,
        rawOf(
            mapOf(
                "consumeDate" to consumeDate,
                "loginCode" to loginCode,
                "telephone" to telephone
            )
        )
    )
}
