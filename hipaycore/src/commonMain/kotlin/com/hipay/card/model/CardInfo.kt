package com.hipay.card.model

import com.hipay.card.validation.CardNetwork
import com.hipay.card.validation.CardNetworks
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Card-network resolution result from the Secure Vault (FR: network icons /
 * co-branding). Returned by `CardTokenizer.resolveCardInfo` once the entered
 * number is complete and Luhn-valid — the authoritative source for the brand
 * and any domestic co-brand (CB / BCMC), which local BIN detection cannot give
 * (CB is co-badged Visa/MC and undetectable by prefix).
 *
 * - [brand]: international network ("VISA", "MASTERCARD", "MAESTRO"…).
 * - [domesticNetwork]: local co-brand when present ("cb", "bcmc"), else null.
 */
@Serializable
public class CardInfo(
    @SerialName("brand") public val brand: String? = null,
    @SerialName("domestic_network") public val domesticNetwork: String? = null,
    @SerialName("card_type") public val cardType: String? = null,
    @SerialName("issuer") public val issuer: String? = null,
    @SerialName("country") public val country: String? = null,
) {
    /**
     * Networks to offer, default-selected first: the domestic co-brand wins
     * when present (legacy rule — CB/BCMC selected by default), then the
     * international brand. Empty when neither resolves to a known network.
     */
    public fun resolvedNetworks(): List<CardNetwork> {
        val result = mutableListOf<CardNetwork>()
        CardNetworks.fromApiBrand(domesticNetwork)?.let { result.add(it) }
        CardNetworks.fromApiBrand(brand)?.let { if (it !in result) result.add(it) }
        return result
    }
}
