package dev.system.app

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SignInScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun emailSignInValidatesAddressBeforeAllowingCodeRequest() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Continue with email").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Continue with email").assertIsDisplayed()
        composeRule.onNodeWithText("Send sign-in email").assertIsNotEnabled()

        composeRule.onNode(hasSetTextAction()).performTextInput("qa@example.com")

        composeRule.onNodeWithText("Send sign-in email").assertIsEnabled()
    }

    @Test
    fun nativeMagicLinkRedirectResolvesToSystem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val matches = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("dev.system.app://auth")),
            0,
        )

        assertTrue(matches.any { it.activityInfo.name == MainActivity::class.java.name })
    }
}
