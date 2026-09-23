package com.hipay.card.recovery

import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayException
import com.hipay.core.gateway.GatewayClient
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.TransactionState
import com.hipay.golden.GOLDEN_ORDER_RESPONSE
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingPaymentResolverTest {

    private val config = HiPayConfig("user", "pass", Environment.STAGE)
    private var clock = 1_000_000L
    private val store = PendingPaymentStore(FakeRawSecureStore(), { clock })

    private fun order(id: String = "ORDER-1") = OrderRequest(
        orderId = id,
        paymentProduct = "visa",
        amount = "1.00",
        description = "d",
        acceptUrl = "a://x", declineUrl = "a://x", pendingUrl = "a://x",
        exceptionUrl = "a://x", cancelUrl = "a://x",
        cardToken = "tok",
    )

    private fun json(body: String) =
        MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }

    private fun resolver(engine: MockEngine) = PendingPaymentResolver(store, GatewayClient(config, engine, store))

    /**
     * A gateway that answers the order as pending, then the transaction read with [thenState].
     *
     * The client this engine is given also carries the checkout-data sender, so the analytics posts
     * ride the same engine — [seen] keeps the gateway calls only, or an assertion on "the last
     * request" would race a fire-and-forget event.
     */
    private fun twoStepEngine(thenState: String, seen: MutableList<HttpRequestData> = mutableListOf()) =
        MockEngine { request ->
            if (!request.url.host.endsWith("data.hipay.com")) seen += request
            val body = if (request.url.encodedPath.contains("/transaction/")) {
                """{"transaction":{"state":"$thenState","status":"116","transactionReference":"800000000001"}}"""
            } else {
                """{"state":"pending","status":"116","transactionReference":"800000000001"}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

    @Test
    fun anUnknownOrderIsNotOurs() = runTest {
        assertNull(resolver(json(GOLDEN_ORDER_RESPONSE)).refreshPayment("NEVER-LAUNCHED"))
    }

    @Test
    fun refreshingReadsTheCurrentStateFromTheHostsOwnOrderId() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val engine = twoStepEngine("completed", seen)
        val resolver = resolver(engine)
        GatewayClient(config, engine, store).requestNewOrder(order())

        val refreshed = resolver.refreshPayment("ORDER-1")

        assertEquals(TransactionState.COMPLETED, refreshed?.lastState)
        // The host asked with its own id; the reference was used internally and never handed back.
        assertTrue(seen.last().url.encodedPath.endsWith("/transaction/800000000001"))
        assertFalse(refreshed.toString().contains("800000000001"))
    }

    @Test
    fun aTerminalAnswerDoesNotDeleteTheEntry() = runTest {
        val engine = twoStepEngine("completed")
        val resolver = resolver(engine)
        GatewayClient(config, engine, store).requestNewOrder(order())

        resolver.refreshPayment("ORDER-1")

        // Only acknowledge or the lifetime removes it: a host may still die before its own bookkeeping.
        assertEquals(1, resolver.unresolvedPayments().size)
        assertTrue(resolver.acknowledge("ORDER-1"))
        assertTrue(resolver.unresolvedPayments().isEmpty())
    }

    @Test
    fun anOrderWhoseResponseWasLostIsFoundFromTheOrderIdItself() = runTest {
        val lost = MockEngine { throw IllegalStateException("connection lost") }
        assertFailsWith<HiPayException> { GatewayClient(config, lost, store).requestNewOrder(order()) }
        assertFalse(store.unresolvedPayments().single().referenceKnown, "no reference was ever stored")

        val seen = mutableListOf<HttpRequestData>()
        val byOrderId = MockEngine { request ->
            seen += request
            respond(
                """{"transaction":{"state":"completed","status":"118","transactionReference":"800000000001"}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val refreshed = resolver(byOrderId).refreshPayment("ORDER-1", signature = "sig")

        // The window between sending an order and losing its response is recoverable: the gateway
        // finds the transaction from the merchant's own id.
        assertEquals(TransactionState.COMPLETED, refreshed?.lastState)
        assertTrue(seen.single().url.encodedQuery.contains("orderid=ORDER-1"), seen.single().url.toString())
        // And the reference it answered is kept, so the next read goes direct.
        assertTrue(store.unresolvedPayments().single().referenceKnown)
    }

    @Test
    fun anUnreachableGatewayRaisesRatherThanInventAnOutcome() = runTest {
        val orderEngine = twoStepEngine("completed")
        GatewayClient(config, orderEngine, store).requestNewOrder(order())
        val downEngine = MockEngine { throw IllegalStateException("connection lost") }

        assertFailsWith<HiPayException> { resolver(downEngine).refreshPayment("ORDER-1") }

        // The entry survives, so the question can be asked again.
        assertEquals(1, store.unresolvedPayments().size)
    }
}
