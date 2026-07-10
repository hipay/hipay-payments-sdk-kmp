package com.hipay.card

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hipay.card.store.OneClickError
import com.hipay.card.store.OneClickErrorReason
import com.hipay.card.store.SavedCard
import com.hipay.card.store.createSecureCardStore
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One-click decline recovery on the card component — the inline per-card error, the
 * section-level "card removed" notice, the "new card" fallback and the transient clears —
 * against the real store (seeded per test). Failure states that would need a gateway stub
 * (declined / token-invalid) are driven through the controller's internal outcome seam; the
 * real `payWithSavedCard` failure wiring is covered by the unauthenticated-gateway test below.
 */
@RunWith(AndroidJUnit4::class)
class OneClickDeclineUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val config = HiPayConfig("oneclick-decline-test-user", "pw", Environment.STAGE)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Localized copy under localeOverride="en" (values/strings.xml).
    private val declinedText = "Payment declined. Try another card or enter a new one."
    private val removedText = "This card can no longer be used and was removed. Pay with a new card."

    private fun testCard(suffix: String = "1111", token: Char = 't') = SavedCard(
        token = token.toString().repeat(64), maskedPan = "411111xxxxxx$suffix", network = "VISA",
        holder = "JANE DOE", expiryMonth = "12", expiryYear = "2031",
    )

    private fun seed(vararg cards: SavedCard) = runBlocking(Dispatchers.IO) {
        val store = createSecureCardStore(context, config)
        cards.forEach { assertTrue(store.save(it, consentGiven = true)) }
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

    private fun setError(controller: HiPayCardEntryController, error: OneClickError?) {
        composeRule.runOnUiThread { controller.lastOneClickError = error }
        composeRule.waitForIdle()
    }

    @Test
    fun declinedError_rendersInlineOnTheCell_fallbackReachable_cardRetryable() {
        seed(testCard())
        val controller = HiPayCardEntryController(config, oneClickEnabled = true)
        composeRule.setContent { HiPayCardEntry(controller, localeOverride = "en") }
        awaitSections()

        setError(controller, OneClickError(controller.savedCards.first(), OneClickErrorReason.DECLINED))

        // Inline on the affected cell: icon + localized message (non-colour-only).
        composeRule.onNodeWithTag(HiPayCardEntryTags.error("savedcard.0"), useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(declinedText, useUnmergedTree = true).assertIsDisplayed()
        // No section-level notice for a still-listed card.
        assertEquals(0, countTag(HiPayCardEntryTags.error("oneclick.section")))
        // The fallback stays one action away and the card itself stays selectable (retry).
        composeRule.onNodeWithTag(HiPayCardEntryTags.NEW_CARD).assertIsDisplayed()
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(0)).performClick()
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(0)).assertIsSelected()
        // Re-selecting is a fresh intent: the error is cleared.
        composeRule.waitForIdle()
        assertEquals(0, countTag(HiPayCardEntryTags.error("savedcard.0")))
        assertNull(controller.lastOneClickError)
    }

    @Test
    fun error_isClearedOnSelectingNewCard() {
        seed(testCard())
        val controller = HiPayCardEntryController(config, oneClickEnabled = true)
        composeRule.setContent { HiPayCardEntry(controller, localeOverride = "en") }
        awaitSections()

        setError(controller, OneClickError(controller.savedCards.first(), OneClickErrorReason.THREE_DS_FAILED))
        assertEquals(1, countTag(HiPayCardEntryTags.error("savedcard.0")))

        composeRule.onNodeWithTag(HiPayCardEntryTags.NEW_CARD).performClick()
        composeRule.waitForIdle()
        assertEquals(0, countTag(HiPayCardEntryTags.error("savedcard.0")))
        assertNull(controller.lastOneClickError)
    }

    @Test
    fun error_isClearedOnANewCardFieldEdit() {
        seed(testCard())
        val controller = HiPayCardEntryController(config, oneClickEnabled = true)
        composeRule.setContent { HiPayCardEntry(controller, localeOverride = "en") }
        awaitSections()

        // Enter the new-card branch FIRST (selection change clears), then surface an error:
        // the affected card is the visible MRU cell, the entry fields are open below it.
        composeRule.onNodeWithTag(HiPayCardEntryTags.NEW_CARD).performClick()
        composeRule.waitForIdle()
        setError(controller, OneClickError(controller.savedCards.first(), OneClickErrorReason.GENERIC))
        assertEquals(1, countTag(HiPayCardEntryTags.error("savedcard.0")))

        composeRule.onNodeWithTag(HiPayCardEntryTags.HOLDER).performTextInput("J")
        composeRule.waitForIdle()
        assertEquals(0, countTag(HiPayCardEntryTags.error("savedcard.0")))
        assertNull(controller.lastOneClickError)
    }

    @Test
    fun tokenInvalid_lastCardPurged_sectionMessageShows_entryFieldsUsable() {
        val card = testCard()
        seed(card)
        val controller = HiPayCardEntryController(config, oneClickEnabled = true)
        composeRule.setContent { HiPayCardEntry(controller, localeOverride = "en") }
        awaitSections()

        // Reproduce the observable state the controller leaves after its token-invalid purge:
        // the card is gone from the store and the list, the selection fell back to new-card,
        // and the error (set BEFORE the reload in the real path) is still present.
        runBlocking(Dispatchers.IO) { createSecureCardStore(context, config).delete(card) }
        runBlocking { controller.refreshSavedCards() }
        composeRule.waitForIdle()
        setError(controller, OneClickError(card, OneClickErrorReason.TOKEN_INVALID))

        // Section-level notice (no cell left to anchor to) + re-entry immediately usable.
        composeRule.onNodeWithTag(HiPayCardEntryTags.error("oneclick.section"), useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(removedText, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, countTag(HiPayCardEntryTags.savedCard(0)))
        composeRule.onNodeWithTag(HiPayCardEntryTags.HOLDER).assertIsDisplayed()
        composeRule.onNodeWithTag(HiPayCardEntryTags.HOLDER).performTextInput("J")
        composeRule.waitForIdle()
        assertEquals(0, countTag(HiPayCardEntryTags.error("oneclick.section"))) // edit clears
    }

    @Test
    fun tokenInvalid_withAnotherCardRemaining_sectionMessageAboveTheList() {
        val purged = testCard(suffix = "2222", token = 'u')
        seed(testCard(), purged) // MRU-first list: [2222, 1111]
        val controller = HiPayCardEntryController(config, oneClickEnabled = true)
        composeRule.setContent { HiPayCardEntry(controller, localeOverride = "en") }
        awaitSections()

        runBlocking(Dispatchers.IO) { createSecureCardStore(context, config).delete(purged) }
        runBlocking { controller.refreshSavedCards() }
        composeRule.waitForIdle()
        setError(controller, OneClickError(purged, OneClickErrorReason.TOKEN_INVALID))

        composeRule.onNodeWithTag(HiPayCardEntryTags.error("oneclick.section"), useUnmergedTree = true)
            .assertIsDisplayed()
        // The surviving card is listed normally, without an inline error of its own.
        assertEquals(1, countTag(HiPayCardEntryTags.savedCard(0)))
        assertEquals(0, countTag(HiPayCardEntryTags.error("savedcard.0")))
    }

    @Test
    fun realPayFailure_setsGenericError_keepsTheCard_andRethrows() {
        seed(testCard())
        val controller = HiPayCardEntryController(config, oneClickEnabled = true)
        controller.bindPresentationContext(context)
        runBlocking { controller.refreshSavedCards() }
        val card = controller.savedCards.first()

        // The REAL pay path against the gateway with unauthenticated credentials: whatever the
        // failure class (auth rejection or no network), it is a non-token-invalid HiPayException —
        // the contract is one observable outcome: GENERIC, card kept, exception rethrown unchanged.
        runBlocking {
            try {
                controller.payWithSavedCard(
                    card = card,
                    orderId = "TEST-DECLINE-${System.currentTimeMillis()}",
                    amount = "1.00",
                    description = "decline recovery harness",
                    redirectScheme = "hipaytest",
                )
                fail("expected the unauthenticated order to throw")
            } catch (e: HiPayException) {
                // rethrown unchanged — the host contract is intact
            }
        }
        val error = controller.lastOneClickError
        assertEquals(OneClickErrorReason.GENERIC, error?.reason)
        assertTrue(error!!.matches(card))
        assertTrue(controller.savedCards.contains(card)) // a transient failure never purges
        assertEquals(card, controller.selectedSavedCard) // still selected for a retry
    }
}
