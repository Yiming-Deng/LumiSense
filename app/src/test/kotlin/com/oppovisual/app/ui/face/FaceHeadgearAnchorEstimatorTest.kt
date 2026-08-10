package com.oppovisual.app.ui.face

import com.oppovisual.core.HeadPose
import com.oppovisual.core.Point3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceHeadgearAnchorEstimatorTest {
    @Test
    fun `valid landmarks produce stable visible anchor`() {
        val estimator = FaceHeadgearAnchorEstimator()
        val anchor = estimator.update(face(), HeadPose(0f, 0f, 0f), true, 1_000)

        assertTrue(anchor.visible)
        assertEquals(0.5f, anchor.centerX, 0.001f)
        assertEquals(0.5f, anchor.centerY, 0.001f)
        assertEquals(0f, anchor.rotationDegrees, 0.001f)
    }

    @Test
    fun `yaw fades and hides headgear`() {
        val estimator = FaceHeadgearAnchorEstimator()
        val faded = estimator.update(face(), HeadPose(42.5f, 0f, 0f), true, 1_000)
        assertEquals(0.5f, faded.alpha, 0.01f)
        val hidden = estimator.update(face(), HeadPose(55f, 0f, 0f), true, 1_010)
        assertFalse(hidden.visible)
    }

    @Test
    fun `short face loss holds then fades and hides`() {
        val estimator = FaceHeadgearAnchorEstimator()
        estimator.update(face(), HeadPose(0f, 0f, 0f), true, 1_000)

        assertEquals(1f, estimator.update(emptyList(), null, false, 1_100).alpha, 0.001f)
        assertTrue(estimator.update(emptyList(), null, false, 1_250).alpha in 0f..1f)
        assertFalse(estimator.update(emptyList(), null, false, 1_400).visible)
    }

    @Test
    fun `eye line controls roll independently of resolution`() {
        val estimator = FaceHeadgearAnchorEstimator()
        val points = face().toMutableList().apply {
            this[33] = Point3(0.35f, 0.45f, 0f)
            this[133] = Point3(0.41f, 0.47f, 0f)
            this[362] = Point3(0.59f, 0.53f, 0f)
            this[263] = Point3(0.65f, 0.55f, 0f)
        }
        val anchor = estimator.update(points, HeadPose(0f, 0f, 0f), true, 1_000)
        assertTrue(anchor.rotationDegrees > 15f)
    }

    @Test
    fun `valid iris centers preserve the calibrated registration source`() {
        val points = face().toMutableList().apply {
            this[468] = Point3(0.40f, 0.45f, 0f)
            this[473] = Point3(0.60f, 0.45f, 0f)
        }
        val anchor = FaceHeadgearAnchorEstimator().update(points, HeadPose(0f, 0f, 0f), true, 1_000)

        assertEquals(0.5f, anchor.eyeCenterX, 0.001f)
        assertEquals(0.2f, anchor.eyeVectorX, 0.001f)
        assertEquals(0f, anchor.eyeVectorY, 0.001f)
    }

    @Test
    fun `horizontal mirror preserves size and reverses roll`() {
        val points = face().toMutableList().apply {
            this[33] = Point3(0.35f, 0.44f, 0f)
            this[133] = Point3(0.41f, 0.46f, 0f)
            this[362] = Point3(0.59f, 0.54f, 0f)
            this[263] = Point3(0.65f, 0.56f, 0f)
        }
        val original = FaceHeadgearAnchorEstimator().update(points, HeadPose(0f, 0f, 0f), true, 1_000)
        val mirrored = points.map { it.copy(x = 1f - it.x) }.toMutableList().apply {
            val leftEye = this[33]
            this[33] = this[263]
            this[263] = leftEye
            val leftInnerEye = this[133]
            this[133] = this[362]
            this[362] = leftInnerEye
            val leftFace = this[234]
            this[234] = this[454]
            this[454] = leftFace
        }
        val reflected = FaceHeadgearAnchorEstimator().update(mirrored, HeadPose(0f, 0f, 0f), true, 1_000)

        assertEquals(original.faceWidth, reflected.faceWidth, 0.001f)
        assertEquals(original.faceHeight, reflected.faceHeight, 0.001f)
        assertEquals(-original.rotationDegrees, reflected.rotationDegrees, 0.001f)
    }

    @Test
    fun `viewport mapping matches portrait landscape and letterbox contracts`() {
        val anchor = FaceHeadgearAnchor(
            centerX = 0.5f,
            centerY = 0.5f,
            faceWidth = 0.4f,
            faceHeight = 0.5f,
            alpha = 1f,
        )
        val portrait = mapHeadgearAnchorToViewport(anchor, 480, 640, 1080f, 1440f)
        assertEquals(540f, portrait.centerX, 0.001f)
        assertEquals(720f, portrait.centerY, 0.001f)
        assertEquals(432f, portrait.faceWidthPx, 0.001f)

        val landscape = mapHeadgearAnchorToViewport(anchor, 640, 480, 1440f, 1080f)
        assertEquals(720f, landscape.centerX, 0.001f)
        assertEquals(540f, landscape.centerY, 0.001f)
        assertEquals(576f, landscape.faceWidthPx, 0.001f)

        val letterboxed = mapHeadgearAnchorToViewport(anchor, 640, 480, 1080f, 1440f)
        assertEquals(540f, letterboxed.centerX, 0.001f)
        assertEquals(720f, letterboxed.centerY, 0.001f)
    }

    @Test
    fun `face oval bounds determine center when individual landmarks are asymmetric`() {
        val points = face().toMutableList().apply {
            this[10] = Point3(0.58f, 0.20f, 0f)
            this[152] = Point3(0.44f, 0.80f, 0f)
            this[234] = Point3(0.20f, 0.56f, 0f)
            this[454] = Point3(0.76f, 0.45f, 0f)
        }
        val anchor = FaceHeadgearAnchorEstimator().update(points, HeadPose(0f, 0f, 0f), true, 1_000)

        assertEquals(0.48f, anchor.centerX, 0.001f)
        assertEquals(0.50f, anchor.centerY, 0.001f)
        assertEquals(0.56f, anchor.faceWidth, 0.001f)
        assertEquals(0.60f, anchor.faceHeight, 0.001f)
    }

    @Test
    fun `large face translation catches up without a long center lag`() {
        val estimator = FaceHeadgearAnchorEstimator()
        estimator.update(face(centerX = 0.35f), HeadPose(0f, 0f, 0f), true, 1_000)
        val moved = estimator.update(face(centerX = 0.65f), HeadPose(0f, 0f, 0f), true, 1_033)

        assertTrue(moved.centerX >= 0.56f)
    }

    @Test
    fun `yaw deformation does not move a stationary head anchor`() {
        val estimator = FaceHeadgearAnchorEstimator()
        estimator.update(face(), HeadPose(0f, 0f, 0f), true, 1_000)
        val turned = face().toMutableList().apply {
            this[162] = Point3(0.38f, 0.50f, 0f)
            this[389] = Point3(0.82f, 0.50f, 0f)
        }

        val anchor = estimator.update(turned, HeadPose(28f, 0f, 0f), true, 1_033)

        assertEquals(0.5f, anchor.centerX, 0.01f)
    }

    @Test
    fun `pitch deformation does not move a stationary head anchor`() {
        val estimator = FaceHeadgearAnchorEstimator()
        estimator.update(face(), HeadPose(0f, 0f, 0f), true, 1_000)
        val tilted = face().toMutableList().apply {
            this[10] = Point3(0.50f, 0.36f, 0f)
            this[152] = Point3(0.50f, 0.86f, 0f)
        }

        val anchor = estimator.update(tilted, HeadPose(0f, 24f, 0f), true, 1_033)

        assertEquals(0.5f, anchor.centerY, 0.01f)
    }

    private fun face(centerX: Float = 0.5f, centerY: Float = 0.5f): List<Point3> =
        MutableList(478) { Point3(centerX, centerY, 0f) }.apply {
        this[234] = Point3(centerX - 0.20f, centerY, 0f)
        this[454] = Point3(centerX + 0.20f, centerY, 0f)
        this[10] = Point3(centerX, centerY - 0.25f, 0f)
        this[152] = Point3(centerX, centerY + 0.25f, 0f)
        this[33] = Point3(centerX - 0.12f, centerY - 0.05f, 0f)
        this[133] = Point3(centerX - 0.04f, centerY - 0.05f, 0f)
        this[362] = Point3(centerX + 0.04f, centerY - 0.05f, 0f)
        this[263] = Point3(centerX + 0.12f, centerY - 0.05f, 0f)
    }
}
