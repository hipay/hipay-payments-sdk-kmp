package com.hipay.card.validation

import kotlin.test.Test
import kotlin.test.assertEquals

class ValidationReasonTest {

    // --- Card number ---

    @Test
    fun emptyNumberIsValid() {
        assertEquals(ValidationReason.VALID, CardFieldValidation.cardNumberReason(""))
    }

    @Test
    fun completeLuhnValidNumberIsValid() {
        assertEquals(ValidationReason.VALID, CardFieldValidation.cardNumberReason("4111111111111111"))
    }

    @Test
    fun shortPrefixIsIncomplete() {
        // Visa prefix, digits only, below the 16-digit completion length
        assertEquals(ValidationReason.INCOMPLETE_NUMBER, CardFieldValidation.cardNumberReason("41111111"))
    }

    @Test
    fun completeLengthFailingLuhnIsInvalid() {
        // 16 digits (complete for Visa) but Luhn fails
        assertEquals(ValidationReason.INVALID_NUMBER, CardFieldValidation.cardNumberReason("4111111111111112"))
    }

    @Test
    fun nonDigitOrTooLongIsInvalid() {
        assertEquals(ValidationReason.INVALID_NUMBER, CardFieldValidation.cardNumberReason("4111 1111"))
        assertEquals(ValidationReason.INVALID_NUMBER, CardFieldValidation.cardNumberReason("١٢٣٤")) // Unicode digits
        assertEquals(ValidationReason.INVALID_NUMBER, CardFieldValidation.cardNumberReason("50500000000000000000")) // 20 digits
    }

    @Test
    fun overNetworkCompletionLengthFailingLuhnIsInvalid() {
        // Amex completes at 15; a 19-digit Amex-prefixed Luhn-failing number is
        // past completion → INVALID, not INCOMPLETE (D1 review fix).
        assertEquals(ValidationReason.INVALID_NUMBER, CardFieldValidation.cardNumberReason("3" + "4".repeat(18)))
        // A failing Maestro below its 19-digit completion is still INCOMPLETE.
        assertEquals(ValidationReason.INCOMPLETE_NUMBER, CardFieldValidation.cardNumberReason("5" + "0".repeat(15)))
    }

    // --- Expiry ---

    @Test
    fun emptyExpiryIsValid() {
        assertEquals(ValidationReason.VALID, CardFieldValidation.expiryReason("", ""))
    }

    @Test
    fun futureExpiryIsValid() {
        val (year, _) = currentYearMonth()
        assertEquals(ValidationReason.VALID, CardFieldValidation.expiryReason("12", (year + 2).toString()))
    }

    @Test
    fun pastExpiryIsExpired() {
        val (year, _) = currentYearMonth()
        assertEquals(ValidationReason.EXPIRED, CardFieldValidation.expiryReason("12", (year - 1).toString()))
    }

    @Test
    fun malformedExpiryIsInvalid() {
        assertEquals(ValidationReason.INVALID_EXPIRY, CardFieldValidation.expiryReason("13", "2030"))
        assertEquals(ValidationReason.INVALID_EXPIRY, CardFieldValidation.expiryReason("12", "30")) // year not YYYY
    }

    // --- CVV (network-dependent) ---

    @Test
    fun cvcNotRequiredOrEmptyIsValid() {
        assertEquals(ValidationReason.VALID, CardFieldValidation.cvcReason("", CardNetwork.VISA))
        assertEquals(ValidationReason.VALID, CardFieldValidation.cvcReason("12", CardNetwork.MAESTRO)) // not required
        assertEquals(ValidationReason.VALID, CardFieldValidation.cvcReason("", CardNetwork.BCMC))
    }

    @Test
    fun cvcForUnresolvedNetworkIsValid() {
        // Network not yet resolved (mid-typing / CB) → never flag, even with a
        // length that would be wrong for some network (D2 review fix).
        assertEquals(ValidationReason.VALID, CardFieldValidation.cvcReason("1234", CardNetwork.UNKNOWN))
        assertEquals(ValidationReason.VALID, CardFieldValidation.cvcReason("12", CardNetwork.UNKNOWN))
    }

    @Test
    fun cvcLengthChecksAgainstNetwork() {
        assertEquals(ValidationReason.VALID, CardFieldValidation.cvcReason("123", CardNetwork.VISA))
        assertEquals(ValidationReason.INCOMPLETE_CVV, CardFieldValidation.cvcReason("12", CardNetwork.VISA))
        assertEquals(ValidationReason.VALID, CardFieldValidation.cvcReason("1234", CardNetwork.AMEX)) // amex = 4
        assertEquals(ValidationReason.INCOMPLETE_CVV, CardFieldValidation.cvcReason("123", CardNetwork.AMEX))
        assertEquals(ValidationReason.INVALID_CVV, CardFieldValidation.cvcReason("12345", CardNetwork.VISA)) // too long
        assertEquals(ValidationReason.INVALID_CVV, CardFieldValidation.cvcReason("12a", CardNetwork.VISA))
    }

    // --- Holder ---

    @Test
    fun holderLength() {
        assertEquals(ValidationReason.VALID, CardFieldValidation.holderReason("A".repeat(60)))
        assertEquals(ValidationReason.HOLDER_TOO_LONG, CardFieldValidation.holderReason("A".repeat(61)))
    }
}
