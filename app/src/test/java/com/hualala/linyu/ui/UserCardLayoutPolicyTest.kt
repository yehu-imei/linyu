package com.hualala.linyu.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UserCardLayoutPolicyTest {
    @Test
    fun `profile page has three default cards`() {
        assertEquals(
            listOf(UserCardType.ACCOUNT, UserCardType.USE_CODE, UserCardType.BOUND_ROOM),
            UserCardLayoutPolicy.profileDefaults
        )
    }

    @Test
    fun `settings page uses requested default order`() {
        assertEquals(
            listOf(
                UserCardType.NOTIFY,
                UserCardType.TREND,
                UserCardType.BACKGROUND,
                UserCardType.LOG,
                UserCardType.ABOUT,
                UserCardType.UPDATE
            ),
            UserCardLayoutPolicy.settingsDefaults
        )
    }

    @Test
    fun `saved order is normalized within its own page`() {
        assertEquals(
            listOf(UserCardType.BOUND_ROOM, UserCardType.ACCOUNT, UserCardType.USE_CODE),
            UserCardLayoutPolicy.restore(
                UserCardLayoutPolicy.profileDefaults,
                "BOUND_ROOM,ACCOUNT"
            )
        )
        assertEquals(
            UserCardLayoutPolicy.settingsDefaults,
            UserCardLayoutPolicy.restore(UserCardLayoutPolicy.settingsDefaults, "ACCOUNT,USE_CODE")
        )
    }

    @Test
    fun `moving a profile card appends it to settings without duplication`() {
        val result = UserCardLayoutPolicy.moveToSettings(
            UserCardType.ACCOUNT,
            UserCardLayoutPolicy.profileDefaults,
            UserCardLayoutPolicy.settingsDefaults
        )

        assertEquals(listOf(UserCardType.USE_CODE, UserCardType.BOUND_ROOM), result.profile)
        assertEquals(UserCardLayoutPolicy.settingsDefaults + UserCardType.ACCOUNT, result.settings)
    }

    @Test
    fun `moving a settings card appends it to profile without duplication`() {
        val result = UserCardLayoutPolicy.moveToProfile(
            UserCardType.NOTIFY,
            UserCardLayoutPolicy.profileDefaults,
            UserCardLayoutPolicy.settingsDefaults
        )

        assertEquals(UserCardLayoutPolicy.profileDefaults + UserCardType.NOTIFY, result.profile)
        assertEquals(UserCardLayoutPolicy.settingsDefaults - UserCardType.NOTIFY, result.settings)
    }

    @Test
    fun `restoring pages keeps cards on their selected page`() {
        val result = UserCardLayoutPolicy.restorePages(
            profileSaved = "USE_CODE,BOUND_ROOM",
            settingsSaved = "NOTIFY,TREND,BACKGROUND,LOG,ABOUT,UPDATE,ACCOUNT"
        )

        assertEquals(listOf(UserCardType.USE_CODE, UserCardType.BOUND_ROOM), result.profile)
        assertEquals(UserCardLayoutPolicy.settingsDefaults + UserCardType.ACCOUNT, result.settings)
    }

    @Test
    fun `legacy single page order is partitioned while preserving relative order`() {
        val result = UserCardLayoutPolicy.restorePages(
            profileSaved = "",
            settingsSaved = "",
            legacySaved = "LOG,BOUND_ROOM,ACCOUNT,NOTIFY,USE_CODE,BACKGROUND,UPDATE,ABOUT"
        )

        assertEquals(
            listOf(UserCardType.BOUND_ROOM, UserCardType.ACCOUNT, UserCardType.USE_CODE),
            result.profile
        )
        assertEquals(
            listOf(UserCardType.LOG, UserCardType.NOTIFY, UserCardType.BACKGROUND, UserCardType.UPDATE, UserCardType.ABOUT, UserCardType.TREND),
            result.settings
        )
    }
}
