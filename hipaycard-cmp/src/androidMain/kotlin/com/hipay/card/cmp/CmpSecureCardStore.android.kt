// PCI: com.hipay.card path — never log here.
package com.hipay.card.cmp

import com.hipay.card.store.SecureCardStore
import com.hipay.core.HiPayConfig

/**
 * Android actual: never called. The Android CMP `HiPayCardController` delegates
 * to the native `:hipaycard` controller (which owns the Keystore-backed store),
 * so `CmpCardController` — and therefore this factory — is never instantiated
 * on Android (same rationale as the `CmpThreeDSLauncher` Android actual).
 */
internal actual fun createCmpSecureCardStore(config: HiPayConfig): SecureCardStore =
    throw UnsupportedOperationException(
        "Android CMP one-click goes through the native :hipaycard controller",
    )
