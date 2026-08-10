package com.oppovisual.app.recognition

import java.util.concurrent.atomic.AtomicBoolean

internal class RuntimeFailoverController {
    private val fallbackActive = AtomicBoolean(false)

    val isFallbackActive: Boolean
        get() = fallbackActive.get()

    fun <T> run(
        primary: () -> T,
        fallback: () -> T,
        onSwitch: (Throwable) -> Unit,
    ): T {
        if (fallbackActive.get()) return fallback()
        return try {
            primary()
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is LinkageError) throw failure
            if (fallbackActive.compareAndSet(false, true)) {
                onSwitch(failure)
            }
            fallback()
        }
    }
}
