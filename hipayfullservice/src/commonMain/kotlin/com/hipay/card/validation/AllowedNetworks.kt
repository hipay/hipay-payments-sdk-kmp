package com.hipay.card.validation

/**
 * Merchant allowed-networks logic (FR24, architecture D13). Pure commonMain
 * logic — no UI, no I/O. The integrator optionally supplies the networks they
 * accept; the offered set is that list intersected with the backend-resolved
 * networks (`CardInfo.resolvedNetworks()`). An empty/absent allowed list means
 * "accept everything the backend resolves".
 */
public object AllowedNetworks {

    /** Offered networks = resolved ∩ allowed (order: resolved); empty allowed → all resolved. */
    public fun offered(resolved: List<CardNetwork>, allowed: List<CardNetwork>): List<CardNetwork> =
        if (allowed.isEmpty()) resolved else resolved.filter { it in allowed }

    /** Whether a network is accepted by the merchant. Empty allowed → always true. */
    public fun isAuthorized(network: CardNetwork, allowed: List<CardNetwork>): Boolean =
        allowed.isEmpty() || network in allowed

    /** `VALID` when authorized, else `NETWORK_NOT_AUTHORIZED` — for the inline message. */
    public fun reason(network: CardNetwork, allowed: List<CardNetwork>): ValidationReason =
        if (isAuthorized(network, allowed)) ValidationReason.VALID else ValidationReason.NETWORK_NOT_AUTHORIZED
}
