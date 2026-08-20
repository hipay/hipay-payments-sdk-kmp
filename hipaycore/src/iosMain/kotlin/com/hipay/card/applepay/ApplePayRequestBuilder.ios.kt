// PCI: com.hipay.card path — never log here.
@file:OptIn(ExperimentalForeignApi::class)

package com.hipay.card.applepay

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDecimalNumber
import platform.PassKit.PKMerchantCapability3DS
import platform.PassKit.PKPaymentRequest
import platform.PassKit.PKPaymentSummaryItem

/**
 * Builds the native `PKPaymentRequest` from the shared [ApplePaySheetRequest] (one place, both
 * channels): merchant id, 3DS capability, the selectable routable networks mapped via
 * [toPkPaymentNetwork], and a single summary item whose label = the merchant store name and amount =
 * the total. No billing/shipping contact fields are set, so the customer is asked for none.
 *
 * The amount format and the ISO codes are already validated by [applePaySheetRequest], so
 * `NSDecimalNumber` here can never end up as `notANumber` (which PassKit rejects with an
 * Objective-C exception Kotlin cannot catch).
 */
internal fun buildPaymentRequest(sheet: ApplePaySheetRequest): PKPaymentRequest {
    // Networks Apple Pay does not carry (e.g. Bancontact) drop out here. If nothing survives, the
    // sheet would be presentable with no selectable card and fail opaquely — name the cause instead.
    val networks = sheet.supportedNetworks.mapNotNull { it.toPkPaymentNetwork() }
    if (networks.isEmpty()) {
        throw HiPayException(
            code = HiPayErrorCode.VALIDATION,
            message = "Apple Pay: none of the selectable networks is supported by Apple Pay",
        )
    }
    val request = PKPaymentRequest()
    request.merchantIdentifier = sheet.merchantIdentifier
    request.merchantCapabilities = PKMerchantCapability3DS
    request.countryCode = sheet.countryCode
    request.currencyCode = sheet.currencyCode
    request.supportedNetworks = networks
    request.paymentSummaryItems = listOf(
        PKPaymentSummaryItem.summaryItemWithLabel(
            sheet.merchantDisplayName,
            NSDecimalNumber(string = sheet.amount),
        ),
    )
    return request
}
