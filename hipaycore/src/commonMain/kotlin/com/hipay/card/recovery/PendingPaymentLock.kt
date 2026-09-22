// PCI: com.hipay.card path — NEVER log here.
package com.hipay.card.recovery

/**
 * One process-wide lock, shared by every [PendingPaymentStore].
 *
 * Several stores exist at once by design — the one a payment writes through, the one a host reads its
 * list from — each on its own thread and none aware of the others. Every mutation rewrites the whole
 * envelope, and even a read rewrites it when it prunes expired entries, so two of them interleaving
 * lose an update: a payment settling while the host has the list open could drop either write.
 *
 * Recursive, so one entry point calling another cannot deadlock on itself.
 */
internal expect fun <T> withPendingPaymentLock(block: () -> T): T
