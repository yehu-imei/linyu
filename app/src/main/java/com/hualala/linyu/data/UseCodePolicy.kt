package com.hualala.linyu.data

import java.time.LocalDate

/** 与 iOS UseCodeQuotaStore 相同：只保存服务端额度，按账户、项目和自然日隔离。 */
data class UseCodeQuota(val remaining: Int, val date: String)

object UseCodePolicy {
    const val EXHAUSTED_MESSAGE = "今日换码次数已用完，请明天再来"

    fun remainingToday(quota: UseCodeQuota?, today: LocalDate = LocalDate.now()): Int? =
        quota?.takeIf { it.date == today.toString() }?.remaining?.coerceAtLeast(0)

    fun blockReason(resetAvailability: Int?, remaining: Int?): String? = when {
        resetAvailability == 0 -> "每天只能领取一次使用码"
        remaining == 0 -> EXHAUSTED_MESSAGE
        else -> null
    }

    fun canGenerate(remaining: Int?, busy: Boolean): Boolean = remaining != 0 && !busy

    // 最后一次生成后 remaining=0，仍可领取手里的有效候选码。
    fun canClaim(candidate: String?, secondsLeft: Int, busy: Boolean): Boolean =
        !candidate.isNullOrBlank() && secondsLeft > 0 && !busy

    fun quotaFromResponse(remaining: Int?, errorCode: Int, today: LocalDate = LocalDate.now()): UseCodeQuota? =
        (if (errorCode == 79) 0 else remaining)?.let { UseCodeQuota(it.coerceAtLeast(0), today.toString()) }
}
