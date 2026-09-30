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
    /**
     * 明确失败。
     *
     * @param errorCode 服务端业务错误码；**本地原因（参数不全、设备类型不支持等）为 null**。
     *   上层判断「是不是设备不在线（306）」要靠它，别再匹配文案——
     *   文案会随学校/服务端版本变，而且中文措辞各地不一。
     */
    data class Failed(val message: String, val errorCode: Int? = null) : OpenOutcome {
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
    fun isRunning(snCode: String): Boolean = ActiveOrderRepository.isRunning(snCode)

    /** 已用时长（秒），按开阀时间戳算；没开始过则为 0 */
    fun elapsedSeconds(snCode: String): Int {
        val startAt = PrefsHelper.getStartedAt(snCode)
        return if (startAt > 0) ((System.currentTimeMillis() - startAt) / 1000).toInt() else 0
    }

    /** 开阀时的 Unix 毫秒时间戳，供小组件的 Chronometer 作基准 */
    fun startedAt(snCode: String): Long = PrefsHelper.getStartedAt(snCode)

    /** 自动关停剩余秒数，0 表示未知/无倒计时 */
    fun autoDisconRemain(snCode: String): Int = PrefsHelper.getAutoDisconRemain(snCode)

    fun activeOrderFor(snCode: String): ActiveOrder? = ActiveOrderRepository.find(snCode)

    fun lastDeviceSnCode(): String = PrefsHelper.lastDeviceSnCode
    fun lastDeviceName(): String = PrefsHelper.lastDeviceName

    // ════════════════════════════════════════════
    //  动作
    // ════════════════════════════════════════════

    /**
     * 蓝牙表走到**云端通道**时的兜底文案。
     *
     * 蓝牙表已由 `BleShowerController` 支持，App 内的开阀入口会先分流过去，
     * 正常不会看到这句。留着是为了挡住**没有设备类型信息的入口**——
     * 最典型的是桌面小组件（它只有 `snCode`，拿不到 `isBle`），
     * 那种情况下不给提示的话，用户只会看到服务端的「设备不在线」。
     */
    const val UNSUPPORTED_DEVICE_MESSAGE =
        "该设备需通过手机蓝牙开启，请在本 App 内操作（桌面小组件暂不支持）"

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

        // ⚠️ 只能靠手机蓝牙直连的水表（`smallTypeId == 1`），云端下发必然回 306。
        //
        // 在这里就拦住，别让用户白等一次网络往返、再看到一句「设备不在线」——
        // 那句话既不解释原因，也不告诉他能做什么，他只会以为是网络问题反复试。
        //
        // 覆盖范围：App 内（`MainViewModel` 传了 device）。
        // 小组件那条路径传的是 `null`（它只有 snCode，拿不到设备类型），拦不到，
        // 仍会走 306 分支并显示已有的错误提示。
        if (device?.needsBluetoothControl == true) {
            AppLogger.i("开阀被拦：$snCode 是需要蓝牙直连的水表（smallTypeId=${device.smallTypeId}）")
            return OpenOutcome.Failed(UNSUPPORTED_DEVICE_MESSAGE)
        }

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
        // ⚠️ errorCode 必须带上：上层要用它判断「是不是 306 设备不在线」，
        // 从而决定要不要引导用户改用蓝牙。丢掉它就只能去匹配文案了。
        if (!resp.success) {
            return OpenOutcome.Failed(
                resp.displayMessage ?: "开始失败",
                resp.errorCode
            )
        }

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
        //                          "orderNo":"13200000000000000000","state":1,"result":0,...}
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
            val resp = DeviceInfoCache.load(mac)
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
            ActiveOrderRepository.update { list ->
                val i = list.indexOfFirst { it.snCode == snCode }
                if (i >= 0) list[i] = list[i].copy(orderNo = fresh)
            }
            AppLogger.i("orderNo 本地缺失，已从服务端回填：$fresh")
        }
        return fresh
    }


    // ════════════════════════════════════════════
    //  内部：持久化
    // ════════════════════════════════════════════

    /**
     * 活跃订单里没有该设备就补一条（已有则不动，避免覆盖已解析到的 orderNo）。
     *
     * ⚠️ **必须在启动 [ShowerWatchService] 之前调用**——服务启动时会把活跃订单
     * 读出来构建「使用中」通知，列表为空会直接崩（`first()`）。
     * 4G 表路径在 [openValve] 里落盘；蓝牙表路径在 `BleShowerController.openValve`
     * 成功后调用这里，两条路径都得保证「先落盘、再启服务」。
     */
    fun ensureActiveOrder(snCode: String, orderNo: String, device: DeviceInfo?) {
        // 原子地「有则不动、无则加入」——并发时不会重复也不会互相覆盖
        ActiveOrderRepository.update { list ->
            if (list.any { it.snCode == snCode }) return@update
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
        }
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
        ActiveOrderRepository.remove(snCode)
        PrefsHelper.setStartedAt(snCode, 0L)
        PrefsHelper.clearAutoDiscon(snCode)
    }
}
