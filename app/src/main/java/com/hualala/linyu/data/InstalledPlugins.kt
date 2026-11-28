package com.hualala.linyu.data

import com.hualala.linyu.utils.PrefsHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object InstalledPlugins {
    const val ROOM_BINDING = "room-binding"
    const val NEARBY = "nearby-devices"
    const val CONSUMPTION_TREND = "consumption-trend"
    const val KEEP_SCREEN_ON = "keep-screen-on"
    const val BACKGROUND = "background"
    const val RUNTIME_LOG = "runtime-log"

    private val revision = MutableStateFlow(0)
    val changes = revision.asStateFlow()

    private data class RuntimeLogState(
        val storage: Any,
        val catalog: List<PluginListing>,
        val revision: Int,
        val enabled: Boolean
    )

    @Volatile private var runtimeLogState: RuntimeLogState? = null

    val all: List<PluginManifest>
        get() = PluginRepository.bundledCatalog.mapNotNull { installed(it.manifest.id) } +
            listOfNotNull(RoomBindingPlugin.installed)

    fun isInstalled(id: String): Boolean = installed(id) != null

    fun canRecordRuntimeLogs(): Boolean {
        // Catalog parsing itself may log before preferences are ready.
        val storage = PrefsHelper.storageIdentity ?: return true
        val catalog = PluginRepository.bundledCatalog
        val pluginRevision = revision.value
        val cached = runtimeLogState
        if (cached != null && cached.storage === storage && cached.catalog === catalog &&
            cached.revision == pluginRevision
        ) return cached.enabled

        val enabled = isInstalled(RUNTIME_LOG)
        runtimeLogState = RuntimeLogState(storage, catalog, pluginRevision, enabled)
        return enabled
    }

    private fun installed(id: String): PluginManifest? {
        if (id == ROOM_BINDING) return RoomBindingPlugin.installed
        val bundled = PluginRepository.bundledCatalog.firstOrNull { it.manifest.id == id } ?: return null
        val stored = PrefsHelper.featurePluginManifest(id)
        return if (stored == null) bundled.manifest
        else stored.takeIf { it.isNotEmpty() }?.let(PluginRepository::readInstalled)?.takeIf { it.id == id }
    }

    @Synchronized
    fun install(manifest: PluginManifest): Boolean {
        val changed = if (manifest.id == ROOM_BINDING) {
            RoomBindingPlugin.install(manifest)
        } else {
            val serialized = PluginRepository.serialize(manifest)
            if (PluginRepository.readInstalled(serialized) != manifest) return false
            if (installed(manifest.id)?.let { !PluginRepository.isUpdate(it, manifest) } == true) return false
            PrefsHelper.setFeaturePluginManifest(manifest.id, serialized)
            true
        }
        if (changed) revision.value++
        return changed
    }

    @Synchronized
    fun uninstall(id: String): Boolean {
        if (!isInstalled(id)) return false
        if (id == ROOM_BINDING) RoomBindingPlugin.uninstall()
        else PrefsHelper.setFeaturePluginManifest(id, "")
        revision.value++
        return true
    }
}
