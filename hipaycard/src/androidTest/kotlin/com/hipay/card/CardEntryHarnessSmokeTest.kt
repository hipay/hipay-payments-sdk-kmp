package com.hipay.card

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hipay.card.ui.CardEntryHarnessTags
import com.hipay.card.ui.PlaceholderCardEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Story 7.1 — the Android Compose UI-test harness enabler (mirror of iOS story 5.3).
 *
 * Proves the harness can, against a hosted composable: (a) set content, (b) drive input,
 * (c) read semantics (label + a state), and (d) assert the relative field order — the
 * foundation stories 7.2–7.4 build their a11y/error assertions on. Network-free.
 */
@RunWith(AndroidJUnit4::class)
class CardEntryHarnessSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun harnessDrivesAndReadsTheCardComponent() {
        val robot = CardEntryRobot(composeRule)

        // (a) set content
        robot.setContent { PlaceholderCardEntry() }

        // (a) all four fields present
        robot.assertPresent(
            CardEntryHarnessTags.HOLDER,
            CardEntryHarnessTags.NUMBER,
            CardEntryHarnessTags.EXPIRY,
            CardEntryHarnessTags.CVC,
        )

        // (b) drive input and read it back (BIN-detectable but incomplete prefix; no network anyway)
        robot.type(CardEntryHarnessTags.NUMBER, "411111")
        robot.assertText(CardEntryHarnessTags.NUMBER, "411111")

        // (c) read a label (contentDescription) and a state (stateDescription)
        robot.assertContentDescription(CardEntryHarnessTags.HOLDER, "Card holder")
        robot.assertState(CardEntryHarnessTags.CVC, "disabled")

        // (d) assert relative field order holder -> number -> expiry -> cvc
        robot.assertFieldOrder(
            CardEntryHarnessTags.HOLDER,
            CardEntryHarnessTags.NUMBER,
            CardEntryHarnessTags.EXPIRY,
            CardEntryHarnessTags.CVC,
        )
    }
}
