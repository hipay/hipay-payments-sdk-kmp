// PCI: com.hipay.card path — NEVER log here.
package com.hipay.card.recovery

import com.hipay.card.store.RawSecureStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Real threads, so JVM-only: the point is an actual race between two stores sharing one blob — the
 * shape the reader and a payment in flight have on a device — not a simulated one.
 */
class PendingPaymentConcurrencyTest {

    /** One blob behind every store, as the platform primitive behaves for a given namespace. */
    private class SharedRaw : RawSecureStore {
        @Volatile
        var blob: String? = null

        override fun read(): String? = blob

        override fun write(value: String) {
            // Widens the read-modify-write window: without the shared lock, this test loses entries.
            Thread.sleep(1)
            blob = value
        }

        override fun clear() {
            blob = null
        }
    }

    @Test
    fun paymentsRecordedAtOnceFromSeveralStoresLoseNothing() {
        val raw = SharedRaw()
        val writers = (0 until 8).map { writer ->
            Thread {
                val store = PendingPaymentStore(raw, { 1_000L + writer })
                repeat(5) { n -> store.record("ORDER-$writer-$n", "1.00", "EUR") }
            }
        }
        writers.forEach { it.start() }
        writers.forEach { it.join() }

        assertEquals(40, PendingPaymentStore(raw, { 1_000L }).unresolvedPayments().size)
    }

    @Test
    fun anOutcomeWrittenWhileTheListIsReadIsNotLost() {
        val raw = SharedRaw()
        val writer = PendingPaymentStore(raw, { 1_000L })
        repeat(20) { n -> writer.record("ORDER-$n", "1.00", "EUR") }

        val reader = PendingPaymentStore(raw, { 1_000L })
        val reading = Thread { repeat(20) { reader.unresolvedPayments() } }
        val completing = Thread {
            repeat(20) { n -> writer.complete("ORDER-$n", "REF-$n", com.hipay.core.gateway.model.TransactionState.COMPLETED) }
        }
        reading.start(); completing.start()
        reading.join(); completing.join()

        val settled = reader.unresolvedPayments()
        assertEquals(20, settled.size)
        assertEquals(20, settled.count { it.referenceKnown }, "every outcome survived the concurrent reads")
    }
}
