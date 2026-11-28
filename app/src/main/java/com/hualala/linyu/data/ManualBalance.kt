package com.hualala.linyu.data

/** A local estimate baseline. Amount and timestamp are written as a single value. */
internal data class ManualBalance(val amount: String, val enteredAt: Long) {
    fun encode(): String = "$enteredAt|$amount"

    fun estimate(entries: List<Pair<Long, Double>>): Double =
        amount.toDouble() - entries.filter { it.first > enteredAt }.sumOf { it.second }

    companion object {
        fun key(owner: String): String? = owner.takeIf { value ->
            val parts = value.split(':')
            parts.size == 3 && parts.all { it.isNotBlank() && it != "0" }
        }?.let { "manualBalance.v2.$it" }

        fun create(amount: String, enteredAt: Long): ManualBalance? {
            val text = amount.trim()
            return if (enteredAt > 0 && text.toDoubleOrNull()?.isFinite() == true) ManualBalance(text, enteredAt)
            else null
        }

        fun decode(value: String): ManualBalance? {
            val parts = value.split('|', limit = 2)
            val time = parts.first().toLongOrNull() ?: return null
            return create(parts.getOrNull(1) ?: return null, time)
        }
    }
}
