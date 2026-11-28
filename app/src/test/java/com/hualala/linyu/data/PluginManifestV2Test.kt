package com.hualala.linyu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.hualala.linyu.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * 插件清单 v2 + 双源下载 的回归测试。
 *
 * 这里锁住三件容易在后续改动中被破坏的事：
 * 1. **v1 清单必须继续能用**（老插件不能因为加了新字段就装不上）；
 * 2. 三条限制必须真的生效：`apiVersion` 上限、`minAppVersion`、未知 `permissions`；
 * 3. 下载源顺序必须是「主 Gitee、备 GitHub」。
 */
class PluginManifestV2Test {

    private val sha = "a".repeat(64)

    /** 一个合法的 v1 清单（老插件的形态）；[extra] 用来追加 v2 字段 */
    private fun v1Manifest(
        id: String = "nearby-devices",
        capability: String = "device.nearby",
        extra: JsonObject = JsonObject()
    ): JsonObject {
        val json = JsonObject()
        json.addProperty("formatVersion", 1)
        json.addProperty("id", id)
        json.addProperty("name", "附近设备")
        json.addProperty("author", "yehu-imei")
        json.addProperty("version", "1.0.0")
        json.addProperty("description", "查看附近的蓝牙设备")
        json.add("capabilities", JsonArray().apply { add(capability) })
        json.addProperty("path", "packages/$id-1.0.0.zip")
        json.addProperty("sha256", sha)
        for ((key, value) in extra.entrySet()) json.add(key, value)
        return json
    }

    /** 组装一份目录 JSON */
    private fun catalogOf(vararg entries: JsonObject): ByteArray {
        val plugins = JsonArray()
        entries.forEach(plugins::add)
        return JsonObject().apply {
            addProperty("formatVersion", 1)
            add("plugins", plugins)
        }.toString().toByteArray()
    }

