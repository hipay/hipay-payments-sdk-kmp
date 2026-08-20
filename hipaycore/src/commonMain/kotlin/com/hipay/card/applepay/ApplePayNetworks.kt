// PCI: com.hipay.card path — never log here.
package com.hipay.card.applepay

import com.hipay.card.validation.CardNetwork

/**
 * Static Apple Pay network maps, mirroring the web hosted-fields SDK (`applepay.const.js` +
 * `payment-request-button.js` `_filterSupportedNetworks`). The web proves the model: a network is
 * routable via Apple Pay only if it is BOTH Apple-carriable AND in HiPay's Apple-Pay-routable set,
 * and it hard-excludes Amex through the HiPay map — it does not rely on the account query to do so.
 * We mirror that: [routable] is the platform-level filter; the account query then narrows to what
 * the merchant actually accepts, and an optional merchant restriction narrows further.
 *
 * [hipaySupported] is a HiPay-platform fact (which networks HiPay routes on Apple Pay), not a
 * per-account one — it changes only if HiPay adds a network to the Apple Pay route (e.g. Amex), a
 * one-line update here.
 */
internal object ApplePayNetworks {

    /** Networks Apple Pay can physically carry, among the networks this SDK models. */
    val appleSupported: Set<CardNetwork> = setOf(
        CardNetwork.VISA,
        CardNetwork.MASTERCARD,
        CardNetwork.AMEX,
        CardNetwork.MAESTRO,
        CardNetwork.CB,
    )

    /** Networks HiPay routes via Apple Pay (the web `HIPAY_SUPPORTED_NETWORKS_MAPPING`). Amex is
     *  absent — HiPay does Amex on classic card but not on the Apple Pay route — which is the
     *  mechanism that makes Amex non-routable via Apple Pay. */
    val hipaySupported: Set<CardNetwork> = setOf(
        CardNetwork.VISA,
        CardNetwork.MASTERCARD,
        CardNetwork.MAESTRO,
        CardNetwork.CB,
    )

    /** Networks routable via the HiPay Apple Pay route = Apple ∩ HiPay (Amex and Bancontact both
     *  excluded). The platform-level filter applied before the account/merchant narrowing. */
    val routable: Set<CardNetwork> = appleSupported intersect hipaySupported

    /** Card product codes queried on `available-payment-products` to learn the account's accepted
     *  cards among the routable set. */
    val cardProductCodes: List<String> = listOf(
        "visa",
        "mastercard",
        "maestro",
        "cb",
    )
}
