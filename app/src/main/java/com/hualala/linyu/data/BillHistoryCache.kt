package com.hualala.linyu.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hualala.linyu.api.ProjectAuthContext
import com.hualala.linyu.model.BillDTO
import com.hualala.linyu.model.BillItem
import java.time.YearMonth

/** JSON codec for the account-scoped bill snapshot shown while refresh is running. */
object BillHistoryCache {
    private val gson = Gson()
    private val type = object : TypeToken<List<BillItem>>() {}.type

    fun keyFor(accountKey: String): String = "billCache_$accountKey"

    /** 项目列表有多个账户，按登录所有者、账单来源和月份分别刷新。 */
    fun monthAccountKey(owner: String, source: ProjectAuthContext, month: YearMonth): String =
        "$owner/${source.projectId}/${source.accountId}/$month"

    fun fromProject(bills: List<BillItem?>, source: ProjectAuthContext): List<BillItem> =
        bills.mapNotNull { item ->
            // 接口也可能返回充值/退款等不含 consumeBillDTO 的记录。
            val dto: BillDTO = item?.consumeBillDTO ?: return@mapNotNull null
            val date = listOf(dto.consumeDate, dto.dealDate, dto.createTime)
                .firstOrNull { !it.isNullOrBlank() } ?: return@mapNotNull null
            val description = dto.description.orEmpty().ifBlank { dto.deviceDescription.orEmpty() }
            val ordinaryAmount = dto.consumeMoney.orEmpty()
            val thirdPartyAmount = dto.thirdTradeMoney.orEmpty()
            val ordinarySign = ordinaryAmount.toBigDecimalOrNull()?.signum() ?: 0
            val amount = if (ordinarySign == 0 &&
                thirdPartyAmount.toBigDecimalOrNull()?.signum() == 1) thirdPartyAmount else ordinaryAmount
            BillItem(
                dto.copy(
                    orderId = dto.orderId.orEmpty().ifBlank { dto.orderNo.orEmpty() },
                    consumeDate = date,
                    consumeMoney = amount,
                    description = description
                ),
                source.projectId,
                source.accountId
            )
        }

    fun newestFirst(bills: List<BillItem>): List<BillItem> {
        val orderNumbers = mutableSetOf<Pair<String, String>>()
        val scopedIds = mutableSetOf<List<String?>>()
        return bills.filter { bill ->
            val dto = bill.consumeBillDTO
            val orderNo = dto.orderNo?.takeIf { it.isNotBlank() && it != "0" }
            val orderId = dto.orderId.takeIf { it.isNotBlank() && it != "0" }
            when {
                orderNo != null -> orderNumbers.add(orderNo to dto.consumeDate)
                orderId != null -> scopedIds.add(
                    listOf(bill.sourceProjectId, bill.sourceAccountId, orderId, dto.consumeDate)
                )
                else -> true
            }
        }.map { bill -> BillDateParser.toEpochMillis(bill.consumeBillDTO.consumeDate) to bill }
            .sortedByDescending { it.first }
            .map { it.second }
    }

    fun encode(bills: List<BillItem>): String = gson.toJson(bills, type)

    fun decode(json: String): List<BillItem> = runCatching {
        gson.fromJson<List<BillItem>>(json, type) ?: emptyList()
    }.getOrDefault(emptyList())
}
