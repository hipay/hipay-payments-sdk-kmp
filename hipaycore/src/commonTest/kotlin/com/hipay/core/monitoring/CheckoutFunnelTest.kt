package com.hipay.core.monitoring

import com.hipay.card.CardTokenizer
import com.hipay.card.applepay.ApplePayTokenizer
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.gateway.GatewayClient
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.golden.GOLDEN_ORDER_RESPONSE
import com.hipay.golden.GOLDEN_TOKEN_CREATE_RESPONSE
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The three events of one checkout: what each carries, and what ties them together. */
@OptIn(HiPayInternalApi::class)
class CheckoutFunnelTest {

    private val config = HiPayConfig("user", "pass", Environment.STAGE)

    @AfterTest
    fun closeSession() = CheckoutSession.resetForTest()

    /** Serves the analytics endpoint into [captured] and everything else with [apiResponse]. */
    private fun engine(captured: CompletableDeferred<String>, apiResponse: String) =
        MockEngine { request ->
            if (request.url.host.endsWith("data.hipay.com")) {
                captured.complete(request.body.toByteArray().decodeToString())
                respond("", HttpStatusCode.OK)
            } else {
                respond(apiResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }

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
    fun displayingThePaymentSurfaceReportsTheCreationToDisplayDelay() = runTest {
        val captured = CompletableDeferred<String>()
        val monitor = HiPayCheckoutMonitor(CheckoutDataSender(Environment.STAGE, engine(captured, "")))

        monitor.paymentSurfaceCreated()
        monitor.paymentSurfaceDisplayed()

        val body = captured.await()
        assertTrue(body.contains("\"event\":\"init\""), body)
        assertTrue(body.contains("\"date_init\":"), body)
        assertTrue(body.contains("\"date_display\":"), body)
    }

    @Test
    fun onlyTheFirstRenderOfASessionReports() = runTest {
        // A second event would land in `second`; MockEngine's own history is appended after the
        // handler returns, so it cannot be counted from here without racing.
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()
        val mock = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            if (!first.complete(body)) second.complete(body)
            respond("", HttpStatusCode.OK)
        }
        val monitor = HiPayCheckoutMonitor(CheckoutDataSender(Environment.STAGE, mock))

        monitor.paymentSurfaceCreated()
        monitor.paymentSurfaceDisplayed()
        first.await()
        // A recomposition, or the native controller the Android CMP surface delegates to.
        monitor.paymentSurfaceDisplayed()

        assertFalse(second.isCompleted, "init must be raised once per session")
        assertTrue(CheckoutSession.current().displayReported)
    }

    @Test
    fun tokenizingReportsTheBrandAndTheCountryButNoToken() = runTest {
        val captured = CompletableDeferred<String>()
        val mock = engine(captured, GOLDEN_TOKEN_CREATE_RESPONSE)
        val tokenizer = CardTokenizer(config, mock, CheckoutDataSender(Environment.STAGE, mock))

        tokenizer.generateToken("4111111111111111", "12", "2030", "Test", "123", multiUse = false)

        val body = captured.await()
        assertTrue(body.contains("\"event\":\"tokenize\""), body)
        assertTrue(body.contains("\"date_pay\":"), body)
        assertTrue(body.contains("\"payment_method\":\"visa\""), body)
        assertTrue(body.contains("\"card_country\":\"PL\""), body)
        // The golden token and its masked PAN stay inside the tokenizer.
        assertTrue(!body.contains("f0e1d2c3"), body)
        assertTrue(!body.contains("411111"), body)
    }

    @Test
    fun theWalletReportsItsOwnTokenizationStep() = runTest {
        val captured = CompletableDeferred<String>()
        val mock = engine(captured, GOLDEN_TOKEN_CREATE_RESPONSE)
        val tokenizer = ApplePayTokenizer(config, mock, CheckoutDataSender(Environment.STAGE, mock))

        tokenizer.tokenize("{\"data\":\"…\"}", privateKeyPassword = "hipayStage")

        val body = captured.await()
        assertTrue(body.contains("\"event\":\"tokenize\""), body)
        assertTrue(body.contains("\"date_pay\":"), body)
        assertTrue(body.contains("\"payment_method\":\"visa\""), body)
        // The wallet payload and the token it produces stay inside the tokenizer.
        assertTrue(!body.contains("f0e1d2c3"), body)
        assertTrue(!body.contains("apple_pay_token"), body)
    }

    @Test
    fun theEventsOfOneCheckoutCarryTheSameCorrelationId() = runTest {
        val session = CheckoutSession.start()

        val tokenizeBody = CompletableDeferred<String>()
        val vault = engine(tokenizeBody, GOLDEN_TOKEN_CREATE_RESPONSE)
        CardTokenizer(config, vault, CheckoutDataSender(Environment.STAGE, vault))
            .generateToken("4111111111111111", "12", "2030", "Test", "123", multiUse = false)

        val requestBody = CompletableDeferred<String>()
        GatewayClient(config, engine(requestBody, GOLDEN_ORDER_RESPONSE)).requestNewOrder(order())

        val id = "\"id\":\"${session.id}\""
        assertTrue(tokenizeBody.await().contains(id), "tokenize left the session")
        assertTrue(requestBody.await().contains(id), "request left the session")
    }

    @Test
    fun theOrderEventTiesTheJourneyToItsTransaction() = runTest {
        val captured = CompletableDeferred<String>()
        GatewayClient(config, engine(captured, GOLDEN_ORDER_RESPONSE)).requestNewOrder(order())

        // The merchant reconciles on its own order id; the reference is what HiPay answered with.
        val body = captured.await()
        assertTrue(body.contains("\"order_id\":\"TEST-1\""), body)
        assertTrue(body.contains("\"transaction_id\":\""), body)
        assertTrue(body.contains("\"amount\":1.0"), body)
        assertTrue(body.contains("\"currency\":\"EUR\""), body)
    }

    @Test
    fun theStepsBeforeTheOrderCarryNoOrderFields() = runTest {
        val captured = CompletableDeferred<String>()
        val vault = engine(captured, GOLDEN_TOKEN_CREATE_RESPONSE)
        CardTokenizer(config, vault, CheckoutDataSender(Environment.STAGE, vault))
            .generateToken("4111111111111111", "12", "2030", "Test", "123", multiUse = false)

        // Nothing links tokenization to an order yet — the correlation id is what will.
        val body = captured.await()
        listOf("order_id", "transaction_id", "amount", "currency").forEach {
            assertFalse(body.contains(it), "$it must not be on the tokenize event: $body")
        }
    }

    @Test
    fun noEventEverCarriesCardData() = runTest {
        val captured = CompletableDeferred<String>()
        val vault = engine(captured, GOLDEN_TOKEN_CREATE_RESPONSE)
        CardTokenizer(config, vault, CheckoutDataSender(Environment.STAGE, vault))
            .generateToken("4111111111111111", "12", "2030", "Jane Doe", "123", multiUse = false)

        // The PAN the payer typed, the holder name, and the vault token the response carried. A CVV
        // is deliberately not asserted here: three digits match any timestamp by chance.
        val body = captured.await()
        listOf(
            "4111111111111111",
            "Jane Doe",
            "f0e1d2c3b4a5968778695a4b3c2d1e0ff0e1d2c3b4a5968778695a4b3c2d1e0f",
        ).forEach {
            assertFalse(body.contains(it, ignoreCase = true), "$it reached the payload: $body")
        }
    }

    @Test
    fun creatingACardEntryOpensAFreshSession() {
        val first = CheckoutSession.start()
        val second = CheckoutSession.start()

        assertTrue(first.id != second.id, "a new card component must not inherit the previous funnel")
    }
}
