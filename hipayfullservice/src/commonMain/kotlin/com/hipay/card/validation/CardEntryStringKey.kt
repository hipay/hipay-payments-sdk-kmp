package com.hipay.card.validation

/**
 * The documented common i18n KEY set for the card-entry component (FR21/FR22,
 * architecture D11). commonMain owns the KEYS; each platform owns the VALUES
 * (FR/EN/IT `.strings` / `strings.xml`) — delivered in story 5.2. Both
 * platforms resolve a key to localized text the same way; the key-parity guard
 * (5.2) asserts every key has a value in all three locales on both platforms.
 */
public enum class CardEntryStringKey {
    LABEL_HOLDER,
    LABEL_NUMBER,
    LABEL_EXPIRY,
    LABEL_CVV,
    PLACEHOLDER_HOLDER,
    PLACEHOLDER_NUMBER,
    PLACEHOLDER_EXPIRY,
    PLACEHOLDER_CVV,
    CVV_OPTIONAL,
    CVV_TOOLTIP,
    ERROR_INVALID_NUMBER,
    ERROR_INCOMPLETE_NUMBER,
    ERROR_INVALID_EXPIRY,
    ERROR_EXPIRED,
    ERROR_INVALID_CVV,
    ERROR_INCOMPLETE_CVV,
    ERROR_HOLDER_TOO_LONG,
    ERROR_NETWORK_NOT_AUTHORIZED,
    LABEL_SAVED_CARDS,
    LABEL_NEW_CARD,
    LABEL_SAVE_CARD,
    CONSENT_SAVE_CARD,
    A11Y_SAVED_CARD,
    A11Y_EXPANDED,
    A11Y_COLLAPSED,
    LABEL_DELETE_CARD,
    CONFIRM_DELETE_CARD,
    LABEL_CANCEL,
    ERROR_ONE_CLICK_DECLINED,
    ERROR_ONE_CLICK_CARD_REMOVED,
    ERROR_ONE_CLICK_3DS,
    ERROR_ONE_CLICK_EXPIRED,
    ERROR_ONE_CLICK_GENERIC,
    ERROR_ONE_CLICK_PENDING,
}

/**
 * The localized message key for a reason, or null when the reason carries no
 * message (`VALID`). The UI resolves the key to text per platform (D11).
 */
public fun ValidationReason.messageKey(): CardEntryStringKey? = when (this) {
    ValidationReason.VALID -> null
    ValidationReason.INVALID_NUMBER -> CardEntryStringKey.ERROR_INVALID_NUMBER
    ValidationReason.INCOMPLETE_NUMBER -> CardEntryStringKey.ERROR_INCOMPLETE_NUMBER
    ValidationReason.INVALID_EXPIRY -> CardEntryStringKey.ERROR_INVALID_EXPIRY
    ValidationReason.EXPIRED -> CardEntryStringKey.ERROR_EXPIRED
    ValidationReason.INVALID_CVV -> CardEntryStringKey.ERROR_INVALID_CVV
    ValidationReason.INCOMPLETE_CVV -> CardEntryStringKey.ERROR_INCOMPLETE_CVV
    ValidationReason.HOLDER_TOO_LONG -> CardEntryStringKey.ERROR_HOLDER_TOO_LONG
    ValidationReason.NETWORK_NOT_AUTHORIZED -> CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED
}
