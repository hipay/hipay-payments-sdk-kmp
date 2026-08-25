package com.hipay.core.gateway.model

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.golden.GOLDEN_ORDER_REQUEST
import com.hipay.golden.assertJsonParity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderRequestParityTest {

    /** Fields derived from the running device, so their values cannot be pinned in the golden. */
    private val DEVICE_DERIVED = setOf("source", "http_user_agent")

    private fun cardOrder(
        customer: CustomerInfo? = null,
        shipping: CustomerInfo? = null,
        customData: Map<String, String> = emptyMap(),
        oneClick: Boolean = false,
    ) = OrderRequest(
        oneClick = oneClick,
        orderId = "TEST-ORDER-1",
        paymentProduct = "visa",
        operation = Operation.SALE,
        amount = "1.00",
        currency = "EUR",
        description = "Test order",
        language = "fr_FR",
        acceptUrl = "hipaydemo://hipay-payments/gateway/orders/TEST-ORDER-1/accept",
        declineUrl = "hipaydemo://hipay-payments/gateway/orders/TEST-ORDER-1/decline",
        pendingUrl = "hipaydemo://hipay-payments/gateway/orders/TEST-ORDER-1/pending",
        exceptionUrl = "hipaydemo://hipay-payments/gateway/orders/TEST-ORDER-1/exception",
        cancelUrl = "hipaydemo://hipay-payments/gateway/orders/TEST-ORDER-1/cancel",
        cardToken = "f0e1d2c3b4a5968778695a4b3c2d1e0f".repeat(2),
        customer = customer,
        shippingAddress = shipping,
        customData = customData,
    )

    @Test
    fun cardOrderFieldsMatchTheGoldenRequest() {
        // `source` and `http_user_agent` are deliberately out of the golden: both are derived from the
        // running device, so a literal would fail on another simulator or on the other target. Their
        // shapes are asserted by the two tests below; the golden stays the stable contract for the
        // fields that do not move.
        val fields = cardOrder().toFields() - DEVICE_DERIVED
        assertJsonParity(GOLDEN_ORDER_REQUEST, JsonObject(fields.mapValues { JsonPrimitive(it.value) }))
    }

    @Test
    fun sourceIdentifiesThisClientSdk() {
        // Restored parity with both previous-generation SDKs, which sent this and made the back office
        // attribute a mobile payment correctly instead of defaulting it to a desktop profile.
        val source = cardOrder().toFields().getValue("source")
        val parsed = Json.parseToJsonElement(source).jsonObject

        assertEquals("CSDK", parsed.getValue("source").jsonPrimitive.content)
        assertTrue(parsed.getValue("brand").jsonPrimitive.content in setOf("android", "ios"))
        // The version the merchant is actually running — the point of the whole field.
        assertTrue(parsed.getValue("integration_version").jsonPrimitive.content.isNotBlank())
        assertTrue("brand_version" in parsed)
    }

    @Test
    fun userAgentLooksLikeADeviceNotABlank() {
        // Restored alongside `source`: parsing the User-Agent is how the platform attributes a
        // transaction to a device.
        //
        // Absent is a VALID outcome, and the reason matters: the gateway SCORES this field. A malformed
        // value is not a harmless approximation — an early version fell back to
        // `Dalvik/2.1.0 (Linux; U; Android )` when `http.agent` was unset, and that turned a real stage
        // order from COMPLETED into DECLINED. Omitting the field completes normally, so a platform that
        // cannot produce a credible value must send nothing.
        val ua = cardOrder().toFields()["http_user_agent"] ?: return
        assertTrue(ua.isNotBlank(), "when present, http_user_agent must not be blank")

        if (ua.contains("Android")) {
            // Android hands us its own `http.agent`, so the only claim worth making is that the OS
            // named itself — the model and build come from the device and cannot be pinned here.
            assertTrue(ua.contains("Linux"), "unexpected Android User-Agent: $ua")
        } else {
            // iOS is synthesized, so the FULL Safari shape is asserted: the device attribution depends
            // on the string being recognisable as a browser, and a missing token is the likely reason a
            // parser would fall through to its default profile.
            assertTrue(ua.startsWith("Mozilla/5.0 ("), "must open like a browser: $ua")
            assertTrue(ua.contains("iPhone") || ua.contains("iPad"), "must name the device: $ua")
            assertTrue(ua.contains("like Mac OS X"), "missing platform token: $ua")
            assertTrue(ua.contains("AppleWebKit/605.1.15"), "missing engine token: $ua")
            assertTrue(ua.contains("Version/"), "missing Safari version: $ua")
            assertTrue(ua.endsWith("Safari/604.1"), "must be identifiable as Safari: $ua")
            // Apple's own inconsistency, reproduced: iPad omits the model from the CPU token.
            if (ua.contains("iPad")) {
                assertTrue(ua.contains("iPad; CPU OS "), "iPad token must be 'CPU OS': $ua")
            } else {
                assertTrue(ua.contains("CPU iPhone OS "), "iPhone token must be 'CPU iPhone OS': $ua")
            }
            // Underscores in the platform token, dots in Version/ — both from the same OS version.
            assertTrue(ua.contains("_") || ua.contains("OS 1"), "OS version not interpolated: $ua")
        }
    }

    @Test
    fun everyOrderCarriesSource() {
        // Not opt-in and not card-specific: a wallet order has to be attributable too. Unlike the
        // User-Agent, `source` is always derivable, so it is unconditional.
        assertTrue("source" in cardOrder().toFields())
    }

    @Test
    fun operationSerializesAsStringVerb() {
        assertEquals("Sale", Operation.SALE.wireValue)
        assertEquals("Authorization", Operation.AUTHORIZATION.wireValue)
    }

    @Test
    fun amountMustBeTwoDecimalString() {
        // Validation fires at toFields() time (NOT the constructor: a throwing
        // K/N constructor would crash the Swift host instead of surfacing a
        // catchable error). Constructing with a bad amount must NOT throw.
        val bad = cardOrder().copyWithAmount("1.0") // no throw here
        val ex = assertFailsWith<HiPayException> { bad.toFields() }
        assertEquals(HiPayErrorCode.VALIDATION, ex.code)
        assertFailsWith<HiPayException> { cardOrder().copyWithAmount("1").toFields() }
        assertFailsWith<HiPayException> { cardOrder().copyWithAmount("1,00").toFields() }
        assertFailsWith<HiPayException> { cardOrder().copyWithAmount("10.00abc").toFields() }
    }

    @Test
    fun cardFieldsOnlyPresentWithToken() {
        val noCard = OrderRequest(
            orderId = "O1", paymentProduct = "visa", amount = "2.00",
            description = "d",
            acceptUrl = "a://x", declineUrl = "a://x", pendingUrl = "a://x",
            exceptionUrl = "a://x", cancelUrl = "a://x",
        ).toFields()
        assertFalse("cardtoken" in noCard)
        assertFalse("eci" in noCard)
        assertFalse("authentication_indicator" in noCard)

        val withCard = cardOrder().toFields()
        assertEquals("7", withCard["eci"])
        assertEquals("0", withCard["authentication_indicator"])
    }

    @Test
    fun oneClickEmitsTheFlagOnlyWithAToken() {
        // Wire regression: a non-one-click order must serialize exactly as today.
        assertFalse("one_click" in cardOrder().toFields())

        val fields = cardOrder(oneClick = true).toFields()
        assertEquals("1", fields["one_click"])
        // A saved-card payment is a customer-initiated e-commerce transaction:
        // ECI stays 7 (9 is recurring/merchant-initiated, a different product).
        assertEquals("7", fields["eci"])

        // one_click is a card-payment field: without a token it is never emitted.
        val noToken = OrderRequest(
            orderId = "O1", paymentProduct = "visa", amount = "2.00", description = "d",
            acceptUrl = "a://x", declineUrl = "a://x", pendingUrl = "a://x",
            exceptionUrl = "a://x", cancelUrl = "a://x",
            oneClick = true,
        ).toFields()
        assertFalse("one_click" in noToken)
        assertFalse("cardtoken" in noToken)
    }

    @Test
    fun customerIsFlatMergedAndShippingIsPrefixed() {
        val info = CustomerInfo(
            firstName = "John", lastName = "DOE", email = "j@example.com",
            phone = "0600000000", streetAddress = "1 rue de la Paix",
            city = "Paris", zipCode = "75002", country = "FR",
        )
        val fields = cardOrder(customer = info, shipping = info).toFields()
        assertEquals("John", fields["firstname"])
        assertEquals("j@example.com", fields["email"])
        assertEquals("1 rue de la Paix", fields["streetaddress"])
        assertEquals("John", fields["shipto_firstname"])
        assertEquals("75002", fields["shipto_zipcode"])
        assertFalse("shipto_email" in fields || "shipto_phone" in fields) // shipping = personal info only
    }

    @Test
    fun absentCustomerAndShippingEmitNoKeys() {
        // Story 6.1: customer/shipping are optional — omitted → not sent.
        val fields = cardOrder(customer = null, shipping = null).toFields()
        assertFalse("firstname" in fields)
        assertFalse("email" in fields)
        assertFalse(fields.keys.any { it.startsWith("shipto_") })
    }

    @Test
    fun shippingOnlyOmitsCustomerFlatKeys() {
        // A shipping-only order must not leak flat customer keys.
        val ship = CustomerInfo(firstName = "Jane", city = "Lyon", country = "FR")
        val fields = cardOrder(customer = null, shipping = ship).toFields()
        assertEquals("Jane", fields["shipto_firstname"])
        assertEquals("FR", fields["shipto_country"])
        assertFalse("firstname" in fields) // no flat customer keys
    }

    @Test
    fun customDataSerializesAsJsonObjectString() {
        val fields = cardOrder(customData = mapOf("basket" to "B42")).toFields()
        assertEquals("""{"basket":"B42"}""", fields["custom_data"])
        assertFalse("custom_data" in cardOrder().toFields())
    }

    @Test
    fun optionalCidAndIpAddrAppearOnlyWhenSet() {
        val fields = OrderRequest(
            orderId = "O1", paymentProduct = "visa", amount = "2.00", description = "d",
            acceptUrl = "a://x", declineUrl = "a://x", pendingUrl = "a://x",
            exceptionUrl = "a://x", cancelUrl = "a://x",
            customerId = "CID-1", ipAddress = "1.2.3.4",
        ).toFields()
        assertEquals("CID-1", fields["cid"])
        assertEquals("1.2.3.4", fields["ipaddr"])
        assertTrue("cid" !in cardOrder().toFields())
    }
}

// Test helper: rebuild with a different amount to exercise validation.
private fun OrderRequest.copyWithAmount(amount: String) = OrderRequest(
    orderId = "TEST-ORDER-1", paymentProduct = "visa", amount = amount,
    description = "Test order",
    acceptUrl = "a://x", declineUrl = "a://x", pendingUrl = "a://x",
    exceptionUrl = "a://x", cancelUrl = "a://x",
)
