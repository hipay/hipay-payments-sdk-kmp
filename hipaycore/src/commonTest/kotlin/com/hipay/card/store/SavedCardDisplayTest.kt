package com.hipay.card.store

import com.hipay.card.validation.CardNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SavedCardDisplayTest {

    private fun card(
        maskedPan: String = "411111xxxxxx1111",
        network: String = "VISA",
        month: String = "12",
        year: String = "2030",
    ) = SavedCard(
        token = "t".repeat(64),
        maskedPan = maskedPan,
        network = network,
        holder = "JANE DOE",
        expiryMonth = month,
        expiryYear = year,
    )

    @Test
    fun fourByFourNetworksBulletEverythingButTheLastFour() {
        // Visa / Mastercard / CB / Maestro share the 4x4 grouping.
        for (brand in listOf("VISA", "MASTERCARD", "CB", "maestro")) {
            val d = savedCardDisplay(card(network = brand))
            assertEquals("•••• •••• •••• 1111", d.maskedNumber, "brand=$brand")
        }
    }

    @Test
    fun amexUsesTheFourSixFivePattern() {
        val d = savedCardDisplay(card(maskedPan = "371111xxxxx1111", network = "AMERICAN EXPRESS"))
        assertEquals("•••• •••••• •1111", d.maskedNumber)
        assertEquals(CardNetwork.AMEX, d.network)
    }

    @Test
    fun unknownBrandFallsBackToFourByFour() {
        val d = savedCardDisplay(card(network = "future-brand"))
        assertEquals("•••• •••• •••• 1111", d.maskedNumber)
        assertNull(d.network)
    }

    @Test
    fun lastFourComesFromTheTrailingDigitsOnly() {
        assertEquals("1111", savedCardDisplay(card()).last4)
        // *-masked defensive input still yields the trailing digits
        assertEquals("4444", savedCardDisplay(card(maskedPan = "541111******4444")).last4)
    }

    @Test
    fun displayNeverLeaksTheBin() {
        val d = savedCardDisplay(card())
        assertFalse(d.maskedNumber.contains("4111"), "the BIN must never appear in the display")
        assertEquals("•••• •••• •••• 1111", d.maskedNumber)
    }

    @Test
    fun shortOrEmptyMaskedPanRendersSafely() {
        // Fewer than 4 trailing digits: show what exists, never crash.
        val d = savedCardDisplay(card(maskedPan = "11"))
        assertEquals("11", d.last4)
        assertEquals("•••• •••• •••• 11", d.maskedNumber)
        val empty = savedCardDisplay(card(maskedPan = "xxxx"))
        assertEquals("", empty.last4)
    }

    @Test
    fun expiryDisplaysAsMonthSlashFullYear() {
        assertEquals("12 / 2030", savedCardDisplay(card()).displayExpiry)
        // 2-digit stored year is normalized to 20xx
        assertEquals("03 / 2029", savedCardDisplay(card(month = "3", year = "29")).displayExpiry)
    }

    @Test
    fun unparseableExpiryFallsBackToTheRawValues() {
        val d = savedCardDisplay(card(month = "1a", year = "20b0"))
        assertEquals("1a / 20b0", d.displayExpiry)
    }
}
