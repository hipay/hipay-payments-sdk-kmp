// PCI: com.hipay.card path — NEVER log here.
package com.hipay.card.recovery

import java.util.concurrent.locks.ReentrantLock

// Held by an object rather than a file-level property: an object initializes on first access, which
// is also what keeps the iOS actual clear of the release-framework file-initializer issue.
private object Lock {
    val reentrant = ReentrantLock()
}

internal actual fun <T> withPendingPaymentLock(block: () -> T): T {
    Lock.reentrant.lock()
    try {
        return block()
    } finally {
        Lock.reentrant.unlock()
    }
}
