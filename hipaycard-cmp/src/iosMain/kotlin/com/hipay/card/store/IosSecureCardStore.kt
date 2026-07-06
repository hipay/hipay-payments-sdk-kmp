// PCI: com.hipay.card path — NEVER log here, never expose the raw PAN or token.
package com.hipay.card.store

import com.hipay.core.HiPayConfig
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

// One generic-password item per namespace: FIXED service + namespace as the account. The fixed
// service keeps every namespace format-compatible with the iOS-native Swift primitive and lets the
// first-launch purge sweep all namespaces with a single delete.
internal const val SAVED_CARDS_SERVICE = "com.hipay.savedcards"
internal const val SAVED_CARDS_LAUNCHED_KEY = "com.hipay.savedcards.launched"

/**
 * CMP-iOS [RawSecureStore]: the store's serialized blob as a Keychain generic-password item —
 * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, so it is device-bound (never migrates via
 * backup/transfer) and never iCloud-synced (`ThisDeviceOnly` is inherently non-synchronizable).
 * The OS encrypts Keychain items at rest — no SDK-level crypto layer is needed (unlike Android).
 *
 * Failure handling on read is two-tier, never throws, never logs:
 *  - dead entry (item data not decodable as UTF-8) → purge the item so a later write starts clean;
 *  - transient status (e.g. `errSecInteractionNotAllowed` before the first unlock after boot —
 *    REAL under the `AfterFirstUnlock` class) → null WITHOUT deleting (the data is likely intact).
 *
 * `write`/`clear` throw on an unexpected status so the store's `runCatching` reports the failed
 * mutation (`save`/`clearAll` return false — the deletion stays verifiable). Keychain calls are
 * synchronous in-process ops (no blocking-I/O bridge) — but the store contract still confines
 * usage to a single background thread. Not thread-safe.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosSecureCardStore(
    private val namespace: String,
) : RawSecureStore {

    override fun read(): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withItemQuery(
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        ) { query -> SecItemCopyMatching(query, result.ptr) }
        when (status) {
            errSecSuccess -> {
                val text = (CFBridgingRelease(result.value) as? NSData)?.decodeUtf8OrNull()
                if (text == null) {
                    // Dead entry — purge it so the next write starts clean.
                    runCatching { clear() }
                }
                text
            }
            errSecItemNotFound -> null
            else -> null // transient (e.g. device not yet unlocked) — keep the item
        }
    }

    override fun write(value: String) {
        val cfData = CFBridgingRetain(value.toUtf8NSData())
        try {
            var status = updateItem(cfData)
            if (status == errSecItemNotFound) {
                status = withItemQuery(
                    kSecValueData to cfData,
                    kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                ) { attributes -> SecItemAdd(attributes, null) }
                // Lost the add race against a concurrent writer — the item exists now: update it.
                if (status == errSecDuplicateItem) status = updateItem(cfData)
            }
            check(status == errSecSuccess) { "keychain write failed (status $status)" }
        } finally {
            CFBridgingRelease(cfData)
        }
    }

    /** Update half of the upsert — re-asserts the protection class so pre-existing items (created
     *  without it, or restored precisely because they lacked `ThisDeviceOnly`) get upgraded. */
    private fun updateItem(cfData: CFTypeRef?) = withItemQuery { query ->
        memScoped {
            SecItemUpdate(
                query,
                cfDictionary(
                    kSecValueData to cfData,
                    kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                ),
            )
        }
    }

    override fun clear() {
        val status = withItemQuery { query -> SecItemDelete(query) }
        check(status == errSecSuccess || status == errSecItemNotFound) {
            "keychain delete failed (status $status)"
        }
    }

    /** Runs [block] with the item query (service + this namespace's account [+ extras]). */
    private inline fun <T> withItemQuery(
        vararg extras: Pair<CFTypeRef?, CFTypeRef?>,
        block: (CFDictionaryRef?) -> T,
    ): T {
        val cfService = CFBridgingRetain(SAVED_CARDS_SERVICE)
        val cfAccount = CFBridgingRetain(namespace)
        try {
            return memScoped {
                block(
                    cfDictionary(
                        kSecClass to kSecClassGenericPassword,
                        kSecAttrService to cfService,
                        kSecAttrAccount to cfAccount,
                        *extras,
                    ),
                )
            }
        } finally {
            CFBridgingRelease(cfAccount)
            CFBridgingRelease(cfService)
        }
    }
}

