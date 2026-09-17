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
import com.hipay.core.monitoring.HiPayIntegrationSurface
import com.hipay.core.monitoring.HiPayInternalApi

/** Delegates to the shared iOS implementation — the same entry point the Swift facade calls. */
public actual suspend fun runHiPayApplePayPayment(
    config: HiPayConfig,
    applePayConfig: HiPayApplePayConfig,
    order: ApplePayOrder,
    customerCountry: String?,
): ApplePayPaymentResult {
    // A wallet-only host must not be reported as native.
    @OptIn(HiPayInternalApi::class)
    HiPayIntegrationSurface.declareComposeMultiplatform()
    // Resolved here, not taken from the host: the sheet can never offer a network the account has
    // stopped accepting, and it reads the same inputs as the button, so the two cannot disagree.
    val eligibility = resolveHiPayApplePayAvailability(
        config = config,
        currency = order.currency,
        customerCountry = customerCountry,
        allowedNetworks = applePayConfig.allowedNetworks,
    )
    return runApplePayPayment(
        config = config,
        applePayConfig = applePayConfig,
        // An empty set makes the shared implementation raise its own validation error, rather than
        // opening a sheet that fails as a PassKit error looking like a transport one.
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
