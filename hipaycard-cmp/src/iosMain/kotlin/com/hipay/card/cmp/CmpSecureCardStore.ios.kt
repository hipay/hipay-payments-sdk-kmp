// PCI: com.hipay.card path — never log here.
package com.hipay.card.cmp

import com.hipay.card.store.SecureCardStore
import com.hipay.card.store.createSecureCardStore
import com.hipay.core.HiPayConfig

/** iOS actual: the Keychain-backed factory (fixed saved-cards service, first-launch purge). */
internal actual fun createCmpSecureCardStore(config: HiPayConfig): SecureCardStore =
    createSecureCardStore(config)
