// PCI: com.hipay.card path — never log here.
package com.hipay.card.applepay

/**
 * Double-tap guard: while an Apple Pay payment is in flight, a second tap must present no second
 * sheet — a gap the legacy SDK had. The payment entry point acquires the slot with [tryBegin] before
 * presenting the sheet and releases it with [end] when the flow finishes (success, failure, or
 * cancel), so the guarantee holds without the host having to implement anything.
 *
 * Internal on purpose: a second, host-owned guard would be a competing source of truth for the same
 * rule. Acquire/release happen on the main thread (where PassKit is driven), so a plain flag is
 * sufficient — no atomics.
 */
internal class PaymentInFlightGuard {
    private var inFlight = false

    /** Acquires the slot: returns `true` if the caller may present the sheet, `false` if a payment
     *  is already in flight (ignore this tap). */
    fun tryBegin(): Boolean {
        if (inFlight) return false
        inFlight = true
        return true
    }

    /** Releases the slot once the flow ends (success, decline, or cancel). */
    fun end() {
        inFlight = false
    }
}
