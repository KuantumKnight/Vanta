package dev.system.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SignInScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun publicAppIdentityUsesVanta() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appLabel = context.getString(context.applicationInfo.labelRes)
        val widgetInfo = context.packageManager.getReceiverInfo(
            android.content.ComponentName(context, dev.system.app.widget.SystemWidgetReceiver::class.java),
            android.content.pm.PackageManager.ComponentInfoFlags.of(0L),
        )
        val widgetLabel = context.getString(widgetInfo.labelRes)

        assertEquals("Vanta", appLabel)
        assertEquals("Vanta · NOW / NEXT", widgetLabel)
        composeRule.onNodeWithText("Vanta").assertIsDisplayed()
    }

    @Test
    fun mainActivityStartsAndInitializesLocalRepositories() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SystemApplication

        application.database.openHelper.writableDatabase
        runBlocking { application.userPreferencesRepository.preferences.first() }
        composeRule.onNodeWithText("Continue with email").assertIsDisplayed()
    }

    @Test
    fun emailSignInChecksAddressBeforeEnablingLinkRequest() {
        composeRule.onNodeWithText("Continue with email").assertIsDisplayed()
        composeRule.onNodeWithText("Email me a sign-in link").assertIsNotEnabled()

        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SystemApplication
        val emailField = composeRule.onNode(hasSetTextAction())
        emailField.performTextInput("not-an-email")
        composeRule.onNodeWithText("Email me a sign-in link").assertIsNotEnabled()

        emailField.performTextReplacement("qa@example.com")
        if (application.repository.isConfigured) {
            composeRule.onNodeWithText("Email me a sign-in link").assertIsEnabled()
        } else {
            composeRule.onNodeWithText("Connect Supabase to enable sign-in.").assertIsDisplayed()
            composeRule.onNodeWithText("Email me a sign-in link").assertIsNotEnabled()
        }
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
