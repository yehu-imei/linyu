package com.hualala.linyu.ui

import com.hualala.linyu.data.PluginManifest

internal fun restorePluginOrder(
    installed: List<PluginManifest>,
    savedOrder: List<String>
): List<PluginManifest> {
    val byId = installed.associateBy { it.id }
    return savedOrder.distinct().mapNotNull(byId::get) + installed.filterNot { it.id in savedOrder }
}

internal fun movePlugin(order: List<String>, id: String, targetIndex: Int): List<String> {
    val sourceIndex = order.indexOf(id)
    if (sourceIndex < 0 || targetIndex !in order.indices || targetIndex == sourceIndex) return order
    return order.toMutableList().apply { add(targetIndex, removeAt(sourceIndex)) }
}
