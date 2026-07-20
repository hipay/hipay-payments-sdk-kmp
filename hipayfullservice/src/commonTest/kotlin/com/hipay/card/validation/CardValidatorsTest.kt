package com.hipay.card.validation

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardValidatorsTest {

    // --- Card number: 12-19 digits + Luhn (AC #1, #2) ---

    @Test
    fun acceptsValidTestCards() {
        assertTrue(CardValidators.isCardNumberValid("4111111111111111"))
        assertTrue(CardValidators.isCardNumberValid("5496198584584769"))
    }

    @Test
    fun rejectsLuhnFailure() {
        assertFalse(CardValidators.isCardNumberValid("4111111111111112"))
    }

    @Test
    fun rejectsOutOfBoundsLengths() {
        assertFalse(CardValidators.isCardNumberValid("41111111111")) // 11
        assertTrue(CardValidators.isCardNumberValid("505000000008")) // 12, Luhn-valid
        assertTrue(CardValidators.isCardNumberValid("5050000000000000000")) // 19, Luhn-valid
        assertFalse(CardValidators.isCardNumberValid("50500000000000000000")) // 20
    }

    @Test
    fun rejectsNonDigitInput() {
        assertFalse(CardValidators.isCardNumberValid("4111 1111 1111 1111"))
        assertFalse(CardValidators.isCardNumberValid("4111-1111-1111-1111"))
        assertFalse(CardValidators.isCardNumberValid("abcd111111111111"))
        assertFalse(CardValidators.isCardNumberValid(""))
    }

    // --- Expiry month/year formats (AC #1, #2) ---

    @Test
    fun monthMustBeTwoDigitsBetween01And12() {
        assertTrue(CardValidators.isExpiryMonthValid("01"))
        assertTrue(CardValidators.isExpiryMonthValid("12"))
        assertFalse(CardValidators.isExpiryMonthValid("00"))
        assertFalse(CardValidators.isExpiryMonthValid("13"))
        assertFalse(CardValidators.isExpiryMonthValid("1"))
        assertFalse(CardValidators.isExpiryMonthValid("ab"))
    }

    @Test
    fun yearMustBeFourDigits() {
        assertTrue(CardValidators.isExpiryYearValid("2026"))
        assertFalse(CardValidators.isExpiryYearValid("26"))
        assertFalse(CardValidators.isExpiryYearValid("20266"))
        assertFalse(CardValidators.isExpiryYearValid("20a6"))
    }

    // --- Expiry in the past (AC #2) ---

    @Test
    fun currentMonthIsNotExpired() {
        val (year, month) = currentYearMonth()
        val mm = month.toString().padStart(2, '0')
        assertTrue(CardValidators.isExpiryDateValid(mm, year.toString()))
    }

    @Test
    fun pastDateIsExpired() {
        val (year, _) = currentYearMonth()
        assertFalse(CardValidators.isExpiryDateValid("12", (year - 1).toString()))
    }

    @Test
    fun futureDateIsValid() {
        val (year, _) = currentYearMonth()
        assertTrue(CardValidators.isExpiryDateValid("01", (year + 2).toString()))
    }

    @Test
    fun lastMonthThisYearIsExpired() {
        val (year, month) = currentYearMonth()
        if (month > 1) {
            val mm = (month - 1).toString().padStart(2, '0')
            assertFalse(CardValidators.isExpiryDateValid(mm, year.toString()))
        }
    }

    // --- Holder & CVC (AC #1) ---

    @Test
    fun holderUpTo60Chars() {
        assertTrue(CardValidators.isHolderValid("A".repeat(60)))
        assertFalse(CardValidators.isHolderValid("A".repeat(61)))
        assertTrue(CardValidators.isHolderValid("Test"))
    }

    @Test
    fun holderMinimumThreeCharsOnceNonEmpty() {
        assertTrue(CardValidators.isHolderLongEnough("")) // untouched field stays unflagged
        assertFalse(CardValidators.isHolderLongEnough("A"))
        assertFalse(CardValidators.isHolderLongEnough("AB"))
        assertTrue(CardValidators.isHolderLongEnough("ABC"))
    }

    @Test
    fun sanitizeHolderUppercasesAndFilters() {
        assertEquals("JEAN-PIERRE D'ARC JR.", CardValidators.sanitizeHolder("Jean-Pierre d'Arc Jr."))
        // Symbols outside letters/digits/space/-/'/. are dropped
        assertEquals("AB", CardValidators.sanitizeHolder("a@#%b"))
        // Accented letters are kept and uppercased
        assertEquals("HÉLÈNE", CardValidators.sanitizeHolder("Hélène"))
    }

    @Test
    fun sanitizeHolderCapsDigitsAtEight() {
        assertEquals("A12345678B", CardValidators.sanitizeHolder("a123456789b")) // 9th digit dropped
        assertEquals("12345678", CardValidators.sanitizeHolder("1234567890"))
    }

    @Test
    fun sanitizeHolderCapsLengthAtSixty() {
        assertEquals(60, CardValidators.sanitizeHolder("A".repeat(80)).length)
    }

    @Test
    fun expiryYearHorizonIsFifteenYears() {
        val (year, _) = currentYearMonth()
        assertTrue(CardValidators.isExpiryYearWithinHorizon((year + 15).toString()))
        assertFalse(CardValidators.isExpiryYearWithinHorizon((year + 16).toString()))
        assertFalse(CardValidators.isExpiryYearWithinHorizon("20AB"))
    }

    @Test
    fun cvcEmptyOrThreeToFourDigits() {
        assertTrue(CardValidators.isCvcValid(""))
        assertTrue(CardValidators.isCvcValid("123"))
        assertTrue(CardValidators.isCvcValid("1234"))
        assertFalse(CardValidators.isCvcValid("12"))
        assertFalse(CardValidators.isCvcValid("12345"))
        assertFalse(CardValidators.isCvcValid("abc"))
    }

    // --- Public boundary: VALIDATION exception, value-free messages (AC #3, #4) ---

    @Test
    fun invalidNumberThrowsValidationWithFieldNameOnly() {
        val ex = assertFailsWith<HiPayException> {
            ensureValidForTokenization("4111111111111112", "12", "2030", "Test", "123")
        }
        assertEquals(HiPayErrorCode.VALIDATION, ex.code)
        assertTrue(ex.message!!.contains("card_number"))
        // The PAN must NEVER appear in the message (host logging = PCI leak)
        assertFalse(ex.message!!.contains("4111111111111112"))
        assertFalse(ex.toString().contains("4111111111111112"))
    }

    @Test
    fun expiredCardThrowsValidationWithoutEchoingValues() {
        val (year, _) = currentYearMonth()
        val pastYear = (year - 1).toString()
        val ex = assertFailsWith<HiPayException> {
            ensureValidForTokenization("4111111111111111", "12", pastYear, "Test", "123")
        }
        assertEquals(HiPayErrorCode.VALIDATION, ex.code)
        assertTrue(ex.message!!.contains("card_expiry"))
        assertFalse(ex.message!!.contains(pastYear))
    }

    @Test
    fun invalidHolderAndCvcThrowValidation() {
        val holder = "X".repeat(61)
        val exHolder = assertFailsWith<HiPayException> {
            ensureValidForTokenization("4111111111111111", "12", "2030", holder, "123")
        }
        assertEquals(HiPayErrorCode.VALIDATION, exHolder.code)
        assertTrue(exHolder.message!!.contains("card_holder"))
        assertFalse(exHolder.message!!.contains(holder))

        val exCvc = assertFailsWith<HiPayException> {
            ensureValidForTokenization("4111111111111111", "12", "2030", "Test", "12345")
        }
        assertEquals(HiPayErrorCode.VALIDATION, exCvc.code)
        assertTrue(exCvc.message!!.contains("cvc"))
        assertFalse(exCvc.message!!.contains("12345"))
    }

    @Test
    fun validInputsPassTheGate() {
        // must not throw — empty cvc allowed by the vault contract
        ensureValidForTokenization("4111111111111111", "12", "2030", "Test", "")
        ensureValidForTokenization("5496198584584769", "01", "2031", "A".repeat(60), "1234")
    }

    // --- Review fix: Unicode digits ---
    // Char.isDigit() accepts the Unicode Nd category but toInt() parses ASCII
    // only — Unicode-digit inputs must yield a clean false / VALIDATION, never
    // a raw NumberFormatException crash.

    @Test
    fun rejectsUnicodeDigitsWithoutCrashing() {
        assertFalse(CardValidators.isExpiryMonthValid("١٢")) // Arabic-Indic 12
        assertFalse(CardValidators.isExpiryYearValid("٢٠٣٠")) // 2030
        assertFalse(CardValidators.isCvcValid("１２３")) // fullwidth 123
        assertFalse(CardValidators.isExpiryDateValid("١٢", "٢٠٣٠"))
        assertFalse(CardValidators.isCardNumberValid("٤".repeat(16))) // Arabic-Indic 4 x16
    }

    @Test
    fun gateRejectsUnicodeDigitMonthAsValidationNotCrash() {
        val ex = assertFailsWith<HiPayException> {
            ensureValidForTokenization("4111111111111111", "١٢", "2030", "Test", "123")
        }
        assertEquals(HiPayErrorCode.VALIDATION, ex.code)
        assertTrue(ex.message!!.contains("card_expiry_month"))
    }
}
