package com.hualala.linyu.ui

internal data class LoginFields(
    val phone: String,
    val password: String,
    val smsCode: String
) {
    fun afterPasswordRequest() = copy(password = LoginSecretPolicy.clearPassword(password))
    fun afterSmsRequest() = copy(smsCode = LoginSecretPolicy.clearSmsCode(smsCode))
}

internal object LoginSecretPolicy {
    fun clearPassword(value: String): String = ""
    fun clearSmsCode(value: String): String = ""
}
