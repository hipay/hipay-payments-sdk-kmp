package com.hipay.card

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hipay.card.store.SavedCard
import com.hipay.card.store.createSecureCardStore
import com.hipay.card.store.secureCardStoreNamespace
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One-click preconditions and store wiring on the controller — real DataStore/Keystore
 * behind, no network (none of these paths reaches the gateway).
 */
@RunWith(AndroidJUnit4::class)
class OneClickControllerTest {

    private val config = HiPayConfig("oneclick-controller-test-user", "pw", Environment.STAGE)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun clearNamespace() = runBlocking(Dispatchers.IO) {
        createSecureCardStore(context, config).clearAll()
    }

    @Test
    fun refresh_withoutBoundContext_isFailSoftAndLoadsNothing() {
        // refreshSavedCards is fail-soft by design (the component may call it before the
        // context binds); the STRICT precondition (clear IllegalStateException) stays on the
        // paying APIs — covered by paySaveCard_withoutBoundContext below.
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        runBlocking { controller.refreshSavedCards() } // must not throw
        assertTrue(controller.savedCards.isEmpty())
        assertEquals(null, controller.selectedSavedCard)
    }

    @Test
    fun paySaveCard_withoutBoundContext_failsBeforeAnyNetworkCall() {
        val controller = HiPayCardEntryController(config).withOfflineCeiling()
        val ex = runCatching {
            runBlocking {
                controller.pay(
                    orderId = "O1", amount = "1.00", description = "d",
                    redirectScheme = "hipaydemo", saveCard = true,
                )
            }
        }.exceptionOrNull()
        // IllegalStateException (not a network HiPayException): nothing was sent.
        assertTrue(ex is IllegalStateException)
    }

    @Test
    fun refresh_isCallableFromTheMainThread_storeConfinedInternally() {
        // The platform factory refuses the main thread; the controller must hop
        // to its confined dispatcher so a Compose host can refresh directly
        // from the UI scope.
        clearNamespace()
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        controller.bindPresentationContext(context)
        try {
            runBlocking(Dispatchers.Main.immediate) { controller.refreshSavedCards() }
            assertEquals(emptyList<SavedCard>(), controller.savedCards)
        } finally {
            controller.dispose()
            clearNamespace()
        }
    }

    @Test
    fun refresh_seesCardsPersistedUnderTheSameConfigNamespace_andPreselects() {
        clearNamespace()
        val card = SavedCard(
            token = "t".repeat(64), maskedPan = "411111xxxxxx1111", network = "VISA",
            holder = "JANE DOE", expiryMonth = "12", expiryYear = "2031",
        )
        runBlocking(Dispatchers.IO) {
            assertTrue(createSecureCardStore(context, config).save(card, consentGiven = true))
        }
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        controller.bindPresentationContext(context)
        try {
            runBlocking { controller.refreshSavedCards() }
            assertEquals(1, controller.savedCards.size)
            assertEquals("411111xxxxxx1111", controller.savedCards.first().maskedPan)
            assertEquals(controller.savedCards.first(), controller.selectedSavedCard)
            // Sanity: same namespace derivation as the store factory.
            secureCardStoreNamespace(config)
        } finally {
            controller.dispose()
            clearNamespace()
        }
    }

    /** What the controller puts ON THE ORDER when paying from a stored token, asserted through
     *  [HiPayCardEntryController.orderResolver] — the only way in. `oneClick` once went missing from
     *  this very order and shipped, surfacing as a recurring payment. Mirrored in the CMP module. */
    @Test
    fun payWithSavedCard_ordersTheStoredTokenAsACustomerInitiatedOneClick() {
        clearNamespace()
        val card = SavedCard(
            token = "t".repeat(64), maskedPan = "411111xxxxxx1111", network = "VISA",
            holder = "JANE DOE", expiryMonth = "12", expiryYear = "2031",
        )
        runBlocking(Dispatchers.IO) {
            assertTrue(createSecureCardStore(context, config).save(card, consentGiven = true))
        }
        val controller = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        controller.bindPresentationContext(context)
        try {
            runBlocking { controller.refreshSavedCards() }
            val stored = controller.savedCards.first()

            var captured: OrderRequest? = null
            controller.orderResolver = { order, _ -> captured = order; Transaction("completed") }

            runBlocking {
                controller.payWithSavedCard(
                    card = stored, orderId = "OC-1", amount = "12.00",
                    description = "d", redirectScheme = "hipaydemo",
                )
            }

            val order = requireNotNull(captured) { "the order path was never reached" }
            assertEquals("OC-1", order.orderId)
            assertEquals(stored.token, order.cardToken)
            // Declared on EVERY payment made from a stored token, not only on the enrolling order.
            assertTrue(order.oneClick)
            // Customer-initiated: a recurring payment would be eci 9 plus recurring_payment, and the
            // SDK sends neither.
            assertEquals(7, order.eci)
        } finally {
            controller.dispose()
            clearNamespace()
        }
    }

