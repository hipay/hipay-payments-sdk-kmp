// PCI (NFR2): this module is on the com.hipay.card anti-logging path — NEVER log
// here, and never expose the raw PAN or the vault token on the public surface.
package com.hipay.card

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hipay.core.callback.CallbackUrlParser
import com.hipay.core.callback.hipayCallbackBase
import com.hipay.card.validation.CardEntryStringKey
import com.hipay.card.validation.CardFieldValidation
import com.hipay.card.validation.CardNetwork
import com.hipay.card.validation.messageKey
import com.hipay.card.validation.CardNetworks
import com.hipay.card.validation.CardValidators
import com.hipay.card.validation.ValidationReason
import com.hipay.card.validation.AllowedNetworks
import com.hipay.card.model.CardToken
import com.hipay.card.store.DEFAULT_SAVED_CARDS_DISPLAY_COUNT
import com.hipay.card.store.OneClickError
import com.hipay.card.store.OneClickErrorReason
import com.hipay.card.store.SavedCard
import com.hipay.card.store.SavedCardOutcome
import com.hipay.card.store.SecureCardStore
import com.hipay.card.store.cardNoLongerValidOrNull
import com.hipay.card.store.coerceSavedCardsDisplayCount
import com.hipay.card.store.createSecureCardStore
import com.hipay.card.store.oneClickReasonForOutcome
import com.hipay.card.store.savedCardExpiredNow
import com.hipay.card.store.savedCardFromToken
import com.hipay.card.store.savedCardPaymentProduct
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.core.gateway.GatewayClient
import com.hipay.core.gateway.model.CustomerInfo
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.Transaction
import com.hipay.core.gateway.model.TransactionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val config: HiPayConfig,
    allowedNetworks: List<HiPayCardNetwork> = emptyList(),
    scope: CoroutineScope? = null,
    /** Explicit integrator opt-in for the one-click (saved cards) UI — off by default: without it
     *  the component renders and behaves exactly as before and no card store is ever created.
     *  Headless-host note: once [refreshSavedCards] has pre-selected a saved card, a plain [pay]
     *  call routes to that stored token (no CVV) — call [selectNewCard] first to force card entry. */
    public val oneClickEnabled: Boolean = false,
    /** How many saved cards the one-click UI shows before a "Show more" control.
     *  Additive, defaulted to [DEFAULT_SAVED_CARDS_DISPLAY_COUNT] (3), clamped to 1..10. Bounds only
     *  the DISPLAY — every saved card is still persisted (see the storage cap in SecureCardStore). */
    savedCardsDisplayCount: Int = DEFAULT_SAVED_CARDS_DISPLAY_COUNT,
    /** Ask the payer to confirm before a saved card is deleted. OFF by default: reaching the trash
     *  already takes two deliberate steps (left-swipe or long-press, then tapping the trash), so a
     *  dialog on top adds friction rather than intent. Turn it on if your checkout wants the extra
     *  guard. The confirmation is shown REGARDLESS of this flag when the request comes from the
     *  screen-reader "Delete" action, which is a single step with no trash to aim at. */
    public val confirmCardDeletion: Boolean = false,
    /** Currency the account's accepted card products are resolved for — a contract can differ per
     *  currency, so this should match the currency the order will be created in. Only used for that
     *  resolution; [pay] still takes its own currency. */
    currency: String = "EUR",
) {
    /** The clamped (1..10) saved-cards display count — see the constructor parameter. */
    public val savedCardsDisplayCount: Int = coerceSavedCardsDisplayCount(savedCardsDisplayCount)

    // Held under its own name: `pay()` has a `currency` parameter of its own, and a property it
    // silently shadowed would be a trap for the next reader.
    private val accountCurrency: String = currency

    /** Field identifiers (for blur tracking / first-invalid focus — error UI is story 7.4). */
    public enum class Field { HOLDER, NUMBER, EXPIRY, CVC }

    private val tokenizer = CardTokenizer(config)
    private val gateway = GatewayClient(config)

    /** SDK-wide forced locale from [HiPayConfig.settings], or a constant null flow when unset.
     *  Always non-null so the component can `collectAsState()` it unconditionally (Compose rule). */
    internal val settingsLocale: StateFlow<String?> =
        config.settings?.localeOverride ?: MutableStateFlow<String?>(null).asStateFlow()
    private val allowedKmp: List<CardNetwork> = allowedNetworks.map { it.kmpNetwork }

    private val ownsScope = scope == null
    private val scope: CoroutineScope =
        scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- SDK-managed 3DS presentation (story 11.13) ----
    // Custom Tabs needs a Context; unlike iOS (global key window) Android can't grab one. The
    // HiPayCardEntry composable binds the host Activity context for the duration it is on screen
    // (DisposableEffect) so pay() stays turnkey — no Context parameter on the public API.
    private var presentationContext: Context? = null
    private class Pending3DS(
        val deferred: CompletableDeferred<Transaction>,
        val reference: String?,
        val signature: String?,
    )
    private var pending3DS: Pending3DS? = null
    // Cancellation watcher (story 11.15): Custom Tabs gives no dismiss callback, so we detect the
    // user returning to the host Activity without a deep-link return = cancellation.
    private var lifecycleApp: Application? = null
    private var lifecycleCallback: Application.ActivityLifecycleCallbacks? = null

    /** Bound by [HiPayCardEntry] from `LocalContext`; do not call from app code — exception: a
     *  headless host using the one-click APIs without rendering the component binds its context here. */
    public fun bindPresentationContext(context: Context?) {
        presentationContext = context
    }

    // ---- Saved cards (one-click) ----
    // One store instance per controller, and EVERY access (creation included) confined to this
    // single-thread dispatcher: the store is not thread-safe, and the platform factory refuses
    // the main thread (it does blocking DataStore I/O).
    private val storeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var store: SecureCardStore? = null

    /** Only call from [storeDispatcher]. */
    private fun obtainStore(applicationContext: Context): SecureCardStore =
        store ?: createSecureCardStore(applicationContext, config).also { store = it }

    private fun requireOneClickContext(): Context =
        checkNotNull(presentationContext?.applicationContext) {
            "one-click requires a presentation context: render HiPayCardEntry, " +
                "or call bindPresentationContext(context) first"
        }


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
     * Setter internal (not private): the in-module UI harness drives outcomes the tests cannot
     * obtain without a gateway stub (declined / token-invalid); never written by the component.
     */
    public var lastOneClickError: OneClickError? by mutableStateOf(null); internal set

    // ---- One-click UI state (rendered by HiPayCardEntry only when oneClickEnabled) ----

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
     * (Re)loads [savedCards] for the component. Called after composition and on each re-appearance.
     * The selection is PRESERVED across a reload when it still resolves to a present card (a
     * re-appearance must never silently switch the payer back to a stored card after they picked
     * "new card"); the most recent card is pre-selected only on the very first load. Fail-soft:
     * a no-op (and no store created) unless [oneClickEnabled] with a bound presentation context.
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
        val context = presentationContext?.applicationContext ?: return
        val cards = try {
            withContext(storeDispatcher) { obtainStore(context).list() }.allowedByMerchant()
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
        filter { AllowedNetworks.isAuthorized(CardNetworks.fromApiBrand(it.network) ?: CardNetwork.UNKNOWN, effectiveAllowed) }

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
     * state. Fail-visible — if the store delete does not take effect the refreshed list still
     * shows the card. No-op unless [oneClickEnabled] with a bound presentation context.
     */
    public suspend fun deleteSavedCard(card: SavedCard) {
        if (!oneClickEnabled) return
        val context = presentationContext?.applicationContext ?: return
        try {
            withContext(storeDispatcher) { obtainStore(context).delete(card) }
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

    /**
     * Persist the tokenized card after a COMPLETED payment; never throws (the payment already
     * settled). Records the outcome in [lastSaveOutcome]. [applicationContext] is captured by the
     * caller BEFORE the (possibly backgrounding) 3DS window, so a component leaving composition
     * mid-3DS cannot null it out and silently drop the save.
     */
    private suspend fun persistSavedCard(
        applicationContext: Context,
        token: CardToken,
        transaction: Transaction,
    ) {
        if (transaction.state != TransactionState.COMPLETED) return
        val card = savedCardFromToken(token)
        if (card == null) {
            lastSaveOutcome = SavedCardOutcome.NOT_ELIGIBLE
            return
        }
        lastSaveOutcome = try {
            val persisted = withContext(storeDispatcher) {
                obtainStore(applicationContext).save(card, consentGiven = true)
            }
            if (persisted) SavedCardOutcome.SAVED else SavedCardOutcome.STORAGE_FAILED
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fail-soft: the payment outcome is already decided; storage must not affect it.
            SavedCardOutcome.STORAGE_FAILED
        }
    }

    // ---- Editable state (Compose snapshot state; mutated only via the on*Change handlers) ----
    public var holder: String by mutableStateOf(""); private set
    public var cardNumber: String by mutableStateOf(""); private set   // RAW digits (11.1); spacing is a VisualTransformation
    public var expiry: String by mutableStateOf(""); private set       // RAW digits MMYY (11.8); "/" is a VisualTransformation
    public var cvc: String by mutableStateOf(""); private set

    // ---- Network state ----
    public var networks: List<HiPayCardNetwork> by mutableStateOf(emptyList()); private set
    public var selectedNetwork: HiPayCardNetwork? by mutableStateOf(null); private set

    /** True while a [pay] is in flight (tokenise → order → 3DS round-trip), set by the SDK (story
     *  11.14). [HiPayCardEntry] locks its fields on this; the host disables its own Pay button with
     *  `!canPay || isProcessing`. Read-only — no integrator wiring needed. */
    public var isProcessing: Boolean by mutableStateOf(false); private set

    // ---- Blur state (consumed by the 7.4 inline-error UI; exposed now, no UI here) ----
    public var holderBlurred: Boolean by mutableStateOf(false); private set
    public var numberBlurred: Boolean by mutableStateOf(false); private set
    public var expiryBlurred: Boolean by mutableStateOf(false); private set
    public var cvcBlurred: Boolean by mutableStateOf(false); private set

    private var userSelectedNetwork = false
    private var lastResolvedDigits: String? = null
    // Networks the vault resolved for [lastResolvedDigits], so a later-arriving account ceiling can
    // be applied to that verdict without a second vault call.
    private var lastResolvedNetworks: List<CardNetwork> = emptyList()
    private var lastDetected: CardNetwork = CardNetwork.UNKNOWN

    // ---- Account network ceiling ----
    // The card products this ACCOUNT is contracted for. null = not known yet (query pending, or it
    // failed) → the ceiling stays open and the integrator list is used as-is. An EMPTY set is a
    // verdict, not an absence: the account accepts no card at all.
    private var accountNetworks: Set<CardNetwork>? by mutableStateOf<Set<CardNetwork>?>(null)
    // Guards the one query per controller; reset on failure so a later attempt retries.
    private var accountQueryStarted by mutableStateOf(false)
    // Set once the first attempt has failed, and never cleared: from then on the component behaves as
    // it did before the ceiling existed. Without it a retry would hide the brand icon again, so the
    // payer would watch it blink on every attempt.
    private var accountQueryFailed by mutableStateOf(false)

    /**
     * The ceiling is being fetched and nothing is known yet. While this holds, local BIN detection
     * must NOT show a brand icon: whether that network is offerable at all is exactly what is in
     * flight, and showing a logo we may have to take back is worse than showing it a beat later.
     * True only during the FIRST attempt — a failed query degrades to the pre-ceiling behaviour.
     */
    private val accountCeilingPending: Boolean
        get() = accountQueryStarted && accountNetworks == null && !accountQueryFailed

    /** The allowed set every check must read: the account ceiling, narrowed by the integrator list. */
    private val effectiveAllowed: List<CardNetwork>?
        get() = AllowedNetworks.effectiveAllowed(accountNetworks, allowedKmp)

    /** In-instrumented-test seam for the account ceiling — same spirit as [cardInfoResolver]; null in
     *  production, where the real [gateway] is queried. */
    internal var accountNetworksResolver: (suspend () -> Set<CardNetwork>)? = null

    /**
     * Test seam that presets the ceiling SYNCHRONOUSLY. A UI test asserting network chips must not
     * race an asynchronous fetch: with only [accountNetworksResolver] the ceiling lands a coroutine
     * later, the pending state legitimately suppresses every chip until then, and the assertion
     * becomes timing-dependent — it passes on an idle machine and fails under load. Tests that are
     * ABOUT the fetch itself still use [accountNetworksResolver].
     */
    internal fun presetAccountNetworks(accepted: Set<CardNetwork>) {
        accountNetworks = accepted
        accountQueryStarted = true
    }

    // PAN whose backend BIN verdict left NO allowed network — the only trigger for the
    // "not authorized" error. Local detection alone must never show
    // it: a co-branded card (e.g. CB+Visa with only CB allowed) locally detects the
    // disallowed brand and would flash a false error until the verdict lands.
    private var unauthorizedDigits: String? by mutableStateOf(null)

    /** In-module test seam: instrumented tests fake the backend co-brand verdict through
     *  this (no network); null in production — [resolve] then calls the real [tokenizer].
     *  Same convention as the CMP controller's resolver seam. */
    internal var cardInfoResolver: (suspend (digits: String) -> com.hipay.card.model.CardInfo)? = null

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
    public val isNetworkAuthorized: Boolean get() = AllowedNetworks.isAuthorized(network, effectiveAllowed)

    /** True when the host's Pay action may proceed (the fields render their own inline errors).
     *  A selected saved card is always payable — field state is irrelevant on that branch. */
    public val canPay: Boolean
        get() = (oneClickEnabled && selectedSavedCard != null) ||
            (holder.isNotBlank() &&
                CardValidators.isHolderValid(holder) &&
                CardValidators.isHolderLongEnough(holder) &&
                panDigits.isNotEmpty() &&
                CardFieldValidation.cardNumberReason(panDigits) == ValidationReason.VALID &&
                CardValidators.isExpiryDateValid(expiryMonth, expiryYear) &&
                CardValidators.isExpiryYearWithinHorizon(expiryYear) &&
                isCvcComplete &&
                isNetworkAuthorized)

    /** First field (holder→number→expiry→cvc) currently failing — for the host's focus-to-error. */
    public val firstInvalidField: Field?
        get() = when {
            CardFieldValidation.holderReason(holder) != ValidationReason.VALID -> Field.HOLDER
            !CardValidators.isCardNumberValid(panDigits) ||
                !CardNetworks.isPrefixViable(panDigits) -> Field.NUMBER
            !CardValidators.isExpiryDateValid(expiryMonth, expiryYear) ||
                !CardValidators.isExpiryYearWithinHorizon(expiryYear) -> Field.EXPIRY
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

    // Backend-verdict-gated (contractual, not blur-gated unlike expiry/CVV): shown as soon
    // as the BIN verdict for the CURRENT number leaves no allowed network. The comparison
    // with panDigits clears it on any further edit.
    private val networkErrorKey: CardEntryStringKey?
        get() = if (unauthorizedDigits != null && unauthorizedDigits == panDigits)
            CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED else null

    // Unrepairable prefix — no supported network can ever match the typed digits
    // (e.g. leading "1" or "30"). Immediate like networkErrorKey (no blur gate):
    // further typing cannot fix it, so waiting for focus loss only delays the user.
    private val patternErrorKey: CardEntryStringKey?
        get() = if (panDigits.isNotEmpty() && !CardNetworks.isPrefixViable(panDigits))
            CardEntryStringKey.ERROR_INVALID_NUMBER else null

    // Locally UNAMBIGUOUS network rejection — shown immediately during focus (not
    // blur-gated, no backend needed) when the detected network can never be a co-brand
    // of any allowed one (e.g. Amex detected, only CB allowed). The AMBIGUOUS cases
    // (Visa/MC detected with an allowed domestic co-brand like CB) stay backend-gated
    // via networkErrorKey — a real co-branded card is never flashed as rejected while
    // typing (contract 2026-07-17 + refinement 2026-07-20). Guarded on an empty offered
    // set so a resolved allowed co-brand always wins.
    private val localNetworkErrorKey: CardEntryStringKey?
        get() = if (networks.isEmpty() &&
            AllowedNetworks.isLocallyUnauthorized(CardNetworks.detect(panDigits), effectiveAllowed))
            CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED else null

    /** Number-field slot error: network-not-authorized takes precedence over the number's own error (D1). */
    public val numberSlotErrorKey: CardEntryStringKey?
        get() = networkErrorKey ?: patternErrorKey ?: localNetworkErrorKey ?: numberErrorKey

    // ---- Field handlers (called from the Composable onValueChange) ----
    // Each edit is a fresh payment intent → it supersedes a showing one-click error.
    public fun onHolderChange(input: String) {
        lastOneClickError = null
        holder = CardValidators.sanitizeHolder(input)
    }

    public fun onNumberChange(input: String) {
        // Store RAW digits (story 11.1) — HiPayCardEntry renders the spaces via a
        // VisualTransformation, so the caret never breaks on a grouping space.
        // Cap to the DETECTED network's complete length (story 11.7): Visa 16 / Amex 15 / etc.,
        // 19 while UNKNOWN so early typing is never blocked. Detect on the new digits.
        val digits = input.filter { it in '0'..'9' }
        lastOneClickError = null
        cardNumber = digits.take(CardNetworks.completionLength(CardNetworks.detect(digits)))
        recomputeNetworks()
        // Fallback trigger for a headless host that never composes the view (the composable asks on
        // appearance). It lives HERE and not in [recomputeNetworks], which the ceiling failure path
        // re-enters through [reapplyCeiling] — asking from there would retry on every failure, in a
        // tight loop on the main dispatcher.
        if (CardValidators.isCardNumberValid(panDigits)) ensureAccountNetworks()
    }

    public fun onExpiryChange(input: String) {
        // Store RAW digits (story 11.8) — HiPayCardEntry renders the "/" via an
        // ExpiryVisualTransformation, so the caret never breaks on the separator.
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
        // Nothing is offered while the account ceiling is still pending — see [accountCeilingPending].
        val locallyDetected =
            if (accountCeilingPending) emptyList()
            else listOfNotNull(HiPayCardNetwork.from(detected)?.kmpNetwork)
        val localOffered = AllowedNetworks
            .offered(locallyDetected, effectiveAllowed)
            .mapNotNull { HiPayCardNetwork.from(it) }
        applyOffered(localOffered)

        if (CardValidators.isCardNumberValid(digits)) {
            if (digits != lastResolvedDigits) {
                lastResolvedDigits = digits
                // A verdict belongs to the PAN it was resolved for: drop the previous one now, or a
                // ceiling landing before the new verdict would apply the old card's networks.
                lastResolvedNetworks = emptyList()
                scope.launch { resolve(digits) }
            }
        } else if (!CardValidators.isCardNumberValid(digits)) {
            lastResolvedDigits = null
        }
    }

    private suspend fun resolve(digits: String) {
        try {
            val info = cardInfoResolver?.invoke(digits) ?: tokenizer.resolveCardInfo(digits, "12", nextYear())
            if (digits != panDigits) return // user kept typing — drop the stale result
            val resolved = info.resolvedNetworks()
            // Kept so a later-arriving account ceiling can be applied to this verdict without
            // paying for a second vault call.
            lastResolvedNetworks = resolved
            applyVerdict(digits, resolved)
        } catch (e: Exception) {
            // Degrade: keep the locally detected icon; allow a retry on the next edit.
            if (digits == panDigits) lastResolvedDigits = null
        }
    }

    /** Applies a vault verdict against the current allowed set — the ONE place that decides between
     *  "offer these networks" and the contractual not-authorized error, so the vault path and the
     *  account-ceiling path can never drift. */
    private fun applyVerdict(digits: String, resolved: List<CardNetwork>) {
        // The vault identified nothing: keep the locally detected icon, decide nothing.
        if (resolved.isEmpty()) return
        // Whether these networks are offerable at all is still in flight. Applying them now would
        // show a brand icon we may have to take back the moment the ceiling lands — the very thing
        // the pending state exists to prevent. The verdict is kept in [lastResolvedNetworks] and
        // [reapplyCeiling] applies it as soon as the ceiling is known.
        if (accountCeilingPending) return
        val offered = AllowedNetworks.offered(resolved, effectiveAllowed)
            .mapNotNull { HiPayCardNetwork.from(it) }
        // Applied even when EMPTY: the card is not offerable, so its chips must go — and they may
        // have been shown before an account ceiling arrived and disallowed them.
        applyOffered(offered)
        // Nothing left → the contractual "not authorized" error (networkErrorKey).
        unauthorizedDigits = if (offered.isEmpty()) digits else null
    }

    /** One account-ceiling query per controller. Fired as soon as the component is composed, and
     *  again from the number field as a fallback for a headless host that never composes anything. */
    private fun ensureAccountNetworks() {
        if (accountQueryStarted) return
        accountQueryStarted = true
        scope.launch { loadAccountNetworks() }
    }

    /** Called from the Composable on first composition, so the ceiling is being resolved while the
     *  payer is still reading the form rather than after they have typed a BIN. */
    internal suspend fun loadAccountNetworksIfNeeded() {
        if (accountQueryStarted) return
        accountQueryStarted = true
        loadAccountNetworks()
    }

    /** Resolves the networks this account is contracted for — the ceiling the merchant restriction
     *  narrows. A technical failure leaves the ceiling OPEN (unchanged behaviour, entry never
     *  blocked, no error) and re-arms a retry on the next edit, exactly like [resolve]. A successful
     *  EMPTY answer is a verdict, not a failure: the account takes no card. */
    private suspend fun loadAccountNetworks() {
        try {
            accountNetworks = accountNetworksResolver?.invoke()
                ?: gateway.getAvailablePaymentProducts(CardNetworks.cardPaymentProductCodes, accountCurrency)
            reapplyCeiling()
        } catch (e: CancellationException) {
            // The caller's coroutine died — the component left composition, the screen was navigated
            // away from. The ceiling is neither resolved nor failed, so the one-shot guard MUST be
            // released: leaving it set would keep the component pending, icon-less and unable to ever
            // retry for the rest of its life.
            accountQueryStarted = false
            throw e
        } catch (e: Exception) {
            // Degrade to the pre-ceiling behaviour and re-arm a retry, without ever hiding the brand
            // icon again (see [accountQueryFailed]).
            accountQueryFailed = true
            accountQueryStarted = false
            reapplyCeiling()
            return
        }
        // Deliberately OUTSIDE the try above: the saved-card list is filtered by the same allowed set
        // and is loaded by a sibling effect reading the local store — a race the network always
        // loses — so it must be re-filtered once the ceiling is known, and its own failure must not
        // undo the ceiling we just resolved.
        if (oneClickEnabled) reloadQuietly(reselectMostRecent = false)
    }

    /** The ceiling can land after the payer has already typed: re-derive the offered set and the
     *  not-authorized verdict for the number currently in the field, reusing the vault verdict
     *  already obtained for it — never a second network call. */
    private fun reapplyCeiling() {
        // Prefer the vault verdict for the number in the field: it is strictly better information
        // than local detection, [applyVerdict] clears the chips by itself when the ceiling disallows
        // them, and going through [recomputeNetworks] first would reassign `selectedNetwork` from the
        // local mono network — silently discarding a co-brand the payer had explicitly chosen.
        val pan = panDigits
        if (lastResolvedDigits == pan && lastResolvedNetworks.isNotEmpty()) applyVerdict(pan, lastResolvedNetworks)
        else recomputeNetworks()
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
     * 3DS (story 11.13): when [autoPresent3DS] is `true` (default) and the order
     * returns `FORWARDING`, the SDK presents the challenge in Chrome Custom Tabs
     * and **suspends until the host forwards the return URL via [resume3DS]**,
     * then returns the FINAL, server-confirmed [Transaction] (FR9 — confirmed via
     * `getTransaction`, never the redirect params). The host's only touch-point is
     * calling [resume3DS] from `onNewIntent`. With [autoPresent3DS] `false` (or if
     * no presentation context is bound), the raw `FORWARDING` transaction is
     * returned and the host handles the redirect itself (legacy story 7.5 path).
     *
     * Saved cards: with [saveCard] `true` (the payer's explicit consent — the save
     * switch state), the card is tokenized as reusable and persisted to the secure
     * card store, but ONLY once this call itself observes a final `COMPLETED`
     * (directly, or through the SDK-managed 3DS). A `PENDING` outcome, or the
     * [autoPresent3DS] `false` path where the host confirms the redirect manually,
     * never saves. Storage failures are silent — the payment result is unaffected;
     * the host reads [lastSaveOutcome] to learn whether the card was saved.
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
        autoPresent3DS: Boolean = true,
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
                autoPresent3DS = autoPresent3DS,
            )
        }
        // The component's save switch and the parameter express the same consent.
        val effectiveSave = saveCard || (oneClickEnabled && saveCardOptIn)
        // Capture the store context up front (fail fast BEFORE any money moves, and pin it so a
        // component leaving composition during the 3DS window can't null it and drop the save).
        val storeContext = if (effectiveSave) requireOneClickContext() else null
        if (effectiveSave) lastSaveOutcome = null
        // Lock the fields for the whole flow (incl. the suspended 3DS); reset on every exit (11.14).
        isProcessing = true
        try {
        // Falls back to the LOCALLY DETECTED network, never to a hardcoded brand: while the account
        // ceiling is still pending there is no selected network, and a blind "visa" would declare the
        // wrong instrument for any other card.
        val product = (selectedNetwork ?: HiPayCardNetwork.from(CardNetworks.detect(panDigits)))
            ?.paymentProductCode ?: "visa"
        val token = tokenizer.generateToken(
            cardNumber = panDigits,
            expiryMonth = expiryMonth,
            expiryYear = expiryYear,
            holder = holder,
            cvc = if (isCvcRequired) cvc else "",
            multiUse = effectiveSave,
        )
        val base = hipayCallbackBase(redirectScheme, orderId)
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
        lastResolvedNetworks = emptyList()
        userSelectedNetwork = false
        lastDetected = CardNetwork.UNKNOWN
        holderBlurred = false
        numberBlurred = false
        expiryBlurred = false
        cvcBlurred = false

        val final = present3DSAndAwait(transaction, signature, autoPresent3DS)
        if (storeContext != null) {
            persistSavedCard(storeContext, token, final)
            if (final.state == TransactionState.COMPLETED) {
                saveCardOptIn = false // consent is per-transaction
                reloadQuietly(reselectMostRecent = true) // the new card appears, pre-selected for the next payment
            }
        }
        return final
        } finally {
            isProcessing = false
            // If the host scope was cancelled mid-3DS, await() resumes here without resume3DS or the
            // watcher having cleaned up → release both so we never leak the Activity-lifecycle callback
            // (idempotent on the happy path, where they are already cleared).
            pending3DS = null
            unregisterCancellationWatcher()
        }
    }

    /**
     * 3DS presentation shared by [pay] and [payWithSavedCard]: present in-app (Custom Tabs) and
     * suspend until [resume3DS] (or the cancellation watcher) confirms, unless the host opted out
     * or no context is bound — then hand back the raw FORWARDING tx for manual host handling.
     */
    private suspend fun present3DSAndAwait(
        transaction: Transaction,
        signature: String?,
        autoPresent3DS: Boolean,
    ): Transaction {
        val context = presentationContext
        val forwardUrl = transaction.forwardUrl
        if (context == null || forwardUrl.isNullOrBlank() || !willPresent3DS(transaction, autoPresent3DS)) {
            return transaction
        }
        val deferred = CompletableDeferred<Transaction>()
        pending3DS = Pending3DS(deferred, transaction.transactionReference, signature)
        // Watch for a dismissed Custom Tab (story 11.15) BEFORE launching, so we never miss the return.
        registerCancellationWatcher(context)
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(forwardUrl))
        return deferred.await()
    }

    /** The single decides-a-challenge-is-presented guard for [present3DSAndAwait] — also feeds
     *  the one-click `challenged` flag, so the two can never drift. */
    private fun willPresent3DS(transaction: Transaction, autoPresent3DS: Boolean): Boolean =
        autoPresent3DS &&
            transaction.state == TransactionState.FORWARDING &&
            !transaction.forwardUrl.isNullOrBlank() &&
            presentationContext != null

    /**
     * One-click payment with a previously saved card: the order is created directly
     * from the stored reusable token — no card re-entry, no CVV, no tokenization
     * round-trip. 3DS behaves exactly as in [pay] (a challenge still fires when the
     * bank requires it). Requires a bound presentation context.
     *
     * On a final `COMPLETED` the card's recency is bumped (most-recently-used). If
     * the gateway reports the stored token as no longer usable, the card is purged
     * from local storage and a [HiPayException] with
     * `HiPayErrorCode.CARD_NO_LONGER_VALID` is thrown — fall back to card entry.
     * A declined payment is returned as a normal `DECLINED` transaction.
     *
     * Any failure outcome is ALSO reflected in [lastOneClickError] (additive — the
     * throw/return behavior above is unchanged) so the component can guide the
     * payer to another card or re-entry.
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
        autoPresent3DS: Boolean = true,
    ): Transaction {
        val storeContext = requireOneClickContext()
        lastOneClickError = null // a fresh attempt supersedes the previous outcome
        // Sampled before the (possibly long) 3DS round-trip: the reason must reflect the
        // card as it was when the payer tapped Pay.
        val expiredAtAttempt = savedCardExpiredNow(card)
        isProcessing = true
        try {
            val base = hipayCallbackBase(redirectScheme, orderId)
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
                    purgeQuietly(storeContext, card)
                    // Refresh inside the processing lock, so the purged card is gone and the
                    // selection has fallen back to the new-card branch before the host unlocks.
                    reloadQuietly(reselectMostRecent = false)
                    throw cnlv
                }
                lastOneClickError = OneClickError(card, OneClickErrorReason.GENERIC)
                throw e
            }
            val challenged = willPresent3DS(transaction, autoPresent3DS)
            val final = try {
                present3DSAndAwait(transaction, signature, autoPresent3DS)
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
                touchQuietly(storeContext, card)
                // Refresh inside the lock (the touched card is now MRU and re-selected) so there
                // is no post-unlock double-tap window on the store read.
                reloadQuietly(reselectMostRecent = true)
            }
            return final
        } finally {
            isProcessing = false
            pending3DS = null
            unregisterCancellationWatcher()
        }
    }

    /** Purge a no-longer-valid card; storage failure never masks the payment error (fail-soft). */
    private suspend fun purgeQuietly(applicationContext: Context, card: SavedCard) {
        try {
            withContext(storeDispatcher) { obtainStore(applicationContext).delete(card) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Bump the card's recency after a completed one-click payment (fail-soft). */
    private suspend fun touchQuietly(applicationContext: Context, card: SavedCard) {
        try {
            withContext(storeDispatcher) { obtainStore(applicationContext).touch(card) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * Forward the 3DS return URL here (from the host Activity's `onNewIntent`) so the SDK can
     * confirm the outcome via `getTransaction` (FR9) and resume the suspended [pay] with the FINAL
     * transaction. No-op if no 3DS is pending. Story 11.13.
     */
    public fun resume3DS(uri: String) {
        val pending = pending3DS ?: return
        pending3DS = null
        unregisterCancellationWatcher() // a real return arrived → stop watching for a dismissal
        scope.launch {
            val reference = pending.reference
                ?: runCatching { CallbackUrlParser.parse(uri).queryParams["reference"] }.getOrNull()
            pending.deferred.complete(reconcileOrPending(reference, pending.signature))
        }
    }

    /**
     * FR9 confirmation that never yields a false outcome: query `getTransaction` for the
     * authoritative state from the captured [reference]; if we can't confirm — no reference, or the
     * server is unreachable — return an indeterminate PENDING snapshot ([Transaction.verificationPending])
     * rather than a thrown error or a false abort, so the host can re-query later.
     */
    private suspend fun reconcileOrPending(reference: String?, signature: String?): Transaction {
        if (reference == null) return Transaction.verificationPending(null)
        return try {
            gateway.getTransaction(reference, signature)
        } catch (e: Exception) {
            Transaction.verificationPending(reference)
        }
    }

    /**
     * Detects a dismissed Custom Tab (story 11.15): Custom Tabs emits no callback, so when the host
     * Activity comes back to the foreground with a 3DS still pending (no deep-link [resume3DS] fired),
     * we DON'T assume an abort — we reconcile with the authoritative server state:
     * the user may have validated 3DS in the tab without the deep link firing (→ COMPLETED), a genuine
     * dismiss stays FORWARDING, an unreachable server → indeterminate PENDING. `onNewIntent` precedes
     * `onResume` in `singleTop`, so a real return clears `pending3DS` before this fires.
     */
    private fun registerCancellationWatcher(context: Context) {
        val activity = context.findActivity() ?: return // can't watch without an Activity → no-op
        val app = activity.application
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity1: Activity) {
                if (activity1 !== activity) return
                val pending = pending3DS
                if (pending != null) { // returned without a deep-link callback → reconcile, never assume abort
                    pending3DS = null
                    scope.launch {
                        pending.deferred.complete(reconcileOrPending(pending.reference, pending.signature))
                    }
                }
                unregisterCancellationWatcher()
            }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(callback)
        lifecycleApp = app
        lifecycleCallback = callback
    }

    private fun unregisterCancellationWatcher() {
        lifecycleCallback?.let { lifecycleApp?.unregisterActivityLifecycleCallbacks(it) }
        lifecycleCallback = null
        lifecycleApp = null
    }

    /** Cancel the owned coroutine scope. No-op if the host supplied its own scope. */
    public fun dispose() {
        unregisterCancellationWatcher()
        pending3DS?.deferred?.cancel()
        pending3DS = null
        presentationContext = null
        if (ownsScope) scope.cancel()
    }

    private fun nextYear(): String = (Calendar.getInstance().get(Calendar.YEAR) + 1).toString()

    /** Unwrap a Compose `LocalContext` (which may be a themed `ContextWrapper`) to its Activity. */
    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
