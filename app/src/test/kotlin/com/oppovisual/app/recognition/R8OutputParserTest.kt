package com.oppovisual.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class R8OutputParserTest {
    @Test
    fun `parses fused row and reverses letterbox`() {
        val output = FloatArray(R8OutputParser.CANDIDATE_COUNT * R8OutputParser.ROW_SIZE)
        val transform = R8OutputParser.letterbox(1280, 720)
        output[0] = 160f
        output[1] = 230f
        output[2] = 480f
        output[3] = 410f
        output[4] = 0.92f
        output[5] = 15f
        repeat(R8OutputParser.KEYPOINT_COUNT) { index ->
            val offset = 6 + index * 3
            output[offset] = 320f
            output[offset + 1] = 320f
            output[offset + 2] = 0.8f
        }
        val detection = R8OutputParser.parse(output, transform).single()
        assertEquals("palm", detection.datasetLabel)
        assertEquals(0.25f, detection.box.left, 1e-4f)
        assertEquals(0.75f, detection.box.right, 1e-4f)
        assertEquals(0.25f, detection.box.top, 1e-4f)
        assertEquals(0.75f, detection.box.bottom, 1e-4f)
        assertTrue(detection.keypoints.all { it.x == 0.5f && it.y == 0.5f })
    }

    @Test
    fun `rejects malformed tensor and suppresses same-class duplicate boxes`() {
        assertThrows(IllegalArgumentException::class.java) {
            R8OutputParser.parse(FloatArray(10), R8OutputParser.letterbox(640, 640))
        }
        val output = FloatArray(R8OutputParser.CANDIDATE_COUNT * R8OutputParser.ROW_SIZE)
        writeRow(output, 0, 0.9f, 2f, 100f)
        writeRow(output, 1, 0.8f, 2f, 105f)
        assertEquals(1, R8OutputParser.parse(output, R8OutputParser.letterbox(640, 640)).size)
    }

    @Test
    fun `suppresses overlapping boxes with different classes for the same physical hand`() {
        val output = FloatArray(R8OutputParser.CANDIDATE_COUNT * R8OutputParser.ROW_SIZE)
        writeRow(output, 0, 0.9f, 2f, 100f)
        writeRow(output, 1, 0.8f, 15f, 105f)

        val detections = R8OutputParser.parse(output, R8OutputParser.letterbox(640, 640))

        assertEquals(listOf("fist"), detections.map(R8Detection::datasetLabel))
    }

    @Test
    fun `keeps overlapping boxes when palm keypoints identify two physical hands`() {
        val output = FloatArray(R8OutputParser.CANDIDATE_COUNT * R8OutputParser.ROW_SIZE)
        writeRow(output, 0, 0.9f, 8f, 100f, keypointX = 180f)
        writeRow(output, 1, 0.8f, 8f, 105f, keypointX = 260f)

        val detections = R8OutputParser.parse(output, R8OutputParser.letterbox(640, 640))

        assertEquals(2, detections.size)
        assertTrue(detections.all { it.datasetLabel == "holy" })
    }

    @Test
    fun `duplicate transition candidate does not consume the second physical hand slot`() {
        val output = FloatArray(R8OutputParser.CANDIDATE_COUNT * R8OutputParser.ROW_SIZE)
        writeRow(output, 0, 0.95f, 2f, 80f, keypointX = 180f)
        writeRow(output, 1, 0.90f, 15f, 85f, keypointX = 182f)
        writeRow(output, 2, 0.85f, 20f, 360f, keypointX = 460f)

        val detections = R8OutputParser.parse(output, R8OutputParser.letterbox(640, 640))

        assertEquals(listOf("fist", "stop"), detections.map(R8Detection::datasetLabel))
    }

    @Test
    fun `keeps two nearby physical hands with different classes`() {
        val output = FloatArray(R8OutputParser.CANDIDATE_COUNT * R8OutputParser.ROW_SIZE)
        writeRow(output, 0, 0.9f, 2f, 100f, keypointX = 170f)
        writeRow(output, 1, 0.8f, 15f, 105f, keypointX = 270f)

        val detections = R8OutputParser.parse(output, R8OutputParser.letterbox(640, 640))

        assertEquals(listOf("fist", "palm"), detections.map(R8Detection::datasetLabel))
    }

    @Test
    fun `letterbox mapping is invariant across supported input sizes`() {
        listOf(448, 512, 576, 640).forEach { inputSize ->
            val output = FloatArray(R8OutputParser.CANDIDATE_COUNT * R8OutputParser.ROW_SIZE)
            val transform = R8OutputParser.letterbox(1280, 720, inputSize)
            val scale = inputSize / 1280f
            val padY = (inputSize - 720 * scale) / 2f
            output[0] = 320 * scale
            output[1] = 180 * scale + padY
            output[2] = 960 * scale
            output[3] = 540 * scale + padY
            output[4] = 0.9f
            output[5] = 15f
            repeat(R8OutputParser.KEYPOINT_COUNT) { index ->
                output[6 + index * 3] = 640 * scale
                output[7 + index * 3] = 360 * scale + padY
                output[8 + index * 3] = 0.9f
            }
            val detection = R8OutputParser.parse(output, transform).single()
            assertEquals(0.25f, detection.box.left, 1e-4f)
            assertEquals(0.75f, detection.box.right, 1e-4f)
            assertEquals(0.25f, detection.box.top, 1e-4f)
            assertEquals(0.75f, detection.box.bottom, 1e-4f)
        }
    }

    @Test
    fun `parses channel-first raw output before TopK postprocessing`() {
        val output = FloatArray(R8OutputParser.RAW_CANDIDATE_COUNT * R8OutputParser.RAW_ROW_SIZE)
        writeRawCandidate(output, candidate = 7, score = 0.92f, classId = 15, left = 160f)

        val detection = R8OutputParser.parse(output, R8OutputParser.letterbox(1280, 720)).single()

        assertEquals("palm", detection.datasetLabel)
        assertEquals(0.25f, detection.box.left, 1e-4f)
        assertEquals(0.75f, detection.box.right, 1e-4f)
        assertTrue(detection.keypoints.all { it.x == 0.5f && it.y == 0.5f })
    }

    private fun writeRow(
        output: FloatArray,
        row: Int,
        score: Float,
        classId: Float,
        left: Float,
        keypointX: Float = 200f,
    ) {
        val offset = row * R8OutputParser.ROW_SIZE
        output[offset] = left
        output[offset + 1] = 100f
        output[offset + 2] = left + 200f
        output[offset + 3] = 300f
        output[offset + 4] = score
        output[offset + 5] = classId
        repeat(R8OutputParser.KEYPOINT_COUNT) { index ->
            output[offset + 6 + index * 3] = keypointX
            output[offset + 7 + index * 3] = 200f
            output[offset + 8 + index * 3] = 0.9f
        }
    }

    private fun writeRawCandidate(
        output: FloatArray,
        candidate: Int,
        score: Float,
        classId: Int,
        left: Float,
    ) {
        fun write(channel: Int, value: Float) {
            output[channel * R8OutputParser.RAW_CANDIDATE_COUNT + candidate] = value
        }
        write(0, left)
        write(1, 230f)
        write(2, left + 320f)
        write(3, 410f)
        write(4 + classId, score)
        repeat(R8OutputParser.KEYPOINT_COUNT) { index ->
            val channel = 4 + R8OutputParser.datasetLabels.size + index * 3
            write(channel, 320f)
            write(channel + 1, 320f)
            write(channel + 2, 0.8f)
        }
    }
}
