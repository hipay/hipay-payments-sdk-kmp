package com.hipay.core.gateway.model

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.golden.GOLDEN_ORDER_REQUEST
import com.hipay.golden.assertJsonParity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderRequestParityTest {

    private fun cardOrder(
        customer: CustomerInfo? = null,
        shipping: CustomerInfo? = null,
        customData: Map<String, String> = emptyMap(),
    ) = OrderRequest(
        orderId = "TEST-ORDER-1",
        paymentProduct = "visa",
        operation = Operation.SALE,
        amount = "1.00",
        currency = "EUR",
        description = "Test order",
        language = "fr_FR",
        acceptUrl = "hipaydemo://hipay-fullservice/gateway/orders/TEST-ORDER-1/accept",
        declineUrl = "hipaydemo://hipay-fullservice/gateway/orders/TEST-ORDER-1/decline",
        pendingUrl = "hipaydemo://hipay-fullservice/gateway/orders/TEST-ORDER-1/pending",
        exceptionUrl = "hipaydemo://hipay-fullservice/gateway/orders/TEST-ORDER-1/exception",
        cancelUrl = "hipaydemo://hipay-fullservice/gateway/orders/TEST-ORDER-1/cancel",
        cardToken = "f0e1d2c3b4a5968778695a4b3c2d1e0f".repeat(2),
        customer = customer,
        shippingAddress = shipping,
        customData = customData,
    )

    @Test
    fun cardOrderFieldsMatchTheGoldenRequest() {
        val actual = JsonObject(cardOrder().toFields().mapValues { JsonPrimitive(it.value) })
        assertJsonParity(GOLDEN_ORDER_REQUEST, actual)
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
