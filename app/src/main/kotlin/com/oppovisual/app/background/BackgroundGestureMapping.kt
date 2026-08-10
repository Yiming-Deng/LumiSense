package com.oppovisual.app.background

import com.oppovisual.core.GestureId

/** The only system operations that background control is allowed to inject. */
internal enum class BackgroundGestureOperation {
    SWIPE_LEFT,
    SWIPE_RIGHT,
    SWIPE_UP,
    SWIPE_DOWN,
    ZOOM_IN,
    ZOOM_OUT,
    TWO_HAND_PINCH,
}

internal fun backgroundOperationFor(gesture: GestureId): BackgroundGestureOperation? = when (gesture) {
    GestureId.SWIPE_LEFT -> BackgroundGestureOperation.SWIPE_LEFT
    GestureId.SWIPE_RIGHT -> BackgroundGestureOperation.SWIPE_RIGHT
    GestureId.SWIPE_UP -> BackgroundGestureOperation.SWIPE_UP
    GestureId.SWIPE_DOWN -> BackgroundGestureOperation.SWIPE_DOWN
    GestureId.ZOOM_IN -> BackgroundGestureOperation.ZOOM_IN
    GestureId.ZOOM_OUT -> BackgroundGestureOperation.ZOOM_OUT
    GestureId.TWO_HAND_ZOOM -> BackgroundGestureOperation.TWO_HAND_PINCH
    else -> null
}
