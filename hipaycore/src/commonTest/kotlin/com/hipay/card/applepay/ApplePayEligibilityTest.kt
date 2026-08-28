package com.hipay.card.applepay

import com.hipay.card.validation.CardNetwork
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.gateway.GatewayClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplePayEligibilityTest {

    private val config = HiPayConfig("user", "pass", Environment.STAGE)

    /** A device seam whose base capability and per-network answer are both scripted. */
    private class FakeDevice(
        private val base: Boolean = true,
        private val payableNetworks: Set<CardNetwork> = CardNetwork.entries.toSet(),
    ) : ApplePayDeviceCapability {
        override fun canMakePayments(): Boolean = base
        override fun canMakePayments(networks: List<CardNetwork>): Boolean =
            networks.any { it in payableNetworks }
    }

    /** Mock gateway returning an `available-payment-products` array for [codes]. */
    private fun gatewayReturning(
        codes: List<String>,
        onRequest: (io.ktor.client.request.HttpRequestData) -> Unit = {},
    ): GatewayClient {
        val body = codes.joinToString(prefix = "[", postfix = "]") { "{\"code\":\"$it\"}" }
        val engine = MockEngine { request ->
            onRequest(request)
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return GatewayClient(config, engine)
    }

    private suspend fun eligibility(
        codes: List<String>,
        device: ApplePayDeviceCapability,
        allowedNetworks: List<CardNetwork> = emptyList(),
        onRequest: (io.ktor.client.request.HttpRequestData) -> Unit = {},
    ) = resolveApplePayEligibility(
        gateway = gatewayReturning(codes, onRequest),
        device = device,
        currency = "EUR",
        customerCountry = "FR",
        allowedNetworks = allowedNetworks,
    )

    // AC1 — account with Visa routable + a Visa card on device → available, resolved contains Visa.
    @Test
    fun availableWhenRoutableNetworkAndDeviceCard() = runTest {
        val result = eligibility(
            codes = listOf("visa", "mastercard"),
            device = FakeDevice(payableNetworks = setOf(CardNetwork.VISA)),
        ) { request ->
            // Task 2 — the account query is a GET to gateway-v2 with the ECI-7 SALE params.
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/rest/v2/available-payment-products.json", request.url.encodedPath)
            val q = request.url.parameters
            assertEquals("7", q["eci"])
            assertEquals("4", q["operation"])
            assertEquals("true", q["with_options"])
            assertEquals("EUR", q["currency"])
            assertEquals("FR", q["customer_country"])
        }
        assertEquals(ApplePayEligibilityState.AVAILABLE, result.state)
        assertEquals(ApplePayEligibilityReason.AVAILABLE, result.reason)
        assertTrue(CardNetwork.VISA in result.resolvedNetworks)
    }

    // AC2 — Amex is card-active but not routable via Apple Pay (absent from the HiPay routable set)
    // → excluded from resolved, even if the account returns it.
    @Test
    fun amexExcludedFromResolved() = runTest {
        val result = eligibility(
            codes = listOf("visa", "american-express"),
            device = FakeDevice(payableNetworks = setOf(CardNetwork.VISA)),
        )
        assertEquals(ApplePayEligibilityState.AVAILABLE, result.state)
        assertFalse(CardNetwork.AMEX in result.resolvedNetworks)
        assertTrue(CardNetwork.VISA in result.resolvedNetworks)
    }

    // Bancontact is likewise not routable (absent from the routable set) → dropped.
    @Test
    fun bancontactExcludedFromResolved() = runTest {
        val result = eligibility(
            codes = listOf("visa", "bcmc"),
            device = FakeDevice(payableNetworks = setOf(CardNetwork.VISA)),
        )
        assertFalse(CardNetwork.BCMC in result.resolvedNetworks)
        assertTrue(CardNetwork.VISA in result.resolvedNetworks)
    }

    // AC3 — no routable network (account accepts only Amex, which is not routable) → unavailable,
    // empty, noRoutableNetwork.
    @Test
    fun unavailableWhenNoRoutableNetwork() = runTest {
        val result = eligibility(
            codes = listOf("american-express"),
            device = FakeDevice(),
        )
        assertEquals(ApplePayEligibilityState.UNAVAILABLE, result.state)
        assertEquals(ApplePayEligibilityReason.NO_ROUTABLE_NETWORK, result.reason)
        assertTrue(result.resolvedNetworks.isEmpty())
    }

    // AC4 — merchant restricts to Visa + CB → resolved is exactly [Visa, CB].
    @Test
    fun merchantRestrictionNarrows() = runTest {
        val result = eligibility(
            codes = listOf("visa", "mastercard", "cb"),
            device = FakeDevice(),
            allowedNetworks = listOf(CardNetwork.VISA, CardNetwork.CB),
        )
        assertEquals(ApplePayEligibilityState.AVAILABLE, result.state)
        assertEquals(listOf(CardNetwork.VISA, CardNetwork.CB), result.resolvedNetworks)
    }

    // A merchant that enumerates its networks keeps ITS order. `PKPaymentRequest.supportedNetworks` is
    // order-significant — the first entry is what the sheet defaults to — so overriding the merchant's
    // order would silently override which network their co-badged cards route on.
    @Test
    fun resolvedOrderFollowsTheMerchantWhenItDeclaresOne() = runTest {
        val result = eligibility(
            codes = listOf("cb", "visa", "mastercard"),
            device = FakeDevice(),
            allowedNetworks = listOf(CardNetwork.CB, CardNetwork.VISA),
        )
        assertEquals(listOf(CardNetwork.CB, CardNetwork.VISA), result.resolvedNetworks)

        // The reverse declaration is honoured too, so the order really is theirs and not a coincidence.
        val reversed = eligibility(
            codes = listOf("cb", "visa", "mastercard"),
            device = FakeDevice(),
            allowedNetworks = listOf(CardNetwork.VISA, CardNetwork.CB),
        )
        assertEquals(listOf(CardNetwork.VISA, CardNetwork.CB), reversed.resolvedNetworks)
    }

    // With NO merchant restriction the SDK supplies the order, and CB leads it: on a co-badged CB/Visa
    // card the sheet then defaults to the domestic network. This is the default every integrator gets
    // without doing anything, so it is the case worth pinning.
    @Test
    fun withoutARestrictionTheSdkOrdersCbFirst() = runTest {
        val result = eligibility(
            codes = listOf("visa", "mastercard", "cb", "maestro"),
            device = FakeDevice(),
            allowedNetworks = emptyList(),
        )
        assertEquals(CardNetwork.CB, result.resolvedNetworks.first())
        assertEquals(
            listOf(CardNetwork.CB, CardNetwork.VISA, CardNetwork.MASTERCARD, CardNetwork.MAESTRO),
            result.resolvedNetworks,
        )
    }

    // CB leads only when the account actually routes it — the SDK order is a preference, never a claim.
    @Test
    fun cbIsAbsentWhenTheAccountDoesNotRouteIt() = runTest {
        val result = eligibility(
            codes = listOf("visa", "mastercard"),
            device = FakeDevice(),
            allowedNetworks = emptyList(),
        )
        assertEquals(listOf(CardNetwork.VISA, CardNetwork.MASTERCARD), result.resolvedNetworks)
    }

    // The account query actually RECOGNIZES the Amex brand (so AC2's exclusion is by the routable
    // set, not because Amex was silently unmapped).
    @Test
    fun gatewayRecognizesAmexBrand() = runTest {
        val accepted = gatewayReturning(listOf("visa", "american-express"))
            .getAvailablePaymentProducts(ApplePayNetworks.cardProductCodes, "EUR")
        assertTrue(CardNetwork.AMEX in accepted)
        assertTrue(CardNetwork.VISA in accepted)
    }

    // A product whose `code` is not a plain string is skipped, not fatal — the good products survive.
    @Test
    fun malformedProductCodeIsSkippedNotFatal() = runTest {
        val engine = MockEngine {
            respond(
                "[{\"code\":\"visa\"},{\"code\":{\"nested\":true}}]",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val accepted = GatewayClient(config, engine)
            .getAvailablePaymentProducts(ApplePayNetworks.cardProductCodes, "EUR")
        assertEquals(setOf(CardNetwork.VISA), accepted)
    }

    // AC5 — merchant declares Visa + Mastercard but only Visa routable → resolved is exactly [Visa].
    @Test
    fun merchantCannotWidenBeyondRoute() = runTest {
        val result = eligibility(
            codes = listOf("visa"),
            device = FakeDevice(),
            allowedNetworks = listOf(CardNetwork.VISA, CardNetwork.MASTERCARD),
        )
        assertEquals(listOf(CardNetwork.VISA), result.resolvedNetworks)
    }

    // AC6 — only Mastercard routable, device holds only Visa → unavailable, deviceNoUsableCard.
    @Test
    fun unavailableWhenDeviceHasNoUsableCard() = runTest {
        val result = eligibility(
            codes = listOf("mastercard"),
            device = FakeDevice(payableNetworks = setOf(CardNetwork.VISA)),
        )
        assertEquals(ApplePayEligibilityState.UNAVAILABLE, result.state)
        assertEquals(ApplePayEligibilityReason.DEVICE_NO_USABLE_CARD, result.reason)
        assertEquals(listOf(CardNetwork.MASTERCARD), result.resolvedNetworks)
    }

    // Device cannot do Apple Pay at all → unavailable without querying the account.
    @Test
    fun unavailableAndNoQueryWhenDeviceCannotPay() = runTest {
        var called = false
        val result = eligibility(
            codes = listOf("visa"),
            device = FakeDevice(base = false),
        ) { called = true }
        assertEquals(ApplePayEligibilityState.UNAVAILABLE, result.state)
        assertEquals(ApplePayEligibilityReason.DEVICE_NO_USABLE_CARD, result.reason)
        assertFalse(called, "account query must be skipped when the device cannot pay")
    }

    // Country is optional — a null country sends an empty customer_country (web-SDK default).
    @Test
    fun nullCountrySendsEmptyCustomerCountry() = runTest {
        var seen: String? = "unset"
        resolveApplePayEligibility(
            gateway = gatewayReturning(listOf("visa")) { seen = it.url.parameters["customer_country"] },
            device = FakeDevice(),
            currency = "EUR",
            customerCountry = null,
        )
        assertEquals("", seen)
    }
}
