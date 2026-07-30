package com.hipay.card.applepay.cmp

import com.hipay.card.applepay.HiPayApplePayButtonStyle
import com.hipay.card.applepay.HiPayApplePayButtonType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the shared Apple Pay appearance contract both delivery channels (Swift SPM + this CMP
 * module) expose. A change here means the public button API changed — intentional edits update the
 * expected lists; accidental ones are caught.
 */
class AppearanceContractTest {

    @Test
    fun styleContractIsStable() {
        assertEquals(
            listOf("BLACK", "WHITE", "WHITE_OUTLINE", "AUTOMATIC"),
            HiPayApplePayButtonStyle.entries.map { it.name },
        )
    }

    @Test
    fun typeContractIsStable() {
        assertEquals(
            listOf(
                "PLAIN", "BUY", "CHECKOUT", "BOOK", "SUBSCRIBE", "ORDER", "CONTINUE",
                "RELOAD", "ADD_MONEY", "TOP_UP", "TIP", "DONATE", "SUPPORT", "CONTRIBUTE",
            ),
            HiPayApplePayButtonType.entries.map { it.name },
        )
    }
}
