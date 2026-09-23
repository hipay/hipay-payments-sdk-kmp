// PCI: com.hipay.card path — NEVER log here.
package com.hipay.card.recovery

import platform.Foundation.NSRecursiveLock

// An object, not a file-level property: a file-level `val` has been observed null through the ObjC
// bridge in a release framework, and a lock that is null locks nothing.
private object Lock {
    val recursive = NSRecursiveLock()
}

internal actual fun <T> withPendingPaymentLock(block: () -> T): T {
    Lock.recursive.lock()
    try {
        return block()
    } finally {
        Lock.recursive.unlock()
    }
}
