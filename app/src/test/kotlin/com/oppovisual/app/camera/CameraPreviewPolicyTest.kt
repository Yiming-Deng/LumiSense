package com.oppovisual.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraPreviewPolicyTest {
    @Test
    fun preferredSixtyFpsIsUsedWhenBindingSucceeds() {
        val attempts = mutableListOf<IntRange?>()
        val result = bindWithFrameRateFallback(PREFERRED_CAMERA_FPS..PREFERRED_CAMERA_FPS) {
            attempts += it
            "bound"
        }

        assertEquals("bound", result)
        assertEquals(listOf(60..60), attempts)
    }

    @Test
    fun unsupportedPreferredRateFallsBackExactlyOnce() {
        val attempts = mutableListOf<IntRange?>()
        val result = bindWithFrameRateFallback(60..60) {
            attempts += it
            if (it != null) error("unsupported")
            "fallback"
        }

        assertEquals("fallback", result)
        assertEquals(2, attempts.size)
        assertEquals(60..60, attempts[0])
        assertNull(attempts[1])
    }

    @Test
    fun fallbackFailurePreservesPreferredFailureForDiagnosis() {
        val preferredFailure = IllegalStateException("preferred")
        val fallbackFailure = IllegalArgumentException("fallback")

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            bindWithFrameRateFallback(60..60) {
                if (it == null) throw fallbackFailure
                throw preferredFailure
            }
        }

        assertSame(fallbackFailure, thrown)
        assertEquals(listOf(preferredFailure), thrown.suppressed.toList())
    }
}
