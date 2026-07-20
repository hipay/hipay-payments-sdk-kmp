package com.hipay.card.validation

/**
 * Merchant allowed-networks logic (FR24, architecture D13). Pure commonMain
 * logic — no UI, no I/O. The integrator optionally supplies the networks they
 * accept; the offered set is that list intersected with the backend-resolved
 * networks (`CardInfo.resolvedNetworks()`). An empty/absent allowed list means
 * "accept everything the backend resolves".
 *
 * CONTRACT — pass the RESOLVED network to [isAuthorized]/[reason]. While the
 * network is still [CardNetwork.UNKNOWN] (mid-typing, or a locally undetectable
 * co-brand like CB before backend resolution) the card is treated as authorized
 * so the UI never shows a premature "not authorized" before the network is
 * known. `resolvedNetworks()` itself never contains UNKNOWN, so [offered] is
 * unaffected. The same contract holds for the Android (Compose) UI.
 */
public object AllowedNetworks {

    /** Offered networks = resolved ∩ allowed (order: resolved); empty allowed → all resolved. */
    public fun offered(resolved: List<CardNetwork>, allowed: List<CardNetwork>): List<CardNetwork> =
        if (allowed.isEmpty()) resolved else resolved.filter { it in allowed }

    /** Whether a network is accepted by the merchant. Empty allowed (or not-yet-resolved network) → true. */
    public fun isAuthorized(network: CardNetwork, allowed: List<CardNetwork>): Boolean =
        network == CardNetwork.UNKNOWN || allowed.isEmpty() || network in allowed

    /** `VALID` when authorized, else `NETWORK_NOT_AUTHORIZED` — for the inline message. */
    public fun reason(network: CardNetwork, allowed: List<CardNetwork>): ValidationReason =
        if (isAuthorized(network, allowed)) ValidationReason.VALID else ValidationReason.NETWORK_NOT_AUTHORIZED

    /**
     * True when a card whose LOCAL detection is [detected] cannot be ANY of the [allowed]
     * networks — not even through a domestic co-brand the backend might add
     * ([CardNetworks.possibleResolutions]). When this holds, the "not authorized" error is
     * safe to show immediately on local detection, because no backend resolution could
     * rescue the card.
     *
     * Empty [allowed] (accept everything) or a still-[UNKNOWN] prefix → false: nothing to
     * reject yet. This is the ONLY situation where the network error is NOT
     * backend-verdict-gated; the AMBIGUOUS co-brand cases — where an
     * allowed domestic network (CB/BCMC) could ride on the detected international BIN, e.g.
     * Visa detected with only CB allowed — stay backend-gated per the contract,
     * so a real co-branded card is never flashed as rejected while typing.
     */
    public fun isLocallyUnauthorized(detected: CardNetwork, allowed: List<CardNetwork>): Boolean {
        if (allowed.isEmpty() || detected == CardNetwork.UNKNOWN) return false
        return CardNetworks.possibleResolutions(detected).none { it in allowed }
    }
}
