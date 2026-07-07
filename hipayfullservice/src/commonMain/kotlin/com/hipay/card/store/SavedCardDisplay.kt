// PCI: com.hipay.card path — NEVER log here, never expose the raw PAN or token.
package com.hipay.card.store

import com.hipay.card.validation.CardNetwork
import com.hipay.card.validation.CardNetworks

/**
 * Display-ready values for a saved-card cell. Only the LAST 4 digits are ever
 * visible — the stored masked pan keeps the BIN for identity, but the UI shows
 * bullets: Visa/Mastercard/CB/Maestro `•••• •••• •••• 1111`, Amex (4-6-5
 * grouping) `•••• •••••• •1111`. Accessibility labels must use [last4] and the
 * localized pattern, never [maskedNumber]'s bullet characters.
 */
public class SavedCardDisplay(
    public val maskedNumber: String,
    public val last4: String,
    public val displayExpiry: String,
    public val network: CardNetwork?,
)

/**
 * Builds the display values for [card]. The network comes from the stored
 * brand; an unknown brand falls back to the generic 4×4 bullet grouping.
 * `CardNetworks.format()` is NOT usable here: it keeps digits only, so it
 * would drop the mask and re-expose the BIN.
 */
public fun savedCardDisplay(card: SavedCard): SavedCardDisplay {
    val network = CardNetworks.fromApiBrand(card.network)
    // Trailing digit run only: a mask that hides the tail must not surface
    // BIN digits as a fake "last4".
    val last4 = card.maskedPan.takeLastWhile { it in '0'..'9' }.takeLast(4)
    val maskedNumber = when (network) {
        CardNetwork.AMEX -> "•••• •••••• •" + last4.ifEmpty { "••••" }
        else -> "•••• •••• •••• " + last4.ifEmpty { "••••" }
    }
    return SavedCardDisplay(
        maskedNumber = maskedNumber,
        last4 = last4,
        displayExpiry = displayExpiry(card),
        network = network,
    )
}

private fun displayExpiry(card: SavedCard): String {
    val month = card.expiryMonth.trim().toIntOrNull()
    val year = normalizeYear(card.expiryYear)
    return if (month != null && year != null) {
        "${month.toString().padStart(2, '0')} / $year"
    } else {
        "${card.expiryMonth.trim()} / ${card.expiryYear.trim()}"
    }
}
