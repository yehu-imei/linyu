package com.hualala.linyu.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LoginSecretPolicyTest {
    @Test
    fun `password request clears password but keeps phone and sms code`() {
        val fields = LoginFields(phone = "13800000000", password = "secret", smsCode = "123456")

        assertEquals(
            LoginFields(phone = "13800000000", password = "", smsCode = "123456"),
            fields.afterPasswordRequest()
        )
    }

    @Test
    fun `sms request clears sms code but keeps phone and password`() {
        val fields = LoginFields(phone = "13800000000", password = "secret", smsCode = "123456")

        assertEquals(
            LoginFields(phone = "13800000000", password = "secret", smsCode = ""),
            fields.afterSmsRequest()
        )
    }
}
