package com.hipay.card.validation

/**
 * Merchant allowed-networks logic. Pure commonMain
 * logic — no UI, no I/O.
 *
 * The allowed set comes from the merchant's ACCOUNT (the card products it is
 * contracted for, from `GatewayClient.getAvailablePaymentProducts`), narrowed by
 * the integrator's optional restriction. Every card component resolves its
 * allowed list through [effectiveAllowed] and passes THAT to the functions below;
 * the offered set is then the effective list intersected with the
 * backend-resolved networks (`CardInfo.resolvedNetworks()`).
 *
 * CONTRACT — `allowed` distinguishes two things a single list cannot:
 * - **`null` = no restriction at all** (nothing is known to restrict, so
 *   everything the card resolves to is authorized).
 * - **an empty list = a restriction that authorizes NOTHING** (e.g. an account
 *   contracted for no card product).
 *
 * Conflating the two is what let a component accept networks its account could
 * not process, so the distinction is deliberate and must be preserved by every
 * caller.
 *
 * CONTRACT — pass the RESOLVED network to [isAuthorized]/[reason]. While the
 * network is still [CardNetwork.UNKNOWN] (mid-typing, or a locally undetectable
 * co-brand like CB before backend resolution) the card is treated as authorized
 * so the UI never shows a premature "not authorized" before the network is
 * known. `resolvedNetworks()` itself never contains UNKNOWN, so [offered] is
 * unaffected. The same contract holds for the Android (Compose) UI.
 */
public object AllowedNetworks {

    /**
     * The allowed set a card component must actually work from: the networks the ACCOUNT is
     * contracted for, narrowed by the integrator's optional restriction.
     *
     * The account is the ceiling and the integrator can only reduce it — a network the account does
     * not accept is never offered, even when the integrator lists it explicitly, because the gateway
     * would refuse the order anyway.
     *
     * [account] is null when the ceiling is not known: the query has not answered yet, or it failed.
     * Then the integrator's own list is the only restriction — and no restriction at all when that
     * list is empty, which deliberately keeps entry working rather than blocking a payment form on a
     * network hiccup.
     *
     * An EMPTY [account] set is a verdict, not an absence: the account accepts no card, so the
     * result is an empty list — a restriction that authorizes nothing, NOT `null`.
     */
    public fun effectiveAllowed(
        account: Set<CardNetwork>?,
        integrator: List<CardNetwork>,
    ): List<CardNetwork>? = when {
        account == null -> if (integrator.isEmpty()) null else integrator
        integrator.isEmpty() -> account.toList()
        else -> account.filter { it in integrator }
    }

    /** Offered networks = resolved ∩ allowed (order: resolved); `null` allowed → all resolved. */
    public fun offered(resolved: List<CardNetwork>, allowed: List<CardNetwork>?): List<CardNetwork> =
        if (allowed == null) resolved else resolved.filter { it in allowed }

    /** Whether a network is accepted. `null` allowed (or a not-yet-resolved network) → true. */
    public fun isAuthorized(network: CardNetwork, allowed: List<CardNetwork>?): Boolean =
        network == CardNetwork.UNKNOWN || allowed == null || network in allowed

    /** `VALID` when authorized, else `NETWORK_NOT_AUTHORIZED` — for the inline message. */
    public fun reason(network: CardNetwork, allowed: List<CardNetwork>?): ValidationReason =
        if (isAuthorized(network, allowed)) ValidationReason.VALID else ValidationReason.NETWORK_NOT_AUTHORIZED

    /**
     * True when a card whose LOCAL detection is [detected] cannot be ANY of the [allowed]
     * networks — not even through a domestic co-brand the backend might add
     * ([CardNetworks.possibleResolutions]). When this holds, the "not authorized" error is
     * safe to show immediately on local detection, because no backend resolution could
     * rescue the card.
     *
     * `null` [allowed] (no restriction) or a still-[UNKNOWN] prefix → false: nothing to
     * reject yet. An EMPTY list rejects immediately — no resolution can rescue a card on an
     * account that takes none. This is the ONLY situation where the network error is NOT
     * backend-verdict-gated; the AMBIGUOUS co-brand cases — where an
     * allowed domestic network (CB/BCMC) could ride on the detected international BIN, e.g.
     * Visa detected with only CB allowed — stay backend-gated per the contract,
     * so a real co-branded card is never flashed as rejected while typing.
     */
    public fun isLocallyUnauthorized(detected: CardNetwork, allowed: List<CardNetwork>?): Boolean {
        if (allowed == null || detected == CardNetwork.UNKNOWN) return false
        return CardNetworks.possibleResolutions(detected).none { it in allowed }
    }
}
