package com.hipay.card

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hipay.card.store.SavedCard
import com.hipay.card.store.createSecureCardStore
import com.hipay.card.store.secureCardStoreNamespace
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
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
    fun savedCards_withoutBoundContext_failsFastWithAClearMessage() {
        val controller = HiPayCardEntryController(config)
        val ex = runCatching { runBlocking { controller.savedCards() } }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
        assertTrue(ex!!.message!!.contains("one-click"))
    }

    @Test
    fun paySaveCard_withoutBoundContext_failsBeforeAnyNetworkCall() {
        val controller = HiPayCardEntryController(config)
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
    fun savedCards_isCallableFromTheMainThread_storeConfinedInternally() {
        // The platform factory refuses the main thread; the controller must hop
        // to its confined dispatcher so a Compose host can call savedCards()
        // directly from the UI scope.
        clearNamespace()
        val controller = HiPayCardEntryController(config)
        controller.bindPresentationContext(context)
        try {
            val cards = runBlocking(Dispatchers.Main.immediate) { controller.savedCards() }
            assertEquals(emptyList<SavedCard>(), cards)
        } finally {
            controller.dispose()
            clearNamespace()
        }
    }

    @Test
    fun savedCards_seesCardsPersistedUnderTheSameConfigNamespace() {
        clearNamespace()
        val card = SavedCard(
            token = "t".repeat(64), maskedPan = "411111xxxxxx1111", network = "VISA",
            holder = "JANE DOE", expiryMonth = "12", expiryYear = "2031",
        )
        runBlocking(Dispatchers.IO) {
            assertTrue(createSecureCardStore(context, config).save(card, consentGiven = true))
        }
        val controller = HiPayCardEntryController(config)
        controller.bindPresentationContext(context)
        try {
            val cards = runBlocking { controller.savedCards() }
            assertEquals(1, cards.size)
            assertEquals("411111xxxxxx1111", cards.first().maskedPan)
            // Sanity: same namespace derivation as the store factory.
            secureCardStoreNamespace(config)
        } finally {
            controller.dispose()
            clearNamespace()
        }
    }
}
