package com.hualala.linyu.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.hualala.linyu.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** APK 下载状态 */
sealed interface ApkDownloadState {
    data object Idle : ApkDownloadState
    /** [total] 为 0 表示服务器没给长度；percent 为 -1 时进度条走不确定模式 */
    data class Running(val downloaded: Long, val total: Long, val percent: Int) : ApkDownloadState
    data class Done(val file: File) : ApkDownloadState
    data class Failed(val message: String) : ApkDownloadState
}

/** 调起安装器的结果 */
sealed interface ApkInstallResult {
    /** 已经拉起系统安装器 */
    data object Installing : ApkInstallResult
    /** 缺「安装未知应用」权限，已把用户送到设置页 */
    data object NeedPermission : ApkInstallResult
    data class Error(val message: String) : ApkInstallResult
}

/**
 * 应用内下载更新包并调起系统安装器。
 *
 * ## 设计要点
 *
 * 1. **下载跑在进程级作用域**，不挂 Compose 的 `rememberCoroutineScope`。
 *    否则用户切个 tab（页面被 AnimatedContent 销毁重建）就会把下载掐断。
 *
 * 2. **用 attempt 令牌保护状态写入**。每次下载有一个自增编号，
 *    只有当前编号的任务才有权改 [state]，旧任务的回调一律作废。
 *    没有这层保护时，被取消的旧任务会把新任务的结果覆盖掉。
 *
 * 3. **取消不是失败**。`CancellationException` 必须原样抛出——
 *    它是 `Exception` 的子类，若被下面的 `catch (e: Exception)` 吃掉，
 *    用户点了「取消下载」反而会看到「下载失败」，看起来像凭空冒出来的网络错误。
 *
 * 4. **下载完校验字节数**，不完整就报错重下，不给用户一个装不上的坏包。
 */
object ApkUpdater {

    /** 供界面观察的下载状态 */
    var state by mutableStateOf<ApkDownloadState>(ApkDownloadState.Idle)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 下载任务编号：只有编号匹配的回调才能写状态 */
    private var attempt = 0

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 是否正在下载（界面用来禁掉重复点击） */
    val isDownloading: Boolean get() = state is ApkDownloadState.Running

    /** 清掉状态，回到可下载。下载中调用无效果 */
    fun reset() {
        if (isDownloading) return
        job = null
        state = ApkDownloadState.Idle
    }

    /**
     * 开始下载。
     * @param fileName 保存到 cacheDir/updates/ 下的文件名，用发行版里的原始名
     */
    fun start(
        context: Context,
        url: String,
        fileName: String,
        expectedSize: Long = 0L,
        releaseTag: String? = null
    ) {
        if (isDownloading) return
        val app = context.applicationContext

        job?.cancel()
        val myAttempt = ++attempt
        state = ApkDownloadState.Running(0, 0, -1)

        job = scope.launch {
            try {
                // GitHub 的 digest 是可选字段，镜像下载也可能遇到 GitHub API 不通，
                // 因此取不到时只降级到安装前的签名校验，不能让 Gitee 用户无法更新。
                val expectedSha256 = releaseTag
                    ?.takeIf { it.isNotBlank() }
                    ?.let { fetchExpectedSha256(it, fileName) }
                val file = download(app, url, fileName, expectedSize, expectedSha256) { done, total ->
                    if (myAttempt == attempt) {
                        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                        state = ApkDownloadState.Running(done, total, pct)
                    }
                }
                if (myAttempt == attempt) state = ApkDownloadState.Done(file)
            } catch (e: CancellationException) {
                // 用户主动取消：状态由 cancel() 负责，这里不能报失败
                throw e
            } catch (e: Exception) {
                if (myAttempt == attempt) state = ApkDownloadState.Failed(describe(e))
            }
        }
    }

    fun cancel() {
        attempt++        // 让在途任务的回调与收尾写入全部失效
        job?.cancel()
        job = null
        state = ApkDownloadState.Idle
    }

