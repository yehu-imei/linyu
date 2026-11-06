package com.hualala.linyu.data

data class UserReleaseNote(val version: String, val date: String, val items: List<String>) {
    // 兼容旧缓存；版本号/金额中的小数点保持不变。
    val text: String get() = items.joinToString("\n") { "• ${it.trim().trimEnd('。')}" }
}

/** 与 iOS 共用 APP_CHANGELOG.md 格式；无语义关键词过滤。 */
object UserReleaseNotes {
    const val REMOTE_URL = "https://raw.githubusercontent.com/yehu-imei/linyu/master/docs/APP_CHANGELOG.md"
    fun local(document: String, bundled: List<UserReleaseNote>): List<UserReleaseNote> =
        parse(document).ifEmpty { bundled }

    private val versionPattern = Regex("^v[0-9]+(?:\\.[0-9]+){1,2}$")
    private val datePattern = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val descending = Comparator<UserReleaseNote> { a, b ->
        val left = a.version.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.version.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        (0 until maxOf(left.size, right.size)).firstNotNullOfOrNull { i ->
            (right.getOrElse(i) { 0 }.compareTo(left.getOrElse(i) { 0 })).takeIf { it != 0 }
        } ?: 0
    }

    fun parse(source: String, platform: String = "Android"): List<UserReleaseNote> {
        if (source.toByteArray(Charsets.UTF_8).size > 131_072 || !source.contains("<!-- linyu-release-notes:1 -->")) return emptyList()
        val result = mutableListOf<UserReleaseNote>()
        var version: String? = null
        var date = ""
        val items = mutableListOf<String>()
        fun flush() {
            version?.let { v -> if (items.isNotEmpty() && result.none { it.version == v }) result += UserReleaseNote(v, date, items.toList()) }
            version = null
            items.clear()
        }
        source.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("## ")) {
                flush()
                val fields = line.drop(3).split('|').map(String::trim)
                if (fields.size == 2 && versionPattern.matches(fields[0]) && datePattern.matches(fields[1])) {
                    version = fields[0]; date = fields[1]
                }
            } else if (version != null && line.startsWith("- ")) {
                var item = line.drop(2).trim()
                val tag = listOf("Android", "iOS").firstOrNull { item.startsWith("[$it] ") }
                if (tag != null) item = if (tag == platform) item.drop(tag.length + 3) else ""
                if (item.isNotEmpty()) items += item
            }
        }
        flush()
        return result.sortedWith(descending)
    }

    fun merge(preferred: List<UserReleaseNote>, fallback: List<UserReleaseNote>): List<UserReleaseNote> =
        (preferred + fallback).distinctBy { it.version }.sortedWith(descending)
}
