package com.hipay.card.applepay

import com.hipay.card.validation.CardNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import platform.PassKit.PKPaymentNetworkAmex
import platform.PassKit.PKPaymentNetworkCartesBancaires
import platform.PassKit.PKPaymentNetworkMaestro
import platform.PassKit.PKPaymentNetworkMasterCard
import platform.PassKit.PKPaymentNetworkVisa

/** Each card network maps to the correct native `PKPaymentNetwork`; networks Apple Pay does not
 *  carry map to null. Guards the device-capability + payment-request mapping against typos. */
class ApplePayNetworkMappingTest {

    @Test
    fun mapsEachNetworkToPassKit() {
        assertEquals(PKPaymentNetworkVisa, CardNetwork.VISA.toPkPaymentNetwork())
        assertEquals(PKPaymentNetworkMasterCard, CardNetwork.MASTERCARD.toPkPaymentNetwork())
        assertEquals(PKPaymentNetworkMaestro, CardNetwork.MAESTRO.toPkPaymentNetwork())
        assertEquals(PKPaymentNetworkCartesBancaires, CardNetwork.CB.toPkPaymentNetwork())
        assertEquals(PKPaymentNetworkAmex, CardNetwork.AMEX.toPkPaymentNetwork())
    }

    @Test
    fun networksWithoutApplePayEquivalentMapToNull() {
        assertNull(CardNetwork.BCMC.toPkPaymentNetwork())
        assertNull(CardNetwork.UNKNOWN.toPkPaymentNetwork())
    }
}
