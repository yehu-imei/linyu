package com.hualala.linyu.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 状态载荷解析的回归测试。
 *
 * 用例全部来自**真机抓到**的帧（2026-10-19 / 2026-10-20，梧州学院，projectId=513）：
 * 这块水表对 `0x23` 回的是 **28 字节短布局**，旧代码按 31 字节下限判定，
 * 直接报「状态应答过短」→ 上层显示「设备无响应」，开阀与结算都走不下去。
 *
 * 现场的典型表现：蓝牙能连上、服务发现正常、通知也能收到，唯独点了开水没反应。
 */
class DeviceQueryParserTest {

    /** 实测载荷（28 字节）：消费已结束、数据待采集（onCharge=3），故障位 0 */
    private val pendingSettle28 = hex("800000020100000A0400004BB5C47F0E3E4CFD030000000001000000")

    /** 同一台设备回到空闲（onCharge=0）时的载荷，由现场会话连续采集 */
    private val idle28 = hex("800000020100000A0400004BB5C47F0E3E4CFD000000000001000000")

    @Test
    fun `28 字节短布局按自己的偏移解析`() {
        val s = DeviceQueryParser.parse(pendingSettle28)!!
        assertEquals(28, pendingSettle28.size)
        assertEquals(3, s.state)
        // 短布局没有协议版本字段，不能拿 p[19] 去冒充
        assertEquals("", s.protocolType)
        // 挑战值只有 2 字节，原宽度透传（本帧全 0）
        assertEquals("0000", s.randomNumber)
        assertEquals(0, s.devType)
        assertEquals(1, s.a1)
    }

    @Test
    fun `短布局的用水状态与故障位互不干扰`() {
        // 同一帧 p19=3、p20=0：是「待采集」，不是「设备故障 3」
        val s = DeviceQueryParser.parse(pendingSettle28)!!
        assertEquals(3, s.state)
        assertEquals(0, pendingSettle28[20].toInt() and 0xFF)
    }

    @Test
    fun `空闲状态同样能解出来`() {
        val s = DeviceQueryParser.parse(idle28)!!
        assertEquals(0, s.state)
    }

    @Test
    fun `31 字节及以上仍走原长布局`() {
        val payload = ByteArray(31).apply {
            this[0] = 0x80.toByte()
            this[19] = 0x20            // protocolType
            this[24] = 0xA1.toByte()   // randomNumber 24..27
            this[25] = 0xB2.toByte()
            this[26] = 0xC3.toByte()
            this[27] = 0xD4.toByte()
            this[28] = 5               // state
            this[29] = 0x04            // devType
            this[30] = 0x01            // a1
        }
        val s = DeviceQueryParser.parse(payload)!!
        assertEquals("20", s.protocolType)
        assertEquals("A1B2C3D4", s.randomNumber)
        assertEquals(5, s.state)
        assertEquals(4, s.devType)
        assertEquals(1, s.a1)
    }

    @Test
    fun `首字节不是成功标记时拒绝`() {
        val bad = pendingSettle28.copyOf().apply { this[0] = 0x81.toByte() }
        assertNull(DeviceQueryParser.parse(bad))
        assertNull(DeviceQueryParser.parse(ByteArray(0)))
    }

    @Test
    fun `没有依据的长度一律拒绝`() {
        // 不能把 28 的偏移套到别的长度上——那会把错误的 macType/random 发给服务端
        for (size in listOf(20, 27, 29, 30)) {
            assertNull("size=$size 应被拒绝", DeviceQueryParser.parse(ByteArray(size).apply { this[0] = 0x80.toByte() }))
        }
    }

    @Test
    fun `三条无业务数据命令带上 SDK 的零字节`() {
        // 官方 SDK 的 KRWMCore 在这三条命令上都传一个零字节，不是空载荷
        assertEquals("#60000480230000A316\n", wire(0x23, DeviceQueryParser.requestData()))
        assertEquals("#60000480220000A216\n", wire(0x22, byteArrayOf(0)))
        assertEquals("#600004808500000516\n", wire(0x85, byteArrayOf(0)))
    }

    private fun wire(cmd: Int, data: ByteArray): String =
        String(BleFrame.cmdToWire(cmd, data), Charsets.US_ASCII)

    private companion object {
        fun hex(s: String): ByteArray = BleFrame.fromHex(s)!!
    }
}
