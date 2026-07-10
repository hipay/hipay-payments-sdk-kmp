// PCI: com.hipay.card path — NEVER log here, never expose the raw PAN or token.
package com.hipay.card.store

import com.hipay.card.validation.CardEntryStringKey
import com.hipay.core.gateway.model.TransactionState

/**
 * Why a one-click payment attempt with a saved card failed, as far as the SDK
 * can tell from the outcome it already produces (the thrown exception or the
 * returned transaction — no new gateway signal is invented).
 */
public enum class OneClickErrorReason {
    /** The gateway returned a declined transaction for the attempt. */
    DECLINED,

    /**
     * The stored token was rejected as no longer usable (the definitive
     * CARD_NO_LONGER_VALID verdict) — the card has been purged locally.
     */
    TOKEN_INVALID,

    /** A 3DS challenge was presented and failed or was cancelled/abandoned. */
    THREE_DS_FAILED,

    /**
     * The attempt failed and the card's expiry had already passed at attempt
     * time. Normally unreachable (the store purges expired cards on load) —
     * kept so a card expiring between load and attempt is named precisely,
     * and so a future "show expired cards" behavior is additive.
     */
    EXPIRED,

    /** Any other transient failure (network/server/client) — the card stays usable. */
    GENERIC,

    /**
     * Not a failure: the attempt is still being confirmed (verification pending, or the
     * server was momentarily unreachable during 3DS reconciliation). Surfaced as a soft,
     * non-blocking hint so the payer is not left on a silently-cleared spinner; the card
     * stays usable and the host still receives the PENDING transaction to reconcile.
     * PROVISIONAL copy — final wording pending UX/compliance sign-off.
     */
    PENDING,
}

/**
 * A transient, observable one-click failure: the affected card's masked
 * identity plus a [reason]. Set by the entry controllers inside
 * `payWithSavedCard` (the host contract is unchanged — the exception is still
 * thrown / the transaction still returned); cleared on the next attempt, on
 * any selection change, and on a new-card field edit. Never persisted, never
 * logged; carries only the backend-masked pan (BIN6 + last4) and expiry.
 */
public class OneClickError(card: SavedCard, public val reason: OneClickErrorReason) {
    public val maskedPan: String = card.maskedPan
    public val expiryMonth: String = card.expiryMonth
    public val expiryYear: String = card.expiryYear

    // Same normalised identity the store dedups on — a cosmetic re-mask of the
    // card across a reload still matches its error.
    private val cardIdentity: String = card.identity

    /** True when [card] is the card this error is about (masked-pan + expiry identity). */
    public fun matches(card: SavedCard): Boolean = card.identity == cardIdentity

    // Value equality (mirrors SavedCard): the UI compares fresh reads across reloads.
    override fun equals(other: Any?): Boolean = other is OneClickError &&
        reason == other.reason &&
        cardIdentity == other.cardIdentity

    override fun hashCode(): Int = 31 * reason.hashCode() + cardIdentity.hashCode()

    // Masked-only, mirroring SavedCard's terseness.
    override fun toString(): String =
        "OneClickError(reason=$reason, maskedPan=$maskedPan, exp=$expiryMonth/$expiryYear)"
}

/**
 * The localized message key for a one-click error (every reason carries one).
 * The UI resolves the key to text per platform (mirrors `ValidationReason.messageKey`).
 */
public fun OneClickErrorReason.messageKey(): CardEntryStringKey = when (this) {
    OneClickErrorReason.DECLINED -> CardEntryStringKey.ERROR_ONE_CLICK_DECLINED
    OneClickErrorReason.TOKEN_INVALID -> CardEntryStringKey.ERROR_ONE_CLICK_CARD_REMOVED
    OneClickErrorReason.THREE_DS_FAILED -> CardEntryStringKey.ERROR_ONE_CLICK_3DS
    OneClickErrorReason.EXPIRED -> CardEntryStringKey.ERROR_ONE_CLICK_EXPIRED
    OneClickErrorReason.GENERIC -> CardEntryStringKey.ERROR_ONE_CLICK_GENERIC
    OneClickErrorReason.PENDING -> CardEntryStringKey.ERROR_ONE_CLICK_PENDING
}

/** Where the component surfaces a one-click error — decided by [oneClickErrorSurface]. */
public enum class OneClickErrorSurface {
    /** Inline on the affected saved-card cell (the card is still listed and retryable). */
    INLINE_CARD,

    /** At section level: the affected card is gone (purged as no longer valid). */
    SECTION,
}

/**
 * THE single rendering-policy point for one-click errors, shared by every
 * platform component: inline on the affected cell while the card is still
 * listed; a section-level notice when the card was purged as no longer valid;
 * nothing when the card vanished for any other cause (e.g. the payer deleted
 * it). A future integrator configuration that tunes or disables the in-component
 * surface gates exactly this function — the observable error itself stays raw.
 */
