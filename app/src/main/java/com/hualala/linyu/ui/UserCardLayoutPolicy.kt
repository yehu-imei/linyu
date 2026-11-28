package com.hualala.linyu.ui

internal object UserCardLayoutPolicy {
    data class CardPages(
        val profile: List<UserCardType>,
        val settings: List<UserCardType>,
        val features: List<UserCardType>
    )

    val profileDefaults = listOf(
        UserCardType.ACCOUNT,
        UserCardType.USE_CODE,
        UserCardType.BOUND_ROOM
    )

    val settingsDefaults = listOf(
        UserCardType.FEATURE_MANAGEMENT,
        UserCardType.PLUGIN_MANAGEMENT,
        UserCardType.BACKGROUND,
        UserCardType.LOG,
        UserCardType.ABOUT,
        UserCardType.UPDATE
    )

    val featureDefaults = listOf(
        UserCardType.NOTIFY,
        UserCardType.TREND,
        UserCardType.KEEP_SCREEN_ON
    )

    fun restorePages(
        profileSaved: String,
        settingsSaved: String,
        legacySaved: String,
        featureSaved: String?
    ): CardPages {
        val group = UserCardType.FEATURE_MANAGEMENT
        val useLegacy = profileSaved.isBlank() && settingsSaved.isBlank() && legacySaved.isNotBlank()
        val legacy = if (useLegacy) parse(legacySaved) else emptyList()
        val profileSource = if (useLegacy) legacy.filter { it in profileDefaults } else parse(profileSaved)
        val settingsSource = if (useLegacy) legacy.filter { it !in profileDefaults } else parse(settingsSaved)
        val profile = profileSource.filterNot { it == group }.distinct().toMutableList()
        val availableSettings = settingsSource.filterNot { it in profile }
        val savedFeatures = featureSaved?.let(::parse).orEmpty()
        val features = if (featureSaved == null) {
            // Carry over the existing Settings order without pulling explicitly moved cards out of Profile.
            val formerSettings = availableSettings.filter { it in featureDefaults }
            (formerSettings + featureDefaults).distinct().filterNot { it in profile }
        } else {
            savedFeatures.filter { it in featureDefaults && it !in profile }.distinct()
        }
        val settings = availableSettings
            .mapNotNull {
                when {
                    it !in features -> it
                    featureSaved == null -> group
                    else -> null
                }
            }
            .distinct()
            .toMutableList()
        val movedOut = savedFeatures
            .filter { it == UserCardType.BACKGROUND || it == UserCardType.LOG }
            .filterNot { it in profile || it in settings }
            .distinct()
        settings.addAll(settings.indexOf(group) + 1, movedOut)
        val assigned = (profile + settings + features).toMutableSet()

        profileDefaults.filterTo(profile) { assigned.add(it) }
        settingsDefaults.filterTo(settings) { assigned.add(it) }
        featureDefaults.filterTo(settings) { assigned.add(it) }
        return CardPages(profile, settings, features)
    }

    fun moveToSettings(type: UserCardType, pages: CardPages): CardPages = CardPages(
        profile = pages.profile - type,
        settings = pages.settings - type + type,
        features = pages.features - type
    )

    fun moveToProfile(type: UserCardType, pages: CardPages): CardPages =
        if (type == UserCardType.FEATURE_MANAGEMENT) pages else CardPages(
            profile = pages.profile - type + type,
            settings = pages.settings - type,
            features = pages.features - type
        )

    fun moveToFeatures(type: UserCardType, pages: CardPages): CardPages =
        if (type !in featureDefaults) pages else CardPages(
            profile = pages.profile - type,
            settings = pages.settings - type,
            features = pages.features - type + type
        )

    fun swapCards(order: List<UserCardType>, first: UserCardType, second: UserCardType): List<UserCardType> {
        val firstIndex = order.indexOf(first)
        val secondIndex = order.indexOf(second)
        if (firstIndex == -1 || secondIndex == -1 || firstIndex == secondIndex) return order
        return order.toMutableList().apply {
            this[firstIndex] = second
            this[secondIndex] = first
        }
    }

    private fun parse(saved: String): List<UserCardType> = saved.split(',')
        .mapNotNull { value -> UserCardType.entries.firstOrNull { it.name == value.trim() } }
}
