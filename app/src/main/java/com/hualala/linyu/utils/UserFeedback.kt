package com.hualala.linyu.utils

/** Only for error presentation. Keep raw responses for logging and business decisions. */
object UserFeedback {
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
