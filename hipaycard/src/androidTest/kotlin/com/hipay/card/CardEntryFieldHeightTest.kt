package com.hipay.card

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Story 11.3 — the CVC label must stay on one line so the CVC field keeps the standard height and
 * stays symmetric with the Expiry field in the same row. A 2-line-wrapped label would make the CVC
 * field taller; asserting equal heights is the automatable proxy for "one line + standard height".
 * NETWORK-FREE: only incomplete (non-Luhn) BIN prefixes are typed.
 */
@RunWith(AndroidJUnit4::class)
class CardEntryFieldHeightTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun controller() =
        HiPayCardEntryController(HiPayConfig("test-user", "test-pass", Environment.STAGE))

    // Clipped (visible) field bounds — reflects what the user actually sees.
    private fun heightOf(tag: String): Float =
        composeRule.onNodeWithTag(tag).getBoundsInRoot().height.value

    @Test
    fun cvcMatchesExpiryHeight_whenCvcRequired() {
        composeRule.setContent { HiPayCardEntry(controller()) }
        // Incomplete Visa prefix → CVC required ("Security code" + "ⓘ"); no suffix on the label.
        composeRule.onNodeWithTag(HiPayCardEntryTags.NUMBER).performTextInput("411111")
        composeRule.waitForIdle()
        assertEquals(heightOf(HiPayCardEntryTags.EXPIRY), heightOf(HiPayCardEntryTags.CVC), 1f)
    }

    @Test
    fun cvcMatchesExpiryHeight_monoMaestro() {
        composeRule.setContent { HiPayCardEntry(controller()) }
        // Incomplete Maestro prefix → mono Maestro → CVC required + enabled (story 11.5); the
        // label stays "Security code" and the field keeps the standard height (the 11.3 invariant).
        composeRule.onNodeWithTag(HiPayCardEntryTags.NUMBER).performTextInput("5018")
        composeRule.waitForIdle()
        assertEquals(heightOf(HiPayCardEntryTags.EXPIRY), heightOf(HiPayCardEntryTags.CVC), 1f)
    }

    @Test
    fun cvcMatchesExpiryHeight_frenchLongestLabel() {
        // French "Code de sécurité" is the longest CVC label — worst case for one-line fit.
        composeRule.setContent { HiPayCardEntry(controller(), localeOverride = "fr") }
        composeRule.onNodeWithTag(HiPayCardEntryTags.NUMBER).performTextInput("411111")
        composeRule.waitForIdle()
        assertEquals(heightOf(HiPayCardEntryTags.EXPIRY), heightOf(HiPayCardEntryTags.CVC), 1f)
    }
}
