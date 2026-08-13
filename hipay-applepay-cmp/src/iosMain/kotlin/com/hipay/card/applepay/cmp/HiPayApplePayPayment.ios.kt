package com.hipay.card.applepay.cmp

import com.hipay.card.applepay.ApplePayEligibilityResult
import com.hipay.card.applepay.ApplePayOrder
import com.hipay.card.applepay.ApplePayPaymentResult
import com.hipay.card.applepay.HiPayApplePayConfig
import com.hipay.card.applepay.defaultApplePayDeviceCapability
import com.hipay.card.applepay.resolveApplePayEligibility
import com.hipay.card.applepay.runApplePayPayment
import com.hipay.core.HiPayConfig

/** Delegates to the shared iOS implementation — the same entry point the Swift facade calls. */
public actual suspend fun runHiPayApplePayPayment(
    config: HiPayConfig,
    applePayConfig: HiPayApplePayConfig,
    order: ApplePayOrder,
): ApplePayPaymentResult {
    // The routable set is resolved here rather than taken from the host, so the sheet can never offer
    // a network the account stopped accepting since the button was drawn.
    val eligibility = resolveHiPayApplePayAvailability(config, order.currency)
    return runApplePayPayment(
        config = config,
        applePayConfig = applePayConfig,
        resolvedNetworks = eligibility.resolvedNetworks,
        order = order,
    )
}

public actual suspend fun resolveHiPayApplePayAvailability(
    config: HiPayConfig,
    currency: String,
    customerCountry: String?,
): ApplePayEligibilityResult =
    resolveApplePayEligibility(
        config = config,
        device = defaultApplePayDeviceCapability(),
        currency = currency,
        customerCountry = customerCountry,
    )

public actual fun hiPayApplePaySupported(): Boolean = true
