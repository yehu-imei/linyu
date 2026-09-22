package com.hualala.linyu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyedTaskRegistryTest {
    @Test
    fun `同一 key 重复注册只启动一次`() {
        val registry = KeyedTaskRegistry<Any>()
        var starts = 0

        val first = registry.getOrStart("device-a") { starts += 1; Any() }
        val second = registry.getOrStart("device-a") { starts += 1; Any() }

        assertSame(first, second)
        assertEquals(1, starts)
        assertEquals(1, registry.size())
    }

    @Test
    fun `不同 key 可以同时存在且移除一个不影响另一个`() {
        val registry = KeyedTaskRegistry<Any>()
        val deviceA = registry.getOrStart("device-a", ::Any)
        val deviceB = registry.getOrStart("device-b", ::Any)

        assertSame(deviceA, registry.remove("device-a"))
        assertEquals(1, registry.size())
        assertFalse(registry.isEmpty())
        assertSame(deviceB, registry.getOrStart("device-b", ::Any))
    }

    @Test
    fun `旧任务不能误删同 key 的新任务`() {
        val registry = KeyedTaskRegistry<Any>()
        val oldTask = registry.getOrStart("device-a", ::Any)
        registry.remove("device-a")
        val newTask = registry.getOrStart("device-a", ::Any)

        assertFalse(registry.remove("device-a", oldTask))
        assertTrue(registry.remove("device-a", newTask))
        assertTrue(registry.isEmpty())
    }
}
