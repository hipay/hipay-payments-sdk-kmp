package com.hipay.card

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPaySettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SDK-wide [HiPaySettings] locale drives the component when no per-component `localeOverride` is
 * passed, and a runtime change re-localizes the SAME live component — no re-init. Matching is
 * case-insensitive ("FR" → French). Device-locale-independent: the settings force the language.
 */
@RunWith(AndroidJUnit4::class)
class CardEntrySettingsLocaleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun controllerWith(settings: HiPaySettings) =
        HiPayCardEntryController(HiPayConfig("test-user", "test-pass", Environment.STAGE, settings))

    @Test
    fun settingsLocale_drivesLanguage_andFlipsLiveWithoutReinit() {
        val settings = HiPaySettings("en") // start English
        composeRule.setContent { HiPayCardEntry(controllerWith(settings)) } // no per-component override

        composeRule.onNodeWithText("Cardholder name").assertIsDisplayed() // English holder label

        settings.setLocaleOverride("FR") // runtime flip, case-insensitive — same component
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Nom du titulaire").assertIsDisplayed() // French, no re-init
        composeRule.onNodeWithText("Cardholder name").assertDoesNotExist()
    }

    @Test
    fun perComponentOverride_winsOverSettings() {
        val settings = HiPaySettings("it")
        composeRule.setContent { HiPayCardEntry(controllerWith(settings), localeOverride = "en") }
        // localeOverride "en" beats settings "it".
        composeRule.onNodeWithText("Cardholder name").assertIsDisplayed()
    }
}
