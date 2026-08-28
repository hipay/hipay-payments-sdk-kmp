@file:OptIn(ExperimentalForeignApi::class)

package com.hipay.card.applepay

import com.hipay.card.validation.CardNetwork
import com.hipay.core.HiPayException
import kotlinx.cinterop.ExperimentalForeignApi
import platform.PassKit.PKMerchantCapability3DS
import platform.PassKit.PKPaymentNetworkCartesBancaires
import platform.PassKit.PKPaymentNetworkVisa
import platform.PassKit.PKPaymentSummaryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The native `PKPaymentRequest` the sheet is actually built from. Needs no device: constructing the
 * request is pure PassKit data, so the store-name label, the total, the selectable networks and the
 * absence of contact fields can all be pinned in CI.
 */
class ApplePaySheetBuilderTest {

    private fun request(networks: List<CardNetwork> = listOf(CardNetwork.VISA, CardNetwork.CB)) =
        buildPaymentRequest(
            applePaySheetRequest(
                config = HiPayApplePayConfig(
                    merchantIdentifier = "merchant.x",
                    privateKeyPassword = "p12pass",
                    merchantDisplayName = "MyShop",
                ),
                resolvedNetworks = networks,
                amount = "12.34",
                currencyCode = "EUR",
                countryCode = "FR",
            ),
        )

    // The sheet's final line is the merchant's store name + the total: a SINGLE summary item, whose
    // label is the store name (never HiPay) and whose amount is the order amount.
    @Test
    fun theSummaryIsTheStoreNameAndTheTotal() {
        val request = request()
        assertEquals(1, request.paymentSummaryItems.size)
        val total = request.paymentSummaryItems.first() as PKPaymentSummaryItem
        assertEquals("MyShop", total.label)
        assertEquals("12.34", total.amount.stringValue)
    }

    @Test
    fun theRequestIdentifiesTheMerchantAndTheCurrency() {
        val request = request()
        assertEquals("merchant.x", request.merchantIdentifier)
        assertEquals("EUR", request.currencyCode)
        assertEquals("FR", request.countryCode)
        assertEquals(PKMerchantCapability3DS, request.merchantCapabilities)
    }

    // Only the networks passed in are selectable, in order.
    @Test
    fun onlyTheSelectableNetworksAreOffered() {
        assertEquals(
            listOf(PKPaymentNetworkVisa, PKPaymentNetworkCartesBancaires),
            request().supportedNetworks,
        )
        assertEquals(listOf(PKPaymentNetworkVisa), request(listOf(CardNetwork.VISA)).supportedNetworks)
    }

    // No billing or shipping information is ever requested from the customer.
    @Test
    fun noContactDataIsRequested() {
        val request = request()
        assertTrue(request.requiredBillingContactFields.orEmpty().isEmpty())
        assertTrue(request.requiredShippingContactFields.orEmpty().isEmpty())
    }

    // A set Apple Pay cannot carry at all must fail by name, not produce a sheet with no payable card.
    @Test
    fun aSetApplePayCannotCarryIsRejected() {
        assertFailsWith<HiPayException> { request(listOf(CardNetwork.BCMC)) }
    }
}
