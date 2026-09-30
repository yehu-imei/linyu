package com.hualala.linyu.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志脱敏的回归测试。
 *
 * `AppLogger.mask` 是「导出日志不泄密」的**唯一**防线（`NetworkModule` 的
 * Release 拦截器会把响应体前 400 字符原样送进来），所以它的边界必须锁死：
 *
 * 1. 验证码（`QzxyService` 用通用字段名 `code` 传）必须被遮
 * 2. `snCode` / `useCode` 这类**含 Code 但不该遮**的，不能被误伤——
 *    设备序列号是排查结算问题时唯一能把日志和账单对上的线索
 * 3. 账号标识、手机号按既有规则处理
 */
class AppLoggerMaskingTest {

    /**
     * 走真实写入路径（`write()` → `mask()` → 内存缓冲），而不是直接调 private 的 mask。
     * 不 `init(context)`：`logFile` 为 null 时 `appendToFile` 会直接返回，
     * 内存缓冲照常工作，单测里不需要文件系统。
     */
    private fun masked(msg: String): String {
        AppLogger.clear()
        AppLogger.i(msg)
        return AppLogger.getLogs().last()
    }

    @Test
    fun `sms code field is masked`() {
        val line = masked("resp: {\"code\":\"481920\"}")
        assertTrue(line.contains("code=***"))
        assertFalse(line.contains("481920"))
    }

    @Test
    fun `sms code in form body is masked`() {
        val line = masked("req: telephone=19112342082&code=481920&typeId=3")
        assertTrue(line.contains("code=***"))
        assertFalse(line.contains("481920"))
    }

    @Test
    fun `device serial is NOT masked`() {
        val line = masked("open valve snCode=AABBCCDDEEFF")
        assertTrue(line.contains("AABBCCDDEEFF"))
    }

    @Test
    fun `order number is NOT masked`() {
        val line = masked("settle orderNo=13200000000000000000")
        assertTrue(line.contains("13200000000000000000"))
    }

    @Test
    fun `use code IS masked because it can open a valve`() {
        val line = masked("{\"useCode\":\"87654321\"}")
        assertTrue(line.contains("useCode=***"))
        assertFalse(line.contains("87654321"))
    }

    @Test
    fun `account identifiers are masked`() {
        val line = masked("{\"userId\":991234,\"accountId\":556677}")
        assertFalse(line.contains("991234"))
        assertFalse(line.contains("556677"))
    }

    @Test
    fun `password and loginCode are masked`() {
        val line = masked("{\"password\":\"abc123\",\"loginCode\":\"xy7788\"}")
        assertFalse(line.contains("abc123"))
        assertFalse(line.contains("xy7788"))
    }

    /**
     * 手机号出现在 `telephone=` 之类**字段名**后面时，整值替换成 `***`——
     * 字段级规则优先于号码级规则，这是对的：既然知道它是手机号字段，
     * 就没必要再留前 3 后 4。
     */
    @Test
    fun `phone inside a known field is fully masked`() {
        val line = masked("telephone=19112342082")
        assertTrue(line.contains("telephone=***"))
        assertFalse(line.contains("19112342082"))
    }

    /** 裸手机号（不在已知字段名后）才走「保留前 3 后 4」 */
    @Test
    fun `bare phone keeps first three and last four`() {
        val line = masked("用户手机号 19112342082 已登录")
        assertTrue(line.contains("191****2082"))
        assertFalse(line.contains("19112342082"))
    }
}
