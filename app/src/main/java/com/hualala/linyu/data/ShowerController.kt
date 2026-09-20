package com.hualala.linyu.data

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

/** 开阀结果 */
sealed interface OpenOutcome {
    /** 需要交给界面做挤号判断的原始服务端消息（没有则为 null） */
    val kickHint: String?

    /** 新开阀成功 */
    data class Opened(val autoDiscon: Int) : OpenOutcome {
        override val kickHint: String? = null
    }

    /** 设备上已有进行中的订单，直接恢复（没有发 downRate） */
    data class Resumed(val orderNo: String) : OpenOutcome {
        override val kickHint: String? = null
    }

    /** 明确失败 */
    data class Failed(val message: String) : OpenOutcome {
        override val kickHint: String? get() = message
    }

    /**
     * 设备上有进行中的订单，但**不是你的**。
     *
     * 服务端的 `queryUsing` 会回一个 `isOwner` 字段。App 内的设备详情弹窗靠它
     * 把按钮置灰、显示「他人使用中」；小组件没有界面，必须在这一层就拦住。
     *
     * 拦不住的后果不只是显示错：会把**别人的订单**当成自己的记进 activeOrders，
     * 卡片显示「使用中」并开始计时，用户点停止时还会拿别人的 orderNo 去调关阀。
     */
    data class InUseByOthers(val message: String) : OpenOutcome {
        override val kickHint: String? = null
    }

    /**
     * 预算耗尽，开没开不确定。
     * 这种情况**不能**当成失败——downRate 其实已经发出去了，
     * 只是确认轮询没跑完。谎报失败会让用户重复操作。
     */
    data object Unknown : OpenOutcome {
        override val kickHint: String? = null
    }
}

/**
 * 「选用设备」的结果。
 *
 * ⚠️ **必须分三种，不能只返回一个 Boolean。** 以前是 `Boolean`，调用方只能报一句
 * 「切换失败，请稍后再试」——而"服务端说没有这台设备"和"网络不通"是两回事：
 * 前者用户去查网络永远查不出东西，后者查了才有用。
 */
sealed interface PickResult {
    data object Ok : PickResult

    /** 服务端明确回答"没有这台设备" */
    data object DeviceNotFound : PickResult

    /** 请求本身失败（断网、超时）——**不知道**设备在不在 */
    data object Network : PickResult
}

/** 关阀结果 */
sealed interface CloseOutcome {
    data class Closed(
        /** 关阀前的开阀时间戳，供调用方做账单过滤（Controller 已经把它清掉了，所以要带出来） */
        val startTimeMs: Long,
        override val kickHint: String? = null
    ) : CloseOutcome

    data class Failed(
        val message: String,
        override val kickHint: String?
    ) : CloseOutcome

    /**
     * 关阀指令**发出去了或没能发出去，但完全没有"设备已停"的证据**。
     *
     * ⚠️ 这个状态必须存在，不能合并进 [Closed]。以前是这么写的：轮询 5 次、
     * 不管结果如何，循环一结束就 `clearDeviceState()` + 返回 [Closed]。
     * 也就是说**断网时 5 次请求全抛异常，照样告诉用户「使用结束」**——
     * 界面上水停了，实际阀还开着，钱继续扣。
     *
     * 判据是「一点证据都没有」，不是「确认得很完美」，这是有意的：
     * 真拿"服务端明确说订单没了"当唯一标准的话，网络稍微抖一下就报未确认，
     * 正常停止会天天弹「没确认到」——那种噪声会让人忽略真正的异常。
     *
     * 所以只在**关阀请求本身失败**、且**服务端状态也查不到**时才返回它。
     */
    data class Unconfirmed(
        /** 关阀前的开阀时间戳 */
        val startTimeMs: Long,
        val message: String?,
        override val kickHint: String? = null
    ) : CloseOutcome

    /** 需要交给界面做挤号判断的原始服务端消息 */
    val kickHint: String?
}

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

