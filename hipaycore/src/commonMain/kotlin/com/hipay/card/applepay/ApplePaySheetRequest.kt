// PCI: com.hipay.card path — never log here.
package com.hipay.card.applepay

import com.hipay.card.validation.CardNetwork
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.core.gateway.model.requireAmountFormat
import kotlin.coroutines.cancellation.CancellationException

/**
 * The platform-agnostic inputs for the Apple Pay sheet — the per-channel adapter turns this into a
 * `PKPaymentRequest`. Keeping it shared guarantees identical sheet behaviour on both channels.
 *
 * The adapter builds the request as: `merchantIdentifier`, `merchantCapabilities = 3DS`,
 * `supportedNetworks` = [supportedNetworks] mapped to `PKPaymentNetwork`, a SINGLE summary item
 * whose label = [merchantDisplayName] and amount = [amount] — so the sheet's final line reads
 * "merchant store name + total" — and **no** `requiredBilling/ShippingContactFields`.
 *
 * @property supportedNetworks the routable set resolved for the account, already narrowed by any
 *   merchant restriction: only these are selectable in the sheet.
 * @property currencyCode,countryCode uppercased ISO codes, the form PassKit expects (the order keeps
 *   the merchant's own strings).
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
 * Assembles an [ApplePaySheetRequest] from the merchant config + the resolved routable networks.
 *
 * Validates everything the sheet needs BEFORE it can open: the mandatory config fields, the ISO
 * currency/country codes, the amount format, and that at least one network is selectable. The Apple
 * Pay token is single-use, so any input the order would later reject must fail here — once the
 * customer has authorized, the token is spent and they have to start over. A missing merchant name
 * or identifier therefore surfaces as an explicit [HiPayException] instead of a broken sheet.
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
    requireAmountFormat(amount)
    val currency = requireIsoCode(currencyCode, field = "currency", length = 3)
    val country = requireIsoCode(countryCode, field = "country", length = 2)
    if (resolvedNetworks.isEmpty()) {
        throw HiPayException(
            code = HiPayErrorCode.VALIDATION,
            message = "Apple Pay: no card network is selectable for this payment",
        )
    }
    return ApplePaySheetRequest(
        merchantIdentifier = config.merchantIdentifier,
        merchantDisplayName = config.merchantDisplayName,
        supportedNetworks = resolvedNetworks,
        amount = amount,
        currencyCode = currency,
        countryCode = country,
    )
}

/** Returns the uppercased code, or fails naming the field — a blank or malformed code would
 *  otherwise surface as an opaque "sheet could not be presented" from PassKit. */
private fun requireIsoCode(value: String, field: String, length: Int): String {
    val code = value.trim()
    if (code.length != length || !code.all { it.isLetter() }) {
        throw HiPayException(
            code = HiPayErrorCode.VALIDATION,
            message = "Apple Pay: $field must be a $length-letter ISO code",
        )
    }
    return code.uppercase()
}
