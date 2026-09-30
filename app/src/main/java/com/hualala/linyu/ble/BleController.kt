package com.hualala.linyu.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.hualala.linyu.utils.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** 设备状态帧（`0x23` 的应答）里挖出来的关键值 */
data class DeviceQueryState(
    /** 协议版本，2 位大写 hex，如 `"20"`（取自 `p[19]`） */
    val protocolType: String,
    /** 设备给的随机数，8 位 hex（取自 `p[24..27]`）——**必须用它调 rateOrder**，不能本地生成 */
    val randomNumber: String,
    /**
     * 设备状态字节（`p[28]`）。已观测值：
     * - 0 = 空闲
     * - 1 = 使用中（阀开着）
     * - **6 = 已关阀但有遗留未结算的消费记录**（2026-09-29 实测：结算上传被 170 拒后，
     *   设备停在 6，并会在下次被连上时主动推 0x22+0x85 把遗留记录给出来——这就是
     *   官方「蓝牙重连自动结算」的设备侧机制）。本地有该设备活跃订单时按恢复处理。
     */
    val state: Int,
    /** MAC 类型第 1 字节（`p[29]`）——`macType` 参数要用它，**不能自己猜** */
    val devType: Int,
    /** MAC 类型第 2 字节（`p[30]`）——同上 */
    val a1: Int
)

/** 连接结果。**必须区分「连上了但不是水控设备」**——见 [NoService] */
sealed interface BleConnectResult {
    /** 已就绪，可以发命令 */
    data object Ready : BleConnectResult

    /**
     * 连上了，但设备上**没有 FF00/FF01/FF02 服务**。
     *
     * 这只有一个解释：**它根本不是水控设备**（不是蓝牙表）。
     * 上层据此可以**自动撤销**「这台需要蓝牙」的标记，避免以后每次白试。
     *
     * ⚠️ 和普通的连接失败（超时 / 连不上）**不是一回事**：
     * 那种情况设备可能确实是蓝牙表，只是走远了或正忙，标记要保留。
     */
    data object NoService : BleConnectResult

    /** 连接失败（找不到设备、超时、系统异常等） */
    data class Failed(val reason: String) : BleConnectResult
}

/**
 * 蓝牙水表的 GATT 连接管理。
 *
 * ## 与 `BluetoothScanner` 的分工
 *
 * `BluetoothScanner` 只负责**发现**设备（拿 MAC）；这个类负责**连接与控制**。
 * 两者互不依赖——已经有 MAC 时直接连即可，不需要再扫一遍。
 *
 * ## 设计：把回调包成挂起函数
 *
 * BLE 的天生形态是回调，直接写业务流程会变成多层嵌套 + 状态标志，
 * 极难维护（参考实现里就有一堆 `xxxInFlight` 布尔量）。
 * 这里把每个操作包成 **suspend 函数**，用 [CompletableDeferred] / [Channel]
 * 等结果，上层就能按顺序写：
 *
 * ```kotlin
 * val st = ble.query() ?: return fail()
 * val rate = api.rateOrder(...) ?: return fail()
 * if (!ble.writeRate(rate)) return fail()
 * ```
 *
 * ## 协议要点（详见 [BleFrame]）
 *
 * | 命令 | 用途 | 应答 |
 * |---|---|---|
 * | `0x23` | 查询状态 | payload ≥31B：`p[19]`=protocolType、`p[24..27]`=randomNumber、`p[28]`=state |
 * | `0x21` | 下发费率包 | `payload[0] == 0x80` 表示成功 |
 * | `0x22` | 关阀 | `payload[0] == 0x80` 表示成功 |
 * | `0x85` | 采集消费数据 | payload 即 xfData |
 * | `0x86` | 写回设备存储 | `payload[0] == 0x80` 表示成功 |
 *
 * ## ⚠️ 调用前必须已有蓝牙权限
 *
 * `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`（Android 12+）由上层负责申请，
 * 这里只做 `@SuppressLint` 不做检查——权限没给会在系统层抛 SecurityException。
 */
