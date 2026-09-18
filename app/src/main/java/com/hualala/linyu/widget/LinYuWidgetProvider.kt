package com.hualala.linyu.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.data.CloseOutcome
import com.hualala.linyu.data.OpenOutcome
import com.hualala.linyu.data.ShowerController
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.service.ShowerWatchService
import com.hualala.linyu.utils.AppLogger
import com.hualala.linyu.utils.Notifier
import com.hualala.linyu.utils.PrefsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 桌面小组件。
 *
 * 只做三件事：渲染、承接按钮点击、调 [ShowerController]。
 * 所有业务逻辑都在 ShowerController 里，和 App 内是同一份。
 *
 * ## 为什么不用 WorkManager / Service
 * 小组件的点击是用户主动触发的广播，`goAsync()` 就能撑住几秒的网络往返，
 * 没必要为这点事引入后台任务框架。真正需要长时间跑的场景（实时消费推送）
 * 小组件也不做，见下方「已知取舍」。
 *
 * ## 已知取舍
 * - 不连 MQTT，所以使用中看不到实时消费金额，只显示预扣
 * - 停止后不做账单结算（要轮询最多 7 秒，超出广播预算），打开 App 会正常结算
 * - 开阀/关阀的确认轮询有 6 秒预算，超时显示「状态未知」而不是假装成功
 */
open class LinYuWidgetProvider : AppWidgetProvider() {

