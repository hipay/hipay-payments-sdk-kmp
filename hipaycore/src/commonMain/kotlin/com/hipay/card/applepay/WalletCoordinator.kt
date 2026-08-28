// PCI: com.hipay.card path — NEVER log here (paymentData / token must never be logged).
package com.hipay.card.applepay

import com.hipay.card.model.CardToken
import com.hipay.card.validation.CardNetwork
import com.hipay.card.validation.CardNetworks
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.core.gateway.GatewayClient
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.Transaction
import com.hipay.core.http.defaultHttpClientEngine
import com.hipay.core.threeds.ThreeDSLauncher
import com.hipay.core.threeds.ThreeDSResolver
import io.ktor.client.engine.HttpClientEngine
import kotlin.coroutines.cancellation.CancellationException

/**
 * Orchestrates a nominal Apple Pay payment (shared, no UI): wallet-tokenize → order → transaction.
 * The per-channel presentation layer captures the `PKPayment` token and calls [pay]; this engine is
 * platform-agnostic (a later Google Pay wallet can reuse it).
 *
 * Nominal path — biometric = SCA, so `eci=7` + `authentication_indicator=0` and no 3DS challenge;
 * a gateway that answers `forwarding` anyway is a step-up, handled by the caller. When
 * [HiPayApplePayConfig.applePayUsername] is present it routes BOTH the tokenize and the order through
 * that account (fixing the legacy asymmetry).
 */
public class WalletCoordinator internal constructor(
    private val config: HiPayConfig,
    private val engine: HttpClientEngine,
) {
    public constructor(config: HiPayConfig) : this(config, defaultHttpClientEngine())

    /**
     * Runs the payment for an already-authorized Apple Pay token.
     *
     * Submits exactly one order and never retries it: a payment whose outcome is unknown is reported
     * as such and left for the host to reconcile on the same `orderId`, because a silent resubmission
     * is the one way this SDK could authorize twice.
     *
     * @param paymentData the JSON string of `PKPayment.token.paymentData`.
     * @param applePayConfig the merchant Apple Pay config (credentials + optional dedicated account).
     * @param order the merchant order fields; the gateway redirect URLs are derived from
     *   [ApplePayOrder.redirectScheme].
     */
    @Throws(HiPayException::class, CancellationException::class)
    public suspend fun pay(
        paymentData: String,
        applePayConfig: HiPayApplePayConfig,
        order: ApplePayOrder,
    ): Transaction {
        val effectiveConfig = effectiveConfig(applePayConfig)

        val token = ApplePayTokenizer(effectiveConfig, engine)
            .tokenize(paymentData, applePayConfig.privateKeyPassword)

        val base = order.callbackBaseUrl
        val orderRequest = OrderRequest(
            orderId = order.orderId,
            paymentProduct = walletPaymentProduct(token),
            amount = order.amount,
            currency = order.currency,
            description = order.description,
            language = order.language,
            acceptUrl = "$base/accept",
            declineUrl = "$base/decline",
            pendingUrl = "$base/pending",
            exceptionUrl = "$base/exception",
            cancelUrl = "$base/cancel",
            cardToken = token.token,
            eci = 7,
            authenticationIndicator = 0,
        )
        return GatewayClient(effectiveConfig, engine).requestNewOrder(orderRequest, order.signature)
    }

    /**
     * The resolver for an authentication step-up on a wallet payment. Built here so the challenge is
     * confirmed through the SAME account and HTTP engine that created the order — reading the
     * transaction back on the classic account after ordering on the dedicated Apple Pay one would fail
     * to confirm a payment that did go through.
     */
    internal fun threeDSResolver(
        applePayConfig: HiPayApplePayConfig,
        launcher: ThreeDSLauncher,
    ): ThreeDSResolver = ThreeDSResolver(GatewayClient(effectiveConfig(applePayConfig), engine), launcher)

    /**
     * Route tokenize + order through the dedicated Apple Pay account when configured, else the classic
     * account (same credentials on both calls — no legacy asymmetry). A blank username is treated as
     * absent: authenticating as ":password" would only 401 after the customer has already authorized.
     */
    private fun effectiveConfig(applePayConfig: HiPayApplePayConfig): HiPayConfig =
        applePayConfig.applePayUsername
            ?.takeIf { it.isNotBlank() }
            ?.let { HiPayConfig(it, config.password, config.environment, config.settings) }
            ?: config
}

/**
 * The `payment_product` for a wallet order, derived from the token's **resolved brand**: prefer a
 * known domestic co-brand (`domestic_network`, e.g. CB/BCMC) over the international brand, mirroring
 * the web hosted-fields tokenize response. Only the one resolved network is transmitted; the
 * non-selected co-brand is not.
 *
 * A brand the SDK does not map is passed through as-is (trimmed, lowercased) rather than replaced by
 * a default. Unlike card entry — where an unrecognized brand is only a local UI guess — here the
 * brand is what the Secure Vault resolved for the wallet token, so substituting another network would
 * misdeclare the instrument to the gateway; letting the gateway reject an unknown code is the
 * truthful outcome. A response carrying no brand at all is unusable and fails.
 */
@Throws(HiPayException::class, CancellationException::class)
internal fun walletPaymentProduct(token: CardToken): String {
    val resolved = token.domesticNetwork?.takeIf { CardNetworks.fromApiBrand(it) != null }
        ?: token.brand
    return when (CardNetworks.fromApiBrand(resolved)) {
        CardNetwork.VISA -> "visa"
        CardNetwork.MASTERCARD -> "mastercard"
        CardNetwork.AMEX -> "american-express"
        CardNetwork.MAESTRO -> "maestro"
        CardNetwork.CB -> "cb"
        CardNetwork.BCMC -> "bcmc"
        CardNetwork.UNKNOWN, null -> resolved?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: throw HiPayException(
                code = HiPayErrorCode.SERVER,
                message = "Secure Vault returned no card brand for the wallet token",
            )
    }
}
