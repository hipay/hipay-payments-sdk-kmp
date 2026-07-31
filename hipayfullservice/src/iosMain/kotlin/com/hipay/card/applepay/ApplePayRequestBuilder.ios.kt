// PCI: com.hipay.card path — never log here.
@file:OptIn(ExperimentalForeignApi::class)

package com.hipay.card.applepay

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDecimalNumber
import platform.PassKit.PKMerchantCapability3DS
import platform.PassKit.PKPaymentRequest
import platform.PassKit.PKPaymentSummaryItem

/**
 * Builds the native `PKPaymentRequest` from the shared [ApplePaySheetRequest] (one place, both
 * channels): merchant id, 3DS capability, the resolved routable networks (AC3/AC4, mapped via
 * [toPkPaymentNetwork]), and a single summary item whose label = the merchant store name and amount
 * = the total (AC1). No billing/shipping contact fields are set (AC7).
 */
internal fun buildPaymentRequest(sheet: ApplePaySheetRequest): PKPaymentRequest {
    val request = PKPaymentRequest()
    request.merchantIdentifier = sheet.merchantIdentifier
    request.merchantCapabilities = PKMerchantCapability3DS
    request.countryCode = sheet.countryCode
    request.currencyCode = sheet.currencyCode
    request.supportedNetworks = sheet.supportedNetworks.mapNotNull { it.toPkPaymentNetwork() }
    request.paymentSummaryItems = listOf(
        PKPaymentSummaryItem.summaryItemWithLabel(
            sheet.merchantDisplayName,
            NSDecimalNumber(string = sheet.amount),
        ),
    )
    return request
}
