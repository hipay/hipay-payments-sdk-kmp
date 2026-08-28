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
        orderStatus: HttpStatusCode = HttpStatusCode.OK,
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
                    respond(
                        if (orderStatus == HttpStatusCode.OK) GOLDEN_ORDER_RESPONSE else "",
                        orderStatus,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> error("unexpected request: ${request.url}")
            }
        }
        return WalletCoordinator(cfg, engine)
    }

    private fun order(orderId: String = "AP-1", redirectScheme: String = "hipaydemo") = ApplePayOrder(
        orderId = orderId,
        amount = "12.00",
        currency = "EUR",
        countryCode = "FR",
        description = "d",
        redirectScheme = redirectScheme,
    )

    private suspend fun pay(
        coordinator: WalletCoordinator,
        applePayCfg: HiPayApplePayConfig = applePayConfig,
        applePayOrder: ApplePayOrder = order(),
    ) = coordinator.pay(
        paymentData = PAYMENT_DATA,
        applePayConfig = applePayCfg,
        order = applePayOrder,
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

    // The gateway redirect URLs are derived from the app scheme in the one shape the SDK can parse
    // back — a challenge return in any other shape could not be read.
    @Test
    fun redirectUrlsAreDerivedFromTheAppScheme() = runTest {
        pay(coordinator())

        val order = orderRequest!!.form()
        val base = "hipaydemo://hipay-payments/gateway/orders/AP-1"
        assertEquals("$base/accept", order["accept_url"])
        assertEquals("$base/decline", order["decline_url"])
        assertEquals("$base/pending", order["pending_url"])
        assertEquals("$base/exception", order["exception_url"])
        assertEquals("$base/cancel", order["cancel_url"])
    }

    // A failed submission is reported once and never resubmitted: a silent retry is the one way the SDK
    // could authorize the same order twice.
    @Test
    fun failedOrderIsNeverResubmitted() = runTest {
        assertFailsWith<HiPayException> {
            pay(coordinator(orderStatus = HttpStatusCode.InternalServerError))
        }
        assertEquals(listOf("tokenize", "order"), calls)
    }

    // Retrying the same payment reuses the same order id, which is what lets the gateway recognize the
    // retry instead of authorizing twice. The wallet token differs (Apple Pay tokens are single-use),
    // so the order id is the only thing tying the two attempts together.
    @Test
    fun retryOfTheSamePaymentReusesTheOrderId() = runTest {
        val sameOrder = order(orderId = "AP-RETRY")

        val first = coordinator()
        pay(first, applePayOrder = sameOrder)
        val firstOrderId = orderRequest!!.form()["orderid"]

        val second = coordinator()
        pay(second, applePayOrder = sameOrder)
        val secondOrderId = orderRequest!!.form()["orderid"]

        assertEquals("AP-RETRY", firstOrderId)
        assertEquals(firstOrderId, secondOrderId)
    }

    // The wallet payload must never surface in anything a host may log — not in the SDK's own error
    // messages, and not through the config that carries the .p12 password.
    @Test
    fun theWalletTokenNeverReachesErrorMessagesOrConfigText() = runTest {
        val failure = assertFailsWith<HiPayException> {
            pay(coordinator(tokenJson = "{\"code\":1000,\"message\":\"nope\"}", tokenStatus = HttpStatusCode.BadRequest))
        }

        assertTrue(PAYMENT_DATA !in failure.message.orEmpty())
        assertTrue(PAYMENT_DATA !in failure.toString())
        assertTrue("p12pass" !in applePayConfig.toString())
    }

    private companion object {
        const val PAYMENT_DATA = "{\"data\":\"opaque\",\"version\":\"EC_v1\"}"
    }
}
