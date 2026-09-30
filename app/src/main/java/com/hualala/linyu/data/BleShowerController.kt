package com.hualala.linyu.data

import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.bluetoothFailOrderSafe
import com.hualala.linyu.api.bluetoothRateOrderSafe
import com.hualala.linyu.api.bluetoothUploadDataSafe
import com.hualala.linyu.ble.BleConnectResult
import com.hualala.linyu.ble.BleController
import com.hualala.linyu.ble.BleFrame
import com.hualala.linyu.ble.DeviceQueryState
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.utils.AppLogger
import com.hualala.linyu.utils.KlcxkjCrypto
import com.hualala.linyu.utils.KlcxkjSigner
import kotlinx.coroutines.delay

/**
 * 蓝牙水表的**开阀 / 结算编排**。
 *
 * 对应 4G 表那条链路的 [ShowerController]——但两者差别很大：
 *
 * | | 4G 表（ShowerController） | 蓝牙表（本类） |
 * |---|---|---|
 * | 控制通道 | 云端下发 | **手机当网关**，BLE 直连 |
 * | 服务端角色 | 直接控制设备 | 只下发费率包、记账 |
 * | 断连后果 | 云端能兜 | **设备可能停在「开了但没记账」** |
 * | 额外依赖 | 无 | 无（签名纯 Kotlin，AES 写回已降级跳过） |
 *
 * ## 完整流程
 *
 * ```
 * 开阀：连接 → 查询状态 → 下费率(HTTP) → 写费率包(BLE 0x21) → 成功
 * 结算：关阀(0x22) → 采集(0x85) → 上传(HTTP) → 解密 → 写回(0x86)
 * ```
 *
 * ## ⚠️ 三个必须守住的点
 *
 * 1. **费率包必须来自服务端**——自己造一个写进设备，可能多扣钱或损坏设备
 * 2. **`randomNumber` 必须用设备给的**——它在状态帧里，本地生成的对不上
 * 3. **失败必须上报**（[failBluetoothOrderSafe]）——蓝牙通道没有服务端兜底，
 *    不报的话这一单会一直挂在预扣状态
 */
object BleShowerController {

    /** 一次蓝牙用水会话——开阀成功后拿到，结算时要用 */
    data class Session(
        val snCode: String,
        val mac: String,
        /** 设备状态帧里取到的协议版本 */
        val protocolType: String,
        /** 设备给的随机数 */
        val randomNumber: String,
        /** 服务端给的消费时间戳，**失败上报要用** */
        val consumeDate: String,
        val orderNo: String
    )

    sealed interface OpenOutcome {
        /** 开阀成功（设备已确认） */
        data class Opened(val session: Session) : OpenOutcome

        /**
         * 恢复用水——设备本来就在出水（`state != 0`），且本地有这台设备的活跃订单，
         * 说明是自己上一次没关干净 / App 被杀过。**不再重新开阀**，直接沿用当前
         * 会话进入使用页，让用户能继续计时、也能关阀。
         */
        data class Resumed(val session: Session) : OpenOutcome

        /**
         * 补结算完成——上一笔遗留账单已结清，但设备**不会立即**为下一个订单生成新的
         * randomNumber（只有 rateOrder 时才更新）。立刻开阀会复用旧 random 被服务端
         * 判 `226 加密校验失败`。所以这里断开连接、让上层提示用户**重新点开阀**。
         */
        data class Recovered(val message: String) : OpenOutcome

        /** 明确失败——可以安全重试 */
        data class Failed(val message: String) : OpenOutcome

        /**
         * 没确认成功。
         * ⚠️ 费率包**可能已经写进去了**，不能当成失败让用户重试，
         * 也不能当成成功让用户以为能洗澡——要引导他核对设备状态。
         */
        data class Unconfirmed(val message: String) : OpenOutcome

        /**
         * 蓝牙连上了，但设备上**没有水控服务**——这台**不是**蓝牙表。
         *
         * 上层收到这个应当**撤销**「该 snCode 需要蓝牙」的标记：
         * 说明用户当初猜错了，以后再走云端流程就好。
         */
        data class NotBleDevice(val message: String) : OpenOutcome
    }