    /** 子类指定自己的尺寸 */
    open val size: WidgetSize get() = WidgetSize.SMALL

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        WidgetBridge.ensureInit(context)
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, render(context, id))
        }
    }

    /**
     * 用户拖动改变了小组件尺寸。
     *
     * 布局本身不再随尺寸切换了（2x2 只有一套，靠 weight 自适应），
     * 留着这个回调是为了顺手重新渲染一次，让拉伸后的内容跟当前状态对齐。
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        WidgetBridge.ensureInit(context)
        WidgetBridge.render(context, appWidgetId, size)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // 清掉这个 widget 的过渡态，免得反复增删后残留一堆无用条目
        appWidgetIds.forEach {
            WidgetBridge.forget(it)
            PrefsHelper.clearWidgetTab(it)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 先让父类处理 APPWIDGET_UPDATE / ENABLED / DISABLED / DELETED 这些系统广播
        super.onReceive(context, intent)

        val action = intent.action ?: return
        if (action != ACTION_START && action != ACTION_STOP &&
            action != ACTION_REFRESH && action != ACTION_SET_TAB &&
            action != ACTION_PICK_DEVICE
        ) return

        val id = intent.getIntExtra(EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return

        // 切换 2x4 页面：存下选中项再重绘，不需要走网络，同步处理即可
        if (action == ACTION_SET_TAB) {
            WidgetBridge.ensureInit(context)
            PrefsHelper.setWidgetTab(id, intent.getIntExtra(EXTRA_TAB, 0))
            WidgetBridge.renderId(context, id)
            return
        }

        val pickMac = intent.getStringExtra(EXTRA_PICK_MAC) ?: ""

        // goAsync：告诉系统"这个广播还没处理完，别回收进程"，最多约 10 秒
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handleAction(context, id, action, pickMac)
            } catch (e: Exception) {
                // 小组件里没有地方弹错误，只能落日志；界面靠重新渲染回落到真实状态
                AppLogger.e("Widget action failed: $action", e)
            } finally {
                finishAction(context, pending)
            }
        }
    }

    /**
     * 广播收尾。
     *
     * 正常路径：清掉「进行中」再重绘——必须刷新桌面上的**所有**小组件，不能只刷被点的那个。
     * 2x2 和 2x4 可能同时摆在桌面上，它们读的是同一份 Prefs，只刷一个的话另一个会一直停在旧状态
     * （点了 2x2 开阀，2x4 还显示「空闲」）。
     *
     * 例外：本次操作留了一条要短暂展示的提示（「选用」失败）。小组件没有 toast 可弹，
     * 所以先把提示画出来，几秒后再清掉重绘。
     */
    private fun finishAction(context: Context, pending: BroadcastReceiver.PendingResult) {
        // 这次操作已经交给前台服务了（停止用水），busy 归服务管，这里什么都不做
        if (WidgetBridge.consumeDelegated()) {
            pending.finish()
            return
        }
        val notice = WidgetBridge.takeNotice()
        if (notice == null) {
            WidgetBridge.clearBusy()
            WidgetBridge.renderAll(context)
            pending.finish()
            return
        }
        WidgetBridge.renderAll(context)
        Handler(Looper.getMainLooper()).postDelayed({
            // 只在提示还挂着时清它。这几秒里用户又点了别的按钮的话，
            // busy 已经是那次操作的状态了，别去动它
            WidgetBridge.clearNotice(notice)
            WidgetBridge.renderAll(context)
        }, NOTICE_DURATION_MS)
        pending.finish()
    }

    private suspend fun handleAction(
        context: Context,
        appWidgetId: Int,
        action: String,
        pickMac: String = ""
    ) {
        WidgetBridge.ensureInit(context)

        // 「选用」只换设备、不开阀，所以放在下面的 snCode 校验之前——
        // 这个动作本来就是要把「还没有设备」变成「有设备」
        if (action == ACTION_PICK_DEVICE) {
            WidgetBridge.markBusy(WidgetRenderer.DisabledReason.SWITCHING)
            // 先切回设备控制页再重绘：用户点完"选用"最想看的就是那一页，
            // 而切换又需要一次网络往返，正好利用这段时间显示「正在切换设备…」
            PrefsHelper.setWidgetTab(appWidgetId, 0)
            WidgetBridge.renderAll(context)

            if (ShowerController.pickDevice(pickMac)) {
                AppLogger.i("Widget 选用设备成功 ($pickMac)")
            } else {
                // 新设备没查出来就保持原样，只报个错，别把用户原来那台也弄丢
                AppLogger.w("Widget 选用设备失败 ($pickMac)")
                WidgetBridge.markNotice(WidgetRenderer.DisabledReason.PICK_FAILED)
            }
            return
        }

        WidgetBridge.markBusy(
            when (action) {
                ACTION_STOP -> WidgetRenderer.DisabledReason.STOPPING
                ACTION_REFRESH -> WidgetRenderer.DisabledReason.REFRESHING
                else -> WidgetRenderer.DisabledReason.STARTING
            }
        )
        // 先画一次"进行中"，让用户点下去立刻有反馈。
        // 必须是 renderAll：操作状态是全局的，桌面上每个淋浴小组件都该同时进入
        // 「正在开启…/正在关闭…」，只重绘被点的那个会让另一个看起来没反应
        WidgetBridge.renderAll(context)

        val snCode = PrefsHelper.lastDeviceSnCode
        if (snCode.isNullOrEmpty() || !PrefsHelper.isLoggedIn) {
            WidgetBridge.clearBusy()
            return
        }

        // ⚠️ 寝室闸门。`readState()` 里也有一道，但那一处只管**显示**——
        // 开阀真正走的是这里，而这里读的是 Prefs 里的 lastDeviceSnCode，不经过渲染。
        // 少了这道判断，就会出现「桌面显示『请先选择设备』，点下去却把别寝室的阀开了」：
        // 用户看不到自己在开哪台，钱却已经花了。
        //
        // ⚠️ **只拦开阀**。停止和刷新必须放行——正在用水时卡片可能显示的是别的状态，
        // 但水还在流，用户得有办法关掉它。把停止也拦住等于让人关不了水。
        if (action == ACTION_START &&
            !DeviceInfo.inSameRoom(PrefsHelper.boundRoom, ShowerController.roomFilterName())
        ) {
            AppLogger.w("Widget 开阀被寝室筛选拦下：${PrefsHelper.lastDeviceName}")
            WidgetBridge.clearBusy()
            WidgetBridge.renderAll(context)
            return
        }

        when (action) {
            ACTION_START -> when (val outcome = ShowerController.openValve(snCode)) {
                // Opened / Resumed：状态已落盘，重新渲染就会变成「使用中」
                is OpenOutcome.Opened, is OpenOutcome.Resumed -> {
                    AppLogger.i("Widget 开阀成功 ($snCode)")
                    // 开起来了，之前记下的「占用中」不再成立
                    PrefsHelper.occupiedSnCode = ""
                    // 先把「使用中」通知发出去，**再**去起服务。
                    //
                    // 顺序很重要：通知本来就由服务负责挂（前台服务必须有一条），
                    // 但服务是 startForegroundService 冷启动的，要等它起来才看得到通知；
                    // 而小组件点一下的时候 App 在后台，服务能不能起来还不一定
                    // （Android 12+ 对后台启动前台服务有限制）。先自己发一条，
                    // 用户点完立刻能看到反馈，服务随后起来也只是更新同一条（id 相同）。
                    Notifier.showInUse(
                        context,
                        PrefsHelper.lastDeviceName.ifEmpty { "热水器" },
                        PrefsHelper.getStartedAt(snCode)
                    )
                    // 交给前台服务持续盯着：超时自动关停、被外部关闭，都要能立刻发现
                    ShowerWatchService.start(context, snCode)
                }

                /**
                 * 设备正被别人用着。
                 *
                 * ⚠️ 这里**绝不能**当成 Resumed 处理——那会把别人的订单记成自己的，
                 * 卡片显示「使用中」并开始计时，用户点停止时还会拿别人的 orderNo 去关阀。
                 * App 内的设备详情弹窗靠 isOwner 把按钮置灰，小组件没有界面，
                 * 只能在这里拦住，然后用徽章 + 短暂提示告诉用户。
                 */
                is OpenOutcome.InUseByOthers -> {
                    AppLogger.w("Widget 开阀被拒（他人使用中）$snCode")
                    PrefsHelper.occupiedSnCode = snCode
                    WidgetBridge.markNotice(WidgetRenderer.DisabledReason.IN_USE_BY_OTHERS)
                    Notifier.showOccupied(
                        context,
                        PrefsHelper.lastDeviceName.ifEmpty { "该设备" }
                    )
                }

                /**
                 * 开阀失败（服务端拒绝、余额不足、有未扣账单之类）。
                 *
                 * ⚠️ 以前这里**只写一行日志**，桌面上什么反应都没有——「正在开启…」
                 * 转完圈直接弹回原样，用户根本不知道失败了。
                 *
                 * 现在分两处说：
                 * - 卡片上闪 3 秒「开阀失败」（**不放服务端原话**——那句话能长到
                 *   「账户异常，请检查账户信息」，卡片那条面板一行根本放不下）
                 * - 完整原因交给**横幅通知**，用户从屏幕顶上能看全
                 */
                is OpenOutcome.Failed -> {
                    AppLogger.w("Widget 开阀失败: ${outcome.message}")
                    WidgetBridge.markNotice(WidgetRenderer.DisabledReason.FAILED)
                    Notifier.showOpenFailed(
                        context,
                        PrefsHelper.lastDeviceName.ifEmpty { "热水器" },
                        outcome.message
                    )
                }
                // 预算耗尽：不谎报成功也不谎报失败，改成"点击刷新"由用户手动对齐
                OpenOutcome.Unknown -> {
                    AppLogger.w("Widget 开阀结果未知（预算耗尽）")
                    WidgetBridge.markUnknown()
                }
            }

            /**
             * 停止也交给服务做。
             *
             * 关阀确认要 5 秒、账单结算最多再要 7 秒，合起来超过广播 `goAsync()` 约 10 秒的预算——
             * 在这里做完的话，最后那条带金额的通知根本发不出去。
             * 服务没有这个时限，所以整条停止流程（关阀 → 结算 → 通知）都搬过去了。
             */
            ACTION_STOP -> {
                AppLogger.i("Widget 请求停止 $snCode")
                // 告诉广播收尾：别清「正在关闭…」。关阀要 5 秒，
                // 广播是立刻返回的，清了 busy 卡片会先弹回「使用中」再变空闲，
                // 中间闪一下很难看。busy 交给服务完成后自己清。
                WidgetBridge.markDelegated()
                ShowerWatchService.finishShower(context, snCode)
            }

            // 「状态未知」状态下用户点按钮：按一次服务端真实状态，把本地对齐
            ACTION_REFRESH -> {
                val running = ShowerController.reconcile(snCode)
                AppLogger.i("Widget 手动刷新：服务端状态 running=$running")
                WidgetBridge.clearUnknown()
            }
        }
    }

    /** 渲染单个 widget（不含"进行中"覆盖） */
    private fun render(context: Context, id: Int): android.widget.RemoteViews {
        val disabled = WidgetBridge.busyReason()
        return WidgetRenderer.build(context, size, WidgetRenderer.readState(), id, disabled)
    }

    companion object {
        const val EXTRA_APPWIDGET_ID = "appWidgetId"
        const val ACTION_START = "com.hualala.linyu.widget.ACTION_START"
        const val ACTION_STOP = "com.hualala.linyu.widget.ACTION_STOP"
        const val ACTION_REFRESH = "com.hualala.linyu.widget.ACTION_REFRESH"
        const val ACTION_SET_TAB = "com.hualala.linyu.widget.ACTION_SET_TAB"
        const val ACTION_PICK_DEVICE = "com.hualala.linyu.widget.ACTION_PICK_DEVICE"
        const val EXTRA_TAB = "tab"
        const val EXTRA_PICK_MAC = "pickMac"

        /** 「选用」失败这类提示在桌面上停留多久 */
        private const val NOTICE_DURATION_MS = 3_000L
    }
}

