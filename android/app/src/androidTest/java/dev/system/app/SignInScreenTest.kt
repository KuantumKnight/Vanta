package dev.system.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class SignInScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun emailSignInValidatesAddressBeforeAllowingCodeRequest() {
        composeRule.onNodeWithText("Continue with email").assertIsDisplayed()
        composeRule.onNodeWithText("Send one-time code").assertIsNotEnabled()

        composeRule.onNode(hasSetTextAction()).performTextInput("qa@example.com")

        composeRule.onNodeWithText("Send one-time code").assertIsEnabled()
    }
}
