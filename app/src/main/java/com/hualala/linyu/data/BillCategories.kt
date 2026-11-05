package com.hualala.linyu.data

import com.hualala.linyu.model.BillItem

enum class BillCategory(val label: String) {
    BATH("洗澡"),
    WASHER("洗衣机")
}

fun BillItem.billCategory(): BillCategory =
    if (consumeBillDTO.isWasher) {
        BillCategory.WASHER
    } else {
        BillCategory.BATH
    }

fun List<BillItem>.forBillCategory(category: BillCategory): List<BillItem> =
    filter { it.billCategory() == category }

fun List<BillItem>.billPage(category: BillCategory, limit: Int = 20): List<BillItem> =
    forBillCategory(category).take(limit)
