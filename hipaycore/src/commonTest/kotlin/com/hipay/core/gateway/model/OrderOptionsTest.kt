package com.hipay.core.gateway.model

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderOptionsTest {

    private fun order(customData: Map<String, String> = emptyMap()) = OrderRequest(
        customData = customData,
        orderId = "TEST-ORDER-1",
        paymentProduct = "visa",
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
    )

    /** Rejection surfaces at build(), never at the setter: an exception out of a non-`@Throws`
     *  function terminates a Swift host instead of being catchable. */
    private fun validation(block: () -> Unit): HiPayException {
        val e = assertFailsWith<HiPayException>(block = block)
        assertEquals(HiPayErrorCode.VALIDATION, e.code)
        return e
    }

    @Test
    fun anOrderWithoutOptionsEmitsNothingExtra() {
        val baseline = order().toFields()
        assertFalse("notify_url" in baseline)
        assertEquals(baseline, order().toFields()) // the new field changes nothing when unused
    }

    @Test
    fun blessedOptionsReachTheWire() {
        val fields = order().withOptions(
            OrderOptions.Builder()
                .notifyUrl("https://backend.example/hipay/notify")
                .softDescriptor("MY SHOP")
                .longDescription("A longer description of the order")
                .basket("""[{"product_reference":"A"}]""")
                .build(),
        ).toFields()

        assertEquals("https://backend.example/hipay/notify", fields["notify_url"])
        assertEquals("MY SHOP", fields["soft_descriptor"])
        assertEquals("A longer description of the order", fields["long_description"])
        assertTrue(fields["basket"]!!.contains("product_reference"))
    }

    @Test
    fun customCarriesAnyOtherGatewayParameter() {
        val fields = order()
            .withOptions(OrderOptions.Builder().custom("website_id", "STWAK4897048").build())
            .toFields()
        assertEquals("STWAK4897048", fields["website_id"])
    }

    /** The order schema declares `custom_data` as a JSON string, not an object. */
    @Test
    fun customDataReachesTheWireAsOneJsonString() {
        val fields = order().withOptions(
            OrderOptions.Builder()
                .customData("internal_reference", "ORD-987465")
                .customData("shipping_method", "express")
                .build(),
        ).toFields()

        assertEquals(
            """{"internal_reference":"ORD-987465","shipping_method":"express"}""",
            fields["custom_data"],
        )
    }

    @Test
    fun customDataRefusesBlankNamesAndValues() {
        validation { OrderOptions.Builder().customData(" ", "value").build() }
        validation { OrderOptions.Builder().customData("internal_reference", " ").build() }
    }

    /** One way in: `custom_data` stays reserved for [OrderOptions.Builder.custom], so the two cannot
     *  each write the field and silently drop one another. */
    @Test
    fun customCannotWriteCustomDataItself() {
        validation { OrderOptions.Builder().custom("custom_data", """{"a":"b"}""").build() }
    }

    /** Freezes the documented precedence: the order's own typed value wins and the option is dropped.
     *  No card controller sets it today, so this cannot bite yet — the test is what keeps it visible. */
    @Test
    fun anOrderThatAlreadyCarriesCustomDataKeepsItsOwn() {
        val fields = order(customData = mapOf("from" to "order"))
            .withOptions(OrderOptions.Builder().customData("from", "options").build())
            .toFields()
        assertEquals("""{"from":"order"}""", fields["custom_data"])
    }

    @Test
    fun notifyUrlMustBeAnHttpEndpoint() {
        // HiPay's servers call it, so an app scheme could never be reached.
        validation { OrderOptions.Builder().notifyUrl("myapp://notify").build() }
        validation { OrderOptions.Builder().notifyUrl("backend.example/notify").build() }
        OrderOptions.Builder().notifyUrl("http://localhost:8080/notify").build()   // accepted
        OrderOptions.Builder().notifyUrl("https://backend.example/notify").build() // accepted
    }

    @Test
    fun customRefusesTheFieldsTheSdkOwns() {
        // The signature covers these three: overriding them would desynchronize it.
        for (signed in listOf("orderid", "amount", "currency")) {
            validation { OrderOptions.Builder().custom(signed, "tampered").build() }
        }
        // PCI-controlled and payment-path fields.
        for (owned in listOf("cardtoken", "eci", "one_click", "authentication_indicator")) {
            validation { OrderOptions.Builder().custom(owned, "1").build() }
        }
        // Return URLs: CallbackUrlParser has to match these again on the way back.
        for (url in listOf("accept_url", "decline_url", "pending_url", "exception_url", "cancel_url")) {
            validation { OrderOptions.Builder().custom(url, "https://evil.example/").build() }
        }
        // Typed parameters already on OrderRequest.
        for (typed in listOf("cid", "ipaddr", "custom_data", "email", "firstname", "zipcode")) {
            validation { OrderOptions.Builder().custom(typed, "x").build() }
        }
    }

    @Test
    fun customRefusesTheWholeShippingBlock() {
        // Prefix-guarded rather than enumerated, so a new shipto_ field is covered without an edit.
        for (key in listOf("shipto_city", "shipto_country", "shipto_streetaddress2", "shipto_anything")) {
            validation { OrderOptions.Builder().custom(key, "x").build() }
        }
    }

    @Test
    fun reservedFieldsCoverEverythingAnOrderEmits() {
        // The guard is only as good as this list: every key a fully-populated order sends must be
        // refused by custom(), or an integrator could set it twice with two different values.
        val emitted = OrderRequest(
            orderId = "O", paymentProduct = "visa", amount = "1.00", currency = "EUR",
            description = "d", language = "fr_FR",
            acceptUrl = "s://a", declineUrl = "s://d", pendingUrl = "s://p",
            exceptionUrl = "s://e", cancelUrl = "s://c",
            cardToken = "t", oneClick = true, customerId = "cid", ipAddress = "1.2.3.4",
            customData = mapOf("k" to "v"),
            customer = CustomerInfo(email = "a@b.c", country = "FR"),
            shippingAddress = CustomerInfo(email = "a@b.c", country = "FR"),
        ).toFields().keys

        val unguarded = emitted.filter {
            it !in OrderOptions.RESERVED_FIELDS && !it.startsWith("shipto_")
        }
        assertEquals(emptyList(), unguarded, "unguarded SDK-owned fields: $unguarded")
    }

    @Test
    fun anOptionNeverOverwritesAFieldTheOrderAlreadySet() {
        // Second lock: even if a key slipped past the guard, the SDK's own value has to win.
        val options = OrderOptions.Builder().custom("website_id", "keep-me").build()
        val fields = order().withOptions(options).toFields()
        assertEquals("visa", fields["payment_product"])
        assertEquals("1.00", fields["amount"])
        assertEquals("keep-me", fields["website_id"])
    }

    @Test
    fun blankValuesAndNamesAreRefused() {
        validation { OrderOptions.Builder().softDescriptor("  ").build() }
        validation { OrderOptions.Builder().custom("  ", "v").build() }
        validation { OrderOptions.Builder().custom("website_id", "").build() }
    }

    @Test
    fun theLastValueForAKeyWins() {
        val fields = order().withOptions(
            OrderOptions.Builder()
                .softDescriptor("FIRST")
                .softDescriptor("SECOND")
                .build(),
        ).toFields()
        assertEquals("SECOND", fields["soft_descriptor"])
    }

    @Test
    fun withOptionsReplacesThePreviousSet() {
        val order = order()
        order.withOptions(OrderOptions.Builder().softDescriptor("FIRST").build())
        val fields = order.withOptions(OrderOptions.Builder().notifyUrl("https://x.example/n").build())
            .toFields()
        assertFalse("soft_descriptor" in fields)
        assertEquals("https://x.example/n", fields["notify_url"])
    }
}
