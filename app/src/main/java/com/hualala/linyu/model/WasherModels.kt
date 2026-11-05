package com.hualala.linyu.model

import com.google.gson.annotations.SerializedName

data class WasherMode(
    val id: Long? = null,
    @SerializedName(value = "typeId", alternate = ["modeId"])
    val typeId: Int = 0,
    @SerializedName(value = "typeName", alternate = ["modeName"])
    val typeName: String? = null,
    val typeValue: String? = null,
    val projectId: String? = null,
    val smallTypeId: String? = null,
    @SerializedName(value = "money", alternate = ["modeMoney"])
    val money: Int = 0,
    @SerializedName(value = "times", alternate = ["modeTimeQuantity"])
    val times: Int = 0,
    val modeMoneyYuan: String? = null,
    val modeTimeMinute: String? = null,
    val positionId: Int? = null,
    val goodsId: Int = 0,
    val isSelected: Int = 0,
    @SerializedName(value = "descname", alternate = ["modeDesc"])
    val descname: String? = null
) {
    val displayName: String get() = typeName?.takeIf { it.isNotBlank() } ?: "未命名模式"


    val priceYuan: Double
        get() = modeMoneyYuan?.toDoubleOrNull()
            ?: (money.coerceAtLeast(0) / 1000.0)


    val durationMinutes: Int
        get() = modeTimeMinute?.toIntOrNull()?.takeIf { it > 0 }
            ?: (times.coerceAtLeast(0) + 59) / 60


    val isMainMode: Boolean get() = positionId == null || positionId == 0
}

fun washerMainModes(modes: List<WasherMode>): List<WasherMode> =
    modes.filter { it.isMainMode }
        .distinctBy { it.typeId }

data class WasherStartData(
    val isOwner: Boolean = true,
    val perMoney: Int = 0,

    val timeIds: String? = null
)

data class WasherStatus(
    val isOwner: Boolean = true,
    @SerializedName(value = "modelName", alternate = ["modeName"])
    val modelName: String? = null,
    val modeTime: Int = 0,
    val modeMoney: Int = 0,
    val time: Int = 0,
    val timeIds: String? = null,
    val liquidOrderNo: String? = null,
    val liquidOrderStatus: Int = 0,
    val liquidOrderStatusDisplay: String? = null,
    val liquidModeName: String? = null,
    val liquidConsumeMoney: Int = 0,
    val perMoney: Int = 0,
    val leftModeName: String? = null,
    val leftModeMoney: Int = 0,
    val leftGoodsName: String? = null,
    val rightModeName: String? = null,
    val rightModeMoney: Int = 0,
    val rightGoodsName: String? = null
) {
    val orderKey: String get() = timeIds?.takeIf { it.isNotBlank() }
        ?: liquidOrderNo?.takeIf { it.isNotBlank() }
        ?: ""

    val stageText: String get() =
        liquidOrderStatusDisplay?.takeIf { it.isNotBlank() }
            ?: modelName?.takeIf { it.isNotBlank() }
            ?: "设备运行中"


    val looksActive: Boolean get() =
        orderKey.isNotEmpty() || liquidOrderStatus != 0 || time > 0
}

data class WasherSession(
    val device: DeviceInfo,
    val mode: WasherMode,
    val orderId: String,
    val startedAtMs: Long = System.currentTimeMillis(),
    val preDeductMilliYuan: Int = 0
) {
    val preDeductYuan: Double get() = preDeductMilliYuan.coerceAtLeast(0) / 1000.0
}
