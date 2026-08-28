package com.hipay.core.gateway

import com.hipay.card.validation.CardNetwork
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.TransactionState
import com.hipay.golden.GOLDEN_ORDER_RESPONSE
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GatewayClientTest {

    private val config = HiPayConfig("user", "pass", Environment.STAGE)

    private fun order() = OrderRequest(
        orderId = "TEST-1",
        paymentProduct = "visa",
        amount = "1.00",
        description = "d",
        language = "fr_FR",
        acceptUrl = "a://x", declineUrl = "a://x", pendingUrl = "a://x",
        exceptionUrl = "a://x", cancelUrl = "a://x",
        cardToken = "tok",
    )

    private fun goldenEngine(check: suspend (io.ktor.client.request.HttpRequestData) -> Unit = {}) =
        MockEngine { request ->
            check(request)
            respond(
                GOLDEN_ORDER_RESPONSE,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

    @Test
    fun nominalCardOrderPostsFieldsAndMapsTransaction() = runTest {
        val engine = goldenEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/rest/v1/order", request.url.encodedPath)
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("orderid=TEST-1"))
            assertTrue(body.contains("operation=Sale"))
            assertTrue(body.contains("cardtoken=tok"))
            assertTrue(body.contains("eci=7"))
            assertTrue(body.contains("authentication_indicator=0"))
            // Basic auth by default
            assertEquals("Basic dXNlcjpwYXNz", request.headers[HttpHeaders.Authorization])
        }
        val tx = GatewayClient(config, engine).requestNewOrder(order())
        assertEquals(TransactionState.COMPLETED, tx.state)
        assertEquals("800000000001", tx.transactionReference)
    }

    @Test
    fun providedSignatureSwitchesToHsSchemeOnBothMethods() = runTest {
        // base64("user:sig-123") = dXNlcjpzaWctMTIz
        val engine = goldenEngine { request ->
            assertEquals("HS dXNlcjpzaWctMTIz", request.headers[HttpHeaders.Authorization])
        }
        val client = GatewayClient(config, engine)
        client.requestNewOrder(order(), signature = "sig-123")
        client.getTransaction("ref-1", signature = "sig-123")
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun getTransactionHitsTheDocumentedPath() = runTest {
        val engine = goldenEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/rest/v1/transaction/ref800", request.url.encodedPath)
        }
        val tx = GatewayClient(config, engine).getTransaction("ref800")
        assertEquals(TransactionState.COMPLETED, tx.state)
    }

    @Test
    fun getTransactionUnwrapsTheTransactionKey() = runTest {
        // GET transaction/{ref} wraps its payload (legacy HPFTransactionDetailsMapper)
        val engine = MockEngine {
            respond(
                """{"transaction": $GOLDEN_ORDER_RESPONSE}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val tx = GatewayClient(config, engine).getTransaction("ref800")
        assertEquals(TransactionState.COMPLETED, tx.state)
        assertEquals("800000000001", tx.transactionReference)
    }

    @Test
    fun apiErrorMapsToApi() = runTest {
        val engine = MockEngine {
            respond(
                """{"code":"1000001","message":"Incorrect Credentials : Insufficient Privilege","description":""}""",
                HttpStatusCode.Unauthorized,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val ex = assertFailsWith<HiPayException> {
            GatewayClient(config, engine).requestNewOrder(order())
        }
        assertEquals(HiPayErrorCode.API, ex.code)
        assertEquals(1000001, ex.apiCode)
    }

    @Test
    fun unusableSuccessBodyMapsToServerWithoutEcho() = runTest {
        val engine = MockEngine { respond("not-json cardtoken=tok", HttpStatusCode.OK) }
        val ex = assertFailsWith<HiPayException> {
            GatewayClient(config, engine).requestNewOrder(order())
        }
        assertEquals(HiPayErrorCode.SERVER, ex.code)
        assertTrue(!ex.message!!.contains("cardtoken"))
    }

    // ---- available-payment-products: the account's card networks ----

    private fun productsEngine(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        capture: ((io.ktor.client.request.HttpRequestData) -> Unit)? = null,
    ) = MockEngine { request ->
        capture?.invoke(request)
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    // The query must match the web integration's card path exactly, or the two channels would
    // resolve different sets for the same account.
    @Test
    fun accountQueryUsesTheDocumentedCardParameters() = runTest {
        var captured: io.ktor.client.request.HttpRequestData? = null
        val engine = productsEngine("[]") { captured = it }

        GatewayClient(config, engine).getAvailablePaymentProducts(
            paymentProducts = listOf("visa", "cb"),
            currency = "EUR",
        )

        val url = captured!!.url
        assertEquals(HttpMethod.Get, captured!!.method)
        assertTrue(url.encodedPath.endsWith("/rest/v2/available-payment-products.json"))
        assertEquals("7", url.parameters["eci"])
        assertEquals("4", url.parameters["operation"])
        assertEquals("EUR", url.parameters["currency"])
        assertEquals("visa,cb", url.parameters["payment_product"])
        assertEquals("true", url.parameters["with_options"])
        assertEquals("", url.parameters["customer_country"])
    }

    @Test
    fun customerCountryIsSentWhenSupplied() = runTest {
        var captured: io.ktor.client.request.HttpRequestData? = null
        val engine = productsEngine("[]") { captured = it }

        GatewayClient(config, engine).getAvailablePaymentProducts(
            paymentProducts = listOf("visa"),
            currency = "EUR",
            customerCountry = "FR",
        )

        assertEquals("FR", captured!!.url.parameters["customer_country"])
    }

    @Test
    fun productCodesMapToCardNetworks() = runTest {
        val body = """[{"code":"visa"},{"code":"american-express"},{"code":"cb"}]"""
        val networks = GatewayClient(config, productsEngine(body)).getAvailablePaymentProducts(
            paymentProducts = listOf("visa"),
            currency = "EUR",
        )
        assertEquals(setOf(CardNetwork.VISA, CardNetwork.AMEX, CardNetwork.CB), networks)
    }

    // A non-card or unknown product is not an error — the account may well sell more than cards.
    @Test
    fun nonCardProductsAreIgnoredNotFatal() = runTest {
        val body = """[{"code":"paypal"},{"code":"visa"},{"code":42},{"noCode":true}]"""
        val networks = GatewayClient(config, productsEngine(body)).getAvailablePaymentProducts(
            paymentProducts = listOf("visa"),
            currency = "EUR",
        )
        assertEquals(setOf(CardNetwork.VISA), networks)
    }

    // An account contracted for no card answers with an empty LIST. That is a verdict (refuse every
    // card), not a malformed response, so it must come back as an empty set rather than throwing.
    @Test
    fun anEmptyListIsAnEmptySetNotAnError() = runTest {
        assertEquals(
            emptySet(),
            GatewayClient(config, productsEngine("[]")).getAvailablePaymentProducts(listOf("visa"), "EUR"),
        )
    }

    // But anything that is NOT a list must fail rather than pass for "no products": read as a verdict
    // it would refuse every card on the account. A blank body and an object without a list are both
    // unusable, not empty.
    @Test
    fun anAnswerThatIsNotAListFailsRatherThanReadingAsNoProducts() = runTest {
        listOf("", "not-json", "{}", "{\"error\":\"nope\"}", "null").forEach { body ->
            assertFailsWith<HiPayException>("expected failure for body '$body'") {
                GatewayClient(config, productsEngine(body)).getAvailablePaymentProducts(listOf("visa"), "EUR")
            }
        }
    }

    // A non-2xx must throw so the caller can tell "the ceiling is unknown" from "the account takes
    // no card" — the whole failure/verdict distinction depends on it.
    // The body is a PERFECTLY READABLE product list, so the only thing that can make this fail is the
    // status itself — otherwise the test would pass even if the status were ignored entirely, and the
    // failure-versus-verdict distinction rests on that status.
    @Test
    fun nonSuccessStatusThrowsEvenWithAReadableBody() = runTest {
        val engine = productsEngine("""[{"code":"visa"}]""", HttpStatusCode.InternalServerError)
        assertFailsWith<HiPayException> {
            GatewayClient(config, engine).getAvailablePaymentProducts(listOf("visa"), "EUR")
        }
    }

    // Products WERE listed and none mapped to a known card network — a mapping gap on our side (a
    // renamed or unmapped product code), NOT an account without cards. Read as a verdict it would
    // refuse every payer, so it must fail and leave the caller's ceiling unknown.
    @Test
    fun aListWeUnderstoodNothingOfFailsRatherThanRefusingEveryCard() = runTest {
        val body = """[{"code":"paypal"},{"code":"visa-electron"},{"code":"sofort"}]"""
        val ex = assertFailsWith<HiPayException> {
            GatewayClient(config, productsEngine(body)).getAvailablePaymentProducts(listOf("visa"), "EUR")
        }
        assertEquals(HiPayErrorCode.SERVER, ex.code)
    }

    // An object wrapper is NOT tolerated: "the first array-valued member" would read an error payload
    // such as {"errors":[…]} as a product list and turn it into the refuse-everything verdict.
    @Test
    fun anObjectWrappingAnArrayIsNotReadAsAProductList() = runTest {
        listOf(
            """{"errors":[{"message":"nope"}]}""",
            """{"products":[{"code":"visa"}]}""",
            """{"warnings":[],"products":[{"code":"visa"}]}""",
        ).forEach { body ->
            assertFailsWith<HiPayException>("expected failure for body '$body'") {
                GatewayClient(config, productsEngine(body)).getAvailablePaymentProducts(listOf("visa"), "EUR")
            }
        }
    }

    @Test
    fun unusableProductsBodyMapsToServer() = runTest {
        val engine = productsEngine("not-json")
        val ex = assertFailsWith<HiPayException> {
            GatewayClient(config, engine).getAvailablePaymentProducts(listOf("visa"), "EUR")
        }
        assertEquals(HiPayErrorCode.SERVER, ex.code)
    }
}
