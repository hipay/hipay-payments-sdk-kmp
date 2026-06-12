package com.hipay.core.gateway.model

/**
 * Transaction state — the single source of truth (architecture pattern:
 * comparing state strings outside this mapper is forbidden).
 */
public enum class TransactionState {
    COMPLETED,
    FORWARDING,
    PENDING,
    DECLINED,
    ERROR,
    ;

    public companion object {
        /** Unknown values fall back to ERROR (legacy mapper default). */
        public fun fromWire(state: String): TransactionState = when (state) {
            "completed" -> COMPLETED
            "forwarding" -> FORWARDING
            "pending" -> PENDING
            "declined" -> DECLINED
            else -> ERROR
        }
    }
}