/**
 * 洗澡（开阀 / 关阀）的共享业务层。
 *
 * 从 MainViewModel 里抽出来，目的是让**没有 Activity、没有 Compose**的调用方
 * （桌面小组件）也能启停热水。这里只做两件事：
 *
 * 1. 调接口，把「查询 → 指令 → 轮询确认」这套流程封装好
 * 2. 把结果落到 PrefsHelper（开阀时间戳、自动关停倒计时、活跃订单、上次使用设备）
 *
 * **不碰的东西**：MQTT（只是加速器，HTTP 轮询能独立保证正确性）、
 * Compose 状态、挤号弹窗、toast —— 这些留在各自的调用方。
 *
 * ⚠️ 迁移自 MainViewModel 时是**纯搬运**，以下判断条件必须原样保留：
 * - `errorCode == 307` 表示「已有订单 / 已在关闭中」
 * - 关阀成功判据里 `displayMessage.contains("已在")` 依赖服务端中文字符串
 * - 开阀成功判据 `success && (state == 0 || result == 0 || orderNo != null)`
 */
object ShowerController {

    // ════════════════════════════════════════════
    //  本地状态（纯 Prefs 读取，无网络）
    // ════════════════════════════════════════════

    /** 该设备当前是否有进行中的订单 */
    fun isRunning(snCode: String): Boolean =
        snCode.isNotEmpty() && PrefsHelper.getActiveOrders().any { it.snCode == snCode }

    /** 已用时长（秒），按开阀时间戳算；没开始过则为 0 */
    fun elapsedSeconds(snCode: String): Int {
        val startAt = PrefsHelper.getStartedAt(snCode)
        return if (startAt > 0) ((System.currentTimeMillis() - startAt) / 1000).toInt() else 0
    }

    /** 开阀时的 Unix 毫秒时间戳，供小组件的 Chronometer 作基准 */
    fun startedAt(snCode: String): Long = PrefsHelper.getStartedAt(snCode)

    /** 自动关停剩余秒数，0 表示未知/无倒计时 */
    fun autoDisconRemain(snCode: String): Int = PrefsHelper.getAutoDisconRemain(snCode)

    fun activeOrderFor(snCode: String): ActiveOrder? =
        PrefsHelper.getActiveOrders().find { it.snCode == snCode }

    fun lastDeviceSnCode(): String = PrefsHelper.lastDeviceSnCode
    fun lastDeviceName(): String = PrefsHelper.lastDeviceName

    // ════════════════════════════════════════════
    //  动作
    // ════════════════════════════════════════════

