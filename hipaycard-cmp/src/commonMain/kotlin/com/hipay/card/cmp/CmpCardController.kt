// PCI (NFR2): com.hipay.card path — NEVER log here, never expose the raw PAN or token.
package com.hipay.card.cmp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hipay.card.CardTokenizer
import com.hipay.card.validation.AllowedNetworks
import com.hipay.card.validation.CardEntryStringKey
import com.hipay.card.validation.CardFieldValidation
import com.hipay.card.validation.CardNetwork
import com.hipay.card.validation.CardNetworks
import com.hipay.card.validation.CardValidators
import com.hipay.card.validation.ValidationReason
import com.hipay.card.validation.messageKey
import com.hipay.core.HiPayConfig
import com.hipay.core.gateway.GatewayClient
import com.hipay.core.gateway.model.CustomerInfo
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.Transaction

/**
 * Compose-Multiplatform state holder for the shared card-entry UI (story 10.2, slice A) —
 * the commonMain mirror of the Android `HiPayCardEntryController`. All validation/network
 * logic comes from the frozen commonMain contract (`com.hipay.card.validation.*`); this only
 * orchestrates it and drives tokenization/payment via `CardTokenizer`/`GatewayClient`.
 *
 * Uses the shared [CardNetwork] directly (no Android `HiPayCardNetwork`). The raw PAN never
 * leaves the holder and the vault token is consumed internally by [pay] (PCI/NFR2).
 *
 * Slice-A scope: LOCAL network detection only. The async backend co-branding refinement
 * (`resolveCardInfo`) is intentionally deferred (it needs a multiplatform clock + scope) —
 * tracked for slice B / a follow-up; chips here come from local BIN detection.
 */
