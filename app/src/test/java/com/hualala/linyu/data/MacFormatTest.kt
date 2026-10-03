package com.hualala.linyu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「空口广播地址 ↔ 服务端台账 MAC」换算的回归测试。
 *
 * 用例里的地址都是真机抓到的（2026-10-03，南京理工大学紫金学院）：
 * 手机扫描到 `C0:15:83:04:D3:00`，服务端台账里是 `00:15:83:04:D3:00`。
 */
class MacFormatTest {

    /** 安卓 `BluetoothDevice.getAddress()` 给出的广播地址（随机静态地址，最高两位为 1） */
    private val bleAddress = "C0:15:83:04:D3:00"

    /** 服务端台账里的 MAC（公开地址）——官方客户端抓包实测 */
    private val ledgerMac = "00:15:83:04:D3:00"

    @Test
    fun `normalizes to twelve uppercase hex digits`() {
        assertEquals("C0158304D300", MacFormat.normalize(bleAddress))
        assertEquals("C0158304D300", MacFormat.normalize("c0:15:83:04:d3:00"))
        assertEquals("C0158304D300", MacFormat.normalize("C0-15-83-04-D3-00"))
        assertEquals("C0158304D300", MacFormat.normalize("C0158304D300"))
    }

    @Test
    fun `non mac input is not treated as mac`() {
        assertNull(MacFormat.normalize("QZXY20230001"))   // 扫码得到的 snCode
        assertNull(MacFormat.normalize("KLCXKJ-Water"))   // 广播名
        assertNull(MacFormat.normalize("C4:7F:0E"))       // 只有厂商前缀
        assertNull(MacFormat.normalize(""))
    }

    @Test
    fun `derives the ledger mac from the broadcast address`() {
        assertEquals("00158304D300", MacFormat.publicAddressOf(bleAddress))
        assertEquals("00158304D300", MacFormat.publicAddressOf("c0158304d300"))
        // 公开地址（最高两位为 0）推不出"更公开"的地址
        assertNull(MacFormat.publicAddressOf(ledgerMac))
    }

    @Test
    fun `derives the connect address back from the ledger mac`() {
        // 无分隔大写——与 Session.mac 的既有约定一致，冒号由 BleController.connect 归一化
        assertEquals("C0158304D300", MacFormat.connectAddressFor(ledgerMac))
        assertEquals("C0158304D300", MacFormat.connectAddressFor("00158304D300"))
    }

    @Test
    fun `the raw address is always tried first`() {
        assertEquals(bleAddress, MacFormat.lookupVariants(bleAddress).first())
        // 官方客户端实测用的写法（小写无分隔）紧随其后
        assertEquals("00158304d300", MacFormat.lookupVariants(bleAddress)[1])
    }

    @Test
    fun `schools whose ledger mac is already the broadcast address are untouched`() {
        // 项目文档里写的 KLCXKJ 前缀 C4:7F:0E —— 最高两位已经是 1，
        // 说明这类学校"台账 MAC == 广播地址"。连接地址必须退化成恒等变换，
        // 否则会把现在能用的学校改坏；候选列表第一项也仍然是原样。
        // 推不出来时返回**规范化后**的输入（无分隔大写），冒号仍由 BleController.connect 归一化
        assertEquals("C47F0EDDD7C0", MacFormat.connectAddressFor("C4:7F:0E:DD:D7:C0"))
        // 候选列表第一项永远是原样（一字不改）
        assertEquals("C4:7F:0E:DD:D7:C0", MacFormat.lookupVariants("C4:7F:0E:DD:D7:C0").first())
        // 候选列表里第一项之后的推导值命中不了也无害（服务端会回 data=null）
        assertEquals(2, MacFormat.lookupVariants("C4:7F:0E:DD:D7:C0").size)
    }
}
