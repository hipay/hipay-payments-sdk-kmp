// PCI: com.hipay.card path — never log here.
package com.hipay.card.applepay

import com.hipay.card.validation.CardNetwork
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Apple Pay configuration, supplied by the merchant alongside the card [com.hipay.core.HiPayConfig].
 * Kept separate from `HiPayConfig` (which stays card-focused): a merchant not using Apple Pay
 * provides none of this.
 *
 * @property merchantIdentifier the Apple Pay merchant id (from the app's Apple Pay entitlement).
 * @property privateKeyPassword the `.p12` merchant-certificate password (`private_key_pass`) — the
 *   app uses a MERCHANT certificate, so this is mandatory with no fallback (the web-only
 *   HiPay-managed cert defaults do not apply here). Injected by the merchant at runtime, never in
 *   the binary, never logged.
 * @property merchantDisplayName the merchant's brand name shown on the Apple Pay sheet's total line
 *   (Apple convention: the grand-total summary item's label). Must be the merchant's store, not
 *   HiPay. Mandatory.
 * @property applePayUsername an optional dedicated Apple Pay account username; when present BOTH the
 *   wallet tokenize and the order route through it (fixing the legacy asymmetry), else the classic
 *   account is used. Blank is treated as absent.
 * @property allowedNetworks an optional merchant restriction on the routable networks, applied to the
 *   sheet at payment time; empty accepts every routable network the account offers.
 */
public class HiPayApplePayConfig(
    public val merchantIdentifier: String,
    public val privateKeyPassword: String,
    public val merchantDisplayName: String,
    public val applePayUsername: String? = null,
    public val allowedNetworks: List<CardNetwork> = emptyList(),
) {
    // No validation in the constructor: a throwing constructor is not catchable across the
    // Kotlin/Native boundary and would crash the Swift host (same rule as OrderRequest). Validation
    // runs in [ensureValid], which the payment entry point calls before it can open a sheet, so a
    // missing field always surfaces as a catchable error.

    // Never expose the .p12 password.
    override fun toString(): String =
        "HiPayApplePayConfig(merchantIdentifier=$merchantIdentifier, merchantDisplayName=$merchantDisplayName, " +
            "privateKeyPassword=***, applePayUsername=$applePayUsername, allowedNetworks=$allowedNetworks)"
}

/**
 * Validates the mandatory Apple Pay fields. Throws [HiPayException] with
 * [HiPayErrorCode.VALIDATION] naming the first missing/blank field. Hosts can call this at component
 * init to fail early; the payment entry point calls it too, so no sheet can open unvalidated.
 */
@Throws(HiPayException::class, CancellationException::class)
public fun HiPayApplePayConfig.ensureValid() {
    fun require(value: String, field: String) {
        if (value.isBlank()) {
            throw HiPayException(
                code = HiPayErrorCode.VALIDATION,
                message = "Apple Pay configuration: $field is required",
            )
        }
    }
    require(merchantIdentifier, "merchantIdentifier")
    require(privateKeyPassword, "privateKeyPassword")
    require(merchantDisplayName, "merchantDisplayName")
}

/**
 * The networks actually offered in the sheet: the account's routable set narrowed by the merchant
 * restriction when one is configured. Applied by the payment entry point, so the restriction holds
 * whatever list the host passes in — the config never disagrees with what the sheet shows.
 */
internal fun HiPayApplePayConfig.selectableNetworks(resolvedNetworks: List<CardNetwork>): List<CardNetwork> =
    if (allowedNetworks.isEmpty()) resolvedNetworks else resolvedNetworks.filter { it in allowedNetworks }
