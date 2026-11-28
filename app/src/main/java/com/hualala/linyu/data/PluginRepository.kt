package com.hualala.linyu.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.google.gson.JsonArray
import com.hualala.linyu.BuildConfig
import com.hualala.linyu.utils.AppLogger
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 插件清单（`manifest.json`）。
 *
 * ## 版本约定
 *
 * - `formatVersion` = 1：现有 v1 清单（只有元数据 + capabilities）
 * - `formatVersion` = 2：旧客户端会跳过该清单，适用于依赖新版兼容校验的插件。
 * - 新增可选字段（旧清单不写时取默认值）：
 *   - [apiVersion]：插件按哪一版客户端能力表写的；客户端只接受 `<= SUPPORTED_API`
 *   - [minAppVersion]：要求的最低 App 版本（x.y.z）；低于它的客户端直接跳过该插件
 *   - [permissions]：插件会访问的数据/动作，**用户可在详情页看到**；
 *     不在客户端白名单里的权限一律拒绝安装（fail closed）
 *
 * ⚠️ 这三项都是**限制**而不是能力扩展：插件依旧不含代码，能做什么仍由客户端决定。
 */
internal data class PluginManifest(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val description: String,
    val details: String? = null,
    val authorGithubId: Long? = null,
    val versionEpoch: Int = 0,
    val apiVersion: Int = 1,
    val minAppVersion: String? = null,
    val permissions: List<String> = emptyList(),
    val formatVersion: Int = 1
)

internal data class PluginListing(
    val manifest: PluginManifest, val path: String, val sha256: String, val source: String? = null
)

internal object PluginRepository {
    /**
     * 插件仓库的候选下载源，**按顺序尝试**。
     *
     * - 主源 Gitee（国内直连快）
     * - 备源 GitHub raw（Gitee 挂掉/被限流时兜底）
     *
     * ⚠️ 两个源必须放**同一份**文件（校验值是同一个），否则会出现
     * 「Gitee 版本下载成功、GitHub 版本校验失败」这种自找的故障。
     */
    private val SOURCES = listOf(
        "https://raw.giteeusercontent.com/yehu-imei/linyu-plugins/raw/main/",
        "https://raw.githubusercontent.com/yehu-imei/linyu-plugins/main/"
    )

    /** 当前生效的下载源（按优先级），测试用它锁住「主 Gitee、备 GitHub」 */
    internal val repositorySources: List<String> get() = SOURCES

    /** 客户端支持的插件 API 版本上限（清单里的 `apiVersion` 超过它就被跳过） */
    private const val SUPPORTED_API = 2

    /**
     * 允许插件声明的权限白名单。
     *
     * 插件不执行代码，所以这些权限目前只影响**详情页展示**与**安装校验**
     * （声明了未知权限 = 这个插件是给更新版本客户端写的，直接跳过）。
     * 保留这份表的意义：将来做声明式 UI 时，权限模型不用推倒重来。
     */
    private val KNOWN_PERMISSIONS = setOf(
        "device.nearby",            // 读取附近设备列表
        "device.room-filter",       // 按寝室筛选设备
        "wallet.balance.read",      // 读取钱包/一卡通余额
        "bill.list.read",           // 读取消费账单
        "usage.keep-screen-on",     // 用水期间保持屏幕常亮
        "appearance.background",    // 修改界面背景
        "diagnostics.runtime-log",  // 读写运行日志
        "action.open-url",          // 用系统浏览器打开声明过的链接
        "action.open-recharge"      // 打开官方充值入口
    )
    private const val ROOM_BINDING = "room-binding"
    private const val ROOM_FILTER = "device.room-filter"
    private val capabilities = mapOf(
        ROOM_BINDING to ROOM_FILTER,
        InstalledPlugins.NEARBY to "device.nearby",
        InstalledPlugins.CONSUMPTION_TREND to "bill.consumption-trend",
        InstalledPlugins.KEEP_SCREEN_ON to "usage.keep-screen-on",
        InstalledPlugins.BACKGROUND to "appearance.background",
        InstalledPlugins.RUNTIME_LOG to "diagnostics.runtime-log"
    )
    private const val CONTENT_LIMIT = 128 * 1024
    private const val MANIFEST_LIMIT = 16 * 1024
    private val versionPattern = Regex("(0|[1-9][0-9]{0,8})(\\.(0|[1-9][0-9]{0,8})){2}")
    private val checksumPattern = Regex("[0-9a-fA-F]{64}")
    private val client = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Volatile var bundledCatalog: List<PluginListing> = emptyList()
        private set
    @Volatile private var authorCache: SharedPreferences? = null
    private val github = client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()

