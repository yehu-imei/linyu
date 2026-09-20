package com.hualala.linyu.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hualala.linyu.MainActivity
import com.hualala.linyu.R

/**
 * 系统通知。
 *
 * 两条渠道，按「要不要打扰人」分开：
 * - [CHANNEL_IN_USE] **低优先级**：用水期间常驻的状态条，不该响铃震动，
 *   跟音乐播放器那种常驻通知是同一类。用户可以单独把它静音而不影响别的通知。
 * - [CHANNEL_EVENTS] **默认优先级**：结束提醒这类「知道了就行」的通知。
 * - [CHANNEL_ALERT] **高优先级**：开阀失败 / 设备被占用 / 超时自动关停，会弹横幅。
 *   横幅必须 IMPORTANCE_HIGH，DEFAULT 不会弹——所以单独建了两条渠道按开关切。
 *
 * 每种通知都由 [PrefsHelper] 里对应的开关控制，用户在「我的 → 通知」里能单独关掉。
 */
object Notifier {

    private const val CHANNEL_IN_USE = "linyu_in_use"
    private const val CHANNEL_IN_USE_QUIET = "linyu_in_use_quiet"
    private const val CHANNEL_EVENTS = "linyu_events"

    /** 会弹横幅的那一类（IMPORTANCE_HIGH） */
    private const val CHANNEL_ALERT = "linyu_alert"

    /** 关掉「横幅提醒」后改走这条（IMPORTANCE_DEFAULT，同一条通知不弹横幅） */
    private const val CHANNEL_ALERT_QUIET = "linyu_alert_quiet"

    /** 常驻那条的固定 id：同一个 id 反复 post 就是「更新」而不是「堆叠」 */
    const val ID_IN_USE = 1001
    const val ID_FINISHED = 1002
    const val ID_AUTO_CLOSED = 1003
    const val ID_OCCUPIED = 1004
    const val ID_OPEN_FAILED = 1005

