package com.hualala.linyu.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `new layouts group switches and keep action cards outside`() {
        val result = UserCardLayoutPolicy.restorePages("", "", "", null)
        assertEquals(listOf(
            UserCardType.FEATURE_MANAGEMENT, UserCardType.PLUGIN_MANAGEMENT,
            UserCardType.BACKGROUND, UserCardType.LOG,
            UserCardType.ABOUT, UserCardType.UPDATE
        ), result.settings)
        assertEquals(listOf(
            UserCardType.NOTIFY,
            UserCardType.TREND,
            UserCardType.KEEP_SCREEN_ON
        ), result.features)
    }

    @Test
    fun `moving a profile card appends it to settings without duplication`() {
        val defaults = UserCardLayoutPolicy.restorePages("", "", "", null)
        val result = UserCardLayoutPolicy.moveToSettings(UserCardType.ACCOUNT, defaults)

        assertEquals(listOf(UserCardType.USE_CODE, UserCardType.BOUND_ROOM), result.profile)
        assertEquals(UserCardLayoutPolicy.settingsDefaults + UserCardType.ACCOUNT, result.settings)
        assertEquals(defaults.features, result.features)
    }

    @Test
    fun `moving a card out then to profile before restart keeps it out of group`() {
        val defaults = UserCardLayoutPolicy.restorePages("", "", "", null)
        val movedOut = UserCardLayoutPolicy.moveToSettings(UserCardType.NOTIFY, defaults)
        assertEquals(UserCardLayoutPolicy.settingsDefaults + UserCardType.NOTIFY, movedOut.settings)
        val result = UserCardLayoutPolicy.moveToProfile(UserCardType.NOTIFY, movedOut)
        assertEquals(UserCardLayoutPolicy.profileDefaults + UserCardType.NOTIFY, result.profile)
        assertEquals(UserCardLayoutPolicy.settingsDefaults, result.settings)
        assertEquals(defaults.features - UserCardType.NOTIFY, result.features)
        assertEquals(result, reload(result))
    }

    @Test
    fun `old layout keeps profile overrides and settings position while grouping settings features`() {
        val result = UserCardLayoutPolicy.restorePages(
            profileSaved = "USE_CODE,BOUND_ROOM,LOG",
            settingsSaved = "ABOUT,NOTIFY,TREND,BACKGROUND,UPDATE,ACCOUNT",
            legacySaved = "",
            featureSaved = null
        )

        assertEquals(listOf(UserCardType.USE_CODE, UserCardType.BOUND_ROOM, UserCardType.LOG), result.profile)
        assertEquals(listOf(
            UserCardType.ABOUT, UserCardType.FEATURE_MANAGEMENT, UserCardType.BACKGROUND,
            UserCardType.UPDATE, UserCardType.ACCOUNT, UserCardType.PLUGIN_MANAGEMENT
        ), result.settings)
        assertEquals(UserCardLayoutPolicy.featureDefaults, result.features)
    }

    @Test
    fun `legacy single page order is partitioned while preserving relative order`() {
        val result = UserCardLayoutPolicy.restorePages(
            profileSaved = "",
            settingsSaved = "",
            legacySaved = "LOG,BOUND_ROOM,ACCOUNT,NOTIFY,USE_CODE,BACKGROUND,UPDATE,ABOUT",
            featureSaved = null
        )

        assertEquals(
            listOf(UserCardType.BOUND_ROOM, UserCardType.ACCOUNT, UserCardType.USE_CODE),
            result.profile
        )
        assertEquals(
            listOf(UserCardType.LOG, UserCardType.FEATURE_MANAGEMENT, UserCardType.BACKGROUND,
                UserCardType.UPDATE, UserCardType.ABOUT, UserCardType.PLUGIN_MANAGEMENT),
            result.settings
        )
        assertEquals(UserCardLayoutPolicy.featureDefaults, result.features)
    }

    @Test
    fun `saved feature order extracts old action cards directly below the switch group`() {
        val result = UserCardLayoutPolicy.restorePages(
            profileSaved = "ACCOUNT,USE_CODE,BOUND_ROOM",
            settingsSaved = "ABOUT,FEATURE_MANAGEMENT,UPDATE",
            legacySaved = "",
            featureSaved = "TREND,LOG,NOTIFY,BACKGROUND,KEEP_SCREEN_ON"
        )
        assertEquals(
            listOf(UserCardType.ABOUT, UserCardType.FEATURE_MANAGEMENT, UserCardType.LOG,
                UserCardType.BACKGROUND, UserCardType.UPDATE, UserCardType.PLUGIN_MANAGEMENT),
            result.settings
        )
        assertEquals(listOf(UserCardType.TREND, UserCardType.NOTIFY, UserCardType.KEEP_SCREEN_ON), result.features)
        assertEquals(result, reload(result))
    }

    @Test
    fun `empty group survives reload and moved feature is only shown once`() {
        val defaults = UserCardLayoutPolicy.restorePages("", "", "", null)
        val emptyGroup = defaults.features.fold(defaults) { pages, type ->
            UserCardLayoutPolicy.moveToSettings(type, pages)
        }
        assertEquals(emptyGroup, reload(emptyGroup))
        assertTrue(emptyGroup.features.isEmpty())

        assertEquals(emptyGroup, UserCardLayoutPolicy.moveToFeatures(UserCardType.BACKGROUND, emptyGroup))
        assertEquals(emptyGroup, UserCardLayoutPolicy.moveToFeatures(UserCardType.LOG, emptyGroup))

        val regrouped = UserCardLayoutPolicy.moveToFeatures(UserCardType.TREND, emptyGroup)
        assertEquals(listOf(UserCardType.TREND), regrouped.features)
        assertEquals(emptyGroup.settings - UserCardType.TREND, regrouped.settings)
        assertEquals(regrouped, reload(regrouped))
    }

    @Test
    fun `uninstalled room position is preserved while other cards move`() {
        val original = listOf(UserCardType.USE_CODE, UserCardType.BOUND_ROOM, UserCardType.ACCOUNT)
        val edited = UserCardLayoutPolicy.swapCards(original, UserCardType.ACCOUNT, UserCardType.USE_CODE)
        assertEquals(listOf(UserCardType.ACCOUNT, UserCardType.BOUND_ROOM, UserCardType.USE_CODE), edited)
    }

    private fun reload(pages: UserCardLayoutPolicy.CardPages) = UserCardLayoutPolicy.restorePages(
        profileSaved = pages.profile.joinToString(",") { it.name },
        settingsSaved = pages.settings.joinToString(",") { it.name },
        legacySaved = "",
        featureSaved = pages.features.joinToString(",") { it.name }
    )
}
