package com.hipay.core.monitoring

import com.hipay.core.Environment
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckoutDataSenderTest {

    private fun event() = CheckoutData(
        event = CheckoutEvent.REQUEST,
        id = "hash",
        status = "118",
        paymentMethod = "visa",
    )

    /** The send is fire-and-forget, so the test waits on the endpoint being reached, not on the call. */
    private fun capturingEngine(
        captured: CompletableDeferred<Pair<HttpRequestData, String>>,
        fail: Boolean = false,
    ) = MockEngine { request ->
        captured.complete(request to request.body.toByteArray().decodeToString())
        if (fail) throw IllegalStateException("network down")
        respond("", HttpStatusCode.OK)
    }

    @Test
    fun postsTheEventToTheEnvironmentsEndpoint() = runTest {
        val captured = CompletableDeferred<Pair<HttpRequestData, String>>()
        CheckoutDataSender(Environment.STAGE, capturingEngine(captured)).send(event())

        val (request, body) = captured.await()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("stage-data.hipay.com", request.url.host)
        assertEquals("/checkout-data", request.url.encodedPath)
        assertTrue(body.contains("\"status\":\"118\""), body)
    }

    @Test
    fun productionGoesToTheProductionEndpoint() {
        assertEquals("https://data.hipay.com/checkout-data", checkoutDataUrl(Environment.PRODUCTION))
    }

    @Test
    fun identifiesItselfToTheIngestionWithoutCarryingMerchantCredentials() = runTest {
        val captured = CompletableDeferred<Pair<HttpRequestData, String>>()
        CheckoutDataSender(Environment.STAGE, capturingEngine(captured)).send(event())

        val (request, _) = captured.await()
        assertTrue(request.headers["X-Who-Api"]?.matches(Regex("sdk-(android|ios)-hipay")) == true)
        // An analytics endpoint, not the gateway: merchant credentials must never reach it.
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun aFailingEndpointIsSwallowedRatherThanRaised() = runTest {
        val captured = CompletableDeferred<Pair<HttpRequestData, String>>()
        val sender = CheckoutDataSender(Environment.STAGE, capturingEngine(captured, fail = true))

        // The throw must die in the sender's own scope, and the next event must still be sent.
        sender.send(event())
        captured.await()
        sender.send(event())
    }

    @Test
    fun noCardDataIsRepresentableInAnEvent() {
        val json = checkoutDataJson.encodeToString(event())

        listOf("pan", "token", "cvc", "card_holder", "cardExpiry").forEach { forbidden ->
            assertFalse(json.contains(forbidden, ignoreCase = true), "$forbidden reached the payload: $json")
        }
    }
}
