// PCI: com.hipay.card path — never log here.
@file:OptIn(ExperimentalForeignApi::class)

package com.hipay.card.applepay

import com.hipay.card.validation.CardNetwork
import kotlinx.cinterop.ExperimentalForeignApi
import platform.PassKit.PKPaymentAuthorizationController
import platform.PassKit.PKPaymentNetwork
import platform.PassKit.PKPaymentNetworkAmex
import platform.PassKit.PKPaymentNetworkCartesBancaires
import platform.PassKit.PKPaymentNetworkMaestro
import platform.PassKit.PKPaymentNetworkMasterCard
import platform.PassKit.PKPaymentNetworkVisa

/** The default PassKit-backed device capability used on iOS (both delivery channels). */
public fun defaultApplePayDeviceCapability(): ApplePayDeviceCapability = PassKitDeviceCapability

private object PassKitDeviceCapability : ApplePayDeviceCapability {
    override fun canMakePayments(): Boolean =
        PKPaymentAuthorizationController.canMakePayments()

    override fun canMakePayments(networks: List<CardNetwork>): Boolean {
        val pk = networks.mapNotNull { it.toPkPaymentNetwork() }
        if (pk.isEmpty()) return false
        return PKPaymentAuthorizationController.canMakePaymentsUsingNetworks(pk)
    }
}

/** Maps a [CardNetwork] to its `PKPaymentNetwork`, or `null` when Apple Pay carries no equivalent
 *  (Bancontact, unknown). Also drives the payment sheet's `PKPaymentRequest.supportedNetworks`. */
internal fun CardNetwork.toPkPaymentNetwork(): PKPaymentNetwork? = when (this) {
    CardNetwork.VISA -> PKPaymentNetworkVisa
    CardNetwork.MASTERCARD -> PKPaymentNetworkMasterCard
    CardNetwork.MAESTRO -> PKPaymentNetworkMaestro
    CardNetwork.CB -> PKPaymentNetworkCartesBancaires
    CardNetwork.AMEX -> PKPaymentNetworkAmex
    CardNetwork.BCMC, CardNetwork.UNKNOWN -> null
}
