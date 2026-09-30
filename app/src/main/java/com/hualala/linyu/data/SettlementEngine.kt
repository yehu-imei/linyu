package com.hualala.linyu.data

// 从 ShowerController.kt 拆出：结算金额的查询与回退（原文件最大的一块，约 470 行）。
// 它只依赖网络接口 + PrefsHelper + 账单模型，不碰订单/开阀状态，边界干净。

import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.closeOrderResultSafe
import com.hualala.linyu.api.closeOrderSafe
import com.hualala.linyu.api.consumeOrderResultRaw
import com.hualala.linyu.api.downRateResultSafe
import com.hualala.linyu.api.downRateSafe
import com.hualala.linyu.api.getBillListSafe
import com.hualala.linyu.api.getDeviceInfoSafe
import com.hualala.linyu.api.queryUsingSafe
import com.hualala.linyu.model.ActiveOrder
import com.hualala.linyu.model.BillItem
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.utils.AppLogger
import com.hualala.linyu.utils.PrefsHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 「这一单花了多少钱」的一次探询结果。
 *
 * 三态是必需的，`Double?` 表达不了：**「字段不存在」和「字段就是 0」必须分开**。
 * 前者是「服务端还没算完，继续等」，后者是「确实没花钱，可以收工」。
 * 混成一个 `null` 的话，开完水马上停这种零消费的单子会一直等不到金额，
 * 通知就永远停在「结算中」。
 */
private sealed interface MoneyProbe {
    /** 拿到了一个大于 0 的金额 */
    data class Found(val value: Double) : MoneyProbe

    /**
     * `consumeMoney` 字段在、值就是 0：这一单确实没花钱。
     *
     * 实测支持这个判断（09-18 15:06 那次 5 秒的启停）：接口回 `consumeMoney: 0`，
     * 同时账单列表里也**始终没有**这一单的账单——两边对得上。
     * 而中午真正用了水的 12:43 那单，账单列表里是有记录的（`0.41`）。
     *
     * ⚠️ 仍要留意一种情况：服务端**结算未完成时也许同样报 0**。
     * 所以它不单独作数——只有五轮都是 0、**且**账单列表也查不到时，才按 0 收尾
     * （见 [settleAmount]）。账单就是这里的旁证。
     */
    data object KnownZero : MoneyProbe

    /** 没拿到（网络失败 / 字段名不认识 / 还没结算）——继续等或走回退 */
    data object Unknown : MoneyProbe
}

object SettlementEngine {

