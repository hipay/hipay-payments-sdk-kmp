package com.hipay.core.monitoring

/**
 * Marks SDK-internal plumbing that has to be public only because Kotlin `internal` does not cross
 * module boundaries. Not part of the integration contract, and not covered by the compatibility
 * promise: it may change or disappear in any release.
 */
@RequiresOptIn(
    message = "SDK-internal API: not part of the HiPay integration contract and may change at any time.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
public annotation class HiPayInternalApi
