package com.hualala.linyu.widget

// 从 WidgetRenderer.kt 拆出：「点一下会发生什么」的构造逻辑
// （PendingIntent + 布局映射），与 RemoteViews 渲染解耦。

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
import com.hualala.linyu.utils.MoneyFormat

    internal fun actionIntent(
        context: Context,
        appWidgetId: Int,
        disabled: WidgetRenderer.DisabledReason?,
        action: String
    ): PendingIntent? = when {
        // 没有 disabled，或只是「结果」提示（开阀失败 / 切换失败 / 他人使用中）→ 照常可点。
        // 用户在这些情况下多半就是想重试，把点击清掉会让小组件看起来坏了
        disabled == null || disabled.isNotice ->
            toggleIntent(context, appWidgetId, action)
        disabled == WidgetRenderer.DisabledReason.UNKNOWN ->
            toggleIntent(context, appWidgetId, LinYuWidgetProvider.ACTION_REFRESH)
        else -> null
    }

    /**
     * 只有两套布局，按尺寸选，**不再看宽高比**。
     * 竖向那套的中间区域用 `layout_weight=1` 撑满，被拉宽拉高都自己适配。
     */
    internal fun layoutRes(size: WidgetSize): Int = when (size) {
        WidgetSize.WIDE -> R.layout.widget_linyu_2x4
        WidgetSize.TILE -> R.layout.widget_linyu_1x1
        else -> R.layout.widget_linyu_2x2
    }

    // ── PendingIntent ──
    // requestCode 必须每个 widget、每个动作都不同，否则会互相覆盖

    internal fun openAppIntent(context: Context, appWidgetId: Int, tab: Int): PendingIntent {
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
    internal fun pickIntent(
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
    internal fun toggleIntent(context: Context, appWidgetId: Int, action: String): PendingIntent {
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
    internal fun tabIntent(context: Context, appWidgetId: Int, tab: Int): PendingIntent {
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

    internal fun providerClass(context: Context, appWidgetId: Int): Class<*> {
        val manager = AppWidgetManager.getInstance(context)
        val small = manager.getAppWidgetIds(
            android.content.ComponentName(context, LinYuWidget2x2::class.java)
        )
        return if (appWidgetId in small) LinYuWidget2x2::class.java else LinYuWidget2x4::class.java
    }
