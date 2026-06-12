package com.hipay.card.validation

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException

/**
 * Local card-input validation (FR11) — minimal mirror of the legacy iOS
 * utilities. Saves a network round-trip; the backend stays the final
 * authority (validators guarantee nothing).
 *
 * Boolean functions are the live-validation surface consumed by the
 * card-entry view through the Swift facade (D1: logic in commonMain, tested
 * before the view exists). Zero logging in this package (PCI).
 */
public object CardValidators {

    /** 12-19 digits, digits only, passing the Luhn check. */
    public fun isCardNumberValid(number: String): Boolean =
        number.length in 12..19 && number.all { it.isDigit() } && passesLuhn(number)

    /** Exactly "MM", 01-12. */
    public fun isExpiryMonthValid(month: String): Boolean =
        month.length == 2 && month.all { it.isDigit() } && month.toInt() in 1..12

    /** Exactly "YYYY". */
    public fun isExpiryYearValid(year: String): Boolean =
        year.length == 4 && year.all { it.isDigit() }

    /** Valid formats AND not in the past (current month is still valid). */
    public fun isExpiryDateValid(month: String, year: String): Boolean {
        if (!isExpiryMonthValid(month) || !isExpiryYearValid(year)) return false
        val (currentYear, currentMonth) = currentYearMonth()
        val y = year.toInt()
        return y > currentYear || (y == currentYear && month.toInt() >= currentMonth)
    }

    /** At most 60 characters (vault contract). */
    public fun isHolderValid(holder: String): Boolean = holder.length <= 60

    /** Empty (allowed by the vault contract) or 3-4 digits. */
    public fun isCvcValid(cvc: String): Boolean =
        cvc.isEmpty() || (cvc.length in 3..4 && cvc.all { it.isDigit() })

    private fun passesLuhn(digits: String): Boolean {
        var sum = 0
        digits.reversed().forEachIndexed { index, char ->
            var digit = char.digitToInt()
            if (index % 2 == 1) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
        }
        return sum % 10 == 0
    }
}

/**
 * Public-boundary gate used by the tokenization path (2.4): throws
 * [HiPayException] with [HiPayErrorCode.VALIDATION] on the first invalid
 * field. Messages carry the FIELD NAME AND REASON ONLY — never the input
 * value: a host logging the exception must never capture a PAN (PCI).
 */
internal fun ensureValidForTokenization(
    cardNumber: String,
    expiryMonth: String,
    expiryYear: String,
    holder: String,
    cvc: String,
) {
    if (!CardValidators.isCardNumberValid(cardNumber)) {
        throw validationError("card_number: must be 12-19 digits and pass the Luhn check")
    }
    if (!CardValidators.isExpiryMonthValid(expiryMonth)) {
        throw validationError("card_expiry_month: must be MM (01-12)")
    }
    if (!CardValidators.isExpiryYearValid(expiryYear)) {
        throw validationError("card_expiry_year: must be YYYY")
    }
    if (!CardValidators.isExpiryDateValid(expiryMonth, expiryYear)) {
        throw validationError("card_expiry: card is expired")
    }
    if (!CardValidators.isHolderValid(holder)) {
        throw validationError("card_holder: must be at most 60 characters")
    }
    if (!CardValidators.isCvcValid(cvc)) {
        throw validationError("cvc: must be empty or 3-4 digits")
    }
}

private fun validationError(fieldAndReason: String): HiPayException =
    HiPayException(
        code = HiPayErrorCode.VALIDATION,
        message = "Invalid card input — $fieldAndReason",
    )

/** Current (year, month 1-12) without a datetime dependency (NFR5). */
internal expect fun currentYearMonth(): Pair<Int, Int>