/** 1x1 小组件入口：一个按钮开关热水 */
class LinYuWidget1x1 : LinYuWidgetProvider() {
    override val size = WidgetSize.TILE
}

/** 2x2 小组件入口 */
class LinYuWidget2x2 : LinYuWidgetProvider() {
    override val size = WidgetSize.SMALL
}

/** 2x4 小组件入口 */
class LinYuWidget2x4 : LinYuWidgetProvider() {
    override val size = WidgetSize.WIDE
}

/**
 * 供外部（Provider、App 内部）复用的工具方法。
 *
 * 拆出来是为了让 App 侧不用持有 Provider 实例也能刷新桌面——
 * AppWidgetProvider 由系统实例化，自己 new 一个语义上是错的。
 */
object LinYuWidget {

    /** App 内状态变化时调用，把桌面上的小组件全部刷新一遍 */
    fun refreshAll(context: Context) {
        WidgetBridge.ensureInit(context)
        // App 刚把状态对齐过，桌面上残留的"状态未知"不再成立。
        // 注意小组件自己操作完走的 [WidgetBridge.renderAll] 不带这一步——
        // 那时其他 widget 的"状态未知"仍然成立，不该被顺手清掉。
        WidgetBridge.clearAllUnknown()
        WidgetBridge.forEachWidget(context) { id, size ->
            WidgetBridge.render(context, id, size)
        }
    }
}

