package com.hipay.card.cmp

import com.hipay.card.validation.CardEntryStringKey

/**
 * Slice-A string resolution (story 10.2): EN values, verbatim from the existing
 * iOS/Android catalogs. **Slice B replaces this with a Compose-Multiplatform
 * resource catalog (FR/EN/IT, device locale + localeOverride)** keyed by the same
 * [CardEntryStringKey] — this `when` is the temporary EN-only stand-in so slice A
 * compiles and renders without the compose-resources setup.
 */
internal fun cmpString(key: CardEntryStringKey): String = when (key) {
    CardEntryStringKey.LABEL_HOLDER -> "Cardholder name"
    CardEntryStringKey.LABEL_NUMBER -> "Card number"
    CardEntryStringKey.LABEL_EXPIRY -> "Expiry date"
    CardEntryStringKey.LABEL_CVV -> "Security code"
    CardEntryStringKey.PLACEHOLDER_HOLDER -> "Name on card"
    CardEntryStringKey.PLACEHOLDER_NUMBER -> "1234 5678 9012 3456"
    CardEntryStringKey.PLACEHOLDER_EXPIRY -> "MM/YY"
    CardEntryStringKey.PLACEHOLDER_CVV -> "CVV"
    CardEntryStringKey.CVV_OPTIONAL -> "Optional"
    CardEntryStringKey.CVV_TOOLTIP -> "Enter the CVV or security code on your card."
    CardEntryStringKey.ERROR_INVALID_NUMBER -> "Invalid card number"
    CardEntryStringKey.ERROR_INCOMPLETE_NUMBER -> "Card number is incomplete"
    CardEntryStringKey.ERROR_INVALID_EXPIRY -> "Invalid expiry date"
    CardEntryStringKey.ERROR_EXPIRED -> "This card has expired"
    CardEntryStringKey.ERROR_INVALID_CVV -> "Invalid security code"
    CardEntryStringKey.ERROR_INCOMPLETE_CVV -> "Security code is incomplete"
    CardEntryStringKey.ERROR_HOLDER_TOO_LONG -> "Cardholder name is too long"
    CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED -> "This card network is not accepted"
    CardEntryStringKey.LABEL_SAVED_CARDS -> "Saved cards"
    CardEntryStringKey.LABEL_NEW_CARD -> "New card"
    CardEntryStringKey.LABEL_SAVE_CARD -> "Save this card"
    // Draft consent copy: final legal wording validated later with the merchant privacy slot.
    CardEntryStringKey.CONSENT_SAVE_CARD -> "Save this card for faster checkout. You can remove it at any time."
    CardEntryStringKey.A11Y_SAVED_CARD -> "%1\$s finishing %2\$s, expires %3\$s"
}

/** Positional `%n$s` substitution for the slice-A templates (compose-resources handles it in slice B). */
internal fun cmpFormat(template: String, vararg args: String): String {
    var out = template
    args.forEachIndexed { i, arg -> out = out.replace("%${i + 1}\$s", arg) }
    return out
}
