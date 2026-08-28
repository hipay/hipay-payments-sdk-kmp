package com.hipay.card.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Secure Vault tokenization result (FR10) — shape from the REAL stage
 * response (2026-06-12 capture, golden `token_create_response.json`):
 * expiry month/year are strings on the wire, `multi_use` is a number.
 *
 * The `pan` is masked by the backend (`411111xxxxxx1111`); no raw card data
 * ever enters this type (PCI).
 */
@Serializable
public class CardToken(
    @SerialName("token") public val token: String,
    @SerialName("request_id") public val requestId: String? = null,
    @SerialName("card_id") public val cardId: String? = null,
    @SerialName("multi_use") public val multiUse: Int? = null,
    @SerialName("brand") public val brand: String? = null,
    @SerialName("pan") public val pan: String? = null,
    @SerialName("card_holder") public val cardHolder: String? = null,
    @SerialName("card_expiry_month") public val cardExpiryMonth: String? = null,
    @SerialName("card_expiry_year") public val cardExpiryYear: String? = null,
    @SerialName("issuer") public val issuer: String? = null,
    @SerialName("country") public val country: String? = null,
    @SerialName("domestic_network") public val domesticNetwork: String? = null,
    @SerialName("card_type") public val cardType: String? = null,
    @SerialName("card_category") public val cardCategory: String? = null,
    @SerialName("forbidden_issuer_country") public val forbiddenIssuerCountry: Boolean? = null,
) {
    // Deliberately terse: token + brand + backend-masked pan only.
    override fun toString(): String = "CardToken(token=$token, brand=$brand, pan=$pan)"
}
