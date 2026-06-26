// PCI (NFR2): this module is on the com.hipay.card anti-logging path — NEVER log
// here, and never expose the raw PAN or the vault token on the public surface.
package com.hipay.card

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hipay.card.validation.CardEntryStringKey
import com.hipay.card.validation.CardFieldValidation
import com.hipay.card.validation.CardNetwork
import com.hipay.card.validation.messageKey
import com.hipay.card.validation.CardNetworks
import com.hipay.card.validation.CardValidators
import com.hipay.card.validation.ValidationReason
import com.hipay.card.validation.AllowedNetworks
import com.hipay.core.HiPayConfig
import com.hipay.core.gateway.GatewayClient
import com.hipay.core.gateway.model.CustomerInfo
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * State holder for the Android Compose card-entry component — the behavioral
 * mirror of the iOS `HiPayCardEntryController` (a plain observable, NOT a
 * ViewModel, so it embeds in any host: a Compose screen or an XML/Fragment host
 * via `ComposeView`). All card-entry logic lives in the shared commonMain
 * contract (`com.hipay.card.validation.*`); this class only orchestrates it and
 * drives tokenization/payment via the KMP `CardTokenizer`/`GatewayClient`.
 *
 * The raw PAN never leaves the component and the vault token is consumed
 * internally by [pay] — neither is exposed on the public surface (PCI/NFR2),
 * mirroring iOS.
 *
 * @param scope optional host scope for the async network-resolution; when null
 *   the controller owns one (cancel it with [dispose], e.g. from a Composable
 *   `DisposableEffect`).
 */
