package com.hualala.linyu.model

import androidx.compose.ui.graphics.Color

data class DeviceInfo(
    val deviceId: Int,
    val deviceName: String,
    val snCode: String,
    val macAddress: String,
    val withholdMoney: Double,
    val onlineStatusId: Int,
    // 服务器返回的设备大类（饮水机 = 5），用于精确识别；缺失时回退到名称判断
    val bigTypeId: Int? = null,
    val bigTypeName: String? = null
) {
    val displayName: String get() = formatDeviceName(deviceName)
    val locationOnly: String get() = displayName

    /** 是否直饮水机：bigTypeId == 5，或设备名含相关关键词 */
    val isDrinkingWater: Boolean get() =
        bigTypeId == 5 ||
            deviceName.contains("饮水") || deviceName.contains("直饮") ||
            deviceName.contains("冷水") ||
            (deviceName.contains("热水") && !deviceName.startsWith("热水器") && !deviceName.startsWith("热水表"))

    /** 饮水机是否出热水（否则为冷水） */
    val isHotWater: Boolean get() =
        deviceName.contains("热") || deviceName.contains("开水") || bigTypeName?.contains("热") == true

    /** 设备类型名称 */
    val typeName: String get() = when {
        isDrinkingWater -> "饮水机"
        deviceName.startsWith("洗手台") -> "洗手台"
        else -> "热水器"
    }

    val typeEmoji: String get() = when {
        isDrinkingWater -> if (isHotWater) "♨️" else "❄️"
        deviceName.startsWith("洗手台") -> "🪥"
        else -> "🚿"
    }

    /**
     * 设备类型说明，给小组件副标题这类"小字"位置用。
     * 措辞与账单里的 [com.hualala.linyu.model.BillDTO.deviceTypeLabel] 保持一致。
     */
    val typeLabel: String get() = when {
        isDrinkingWater -> if (isHotWater) "直饮水机 · 热水" else "直饮水机 · 冷水"
        deviceName.startsWith("洗手台") -> "洗手台热水器"
        else -> "卫生间热水器"
    }

    val typeColor: Color get() = when {
        isDrinkingWater -> Color(0xFF10B981) // 饮水机：绿色
        deviceName.startsWith("洗手台") -> Color(0xFFFFCC80)
        else -> Color(0xFF2563EB)
    }

    val statusText: String get() = when {
        isDrinkingWater -> if (isHotWater) "正在接热水" else "正在接凉水"
        deviceName.startsWith("洗手台") -> "正在洗漱中"
        else -> "正在沐浴中"
    }

    companion object {
        fun formatDeviceName(name: String): String {
            val formatted = name
                // 1. 必须先处理「热水器 / 热水表」——否则下面按"热水"开头的规则会先吃掉"热水"，
                //    导致「热水表-xxx」被处理成「表 xxx」
                .replace(Regex("^热水[器表][- ]*"), "")
                // 2. 饮水机前缀（直饮冷水 / 直饮热水 等）
                .replace(Regex("^直饮[- ]*(开水|冷水|热水|温水)[- ]*"), "")
                .replace(Regex("^(开水|冷水|温水)[- ]*"), "")
                .replace(Regex("^(平衡|直饮水?机?)[- ]*"), "")
                // 3. 洗手台前缀
                .replace(Regex("^洗手台\\d*[- ]*"), "")
                // 4. 去掉尾部的水类型
                .replace(Regex("[- ](直饮)?(开水|冷水|热水|温水)$"), "")
                .replace(Regex("-\\d+层-"), "-")
                .replace(Regex("洗手台$"), "房")
                .replace("-", " ")
                .trim()
            return formatted.ifEmpty { name }
        }

        /** 开头的设备类型：`热水表-` / `洗手台54-` / `直饮冷水-` … */
        private val ROOM_PREFIX = Regex(
            "^(热水[器表]|洗手台\\d*|直饮(?:开水|冷水|热水|温水)|开水机?|冷水|温水|平衡|直饮水?机?)[- ]*"
        )

        /** 结尾的设备类型：`…-320洗手台` / `…-320热水表` */
        private val ROOM_SUFFIX = Regex(
            "[- ]*(?:热水器|热水表|洗手台|卫生间|洗漱台|浴室|淋浴间|淋浴" +
                "|饮水机|直饮水|开水机|开水器|水龙头|水房)$"
        )

        /**
         * 「寝室关键词」——判断两台设备在不在同一间寝室，就比这个。
         *
         * ## 规则
         *
         * 把设备名**掐头去尾**：剥掉开头的设备类型前缀、结尾的设备类型后缀、
         * 结尾的「房」字，剩下的就是寝室标识。取不到返回 null。
         *
         * | 设备名 | 关键词 |
         * |---|---|
         * | `热水表-龙川北苑-3号楼南-3层-320房` | `龙川北苑-3号楼南-3层-320` |
         * | `洗手台54-龙川北苑-3号楼南-3层-320洗手台` | `龙川北苑-3号楼南-3层-320` |
         * | `热水表-某校-1号楼-5层-829` | `某校-1号楼-5层-829` |
         *
         * ## 为什么不是「取最后一段」
         *
         * 取最后一段（`320`）看起来更简单，但那是**在只扫到一台设备时才出问题的规则**：
         * 列表里显示哪一条、存进去什么，取决于这次恰好扫到了热水表还是洗手台。
         * 同一间寝室会有两种结果——也就是「不会统一、保存的也不对」。
         * 掐头去尾得到的键**和设备无关**，扫到哪台都是同一个值。
         *
         * 顺带：键里带着楼栋和楼层，所以 `3号楼南-3层-320` 和 `3号楼北-3层-320`
         * 不会撞在一起。取最后一段就会撞。
         *
         * ## 三条不能动的规矩
         *
         * 1. **从原始设备名取，不从 [formatDeviceName] 的结果取。**
         *    [formatDeviceName] 是给界面看的，里面有 `.replace(Regex("洗手台$"), "房")`
         *    这种**凭空造字**的处理——拿它的结果当筛选依据，那个「房」在真实设备名里
         *    根本不存在，匹配必然落空。这正是外校那位报的「选择附近显示 829房、
         *    过滤结果为空」的根因。
         *
         * 2. **什么都不补、什么都不改。** 以前这里会「剥完只剩数字就补个房字」，
         *    就是上面那个 bug 的来源。
         *
         * 3. **这个函数必须是幂等的**：键过一遍还等于自己。绑定值存的就是键，
         *    筛选时会拿它再走一次 [inSameRoom]——不幂等就会自己筛掉自己。
         */
        fun roomKey(name: String): String? {
            var s = ROOM_PREFIX.replace(name, "")
            s = ROOM_SUFFIX.replace(s, "")
            s = s.removeSuffix("房")
            s = s.trim(' ', '　', '-', '_').lowercase()
            s = Regex("[-_\\s　]+").replace(s, "-")
            return s.ifEmpty { null }
        }

        /**
         * 寝室筛选的**唯一判据**：这台设备在不在 [boundKey] 代表的那间寝室里。
         *
         * ⚠️ 首页、小组件附近页、小组件主按钮——所有筛选都必须走这一个函数。
         * 以前首页筛原始设备名、小组件快照存的是格式化后的显示名，两份名字对不上，
         * 于是 App 里筛得干净、桌面上却还冒出别的寝室的设备。规则只能有一处。
         *
         * 两个条件：
         * - **相等**：绑定值就是选择附近存下来的完整键，正常情况走这条
         * - **以 `-键` 结尾**：兼容老版本存下来的短值（`320房` / `320`）。
         *   加那个 `-` 是为了不误伤——`…-1320` 不以 `-320` 结尾，`contains` 就会误匹配。
         *   用户已经绑过的值不能因为换算法就失效，否则升级后设备列表会突然全空。
         *
         * 取不出关键词（名字里只有类型词之类）时**放行**：宁可多显示几台，
         * 也不要因为一个怪名字把用户的设备全藏了。
         */
        fun inSameRoom(boundKey: String, deviceName: String): Boolean {
            val bound = boundKey.trim()
            if (bound.isEmpty()) return true
            val key = roomKey(bound) ?: return true
            val device = roomKey(deviceName) ?: return true
            return device == key || device.endsWith("-$key")
        }
    }
}

data class NearbyDevice(
    val name: String,
    val mac: String,
    val rssi: Int,
    val deviceInfo: DeviceInfo? = null
) {
    val displayName: String get() = deviceInfo?.displayName ?: DeviceInfo.formatDeviceName(name)
    val signalText: String get() = "信号强度: $rssi dBm"

    // 优先从 deviceInfo 获取类型，扫描阶段默认 🚿
    val typeEmoji: String get() = deviceInfo?.typeEmoji ?: "🚿"
    val typeColor: Color get() = deviceInfo?.typeColor ?: if (name.startsWith("洗手台")) Color(0xFFFFCC80) else Color(0xFF2563EB)
}
