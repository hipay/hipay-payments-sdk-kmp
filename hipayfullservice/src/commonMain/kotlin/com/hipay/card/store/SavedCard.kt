// PCI: com.hipay.card path — NEVER log here, never expose the raw PAN or token.
package com.hipay.card.store

import kotlinx.serialization.Serializable

/**
 * A card the user chose to save for one-click payment.
 *
 * Holds ONLY what one-click needs: the multi-use Secure Vault [token] (the payment
 * credential), a backend-MASKED pan (BIN6 + last4, e.g. "411111xxxxxx1111"), and the
 * display network/holder/expiry. The raw PAN and the CVV are NEVER stored (NFR2/NFR8).
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
    /** Identity for dedup/overwrite: masked PAN + expiry — never the token, never last4 alone. */
    internal val identity: String get() = "$maskedPan|$expiryMonth|$expiryYear"

    // Never expose the token — mirror HiPayConfig/CardToken terseness.
    override fun toString(): String =
        "SavedCard(maskedPan=$maskedPan, network=$network, exp=$expiryMonth/$expiryYear)"
}

/** Year + month for the expiry check, injected so the store carries no date dependency (NFR5). */
public data class YearMonth(public val year: Int, public val month: Int)