    /**
     * 查出本次消费金额。
     *
     * ## 为什么几乎不用等
     *
     * 早先这里是「拉账单列表 + 自己按时间窗猜」，那套注定慢也注定不准。
     * 现在主路径是一次 [queryConsumeResult]——直接问「这一单结算了多少钱」。
     *
     * 实测（09-18 一整天、27 次调用的日志）：
     * **每一次的第 1 轮返回就是最终值，从来没有变过。**
     * 非零的例子（`17:16` 那单）第 1 轮直接给 `40`；零消费的那几单第 1 轮到第 5 轮
     * 全是 `0`。也就是说原来那 5 轮 + 3 轮的固定重试**纯属白等**：
     * 零消费的单子硬生生拖了 8.4 秒才把通知从「结算中」改成「无消费」。
     *
     * 所以现在只留**一轮确认**：拿到 0 时多问一次（万一是结算竞态），
     * 不是 0 就直接收工。见 [SETTLE_CONFIRM_DELAY_MS]。
     *
     * @param snCode 设备序列号。查到金额后会**按设备**记一笔「上次消费」给桌面小组件——
     *               必须带上，否则换设备后小组件会拿上一台的金额冒充当前这台。
     * @return 金额（元）；`0.0` = 确实没花钱；`null` = 没查出来（和 0.0 不是一回事）
     */
    suspend fun settleAmount(
        orderNo: String,
        startTimeMs: Long,
        snCode: String,
        deviceName: String = ""
    ): Double? {
        var sawDefiniteZero = false
        if (orderNo.isNotEmpty()) {
            // ① 快速探测：命中正数立刻收工。实测第 1 轮就是终值，
            //    不能让有消费的单子干等——这条路的耗时必须保持在一两秒。
            for (attempt in 0 until SETTLE_PROBE_ROUNDS) {
                when (val probe = queryConsumeResult(orderNo, snCode)) {
                    is MoneyProbe.Found -> {
                        PrefsHelper.recordConsume(snCode, probe.value)
                        PrefsHelper.clearPendingSettlement(snCode)
                        AppLogger.i("结算命中（consumeOrder/result，第 ${attempt + 1} 轮）：¥${probe.value}")
                        return probe.value
                    }
                    // 字段在、值是 0 —— 交给下面的零确认，别急着下结论
                    MoneyProbe.KnownZero -> { sawDefiniteZero = true; break }
                    MoneyProbe.Unknown -> Unit
                }
                if (attempt < SETTLE_PROBE_ROUNDS - 1) delay(SETTLE_CONFIRM_DELAY_MS)
            }

            // ② 零确认：拿到过 0 就**多观察一会儿**再认。
            //
            // 这是「偶发无消费」的正主。直答接口在结算完成前 `consumeMoney` 就是 0，
            // 和服务端真算出 0 元**长得一模一样**。只问一两轮的话，服务端稍微慢一点，
            // 每一轮都落在「还没结算」的窗口里，于是把「还没算完」当成「没花钱」。
            //
            // 代价是零消费的单子要多等几秒才出结果（约 5 秒，仍短于 v3.0.3 之前的 8.4 秒）。
            // 这个交换划算：**「明明花了钱却被告知无消费」比多等几秒严重得多**——
            // 前者用户根本不会去核对，后者只是一句「结算中」多停留一会儿。
            if (sawDefiniteZero) {
                for (attempt in 0 until SETTLE_ZERO_CONFIRM_ROUNDS) {
                    delay(SETTLE_ZERO_CONFIRM_DELAY_MS)
                    when (val probe = queryConsumeResult(orderNo, snCode)) {
                        is MoneyProbe.Found -> {
                            PrefsHelper.recordConsume(snCode, probe.value)
                            PrefsHelper.clearPendingSettlement(snCode)
                            AppLogger.i("结算命中（零确认第 ${attempt + 1} 轮）：¥${probe.value}")
                            return probe.value
                        }
                        MoneyProbe.KnownZero -> Unit
                        MoneyProbe.Unknown -> Unit
                    }
                }
            }
            AppLogger.w("consumeOrder/result 没给金额（见过明确的 0：$sawDefiniteZero），回退到账单列表")
        }

        // ③ 回退：拉账单列表自己匹配
        val probe = settleFromBillList(orderNo, startTimeMs, snCode)

        // 账单列表也没查到金额，要靠**两种不同的信息**下结论，别混为一谈：
        //
        // | 直答接口 | 账单列表里有没有这一单 | 结论 |
        // |---|---|---|
        // | 说 0 | **有**（金额还没填） | **未结算完** → null（「结算中」） |
        // | 说 0 | **从来没有** | 真零消费 → 0.0（「无消费」） |
        // | 说不清 | 有没有都行 | 未知 → null |
        //
        // 第二行是关键：**真零消费的单子账单列表里压根不会有这一条**
        // （开完水马上停，一笔账都不产生）。所以"账单里出现过"本身就是
        // 「有消费」的证据，不能因为金额还是 0 就判成没花钱——
        // 这正是「有消费却显示无消费」的来源。
        val resolved = when {
            probe.amount != null -> probe.amount
            probe.sawBill -> null
            sawDefiniteZero -> 0.0
            else -> null
        }
        if (probe.sawBill && probe.amount == null) {
            AppLogger.w("结算：账单已出现但金额未填，按「未结算完」处理（不判无消费）")
        }
        // 待对账登记：**只要不是铁板钉钉的金额**就留一条记录。
        //
        // 尤其是那个靠 `sawDefiniteZero` 推断出来的 0.0——它是"接口说 0 + 账单从未出现"，
        // 已经是最可信的零消费判据了，但仍然是**推断**而非直接证据
        // （万一账单生成得特别慢）。所以照样登记，让 [SettlementReconciler]
        // 之后拿账单列表复核一遍——复核到了就补金额，复核不到就维持原判。
        val needsReconcile = resolved == null || (resolved == 0.0 && sawDefiniteZero)
        if (needsReconcile) {
            PrefsHelper.recordPendingSettlement(
                PendingSettlement(
                    snCode = snCode,
                    orderNo = orderNo,
                    startedAt = startTimeMs,
                    deviceName = deviceName,
                    createdAt = System.currentTimeMillis(),
                    elapsedSec = if (startTimeMs > 0)
                        ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
                    else 0
                )
            )
            if (resolved == 0.0) {
                AppLogger.w("结算：接口说 0 且账单从未出现，按真零消费处理，同时留一条待对账")
            }
        } else {
            PrefsHelper.clearPendingSettlement(snCode)
        }
        return resolved
    }

