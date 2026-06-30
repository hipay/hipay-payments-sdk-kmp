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
import com.hipay.core.callback.CallbackUrlParser
import com.hipay.core.gateway.GatewayClient
import com.hipay.core.gateway.model.CustomerInfo
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.Transaction
import com.hipay.core.gateway.model.TransactionState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
    // Platform 3DS presenter (story 11.13): iOS → ASWebAuthenticationSession / external Safari;
    // Android actual is a no-op (the Android CMP controller delegates to native :hipaycard).
    private val threeDSLauncher = CmpThreeDSLauncher()
    // EXTERNAL_BROWSER 3DS: pay() suspends here until resume3DS(url) forwards the app-scheme return.
    private var pendingExternal: CancellableContinuation<String?>? = null

    public var holder: String by mutableStateOf(""); private set
    public var cardNumber: String by mutableStateOf(""); private set
    public var expiry: String by mutableStateOf(""); private set       // RAW digits MMYY (11.8); "/" is a VisualTransformation
    public var cvc: String by mutableStateOf(""); private set

    public var networks: List<CardNetwork> by mutableStateOf(emptyList()); private set
    public var selectedNetwork: CardNetwork? by mutableStateOf(null); private set

    /** True while a [pay] is in flight (set by the SDK, story 11.14). The card UI locks its fields
     *  on this; the host disables its Pay button with `!canPay || isProcessing`. Read-only. */
    public var isProcessing: Boolean by mutableStateOf(false); private set

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
        // Cap to the DETECTED network's complete length (story 11.7): Visa 16 / Amex 15 / etc.,
        // 19 while UNKNOWN so early typing is never blocked. Detect on the new digits.
        val digits = input.filter { it in '0'..'9' }
        cardNumber = digits.take(CardNetworks.completionLength(CardNetworks.detect(digits)))
        recomputeNetworks()
    }

    public fun onExpiryChange(input: String) {
        // Store RAW digits (story 11.8) — the field's ExpiryVisualTransformation renders the "/",
        // so the caret never breaks on the separator.
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
     * exposed (PCI/NFR2). Card fields are cleared after a successful order.
     *
     * 3DS (story 11.13) on a FORWARDING outcome, by [threeDS] mode (iOS):
     * - [HiPayThreeDSMode.IN_APP_SESSION] (default): in-app `ASWebAuthenticationSession`,
     *   self-captures the callback, no host wiring; cancel → reconciled with the server.
     * - [HiPayThreeDSMode.EXTERNAL_BROWSER]: external Safari; `pay()` suspends until the host
     *   forwards the app-scheme return via [resume3DS].
     * Both confirm via `getTransaction` (FR9) and return the FINAL [Transaction].
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
        threeDS: HiPayThreeDSMode = HiPayThreeDSMode.IN_APP_SESSION,
    ): Transaction {
        // Lock the fields for the whole flow (incl. the suspended 3DS); reset on every exit (11.14).
        isProcessing = true
        try {
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
        holder = ""; cardNumber = ""; expiry = ""; cvc = ""
        networks = emptyList(); selectedNetwork = null
        userSelectedNetwork = false; lastDetected = CardNetwork.UNKNOWN
        holderBlurred = false; numberBlurred = false; expiryBlurred = false; cvcBlurred = false

        val forwardUrl = transaction.forwardUrl
        if (transaction.state != TransactionState.FORWARDING || forwardUrl == null) {
            return transaction
        }
        val callbackUrl: String? = when (threeDS) {
            // In-app session self-captures the scheme:// callback. Suspend until it completes;
            // a null callback = user cancelled the sheet → reconcile with the server below (never assume abort).
            HiPayThreeDSMode.IN_APP_SESSION -> suspendCancellableCoroutine { cont ->
                threeDSLauncher.launchInApp(forwardUrl, redirectScheme) { url ->
                    if (cont.isActive) cont.resume(url)
                }
            }
            // External Safari: suspend until the host forwards the app-scheme return via resume3DS,
            // OR until the app returns to the foreground without one = user abort → null (story 11.16).
            HiPayThreeDSMode.EXTERNAL_BROWSER -> suspendCancellableCoroutine { cont ->
                pendingExternal = cont
                cont.invokeOnCancellation { pendingExternal = null; threeDSLauncher.stopExternalWatcher() }
                threeDSLauncher.launchExternal(forwardUrl) {
                    // Foreground return without resume3DS → aborted by the user.
                    pendingExternal?.let { c ->
                        pendingExternal = null
                        threeDSLauncher.stopExternalWatcher()
                        c.resume(null)
                    }
                }
            }
        }
        return if (callbackUrl != null) {
            confirm3DS(callbackUrl, transaction.transactionReference, signature)
        } else {
            // No callback (in-app sheet cancelled / external Safari abandoned). Don't assume an abort —
            // RECONCILE with the authoritative server state (FR9, story 11.16): the user may have
            // validated 3DS without the app receiving the redirect. COMPLETED if captured; FORWARDING if
            // genuinely abandoned; PENDING if the server is unreachable (indeterminate, re-query later).
            reconcileOrPending(transaction.transactionReference, signature)
        }
        } finally {
            isProcessing = false
        }
    }

    /** FR9 confirmation: prefer the captured reference, else the callback's; never trust redirect params. */
    private suspend fun confirm3DS(callbackUrl: String, reference: String?, signature: String?): Transaction {
        val cb = CallbackUrlParser.parse(callbackUrl)
        val ref = reference ?: cb.queryParams["reference"]
        return reconcileOrPending(ref, signature)
    }

    /** FR9 confirmation that never yields a false outcome (story 11.16): query getTransaction for the
     *  authoritative state from [reference]; if we can't confirm — no reference, or the server is
     *  unreachable — return an indeterminate PENDING snapshot ([Transaction.verificationPending]) rather
     *  than a thrown error or a false abort, so the host can re-query later. */
    private suspend fun reconcileOrPending(reference: String?, signature: String?): Transaction {
        if (reference == null) return Transaction.verificationPending(null)
        return try {
            gateway.getTransaction(reference, signature)
        } catch (e: Exception) {
            Transaction.verificationPending(reference)
        }
    }

    /** Forward the 3DS app-scheme return for [HiPayThreeDSMode.EXTERNAL_BROWSER] (iOS host
     *  `.onOpenURL`). Resumes the suspended [pay], which then confirms via `getTransaction`. No-op
     *  for IN_APP_SESSION (self-captures) and when nothing is pending. */
    public fun resume3DS(url: String) {
        val cont = pendingExternal ?: return
        pendingExternal = null
        threeDSLauncher.stopExternalWatcher() // real callback arrived → stop the abort watcher
        cont.resume(url)
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
