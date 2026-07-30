// PCI: com.hipay.card path — never log here.
package com.hipay.card.applepay.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hipay.card.applepay.HiPayApplePayButtonStyle
import com.hipay.card.applepay.HiPayApplePayButtonType

/** Apple Pay is unavailable on Android — the button renders nothing. */
@Composable
public actual fun HiPayApplePayButton(
    onTap: () -> Unit,
    modifier: Modifier,
    style: HiPayApplePayButtonStyle,
    type: HiPayApplePayButtonType,
    isAvailable: Boolean?,
) {
    // no-op: Apple Pay is iOS-only.
}