    /** 通知里「结束使用」按钮的动作，由 ShowerWatchService 接 */
    const val ACTION_STOP_SHOWER = "com.hualala.linyu.notify.ACTION_STOP_SHOWER"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_IN_USE,
                "用水状态",
                // LOW：不出声、不震动、不在锁屏弹，只在通知栏静静挂着
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "用水期间显示已用时间" }
        )
        // 「用水状态通知」关掉时用这条：IMPORTANCE_MIN 会让它折叠到通知栏最底部，
        // 不占状态栏图标、不打扰。
        //
        // ⚠️ 这里**不能直接不发通知**。Android 强制要求前台服务必须挂一条通知，
        // 没有它就起不来——而前台服务正是「App 被划掉也能自动关停」的唯一保障。
        // 所以关掉开关的语义是「把它收起来」，不是「关掉后台监控」。
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_IN_USE_QUIET,
                "用水状态（静默）",
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = "关闭「用水状态通知」后，状态会折叠到这里" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                "用水提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "开始、结束等提醒" }
        )

        // ── 重要提醒：会**弹横幅**（heads-up）的那一类 ──
        //
        // ⚠️ 横幅要 IMPORTANCE_HIGH。DEFAULT 只是响一声、不会从屏幕顶上弹出来——
        // 之前三条渠道最高才 DEFAULT，所以全 App 一个横幅都没有。
        //
        // ⚠️ 渠道的 importance **创建之后改不了**（系统不允许 App 改已存在渠道），
        // 所以「关掉横幅」不能靠改这条渠道，只能另建一条 DEFAULT 的，
        // 发送时按开关二选一——和上面 IN_USE / IN_USE_QUIET 是同一套办法。
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "重要提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "开阀失败、设备被占用、超时自动关停，会从屏幕顶部弹出横幅" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT_QUIET,
                "重要提醒（不弹横幅）",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "关闭「横幅提醒」后，这些通知只响一声，不再弹横幅" }
        )
    }

    /**
     * 通知能不能发。
     *
     * Android 13 起 `POST_NOTIFICATIONS` 是运行时权限，用户拒绝了这里就是 false。
     * 调用方据此决定要不要引导用户去设置里开——**不能假设通知一定能发出去**。
     */
    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 用水期间常驻的状态条。重复调用即更新同一条。
     *
     * **即使 [PrefsHelper.notifyInUse] 关着也照样发**，只是换到静默渠道——
     * 前台服务没有通知就起不来，而它是自动关停的唯一保障。见渠道创建的注释。
     */
    fun showInUse(context: Context, deviceName: String, startedAtMs: Long): android.app.Notification {
        ensureChannels(context)

        // 关掉时（总开关，或单独关掉「用水状态通知」）不发内容，
        // 只给一条满足 startForeground 要求的最小通知；服务拿到后会立刻摘掉，
        // 用户其实看不到（见 ShowerWatchService.attachForeground）
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyInUse) return minimal(context)

        val channel = if (PrefsHelper.notifyInUse) CHANNEL_IN_USE else CHANNEL_IN_USE_QUIET
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notify_shower)
            .setContentTitle("正在使用 · ${shortName(deviceName)}")
            // Chronometer 由系统自己走秒，不需要我们每秒刷新通知
            .setUsesChronometer(true)
            .setWhen(if (startedAtMs > 0) startedAtMs else System.currentTimeMillis())
            .setShowWhen(true)
            .setOngoing(true)                        // 划不掉，跟服务同生共死
            .setOnlyAlertOnce(true)                  // 更新时不重复提醒
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // 点它就是想回使用页看当前状态，不是回首页
            .setContentIntent(openApp(context, tab = 0, showShower = true))
            .addAction(stopAction(context))
            .build()

        notify(context, ID_IN_USE, n)
        return n
    }

    /**
     * 「正在结束…」：关阀确认 + 账单结算期间挂的过渡通知。
     *
     * 有它是因为停止流程被搬到了服务里——先挂上这条，服务才是前台服务；
     * 结算完再换成带金额的结束通知。
     */
    fun showStopping(context: Context, deviceName: String): android.app.Notification {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_IN_USE)
            .setSmallIcon(R.drawable.ic_notify_shower)
            .setContentTitle("正在结束 · $deviceName")
            .setContentText("确认关阀并结算中…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notify(context, ID_IN_USE, n)
        return n
    }

    fun cancelInUse(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_IN_USE) }
    }

    /**
     * 「事件类」通知的**唯一出口**。
     *
     * 以前 showFinished / showAutoClosed / showOccupied / showOpenFailed 是四份
     * 各自十几行的 Builder 复制粘贴，渠道和优先级还得各写一遍——而这两件事**必须成对**，
     * 漏了优先级某些 ROM 就静默不弹（这个坑注释里记着，但复制时照样会漏）。
     *
     * @param alert true = 横幅类（开阀失败 / 设备被占用 / 超时关停），从屏幕顶上弹出
     * @param tab   点通知落到哪个 tab：0 首页 / 1 账单
     */
    private fun postEvent(
        context: Context,
        id: Int,
        alert: Boolean,
        title: String,
        body: String,
        tab: Int = 0
    ) {
        if (!canNotify(context)) return
        ensureChannels(context)

        // 渠道和优先级**一次算出来**。分成两个函数的话，调用方得自己记住
        // 「传了 alert 渠道就得配 alert 优先级」，而类型上没有任何约束
        val (channel, priority) = if (!alert) {
            CHANNEL_EVENTS to NotificationCompat.PRIORITY_DEFAULT
        } else if (PrefsHelper.notifyAlert) {
            CHANNEL_ALERT to NotificationCompat.PRIORITY_HIGH
        } else {
            CHANNEL_ALERT_QUIET to NotificationCompat.PRIORITY_DEFAULT
        }

        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notify_shower)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(priority)
            .setContentIntent(openApp(context, tab))
            .build()

        notify(context, id, n)
    }

    /**
     * 结果条正文。
     *
     * ⚠️ `money == null`（两条路都没拿到金额）和 `money == 0`（账单在、金额确实是 0）
     * **必须分开说**。以前一律显示「无消费」，于是「没查到」被说成了「没花钱」——
     * 用户明明用了热水，通知却告诉他无消费。
     */
    private fun resultBody(elapsedSec: Int, money: Double?): String {
        val timeText = formatDuration(elapsedSec)
        return when {
            money == null -> "用时 $timeText · 结算中"
            money > 0 -> "用时 $timeText · 消费 ¥%.2f".format(money)
            else -> "用时 $timeText · 无消费"
        }
    }

    /**
     * 「正在结算…」——关阀已经确认，账单金额还没回来。
     *
     * ⚠️ 用和结束通知**同一个 id**（[ID_FINISHED] / [ID_AUTO_CLOSED]），
     * 所以拿到金额后 `showFinished` / `showAutoClosed` 会把这一条**原地更新**掉——
     * 用户看到的是同一条通知从「结算中」变成「消费 ¥x.xx」，而不是先蹦一条再蹦一条。
     *
     * 不发横幅（`alert = false`）：结算只是个过渡态，没必要响两遍。
     */
    fun showSettling(context: Context, id: Int, title: String, elapsedSec: Int) {
        if (!PrefsHelper.notifyEnabled) return
        postEvent(context, id, alert = false, title = title,
            body = "用时 ${formatDuration(elapsedSec)} · 结算中…")
    }

    /**
     * 停止请求发出去了，但**没确认到设备真的停了**（多半是断网）。
     *
     * ⚠️ 这条不能省，也不能拿「使用结束」凑合。水可能还在流、钱还在扣，
     * 报一条「使用结束」就是**谎报**——用户据此走人，问题要到看账单时才发现。
     *
     * 文案刻意短：标题只写状态、正文只带设备名 + 后果。
     * 以前正文塞的是 `closeResult.message`，而那条消息长这样——
     * 「关阀请求没发出去：Unable to resolve host "v3-api.china-qzxy.cn"」，
     * 异常类名和主机名全在里面，通知栏折成三行，用户一个字都得不到有用信息。
     * 具体原因仍然进日志（`AppLogger.w`），那是排查用的，不是给用户看的。
     *
     * 用 [ID_FINISHED] 是对的：它和「结算中」「使用结束」抢同一个位置，
     * 三种结果互斥，不该在通知栏里并排躺着。
     */
    fun showCloseUnconfirmed(context: Context, deviceName: String) {
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyFinished) return
        postEvent(context, ID_FINISHED, alert = true,
            title = "关阀失败 · 网络异常",
            body = "设备未成功关闭",
            tab = 0)
    }

    /** 手动停止。点通知去账单页——刚消费完，多半想看这笔账 */
    fun showFinished(context: Context, deviceName: String, elapsedSec: Int, money: Double?) {
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyFinished) return
        postEvent(context, ID_FINISHED, alert = false,
            title = "使用结束 · ${shortName(deviceName)}",
            body = resultBody(elapsedSec, money),
            tab = 1)
    }

    /** 设备超时自动关停。同样去账单页 */
    fun showAutoClosed(context: Context, deviceName: String, elapsedSec: Int, money: Double?) {
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyAutoClose) return
        postEvent(context, ID_AUTO_CLOSED, alert = true,
            title = "设备已自动关停 · ${shortName(deviceName)}",
            body = resultBody(elapsedSec, money),
            tab = 1)
    }

    /**
     * 想开的水正被别人用着。这条不是「某一单在用」，回首页就行。
     *
     * 正文刻意只留「正在被他人使用」。以前后面还跟着一句
     * 「等对方用完再试，或者在小组件上『选用』换一台设备」——通知栏放不下，
     * 折成两三行反而把「哪台设备被占了」这个关键信息挤没了。
     */
    fun showOccupied(context: Context, deviceName: String, alert: Boolean = true) {
        if (!PrefsHelper.notifyEnabled) return
        // 标题只写状态、正文才带设备名：设备名很长（「龙川北苑 3号楼南 3层 320房」），
        // 放在标题里会把通知栏那一行占满，一眼看不出是"发生了什么"
        postEvent(context, ID_OCCUPIED, alert = alert,
            title = "设备占用中",
            body = "${shortName(deviceName)}正在被他人使用")
    }

    /**
     * 开阀失败（服务端拒绝：余额不足、有未扣账单、账户异常…）。
     *
     * 桌面小组件那边只显示得下「开阀失败」四个字，完整原因放不下，
     * 所以**由这条通知把服务端的原话带出来**——开阀失败时用户多半正在桌面上
     * 点小组件，横幅是他唯一能看到原因的地方。
     */
    fun showOpenFailed(context: Context, deviceName: String, reason: String) {
        if (!PrefsHelper.notifyEnabled) return
        postEvent(context, ID_OPEN_FAILED, alert = true,
            title = "开阀失败 · ${shortName(deviceName)}",
            body = reason)
    }

    /**
     * 设备名太长就省略**前面**，保留结尾的房号。
     *
     * 通知是系统渲染的，用不了 App 里的 TailEllipsisText，只能自己截。
     * 思路和它一致：「龙川北苑 3号楼南 320房」把前面截掉留「…南 320房」，
     * 一眼能认出是哪间；反过来截尾巴只剩「龙川北苑…」就白搭了。
     */
    private fun shortName(full: String, maxLen: Int = 14): String {
        val s = full.trim()
        return if (s.length <= maxLen) s else "…" + s.takeLast(maxLen - 1)
    }

    /** 总开关关掉时用的占位通知，只为了满足 startForeground，随即会被摘掉 */
    private fun minimal(context: Context): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_IN_USE_QUIET)
            .setSmallIcon(R.drawable.ic_notify_shower)
            .setContentTitle("淋浴")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    /** 时长文案：1小时02分 / 12分34秒 / 45秒 */
    fun formatDuration(sec: Int): String {
        if (sec <= 0) return "0 秒"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> "%d 小时 %02d 分".format(h, m)
            m > 0 -> "%d 分 %d 秒".format(m, s)
            else -> "%d 秒".format(s)
        }
    }

    /**
     * 打开 App。
     *
     * @param showShower true = 点进来直接落到**使用页**而不是首页。
     *        点「正在使用」那条通知的人就是想看当前这单用水的状态，
     *        把他扔到首页还得自己再找一次。
     */
    private fun openApp(context: Context, tab: Int, showShower: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, tab)
            if (showShower) putExtra(MainActivity.EXTRA_SHOW_SHOWER, true)
        }
        // requestCode 要把 showShower 也带上：两个 PendingIntent 只有 extra 不同的话，
        // FLAG_UPDATE_CURRENT 会让后建的覆盖先建的，结果点哪个都进同一个页面
        return PendingIntent.getActivity(
            context, (if (showShower) 7600 else 7000) + tab, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 「结束使用」按钮：把动作回传给正在跑的 ShowerWatchService */
    private fun stopAction(context: Context): NotificationCompat.Action {
        val intent = Intent(context, com.hualala.linyu.service.ShowerWatchService::class.java).apply {
            action = ACTION_STOP_SHOWER
        }
        val pi = PendingIntent.getService(
            context, 7100, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_widget_stop, "结束使用", pi
        ).build()
    }

    private fun notify(context: Context, id: Int, n: android.app.Notification) {
        // 权限可能在运行时被撤销，post 会抛 SecurityException
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }
}
