package com.hualala.linyu.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomBindingPluginTest {
    private val legacyManifest = """
        {
          "id": "room-binding",
          "formatVersion": 1,
          "version": "1.0.0",
          "capabilities": ["device.room-filter"]
        }
    """.trimIndent()
    private val expected = PluginRepository.legacyRoomBinding().copy(version = "1.1.0")
    private val manifest = PluginRepository.serialize(expected)

    @Test
    fun keepsThePreviouslyInstalledManifest() {
        assertEquals(PluginRepository.legacyRoomBinding(), PluginRepository.readInstalled(legacyManifest))
        assertNull(PluginRepository.readInstalled("{}"))
        assertThrows(IllegalArgumentException::class.java) {
            PluginRepository.parsePackage(legacyManifest.toByteArray())
        }
    }

    @Test
    fun importsJsonAndZipWithTheSameManifest() {
        assertEquals(expected, PluginRepository.parsePackage(("\uFEFF" + manifest).toByteArray()))
        assertEquals(expected, PluginRepository.parsePackage(zip("manifest.json" to manifest)))
    }

    @Test
    fun rejectsUnsupportedFormatsAndCapabilities() {
        listOf(
            manifest.replace("room-binding", "another-plugin"),
            manifest.replace("\"formatVersion\":1", "\"formatVersion\":3"),
            manifest.replace("device.room-filter", "device.start"),
            manifest.replace("1.1.0", "1.1.0-preview"),
            "{}",
            "[]"
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                PluginRepository.parsePackage(invalid.toByteArray())
            }
        }
    }

    @Test
    fun acceptsOnlyASingleRootManifestWithinTheSizeLimit() {
        listOf(
            zip("../manifest.json" to manifest),
            zip("files/manifest.json" to manifest),
            zip("manifest.json" to manifest, "README.md" to "Unexpected content"),
            zip("manifest.json" to manifest.padEnd(17 * 1024))
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                PluginRepository.parsePackage(invalid)
            }
        }
    }

    @Test
    fun verifiesCatalogPathChecksumAndMatchingManifest() {
        val payload = zip("manifest.json" to manifest)
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val item = manifest.dropLast(1) +
            ""","path":"packages/room-binding-1.1.0.zip","sha256":"$checksum"}"""
        val catalog = """{"formatVersion":1,"plugins":[$item]}"""
        val listing = PluginRepository.parseCatalog(catalog.toByteArray()).single()
        assertEquals(expected, PluginRepository.verifyDownload(listing, payload))

        assertThrows(IllegalArgumentException::class.java) {
            PluginRepository.verifyDownload(listing, payload + 0.toByte())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginRepository.verifyDownload(listing.copy(manifest = expected.copy(version = "1.2.0")), payload)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginRepository.parseCatalog(catalog.replace("packages/", "../").toByteArray())
        }
    }

    @Test
    fun detectsOnlyHigherVersionsOfTheSamePlugin() {
        assertTrue(PluginRepository.isUpdate(expected, expected.copy(version = "1.12.0")))
        assertFalse(PluginRepository.isUpdate(expected, expected))
        assertFalse(PluginRepository.isUpdate(expected, expected.copy(version = "1.0.9")))
        assertFalse(PluginRepository.isUpdate(expected, expected.copy(id = "other", version = "2.0.0")))
    }

    @Test
    fun bundledPluginsRoundTripWithTheirAuthorsAndPublishedVersions() {
        val catalog = PluginRepository.parseCatalog(File("src/main/assets/linyu_bundled_plugins.json").readBytes())
        assertEquals(setOf(InstalledPlugins.NEARBY, InstalledPlugins.CONSUMPTION_TREND,
            InstalledPlugins.KEEP_SCREEN_ON, InstalledPlugins.BACKGROUND, InstalledPlugins.RUNTIME_LOG),
            catalog.map { it.manifest.id }.toSet())
        for ((plugin) in catalog) {
            assertEquals(plugin, PluginRepository.readInstalled(PluginRepository.serialize(plugin)))
            assertEquals("1.0.0", plugin.version)
            assertEquals(1, plugin.versionEpoch)
            assertTrue(plugin.details.orEmpty().length > plugin.description.length)
        }
        val trend = catalog.single { it.manifest.id == InstalledPlugins.CONSUMPTION_TREND }.manifest
        assertEquals("XiaoLoad", trend.author)
        assertEquals(63766325L, trend.authorGithubId)
        assertNull(PluginRepository.readInstalled(PluginRepository.serialize(trend.copy(authorGithubId = -1))))
    }

    @Test
    fun previewMetadataMigratesAndCanUpdateToTheFirstRelease() {
        val preview = PluginRepository.readInstalled("""
            {"id":"consumption-trend","name":"消费趋势","author":"LinYu","version":"1.0.0",
             "description":"查看趋势","formatVersion":1,"capabilities":["bill.consumption-trend"],
             "credit":"XiaoLoad","creditGithubId":63766325}
        """.trimIndent())!!
        assertEquals("XiaoLoad", preview.author)
        assertEquals(63766325L, preview.authorGithubId)
        assertEquals("1.0.0", preview.version)
        assertTrue(PluginRepository.isUpdate(preview, preview.copy(versionEpoch = 1)))
        val first = expected.copy(version = "1.0.0", versionEpoch = 1)
        assertTrue(PluginRepository.isUpdate(expected, first))
        assertFalse(PluginRepository.isUpdate(first, expected))
        assertTrue(PluginRepository.isUpdate(first, first.copy(version = "1.0.1")))
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