/** Provider 与 App 共用的内部实现 */
internal object WidgetBridge {

    private var initialized = false

    /**
     * 小组件可能在一个全新的进程里被唤起（App 完全没运行过），
     * 那时 PrefsHelper / NetworkModule 都还没初始化，必须先补上。
     */
    fun ensureInit(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        if (!PrefsHelper.isInitialized) PrefsHelper.init(app)
        AppLogger.init(app)
        NetworkModule.restoreFromPrefs()
        initialized = true
    }

    // ── 进行中状态 ──
    // 只存在内存里：进程被杀就丢，丢了也只是少一次"正在开启…"的过渡动画，不影响正确性
    // 全局一份，不按 widget id 分：
    // 一次操作本来就只可能有一个，按 id 分会造成"只有被点的那个显示正在关闭，
    // 另一个要等结束才同步"——用户明确反馈过这个问题
    private var busy: WidgetRenderer.DisabledReason? = null
    private var unknown = false

    /** 需要短暂停留在桌面上的提示（「选用」失败、开阀失败），由 [takeNotice] 取走 */
    private var notice: WidgetRenderer.DisabledReason? = null

    fun markBusy(reason: WidgetRenderer.DisabledReason) {
        notice = null            // 新操作开始，上一次留下的提示作废
        unknown = false
        busy = reason
    }

