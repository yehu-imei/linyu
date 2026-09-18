package com.hualala.linyu.model

/**
 * 桌面小组件的「离线快照」。
 *
 * 小组件跑在广播接收器里，既扫不了蓝牙也拉不了账单——所以 2x4 的
 * 「附近设备」和「账单」两页展示的是**上一次 App 运行时同步到的数据**，
 * 界面上会标出同步时间，避免让人误以为是实时的。
 */

/**
 * 附近设备的一条（小组件用）。
 *
 * ⚠️ 字段一律声明为**可空**：这些对象是 Gson 从本地 JSON 反序列化出来的，
 * 而 Gson 是绕过构造函数、直接写字段的——**不认 Kotlin 的非空类型**。
 * 只要 JSON 是旧版本写的（少一个字段），取出来就是 null，
 * 在非空类型上调用 `isNotEmpty()` 之类会直接 NPE 让小组件崩掉。
 * 取值时记得 `?: ""`。
 */
data class CachedDevice(
    val emoji: String? = null,
    /** 界面上显示的名字（**格式化过**的） */
    val name: String? = null,
    val desc: String? = null,
    val rssi: Int = 0,
    /** MAC 地址：小组件的「选用」要把这个带回 App 才能绑设备 */
    val mac: String? = null,
    /**
     * **原始**设备名，只用来做寝室筛选。
     *
     * 为什么单独存一份：[name] 是 `formatDeviceName` 格式化过的显示名，里面带
     * 「洗手台→房」这种**凭空造字**的处理。拿它当筛选依据，关键词在真实设备名里
     * 根本不存在，过滤必然落空。首页筛的是原始名，小组件也必须筛原始名，两边才一致。
     *
     * 老版本写的快照里没有这个字段 → 取出来是 null → 调用方回退到 [name]，不崩。
     */
    val rawName: String? = null
)

/** 账单的一条（小组件用）。字段可空的原因同上 */
data class CachedBill(
    val emoji: String? = null,
    val name: String? = null,
    val timeText: String? = null,
    val moneyText: String? = null,
    /**
     * 原始时间戳（毫秒）与原始金额，专门给「余额估算」用。
     *
     * 界面显示的那两个字段是**格式化过的字符串**（"-¥ 1.84"），拿去做减法要反过来解析文本，
     * 既脆又容易出错；而且 Gson 不受 Kotlin 非空约束，老版本写的快照里没有这两个字段，
     * 取出来必然是 null，所以必须是可空类型。
     */
    val rawTimeMs: Long? = null,
    val rawMoney: Double? = null
)
