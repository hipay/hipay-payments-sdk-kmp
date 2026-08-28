package com.hipay.card.applepay.cmp

import com.hipay.card.applepay.ApplePayEligibilityResult
import com.hipay.card.applepay.ApplePayOrder
import com.hipay.card.applepay.ApplePayPaymentResult
import com.hipay.card.applepay.ApplePayEligibilityState
import com.hipay.card.applepay.HiPayApplePayConfig
import com.hipay.card.validation.CardNetwork
import com.hipay.card.applepay.defaultApplePayDeviceCapability
import com.hipay.card.applepay.resolveApplePayEligibility
import com.hipay.card.applepay.runApplePayPayment
import com.hipay.core.HiPayConfig

/** Delegates to the shared iOS implementation — the same entry point the Swift facade calls. */
public actual suspend fun runHiPayApplePayPayment(
    config: HiPayConfig,
    applePayConfig: HiPayApplePayConfig,
    order: ApplePayOrder,
    customerCountry: String?,
): ApplePayPaymentResult {
    // The routable set is resolved here rather than taken from the host, so the sheet can never offer
    // a network the account stopped accepting since the button was drawn. The merchant restriction and
    // the customer country are the same inputs the availability check uses, so the button and the sheet
    // cannot disagree.
    val eligibility = resolveHiPayApplePayAvailability(
        config = config,
        currency = order.currency,
        customerCountry = customerCountry,
        allowedNetworks = applePayConfig.allowedNetworks,
    )
    return runApplePayPayment(
        config = config,
        applePayConfig = applePayConfig,
        // An unavailable result must not open a sheet. An empty set makes the shared implementation
        // raise its own validation error, so both channels fail identically instead of surfacing a
        // PassKit presentation failure that looks like a transport error.
        resolvedNetworks = if (eligibility.state == ApplePayEligibilityState.AVAILABLE) {
            eligibility.resolvedNetworks
        } else {
            emptyList()
        },
        order = order,
    )
}

public actual suspend fun resolveHiPayApplePayAvailability(
    config: HiPayConfig,
    currency: String,
    customerCountry: String?,
    allowedNetworks: List<CardNetwork>,
): ApplePayEligibilityResult =
    resolveApplePayEligibility(
        config = config,
        device = defaultApplePayDeviceCapability(),
        currency = currency,
        customerCountry = customerCountry,
        allowedNetworks = allowedNetworks,
    )

public actual fun hiPayApplePaySupported(): Boolean = true
