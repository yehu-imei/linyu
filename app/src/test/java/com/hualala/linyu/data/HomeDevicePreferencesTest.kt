package com.hualala.linyu.data

import com.google.gson.Gson
import com.hualala.linyu.model.DeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDevicePreferencesTest {
    private val drinking = DeviceInfo(
        22, "教学楼饮水机", "SN-22", "AABBCCDDEE22", 0.5, 1, bigTypeId = 5, projectId = 3745
    )
    private val bath = DeviceInfo(23, "澡堂", "SN-23", "AABBCCDDEE23", 5.0, 1)

    @Test fun `section order persists while hidden and upgrades old layouts`() {
        val gson = Gson()
        val old = gson.fromJson(
            """{"favorites":[],"hiddenSections":["NEARBY"]}""",
            HomeDeviceLayout::class.java
        )
        assertEquals(HomeDeviceSection.entries.toList(), old.orderedSections)
        assertEquals(old, old.moveSection(HomeDeviceSection.LAST_USED, -1))
        val moved = old.moveSection(HomeDeviceSection.NEARBY, -1)
        val restored = gson.fromJson(gson.toJson(moved), HomeDeviceLayout::class.java)
        assertFalse(restored.isVisible(HomeDeviceSection.NEARBY))
        assertEquals(
            listOf(HomeDeviceSection.LAST_USED, HomeDeviceSection.NEARBY, HomeDeviceSection.FAVORITES),
            restored.orderedSections
        )
        assertEquals(restored.orderedSections, restored.toggleSection(HomeDeviceSection.NEARBY).orderedSections)
    }

    @Test fun `favorite requires device identity but can await project information`() {
        assertEquals("3745:SN-22", FavoriteDevice.from(drinking, "3717")?.key)
        assertEquals("3717:SN-23", FavoriteDevice.from(bath, "3717")?.key)
        val pending = requireNotNull(FavoriteDevice.from(drinking.copy(projectId = null), "3717"))
        assertNull(pending.projectId)
        assertNull(FavoriteDevice.from(bath.copy(snCode = ""), "3717"))
        assertNull(FavoriteDevice.from(bath.copy(macAddress = ""), "3717"))
    }

    @Test fun `missing API identity fields do not crash favorite validation`() {
        val gson = Gson()
        for (missing in listOf("snCode", "macAddress")) {
            val json = gson.toJsonTree(drinking).asJsonObject
            json.remove(missing)
            val partial = gson.fromJson(json, DeviceInfo::class.java)
            assertFalse(partial.hasDeviceIdentity)
            assertNull(FavoriteDevice.from(partial, "3717"))
        }
    }

    @Test fun `pending favorite gets its real project only from matching device metadata`() {
        val pending = requireNotNull(FavoriteDevice.from(drinking.copy(projectId = null), "3717"))
        val resolved = requireNotNull(FavoriteDevice.from(drinking, "3717"))
        val mismatched = requireNotNull(FavoriteDevice.from(drinking.copy(macAddress = "AABBCCDDEE99"), "3717"))
        val implicitDefault = requireNotNull(FavoriteDevice.from(
            drinking.copy(projectId = null, bigTypeId = null, deviceName = "设备 2-3-1"), "3717"
        ))
        val gson = Gson()
        val layout = gson.fromJson(
            gson.toJson(HomeDeviceLayout().toggleFavorite(pending)), HomeDeviceLayout::class.java
        )
        assertEquals(pending, layout.findFavorite(resolved))
        assertNull(layout.findFavorite(mismatched))
        assertEquals(layout, layout.confirmFavorite(pending, mismatched))
        assertEquals(layout, layout.confirmFavorite(pending, implicitDefault))
        assertEquals(listOf(resolved), layout.confirmFavorite(pending, resolved).favorites)
    }

    @Test fun `favorites use project and serial rather than name or mac`() {
        val original = requireNotNull(FavoriteDevice.from(drinking, "3717"))
        val renamed = requireNotNull(
            FavoriteDevice.from(drinking.copy(deviceName = "新名字", macAddress = "AABBCCDDEE24"), "3717")
        )
        val otherProject = requireNotNull(FavoriteDevice.from(drinking.copy(projectId = 3746), "3717"))
        val layout = HomeDeviceLayout().toggleFavorite(original).toggleFavorite(otherProject)
        assertEquals(listOf(original, otherProject), layout.favorites)
        assertEquals(listOf(otherProject), layout.toggleFavorite(renamed).favorites)
    }

    @Test fun `favorite order and independent visibility survive serialization`() {
        val first = requireNotNull(FavoriteDevice.from(drinking, "3717"))
        val second = requireNotNull(FavoriteDevice.from(bath, "3717"))
        val original = HomeDeviceLayout().toggleFavorite(first).toggleFavorite(second)
        assertTrue(HomeDeviceSection.entries.all(original::isVisible))
        assertEquals(original, original.moveFavorite(first.key, -1))
        assertEquals(original, original.moveFavorite(second.key, 1))
        val layout = original.moveFavorite(second.key, -1).toggleSection(HomeDeviceSection.NEARBY)
        val gson = Gson()
        val restored = gson.fromJson(gson.toJson(layout), HomeDeviceLayout::class.java)
        assertEquals(listOf(second, first), restored.favorites)
        assertTrue(restored.isVisible(HomeDeviceSection.FAVORITES))
        assertTrue(restored.isVisible(HomeDeviceSection.LAST_USED))
        assertFalse(restored.isVisible(HomeDeviceSection.NEARBY))
        assertTrue(restored.toggleSection(HomeDeviceSection.NEARBY).isVisible(HomeDeviceSection.NEARBY))
    }
}
