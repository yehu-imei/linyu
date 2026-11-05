package com.hualala.linyu.data

import android.content.Context
import com.hualala.linyu.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object UserReleaseNotesRepository {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private const val KEY = "document.v1"
    private fun bundled(context: Context): List<UserReleaseNote> = runCatching {
        context.assets.open("APP_CHANGELOG.md").bufferedReader(Charsets.UTF_8).use { UserReleaseNotes.parse(it.readText()) }
    }.getOrDefault(emptyList())

    fun local(context: Context): List<UserReleaseNote> {
        val cached = context.getSharedPreferences("user_release_notes", Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        return UserReleaseNotes.merge(UserReleaseNotes.parse(cached), bundled(context))
    }

    suspend fun refresh(context: Context): List<UserReleaseNote> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(UserReleaseNotes.REMOTE_URL).build()
            client.newCall(request).execute().use { response ->
                val document = if (response.isSuccessful) response.body?.string() else null
                val notes = UserReleaseNotes.parse(document.orEmpty())
                if (notes.isNotEmpty()) {
                    val merged = UserReleaseNotes.merge(notes, local(context))
                    context.getSharedPreferences("user_release_notes", Context.MODE_PRIVATE).edit().putString(KEY, document).apply()
                    return@withContext merged
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { AppLogger.w("用户更新日志暂未刷新，保留本地内容") }
        local(context)
    }
}
