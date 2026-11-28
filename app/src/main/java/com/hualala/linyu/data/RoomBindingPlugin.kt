package com.hualala.linyu.data

import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.utils.PrefsHelper

internal object RoomBindingPlugin {
    @get:Synchronized
    val installed: PluginManifest?
        get() = PrefsHelper.roomBindingManifest?.let(PluginRepository::readInstalled)
            ?.takeIf { it.id == InstalledPlugins.ROOM_BINDING }

    val isInstalled: Boolean
        get() = installed != null

    @get:Synchronized
    val effectiveRoom: String
        get() = PrefsHelper.boundRoom.takeIf { it.isNotBlank() && isInstalled }.orEmpty()

    fun allows(deviceName: String): Boolean = DeviceInfo.inSameRoom(effectiveRoom, deviceName)

    @Synchronized
    fun setRoom(value: String) {
        if (isInstalled) PrefsHelper.boundRoom = value.trim()
    }

    @Synchronized
    fun install(manifest: PluginManifest): Boolean {
        if (manifest.id != InstalledPlugins.ROOM_BINDING) return false
        val serialized = PluginRepository.serialize(manifest)
        if (PluginRepository.readInstalled(serialized) != manifest) return false
        if (installed?.let { !PluginRepository.isUpdate(it, manifest) } == true) return false
        PrefsHelper.updateRoomBindingManifest(serialized)
        return true
    }

    @Synchronized
    fun uninstall(): Boolean {
        PrefsHelper.updateRoomBindingManifest(null)
        return true
    }

    @Synchronized
    fun migrateLegacyBinding() {
        if (PrefsHelper.boundRoom.isNotBlank() && PrefsHelper.roomBindingManifest == null) {
            PrefsHelper.updateRoomBindingManifest(PluginRepository.serialize(PluginRepository.legacyRoomBinding()))
        }
    }
}
