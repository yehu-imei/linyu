package com.hualala.linyu.model

import com.google.gson.annotations.SerializedName

data class BaseResponse<T>(
    val success: Boolean,
    val data: T?,
    @SerializedName("errorCode") val errorCode: Int = 0,
    @SerializedName("errorMessage") val errorMessage: String? = null,
    val msg: String? = null
) {
    val displayMessage: String?
        get() = errorMessage ?: msg
}

data class LoginData(
    val userId: Long,
    val loginCode: String,
    val userAccount: UserAccount
)

data class UserAccount(
    val accountId: Long,
    val name: String,
    val projectId: Long,
    val accountRealMoney: Double
)

data class WalletData(
    val accountRealMoney: Double,
    val accountGivenMoney: Double,
    val money: String
)

/**
 * 账号信息（`GET /account/info`）。
 *
 * 拿**姓名和学号**最干净的接口——比 `/account/card/getBindCardInfo`
 * 少四十多个用不上的字段。
 *
 * ⚠️ 字段一律可空：学校没填的项（年级 / 班级 / 院系）服务端返回 null，
 * 而 Gson 反序列化绕过 Kotlin 非空约定，声明成非空会在取用时 NPE。
 */
data class AccountInfo(
    /** 姓名。学校没同步时可能是 null —— 这就是「姓名显示未设置」的根因 */
    val name: String? = null,
    /** 学号 */
    val idCardNumber: String? = null,
    val telephone: String? = null,
    val genderName: String? = null,
    /** 校园卡绑定状态：-1 = 未绑定 */
    val cardStatus: Int? = null,
    val cardStatusName: String? = null,
    val gradeName: String? = null,
    val className: String? = null
)

/**
 * 一卡通信息（`GET /settlement/campus/userInfo`）。
 *
 * 趣智校园把易校园的接口**代理了**，所以拿余额不需要去破易校园那套
 * HMAC-SHA256 原生签名——用趣智校园自己的 loginCode 就行。
 *
 * ⚠️ 前提是学生**签约过校园卡免密支付**：没签约时服务端不给 amount。
 */
data class CampusUserInfo(
    val studentNumber: String? = null,
    val studentName: String? = null,
    /** 一卡通余额，字符串形式（如 "16.76"） */
    val amount: String? = null,
    /** 1 = 已签约免密支付 */
    val signStatus: Int? = null
)

data class OrderStatus(
    val orderNo: String? = null,
    val state: Int? = null,
    val snCode: String? = null,
    val isOwner: Boolean = true
)

data class BillItem(
    val consumeBillDTO: BillDTO
)

data class BillDTO(
    /** 账单**序号**（7 位，如 `9564403`）。⚠️ 和 `orderNo` 不是一回事，别拿来互相比 */
    val orderId: String,
    val consumeDate: String,
    val consumeMoney: String,
    val description: String,
    /**
     * 下单时返回的 `orderNo`（20 位，如 `13202609172359167875`）——**这才是关阀用的那个**。
     *
     * 以前没解析它，`settleAmount` 只能拿 `orderId` 去比 `orderNo`，永远匹配不上，
     * 于是结束通知永远显示「无消费」。字段可空：Gson 反序列化时缺字段就是 null。
     */
    val orderNo: String? = null
) {
    /** 设备名：龙川北苑 3号楼南 320房 */
    val displayDesc: String get() {
        val name = description.substringAfter(":")
        return if (name.isNotEmpty()) DeviceInfo.formatDeviceName(name) else description
    }

    /** 是否饮水机账单 */
    val isDrinkingWater: Boolean get() =
        description.contains("饮水") || description.contains("直饮") || description.contains("冷水")

    /** 设备类型标签：饮水机 / 洗手台热水器 / 卫生间热水器 */
    val deviceTypeLabel: String get() {
        val name = description.substringAfter(":")
        return when {
            isDrinkingWater -> "饮水机"
            name.startsWith("洗手台") || description.contains("洗手台", ignoreCase = true) -> "洗手台热水器"
            else -> "卫生间热水器"
        }
    }

    /** 设备类型 emoji：饮水机 🚰 / 洗手台 🪥 / 热水器 🚿 */
    val deviceEmoji: String get() = when {
        isDrinkingWater -> "🚰"
        description.contains("洗手台", ignoreCase = true) -> "🪥"
        else -> "🚿"
    }
}

