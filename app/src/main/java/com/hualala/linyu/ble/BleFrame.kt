package com.hualala.linyu.ble

/**
 * 水控设备的**帧协议**（纯函数，无状态，可单测）。
 *
 * ## 二进制帧
 *
 * ```
 * 下标:  0     1     2        3     4     5      6 … 6+n-1   6+n    7+n
 * 含义: 0x60  0x00  len+3   0x80  cmd   0x00   data[…]     crc   0x16
 * ```
 *
 * - `len` = `data.size + 3`（即下标 3、4、5 三个字节算进长度）
 * - **crc** = `(sum(data) + 0x80 + cmd) % 256`
 * - 总长 = `data.size + 8`
 *
 * ## 线上帧（真正写入 BLE 特征的字节）
 *
 * ```
 * 0x23('#') + 二进制帧的大写 HEX 文本(ASCII) + 0x0A('\n')
 * ```
 *
 * ⚠️ 发的是 **HEX 文本**而不是原始二进制——这是个很容易写错的地方：
 * 直接把二进制帧写进去，设备不会报错，只会静默不理你。
 *
 * ## 为什么单独抽出来
 *
 * 帧读写是最容易出错、也最难现场调试的一环（蓝牙连上但设备不响应，
 * 到底是帧错了还是设备不认？）。抽成纯函数之后可以在单测里逐字节验证，
 * 不用每次都上真机。
 */
object BleFrame {

    const val HEAD: Int = 0x60
    const val TAIL: Int = 0x16
    const val WIRE_START: Int = 0x23   // '#'
    const val WIRE_END: Int = 0x0A     // '\n'
    private const val FLAG: Int = 0x80

    /**
     * 组二进制帧。
     *
     * @param cmd  命令字（见 [BleCmd]）
     * @param data 载荷，可为空
     */
    fun build(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val len = data.size + 3
        val frame = ByteArray(data.size + 8)
        frame[0] = HEAD.toByte()
        frame[1] = 0
        frame[2] = len.toByte()
        frame[3] = FLAG.toByte()
        frame[4] = cmd.toByte()
        frame[5] = 0
        System.arraycopy(data, 0, frame, 6, data.size)

        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        frame[6 + data.size] = ((sum + FLAG + cmd) % 256).toByte()
        frame[7 + data.size] = TAIL.toByte()
        return frame
    }

    /** 二进制帧 → 线上帧（`#` + 大写 HEX + `\n`） */
    fun toWire(frame: ByteArray): ByteArray {
        val hex = toHex(frame).toByteArray(Charsets.US_ASCII)
        val out = ByteArray(hex.size + 2)
        out[0] = WIRE_START.toByte()
        System.arraycopy(hex, 0, out, 1, hex.size)
        out[out.size - 1] = WIRE_END.toByte()
        return out
    }

    /** 组帧并转线上格式，一步到位 */
    fun cmdToWire(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray =
        toWire(build(cmd, data))

    /**
     * 解析一条完整的线上帧（必须以 `0x0A` 结尾）。
     *
     * @return 二进制帧；格式或 CRC 不合法时返回 null
     */
    fun parseWire(wire: ByteArray): ByteArray? {
        if (wire.isEmpty() || wire.last().toInt() and 0xFF != WIRE_END) return null
        val start = if (wire[0].toInt() and 0xFF == WIRE_START) 1 else 0
        val hexLen = wire.size - start - 1
        if (hexLen <= 0 || hexLen % 2 != 0) return null
        val frame = fromHex(String(wire, start, hexLen, Charsets.US_ASCII)) ?: return null
        return if (verify(frame)) frame else null
    }

    /** 校验二进制帧的魔数、长度、结尾与 CRC */
    fun verify(frame: ByteArray): Boolean {
        if (frame.size < 8) return false
        if (frame[0].toInt() and 0xFF != HEAD) return false
        val len = frame[2].toInt() and 0xFF
        if (frame.size != len + 5) return false          // 总长 = (len-3)+8
        if (frame.last().toInt() and 0xFF != TAIL) return false
        val dataLen = len - 3
        if (dataLen < 0) return false
        var sum = 0
        for (i in 0 until dataLen) sum += frame[6 + i].toInt() and 0xFF
        val expected = (sum + (frame[3].toInt() and 0xFF) + (frame[4].toInt() and 0xFF)) % 256
        return (frame[frame.size - 2].toInt() and 0xFF) == expected
    }

    /** 取载荷（下标 6 起的 `data` 区）；帧不合法返回 null */
    fun payloadOf(frame: ByteArray): ByteArray? {
        if (!verify(frame)) return null
        val dataLen = (frame[2].toInt() and 0xFF) - 3
        if (dataLen < 0) return null
        return frame.copyOfRange(6, 6 + dataLen)
    }

    /** 帧里的命令字 */
    fun cmdOf(frame: ByteArray): Int = frame[4].toInt() and 0xFF

    // ── hex 工具 ──

    private const val HEX = "0123456789ABCDEF"

    /** 字节数组 → **大写** HEX 字符串 */
    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    /** HEX 字符串 → 字节数组（大小写都认）；非法输入返回 null */
    fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hexVal(hex[i * 2]) ?: return null
            val lo = hexVal(hex[i * 2 + 1]) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexVal(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    /** 取 4 字节大端整数（协议里金额、accountId 等都是这个序） */
    fun intAt(data: ByteArray, offset: Int): Int {
        var v = 0
        for (i in 0 until 4) v = (v shl 8) or (data[offset + i].toInt() and 0xFF)
        return v
    }
}
