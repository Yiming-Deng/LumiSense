package com.oppovisual.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class R8QnnRuntimeConfigTest {
    @Test
    fun `QNN performance modes are explicit`() {
        listOf("default", "burst", "sustained", "powersave").forEach { mode ->
            assertEquals(mode, R8QnnRuntimeConfig(performanceMode = mode).performanceMode)
        }
    }

    @Test
    fun `unknown QNN performance mode is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            R8QnnRuntimeConfig(performanceMode = "auto")
        }
    }
}
