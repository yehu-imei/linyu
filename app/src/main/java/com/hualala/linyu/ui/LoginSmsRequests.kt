package com.hualala.linyu.ui

/** Owns pending sends and resend deadlines per recipient for one login screen session. */
internal class LoginSmsRequests {
    class Request(val phone: String, private val formRevision: Long) {
        fun matches(phone: String, formRevision: Long): Boolean =
            this.phone == phone && this.formRevision == formRevision
    }

    data class Status(val pending: Boolean = false, val secondsLeft: Int = 0)

    private data class Entry(val request: Request, val deadlineMs: Long, val pending: Boolean = true)

    private val entries = mutableMapOf<String, Entry>()

    fun begin(phone: String, formRevision: Long, nowMs: Long): Request? {
        entries.values.removeAll { !it.pending && it.deadlineMs <= nowMs }
        if (entries.containsKey(phone)) return null
        val request = Request(phone, formRevision)
        entries[phone] = Entry(request, nowMs + 60_000)
        return request
    }

    fun complete(request: Request, succeeded: Boolean): Boolean {
        val entry = entries[request.phone] ?: return false
        if (entry.request !== request || !entry.pending) return false
        if (succeeded) entries[request.phone] = entry.copy(pending = false)
        else entries.remove(request.phone)
        return true
    }

    fun status(phone: String, nowMs: Long): Status {
        val entry = entries[phone] ?: return Status()
        val remainingMs = (entry.deadlineMs - nowMs).coerceAtLeast(0)
        return Status(entry.pending, ((remainingMs + 999) / 1_000).toInt())
    }

    fun reset() = entries.clear()
}
