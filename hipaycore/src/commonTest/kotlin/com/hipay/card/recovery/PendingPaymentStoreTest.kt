package com.hipay.card.recovery

import com.hipay.card.store.RawSecureStore
import com.hipay.core.Environment
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.core.HiPayConfig
import com.hipay.core.gateway.model.TransactionState
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingPaymentStoreTest {

    private val hour = 60L * 60 * 1000
    private var clock = 1_000_000L

    private fun store(
        raw: RawSecureStore = FakeRawSecureStore(),
        pendingTtl: Long = DEFAULT_PENDING_PAYMENT_TTL_MILLIS,
        resolvedTtl: Long = DEFAULT_RESOLVED_PAYMENT_TTL_MILLIS,
    ) = PendingPaymentStore(raw, { clock }, pendingTtl, resolvedTtl)

    @Test
    fun aSubmittedPaymentIsListedBeforeItIsAnswered() {
        val store = store()

        store.record("ORDER-1", "10.00", "EUR")

        val pending = store.unresolvedPayments().single()
        assertEquals("ORDER-1", pending.orderId)
        assertEquals(TransactionState.PENDING, pending.lastState)
        assertEquals("10.00", pending.amount)
        // Nothing links this order to a transaction yet, and the host must be able to tell.
        assertFalse(pending.referenceKnown)
    }

    @Test
    fun theAnswerCompletesTheEntryWithoutExposingTheReference() {
        val store = store()
        store.record("ORDER-1", "10.00", "EUR")

        store.complete("ORDER-1", "800000000001", TransactionState.COMPLETED)

        val entry = store.unresolvedPayments().single()
        assertEquals(TransactionState.COMPLETED, entry.lastState)
        assertTrue(entry.referenceKnown)
        assertFalse(entry.toString().contains("800000000001"), "the reference must not surface: $entry")
    }

    @Test
    fun aResolvedPaymentSurvivesUntilItIsAcknowledged() {
        val store = store()
        store.record("ORDER-1", "10.00", "EUR")
        store.complete("ORDER-1", "ref", TransactionState.COMPLETED)

        // Deleting on resolution would lose the very case this store exists for: a host that dies
        // between receiving the outcome and recording it.
        assertEquals(1, store.unresolvedPayments().size)
        assertTrue(store.acknowledge("ORDER-1"))
        assertTrue(store.unresolvedPayments().isEmpty())
    }

    @Test
    fun acknowledgingSomethingElseChangesNothing() {
        val store = store()
        store.record("ORDER-1", "10.00", "EUR")

        assertFalse(store.acknowledge("ORDER-2"))
        assertEquals(1, store.unresolvedPayments().size)
    }

    @Test
    fun anUnansweredPaymentExpiresOnItsOwn() {
        val raw = FakeRawSecureStore()
        store(raw, pendingTtl = 8 * hour).record("ORDER-1", "10.00", "EUR")

        clock += 8 * hour
        assertTrue(store(raw, pendingTtl = 8 * hour).unresolvedPayments().isEmpty())
    }

    @Test
    fun aResolvedPaymentExpiresFromItsResolutionNotItsCreation() {
        val raw = FakeRawSecureStore()
        val store = store(raw, pendingTtl = 100 * hour, resolvedTtl = 2 * hour)
        store.record("ORDER-1", "10.00", "EUR")
        clock += 10 * hour
        store.complete("ORDER-1", "ref", TransactionState.COMPLETED)

        clock += hour
        assertEquals(1, store.unresolvedPayments().size, "still within the resolved lifetime")
        clock += hour
        assertTrue(store.unresolvedPayments().isEmpty())
    }

    @Test
    fun aForwardingPaymentIsNotTreatedAsResolved() {
        val raw = FakeRawSecureStore()
        val store = store(raw, pendingTtl = 4 * hour, resolvedTtl = hour)
        store.record("ORDER-1", "10.00", "EUR")
        // A 3DS step-up is an intermediate state: it must age on the pending lifetime, not the short
        // resolved one, or a challenge left open would vanish mid-authentication.
        store.complete("ORDER-1", "ref", TransactionState.FORWARDING)

        clock += 2 * hour
        assertEquals(1, store.unresolvedPayments().size)
    }

    @Test
    fun resubmittingTheSameOrderIdReplacesItsEntry() {
        val store = store()
        store.record("ORDER-1", "10.00", "EUR")
        store.record("ORDER-1", "10.00", "EUR")

        assertEquals(1, store.unresolvedPayments().size)
    }

    @Test
    fun theOldestEntryGoesOnceTheStoreIsFull() {
        val store = store()
        repeat(MAX_PENDING_PAYMENTS + 5) { i ->
            clock += 1000
            store.record("ORDER-$i", "10.00", "EUR")
        }

        // The whole envelope is rewritten on every payment, so the list cannot be allowed to grow
        // without bound — a host free to lengthen the lifetimes would otherwise pay for it here.
        val kept = store.unresolvedPayments()
        assertEquals(MAX_PENDING_PAYMENTS, kept.size)
        assertEquals("ORDER-${MAX_PENDING_PAYMENTS + 4}", kept.first().orderId, "newest first")
        assertTrue(kept.none { it.orderId == "ORDER-0" }, "the oldest went")
    }

    @Test
    fun theStoredPayloadCarriesNoCardData() {
        val raw = FakeRawSecureStore()
        val store = store(raw)
        store.record("ORDER-1", "10.00", "EUR")
        store.complete("ORDER-1", "800000000001", TransactionState.COMPLETED)

        val blob = raw.blob!!
        listOf("pan", "cvc", "cvv", "token", "holder", "card", "signature").forEach { forbidden ->
            assertFalse(blob.contains(forbidden, ignoreCase = true), "$forbidden reached the store: $blob")
        }
        // Asserted positively too, so a future field cannot slip in unnoticed.
        assertEquals(
            setOf("version", "payments", "orderId", "reference", "amount", "currency", "state", "createdAt", "resolvedAt"),
            Regex("\"([a-zA-Z]+)\":").findAll(blob).map { it.groupValues[1] }.toSet(),
        )
    }

    @Test
    fun aCorruptStoreReadsAsEmptyAndNeverThrows() {
        assertTrue(store(FakeRawSecureStore(blob = "{not json")).unresolvedPayments().isEmpty())
        assertTrue(store(FakeRawSecureStore(failRead = true)).unresolvedPayments().isEmpty())
    }

    @Test
    fun anUnknownEnvelopeVersionReadsAsEmpty() {
        val blob = """{"version":99,"payments":[{"orderId":"ORDER-1","amount":"1.00","currency":"EUR",""" +
            """"state":"PENDING","createdAt":1}]}"""

        assertTrue(store(FakeRawSecureStore(blob = blob)).unresolvedPayments().isEmpty())
    }

    @Test
    fun aStorageFailureIsReportedNotRaised() {
        val store = store(FakeRawSecureStore(failWrite = true))

        assertFalse(store.record("ORDER-1", "10.00", "EUR"))
        assertTrue(store.unresolvedPayments().isEmpty())
    }

    @Test
    fun aClockThatMovedBackwardsDestroysNothing() {
        val raw = FakeRawSecureStore()
        store(raw).record("ORDER-1", "10.00", "EUR")

        clock -= 10 * hour
        assertEquals(1, store(raw).unresolvedPayments().size)
    }

    @Test
    fun aTransportFailureBecomesAnIndeterminateOutcome() {
        val lost = HiPayException(HiPayErrorCode.NETWORK, "connection lost")

        // The gateway may well have taken the payment; a failure here is what would make a host
        // refuse an order that was charged.
        assertEquals(TransactionState.PENDING, indeterminateOrRethrow(lost).state)
    }

    @Test
    fun anAnswerFromTheGatewayStaysAFailure() {
        listOf(HiPayErrorCode.API, HiPayErrorCode.CLIENT, HiPayErrorCode.SERVER, HiPayErrorCode.VALIDATION)
            .forEach { code ->
                assertFailsWith<HiPayException>("$code must not be swallowed") {
                    indeterminateOrRethrow(HiPayException(code, "answered"))
                }
            }
    }

    @Test
    fun theNamespaceSeparatesMerchantsAndEnvironments() {
        val stage = pendingPaymentNamespace(HiPayConfig("user", "p", Environment.STAGE))
        val production = pendingPaymentNamespace(HiPayConfig("user", "p", Environment.PRODUCTION))
        val other = pendingPaymentNamespace(HiPayConfig("user2", "p", Environment.STAGE))

        assertTrue(setOf(stage, production, other).size == 3)
        // And never the saved-card namespace, which uses the same shape on the same storage.
        assertFalse(stage.contains("savedcards"))
    }
}
