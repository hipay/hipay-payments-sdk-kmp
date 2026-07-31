// PCI: com.hipay.card path — never log here.
package com.hipay.card.applepay

import com.hipay.card.validation.CardNetwork
import com.hipay.core.HiPayException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The platform-agnostic inputs for the Apple Pay sheet — the per-channel adapter turns this into a
 * `PKPaymentRequest`. Keeping it shared guarantees identical sheet behaviour on both channels.
 *
 * The adapter builds the request as: `merchantIdentifier`, `merchantCapabilities = 3DS`,
 * `supportedNetworks` = [supportedNetworks] mapped to `PKPaymentNetwork`, a SINGLE summary item
 * whose label = [merchantDisplayName] and amount = [amount] (the sheet's final line = store name +
 * total, AC1), and **no** `requiredBilling/ShippingContactFields` (AC7).
 *
 * @property supportedNetworks the 17.2 resolved routable set (already narrowed by any merchant
 *   restriction) — only these are selectable in the sheet (AC3/AC4).
 */
public class ApplePaySheetRequest(
    public val merchantIdentifier: String,
    public val merchantDisplayName: String,
    public val supportedNetworks: List<CardNetwork>,
    public val amount: String,
    public val currencyCode: String,
    public val countryCode: String,
)

/**
 * Assembles an [ApplePaySheetRequest] from the merchant config + the 17.2 resolved networks.
 * Validates the config first (AC2) — a missing merchant name / identifier fails here with an
 * explicit [HiPayException] rather than opening a broken sheet.
 */
@Throws(HiPayException::class, CancellationException::class)
public fun applePaySheetRequest(
    config: HiPayApplePayConfig,
    resolvedNetworks: List<CardNetwork>,
    amount: String,
    currencyCode: String,
    countryCode: String,
): ApplePaySheetRequest {
    config.ensureValid()
    return ApplePaySheetRequest(
        merchantIdentifier = config.merchantIdentifier,
        merchantDisplayName = config.merchantDisplayName,
        supportedNetworks = resolvedNetworks,
        amount = amount,
        currencyCode = currencyCode,
        countryCode = countryCode,
    )
}
