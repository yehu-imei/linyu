package com.hualala.linyu.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesLoaderTest {
    private val document = "<!-- linyu-release-notes:1 -->\n## v3.1.0 | 2026-09-30\n- 精简日志"
    private fun ok(text: String = document) = ReleaseNotesResponse(200, text.toByteArray())

    @Test fun `valid raw response avoids fallback`() = runBlocking {
        val urls = mutableListOf<String>()
        assertEquals(document, UserReleaseNotesLoader.load { urls += it; ok() })
        assertEquals(listOf(UserReleaseNotes.REMOTE_URL), urls)
    }

    @Test fun `raw HTTP failure falls back to repository contents API`() = runBlocking {
        val urls = mutableListOf<String>()
        assertEquals(document, UserReleaseNotesLoader.load {
            urls += it
            if (urls.size == 1) ReleaseNotesResponse(503, byteArrayOf()) else ok()
        })
        assertEquals(UserReleaseNotesLoader.FALLBACK_URL, urls.last())
    }

    @Test fun `network failure malformed content oversized and invalid UTF8 try fallback`() = runBlocking {
        val badResponses = listOf(ok("<html>error</html>"), ok("x".repeat(131_073)),
            ReleaseNotesResponse(200, byteArrayOf(0xFF.toByte())))
        for (bad in badResponses) {
            var count = 0
            assertEquals(document, UserReleaseNotesLoader.load { if (++count == 1) bad else ok() })
        }
        var count = 0
        assertEquals(document, UserReleaseNotesLoader.load {
            if (++count == 1) throw java.io.IOException("offline") else ok()
        })
    }

    @Test fun `both sources fail leaves local cache usable`() = runBlocking {
        assertNull(UserReleaseNotesLoader.load { ReleaseNotesResponse(404, byteArrayOf()) })
        val bundled = UserReleaseNotes.parse(document)
        assertEquals(bundled, UserReleaseNotes.local("<html>bad</html>", bundled))
    }

    @Test fun `new full document removes deleted versions instead of merging old bundle`() {
        val old = UserReleaseNotes.parse(document + "\n## v3.0.4 | 2026-09-01\n- 已删除版本")
        assertEquals(2, old.size)
        assertEquals(UserReleaseNotes.parse(document), UserReleaseNotes.local(document, old))
    }

    @Test fun `cancellation does not retry another endpoint`() = runBlocking {
        var count = 0
        try {
            UserReleaseNotesLoader.load { count++; throw CancellationException() }
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(1, count) }
    }

    @Test fun `automatic lifecycle calls coalesce but manual force bypasses cooldown`() {
        val policy = ReleaseNotesRefreshPolicy()
        assertTrue(policy.shouldRefresh(0, false))
        policy.finish(0, true)
        assertFalse(policy.shouldRefresh(14_999, false))
        assertTrue(policy.shouldRefresh(15_000, false))
        assertTrue(policy.shouldRefresh(1, true))
        policy.finish(15_000, false)
        assertFalse(policy.shouldRefresh(19_999, false))
        assertTrue(policy.shouldRefresh(20_000, false))
    }
}
