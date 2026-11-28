package com.hualala.linyu.data

import android.content.SharedPreferences
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.utils.PrefsHelper
import java.io.File
import java.lang.reflect.Proxy
import java.time.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/** 运行真实 PrefsHelper，验证额度/消费确实按隔离键持久化，不只验证展示策略。 */
class ParityPrefsTest {
    private val values = mutableMapOf<String, Any?>()
    private val field = PrefsHelper.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
    private var previous: Any? = null

    @Before fun setUp() {
        previous = field.get(PrefsHelper)
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { values[args!![0] as String] = args[1]; proxy }
                method.name == "remove" -> { values.remove(args!![0]); proxy }
                method.name == "apply" -> null
                method.name == "commit" -> true
                else -> error("Unexpected editor call: ${method.name}")
            }
        }
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when {
                method.name == "edit" -> editor
                method.name == "getAll" -> values.toMap()
                method.name == "contains" -> values.containsKey(args!![0])
                method.name.startsWith("get") -> values[args!![0]] ?: args[1]
                else -> error("Unexpected prefs call: ${method.name}")
            }
        }
        field.set(PrefsHelper, prefs)
    }

    @After fun tearDown() { field.set(PrefsHelper, previous) }

    @Test fun `manual balance survives its own sign in and cannot be read or changed by another account`() {
        val original = NetworkModule.session()
        try {
            values["manualBalance"] = "99"
            values["manualBalanceTime"] = 1L
            NetworkModule.updateAuth("session-a", "1", "3", "2", "")
            val a = NetworkModule.session()
            assertNull(PrefsHelper.manualBalance())
            assertTrue(PrefsHelper.saveManualBalance(a, "20.10"))
            val record = PrefsHelper.manualBalance()
            assertEquals("20.10", record?.amount)
            assertFalse(values.containsKey("manualBalance"))

            PrefsHelper.clear()
            assertNull(PrefsHelper.manualBalance())
            NetworkModule.updateAuth("session-b", "9", "3", "2", "")
            assertNull(PrefsHelper.manualBalance())
            assertFalse(PrefsHelper.saveManualBalance(a, "999"))
            val b = NetworkModule.session()
            assertTrue(PrefsHelper.saveManualBalance(b, "0"))
            assertEquals("0", PrefsHelper.manualBalance()?.amount)

            NetworkModule.updateAuth("session-a-new", "1", "3", "2", "")
            assertEquals(record, PrefsHelper.manualBalance())
            assertFalse(PrefsHelper.saveManualBalance(a, "777"))
        } finally {
            NetworkModule.updateAuth(original.loginCode, original.userId, original.accountId, original.projectId, original.telephone)
        }
    }

    @Test fun `runtime log availability cache follows plugin install and removal`() {
        val catalog = PluginRepository.parseCatalog(File("src/main/assets/linyu_bundled_plugins.json").readBytes())
        val catalogField = PluginRepository.javaClass.getDeclaredField("bundledCatalog").apply { isAccessible = true }
        val previousCatalog = catalogField.get(PluginRepository)
        catalogField.set(PluginRepository, catalog)
        try {
            val runtimeLog = catalog.single { it.manifest.id == InstalledPlugins.RUNTIME_LOG }.manifest
            assertTrue(InstalledPlugins.canRecordRuntimeLogs())
            assertTrue(InstalledPlugins.uninstall(runtimeLog.id))
            assertFalse(InstalledPlugins.canRecordRuntimeLogs())
            assertTrue(InstalledPlugins.install(runtimeLog))
            assertTrue(InstalledPlugins.canRecordRuntimeLogs())
        } finally {
            catalogField.set(PluginRepository, previousCatalog)
        }
    }

    @Test fun `keep screen on defaults to disabled and reads the persisted choice`() {
        assertFalse(PrefsHelper.keepScreenOnDuringUse)
        PrefsHelper.keepScreenOnDuringUse = true
        assertEquals(true, values["keepScreenOnDuringUse"])
        assertTrue(PrefsHelper.keepScreenOnDuringUse)
    }

    @Test fun `restoring legacy notifications only lifts old app level restrictions`() {
        assertFalse(PrefsHelper.hasLegacyNotificationRestrictions)
        PrefsHelper.notifyEnabled = false
        PrefsHelper.notifyInUse = false
        PrefsHelper.notifyFinished = false
        PrefsHelper.notifyAutoClose = false
        PrefsHelper.notifyAlert = false
        assertTrue(PrefsHelper.hasLegacyNotificationRestrictions)

        PrefsHelper.restoreLegacyNotificationOptions()

        assertFalse(PrefsHelper.hasLegacyNotificationRestrictions)
        assertTrue(PrefsHelper.notifyInUse)
        assertTrue(PrefsHelper.notifyFinished)
        assertTrue(PrefsHelper.notifyAutoClose)
        assertTrue(PrefsHelper.notifyAlert)
        assertFalse(PrefsHelper.notifyEnabled)
        assertEquals(setOf("notifyEnabled"), values.keys)
    }

    @Test fun `bundled plugin can stay removed and reinstall from its local manifest`() {
        val catalog = PluginRepository.parseCatalog(File("src/main/assets/linyu_bundled_plugins.json").readBytes())
        val catalogField = PluginRepository.javaClass.getDeclaredField("bundledCatalog").apply { isAccessible = true }
        val previousCatalog = catalogField.get(PluginRepository)
        catalogField.set(PluginRepository, catalog)
        try {
            val nearby = catalog.single { it.manifest.id == InstalledPlugins.NEARBY }.manifest
            assertEquals(5, InstalledPlugins.all.size)
            assertFalse(InstalledPlugins.isInstalled(InstalledPlugins.ROOM_BINDING))
            assertTrue(InstalledPlugins.uninstall(nearby.id))
            assertFalse(InstalledPlugins.isInstalled(nearby.id))
            PrefsHelper.clear()
            assertFalse(InstalledPlugins.isInstalled(nearby.id))
            assertTrue(InstalledPlugins.install(nearby))
            assertTrue(InstalledPlugins.isInstalled(nearby.id))
            assertFalse(InstalledPlugins.install(nearby))
        } finally {
            catalogField.set(PluginRepository, previousCatalog)
        }
    }

    @Test fun `updating room binding from the preview preserves the selected room`() {
        val preview = PluginRepository.legacyRoomBinding().copy(version = "1.1.0")
        assertTrue(InstalledPlugins.install(preview))
        RoomBindingPlugin.setRoom("已有寝室")
        val first = preview.copy(version = "1.0.0", versionEpoch = 1)
        assertTrue(InstalledPlugins.install(first))
        assertEquals(first, RoomBindingPlugin.installed)
        assertEquals("已有寝室", RoomBindingPlugin.effectiveRoom)
    }

    @Test fun `clearing the background plugin removes both layouts and keeps other preferences`() {
        PrefsHelper.backgroundScope = "shower"
        PrefsHelper.keepScreenOnDuringUse = true
        for (scope in listOf("home", "shower")) {
            PrefsHelper.bgPutBool(scope, "enabled", true)
            PrefsHelper.bgPutFloat(scope, "opacity", 0.5f)
            PrefsHelper.bgPutFloat(scope, "blur", 10f)
            PrefsHelper.bgPutFloat(scope, "brightness", 0.7f)
            PrefsHelper.bgPutInt(scope, "themeColor", 123)
        }

        PrefsHelper.clearBackgroundOptions()

        assertEquals("home", PrefsHelper.backgroundScope)
        assertEquals(setOf("keepScreenOnDuringUse"), values.keys)
    }

    @Test fun `quota is isolated by account and project and survives same account relogin`() {
        PrefsHelper.accountId = "account-a"
        PrefsHelper.projectId = "school-a"
        val owner = PrefsHelper.useCodeOwner()
        val quota = UseCodeQuota(0, LocalDate.now().toString())
        PrefsHelper.saveUseCodeQuota(owner, quota)
        PrefsHelper.accountId = "account-b"
        assertNull(PrefsHelper.useCodeQuota())
        PrefsHelper.accountId = "account-a"
        PrefsHelper.projectId = "school-b"
        assertNull(PrefsHelper.useCodeQuota())
        PrefsHelper.clear()
        PrefsHelper.accountId = "account-a"
        PrefsHelper.projectId = "school-a"
        assertEquals(quota, PrefsHelper.useCodeQuota())
    }

    @Test fun `old bills never replace a fresh device settlement and devices remain independent`() {
        PrefsHelper.recordConsume("device-a", 1.25, 200)
        PrefsHelper.recordConsume("device-a", 9.0, 100)
        PrefsHelper.recordConsume("device-a", 7.0, 200)
        PrefsHelper.recordConsume("device-b", 2.0, 150)
        assertEquals(1.25f, PrefsHelper.lastConsumeFor("device-a"), 0.001f)
        assertEquals(2.0f, PrefsHelper.lastConsumeFor("device-b"), 0.001f)
        assertEquals(200, PrefsHelper.lastConsumeTimeFor("device-a"))
        assertEquals(200, PrefsHelper.lastConsumeTime)
    }

    @Test fun `legacy amount is preserved and acquires timestamp only from valid consumption`() {
        values["consume_device-a"] = 1.25f
        PrefsHelper.recordConsume("device-a", Double.NaN, 100)
        PrefsHelper.recordConsume("device-a", 0.0, 100)
        assertEquals(1.25f, PrefsHelper.lastConsumeFor("device-a"), 0.001f)
        assertEquals(0, PrefsHelper.lastConsumeTimeFor("device-a"))
        PrefsHelper.recordConsume("device-a", 2.0, 100)
        assertEquals(100, PrefsHelper.lastConsumeTimeFor("device-a"))
    }

    @Test fun `logout clears device amount and corresponding time together`() {
        PrefsHelper.recordConsume("device-a", 1.0, 100)
        PrefsHelper.clear()
        assertEquals(0f, PrefsHelper.lastConsumeFor("device-a"), 0f)
        assertEquals(0, PrefsHelper.lastConsumeTimeFor("device-a"))
    }
}
