package com.hualala.linyu.data

import com.google.gson.Gson
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.utils.PrefsHelper

enum class HomeDeviceSection(val title: String) {
    LAST_USED("上次使用设备"),
    FAVORITES("常用设备"),
    NEARBY("附近设备")
}

/** 收藏保存已识别的设备信息，换名字或改变扫描排序不影响同一设备的识别。 */
data class FavoriteDevice(val projectId: String?, val device: DeviceInfo) {
    private val addressKey: String
        get() = MacFormat.normalize(device.macAddress) ?: device.macAddress.trim().uppercase()

    val key: String get() = if (projectId == null) "pending:${device.snCode}:$addressKey" else "$projectId:${device.snCode}"

    fun matches(other: FavoriteDevice): Boolean {
        if (projectId != null && other.projectId != null) return key == other.key
        return device.snCode == other.device.snCode && addressKey == other.addressKey
    }

    companion object {
        fun from(device: DeviceInfo, defaultProjectId: String): FavoriteDevice? {
            if (!device.hasDeviceIdentity) return null
            val projectId = device.projectId?.takeIf { it > 0 }?.toString()
                ?: defaultProjectId.takeIf { !device.isDrinkingWater && (it.toLongOrNull() ?: 0) > 0 }
            return FavoriteDevice(projectId, device)
        }
    }
}

data class HomeDeviceLayout(
    val favorites: List<FavoriteDevice> = emptyList(),
    private val hiddenSections: Set<HomeDeviceSection> = emptySet(),
    private val sectionOrder: List<HomeDeviceSection?>? = null
) {
    val orderedSections: List<HomeDeviceSection>
        get() = (sectionOrder.orEmpty().filterNotNull() + HomeDeviceSection.entries).distinct()

    fun isVisible(section: HomeDeviceSection): Boolean = section !in hiddenSections

    fun toggleSection(section: HomeDeviceSection): HomeDeviceLayout =
        copy(hiddenSections = if (isVisible(section)) hiddenSections + section else hiddenSections - section)

    fun moveSection(section: HomeDeviceSection, delta: Int): HomeDeviceLayout {
        if (delta != -1 && delta != 1) return this
        val order = orderedSections.toMutableList()
        val index = order.indexOf(section)
        val target = index + delta
        if (target !in order.indices) return this
        order.add(target, order.removeAt(index))
        return copy(sectionOrder = order)
    }

    fun findFavorite(favorite: FavoriteDevice): FavoriteDevice? =
        favorites.firstOrNull { it.key == favorite.key } ?: favorites.singleOrNull { it.matches(favorite) }

    fun toggleFavorite(favorite: FavoriteDevice): HomeDeviceLayout =
        findFavorite(favorite)?.let { removeFavorite(it.key) } ?: copy(favorites = favorites + favorite)

    fun confirmFavorite(previous: FavoriteDevice, resolved: FavoriteDevice): HomeDeviceLayout {
        if (previous.projectId != null || resolved.device.projectId?.takeIf { it > 0 } == null ||
            !previous.matches(resolved)
        ) return this
        return copy(favorites = favorites.map { if (it.key == previous.key) resolved else it }.distinctBy { it.key })
    }

    fun removeFavorite(key: String): HomeDeviceLayout = copy(favorites = favorites.filterNot { it.key == key })

    fun moveFavorite(key: String, delta: Int): HomeDeviceLayout {
        if (delta != -1 && delta != 1) return this
        val index = favorites.indexOfFirst { it.key == key }
        val target = index + delta
        if (index < 0 || target !in favorites.indices) return this
        val reordered = favorites.toMutableList()
        reordered.add(target, reordered.removeAt(index))
        return copy(favorites = reordered)
    }
}

/** 只保存本机首页配置。账号退出后仍以该账号的键保存，重新登录即可恢复。 */
object HomeDevicePreferences {
    private val gson = Gson()

    fun load(owner: String): HomeDeviceLayout {
        if (!ownsPreferences(owner)) return HomeDeviceLayout()
        return runCatching {
            val saved = gson.fromJson(PrefsHelper.homeDeviceLayoutJson(owner), HomeDeviceLayout::class.java)
                ?: return HomeDeviceLayout()
            val favorites = saved.favorites.mapNotNull { entry ->
                FavoriteDevice.from(entry.device, PrefsHelper.projectId)?.takeIf { it.projectId == entry.projectId }
            }.distinctBy { it.key }
            saved.copy(favorites = favorites)
        }.getOrDefault(HomeDeviceLayout())
    }

    fun save(owner: String, layout: HomeDeviceLayout) {
        if (ownsPreferences(owner)) PrefsHelper.saveHomeDeviceLayoutJson(owner, gson.toJson(layout))
    }

    private fun ownsPreferences(owner: String): Boolean =
        PrefsHelper.isInitialized && PrefsHelper.isLoggedIn && PrefsHelper.userId.isNotBlank() &&
            owner == "${PrefsHelper.userId}:${PrefsHelper.projectId}:${PrefsHelper.accountId}"
}
