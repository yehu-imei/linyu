package com.hualala.linyu.data

import android.content.SharedPreferences
import com.hualala.linyu.utils.PrefsHelper
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
