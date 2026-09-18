package com.hualala.linyu.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.queryUsingSafe
import com.hualala.linyu.data.CloseOutcome
import com.hualala.linyu.data.ShowerController
import com.hualala.linyu.data.ShowerEvents
import com.hualala.linyu.utils.AppLogger
import com.hualala.linyu.utils.Notifier
import com.hualala.linyu.utils.PrefsHelper
import com.hualala.linyu.widget.LinYuWidget
import com.hualala.linyu.widget.WidgetBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 这个进程可能是被 startForegroundService 冷启动的，
        // PrefsHelper / NetworkModule / AppLogger 都还没初始化
        ensureInit()

        // 两种「结束用水」请求，流程完全一样：
        //   ACTION_FINISH      —— 用户在小组件上点了停止
        //   ACTION_STOP_SHOWER —— 通知栏那条上的「结束使用」按钮
        if (intent?.action == ACTION_FINISH || intent?.action == Notifier.ACTION_STOP_SHOWER) {
            val sn = intent.getStringExtra(EXTRA_SNCODE)?.takeIf { it.isNotEmpty() }
                ?: PrefsHelper.lastDeviceSnCode
            if (sn.isEmpty()) {
                stopSelf()
                return START_NOT_STICKY
            }
            // 先挂前台通知：用 startForegroundService 起的服务，5 秒内不挂就会被系统崩掉
            attachForeground(
                Notifier.showStopping(this, PrefsHelper.lastDeviceName.ifEmpty { "热水器" })
            )
            scope.launch { doFinish(sn); stopSelf() }
            return START_NOT_STICKY
        }

        val snCode = intent?.getStringExtra(EXTRA_SNCODE)?.takeIf { it.isNotEmpty() }
            ?: PrefsHelper.lastDeviceSnCode

        if (snCode.isEmpty() || !PrefsHelper.isLoggedIn) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 前台服务必须在 5 秒内挂上通知，否则系统直接崩掉这个服务
        attachForeground(
            Notifier.showInUse(
                this,
                PrefsHelper.lastDeviceName.ifEmpty { "热水器" },
                PrefsHelper.getStartedAt(snCode)
            )
        )

        if (watchJob?.isActive != true) {
            watchJob = scope.launch { watch(snCode) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        Notifier.cancelInUse(this)
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

            when (orderRunning(snCode)) {
                true -> Unit                    // 还在用，继续
                false -> {                      // 订单没了 → 结束
                    finish(snCode)
                    return
                }
                null -> Unit                    // 请求失败，不确定，下轮再说
            }
        }
        AppLogger.i("ShowerWatch 退出监控 $snCode")
    }

    /**
     * 服务端还有没有这个订单。
     *
     * @return true 有 / false **明确**没有 / null 请求失败、不确定
     *
     * ⚠️ 三态很重要：把「网络失败」当成「已结束」会在信号差的时候
     * 凭空发一条「使用结束」并把小组件切回空闲，而水其实还在流。
     */
    private suspend fun orderRunning(snCode: String): Boolean? = try {
        val q = NetworkModule.apiService.queryUsingSafe(
            snCode = snCode, auth = NetworkModule.authFields()
        )
        when {
            q.errorCode == 307 || (q.success && q.data?.orderNo != null) -> true
            q.success -> false
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** 检测到用水结束：结算 → 清理本地状态 → 刷新小组件 → 通知 */
    private suspend fun finish(snCode: String) {
        // 已经被别处清理过了（比如用户在 App 里手动停的）——不重复处理
        if (!ShowerController.isRunning(snCode)) {
            stopSelf()
            return
        }

        val startedAt = PrefsHelper.getStartedAt(snCode)
        val elapsed = elapsedSec(startedAt)
        val deviceName = PrefsHelper.lastDeviceName.ifEmpty { "热水器" }
        val orderNo = ShowerController.activeOrderFor(snCode)?.orderNo ?: ""

        // ⚠️ 顺序要紧：先清本地状态，小组件的 isRunning() 才会变 false。
        // 以前没有这一步，所以自动关停后小组件永远停在「使用中」。
        ShowerController.markFinished(snCode)
        LinYuWidget.refreshAll(this)
        Notifier.cancelInUse(this)

        // 结算要轮询账单（最多约 7 秒），放在清理之后——金额晚一点到没关系，
        // 状态先对上是第一位的
        // 同上：先挂「结算中」，拿到金额后原地更新成自动关停通知
        Notifier.showSettling(this, Notifier.ID_AUTO_CLOSED, "设备已自动关停 · $deviceName", elapsed)

        val money = try {
            ShowerController.settleAmount(orderNo, startedAt, snCode)
        } catch (_: Exception) { null }

        Notifier.showAutoClosed(this, deviceName, elapsed, money)
        AppLogger.i("ShowerWatch 检测到结束 $snCode 用时 ${elapsed}s 消费 $money")

        // 通知 App：如果洗澡界面正开着，让它把弹窗弹出来并同步内存状态。
        // App 不在的话这个事件没人收，也不影响——状态和通知上面都处理完了。
        ShowerEvents.notifyFinished(
            ShowerEvents.Finished(snCode, deviceName, elapsed, money, autoClosed = true)
        )
        stopSelf()
    }

    /**
     * 用户主动结束用水。
     *
     * 关阀确认要 5 秒、账单结算最多再要 7 秒，合起来超过小组件广播
     * `goAsync()` 约 10 秒的预算——在广播里做的话，最后那条带金额的通知发不出去。
     * 服务没有这个时限，所以整条流程都在这儿。
     */
    private suspend fun doFinish(snCode: String) {
        // 压根没在用水就别走这套。
        // 少了这道检查，点一下通知栏的「结束用水」（那时已经没有活跃订单了）
        // 会跑完整个流程：closeValve 失败、settleAmount 因为 startedAt=0
        // 拿回上一次的账单金额，最后弹一条「用时 0 秒 · 消费 ¥上次的金额」的假通知。
        if (!ShowerController.isRunning(snCode)) {
            // 小组件的 busy 归服务管（见 LinYuWidgetProvider.markDelegated），
            // 这条提前返回也必须清掉，否则卡片会永远卡在「正在关闭…」
            WidgetBridge.clearBusy()
            LinYuWidget.refreshAll(this)
            Notifier.cancelInUse(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        val startedAt = PrefsHelper.getStartedAt(snCode)
        val elapsed = elapsedSec(startedAt)
        val deviceName = PrefsHelper.lastDeviceName.ifEmpty { "热水器" }

        // ⚠️ orderNo 必须在**关阀之前**敲定，所以它在 `markFinished` 之前读——
        // 本地清理会把活跃订单删掉，之后就再也读不到（`queryUsing` 也问不出来了，
        // 因为订单已经没了）。而开阀后 orderNo 是异步轮询补上的，
        // 「开完水马上停」时本地存的还是空串，那就白丢了结算用的那个直答接口。
        val orderNo = ShowerController.resolveOrderNo(snCode)

        // ── 第一阶段：立刻响应 ──
        //
        // ⚠️ 顺序很要紧。原来是「先关阀、再清状态」，而关阀要轮询确认最多 5 秒，
        // 于是用户点了结束之后界面要愣 5 秒才动，中间这几件事全都对不上：
        //   · 使用页不退出
        //   · 「上次使用设备」还显示「恢复」
        //   · 这时候点「恢复」能进使用页，但 startedAt 已被清成 0 → 计时显示 0
        // 现在把本地清理提到关阀前面，用户点完当场就有反馈。
        ShowerController.markFinished(snCode)
        // ⚠️ 这里**故意不清 busy**：清了小组件会立刻变成「空闲」，
        // 用户在关阀那 5 秒里看不到任何"正在进行"的反馈。
        // 留着它，卡片会一直显示「正在关闭…」，等关阀完再一起清（见下）。
        LinYuWidget.refreshAll(this)
        Notifier.cancelInUse(this)
        stopForeground(STOP_FOREGROUND_REMOVE)

        // 带上金额 0：这时候还没结算。App 只拿它来退出使用页，
        // 真正的金额走下面那条通知。
        ShowerEvents.notifyFinished(
            ShowerEvents.Finished(snCode, deviceName, elapsed, 0.0, autoClosed = false)
        )

        // ── 第二阶段：慢活放后面 ──
        // 关阀确认 5 秒 + 账单结算最多 7 秒。用户已经在界面上看到结束了，
        // 这两步纯粹是收尾，慢一点没关系。
        val closeResult = ShowerController.closeValve(snCode, orderNo)
        if (closeResult is CloseOutcome.Failed) {
            AppLogger.w("ShowerWatch 关阀失败 $snCode: ${closeResult.message}")
        }

        // 关阀走完了才收掉「正在关闭…」，卡片从它直接跳到「空闲」，
        // 中间不会再闪一下「使用中」
        WidgetBridge.clearBusy()
        LinYuWidget.refreshAll(this)

        // 关阀已经确认，账单还在路上——先挂一条「结算中」。
        // 它和结束通知**用的是同一个 id**，所以下面 showFinished 是**原地更新**，
        // 用户看到的是同一条通知从「结算中」变成「消费 ¥x.xx」，不会蹦出两条。
        Notifier.showSettling(this, Notifier.ID_FINISHED, "使用结束 · $deviceName", elapsed)

        val money = try {
            ShowerController.settleAmount(orderNo, startedAt, snCode)
        } catch (_: Exception) { null }

        Notifier.showFinished(this, deviceName, elapsed, money)
        AppLogger.i("ShowerWatch 用户结束 $snCode 用时 ${elapsed}s 消费 $money")
    }

    /**
     * 挂上前台通知。
     *
     * 通知总开关关掉时**先挂再摘**：Android 不允许前台服务没有通知，
     * 而且用 `startForegroundService` 起的服务 5 秒内不挂就会被系统直接崩掉，
     * 所以只能先满足它、再立刻把通知拿掉。
     *
     * 代价是服务降级成普通后台服务，被系统回收的概率会高一些——
     * 极端情况下（内存紧张 / 国产 ROM 清后台）自动关停可能来不及跑。
     */
    private fun attachForeground(n: android.app.Notification) {
        startForeground(Notifier.ID_IN_USE, n)
        // 通知被关掉时（总开关，或单独关掉「用水状态通知」）**先挂再摘**。
        // Android 不允许前台服务没有通知，而且用 startForegroundService 起的服务
        // 5 秒内不挂还会被系统直接崩掉，所以只能先满足它、再立刻拿掉。
        //
        // 代价：服务降级成普通后台服务，被系统回收的概率会高一些。
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyInUse) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun elapsedSec(startedAt: Long): Int =
        if (startedAt > 0) ((System.currentTimeMillis() - startedAt) / 1000).toInt() else 0

    private fun ensureInit() {
        if (!PrefsHelper.isInitialized) PrefsHelper.init(applicationContext)
        AppLogger.init(applicationContext)
        NetworkModule.restoreFromPrefs()
    }

    companion object {
        const val EXTRA_SNCODE = "snCode"
        const val ACTION_FINISH = "com.hualala.linyu.service.ACTION_FINISH"

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
        fun finishShower(context: Context, snCode: String) {
            if (snCode.isEmpty()) return
            val i = Intent(context, ShowerWatchService::class.java).apply {
                action = ACTION_FINISH
                putExtra(EXTRA_SNCODE, snCode)
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
