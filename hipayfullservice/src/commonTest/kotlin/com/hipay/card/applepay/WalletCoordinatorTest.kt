package com.hipay.card.applepay

import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayException
import com.hipay.core.gateway.model.TransactionState
import com.hipay.golden.GOLDEN_ORDER_RESPONSE
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
    private val calls = mutableListOf<String>()

    private fun coordinator(
        tokenJson: String = "{\"token\":\"tok-123\",\"brand\":\"visa\"}",
        tokenStatus: HttpStatusCode = HttpStatusCode.OK,
        cfg: HiPayConfig = config,
    ): WalletCoordinator {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("apple-pay/token.json") -> {
                    tokenizeRequest = request
                    calls += "tokenize"
                    respond(tokenJson, tokenStatus, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                path.endsWith("/order") -> {
                    orderRequest = request
                    calls += "order"
                    respond(GOLDEN_ORDER_RESPONSE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> error("unexpected request: ${request.url}")
            }
        }
        return WalletCoordinator(cfg, engine)
    }

    private suspend fun pay(coordinator: WalletCoordinator, applePayCfg: HiPayApplePayConfig = applePayConfig) =
        coordinator.pay(
            paymentData = PAYMENT_DATA,
            applePayConfig = applePayCfg,
            orderId = "AP-1",
            amount = "12.00",
            currency = "EUR",
            description = "d",
            acceptUrl = "a://x", declineUrl = "a://x", pendingUrl = "a://x",
            exceptionUrl = "a://x", cancelUrl = "a://x",
        )

    private suspend fun HttpRequestData.form(): Parameters =
        body.toByteArray().decodeToString().parseUrlEncodedParameters()

    // The nominal payment: tokenize the wallet payload, then order it with the card token,
    // eci=7 / authentication_indicator=0, and return the completed transaction.
    @Test
    fun nominalPaymentTokenizesThenOrders() = runTest {
        val tx = pay(coordinator())

        assertEquals(listOf("tokenize", "order"), calls)
        assertTrue(tokenizeRequest!!.url.encodedPath.endsWith("/rest/v2/apple-pay/token.json"))
        val tokenize = tokenizeRequest!!.form()
        // The payload itself must reach the vault — asserting the field name alone would pass even if
        // an empty token were sent.
        assertEquals(PAYMENT_DATA, tokenize["apple_pay_token"])
        assertEquals("p12pass", tokenize["private_key_pass"])

        val order = orderRequest!!.form()
        assertEquals("visa", order["payment_product"])
        assertEquals("tok-123", order["cardtoken"])
        assertEquals("7", order["eci"])
        assertEquals("0", order["authentication_indicator"])
        // The .p12 password belongs to the vault call only.
        assertNull(order["private_key_pass"])
        assertNull(order["apple_pay_token"])

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

    // A blank dedicated username is not a username: it must fall back to the classic account rather
    // than authenticate as ":password" and 401 after the customer has authorized.
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun blankApplePayUsernameFallsBackToClassicAccount() = runTest {
        val cfg = HiPayApplePayConfig(
            merchantIdentifier = "m", privateKeyPassword = "p12pass", merchantDisplayName = "MyShop",
            applePayUsername = "  ",
        )
        pay(coordinator(), cfg)

        val expected = "Basic " + Base64.encode("user:pass".encodeToByteArray())
        assertEquals(expected, tokenizeRequest!!.headers[HttpHeaders.Authorization])
        assertEquals(expected, orderRequest!!.headers[HttpHeaders.Authorization])
    }

    // Co-branded: payment_product is the resolved domestic network, not the international brand, and
    // the non-selected co-brand is not transmitted.
    @Test
    fun coBrandedResolvesToDomesticNetwork() = runTest {
        pay(coordinator(tokenJson = "{\"token\":\"tok-9\",\"brand\":\"visa\",\"domestic_network\":\"cb\"}"))
        assertEquals("cb", orderRequest!!.form()["payment_product"])
    }

    // A brand the SDK does not map is transmitted as-is: substituting a known network would
    // misdeclare the instrument the wallet token actually resolved to.
    @Test
    fun unmappedBrandIsPassedThroughNotReplaced() = runTest {
        pay(coordinator(tokenJson = "{\"token\":\"tok-7\",\"brand\":\"VPay\"}"))
        assertEquals("vpay", orderRequest!!.form()["payment_product"])
    }

    // No brand at all is an unusable vault response — fail instead of guessing a network.
    @Test
    fun missingBrandFailsBeforeOrdering() = runTest {
        assertFailsWith<HiPayException> { pay(coordinator(tokenJson = "{\"token\":\"tok-6\"}")) }
        assertEquals(listOf("tokenize"), calls)
    }

    // An empty token is unusable: no order must be attempted with it.
    @Test
    fun emptyTokenFailsBeforeOrdering() = runTest {
        assertFailsWith<HiPayException> { pay(coordinator(tokenJson = "{\"token\":\" \"}")) }
        assertEquals(listOf("tokenize"), calls)
    }

    // A rejected tokenize stops the flow — the HTTP client maps the status before any decoding.
    @Test
    fun rejectedTokenizeFailsBeforeOrdering() = runTest {
        assertFailsWith<HiPayException> {
            pay(coordinator(tokenJson = "{\"code\":1000,\"message\":\"nope\"}", tokenStatus = HttpStatusCode.BadRequest))
        }
        assertEquals(listOf("tokenize"), calls)
    }

    private companion object {
        const val PAYMENT_DATA = "{\"data\":\"opaque\",\"version\":\"EC_v1\"}"
    }
}