    sealed interface SettleOutcome {
        /** 结算完成 */
        data class Completed(val rawUpMoney: Int?) : SettleOutcome

        /** 关阀成功但结算没走完（消费数据没上传或没写回） */
        data class PartialSettlement(val message: String) : SettleOutcome

        /** 关阀都没成功 */
        data class CloseFailed(val message: String) : SettleOutcome
    }

    /** 上传接口把这些错误码也算成功（服务端历史遗留，不同学校口径不一） */
    private val UPLOAD_OK_CODES = setOf(0, 206, 209)

    /** 设备状态：已关阀但有遗留未结算记录（官方 App 的 stateText="有遗留数据"，需补结算） */
    private const val STATE_PENDING_SETTLE = 3

    /** state=6：实测到的另一种「有遗留数据」状态（官方不识别，先与 3 同处理） */
    private const val STATE_PENDING_SETTLE_ALT = 6

    /**
     * 「签名错误，请更新版本」——服务端认为上传的数据与签名对不上。
     * 定案根因是 xfData 大小写不一致（签名小写、上传字段大写），已在两处统一
     * lowercase；保留此码做兜底识别，避免用户盲目重试。
     */
    private const val ERROR_SIGNATURE_REJECTED = 170

    // ════════════════════════════════════════════
    //  开阀
    // ════════════════════════════════════════════