    /** 上次记进日志的原始响应体。只在**变了**的时候再记，避免 5 轮刷屏 */
    @Volatile private var lastRawLogged: String? = null

    /**
     * 直接问「这一单结算了多少钱」。
     *
     * ## 真实响应结构（09-18 真机抓到，某职业技术大学）
     *
     * ```json
     * {"success":true,"errorCode":0,"errorMessage":"成功","data":{
     *   "consumeTime":"2026-09-18 15:06:27",
     *   "consumeDate":"2026-09-18 15:06:27",
     *   "consumeMoney":0,            ← 数字，不是字符串
     *   "preDeductMoney":0, "preDeductMoneyAfter":0,
     *   "orderNo":"13200000000000000000",
     *   "deviceSnCode":"AABBCCDDEEFF", "orderAccountId":10001,
     *   "createTime":"1789715194",   ← 服务端当前时间（每次请求都在变），不是下单时间
     *   "telephone":"1xxxxxxxxxx", "clData":null,
     *   "modeName":null, "liquidModeName":null, "liquidConsumeMoney":null,
     *   "leftModeMoney":null, "rightModeMoney":null,
     *   "leftModeName":null, "rightModeName":null}}
     * ```
     *
     * ⚠️ 注意两件事：
     * - **没有** `consumeMoneyStr`，也**没有** `state` / `result` / `status`
     *   ——那几个是 `downRateResult` 的字段，别混。
     * - `consumeMoney` 在这里是**数字**，而账单列表里同名字段是**字符串**（`"0.41"`）。
     *
     * 至于为什么明知结构还是走原始串解析，见
     * [com.hualala.linyu.api.consumeOrderResultRaw] 的说明。
     */
    private suspend fun queryConsumeResult(orderNo: String, snCode: String): MoneyProbe = try {
        val raw = NetworkModule.apiService
            .consumeOrderResultRaw(snCode, orderNo, NetworkModule.authFields())

        // 只在内容变化时记一次：5 轮响应通常一模一样，记 5 遍只是噪音；
        // 但金额从不结算变成结算会改内容，那一版必须留下
        if (raw != lastRawLogged) {
            lastRawLogged = raw
            AppLogger.i("结算原始响应 consumeOrder/result/query: $raw")
        }
        extractMoney(raw)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        MoneyProbe.Unknown
    }

