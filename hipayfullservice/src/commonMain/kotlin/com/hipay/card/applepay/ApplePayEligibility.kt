// PCI: com.hipay.card path — never log here.
package com.hipay.card.applepay

import com.hipay.card.validation.AllowedNetworks
import com.hipay.card.validation.CardNetwork
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayException
import com.hipay.core.gateway.GatewayClient
import kotlin.coroutines.cancellation.CancellationException

/** Whether Apple Pay can be offered right now. */
public enum class ApplePayEligibilityState {
    AVAILABLE,
    UNAVAILABLE,
}

/** Why eligibility resolved the way it did. */
public enum class ApplePayEligibilityReason {
    /** Routable networks exist and the device can pay with at least one of them. */
    AVAILABLE,

    /** No network is routable via Apple Pay for this account (after the merchant restriction). */
    NO_ROUTABLE_NETWORK,

    /** The device cannot pay: Apple Pay is unavailable, or no provisioned card matches a routable
     *  network. */
    DEVICE_NO_USABLE_CARD,
}

/**
 * The outcome of an Apple Pay eligibility evaluation.
 *
 * @property state whether Apple Pay can be offered.
 * @property resolvedNetworks the networks routable via Apple Pay for this account, in a stable
 *   order; empty when nothing is routable.
 * @property reason the classification behind [state].
 */
public class ApplePayEligibilityResult(
    public val state: ApplePayEligibilityState,
    public val resolvedNetworks: List<CardNetwork>,
    public val reason: ApplePayEligibilityReason,
)

/**
 * Device Apple Pay capability — the ONE platform-specific seam in eligibility. commonMain keeps the
 * resolution logic; each platform provides a PassKit-backed implementation (and tests inject a
 * fake). See the iosMain default (`defaultApplePayDeviceCapability`).
 */
public interface ApplePayDeviceCapability {
    /** `PKPaymentAuthorizationController.canMakePayments()` — the device supports Apple Pay at all. */
    public fun canMakePayments(): Boolean

    /** `canMakePayments(usingNetworks:)` — the device holds a usable card of one of [networks]. */
    public fun canMakePayments(networks: List<CardNetwork>): Boolean
}

/**
 * Computes the networks routable via Apple Pay:
 * `[ApplePayNetworks.routable] ∩ accepted ∩ merchantRestriction`, preserving `routable` order. The
 * platform-level `routable` set (Apple ∩ HiPay, Amex excluded) is intersected with the account's
 * accepted cards, then optionally narrowed by the merchant restriction — which can only narrow,
 * never widen (reuses [AllowedNetworks.offered]); an empty restriction keeps the full account set.
 * Pure — no I/O, no device.
 */
internal fun resolveRoutableNetworks(
    accepted: Set<CardNetwork>,
    allowedNetworks: List<CardNetwork>,
): List<CardNetwork> {
    val routable = ApplePayNetworks.routable.filter { it in accepted }
    return AllowedNetworks.offered(routable, allowedNetworks)
}

/**
 * Resolves Apple Pay eligibility for the account + this device.
 *
 * `resolved = routable{Apple ∩ HiPay} ∩ account.acceptedCards ∩ merchantRestriction`, then the
 * device must be able to pay with at least one resolved network. Mirrors the web hosted-fields flow
 * (platform-level routable filter — Amex excluded — narrowed by the account's accepted cards and an
 * optional merchant restriction), adding the device intersection so an unusable device yields
 * `unavailable` + a reason (not just a hidden button).
 *
 * @param currency ISO currency of the intended payment (parameterizes the account query).
 * @param customerCountry optional ISO country; omitted (empty) when null.
 * @param allowedNetworks optional merchant restriction; empty accepts every routable network.
 */
@Throws(HiPayException::class, CancellationException::class)
public suspend fun resolveApplePayEligibility(
    config: HiPayConfig,
    device: ApplePayDeviceCapability,
    currency: String,
    customerCountry: String? = null,
    allowedNetworks: List<CardNetwork> = emptyList(),
): ApplePayEligibilityResult =
    resolveApplePayEligibility(GatewayClient(config), device, currency, customerCountry, allowedNetworks)

@Throws(HiPayException::class, CancellationException::class)
internal suspend fun resolveApplePayEligibility(
    gateway: GatewayClient,
    device: ApplePayDeviceCapability,
    currency: String,
    customerCountry: String? = null,
    allowedNetworks: List<CardNetwork> = emptyList(),
): ApplePayEligibilityResult {
    // The device can't do Apple Pay at all → no point querying the account.
    if (!device.canMakePayments()) {
        return ApplePayEligibilityResult(
            ApplePayEligibilityState.UNAVAILABLE,
            emptyList(),
            ApplePayEligibilityReason.DEVICE_NO_USABLE_CARD,
        )
    }

    val accepted = gateway.getAvailablePaymentProducts(
        paymentProducts = ApplePayNetworks.cardProductCodes,
        currency = currency,
        customerCountry = customerCountry,
    )
    val resolved = resolveRoutableNetworks(accepted, allowedNetworks)

    return when {
        resolved.isEmpty() -> ApplePayEligibilityResult(
            ApplePayEligibilityState.UNAVAILABLE,
            emptyList(),
            ApplePayEligibilityReason.NO_ROUTABLE_NETWORK,
        )
        !device.canMakePayments(resolved) -> ApplePayEligibilityResult(
            ApplePayEligibilityState.UNAVAILABLE,
            resolved,
            ApplePayEligibilityReason.DEVICE_NO_USABLE_CARD,
        )
        else -> ApplePayEligibilityResult(
            ApplePayEligibilityState.AVAILABLE,
            resolved,
            ApplePayEligibilityReason.AVAILABLE,
        )
    }
}
