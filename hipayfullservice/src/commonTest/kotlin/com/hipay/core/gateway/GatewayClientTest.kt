package com.hipay.core.gateway

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
}
