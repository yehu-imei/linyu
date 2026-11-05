package com.hualala.linyu.data

import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.getWasherModesSafe
import com.hualala.linyu.api.queryWasherStatusSafe
import com.hualala.linyu.api.startWasherSafe
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.model.WasherMode
import com.hualala.linyu.model.WasherSession
import com.hualala.linyu.model.WasherStatus
import com.hualala.linyu.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

sealed interface WasherStartOutcome {
    data class Started(
        val session: WasherSession,
        val status: WasherStatus?
    ) : WasherStartOutcome

    data class Resumed(
        val session: WasherSession,
        val status: WasherStatus?
    ) : WasherStartOutcome

    data class InUseByOthers(val message: String) : WasherStartOutcome

    data class Failed(
        val message: String,
        val errorCode: Int? = null
    ) : WasherStartOutcome
}

object WasherController {


    suspend fun loadModes(device: DeviceInfo) =
        NetworkModule.apiService.getWasherModesSafe(
            smallTypeId = device.smallTypeId
                ?: throw IllegalArgumentException("设备缺少 smallTypeId，无法读取洗衣模式")
        )


    suspend fun start(
        device: DeviceInfo,
        mode: WasherMode
    ): WasherStartOutcome {
        if (!device.isLaundry) {
            return WasherStartOutcome.Failed("该设备不是已支持的洗衣机")
        }
        if (device.snCode.isBlank()) {
            return WasherStartOutcome.Failed("设备缺少 SN 码，无法启动")
        }
        if (mode.typeId <= 0) {
            return WasherStartOutcome.Failed("所选模式缺少有效编号")
        }

        val resp = try {
            NetworkModule.apiService.startWasherSafe(
                snCode = device.snCode,
                modeTypeId = mode.typeId,
                auth = NetworkModule.authFields(),
                isHaier = device.devTypeStatus == 7
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("启动洗衣机失败", e)
            return WasherStartOutcome.Failed("网络异常，未能启动设备")
        }

        if (resp.success && resp.errorCode == 0) {
            val orderId = resp.data?.timeIds.orEmpty()
            val status = pollInitialStatus(device)
            val resolvedOrder = orderId.ifBlank { status?.orderKey.orEmpty() }
            if (resolvedOrder.isBlank()) {
                AppLogger.w("洗衣机启动成功，但暂未拿到 timeIds：${device.snCode}")
            }
            return WasherStartOutcome.Started(
                session = WasherSession(
                    device = device,
                    mode = mode,
                    orderId = resolvedOrder,
                    preDeductMilliYuan = resp.data?.perMoney ?: mode.money
                ),
                status = status
            )
        }
        if (resp.errorCode == 307 && resp.data?.isOwner != false) {
            val status = pollInitialStatus(device)
            val orderId = resp.data?.timeIds.orEmpty().ifBlank { status?.orderKey.orEmpty() }
            return WasherStartOutcome.Resumed(
                session = WasherSession(
                    device = device,
                    mode = mode,
                    orderId = orderId,
                    preDeductMilliYuan = resp.data?.perMoney ?: mode.money
                ),
                status = status
            )
        }
        if (resp.errorCode == 307 || resp.data?.isOwner == false) {
            return WasherStartOutcome.InUseByOthers("该设备正在被他人使用")
        }
        return WasherStartOutcome.Failed(
            resp.displayMessage ?: "启动失败",
            resp.errorCode
        )
    }

    suspend fun status(device: DeviceInfo): WasherStatus? {
        if (device.snCode.isBlank()) return null
        val resp = try {
            NetworkModule.apiService.queryWasherStatusSafe(
                snCode = device.snCode,
                auth = NetworkModule.authFields(),
                isHaier = device.devTypeStatus == 7
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("查询洗衣机状态失败", e)
            return null
        }

        if (resp.data?.isOwner == false) return resp.data
        return resp.data.takeIf { resp.success || resp.errorCode == 307 }
    }


    private suspend fun pollInitialStatus(device: DeviceInfo): WasherStatus? {
        repeat(4) { attempt ->
            if (attempt > 0) delay(500)
            status(device)?.let { return it }
        }
        return null
    }
}