/**
 * `GET /account/useCode/new` 的返回。
 *
 * ⚠️ 字段一律可空——**没领过使用码时服务端真的返回 `"useCode": null`**（实测），
 * Gson 又会绕过 Kotlin 的非空约定把 null 塞进来，写成 `String = ""` 只是自欺欺人。
 */
data class UseCodeData(
    val useCode: String? = null,
    val useCodeStatus: Int? = null,
    /** 手机号后三位，跟 `useCode` 的末三位相同 */
    val useCodeRandom: String? = null,
    val useCodeStartTime: String? = null,
    /**
     * 今天还能不能「重新领取」使用码：`1` = 能，`0` = 不能。
     * 值为 0 时 [resetAvailabilityWarMark] 会给出原因（实测是「1天只能领取一次使用码」）。
     */
    val resetAvailability: Int? = null,
    val resetAvailabilityWarMark: String? = null
)

/** `GET /project/info/triple`——只取学校名，其余字段用不着 */
data class ProjectInfo(
    val projectId: Int? = null,
    val projectName: String? = null
)

/**
 * `/order/weixinScorePay/unPay/queryBill` 返回的未支付账单。
 *
 * ⚠️ 它是**扁平结构**，不像 `/order/query/account/bill/list` 那样套一层
 * `consumeBillDTO`——两个接口看着像，模型不通用，别想着复用 [BillItem]。
 *
 * `consumeDate` 是代扣接口唯一定位一张账单的字段，所以它是最重要的一个。
 */
data class UnpaidBill(
    val orderId: String? = null,
    val orderNo: String? = null,
    val consumeDate: String? = null,
    val consumeMoney: String? = null,
    val description: String? = null
)

/**
 * `POST /account/useCode/new/generate` 的返回：**换**出来的候选码，不是生效的码。
 *
 * 换出来的码要再调 `set` 才真正生效，3 分钟内不领取就作废。
 */
data class GenerateUseCodeResult(
    val useCode: String? = null,
    /** 本次「换一个」还剩几次。每天 20 次，从 20 开始往下扣 */
    val remainTimes: Int? = null
)

data class BillDetail(
    val orderId: String? = null,
    val consumeDate: String? = null,
    val consumeMoney: Double = 0.0,
    val description: String? = null,
    val deviceSnCode: String? = null,
    val orderNo: String? = null,
    val preDeductMoney: Double = 0.0
)

/**
 * 开阀结果查询 (/order/tcpDevice/query/downRateResult)
 *
 * 用于确认 downRate 开阀是否真正成功，同时可携带 autoDisConTime（自动关停秒数）。
 */
data class DownRateResult(
    /** 订单号 */
    val orderNo: String? = null,
    /** 自动关停时间（秒），如 600 = 10 分钟 */
    val autoDisConTime: Int? = null,
    /** 状态码，0 通常表示开阀成功 */
    val state: Int? = null,
    val result: Int? = null,
    /** 预扣金额 */
    val preDeductMoney: Double? = null,
    val preDeductMoneySend: Double? = null,
    /** 费率 */
    val rate: Double? = null,
    /** 设备序列号 */
    val snCode: String? = null
)

/**
 * 关阀结果查询 (/order/tcpDevice/closeOrder/result/query)
 *
 * 用于确认 closeOrder 是否真正执行成功。服务器对成功/失败的字段
 * 命名可能因学校而异，故用 @SerializedName 做多字段容错。
 */
data class CloseOrderResult(
    /** 订单号 */
    val orderNo: String? = null,
    /** 订单状态：1=使用中，0=已关闭（部分服务器用 state） */
    val state: Int? = null,
    /** 订单状态（部分服务器用 status） */
    val status: Int? = null,
    /** 操作结果码，0 通常表示成功 */
    val result: Int? = null,
    /** 最终消费金额（元，数字形式） */
    val consumeMoney: Double? = null,
    /** 最终消费金额（元，字符串形式，部分学校返回） */
    @SerializedName("consumeMoneyStr") val consumeMoneyStr: String? = null,
    /** 结算时间 */
    val consumeTime: String? = null,
    /** 设备序列号 */
    val deviceSnCode: String? = null
)
