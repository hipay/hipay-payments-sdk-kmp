package com.hipay.card.applepay

import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.gateway.model.TransactionState
import com.hipay.golden.GOLDEN_ORDER_RESPONSE
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalletCoordinatorTest {

    private val config = HiPayConfig("user", "pass", Environment.STAGE)
    private val applePayConfig = HiPayApplePayConfig(
        merchantIdentifier = "merchant.com.hipay.demo",
        privateKeyPassword = "p12pass",
        merchantDisplayName = "MyShop",
    )

    private var tokenizeRequest: HttpRequestData? = null
    private var orderRequest: HttpRequestData? = null

    private fun coordinator(
        tokenJson: String = "{\"token\":\"tok-123\",\"brand\":\"visa\"}",
        cfg: HiPayConfig = config,
    ): WalletCoordinator {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("apple-pay/token.json") -> {
                    tokenizeRequest = request
                    respond(tokenJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                path.endsWith("/order") -> {
                    orderRequest = request
                    respond(GOLDEN_ORDER_RESPONSE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> error("unexpected request: ${request.url}")
            }
        }
        return WalletCoordinator(cfg, engine)
    }

    private suspend fun pay(coordinator: WalletCoordinator, applePayCfg: HiPayApplePayConfig = applePayConfig) =
        coordinator.pay(
            paymentData = "{\"data\":\"opaque\"}",
            applePayConfig = applePayCfg,
            orderId = "AP-1",
            amount = "12.00",
            currency = "EUR",
            description = "d",
            acceptUrl = "a://x", declineUrl = "a://x", pendingUrl = "a://x",
            exceptionUrl = "a://x", cancelUrl = "a://x",
        )

    // AC6 — tokenize → order(eci7/ind0, payment_product=brand, cardtoken) → completed transaction.
    @Test
    fun nominalPaymentTokenizesThenOrders() = runTest {
        val tx = pay(coordinator())

        val tokenizeBody = tokenizeRequest!!.body.toByteArray().decodeToString()
        assertTrue(tokenizeRequest!!.url.encodedPath.endsWith("/rest/v2/apple-pay/token.json"))
        assertTrue(tokenizeBody.contains("apple_pay_token="))
        assertTrue(tokenizeBody.contains("private_key_pass=p12pass"))

        val orderBody = orderRequest!!.body.toByteArray().decodeToString()
        assertTrue(orderBody.contains("payment_product=visa"))
        assertTrue(orderBody.contains("cardtoken=tok-123"))
        assertTrue(orderBody.contains("eci=7"))
        assertTrue(orderBody.contains("authentication_indicator=0"))

        assertEquals(TransactionState.COMPLETED, tx.state)
    }

    // Both tokenize and order route through applePayUsername when present (no legacy asymmetry).
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun applePayUsernameRoutesBothCalls() = runTest {
        val cfg = HiPayApplePayConfig(
            merchantIdentifier = "m", privateKeyPassword = "p12pass", merchantDisplayName = "MyShop",
            applePayUsername = "apuser",
        )
        pay(coordinator(), cfg)

        val expected = "Basic " + Base64.encode("apuser:pass".encodeToByteArray())
        assertEquals(expected, tokenizeRequest!!.headers[HttpHeaders.Authorization])
        assertEquals(expected, orderRequest!!.headers[HttpHeaders.Authorization])
    }

    // AC5 — co-branded: payment_product is the resolved domestic network (cb), not the international
    // brand, and the non-selected co-brand is not transmitted.
    @Test
    fun coBrandedResolvesToDomesticNetwork() = runTest {
        pay(coordinator(tokenJson = "{\"token\":\"tok-9\",\"brand\":\"visa\",\"domestic_network\":\"cb\"}"))
        val orderBody = orderRequest!!.body.toByteArray().decodeToString()
        assertTrue(orderBody.contains("payment_product=cb"))
        assertTrue(!orderBody.contains("payment_product=visa"))
    }
}