@SuppressLint("MissingPermission")
class BleController(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000FF00-0000-1000-8000-00805F9B34FB")
        val NOTIFY_UUID: UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
        val WRITE_UUID: UUID = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 单次写入分片大小。BLE 默认 MTU 23，减去 3 字节头就是 20 */
        private const val CHUNK = 20

        /** 分片之间要留点时间，写太快设备会丢 */
        private const val CHUNK_GAP_MS = 20L

        const val CMD_QUERY = 0x23
        const val CMD_RATE = 0x21
        const val CMD_CLOSE = 0x22
        const val CMD_COLLECT = 0x85
        const val CMD_WRITE_BACK = 0x86

        /** 应答里表示「成功」的字节 */
        private const val ACK_OK = 0x80

        const val DEFAULT_TIMEOUT_MS = 8_000L
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    /** 收到的帧（cmd to payload）。用 Channel 而非回调，上层可以直接 await */
    private val incoming = Channel<Pair<Int, ByteArray>>(Channel.UNLIMITED)

    /** 累积收到的字节，按 `0x0A` 切帧——一帧可能分多次通知到达 */
    private val rxBuf = java.io.ByteArrayOutputStream()

    private var connectSignal: CompletableDeferred<Boolean>? = null
    private var notifySignal: CompletableDeferred<Boolean>? = null
    private var writeSignal: CompletableDeferred<Boolean>? = null

    @Volatile private var ready = false

    /** 本次连接是否遇到「连上了但没水控服务」——用于区分设备类型猜错的场景 */
    @Volatile private var sawNoService = false

    val isReady: Boolean get() = ready
    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    // ════════════════════════════════════════════
    //  连接
    // ════════════════════════════════════════════

    /**
     * 把各种写法的 MAC 归一化成 `getRemoteDevice` 要求的格式。
     *
     * ## ⚠️ 这里踩过坑：两种格式要求是**相反**的
     *
     * - 服务端接口（`rateOrder` 的 `macAddress` 参数）要 **无冒号**：`AABBCCDDEEFF`
     * - `BluetoothAdapter.getRemoteDevice()` 要 **带冒号**：`C4:7F:0E:DD:D7:C0`
     *
     * 之前上层把冒号去掉后直接传进来，`getRemoteDevice` 抛异常，
     * 日志里就是「MAC 非法 AABBCCDDEEFF」——**12 位十六进制它不认**。
     *
     * 归一化放在这里做（而不是让每个调用方自己拼），是因为**只有这一层知道
     * `getRemoteDevice` 的要求**；上层只管传服务端给的那个值。
     *
     * @return 带冒号的大写 MAC；长度不对时返回 null
     */
    private fun normalizeMac(raw: String): String? {
        val plain = raw.replace(":", "").replace("-", "").uppercase()
        if (plain.length != 12 || !plain.all { it in "0123456789ABCDEF" }) return null
        return plain.chunked(2).joinToString(":")
    }

    /**
     * 连接到指定 MAC，并等到「服务发现 + 通知开启」全部就绪。
     *
     * @param mac 服务端给的 MAC（**可带冒号也可不带**，内部会归一化）
     *
     * ⚠️ 只有返回 [BleConnectResult.Ready] 才能发命令。刚 connected 就写，
     * 特征还没拿到，会静默失败（`writeCharacteristic` 返回 false，但很容易被忽略）。
     */
    suspend fun connect(mac: String, timeoutMs: Long = 15_000L): BleConnectResult {
        val a = adapter ?: return BleConnectResult.Failed("本机没有蓝牙适配器")
        if (!a.isEnabled) return BleConnectResult.Failed("蓝牙未开启")

        val normalized = normalizeMac(mac)
            ?: return BleConnectResult.Failed("设备地址无效").also {
                AppLogger.e("BLE 连接失败：MAC 格式不对（$mac）", null)
            }

        val device = try {
            a.getRemoteDevice(normalized)
        } catch (t: Throwable) {
            AppLogger.e("BLE 连接失败：$normalized", t)
            return BleConnectResult.Failed("设备地址无效")
        }

        // 先清干净，避免上一次的残留状态影响这一次
        disconnect()
        ready = false
        sawNoService = false
        val notifyDone = CompletableDeferred<Boolean>()
        notifySignal = notifyDone

        return try {
            withTimeout(timeoutMs) {
                gatt = device.connectGatt(context, false, callback)
                // 等「通知已开启」——这才是真正可用的时刻
                if (notifyDone.await()) BleConnectResult.Ready
                else if (sawNoService) BleConnectResult.NoService
                else BleConnectResult.Failed("连接中断")
            }
        } catch (_: TimeoutCancellationException) {
            AppLogger.w("BLE 连接超时（${timeoutMs}ms）：$mac")
            // 超时前如果已经发现「没有水控服务」，也算 NoService
            if (sawNoService) BleConnectResult.NoService
            else BleConnectResult.Failed("连接超时，请靠近设备后重试")
        } catch (t: Throwable) {
            AppLogger.e("BLE 连接异常", t)
            BleConnectResult.Failed("蓝牙连接异常")
        } finally {
            notifySignal = null
            if (sawNoService || !ready) disconnect()
        }
    }

    fun disconnect() {
        ready = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        synchronized(rxBuf) { rxBuf.reset() }
        // 把还挂着的等待都放掉，别让上层协程悬着
        notifySignal?.takeIf { !it.isCompleted }?.complete(false)
        writeSignal?.takeIf { !it.isCompleted }?.complete(false)
    }

    // ════════════════════════════════════════════
    //  收发
    // ════════════════════════════════════════════

    /**
     * 发一条命令并**等对应 cmd 的应答帧**。
     *
     * @return 应答 payload；超时或写入失败返回 null
     */
    private suspend fun sendAndAwait(
        cmd: Int,
        data: ByteArray = ByteArray(0),
        replyCmd: Int = cmd,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ByteArray? {
        if (!ready) { AppLogger.w("BLE 未就绪，丢弃命令 0x${cmd.toString(16)}"); return null }

        // ⚠️ **先清残留、再发命令**——顺序不能反。
        // 设备会**主动推帧**（上一笔未结算交易的 0x22+0x85，即官方「蓝牙重连自动结算」
        // 的设备侧机制）。如果先 write 再 drain，写命令与等应答之间的窗口里到达的
        // 主动推帧会被误当成**本次命令的应答**：2026-09-29 实测，上一笔的消费数据
        // 被用这一笔的会话上传，服务端对不上账，报 170「签名错误」。
        drainIncoming()

        if (!write(BleFrame.cmdToWire(cmd, data))) return null
        return try {
            withTimeout(timeoutMs) {
                while (true) {
                    val (got, payload) = incoming.receive()
                    if (got == replyCmd) return@withTimeout payload
                    AppLogger.i("BLE 丢弃非期望帧 cmd=0x${got.toString(16)}")
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        } catch (_: TimeoutCancellationException) {
            AppLogger.w("BLE 等应答超时 cmd=0x${cmd.toString(16)}（${timeoutMs}ms）")
            null
        }
    }

    /** 查询设备状态（`0x23`）。返回 null 表示设备没应答或帧太短 */
    suspend fun query(timeoutMs: Long = DEFAULT_TIMEOUT_MS): DeviceQueryState? {
        val p = sendAndAwait(CMD_QUERY, timeoutMs = timeoutMs) ?: return null
        // 要读到 p[30]，所以至少 31 字节。实测应答更长，这里只是下限保护——
        // 帧短了就去读下标，会拿到越界后的垃圾值，进而把错误的 macType 发给服务端
        if (p.size < 31) {
            AppLogger.w("状态应答过短：${p.size}B（需要 ≥31B），无法解析")
            return null
        }
        return DeviceQueryState(
            protocolType = String.format("%02X", p[19].toInt() and 0xFF),
            randomNumber = BleFrame.toHex(p.copyOfRange(24, 28)),
            state = p[28].toInt() and 0xFF,
            devType = p[29].toInt() and 0xFF,
            a1 = p[30].toInt() and 0xFF
        )
    }

    /**
     * 下发费率包（`0x21`）。
     *
     * ⚠️ 这是**真正开阀**的一步。`downData` 必须来自服务端的 `rateOrder` 应答，
     * 不能自己造——写错可能多扣钱或损坏设备。
     *
     * @param downData 费率包原始字节（由服务端返回的 hex 串解码而来）
     */
    suspend fun writeRate(downData: ByteArray, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val p = sendAndAwait(CMD_RATE, downData, timeoutMs = timeoutMs) ?: return false
        return p.isNotEmpty() && (p[0].toInt() and 0xFF) == ACK_OK
    }

    /** 关阀（`0x22`） */
    suspend fun closeValve(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val p = sendAndAwait(CMD_CLOSE, timeoutMs = timeoutMs) ?: return false
        return p.isNotEmpty() && (p[0].toInt() and 0xFF) == ACK_OK
    }

    /**
     * 采集消费数据（`0x85`）。
     *
     * @return payload 即上传用的 `xfData`（hex 前需要自己转）
     */
    suspend fun collect(timeoutMs: Long = DEFAULT_TIMEOUT_MS): ByteArray? =
        sendAndAwait(CMD_COLLECT, timeoutMs = timeoutMs)

    /** 把结算结果写回设备存储（`0x86`） */
    suspend fun writeBack(data: ByteArray, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val p = sendAndAwait(CMD_WRITE_BACK, data, timeoutMs = timeoutMs) ?: return false
        return p.isNotEmpty() && (p[0].toInt() and 0xFF) == ACK_OK
    }

    /** 丢掉还没被取走的帧 */
    private fun drainIncoming() {
        while (true) {
            val r = incoming.tryReceive()
            if (r.isFailure) break
            AppLogger.i("BLE 丢弃残留帧 cmd=0x${r.getOrNull()?.first?.toString(16)}")
        }
    }
    // ════════════════════════════════════════════
    //  写入（分片）
    // ════════════════════════════════════════════

    /**
     * 把线上帧分片写入。
     *
     * ⚠️ 必须**分片**且**逐片等回执**：BLE 单次写入上限是 MTU-3（默认 20 字节），
     * 一帧 HEX 文本动辄上百字节，一次性写只会失败；而连续写不等回执，
     * 设备侧会丢片。
     */
    private suspend fun write(wire: ByteArray): Boolean {
        val c = writeChar ?: run { AppLogger.w("BLE 写入失败：特征未就绪"); return false }
        val g = gatt ?: return false

        var offset = 0
        while (offset < wire.size) {
            val len = minOf(CHUNK, wire.size - offset)
            val chunk = wire.copyOfRange(offset, offset + len)
            val signal = CompletableDeferred<Boolean>()
            writeSignal = signal

            val accepted = try {
                writeChunk(g, c, chunk)
            } catch (t: Throwable) {
                AppLogger.e("BLE 写入异常", t)
                writeSignal = null
                return false
            }
            if (!accepted) {
                AppLogger.w("BLE 分片未被接受（offset=$offset）")
                writeSignal = null
                return false
            }
            val ok = try {
                withTimeout(3_000L) { signal.await() }
            } catch (_: TimeoutCancellationException) {
                AppLogger.w("BLE 分片写入超时（offset=$offset）")
                writeSignal = null
                return false
            }
            writeSignal = null
            if (!ok) return false

            offset += len
            if (offset < wire.size) kotlinx.coroutines.delay(CHUNK_GAP_MS)
        }
        return true
    }

    /** 兼容各 API 版本的写入调用 */
    private fun writeChunk(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        chunk: ByteArray
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // ⚠️ Android 13+ 这个重载返回的是 BluetoothStatusCodes，**不是** BluetoothGatt.GATT_SUCCESS
        // （后者是旧 API 的返回值）。混用会被 Lint 判 WrongConstant；虽然两者数值恰好都是 0、
        // 实际比较能过，但语义不对，统一用 BluetoothStatusCodes。
        g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
            android.bluetooth.BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        c.value = chunk
        @Suppress("DEPRECATION")
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        g.writeCharacteristic(c)
    }

    // ════════════════════════════════════════════
    //  GATT 回调
    // ════════════════════════════════════════════

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    AppLogger.i("BLE 已连接，开始发现服务")
                    runCatching { g.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    AppLogger.w("BLE 已断开（status=$status）")
                    ready = false
                    notifySignal?.takeIf { !it.isCompleted }?.complete(false)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLogger.w("BLE 服务发现失败 status=$status")
                notifySignal?.takeIf { !it.isCompleted }?.complete(false)
                return
            }
            val svc = g.getService(SERVICE_UUID)
            val notify = svc?.getCharacteristic(NOTIFY_UUID)
            val write = svc?.getCharacteristic(WRITE_UUID)
            if (svc == null || notify == null || write == null) {
                // ⚠️ 这个分支很重要：设备连上了但没有水控服务 → **它根本不是水控表**。
                // 上层会据此撤销「这台需要蓝牙」的标记，免得以后每次都白试。
                AppLogger.w("BLE 未找到 FF00/FF01/FF02 特征——这台设备没有水控协议，不是蓝牙表")
                sawNoService = true
                notifySignal?.takeIf { !it.isCompleted }?.complete(false)
                return
            }
            writeChar = write
            runCatching { g.setCharacteristicNotification(notify, true) }

            val cccd = notify.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                AppLogger.w("BLE 未找到 CCCD 描述符")
                notifySignal?.takeIf { !it.isCompleted }?.complete(false)
                return
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }.onFailure {
                AppLogger.e("BLE 开启通知失败", it)
                notifySignal?.takeIf { !it.isCompleted }?.complete(false)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            ready = ok
            AppLogger.i("BLE 通知开启${if (ok) "成功，可以发命令" else "失败 status=$status"}")
            notifySignal?.takeIf { !it.isCompleted }?.complete(ok)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeSignal?.takeIf { !it.isCompleted }?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val bytes = characteristic.value ?: return
            appendAndDispatch(bytes)
        }
    }

    /** 累积字节并按 `0x0A` 切出完整帧 */
    private fun appendAndDispatch(bytes: ByteArray) {
        val frames = mutableListOf<ByteArray>()
        synchronized(rxBuf) {
            rxBuf.write(bytes)
            val all = rxBuf.toByteArray()
            var start = 0
            for (i in all.indices) {
                if (all[i].toInt() and 0xFF == BleFrame.WIRE_END) {
                    frames += all.copyOfRange(start, i + 1)
                    start = i + 1
                }
            }
            rxBuf.reset()
            if (start < all.size) rxBuf.write(all, start, all.size - start)
        }
        for (f in frames) {
            val frame = BleFrame.parseWire(f)
            if (frame == null) {
                AppLogger.w("BLE 收到非法帧：${BleFrame.toHex(f)}")
                continue
            }
            val cmd = BleFrame.cmdOf(frame)
            val payload = BleFrame.payloadOf(frame) ?: ByteArray(0)
            AppLogger.i("BLE 收帧 cmd=0x${cmd.toString(16)} payload=${BleFrame.toHex(payload)}")
            incoming.trySend(cmd to payload)
        }
    }
}
