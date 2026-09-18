package com.hualala.linyu.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.google.gson.Gson
import com.hualala.linyu.MainActivity
import com.hualala.linyu.R
import com.hualala.linyu.data.BalanceEstimator
import com.hualala.linyu.data.ShowerController
import com.hualala.linyu.model.CachedBill
import com.hualala.linyu.model.CachedDevice
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.utils.PrefsHelper
import com.hualala.linyu.utils.ScanPermission

/** 小组件尺寸 */
enum class WidgetSize(val label: String) {
    /** 1x1，整块就是一个开关。只有底色和图标，没有任何文字 */
    TILE("1x1"),

    /** 2x2，可自由拉伸；只有一套卡片布局，内容随尺寸自适应 */
    SMALL("2x2"),

    /** 2x4，带左侧图标导航与三个页面 */
    WIDE("2x4")
}

/** 小组件展示状态 */
sealed interface WidgetState {
    data object LoggedOut : WidgetState
    data object NoDevice : WidgetState

    data class Idle(
        val deviceName: String,
        val deviceDesc: String,
        /** 当前控制设备的序列号。「上次消费」是按设备分开存的，要靠它取对应的一条 */
        val snCode: String,
        /**
         * 设备上有人正在用，但**不是机主**。
         *
         * 只由「用户点了开始、服务端回了 isOwner=false」这一个时机写入，
         * 下次点按钮重查时清掉——小组件发不了网络请求，没法自己知道对方什么时候用完。
         */
        val occupied: Boolean = false
    ) : WidgetState

    data class Running(
        val deviceName: String,
        val deviceDesc: String,
        val preDeduct: Double,
        val startedAtMs: Long
    ) : WidgetState
}

/**
 * 小组件渲染。
 *
 * 设计稿：`gemini-code-1789438284508.html`（Kyant0 液态玻璃风格）。
 *
 * ## 与设计稿的强制差异（RemoteViews 限制，不是偷懒）
 * - 设计稿的 `backdrop-filter: blur()` 无法实现 → 用「对角渐变 + 顶部高光」两层叠加近似
 * - `:active` 缩放、状态切换过渡等动画一律没有
 * - 运行时不能换背景（框架对反射调用有白名单）→ 一切"同一位置两种外观"都拆成两个控件切 visibility
 * - 只有这些控件可用：LinearLayout / FrameLayout / TextView / ImageView / Chronometer 等
 *
 * ## 配色
 * 跟随**壁纸明暗**而不是 App 内的主题设置：小组件是半透明的，压在深色壁纸上的浅色卡
 * 会让深色文字糊掉，反之亦然。
 */
object WidgetRenderer {

    private val gson = Gson()

    /** App 底部导航的三个 tab 序号，跳转用 */
    const val TAB_HOME = 0
    const val TAB_BILL = 1

    /**
     * 2x4 侧边栏的页序号：0 = 主页，1 = 附近设备，2 = 账单。
     *
     * ⚠️ 和上面 [TAB_HOME]/[TAB_BILL] 不是一套东西——那两个是**打开 App** 时要落到的页面，
     * 这三个是小组件自己内部的翻页。数值刚好对得上纯属巧合，别混用。
     */
    const val TAB_NEARBY = 1

    fun readState(): WidgetState {
        if (!PrefsHelper.isLoggedIn) return WidgetState.LoggedOut

        val snCode = PrefsHelper.lastDeviceSnCode
        if (snCode.isNullOrEmpty()) return WidgetState.NoDevice

        val name = PrefsHelper.lastDeviceName.ifEmpty { "上次使用的设备" }
        val desc = deviceDesc()

        return if (ShowerController.isRunning(snCode)) {
            // ⚠️ **正在用水时不受寝室筛选影响**，无条件照实显示。
            //
            // 用水中的卡片是用户**唯一的停止入口**。要是绑了别的寝室就把它藏成
            // 「请先选择设备」，水还在流、卡片上却按不动——只能去 App 里停。
            // 筛选是用来「少看到几台设备」的，不能拿来把正在跑的东西藏掉。
            WidgetState.Running(
                deviceName = name,
                deviceDesc = desc,
                preDeduct = ShowerController.activeOrderFor(snCode)?.preDeduct ?: 0.0,
                startedAtMs = ShowerController.startedAt(snCode)
            )
        } else if (!DeviceInfo.inSameRoom(PrefsHelper.boundRoom, ShowerController.roomFilterName())) {
            // 空闲时：这台设备在别的寝室 → 当作还没选设备。
            //
            // 复用 NoDevice 是有意的：它的按钮**本来就不开阀**（2x4 翻到附近设备页、
            // 2x2 打开 App），正是这里要的行为。
            //
            // ⚠️ 这只挡住「显示」。真正开阀的是 LinYuWidgetProvider.handleAction，
            // 它自己直接读 lastDeviceSnCode、不走这里——那边必须加同一道判断，
            // 否则会出现「桌面显示未选设备、点下去却把别寝室的阀开了」。
            WidgetState.NoDevice
        } else {
            WidgetState.Idle(
                name, desc, snCode,
                // 记住的占用设备就是当前这台，才显示「占用中」
                occupied = PrefsHelper.occupiedSnCode.isNotEmpty() &&
                    PrefsHelper.occupiedSnCode == snCode
            )
        }
    }

