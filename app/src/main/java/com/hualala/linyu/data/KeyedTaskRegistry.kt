package com.hualala.linyu.data

class KeyedTaskRegistry<T : Any> {
    private val tasks = mutableMapOf<String, T>()

    @Synchronized
    fun getOrStart(key: String, starter: () -> T): T = tasks.getOrPut(key, starter)

    @Synchronized
    fun remove(key: String, expected: T): Boolean {
        if (tasks[key] !== expected) return false
        tasks.remove(key)
        return true
    }

    @Synchronized
    fun remove(key: String): T? = tasks.remove(key)

    @Synchronized
    fun isEmpty(): Boolean = tasks.isEmpty()

    @Synchronized
    fun size(): Int = tasks.size
}
