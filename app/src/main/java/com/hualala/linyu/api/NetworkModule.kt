package com.hualala.linyu.api

import com.hualala.linyu.BuildConfig
import com.hualala.linyu.utils.AppLogger
import com.hualala.linyu.utils.PrefsHelper
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkModule {
    @Volatile private var auth = ProjectAuthContext("", "", "", "", "")
    fun session(): ProjectAuthContext = auth

    /** 从 SharedPreferences 恢复登录态 */
    fun restoreFromPrefs(): Boolean {
        updateAuth(PrefsHelper.loginCode, PrefsHelper.userId, PrefsHelper.accountId,
            PrefsHelper.projectId, PrefsHelper.telephone)
        return auth.loginCode.isNotEmpty()
    }

    fun updateAuth(
        loginCode: String,
        userId: String,
        accountId: String,
        projectId: String,
        telephone: String
    ) {
        auth = ProjectAuthContext(loginCode, userId, accountId, projectId, telephone)
    }

    fun authFields(): Map<String, String> = auth.fields()

    /**
     * 官方 v3 API 固定要求的配置请求头。
     *
     * 参考 Funnyass_school（能正常结算）的 `ApiClient.headers()`：每个请求都带
     * `Config-Project`（= projectId）与 `Config-Keys`。我们此前完全没带，
     * 蓝牙结算（upload）因此报 226「加密校验失败」。
     */
    private val authInterceptor = Interceptor { chain ->
        val session = auth
        val request = authenticateProjectRequest(chain.request(), session)
        val response = chain.proceed(request)
        // 换账号期间返回的旧响应不能继续更新本地订单和账单。
        if (session.loginCode != auth.loginCode) {
            response.close()
            throw ProjectAuthException("登录状态已变化，请重试")
        }
        response
    }

    /** 应用内日志拦截器：始终生效，把每个请求/响应写入 AppLogger（自动脱敏），供 App 内查看 */
    private val appLogInterceptor = Interceptor { chain ->
        val req = chain.request()
        val start = System.currentTimeMillis()
        val reqBody = runCatching {
            val buffer = okio.Buffer()
            req.body?.writeTo(buffer)
            buffer.readUtf8()
        }.getOrDefault("")
        try {
            val resp = chain.proceed(req)
            val took = System.currentTimeMillis() - start
            val respBody = runCatching { resp.peekBody(4096).string() }.getOrDefault("")
            AppLogger.i(
                "HTTP ${resp.code} ${req.method} ${req.url.encodedPath} (${took}ms) project=${req.header("Config-Project") ?: "-"}" +
                    (if (reqBody.isNotEmpty()) "\n  req: ${reqBody.take(300)}" else "") +
                    (if (respBody.isNotEmpty()) "\n  resp: ${respBody.take(400)}" else "")
            )
            resp
        } catch (e: Exception) {
            AppLogger.e("HTTP FAIL ${req.method} ${req.url.encodedPath}", e)
            throw e
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(appLogInterceptor)
        .apply {
            if (BuildConfig.DEBUG) {
                // 原始 BODY 含 token / 账户 / 手机号；诊断统一用上面的脱敏日志。
                addInterceptor(HttpLoggingInterceptor { AppLogger.i(it) }.apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    val apiService: QzxyService = Retrofit.Builder()
        .baseUrl(QzxyService.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(QzxyService::class.java)
}
