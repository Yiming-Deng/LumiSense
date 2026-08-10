package com.oppovisual.app.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class SerializedCloseableSlotTest {
    @Test
    fun closeWaitsForInFlightUseToFinish() {
        val slot = SerializedCloseableSlot<TestResource>()
        val resource = TestResource()
        val useStarted = CountDownLatch(1)
        val releaseUse = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        assertTrue(slot.install(resource))

        val useThread = thread(start = true) {
            slot.useIfPresent {
                useStarted.countDown()
                assertTrue(releaseUse.await(2, TimeUnit.SECONDS))
            }
        }
        assertTrue(useStarted.await(2, TimeUnit.SECONDS))
        val closeThread = thread(start = true) {
            slot.closeAndClear()
            closeFinished.countDown()
        }

        assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS))
        assertFalse(resource.closed.get())
        releaseUse.countDown()
        assertTrue(closeFinished.await(2, TimeUnit.SECONDS))
        useThread.join(2_000)
        closeThread.join(2_000)
        assertTrue(resource.closed.get())
    }

    @Test
    fun closedSlotRejectsFurtherUse() {
        val slot = SerializedCloseableSlot<TestResource>()
        val resource = TestResource()
        assertTrue(slot.install(resource))

        slot.closeAndClear()

        assertTrue(resource.closed.get())
        assertFalse(slot.useIfPresent { error("closed resource must not be used") })
    }

    private class TestResource : Closeable {
        val closed = AtomicBoolean(false)

        override fun close() {
            closed.set(true)
        }
    }
}
