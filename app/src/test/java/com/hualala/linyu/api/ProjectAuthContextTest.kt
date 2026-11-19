package com.hualala.linyu.api

import com.google.gson.Gson
import com.hualala.linyu.model.DeviceInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import java.util.Collections

class ProjectAuthContextTest {
    private val session = ProjectAuthContext("test-token", "101", "201", "3717", "test-phone")
    private val foreign = session.forProject("3745", mapOf("3745" to "202"))

    @Test fun `device json retains its project including string IDs`() {
        val json = """{"deviceId":22,"deviceName":"开水-测试楼","snCode":"S1","macAddress":"AABBCCDDEEFF","withholdMoney":5,"onlineStatusId":1,"bigTypeId":5,"smallTypeId":2,"smallTypeName":"开水","projectId":"3745"}"""
        val device = Gson().fromJson(json, DeviceInfo::class.java)
        assertEquals(3745L, device.projectId)
        assertEquals(device, Gson().fromJson(Gson().toJson(device), DeviceInfo::class.java))
        assertFalse(device.needsBluetoothControl)
    }

    @Test fun `foreign account never falls back to login default`() {
        assertThrows(ProjectAuthException::class.java) { session.forProject("3745", emptyMap()) }
        assertThrows(ProjectAuthException::class.java) { session.forProject("3745", mapOf("3745" to "0")) }
        assertEquals("202", foreign.accountId)
        assertEquals("201", session.accountId)
        assertEquals("3717", session.projectId)
        assertEquals(session, session.forProject("3717", emptyMap()))
    }

    @Test fun `project list rejects other user invalid IDs and ambiguous accounts`() {
        val items = listOf(UserProjectItem(101, 3745, 202), UserProjectItem(101, 3745, 202),
            UserProjectItem(999, 900, 300), UserProjectItem(101, 0, 200),
            UserProjectItem(101, 901, 0), UserProjectItem(101, 902, 400), UserProjectItem(101, 902, 401),
            UserProjectItem(101, 3717, 999))
        assertEquals(mapOf("3745" to "202", "3717" to "201"), projectAccountMap(items, session))
    }

    @Test fun `old request cannot use new session`() {
        val request = Request.Builder().url("https://example.invalid/order/test")
            .post(form(foreign.fields())).build()
        assertThrows(ProjectAuthException::class.java) {
            authenticateProjectRequest(request, session.copy(loginCode = "new-token", userId = "102"))
        }
    }

    @Test fun `incomplete cross project GET is rejected instead of mixing accounts`() {
        assertThrows(ProjectAuthException::class.java) {
            authenticateProjectRequest(Request.Builder().url("https://example.invalid/account/wallet?projectId=3745").build(), session)
        }
    }

    @Test fun `explicit GET fields replace duplicates and header uses target project`() {
        val request = service().getBillList("2026-11", auth = foreign.fields()).request()
        val duplicated = request.newBuilder().url(request.url.newBuilder().addQueryParameter("projectId", "3717").build()).build()
        val result = authenticateProjectRequest(duplicated, session)
        assertEquals("3745", result.header("Config-Project"))
        foreign.fields().forEach { (key, value) -> assertEquals(listOf(value), result.url.queryParameterValues(key)) }
        assertEquals("2026-11", result.url.queryParameter("month"))
    }

    @Test fun `default GET keeps original default account and verification phone`() {
        val wallet = authenticateProjectRequest(service().getWallet().request(), session)
        assertEquals("201", wallet.url.queryParameter("accountId"))
        assertEquals("3717", wallet.header("Config-Project"))
        val campus = authenticateProjectRequest(service().getCampusUserInfo().request(), session)
        assertEquals(wallet.url.queryParameter("accountId"), campus.url.queryParameter("accountId"))
        assertEquals(session.telephone, campus.url.queryParameter("telephone"))
        assertEquals(session.telephone, campus.url.queryParameter("telPhone"))
        val sms = authenticateProjectRequest(service().getVerificationCode("new-phone", "secret").request(), session)
        assertEquals("new-phone", sms.url.queryParameter("telephone"))
        assertNull(sms.url.queryParameter("loginCode"))
    }

