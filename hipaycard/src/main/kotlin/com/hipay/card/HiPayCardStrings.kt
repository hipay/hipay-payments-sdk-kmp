// TODO(7.3): replace this hardcoded English map with Android strings.xml (FR/EN/IT,
// default EN) and extend scripts/check-i18n-parity.sh to cover the Android catalog.
// The component already routes ALL user-facing text through the shared
// CardEntryStringKey contract, so 7.3 only swaps the resolution backend.
package com.hipay.card

import com.hipay.card.validation.CardEntryStringKey

/** Temporary English string resolver keyed by the shared [CardEntryStringKey] (i18n is story 7.3). */
internal object HiPayCardStrings {
    fun get(key: CardEntryStringKey): String = when (key) {
        CardEntryStringKey.LABEL_HOLDER -> "Cardholder name"
        CardEntryStringKey.LABEL_NUMBER -> "Card number"
        CardEntryStringKey.LABEL_EXPIRY -> "Expiry date"
        CardEntryStringKey.LABEL_CVV -> "Security code"
        CardEntryStringKey.PLACEHOLDER_HOLDER -> "NAME ON CARD"
        CardEntryStringKey.PLACEHOLDER_NUMBER -> "1234 5678 9012 3456"
        CardEntryStringKey.PLACEHOLDER_EXPIRY -> "MM/YY"
        CardEntryStringKey.PLACEHOLDER_CVV -> "CVV"
        CardEntryStringKey.CVV_OPTIONAL -> "optional"
        CardEntryStringKey.CVV_TOOLTIP -> "Enter the CVV or security code on your card."
        CardEntryStringKey.ERROR_INVALID_NUMBER -> "Invalid card number."
        CardEntryStringKey.ERROR_INCOMPLETE_NUMBER -> "Card number is incomplete."
        CardEntryStringKey.ERROR_INVALID_EXPIRY -> "Invalid expiry date."
        CardEntryStringKey.ERROR_EXPIRED -> "This card has expired."
        CardEntryStringKey.ERROR_INVALID_CVV -> "Invalid security code."
        CardEntryStringKey.ERROR_INCOMPLETE_CVV -> "Security code is incomplete."
        CardEntryStringKey.ERROR_HOLDER_TOO_LONG -> "Name is too long."
        CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED -> "This card network is not accepted."
    }
}
