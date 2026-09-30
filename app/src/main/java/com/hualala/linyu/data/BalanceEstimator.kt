package com.hualala.linyu.data

import com.hualala.linyu.model.BillItem
import com.hualala.linyu.utils.MoneyFormat
import com.hualala.linyu.utils.PrefsHelper

/**
 * 账户余额。
 *
 * **余额有三个来源，按优先级取第一个拿得到的：**
 *
 * | 优先级 | 来源 | 接口 | 什么学校会有 |
 * |---|---|---|---|
 * | 1 | **一卡通**（校园卡） | `GET /settlement/campus/userInfo` | 接入了校园卡免密支付 |
 * | 2 | **趣智校园钱包** | `GET /account/wallet` | 几乎所有学校都有 |
 * | 3 | 本地估算 | — | 前两者都拿不到的兜底 |
 *
 * ## 为什么要有第 2 档
 *
 * 原先只有「一卡通」和「本地估算」两档，于是没接一卡通的学校
 * （如某工商职业学院，`/settlement/campus/userInfo` 返回
 * `errorCode 12「无效支付配置」`）会一路掉到**用户手填的估算值**上——
 * 而钱包余额明明就在手边：`/account/wallet` 每 25 秒被挤号心跳拉一次，
 * 响应里的 `money` 就是余额，只是数字从来没被显示过。
 *
 * 顺序上**一卡通优先**，是为了不动已有学校的行为：项目 v3.0.0 接一卡通
 * 就是为了拿真实余额，作者学校走的就是这条。钱包只在一卡通拿不到时补位。
 *
 * 本地估算 = 用户手动填写的初始余额 − 填写时刻之后产生的消费。
 * 这段逻辑原先在 [com.hualala.linyu.ui.MainScreen] 和 [com.hualala.linyu.ui.WalletScreen]
 * 里各写了一遍，桌面小组件是第三份——而小组件那份当时漏了减法，直接显示没动过的初始值，
 * 于是「App 里余额变了、桌面上不变」。抽到这里，三处共用一份，不会再各算各的。
 */
object BalanceEstimator {

    /**
     * 真实余额：一卡通优先，其次趣智校园钱包；都拿不到返回 null。
     *
     * 从 **Prefs** 读而不是从 ViewModel：小组件是另一个进程入口，
     * 它读不到 ViewModel 的内存状态，只能读持久化的那份。
     */
    fun realBalance(): Double? =
        PrefsHelper.campusBalance.toDoubleOrNull()
            ?: PrefsHelper.walletBalance.toDoubleOrNull()

    /** 有没有真实余额可用（决定界面要不要标注「估算」） */
    fun hasRealBalance(): Boolean = realBalance() != null

    /** 是否有**一卡通**余额（来自 `/settlement/campus/userInfo`） */
    fun hasCampusBalance(): Boolean = PrefsHelper.campusBalance.toDoubleOrNull() != null

    /** 是否有**趣智校园钱包**余额（来自 `/account/wallet`） */
    fun hasWalletBalance(): Boolean = PrefsHelper.walletBalance.toDoubleOrNull() != null

    /**
     * 余额标题——按**钱在哪**来写，避免张冠李戴：
     * - 一卡通余额 → 「一卡通余额」
     * - 趣智校园钱包余额 → 「趣智校园余额」
     * - 只有本地估算 → 「余额」
     */
    fun title(): String = when {
        hasCampusBalance() -> "一卡通余额"
        hasWalletBalance() -> "趣智校园余额"
        else -> "余额"
    }

    /** 账单里的日期字符串 → 毫秒时间戳；解析不了返回 0，会被当成「早于填余额的时刻」而不计入 */
    fun billTimeMs(consumeDate: String): Long = try {
        java.time.LocalDateTime.parse(consumeDate.replace(" ", "T"))
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    /** App 内用：直接吃接口返回的账单。有真实余额就直接返回它 */
    fun estimate(bills: List<BillItem>): Double = estimateFromEntries(
        bills.map {
            billTimeMs(it.consumeBillDTO.consumeDate) to
                (it.consumeBillDTO.consumeMoney.toDoubleOrNull() ?: 0.0)
        }
    )

    /**
     * 小组件用：吃本地快照折算出来的 (时间, 金额)。
     *
     * 这里必须**收口在同一份实现**——小组件读的是自己缓存的账单，
     * 字段是 Gson 反序列化出来的（可能缺字段），所以时间/金额缺失时按 0 处理，
     * 结果是「这笔不计入消费」，金额不会凭空变多。
     *
     * 真实余额在 [estimate] 里就返回了，走到这儿说明拿不到，才做本地推算。
     */
    fun estimateFromEntries(entries: List<Pair<Long, Double>>): Double {
        realBalance()?.let { return it }
        val initial = PrefsHelper.manualBalance
        if (initial.isEmpty()) return 0.0
        val since = PrefsHelper.manualBalanceTime
        val spent = entries.filter { it.first > since }.sumOf { it.second }
        return (initial.toDoubleOrNull() ?: 0.0) - spent
    }

    /**
     * 还没填过初始余额、也拿不到真实余额时，界面上用「—」而不是显示 0，
     * 免得被当成「余额为 0」。
     */
    fun format(balance: Double): String =
        if (!hasRealBalance() && PrefsHelper.manualBalance.isEmpty()) "¥ —"
        else "¥ ${formatMoney(balance)}"

    /**
     * 余额数字：默认保留 3 位小数；**末位为 0 时收敛为 2 位**。
     *
     * 见 [com.hualala.linyu.utils.MoneyFormat]——全项目金额统一走那份实现，
     * 不再各自 `%.2f`（那会把 0.025 显示成 0.03）。
     */
    fun formatMoney(value: Double): String = MoneyFormat.format(value)
}
