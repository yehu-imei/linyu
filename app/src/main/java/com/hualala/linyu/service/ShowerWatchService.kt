package com.hualala.linyu.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.data.CloseDisposition
import com.hualala.linyu.data.CloseOutcome
import com.hualala.linyu.data.KeyedTaskRegistry
import com.hualala.linyu.data.OrderPresence
import com.hualala.linyu.data.ShowerController
import com.hualala.linyu.data.ShowerEvents
import com.hualala.linyu.data.ShowerOrderTarget
import com.hualala.linyu.data.closeDisposition
import com.hualala.linyu.data.monitorSerials
import com.hualala.linyu.utils.AppLogger
import com.hualala.linyu.utils.Notifier
import com.hualala.linyu.utils.PrefsHelper
import com.hualala.linyu.widget.LinYuWidget
import com.hualala.linyu.widget.WidgetBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicInteger
import com.hualala.linyu.data.SettlementEngine

/**
 * 用水监控（前台服务）。
 *
 * ## 为什么必须有它
 *
 * 原来「倒计时 + 每 15 秒轮询订单」这两件事写在 `MainViewModel.timerJob` 里，
 * 而那个 Job：
 * - 在 [com.hualala.linyu.ui.MainViewModel.minimizeShower] 里被 cancel —— 退出洗澡页就停摆
 * - 只在 `startShower` 里启动 —— **从小组件开阀时压根不会启动**
 *
 * 结果就是：退出使用页、或者从桌面小组件开的水，超时了也没人管，
 * 小组件会一直卡在「使用中」。
 *
 * 搬到服务里之后，监控跟界面彻底解耦：App 在不在前台、有没有被划掉，都不影响。
 *
 * ## 判定逻辑
 *
 * **唯一判据是「服务端还有没有这个订单」**，倒计时只决定什么时候醒过来查：
 * - 每 15 秒一轮；快关停时（剩余 ≤ 14 秒）改成精确等到那一刻
 * - 查到订单没了 → 结束流程
 * - 请求失败（不确定）→ 什么都不做，下一轮再说，**绝不因为一次网络抖动就误判结束**
 */
class ShowerWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchJobs = KeyedTaskRegistry<Job>()
    private val finishingCount = AtomicInteger(0)
    @Volatile private var attachedInUse = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 这个进程可能是被 startForegroundService 冷启动的，
        // PrefsHelper / NetworkModule / AppLogger 都还没初始化
        ensureInit()

        if (intent?.action == ACTION_MONITOR_EXISTING_ORDER) {
            val target = ShowerOrderTarget.fromIntent(intent)
            val order = target?.currentOrder()
            if (target == null || order == null) {
                // 指令可能在启动前台服务后才失效。已有监控不应被这条旧指令覆盖。
                if (watchJobs.isEmpty() && finishingCount.get() == 0) {
                    startForeground(ID_STALE_STOP, Notifier.minimal(this))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            if (finishingCount.get() == 0) {
                val orders = PrefsHelper.getActiveOrders()
                if (orders.size > 1) {
                    attachForeground(inUseNotification(orders))
                } else {
                    attachForeground(Notifier.showInUse(
                        this, target.snCode, order.deviceName.ifEmpty { "热水器" }, target.startedAtMs, target
                    ))
                }
            }
            startWatching(target.snCode)
            return START_STICKY
        }

        // 关水进行中保留现有的前台状态，等关水流程结束时再刷新。
        if (intent?.action == ACTION_REFRESH_NOTIFICATION && finishingCount.get() > 0) {
            return START_STICKY
        }

        // 两种「结束用水」请求，流程完全一样：
        //   ACTION_FINISH      —— 用户在小组件上点了停止
        //   ACTION_STOP_SHOWER —— 通知栏那条上的「结束使用」按钮
        if (intent?.action == ACTION_FINISH || intent?.action == Notifier.ACTION_STOP_SHOWER) {
            val sn = intent.getStringExtra(EXTRA_SNCODE).orEmpty()
            val target = ShowerOrderTarget.fromIntent(intent)
            if (target == null || target.snCode != sn || target.currentOrder() == null) {
                WidgetBridge.clearBusy()
                LinYuWidget.refreshAll(this)
                val orders = PrefsHelper.getActiveOrders()
                if (orders.isEmpty()) {
                    if (finishingCount.get() == 0 && watchJobs.isEmpty()) {
                        startForeground(ID_STALE_STOP, Notifier.minimal(this))
                        stopSelf(startId)
                    }
                } else {
                    attachForeground(inUseNotification(orders))
                    monitorSerials(orders).forEach(::startWatching)
                }
                return START_NOT_STICKY
            }
            finishingCount.incrementAndGet()
            watchJobs.remove(sn)?.cancel()
            // 先挂前台通知：用 startForegroundService 起的服务，5 秒内不挂就会被系统崩掉
            attachForeground(Notifier.showStopping(this, deviceNameFor(sn)))
            scope.launch {
                try {
                    doFinish(target)
                } finally {
                    finishingCount.decrementAndGet()
                    if (PrefsHelper.isLoggedIn && ShowerController.isRunning(sn)) {
                        startWatching(sn)
                    }
                    updateForegroundOrStop()
                }
            }
            return START_NOT_STICKY
        }

        if (!PrefsHelper.isLoggedIn) {
            stopSelf()
            return START_NOT_STICKY
        }

        val activeOrders = PrefsHelper.getActiveOrders()
        val requestedSn = intent?.getStringExtra(EXTRA_SNCODE)?.takeIf { it.isNotEmpty() }
        val serials = requestedSn?.let(::listOf) ?: monitorSerials(activeOrders)
        if (serials.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        attachForeground(inUseNotification(activeOrders))
        serials.forEach(::startWatching)
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (attachedInUse) Notifier.cancelInUse(this)
        super.onDestroy()
    }

    // ════════════════════════════════════════════

    private suspend fun watch(snCode: String) {
        AppLogger.i("ShowerWatch 开始监控 $snCode")
        while (scope.isActive) {
            // 快关停了就精确等到那一刻，否则 15 秒一轮
            val remain = PrefsHelper.getAutoDisconRemain(snCode)
            delay(if (remain in 1..14) remain * 1000L else 15_000L)

            if (!PrefsHelper.isLoggedIn) break
            if (ShowerController.isClosing(snCode)) continue
            if (!ShowerController.isRunning(snCode)) break
            val target = ShowerOrderTarget.capture(snCode) ?: continue

            // ⚠️ 蓝牙表**不能**用云端订单判断：它没有 4G，服务端 tcpDevice 通道
            // 里根本没有它的订单，queryUsing 永远回 data=null → 会被当成
            // 「订单没了」误触发自动结算（水其实还在流）。
            // 蓝牙表的结束只来自用户主动停止（App / 通知栏按钮），看本地状态即可。
            val status = if (PrefsHelper.isBleDevice(snCode)) {
                if (ShowerController.isRunning(snCode)) OrderPresence.Original(target.orderNo)
                else OrderPresence.Ended()
            } else {
                ShowerController.orderPresence(snCode, target.orderNo)
            }
            if (ShowerController.isClosing(snCode)) continue
            if (target.currentOrder() == null) continue

            if (status is OrderPresence.Ended && finish(target, status.replacingOrderOwner)) return
        }
        AppLogger.i("ShowerWatch 退出监控 $snCode")
    }

    /** 检测到用水结束。手动关阀正在等待确认时，继续监控，不抢先结算。 */
    private suspend fun finish(target: ShowerOrderTarget, replacingOrderOwner: Boolean?): Boolean {
        val snCode = target.snCode
        // 已经被别处清理过了（比如用户在 App 里手动停的）——不重复处理
        if (!ShowerController.isRunning(snCode)) {
            return true
        }

        val startedAt = target.startedAtMs
        val elapsed = elapsedSec(startedAt)
        val deviceName = deviceNameFor(snCode)
        val orderNo = target.currentOrder()?.orderNo ?: return !ShowerController.isRunning(snCode)
        val settlementContext = try { com.hualala.linyu.data.ProjectAuthRepository.forDevice(snCode) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { return true }

        // ⚠️ 顺序要紧：先清本地状态，小组件的 isRunning() 才会变 false。
        // 以前没有这一步，所以自动关停后小组件永远停在「使用中」。
        if (!ShowerController.markFinishedFromWatch(target)) return !ShowerController.isRunning(snCode)
        finishingCount.incrementAndGet()
        // 旧账单结算期间，同一设备的新订单也能注册自己的监控。
        currentCoroutineContext()[Job]?.let { watchJobs.remove(snCode, it) }
        try {
            LinYuWidget.refreshAll(this)

            // 状态先更新，金额到达后更新原通知。
            val automatic = replacingOrderOwner == null
            val noticeID = if (automatic) Notifier.ID_AUTO_CLOSED else Notifier.ID_FINISHED
            val title = if (automatic) "设备已自动关停" else "使用结束"
            Notifier.showSettling(this, noticeID, "$title · $deviceName", elapsed)
            val finished = ShowerEvents.Finished(
                snCode, deviceName, elapsed, null, autoClosed = automatic, target = target,
                replacingOrderOwner = replacingOrderOwner
            )
            if (!automatic) ShowerEvents.notifyFinished(finished)

            val money = try {
                SettlementEngine.settleAmount(orderNo, startedAt, snCode, deviceName, settlementContext)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
            LinYuWidget.refreshAll(this)

            if (automatic) Notifier.showAutoClosed(this, deviceName, elapsed, money)
            else Notifier.showFinished(this, deviceName, elapsed, money)
            AppLogger.i("ShowerWatch 检测到结束 $snCode 用时 ${elapsed}s 消费 $money")

            if (automatic) ShowerEvents.notifyFinished(finished.copy(money = money))
        } finally {
            finishingCount.decrementAndGet()
        }
        return true
    }

    /**
     * 用户主动结束用水。
     *
     * 关阀确认和账单结算合起来可能超过小组件广播
     * `goAsync()` 约 10 秒的预算——在广播里做的话，最后那条带金额的通知发不出去。
     * 服务没有这个时限，所以整条流程都在这儿。
     */
    private suspend fun doFinish(target: ShowerOrderTarget) {
        val snCode = target.snCode
        // 压根没在用水就别走这套。
        // 少了这道检查，点一下通知栏的「结束用水」（那时已经没有活跃订单了）
        // 会跑完整个流程：closeValve 失败、settleAmount 因为 startedAt=0
        // 拿回上一次的账单金额，最后弹一条「用时 0 秒 · 消费 ¥上次的金额」的假通知。
        if (target.currentOrder() == null || ShowerController.isClosing(snCode)) {
            // 小组件的 busy 归服务管（见 LinYuWidgetProvider.markDelegated），
            // 这条提前返回也必须清掉，否则卡片会永远卡在「正在关闭…」
            WidgetBridge.clearBusy()
            LinYuWidget.refreshAll(this)
            return
        }

        val startedAt = target.startedAtMs
        val elapsed = elapsedSec(startedAt)
        val deviceName = deviceNameFor(snCode)

        // 蓝牙表：云端 `closeOrder` 对它不适用（orderNo 为空会报「订单号不能为空」），
        // 关阀要靠 App 内存里的 BLE 连接。服务没有这条连接，只能发事件让 App 走
        // stopBleShower。App 进程不在（连接已丢）时，App 也关不了，如实提示用户。
        if (PrefsHelper.isBleDevice(snCode)) {
            AppLogger.i("ShowerWatch 检测到蓝牙表 $snCode，转发给 App 走 BLE 关阀")
            WidgetBridge.clearBusy()
            LinYuWidget.refreshAll(this)
            ShowerEvents.notifyStopBleRequest(snCode, target)
            return
        }

        // ⚠️ orderNo 必须在**关阀之前**敲定，所以它在 `markFinished` 之前读——
        // 本地清理会把活跃订单删掉，之后就再也读不到（`queryUsing` 也问不出来了，
        // 因为订单已经没了）。而开阀后 orderNo 是异步轮询补上的，
        // 「开完水马上停」时本地存的还是空串，那就白丢了结算用的那个直答接口。
        val orderNo = ShowerController.resolveOrderNo(snCode, target)
        val settlementContext = try { com.hualala.linyu.data.ProjectAuthRepository.forDevice(snCode) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            WidgetBridge.clearBusy()
            LinYuWidget.refreshAll(this)
            Notifier.showCloseFailed(this, deviceName, e.message ?: "设备账户不可用")
            return
        }

        // 保留原订单、项目账户和开始时间直到关阀确认；小组件 busy 提供“正在关闭”反馈。
        // ⚠️ 这里**故意不清 busy**：清了小组件会立刻变成「空闲」，
        // 用户在等待关阀确认时看不到操作进度。
        // 留着它，卡片会一直显示「正在关闭…」，等关阀完再一起清（见下）。
        LinYuWidget.refreshAll(this)

        // 关阀结果决定后续分支；失败和未确认都不能进入结算或完成通知。
        val closeResult = ShowerController.closeValve(snCode, orderNo, target)

        when (closeDisposition(closeResult)) {
            CloseDisposition.RESTORE_FAILED -> {
                val message = (closeResult as CloseOutcome.Failed).message
                AppLogger.w("ShowerWatch 关阀失败 $snCode: $message")
                WidgetBridge.clearBusy()
                LinYuWidget.refreshAll(this)
                Notifier.showCloseFailed(this, deviceName, message)
                ShowerEvents.notifyOrdersRestored()
                return
            }

            CloseDisposition.RESTORE_UNCONFIRMED -> {
                val message = (closeResult as CloseOutcome.Unconfirmed).message
                AppLogger.w("ShowerWatch 停止未确认 $snCode: $message")
                WidgetBridge.clearBusy()
                LinYuWidget.refreshAll(this)
                Notifier.showCloseUnconfirmed(this, deviceName)
                ShowerEvents.notifyOrdersRestored()
                return
            }

            CloseDisposition.COMPLETE -> Unit
        }

        // 关阀走完了才收掉「正在关闭…」，卡片从它直接跳到「空闲」，
        // 中间不会再闪一下「使用中」
        WidgetBridge.clearBusy()
        LinYuWidget.refreshAll(this)

        // 只有关阀确认成功后，App 才能退出使用页并展示完成状态。
        ShowerEvents.notifyFinished(
            ShowerEvents.Finished(snCode, deviceName, elapsed, null, autoClosed = false, target = target,
                replacingOrderOwner = (closeResult as CloseOutcome.Closed).replacingOrderOwner)
        )

        // 关阀已经确认，账单还在路上——先挂一条「结算中」。
        // 它和结束通知**用的是同一个 id**，所以下面 showFinished 是**原地更新**，
        // 用户看到的是同一条通知从「结算中」变成「消费 ¥x.xx」，不会蹦出两条。
        Notifier.showSettling(this, Notifier.ID_FINISHED, "使用结束 · $deviceName", elapsed)

        val money = try {
            SettlementEngine.settleAmount(orderNo, startedAt, snCode, deviceName, settlementContext)
        } catch (_: Exception) { null }
        LinYuWidget.refreshAll(this)

        Notifier.showFinished(this, deviceName, elapsed, money)
        AppLogger.i("ShowerWatch 用户结束 $snCode 用时 ${elapsed}s 消费 $money")
    }

    /** 关闭常规提醒时也保留前台运行，由 Notifier 提供低优先级的后台提示。 */
    private fun attachForeground(n: android.app.Notification) {
        startForeground(Notifier.ID_IN_USE, n)
        attachedInUse = true
    }

    private fun elapsedSec(startedAt: Long): Int =
        if (startedAt > 0) ((System.currentTimeMillis() - startedAt) / 1000).toInt() else 0

    private fun startWatching(snCode: String) {
        val job = watchJobs.getOrStart(snCode) {
            scope.launch(start = CoroutineStart.LAZY) {
                val self = currentCoroutineContext()[Job] ?: return@launch
                try {
                    watch(snCode)
                } finally {
                    watchJobs.remove(snCode, self)
                    updateForegroundOrStop()
                }
            }
        }
        job.start()
    }

    private fun updateForegroundOrStop() {
        if (watchJobs.isEmpty() && finishingCount.get() == 0) {
            stopSelf()
            return
        }
        if (!PrefsHelper.isLoggedIn || finishingCount.get() > 0) return
        val activeOrders = PrefsHelper.getActiveOrders()
        if (activeOrders.isNotEmpty()) attachForeground(inUseNotification(activeOrders))
    }

    private fun inUseNotification(orders: List<com.hualala.linyu.model.ActiveOrder>): android.app.Notification {
        if (orders.size > 1) return Notifier.showInUseSummary(this, orders.size)
        // ⚠️ 绝不能 `first()`：这里是前台服务的主线程，抛异常 = 整个进程死掉，
        // BLE 连接、计时、结算监控全部陪葬（09-29 实测过一次：蓝牙开阀成功后
        // 活跃订单没落盘，列表为空，进程当场崩掉，水关不了）。
        // 列表为空说明本地状态丢了，用「上次设备」兜底出一条通知，保住服务。
        val order = orders.firstOrNull()
            ?: return Notifier.showInUse(
                this,
                PrefsHelper.lastDeviceSnCode,
                PrefsHelper.lastDeviceName.ifEmpty { "热水器" },
                PrefsHelper.getStartedAt(PrefsHelper.lastDeviceSnCode)
            )
        return Notifier.showInUse(
            this,
            order.snCode,
            order.deviceName.ifEmpty { "热水器" },
            PrefsHelper.getStartedAt(order.snCode)
        )
    }

    private fun deviceNameFor(snCode: String): String =
        ShowerController.activeOrderFor(snCode)?.deviceName?.takeIf { it.isNotEmpty() }
            ?: PrefsHelper.lastDeviceName.ifEmpty { "热水器" }

    private fun ensureInit() {
        if (!PrefsHelper.isInitialized) PrefsHelper.init(applicationContext)
        AppLogger.init(applicationContext)
        NetworkModule.restoreFromPrefs()
    }

    companion object {
        private const val ID_STALE_STOP = 1006
        const val EXTRA_SNCODE = "snCode"
        const val ACTION_FINISH = "com.hualala.linyu.service.ACTION_FINISH"
        private const val ACTION_REFRESH_NOTIFICATION = "com.hualala.linyu.service.REFRESH_NOTIFICATION"
        private const val ACTION_MONITOR_EXISTING_ORDER = "com.hualala.linyu.service.MONITOR_EXISTING_ORDER"

        /** 从设置页更新活跃订单的通知，已有监控任务由 watchJobs 去重。 */
        internal fun refreshNotification(context: Context) {
            if (PrefsHelper.getActiveOrders().isEmpty()) return
            val intent = Intent(context, ShowerWatchService::class.java).setAction(ACTION_REFRESH_NOTIFICATION)
            launch(context, intent, "refresh notification")
        }

        internal fun refreshNotification(context: Context, target: ShowerOrderTarget) {
            if (target.currentOrder() == null) return
            val intent = Intent(context, ShowerWatchService::class.java).setAction(ACTION_MONITOR_EXISTING_ORDER)
            target.putInto(intent)
            launch(context, intent, "monitor existing order")
        }

        /** 开始（或确保正在）监控某台设备 */
        fun start(context: Context, snCode: String) {
            if (snCode.isEmpty()) return
            val i = Intent(context, ShowerWatchService::class.java).apply {
                putExtra(EXTRA_SNCODE, snCode)
            }
            launch(context, i, "start")
        }

        /**
         * 请求结束用水（小组件点停止走这条）。
         *
         * 之所以不直接在小组件里做：关阀 + 结算要 12 秒以上，
         * 超过广播 `goAsync()` 约 10 秒的预算。
         */
        fun finishShower(context: Context, snCode: String, target: ShowerOrderTarget? = ShowerOrderTarget.capture(snCode)) {
            if (snCode.isEmpty()) return
            val i = Intent(context, ShowerWatchService::class.java).apply {
                action = ACTION_FINISH
                putExtra(EXTRA_SNCODE, snCode)
                target?.putInto(this)
            }
            launch(context, i, "finish")
        }

        /**
         * 起服务，失败要**记日志**。
         *
         * 原来这里是 `runCatching {}` 一包了事——启动失败没有任何痕迹，
         * 表现就是「小组件开阀后没有使用中通知，也没有自动关停」，但日志里一片正常，
         * 完全查不出来。Android 12+ 在后台起前台服务本来就可能被拒
         * （`ForegroundServiceStartNotAllowedException`），这属于**预期内**的失败，
         * 降级成普通后台服务再试一次：拿不到前台待遇，但进程还活着就还能监控。
         */
        private fun launch(context: Context, intent: Intent, tag: String) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                AppLogger.w("前台服务启动被拒（$tag）：${e.javaClass.simpleName} ${e.message}，降级为普通服务")
                runCatching { context.startService(intent) }
                    .onFailure { AppLogger.e("普通服务也起不来（$tag）", it) }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ShowerWatchService::class.java)) }
        }
    }
}
