package com.oppovisual.app.ui.face

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import com.oppovisual.app.TestHostActivity
import com.oppovisual.app.recognition.RecognitionDomain
import com.oppovisual.app.ui.RecognitionUiState
import com.oppovisual.app.ui.theme.OppoVisualTheme
import com.oppovisual.core.ExpressionId
import com.oppovisual.core.HeadPose
import com.oppovisual.core.HeadMotionId
import com.oppovisual.core.Point3
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FaceExperienceOverlayTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestHostActivity>()

    @Test
    fun switchesModeAndHeadgear() {
        var state by mutableStateOf(faceState())
        composeRule.setContent {
            OppoVisualTheme {
                FaceExperienceOverlay(
                    state = state,
                    onModeSelected = { state = state.copy(faceExperienceMode = it) },
                    onHeadgearSelected = { state = state.copy(selectedHeadgear = it) },
                    onStartChallenge = {},
                    onPauseChallenge = {},
                    onResumeChallenge = {},
                    onQuitChallenge = {},
                    onRetryChallenge = {},
                    enableCat3d = false,
                )
            }
        }

        composeRule.onNodeWithTag("headgear_CAT").performClick()
        composeRule.runOnIdle { assertEquals(HeadgearId.CAT, state.selectedHeadgear) }
        composeRule.onNodeWithTag("headgear_OFF").performClick()
        composeRule.runOnIdle { assertEquals(HeadgearId.OFF, state.selectedHeadgear) }
        composeRule.onNodeWithTag("face_mode_CHALLENGE").performClick()
        composeRule.onNodeWithTag("challenge_start").assertIsDisplayed()
    }

    @Test
    fun externalPanelKeepsModeAndChallengeControlsAvailable() {
        var state by mutableStateOf(faceState())
        composeRule.setContent {
            OppoVisualTheme {
                FaceExperienceControlPanel(
                    state = state,
                    onModeSelected = { state = state.copy(faceExperienceMode = it) },
                    onHeadgearSelected = { state = state.copy(selectedHeadgear = it) },
                    onStartChallenge = {},
                    onPauseChallenge = {},
                    onResumeChallenge = {},
                    onQuitChallenge = {},
                    onRetryChallenge = {},
                )
            }
        }

        composeRule.onNodeWithTag("headgear_CAT").performClick()
        composeRule.runOnIdle { assertEquals(HeadgearId.CAT, state.selectedHeadgear) }
        composeRule.onNodeWithTag("face_mode_CHALLENGE").performClick()
        composeRule.onNodeWithTag("challenge_start_external").assertIsDisplayed()
    }

    @Test
    fun exposesManualPauseAndExitControls() {
        var pauseCount = 0
        var exitCount = 0
        composeRule.setContent {
            OppoVisualTheme {
                FaceExperienceOverlay(
                    state = faceState(
                        mode = FaceExperienceMode.CHALLENGE,
                        challenge = ChallengeUiState(
                            phase = ChallengePhase.PROMPT,
                            sequence = listOf(ChallengeTarget.Expression(ExpressionId.SMILE)),
                            remainingMs = 1_200,
                        ),
                    ),
                    onModeSelected = {},
                    onHeadgearSelected = {},
                    onStartChallenge = {},
                    onPauseChallenge = { pauseCount++ },
                    onResumeChallenge = {},
                    onQuitChallenge = { exitCount++ },
                    onRetryChallenge = {},
                    enableCat3d = false,
                )
            }
        }

        composeRule.onNodeWithTag("challenge_pause").performClick()
        composeRule.onNodeWithTag("challenge_exit").performClick()
        composeRule.runOnIdle {
            assertEquals(1, pauseCount)
            assertEquals(1, exitCount)
        }
    }

    @Test
    fun resultProvidesRetryAndExit() {
        var retryCount = 0
        var exitCount = 0
        composeRule.setContent {
            OppoVisualTheme {
                FaceExperienceOverlay(
                    state = faceState(
                        mode = FaceExperienceMode.CHALLENGE,
                        challenge = ChallengeUiState(
                            phase = ChallengePhase.RESULT,
                            sequence = listOf(ChallengeTarget.Expression(ExpressionId.SMILE)),
                            roundIndex = 1,
                            score = 900,
                            bestScore = 1_000,
                        ),
                    ),
                    onModeSelected = {},
                    onHeadgearSelected = {},
                    onStartChallenge = {},
                    onPauseChallenge = {},
                    onResumeChallenge = {},
                    onQuitChallenge = { exitCount++ },
                    onRetryChallenge = { retryCount++ },
                    enableCat3d = false,
                )
            }
        }

        composeRule.onNodeWithTag("challenge_retry").performClick()
        composeRule.onNodeWithTag("challenge_result_exit").performClick()
        composeRule.runOnIdle {
            assertEquals(1, retryCount)
            assertEquals(1, exitCount)
        }
    }

    @Test
    fun exportsFourDeterministicAvatarPreviews() {
        var state by mutableStateOf(previewState(HeadgearId.CAT, ExpressionId.SMILE))
        composeRule.setContent {
            OppoVisualTheme {
                FaceExperienceOverlay(
                    state = state,
                    onModeSelected = {},
                    onHeadgearSelected = {},
                    onStartChallenge = {},
                    onPauseChallenge = {},
                    onResumeChallenge = {},
                    onQuitChallenge = {},
                    onRetryChallenge = {},
                    enableCat3d = false,
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "face-previews").apply { mkdirs() }

        FACE_EFFECT_EXPRESSIONS.sortedBy(ExpressionId::ordinal).forEach { expression ->
            composeRule.runOnIdle { state = previewState(HeadgearId.CAT, expression) }
            composeRule.waitForIdle()
            val bitmap = composeRule.onNodeWithTag("face_experience_root").captureToImage().asAndroidBitmap()
            File(output, "avatar-${expression.wireName}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test
    fun exportsFashionEventPreviews() {
        var state by mutableStateOf(previewState(HeadgearId.CAT, ExpressionId.NONE))
        composeRule.setContent {
            OppoVisualTheme {
                FaceExperienceOverlay(
                    state = state,
                    onModeSelected = {},
                    onHeadgearSelected = {},
                    onStartChallenge = {},
                    onPauseChallenge = {},
                    onResumeChallenge = {},
                    onQuitChallenge = {},
                    onRetryChallenge = {},
                    enableCat3d = false,
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "face-effect-previews").apply { mkdirs() }
        val publicOutput = "/sdcard/Download/oppovisual-face-effect-previews"
        instrumentation.uiAutomation.executeShellCommand("mkdir -p $publicOutput").close()
        composeRule.mainClock.autoAdvance = false

        fun capture(name: String, next: RecognitionUiState) {
            composeRule.runOnIdle { state = next }
            composeRule.mainClock.advanceTimeBy(260)
            composeRule.waitForIdle()
            val bitmap = composeRule.onNodeWithTag("face_experience_root").captureToImage().asAndroidBitmap()
            val file = File(output, "$name.png")
            file.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            instrumentation.uiAutomation.executeShellCommand(
                "cp ${file.absolutePath} $publicOutput/$name.png",
            ).close()
        }

        capture(
            "brow-bang",
            state.copy(
                expressionEventSequence = 1,
                lastExpressionEvent = ExpressionId.BROW_RAISE,
            ),
        )
        capture(
            "smile-cheeks",
            state.copy(
                expressionEventSequence = 2,
                lastExpressionEvent = ExpressionId.SMILE,
            ),
        )
        capture(
            "pucker-bubbles",
            state.copy(
                expression = ExpressionId.MOUTH_PUCKER,
                expressionConfidence = 0.84f,
                expressionScores = mapOf(ExpressionId.MOUTH_PUCKER to 0.84f),
                expressionEventSequence = 3,
                lastExpressionEvent = ExpressionId.MOUTH_PUCKER,
            ),
        )
        capture(
            "left-eye-star",
            state.copy(
                expression = ExpressionId.LEFT_WINK,
                expressionEventSequence = 4,
                lastExpressionEvent = ExpressionId.LEFT_WINK,
            ),
        )
        capture(
            "right-eye-star",
            state.copy(
                expression = ExpressionId.RIGHT_WINK,
                expressionEventSequence = 5,
                lastExpressionEvent = ExpressionId.RIGHT_WINK,
            ),
        )
        capture(
            "turn-left-arrow",
            state.copy(
                expression = ExpressionId.NONE,
                headMotionEventSequence = 1,
                lastHeadMotion = HeadMotionId.TURN_LEFT,
            ),
        )
        capture(
            "turn-right-arrow",
            state.copy(
                headMotionEventSequence = 2,
                lastHeadMotion = HeadMotionId.TURN_RIGHT,
            ),
        )
        capture(
            "nod-yes",
            state.copy(
                headMotionEventSequence = 3,
                lastHeadMotion = HeadMotionId.NOD,
            ),
        )
        capture(
            "shake-no",
            state.copy(
                headMotionEventSequence = 4,
                lastHeadMotion = HeadMotionId.SHAKE,
            ),
        )
        composeRule.mainClock.autoAdvance = true
    }

    private fun faceState(
        mode: FaceExperienceMode = FaceExperienceMode.FREE,
        challenge: ChallengeUiState = ChallengeUiState(),
    ) = RecognitionUiState(
        domain = RecognitionDomain.FACE,
        faceExperienceMode = mode,
        challenge = challenge,
    )

    private fun previewState(headgear: HeadgearId, expression: ExpressionId): RecognitionUiState {
        val landmarks = MutableList(478) { Point3(0.5f, 0.5f, 0f) }
        landmarks[234] = Point3(0.30f, 0.50f, 0f)
        landmarks[454] = Point3(0.70f, 0.50f, 0f)
        landmarks[10] = Point3(0.50f, 0.25f, 0f)
        landmarks[152] = Point3(0.50f, 0.75f, 0f)
        landmarks[33] = Point3(0.40f, 0.43f, 0f)
        landmarks[133] = Point3(0.46f, 0.43f, 0f)
        landmarks[362] = Point3(0.54f, 0.43f, 0f)
        landmarks[263] = Point3(0.60f, 0.43f, 0f)
        return faceState().copy(
            facePresent = true,
            selectedHeadgear = headgear,
            faceLandmarks = landmarks,
            headPose = HeadPose(0f, 0f, 0f),
            inputWidth = 480,
            inputHeight = 640,
            lastFaceFrameTimestampMs = 1_000,
            expression = expression,
            expressionConfidence = 0.9f,
            expressionScores = mapOf(expression to 0.9f),
        )
    }
}