    /**
     * 从响应 JSON 里挖出消费金额，并**换算成元**。
     *
     * 两步：
     * 1. 先认已知的字段名（`consumeMoney` 等，数字和字符串两种）
     * 2. 认不出来就在 `data` 里**按名字找**——凡是键名像金额的数值字段都算候选
     *
     * ⚠️ 第 2 步必须**排除**余额类字段（`balance` / `account` / `wallet` /
     * `pre` / `give` / `remain`）。拿钱包余额当消费金额是这里最危险的错法：
     * 金额会大得离谱，而且看着还挺像个正常数字，不会有人发现不对。
     *
     * ⚠️ **单位不是元，是厘**，必须过一遍 [toYuan]，见那里的说明。
     *
     * ⚠️ 返回三态而不是 `Double?`：**「没这个字段」和「字段是 0」是两回事**。
     * 前者是「还不知道」，得继续等；后者是「确实没花钱」，可以收工了。
     * 用 `Double?` 表达不了这个区别——0.0 和 null 都会被当成「没查到」，
     * 于是零消费的单子会一直卡在「结算中」。
     */
    private fun extractMoney(raw: String): MoneyProbe {
        val root = try {
            com.google.gson.JsonParser().parse(raw)
        } catch (_: Exception) {
            return MoneyProbe.Unknown
        }
        if (!root.isJsonObject) return MoneyProbe.Unknown
        val obj = root.asJsonObject

        // 金额可能在 data 里，也可能直接铺在顶层，两处都看
        val scopes = listOfNotNull(
            obj.getAsJsonObject("data"),
            obj
        )

        // ① 已知字段名。字段**在**就采信，哪怕值是 0
        for (scope in scopes) {
            for (key in listOf("consumeMoney", "consumeMoneyStr", "money", "consumeAmount")) {
                val el = scope.get(key) ?: continue
                if (el.isJsonNull || !el.isJsonPrimitive) continue
                val raw = el.asString.toDoubleOrNull() ?: continue
                val v = toYuan(raw)
                if (v > 0) {
                    // 原始值和换算后都记：万一某个学校不是按厘返回的，
                    // 日志里「原始 40 → ¥0.04」这种对照一眼就能看出单位对不对
                    AppLogger.i("结算：`$key` 原始值 $raw → ¥$v（按 ${MILLI_PER_YUAN.toInt()} 厘/元 换算）")
                    return MoneyProbe.Found(v)
                }
                return MoneyProbe.KnownZero
            }
        }

        // ② 兜底：按名字找像金额的数值字段。
        // ⚠️ 这里**只认正数**——字段名是猜的，猜出来的 0 不敢当「确实没花钱」用，
        // 宁可算 Unknown 让它走账单列表那条路复核一遍
        for (scope in scopes) {
            for ((key, el) in scope.entrySet()) {
                if (!el.isJsonPrimitive) continue
                val k = key.lowercase()
                if (!AMOUNT_KEY_HINT.containsMatchIn(k)) continue
                if (AMOUNT_KEY_EXCLUDE.containsMatchIn(k)) continue
                val raw = el.asString.toDoubleOrNull() ?: continue
                val v = toYuan(raw)
                if (v > 0) {
                    AppLogger.w("结算：字段名不在预期内，按名字兜底命中 `$key` = $raw（原始值）→ ¥$v")
                    return MoneyProbe.Found(v)
                }
            }
        }

        // 成功但没金额：可能这单还没结算，也可能结构完全不同——
        // 原始响应上面已经记进日志了，看得出来是哪种
        if (obj.get("success")?.asBoolean == true && obj.get("data")?.isJsonNull != false) {
            AppLogger.i("结算：接口返回成功但 data 为空，这单可能还没结算")
        }
        return MoneyProbe.Unknown
    }

    /**
     * 账单回退的探测结果。
     *
     * ⚠️ **两个字段缺一不可**：光有 `amount` 区分不了
     * 「确实没花钱」和「账单出现了但金额还没填」——后者是有消费的。
     */
    private data class BillProbe(
        /** 查到的金额（元）；没查到为 null */
        val amount: Double?,
        /**
         * 账单列表里**是否出现过这一单**（哪怕金额还是占位 `"0.0"`）。
         *
         * 这是「有消费」的直接证据：真零消费的单子压根不会产生账单。
         */
        val sawBill: Boolean
    )

