package com.oppovisual.app.background

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.oppovisual.core.GestureId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BackgroundControlPhase { IDLE, STARTING, RUNNING, PAUSED, ERROR }

data class BackgroundControlState(
    val phase: BackgroundControlPhase = BackgroundControlPhase.IDLE,
    val accessibilityConnected: Boolean = false,
    val lastGesture: GestureId? = null,
    val error: String? = null,
) {
    val active: Boolean get() = phase !in setOf(BackgroundControlPhase.IDLE, BackgroundControlPhase.ERROR)
}

object BackgroundGestureControl {
    private val _state = MutableStateFlow(BackgroundControlState())
    val state: StateFlow<BackgroundControlState> = _state.asStateFlow()

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, GestureAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    fun start(context: Context) {
        _state.value = _state.value.copy(phase = BackgroundControlPhase.STARTING, error = null)
        ContextCompat.startForegroundService(
            context,
            Intent(context, GestureControlForegroundService::class.java)
                .setAction(GestureControlForegroundService.ACTION_START),
        )
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, GestureControlForegroundService::class.java)
                .setAction(GestureControlForegroundService.ACTION_STOP),
        )
    }

    internal fun updatePhase(phase: BackgroundControlPhase, error: String? = null) {
        _state.value = _state.value.copy(phase = phase, error = error)
    }

    internal fun reportGesture(gesture: GestureId) {
        _state.value = _state.value.copy(lastGesture = gesture)
    }

    internal fun setAccessibilityConnected(connected: Boolean) {
        _state.value = _state.value.copy(accessibilityConnected = connected)
    }

    internal fun serviceStopped() {
        if (_state.value.phase != BackgroundControlPhase.ERROR) {
            _state.value = _state.value.copy(phase = BackgroundControlPhase.IDLE)
        }
    }
}
