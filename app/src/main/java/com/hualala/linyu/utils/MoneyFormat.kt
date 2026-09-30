package com.hualala.linyu.utils

/**
 * 金额显示的统一格式化。
 *
 * ## 为什么不能再用 `%.2f`
 *
 * 服务端金额以**厘**（0.001 元）为单位传输——蓝牙结算返回 `upMoney=25` 就是 ¥0.025，
 * 账单里也写着 `0.025`。用 `%.2f` 会把它四舍五入成 `¥0.03`，用户看到账单是 0.025、
 * 提示却写 0.03，会以为多扣了钱（2026-09-30 实测反馈）。
 *
 * 所以统一保留 **3 位小数**（精度到厘）；**末位为 0 时收敛为 2 位**——有些学校的
 * 消费精度只到分，金额常年是 `5.000`、`10.150` 这种，硬留 3 位会多一个无意义的 0。
 */
object MoneyFormat {

    /** 金额数字（不带货币符号）：`0.025` → `"0.025"`，`5.0` → `"5.0"` */
    fun format(value: Double): String {
        val s = "%.3f".format(value)
        return if (s.endsWith('0')) s.dropLast(1) else s
    }

    /** 带 ¥ 前缀：`0.025` → `"¥0.025"` */
    fun withSymbol(value: Double): String = "¥${format(value)}"

    /** 厘 → 元。服务端 `upMoney` / `preDeductMoney` 等整数字段的单位是厘 */
    fun milliToYuan(milli: Int?): Double? = milli?.let { it / 1000.0 }
}
