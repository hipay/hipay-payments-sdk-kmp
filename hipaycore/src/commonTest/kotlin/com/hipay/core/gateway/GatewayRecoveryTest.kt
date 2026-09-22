package com.hipay.core.gateway

import com.hipay.card.recovery.PendingPaymentStore
import com.hipay.card.store.RawSecureStore
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayException
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.TransactionState
import com.hipay.golden.GOLDEN_ORDER_RESPONSE
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeRawSecureStore : RawSecureStore {
    var blob: String? = null
    override fun read(): String? = blob
    override fun write(value: String) { blob = value }
    override fun clear() { blob = null }
}

/** What an order leaves behind for a payment that gets interrupted. */
class GatewayRecoveryTest {

    private val config = HiPayConfig("user", "pass", Environment.STAGE)
    private var clock = 1_000_000L

    private fun store() = PendingPaymentStore(FakeRawSecureStore(), { clock })

    private fun order() = OrderRequest(
        orderId = "TEST-1",
        paymentProduct = "visa",
        amount = "1.00",
        description = "d",
        acceptUrl = "a://x", declineUrl = "a://x", pendingUrl = "a://x",
        exceptionUrl = "a://x", cancelUrl = "a://x",
        cardToken = "tok",
    )

    @Test
    fun theOrderIsRecordedBeforeItIsSentAndCompletedByTheAnswer() = runTest {
        val recovery = store()
        val engine = MockEngine {
            // Asserted from inside the round-trip: this is the window a crash would fall into, and the
            // entry has to exist already — without a reference, which does not exist yet.
            val inFlight = recovery.unresolvedPayments().single()
            assertEquals("TEST-1", inFlight.orderId)
            assertEquals(TransactionState.PENDING, inFlight.lastState)
            assertFalse(inFlight.referenceKnown)
            respond(GOLDEN_ORDER_RESPONSE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        GatewayClient(config, engine, recovery).requestNewOrder(order())

        val settled = recovery.unresolvedPayments().single()
        assertEquals(TransactionState.COMPLETED, settled.lastState)
        assertTrue(settled.referenceKnown)
        assertEquals("1.00", settled.amount)
        assertEquals("EUR", settled.currency)
    }

    @Test
    fun anOrderThatNeverGetsAnAnswerStaysRecoverable() = runTest {
        val recovery = store()
        val engine = MockEngine { throw IllegalStateException("connection lost") }

        assertFailsWith<HiPayException> {
            GatewayClient(config, engine, recovery).requestNewOrder(order())
        }

        // The whole point of recording before the POST: the host keeps its own order id and can ask
        // again, instead of being left with nothing to ask about.
        val orphan = recovery.unresolvedPayments().single()
        assertEquals("TEST-1", orphan.orderId)
        assertFalse(orphan.referenceKnown)
    }

    @Test
    fun aClientWithoutARecoveryStoreRecordsNothing() = runTest {
        val engine = MockEngine {
            respond(GOLDEN_ORDER_RESPONSE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        // Recovery is opt-in: a host that never asked for it gets exactly the previous behaviour.
        GatewayClient(config, engine).requestNewOrder(order())
    }
}
