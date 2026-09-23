package com.hualala.linyu.ui

internal object UserCardLayoutPolicy {
    val profileDefaults = listOf(
        UserCardType.ACCOUNT,
        UserCardType.USE_CODE,
        UserCardType.BOUND_ROOM
    )

    val settingsDefaults = listOf(
        UserCardType.NOTIFY,
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
}
