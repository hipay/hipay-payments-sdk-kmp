package com.hipay.core.gateway.model

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderOptionsTest {

    private fun order() = OrderRequest(
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
                .cdata(1, "campaign-42")
                .cdata(OrderOptions.CDATA_MAX, "last-slot")
                .basket("""[{"product_reference":"A"}]""")
                .build(),
        ).toFields()

        assertEquals("https://backend.example/hipay/notify", fields["notify_url"])
        assertEquals("MY SHOP", fields["soft_descriptor"])
        assertEquals("A longer description of the order", fields["long_description"])
        assertEquals("campaign-42", fields["cdata1"])
        assertEquals("last-slot", fields["cdata10"])
        assertTrue(fields["basket"]!!.contains("product_reference"))
    }

    @Test
    fun customCarriesAnyOtherGatewayParameter() {
        val fields = order()
            .withOptions(OrderOptions.Builder().custom("website_id", "STWAK4897048").build())
            .toFields()
        assertEquals("STWAK4897048", fields["website_id"])
    }

    @Test
    fun notifyUrlMustBeAnHttpEndpoint() {
        // HiPay's servers call it, so an app scheme could never be reached.
        validation { OrderOptions.Builder().notifyUrl("myapp://notify") }
        validation { OrderOptions.Builder().notifyUrl("backend.example/notify") }
        OrderOptions.Builder().notifyUrl("http://localhost:8080/notify")   // accepted
        OrderOptions.Builder().notifyUrl("https://backend.example/notify") // accepted
    }

    @Test
    fun customRefusesTheFieldsTheSdkOwns() {
        // The signature covers these three: overriding them would desynchronize it.
        for (signed in listOf("orderid", "amount", "currency")) {
            validation { OrderOptions.Builder().custom(signed, "tampered") }
        }
        // PCI-controlled and payment-path fields.
        for (owned in listOf("cardtoken", "eci", "one_click", "authentication_indicator")) {
            validation { OrderOptions.Builder().custom(owned, "1") }
        }
        // Return URLs: CallbackUrlParser has to match these again on the way back.
        for (url in listOf("accept_url", "decline_url", "pending_url", "exception_url", "cancel_url")) {
            validation { OrderOptions.Builder().custom(url, "https://evil.example/") }
        }
        // Typed parameters already on OrderRequest.
        for (typed in listOf("cid", "ipaddr", "custom_data", "email", "firstname", "zipcode")) {
            validation { OrderOptions.Builder().custom(typed, "x") }
        }
    }

    @Test
    fun customRefusesTheWholeShippingBlock() {
        // Prefix-guarded rather than enumerated, so a new shipto_ field is covered without an edit.
        for (key in listOf("shipto_city", "shipto_country", "shipto_streetaddress2", "shipto_anything")) {
            validation { OrderOptions.Builder().custom(key, "x") }
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
    fun cdataIndexIsBounded() {
        validation { OrderOptions.Builder().cdata(0, "x") }
        validation { OrderOptions.Builder().cdata(OrderOptions.CDATA_MAX + 1, "x") }
    }

    @Test
    fun blankValuesAndNamesAreRefused() {
        validation { OrderOptions.Builder().softDescriptor("  ") }
        validation { OrderOptions.Builder().custom("  ", "v") }
        validation { OrderOptions.Builder().custom("website_id", "") }
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
