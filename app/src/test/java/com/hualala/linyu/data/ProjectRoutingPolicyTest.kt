package com.hualala.linyu.data

import com.google.gson.Gson
import com.hualala.linyu.api.ProjectAuthContext
import com.hualala.linyu.api.ProjectAuthException
import com.hualala.linyu.model.ActiveOrder
import com.hualala.linyu.model.DeviceInfo
import org.junit.Assert.*
import org.junit.Test

class ProjectRoutingPolicyTest {
    private val session = ProjectAuthContext("test", "101", "201", "3717", "test-phone")
    private val target = session.copy(projectId = "3745", accountId = "202")
    private val device = DeviceInfo(22, "饮水机", "S1", "AABBCCDDEEFF", 5.0, 1, bigTypeId = 5, projectId = 3745)
    private val active = ActiveOrder("S1", "O1", "饮水机", "AABBCCDDEEFF", "🚰", 5.0, "3745", "202", session.owner)

    @Test fun `cold start restores projects devices and owner without storing tokens`() {
        val saved = ProjectRoutingSnapshot(session.owner, mapOf("3745" to "202"), mapOf("S1" to device))
        val json = Gson().toJson(saved)
        assertFalse(json.contains("loginCode"))
        val restored = Gson().fromJson(json, ProjectRoutingSnapshot::class.java)
        assertEquals(saved, restored)
        assertTrue(restored.belongsTo(session.copy(loginCode = "new-token")))
        assertFalse(restored.belongsTo(session.copy(userId = "102")))
        assertFalse(restored.belongsTo(session.copy(accountId = "999")))
        assertEquals("3745", deviceProject(session, restored.devices["S1"], null))
        assertEquals("202", session.forProject("3745", restored.accounts).accountId)
    }

    @Test fun `order survives serialization and missing device cache`() {
        val order = Gson().fromJson(Gson().toJson(active), ActiveOrder::class.java)
        assertEquals("3745", deviceProject(session, null, order))
        assertThrows(ProjectAuthException::class.java) { deviceProject(session.copy(userId = "102"), null, order) }
    }

    @Test fun `legacy missing device project is never guessed for drinking water`() {
        val legacy = active.copy(projectId = null, accountId = null, authOwner = null)
        assertThrows(ProjectAuthException::class.java) { deviceProject(session, null, legacy) }
        assertThrows(ProjectAuthException::class.java) { deviceProject(session, device.copy(projectId = null), null) }
        assertEquals("3745", deviceProject(session, device, legacy))
    }

    @Test fun `a running order is not silently moved to a new device project`() {
        assertThrows(ProjectAuthException::class.java) { deviceProject(session, device.copy(projectId = 9999), active) }
    }

    @Test fun `old shower metadata can continue using default project`() {
        val shower = device.copy(deviceName = "热水表-测试楼", bigTypeId = 4, smallTypeId = 1, projectId = null)
        assertEquals("3717", deviceProject(session, shower, null))
        assertFalse(shower.needsBluetoothControl)
    }

    @Test fun `pending settlement cannot match a bill from another project account or user`() {
        val pending = PendingSettlement("S1", "O1", 100, "饮水机", 200, projectId = "3745", accountId = "202", authOwner = session.owner)
        val restored = Gson().fromJson(Gson().toJson(pending), PendingSettlement::class.java)
        assertTrue(restored.matchesContext(session, target))
        assertFalse(restored.matchesContext(session, session))
        assertFalse(restored.matchesContext(session, target.copy(accountId = "203")))
        assertFalse(restored.matchesContext(session.copy(userId = "102"), target))
    }
}
