package com.hipay.card.applepay

import com.hipay.card.validation.CardNetwork
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplePayConfigAndSheetTest {

    private fun config(
        merchantIdentifier: String = "merchant.x",
        privateKeyPassword: String = "p12pass",
        merchantDisplayName: String = "MyShop",
        allowedNetworks: List<CardNetwork> = emptyList(),
    ) = HiPayApplePayConfig(
        merchantIdentifier = merchantIdentifier,
        privateKeyPassword = privateKeyPassword,
        merchantDisplayName = merchantDisplayName,
        allowedNetworks = allowedNetworks,
    )

    private fun sheet(
        config: HiPayApplePayConfig = config(),
        resolvedNetworks: List<CardNetwork> = listOf(CardNetwork.VISA),
        amount: String = "12.00",
        currencyCode: String = "EUR",
        countryCode: String = "FR",
    ) = applePaySheetRequest(config, resolvedNetworks, amount, currencyCode, countryCode)

    @Test
    fun validConfigPasses() {
        config().ensureValid() // does not throw
    }

    // A missing merchant/store name fails with an explicit VALIDATION error naming the field.
    @Test
    fun missingMerchantDisplayNameFailsValidation() {
        val e = assertFailsWith<HiPayException> { config(merchantDisplayName = "").ensureValid() }
        assertEquals(HiPayErrorCode.VALIDATION, e.code)
        assertTrue(e.message!!.contains("merchantDisplayName"))
    }

    @Test
    fun missingMerchantIdentifierFailsValidation() {
        val e = assertFailsWith<HiPayException> { config(merchantIdentifier = "").ensureValid() }
        assertEquals(HiPayErrorCode.VALIDATION, e.code)
    }

    @Test
    fun missingPrivateKeyPasswordFailsValidation() {
        val e = assertFailsWith<HiPayException> { config(privateKeyPassword = " ").ensureValid() }
        assertEquals(HiPayErrorCode.VALIDATION, e.code)
    }

    // The sheet request carries the store-name label and exactly the resolved networks as the
    // selectable set; ISO codes reach PassKit uppercased.
    @Test
    fun sheetRequestCarriesStoreNameAndResolvedNetworks() {
        val resolved = listOf(CardNetwork.VISA, CardNetwork.CB)
        val request = sheet(resolvedNetworks = resolved, currencyCode = "eur", countryCode = "fr")
        assertEquals("MyShop", request.merchantDisplayName)
        assertEquals(resolved, request.supportedNetworks)
        assertEquals("merchant.x", request.merchantIdentifier)
        assertEquals("12.00", request.amount)
        assertEquals("EUR", request.currencyCode)
        assertEquals("FR", request.countryCode)
    }

    // Building the sheet request also fails when a mandatory config field is missing.
    @Test
    fun sheetRequestFailsWhenNameMissing() {
        assertFailsWith<HiPayException> { sheet(config = config(merchantDisplayName = "")) }
    }

    // The wallet token is single-use: an amount the order would reject must fail BEFORE the sheet
    // opens, not after the customer has authorized.
    @Test
    fun malformedAmountFailsBeforeTheSheetOpens() {
        for (amount in listOf("", "12", "12,00", "9.999", "abc")) {
            val e = assertFailsWith<HiPayException>(message = "accepted amount \"$amount\"") {
                sheet(amount = amount)
            }
            assertEquals(HiPayErrorCode.VALIDATION, e.code)
        }
    }

    @Test
    fun malformedIsoCodesFailBeforeTheSheetOpens() {
        val currency = assertFailsWith<HiPayException> { sheet(currencyCode = "") }
        assertEquals(HiPayErrorCode.VALIDATION, currency.code)
        assertTrue(currency.message!!.contains("currency"))

        val country = assertFailsWith<HiPayException> { sheet(countryCode = "FRA") }
        assertEquals(HiPayErrorCode.VALIDATION, country.code)
        assertTrue(country.message!!.contains("country"))
    }

    // No selectable network → a sheet nobody can pay with. Fail with a named cause instead.
    @Test
    fun noSelectableNetworkFails() {
        val e = assertFailsWith<HiPayException> { sheet(resolvedNetworks = emptyList()) }
        assertEquals(HiPayErrorCode.VALIDATION, e.code)
    }

    // The merchant restriction narrows the account's routable set, and never widens it.
    @Test
    fun merchantRestrictionNarrowsTheSelectableNetworks() {
        val routable = listOf(CardNetwork.VISA, CardNetwork.MASTERCARD, CardNetwork.CB)
        assertEquals(
            listOf(CardNetwork.VISA),
            config(allowedNetworks = listOf(CardNetwork.VISA)).selectableNetworks(routable),
        )
        // A network the account does not route stays out, even if the merchant allows it.
        assertEquals(
            listOf(CardNetwork.VISA),
            config(allowedNetworks = listOf(CardNetwork.VISA, CardNetwork.AMEX)).selectableNetworks(routable),
        )
        // No restriction configured → everything routable stays selectable.
        assertEquals(routable, config().selectableNetworks(routable))
    }

    // A second tap while a payment is in flight is refused until the flow releases the slot.
    @Test
    fun inFlightGuardBlocksSecondTap() {
        val guard = PaymentInFlightGuard()
        assertTrue(guard.tryBegin())   // first tap acquires
        assertFalse(guard.tryBegin())  // second tap ignored while in flight
        guard.end()
        assertTrue(guard.tryBegin())   // released → acquires again
    }

    // The .p12 password must never appear in a string representation of the config.
    @Test
    fun configNeverPrintsThePrivateKeyPassword() {
        val text = config(privateKeyPassword = "s3cr3t").toString()
        assertFalse(text.contains("s3cr3t"))
    }

    private fun order(orderId: String = "AP-1", redirectScheme: String = "hipaydemo") = ApplePayOrder(
        orderId = orderId,
        amount = "12.00",
        currency = "EUR",
        countryCode = "FR",
        description = "d",
        redirectScheme = redirectScheme,
    )

    @Test
    fun validOrderPasses() {
        order().ensureValid() // does not throw
    }

    // A blank order id would be rejected by the gateway only after the single-use token was spent.
    @Test
    fun blankOrderIdIsRejectedBeforeTheSheetOpens() {
        val e = assertFailsWith<HiPayException> { order(orderId = " ").ensureValid() }
        assertEquals(HiPayErrorCode.VALIDATION, e.code)
        assertTrue(e.message!!.contains("orderId"))
    }

    // The order id becomes a path segment of the redirect URLs: a separator or a space would build URLs
    // the gateway rejects and a return the callback parser cannot read back — again only discoverable
    // once the single-use token is gone.
    @Test
    fun orderIdBreakingTheRedirectUrlIsRejectedBeforeTheSheetOpens() {
        listOf("AP 1", "cart/42", "AP?1", "AP#1", "AP%201", "AP&1", "commandé-1").forEach { id ->
            val e = assertFailsWith<HiPayException>("expected rejection of orderId '$id'") {
                order(orderId = id).ensureValid()
            }
            assertEquals(HiPayErrorCode.VALIDATION, e.code)
            assertTrue(e.message!!.contains("orderId"))
        }
    }

    // The shapes real merchant order ids use must keep working.
    @Test
    fun usualOrderIdShapesAreAccepted() {
        listOf("AP-1", "order_42", "2026.08.03-7", "abcDEF123").forEach { id ->
            order(orderId = id).ensureValid()
        }
    }

    // A malformed scheme builds redirect URLs the gateway rejects and a challenge return that cannot
    // be captured — both only discoverable after the customer has authorized.
    @Test
    fun malformedRedirectSchemeIsRejectedBeforeTheSheetOpens() {
        listOf("", "  ", "1demo", "my scheme", "demo://").forEach { scheme ->
            val e = assertFailsWith<HiPayException>("expected rejection of scheme '$scheme'") {
                order(redirectScheme = scheme).ensureValid()
            }
            assertEquals(HiPayErrorCode.VALIDATION, e.code)
            assertTrue(e.message!!.contains("redirectScheme"))
        }
    }

    // Schemes RFC 3986 allows must keep working.
    @Test
    fun validSchemeShapesAreAccepted() {
        listOf("hipaydemo", "hipay-demo", "hipay.demo", "hipay+demo", "h1").forEach { scheme ->
            order(redirectScheme = scheme).ensureValid()
        }
    }

    // The derived base URL is the one shape the SDK's own callback parser accepts, whitespace-trimmed
    // so it can never disagree with the scheme the challenge is captured on.
    @Test
    fun callbackBaseUrlIsTheParseableShape() {
        assertEquals(
            "hipaydemo://hipay-fullservice/gateway/orders/AP-1",
            order(redirectScheme = " hipaydemo ").callbackBaseUrl,
        )
    }
}
