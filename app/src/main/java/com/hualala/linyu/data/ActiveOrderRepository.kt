package com.hualala.linyu.data

import com.hualala.linyu.model.ActiveOrder
import com.hualala.linyu.utils.PrefsHelper

/**
 * 活跃订单仓库。
 *
 * ## 解决什么问题
 *
 * 原先各处自己写「`getActiveOrders()` → 改 list → `saveActiveOrders()`」，这三步
 * **不是原子的**：App 界面和前台服务（同一进程的两个入口）并发跑时，典型的读-改-写
 * 竞态会让后写覆盖前写——表现为**订单凭空消失或复活**。
 *
 * `PrefsHelper` 给两个方法各加了 `@Synchronized`，但那只保护了单次调用，
 * 保护不了「读和写之间」的窗口。这里用一把锁把整段串行化，并只暴露语义化操作，
 * 让调用方**没有机会**再写出裸的三步。
 *
 * ⚠️ 同一进程内的锁（服务与 Activity 同进程）。若将来把服务拆到独立进程，
 * 这里要换成跨进程方案（文件锁 / ContentProvider）。
 */
object ActiveOrderRepository {

    private val lock = Any()

    fun all(): List<ActiveOrder> = PrefsHelper.getActiveOrders()

    fun find(snCode: String): ActiveOrder? =
        if (snCode.isEmpty()) null else all().find { it.snCode == snCode }

    fun isRunning(snCode: String): Boolean =
        snCode.isNotEmpty() && all().any { it.snCode == snCode }

    /** 原子地修改整份列表；block 内对 list 的增删改会被一次性写回 */
    fun update(block: (MutableList<ActiveOrder>) -> Unit) {
        synchronized(lock) {
            val list = PrefsHelper.getActiveOrders()
            block(list)
            PrefsHelper.saveActiveOrders(list)
        }
    }

    /** 加入；已存在同 snCode 的直接替换，不产生重复 */
    fun upsert(order: ActiveOrder) = update { list ->
        val i = list.indexOfFirst { it.snCode == order.snCode }
        if (i >= 0) list[i] = order else list.add(order)
    }

    /** 就地替换同 snCode 的那条（不存在则忽略——不要凭空创建） */
    fun replace(order: ActiveOrder) = update { list ->
        val i = list.indexOfFirst { it.snCode == order.snCode }
        if (i >= 0) list[i] = order
    }

    fun remove(snCode: String) = update { list -> list.removeAll { it.snCode == snCode } }

    fun clear() = synchronized(lock) { PrefsHelper.clearActiveOrders() }
}
