package com.hualala.linyu.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 用水结束的**进程内**事件。
 *
 * 监控搬进 [com.hualala.linyu.service.ShowerWatchService] 之后，App 的洗澡界面
 * 就没法靠自己知道"设备已经关了"了——以前它靠 `MainViewModel.timerJob` 里的
 * 每 15 秒轮询，而那正是要删掉的东西（退出界面会 cancel 它，小组件开的水更是压根没启动）。
 *
 * 服务和 Activity 跑在同一个进程里（没给 service 设 `android:process`），
 * 所以用一个进程级 SharedFlow 把结果递过去就够了，不需要 Messenger / Binder 那套。
 *
 * ⚠️ **没有 replay**：App 不在的时候事件就是丢了，这是有意的——
 * 那种情况下服务自己已经把状态清好、通知也发了，App 下次打开读 Prefs 就是对的，
 * 再补一个事件反而会让界面莫名其妙地弹出"使用结束"弹窗。
 */
object ShowerEvents {

    /** 一次用水的结束 */
    data class Finished(
        val snCode: String,
        val deviceName: String,
        val elapsedSec: Int,
        /**
         * 本次消费金额。**null = 没拿到**（结算超时，两条路都失败），
         * 和 `0.0`（账单在、确实没花钱）不是一回事——消费方别把 null 当成「无消费」。
         */
        val money: Double?,
        /** 设备自己超时关的（而不是被外部关闭） */
        val autoClosed: Boolean
    )

    private val _finished = MutableSharedFlow<Finished>(extraBufferCapacity = 8)
    val finished: SharedFlow<Finished> = _finished

    fun notifyFinished(event: Finished) {
        _finished.tryEmit(event)
    }
}
