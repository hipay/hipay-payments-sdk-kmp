package com.hipay.card

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hipay.card.recovery.hiPayPaymentRecovery
import com.hipay.card.store.SavedCard
import com.hipay.card.store.createSecureCardStore
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.gateway.model.Transaction
import com.hipay.core.gateway.model.TransactionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a payment leaves behind, against the real Keystore-backed store — the layer the commonTest
 * fakes cannot reach. The gateway is replaced by [HiPayCardEntryController.orderResolver]: none of
 * these paths goes to the network.
 */
@RunWith(AndroidJUnit4::class)
class PendingPaymentRecoveryTest {

    private val config = HiPayConfig("recovery-controller-test-user", "pw", Environment.STAGE)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun recovery(of: HiPayConfig = config) = hiPayPaymentRecovery(context, of)

    private fun clearAll(of: HiPayConfig = config) {
        runBlocking {
            recovery(of).unresolvedPayments().forEach { recovery(of).acknowledge(it.orderId) }
        }
        runBlocking(Dispatchers.IO) { createSecureCardStore(context, of).clearAll() }
    }

    @Before
    fun clean() {
        clearAll()
    }

    @After
    fun cleanUp() {
        clearAll()
    }

    /** A controller holding one saved card, so paying skips tokenization and goes straight to the order. */
    private fun controllerWithACard(of: HiPayConfig = config): Pair<HiPayCardEntryController, SavedCard> {
        val card = SavedCard(
            token = "t".repeat(64), maskedPan = "411111xxxxxx1111", network = "VISA",
            holder = "JANE DOE", expiryMonth = "12", expiryYear = "2031",
        )
        runBlocking(Dispatchers.IO) {
            assertTrue(createSecureCardStore(context, of).save(card, consentGiven = true))
        }
        val controller = HiPayCardEntryController(of, oneClickEnabled = true).withOfflineCeiling()
        controller.bindPresentationContext(context)
        runBlocking { controller.refreshSavedCards() }
        return controller to controller.savedCards.first()
    }

    @Test
    fun theOrderIsRecordedBeforeItIsSentAndCompletedByItsAnswer() {
        val (controller, stored) = controllerWithACard()
        try {
            var inFlight: List<com.hipay.card.recovery.HiPayPendingPayment> = emptyList()
            controller.orderResolver = { _, _ ->
                // Read from inside the round-trip: this is the window a crash would fall into.
                inFlight = runBlocking { recovery().unresolvedPayments() }
                Transaction("completed", transactionReference = "800000000001")
            }

            runBlocking {
                controller.payWithSavedCard(
                    card = stored, orderId = "REC-1", amount = "12.00",
                    description = "d", redirectScheme = "hipaydemo",
                )
            }

            val duringOrder = inFlight.single()
            assertEquals("REC-1", duringOrder.orderId)
            assertEquals(TransactionState.PENDING, duringOrder.lastState)
            assertFalse("no reference exists yet", duringOrder.referenceKnown)

            val settled = runBlocking { recovery().unresolvedPayments() }.single()
            assertEquals(TransactionState.COMPLETED, settled.lastState)
            assertTrue(settled.referenceKnown)
            assertEquals("12.00", settled.amount)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun anotherInstanceSeesWhatThePaymentRecorded() {
        val (controller, stored) = controllerWithACard()
        try {
            controller.orderResolver = { _, _ -> Transaction("completed", transactionReference = "ref") }
            runBlocking {
                controller.payWithSavedCard(
                    card = stored, orderId = "REC-4", amount = "12.00",
                    description = "d", redirectScheme = "hipaydemo",
                )
            }
        } finally {
            controller.dispose()
        }

        // A facade built from scratch, sharing nothing in memory with the controller — the closest an
        // instrumented test gets to asking after a process death.
        val seen = runBlocking { hiPayPaymentRecovery(context, config).unresolvedPayments() }
        assertEquals("REC-4", seen.single().orderId)
    }

    @Test
    fun acknowledgingRemovesTheEntryAtOnce() {
        val (controller, stored) = controllerWithACard()
        try {
            controller.orderResolver = { _, _ -> Transaction("completed", transactionReference = "ref") }
            runBlocking {
                controller.payWithSavedCard(
                    card = stored, orderId = "REC-5", amount = "12.00",
                    description = "d", redirectScheme = "hipaydemo",
                )
            }
        } finally {
            controller.dispose()
        }

        runBlocking {
            assertTrue(recovery().acknowledge("REC-5"))
            assertTrue(recovery().unresolvedPayments().isEmpty())
        }
    }

    @Test
    fun aShortenedLifetimeExpiresTheEntryWithoutAnyAction() {
        val (controller, stored) = controllerWithACard()
        try {
            controller.orderResolver = { _, _ -> Transaction("completed", transactionReference = "ref") }
            runBlocking {
                controller.payWithSavedCard(
                    card = stored, orderId = "REC-6", amount = "12.00",
                    description = "d", redirectScheme = "hipaydemo",
                )
            }
            Thread.sleep(20)

            // The lifetimes belong to the reader, which is what makes this testable without waiting
            // forty-eight hours: the same entry is listed by a default facade and gone from a brief one.
            assertEquals(1, runBlocking { recovery().unresolvedPayments() }.size)
            val brief = hiPayPaymentRecovery(context, config, resolvedTtlMillis = 1)
            assertTrue(runBlocking { brief.unresolvedPayments() }.isEmpty())
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun refreshingAnOrderWithNoReferenceReportsItWithoutCallingTheGateway() {
        val (controller, stored) = controllerWithACard()
        try {
            // No reference: the answer carries none, as a lost order response would leave it.
            controller.orderResolver = { _, _ -> Transaction("pending") }
            runBlocking {
                controller.payWithSavedCard(
                    card = stored, orderId = "REC-7", amount = "12.00",
                    description = "d", redirectScheme = "hipaydemo",
                )
            }

            // No reference means nothing to ask about: the call answers from the store alone, which is
            // why it works with no network in this test.
            val snapshot = runBlocking { recovery().refreshPayment("REC-7") }
            assertEquals(TransactionState.PENDING, snapshot?.lastState)
            assertNull(runBlocking { recovery().refreshPayment("NEVER-LAUNCHED") })
        } finally {
            controller.dispose()
        }
    }
}
