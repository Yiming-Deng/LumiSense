package com.oppovisual.app.recognition

import com.oppovisual.core.ExpressionDecision
import com.oppovisual.core.ExpressionId
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionFrameTest {
    @Test
    fun gestureAndFaceFramesExposeTheirDomainWithoutChangingLegacyPayload() {
        val gesturePayload = FrameRecognition(
            timestampMs = 10,
            hands = emptyList(),
            inputWidth = 640,
            inputHeight = 480,
            processingLatencyMs = 20,
            averageLuma = 100f,
            componentLatency = ComponentLatency(detectorMs = 12, totalMs = 20),
        )
        val facePayload = FaceRecognition(
            timestampMs = 11,
            facePresent = false,
            expression = ExpressionDecision(ExpressionId.NONE, 0f, false, emptyMap()),
            headMotion = null,
            blendshapes = emptyList(),
            landmarks = emptyList(),
            inputWidth = 640,
            inputHeight = 480,
            processingLatencyMs = 21,
            modelLatencyMs = 15,
            averageLuma = 100f,
        )

        val gesture = RecognitionFrame.Gesture(gesturePayload)
        val face = RecognitionFrame.Face(facePayload)
        assertEquals(RecognitionDomain.GESTURE, gesture.domain)
        assertEquals(12, gesture.modelLatencyMs)
        assertEquals(gesturePayload, gesture.recognition)
        assertEquals(RecognitionDomain.FACE, face.domain)
        assertEquals(15, face.modelLatencyMs)
        assertEquals(facePayload, face.recognition)
    }
}