    fun initialize(context: Context) {
        if (bundledCatalog.isEmpty()) {
            bundledCatalog = context.assets.open("linyu_bundled_plugins.json").use {
                parseCatalog(readLimited(it, CONTENT_LIMIT))
            }
        }
        authorCache = context.getSharedPreferences("linyu_plugin_credits", Context.MODE_PRIVATE)
    }

    suspend fun fetchCatalog(
        read: suspend (String, String) -> ByteArray = { base, path -> fetchBytes(base, path) }
    ): List<PluginListing> = withContext(Dispatchers.IO) {
        fetchValidated("catalog.json", null, read) { bytes, source ->
            parseCatalog(bytes).map { it.copy(source = source) }
        }
    }

    suspend fun download(
        listing: PluginListing, read: suspend (String, String) -> ByteArray = { base, path -> fetchBytes(base, path) }
    ): PluginManifest = withContext(Dispatchers.IO) {
        require(listing.path == packagePath(listing.manifest)) { "插件下载地址无效" }
        fetchValidated(listing.path, listing.source, read) { bytes, _ -> verifyDownload(listing, bytes) }
    }

    suspend fun fetchGithubLogins(manifests: List<PluginManifest>): Map<Long, String> = withContext(Dispatchers.IO) {
        manifests.mapNotNull { it.authorGithubId }.distinct().map { id ->
            async { githubLogin(id)?.let { id to it } }
        }.awaitAll().filterNotNull().toMap()
    }

