package com.hipay.card.applepay.cmp

import com.hipay.card.applepay.HiPayApplePayButtonStyle
import com.hipay.card.applepay.HiPayApplePayButtonType
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.PassKit.PKPaymentButtonStyleAutomatic
import platform.PassKit.PKPaymentButtonStyleBlack
import platform.PassKit.PKPaymentButtonStyleWhite
import platform.PassKit.PKPaymentButtonStyleWhiteOutline
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

/** Each SDK appearance value maps to the correct native PassKit value. */
class ButtonMappingTest {

    @Test
    fun styleMapsToPassKit() {
        assertEquals(PKPaymentButtonStyleBlack, HiPayApplePayButtonStyle.BLACK.toPk())
        assertEquals(PKPaymentButtonStyleWhite, HiPayApplePayButtonStyle.WHITE.toPk())
        assertEquals(PKPaymentButtonStyleWhiteOutline, HiPayApplePayButtonStyle.WHITE_OUTLINE.toPk())
        assertEquals(PKPaymentButtonStyleAutomatic, HiPayApplePayButtonStyle.AUTOMATIC.toPk())
    }

    @Test
    fun typeMapsToPassKit() {
        assertEquals(PKPaymentButtonTypePlain, HiPayApplePayButtonType.PLAIN.toPk())
        assertEquals(PKPaymentButtonTypeBuy, HiPayApplePayButtonType.BUY.toPk())
        assertEquals(PKPaymentButtonTypeCheckout, HiPayApplePayButtonType.CHECKOUT.toPk())
        assertEquals(PKPaymentButtonTypeBook, HiPayApplePayButtonType.BOOK.toPk())
        assertEquals(PKPaymentButtonTypeSubscribe, HiPayApplePayButtonType.SUBSCRIBE.toPk())
        assertEquals(PKPaymentButtonTypeOrder, HiPayApplePayButtonType.ORDER.toPk())
        assertEquals(PKPaymentButtonTypeContinue, HiPayApplePayButtonType.CONTINUE.toPk())
        assertEquals(PKPaymentButtonTypeReload, HiPayApplePayButtonType.RELOAD.toPk())
        assertEquals(PKPaymentButtonTypeAddMoney, HiPayApplePayButtonType.ADD_MONEY.toPk())
        assertEquals(PKPaymentButtonTypeTopUp, HiPayApplePayButtonType.TOP_UP.toPk())
        assertEquals(PKPaymentButtonTypeTip, HiPayApplePayButtonType.TIP.toPk())
        assertEquals(PKPaymentButtonTypeDonate, HiPayApplePayButtonType.DONATE.toPk())
        assertEquals(PKPaymentButtonTypeSupport, HiPayApplePayButtonType.SUPPORT.toPk())
        assertEquals(PKPaymentButtonTypeContribute, HiPayApplePayButtonType.CONTRIBUTE.toPk())
    }
}
