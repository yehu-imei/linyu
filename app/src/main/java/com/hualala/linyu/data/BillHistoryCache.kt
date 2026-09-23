package com.hualala.linyu.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hualala.linyu.model.BillItem

/** JSON codec for the account-scoped bill snapshot shown while refresh is running. */
object BillHistoryCache {
    private val gson = Gson()
    private val type = object : TypeToken<List<BillItem>>() {}.type

    fun keyFor(accountKey: String): String = "billCache_$accountKey"

    fun encode(bills: List<BillItem>): String = gson.toJson(bills, type)

    fun decode(json: String): List<BillItem> = runCatching {
        gson.fromJson<List<BillItem>>(json, type) ?: emptyList()
    }.getOrDefault(emptyList())
}