/**
 * A CF dictionary valid within the surrounding [MemScope] call; keys/values must stay retained by
 * the caller for its lifetime (no CF retain callbacks — the kSec constants and bridged values are).
 * NULL key callbacks also mean POINTER-IDENTITY key lookup: keys must be the kSec singleton
 * constants, never a rebuilt/bridged string.
 */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.cfDictionary(vararg pairs: Pair<CFTypeRef?, CFTypeRef?>): CFDictionaryRef? {
    val keys = allocArray<CFTypeRefVar>(pairs.size)
    val values = allocArray<CFTypeRefVar>(pairs.size)
    pairs.forEachIndexed { index, (key, value) ->
        keys[index] = key
        values[index] = value
    }
    val dictionary = CFDictionaryCreate(kCFAllocatorDefault, keys, values, pairs.size.convert(), null, null)
    defer { dictionary?.let { CFRelease(it) } }
    return dictionary
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun String.toUtf8NSData(): NSData {
    val bytes = encodeToByteArray()
    if (bytes.isEmpty()) return NSData()
    return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
}

/** Strict UTF-8 decode: null marks a dead (undecodable) entry. */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.decodeUtf8OrNull(): String? {
    val size = length.toInt()
    if (size == 0) return ""
    val source = bytes ?: return null
    val copy = ByteArray(size)
    copy.usePinned { memcpy(it.addressOf(0), source, length) }
    return runCatching { copy.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
}

/**
 * One-time sweep of every saved-card item (all namespaces) under the fixed service.
 * True iff the sweep is known complete (success, or nothing to purge).
 */
@OptIn(ExperimentalForeignApi::class)
internal fun purgeAllSavedCardItems(): Boolean {
    val cfService = CFBridgingRetain(SAVED_CARDS_SERVICE)
    try {
        return memScoped {
            val status = SecItemDelete(
                cfDictionary(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfService,
                ),
            )
            status == errSecSuccess || status == errSecItemNotFound
        }
    } finally {
        CFBridgingRelease(cfService)
    }
}

/**
 * Assemble a ready [SecureCardStore] for CMP-iOS: the Keychain [IosSecureCardStore] + an
 * `NSCalendar`-based clock. Runs a one-time first-launch purge of ALL namespaces — the iOS Keychain
 * survives app uninstall, so without it a reinstall would resurrect the previous install's saved
 * cards; the `NSUserDefaults` flag IS wiped on uninstall, which is exactly the fresh-install
 * detector. [HiPayConfig] carries no one-click reference — enabling one-click is the UI layer's
 * opt-in. Call from a single background thread (the store it returns is not thread-safe).
 */
// Serializes the first-launch check-purge-arm section across concurrent factory calls.
private val firstLaunchLock = NSLock()

public fun createSecureCardStore(config: HiPayConfig): SecureCardStore {
    val defaults = NSUserDefaults.standardUserDefaults
    firstLaunchLock.lock()
    try {
        // The flag is armed ONLY after a successful sweep: a transient failure (e.g. a background
        // first launch before the device's first unlock) leaves it unset, so the purge retries on
        // the next launch instead of latching stale items in forever.
        if (!defaults.boolForKey(SAVED_CARDS_LAUNCHED_KEY) && purgeAllSavedCardItems()) {
            defaults.setBool(true, SAVED_CARDS_LAUNCHED_KEY)
        }
    } finally {
        firstLaunchLock.unlock()
    }
    return SecureCardStore(
        IosSecureCardStore(secureCardStoreNamespace(config)),
        currentYearMonth = { iosYearMonth() },
    )
}

private fun iosYearMonth(): YearMonth {
    // Pinned Gregorian: the user's calendar (Thai Buddhist year 2569, Japanese era…) would put the
    // year outside the store's plausibility window and silently disable the expiry purge.
    val calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian)
    val components = calendar.components(
        NSCalendarUnitYear or NSCalendarUnitMonth,
        fromDate = NSDate(),
    )
    return YearMonth(components.year.toInt(), components.month.toInt())
}
