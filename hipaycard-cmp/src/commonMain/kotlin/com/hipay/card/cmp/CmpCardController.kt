// PCI (NFR2): com.hipay.card path — NEVER log here, never expose the raw PAN or token.
package com.hipay.card.cmp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hipay.card.CardTokenizer
import com.hipay.card.model.CardInfo
import com.hipay.card.model.CardToken
import com.hipay.card.store.OneClickError
import com.hipay.card.store.OneClickErrorReason
import com.hipay.card.store.SavedCard
import com.hipay.card.store.SavedCardOutcome
import com.hipay.card.store.SecureCardStore
import com.hipay.card.store.cardNoLongerValidOrNull
import com.hipay.card.store.oneClickReasonForOutcome
import com.hipay.card.store.savedCardExpiredNow
import com.hipay.card.store.savedCardFromToken
import com.hipay.card.store.savedCardPaymentProduct
import com.hipay.card.validation.AllowedNetworks
import com.hipay.card.validation.CardEntryStringKey
import com.hipay.card.validation.CardFieldValidation
import com.hipay.card.validation.CardNetwork
import com.hipay.card.validation.CardNetworks
import com.hipay.card.validation.CardValidators
import com.hipay.card.validation.ValidationReason
import com.hipay.card.validation.messageKey
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.core.callback.CallbackUrlParser
import com.hipay.core.gateway.GatewayClient
import com.hipay.core.gateway.model.CustomerInfo
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.Transaction
import com.hipay.core.gateway.model.TransactionState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
 * Network detection is two-stage: local BIN detection drives the trailing icon per keystroke,
 * then — once the number is complete and Luhn-valid — the backend `resolveCardInfo` verdict
 * refines the offered set with any domestic co-brand (CB/BCMC) that local detection cannot see,
 * so a co-branded card offers BOTH networks, CB/BCMC default-selected (parity with the native
 * Android/iOS components, fixed in 0.3.0). Failures degrade to the local icon and never block
 * entry.
 */
