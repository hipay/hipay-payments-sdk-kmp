// PCI: com.hipay.card path — never log here.
package com.hipay.card.cmp

import com.hipay.card.store.SecureCardStore
import com.hipay.core.HiPayConfig

/**
 * Platform saved-card store for [CmpCardController] (iOS only at runtime): the
 * iOS actual wires the Keychain-backed factory. Android never calls this — the
 * Android CMP `HiPayCardController` delegates one-click (like everything else)
 * to the native `:hipaycard` controller, which owns its own store.
 */
internal expect fun createCmpSecureCardStore(config: HiPayConfig): SecureCardStore
