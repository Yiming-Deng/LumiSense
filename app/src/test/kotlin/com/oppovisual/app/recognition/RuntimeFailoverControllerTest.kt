package com.oppovisual.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeFailoverControllerTest {
    @Test
    fun `primary result is used while primary succeeds`() {
        val controller = RuntimeFailoverController()

        val result = controller.run(
            primary = { "qnn" },
            fallback = { "litert" },
            onSwitch = { error("must not switch") },
        )

        assertEquals("qnn", result)
        assertFalse(controller.isFallbackActive)
    }

    @Test
    fun `first primary failure switches and retries through fallback`() {
        val controller = RuntimeFailoverController()
        var switches = 0

        val result = controller.run(
            primary = { error("deviceCreate failed") },
            fallback = { "litert" },
            onSwitch = { switches++ },
        )

        assertEquals("litert", result)
        assertEquals(1, switches)
        assertTrue(controller.isFallbackActive)
    }

    @Test
    fun `fallback remains sticky after switch`() {
        val controller = RuntimeFailoverController()
        var primaryCalls = 0
        var fallbackCalls = 0

        controller.run(
            primary = {
                primaryCalls++
                error("unsupported HTP")
            },
            fallback = {
                fallbackCalls++
                Unit
            },
            onSwitch = {},
        )
        controller.run(
            primary = {
                primaryCalls++
                Unit
            },
            fallback = {
                fallbackCalls++
                Unit
            },
            onSwitch = { error("must only switch once") },
        )

        assertEquals(1, primaryCalls)
        assertEquals(2, fallbackCalls)
    }

    @Test
    fun `native linkage failure also switches to fallback`() {
        val controller = RuntimeFailoverController()

        val result = controller.run(
            primary = { throw UnsatisfiedLinkError("unsupported ABI") },
            fallback = { "litert" },
            onSwitch = {},
        )

        assertEquals("litert", result)
        assertTrue(controller.isFallbackActive)
    }

    @Test(expected = AssertionError::class)
    fun `unrelated fatal errors are not swallowed`() {
        RuntimeFailoverController().run(
            primary = { throw AssertionError("fatal") },
            fallback = { "litert" },
            onSwitch = {},
        )
    }
}
