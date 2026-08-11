package com.hipay.card

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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
 * One-click UI on the card component — sections, multi-card list, selection, collapse, save
 * switch — against the real store (seeded per test). No network: nothing here reaches the gateway.
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

    /** Seeds 3 distinct cards in order, so the store's MRU-first list is [3333, 2222, 1111]. */
    private fun seedCards() = runBlocking(Dispatchers.IO) {
        val store = createSecureCardStore(context, config)
        listOf(
            Triple("411111xxxxxx1111", "VISA", "CARD ONE"),
            Triple("510510xxxxxx2222", "MASTERCARD", "CARD TWO"),
            Triple("411111xxxxxx3333", "VISA", "CARD THREE"),
        ).forEachIndexed { i, (pan, net, holder) ->
            assertTrue(
                store.save(
                    SavedCard(
                        token = i.toString().repeat(64), maskedPan = pan, network = net,
                        holder = holder, expiryMonth = "12", expiryYear = "2031",
                    ),
                    consentGiven = true,
                ),
            )
        }
    }

    @After
    fun clearStore() {
        runBlocking(Dispatchers.IO) { createSecureCardStore(context, config).clearAll() }
    }

    private fun awaitSections() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag(HiPayCardEntryTags.savedCard(0)))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun countTag(tag: String): Int =
        composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun optInOff_rendersNoOneClickUi_evenWithASeededCard() {
        seedCard()
        val controller = HiPayCardEntryController(config).withOfflineCeiling() // oneClickEnabled defaults to false
        composeRule.setContent { HiPayCardEntry(controller) }
        composeRule.onNodeWithTag(HiPayCardEntryTags.HOLDER).assertIsDisplayed()
        assertEquals(0, countTag(HiPayCardEntryTags.savedCard(0)))
        assertEquals(0, countTag(HiPayCardEntryTags.SAVE_SWITCH))
    }

    @Test
    fun withASavedCard_sectionsRenderAndTheCardIsPreselected_fieldsHidden() {
        seedCard()
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        // Pin EN so the "New card" collapsed/expanded state description is deterministic.
        composeRule.setContent { HiPayCardEntry(controller, localeOverride = "en") }
        awaitSections()
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(0)).assertIsSelected()
        // "New card" is a button that reads its expanded/collapsed state (no radio selection).
        composeRule.onNodeWithTag(HiPayCardEntryTags.NEW_CARD)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "collapsed"))
        // A single card has no collapsible "Saved cards" header (identical to the single-card layout).
        assertEquals(0, countTag(HiPayCardEntryTags.SAVED_CARDS_HEADER))
        // Bullet-masked display, last4 only — the BIN never shows.
        composeRule.onNodeWithText("•••• •••• •••• 1111").assertIsDisplayed()
        // Entry fields are not rendered while the saved card is selected.
        assertEquals(0, countTag(HiPayCardEntryTags.HOLDER))
        assertTrue(controller.canPay) // one tap away
    }

    @Test
    fun withoutCards_noHeaders_fieldsAndSwitchOnly() {
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller) }
        composeRule.onNodeWithTag(HiPayCardEntryTags.HOLDER).assertIsDisplayed()
        composeRule.onNodeWithTag(HiPayCardEntryTags.SAVE_SWITCH).assertIsDisplayed()
        composeRule.onNodeWithTag(HiPayCardEntryTags.CONSENT).assertIsDisplayed()
        assertEquals(0, countTag(HiPayCardEntryTags.savedCard(0)))
        assertFalse(controller.canPay) // empty fields, no saved card
    }

    @Test
    fun selectingNewCard_expandsFields_andTypedValuesSurviveTheRoundTrip() {
        seedCard()
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        // Pin EN so the "New card" expanded/collapsed state description is deterministic.
        composeRule.setContent { HiPayCardEntry(controller, localeOverride = "en") }
        awaitSections()

        // Expand "New card": fields appear, the saved card is deselected, header reads "expanded".
        composeRule.onNodeWithTag(HiPayCardEntryTags.NEW_CARD).performClick()
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(0)).assertIsNotSelected()
        composeRule.onNodeWithTag(HiPayCardEntryTags.NEW_CARD)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "expanded"))
        composeRule.onNodeWithTag(HiPayCardEntryTags.HOLDER).assertIsDisplayed()
        composeRule.onNodeWithTag(HiPayCardEntryTags.HOLDER).performTextInput("MARIE MARTIN")

        // Back to the saved card: fields collapse but NOTHING is cleared…
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(0)).performClick()
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(0)).assertIsSelected()
        assertEquals("MARIE MARTIN", controller.holder)

        // …and re-expanding shows the preserved value.
        composeRule.onNodeWithTag(HiPayCardEntryTags.NEW_CARD).performClick()
        composeRule.onNodeWithText("MARIE MARTIN").assertIsDisplayed()
    }

    @Test
    fun multipleCards_renderMostRecentFirst_andTheMruIsPreselected() {
        seedCards()
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller) }
        awaitSections()
        // All three cells render; MRU (last saved = 3333) is index 0 and pre-selected.
        assertEquals(1, countTag(HiPayCardEntryTags.savedCard(0)))
        assertEquals(1, countTag(HiPayCardEntryTags.savedCard(1)))
        assertEquals(1, countTag(HiPayCardEntryTags.savedCard(2)))
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(0)).assertIsSelected()
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(1)).assertIsNotSelected()
        composeRule.onNodeWithText("•••• •••• •••• 3333").assertIsDisplayed() // MRU on top
        composeRule.onNodeWithText("•••• •••• •••• 1111").assertIsDisplayed() // oldest still listed
    }

    @Test
    fun tappingAnotherCard_movesTheSelection() {
        seedCards()
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller) }
        awaitSections()
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(1)).performClick()
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(1)).assertIsSelected()
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(0)).assertIsNotSelected()
        assertEquals(controller.savedCards[1], controller.selectedSavedCard)
        assertTrue(controller.canPay)
    }

    @Test
    fun saveSwitch_togglesConsentState() {
        seedCard()
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller) }
        awaitSections()
        composeRule.onNodeWithTag(HiPayCardEntryTags.NEW_CARD).performClick()
        composeRule.onNodeWithTag(HiPayCardEntryTags.SAVE_SWITCH).assertIsOff()
        assertFalse(controller.saveCardOptIn)
        composeRule.onNodeWithTag(HiPayCardEntryTags.SAVE_SWITCH).performClick()
        composeRule.onNodeWithTag(HiPayCardEntryTags.SAVE_SWITCH).assertIsOn()
        assertTrue(controller.saveCardOptIn)
    }

    // Left-swipe reveals a trash action; confirming deletes, an accidental swipe alone
    // never does. No trash is shown at rest (reverses hidden delete — now visible on swipe).
    @Test
    fun swipeRevealsTrash_confirm_deletesCard() {
        seedNCards(2) // both fit the default display count of 3
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller) }
        awaitSections()
        val before = controller.savedCards.size
        assertEquals(0, countTag(HiPayCardEntryTags.savedCardDelete(0))) // no trash at rest
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(0)).performTouchInput { swipeLeft() }
        composeRule.waitUntil(5_000) { countTag(HiPayCardEntryTags.savedCardDelete(0)) == 1 }
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCardDelete(0)).performClick()
        composeRule.onNodeWithTag(HiPayCardEntryTags.CONFIRM_DELETE).assertExists()
        composeRule.onNodeWithTag(HiPayCardEntryTags.CONFIRM_DELETE).performClick()
        composeRule.waitUntil(5_000) { controller.savedCards.size == before - 1 }
        assertEquals(before - 1, controller.savedCards.size)
    }

    @Test
    fun swipeRevealsTrash_cancel_keepsCard() {
        seedNCards(2)
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller) }
        awaitSections()
        val before = controller.savedCards.size
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(0)).performTouchInput { swipeLeft() }
        composeRule.waitUntil(5_000) { countTag(HiPayCardEntryTags.savedCardDelete(0)) == 1 }
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCardDelete(0)).performClick()
        composeRule.onNodeWithTag(HiPayCardEntryTags.CONFIRM_CANCEL).performClick()
        assertEquals(before, controller.savedCards.size)
    }

    // collapse-to-MRU model is replaced by a display count + a "Show more / Show less" toggle.
    @Test
    fun showMore_togglesCardsBeyondTheDefaultDisplayCount() {
        seedNCards(4) // default display count = 3
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller) }
        awaitSections()
        // The first 3 (MRU-first) show; the 4th is behind "Show more".
        assertEquals(1, countTag(HiPayCardEntryTags.savedCard(0)))
        assertEquals(1, countTag(HiPayCardEntryTags.savedCard(2)))
        assertEquals(0, countTag(HiPayCardEntryTags.savedCard(3)))
        assertEquals(1, countTag(HiPayCardEntryTags.SHOW_MORE))
        // Expand: the rest appears and the toggle STAYS present (now "Show less").
        composeRule.onNodeWithTag(HiPayCardEntryTags.SHOW_MORE).performClick()
        assertEquals(1, countTag(HiPayCardEntryTags.savedCard(3)))
        assertEquals(1, countTag(HiPayCardEntryTags.SHOW_MORE))
        // Collapse again: the 4th hides, the toggle stays.
        composeRule.onNodeWithTag(HiPayCardEntryTags.SHOW_MORE).performClick()
        assertEquals(0, countTag(HiPayCardEntryTags.savedCard(3)))
        assertEquals(1, countTag(HiPayCardEntryTags.SHOW_MORE))
    }

    // The paying card is never hidden — selecting a card beyond the default fold force-expands the
    // list and disables "Show less" until a card within the fold is selected.
    @Test
    fun selectingCardBeyondFold_forcesExpandAndDisablesShowLess() {
        seedNCards(4) // default display count = 3
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller) }
        awaitSections()
        // Expand, then select the 4th card (index 3, beyond the fold of 3).
        composeRule.onNodeWithTag(HiPayCardEntryTags.SHOW_MORE).performClick()
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(3)).performClick()
        assertEquals(controller.savedCards[3], controller.selectedSavedCard)
        // The list stays expanded (4th visible) and "Show less" is disabled.
        assertEquals(1, countTag(HiPayCardEntryTags.savedCard(3)))
        composeRule.onNodeWithTag(HiPayCardEntryTags.SHOW_MORE).assertIsNotEnabled()
        // Selecting a card within the fold re-enables "Show less".
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(0)).performClick()
        composeRule.onNodeWithTag(HiPayCardEntryTags.SHOW_MORE).assertIsEnabled()
    }

    @Test
    fun showMore_absentWhenAllCardsFitTheDisplayCount() {
        seedCards() // 3 cards, default display count 3 → nothing hidden
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller) }
        awaitSections()
        assertEquals(1, countTag(HiPayCardEntryTags.savedCard(2)))
        assertEquals(0, countTag(HiPayCardEntryTags.SHOW_MORE))
    }

    @Test
    fun displayCount_isClampedToOneToTen() {
        assertEquals(1, HiPayCardEntryController(config, savedCardsDisplayCount = 0).savedCardsDisplayCount)
        assertEquals(10, HiPayCardEntryController(config, savedCardsDisplayCount = 99).savedCardsDisplayCount)
        assertEquals(2, HiPayCardEntryController(config, savedCardsDisplayCount = 2).savedCardsDisplayCount)
    }

    @Test
    fun customDisplayCount_boundsTheVisibleList() {
        seedCards() // 3 cards
        val controller =
            HiPayCardEntryController(config, oneClickEnabled = true, savedCardsDisplayCount = 1).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller) }
        awaitSections()
        assertEquals(1, countTag(HiPayCardEntryTags.savedCard(0)))
        assertEquals(0, countTag(HiPayCardEntryTags.savedCard(1))) // hidden behind Show more
        assertEquals(1, countTag(HiPayCardEntryTags.SHOW_MORE))
    }

    /** Seeds [n] distinct cards in order → the store's MRU-first list is [n-1 … 0]. */
    private fun seedNCards(n: Int) = runBlocking(Dispatchers.IO) {
        val store = createSecureCardStore(context, config)
        repeat(n) { i ->
            assertTrue(
                store.save(
                    SavedCard(
                        token = i.toString().repeat(64),
                        maskedPan = "411111xxxxxx${1000 + i}",
                        network = "VISA", holder = "CARD $i", expiryMonth = "12", expiryYear = "2031",
                    ),
                    consentGiven = true,
                ),
            )
        }
    }

}
