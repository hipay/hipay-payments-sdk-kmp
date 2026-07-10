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
    // PROVISIONAL consent wording — NOT yet legally approved: do not ship to production as-is.
    // The RGPD-validated copy and a slot for the merchant's own privacy-policy text come in a
    // later release. The key and its placement are stable — only the text will change.
    CardEntryStringKey.CONSENT_SAVE_CARD -> "Save this card for faster checkout. You can remove it at any time."
    CardEntryStringKey.A11Y_SAVED_CARD -> "%1\$s finishing %2\$s, expires %3\$s"
    CardEntryStringKey.A11Y_EXPANDED -> "expanded"
    CardEntryStringKey.A11Y_COLLAPSED -> "collapsed"
    // PROVISIONAL delete copy — NOT yet legally/UX approved; finalized in a later release.
    CardEntryStringKey.LABEL_DELETE_CARD -> "Delete card"
    CardEntryStringKey.CONFIRM_DELETE_CARD -> "Remove this saved card? You can save it again next time you pay."
    CardEntryStringKey.LABEL_CANCEL -> "Cancel"
    CardEntryStringKey.ERROR_ONE_CLICK_DECLINED -> "Payment declined. Try another card or enter a new one."
    CardEntryStringKey.ERROR_ONE_CLICK_CARD_REMOVED -> "This card can no longer be used and was removed. Pay with a new card."
    CardEntryStringKey.ERROR_ONE_CLICK_3DS -> "Authentication failed or was cancelled. Try again or use another card."
    CardEntryStringKey.ERROR_ONE_CLICK_EXPIRED -> "This card has expired. Pay with another card."
    CardEntryStringKey.ERROR_ONE_CLICK_GENERIC -> "The payment could not be completed. Try again or use another card."
    // PROVISIONAL copy (soft pending hint) — final wording pending UX/compliance sign-off.
    CardEntryStringKey.ERROR_ONE_CLICK_PENDING -> "Your payment is still being confirmed. Please wait a moment before trying again."
}

/** Positional `%n$s` substitution for the slice-A templates (compose-resources handles it in slice B). */
internal fun cmpFormat(template: String, vararg args: String): String {
    var out = template
    args.forEachIndexed { i, arg -> out = out.replace("%${i + 1}\$s", arg) }
    return out
}
