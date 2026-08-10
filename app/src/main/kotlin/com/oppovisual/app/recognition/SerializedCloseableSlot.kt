package com.oppovisual.app.recognition

import java.io.Closeable

/** Serializes resource use with replacement and shutdown. */
internal class SerializedCloseableSlot<T : Closeable> {
    private val monitor = Any()
    private var resource: T? = null

    fun isPresent(): Boolean = synchronized(monitor) { resource != null }

    fun install(value: T): Boolean = synchronized(monitor) {
        if (resource != null) return@synchronized false
        resource = value
        true
    }

    fun useIfPresent(block: (T) -> Unit): Boolean = synchronized(monitor) {
        val current = resource ?: return@synchronized false
        block(current)
        true
    }

    fun closeAndClear() {
        synchronized(monitor) {
            val current = resource ?: return
            resource = null
            current.close()
        }
    }
}
