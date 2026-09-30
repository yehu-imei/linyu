package com.hualala.linyu.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备类型识别的回归测试。
 *
 * 起因是一个真实 issue：某林业大学的表叫「**热水变**--1栋宿舍-11层-1001」，
 * 被识别成「饮水机 · 热水」——因为当时的判据是「名字含『热水』且不以
 * 『热水器』『热水表』开头」。淋浴设备的名字天然就含「热水」，
 * 这种**枚举排除法**遇到新命名就漏。
 *
 * 所以这里把「淋浴设备绝不能被判成饮水机」这件事锁死，
 * 而且刻意用**多种命名变体**来测，避免以后又变成打地鼠。
 */
class DeviceTypeTest {

    private fun device(
        name: String,
        bigTypeId: Int? = null,
        bigTypeName: String? = null,
        smallTypeId: Int? = null,
        isBle: Boolean? = null
    ) =
        DeviceInfo(
            deviceId = 1,
            deviceName = name,
            snCode = "SN",
            macAddress = "AA:BB:CC:DD:EE:FF",
            withholdMoney = 0.0,
            onlineStatusId = 1,
            bigTypeId = bigTypeId,
            bigTypeName = bigTypeName,
            smallTypeId = smallTypeId,
            isBle = isBle
        )

    // ── 蓝牙水表识别 ──

    @Test
    fun `only server-side isBle marks a bluetooth meter`() {
        // ⚠️ 实现**刻意只认 isBle**（见 DeviceModels.needsBluetoothControl 注释）：
        // smallTypeId==1 是「热水表」这个**通用类型名**——某校全校 162 条设备全是 1，
        // 却都是能云端开阀的 4G 表；早期拿它当蓝牙判据导致全校开阀失败。
        assertFalse(device("热水表-某工商职业学院-16D-1层-101房", smallTypeId = 1).needsBluetoothControl)
        assertFalse(device("热水变--1栋宿舍-11层-1001", smallTypeId = 1).needsBluetoothControl)

        // 只有服务端明确标记才算蓝牙表
        assertTrue(device("热水变--1栋宿舍-11层-1001", smallTypeId = 1, isBle = true).needsBluetoothControl)
        assertFalse(device("热水变--1栋宿舍-11层-1001", smallTypeId = 1, isBle = false).needsBluetoothControl)
    }

    @Test
    fun `smallTypeId 3 is cloud controllable`() {
        // 潍坊 790 系列能正常开阀
        assertFalse(device("790-16D某工商职业学院-1层-101", smallTypeId = 3).needsBluetoothControl)
    }

    /**
     * 关键防误判：字段缺失时**绝不能**当成蓝牙表。
     *
     * 旧版本客户端、或返回结构不同的学校可能拿不到 `smallTypeId`，
     * 那种情况下如果判成「不支持」，会把本来能用的设备直接挡在门外——
     * 比漏判严重得多（漏判只是回到现在的 306 提示）。
     */
    @Test
    fun `missing smallTypeId is never treated as bluetooth-only`() {
        assertFalse(device("热水表-某校-1号楼-1层-101").needsBluetoothControl)
        assertFalse(device("热水变--1栋宿舍-11层-1001").needsBluetoothControl)
    }

    // ── 淋浴设备：一律不能判成饮水机 ──

    @Test
    fun `热水变 is not drinking water`() {
        // 就是那个 issue 的原始名字，一个字都不要改
        val d = device("热水变--1栋宿舍-11层-1001", bigTypeId = 4, bigTypeName = "热水表")
        assertFalse(d.isDrinkingWater)
        assertEquals("热水器", d.typeName)
        assertEquals("🚿", d.typeEmoji)
        assertEquals("正在沐浴中", d.statusText)
    }

    @Test
    fun `热水表 is not drinking water`() {
        val d = device("热水表-某工商职业学院-16D-1层-101房", bigTypeId = 4)
        assertFalse(d.isDrinkingWater)
        assertEquals("🚿", d.typeEmoji)
    }

    @Test
    fun `热水器 is not drinking water`() {
        val d = device("热水器-某校-3号楼-3层-320")
        assertFalse(d.isDrinkingWater)
        assertEquals("🚿", d.typeEmoji)
    }

    /**
     * 关键防回归：**任何以「热水+单字」开头的淋浴命名都不能被判成饮水机**。
     * 以后遇到新变体时，这个测试会告诉你「排除法漏了」，而不是等用户来报。
     */
    @Test
    fun `any 热水X shower naming is not drinking water`() {
        listOf("热水表", "热水器", "热水变", "热水源", "热水机", "热水站", "热水房").forEach { prefix ->
            val d = device("$prefix-某校-1号楼-5层-829")
            assertFalse("「$prefix」被误判成饮水机了", d.isDrinkingWater)
        }
    }

    @Test
    fun `洗手台 is not drinking water`() {
        val d = device("洗手台54-龙川北苑-3号楼南-3层-320洗手台")
        assertFalse(d.isDrinkingWater)
        assertEquals("洗手台", d.typeName)
        assertEquals("🪥", d.typeEmoji)
    }

    // ── 饮水机：必须能认出来 ──

    @Test
    fun `bigTypeId 5 is drinking water`() {
        val d = device("某某设备-1号楼-1层-101", bigTypeId = 5)
        assertTrue(d.isDrinkingWater)
        assertEquals("饮水机", d.typeName)
    }

    @Test
    fun `直饮 naming is drinking water`() {
        listOf("直饮热水-某校-1号楼-1层-101", "直饮冷水-某校-1号楼-1层-101").forEach { n ->
            assertTrue("「$n」没被认成饮水机", device(n).isDrinkingWater)
        }
    }

    @Test
    fun `饮水机 and 开水机 naming is drinking water`() {
        listOf("饮水机-1号楼-1层-101", "开水机-1号楼-1层-101").forEach { n ->
            assertTrue("「$n」没被认成饮水机", device(n).isDrinkingWater)
        }
    }

    @Test
    fun `bigTypeName containing 饮 is drinking water`() {
        // 服务端大类名带「饮」字也算（正向判据）
        assertTrue(device("某某-1号楼-1层-101", bigTypeName = "直饮水机").isDrinkingWater)
    }

    // ── 设备名格式化 & 寝室键 ──

    @Test
    fun `热水变 prefix is stripped from display name`() {
        val formatted = DeviceInfo.formatDeviceName("热水变--1栋宿舍-11层-1001")
        assertFalse("显示名还残留着「热水变」", formatted.startsWith("热水变"))
        assertTrue(formatted.contains("1001"))
    }

    /**
     * 同寝室的热水变和洗手台必须算出**同一个**寝室键，
     * 否则绑定寝室后其中一台会被筛掉。
     */
    @Test
    fun `热水变 and 洗手台 in same room share a room key`() {
        val a = DeviceInfo.roomKey("热水变--1栋宿舍-11层-1001")
        val b = DeviceInfo.roomKey("洗手台-1栋宿舍-11层-1001洗手台")
        assertEquals(a, b)
        assertTrue(DeviceInfo.inSameRoom(a!!, "热水变--1栋宿舍-11层-1001"))
    }

    @Test
    fun `roomKey is idempotent for 热水变`() {
        val key = DeviceInfo.roomKey("热水变--1栋宿舍-11层-1001")!!
        assertEquals(key, DeviceInfo.roomKey(key))
    }
}