    /**
     * 开阀。
     *
     * @param budgetMs 确认轮询的总预算。小组件跑在广播里（`goAsync()` 约 10 秒上限），
     *                 必须设上限，超了就返回 [OpenOutcome.Unknown]。
     *                 App 内部调用可以给大一点。
     */
    suspend fun openValve(
        snCode: String,
        device: DeviceInfo? = null,
        budgetMs: Long = 6_000L
    ): OpenOutcome {
        require(snCode.isNotBlank()) { "snCode 不能为空" }
        val deadline = System.currentTimeMillis() + budgetMs

        // 1. 设备上已经有订单 → 直接恢复，不再发 downRate
        val existing = NetworkModule.apiService.queryUsingSafe(
            snCode = snCode, auth = NetworkModule.authFields()
        )
        if (existing.errorCode == 307 || (existing.success && existing.data?.orderNo != null)) {
            // ⚠️ 有订单 ≠ 是你的订单。先看 isOwner，别把别人的当成自己的恢复。
            if (existing.data?.isOwner == false) {
                return OpenOutcome.InUseByOthers("该设备正在被他人使用")
            }
            val orderNo = existing.data?.orderNo ?: ""
            ensureActiveOrder(snCode, orderNo, device)
            rememberDevice(device)
            return OpenOutcome.Resumed(orderNo)
        }

        // 2. 下发开阀指令
        val resp = NetworkModule.apiService.downRateSafe(
            snCode = snCode, auth = NetworkModule.authFields()
        )
        if (!resp.success) return OpenOutcome.Failed(resp.displayMessage ?: "开始失败")

        // 3. 轮询确认开阀（最多 9 次 × 700ms，与 App 内一致）
        var opened = false
        var autoDiscon = resp.data?.autoDisConTime ?: 0

        // ⚠️ 这个响应里**本来就带 orderNo**，别再扔掉了。
        //
        // 原来这里只用它判断「开没开」，落盘时却写空串（`ensureActiveOrder(snCode, "", ...)`），
        // 然后指望 `MainViewModel.startOrderPoll` 那 11 轮 × 800ms 的轮询事后补上。
        // 于是就有了一个纯粹的竞态：**开完水马上停**，轮询还没跑出结果，
        // 本地存的还是空串 —— 而关阀、结算这几个接口全都要 orderNo。
        //
        // 实测日志（09-18 15:06:28）：
        //   downRateResult resp = {"...","consumeDate":"20260918150627",
        //                          "orderNo":"13202609181506275230","state":1,"result":0,...}
        // 服务端第一次确认开阀时就把 orderNo 给了，白白再问一趟没有任何道理。
        var confirmedOrderNo = ""
        for (i in 0..8) {
            if (i > 0 && System.currentTimeMillis() >= deadline) return OpenOutcome.Unknown
            delay(700)
            try {
                val r = NetworkModule.apiService.downRateResultSafe(
                    snCode = snCode, auth = NetworkModule.authFields()
                )
                val d = r.data
                if (r.success && (d?.state == 0 || d?.result == 0 || d?.orderNo != null)) {
                    opened = true
                    confirmedOrderNo = d?.orderNo.orEmpty()
                    val autoTime = d?.autoDisConTime
                    if (autoTime != null && autoTime > 0) autoDiscon = autoTime
                    break
                }
            } catch (_: Exception) {
                // 单次轮询失败不算失败，继续下一轮
            }
        }
        if (!opened) return OpenOutcome.Failed("开阀未确认成功，请确认热水器是否已开启")

        // 4. 落盘：开阀时间戳 / 活跃订单 / 自动关停倒计时 / 上次使用设备
        if (PrefsHelper.getStartedAt(snCode) <= 0L) {
            PrefsHelper.setStartedAt(snCode, System.currentTimeMillis())
        }
        ensureActiveOrder(snCode, confirmedOrderNo, device)
        rememberDevice(device)
        if (autoDiscon > 0 && PrefsHelper.getAutoDisconRemain(snCode) <= 0) {
            PrefsHelper.setAutoDisconRemain(snCode, autoDiscon)
        }
        return OpenOutcome.Opened(autoDiscon)
    }

