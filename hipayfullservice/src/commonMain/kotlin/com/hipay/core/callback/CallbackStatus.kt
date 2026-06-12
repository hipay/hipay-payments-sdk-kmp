package com.hipay.core.callback

/**
 * Redirect outcome carried by the return deep link. Informational only —
 * the final transaction state MUST be confirmed via
 * `GatewayClient.getTransaction` (FR9: never trust redirect params alone).
 */
public enum class CallbackStatus {
    ACCEPT,
    DECLINE,
    PENDING,
    EXCEPTION,
    CANCEL,
}
