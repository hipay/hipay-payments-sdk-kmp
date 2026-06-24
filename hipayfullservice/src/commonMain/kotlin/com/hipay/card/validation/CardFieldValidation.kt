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
 *
 * CONTRACT — pass the RESOLVED network. [cvcReason] and [AllowedNetworks]
 * classify against the [CardNetwork] the caller supplies. Pass the
 * backend-resolved network when available, else the locally detected one;
 * while the network is still [CardNetwork.UNKNOWN] (mid-typing, or a locally
 * undetectable co-brand like CB) these functions deliberately return [VALID]
 * rather than flag — so the UI never shows a premature error before the
 * network is known. The same contract holds for the Android (Compose) UI.
 */
public object CardFieldValidation {

    /** Empty → VALID (untouched). Complete-but-wrong → INVALID; valid prefix → INCOMPLETE. */
    public fun cardNumberReason(number: String): ValidationReason = when {
        number.isEmpty() -> ValidationReason.VALID
        number.any { it !in '0'..'9' } || number.length > 19 -> ValidationReason.INVALID_NUMBER
        CardValidators.isCardNumberValid(number) -> ValidationReason.VALID
        // At or beyond the network's completion length but fails Luhn → invalid
        // (covers over-length-for-network, e.g. a 19-digit Amex); a still-short
        // digits prefix is merely incomplete.
        number.length >= CardNetworks.completionLength(CardNetworks.detect(number)) ->
            ValidationReason.INVALID_NUMBER
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

    /**
     * Not required or empty → VALID. Else checks length/format against the network's CVC length.
     * [offered] is the offered/co-brand set so the requirement is co-brand aware (story 11.5):
     * a mono-network Maestro requires a CVC, a co-branded one does not.
     */
    public fun cvcReason(cvc: String, network: CardNetwork, offered: List<CardNetwork>): ValidationReason {
        // Network not yet resolved → do not flag (see object CONTRACT); the
        // expected CVC length is unknown until the network is known.
        if (network == CardNetwork.UNKNOWN) return ValidationReason.VALID
        if (!CardNetworks.isCvcRequired(network, offered) || cvc.isEmpty()) return ValidationReason.VALID
        val expected = CardNetworks.cvcLength(network)
        return when {
            cvc.any { it !in '0'..'9' } -> ValidationReason.INVALID_CVV
            cvc.length == expected -> ValidationReason.VALID
            cvc.length < expected -> ValidationReason.INCOMPLETE_CVV
            else -> ValidationReason.INVALID_CVV
        }
    }

    /** Convenience: a lone network is treated as mono (so a bare Maestro requires a CVC). */
    public fun cvcReason(cvc: String, network: CardNetwork): ValidationReason =
        cvcReason(cvc, network, listOf(network))

    /** Empty → VALID (untouched). Over 60 chars → HOLDER_TOO_LONG. */
    public fun holderReason(holder: String): ValidationReason =
        if (CardValidators.isHolderValid(holder)) ValidationReason.VALID else ValidationReason.HOLDER_TOO_LONG
}
