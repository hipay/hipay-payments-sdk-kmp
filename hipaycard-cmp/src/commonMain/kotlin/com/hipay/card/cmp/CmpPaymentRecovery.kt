// PCI: com.hipay.card path — never log here.
package com.hipay.card.cmp

import androidx.compose.runtime.Composable
import com.hipay.card.recovery.DEFAULT_PENDING_PAYMENT_TTL_MILLIS
import com.hipay.card.recovery.DEFAULT_RESOLVED_PAYMENT_TTL_MILLIS
import com.hipay.card.recovery.HiPayPaymentRecovery
import com.hipay.card.recovery.PendingPaymentStore
import com.hipay.core.HiPayConfig

/**
 * The recovery entry point on Compose Multiplatform, remembered for the composition that holds it.
 *
 * A composable rather than a plain factory because Android needs a platform context and Compose is
 * where a Multiplatform host can reach one. Call it from a screen the host shows at launch: after a
 * process death there is no card component left to ask.
 */
@Composable
public expect fun rememberHiPayPaymentRecovery(
    config: HiPayConfig,
    pendingTtlMillis: Long = DEFAULT_PENDING_PAYMENT_TTL_MILLIS,
    resolvedTtlMillis: Long = DEFAULT_RESOLVED_PAYMENT_TTL_MILLIS,
): HiPayPaymentRecovery

/** The store [CmpCardController] writes to, mirroring [createCmpSecureCardStore]. */
internal expect fun createCmpPendingPaymentStore(config: HiPayConfig): PendingPaymentStore
