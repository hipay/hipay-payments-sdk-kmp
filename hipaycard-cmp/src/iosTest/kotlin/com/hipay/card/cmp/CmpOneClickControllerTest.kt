package com.hipay.card.cmp

import com.hipay.card.store.OneClickError
import com.hipay.card.store.OneClickErrorReason
import com.hipay.card.store.SavedCard
import com.hipay.card.store.createSecureCardStore
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
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

    // ---- One-click decline recovery: the transient observable error ----
    // Failure states that would need a gateway stub (declined / token-invalid) are driven
    // through the controller's internal outcome seam; the real payWithSavedCard failure
    // wiring is covered by the unauthenticated-gateway test at the end.

    @Test
    fun oneClickError_isClearedOnAnySelectionChange() = runBlocking {
        seedCards()
        val c = CmpCardController(config, oneClickEnabled = true)
        c.refreshSavedCards()
        c.lastOneClickError = OneClickError(c.savedCards.first(), OneClickErrorReason.DECLINED)
        c.selectSavedCard(c.savedCards[1]) // switching card = a fresh intent
        assertNull(c.lastOneClickError)

        c.lastOneClickError = OneClickError(c.savedCards.first(), OneClickErrorReason.THREE_DS_FAILED)
        c.selectNewCard()
        assertNull(c.lastOneClickError)
    }

    @Test
    fun oneClickError_isClearedOnANewCardFieldEdit() = runBlocking {
        seedCard()
        val c = CmpCardController(config, oneClickEnabled = true)
        c.refreshSavedCards()
        listOf<(String) -> Unit>(
            c::onHolderChange, c::onNumberChange, c::onExpiryChange, c::onCvcChange,
        ).forEach { edit ->
            c.lastOneClickError = OneClickError(c.savedCards.first(), OneClickErrorReason.GENERIC)
            edit("4")
            assertNull(c.lastOneClickError)
        }
    }

    @Test
    fun oneClickError_survivesARefreshWhileTheCardIsListed_dropsWhenItIsGone() = runBlocking {
        seedCard()
        val c = CmpCardController(config, oneClickEnabled = true)
        c.refreshSavedCards()
        val card = c.savedCards.first()
        c.lastOneClickError = OneClickError(card, OneClickErrorReason.DECLINED)
        // Still listed → still the last failure: an app-foreground refresh keeps it.
        c.refreshSavedCards()
        assertEquals(OneClickErrorReason.DECLINED, c.lastOneClickError?.reason)
        // Gone from the store (e.g. purged elsewhere) → the refresh drops the stale error.
        createSecureCardStore(config).delete(card)
        c.refreshSavedCards()
        assertNull(c.lastOneClickError)
    }

    @Test
    fun deletingTheErroredCard_clearsTheError() = runBlocking {
        seedCard()
        val c = CmpCardController(config, oneClickEnabled = true)
        c.refreshSavedCards()
        val card = c.savedCards.first()
        c.lastOneClickError = OneClickError(card, OneClickErrorReason.DECLINED)
        c.deleteSavedCard(card)
        assertNull(c.lastOneClickError) // nothing left to recover — no stale observable
    }

    @Test
    fun realPayFailure_setsGenericError_keepsTheCard_andRethrows() = runBlocking {
        seedCard()
        val c = CmpCardController(config, oneClickEnabled = true)
        c.refreshSavedCards()
        val card = c.savedCards.first()
        // The REAL pay path against the gateway with unauthenticated credentials: whatever the
        // failure class (auth rejection or no network), it is a non-token-invalid HiPayException —
        // the contract is one observable outcome: GENERIC, card kept, exception rethrown unchanged.
        try {
            c.payWithSavedCard(
                card = card,
                orderId = "TEST-DECLINE-CMP",
                amount = "1.00",
                description = "decline recovery harness",
                redirectScheme = "hipaytest",
            )
            fail("expected the unauthenticated order to throw")
        } catch (e: HiPayException) {
            // rethrown unchanged — the host contract is intact
        }
        val error = c.lastOneClickError
        assertEquals(OneClickErrorReason.GENERIC, error?.reason)
        assertTrue(assertNotNull(error).matches(card))
        assertTrue(card in c.savedCards) // a transient failure never purges
        assertEquals(card, c.selectedSavedCard) // still selected for a retry
        assertFalse(c.isProcessing) // the lock is released on the failure path
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
