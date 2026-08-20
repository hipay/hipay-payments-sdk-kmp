// PCI: com.hipay.card path — NEVER log here, never expose the raw PAN or token.
package com.hipay.card.store

import kotlinx.serialization.Serializable

/**
 * A card the user chose to save for one-click payment.
 *
 * Holds ONLY what one-click needs: the multi-use Secure Vault [token] (the payment
 * credential), a backend-MASKED pan (BIN6 + last4, e.g. "411111xxxxxx1111"), and the
 * display network/holder/expiry. The raw PAN and the CVV are NEVER stored.
 */
@Serializable
public class SavedCard(
    public val token: String,
    public val maskedPan: String,
    public val network: String,
    public val holder: String,
    public val expiryMonth: String,
    public val expiryYear: String,
) {
    /**
     * Identity for dedup/overwrite: the normalised masked PAN ONLY — never the token, never last4
     * alone, and deliberately NOT the expiry. Re-saving the same card with a renewed expiry (and/or
     * a changed holder) therefore updates the existing alias in place instead of creating a duplicate
     * (one-click "save my card" AC: existing masked PAN → update, no doublon). The masked PAN is
     * reduced to its `[0-9x]` characters (case-insensitive) so a cosmetic re-mask of the same card
     * (spacing, uppercase X, differing BIN length) still matches.
     */
    internal val identity: String
        get() = maskedPan.lowercase().filter { it in '0'..'9' || it == 'x' }

    // Never expose the token — mirror HiPayConfig/CardToken terseness.
    override fun toString(): String =
        "SavedCard(maskedPan=$maskedPan, network=$network, exp=$expiryMonth/$expiryYear)"

    // Value equality on every field: the store reloads fresh instances on each read, so a host
    // holding a card across a refresh must still be able to compare/select it — and the Kotlin
    // behaviour must match the Swift wrapper's Equatable.
    override fun equals(other: Any?): Boolean = other is SavedCard &&
        token == other.token &&
        maskedPan == other.maskedPan &&
        network == other.network &&
        holder == other.holder &&
        expiryMonth == other.expiryMonth &&
        expiryYear == other.expiryYear

    override fun hashCode(): Int {
        var result = token.hashCode()
        result = 31 * result + maskedPan.hashCode()
        result = 31 * result + network.hashCode()
        result = 31 * result + holder.hashCode()
        result = 31 * result + expiryMonth.hashCode()
        result = 31 * result + expiryYear.hashCode()
        return result
    }
}

/** Year + month for the expiry check, injected so the store carries no date dependency. */
public data class YearMonth(public val year: Int, public val month: Int)

/** Parse a card expiry year, normalising a 2-digit year (`"30"`) to `2030`. Null if unparseable. */
internal fun normalizeYear(raw: String): Int? {
    val y = raw.trim().toIntOrNull() ?: return null
    return if (y in 0..99) 2000 + y else y
}
