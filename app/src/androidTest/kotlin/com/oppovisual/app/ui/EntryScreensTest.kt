package com.oppovisual.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oppovisual.app.TestHostActivity
import com.oppovisual.app.ui.theme.OppoVisualTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EntryScreensTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestHostActivity>()

    @Test
    fun onboardingInvokesContinue() {
        var continueCount = 0
        composeRule.setContent {
            OppoVisualTheme {
                OnboardingScreen(onContinue = { continueCount++ })
            }
        }

        composeRule.onNodeWithTag("onboarding_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_continue").performClick()
        composeRule.runOnIdle { assertEquals(1, continueCount) }
    }

    @Test
    fun firstPermissionRequestDoesNotOfferSettings() {
        composeRule.setContent {
            OppoVisualTheme {
                PermissionScreen(
                    permanentlyDenied = false,
                    onRequest = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithTag("permission_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("permission_request").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("permission_settings").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun deniedPermissionOffersRecoveryActions() {
        composeRule.setContent {
            OppoVisualTheme {
                PermissionScreen(
                    permanentlyDenied = true,
                    onRequest = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithTag("permission_request").assertIsDisplayed()
        composeRule.onNodeWithTag("permission_settings").assertIsDisplayed()
    }
}
