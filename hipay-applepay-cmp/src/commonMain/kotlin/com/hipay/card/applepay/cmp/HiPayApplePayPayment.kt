package com.hipay.card.applepay.cmp

import com.hipay.card.applepay.ApplePayEligibilityResult
import com.hipay.card.applepay.ApplePayOrder
import com.hipay.card.applepay.ApplePayPaymentResult
import com.hipay.card.applepay.HiPayApplePayConfig
import com.hipay.card.validation.CardNetwork
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs an Apple Pay payment from a Compose-Multiplatform host: presents the sheet, tokenizes the
 * wallet token, creates the order and reports the outcome.
 *
 * This is a thin delegation to the ONE shared implementation the Swift SPM channel also calls, so the
 * sheet behaves identically on both — nothing about the flow is reimplemented here.
 *
 * Apple Pay is iOS-only: on Android this always throws [UnsupportedOperationException] rather than
 * pretending, so a host that forgets to gate on [hiPayApplePaySupported] gets a clear message instead
 * of silence.
 */
@Throws(HiPayException::class, CancellationException::class)
public expect suspend fun runHiPayApplePayPayment(
    config: HiPayConfig,
    applePayConfig: HiPayApplePayConfig,
    order: ApplePayOrder,
    customerCountry: String? = null,
): ApplePayPaymentResult

/**
 * Whether Apple Pay can be offered, and why — drives the button's visibility, and lets a host explain
 * an absent button instead of leaving it a mystery.
 *
 * Combines the device's capability with the networks the account accepts. Note Amex is never routable
 * via Apple Pay at HiPay, so a wallet holding only an Amex card resolves to unavailable.
 */
@Throws(HiPayException::class, CancellationException::class)
public expect suspend fun resolveHiPayApplePayAvailability(
    config: HiPayConfig,
    currency: String,
    customerCountry: String? = null,
    allowedNetworks: List<CardNetwork> = emptyList(),
): ApplePayEligibilityResult

/** Whether this platform can do Apple Pay at all — false on Android, true on iOS. */
public expect fun hiPayApplePaySupported(): Boolean