    /** 回退路径：拉账单列表，挑出本次这一单 */
    private suspend fun settleFromBillList(
        orderNo: String,
        startTimeMs: Long,
        snCode: String
    ): BillProbe {
        val month = java.time.YearMonth.now().toString()

        // ⚠️ 兜底时间窗必须**往前放宽**，不能拿 `>= startTimeMs` 卡。
        //
        // 账单里的 `consumeDate` 是**下单那一刻**（发 downRate 的时候），
        // 而 `startTimeMs`（startedAt）是**开阀确认成功之后**才写的——开阀要轮询
        // `downRateResult` 一两轮才确认，所以天然晚 1~2 秒。
        // 实测：下单 12:43:37.6 → 账单打的是 12:43:38 → 开阀确认 12:43:39.3，
        // 用 `>=` 判就把**这一单自己**滤掉了，6 轮全空、返回 0，
        // 通知永远显示「无消费」。
        val windowStart = if (startTimeMs > 0) startTimeMs - SETTLE_WINDOW_SLACK_MS else 0L

        // 有没有匹配到过账单——用来区分「压根没查到」和「查到了但金额就是 0」
        var sawBill = false

        for (attempt in 0 until BILL_LIST_ROUNDS) {
            try {
                val resp = NetworkModule.apiService.getBillListSafe(month = month)
                val bills = resp.data ?: return BillProbe(null, false)

                val matched = matchBill(bills, orderNo, windowStart)
                if (matched != null) {
                    sawBill = true
                    val dto = matched.consumeBillDTO
                    // ⚠️ 这里的 `consumeMoney` 是**字符串**、单位是**元**
                    // （和主路径那个数字型的差 1000 倍，见 [MILLI_PER_YUAN]）
                    val m = dto.consumeMoney.toDoubleOrNull()
                    if (m != null && m > 0) {
                        PrefsHelper.recordConsume(snCode, m)   // 供桌面小组件显示「上次消费」
                        AppLogger.i(
                            "结算命中（账单列表，第 ${attempt + 1} 轮）：orderNo=${dto.orderNo} " +
                                "orderId=${dto.orderId} 金额=$m 账单时间=${dto.consumeDate}"
                        )
                        return BillProbe(m, true)
                    }
                    // 账单先以 "0.0" 占位出现、结算完才填金额（12:47:36 实测），
                    // 所以这里不能拿 0 当结论，只能记下"见到过账单"继续下一轮
                    AppLogger.i("结算：已匹配到账单但金额还是 ${dto.consumeMoney}（可能是占位值）")
                }
            } catch (_: Exception) {
                // 网络抖动，继续重试
            }
            if (attempt < BILL_LIST_ROUNDS - 1) delay(1200)
        }

        AppLogger.w("结算超时：orderNo=$orderNo 两条路都没拿到金额（sawBill=$sawBill）")

        // ⚠️ **一律返回 null（"还没确定"），不要返回 0.0**。
        //
        // 这里以前是 `if (sawBill) 0.0 else null`，那个 0.0 是错的：
        // 「账单里匹配到了这一单」**本身就是「有消费」的证据**——
        // 真零消费的单子（开完水马上停）账单列表里压根不会有这一条。
        // 所以 `sawBill == true` 却金额是 0，只有一种解释：**金额还没填上**
        // （账单先以占位 `"0.0"` 出现，结算完才写金额）。
        // 把这种"还没算完"当成"没花钱"，正是「有消费却显示无消费」的来源。
        //
        // 真零消费的判定交给上层：那边会拿 `sawDefiniteZero`（直答接口明确说 0）
        // 结合"账单里始终没有这一单"来下结论。所以这里把 `sawBill` 一并带回去。
        return BillProbe(null, sawBill)
    }

