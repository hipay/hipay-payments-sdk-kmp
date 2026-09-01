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

}