    /**
     * 开阀。
     *
     * @param device    设备信息（要 `macAddress` / `realMac`、`deviceId`、类型 id）
     * @param loginCode 会话密钥，签名用
     * @param telephone 手机号，签名用
     */
    suspend fun openValve(
        ble: BleController,
        device: DeviceInfo,
        loginCode: String,
        telephone: String
    ): OpenOutcome {
        // 签名已改为纯 Kotlin 实现（算法见 KlcxkjSigner），不再依赖 native 库，
        // 所以这里没有「加密库不可用」这条分支了。
        if (loginCode.isEmpty() || telephone.isEmpty()) {
            return OpenOutcome.Failed("登录信息不完整，请重新登录")
        }

        // realMac 优先：部分设备 macAddress 是虚拟的，连不上。
        //
        // ⚠️ 这里**故意去掉冒号**——服务端接口（`rateOrder` 的 `macAddress` 参数）
        // 要的是无冒号格式 `AABBCCDDEEFF`。
        // 而 BLE 侧 `getRemoteDevice()` 要的是**带冒号**的，那个转换在
        // [BleController.connect] 内部做（只有那一层知道系统的要求）。
        // 两边要求相反，别在这里"统一"成一种。
        val mac = (device.realMac?.takeIf { it.isNotBlank() } ?: device.macAddress)
            .replace(":", "")
            .replace("-", "")
            .uppercase()
        if (mac.length != 12) {
            AppLogger.w("蓝牙开阀中止：MAC 长度不对（$mac）")
            return OpenOutcome.Failed("设备缺少有效的 MAC 地址，无法蓝牙连接")
        }

        // ① 连接
        AppLogger.i("蓝牙开阀：连接 $mac")
        when (val cr = ble.connect(mac)) {
            is BleConnectResult.Ready -> Unit
            is BleConnectResult.NoService ->
                return OpenOutcome.NotBleDevice("这台设备不支持蓝牙控制")
            is BleConnectResult.Failed ->
                return OpenOutcome.Failed(cr.reason)
        }

        try {
            // ② 查询设备状态——protocolType 和 randomNumber 都必须用设备给的
            var st = ble.query()
                ?: return OpenOutcome.Failed("设备无响应，请靠近后重试")
            AppLogger.i("蓝牙状态：protocol=${st.protocolType} random=${st.randomNumber} state=${st.state}")

            // state != 0 说明设备不是空闲
            if (st.state != 0) {
                // state=3/6：设备**已关阀但有遗留未结算记录**（上一笔结算失败留下的）。
                // 官方 App 此时走 confirmValveClosed(collect=true) 补结算：0x85 采集 →
                // 上传 → 0x86 写回，清掉记录后设备回到空闲。不处理的话永远开不了阀。
                if (st.state == STATE_PENDING_SETTLE || st.state == STATE_PENDING_SETTLE_ALT) {
                    AppLogger.i("蓝牙设备有遗留未结算数据（state=${st.state}），先补结算")
                    val settleResult = settlePending(ble, st, loginCode, telephone)
                    if (settleResult !is SettleOutcome.Completed) {
                        return OpenOutcome.Failed("设备有未结算记录，自动补结算失败，请稍后重试")
                    }
                    // 补结算已清掉设备记录（state 回 0）。断开连接，让用户重新点一次开阀——
                    // 重开阀需要一次全新的连接（设备在新连接里才会给出可用的会话参数）。
                    ble.disconnect()
                    return OpenOutcome.Recovered("已结清上一笔遗留账单，请重新点击开阀")
                } else if (ShowerController.isRunning(device.snCode)) {
                    // 本地有这台设备的活跃订单 → 是「恢复」场景：上一次没关干净 / App 被杀过，
                    // 设备其实在为自己出水。别当成「别人在用」报错，直接沿用当前会话进入
                    // 使用页——上层会保留这条 BLE 连接，让用户能关阀。
                    AppLogger.i("蓝牙设备已在用水（state=${st.state}），判定为恢复，直接进入使用页")
                    return OpenOutcome.Resumed(
                        Session(
                            snCode = device.snCode,
                            mac = mac,
                            protocolType = st.protocolType,
                            randomNumber = st.randomNumber,
                            consumeDate = "",   // 恢复没重新走 rateOrder，拿不到；只有失败上报才用
                            orderNo = ShowerController.activeOrderFor(device.snCode)?.orderNo ?: ""
                        )
                    )
                } else {
                    return OpenOutcome.Failed("设备正在使用中")
                }
            }

            // ③ 向服务端要费率包
            val signature = BleSignBuilder.signRateOrder(
                loginCode = loginCode,
                telephone = telephone,
                deviceId = device.deviceId.toString(),
                xfModel = XF_MODEL,
                randomNumber = st.randomNumber
            ) ?: return OpenOutcome.Failed("签名失败，请重新登录后重试")

            val resp = NetworkModule.apiService.bluetoothRateOrderSafe(
                deviceId = device.deviceId.toString(),
                macAddress = mac,
                macType = BleSignBuilder.macType(st.devType, st.a1),
                bigTypeId = (device.bigTypeId ?: 0).toString(),
                smallTypeId = (device.smallTypeId ?: 0).toString(),
                protocolType = st.protocolType,
                randomNumber = st.randomNumber.lowercase(),
                xfModel = XF_MODEL,
                signature = signature,
                auth = NetworkModule.authFields()
            )
            if (!resp.success || resp.data == null) {
                val msg = resp.displayMessage ?: "服务端未下发费率"
                AppLogger.w("蓝牙开阀失败：$msg")
                return OpenOutcome.Failed(msg)
            }

            val downDataHex = resp.data.downData.orEmpty()
            val consumeDate = resp.data.consumeDate.orEmpty()
            val downBytes = BleFrame.fromHex(downDataHex)
            // 费率包不合法就绝不能往设备里写——写进去的东西不对，后果不是"失败"而是"扣错钱"
            if (downBytes == null || downBytes.isEmpty()) {
                AppLogger.e("费率包无效：${downDataHex.take(64)}", null)
                return OpenOutcome.Failed("服务端费率数据无效，已停止启动")
            }

            // ④ 写入费率包——这一步真正开阀
            val written = ble.writeRate(downBytes)
            val session = Session(
                snCode = device.snCode,
                mac = mac,
                protocolType = st.protocolType,
                randomNumber = st.randomNumber,
                consumeDate = consumeDate,
                // 服务端不同学校口径下 orderNo 可能放在 liquidOrderNo 里（实测有学校
                // 两个都空）。存下来是为了对账/故障恢复时有凭据，空就空着，不强求。
                orderNo = resp.data.orderNo.orEmpty().ifEmpty { resp.data.liquidOrderNo.orEmpty() }
            )

            return if (written) {
                AppLogger.i("蓝牙开阀成功 orderNo=${session.orderNo}")
                // ⚠️ 必须在返回成功**之前**落盘活跃订单——上层拿到 Opened 就会启动
                // ShowerWatchService，服务要读活跃订单构建「使用中」通知。
                // 蓝牙路径以前漏了这一步（4G 路径在 openValve 里有），
                // 服务拿到空列表直接 `first()` 崩溃，进程死掉、连接丢失、阀关不了。
                ShowerController.ensureActiveOrder(device.snCode, session.orderNo, device)
                OpenOutcome.Opened(session)
            } else {
                // 命令发出去了但设备没回确认——**不能当失败**，费率包可能已经写进去了
                AppLogger.w("费率包已发送但设备未确认，结果未知")
                OpenOutcome.Unconfirmed("未能确认设备已开启，请确认水流状态后决定是否重试")
            }
        } finally {
            // 连接留给结算流程复用（关阀还要用），这里不断开。
            // 会话结束、或判定失败时由调用方 disconnect()。
        }
    }

