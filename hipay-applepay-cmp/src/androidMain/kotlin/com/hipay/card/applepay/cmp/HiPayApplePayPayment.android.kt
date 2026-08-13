package com.hipay.card.applepay.cmp

import com.hipay.card.applepay.ApplePayEligibilityResult
import com.hipay.card.applepay.ApplePayEligibilityReason
import com.hipay.card.applepay.ApplePayEligibilityState
import com.hipay.card.applepay.ApplePayOrder
import com.hipay.card.applepay.ApplePayPaymentResult
import com.hipay.card.applepay.HiPayApplePayConfig
import com.hipay.card.validation.CardNetwork
import com.hipay.core.HiPayConfig

/** Apple Pay does not exist on Android: fail loudly rather than appear to work. */
public actual suspend fun runHiPayApplePayPayment(
    config: HiPayConfig,
    applePayConfig: HiPayApplePayConfig,
    order: ApplePayOrder,
    customerCountry: String?,
): ApplePayPaymentResult =
    // Not a HiPayException: its constructor is internal to the core module. Hosts are expected to gate
    // on `hiPayApplePaySupported()`, so reaching this is a programming error, not a payment outcome.
    throw UnsupportedOperationException("Apple Pay is available on iOS only")

/**
 * Always unavailable — no account query is made.
 *
 * The reason is the least wrong of those available: Apple Pay does not exist on this platform, so there
 * is no PassKit answer to report. There is no `PLATFORM_UNSUPPORTED` reason, which is why a host must
 * gate on [hiPayApplePaySupported] rather than interpret this reason as a statement about the device.
 */
public actual suspend fun resolveHiPayApplePayAvailability(
    config: HiPayConfig,
    currency: String,
    customerCountry: String?,
    allowedNetworks: List<CardNetwork>,
): ApplePayEligibilityResult =
    ApplePayEligibilityResult(
        state = ApplePayEligibilityState.UNAVAILABLE,
        resolvedNetworks = emptyList(),
        reason = ApplePayEligibilityReason.DEVICE_NO_USABLE_CARD,
    )

public actual fun hiPayApplePaySupported(): Boolean = false
