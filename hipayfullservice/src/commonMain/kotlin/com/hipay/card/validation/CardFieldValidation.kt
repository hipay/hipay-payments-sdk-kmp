package com.hipay.card.validation

/**
 * Per-field validation that returns a typed [ValidationReason] (FR26) — the
 * surface the card components use to pick the right localized message and to
 * distinguish "incomplete" (show on focus loss) from "invalid".
 *
 * Built ON TOP of [CardValidators] (the v1 boolean rules, unchanged) and the
 * [CardNetworks] matrix (completion length / CVC length). Inputs are the
 * extracted DIGIT strings the UI already produces (number digits, MM, YYYY,
 * CVC digits). Value-free — no input is echoed anywhere. Zero logging (PCI).
 */
public object CardFieldValidation {

    /** Empty → VALID (untouched). Complete-but-wrong → INVALID; valid prefix → INCOMPLETE. */
    public fun cardNumberReason(number: String): ValidationReason = when {
        number.isEmpty() -> ValidationReason.VALID
        number.any { it !in '0'..'9' } || number.length > 19 -> ValidationReason.INVALID_NUMBER
        CardValidators.isCardNumberValid(number) -> ValidationReason.VALID
        CardNetworks.isNumberComplete(number) -> ValidationReason.INVALID_NUMBER // complete length, fails Luhn
        else -> ValidationReason.INCOMPLETE_NUMBER
    }

    /** Both empty → VALID. Bad format → INVALID_EXPIRY. Valid format but past → EXPIRED. */
    public fun expiryReason(month: String, year: String): ValidationReason = when {
        month.isEmpty() && year.isEmpty() -> ValidationReason.VALID
        !CardValidators.isExpiryMonthValid(month) || !CardValidators.isExpiryYearValid(year) ->
            ValidationReason.INVALID_EXPIRY
        !CardValidators.isExpiryDateValid(month, year) -> ValidationReason.EXPIRED
        else -> ValidationReason.VALID
    }

    /** Not required or empty → VALID. Else checks length/format against the network's CVC length. */
    public fun cvcReason(cvc: String, network: CardNetwork): ValidationReason {
        if (!CardNetworks.isCvcRequired(network) || cvc.isEmpty()) return ValidationReason.VALID
        val expected = CardNetworks.cvcLength(network)
        return when {
            cvc.any { it !in '0'..'9' } -> ValidationReason.INVALID_CVV
            cvc.length == expected -> ValidationReason.VALID
            cvc.length < expected -> ValidationReason.INCOMPLETE_CVV
            else -> ValidationReason.INVALID_CVV
        }
    }

    /** Empty → VALID (untouched). Over 60 chars → HOLDER_TOO_LONG. */
    public fun holderReason(holder: String): ValidationReason =
        if (CardValidators.isHolderValid(holder)) ValidationReason.VALID else ValidationReason.HOLDER_TOO_LONG
}