    /**
     * 记一条「过几秒自己消失」的提示。
     *
     * 小组件弹不了 toast，失败信息只能借「进行中」这个位置显示一会儿。
     * 显示时长由调用方（Provider 的 `postDelayed`）控制。
     */
    fun markNotice(reason: WidgetRenderer.DisabledReason) {
        unknown = false
        notice = reason
        busy = reason
    }

    /**
     * 这次操作交给了前台服务，busy 由服务完成后清。
     *
     * 用于「停止用水」：广播立刻返回，而关阀要 5 秒。若收尾时按常规清 busy，
     * 卡片会先弹回「使用中」（订单还在）、等关阀完再变空闲，中间闪一下。
     */
    private var delegated = false

    fun markDelegated() { delegated = true }

    /** 取走「已交给服务」标记。服务自己那条路径不走广播，所以取过即清 */
    fun consumeDelegated(): Boolean {
        val v = delegated
        delegated = false
        return v
    }

    /** 取走待展示的提示，取过即失效（提示只该展示一次） */
    fun takeNotice(): WidgetRenderer.DisabledReason? {
        val n = notice
        notice = null
        return n
    }

    /** 只在这个提示还挂在屏幕上时清掉它；期间用户又点了别的按钮就交给那次操作收尾 */
    fun clearNotice(reason: WidgetRenderer.DisabledReason) {
        if (busy == reason) busy = null
    }

    fun markUnknown() {
        busy = null
        unknown = true
    }

    fun clearUnknown() {
        unknown = false
    }

    fun clearBusy() {
        busy = null
        // unknown 不清：它表示"上一次操作结果未知"，要等用户手动刷新或 App 对齐后才消
    }

    /** widget 被从桌面删除时调用 */
    fun forget(id: Int) {
        // 状态是全局的，单个 widget 被删不影响它
    }

    /** App 侧刚对过账，桌面上残留的"状态未知"已经不准了，清掉 */
    fun clearAllUnknown() {
        unknown = false
    }

    fun busyReason(): WidgetRenderer.DisabledReason? =
        busy ?: if (unknown) WidgetRenderer.DisabledReason.UNKNOWN else null

    /** 遍历桌面上所有「淋浴」小组件 */
    fun forEachWidget(context: Context, block: (id: Int, size: WidgetSize) -> Unit) {
        val manager = AppWidgetManager.getInstance(context)
        // ⚠️ 每加一个尺寸都要在这里登记，否则那个尺寸的组件收不到重绘
        val entries = listOf(
            LinYuWidget1x1::class.java to WidgetSize.TILE,
            LinYuWidget2x2::class.java to WidgetSize.SMALL,
            LinYuWidget2x4::class.java to WidgetSize.WIDE
        )
        entries.forEach { (cls, size) ->
            manager.getAppWidgetIds(ComponentName(context, cls)).forEach { block(it, size) }
        }
    }

    fun render(context: Context, id: Int, size: WidgetSize) {
        val views = WidgetRenderer.build(
            context, size, WidgetRenderer.readState(), id, busyReason(),
            tab = PrefsHelper.widgetTab(id)
        )
        AppWidgetManager.getInstance(context).updateAppWidget(id, views)
    }

    /** 重绘桌面上所有「淋浴」小组件（2x2 与 2x4 一起） */
    fun renderAll(context: Context) {
        forEachWidget(context) { id, size -> render(context, id, size) }
    }

    /** 只重绘指定 id 的那个；没匹配到说明已被用户删除，顺手清掉它的过渡态 */
    fun renderId(context: Context, id: Int) {
        var matched = false
        forEachWidget(context) { wid, size ->
            if (wid == id) { render(context, id, size); matched = true }
        }
        if (!matched) clearBusy()
    }
}

