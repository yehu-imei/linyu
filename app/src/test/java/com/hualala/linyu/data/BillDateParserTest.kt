package com.hualala.linyu.data

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillDateParserTest {
    @Test
    fun `parses every known server bill date format`() {
        val expected = LocalDateTime.of(2026, 9, 22, 13, 14, 15, 123_000_000)

        assertEquals(expected.withNano(0), BillDateParser.parseLocalDateTime("2026-09-22 13:14:15"))
        assertEquals(expected.withNano(0), BillDateParser.parseLocalDateTime("2026-09-22T13:14:15"))
        assertEquals(expected, BillDateParser.parseLocalDateTime("2026-09-22 13:14:15.123"))
    }

    @Test
    fun `invalid bill date returns null and zero epoch`() {
        assertNull(BillDateParser.parseLocalDateTime("not-a-date"))
        assertEquals(0L, BillDateParser.toEpochMillis("2026-02-30 12:00:00"))
    }

    @Test
    fun `epoch conversion uses the supplied device zone`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val expected = LocalDateTime.of(2026, 9, 22, 13, 14, 15)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        assertEquals(expected, BillDateParser.toEpochMillis("2026-09-22 13:14:15", zone))
    }
}
