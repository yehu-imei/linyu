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
}
