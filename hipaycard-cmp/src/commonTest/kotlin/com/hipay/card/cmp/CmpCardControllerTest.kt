package com.hipay.card.cmp

import com.hipay.card.validation.CardNetwork
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
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
        )

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
    fun numberIsFormattedAndNetworkDetected() {
        val c = controller()
        c.onNumberChange("4111111111111111")
        assertEquals("4111 1111 1111 1111", c.cardNumber)
        assertEquals(CardNetwork.VISA, c.network)
    }

    @Test
    fun expiryIsFormatted() {
        val c = controller()
        c.onExpiryChange("1299")
        assertEquals("12/99", c.expiry)
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
        c.onExpiryChange("1299") // 12/2099 — future
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
