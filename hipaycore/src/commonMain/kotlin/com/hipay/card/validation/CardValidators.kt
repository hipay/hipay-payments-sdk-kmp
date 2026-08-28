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
        number.length in 12..19 && number.isAsciiDigits() && passesLuhn(number)

    /** Exactly "MM", 01-12. */
    public fun isExpiryMonthValid(month: String): Boolean =
        month.length == 2 && month.isAsciiDigits() && month.toInt() in 1..12

    /** Exactly "YYYY". */
    public fun isExpiryYearValid(year: String): Boolean =
        year.length == 4 && year.isAsciiDigits()

    /** Valid formats AND not in the past (current month is still valid). */
    public fun isExpiryDateValid(month: String, year: String): Boolean {
        if (!isExpiryMonthValid(month) || !isExpiryYearValid(year)) return false
        val (currentYear, currentMonth) = currentYearMonth()
        val y = year.toInt()
        return y > currentYear || (y == currentYear && month.toInt() >= currentMonth)
    }

    /** Valid formats AND not further out than [maxYearsAhead] years (expiry
     *  cards are never issued that far; guards against a typo like 2099). */
    public fun isExpiryYearWithinHorizon(year: String, maxYearsAhead: Int = EXPIRY_HORIZON_YEARS): Boolean {
        if (!isExpiryYearValid(year)) return false
        return year.toInt() <= currentYearMonth().first + maxYearsAhead
    }

    /** At most 60 characters (vault contract). */
    public fun isHolderValid(holder: String): Boolean = holder.length <= 60

    /** At least 3 characters once the field is non-empty (an untouched/empty
     *  field is not flagged — same convention as the other fields). */
    public fun isHolderLongEnough(holder: String): Boolean =
        holder.isEmpty() || holder.length >= HOLDER_MIN_LENGTH

    /**
     * Input shaping for the holder field, shared by the three UIs so typing
     * behaves identically everywhere: uppercased; letters, spaces and common
     * name punctuation (- ' .) accepted; ASCII digits accepted up to
     * [HOLDER_MAX_DIGITS] in total; everything else dropped; hard-capped at
     * 60 characters (vault contract — typing past the cap is blocked).
     */
    public fun sanitizeHolder(input: String): String {
        val out = StringBuilder()
        var digits = 0
        for (ch in input.uppercase()) {
            if (out.length >= 60) break
            when {
                ch in '0'..'9' -> if (digits < HOLDER_MAX_DIGITS) { out.append(ch); digits++ }
                ch.isLetter() || ch == ' ' || ch == '-' || ch == '\'' || ch == '.' -> out.append(ch)
            }
        }
        return out.toString()
    }

    public const val HOLDER_MIN_LENGTH: Int = 3
    public const val HOLDER_MAX_DIGITS: Int = 8
    public const val EXPIRY_HORIZON_YEARS: Int = 15

    /** Empty (allowed by the vault contract) or 3-4 digits. */
    public fun isCvcValid(cvc: String): Boolean =
        cvc.isEmpty() || (cvc.length in 3..4 && cvc.isAsciiDigits())

    // ASCII digits only: Char.isDigit() accepts the whole Unicode Nd category
    // (Arabic-Indic ٠-٩, fullwidth, etc.), but String.toInt() parses ASCII
    // only — using isDigit() lets a Unicode-digit month/year pass the format
    // guard and then crash in toInt(). Pin the alphabet to '0'..'9'.
    private fun String.isAsciiDigits(): Boolean = all { it in '0'..'9' }

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
