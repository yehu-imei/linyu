package com.hualala.linyu.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** 等待设备状态同步，只复核状态，不重新发送关阀指令。 */
internal object CloudCloseConfirmation {
    suspend fun confirm(
        pause: suspend () -> Unit = { delay(1_000) },
        readRunning: suspend () -> Boolean?
    ): Boolean = withTimeoutOrNull(9_000) {
        repeat(9) { attempt ->
            if (attempt > 0) pause()
            if (readRunning() == false) return@withTimeoutOrNull true
        }
        false
    } ?: false
}
