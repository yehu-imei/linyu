package com.hualala.linyu.data

import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.getVerificationCodeSafe
import com.hualala.linyu.api.loginSafe
import com.hualala.linyu.api.registerAndLoginSafe
import com.hualala.linyu.model.LoginData
import com.hualala.linyu.utils.MD5Utils
import com.hualala.linyu.utils.PrefsHelper
import com.hualala.linyu.utils.SignUtils

object AuthRepository {
    suspend fun login(phone: String, passwordRaw: String): Result<LoginData> {
        return try {
            val encryptedPassword = MD5Utils.encryptPassword(passwordRaw)
            val response = NetworkModule.apiService.loginSafe(
                telephone = phone,
                password = encryptedPassword
            )
            if (response.success && response.data != null) {
                val data = response.data
                NetworkModule.updateAuth(
                    loginCode = data.loginCode,
                    userId = data.userId.toString(),
                    accountId = data.userAccount.accountId.toString(),
                    projectId = data.userAccount.projectId.toString(),
                    telephone = phone
                )
                // 持久化
                PrefsHelper.saveAuth(
                    data.loginCode, data.userId.toString(),
                    data.userAccount.accountId.toString(),
                    data.userAccount.projectId.toString(), phone,
                    data.userAccount.name
                )
                Result.success(data)
            } else {
                Result.failure(Exception(response.displayMessage ?: "登录失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 发短信验证码。
     *
     * `typeId` 是验证码的用途，官方按这个值决定发什么模板、校验时怎么比对：
     * - `3` = 登录 / 注册（默认，不需要登录态）
     * - `5` = 更换手机号 —— **号要填「新」手机号**，不是当前绑定的那个。
     *   抓包实测：换号时 `telephone=新号&typeId=5`，旧号只出现在认证参数里
     *   （`telPhone`）。发到旧号用户根本收不到，会一直提示验证码错误。
     */
    suspend fun sendSmsCode(phone: String, typeId: Int = 3): Result<Unit> {
        return try {
            // secret 按手机号动态计算（官方算法），所有人可用
            val secret = SignUtils.smsSecret(phone)
            val resp = NetworkModule.apiService.getVerificationCodeSafe(phone, secret, typeId)
            if (resp.success) Result.success(Unit)
            else Result.failure(Exception(resp.displayMessage ?: "发送失败"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun smsLogin(phone: String, smsCode: String): Result<LoginData> {
        return try {
            val response = NetworkModule.apiService.registerAndLoginSafe(phone, smsCode)
            if (response.success && response.data != null) {
                val data = response.data
                NetworkModule.updateAuth(data.loginCode, data.userId.toString(),
                    data.userAccount.accountId.toString(), data.userAccount.projectId.toString(), phone)
                PrefsHelper.saveAuth(data.loginCode, data.userId.toString(),
                    data.userAccount.accountId.toString(), data.userAccount.projectId.toString(), phone,
                    data.userAccount.name)
                Result.success(data)
            } else {
                Result.failure(Exception(response.displayMessage ?: "登录失败"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }
}