public fun oneClickErrorSurface(
    error: OneClickError?,
    savedCards: List<SavedCard>,
): OneClickErrorSurface? = when {
    error == null -> null
    savedCards.any(error::matches) -> OneClickErrorSurface.INLINE_CARD
    error.reason == OneClickErrorReason.TOKEN_INVALID -> OneClickErrorSurface.SECTION
    else -> null
}

/**
 * Maps a RETURNED transaction outcome (the non-exception path) to a one-click
 * error reason, or null when nothing failed. The exception path needs no
 * mapper: a token-invalid rejection ([cardNoLongerValidOrNull]) is
 * [OneClickErrorReason.TOKEN_INVALID], any other thrown failure is
 * [OneClickErrorReason.GENERIC].
 *
 * Exact statuses:
 * - `COMPLETED` → null (success — the caller clears any previous error).
 * - `PENDING` → [OneClickErrorReason.PENDING]: indeterminate (verification pending /
 *   server unreachable). Not a failure, but surfaced as a soft, non-blocking hint so the
 *   payer is not left on a silently-cleared spinner; the host still receives the PENDING
 *   transaction to reconcile.
 * - `FORWARDING` → [OneClickErrorReason.THREE_DS_FAILED] only when a challenge
 *   was actually presented ([challenged]): the server-confirmed still-FORWARDING
 *   state after a presented challenge is the genuine-abandon verdict. Without a
 *   presented challenge the transaction is simply awaiting the host's own 3DS
 *   handling — not a failure.
 * - `DECLINED`/`ERROR` after a presented challenge → [OneClickErrorReason.THREE_DS_FAILED],
 *   unless the transaction's own 3DS result ([authenticationStatus] `Y`/`A` —
 *   authenticated or attempted) shows the challenge itself succeeded; then the
 *   decline is the payment's, not 3DS's.
 * - `DECLINED` otherwise → [OneClickErrorReason.EXPIRED] when the card's expiry
 *   had already passed at attempt time ([cardExpiredAtAttempt]), else
 *   [OneClickErrorReason.DECLINED].
 * - `ERROR` otherwise → [OneClickErrorReason.GENERIC] (unknown/error state —
 *   not a confirmed decline).
 *
 * @param challenged a 3DS challenge was presented and awaited by the SDK for
 *   this attempt (not merely a FORWARDING state returned to the host). Each platform
 *   derives this from its own presentation guard (a blank/absent forward URL is never a
 *   challenge). The guards are not identical across platforms: only the Android entry
 *   point exposes an `autoPresent3DS` opt-out, so when a host opts out there, the SDK
 *   hands the FORWARDING transaction back and `challenged` is false — a path that has no
 *   equivalent on the platforms that always present.
 * @param authenticationStatus the final transaction's
 *   `threeDSecure.authenticationStatus`, when present.
 * @param cardExpiredAtAttempt the saved card's expiry had passed at the moment
 *   the attempt started (client clock, plausibility-guarded).
 */
public fun oneClickReasonForOutcome(
    finalState: TransactionState,
    challenged: Boolean,
    authenticationStatus: String?,
    cardExpiredAtAttempt: Boolean,
): OneClickErrorReason? {
    val challengeFailed = challenged &&
        authenticationStatus?.trim()?.uppercase() !in listOf("Y", "A")
    return when (finalState) {
        TransactionState.COMPLETED -> null
        TransactionState.PENDING -> OneClickErrorReason.PENDING
        TransactionState.FORWARDING -> if (challenged) OneClickErrorReason.THREE_DS_FAILED else null
        TransactionState.DECLINED -> when {
            challengeFailed -> OneClickErrorReason.THREE_DS_FAILED
            cardExpiredAtAttempt -> OneClickErrorReason.EXPIRED
            else -> OneClickErrorReason.DECLINED
        }
        TransactionState.ERROR ->
            if (challengeFailed) OneClickErrorReason.THREE_DS_FAILED else OneClickErrorReason.GENERIC
    }
}

/**
 * Whether [card]'s expiry has passed at [now] — the attempt-time refinement
 * feeding [oneClickReasonForOutcome]. Mirrors the store's expiry rule (a card
 * expires AFTER its expiry month) including the clock plausibility guard: an
 * implausible [now] or an unparseable expiry never claims EXPIRED.
 */
public fun savedCardExpiredAt(card: SavedCard, now: YearMonth): Boolean {
    if (now.year !in 2000..2100 || now.month !in 1..12) return false
    val month = card.expiryMonth.trim().toIntOrNull() ?: return false
    val year = normalizeYear(card.expiryYear) ?: return false
    return year < now.year || (year == now.year && month < now.month)
}

/** [savedCardExpiredAt] against the device clock (the same clock the validators use). */
public fun savedCardExpiredNow(card: SavedCard): Boolean {
    val (year, month) = com.hipay.card.validation.currentYearMonth()
    return savedCardExpiredAt(card, YearMonth(year, month))
}
