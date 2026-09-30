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
    private var loginCode: String = ""
    private var userId: String = ""
    private var accountId: String = ""
    private var projectId: String = ""
    private var telephone: String = ""

    /** 从 SharedPreferences 恢复登录态 */
    fun restoreFromPrefs(): Boolean {
        loginCode = PrefsHelper.loginCode
        userId = PrefsHelper.userId
        accountId = PrefsHelper.accountId
        projectId = PrefsHelper.projectId
        telephone = PrefsHelper.telephone
        return loginCode.isNotEmpty()
    }

    fun updateAuth(
        loginCode: String,
        userId: String,
        accountId: String,
        projectId: String,
        telephone: String
    ) {
        this.loginCode = loginCode
        this.userId = userId
        this.accountId = accountId
        this.projectId = projectId
        this.telephone = telephone
    }

    fun authFields(): Map<String, String> = mapOf(
        "loginCode" to loginCode,
        "userId" to userId,
        "accountId" to accountId,
        "projectId" to projectId,
        "telephone" to telephone,
        "telPhone" to telephone,
        "phoneSystem" to "android",
        "version" to "6.5.28"
    )

    /**
     * 官方 v3 API 固定要求的配置请求头。
     *
     * 参考 Funnyass_school（能正常结算）的 `ApiClient.headers()`：每个请求都带
     * `Config-Project`（= projectId）与 `Config-Keys`。我们此前完全没带，
     * 蓝牙结算（upload）因此报 226「加密校验失败」。
     */
    private const val CONFIG_KEYS =
        "module_list,advertise_type,question_list,service_phone_list," +
            "banner_list_app,activity_list_app,aliCard_popup_config"

    private val authInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url
        val builder = originalRequest.newBuilder()

        // 所有请求都补上官方配置头（对齐 v3 API）
        if (projectId.isNotEmpty() && projectId != "0") {
            builder.addHeader("Config-Project", projectId)
            builder.addHeader("Config-Keys", CONFIG_KEYS)
        }

        if (loginCode.isNotEmpty() && originalRequest.method == "GET" && !originalUrl.encodedPath.contains("verification")) {
            val urlBuilder = originalUrl.newBuilder()
                .addQueryParameter("loginCode", loginCode)
                .addQueryParameter("userId", userId)
                .addQueryParameter("accountId", accountId)
                .addQueryParameter("projectId", projectId)
                .addQueryParameter("telephone", telephone)
                // ⚠️ `telPhone` 必须也带上。绝大多数 GET 接口只认 `telephone`，
                // 但 `/settlement/campus/userInfo`（一卡通余额）要的是 `telPhone`，
                // 少了它服务端直接返回 `手机号不能为空`——而且是 HTTP 200，
                // 只看状态码完全看不出来，只能靠读 errorMessage。
                .addQueryParameter("telPhone", telephone)
                .addQueryParameter("phoneSystem", "android")
                .addQueryParameter("version", "6.5.28")
            builder.url(urlBuilder.build())
        }

        chain.proceed(builder.build())
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
                "HTTP ${resp.code} ${req.method} ${req.url.encodedPath} (${took}ms)" +
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
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
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