    /**
     * 下载（OkHttp 阻塞式 execute，由调用方的 IO 协程承载）。
     *
     * 先写 `.part` 再改名：中途断了不会留下一个看着完整、实际是坏的文件。
     * 每读一块检查一次协程是否被取消——阻塞式 IO 本身不响应取消，
     * 不检查的话点了「取消」后台还会继续下完 40MB。
     */
    private suspend fun download(
        context: Context,
        url: String,
        fileName: String,
        expectedSize: Long,
        expectedSha256: String?,
        onProgress: (Long, Long) -> Unit
    ): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, fileName)
        val temp = File(dir, "$fileName.part")

        // 用户重复点下载时，完整的同版本包可以直接复用；摘要存在时一并校验，
        // 避免同长度但内容已损坏的缓存被误判为可安装。
        if (target.exists() && target.length() > 0 &&
            (expectedSize <= 0 || target.length() == expectedSize) &&
            (expectedSha256 == null || sha256(target) == expectedSha256)
        ) {
            return target
        }
        if (target.exists()) target.delete()

        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "LinYu-Android")
            .build()

        val declaredLength: Long
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("服务器响应为空")
                declaredLength = body.contentLength()

                temp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, declaredLength)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 取消或失败都别留下半截文件占着缓存
            temp.delete()
            throw e
        }

        // 完整性校验：长度对不上说明传输被截断，这种包装了会失败
        val actual = temp.length()
        if (actual <= 0) {
            temp.delete()
            error("更新包下载不完整，请重新下载")
        }
        if (declaredLength > 0 && actual != declaredLength) {
            temp.delete()
            error("更新包下载不完整（$actual/$declaredLength 字节），请重新下载")
        }
        if (expectedSize > 0 && actual != expectedSize) {
            temp.delete()
            error("更新包下载不完整（$actual/$expectedSize 字节），请重新下载")
        }
        if (expectedSha256 != null && sha256(temp) != expectedSha256) {
            temp.delete()
            error("更新包 SHA-256 校验失败，已阻止安装")
        }

        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        // 跨版本附件名不同，若不主动清理会一直堆在缓存目录里。
        dir.listFiles()?.forEach { file ->
            if (file != target) file.delete()
        }
        return target
    }

    private fun fetchExpectedSha256(releaseTag: String, fileName: String): String? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/yehu-imei/linyu/releases/tags/${Uri.encode(releaseTag)}")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "LinYu-Android")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = resp.body?.string() ?: return null
                val assets = JsonParser.parseString(json).asJsonObject.getAsJsonArray("assets")
                    ?: return null
                val digest = assets
                    .map { it.asJsonObject }
                    .firstOrNull { it.get("name")?.asString == fileName }
                    ?.get("digest")?.asString
                    ?: return null
                digest.substringAfter("sha256:", "")
                    .lowercase()
                    .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 失败原因转成人话。
     * 带上异常类名，用户截图反馈时能直接定位是超时、断流还是解析问题——
     * 只写「网络异常」的话什么线索都没有。
     */
    private fun describe(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("Unable to resolve host", true) ||
                m.contains("No address associated", true) -> "无法连接 GitHub，请检查网络后重试"
            m.contains("timeout", true) || m.contains("timed out", true) -> "下载超时，请检查网络后重试"
            m.contains("Network is unreachable", true) -> "网络不可用"
            m.startsWith("HTTP ") -> "更新包下载失败（服务器返回 $m）"
            m.startsWith("更新包") -> m
            else -> "更新包下载失败：${e.javaClass.simpleName}" + if (m.isNotEmpty()) "（$m）" else ""
        }
    }

    // ════════════════════════════════════════════
    //  安装
    // ════════════════════════════════════════════

    /**
     * 调起系统安装器。
     *
     * 三种结果都会明确返回，**不静默失败**——
     * 之前文件不存在时直接 return，用户看到的是"点了没反应"。
     */
    fun installApk(context: Context, file: File): ApkInstallResult {
        if (!file.exists() || file.length() <= 0) {
            return ApkInstallResult.Error("更新包已丢失，请重新下载")
        }

        verifyApkIdentity(context, file)?.let { message ->
            return ApkInstallResult.Error(message)
        }

        // Android 8.0 起安装未知来源应用需要单独授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return openUnknownSourcesSettings(context)
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ApkInstallResult.Installing
        } catch (e: Exception) {
            ApkInstallResult.Error("无法调起安装器：${e.message}")
        }
    }

    private fun verifyApkIdentity(context: Context, file: File): String? {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        val archiveInfo = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return "无法读取更新包签名，已阻止安装"
        if (archiveInfo.packageName != BuildConfig.APPLICATION_ID) {
            return "更新包包名与当前应用不一致，已阻止安装"
        }
        val installedInfo = try {
            packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, flags)
        } catch (_: Exception) {
            return "无法读取当前应用签名，已阻止安装"
        }
        val archiveDigests = certificateDigests(archiveInfo)
        val installedDigests = certificateDigests(installedInfo)
        if (archiveDigests.isEmpty()) return "无法读取更新包签名，已阻止安装"
        if (installedDigests.isEmpty()) return "无法读取当前应用签名，已阻止安装"
        if (archiveDigests != installedDigests) {
            return "更新包签名与当前应用不一致，已阻止安装"
        }
        return null
    }

    private fun certificateDigests(info: PackageInfo): Set<String> {
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * 跳转到「安装未知应用」授权页。
     *
     * 各厂商 ROM 对这个页面的支持不一致，小米 / 华为等可能没有标准入口，
     * 所以按「标准页 → 无参标准页 → 本应用详情页」逐级降级，
     * 都打不开时返回明确的手动操作指引。
     */
    private fun openUnknownSourcesSettings(context: Context): ApkInstallResult {
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"))
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return ApkInstallResult.NeedPermission
            } catch (_: Exception) {
                // 这个入口不存在，试下一个
            }
        }
        return ApkInstallResult.Error(
            "请手动前往：系统设置 → 应用管理 → 淋浴 → 允许安装未知应用"
        )
    }

    /** 带 v 前缀的版本号比较：latest 是否比 current 新 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = versionNumbers(latest)
        val b = versionNumbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** "v2.2.0" / "2.2.0" / "2.2.0-beta" → [2, 2, 0] */
    private fun versionNumbers(tag: String): List<Int> =
        tag.trim().trimStart('v', 'V')
            .split('.', '-', '+')
            .mapNotNull { it.toIntOrNull() }

    /** 当前 App 版本，形如 "v2.2.0" */
    val currentVersion: String get() = "v${BuildConfig.VERSION_NAME}"
}
