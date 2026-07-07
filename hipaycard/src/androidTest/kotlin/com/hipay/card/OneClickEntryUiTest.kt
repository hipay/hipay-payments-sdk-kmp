package com.hipay.card

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hipay.card.store.SavedCard
import com.hipay.card.store.createSecureCardStore
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One-click UI on the card component — sections, selection, save switch — against the
 * real store (seeded per test). No network: nothing here reaches the gateway.
 */
@RunWith(AndroidJUnit4::class)
class OneClickEntryUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val config = HiPayConfig("oneclick-ui-test-user", "pw", Environment.STAGE)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun seedCard(holder: String = "JANE DOE") = runBlocking(Dispatchers.IO) {
        val card = SavedCard(
            token = "t".repeat(64), maskedPan = "411111xxxxxx1111", network = "VISA",
            holder = holder, expiryMonth = "12", expiryYear = "2031",
        )
        assertTrue(createSecureCardStore(context, config).save(card, consentGiven = true))
    }

    @After
    fun clearStore() {
        runBlocking(Dispatchers.IO) { createSecureCardStore(context, config).clearAll() }
    }

    private fun awaitSections() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag(HiPayCardEntryTags.SAVED_CARD))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun optInOff_rendersNoOneClickUi_evenWithASeededCard() {
        seedCard()
        val controller = HiPayCardEntryController(config) // oneClickEnabled defaults to false
        composeRule.setContent { HiPayCardEntry(controller) }
        composeRule.onNodeWithTag(HiPayCardEntryTags.HOLDER).assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasTestTag(HiPayCardEntryTags.SAVED_CARD),
            ).fetchSemanticsNodes().size,
        )
        assertEquals(
            0,
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasTestTag(HiPayCardEntryTags.SAVE_SWITCH),
            ).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun withASavedCard_sectionsRenderAndTheCardIsPreselected_fieldsHidden() {
        seedCard()
        val controller = HiPayCardEntryController(config, oneClickEnabled = true)
        composeRule.setContent { HiPayCardEntry(controller) }
        awaitSections()
        composeRule.onNodeWithTag(HiPayCardEntryTags.SAVED_CARD).assertIsSelected()
        composeRule.onNodeWithTag(HiPayCardEntryTags.NEW_CARD).assertIsNotSelected()
        // Bullet-masked display, last4 only — the BIN never shows.
        composeRule.onNodeWithText("•••• •••• •••• 1111").assertIsDisplayed()
        // Entry fields are not rendered while the saved card is selected.
        assertEquals(
            0,
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasTestTag(HiPayCardEntryTags.HOLDER),
            ).fetchSemanticsNodes().size,
        )
        assertTrue(controller.canPay) // one tap away
    }

    @Test
    fun withoutCards_noHeaders_fieldsAndSwitchOnly() {
        val controller = HiPayCardEntryController(config, oneClickEnabled = true)
        composeRule.setContent { HiPayCardEntry(controller) }
        composeRule.onNodeWithTag(HiPayCardEntryTags.HOLDER).assertIsDisplayed()
        composeRule.onNodeWithTag(HiPayCardEntryTags.SAVE_SWITCH).assertIsDisplayed()
        composeRule.onNodeWithTag(HiPayCardEntryTags.CONSENT).assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasTestTag(HiPayCardEntryTags.SAVED_CARD),
            ).fetchSemanticsNodes().size,
        )
        assertFalse(controller.canPay) // empty fields, no saved card
    }

    @Test
    fun selectingNewCard_expandsFields_andTypedValuesSurviveTheRoundTrip() {
        seedCard()
        val controller = HiPayCardEntryController(config, oneClickEnabled = true)
        composeRule.setContent { HiPayCardEntry(controller) }
        awaitSections()

        // Expand "New card": fields appear, the saved card is deselected.
        composeRule.onNodeWithTag(HiPayCardEntryTags.NEW_CARD).performClick()
        composeRule.onNodeWithTag(HiPayCardEntryTags.SAVED_CARD).assertIsNotSelected()
        composeRule.onNodeWithTag(HiPayCardEntryTags.HOLDER).assertIsDisplayed()
        composeRule.onNodeWithTag(HiPayCardEntryTags.HOLDER).performTextInput("MARIE MARTIN")

        // Back to the saved card: fields collapse but NOTHING is cleared…
        composeRule.onNodeWithTag(HiPayCardEntryTags.SAVED_CARD).performClick()
        composeRule.onNodeWithTag(HiPayCardEntryTags.SAVED_CARD).assertIsSelected()
        assertEquals("MARIE MARTIN", controller.holder)

        // …and re-expanding shows the preserved value.
        composeRule.onNodeWithTag(HiPayCardEntryTags.NEW_CARD).performClick()
        composeRule.onNodeWithText("MARIE MARTIN").assertIsDisplayed()
    }

    @Test
    fun saveSwitch_togglesConsentState() {
        seedCard()
        val controller = HiPayCardEntryController(config, oneClickEnabled = true)
        composeRule.setContent { HiPayCardEntry(controller) }
        awaitSections()
        composeRule.onNodeWithTag(HiPayCardEntryTags.NEW_CARD).performClick()
        composeRule.onNodeWithTag(HiPayCardEntryTags.SAVE_SWITCH).assertIsOff()
        assertFalse(controller.saveCardOptIn)
        composeRule.onNodeWithTag(HiPayCardEntryTags.SAVE_SWITCH).performClick()
        composeRule.onNodeWithTag(HiPayCardEntryTags.SAVE_SWITCH).assertIsOn()
        assertTrue(controller.saveCardOptIn)
    }
}
