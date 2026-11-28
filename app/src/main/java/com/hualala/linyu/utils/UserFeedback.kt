package com.hualala.linyu.utils

/** Only for error presentation. Keep raw responses for logging and business decisions. */
object UserFeedback {
    fun smsSendMessage(value: String?): String {
        val fallback = "验证码发送失败，请重试"
        val reason = message(value, fallback)
        val text = reason.replace("登陆", "登录")
        // 发码请求没有登录凭证，会话状态要由当前账号和认证请求判断。
        if (text in setOf("发送失败", "验证码发送失败", "请求失败", "请求失败，请稍后重试") ||
            listOf("未登录", "请登录", "请先登录", "请重新登录", "登录对应账号",
                "登录已失效", "登录失效", "登录已过期", "登录过期", "登录超时",
                "在别处登录", "在其他地方登录", "在其他设备登录", "在其它设备登录", "在另一台设备登录")
                .any { text.contains(it) }) return fallback
        val refersToSession = listOf("登录状态", "登录会话", "登录凭证", "登录信息", "令牌", "会话")
            .any { text.contains(it) }
        return if (refersToSession && listOf("失效", "过期", "无效", "不存在", "认证失败", "校验失败")
                .any { text.contains(it) }) fallback else reason
    }

    fun message(value: String?, fallback: String = "请求失败，请稍后重试"): String {
        val text = value?.trim().orEmpty()
        if (text == "1天只能领取一次使用码") return "每天只能领取一次使用码"
        val lower = text.lowercase()
        if (listOf("timeout", "timed out").any { lower.contains(it) }) {
            return "连接超时，请重试"
        }
        if (listOf("unable to resolve host", "no address associated", "network is unreachable",
                "offline", "internet connection", "failed to connect", "connection was lost")
                .any { lower.contains(it) }) {
            return "网络连接失败，请检查网络"
        }
        // Do not expose English exception names or mixed Chinese/English stack details.
        return if (text.any { it in '\u4e00'..'\u9fff' } &&
            text.none { it in 'a'..'z' || it in 'A'..'Z' } && text.length <= 120 &&
            !text.contains('\n') && !text.contains('\r')) text else fallback
    }
}
