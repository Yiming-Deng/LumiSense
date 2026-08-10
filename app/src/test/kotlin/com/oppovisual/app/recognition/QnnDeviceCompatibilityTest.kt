package com.oppovisual.app.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnDeviceCompatibilityTest {
    @Test
    fun `supported snapdragon generations try qnn first`() {
        assertTrue(QnnDeviceCompatibility.isQnnCandidate(35, "Qualcomm", "SM8550"))
        assertTrue(QnnDeviceCompatibility.isQnnCandidate(35, "QTI", "SM8650"))
        assertTrue(QnnDeviceCompatibility.isQnnCandidate(35, "Qualcomm", "SM8750-AC"))
        assertTrue(QnnDeviceCompatibility.isQnnCandidate(35, "Qualcomm", "SM8850"))
    }

    @Test
    fun `snapdragon model is accepted when vendor property is missing`() {
        assertTrue(QnnDeviceCompatibility.isQnnCandidate(35, null, "sm8650"))
    }

    @Test
    fun `mediatek unknown and pre android 12 devices use portable runtime`() {
        assertFalse(QnnDeviceCompatibility.isQnnCandidate(35, "MediaTek", "MT6991"))
        assertFalse(QnnDeviceCompatibility.isQnnCandidate(35, null, null))
        assertFalse(QnnDeviceCompatibility.isQnnCandidate(30, "Qualcomm", "SM8750"))
    }
}