    // ════════════════════════════════════════════
    //  关阀 + 结算
    // ════════════════════════════════════════════

    /**
     * 补结算：设备已关阀（state=3/6）但有遗留未结算记录，采集并上传，清掉设备记录。
     *
     * 对应官方 App 的 confirmValveClosed(collect=true)：0x85 采集 → 上传 →
     * 0x86 写回。写回成功后设备回到空闲（state=0），否则下次仍会卡在「有遗留数据」。
     *
     * @return 上传是否被服务端接受（写回失败不影响服务端已记账，仍算成功）
     */
    private suspend fun settlePending(
        ble: BleController,
        st: DeviceQueryState,
        loginCode: String,
        telephone: String
    ): SettleOutcome {
        val collectPayload = ble.collect()
        if (collectPayload == null || collectPayload.isEmpty()) {
            AppLogger.w("结算：采集消费数据失败")
            return SettleOutcome.PartialSettlement("未能读取消费数据")
        }
        // ⚠️ xfData 必须**小写**：服务端对大小写敏感，签名用小写、上传字段也得用小写，
        // 否则重算签名对不上（2026-09-29 实测 170）。Funnyass_school 也是 upload 前 lowercase。
        val xfData = BleFrame.toHex(collectPayload).lowercase()
        val signature = BleSignBuilder.signUpload(loginCode, telephone, xfData)
            ?: return SettleOutcome.PartialSettlement("签名失败")
        val resp = NetworkModule.apiService.bluetoothUploadDataSafe(
            protocolType = st.protocolType,
            randomNumber = st.randomNumber.lowercase(),
            xfData = xfData,
            signature = signature,
            auth = NetworkModule.authFields()
        )
        if (resp.errorCode !in UPLOAD_OK_CODES || resp.data == null) {
            // 170「签名错误」= 签名原文与上传字段不一致（已统一小写，若复现带日志反馈）。
            if (resp.errorCode == ERROR_SIGNATURE_REJECTED) {
                AppLogger.e("结算上传被拒（170）：签名与上传字段不一致，请带日志反馈", null)
                return SettleOutcome.PartialSettlement("结算签名校验未通过，请稍后重试")
            }
            AppLogger.w("结算上传失败（errorCode=${resp.errorCode}）：${resp.displayMessage ?: ""}")
            return SettleOutcome.PartialSettlement(resp.displayMessage ?: "上传失败")
        }
        // 解密 clData → 0x86 写回，清掉设备记录（纯 Kotlin AES，见 KlcxkjCrypto）
        val clData = resp.data.clData.orEmpty()
        val plain = KlcxkjCrypto.decryptByAes(clData)
        val dataHex = plain?.substringAfter("-", "")?.takeIf { it.isNotBlank() }
        val backBytes = dataHex?.let { BleFrame.fromHex(it) }
        if (backBytes == null) {
            AppLogger.w("结算写回数据无效，设备记录可能未清（服务端已记账）")
        } else if (!ble.writeBack(backBytes)) {
            AppLogger.w("结算写回设备失败（服务端已记账）")
        } else {
            AppLogger.i("结算写回设备成功，记录已清")
        }
        AppLogger.i("结算上传成功，upMoney=${resp.data.upMoney}")
        return SettleOutcome.Completed(resp.data.upMoney)
    }

