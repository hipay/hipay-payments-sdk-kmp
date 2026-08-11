package com.hipay.card

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Story 7.2 — instrumented behavior tests for the real Compose card component,
 * built on the story 7.1 harness. NETWORK-FREE: only incomplete (non-Luhn) BIN
 * prefixes are typed, so `resolveCardInfo`/tokenization never fire. The full
 * card + 3DS flow is a stage/manual check (story 7.5).
 */
@RunWith(AndroidJUnit4::class)
class CardEntryHarnessSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun controller() =
        HiPayCardEntryController(HiPayConfig("test-user", "test-pass", Environment.STAGE)).withOfflineCeiling()

    @Test
    fun rendersFormatsAndDetectsVisa() {
        val robot = CardEntryRobot(composeRule)
        // Pin the locale so English string assertions are deterministic regardless of the
        // emulator's device locale (matches the one-click tests' convention).
        robot.setContent { HiPayCardEntry(controller(), localeOverride = "en") }

        robot.assertPresent(
            HiPayCardEntryTags.HOLDER,
            HiPayCardEntryTags.NUMBER,
            HiPayCardEntryTags.EXPIRY,
            HiPayCardEntryTags.CVC,
        )

        // Strings resolve from strings.xml (default EN locale on the emulator).
        composeRule.onNodeWithText("Cardholder name").assertExists()

        // Incomplete Visa prefix → local detection only (no network).
        robot.type(HiPayCardEntryTags.NUMBER, "411111")
        robot.assertText(HiPayCardEntryTags.NUMBER, "4111 11") // CardNetworks.format groups of 4

        // The Visa chip renders and is the default selection.
        robot.assertPresent(HiPayCardEntryTags.network("visa"))
        robot.assertSelected(HiPayCardEntryTags.network("visa"), selected = true)

        // Visa requires a CVC → the field is enabled.
        robot.assertEnabled(HiPayCardEntryTags.CVC, enabled = true)

        // Relative order: holder above number above expiry; number above cvc
        // (expiry & cvc share a row, so they are not ordered against each other).
        robot.assertVerticalOrder(
            HiPayCardEntryTags.HOLDER,
            HiPayCardEntryTags.NUMBER,
            HiPayCardEntryTags.EXPIRY,
        )
        robot.assertVerticalOrder(HiPayCardEntryTags.NUMBER, HiPayCardEntryTags.CVC)
    }

    @Test
    fun monoMaestroRequiresCvc() {
        val robot = CardEntryRobot(composeRule)
        // Pin the locale so English string assertions are deterministic regardless of the
        // emulator's device locale (matches the one-click tests' convention).
        robot.setContent { HiPayCardEntry(controller(), localeOverride = "en") }

        // Incomplete Maestro prefix (starts with 50). Locally this is a MONO Maestro (no co-brand
        // detected → offered = [Maestro]) → CVC IS required → field enabled (story 11.5).
        robot.type(HiPayCardEntryTags.NUMBER, "5018")
        robot.assertPresent(HiPayCardEntryTags.network("maestro"))
        robot.assertEnabled(HiPayCardEntryTags.CVC, enabled = true)
    }
}