    private fun json(vararg pairs: Pair<String, Any>): JsonObject = JsonObject().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                is Int -> addProperty(key, value)
                is Long -> addProperty(key, value)
                is String -> addProperty(key, value)
                is List<*> -> add(key, JsonArray().apply { value.forEach { add(it as String) } })
                is JsonArray -> add(key, value)
                else -> error("unsupported $value")
            }
        }
    }

    @Test
    fun `v1 manifest keeps working with defaults`() {
        val list = PluginRepository.parseCatalog(catalogOf(v1Manifest()))
        assertEquals(1, list.size)
        val manifest = list.first().manifest
        assertEquals(1, manifest.apiVersion)
        assertEquals(null, manifest.minAppVersion)
        assertTrue(manifest.permissions.isEmpty())
    }

    @Test
    fun `apiVersion above client support is skipped`() {
        val ok = PluginRepository.parseCatalog(catalogOf(v1Manifest(extra = json("apiVersion" to 2))))
        assertEquals("apiVersion=2 应当可用", 1, ok.size)
        assertEquals(2, ok.first().manifest.apiVersion)

        val tooNew = PluginRepository.parseCatalog(catalogOf(v1Manifest(extra = json("apiVersion" to 3))))
        assertTrue("apiVersion=3 应当被跳过", tooNew.isEmpty())
    }

    @Test
    fun `minAppVersion higher than current app is skipped`() {
        val accept = PluginRepository.parseCatalog(
            catalogOf(v1Manifest(extra = json("minAppVersion" to "1.0.0")))
        )
        assertEquals(1, accept.size)
        assertEquals("1.0.0", accept.first().manifest.minAppVersion)

        val reject = PluginRepository.parseCatalog(
            catalogOf(v1Manifest(extra = json("minAppVersion" to "99.0.0")))
        )
        assertTrue("要求 99.0.0 的插件应当被跳过", reject.isEmpty())
    }

    @Test
    fun `known permission is parsed and unknown permission is skipped`() {
        val known = PluginRepository.parseCatalog(
            catalogOf(
                v1Manifest(extra = json("permissions" to listOf("wallet.balance.read", "bill.list.read")))
            )
        )
        assertEquals(1, known.size)
        assertEquals(listOf("wallet.balance.read", "bill.list.read"), known.first().manifest.permissions)

        val unknown = PluginRepository.parseCatalog(
            catalogOf(v1Manifest(extra = json("permissions" to listOf("filesystem.write"))))
        )
        assertTrue("未知权限必须 fail closed（跳过）", unknown.isEmpty())
    }

    @Test
    fun `unsupported plugin does not break the rest of the catalog`() {
        val list = PluginRepository.parseCatalog(
            catalogOf(
                v1Manifest(extra = json("apiVersion" to 3)),
                v1Manifest(id = "consumption-trend", capability = "bill.consumption-trend")
            )
        )
        assertEquals("一个插件不支持，其它插件仍要出现", 1, list.size)
        assertEquals("consumption-trend", list.first().manifest.id)
    }

    @Test
    fun `serialize round trips v2 fields`() {
        val manifest = PluginManifest(
            id = "nearby-devices",
            name = "附近设备",
            author = "yehu-imei",
            version = "1.2.3",
            description = "查看附近的蓝牙设备",
            apiVersion = 2,
            minAppVersion = "3.1.2",
            permissions = listOf("device.nearby"),
            formatVersion = 2
        )
        val restored = PluginRepository.readInstalled(PluginRepository.serialize(manifest))
        assertNotNull(restored)
        assertEquals(manifest, restored)
    }

    @Test
    fun `invalid optional fields are skipped and saved incompatible plugins are inactive`() {
        val invalid = listOf(
            json("apiVersion" to "2"), json("apiVersion" to 0), json("minAppVersion" to 1),
            json("minAppVersion" to " "), json("permissions" to "device.nearby")
        )
        val valid = v1Manifest(id = "consumption-trend", capability = "bill.consumption-trend")
        invalid.forEach { fields ->
            val listing = PluginRepository.parseCatalog(catalogOf(v1Manifest(extra = fields), valid)).single()
            assertEquals("consumption-trend", listing.manifest.id)
        }
        val unsupported = v1Manifest(extra = json("minAppVersion" to "99.0.0"))
        assertNull(PluginRepository.readInstalled(unsupported.toString()))
        val deduplicated = v1Manifest(extra = json("formatVersion" to 2, "permissions" to listOf("device.nearby", "device.nearby")))
        val manifest = PluginRepository.parseCatalog(catalogOf(deduplicated)).single().manifest
        assertEquals(2, manifest.formatVersion)
        assertEquals(listOf("device.nearby"), manifest.permissions)
    }

    @Test
    fun `invalid primary directory and mismatched preferred package fall back to verified mirror`() = runBlocking {
        val (primary, backup) = PluginRepository.repositorySources
        val manifest = v1Manifest()
        val packageBytes = manifest.toString().toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(packageBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        manifest.addProperty("sha256", hash)
        val requests = mutableListOf<String>()
        val listing = PluginRepository.fetchCatalog { source, _ ->
            requests += source
            if (source == primary) byteArrayOf() else catalogOf(manifest)
        }.single()
        assertEquals(listOf(primary, backup), requests)
        assertEquals(backup, listing.source)

        requests.clear()
        val downloaded = PluginRepository.download(listing) { source, _ ->
            requests += source
            if (source == backup) byteArrayOf() else packageBytes
        }
        assertEquals(listOf(backup, primary), requests)
        assertEquals(listing.manifest, downloaded)
        val empty = PluginRepository.fetchCatalog { _, _ -> catalogOf() }
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `version comparison is segment wise`() {
        assertTrue(PluginRepository.compareVersions(listOf(3, 1, 2), listOf(3, 1, 3)) < 0)
        assertTrue(PluginRepository.compareVersions(listOf(3, 2, 0), listOf(3, 1, 9)) > 0)
        assertEquals(0, PluginRepository.compareVersions(listOf(3, 1, 2), listOf(3, 1, 2)))
    }

    @Test
    fun `current app version looks like x y z`() {
        // 上面的 minAppVersion 判断依赖 BuildConfig.VERSION_NAME 形如 x.y.z
        val pattern = Regex("^\\d+\\.\\d+\\.\\d+$")
        assertTrue(
            "版本号格式应为 x.y.z，实际 ${BuildConfig.VERSION_NAME}",
            pattern.matches(BuildConfig.VERSION_NAME)
        )
    }

    @Test
    fun `repository sources are gitee first and github as fallback`() {
        val sources = PluginRepository.repositorySources
        assertEquals("下载源应当是两个（主 + 备）", 2, sources.size)
        assertTrue("主源必须是 Gitee", sources[0].startsWith("https://raw.giteeusercontent.com/"))
        assertTrue("备源必须是 GitHub", sources[1].startsWith("https://raw.githubusercontent.com/"))
    }
}
