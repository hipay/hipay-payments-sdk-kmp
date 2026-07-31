// PCI: com.hipay.card path — never log here.
package com.hipay.card.applepay

/**
 * Double-tap guard (AC8): while an Apple Pay payment is in flight, a second tap must present no
 * second sheet — a gap the legacy SDK had. The per-channel presentation calls [tryBegin] before
 * presenting the sheet and [end] when the flow finishes (success, failure, or cancel).
 *
 * Taps are handled on the main thread, so a plain flag is sufficient (no atomics).
 */
public class PaymentInFlightGuard {
    private var inFlight = false

    /** Acquires the slot: returns `true` if the caller may present the sheet, `false` if a payment
     *  is already in flight (ignore this tap). */
    public fun tryBegin(): Boolean {
        if (inFlight) return false
        inFlight = true
        return true
    }

    /** Releases the slot once the flow ends (success, decline, or cancel). */
    public fun end() {
        inFlight = false
    }
}