    private fun githubLogin(id: Long): String? {
        val key = id.toString()
        val cache = authorCache
        val cached = cache?.getString(key, null)
        val now = System.currentTimeMillis()
        val cachedAt = cache?.getLong("$key-time", 0L) ?: 0L
        if (cached != null && now - cachedAt in 0L until 86_400_000L) return cached
        return runCatching {
            val request = Request.Builder().url("https://api.github.com/user/$id")
                .header("Accept", "application/vnd.github+json").build()
            github.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                val profile = parseObject(readLimited(body.byteStream(), MANIFEST_LIMIT), "GitHub 账户无效")
                profile.string("login")?.takeIf {
                    profile.positiveLong("id") == id && it.matches(Regex("[A-Za-z0-9-]{1,39}"))
                }?.also { cache?.edit()?.putString(key, it)?.putLong("$key-time", now)?.apply() }
            }
        }.getOrNull() ?: cached
    }

    suspend fun importFile(context: Context, uri: Uri): PluginManifest = withContext(Dispatchers.IO) {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { readLimited(it, CONTENT_LIMIT) }
        } catch (e: IOException) {
            throw IllegalArgumentException("无法读取插件文件", e)
        } catch (e: SecurityException) {
            throw IllegalArgumentException("无法读取插件文件", e)
        } ?: throw IllegalArgumentException("无法读取插件文件")
        parsePackage(bytes)
    }

    fun isUpdate(installed: PluginManifest, available: PluginManifest): Boolean {
        if (installed.id != available.id) return false
        val current = versionParts(installed.version) ?: return false
        val next = versionParts(available.version) ?: return false
        if (available.versionEpoch != installed.versionEpoch) return available.versionEpoch > installed.versionEpoch
        return next.indices.firstOrNull { next[it] != current[it] }?.let { next[it] > current[it] } ?: false
    }

    internal fun parseCatalog(bytes: ByteArray): List<PluginListing> {
        require(bytes.size <= CONTENT_LIMIT) { "插件目录过大" }
        val catalog = parseObject(bytes, "插件目录无效")
        require(catalog.isFormatOne()) { "插件目录需要新版淋浴" }
        val entries = catalog.get("plugins") as? JsonArray ?: throw IllegalArgumentException("插件目录无效")
        val supported = entries.mapNotNull { entry ->
            val item = entry as? JsonObject ?: throw IllegalArgumentException("插件目录无效")
            if (item.string("id") !in capabilities || item.manifestFormat() == null) {
                return@mapNotNull null
            }
            val manifest = try {
                parseManifest(item)
            } catch (e: IncompatiblePlugin) {
                AppLogger.i("跳过插件 ${item.string("id")}：${e.message}")
                return@mapNotNull null
            }
            val path = item.string("path").orEmpty()
            val hash = item.string("sha256").orEmpty()
            require(path == packagePath(manifest) && checksumPattern.matches(hash)) { "插件下载信息无效" }
            PluginListing(manifest, path, hash)
        }
        require(supported.map { it.manifest.id }.distinct().size == supported.size) { "插件目录包含重复项目" }
        return supported
    }

    internal fun verifyDownload(listing: PluginListing, bytes: ByteArray): PluginManifest {
        require(bytes.size <= CONTENT_LIMIT) { "插件文件过大" }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(actual.equals(listing.sha256, ignoreCase = true)) { "插件校验失败，请重试" }
        val manifest = parsePackage(bytes)
        require(manifest == listing.manifest) { "插件与目录信息不一致" }
        return manifest
    }

    internal fun parsePackage(bytes: ByteArray): PluginManifest {
        require(bytes.size <= CONTENT_LIMIT) { "插件文件过大" }
        val zip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
        val manifestBytes = if (zip) readZipManifest(bytes) else bytes
        require(manifestBytes.size <= MANIFEST_LIMIT) { "插件清单过大" }
        return parseManifest(parseObject(manifestBytes, "插件清单无效"))
    }

    internal fun readInstalled(text: String): PluginManifest? = runCatching {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MANIFEST_LIMIT) return@runCatching null
        val manifest = parseObject(bytes, "插件清单无效")
        if (manifest.isFormatOne() && manifest.supportsRoomBinding() && manifest.string("version") == "1.0.0" &&
            listOf("name", "author", "description", "apiVersion", "minAppVersion", "permissions").none(manifest::has)
        ) {
            legacyRoomBinding()
        } else {
            parseManifest(manifest)
        }
    }.getOrNull()

    internal fun legacyRoomBinding(): PluginManifest = PluginManifest(
        ROOM_BINDING, "绑定寝室", "yehu-imei", "1.0.0", "为首页和桌面小组件筛选寝室设备",
        authorGithubId = 178531070L
    )

    internal fun serialize(manifest: PluginManifest): String = JsonObject().apply {
        addProperty("id", manifest.id)
        addProperty("name", manifest.name)
        addProperty("author", manifest.author)
        addProperty("version", manifest.version)
        addProperty("description", manifest.description)
        manifest.details?.let { addProperty("details", it) }
        manifest.authorGithubId?.let { addProperty("authorGithubId", it) }
        if (manifest.versionEpoch != 0) addProperty("versionEpoch", manifest.versionEpoch)
        if (manifest.apiVersion != 1) addProperty("apiVersion", manifest.apiVersion)
        manifest.minAppVersion?.let { addProperty("minAppVersion", it) }
        if (manifest.permissions.isNotEmpty()) {
            add("permissions", JsonArray().apply { manifest.permissions.forEach(::add) })
        }
        addProperty("formatVersion", manifest.formatVersion)
        add("capabilities", JsonArray().apply { add(capabilities[manifest.id]) })
    }.toString()

    private fun parseManifest(json: JsonObject): PluginManifest {
        val id = json.string("id").orEmpty()
        val (api, minApp, permissions) = compatibility(json)
        require(json.supports(id)) { "当前版本不支持此插件" }
        val version = json.string("version").orEmpty()
        require(versionParts(version) != null) { "插件版本无效" }
        val author = json.requiredText("author", 48)
        val authorID = json.positiveLong("authorGithubId")
        require(!json.has("authorGithubId") || authorID != null) { "插件作者无效" }
        // 正式首版以 v1.0.0 起步，仍应更新此前已安装的预览版。
        val epoch = if (json.has("versionEpoch")) {
            require(json.positiveLong("versionEpoch") == 1L) { "插件版本无效" }
            1
        } else 0
        // 将上一版已保存的内置清单调整为只展示作者，保留实际安装版本。
        val legacyAuthor = json.string("credit")?.trim()?.takeIf { author == "LinYu" && it.isNotEmpty() && it.length <= 48 }
        val legacyOwner = author == "LinYu" && (id == ROOM_BINDING || id == InstalledPlugins.NEARBY)
        return PluginManifest(
            id = id,
            name = json.requiredText("name", 48),
            author = legacyAuthor ?: if (legacyOwner) "yehu-imei" else author,
            version = version,
            description = json.requiredText("description", 300),
            details = if (json.has("details")) json.requiredText("details", 800) else null,
            authorGithubId = authorID ?: if (legacyAuthor != null) json.positiveLong("creditGithubId")
                else if (legacyOwner) 178531070L else null,
            versionEpoch = epoch,
            apiVersion = api,
            minAppVersion = minApp,
            permissions = permissions,
            formatVersion = requireNotNull(json.manifestFormat())
        )
    }

    private fun JsonObject.supportsRoomBinding(): Boolean = supports(ROOM_BINDING)

    private fun JsonObject.supports(id: String): Boolean {
        val expected = capabilities[id] ?: return false
        val capabilities = get("capabilities") as? JsonArray ?: return false
        val cap = capabilities.singleOrNull()
        return string("id") == id && manifestFormat() != null &&
            cap?.isJsonPrimitive == true && cap.asJsonPrimitive.isString && cap.asString == expected
    }

    private fun JsonObject.isFormatOne(): Boolean {
        val value = get("formatVersion") ?: return false
        return value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asString == "1"
    }

    private fun JsonObject.manifestFormat(): Int? = longOrNull("formatVersion")
        ?.takeIf { it in 1L..2L }?.toInt()

    private class IncompatiblePlugin(message: String) : IllegalArgumentException(message)

    private data class Compatibility(val api: Int, val minApp: String?, val permissions: List<String>)

    private fun compatibility(json: JsonObject): Compatibility {
        val api = if (json.has("apiVersion")) {
            json.positiveLong("apiVersion") ?: throw IncompatiblePlugin("插件 API 版本无效")
        } else 1L
        if (api > SUPPORTED_API) throw IncompatiblePlugin("需要新版淋浴（插件 apiVersion=$api）")

        val minApp = if (json.has("minAppVersion")) {
            json.string("minAppVersion")?.trim() ?: throw IncompatiblePlugin("插件的最低版本号无效")
        } else null
        if (minApp != null) {
            val required = versionParts(minApp) ?: throw IncompatiblePlugin("插件的最低版本号无效")
            val current = versionParts(BuildConfig.VERSION_NAME)
            if (current == null || compareVersions(required, current) > 0) {
                throw IncompatiblePlugin("需要淋浴 $minApp 及以上（当前 ${BuildConfig.VERSION_NAME}）")
            }
        }

        val permissions = if (json.has("permissions")) {
            json.stringArray("permissions")?.distinct() ?: throw IncompatiblePlugin("插件权限声明无效")
        } else emptyList()
        val unknown = permissions.firstOrNull { it !in KNOWN_PERMISSIONS }
        if (unknown != null) throw IncompatiblePlugin("声明了未知权限：$unknown")
        return Compatibility(api.toInt(), minApp, permissions)
    }

    /** 逐段比较 `x.y.z`；a < b 返回负数 */
    internal fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /** 权限 id → 给用户看的中文说明（详情页展示用） */
    internal fun permissionLabel(id: String): String = when (id) {
        "device.nearby" -> "读取附近设备"
        "device.room-filter" -> "按寝室筛选设备"
        "wallet.balance.read" -> "读取钱包/一卡通余额"
        "bill.list.read" -> "读取消费账单"
        "usage.keep-screen-on" -> "用水时保持屏幕常亮"
        "appearance.background" -> "修改界面背景"
        "diagnostics.runtime-log" -> "读写运行日志"
        "action.open-url" -> "打开声明过的网页"
        "action.open-recharge" -> "打开官方充值入口"
        else -> id
    }

    private fun JsonObject.longOrNull(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()

    private fun JsonObject.stringArray(key: String): List<String>? {
        val array = get(key) as? JsonArray ?: return null
        val out = ArrayList<String>(array.size())
        for (element in array) {
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
            out.add(element.asString)
        }
        return out
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.positiveLong(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asString?.toLongOrNull()?.takeIf { it > 0 }

    private fun JsonObject.requiredText(key: String, limit: Int): String {
        val value = string(key)?.trim().orEmpty()
        require(value.isNotEmpty() && value.length <= limit) { "插件名称、作者或描述无效" }
        return value
    }

    private fun versionParts(version: String): List<Int>? =
        version.takeIf(versionPattern::matches)?.split('.')?.map(String::toInt)

    private fun packagePath(manifest: PluginManifest): String {
        require(manifest.id in capabilities && versionParts(manifest.version) != null) { "插件下载地址无效" }
        return "packages/${manifest.id}-${manifest.version}.zip"
    }

    private fun readZipManifest(bytes: ByteArray): ByteArray = try {
        ZipInputStream(bytes.inputStream()).use { zip ->
            val entry = zip.nextEntry
            require(entry != null && !entry.isDirectory && entry.name == "manifest.json") {
                "ZIP 根目录需要包含 manifest.json"
            }
            val manifest = readLimited(zip, MANIFEST_LIMIT)
            require(zip.nextEntry == null) { "此插件包只支持一个清单文件" }
            manifest
        }
    } catch (e: IOException) {
        throw IllegalArgumentException("插件 ZIP 已损坏", e)
    }

    private fun parseObject(bytes: ByteArray, error: String): JsonObject = try {
        JsonParser.parseString(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")).asJsonObject
    } catch (e: RuntimeException) {
        throw IllegalArgumentException(error, e)
    }

    private suspend fun <T> fetchValidated(
        path: String, preferredSource: String?, read: suspend (String, String) -> ByteArray,
        validate: (ByteArray, String) -> T
    ): T {
        val sources = SOURCES.sortedBy { if (it == preferredSource) 0 else 1 }
        var validationError: IllegalArgumentException? = null
        var networkError: IOException? = null
        for (base in sources) {
            currentCoroutineContext().ensureActive()
            try {
                val bytes = read(base, path)
                currentCoroutineContext().ensureActive()
                return validate(bytes, base)
            } catch (e: IOException) {
                networkError = e
            } catch (e: IllegalArgumentException) {
                validationError = e
            }
            AppLogger.w("插件源不可用：$base（$path）")
        }
        currentCoroutineContext().ensureActive()
        throw validationError ?: IllegalArgumentException("无法连接插件仓库，请稍后重试", networkError)
    }

    private fun fetchBytes(base: String, path: String): ByteArray {
        return client.newCall(Request.Builder().url(base + path).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("空文件")
            body.byteStream().use { readLimited(it, CONTENT_LIMIT) }
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(4096)
        val output = ByteArrayOutputStream()
        while (true) {
            val size = input.read(buffer)
            if (size < 0) return output.toByteArray()
            require(output.size() + size <= limit) { "插件文件超过大小限制" }
            output.write(buffer, 0, size)
        }
    }
}
