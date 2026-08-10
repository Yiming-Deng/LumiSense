package com.oppovisual.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class R8FrameTransformTest {
    @Test
    fun `four camera rotations produce expected dimensions and corner mapping`() {
        val expected = mapOf(
            0 to Triple(1280 to 720, 0f to 0f, 1280f to 720f),
            90 to Triple(720 to 1280, 720f to 0f, 0f to 1280f),
            180 to Triple(1280 to 720, 1280f to 720f, 0f to 0f),
            270 to Triple(720 to 1280, 0f to 1280f, 720f to 0f),
        )

        expected.forEach { (rotation, values) ->
            val transform = R8FrameTransforms.cameraFrame(1280, 720, rotation, false)
            assertEquals(values.first.first, transform.outputWidth)
            assertEquals(values.first.second, transform.outputHeight)
            assertPoint(values.second, transform.mapPoint(0f, 0f))
            assertPoint(values.third, transform.mapPoint(1280f, 720f))
        }
    }

    @Test
    fun `front camera mirror is applied after rotation`() {
        val transform = R8FrameTransforms.cameraFrame(1280, 720, 90, true)

        assertEquals(720, transform.outputWidth)
        assertEquals(1280, transform.outputHeight)
        assertPoint(0f to 0f, transform.mapPoint(0f, 0f))
        assertPoint(720f to 1280f, transform.mapPoint(1280f, 720f))
        assertPoint(720f to 0f, transform.mapPoint(0f, 720f))
    }

    @Test
    fun `landscape and portrait letterbox round trip normalized coordinates`() {
        listOf(1280 to 720, 720 to 1280).forEach { (width, height) ->
            val letterbox = R8OutputParser.letterbox(width, height)
            val output = FloatArray(R8OutputParser.CANDIDATE_COUNT * R8OutputParser.ROW_SIZE)
            val left = 0.2f
            val top = 0.3f
            val right = 0.8f
            val bottom = 0.7f
            output[0] = left * width * letterbox.scale + letterbox.padX
            output[1] = top * height * letterbox.scale + letterbox.padY
            output[2] = right * width * letterbox.scale + letterbox.padX
            output[3] = bottom * height * letterbox.scale + letterbox.padY
            output[4] = 0.9f
            output[5] = 15f
            repeat(R8OutputParser.KEYPOINT_COUNT) { index ->
                output[6 + index * 3] = 0.4f * width * letterbox.scale + letterbox.padX
                output[7 + index * 3] = 0.6f * height * letterbox.scale + letterbox.padY
                output[8 + index * 3] = 0.9f
            }

            val detection = R8OutputParser.parse(output, letterbox).single()
            assertEquals(left, detection.box.left, 1e-4f)
            assertEquals(top, detection.box.top, 1e-4f)
            assertEquals(right, detection.box.right, 1e-4f)
            assertEquals(bottom, detection.box.bottom, 1e-4f)
            detection.keypoints.forEach {
                assertEquals(0.4f, it.x, 1e-4f)
                assertEquals(0.6f, it.y, 1e-4f)
            }
        }
    }

    @Test
    fun `rejects non-right-angle camera rotation`() {
        assertThrows(IllegalArgumentException::class.java) {
            R8FrameTransforms.cameraFrame(1280, 720, 45, true)
        }
    }

    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 1e-4f)
        assertEquals(expected.second, actual.second, 1e-4f)
    }
}