public class CmpCardController(
    private val config: HiPayConfig,
    private val allowed: List<CardNetwork> = emptyList(),
    /** Explicit integrator opt-in for the one-click (saved cards) UI — off by default: without it
     *  the component renders and behaves exactly as before and no card store is ever created.
     *  Headless-host note: once [refreshSavedCards] has pre-selected a saved card, a plain [pay]
     *  call routes to that stored token (no CVV) — call [selectNewCard] first to force card entry. */
    public val oneClickEnabled: Boolean = false,
    /** Optional host scope for the async backend network resolution (@since 0.3.0); when null
     *  the controller owns one (main-immediate) and cancels it in [dispose]. */
    scope: CoroutineScope? = null,
) {
    public enum class Field { HOLDER, NUMBER, EXPIRY, CVC }

    // The owned scope is created LAZILY (on the first resolve): construction must not touch
    // Dispatchers.Main — absent on the host-test JVM, and needless until a resolve fires.
    // Once disposed the owned scope is never re-created (a launch after dispose would otherwise
    // leak a fresh, never-cancelled SupervisorJob); the host scope, if supplied, is left as-is.
    private val hostScope = scope
    private var ownedScope: CoroutineScope? = null
    private var disposed = false
    private val scope: CoroutineScope
        get() = hostScope
            ?: ownedScope
            ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { ownedScope = it }

    private val tokenizer = CardTokenizer(config)
    private val gateway = GatewayClient(config)

    // ---- Saved cards (one-click) ----
    // One store instance per controller, EVERY access (creation included) confined to this
    // single-thread dispatcher: the store is not thread-safe. Default (not IO — unavailable in
    // common code) is fine here: the iOS Keychain primitive is a fast synchronous call, and
    // Android never instantiates this controller.
    private val storeDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var store: SecureCardStore? = null

    private suspend fun <T> withStore(block: (SecureCardStore) -> T): T =
        withContext(storeDispatcher) {
            block(store ?: createCmpSecureCardStore(config).also { store = it })
        }
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

    /**
     * Result of the most recent `pay(saveCard = true)` save attempt, for the host to react to
     * (e.g. a confirmation or a "card not saved" notice). Null when no save was attempted — a
     * fresh `pay(saveCard = true)` resets it, and it stays null when the payment does not complete.
     */
    public var lastSaveOutcome: SavedCardOutcome? by mutableStateOf(null); private set

    /**
     * The most recent one-click failure, as a transient observable outcome (the sibling of
     * [lastSaveOutcome] for the pay path): the affected card's masked identity plus a reason.
     * Set inside [payWithSavedCard] — the call still throws/returns exactly as before; this is
     * additive. Cleared at the start of the next attempt, on any selection change, on a new-card
     * field edit, and by a [refreshSavedCards] that no longer lists the affected card. The
     * component renders it via the shared `oneClickErrorSurface` policy; hosts may read it too.
     * Setter internal (not private): the in-module test harness drives outcomes the tests cannot
     * obtain without a gateway stub (declined / token-invalid); never written by the component.
     */
    public var lastOneClickError: OneClickError? by mutableStateOf(null); internal set

    // ---- One-click UI state (rendered by the card entry only when oneClickEnabled) ----

    /** The saved cards offered for one-click, most recently used/saved first (expired cards
     *  purged); empty when none or not loaded. Refreshed via [refreshSavedCards]. */
    public var savedCards: List<SavedCard> by mutableStateOf(emptyList()); private set

    /** The active selection: a saved card (entry fields collapsed, values preserved) or null =
     *  the new-card branch. Never points outside [savedCards]. */
    public var selectedSavedCard: SavedCard? by mutableStateOf(null); private set

    /** The in-frame "save this card" switch state (consent) — default OFF, reset after each
     *  successful save (consent is per-transaction). */
    public var saveCardOptIn: Boolean by mutableStateOf(false); private set

    /** Select [card] (collapses the entry fields — their values are preserved). Ignored when the
     *  card is not one of [savedCards]. */
    public fun selectSavedCard(card: SavedCard) {
        if (card in savedCards) {
            selectedSavedCard = card
            lastOneClickError = null // a new intent supersedes the previous failure
        }
    }

    /** Select the new-card branch (expands the entry fields). */
    public fun selectNewCard() {
        selectedSavedCard = null
        lastOneClickError = null // a new intent supersedes the previous failure
    }

    /** Save-switch handler (called from the component's toggle). */
    public fun onSaveCardOptInChange(optIn: Boolean) {
        saveCardOptIn = optIn
    }

    // True once the first load has run: the first load pre-selects the most recent card; later
    // re-appearance refreshes must NOT (they preserve the payer's current choice — see [reload]).
    private var hasLoadedOnce = false

    /**
     * (Re)loads [savedCards] for the card entry. Called after composition and on each re-appearance.
     * The selection is PRESERVED across a reload when it still resolves to a present card (a
     * re-appearance must never silently switch the payer back to a stored card after they picked
     * "new card"); the most recent card is pre-selected only on the very first load. Fail-soft:
     * a no-op (and no store created) unless [oneClickEnabled].
     * Headless-host note: this pre-selection makes a subsequent plain [pay] route to the stored
     * token — call [selectNewCard] to opt back into card entry.
     */
    public suspend fun refreshSavedCards() {
        reload(reselectMostRecent = false)
        // An app-foreground refresh drops a stale one-click error unless the affected card is
        // still listed (then it is still the last failure and keeps its inline surface).
        // Never while a pay is in flight: that path sets and manages the error under its own lock
        // (e.g. TOKEN_INVALID set just before its purge+reload) — a concurrent refresh must not wipe it.
        val error = lastOneClickError
        if (!isProcessing && error != null && savedCards.none(error::matches)) lastOneClickError = null
    }

    /**
     * Core (re)load. [reselectMostRecent] forces the most-recent card back into the selection
     * (first load, and after a save / one-click payment); otherwise a still-present selection is
     * kept and a vanished one (e.g. a purged card) falls back to the new-card branch.
     */
    private suspend fun reload(reselectMostRecent: Boolean) {
        if (!oneClickEnabled) return
        val cards = try {
            withStore { it.list() }.allowedByMerchant()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val firstLoad = !hasLoadedOnce
        hasLoadedOnce = true
        savedCards = cards
        selectedSavedCard = if (reselectMostRecent || firstLoad) {
            cards.firstOrNull()
        } else {
            selectedSavedCard?.let { prev -> cards.firstOrNull { it == prev } }
        }
    }

    /** Saved cards whose resolved network the merchant accepts (empty allow-list → all kept). */
    private fun List<SavedCard>.allowedByMerchant(): List<SavedCard> =
        filter { AllowedNetworks.isAuthorized(CardNetworks.fromApiBrand(it.network) ?: CardNetwork.UNKNOWN, allowed) }

    private suspend fun reloadQuietly(reselectMostRecent: Boolean) {
        try {
            reload(reselectMostRecent)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * Removes [card] from the saved-card store (in-component delete, driven by the gesture +
     * confirmation), then refreshes: a deleted **selected** card drops the selection to the
     * new-card branch, a **non-selected** one is preserved, the **last** one yields the no-card
     * state. Fail-visible — a failed store delete leaves the card in the refreshed list. No-op
     * unless [oneClickEnabled].
     */
    public suspend fun deleteSavedCard(card: SavedCard) {
        if (!oneClickEnabled) return
        try {
            withStore { it.delete(card) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fail-soft: the reload below reveals whether the card is still present.
        }
        reload(reselectMostRecent = false)
        // Deleting the card an error pointed at is an intent too — once the card is really
        // gone there is nothing left to recover, so don't keep a stale outcome observable.
        val error = lastOneClickError
        if (error != null && error.matches(card) && savedCards.none(error::matches)) {
            lastOneClickError = null
        }
    }

    private var userSelectedNetwork = false
    private var lastDetected: CardNetwork = CardNetwork.UNKNOWN
    // Last PAN sent to the backend resolution — one resolve per distinct valid PAN.
    private var lastResolvedDigits: String? = null

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

    /** A selected saved card is always payable — field state is irrelevant on that branch. */
    public val canPay: Boolean
        get() = (oneClickEnabled && selectedSavedCard != null) ||
            (holder.isNotBlank() &&
                CardValidators.isHolderValid(holder) &&
                CardValidators.isCardNumberValid(panDigits) &&
                CardValidators.isExpiryDateValid(expiryMonth, expiryYear) &&
                isCvcComplete &&
                isNetworkAuthorized)

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

    // Each edit is a fresh payment intent → it supersedes a showing one-click error.
    public fun onHolderChange(input: String) {
        lastOneClickError = null
        holder = input.uppercase().take(60)
    }

    public fun onNumberChange(input: String) {
        // Store RAW digits (story 11.1) — the field's VisualTransformation renders the spaces,
        // so the caret never breaks on a grouping space.
        // Cap to the DETECTED network's complete length (story 11.7): Visa 16 / Amex 15 / etc.,
        // 19 while UNKNOWN so early typing is never blocked. Detect on the new digits.
        val digits = input.filter { it in '0'..'9' }
        lastOneClickError = null
        cardNumber = digits.take(CardNetworks.completionLength(CardNetworks.detect(digits)))
        recomputeNetworks()
        // Backend co-brand refinement: once per distinct valid PAN, launched AFTER the local
        // applyOffered so the immediate icon never waits on the network. Partial/invalid input
        // re-arms the next resolve.
        val pan = panDigits
        if (!disposed && CardValidators.isCardNumberValid(pan) && pan != lastResolvedDigits) {
            lastResolvedDigits = pan
            scope.launch { resolve(pan) }
        } else if (!CardValidators.isCardNumberValid(pan)) {
            lastResolvedDigits = null
        }
    }

    public fun onExpiryChange(input: String) {
        // Store RAW digits (story 11.8) — the field's ExpiryVisualTransformation renders the "/",
        // so the caret never breaks on the separator.
        lastOneClickError = null
        expiry = input.filter { it in '0'..'9' }.take(4)
    }

    public fun onCvcChange(input: String) {
        lastOneClickError = null
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
        // Local offered set (immediate); the backend verdict in [resolve] refines it once available.
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

    /** In-module test: common tests fake the backend co-brand verdict through this
     *  (no network, no tokenizer stub); null in production — [resolve] then calls the real
     *  [tokenizer]. Same spirit as the [lastOneClickError] internal setter. */
    internal var cardInfoResolver: (suspend (digits: String) -> CardInfo)? = null

    /** Backend co-brand refinement (mirrors the native controllers): the Secure Vault is the
     *  only source that can see a domestic co-brand (CB/BCMC), so the offered set is re-derived
     *  from its verdict. A stale verdict (the payer kept typing) is dropped; a failure degrades
     *  to the locally detected icon and re-arms a retry on the next edit — entry is never
     *  blocked and no error surfaces. */
    private suspend fun resolve(digits: String) {
        try {
            val info = cardInfoResolver?.invoke(digits)
                ?: tokenizer.resolveCardInfo(digits, "12", nextYear())
            if (digits != panDigits) return // user kept typing — drop the stale result
            val offered = AllowedNetworks.offered(info.resolvedNetworks(), allowed)
            if (offered.isNotEmpty()) applyOffered(offered)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Degrade: keep the locally detected icon; allow a retry on the next edit.
            if (digits == panDigits) lastResolvedDigits = null
        }
    }

    // The resolution expiry is required-but-non-authoritative (the backend resolves on the BIN);
    // a plausible near-future year is enough — same contract as the native controllers.
    private fun nextYear(): String = (currentYear() + 1).toString()

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
        saveCard: Boolean = false,
    ): Transaction {
        // One-click routing: with a saved card selected, the same host call pays via the
        // stored token — no tokenization, no CVV; the host's single touch-point is preserved.
        val routedCard = selectedSavedCard
        if (oneClickEnabled && routedCard != null) {
            // A one-click payment saves nothing — clear any stale outcome from an earlier
            // new-card pay so the host never reads a save result against this transaction.
            lastSaveOutcome = null
            // payWithSavedCard refreshes the saved-card state itself, inside its processing lock,
            // on COMPLETED and on CARD_NO_LONGER_VALID — so there is no post-unlock double-tap window.
            return payWithSavedCard(
                card = routedCard,
                orderId = orderId,
                amount = amount,
                currency = currency,
                description = description,
                language = language,
                redirectScheme = redirectScheme,
                authenticationIndicator = authenticationIndicator,
                signature = signature,
                customer = customer,
                shipping = shipping,
                threeDS = threeDS,
            )
        }
        // The component's save switch and the parameter express the same consent.
        val effectiveSave = saveCard || (oneClickEnabled && saveCardOptIn)
        if (effectiveSave) lastSaveOutcome = null
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
            multiUse = effectiveSave,
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
        userSelectedNetwork = false; lastDetected = CardNetwork.UNKNOWN; lastResolvedDigits = null
        holderBlurred = false; numberBlurred = false; expiryBlurred = false; cvcBlurred = false

        val final = resolve3DS(transaction, redirectScheme, signature, threeDS)
        if (effectiveSave) {
            persistSavedCard(token, final)
            if (final.state == TransactionState.COMPLETED) {
                saveCardOptIn = false // consent is per-transaction
                reloadQuietly(reselectMostRecent = true) // the new card appears, pre-selected for the next payment
            }
        }
        return final
        } finally {
            isProcessing = false
        }
    }

    /**
     * One-click payment with a previously saved card: the order is created directly
     * from the stored reusable token — no card re-entry, no CVV, no tokenization
     * round-trip. 3DS behaves exactly as in [pay].
     *
     * On a final `COMPLETED` the card's recency is bumped (most-recently-used). If
     * the gateway reports the stored token as no longer usable, the card is purged
     * from local storage and a [HiPayException] with
     * `HiPayErrorCode.CARD_NO_LONGER_VALID` is thrown — fall back to card entry.
     * A declined payment is returned as a normal `DECLINED` transaction.
     */
    public suspend fun payWithSavedCard(
        card: SavedCard,
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
        lastOneClickError = null // a fresh attempt supersedes the previous outcome
        // Sampled before the (possibly long) 3DS round-trip: the reason must reflect the
        // card as it was when the payer tapped Pay.
        val expiredAtAttempt = savedCardExpiredNow(card)
        isProcessing = true
        try {
            val base = "$redirectScheme://hipay-fullservice/gateway/orders/$orderId"
            val order = OrderRequest(
                orderId = orderId,
                paymentProduct = savedCardPaymentProduct(card),
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
                cardToken = card.token,
                eci = 7,
                authenticationIndicator = authenticationIndicator,
                oneClick = true,
            )
            val transaction = try {
                gateway.requestNewOrder(order, signature)
            } catch (e: HiPayException) {
                val cnlv = cardNoLongerValidOrNull(e)
                if (cnlv != null) {
                    // Set BEFORE the purge+reload so the error survives the card vanishing
                    // (the component then shows its section-level notice).
                    lastOneClickError = OneClickError(card, OneClickErrorReason.TOKEN_INVALID)
                    purgeQuietly(card)
                    // Refresh inside the processing lock, so the purged card is gone and the
                    // selection has fallen back to the new-card branch before the host unlocks.
                    reloadQuietly(reselectMostRecent = false)
                    throw cnlv
                }
                lastOneClickError = OneClickError(card, OneClickErrorReason.GENERIC)
                throw e
            }
            val challenged = willPresent3DS(transaction)
            val final = try {
                resolve3DS(transaction, redirectScheme, signature, threeDS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Any failure during the 3DS phase must be observable — including non-HiPayException
                // throws from the presentation launch itself (e.g. no browser to open the challenge,
                // or a malformed forward URL), which a HiPayException-only catch let slip through as a
                // null error. The host still gets the rethrow; coroutine cancellation is never relabelled.
                lastOneClickError = OneClickError(card, OneClickErrorReason.GENERIC)
                throw e
            }
            oneClickReasonForOutcome(
                finalState = final.state,
                challenged = challenged,
                authenticationStatus = final.threeDSecure?.authenticationStatus,
                cardExpiredAtAttempt = expiredAtAttempt,
            )?.let { lastOneClickError = OneClickError(card, it) }
            if (final.state == TransactionState.COMPLETED) {
                touchQuietly(card)
                // Refresh inside the lock (the touched card is now MRU and re-selected) so there
                // is no post-unlock double-tap window on the store read.
                reloadQuietly(reselectMostRecent = true)
            }
            return final
        } finally {
            isProcessing = false
        }
    }

    /**
     * Persist the tokenized card after a COMPLETED payment; never throws (the payment already
     * settled). Records the outcome in [lastSaveOutcome].
     */
    private suspend fun persistSavedCard(token: CardToken, transaction: Transaction) {
        if (transaction.state != TransactionState.COMPLETED) return
        val card = savedCardFromToken(token)
        if (card == null) {
            lastSaveOutcome = SavedCardOutcome.NOT_ELIGIBLE
            return
        }
        lastSaveOutcome = try {
            if (withStore { it.save(card, consentGiven = true) }) {
                SavedCardOutcome.SAVED
            } else {
                SavedCardOutcome.STORAGE_FAILED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fail-soft: the payment outcome is already decided; storage must not affect it.
            SavedCardOutcome.STORAGE_FAILED
        }
    }

    /** Purge a no-longer-valid card; storage failure never masks the payment error (fail-soft). */
    private suspend fun purgeQuietly(card: SavedCard) {
        try {
            withStore { it.delete(card) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Bump the card's recency after a completed one-click payment (fail-soft). */
    private suspend fun touchQuietly(card: SavedCard) {
        try {
            withStore { it.touch(card) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** 3DS resolution shared by [pay] and [payWithSavedCard] — behaviour unchanged from [pay]. */
    private suspend fun resolve3DS(
        transaction: Transaction,
        redirectScheme: String,
        signature: String?,
        threeDS: HiPayThreeDSMode,
    ): Transaction {
        val forwardUrl = transaction.forwardUrl
        if (forwardUrl == null || !willPresent3DS(transaction)) {
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
    }

    /** The single decides-a-challenge-is-presented guard for [resolve3DS] — also feeds the
     *  one-click `challenged` flag, so the two can never drift. */
    private fun willPresent3DS(transaction: Transaction): Boolean =
        transaction.state == TransactionState.FORWARDING && !transaction.forwardUrl.isNullOrBlank()

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

    /** Cancel the owned coroutine scope (if one was created) and block any later resolve from
     *  re-creating one. No-op on the scope itself when the host supplied its own. */
    public fun dispose() {
        disposed = true
        ownedScope?.cancel()
        ownedScope = null
    }
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
