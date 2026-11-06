package com.hualala.linyu.data

import android.content.Context
import android.os.SystemClock
import com.hualala.linyu.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object UserReleaseNotesRepository {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private const val KEY = "document.v1"
    private val refreshLock = Mutex()
    private val refreshPolicy = ReleaseNotesRefreshPolicy()
    private fun bundled(context: Context): List<UserReleaseNote> = runCatching {
        context.assets.open("APP_CHANGELOG.md").bufferedReader(Charsets.UTF_8).use { UserReleaseNotes.parse(it.readText()) }
    }.getOrDefault(emptyList())

    fun local(context: Context): List<UserReleaseNote> {
        val cached = context.getSharedPreferences("user_release_notes", Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        return UserReleaseNotes.local(cached, bundled(context))
    }

    suspend fun refresh(context: Context, force: Boolean = false): List<UserReleaseNote> = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            if (!refreshPolicy.shouldRefresh(SystemClock.elapsedRealtime(), force)) return@withLock local(context)
            val document = UserReleaseNotesLoader.load { url ->
                currentCoroutineContext().ensureActive()
                val request = Request.Builder().url(url)
                    .header("Cache-Control", "no-cache")
                    .header("Accept", "application/vnd.github.raw+json").build()
                client.newCall(request).execute().use { response ->
                    val source = response.body?.source()
                    if (!response.isSuccessful || source == null) {
                        ReleaseNotesResponse(response.code, byteArrayOf())
                    } else {
                        source.request(UserReleaseNotesLoader.MAX_BYTES + 1L)
                        val bytes = if (source.buffer.size > UserReleaseNotesLoader.MAX_BYTES) byteArrayOf()
                            else source.readByteArray()
                        ReleaseNotesResponse(response.code, bytes)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            refreshPolicy.finish(SystemClock.elapsedRealtime(), document != null)
            if (document != null) {
                // 完整远端文档覆盖缓存；已删除的条目/版本不再从旧文档补回。
                context.getSharedPreferences("user_release_notes", Context.MODE_PRIVATE).edit().putString(KEY, document).apply()
                AppLogger.i("用户更新日志已刷新")
                UserReleaseNotes.parse(document)
            } else {
                AppLogger.w("用户更新日志暂未刷新，保留本地内容")
                local(context)
            }
        }
    }
}