    @Test fun `all cloud and BLE request forms agree with config project`() {
        val api = service()
        val auth = foreign.fields()
        val calls = listOf(api.queryUsing(snCode = "S1", auth = auth), api.downRate(snCode = "S1", auth = auth),
            api.downRateResult("S1", auth), api.closeOrder("S1", "O1", auth),
            api.closeOrderResult("S1", "O1", auth), api.consumeOrderResult("S1", "O1", auth),
            api.bluetoothRateOrder("22", "AABBCCDDEEFF", "0000", "5", "2", "1", "abcd", "0", "test", auth),
            api.bluetoothUploadData("1", "abcd", "80", "test", auth), api.bluetoothFailOrder("20261111", "test", auth))
        calls.forEach { call ->
            val request = authenticateProjectRequest(call.request(), session)
            assertEquals("3745", request.header("Config-Project"))
            assertEquals(1, request.headers.values("Config-Project").size)
            val body = request.body as FormBody
            val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
            assertEquals("3745", fields["projectId"])
            assertEquals("202", fields["accountId"])
        }
    }

    @Test fun `production safe API parses project list and concurrent requests stay isolated`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(authenticateProjectRequest(chain.request(), session))
        }.addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val data = if (request.url.encodedPath == "/user/project/list")
                """[{"userId":101,"projectId":3717,"accountId":201},{"userId":101,"projectId":3745,"accountId":202}]"""
                else "null"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"success":true,"data":$data}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val api = service(client)
            val projects = api.getUserProjectsSafe(session.fields())
            assertTrue(projects.success)
            val mapped = projectAccountMap(projects.data!!, session)
            val target = session.forProject("3745", mapped)
            (0 until 12).map { i -> async {
                val context = if (i % 2 == 0) session else target
                api.queryUsingSafe(snCode = context.projectId, auth = context.fields())
            } }.awaitAll()
            requests.filter { it.method == "POST" }.forEach {
                val body = it.body as FormBody
                val fields = (0 until body.size).associate { index -> body.name(index) to body.value(index) }
                assertEquals(fields["snCode"], fields["projectId"])
                assertEquals(fields["projectId"], it.header("Config-Project"))
                assertEquals(if (fields["projectId"] == "3717") "201" else "202", fields["accountId"])
            }
            assertEquals(13, requests.size)
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun `foreign history uses all bill types but only parses device consumption`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = authenticateProjectRequest(chain.request(), session)
            requests += request
            val data = """{"success":true,"data":[
                {"consumeBillDTO":{"orderId":"1","deviceTypeName":"饮水机","consumeMoney":"0.15"}},
                {"rechargeBillDTO":{"orderId":"2"}},
                {"consumeBillDTO":{"orderId":"3"},"refundBillDTO":{"orderId":"3"}},
                {"monthCardBillDTO":{"orderId":"4"}},
                {"laundryBillDTO":{"orderId":"5"}}
            ]}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(data.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val api = service(client)
            api.getProjectBillListSafe("2026-11", session, session)
            val bills = api.getProjectBillListSafe("2026-11", session, foreign)
            assertEquals(listOf("2", "0"), requests.map { it.url.queryParameter("billRequestType") })
            assertEquals(listOf("201", "202"), requests.map { it.url.queryParameter("accountId") })
            assertEquals(listOf("3717", "3745"), requests.map { it.header("Config-Project") })
            val bill = bills.data!!.single().consumeBillDTO
            assertEquals("1", bill.orderId)
            assertEquals("饮水机", bill.deviceTypeName)
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    private fun form(fields: Map<String, String>) = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
    private fun service(client: OkHttpClient = OkHttpClient()): QzxyService = Retrofit.Builder()
        .baseUrl("https://example.invalid").client(client).build().create(QzxyService::class.java)
}