    /**
     * @param disabled 非空表示正在操作或状态未知。这个状态是**全局共享**的，
     *                 所以桌面上所有淋浴小组件会同时进入「正在开启…」，不会只有一个在转
     * @param tab      仅 2x4 使用：当前显示第几页
     */
    fun build(
        context: Context,
        size: WidgetSize,
        state: WidgetState,
        appWidgetId: Int,
        disabled: DisabledReason? = null,
        tab: Int = 0
    ): RemoteViews {
        // 面板上显示的那句话。
        //
        // 不用服务端的原文——「账户异常，请检查账户信息」这种句子在 2x2 的面板上
        // 根本放不下，完整原因由通知横幅负责（见 LinYuWidgetProvider 的 Failed 分支）
        val noticeText = disabled?.text

        // 1x1 是另一套结构（没有头/面板/胶囊按钮），单独一条路
        if (size == WidgetSize.TILE) {
            return buildTile(context, state, appWidgetId, disabled)
        }

        // 只有一套固定样式，不分深浅：
        // 小组件压在用户的壁纸上，深浅切换要么看不出变化、要么和壁纸撞色，
        // 不如固定成一个自带背景的高对比卡片——任何壁纸下都一样清楚。
        val views = RemoteViews(context.packageName, layoutRes(size))

        // 整张卡片 → 打开 App 首页
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, appWidgetId, TAB_HOME))

        /** [explicit] 用来顶掉按 action 拼出来的点击行为（「没有设备」时按钮要换成翻页） */
        fun bindAct(
            running: Boolean,
            disabled: DisabledReason?,
            action: String,
            explicit: PendingIntent? = null
        ) {
            bindPillAction(
                views, running,
                explicit ?: actionIntent(context, appWidgetId, disabled, action)
            )
        }

        // 2x2 空间窄，只显示完整设备名的最后一个词（「龙川北苑 3号楼南 320房」→「320房」）；
        // 2x4 位置够就显示完整名。
        // 2x2 被拉宽也不再换布局了——以前按宽高比切成"按钮在右侧"的横向版，
        // 结果稍微拉一下就跳过去、圆球和固定宽度的卡片都不跟着缩放，很难看。
        val rawName = when (state) {
            is WidgetState.Idle -> state.deviceName
            is WidgetState.Running -> state.deviceName
            else -> ""
        }
        val shownName = if (size == WidgetSize.SMALL) shortName(rawName) else rawName

        when (state) {
            WidgetState.LoggedOut -> {
                bindHeader(views, "未登录", "点此打开淋浴", running = false, showDot = false)
                bindPanels(context, views, appWidgetId, idleText = "—", runningText = null, busyText = noticeText)
                bindAct(running = false, disabled = disabled, action = "")
            }

            WidgetState.NoDevice -> {
                // 没选过设备时，按钮不该去开阀（没设备可开），而是直接把用户送到能选设备的地方：
                // 2x4 有自己的「附近设备」页，就地翻过去；2x2 没有页面，只能开 App。
                // 以前这里 action 是空串，按钮等于摆设——点了什么都不会发生。
                val wide = size == WidgetSize.WIDE
                // 文案不能写「还没有用过设备」：绑定寝室后上次用的设备被清掉时也会落到
                // 这个状态，而用户明明用过设备，只是它不在这个寝室里。
                bindHeader(
                    views, "请先选择设备",
                    if (wide) "点按钮选附近设备" else "点此打开淋浴",
                    running = false, showDot = false
                )
                bindPanels(context, views, appWidgetId, idleText = "—", runningText = null, busyText = noticeText)
                bindAct(
                    running = false, disabled = disabled, action = "",
                    explicit = if (wide) tabIntent(context, appWidgetId, TAB_NEARBY)
                    else openAppIntent(context, appWidgetId, TAB_HOME)
                )
            }

            is WidgetState.Idle -> {
                bindHeader(views, shownName, state.deviceDesc,
                    running = false, showDot = true, occupied = state.occupied)
                bindPanels(
                    context, views, appWidgetId,
                    idleText = lastConsumeText(state.snCode),
                    runningText = null,
                    busyText = noticeText
                )
                bindAct(running = false, disabled = disabled, action = LinYuWidgetProvider.ACTION_START)
            }

            is WidgetState.Running -> {
                bindHeader(views, shownName, state.deviceDesc,
                    running = true, showDot = true)
                bindPanels(
                    context, views, appWidgetId,
                    idleText = null,
                    runningText = "预扣 · ¥%.2f".format(state.preDeduct),
                    timerText = null,
                    chronometerBase = chronometerBase(state.startedAtMs),
                    busyText = noticeText
                )
                bindAct(running = true, disabled = disabled, action = LinYuWidgetProvider.ACTION_STOP)
            }
        }

        if (size == WidgetSize.WIDE) {
            bindTabs(views, context, appWidgetId, tab)
            bindPage(context, views, appWidgetId, tab)
        }
        return views
    }

    /**
     * 1x1：整块就是一个开关。
     *
     * **故意不放设备名、计时、预扣金额**——一格大概四五十 dp，塞这些谁也看不清。
     * 用户要的就是「点一下开关热水」，详细状态由用水期间那条常驻通知承担。
     *
     * 状态只能靠底色 + 图标表达（RemoteViews 改不了背景，所以是三块叠着切 visibility）：
     *
     * | 状态 | 底色 | 图标 | 点击 |
     * |---|---|---|---|
     * | 空闲 | 玻璃灰 | 电源 | 开阀 |
     * | 使用中 | 蓝 | 停止方块 | 关阀 |
     * | 他人使用中 | 橙 | 锁 | 仍是开阀（服务端会拒绝，然后弹占用提示） |
     * | 未登录 / 还没选过设备 | 玻璃灰 | 电源 | 打开 App |
     */
    private fun buildTile(
        context: Context,
        state: WidgetState,
        appWidgetId: Int,
        disabled: DisabledReason?
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_linyu_1x1)

        val running = state is WidgetState.Running
        val occupied = (state as? WidgetState.Idle)?.occupied == true
        // 未登录 / 没有设备：点它没有任何可执行的动作，直接开 App 让用户去处理
        val needsApp = state is WidgetState.LoggedOut || state is WidgetState.NoDevice

        // 「正在开阀 / 关阀」是**过程**（要等好几秒）→ 盖一层转圈。
        // 「开阀失败」「他人使用中」「切换失败」是**结果** → 不转圈，改把瓷砖上的小字换掉。
        // 用 DisabledReason.isNotice 判断，别再手写枚举名单——加一个失败原因就会漏一处
        val noticeOnly = disabled?.isNotice == true
        val spinning = disabled != null && !noticeOnly

        // 空闲那块是三种情况共用的（真·空闲 / 未登录 / 没选过设备 / 一次失败提示），
        // 只有顶上的字不同——「未登录」的时候写「空闲」会让人以为能直接开阀。
        //
        // ⚠️ 失败时这里用**枚举自带的短文案**（「开阀失败」4 个字），不用服务端那句
        // 「账户异常，请检查账户信息」——瓷砖上只有一行 8sp 的位置，长句子放不下，
        // 缩短版本总比截成半句强。详细原因由 App 内的弹窗负责。
        val idleLabel = when {
            noticeOnly -> disabled!!.shortText
            state is WidgetState.LoggedOut -> context.getString(R.string.widget_1x1_state_logged_out)
            state is WidgetState.NoDevice -> context.getString(R.string.widget_1x1_state_no_device)
            else -> context.getString(R.string.widget_1x1_state_idle)
        }
        views.setTextViewText(R.id.widget_tile_idle_label, idleLabel)

        val showIdle = !running && !occupied && !spinning
        views.setViewVisibility(R.id.widget_tile_idle, if (showIdle) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_tile_using, if (running && !spinning) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_tile_occupied, if (occupied && !spinning) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_tile_busy, if (spinning) View.VISIBLE else View.GONE)

        // ⚠️ 隐藏的那几块也占着布局位置、也收得到触摸，必须把点击清掉，
        // 否则会点到看不见的按钮上
        val idle = R.id.widget_tile_idle
        val using = R.id.widget_tile_using
        val occ = R.id.widget_tile_occupied

        val idleClick = when {
            !showIdle -> null
            needsApp -> openAppIntent(context, appWidgetId, TAB_HOME)
            else -> actionIntent(context, appWidgetId, disabled, LinYuWidgetProvider.ACTION_START)
        }
        val usingClick =
            if (running && !spinning) actionIntent(context, appWidgetId, null, LinYuWidgetProvider.ACTION_STOP)
            else null
        // 占用中仍然让点：服务端会拒绝，App 那边会把它记成「占用中」并把通知发出来。
        // 直接摘掉点击的话，用户点了没反应，更懵
        val occClick =
            if (occupied && !spinning) actionIntent(context, appWidgetId, disabled, LinYuWidgetProvider.ACTION_START)
            else null

        views.setOnClickPendingIntent(idle, idleClick)
        views.setOnClickPendingIntent(using, usingClick)
        views.setOnClickPendingIntent(occ, occClick)
        // 转圈那块永远不接点击：正在操作时连点会叠加请求
        views.setOnClickPendingIntent(R.id.widget_tile_busy, null)

        // 根布局也挂一份当前的点击动作。布局为了跟应用图标一样大留了 7dp 内边距，
        // 那圈留白落在根布局上、不在任何一块 tile 里——不补这一下，
        // 边缘就成了"看得见但点不着"的死区。
        // 点在内层 tile 上时由内层先接住，不会重复触发。
        views.setOnClickPendingIntent(
            R.id.widget_tile_root,
            if (spinning) null else idleClick ?: usingClick ?: occClick
        )

        return views
    }

    // ── 头部：状态点 + 设备名 + 状态徽章 ──

    private fun bindHeader(
        views: RemoteViews,
        name: String,
        desc: String,
        running: Boolean,
        showDot: Boolean,
        occupied: Boolean = false
    ) {
        views.setTextViewText(R.id.widget_device, name)
        views.setTextViewText(R.id.widget_device_desc, desc)

        if (showDot) {
            views.setTextViewText(R.id.widget_status_dot, "●")
            views.setTextColor(
                R.id.widget_status_dot,
                when {
                    running -> COLOR_USING
                    occupied -> COLOR_WARN
                    else -> COLOR_IDLE
                }
            )
        } else {
            views.setTextViewText(R.id.widget_status_dot, "")
        }

        // 三种徽章颜色不同，靠三个控件切 visibility（运行时改不了背景）。
        // 占用中优先于空闲：设备上确实有订单，只是不是你的。
        views.setViewVisibility(
            R.id.widget_badge_idle,
            if (!running && !occupied) View.VISIBLE else View.GONE
        )
        views.setViewVisibility(
            R.id.widget_badge_using,
            if (running) View.VISIBLE else View.GONE
        )
        views.setViewVisibility(
            R.id.widget_badge_occupied,
            if (!running && occupied) View.VISIBLE else View.GONE
        )
    }

    /**
     * 内嵌玻璃槽：空闲态显示「上次消费」，使用中显示「走秒 + 预扣」。
     *
     * 操作进行中（[busyText] 非空）时，这里改显示「正在开启…/正在关闭…」并**占满整个卡片**——
     * 用户点完按钮最想看到的是"它在动"，而不是原来那个数字。
     *
     * 点击行为按状态分：
     * - 空闲 → 打开 App 的账单页（上次消费是账单信息，点进去看明细最自然）
     * - 使用中 → 直接关阀（不用再去找那个小圆球）
     */
    private fun bindPanels(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        idleText: String?,
        runningText: String?,
        timerText: String? = null,
        chronometerBase: Long = 0L,
        busyText: String? = null
    ) {
        // 三种面板叠在同一位置，切 visibility。
        // 忙碌时优先显示转圈面板——点完按钮最想看到的是"它在转"
        val showBusy = busyText != null
        val showIdle = idleText != null && !showBusy
        views.setViewVisibility(R.id.widget_panel_busy, if (showBusy) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_panel_idle, if (showIdle) View.VISIBLE else View.GONE)
        views.setViewVisibility(
            R.id.widget_panel_running,
            if (!showBusy && !showIdle) View.VISIBLE else View.GONE
        )
        if (showBusy) {
            views.setTextViewText(R.id.widget_busy_text, busyText ?: "")
            // 转圈期间卡片点击统一回 App，不做别的动作
            views.setOnClickPendingIntent(
                R.id.widget_panel_busy,
                openAppIntent(context, appWidgetId, TAB_BILL)
            )
            return
        }

        if (showIdle) {
            // 忙碌时把「上次消费」这个小标题藏掉，免得和「正在开启…」并排读起来别扭
            views.setTextViewText(R.id.widget_panel_label, if (busyText != null) "" else "上次消费")
            views.setTextViewText(R.id.widget_lastconsume, busyText ?: idleText ?: "—")
            views.setOnClickPendingIntent(
                R.id.widget_panel_idle,
                openAppIntent(context, appWidgetId, TAB_BILL)
            )
        } else {
            views.setTextViewText(R.id.widget_prededuct, busyText ?: runningText ?: "")
            if (busyText != null) {
                views.setChronometer(R.id.widget_timer, 0L, null, false)
                views.setTextViewText(R.id.widget_timer, busyText)
            } else if (timerText != null) {
                views.setChronometer(R.id.widget_timer, 0L, null, false)
                views.setTextViewText(R.id.widget_timer, timerText)
            } else {
                views.setChronometer(R.id.widget_timer, chronometerBase, null, true)
            }
            // 使用中点卡片 = 关阀（正在操作时不响应，避免重复触发）
            views.setOnClickPendingIntent(
                R.id.widget_panel_running,
                if (busyText != null) null
                else actionIntent(context, appWidgetId, null, LinYuWidgetProvider.ACTION_STOP)
            )
        }
    }

    /**
     * 胶囊按钮（竖向 2x2 与 2x4）：**只有图标，没有文字**。
     * 图标已经写在布局的 drawableStart 里（开=电源、关=停止方块），这里只负责切显示和挂点击。
     * 显式写 null 清掉上一轮的点击，否则禁用态还会带着旧动作。
     */
    private fun bindPillAction(
        views: RemoteViews,
        running: Boolean,
        onClick: PendingIntent?
    ) {
        val visibleId = if (running) R.id.widget_action_stop else R.id.widget_action_start
        val hiddenId = if (running) R.id.widget_action_start else R.id.widget_action_stop

        views.setViewVisibility(hiddenId, View.GONE)
        views.setViewVisibility(visibleId, View.VISIBLE)
        views.setOnClickPendingIntent(visibleId, onClick)
    }

    // ── 2x4 侧边栏与三个页面 ──

    private fun bindTabs(views: RemoteViews, context: Context, appWidgetId: Int, tab: Int) {
        for (i in 0..2) {
            val onId = when (i) {
                0 -> R.id.widget_tab0_on
                1 -> R.id.widget_tab1_on
                else -> R.id.widget_tab2_on
            }
            val offId = when (i) {
                0 -> R.id.widget_tab0_off
                1 -> R.id.widget_tab1_off
                else -> R.id.widget_tab2_off
            }
            val selected = i == tab
            views.setViewVisibility(onId, if (selected) View.VISIBLE else View.GONE)
            views.setViewVisibility(offId, if (selected) View.GONE else View.VISIBLE)
            views.setOnClickPendingIntent(
                if (selected) onId else offId,
                tabIntent(context, appWidgetId, i)
            )
        }
    }

    private fun bindPage(context: Context, views: RemoteViews, appWidgetId: Int, tab: Int) {
        views.setViewVisibility(R.id.widget_page0, if (tab == 0) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_page1, if (tab == 1) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_page2, if (tab == 2) View.VISIBLE else View.GONE)

        when (tab) {
            1 -> bindNearby(context, views, appWidgetId)
            2 -> {
                bindBills(views)
                // 账单页整页可点 → 进 App 的账单页看明细
                views.setOnClickPendingIntent(
                    R.id.widget_page2,
                    openAppIntent(context, appWidgetId, TAB_BILL)
                )
            }
        }
    }

    /**
     * 附近设备页：读 App 上次扫描存的快照。
     *
     * 小组件扫不了蓝牙，所以显示不了实时结果；但"为什么没有设备"是能判断的——
     * 没权限 / 蓝牙没开 / 扫过但没结果，这三种情况提示完全不同，
     * 一律显示「未扫描到附近设备」会让人以为是 App 的问题。
     */
    private fun bindNearby(context: Context, views: RemoteViews, appWidgetId: Int) {
        val blocker = nearbyBlocker(context)
        val cached = readCache(PrefsHelper.widgetNearbyJson, Array<CachedDevice>::class.java)
        // 按当前绑定过滤，**再**取前两条给那两行。
        //
        // 顺序不能反：快照存的是全量扫描结果，要是先 take(2) 再过滤，本寝室的设备
        // 排在第 3 台之后就会一行都不剩，桌面显示「未发现热水器」——而它就在那儿。
        //
        // 也不用 [CachedDevice.name]（格式化过的显示名）来筛，用 [CachedDevice.rawName]：
        // 显示名里有「洗手台→房」这类凭空造的字，和首页用的原始名对不上。
        // 老快照没有 rawName 字段，回退到 name。
        val list = cached.filter {
            DeviceInfo.inSameRoom(PrefsHelper.boundRoom, it.rawName ?: it.name ?: "")
        }.take(2)
        val showList = blocker == null && list.isNotEmpty()

        views.setTextViewText(R.id.widget_near_synced, syncedAgoText())
        views.setViewVisibility(R.id.widget_near_list, if (showList) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_near_empty, if (showList) View.GONE else View.VISIBLE)
        if (!showList) {
            // 没权限 / 没开蓝牙优先报这两条，它们的成因是确定的。
            // 剩下的情况要区分「扫了但没有」和「压根没扫过」：
            // 前者和 App 首页一样说「未发现热水器」，后者才提示回 App 扫一次
            views.setTextViewText(
                R.id.widget_near_empty,
                blocker ?: if (PrefsHelper.widgetNearbyTime > 0L) "未发现热水器" else "未扫描到附近设备"
            )
            return
        }

        val rowIds = arrayOf(
            intArrayOf(R.id.widget_near0_emoji, R.id.widget_near0_name,
                R.id.widget_near0_desc, R.id.widget_near0_signal, R.id.widget_near0_rssi),
            intArrayOf(R.id.widget_near1_emoji, R.id.widget_near1_name,
                R.id.widget_near1_desc, R.id.widget_near1_signal, R.id.widget_near1_rssi)
        )
        val pickIds = intArrayOf(R.id.widget_near0_pick, R.id.widget_near1_pick)

        for (i in 0..1) {
            val d = list.getOrNull(i)
            if (d == null) {
                // 只扫到一台时第二行留空，不要塞「—」显得像出错
                rowIds[i].forEach { views.setTextViewText(it, "") }
                views.setOnClickPendingIntent(pickIds[i], null)
            } else {
                // 一律兜底：缓存里可能是旧版本写的 JSON，缺字段就是 null
                views.setTextViewText(rowIds[i][0], d.emoji ?: "🚿")
                views.setTextViewText(rowIds[i][1], d.name ?: "")
                views.setTextViewText(rowIds[i][2], d.desc ?: "")
                // 信号点 + dB 数值：沿用 App 首页的强/中/弱配色。
                // 光一个圆点看不出强弱差多少，补上具体的 dB 值（和 App 内是同一份 rssi）
                views.setTextViewText(rowIds[i][3], "●")
                views.setTextColor(rowIds[i][3], signalColor(d.rssi))
                views.setTextViewText(rowIds[i][4], "${d.rssi} dBm")
                views.setTextColor(rowIds[i][4], signalColor(d.rssi))
                // 「选用」→ 在后台把这台设备切成小组件的当前设备，全程不打开 App。
                // 小组件拿到的只有 MAC，所以要让 ShowerController 去查一次完整设备信息。
                val mac = d.mac ?: ""
                views.setOnClickPendingIntent(
                    pickIds[i],
                    if (mac.isNotEmpty()) pickIntent(context, appWidgetId, i, mac) else null
                )
            }
        }
    }

    /**
     * 判断附近设备页现在"卡"在哪一步，返回要显示的提示；返回 null 表示条件都满足。
     * 顺序很重要：没权限时连蓝牙状态都读不到，必须先判权限。
     */
    private fun nearbyBlocker(context: Context): String? {
        if (!ScanPermission.granted(context)) return "请在 App 中开启扫描权限"
        if (!isBluetoothOn(context)) return "请打开蓝牙"
        return null
    }

    /** 读蓝牙开关状态。API 31+ 没 BLUETOOTH_CONNECT 会抛 SecurityException，所以要先判权限 */
    private fun isBluetoothOn(context: Context): Boolean = try {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        bm?.adapter?.isEnabled == true
    } catch (_: Exception) {
        false
    }

    /** 账单页：读 App 上次拉取的账单快照 */
    private fun bindBills(views: RemoteViews) {
        val list = readCache(PrefsHelper.widgetBillJson, Array<CachedBill>::class.java)

        // 余额优先用真实值，拿不到才回退**估算**（初始余额 − 之后产生的消费），
        // 和 App 内共用 BalanceEstimator 这一份算法。
        // 这里以前直接显示 PrefsHelper.manualBalance，也就是用户当初填的那个初始值——
        // 而它是不会变的，所以消费完 App 里余额掉下去了、桌面上纹丝不动。
        val balance = BalanceEstimator.estimateFromEntries(
            list.map { (it.rawTimeMs ?: 0L) to (it.rawMoney ?: 0.0) }
        )
        views.setTextViewText(R.id.widget_balance, BalanceEstimator.format(balance))
        // ⚠️ 这个标签以前是**写死在布局 XML 里**的「一卡通余额（估算）」，
        // 拿到真实余额之后文案也不会变，看着就像一直没生效。
        views.setTextViewText(
            R.id.widget_balance_label,
            if (BalanceEstimator.hasRealBalance()) "一卡通余额" else "一卡通余额（估算）"
        )

        val rowIds = arrayOf(
            intArrayOf(R.id.widget_bill0_emoji, R.id.widget_bill0_name,
                R.id.widget_bill0_time, R.id.widget_bill0_price),
            intArrayOf(R.id.widget_bill1_emoji, R.id.widget_bill1_name,
                R.id.widget_bill1_time, R.id.widget_bill1_price)
        )
        for (i in 0..1) {
            val b = list.getOrNull(i)
            if (b == null) {
                views.setTextViewText(rowIds[i][0], "—")
                views.setTextViewText(rowIds[i][1], if (i == 0) "在 App 里刷新账单" else "—")
                views.setTextViewText(rowIds[i][2], "")
                views.setTextViewText(rowIds[i][3], "")
            } else {
                views.setTextViewText(rowIds[i][0], b.emoji ?: "🚿")
                views.setTextViewText(rowIds[i][1], b.name ?: "")
                views.setTextViewText(rowIds[i][2], b.timeText ?: "")
                views.setTextViewText(rowIds[i][3], b.moneyText ?: "")
            }
        }
    }

    private fun <T> readCache(json: String, cls: Class<Array<T>>): List<T> = try {
        if (json.isBlank()) emptyList() else gson.fromJson(json, cls)?.toList() ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * 「上次扫描」距今多久，跟在「附近设备」后面。
     * 小组件扫不了蓝牙，页面上看到的永远是快照——不标出来会被当成实时数据。
     * 还没扫过时返回空串，不留一个空的括号。
     */
    private fun syncedAgoText(): String {
        val t = PrefsHelper.widgetNearbyTime
        if (t <= 0L) return ""
        val mins = (System.currentTimeMillis() - t) / 60_000L
        val ago = when {
            mins < 1 -> "刚刚"
            mins < 60 -> "$mins 分钟前"
            else -> "${mins / 60} 小时前"
        }
        return "（$ago）"
    }

    /** 信号强度配色，与 App 首页的强/中/弱一致 */
    private fun signalColor(rssi: Int): Int = when {
        rssi >= -70 -> COLOR_IDLE      // 强
        rssi >= -85 -> COLOR_WARN      // 中
        else -> COLOR_DANGER           // 弱
    }

    private fun lastConsumeText(snCode: String): String {
        val money = PrefsHelper.lastConsumeFor(snCode)
        return if (money > 0f) "¥ %.2f".format(money) else "¥ —"
    }

    /** 早期版本写进去的是「热水器 / 洗手台 / 饮水机」这种简写，认出来就用 emoji 重推一遍 */
    private val BARE_TYPE_NAMES = setOf("热水器", "洗手台", "饮水机")

    /**
     * 副标题（卫生间热水器 / 洗手台热水器 / 直饮水机 · 冷水…）。
     *
     * `lastDeviceTypeName` 只在**开始洗澡**时才写入，所以老用户升级上来存的还是旧值。
     * 这里发现是简写就按 emoji 反推，不用等下一次开阀。
     */
    private fun deviceDesc(): String {
        val stored = PrefsHelper.lastDeviceTypeName
        if (stored.isNotEmpty() && stored !in BARE_TYPE_NAMES) return stored
        return when (PrefsHelper.lastDeviceEmoji) {
            "🪥" -> "洗手台热水器"
            "❄️" -> "直饮水机 · 冷水"
            "♨️" -> "直饮水机 · 热水"
            else -> "卫生间热水器"
        }
    }

    /**
     * 取完整设备名的最后一个词。
     * 设备名经过 DeviceInfo.formatDeviceName 处理过，分段之间用空格连接
     * （「龙川北苑 3号楼南 320房」），所以按空白取最后一段即可。
     */
    private fun shortName(full: String): String =
        full.trim().split(' ', '　', '-').lastOrNull { it.isNotBlank() } ?: full

    /**
     * 把「挂钟时间戳」换算成 Chronometer 要的基准。
     *
     * Chronometer 用 SystemClock.elapsedRealtime()（开机以来）算差值，而 startedAt 存的是
     * System.currentTimeMillis()（1970 以来），原点不同，必须换算。
     *
     * ⚠️ 关键：换算必须用「**开机那一刻的挂钟时间**」，不能用「现在」。
     * 如果用 `elapsedRealtime() - (now - startedAt)`，那每次重绘算出来的 base 都会
     * 跟着渲染时刻平移——两个小组件渲染相差几百毫秒，base 就差几百毫秒，
     * 而 Chronometer 是按 base 的秒边界跳的，于是两边每秒里有一小段时间显示不同的秒数
     * （点一下侧边栏就会复现）。
     * 改成 `startedAt - bootWallMs` 后 base 只由 startedAt 决定，所有小组件同相位。
     */
    private fun chronometerBase(startedAtMs: Long): Long {
        if (startedAtMs <= 0L) return SystemClock.elapsedRealtime()
        val bootWallMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return startedAtMs - bootWallMs
    }

    /** 操作进行中时按钮要显示成什么 */
    /**
     * @param text      2x2 / 2x4 上显示的文案，放在那条能放一句话的面板里
     * @param shortText 1x1 瓷砖上显示的一行 8sp 小字。默认和 [text] 一样；
     *                  只有 [text] 太长、瓷砖上放不下的才需要单独给一个短的
     */
    enum class DisabledReason(val text: String, val shortText: String = text) {
        STARTING("正在开启…"),
        STOPPING("正在关闭…"),
        REFRESHING("正在刷新…"),
        SWITCHING("正在切换设备…"),
        UNKNOWN("状态未知，点此刷新"),

        /** 「选用」失败：查不到设备或网络不通。短时间展示一下就自动消失 */
        PICK_FAILED("切换失败，请稍后再试", shortText = "切换失败"),

        /**
         * 想开的设备正被别人用着。
         *
         * 文案刻意短——小组件的忙碌面板只有一行，2x2 上大约只放得下 5 个字。
         * 「可以再点」这个意思靠徽章变成「占用中」+ 按钮仍然可点来传达。
         */
        IN_USE_BY_OTHERS("他人使用中"),

        /**
         * 开阀失败（服务端拒绝、余额不足之类）。
         *
         * 文案刻意短——桌面上只有一行位置，服务端那句「账户异常，请检查账户信息」
         * 根本放不下，**完整原因由 `Notifier.showOpenFailed` 发横幅通知带出来**。
         * 以前失败只写一行日志，桌面上什么反应都没有，用户以为没点上。
         */
        FAILED("开阀失败");

        /**
         * 是「结果」而不是「过程」——渲染时不转圈，而且**按钮照常可点**。
         *
         * 用户在失败后想再点一次重试是很自然的，把点击清掉会让他以为小组件坏了。
         */
        val isNotice: Boolean
            get() = this == FAILED || this == PICK_FAILED || this == IN_USE_BY_OTHERS
    }

    private fun actionIntent(
        context: Context,
        appWidgetId: Int,
        disabled: DisabledReason?,
        action: String
    ): PendingIntent? = when {
        // 没有 disabled，或只是「结果」提示（开阀失败 / 切换失败 / 他人使用中）→ 照常可点。
        // 用户在这些情况下多半就是想重试，把点击清掉会让小组件看起来坏了
        disabled == null || disabled.isNotice ->
            toggleIntent(context, appWidgetId, action)
        disabled == DisabledReason.UNKNOWN ->
            toggleIntent(context, appWidgetId, LinYuWidgetProvider.ACTION_REFRESH)
        else -> null
    }

    /**
     * 只有两套布局，按尺寸选，**不再看宽高比**。
     * 竖向那套的中间区域用 `layout_weight=1` 撑满，被拉宽拉高都自己适配。
     */
    private fun layoutRes(size: WidgetSize): Int = when (size) {
        WidgetSize.WIDE -> R.layout.widget_linyu_2x4
        WidgetSize.TILE -> R.layout.widget_linyu_1x1
        else -> R.layout.widget_linyu_2x2
    }

    // ── PendingIntent ──
    // requestCode 必须每个 widget、每个动作都不同，否则会互相覆盖

    private fun openAppIntent(context: Context, appWidgetId: Int, tab: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, tab)
        }
        return PendingIntent.getActivity(
            context, 1000 + tab * 100 + appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 「选用」：广播回 Provider，在后台把这台设备切成小组件的当前设备。
     *
     * ⚠️ requestCode 里**必须带上行号 [index]**，这是之前一个真 bug 的根因：
     * 两行设备原本共用 `3000 + appWidgetId`，而 `FLAG_UPDATE_CURRENT` 会让后写入的那个
     * 覆盖掉先写入的——结果两行都指向最后一次绑定时的 MAC，表现就是「点 309 打开 307」。
     */
    private fun pickIntent(
        context: Context,
        appWidgetId: Int,
        index: Int,
        mac: String
    ): PendingIntent {
        val intent = Intent(context, providerClass(context, appWidgetId)).apply {
            action = LinYuWidgetProvider.ACTION_PICK_DEVICE
            putExtra(LinYuWidgetProvider.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(LinYuWidgetProvider.EXTRA_PICK_MAC, mac)
        }
        return PendingIntent.getBroadcast(
            context, 6000 + index * 100 + appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * ⚠️ 目标组件必须是 Manifest 里注册过的 receiver。
     * 指向未注册的基类会让广播被系统静默丢弃，表现就是「点按钮毫无反应」。
     */
    private fun toggleIntent(context: Context, appWidgetId: Int, action: String): PendingIntent {
        val intent = Intent(context, providerClass(context, appWidgetId)).apply {
            this.action = action
            putExtra(LinYuWidgetProvider.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val code = when (action) {
            LinYuWidgetProvider.ACTION_START -> 2000
            LinYuWidgetProvider.ACTION_STOP -> 2500
            else -> 4000
        }
        return PendingIntent.getBroadcast(
            context, code + appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 切换 2x4 的页面 */
    private fun tabIntent(context: Context, appWidgetId: Int, tab: Int): PendingIntent {
        val intent = Intent(context, providerClass(context, appWidgetId)).apply {
            action = LinYuWidgetProvider.ACTION_SET_TAB
            putExtra(LinYuWidgetProvider.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(LinYuWidgetProvider.EXTRA_TAB, tab)
        }
        return PendingIntent.getBroadcast(
            context, 5000 + tab * 100 + appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun providerClass(context: Context, appWidgetId: Int): Class<*> {
        val manager = AppWidgetManager.getInstance(context)
        val small = manager.getAppWidgetIds(
            android.content.ComponentName(context, LinYuWidget2x2::class.java)
        )
        return if (appWidgetId in small) LinYuWidget2x2::class.java else LinYuWidget2x4::class.java
    }

    // 与设计稿 CSS 变量一致的状态色
    private const val COLOR_IDLE = 0xFF22C55E.toInt()
    private const val COLOR_USING = 0xFF3B82F6.toInt()
    private const val COLOR_USING_TEXT = 0xFF60A5FA.toInt()
    private const val COLOR_WARN = 0xFFF59E0B.toInt()
    private const val COLOR_DANGER = 0xFFEF4444.toInt()
}
