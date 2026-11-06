package com.hualala.linyu.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 上传成功不代表设备记录已清除；只有写回后的空闲状态才确认清除。 */
internal object BleSettlementCleanup {
    suspend fun confirm(writeBack: suspend () -> Unit, readState: suspend () -> Int?): Boolean {
        currentCoroutineContext().ensureActive()
        try {
            writeBack()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 应答丢失时设备也可能已执行；仍以只读查询为准，不重新上传消费。
        }
        currentCoroutineContext().ensureActive()
        val state = try {
            readState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        currentCoroutineContext().ensureActive()
        return state == 0
    }
}
