package com.hualala.linyu.data

import android.content.SharedPreferences
import com.google.gson.Gson
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.ProjectSession
import com.hualala.linyu.model.ActiveOrder
import com.hualala.linyu.utils.PrefsHelper
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProjectIsolationTest {
    private val values = mutableMapOf<String, Any?>()
    private val prefsField = PrefsHelper.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
    private var previousPrefs: Any? = null

    @Before
    fun setUp() {
        previousPrefs = prefsField.get(PrefsHelper)
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { values[args!![0] as String] = args[1]; proxy }
                method.name == "remove" -> { values.remove(args!![0]); proxy }
                method.name == "apply" -> null
                method.name == "commit" -> true
                else -> error("Unexpected editor call: ${method.name}")
            }
        }
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when {
                method.name == "edit" -> editor
                method.name == "getAll" -> values.toMap()
                method.name == "contains" -> values.containsKey(args!![0])
                method.name.startsWith("get") -> values[args!![0]] ?: args[1]
                else -> error("Unexpected prefs call: ${method.name}")
            }
        }
        prefsField.set(PrefsHelper, prefs)
        selectProject("project-a")
    }

    @After
    fun tearDown() {
        NetworkModule.updateAuth("", "", "", "", "")
        prefsField.set(PrefsHelper, previousPrefs)
    }

    @Test
    fun `switching projects preserves independent device and washer recovery data`() {
        PrefsHelper.lastDeviceName = "shower-a"
        PrefsHelper.bleSessionJson = "ble-a"
        PrefsHelper.washerSessionJson = "washer-a"
        PrefsHelper.boundRoom = "room-a"
        selectProject("project-b")
        assertEquals("", PrefsHelper.lastDeviceName)
        assertEquals("", PrefsHelper.washerSessionJson)
        assertTrue(PrefsHelper.hasWasherSession())
        PrefsHelper.lastDeviceName = "washer-b"
        PrefsHelper.washerSessionJson = "session-b"
        selectProject("project-a")
        assertEquals("shower-a", PrefsHelper.lastDeviceName)
        assertEquals("ble-a", PrefsHelper.bleSessionJson)
        assertEquals("washer-a", PrefsHelper.washerSessionJson)
        assertEquals("room-a", PrefsHelper.boundRoom)
    }

    @Test
    fun `background monitor keeps old credentials and writes to its own project after suspension`() = runBlocking {
        val oldSession = NetworkModule.session()
        val ready = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val monitor = async(Dispatchers.Default + oldSession.contextElement()) {
            ready.complete(Unit)
            resume.await()
            withContext(Dispatchers.IO) {
                assertEquals("project-a", NetworkModule.currentProjectId())
                assertEquals("account-project-a", NetworkModule.authFields()["accountId"])
                PrefsHelper.washerSessionJson = "updated-a"
                PrefsHelper.walletBalance = "8.00"
            }
        }
        ready.await()
        selectProject("project-b")
        PrefsHelper.washerSessionJson = "session-b"
        PrefsHelper.walletBalance = "12.00"
        resume.complete(Unit)
        monitor.await()
        assertEquals("project-b", NetworkModule.currentProjectId())
        assertEquals("session-b", PrefsHelper.washerSessionJson)
        assertEquals("12.00", PrefsHelper.walletBalance)
        oldSession.run {
            assertEquals("updated-a", PrefsHelper.washerSessionJson)
            assertEquals("8.00", PrefsHelper.walletBalance)
        }
    }

    @Test
    fun `removing a visible project order preserves the other project order`() {
        ActiveOrderRepository.upsert(order("device-a", "project-a"))
        ActiveOrderRepository.upsert(order("device-b", "project-b"))
        selectProject("project-b")
        assertEquals(listOf("device-b"), ActiveOrderRepository.forProject().map { it.snCode })
        val a = ActiveOrderRepository.sessionFor("device-a")
        assertEquals("project-a", a.projectId)
        assertEquals("account-project-a", a.fields["accountId"])
        ActiveOrderRepository.remove("device-b")
        assertEquals(listOf("device-a"), ActiveOrderRepository.all().map { it.snCode })
        assertFalse(canLogoutVoluntarily(ActiveOrderRepository.all()))
    }

    @Test
    fun `legacy active orders and session JSON migrate once to their original project`() {
        values["lastDeviceProjectId"] = "project-a"
        values["lastDeviceName"] = "legacy-shower"
        values["bleSessionJson"] = "legacy-ble"
        values["washerSessionJson"] = "legacy-washer"
        values["activeOrders"] = Gson().toJson(listOf(order("device-a", null)))
        val migrate = PrefsHelper.javaClass.getDeclaredMethod("migrateLegacyProjectSessions")
            .apply { isAccessible = true }
        migrate.invoke(PrefsHelper)
        selectProject("project-b")
        migrate.invoke(PrefsHelper)
        assertEquals("", PrefsHelper.washerSessionJson)
        assertTrue(ActiveOrderRepository.forProject().isEmpty())
        selectProject("project-a")
        assertEquals("legacy-shower", PrefsHelper.lastDeviceName)
        assertEquals("legacy-ble", PrefsHelper.bleSessionJson)
        assertEquals("legacy-washer", PrefsHelper.washerSessionJson)
        assertEquals("project-a", ActiveOrderRepository.all().single().projectId)
    }

    @Test
    fun `queued HTTP request keeps matching project header and GET credentials after switching`() {
        val oldSession = NetworkModule.session()
        selectProject("project-b")
        val method = NetworkModule.javaClass.getDeclaredMethod("authInterceptor", ProjectSession::class.java)
            .apply { isAccessible = true }
        val interceptor = method.invoke(NetworkModule, oldSession) as Interceptor
        val original = Request.Builder().url("https://example.test/account/wallet").build()
        lateinit var sent: Request
        val chain = Proxy.newProxyInstance(
            Interceptor.Chain::class.java.classLoader, arrayOf(Interceptor.Chain::class.java)
        ) { _, call, args ->
            when (call.name) {
                "request" -> original
                "proceed" -> {
                    sent = args!![0] as Request
                    Response.Builder().request(sent).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body("{}".toResponseBody()).build()
                }
                else -> error("Unexpected chain call: ${call.name}")
            }
        } as Interceptor.Chain
        interceptor.intercept(chain).close()
        assertEquals("project-a", sent.header("Config-Project"))
        assertEquals("project-a", sent.url.queryParameter("projectId"))
        assertEquals("account-project-a", sent.url.queryParameter("accountId"))
        assertEquals("login-project-a", sent.url.queryParameter("loginCode"))
    }

    @Test
    fun `logout removes all project credentials and running sessions`() {
        PrefsHelper.washerSessionJson = "session-a"
        selectProject("project-b")
        PrefsHelper.bleSessionJson = "ble-b"
        PrefsHelper.clear()
        assertFalse(PrefsHelper.hasWasherSession())
        assertNull(PrefsHelper.projectAuth("project-a"))
        assertNull(PrefsHelper.projectAuth("project-b"))
        assertFalse(values.keys.any { it.startsWith("bleSessionJson__project_") })
    }

    private fun selectProject(id: String) {
        NetworkModule.updateAuth("login-$id", "user", "account-$id", id, "phone")
        PrefsHelper.projectId = id
    }

    private fun order(sn: String, project: String?) =
        ActiveOrder(sn, "order-$sn", sn, "mac", "device", 1.0, project)
}
