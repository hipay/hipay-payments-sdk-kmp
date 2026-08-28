// PCI: com.hipay.card path — NEVER log here.
package com.hipay.card.store

import com.hipay.core.HiPayConfig

/**
 * The thin per-platform secure-storage primitive: one namespaced entry holding the serialized
 * saved-card set. Implementations live in the card-feature modules — Android
 * Keystore+DataStore, iOS Keychain (CMP Kotlin/Native + native Swift). It is a plain `interface`
 * (NOT `expect`/`actual`) on purpose: that keeps the platform storage deps OUT of the core
 * and the [SecureCardStore] logic single-sourced. The implementation is injected into [SecureCardStore].
 */
public interface RawSecureStore {
    /** The stored blob, or null if absent. Implementations SHOULD return null on a read failure rather than throw. */
    public fun read(): String?

    /** Persist the blob, overwriting any previous value. */
    public fun write(value: String)

    /** Remove the entry entirely. */
    public fun clear()
}

/**
 * Per-merchant, per-environment namespace key for the secure entry: STAGE/PRODUCTION and different
 * merchant accounts never collide. The `username` is **length-prefixed** so a value containing dots
 * (or an empty one) can never be confused with another key — the mapping is injective on
 * (environment, username), preventing cross-merchant card bleed. A `v1` segment allows a future
 * key-format migration. The card module passes this to its [RawSecureStore] implementation when
 * keying the Keychain/Keystore entry.
 */
public fun secureCardStoreNamespace(config: HiPayConfig): String {
    val user = config.username
    return "com.hipay.savedcards.v1.${config.environment.name}.${user.length}.$user"
}