public class CmpCardController(
    config: HiPayConfig,
    private val allowed: List<CardNetwork> = emptyList(),
) {
    public enum class Field { HOLDER, NUMBER, EXPIRY, CVC }

    private val tokenizer = CardTokenizer(config)
    private val gateway = GatewayClient(config)

    public var holder: String by mutableStateOf(""); private set
    public var cardNumber: String by mutableStateOf(""); private set
    public var expiry: String by mutableStateOf(""); private set
    public var cvc: String by mutableStateOf(""); private set

    public var networks: List<CardNetwork> by mutableStateOf(emptyList()); private set
    public var selectedNetwork: CardNetwork? by mutableStateOf(null); private set

    public var holderBlurred: Boolean by mutableStateOf(false); private set
    public var numberBlurred: Boolean by mutableStateOf(false); private set
    public var expiryBlurred: Boolean by mutableStateOf(false); private set
    public var cvcBlurred: Boolean by mutableStateOf(false); private set

    private var userSelectedNetwork = false
    private var lastDetected: CardNetwork = CardNetwork.UNKNOWN

    private val panDigits: String get() = cardNumber.filter { it in '0'..'9' }
    private val expiryDigits: String get() = expiry.filter { it in '0'..'9' }
    private val expiryMonth: String get() = expiryDigits.take(2)
    private val expiryYear: String get() = if (expiryDigits.length >= 4) "20" + expiryDigits.substring(2, 4) else ""

    /** Selected co-brand if any, else the locally detected network. */
    public val network: CardNetwork
        get() = selectedNetwork ?: CardNetworks.detect(panDigits)

    // Co-brand aware (story 11.5): a mono Maestro requires a CVC, a co-branded one does not.
    public val isCvcRequired: Boolean get() = CardNetworks.isCvcRequired(network, networks)
    public val cvcMaxLength: Int get() = CardNetworks.cvcLength(network)
    public val isNumberComplete: Boolean get() = CardNetworks.isNumberComplete(panDigits)
    public val isExpiryComplete: Boolean get() = expiryDigits.length == 4
    public val isCvcComplete: Boolean get() = !isCvcRequired || cvc.length == cvcMaxLength
    public val isNetworkAuthorized: Boolean get() = AllowedNetworks.isAuthorized(network, allowed)

    public val canPay: Boolean
        get() = holder.isNotBlank() &&
            CardValidators.isHolderValid(holder) &&
            CardValidators.isCardNumberValid(panDigits) &&
            CardValidators.isExpiryDateValid(expiryMonth, expiryYear) &&
            isCvcComplete &&
            isNetworkAuthorized

    public val firstInvalidField: Field?
        get() = when {
            CardFieldValidation.holderReason(holder) != ValidationReason.VALID -> Field.HOLDER
            !CardValidators.isCardNumberValid(panDigits) -> Field.NUMBER
            !CardValidators.isExpiryDateValid(expiryMonth, expiryYear) -> Field.EXPIRY
            !isCvcComplete -> Field.CVC
            else -> null
        }

    public val holderErrorKey: CardEntryStringKey?
        get() = if (holderBlurred) CardFieldValidation.holderReason(holder).messageKey() else null

    public val expiryErrorKey: CardEntryStringKey?
        get() = if (expiryBlurred) CardFieldValidation.expiryReason(expiryMonth, expiryYear).messageKey() else null

    public val cvcErrorKey: CardEntryStringKey?
        get() = if (cvcBlurred) CardFieldValidation.cvcReason(cvc, network, networks).messageKey() else null

    private val numberErrorKey: CardEntryStringKey?
        get() = if (numberBlurred) CardFieldValidation.cardNumberReason(panDigits).messageKey() else null

    private val networkErrorKey: CardEntryStringKey?
        get() = if (numberBlurred && network != CardNetwork.UNKNOWN && !isNetworkAuthorized)
            AllowedNetworks.reason(network, allowed).messageKey() else null

    /** Number-slot error: network-not-authorized takes precedence over the number's own error (D1). */
    public val numberSlotErrorKey: CardEntryStringKey?
        get() = networkErrorKey ?: numberErrorKey

    public fun onHolderChange(input: String) {
        holder = input.uppercase().take(60)
    }

    public fun onNumberChange(input: String) {
        // Store RAW digits (story 11.1) — the field's VisualTransformation renders the spaces,
        // so the caret never breaks on a grouping space.
        cardNumber = input.filter { it in '0'..'9' }.take(19)
        recomputeNetworks()
    }

    public fun onExpiryChange(input: String) {
        val deleting = input.length < expiry.length
        val digits = input.filter { it in '0'..'9' }.take(4)
        expiry = when {
            digits.length < 2 -> digits
            digits.length == 2 -> if (deleting) digits else "$digits/"
            else -> digits.substring(0, 2) + "/" + digits.substring(2)
        }
    }

    public fun onCvcChange(input: String) {
        cvc = input.filter { it in '0'..'9' }.take(cvcMaxLength)
    }

    public fun markBlurred(field: Field) {
        when (field) {
            Field.HOLDER -> holderBlurred = true
            Field.NUMBER -> numberBlurred = true
            Field.EXPIRY -> expiryBlurred = true
            Field.CVC -> cvcBlurred = true
        }
    }

    public fun revealErrors() {
        holderBlurred = true; numberBlurred = true; expiryBlurred = true; cvcBlurred = true
    }

    public fun selectNetwork(net: CardNetwork) {
        if (net in networks) {
            selectedNetwork = net
            userSelectedNetwork = true
        }
    }

    private fun recomputeNetworks() {
        val detected = CardNetworks.detect(panDigits)
        if (detected != lastDetected) {
            lastDetected = detected
            // Clear a stale CVC when the network (hence its CVC policy) changes. Single-arg = mono
            // (a bare detected network); TRANSIENT cap/clear — `applyOffered` below is the
            // AUTHORITATIVE co-brand-aware clear. Not the CVC policy source (story 11.5 review).
            cvc = if (!CardNetworks.isCvcRequired(detected)) "" else cvc.take(CardNetworks.cvcLength(detected))
        }
        // Local offered set (backend refinement deferred — slice A).
        val offered = AllowedNetworks.offered(listOfNotNull(detected.takeIf { it != CardNetwork.UNKNOWN }), allowed)
        applyOffered(offered)
    }

    private fun applyOffered(offered: List<CardNetwork>) {
        networks = offered
        selectedNetwork = when {
            userSelectedNetwork && selectedNetwork in offered -> selectedNetwork
            else -> offered.firstOrNull()
        }
        if (selectedNetwork !in offered) userSelectedNetwork = false
        cvc = if (isCvcRequired) cvc.take(cvcMaxLength) else ""
    }

    /**
     * Tokenizes the card and creates the order. The vault token is consumed here and never
     * exposed (PCI/NFR2). Card fields are cleared after a successful order. Returns the
     * [Transaction]; a FORWARDING state carries `forwardUrl` for 3DS (host-handled).
     */
    public suspend fun pay(
        orderId: String,
        amount: String,
        currency: String = "EUR",
        description: String,
        language: String = "en_GB",
        redirectScheme: String,
        authenticationIndicator: Int = 0,
        signature: String? = null,
        customer: CustomerInfo? = null,
        shipping: CustomerInfo? = null,
    ): Transaction {
        val product = network.productCode()
        val token = tokenizer.generateToken(
            cardNumber = panDigits,
            expiryMonth = expiryMonth,
            expiryYear = expiryYear,
            holder = holder,
            cvc = if (isCvcRequired) cvc else "",
            multiUse = false,
        )
        val base = "$redirectScheme://hipay-fullservice/gateway/orders/$orderId"
        val order = OrderRequest(
            orderId = orderId,
            paymentProduct = product,
            amount = amount,
            description = description,
            acceptUrl = "$base/accept",
            declineUrl = "$base/decline",
            pendingUrl = "$base/pending",
            exceptionUrl = "$base/exception",
            cancelUrl = "$base/cancel",
            currency = currency,
            language = language,
            customer = customer,
            shippingAddress = shipping,
            cardToken = token.token,
            eci = 7,
            authenticationIndicator = authenticationIndicator,
        )
        val transaction = gateway.requestNewOrder(order, signature)
        // Clear sensitive/derived state after a successful order (parity with :hipaycard).
        holder = ""; cardNumber = ""; cvc = ""
        networks = emptyList(); selectedNetwork = null
        userSelectedNetwork = false; lastDetected = CardNetwork.UNKNOWN
        holderBlurred = false; numberBlurred = false; expiryBlurred = false; cvcBlurred = false
        return transaction
    }

    /** No owned coroutine scope (slice A: synchronous local detection); kept for API parity. */
    public fun dispose() {}
}

/** Wire payment_product code for the order (mirrors the Android HiPayCardNetwork codes). */
internal fun CardNetwork.productCode(): String = when (this) {
    CardNetwork.VISA -> "visa"
    CardNetwork.MASTERCARD -> "mastercard"
    CardNetwork.AMEX -> "american-express"
    CardNetwork.MAESTRO -> "maestro"
    CardNetwork.CB -> "cb"
    CardNetwork.BCMC -> "bcmc"
    CardNetwork.UNKNOWN -> "visa"
}
