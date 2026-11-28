package com.hualala.linyu.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hualala.linyu.data.AuthRepository
import com.hualala.linyu.model.LoginData
import com.hualala.linyu.utils.AppLogger
import com.hualala.linyu.utils.PrefsHelper
import com.hualala.linyu.utils.UserFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginViewModel : ViewModel() {
    var phone by mutableStateOf("")
        private set
    var password by mutableStateOf("")
    var smsCode by mutableStateOf("")
    /**
     * 默认走手机号验证码。
     *
     * 验证码登录**没注册过会自动注册**，新用户不用先去别处开账号；而密码登录
     * 要求用户早就设过密码——对第一次用的人来说是死路。所以默认给验证码。
     */
    var isSmsMode by mutableStateOf(true)
    var rememberPassword by mutableStateOf(false)
        private set
    private var savedCredential: PrefsHelper.SavedPasswordLogin? = null
    private var loadedCredential = false

    var isLoading by mutableStateOf(false)
    var isSendingCode by mutableStateOf(false)
        private set
    var countdown by mutableStateOf(0)
        private set
    var loginResult by mutableStateOf<Result<LoginData>?>(null)
    var errorMessage by mutableStateOf<String?>(null)
    val canEditFields: Boolean get() = !isLoading && loginResult?.isSuccess != true

    private val smsRequests = LoginSmsRequests()
    private var smsFormRevision = 0L
    private var countdownJob: Job? = null

    fun restorePasswordLogin() {
        if (loadedCredential) return
        loadedCredential = true
        val saved = PrefsHelper.loadSavedPasswordLogin() ?: return
        savedCredential = saved
        if (phone.isNotEmpty() || password.isNotEmpty()) return
        phone = saved.phone
        password = saved.password
        isSmsMode = false
        rememberPassword = true
    }

    fun updatePhone(value: String) {
        if (!canEditFields || phone == value) return
        val previousPhone = phone
        phone = value
        smsCode = ""
        errorMessage = null
        smsFormRevision++
        refreshSmsCountdown()
        val savedForPhone = savedCredential?.takeIf { it.phone == value }
        if (!isSmsMode) {
            if (savedForPhone != null) password = savedForPhone.password
            else if (previousPhone.length == 11) password = ""
        }
        if (savedCredential?.phone == previousPhone || savedForPhone != null) {
            rememberPassword = savedForPhone != null
        }
    }

    fun setRememberedPasswordEnabled(enabled: Boolean) {
        if (!canEditFields) return
        if (!enabled && savedCredential?.phone == phone) {
            if (!PrefsHelper.clearSavedPasswordLogin(phone)) {
                errorMessage = "无法清除本机密码，请重试"
                return
            }
            savedCredential = null
        }
        rememberPassword = enabled
    }

    fun login() {
        if (!canEditFields) return
        if (phone.isBlank()) return
        if (phone.length != 11) { errorMessage = "请输入 11 位手机号"; return }
        if (isSmsMode) {
            if (smsCode.isBlank()) return
            smsLogin()
        } else {
            if (password.isBlank()) return
            passwordLogin()
        }
    }

    private fun passwordLogin() {
        val submittedPhone = phone
        val submittedPassword = password
        val shouldRemember = rememberPassword
        viewModelScope.launch {
            isLoading = true; errorMessage = null
            try {
                val result = AuthRepository.login(submittedPhone, submittedPassword)
                if (result.isSuccess) {
                    if (shouldRemember) {
                        val saved = withContext(Dispatchers.IO) {
                            PrefsHelper.savePasswordLogin(submittedPhone, submittedPassword)
                        }
                        if (!saved) {
                            AppLogger.w("无法在本机保存密码")
                            forgetOtherAccount(submittedPhone)
                        }
                    } else {
                        forgetOtherAccount(submittedPhone)
                    }
                }
                loginResult = result
                if (result.isFailure) {
                    val msg = result.exceptionOrNull()?.message ?: "登录失败"
                    errorMessage = when {
                        msg.contains("Unable to resolve host", ignoreCase = true) ||
                        msg.contains("No address associated", ignoreCase = true) ||
                        msg.contains("Network is unreachable", ignoreCase = true) -> "网络连接失败，请检查网络"
                        msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true) ->
                            "连接超时，请重试"
                        else -> msg
                    }
                }
            } finally {
                // 成功时由登录页先提交系统自动填充会话，再立即清空内存中的输入。
                if (loginResult?.isSuccess != true) password = LoginSecretPolicy.clearPassword(password)
                isLoading = false
            }
        }
    }

    private fun smsLogin() {
        val submittedPhone = phone
        viewModelScope.launch {
            isLoading = true; errorMessage = null
            try {
                val result = AuthRepository.smsLogin(submittedPhone, smsCode)
                if (result.isSuccess) forgetOtherAccount(submittedPhone)
                loginResult = result
                if (result.isFailure) {
                    errorMessage = result.exceptionOrNull()?.message ?: "登录失败"
                }
            } finally {
                smsCode = LoginSecretPolicy.clearSmsCode(smsCode)
                isLoading = false
            }
        }
    }

    private suspend fun forgetOtherAccount(submittedPhone: String) {
        val saved = savedCredential ?: return
        if (saved.phone != submittedPhone) {
            withContext(Dispatchers.IO) { PrefsHelper.clearSavedPasswordLogin(saved.phone) }
        }
    }

    fun sendSmsCode() {
        if (!canEditFields || !isSmsMode || phone.isBlank()) return
        if (phone.length != 11) { errorMessage = "请输入 11 位手机号"; return }

        val request = smsRequests.begin(phone, smsFormRevision, SystemClock.elapsedRealtime()) ?: return
        errorMessage = null
        refreshSmsCountdown()

        viewModelScope.launch {
            val result = AuthRepository.sendSmsCode(request.phone)
            if (!smsRequests.complete(request, result.isSuccess)) return@launch
            if (result.isFailure && request.matches(phone, smsFormRevision) && isSmsMode && canEditFields) {
                errorMessage = UserFeedback.smsSendMessage(result.exceptionOrNull()?.message)
            }
            if (request.phone == phone) refreshSmsCountdown()
        }
    }

    private fun refreshSmsCountdown() {
        fun update() {
            val status = if (isSmsMode) smsRequests.status(phone, SystemClock.elapsedRealtime())
                else LoginSmsRequests.Status()
            isSendingCode = status.pending
            countdown = status.secondsLeft
        }
        countdownJob?.cancel()
        update()
        countdownJob = if (countdown > 0) viewModelScope.launch {
            while (countdown > 0) { delay(1_000); update() }
        } else null
    }

    fun switchMode() {
        if (!canEditFields) return
        isSmsMode = !isSmsMode
        errorMessage = null
        smsFormRevision++
        refreshSmsCountdown()
        password = if (isSmsMode) "" else savedCredential?.takeIf { it.phone == phone }?.password.orEmpty()
        smsCode = ""
    }
    fun resetResult() {
        smsRequests.reset()
        smsFormRevision++
        password = LoginSecretPolicy.clearPassword(password)
        smsCode = ""
        phone = ""
        isSmsMode = true
        rememberPassword = false
        savedCredential = null
        loadedCredential = false
        loginResult = null
        refreshSmsCountdown()
    }
}
