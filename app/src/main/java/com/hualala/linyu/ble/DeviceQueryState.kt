package com.hualala.linyu.ble

/**
 * 状态帧（`0x23` 应答）里开阀要用的字段。
 *
 * 字段位置**随载荷长度而变**，不能只有一套下标：
 *
 * | 载荷 | 来源 | protocolType | randomNumber | state | devType / a1 |
 * |---|---|---|---|---|---|
 * | 28 字节 | KRStateObject 短布局（AshWashing `AnalyTools` 同款） | 无此字段，给空串 | `p[21..22]`（4 位 hex） | `p[19]` | `p[23]` / `p[24]` |
 * | ≥31 字节 | 本仓库原有布局 | `p[19]` | `p[24..27]`（8 位 hex） | `p[28]` | `p[29]` / `p[30]` |
 *
 * ⚠️ 短布局的 `randomNumber` 只有 **2 字节**，按原宽度原样透传（不补零成 4 字节）；
 * `protocolType` 在短布局里根本不存在，给空串而不是猜一个 `10`/`20`。
 */
data class DeviceQueryState(
    /** 长布局的协议版本（2 位大写 hex）；短布局没有这个字段，为空串 */
    val protocolType: String,
    /** 设备给的挑战值 hex，保留设备原始宽度（4 位或 8 位）——**必须用它调 rateOrder**，不能本地生成 */
    val randomNumber: String,
    /**
     * 用水状态（`onCharge`），与设备故障位是**两个不同字段**。
     *
     * 已观测值：
     * - 0 = 空闲
     * - 1 = 使用中（阀开着）
     * - **3 = 消费已结束、数据待采集**
     * - **6 = 已关阀但有遗留未结算的消费记录**（2026-09-29 实测：结算上传被 170 拒后，
     *   设备停在 6，并会在下次被连上时主动推 0x22+0x85 把遗留记录给出来——这就是
     *   官方「蓝牙重连自动结算」的设备侧机制）。本地有该设备活跃订单时按恢复处理。
     */
    val state: Int,
    /** MAC 类型第 1 字节（设备大类）——`macType` 参数要用它，**不能自己猜** */
    val devType: Int,
    /** MAC 类型第 2 字节（设备小类）——同上 */
    val a1: Int
)

/**
 * 状态载荷解析器（纯函数，可单测）。
 *
 * 从 [BleController.query] 抽出来的原因：这段下标逻辑是「设备不响应」类问题的
 * 高发处，而它完全不需要蓝牙——抽出来就能在单元测试里用真实抓到的帧覆盖。
 */
internal object DeviceQueryParser {

    /**
     * 状态查询 `0x23` 的参数字节。
     *
     * 官方 SDK（`KRWMCore.krGetStateOfDevice`）实际传的是**一个零字节**，
     * 不是空载荷。空载荷组出的线上帧比设备期望的少一个 `00`（18 字节 vs 20 字节）。
     */
    fun requestData(): ByteArray = byteArrayOf(0)

    /** 解析失败返回 null（载荷为空、首字节不是成功标记、或长度不属于已知布局） */
    fun parse(payload: ByteArray): DeviceQueryState? {
        if (payload.isEmpty() || payload[0].toInt() and 0xFF != 0x80) return null
        return when {
            // 短布局：本表实测载荷正好 28 字节。
            payload.size == 28 -> DeviceQueryState(
                protocolType = "",
                randomNumber = BleFrame.toHex(payload.copyOfRange(21, 23)),
                state = payload[19].toInt() and 0xFF,
                devType = payload[23].toInt() and 0xFF,
                a1 = payload[24].toInt() and 0xFF
            )
            // 长布局：保持本仓库原有行为不变。
            payload.size >= 31 -> DeviceQueryState(
                protocolType = String.format("%02X", payload[19].toInt() and 0xFF),
                randomNumber = BleFrame.toHex(payload.copyOfRange(24, 28)),
                state = payload[28].toInt() and 0xFF,
                devType = payload[29].toInt() and 0xFF,
                a1 = payload[30].toInt() and 0xFF
            )
            // 其余长度没有可靠依据，宁可报未知也不要按错误的偏移去读。
            else -> null
        }
    }
}
