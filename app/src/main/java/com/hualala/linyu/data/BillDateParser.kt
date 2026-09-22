package com.hualala.linyu.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

object BillDateParser {
    private val formatters = listOf(
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withResolverStyle(ResolverStyle.STRICT)
    )

    fun parseLocalDateTime(value: String): LocalDateTime? =
        formatters.firstNotNullOfOrNull { formatter ->
            runCatching { LocalDateTime.parse(value, formatter) }.getOrNull()
        }

    fun toEpochMillis(value: String, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        parseLocalDateTime(value)?.atZone(zoneId)?.toInstant()?.toEpochMilli() ?: 0L
}
