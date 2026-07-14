package com.hipay.card

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The CVC label must stay on ONE line so it never wraps and inflates the CVC field. A 2-line label
 * would add a whole text line (~20dp) to the field; asserting the CVC field is at most a small
 * delta taller than the Expiry field is the automatable proxy for "one line, no wrap".
 *
 * Post-styling note: the SDK default field height is compact (42dp), but Material3's decoration
 * floors any field carrying a TRAILING affordance at a 48dp touch-target minimum. The CVC field
 * has the "ⓘ" info icon, so it sits at that 48dp floor while Expiry (no trailing) sits at 42 — a
 * fixed ~6dp gap that is the same on the shared Compose renderer (CMP/iOS). A wrapped label would
 * push the gap far beyond that; the threshold below separates the two cases.
 * NETWORK-FREE: only incomplete (non-Luhn) BIN prefixes are typed.
 */
@RunWith(AndroidJUnit4::class)
class CardEntryFieldHeightTest {

    @get:Rule
    val composeRule = createComposeRule()

    // The Material touch-target floor makes a trailing-icon field ~6dp taller than a plain one; a
    // wrapped second label line would add far more (~20dp). Anything under this threshold means
    // the label stayed on one line.
    private val maxIconFloorDelta = 8f

    private fun controller() =
        HiPayCardEntryController(HiPayConfig("test-user", "test-pass", Environment.STAGE))

    // Clipped (visible) field bounds — reflects what the user actually sees.
    private fun heightOf(tag: String): Float =
        composeRule.onNodeWithTag(tag).getBoundsInRoot().height.value

    private fun assertCvcLabelDidNotWrap() {
        composeRule.waitForIdle()
        val delta = heightOf(HiPayCardEntryTags.CVC) - heightOf(HiPayCardEntryTags.EXPIRY)
        // Two-sided: above the ceiling = the label wrapped; below the floor = the CVC field
        // collapsed or the wrong node was measured — both are failures, not passes.
        assertTrue(
            "CVC−Expiry height delta is $delta dp — expected in -1..$maxIconFloorDelta " +
                "(above: label wrapped to a second line; below: CVC field collapsed)",
            delta in -1f..maxIconFloorDelta,
        )
    }

    @Test
    fun cvcLabelStaysOneLine_whenCvcRequired() {
        composeRule.setContent { HiPayCardEntry(controller()) }
        // Incomplete Visa prefix → CVC required ("Security code" + "ⓘ"); no suffix on the label.
        composeRule.onNodeWithTag(HiPayCardEntryTags.NUMBER).performTextInput("411111")
        assertCvcLabelDidNotWrap()
    }

    @Test
    fun cvcLabelStaysOneLine_monoMaestro() {
        composeRule.setContent { HiPayCardEntry(controller()) }
        // Incomplete Maestro prefix → mono Maestro → CVC required + enabled; the label stays
        // "Security code" and the field keeps a single line.
        composeRule.onNodeWithTag(HiPayCardEntryTags.NUMBER).performTextInput("5018")
        assertCvcLabelDidNotWrap()
    }

    @Test
    fun cvcLabelStaysOneLine_frenchLongestLabel() {
        // French "Code de sécurité" is the longest CVC label — worst case for one-line fit.
        composeRule.setContent { HiPayCardEntry(controller(), localeOverride = "fr") }
        composeRule.onNodeWithTag(HiPayCardEntryTags.NUMBER).performTextInput("411111")
        assertCvcLabelDidNotWrap()
    }
}
