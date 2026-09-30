package com.hualala.linyu.ui

internal object UserCardLayoutPolicy {
    data class CardPages(
        val profile: List<UserCardType>,
        val settings: List<UserCardType>
    )

    val profileDefaults = listOf(
        UserCardType.ACCOUNT,
        UserCardType.USE_CODE,
        UserCardType.BOUND_ROOM
    )

    val settingsDefaults = listOf(
        UserCardType.NOTIFY,
        UserCardType.TREND,
        UserCardType.BACKGROUND,
        UserCardType.LOG,
        UserCardType.ABOUT,
        UserCardType.UPDATE
    )

    fun restore(defaults: List<UserCardType>, saved: String): List<UserCardType> {
        val allowed = defaults.toSet()
        val restored = saved.split(',')
            .mapNotNull { value ->
                UserCardType.entries.firstOrNull { it.name == value.trim() }
            }
            .filter { it in allowed }
        return (restored + defaults).distinct()
    }

    fun restorePages(
        profileSaved: String,
        settingsSaved: String,
        legacySaved: String = ""
    ): CardPages {
        val known = (profileDefaults + settingsDefaults).toSet()
        val useLegacy = profileSaved.isBlank() && settingsSaved.isBlank() && legacySaved.isNotBlank()
        val legacy = if (useLegacy) parse(legacySaved) else emptyList()
        val profileSource = if (useLegacy) legacy.filter { it in profileDefaults } else parse(profileSaved)
        val settingsSource = if (useLegacy) legacy.filter { it in settingsDefaults } else parse(settingsSaved)
        val profile = profileSource.filter { it in known }.distinct().toMutableList()
        val settings = settingsSource
            .filter { it in known && it !in profile }
            .distinct()
            .toMutableList()
        val assigned = (profile + settings).toMutableSet()

        profileDefaults.filterTo(profile) { assigned.add(it) }
        settingsDefaults.filterTo(settings) { assigned.add(it) }
        return CardPages(profile, settings)
    }

    fun moveToSettings(
        type: UserCardType,
        profile: List<UserCardType>,
        settings: List<UserCardType>
    ): CardPages = CardPages(
        profile = profile.filterNot { it == type },
        settings = settings.filterNot { it == type } + type
    )

    fun moveToProfile(
        type: UserCardType,
        profile: List<UserCardType>,
        settings: List<UserCardType>
    ): CardPages = CardPages(
        profile = profile.filterNot { it == type } + type,
        settings = settings.filterNot { it == type }
    )

    private fun parse(saved: String): List<UserCardType> = saved.split(',')
        .mapNotNull { value -> UserCardType.entries.firstOrNull { it.name == value.trim() } }
}
