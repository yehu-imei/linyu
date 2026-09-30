package com.hualala.linyu.model

import com.google.gson.annotations.SerializedName

/**
 * 蓝牙水表相关的数据模型。
 *
 * ⚠️ 字段名一律**可空 + 带默认值**：Gson 反序列化绕过 Kotlin 的非空约定，
 * 声明成非空会在服务端少给字段时 NPE（这个坑在 `LoginModels.kt` 里已经踩过一次）。
 */

/**
 * `/order/downRate/bluetooth/rateOrder` 的 `data`。
 *
 * 这是**开阀流程的第一步**：服务端算出费率，把要写进设备的费率包给我们。
 */
data class BleDownRateData(
    /**
     * **费率包**（HEX 字符串）——蓝牙开阀的核心载荷。
     *
     * 必须原样 hex 解码后写进设备；解析失败或为空时**绝不能继续开阀**
     * （写进去的东西不对，可能多扣钱或损坏设备）。
     */
    val downData: String? = null,

    /** 费率（服务端口径，仅用于显示/日志） */
    val perMoney: String? = null,

    /**
     * 结算用的消费时间戳。
     *
     * ⚠️ 失败上报 [com.hualala.linyu.api.QzxyService.bluetoothFailOrder] 要的就是它，
     * 所以**必须在开阀成功时就存下来**——等发现要上报时再找就来不及了。
     */
    val consumeDate: String? = null,

    /** 订单号（蓝牙通道的 orderNo 由服务端在这里给出） */
    val orderNo: String? = null,

    val liquidOrderNo: String? = null,

    /** 费率明细 */
    val rate: String? = null
)

/**
 * `/order/upload/bluetooth/data` 的 `data` —— 结算结果。
 */
data class BleUploadData(
    /** 设备域加密数据，本流程一般用不到 */
    val clData: String? = null,

    /**
     * 本次消费金额。
     *
     * ⚠️ 服务端在不同接口/学校里用过 `upMoney` 和 `consumeMoney` 两个名字，
     * 所以两个都接（`alternate`），别只认一个。
     *
     * ⚠️ **单位未确认**：蓝牙通道这条链路的金额口径没有实测样本，
     * 显示前请先跑一次真实结算，用账单列表交叉验证——`consumeOrder/result`
     * 那个接口的"厘 vs 元"坑就是这么来的。
     */
    @SerializedName(value = "upMoney", alternate = ["consumeMoney"])
    val upMoney: Int? = null,

    /** 结算后剩余的预扣额（`preDeductMoneyAfter` 是它在本流程里的名字） */
    @SerializedName(value = "upLeadMoney", alternate = ["preDeductMoneyAfter"])
    val upLeadMoney: Int? = null,

    /** 预扣金额 */
    @SerializedName(value = "perMoney", alternate = ["preDeductMoney"])
    val perMoney: Int? = null,

    /** 消费时间 */
    @SerializedName(value = "fishTime", alternate = ["consumeTime"])
    val fishTime: String? = null
)
