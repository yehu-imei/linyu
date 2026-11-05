package com.hualala.linyu.api

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Immutable credentials carried by a request or a device operation across project switches. */
data class ProjectSession(val fields: Map<String, String>) {
    val projectId: String get() = fields["projectId"].orEmpty()
    val loginCode: String get() = fields["loginCode"].orEmpty()
    val telephone: String get() = fields["telephone"].orEmpty()

    fun contextElement() = current.asContextElement(this)

    suspend fun <T> run(block: suspend () -> T): T =
        withContext(contextElement()) { block() }

    fun <T> read(block: () -> T): T {
        val previous = current.get()
        current.set(this)
        return try { block() } finally { current.set(previous) }
    }

    companion object {
        internal val current = ThreadLocal<ProjectSession?>()
    }
}