public class HiPayCardEntryController(
    config: HiPayConfig,
    allowedNetworks: List<HiPayCardNetwork> = emptyList(),
    scope: CoroutineScope? = null,
) {
    /** Field identifiers (for blur tracking / first-invalid focus — error UI is story 7.4). */
    public enum class Field { HOLDER, NUMBER, EXPIRY, CVC }

    private val tokenizer = CardTokenizer(config)
    private val gateway = GatewayClient(config)
    private val allowedKmp: List<CardNetwork> = allowedNetworks.map { it.kmpNetwork }

    private val ownsScope = scope == null
    private val scope: CoroutineScope =
        scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- Editable state (Compose snapshot state; mutated only via the on*Change handlers) ----
    public var holder: String by mutableStateOf(""); private set
    public var cardNumber: String by mutableStateOf(""); private set   // RAW digits (11.1); spacing is a VisualTransformation
    public var expiry: String by mutableStateOf(""); private set       // RAW digits MMYY (11.8); "/" is a VisualTransformation
    public var cvc: String by mutableStateOf(""); private set

    // ---- Network state ----
    public var networks: List<HiPayCardNetwork> by mutableStateOf(emptyList()); private set
    public var selectedNetwork: HiPayCardNetwork? by mutableStateOf(null); private set

    // ---- Blur state (consumed by the 7.4 inline-error UI; exposed now, no UI here) ----
    public var holderBlurred: Boolean by mutableStateOf(false); private set
    public var numberBlurred: Boolean by mutableStateOf(false); private set
    public var expiryBlurred: Boolean by mutableStateOf(false); private set
    public var cvcBlurred: Boolean by mutableStateOf(false); private set

    private var userSelectedNetwork = false
    private var lastResolvedDigits: String? = null
    private var lastDetected: CardNetwork = CardNetwork.UNKNOWN

    // ---- Derived rules (all from the shared contract — no reimplementation) ----
    private val panDigits: String get() = cardNumber.filter { it in '0'..'9' }
    private val expiryDigits: String get() = expiry.filter { it in '0'..'9' }
    private val expiryMonth: String get() = expiryDigits.take(2)
    private val expiryYear: String get() = if (expiryDigits.length >= 4) "20" + expiryDigits.substring(2, 4) else ""

    /** Selected co-brand if any, else the locally detected network. */
    public val network: CardNetwork
        get() = selectedNetwork?.kmpNetwork ?: CardNetworks.detect(panDigits)

    // Co-brand aware (story 11.5): a mono Maestro requires a CVC, a co-branded one does not.
    public val isCvcRequired: Boolean get() = CardNetworks.isCvcRequired(network, networks.map { it.kmpNetwork })
    public val cvcMaxLength: Int get() = CardNetworks.cvcLength(network)
    public val isNumberComplete: Boolean get() = CardNetworks.isNumberComplete(panDigits)
    public val isExpiryComplete: Boolean get() = expiryDigits.length == 4
    public val isCvcComplete: Boolean get() = !isCvcRequired || cvc.length == cvcMaxLength
    public val isNetworkAuthorized: Boolean get() = AllowedNetworks.isAuthorized(network, allowedKmp)

    /** True when the host's Pay action may proceed (inline error rendering is story 7.4). */
    public val canPay: Boolean
        get() = holder.isNotBlank() &&
            CardValidators.isHolderValid(holder) &&
            CardValidators.isCardNumberValid(panDigits) &&
            CardValidators.isExpiryDateValid(expiryMonth, expiryYear) &&
            isCvcComplete &&
            isNetworkAuthorized

    /** First field (holder→number→expiry→cvc) currently failing — for the host's focus-to-error. */
    public val firstInvalidField: Field?
        get() = when {
            CardFieldValidation.holderReason(holder) != ValidationReason.VALID -> Field.HOLDER
            !CardValidators.isCardNumberValid(panDigits) -> Field.NUMBER
            !CardValidators.isExpiryDateValid(expiryMonth, expiryYear) -> Field.EXPIRY
            !isCvcComplete -> Field.CVC
            else -> null
        }

    // ---- Inline error message KEYS (story 7.4) — the Composable localizes via cardString.
    // Shown only after the field has blurred (or revealErrors()); value-free (PCI). ----
    public val holderErrorKey: CardEntryStringKey?
        get() = if (holderBlurred) CardFieldValidation.holderReason(holder).messageKey() else null

    public val expiryErrorKey: CardEntryStringKey?
        get() = if (expiryBlurred) CardFieldValidation.expiryReason(expiryMonth, expiryYear).messageKey() else null

    public val cvcErrorKey: CardEntryStringKey?
        get() = if (cvcBlurred) {
            CardFieldValidation.cvcReason(cvc, network, networks.map { it.kmpNetwork }).messageKey()
        } else {
            null
        }

    private val numberErrorKey: CardEntryStringKey?
        get() = if (numberBlurred) CardFieldValidation.cardNumberReason(panDigits).messageKey() else null

    private val networkErrorKey: CardEntryStringKey?
        get() = if (numberBlurred && network != CardNetwork.UNKNOWN && !isNetworkAuthorized)
            AllowedNetworks.reason(network, allowedKmp).messageKey() else null

    /** Number-field slot error: network-not-authorized takes precedence over the number's own error (D1). */
    public val numberSlotErrorKey: CardEntryStringKey?
        get() = networkErrorKey ?: numberErrorKey

    // ---- Field handlers (called from the Composable onValueChange) ----
    public fun onHolderChange(input: String) {
        holder = input.uppercase().take(60)
    }

    public fun onNumberChange(input: String) {
        // Store RAW digits (story 11.1) — HiPayCardEntry renders the spaces via a
        // VisualTransformation, so the caret never breaks on a grouping space.
        // Cap to the DETECTED network's complete length (story 11.7): Visa 16 / Amex 15 / etc.,
        // 19 while UNKNOWN so early typing is never blocked. Detect on the new digits.
        val digits = input.filter { it in '0'..'9' }
        cardNumber = digits.take(CardNetworks.completionLength(CardNetworks.detect(digits)))
        recomputeNetworks()
    }

    public fun onExpiryChange(input: String) {
        // Store RAW digits (story 11.8) — HiPayCardEntry renders the "/" via an
        // ExpiryVisualTransformation, so the caret never breaks on the separator.
        expiry = input.filter { it in '0'..'9' }.take(4)
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

    /** Reveal all inline errors (host calls on an explicit submit). The 7.4 UI renders them. */
    public fun revealErrors() {
        holderBlurred = true; numberBlurred = true; expiryBlurred = true; cvcBlurred = true
    }

    /** Select a co-brand chip; ignored if not in the offered set. Preserved across refinement. */
    public fun selectNetwork(net: HiPayCardNetwork) {
        if (net in networks) {
            selectedNetwork = net
            userSelectedNetwork = true
        }
    }

    private fun recomputeNetworks() {
        val digits = panDigits
        val detected = CardNetworks.detect(digits)
        if (detected != lastDetected) {
            lastDetected = detected
            // Clear a stale CVC when the network (hence its CVC policy) changes. Single-arg = mono
            // (a bare detected network); this is a TRANSIENT cap/clear — `applyOffered` below is the
            // AUTHORITATIVE co-brand-aware clear (it re-evaluates against the offered set). Don't
            // treat this line as the CVC policy source (story 11.5 review).
            if (!CardNetworks.isCvcRequired(detected)) cvc = ""
            else cvc = cvc.take(CardNetworks.cvcLength(detected))
        }
        val localOffered = AllowedNetworks
            .offered(listOfNotNull(HiPayCardNetwork.from(detected)?.kmpNetwork), allowedKmp)
            .mapNotNull { HiPayCardNetwork.from(it) }
        applyOffered(localOffered)

        if (CardValidators.isCardNumberValid(digits) && digits != lastResolvedDigits) {
            lastResolvedDigits = digits
            scope.launch { resolve(digits) }
        } else if (!CardValidators.isCardNumberValid(digits)) {
            lastResolvedDigits = null
        }
    }

    private suspend fun resolve(digits: String) {
        try {
            val info = tokenizer.resolveCardInfo(digits, "12", nextYear())
            if (digits != panDigits) return // user kept typing — drop the stale result
            val offered = AllowedNetworks.offered(info.resolvedNetworks(), allowedKmp)
                .mapNotNull { HiPayCardNetwork.from(it) }
            if (offered.isNotEmpty()) applyOffered(offered)
        } catch (e: Exception) {
            // Degrade: keep the locally detected icon; allow a retry on the next edit.
            if (digits == panDigits) lastResolvedDigits = null
        }
    }

    private fun applyOffered(offered: List<HiPayCardNetwork>) {
        networks = offered
        selectedNetwork = when {
            userSelectedNetwork && selectedNetwork in offered -> selectedNetwork
            else -> offered.firstOrNull()
        }
        if (selectedNetwork !in offered) userSelectedNetwork = false
        // The effective network (selected co-brand) may differ from the locally detected one
        // and change the CVC policy (e.g. backend resolves CB/BCMC → no CVC). Re-cap / clear a
        // now-stale CVC against the effective network (code-review 7.2, AC#4 parity with iOS).
        cvc = if (isCvcRequired) cvc.take(cvcMaxLength) else ""
    }

    /**
     * Tokenizes the card and creates the order. The vault token is a local value
     * consumed here — it is NEVER stored on this controller or returned to the
     * host (mirrors iOS `pay()`). Card fields are cleared after tokenizing.
     *
     * Returns the KMP [Transaction]; a `FORWARDING` state carries `forwardUrl`
     * for the 3DS redirect (handled by the host/demo — story 7.5).
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
        val product = selectedNetwork?.paymentProductCode ?: "visa"
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
        // Clear sensitive/derived state after a successful order (code-review 7.2): PAN, CVC,
        // the cardholder name (PII), networks, and the blur flags so a reused controller does
        // not show stale errors against now-empty fields.
        holder = ""
        cardNumber = ""
        expiry = ""
        cvc = ""
        networks = emptyList()
        selectedNetwork = null
        lastResolvedDigits = null
        userSelectedNetwork = false
        lastDetected = CardNetwork.UNKNOWN
        holderBlurred = false
        numberBlurred = false
        expiryBlurred = false
        cvcBlurred = false
        return transaction
    }

    /** Cancel the owned coroutine scope. No-op if the host supplied its own scope. */
    public fun dispose() {
        if (ownsScope) scope.cancel()
    }

    private fun nextYear(): String = (Calendar.getInstance().get(Calendar.YEAR) + 1).toString()
}
