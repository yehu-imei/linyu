package com.hualala.linyu.ui

import com.hualala.linyu.data.PluginManifest
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginOrderTest {
    @Test
    fun preservesSavedOrderAndAppendsNewPlugins() {
        val first = plugin("first")
        val second = plugin("second")
        val added = plugin("added")
        assertEquals(
            listOf(second, first, added),
            restorePluginOrder(listOf(first, second, added), listOf("second", "removed", "second", "first"))
        )
    }

    @Test
    fun movesByIdentityAndRejectsAnOutdatedPosition() {
        val original = listOf("first", "second", "third")
        val reordered = movePlugin(original, "first", 2)
        assertEquals(listOf("second", "third", "first"), reordered)
        assertEquals(reordered, movePlugin(reordered, "third", -1))
        assertEquals(reordered, movePlugin(reordered, "removed", 1))
    }

    private fun plugin(id: String) = PluginManifest(id, id, "LinYu", "1.0.0", "说明")
}
