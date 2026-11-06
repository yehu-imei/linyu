package com.hualala.linyu.data

import kotlinx.coroutines.CancellationException

/** 合并重复生命周期刷新，不把“已经检查过版本”当作日志永不再刷新的条件。 */
class ReleaseNotesRefreshPolicy {
    private var lastAttempt: Long? = null
    private var succeeded = false

    fun shouldRefresh(nowMs: Long, force: Boolean): Boolean {
        val last = lastAttempt ?: return true
        return force || nowMs < last || nowMs - last >= (if (succeeded) 15_000 else 5_000)
    }

    fun finish(nowMs: Long, succeeded: Boolean) {
        lastAttempt = nowMs
        this.succeeded = succeeded
    }
}

data class ReleaseNotesResponse(val status: Int, val bytes: ByteArray)

object UserReleaseNotesLoader {
    const val MAX_BYTES = 131_072
    const val FALLBACK_URL = "https://api.github.com/repos/yehu-imei/linyu/contents/docs/APP_CHANGELOG.md?ref=master"

    suspend fun load(fetch: suspend (String) -> ReleaseNotesResponse): String? {
        for (url in listOf(UserReleaseNotes.REMOTE_URL, FALLBACK_URL)) {
            try {
                val response = fetch(url)
                if (response.status !in 200..299 || response.bytes.size > MAX_BYTES) continue
                val document = response.bytes.decodeToString(throwOnInvalidSequence = true)
                if (UserReleaseNotes.parse(document).isNotEmpty()) return document
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 两个地址均为官方 GitHub 服务，不引入第三方镜像。
            }
        }
        return null
    }
}
