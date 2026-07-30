// PCI: com.hipay.card path — never log here.
package com.hipay.card.applepay.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hipay.card.applepay.HiPayApplePayButtonStyle
import com.hipay.card.applepay.HiPayApplePayButtonType

/**
 * A positionable Apple Pay button for Compose-Multiplatform hosts. Place it anywhere in your
 * Compose hierarchy; provide a PassKit [style] + [type] and an [onTap] to start the payment (the
 * payment flow itself is wired in a later story — this is the button only).
 *
 * Apple forbids redrawing the button, so on iOS this hosts the real `PKPaymentButton` (via
 * `UIKitView`); only PassKit's appearance options are exposed. Apple Pay is iOS-only — on Android
 * this renders nothing.
 *
 * When [isAvailable] is `null` (default), availability falls back to
 * `PKPaymentAuthorizationController.canMakePayments()` on iOS (always unavailable on Android). Full
 * routable-network eligibility is wired in a later story.
 */
@Composable
public expect fun HiPayApplePayButton(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    style: HiPayApplePayButtonStyle = HiPayApplePayButtonStyle.AUTOMATIC,
    type: HiPayApplePayButtonType = HiPayApplePayButtonType.BUY,
    isAvailable: Boolean? = null,
)
