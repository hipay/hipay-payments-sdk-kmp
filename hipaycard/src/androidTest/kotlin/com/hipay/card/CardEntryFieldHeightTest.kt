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
 * Post-styling note: the SDK default field height is compact (42dp). The CVC "ⓘ" info icon is
 * rendered as a sibling BESIDE the field (not in its trailing slot), so it no longer triggers
 * Material3's 48dp trailing-affordance floor — the CVC field now honors 42dp and MATCHES Expiry
 * (delta ~0), identically on the shared Compose renderer (CMP/iOS). A wrapped label would still add
 * a whole text line (~20dp); the threshold below stays well under that to catch a wrap.
 * NETWORK-FREE: only incomplete (non-Luhn) BIN prefixes are typed.
 */
@RunWith(AndroidJUnit4::class)
class CardEntryFieldHeightTest {

    @get:Rule
    val composeRule = createComposeRule()

    // The CVC field now matches Expiry: the "ⓘ" stays in the trailing slot but its custom layout
    // reports zero height to the field, so it no longer triggers Material3's 48dp floor. Delta must
    // be ~0 (tight bound catches a regression back to the 48dp floor at ~6dp); a wrapped label would
    // add ~20dp. 2dp tolerates sub-pixel rounding only.
    private val maxIconFloorDelta = 2f

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
        composeRule.setContent { HiPayCardEntry(controller(), localeOverride = "en") }
        // Incomplete Visa prefix → CVC required ("Security code" + "ⓘ"); no suffix on the label.
        composeRule.onNodeWithTag(HiPayCardEntryTags.NUMBER).performTextInput("411111")
        assertCvcLabelDidNotWrap()
    }

    @Test
    fun cvcLabelStaysOneLine_monoMaestro() {
        composeRule.setContent { HiPayCardEntry(controller(), localeOverride = "en") }
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
