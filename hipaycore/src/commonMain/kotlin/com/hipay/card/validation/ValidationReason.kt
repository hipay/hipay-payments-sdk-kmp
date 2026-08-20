package com.hipay.card.validation

/**
 * Typed, value-free per-field validation outcome (FR26) — the shared contract
 * the iOS (SwiftUI) and Android (Compose) card components both consume, so the
 * card behavior is reasoned about once and the two UIs cannot diverge.
 *
 * Carries NO input data: a host mapping a reason to a message can never capture
 * a PAN/CVC (PCI, NFR2). The UI maps each reason to a localized message via
 * [CardEntryStringKey] / [messageKey] — commonMain owns the reasons and keys,
 * each platform owns the translations (architecture D11).
 */
public enum class ValidationReason {
    /** Field is acceptable (or empty/untouched — empty fields are not flagged). */
    VALID,

    /** Non-ASCII, wrong length, or fails the Luhn check (complete but wrong). */
    INVALID_NUMBER,

    /** Digits-only prefix shorter than the network's completion length — shown on focus loss. */
    INCOMPLETE_NUMBER,

    /** Expiry is not a well-formed MM / YYYY. */
    INVALID_EXPIRY,

    /** Well-formed expiry but in the past. */
    EXPIRED,

    /** CVV has the wrong length/format for the network. */
    INVALID_CVV,

    /** Required CVV, digits-only, shorter than the network's CVC length — shown on focus loss. */
    INCOMPLETE_CVV,

    /** Holder name longer than the 60-char vault limit. */
    HOLDER_TOO_LONG,

    /** Non-empty holder name shorter than 3 characters — shown on focus loss. */
    HOLDER_TOO_SHORT,

    /** The detected/selected network is not in the merchant's allowed set (D13). */
    NETWORK_NOT_AUTHORIZED,
}