    /**
     * 从账单列表里找出「本次这一单」。
     *
     * ⚠️ 顺序和字段都不能想当然：
     *
     * - `orderNo`（20 位，如 `13200000000000000002`）才是**关阀时用的那个**，先拿它精确匹配
     * - `orderId`（7 位，如 `9564403`）是账单**序号**，和 orderNo 不是一个东西。
     *   以前代码写的是 `it.orderId == orderNo`，**永远不成立**——这是结束后
     *   通知一直显示「无消费」的根因。这里保留它只作兜底（万一某校真用同一个值）
     * - 都对不上才退回时间窗，且窗口要**往前放宽**（见 [SETTLE_WINDOW_SLACK_MS]）
     */
    private fun matchBill(bills: List<BillItem>, orderNo: String, windowStartMs: Long): BillItem? {
        if (orderNo.isNotEmpty()) {
            bills.firstOrNull { it.consumeBillDTO.orderNo == orderNo }?.let { return it }
            bills.firstOrNull { it.consumeBillDTO.orderId == orderNo }?.let { return it }
        }
        return bills
            .filter { bill ->
                // 没有开阀时间就**没法判断哪笔是本次的**，一笔都不能算。
                // （这里以前写的是 `return@filter true`，会把所有历史账单都当成
                // 本次的，于是「用时 0 秒 · 消费 ¥上一次的金额」）
                if (windowStartMs <= 0) return@filter false
                billTimeMs(bill.consumeBillDTO.consumeDate) >= windowStartMs
            }
            .maxByOrNull { it.consumeBillDTO.consumeDate }
    }

    /** 账单里的日期 → 毫秒；解析不了返回 0（会被当成早于窗口而排除） */
    private fun billTimeMs(consumeDate: String): Long = BillDateParser.toEpochMillis(consumeDate)

    /**
     * 兜底时间窗往前放宽多久。
     *
     * 账单 `consumeDate` 是**下单时刻**，比本地的 `startedAt`（开阀确认成功）早
     * 1~2 秒，所以必须往前留余量，否则会把自己这一单滤掉。
     * 给 3 分钟足够宽——两单之间不可能挨这么近。
     */
    private const val SETTLE_WINDOW_SLACK_MS = 3 * 60 * 1000L

    /**
     * `/order/consumeOrder/result/query` 里金额的单位是**厘**，1 元 = 1000 厘。
     *
     * ⚠️ 这个接口和账单列表**单位不一样**，这是个纯粹的坑：
     *
     * | 接口 | 字段 | 同样一笔 0.04 元的账返回什么 |
     * |---|---|---|
     * | `consumeOrder/result/query` | `consumeMoney`（数字） | `40` |
     * | `query/account/bill/list`   | `consumeMoney`（**字符串**） | `"0.04"` |
     *
     * 字段名一模一样、类型却一个数字一个字符串、单位还差 1000 倍。
     * 忘了换算的后果不是报错而是**静默错 1000 倍**：用了 0.04 元，
     * 通知上写「消费 ¥40.00」。
     *
     * 实测证据（09-18 两笔独立订单，用 `dealDate` 对齐同一个订单）：
     * - `dealDate 2026-09-18 17:16:28` → 本接口 `40`，账单列表 `"0.04"`
     * - `dealDate 2026-09-18 17:16:56` → 本接口 `80`，账单列表 `"0.08"`
     *
     * ⚠️ 目前只有**一个学校**（某职业技术大学）的样本。别的学校万一按元返回，
     * 这里就会反向错 1000 倍——而且是**静默**错的，不报错、数字看着还挺正常。
     *
     * 所以每次换算都同时把**原始值**和**换算后**打进日志（见 [extractMoney] 和
     * [queryConsumeResult]）。哪个学校不对，导出日志一眼就能看出来，
     * 不用再抓包。真要再稳妥些，可以拿账单列表对一次——
     * 但那个接口在结算完成前会返回占位的 `"0.0"`（12:47:36 实测），
     * 拿它当准绳反而会把真金额覆盖成 0，所以没有默认走那条路。
     */
    private const val MILLI_PER_YUAN = 1000.0

