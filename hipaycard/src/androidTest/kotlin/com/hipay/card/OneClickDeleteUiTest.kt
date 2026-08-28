package com.hipay.card

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One-click delete on the card component — long-press gesture → confirmation, the mandatory
 * a11y custom action, and the selection fallbacks. Real store, no network.
 */
@RunWith(AndroidJUnit4::class)
class OneClickDeleteUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val config = HiPayConfig("oneclick-delete-test-user", "pw", Environment.STAGE)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun seedCard() = runBlocking(Dispatchers.IO) {
        assertTrue(
            createSecureCardStore(context, config).save(
                SavedCard(
                    token = "t".repeat(64), maskedPan = "411111xxxxxx1111", network = "VISA",
                    holder = "JANE DOE", expiryMonth = "12", expiryYear = "2031",
                ),
                consentGiven = true,
            ),
        )
    }

    /** Seeds 3 cards in order → MRU-first list [3333, 2222, 1111]. */
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

    // Long-press REVEALS the trash, exactly like a left-swipe — it never deletes on its own. The
    // trash tap is what validates, and by default no dialog is layered on top of it.
    @Test
    fun longPressRevealsTrash_thenTappingItDeletes() {
        seedCards()
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller, localeOverride = "en") }
        awaitSections()
        assertEquals(0, countTag(HiPayCardEntryTags.savedCardDelete(1))) // nothing revealed at rest
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(1)).performTouchInput { longClick() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            countTag(HiPayCardEntryTags.savedCardDelete(1)) == 1
        }
        // The long press alone must not have asked for anything.
        assertEquals(3, controller.savedCards.size)
        assertEquals(0, countTag(HiPayCardEntryTags.CONFIRM_DELETE))
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCardDelete(1)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { controller.savedCards.size == 2 }
        assertTrue(controller.savedCards.none { it.maskedPan == "510510xxxxxx2222" })
    }

    // The revealed trash is cancelled by swiping the row back, not by a dialog button.
    @Test
    fun longPressRevealsTrash_andSwipingBackCancels() {
        seedCards()
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller, localeOverride = "en") }
        awaitSections()
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(1)).performTouchInput { longClick() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            countTag(HiPayCardEntryTags.savedCardDelete(1)) == 1
        }
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(1)).performTouchInput { swipeRight() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            countTag(HiPayCardEntryTags.savedCardDelete(1)) == 0
        }
        assertEquals(3, controller.savedCards.size) // nothing removed
    }

    // Opting in puts the dialog back — on the trash tap, which is the validating step.
    @Test
    fun confirmCardDeletionOptIn_asksBeforeDeleting() {
        seedCards()
        val controller = HiPayCardEntryController(
            config,
            oneClickEnabled = true,
            confirmCardDeletion = true,
        ).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller, localeOverride = "en") }
        awaitSections()
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(1)).performTouchInput { longClick() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            countTag(HiPayCardEntryTags.savedCardDelete(1)) == 1
        }
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCardDelete(1)).performClick()
        composeRule.onNodeWithTag(HiPayCardEntryTags.CONFIRM_DELETE).assertIsDisplayed()
        assertEquals(3, controller.savedCards.size) // not yet
        composeRule.onNodeWithTag(HiPayCardEntryTags.CONFIRM_DELETE).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { controller.savedCards.size == 2 }
        assertTrue(controller.savedCards.none { it.maskedPan == "510510xxxxxx2222" })
    }

    @Test
    fun deletingTheSelectedCard_fallsBackToNewCardBranch() {
        seedCards()
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller, localeOverride = "en") }
        awaitSections()
        // savedCard(0) is the pre-selected MRU. Deleting it must drop the selection to new-card.
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(0)).performTouchInput { longClick() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            countTag(HiPayCardEntryTags.savedCardDelete(0)) == 1
        }
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCardDelete(0)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { controller.savedCards.size == 2 }
        assertNull(controller.selectedSavedCard) // new-card branch, not the next card
        composeRule.onNodeWithTag(HiPayCardEntryTags.HOLDER).assertIsDisplayed() // fields shown
    }

    @Test
    fun deletingTheLastCard_yieldsNoCardState() {
        seedCard()
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller, localeOverride = "en") }
        awaitSections()
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(0)).performTouchInput { longClick() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            countTag(HiPayCardEntryTags.savedCardDelete(0)) == 1
        }
        composeRule.onNodeWithTag(HiPayCardEntryTags.savedCardDelete(0)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { controller.savedCards.isEmpty() }
        // No-card state: no cells, fields + save switch only.
        assertEquals(0, countTag(HiPayCardEntryTags.savedCard(0)))
        composeRule.onNodeWithTag(HiPayCardEntryTags.HOLDER).assertIsDisplayed()
        composeRule.onNodeWithTag(HiPayCardEntryTags.SAVE_SWITCH).assertIsDisplayed()
    }

    @Test
    fun cellExposesDeleteCustomAction_whichAlwaysConfirms() {
        seedCard()
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        composeRule.setContent { HiPayCardEntry(controller, localeOverride = "en") }
        awaitSections()
        // The mandatory a11y custom action exists (the only delete path for screen readers)…
        val node = composeRule.onNodeWithTag(HiPayCardEntryTags.savedCard(0)).fetchSemanticsNode()
        val delete = node.config[SemanticsActions.CustomActions].first { it.label == "Delete card" }
        // …and it ALWAYS confirms, whatever `confirmCardDeletion` says: a screen reader has no
        // trash to aim at and no reverse swipe, so this path is a single step with no safety net.
        composeRule.runOnUiThread { delete.action?.invoke() }
        composeRule.onNodeWithTag(HiPayCardEntryTags.CONFIRM_DELETE).assertIsDisplayed()
    }
}