    /** Seeds 3 cards in order, so the store's MRU-first list is [CARD THREE, CARD TWO, CARD ONE]. */
    private fun seedCards() {
        runBlocking(Dispatchers.IO) {
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
    }

    private fun boundController(): HiPayCardEntryController =
        HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
            .also { it.bindPresentationContext(context) }

    // ---- The new-card row toggles both ways (mirrored in the CMP module) ----

    @Test
    fun collapseNewCard_returnsToTheCardTheExpandWasLeftFrom() {
        clearNamespace()
        seedCards()
        val controller = boundController()
        try {
            runBlocking { controller.refreshSavedCards() }
            // Not the pre-selected MRU, so a fallback to the first card cannot pass by accident.
            val chosen = controller.savedCards[1]
            controller.selectSavedCard(chosen)

            controller.selectNewCard()
            assertNull(controller.selectedSavedCard)
            assertTrue(controller.canCollapseNewCard)

            controller.collapseNewCard()
            assertEquals(chosen, controller.selectedSavedCard)
            assertFalse(controller.canCollapseNewCard) // nothing left to collapse back to
        } finally {
            controller.dispose()
            clearNamespace()
        }
    }

    /** The remembered card can be deleted while the fields are open. An inert control would look
     *  broken for a reason the payer cannot see, so it falls back to the most recent one. */
    @Test
    fun collapseNewCard_fallsBackToTheMostRecentWhenTheRememberedCardIsGone() {
        clearNamespace()
        seedCards()
        val controller = boundController()
        try {
            runBlocking { controller.refreshSavedCards() }
            val chosen = controller.savedCards[1]
            controller.selectSavedCard(chosen)
            controller.selectNewCard()
            runBlocking { controller.deleteSavedCard(chosen) }
            // The deleted card was not the selected one (that is the new-card branch), so the
            // delete re-selected nothing and the fields are still open.
            assertNull(controller.selectedSavedCard)

            controller.collapseNewCard()
            assertEquals(controller.savedCards.first(), controller.selectedSavedCard)
        } finally {
            controller.dispose()
            clearNamespace()
        }
    }

    @Test
    fun collapseNewCard_isANoOpWithoutSavedCards() {
        clearNamespace()
        val controller = boundController()
        try {
            runBlocking { controller.refreshSavedCards() }
            assertFalse(controller.canCollapseNewCard)
            controller.collapseNewCard()
            assertNull(controller.selectedSavedCard) // still the new-card branch
        } finally {
            controller.dispose()
            clearNamespace()
        }
    }

    // ---- The load-settled flag the component gates its entry fields on ----

    @Test
    fun savedCardsLoaded_isFalseUntilTheFirstLoadSettles() {
        clearNamespace()
        seedCards()
        val controller = boundController()
        try {
            assertFalse(controller.savedCardsLoaded)
            runBlocking { controller.refreshSavedCards() }
            assertTrue(controller.savedCardsLoaded)
            // Set LAST, so the component never renders a settled load with no selection applied.
            assertEquals(controller.savedCards.first(), controller.selectedSavedCard)
        } finally {
            controller.dispose()
            clearNamespace()
        }
    }

    /** Fail-open: the flag means "nothing more is coming". Left false on an early exit — opted out,
     *  or no bound context — the component would hide its entry fields for good. */
    @Test
    fun savedCardsLoaded_settlesOnBothEarlyExits() {
        val optedOut = HiPayCardEntryController(config, oneClickEnabled = false).withOfflineCeiling()
        optedOut.bindPresentationContext(context)
        val unbound = HiPayCardEntryController(config, oneClickEnabled = true).withOfflineCeiling()
        try {
            runBlocking { optedOut.refreshSavedCards() }
            assertTrue(optedOut.savedCardsLoaded)
            runBlocking { unbound.refreshSavedCards() }
            assertTrue(unbound.savedCardsLoaded)
        } finally {
            optedOut.dispose()
            unbound.dispose()
        }
    }

    // ---- The phase a host reads to show its own progress wording ----

    @Test
    fun paymentPhase_reportsCreatingOrderDuringTheOrderCall_andIsNullAgainAtTheEnd() {
        clearNamespace()
        seedCards()
        val controller = boundController()
        try {
            runBlocking { controller.refreshSavedCards() }
            val card = controller.savedCards.first()
            assertNull(controller.paymentPhase) // idle

            // Read from INSIDE the order call: the only moment the phase is observable without a
            // second thread, and the saved-card path skips tokenization so it must already be here.
            var duringOrder: PaymentPhase? = null
            controller.orderResolver = { _, _ ->
                duringOrder = controller.paymentPhase
                Transaction("completed")
            }

            runBlocking {
                controller.payWithSavedCard(
                    card = card, orderId = "OC-2", amount = "12.00",
                    description = "d", redirectScheme = "hipaydemo",
                )
            }

            assertEquals(PaymentPhase.CREATING_ORDER, duringOrder)
            assertNull(controller.paymentPhase) // every exit releases it
        } finally {
            controller.dispose()
            clearNamespace()
        }
    }

}
