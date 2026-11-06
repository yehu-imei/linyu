package com.hualala.linyu.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UserReleaseNotesTest {
    private fun document(): String = listOf(File("../docs/APP_CHANGELOG.md"), File("docs/APP_CHANGELOG.md"))
        .first { it.isFile }.readText(Charsets.UTF_8)

    @Test fun allPublishedVersionsAreBrief() {
        val notes = UserReleaseNotes.parse(document())
        assertEquals(15, notes.size)
        assertEquals("v3.1.1", notes.first().version)
        assertEquals("v1.0", notes.last().version)
        assertFalse(notes.any { it.date == "待发布" })
        assertTrue(notes.all { it.items.size in 1..5 && it.items.all { text -> text.length <= 50 } })
    }
    @Test fun platformTagsAreExplicit() {
        val android = UserReleaseNotes.parse(document()).flatMap { it.items }
        val ios = UserReleaseNotes.parse(document(), "iOS").flatMap { it.items }
        assertTrue(android.any { it.contains("后台用水监控") })
        assertFalse(ios.any { it.contains("后台用水监控") || it.contains("单格开关") })
        assertTrue(ios.any { it.contains("通知、扫码权限") })
        assertFalse(android.any { it.contains("通知、扫码权限") })
    }
    @Test fun noSemanticKeywordFilter() {
        val source = "<!-- linyu-release-notes:1 -->\n## v3.2.0 | 2026-11-05\n- 改善体验\n- 兼容新设备\n- [Android] 精简提示\n## v3.3.0 | 待发布\n- 未发布"
        assertEquals(3, UserReleaseNotes.parse(source).single().items.size)
    }
    @Test fun displayRemovesOnlyTrailingSentenceStops() {
        val legacy = UserReleaseNote("v3.1.0", "2026-09-30", listOf("支持 Android 8.0。 ", "消费 0.025 元。", "区分“结算中”。金额准确。"))
        assertEquals("• 支持 Android 8.0\n• 消费 0.025 元\n• 区分“结算中”。金额准确", legacy.text)
        assertTrue(UserReleaseNotes.parse(document()).flatMap { it.items }.none { it.endsWith('。') })
    }
    @Test fun badOrPartialRemoteDoesNotEraseHistory() {
        val notes = UserReleaseNotes.parse(document())
        assertTrue(UserReleaseNotes.parse("<html>error</html>").isEmpty())
        assertEquals(notes, UserReleaseNotes.merge(emptyList(), notes))
        assertEquals(notes, UserReleaseNotes.parse(document().replace("\n", "\r\n")))
        val updated = notes.first().copy(items = listOf("修订文案"))
        assertEquals(notes.size, UserReleaseNotes.merge(listOf(updated), notes).size)
        assertEquals(updated, UserReleaseNotes.merge(listOf(updated), notes).first())
    }
}
