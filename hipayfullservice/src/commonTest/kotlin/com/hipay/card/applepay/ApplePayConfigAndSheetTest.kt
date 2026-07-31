package com.hipay.card.applepay

import com.hipay.card.validation.CardNetwork
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApplePayConfigAndSheetTest {

    private fun config(
        merchantIdentifier: String = "merchant.x",
        privateKeyPassword: String = "p12pass",
        merchantDisplayName: String = "MyShop",
    ) = HiPayApplePayConfig(merchantIdentifier, privateKeyPassword, merchantDisplayName)

    @Test
    fun validConfigPasses() {
        config().ensureValid() // does not throw
    }

    // AC2 — a missing merchant/store name fails init with an explicit VALIDATION error.
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

    // AC1 + AC3/AC4 — the sheet request carries the store-name label and exactly the resolved
    // networks as the selectable set.
    @Test
    fun sheetRequestCarriesStoreNameAndResolvedNetworks() {
        val resolved = listOf(CardNetwork.VISA, CardNetwork.CB)
        val request = applePaySheetRequest(
            config = config(merchantDisplayName = "MyShop"),
            resolvedNetworks = resolved,
            amount = "12.00",
            currencyCode = "EUR",
            countryCode = "FR",
        )
        assertEquals("MyShop", request.merchantDisplayName)
        assertEquals(resolved, request.supportedNetworks)
        assertEquals("merchant.x", request.merchantIdentifier)
    }

    // AC2 — building the sheet request also fails when a mandatory field is missing.
    @Test
    fun sheetRequestFailsWhenNameMissing() {
        assertFailsWith<HiPayException> {
            applePaySheetRequest(
                config = config(merchantDisplayName = ""),
                resolvedNetworks = listOf(CardNetwork.VISA),
                amount = "1.00", currencyCode = "EUR", countryCode = "FR",
            )
        }
    }

    // AC8 — the guard blocks a second concurrent begin until end() releases it.
    @Test
    fun inFlightGuardBlocksSecondTap() {
        val guard = PaymentInFlightGuard()
        assertTrue(guard.tryBegin())   // first tap acquires
        assertTrue(!guard.tryBegin())  // second tap ignored while in flight
        guard.end()
        assertTrue(guard.tryBegin())   // released → acquires again
    }
}