    /**
     * 关阀。无论确认结果如何都会清掉本地状态（与 App 原逻辑一致——
     * 确认不通过也退出洗澡界面，不把用户卡在里面）。
     */
    suspend fun closeValve(snCode: String, orderNo: String): CloseOutcome {
        if (snCode.isBlank()) return CloseOutcome.Failed("设备信息不完整", null)

        var orderNoResolved = orderNo
        if (orderNoResolved.isEmpty()) {
            val p = NetworkModule.apiService.queryUsingSafe(
                snCode = snCode, auth = NetworkModule.authFields()
            )
            orderNoResolved = p.data?.orderNo ?: ""
        }

        // 关阀前的开阀时间戳要先取出来——下面 clearDeviceState 会把它清零
        val startTime = PrefsHelper.getStartedAt(snCode)

        // 1. 下发关阀指令
        //
        // ⚠️ 这里以前是**裸调用**：请求抛异常（断网、超时）会直接冒到调用方，
        // 而两个调用方都把「抛异常」当成了不起眼的小事，于是照样走完"使用结束"的流程。
        // 现在明确接住——请求没发出去就是**没有任何证据**，必须如实报未确认。
        val close = try {
            NetworkModule.apiService.closeOrderSafe(
                snCode = snCode, orderNo = orderNoResolved, auth = NetworkModule.authFields()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 请求本身失败了，再问一次服务端到底还开着没有
            val running = orderRunning(snCode)
            if (running == true) {
                // 服务端说还开着 → 确实没关成
                return CloseOutcome.Unconfirmed(startTime, "关阀请求没发出去，设备可能还在用水", null)
            }
            // 查不到（多半也是断网）→ 一样没有证据
            return CloseOutcome.Unconfirmed(startTime, "关阀请求没发出去：${e.message}", null)
        }

        if (!close.success) {
            // 服务器已接受 / 已在关闭中时也视为成功
            if (close.errorCode == 307 || close.displayMessage?.contains("已在") == true) {
                clearDeviceState(snCode)
                return CloseOutcome.Closed(startTime, close.displayMessage)
            }
            return CloseOutcome.Failed(close.displayMessage ?: "关闭失败，请重试", close.displayMessage)
        }

        // 2. 顺手核一次服务端状态，只用来**记日志**，不用来等
        //
        // ⚠️ 为什么这里不再轮询：`closeOrder` 已经被服务端接受了（上面 `close.success` 过了），
        // 订单就一定会关掉。这时候再等 5 轮、无论查到什么都还是判「已关闭」，
        // 那就是**白让用户多等最多 5 秒**——而 App 那条路径的界面退出正是在
        // `closeValve` 返回之后，等待会直接变成用户可感知的卡顿。
        // （原来那段轮询本意是确认，但它查的 `closeOrder/result/query` 实测恒返回
        // `data: null`，第 1 轮就 break，所以其实从没真的等过——现在把意图和代价都写清楚。）
        //
        // 真正需要拦的是**关阀请求压根没发出去**那种情况，那个在上面 catch 里已经处理了。
        val running = orderRunning(snCode)
        if (running != false) {
            AppLogger.w("关阀已受理，但服务端仍显示订单（running=$running）：$snCode")
        }

        clearDeviceState(snCode)
        return CloseOutcome.Closed(startTime, null)
    }

    /**
     * 以服务端为准，把本地状态对齐一次。
     *
     * 用在「开阀结果未知」之后：小组件预算耗尽时不知道到底开没开，
     * 与其猜，不如按一次服务端真实状态。
     * @return 对齐后是否处于使用中
     */
    suspend fun reconcile(snCode: String): Boolean {
        if (snCode.isBlank()) return false
        val orderNo = queryOrderNo(snCode)
        return if (orderNo.isNotEmpty() || hasActiveOrder(snCode)) {
            if (PrefsHelper.getStartedAt(snCode) <= 0L) {
                PrefsHelper.setStartedAt(snCode, System.currentTimeMillis())
            }
            ensureActiveOrder(snCode, orderNo, null)
            true
        } else {
            clearDeviceState(snCode)
            false
        }
    }

    /**
     * 把设备标记为「已结束」：清掉本地活跃订单与计时。
     *
     * 专供**没有走 [closeValve] 的结束路径**使用——设备超时自己关了、
     * 或者在别处被关掉了。
     *
     * 以前缺这个入口：自动关停时代码只退出界面，`activeOrders` 里那条一直留着，
     * 于是 `isRunning()` 永远为 true，**小组件会一直卡在「使用中」**。
     */
    fun markFinished(snCode: String) {
        if (snCode.isEmpty()) return
        clearDeviceState(snCode)
    }

    /** 服务端是否报告该设备有进行中的订单 */
    private suspend fun hasActiveOrder(snCode: String): Boolean =
        orderRunning(snCode) == true

    /**
     * 服务端上这台设备还有没有订单。**三态**：true 有 / false 明确没有 / null 请求失败、不确定。
     *
     * ⚠️ 三态不能省。上面那个 [hasActiveOrder] 以前是 `catch { false }`——
     * 网络一抖，「查不到」就被当成了「没有订单」。判断"设备关没关"的时候
     * 用这种二值版本，等于把"不知道"当成"关好了"，正是要避免的那类谎报。
     *
     * 判据和 [com.hualala.linyu.service.ShowerWatchService] 里的同名逻辑保持一致：
     * `errorCode == 307` 也算有订单（服务端在"已在关闭中"这类场景会这么回）。
     */
    private suspend fun orderRunning(snCode: String): Boolean? = try {
        val p = NetworkModule.apiService.queryUsingSafe(
            snCode = snCode, auth = NetworkModule.authFields()
        )
        when {
            p.errorCode == 307 || (p.success && p.data?.orderNo != null) -> true
            p.success -> false
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * 把 [mac] 对应的设备设为「当前设备」（小组件附近设备页的「选用」）。
     *
     * 只做接口查询 + 落盘，不碰任何界面状态——这样桌面小组件就能在**不打开 App** 的前提下
     * 换一台设备来控制。写进去的就是 [rememberDevice] 那一套，和 App 内用过一次设备之后
     * 留下来的状态完全一致，所以小组件随后读到的名字、副标题、预扣金额都是对的。
     *
     * @return 是否成功解析到设备
     */
    suspend fun pickDevice(mac: String): PickResult {
        if (mac.isBlank()) return PickResult.DeviceNotFound
        return try {
            val resp = NetworkModule.apiService.getDeviceInfoSafe(mac)
            if (resp.success && resp.data != null) {
                rememberDevice(resp.data)
                PickResult.Ok
            } else {
                PickResult.DeviceNotFound
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PickResult.Network
        }
    }

    /** 查询设备上进行中订单的订单号，没有则返回空串 */
    suspend fun queryOrderNo(snCode: String): String {
        return try {
            val p = NetworkModule.apiService.queryUsingSafe(
                snCode = snCode, auth = NetworkModule.authFields()
            )
            if (p.errorCode == 307 || (p.success && p.data?.orderNo != null)) p.data?.orderNo ?: "" else ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 拿这次用水**真正的** orderNo：本地存了就用本地的，没存就问服务端，并回填。
     *
     * ## 为什么必须补这一步
     *
     * `openValve` 发完 `downRate` 之后是**不知道** orderNo 的——服务端那一刻还没生成，
     * 落盘时只能先写空串（`ensureActiveOrder(snCode, "", device)`）。orderNo 靠
     * `MainViewModel.startOrderPoll` 那 11 轮 × 800ms 的轮询事后补上。
     *
     * 于是就有了这个坑：**开完水马上停**的话，轮询还没跑出结果，本地存的还是空串。
     * 而所有拿 orderNo 的接口（结算、关阀）都需要它——没有就只能退化成按时间窗
     * 猜账单。所以结算前必须先把这一步补上。
     *
     * ⚠️ **必须在关阀之前调**。关阀成功后订单就没了，`queryUsing` 再也问不出来。
     *
     * @return orderNo；实在拿不到返回空串（调用方仍需能处理这种情况）
     */
    suspend fun resolveOrderNo(snCode: String): String {
        val cached = activeOrderFor(snCode)?.orderNo.orEmpty()
        if (cached.isNotEmpty()) return cached
        val fresh = queryOrderNo(snCode)
        if (fresh.isNotEmpty()) {
            // 回填进 Prefs：别的路径（小组件、通知栏按钮）随后读到的就是真的了
            val list = PrefsHelper.getActiveOrders()
            val i = list.indexOfFirst { it.snCode == snCode }
            if (i >= 0) {
                list[i] = list[i].copy(orderNo = fresh)
                PrefsHelper.saveActiveOrders(list)
            }
            AppLogger.i("orderNo 本地缺失，已从服务端回填：$fresh")
        }
        return fresh
    }

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
    suspend fun settleAmount(orderNo: String, startTimeMs: Long, snCode: String): Double? {
        var sawDefiniteZero = false
        if (orderNo.isNotEmpty()) {
            for (attempt in 0 until SETTLE_PROBE_ROUNDS) {
                when (val probe = queryConsumeResult(orderNo, snCode)) {
                    is MoneyProbe.Found -> {
                        PrefsHelper.recordConsume(snCode, probe.value)
                        AppLogger.i("结算命中（consumeOrder/result，第 ${attempt + 1} 轮）：¥${probe.value}")
                        return probe.value
                    }
                    // 字段在、值是 0。多问一轮再下结论——万一刚好撞上结算窗口，
                    // 下一轮就会给出真金额（而不是把「还没算完」当成「没花钱」）
                    MoneyProbe.KnownZero -> sawDefiniteZero = true
                    MoneyProbe.Unknown -> Unit
                }
                if (attempt < SETTLE_PROBE_ROUNDS - 1) delay(SETTLE_CONFIRM_DELAY_MS)
            }
            AppLogger.w("consumeOrder/result ${SETTLE_PROBE_ROUNDS} 轮没给金额（见过明确的 0：$sawDefiniteZero），回退到账单列表")
        }

        // ② 回退：拉账单列表自己匹配
        val fromBills = settleFromBillList(orderNo, startTimeMs, snCode)

        // 账单列表也没查到，但直答接口**明确说了是 0** —— 那就是真的没花钱
        // （比如开完水马上停，一滴热水都没放）。
        //
        // ⚠️ 这一条必须补。不补的话 [settleFromBillList] 返回 null → 通知上一句
        // 「结算中」，而金额**永远不会再来**（压根没有那笔账单），用户看到的是
        // 一条永远停在「结算中」的通知——比直接说「无消费」还糟。
        return if (fromBills == null && sawDefiniteZero) 0.0 else fromBills
    }

    /** 上次记进日志的原始响应体。只在**变了**的时候再记，避免 5 轮刷屏 */
    @Volatile private var lastRawLogged: String? = null

    /**
     * 直接问「这一单结算了多少钱」。
     *
     * ## 真实响应结构（09-18 真机抓到，金华职业技术大学）
     *
     * ```json
     * {"success":true,"errorCode":0,"errorMessage":"成功","data":{
     *   "consumeTime":"2026-09-18 15:06:27",
     *   "consumeDate":"2026-09-18 15:06:27",
     *   "consumeMoney":0,            ← 数字，不是字符串
     *   "preDeductMoney":0, "preDeductMoneyAfter":0,
     *   "orderNo":"13202609181506275230",
     *   "deviceSnCode":"C47F0EDCBCC7", "orderAccountId":41681,
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

    /** 回退路径：拉账单列表，挑出本次这一单 */
    private suspend fun settleFromBillList(orderNo: String, startTimeMs: Long, snCode: String): Double? {
        val month = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault())
            .format(java.util.Calendar.getInstance().time)

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
                val bills = resp.data ?: return null

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
                        return m
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
        // 匹配到过账单、金额一直是 0 → 确实没有消费；
        // **一次都没匹配到 → 结果未知**，调用方不能把「未知」说成「无消费」
        return if (sawBill) 0.0 else null
    }

    /**
     * 从账单列表里找出「本次这一单」。
     *
     * ⚠️ 顺序和字段都不能想当然：
     *
     * - `orderNo`（20 位，如 `13202609172359167875`）才是**关阀时用的那个**，先拿它精确匹配
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
    private fun billTimeMs(consumeDate: String): Long =
        BILL_DATE_PARSERS.asSequence()
            .map { p -> try { p.parse(consumeDate)?.time ?: 0L } catch (_: Exception) { 0L } }
            .maxOrNull() ?: 0L

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
     * ⚠️ 目前只有**一个学校**（金华职业技术大学）的样本。别的学校万一按元返回，
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
     * 回退路径（账单列表）问几轮。
     *
     * 只留一轮。这条路现在**只是主路径整个失败时的兜底**（网络不通 / 字段名不认识），
     * 而它本来就慢：账单要多等一会儿才带金额出现。再多问几轮只是白白拖长
     * 那条「结算中」通知的停留时间，收益极低。
     */
    private const val BILL_LIST_ROUNDS = 1

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

    private val BILL_DATE_PARSERS = listOf(
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()),
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()),
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
    )

    // ════════════════════════════════════════════
    //  内部：持久化
    // ════════════════════════════════════════════

    /** 活跃订单里没有该设备就补一条（已有则不动，避免覆盖已解析到的 orderNo） */
    private fun ensureActiveOrder(snCode: String, orderNo: String, device: DeviceInfo?) {
        val list = PrefsHelper.getActiveOrders()
        if (list.any { it.snCode == snCode }) return
        list.add(
            ActiveOrder(
                snCode = snCode,
                orderNo = orderNo,
                deviceName = device?.displayName ?: PrefsHelper.lastDeviceName,
                deviceMac = device?.macAddress ?: PrefsHelper.lastDeviceMac,
                deviceEmoji = device?.typeEmoji ?: PrefsHelper.lastDeviceEmoji,
                // 小组件调用时 device 为 null，回落到上次记下的金额，
                // 否则卡片上的「预扣」会一直是 ¥0.00
                preDeduct = device?.withholdMoney ?: PrefsHelper.lastDeviceWithholdMoney.toDouble()
            )
        )
        PrefsHelper.saveActiveOrders(list)
    }

    /**
     * 关阀**没成功**时，把刚才清掉的本地状态**放回去**。
     *
     * ## 为什么需要"回滚"这一步
     *
     * 停止流程为了体验，是**先清本地状态、再去关阀**的（见 `ShowerWatchService.doFinish`
     * 的注释：不这么做，用户点完要愣 5 秒界面才动）。代价是关阀万一失败，
     * 本地已经显示成空闲了，而设备其实还在跑。
     *
     * 这会连锁出三个问题，实测都出现了：
     * 1. 界面/小组件显示空闲，用户以为停了
     * 2. 等联网后真相反回来（`queryUsing` 查到订单还在），状态**补得回来**
     * 3. 但 [PrefsHelper.getStartedAt] 已经被清了 → 计时从 0 重新开始，看着像刚开的
     *
     * 所以这里把订单和**开阀时间戳一起**放回去。时间戳是关键——少了它，
     * 即使状态补回来，计时也是错的。
     *
     * @param startedAtMs 必须在 `markFinished` **之前**取好再传进来
     */
    fun restoreActiveOrder(snCode: String, orderNo: String, startedAtMs: Long) {
        if (snCode.isEmpty()) return
        ensureActiveOrder(snCode, orderNo, null)
        if (startedAtMs > 0L) PrefsHelper.setStartedAt(snCode, startedAtMs)
    }

    /**
     * 当前设备的**寝室筛选用名**：优先原始设备名，老数据回退到显示名。
     *
     * 寝室键是从原始名取的，不能拿显示名去比（显示名被 `formatDeviceName` 去掉了楼层）。
     * 见 [PrefsHelper.lastDeviceRawName] 的说明。
     */
    fun roomFilterName(): String =
        PrefsHelper.lastDeviceRawName.ifEmpty { PrefsHelper.lastDeviceName }

    /** 记录「上次使用的设备」。小组件调用时 device 为 null（信息本来就在 Prefs 里），跳过即可 */
    private fun rememberDevice(device: DeviceInfo?) {
        if (device == null) return
        PrefsHelper.lastDeviceName = device.displayName
        PrefsHelper.lastDeviceRawName = device.deviceName
        PrefsHelper.lastDeviceMac = device.macAddress
        PrefsHelper.lastDeviceSnCode = device.snCode
        PrefsHelper.lastDeviceEmoji = device.typeEmoji
        // 小组件副标题要显示「卫生间热水器」这类类型说明
        PrefsHelper.lastDeviceTypeName = device.typeLabel
        // 小组件开阀时只有 snCode，预扣金额得从这里取
        PrefsHelper.lastDeviceWithholdMoney = device.withholdMoney.toFloat()
    }

    /** 清掉该设备的本地使用状态 */
    private fun clearDeviceState(snCode: String) {
        PrefsHelper.saveActiveOrders(PrefsHelper.getActiveOrders().filterNot { it.snCode == snCode })
        PrefsHelper.setStartedAt(snCode, 0L)
        PrefsHelper.clearAutoDiscon(snCode)
    }
}
