// PCI: com.hipay.card path — never log here.
package com.hipay.card.applepay.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hipay.card.applepay.HiPayApplePayButtonStyle
import com.hipay.card.applepay.HiPayApplePayButtonType

/**
 * A positionable Apple Pay button for Compose-Multiplatform hosts. Place it anywhere in your
 * Compose hierarchy; provide a PassKit [style] + [type], an [onTap] that starts the payment with
 * [runHiPayApplePayPayment], and the [isAvailable] verdict.
 *
 * Apple forbids redrawing the button, so on iOS this hosts the real `PKPaymentButton` (via
 * `UIKitView`); only PassKit's appearance options are exposed. Apple Pay is iOS-only — on Android
 * this renders nothing.
 *
 * [isAvailable] is REQUIRED, deliberately. Resolve it with [resolveHiPayApplePayAvailability] and
 * start from `false`: three conditions must all hold — the device can pay, your HiPay account is
 * contracted for a network Apple Pay can route, and your optional restriction leaves at least one of
 * them. `PKPaymentAuthorizationController.canMakePayments()` answers only the first and is `true` on
 * any Apple-Pay-capable device *even with no card provisioned*, so defaulting to it would show a
 * button that cannot complete a payment — a failure invisible outside a real device. Requiring the
 * parameter turns that mistake into a compile error instead.
 */
@Composable
public expect fun HiPayApplePayButton(
    onTap: () -> Unit,
    isAvailable: Boolean,
    modifier: Modifier = Modifier,
    style: HiPayApplePayButtonStyle = HiPayApplePayButtonStyle.AUTOMATIC,
    type: HiPayApplePayButtonType = HiPayApplePayButtonType.BUY,
)
