package com.hualala.linyu.data

/**
 * 蓝牙水表的「空口广播地址 ↔ 服务端台账 MAC」换算。
 *
 * ## 为什么需要
 *
 * `/device/info/mac` 是拿 MAC 当**字符串**去查台账的，而手机扫描到的地址与服务端台账里
 * 存的那个**不是同一个**。实测（2026-10-03，南京理工大学紫金学院，projectId=71）：
 *
 * | 来源 | 这台设备的值 | 拿去查 `/device/info/mac` |
 * |---|---|---|
 * | 安卓 `BluetoothDevice.getAddress()`（空口广播地址） | `C0:15:83:04:D3:00` | `{"success":true,"data":null}` |
 * | 服务端台账的 `macAddress` | `00:15:83:04:D3:00` | 完整设备信息（见下） |
 *
 * 换算后的那次查询返回的是明文 JSON，开阀需要的字段全在里面：
 * `deviceId=3145`、`bigTypeId=4`、`smallTypeId=1`、`snCode=00158304D300`、预扣 5 元。
 *
 * 所以这两个地址认错，后果不是"查得慢"，而是**这台设备在附近列表里根本没有名字**
 * （显示成广播名 `KLCXKJ-Water`）、**点它也不会有任何反应**——拿不到 snCode 就弹不出设备详情，
 * 更走不到开阀。而它同时还会连累蓝牙连接：手机的蓝牙控制器只认空口广播地址。
 *
 * ## 为什么是这个规律
 *
 * 蓝牙规范要求**随机静态地址的最高两位必须是 1**，所以按规范实现的设备会把自己真实的
 * 公开 MAC 置上这两位当作广播地址用。于是两边各需要一次换算：
 *
 * | 用途 | 换算 | 方法 |
 * |---|---|---|
 * | 查台账 | 广播地址 → 台账 MAC（清掉最高两位） | [publicAddressOf] |
 * | 连蓝牙 | 台账 MAC → 广播地址（置上最高两位） | [connectAddressFor] |
 *
 * 两个方向都只是**首字节最高两位**的差异，其余 40 位完全相同：
 * `C0:15:83:04:D3:00` ↔ `00:15:83:04:D3:00`。
 *
 * ## ⚠️ 这不是万能公式，别当成"MAC 归一化"
 *
 * 只有「由真实 MAC 派生广播地址」的设备能这样换算。设备若用的是**完全随机**的静态地址
 * （同样 `11` 开头，但与真实 MAC 毫无关系），或是私有地址（RPA `01` / NRPA `00`，
 * 后者还会每十几分钟换一次），两个地址毫无关系，换算必然失败——那种情形只能在连上设备之后
 * 读它协议帧里自报的 MAC（官方 iOS 客户端走的就是这条路，因为它连链路地址都拿不到）。
 *
 * 也正因如此，本类只用来**增加候选**：原样地址永远排第一，换算结果排在它后面，
 * 命中不了就继续往下试。对台账里存的就是广播地址的学校（如文档写的 `C4:7F:0E` 前缀）
 * 第一次就命中，**不会有任何额外请求**。
 *
 * ## 冒号归 `BleController` 管，这里不碰
 *
 * 本类产出的字符串一律是**无分隔大写**——与服务端接口参数、`Session.mac` 的既有约定一致。
 * 「带不带冒号」是连接那一层的事，`BleController.normalizeMac` 已经在做，
 * 这里不再实现一份（换算结果交给 `ble.connect()` 时它会自己归一化）。
 */
object MacFormat {

    /** 规范化：12 位十六进制、无分隔、大写。不是 MAC 时返回 null */
    fun normalize(mac: String): String? {
        val hex = mac.filter { it.isLetterOrDigit() }
        if (hex.length != 12) return null
        if (!hex.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
        return hex.uppercase()
    }

    /**
     * 查设备信息时依次尝试的候选，**第一个永远是原样**。
     *
     * ⚠️ 顺序不能倒：台账里存的就是广播地址的学校（`C4:7F:0E` 那类）必须第 1 次就命中，
     * 否则每次扫描都会平白多一倍请求。
     */
    fun lookupVariants(mac: String): List<String> {
        val canon = normalize(mac) ?: return listOf(mac)
        val out = mutableListOf(mac)
        publicAddressOf(canon)?.let { derived ->
            // 小写无分隔——官方客户端实测用的就是这一种写法，服务端认
            if (derived.lowercase() != mac) out.add(derived.lowercase())
        }
        return out
    }

    /** 广播地址 → 台账 MAC（清掉首字节最高两位）。本来就是公开地址时返回 null */
    fun publicAddressOf(mac: String): String? {
        val canon = normalize(mac) ?: return null
        val first = canon.substring(0, 2).toIntOrNull(16) ?: return null
        if (first and 0xC0 == 0) return null
        return (first and 0x3F).toString(16).uppercase().padStart(2, '0') + canon.substring(2)
    }

    /**
     * 台账 MAC → 蓝牙连接地址（首字节最高两位置 1，**无分隔大写**）。
     *
     * 推不出来（说明本来就是个广播地址）时返回**规范化后**的输入——
     * 所以对一切正常的学校是恒等变换（只是顺手归一化了写法）。
     */
    fun connectAddressFor(ledgerMac: String): String {
        val canon = normalize(ledgerMac) ?: return ledgerMac
        val first = canon.substring(0, 2).toIntOrNull(16) ?: return ledgerMac
        if (first and 0xC0 != 0) return canon
        return (first or 0xC0).toString(16).uppercase().padStart(2, '0') + canon.substring(2)
    }
}
