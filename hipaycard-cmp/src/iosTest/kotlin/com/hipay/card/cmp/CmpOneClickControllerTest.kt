package com.hipay.card.cmp

import com.hipay.card.store.SavedCard
import com.hipay.card.store.createSecureCardStore
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * One-click load + selection on the shared controller against the REAL simulator Keychain
 * (no fake). No network: nothing here reaches the gateway.
 */
class CmpOneClickControllerTest {

    private val config = HiPayConfig("cmp-oneclick-test-user", "pw", Environment.STAGE)

    @AfterTest
    fun tearDown() {
        runCatching { createSecureCardStore(config).clearAll() }
    }

    private fun seedCard() {
        val card = SavedCard(
            token = "t".repeat(64), maskedPan = "411111xxxxxx1111", network = "VISA",
            holder = "JANE DOE", expiryMonth = "12", expiryYear = "2031",
        )
        assertTrue(createSecureCardStore(config).save(card, consentGiven = true))
    }

    /** Seeds 3 distinct cards in order, so the store's MRU-first list is [3333, 2222, 1111]. */
    private fun seedCards() {
        val store = createSecureCardStore(config)
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

    @Test
    fun refresh_loadsAndPreselectsTheSavedCard() = runBlocking {
        seedCard()
        val c = CmpCardController(config, oneClickEnabled = true)
        c.refreshSavedCards()
        assertEquals("411111xxxxxx1111", assertNotNull(c.selectedSavedCard).maskedPan)
        assertNotNull(c.selectedSavedCard)
        assertTrue(c.canPay) // one tap away, fields empty
    }

    @Test
    fun refresh_withoutCards_selectsTheNewCardBranch() = runBlocking {
        val c = CmpCardController(config, oneClickEnabled = true)
        c.refreshSavedCards()
        kotlin.test.assertNull(c.selectedSavedCard)
        assertFalse(c.canPay)
    }

    @Test
    fun refresh_loadsMultipleCardsMruFirst_andSelectingAnotherMovesSelection() = runBlocking {
        seedCards()
        val c = CmpCardController(config, oneClickEnabled = true)
        c.refreshSavedCards()
        assertEquals(3, c.savedCards.size)
        // MRU-first: the last saved card (3333) is index 0 and pre-selected.
        assertEquals("411111xxxxxx3333", c.savedCards.first().maskedPan)
        assertEquals(c.savedCards.first(), c.selectedSavedCard)
        // Selecting another card moves the selection to it.
        c.selectSavedCard(c.savedCards[1])
        assertEquals(c.savedCards[1], c.selectedSavedCard)
        assertTrue(c.canPay)
    }

    @Test
    fun selectSavedCard_ignoresACardNotInTheList() = runBlocking {
        seedCards()
        val c = CmpCardController(config, oneClickEnabled = true)
        c.refreshSavedCards()
        val preselected = c.selectedSavedCard
        val foreign = SavedCard(
            token = "z".repeat(64), maskedPan = "400000xxxxxx9999", network = "VISA",
            holder = "NOT SAVED", expiryMonth = "12", expiryYear = "2031",
        )
        c.selectSavedCard(foreign)
        assertEquals(preselected, c.selectedSavedCard) // unchanged — foreign card rejected
    }

    @Test
    fun selectionRoundTrip_preservesTypedFieldValues() = runBlocking {
        seedCard()
        val c = CmpCardController(config, oneClickEnabled = true)
        c.refreshSavedCards()
        c.selectNewCard()
        c.onHolderChange("marie martin")
        c.selectSavedCard(c.savedCards.first())
        assertNotNull(c.selectedSavedCard)
        assertEquals("MARIE MARTIN", c.holder) // preserved (and uppercased by the handler)
    }

    @Test
    fun deleteSavedCard_removesIt_withSelectionFallbacks() = runBlocking {
        seedCards()
        val c = CmpCardController(config, oneClickEnabled = true)
        c.refreshSavedCards()
        val mru = assertNotNull(c.selectedSavedCard) // pre-selected MRU (3333)
        // Deleting a NON-selected card preserves the current selection.
        c.deleteSavedCard(c.savedCards[1])
        assertEquals(2, c.savedCards.size)
        assertEquals(mru, c.selectedSavedCard)
        // Deleting the SELECTED card drops the selection to the new-card branch (not the next card).
        c.deleteSavedCard(mru)
        assertEquals(1, c.savedCards.size)
        kotlin.test.assertNull(c.selectedSavedCard)
        // Deleting the last card yields the no-card state.
        c.deleteSavedCard(c.savedCards.first())
        assertTrue(c.savedCards.isEmpty())
        kotlin.test.assertNull(c.selectedSavedCard)
    }
}