    /**
     * 关阀并完成结算。
     *
     * 任何一步失败都会尝试 [failBluetoothOrderSafe] 上报——
     * **这很重要**：蓝牙通道没有服务端兜底，不上报的话这一单会一直挂在预扣状态。
     */
    suspend fun closeAndSettle(
        ble: BleController,
        session: Session,
        loginCode: String,
        telephone: String
    ): SettleOutcome {
        // ① 关阀
        if (!ble.closeValve()) {
            AppLogger.w("蓝牙关阀未确认")
            reportFail(loginCode, telephone, session)
            return SettleOutcome.CloseFailed("关闭设备未确认，请重试")
        }

        // ② ⚠️ 关阀后**断开重连**，再走结算。
        // 实测（2026-09-30，同一笔订单 97547 对比）：在同一连接里"关阀后立刻采集"的
        // 数据会被服务端判 226「加密校验失败」（账单停在 0.0）；而"重新连接后采集"的
        // 数据能被接受（补结算路径稳定成功，见 openValve 的 state=3 分支）。
        ble.disconnect()
        // 先**立即**试一次重连；BLE 栈偶尔还没处理完上一次断开，失败就稍等再试一次。
        // （原实现是无条件 `delay(400)` 再连——成功路径白等 400ms。）
        suspend fun tryReconnect(): Boolean = try {
            ble.connect(session.mac) is BleConnectResult.Ready
        } catch (t: Throwable) {
            AppLogger.e("关阀后重连异常", t)
            false
        }
        var reconnected = tryReconnect()
        if (!reconnected) {
            delay(500)
            reconnected = tryReconnect()
        }
        if (!reconnected) {
            AppLogger.w("关阀后重连失败，结算推迟到下次连接")
            return SettleOutcome.PartialSettlement("设备已关闭，但结算未完成，下次连接会自动结清")
        }

        // ③ 查设备状态 → 采集 + 上传 + 写回（复用补结算流程）
        val st = ble.query()
        if (st == null) {
            AppLogger.w("关阀重连后读取状态失败")
            return SettleOutcome.PartialSettlement("设备已关闭，但读取状态失败，下次连接会自动结清")
        }
        AppLogger.i("关阀重连后设备状态：state=${st.state} random=${st.randomNumber}")
        val result = settlePending(ble, st, loginCode, telephone)
        if (result !is SettleOutcome.Completed) {
            reportFail(loginCode, telephone, session)
        }
        return result
    }

    /**
     * 上报失败订单（`order/upload/bluetooth/fail`）。
     *
     * 幂等性由服务端保证；本方法**不抛出**——上报失败只记日志，
     * 不能因此把已经关好的阀又"报成失败"。
     */
    private suspend fun reportFail(loginCode: String, telephone: String, session: Session) {
        if (session.consumeDate.isBlank()) {
            AppLogger.w("跳过失败上报：没有 consumeDate（开阀时就没拿到）")
            return
        }
        try {
            val signature = BleSignBuilder.signFail(loginCode, telephone, session.consumeDate)
            if (signature == null) {
                AppLogger.w("失败上报签名失败")
                return
            }
            val resp = NetworkModule.apiService.bluetoothFailOrderSafe(
                consumeDate = session.consumeDate,
                signature = signature,
                auth = NetworkModule.authFields()
            )
            AppLogger.i("失败上报${if (resp.success) "成功" else "被拒：${resp.displayMessage}"}")
        } catch (t: Throwable) {
            AppLogger.e("失败上报异常", t)
        }
    }

    /** 计费模式。蓝牙表这条链路沿用 0，与 4G 表一致 */
    private const val XF_MODEL = "0"
}
