// PCI: com.hipay.card path — never log here.
@file:OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)

package com.hipay.card.applepay.cmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import com.hipay.card.applepay.HiPayApplePayButtonStyle
import com.hipay.card.applepay.HiPayApplePayButtonType
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSSelectorFromString
import platform.PassKit.PKPaymentButton
import platform.PassKit.PKPaymentButtonStyle
import platform.PassKit.PKPaymentButtonStyleAutomatic
import platform.PassKit.PKPaymentButtonStyleBlack
import platform.PassKit.PKPaymentButtonStyleWhite
import platform.PassKit.PKPaymentButtonStyleWhiteOutline
import platform.PassKit.PKPaymentButtonType
import platform.PassKit.PKPaymentButtonTypeAddMoney
import platform.PassKit.PKPaymentButtonTypeBook
import platform.PassKit.PKPaymentButtonTypeBuy
import platform.PassKit.PKPaymentButtonTypeCheckout
import platform.PassKit.PKPaymentButtonTypeContinue
import platform.PassKit.PKPaymentButtonTypeContribute
import platform.PassKit.PKPaymentButtonTypeDonate
import platform.PassKit.PKPaymentButtonTypeOrder
import platform.PassKit.PKPaymentButtonTypePlain
import platform.PassKit.PKPaymentButtonTypeReload
import platform.PassKit.PKPaymentButtonTypeSubscribe
import platform.PassKit.PKPaymentButtonTypeSupport
import platform.PassKit.PKPaymentButtonTypeTip
import platform.PassKit.PKPaymentButtonTypeTopUp
import platform.UIKit.UIControlEventTouchUpInside
import platform.darwin.NSObject

/** Retains the tap closure and exposes an ObjC selector for the `PKPaymentButton` target-action. */
private class ApplePayTapTarget(var onTap: () -> Unit) : NSObject() {
    @ObjCAction
    fun handleTap() {
        onTap()
    }
}

@Composable
public actual fun HiPayApplePayButton(
    onTap: () -> Unit,
    isAvailable: Boolean,
    modifier: Modifier,
    style: HiPayApplePayButtonStyle,
    type: HiPayApplePayButtonType,
) {
    // No fallback on purpose: the caller owns the verdict (see the expect declaration's KDoc).
    if (!isAvailable) return

    val target = remember { ApplePayTapTarget(onTap) }
    target.onTap = onTap

    // PKPaymentButton's type/style are init-only → recreate the native view when they change.
    key(style, type) {
        UIKitView(
            factory = {
                val button = PKPaymentButton(
                    paymentButtonType = type.toPk(),
                    paymentButtonStyle = style.toPk(),
                )
                button.addTarget(
                    target = target,
                    action = NSSelectorFromString("handleTap"),
                    forControlEvents = UIControlEventTouchUpInside,
                )
                button
            },
            modifier = modifier,
        )
    }
}

internal fun HiPayApplePayButtonStyle.toPk(): PKPaymentButtonStyle = when (this) {
    HiPayApplePayButtonStyle.BLACK -> PKPaymentButtonStyleBlack
    HiPayApplePayButtonStyle.WHITE -> PKPaymentButtonStyleWhite
    HiPayApplePayButtonStyle.WHITE_OUTLINE -> PKPaymentButtonStyleWhiteOutline
    HiPayApplePayButtonStyle.AUTOMATIC -> PKPaymentButtonStyleAutomatic
}

internal fun HiPayApplePayButtonType.toPk(): PKPaymentButtonType = when (this) {
    HiPayApplePayButtonType.PLAIN -> PKPaymentButtonTypePlain
    HiPayApplePayButtonType.BUY -> PKPaymentButtonTypeBuy
    HiPayApplePayButtonType.CHECKOUT -> PKPaymentButtonTypeCheckout
    HiPayApplePayButtonType.BOOK -> PKPaymentButtonTypeBook
    HiPayApplePayButtonType.SUBSCRIBE -> PKPaymentButtonTypeSubscribe
    HiPayApplePayButtonType.ORDER -> PKPaymentButtonTypeOrder
    HiPayApplePayButtonType.CONTINUE -> PKPaymentButtonTypeContinue
    HiPayApplePayButtonType.RELOAD -> PKPaymentButtonTypeReload
    HiPayApplePayButtonType.ADD_MONEY -> PKPaymentButtonTypeAddMoney
    HiPayApplePayButtonType.TOP_UP -> PKPaymentButtonTypeTopUp
    HiPayApplePayButtonType.TIP -> PKPaymentButtonTypeTip
    HiPayApplePayButtonType.DONATE -> PKPaymentButtonTypeDonate
    HiPayApplePayButtonType.SUPPORT -> PKPaymentButtonTypeSupport
    HiPayApplePayButtonType.CONTRIBUTE -> PKPaymentButtonTypeContribute
}
