package com.hualala.linyu.data

import com.hualala.linyu.api.ProjectAuthContext
import com.hualala.linyu.model.BillDTO
import com.hualala.linyu.model.BillItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class BillHistoryCacheTest {
    private val defaultAccount = ProjectAuthContext("session", "user", "11", "3717", "tel")
    private val drinkingAccount = defaultAccount.copy(projectId = "3745", accountId = "22")

    @Test
    fun `cache key is isolated by account`() {
        assertEquals("billCache_user-a", BillHistoryCache.keyFor("user-a"))
        assertEquals("billCache_user-b", BillHistoryCache.keyFor("user-b"))
    }

    @Test
    fun `round trips bill history`() {
        val bills = listOf(
            BillItem(BillDTO("1", "2026-09-22 10:00:00", "1.20", "热水器", "order-1"))
        )

        val decoded = BillHistoryCache.decode(BillHistoryCache.encode(bills))

        assertEquals(bills, decoded)
        assertTrue(BillHistoryCache.decode("not-json").isEmpty())
    }

    @Test
    fun `monthly cache is scoped by user project and account`() {
        val month = YearMonth.of(2026, 11)
        val key = BillHistoryCache.monthAccountKey(defaultAccount.owner, drinkingAccount, month)
        assertNotEquals(key, BillHistoryCache.monthAccountKey(defaultAccount.owner, defaultAccount, month))
        assertNotEquals(key, BillHistoryCache.monthAccountKey(defaultAccount.owner, drinkingAccount.copy(accountId = "23"), month))
        assertNotEquals(key, BillHistoryCache.monthAccountKey("other-user", drinkingAccount, month))
        assertNotEquals(key, BillHistoryCache.monthAccountKey(defaultAccount.owner, drinkingAccount, month.minusMonths(1)))
    }

    @Test
    fun `merged drinking record remains associated with its account`() {
        val shower = BillItem(BillDTO("123", "2026-11-10 09:00:00", "1.20", "热水表"))
        val drink = BillItem(BillDTO("123", "2026-11-10 09:00:00", "0.12", "2-3-1", deviceTypeName = "温水"))
        val newer = drink.copy(consumeBillDTO = drink.consumeBillDTO.copy(consumeDate = "2026-11-11 09:00:00"))
        val defaultBills = BillHistoryCache.fromProject(listOf(shower), defaultAccount)
        val drinkingBills = BillHistoryCache.fromProject(listOf(drink, newer, drink), drinkingAccount)
        val merged = BillHistoryCache.newestFirst(defaultBills + drinkingBills)

        assertEquals(3, merged.size)
        assertEquals(newer.consumeBillDTO, merged.first().consumeBillDTO)
        assertTrue(merged.first().consumeBillDTO.isDrinkingWater)
        assertEquals(defaultBills, merged.filter { it.belongsTo(defaultAccount.projectId, defaultAccount.accountId) })
        assertEquals(merged, BillHistoryCache.decode(BillHistoryCache.encode(merged)))
    }

    @Test
    fun `duplicate order number returned by two projects appears once`() {
        val order = BillItem(BillDTO("123", "2026-11-10 09:00:00", "1.20", "热水表", "13200000000000000002"))
        val defaultBills = BillHistoryCache.fromProject(listOf(order), defaultAccount)
        val drinkingBills = BillHistoryCache.fromProject(listOf(order), drinkingAccount)
        assertEquals(defaultBills, BillHistoryCache.newestFirst(defaultBills + drinkingBills))
    }

    @Test
    fun `ignore non-consumption rows and preserve the fallback device description`() {
        val raw = BillHistoryCache.decode("""[
            {"rechargeBillDTO":{"consumeDate":"2026-11-10 09:00:00"}},
            {"consumeBillDTO":{"dealDate":"2026-11-10 09:00:00","consumeMoney":"0.00","thirdTradeMoney":"0.125",
                               "deviceDescription":"开水-2-3-1","orderNo":"order-1"}}
        ]""")
        val rows = BillHistoryCache.fromProject(raw, drinkingAccount)
        assertEquals(listOf("开水-2-3-1"), rows.map { it.consumeBillDTO.description })
        assertEquals("order-1", rows.single().consumeBillDTO.orderId)
        assertEquals("0.125", rows.single().consumeBillDTO.consumeMoney)
        assertEquals("2026-11-10 09:00:00", rows.single().consumeBillDTO.consumeDate)
        assertTrue(rows.single().consumeBillDTO.isDrinkingWater)
    }
}
