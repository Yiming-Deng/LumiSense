package com.oppovisual.app.ui.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oppovisual.app.TestHostActivity
import com.oppovisual.app.recognition.BlendshapeScore
import com.oppovisual.app.recognition.RecognitionDomain
import com.oppovisual.app.ui.RecognitionUiState
import com.oppovisual.core.HeadPose
import com.oppovisual.core.Point3
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatMocapFilamentPreviewTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestHostActivity>()

    @Test
    fun rendersFourBlendshapeDrivenStates() {
        var state by mutableStateOf(previewState(emptyList()))
        lateinit var renderer: CatMocapFilamentView
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(PreviewBackground)) {
                CatMocapOverlay(
                    state,
                    Modifier.fillMaxSize(),
                    onViewCreated = { renderer = it },
                )
            }
        }
        composeRule.runOnIdle { renderer.setDiagnosticFixedPose(true) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "cat-mocap-previews").apply { mkdirs() }
        val publicOutput = "/sdcard/Download/oppovisual-avatar-mocap-previews"
        instrumentation.uiAutomation.executeShellCommand("mkdir -p $publicOutput").close()
        val states = linkedMapOf(
            "neutral" to emptyList(),
            "blinkLeft" to listOf("eyeBlinkLeft" to 0.96f),
            "blinkRight" to listOf("eyeBlinkRight" to 0.96f),
            "smile" to listOf("mouthSmileLeft" to 0.88f, "mouthSmileRight" to 0.88f),
            "mouthOpen" to listOf("jawOpen" to 0.92f),
            "pucker" to listOf("mouthPucker" to 0.92f),
            "browRaise" to listOf("browInnerUp" to 0.92f, "browOuterUpLeft" to 0.84f, "browOuterUpRight" to 0.84f),
        )
        var neutralFrame: Bitmap? = null

        states.forEach { (name, channels) ->
            val scores = channels.map { BlendshapeScore(it.first, it.second) }
            composeRule.runOnIdle {
                state = previewState(scores)
                repeat(6) { frame ->
                    renderer.update(previewState(scores).copy(lastFaceFrameTimestampMs = 1_000L + frame * 16L))
                }
            }
            composeRule.waitForIdle()
            composeRule.waitUntil(5_000) { renderer.renderedFrameCount >= 3 && renderer.hasVisibleAnchor }
            assertTrue(renderer.assetRenderableCount > 0)
            assertTrue(renderer.sceneRenderableCount > 0)
            assertTrue(renderer.rootHasTransform)
            assertTrue(renderer.rootTransformInstance != 0)
            assertTrue(renderer.totalPrimitiveCount >= renderer.assetRenderableCount)
            assertTrue(renderer.assetBoundsHalfExtent.all { it > 0f })
            assertTrue("Animated GLB morph targets were not bound", renderer.animatedEntityCount > 0)
            if (name == "smile") {
                assertTrue("Smile Blendshape did not reach renderer", renderer.lastMotion.smileLeft > 0.5f)
                assertTrue(renderer.lastMorphWeights[VrmAvatarMorphTargets.MOUTH_FUN] > 0.4f)
                assertTrue(renderer.lastMorphWeights[VrmAvatarMorphTargets.MOUTH_CLOSE] > 0.2f)
                assertTrue(renderer.lastMorphWeights[VrmAvatarMorphTargets.MOUTH_JOY] < 0.05f)
                assertTrue(renderer.lastMorphWeights[VrmAvatarMorphTargets.MOUTH_A] < 0.05f)
            }
            if (name == "blinkLeft") {
                assertTrue(renderer.lastMorphWeights[VrmAvatarMorphTargets.EYE_CLOSE_LEFT] > 0.7f)
                assertTrue(renderer.lastMorphWeights[VrmAvatarMorphTargets.EYE_CLOSE_RIGHT] < 0.12f)
            }
            if (name == "blinkRight") {
                assertTrue(renderer.lastMorphWeights[VrmAvatarMorphTargets.EYE_CLOSE_RIGHT] > 0.7f)
                assertTrue(renderer.lastMorphWeights[VrmAvatarMorphTargets.EYE_CLOSE_LEFT] < 0.12f)
            }
            if (name == "pucker") {
                assertTrue(renderer.lastMorphWeights[VrmAvatarMorphTargets.MOUTH_SMALL] <= 0.65f)
                assertTrue(renderer.lastMorphWeights[VrmAvatarMorphTargets.MOUTH_U] <= 0.65f)
                assertTrue(renderer.lastMorphWeights[VrmAvatarMorphTargets.MOUTH_O] <= 0.65f)
            }
            Thread.sleep(250)
            val bitmap = captureFilamentFrame(renderer)
            val file = File(output, "$name.png")
            file.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            instrumentation.uiAutomation.executeShellCommand(
                "cp ${file.absolutePath} $publicOutput/$name.png",
            ).close()
            assertCatPixelsPresent(bitmap)
            if (name == "neutral") {
                neutralFrame = bitmap
            } else {
                assertTrue("$name did not reach the morph renderer", renderer.lastMorphWeights.any { it > 0.35f })
                val changedSamples = countChangedSamples(requireNotNull(neutralFrame), bitmap)
                val minimumChangedSamples = when (name) {
                    "pucker" -> 40
                    "blinkLeft", "blinkRight" -> 24
                    "browRaise" -> 70
                    else -> 120
                }
                assertTrue(
                    "$name did not visibly differ from neutral: changedSamples=$changedSamples",
                    changedSamples > minimumChangedSamples,
                )
            }
        }
    }

    @Test
    fun tracksFaceAnchorInsidePortraitCameraViewport() {
        val state = previewState(
            listOf(
                BlendshapeScore("mouthSmileLeft", 0.72f),
                BlendshapeScore("mouthSmileRight", 0.72f),
            ),
        )
        lateinit var renderer: CatMocapFilamentView
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(PreviewBackground)) {
                CatMocapOverlay(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    onViewCreated = { renderer = it },
                )
            }
        }
        composeRule.waitUntil(5_000) {
            renderer.renderedFrameCount >= 5 && renderer.hasVisibleAnchor && renderer.lastVisibleRenderableCount > 0
        }
        val bitmap = captureFilamentFrame(renderer)
        val bounds = alphaBounds(bitmap)
        val virtualFace = renderer.avatarFaceViewportBounds
        val avatarEyes = renderer.avatarEyeViewportGeometry
        val expectedLayout = mapHeadgearAnchorToViewport(
            anchor = renderer.lastAppliedAnchor,
            inputWidth = 480,
            inputHeight = 640,
            viewportWidth = bitmap.width.toFloat(),
            viewportHeight = bitmap.height.toFloat(),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "cat-mocap-previews").apply { mkdirs() }
        File(output, "tracked-production.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue("Tracked avatar head was too narrow: $bounds", bounds.width() >= bitmap.width * 0.46f)
        val cameraViewportHeight = minOf(bitmap.height.toFloat(), bitmap.width * 4f / 3f)
        assertTrue("Tracked avatar head was too short: $bounds", bounds.height() >= cameraViewportHeight * 0.38f)
        assertEyesAligned(avatarEyes, expectedLayout, "tracked avatar")
        assertTrue("Avatar face projection was empty: ${virtualFace.contentToString()}", virtualFace[2] > virtualFace[0] && virtualFace[3] > virtualFace[1])

    }

    @Test
    fun visibleCatKeepsWindowBackgroundVisible() {
        lateinit var renderer: CatMocapFilamentView
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(TransparencyProbeBackground)) {
                CatMocapOverlay(
                    state = previewState(emptyList()),
                    modifier = Modifier.fillMaxSize(),
                    onViewCreated = { renderer = it },
                )
            }
        }
        composeRule.runOnIdle { renderer.setDiagnosticFixedPose(true) }
        composeRule.waitUntil(5_000) {
            renderer.renderedFrameCount >= 5 && renderer.hasVisibleAnchor && renderer.lastVisibleRenderableCount > 0
        }

        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val location = IntArray(2)
        composeRule.runOnIdle { renderer.getLocationOnScreen(location) }
        val inset = 24
        val probes = listOf(
            location[0] + inset to location[1] + inset,
            location[0] + renderer.width - inset - 1 to location[1] + inset,
            location[0] + inset to location[1] + renderer.height - inset - 1,
            location[0] + renderer.width - inset - 1 to location[1] + renderer.height - inset - 1,
        )
        val visibleBackgroundProbes = probes.count { (x, y) ->
            x in 0 until screenshot.width && y in 0 until screenshot.height &&
                colorDistance(screenshot.getPixel(x, y), TransparencyProbeBackgroundArgb) <= 36
        }
        assertTrue(
            "Visible Filament content replaced the window background: matched=$visibleBackgroundProbes/4",
            visibleBackgroundProbes >= 3,
        )
        assertCatPixelsPresent(captureFilamentFrame(renderer))
    }

    @Test
    fun avatarEyesAlignAcrossViewportPositionsAndSizes() {
        lateinit var renderer: CatMocapFilamentView
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(PreviewBackground)) {
                CatMocapOverlay(
                    state = previewState(emptyList(), headPose = HeadPose(0f, 0f, 0f)),
                    modifier = Modifier.fillMaxSize(),
                    onViewCreated = { renderer = it },
                )
            }
        }
        composeRule.waitUntil(5_000) { renderer.renderedFrameCount >= 3 }

        val cases = listOf(
            floatArrayOf(0.25f, 0.33f, 0.30f, 0.38f),
            floatArrayOf(0.75f, 0.33f, 0.30f, 0.38f),
            floatArrayOf(0.25f, 0.67f, 0.30f, 0.38f),
            floatArrayOf(0.75f, 0.67f, 0.30f, 0.38f),
            floatArrayOf(0.50f, 0.50f, 0.48f, 0.58f),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "cat-mocap-fit").apply { mkdirs() }

        cases.forEachIndexed { index, values ->
            val centerX = values[0]
            val centerY = values[1]
            val faceWidth = values[2]
            val faceHeight = values[3]
            val startingFrame = renderer.renderedFrameCount
            composeRule.runOnIdle {
                renderer.resetTrackingForTest()
                repeat(3) { frame ->
                    renderer.update(
                        previewState(
                            blendshapes = emptyList(),
                            centerX = centerX,
                            centerY = centerY,
                            faceWidth = faceWidth,
                            faceHeight = faceHeight,
                            headPose = HeadPose(0f, 0f, 0f),
                        ).copy(lastFaceFrameTimestampMs = 2_000L + index * 1_000L + frame * 16L),
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.waitUntil(3_000) {
                renderer.renderedFrameCount >= startingFrame + 5 &&
                    renderer.lastAppliedLayout != null
            }
            val bitmap = captureFilamentFrame(renderer)
            File(output, "fit-$index.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val catBounds = alphaBounds(bitmap)
            val avatarFaceBounds = renderer.avatarFaceViewportBounds
            val avatarEyes = renderer.avatarEyeViewportGeometry
            val faceLayout = requireNotNull(renderer.lastAppliedLayout)
            val applied = buildString {
                append("anchor=${renderer.lastAppliedAnchor} layout=${renderer.lastAppliedLayout}")
                append(" assetCenterNdc=${renderer.assetCenterNdc.contentToString()}")
                append(" rootLocal=${renderer.rootLocalTransform.contentToString()}")
                append(" rootWorld=${renderer.rootWorldTransform.contentToString()}")
            }
            assertTrue(
                "Avatar face projection was empty for case $index: avatar=${avatarFaceBounds.contentToString()} $applied",
                avatarFaceBounds[2] > avatarFaceBounds[0] && avatarFaceBounds[3] > avatarFaceBounds[1],
            )
            assertEyesAligned(avatarEyes, faceLayout, "case $index cat=$catBounds")
        }
    }

    @Test
    fun catRotationKeepsAStationaryHeadCentered() {
        lateinit var renderer: CatMocapFilamentView
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(PreviewBackground)) {
                CatMocapOverlay(
                    state = previewState(emptyList(), headPose = HeadPose(0f, 0f, 0f)),
                    modifier = Modifier.fillMaxSize(),
                    onViewCreated = { renderer = it },
                )
            }
        }
        composeRule.waitUntil(5_000) { renderer.renderedFrameCount >= 3 }

        val poses = linkedMapOf(
            "neutral" to HeadPose(0f, 0f, 0f),
            "yaw-left" to HeadPose(-30f, 0f, 0f),
            "yaw-right" to HeadPose(30f, 0f, 0f),
            "pitch-up" to HeadPose(0f, -25f, 0f),
            "pitch-down" to HeadPose(0f, 25f, 0f),
        )
        val bounds = linkedMapOf<String, android.graphics.Rect>()
        val eyeCenters = linkedMapOf<String, Pair<Float, Float>>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "cat-mocap-rotation").apply { mkdirs() }

        poses.forEach { (name, pose) ->
            val startingFrame = renderer.renderedFrameCount
            composeRule.runOnIdle {
                renderer.resetTrackingForTest()
                repeat(12) { frame ->
                    renderer.update(
                        previewState(emptyList(), headPose = pose)
                            .copy(lastFaceFrameTimestampMs = 4_000L + frame * 16L),
                    )
                }
            }
            composeRule.waitUntil(3_000) { renderer.renderedFrameCount >= startingFrame + 5 }
            val bitmap = captureFilamentFrame(renderer)
            File(output, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bounds[name] = alphaBounds(bitmap)
            renderer.avatarEyeViewportGeometry.let { eyes ->
                assertEyesAligned(eyes, requireNotNull(renderer.lastAppliedLayout), name)
                eyeCenters[name] = (eyes[0] + eyes[2]) * 0.5f to (eyes[1] + eyes[3]) * 0.5f
            }
        }

        bounds.filterKeys { it != "neutral" }.forEach { (name, current) ->
            assertTrue("$name produced an empty avatar frame: $current", current.width() > 0 && current.height() > 0)
            val neutralEyes = requireNotNull(eyeCenters["neutral"])
            val currentEyes = requireNotNull(eyeCenters[name])
            assertTrue(
                "$name moved avatar eye anchor: neutral=$neutralEyes current=$currentEyes",
                kotlin.math.hypot(currentEyes.first - neutralEyes.first, currentEyes.second - neutralEyes.second) <= 4f,
            )
        }
    }

    private fun assertEyesAligned(
        avatarEyes: FloatArray,
        expected: FaceHeadgearLayout,
        label: String,
    ) {
        val avatarCenterX = (avatarEyes[0] + avatarEyes[2]) * 0.5f
        val avatarCenterY = (avatarEyes[1] + avatarEyes[3]) * 0.5f
        val avatarDistance = kotlin.math.hypot(
            avatarEyes[2] - avatarEyes[0],
            avatarEyes[3] - avatarEyes[1],
        )
        assertTrue(
            "$label eye center X mismatch: avatar=${avatarEyes.contentToString()} expected=$expected",
            kotlin.math.abs(avatarCenterX - expected.eyeCenterX) <= 3f,
        )
        assertTrue(
            "$label eye center Y mismatch: avatar=${avatarEyes.contentToString()} expected=$expected",
            kotlin.math.abs(avatarCenterY - expected.eyeCenterY) <= 3f,
        )
        assertTrue(
            "$label eye distance mismatch: avatar=${avatarEyes.contentToString()} expected=$expected",
            kotlin.math.abs(avatarDistance - expected.eyeDistancePx) <= 3f,
        )
    }

    private fun copySurface(renderer: CatMocapFilamentView): Bitmap {
        val surface = requireNotNull(renderer.bitmap) { "TextureView did not expose a rendered frame" }
        return Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).apply {
                drawColor(Color.rgb(42, 54, 64))
                drawBitmap(surface, 0f, 0f, null)
            }
        }
    }

    private fun captureFilamentFrame(renderer: CatMocapFilamentView): Bitmap {
        val bitmap = AtomicReference<Bitmap>()
        val latch = CountDownLatch(1)
        composeRule.runOnIdle {
            renderer.captureNextFilamentFrame {
                bitmap.set(it)
                latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return requireNotNull(bitmap.get())
    }

    private fun assertCatPixelsPresent(bitmap: Bitmap) {
        var changed = 0
        var opaque = 0
        var colored = 0
        val left = bitmap.width / 4
        val right = bitmap.width * 3 / 4
        val top = bitmap.height / 4
        val bottom = bitmap.height * 3 / 4
        for (y in top until bottom step 4) {
            for (x in left until right step 4) {
                val pixel = bitmap.getPixel(x, y)
                val distance = kotlin.math.abs(Color.red(pixel) - 42) +
                    kotlin.math.abs(Color.green(pixel) - 54) +
                    kotlin.math.abs(Color.blue(pixel) - 64)
                if (distance > 18) changed++
                if (Color.alpha(pixel) > 32) opaque++
                if (Color.alpha(pixel) > 32 && maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) -
                    minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 20
                ) {
                    colored++
                }
            }
        }
        assertTrue("Filament surface was blank", changed > 800)
        assertTrue("Filament output remained transparent", opaque > 800)
        assertTrue("Cat materials were not visible", colored > 60)
    }

    private fun countChangedSamples(first: Bitmap, second: Bitmap): Int {
        var changed = 0
        val left = first.width / 4
        val right = first.width * 3 / 4
        val top = first.height / 4
        val bottom = first.height * 3 / 4
        for (y in top until bottom step 4) {
            for (x in left until right step 4) {
                val a = first.getPixel(x, y)
                val b = second.getPixel(x, y)
                val distance = kotlin.math.abs(Color.red(a) - Color.red(b)) +
                    kotlin.math.abs(Color.green(a) - Color.green(b)) +
                    kotlin.math.abs(Color.blue(a) - Color.blue(b))
                if (distance > 24) changed++
            }
        }
        return changed
    }

    private fun colorDistance(first: Int, second: Int): Int =
        kotlin.math.abs(Color.red(first) - Color.red(second)) +
            kotlin.math.abs(Color.green(first) - Color.green(second)) +
            kotlin.math.abs(Color.blue(first) - Color.blue(second))

    private fun alphaBounds(bitmap: Bitmap): android.graphics.Rect {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 32) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue("Tracked cat produced no visible alpha", right >= left && bottom >= top)
        return android.graphics.Rect(left, top, right + 1, bottom + 1)
    }

    private fun previewState(
        blendshapes: List<BlendshapeScore>,
        centerX: Float = 0.50f,
        centerY: Float = 0.50f,
        faceWidth: Float = 0.44f,
        faceHeight: Float = 0.54f,
        headPose: HeadPose = HeadPose(10f, -4f, 5f),
    ): RecognitionUiState {
        val landmarks = MutableList(478) { Point3(centerX, centerY, 0f) }
        landmarks[234] = Point3(centerX - faceWidth / 2f, centerY, 0f)
        landmarks[454] = Point3(centerX + faceWidth / 2f, centerY, 0f)
        landmarks[10] = Point3(centerX, centerY - faceHeight / 2f, 0f)
        landmarks[152] = Point3(centerX, centerY + faceHeight / 2f, 0f)
        landmarks[33] = Point3(centerX - faceWidth * 0.25f, centerY - faceHeight * 0.13f, 0f)
        landmarks[133] = Point3(centerX - faceWidth * 0.08f, centerY - faceHeight * 0.13f, 0f)
        landmarks[362] = Point3(centerX + faceWidth * 0.08f, centerY - faceHeight * 0.13f, 0f)
        landmarks[263] = Point3(centerX + faceWidth * 0.25f, centerY - faceHeight * 0.13f, 0f)
        return RecognitionUiState(
            domain = RecognitionDomain.FACE,
            facePresent = true,
            faceLandmarks = landmarks,
            headPose = headPose,
            blendshapes = blendshapes,
            inputWidth = 480,
            inputHeight = 640,
            lastFaceFrameTimestampMs = 1_000,
            selectedHeadgear = HeadgearId.CAT,
        )
    }

    private companion object {
        val PreviewBackground = ComposeColor(0xFF2A3640)
        val TransparencyProbeBackground = ComposeColor(0xFF146B3A)
        val TransparencyProbeBackgroundArgb = 0xFF146B3A.toInt()
    }
}
