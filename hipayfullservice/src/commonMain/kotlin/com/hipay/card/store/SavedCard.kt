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
     * Identity for dedup/overwrite: normalised masked PAN + expiry — never the token, never last4 alone.
     * The masked PAN is reduced to its `[0-9x]` characters (case-insensitive) so a cosmetic re-mask of
     * the same card (spacing, uppercase X, differing BIN length) still matches; month/year are parsed
     * to numbers (2-digit years normalised to 20xx) so `"30"` and `"2030"` are the same card. The parts
     * are numeric-only, so the `|` delimiter is injection-safe.
     */
    internal val identity: String
        get() {
            val pan = maskedPan.lowercase().filter { it in '0'..'9' || it == 'x' }
            val mm = expiryMonth.trim().toIntOrNull() ?: -1
            val yyyy = normalizeYear(expiryYear) ?: -1
            return "$pan|$mm|$yyyy"
        }

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
