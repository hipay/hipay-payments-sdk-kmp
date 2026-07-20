package com.hipay.card.cmp

import com.hipay.card.model.CardInfo
import com.hipay.card.validation.CardNetwork
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline tests for the shared Compose-MP card controller (story 10.2, slice A). Pure state
 * logic over the frozen commonMain contract — no network, no UI. Snapshot state reads/writes
 * work outside composition.
 */
class CmpCardControllerTest {

    private fun controller(allowed: List<CardNetwork> = emptyList()) =
        CmpCardController(
            HiPayConfig(username = "u", password = "p", environment = Environment.STAGE),
            allowed,
            // Synchronous scope + empty backend verdict: these tests exercise the LOCAL entry
            // contract only — the co-brand refinement has its own suite (CmpCoBrandResolutionTest).
            scope = CoroutineScope(Dispatchers.Unconfined),
        ).apply { cardInfoResolver = { CardInfo() } }

    @Test
    fun startsNotPayable() {
        assertFalse(controller().canPay)
    }

    @Test
    fun holderIsUppercasedAndCapped() {
        val c = controller()
        c.onHolderChange("john doe")
        assertEquals("JOHN DOE", c.holder)
        c.onHolderChange("x".repeat(80))
        assertEquals(60, c.holder.length)
    }

    @Test
    fun numberIsRawDigitsAndNetworkDetected() {
        val c = controller()
        c.onNumberChange("4111 1111 1111 1111") // even if spaces are pasted
        assertEquals("4111111111111111", c.cardNumber) // value is RAW digits (11.1)
        assertEquals(CardNetwork.VISA, c.network)
    }

    @Test
    fun numberCappedToDetectedNetworkLength() {
        // Story 11.7: input capped to the detected network's complete length, not a flat 19.
        val visa = controller()
        visa.onNumberChange("4111111111111111999") // 19 digits typed on a Visa (16)
        assertEquals(16, visa.cardNumber.length)
        assertEquals("4111111111111111", visa.cardNumber)

        val amex = controller()
        amex.onNumberChange("3782822463100050000") // 19 digits on an Amex (15)
        assertEquals(15, amex.cardNumber.length)

        val unknown = controller()
        unknown.onNumberChange("99999999999999999999") // 20 digits, unrecognized → 19 max
        assertEquals(19, unknown.cardNumber.length)
    }

    @Test
    fun expiryIsRawDigits() {
        // Story 11.8: value is RAW digits MMYY; the "/" is an ExpiryVisualTransformation (display only).
        val c = controller()
        c.onExpiryChange("12/99") // even if a "/" is pasted
        assertEquals("1299", c.expiry)
    }

    @Test
    fun cvcCappedToNetworkPolicy() {
        val c = controller()
        c.onNumberChange("4111111111111111") // Visa → CVC length 3
        c.onCvcChange("12345")
        assertEquals("123", c.cvc)
    }

    @Test
    fun becomesPayableWithValidInput() {
        val c = controller()
        c.onHolderChange("John Doe")
        c.onNumberChange("4111111111111111")
        c.onExpiryChange("1230") // 12/2030 — future, within the 15-year horizon
        c.onCvcChange("123")
        assertTrue(c.canPay)
    }

    @Test
    fun numberErrorOnlyAfterBlur() {
        val c = controller()
        c.onNumberChange("4111") // incomplete
        assertNull(c.numberSlotErrorKey)          // not shown before blur
        c.markBlurred(CmpCardController.Field.NUMBER)
        assertNotNull(c.numberSlotErrorKey)        // shown after blur
    }
}
