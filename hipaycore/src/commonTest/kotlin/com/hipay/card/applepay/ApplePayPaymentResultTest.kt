package com.hipay.card.applepay

import com.hipay.core.gateway.model.Transaction
import com.hipay.core.gateway.model.TransactionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplePayPaymentResultTest {

    private fun transaction(state: String) = Transaction(stateRaw = state, transactionReference = "TX-1")

    // Only a captured payment is a completion — the one outcome that means the customer was charged.
    @Test
    fun completedMapsToCompleted() {
        val result = transaction("completed").toApplePayPaymentResult()

        assertTrue(result is ApplePayPaymentResult.Completed)
        assertEquals("TX-1", result.transaction.transactionReference)
    }

    // A decline is a result carrying its transaction, not an exception. It is a different type from a
    // cancel, so the two outcomes cannot be confused by construction.
    @Test
    fun declinedMapsToDeclinedCarryingTheTransaction() {
        val result = transaction("declined").toApplePayPaymentResult()

        assertTrue(result is ApplePayPaymentResult.Declined)
        assertEquals(TransactionState.DECLINED, result.transaction.state)
    }

    // A gateway-reported pending stays indeterminate — the host re-queries, it is not a failure.
    @Test
    fun pendingMapsToPending() {
        assertTrue(transaction("pending").toApplePayPaymentResult() is ApplePayPaymentResult.Pending)
    }

    // A forwarding transaction only reaches the mapper once its challenge is resolved: the customer
    // abandoned it. Server-confirmed not-completed, deliberately NOT conflated with indeterminate.
    @Test
    fun forwardingMapsToNotCompletedNotPending() {
        val result = transaction("forwarding").toApplePayPaymentResult()

        assertTrue(result is ApplePayPaymentResult.NotCompleted)
        assertEquals(TransactionState.FORWARDING, result.transaction.state)
    }

    // A gateway error is reported as an outcome the host can inspect, not as a thrown error.
    @Test
    fun errorMapsToNotCompleted() {
        assertTrue(transaction("error").toApplePayPaymentResult() is ApplePayPaymentResult.NotCompleted)
    }

    // An unknown wire state falls back to ERROR upstream, so it must still map to an outcome rather
    // than escaping the mapping.
    @Test
    fun unknownWireStateMapsToNotCompleted() {
        assertTrue(transaction("banana").toApplePayPaymentResult() is ApplePayPaymentResult.NotCompleted)
    }

    // The indeterminate snapshot the timeout and reconcile paths report is a Pending outcome.
    @Test
    fun verificationPendingSnapshotMapsToPending() {
        val result = Transaction.verificationPending("TX-7").toApplePayPaymentResult()

        assertTrue(result is ApplePayPaymentResult.Pending)
        assertEquals("TX-7", result.transaction.transactionReference)
    }

    // The one outcome the host may have nothing to re-query on carries the order id, so a payment whose
    // order call never answered can still be reconciled server-side.
    @Test
    fun pendingCarriesTheOrderIdToReconcileOn() {
        val result = Transaction.verificationPending(null).toApplePayPaymentResult(orderId = "AP-1")

        assertTrue(result is ApplePayPaymentResult.Pending)
        assertNull(result.transaction.transactionReference)
        assertEquals("AP-1", result.orderId)
    }

    // A settled outcome needs no order id: it is identified by its own transaction.
    @Test
    fun settledOutcomesDoNotNeedTheOrderId() {
        assertTrue(transaction("completed").toApplePayPaymentResult(orderId = "AP-1") is ApplePayPaymentResult.Completed)
    }
}