    /** 厘 → 元 */
    private fun toYuan(raw: Double): Double = raw / MILLI_PER_YUAN

    /**
     * 主路径问几轮。
     *
     * 实测 27 次调用**第 1 轮就是最终值**，所以两轮足够：一轮拿正数直接收工，
     * 一轮是 0 时再确认一次（防结算竞态）。
     */
    private const val SETTLE_PROBE_ROUNDS = 2

    /** 两轮之间的间隔。第 1 轮命中时根本不会走到这儿 */
    private const val SETTLE_CONFIRM_DELAY_MS = 1000L

    /**
     * 拿到 0 之后的**额外确认轮数**。
     *
     * ## ⚠️ 这里曾经被改成 6 轮，是走错了方向，别再动它
     *
     * 当时收到了「有消费却显示无消费」的反馈，我的第一反应是「等得不够久」，
     * 于是把等待从 3 轮加到 6 轮（7.2 秒）。**那是治错了病**：
     *
     * v3.0.3 的实测结论是「第 1 轮返回就是终值，从来没变过」，如果服务端真的一直如此，
     * 多等几轮**根本买不到任何信息**——误判不是因为等得不够，而是判据本身错了
     * （见 [settleFromBillList] 里 `sawBill` 的说明）。
     *
     * 代价却很实在：零消费的单子白白多等 3.6 秒，用户看到「结算中」干等。
     *
     * 所以**真正的修复在判据**（账单出现过 ⇒ 有消费 ⇒ 不能判 0），
     * 这里回到 2 轮的保守值：一轮拿正数直接收工，一轮是 0 时确认一次防竞态。
     */
    private const val SETTLE_ZERO_CONFIRM_ROUNDS = 2

    /** 零确认两轮之间的间隔 */
    private const val SETTLE_ZERO_CONFIRM_DELAY_MS = 1200L

    /**
     * 回退路径（账单列表）问几轮。
     *
     * ⚠️ **不能是 1**。这里是「偶发无消费」的另一条误判路径，而且比主路径那条更隐蔽：
     *
     * 账单是**先以占位 `"0.0"` 出现、结算完成后才填金额**的（`API-qzxy.md` §4.11 实测：
     * `12:47:36` 是 `"0.0"`、`uploadDate` 空；`12:47:37` 就变成 `"0.41"`）。
     * 也就是说**「匹配到账单」本身就是「这一单有消费」的证据**——零消费的单子
     * 账单列表里压根不会有这一单。
     *
     * 于是只问一轮会发生什么：账单恰好以占位 `"0.0"` 的形态被抓到 → `sawBill = true`
     * → 金额解析出 0 → 走到最后 `if (sawBill) 0.0` → 通知写「无消费」。
     * **明明有消费，只因为早问了一秒就被告知没花钱。**
     *
     * 给 3 轮（约 2.4 秒）足够跨过服务端那 1 秒的填金额延迟。
     * 代价只落在「主路径已经失败」的单子上，有消费的正常路径不受影响。
     */
    private const val BILL_LIST_ROUNDS = 3

    /** 键名像金额的（[extractMoney] 兜底用） */
    private val AMOUNT_KEY_HINT =
        Regex("(consume|money|amount|fee|cost|pay|charge|price)", RegexOption.IGNORE_CASE)

    /**
     * 键名像金额、但**绝不能**当消费金额的。
     *
     * `balance` / `remain` / `account` / `wallet` 是余额，`pre` / `give` 是预扣和赠送，
     * 拿它们当消费金额会报出一个大得离谱的数——而且看着像正常数字，不会有人发现。
     */
    private val AMOUNT_KEY_EXCLUDE = Regex(
        "(balance|remain|surplus|account|wallet|pre|give|given|total|sum|count|id|no\\b|time|date|status|state)",
        RegexOption.IGNORE_CASE
    )
}
