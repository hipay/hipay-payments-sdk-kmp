package com.hipay.card.store

import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
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
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Runs against the REAL simulator Keychain (no fake). The pure store logic is covered by the shared
 * commonTest; here we verify the platform primitive round-trips through the Keychain and self-heals
 * on the failure paths (undecodable entry, uninstall residue via the first-launch purge).
 */
class IosSecureCardStoreTest {

    private val namespace = "test.hipay.savedcards.${Random.nextLong().toULong()}"
    private val store = IosSecureCardStore(namespace)

    @AfterTest
    fun tearDown() {
        store.clear()
    }

    @Test
    fun missing_entry_reads_null() {
        assertNull(store.read())
    }

    @Test
    fun round_trip_write_then_read() {
        store.write("hello-blob")
        assertEquals("hello-blob", store.read())
    }

    @Test
    fun round_trip_preserves_non_ascii_content() {
        val blob = """{"holder":"Émilie Müller — 你好"}"""
        store.write(blob)
        assertEquals(blob, store.read())
    }

    @Test
    fun write_overwrites_previous_value() {
        store.write("v1")
        store.write("v2")
        assertEquals("v2", store.read())
    }

    @Test
    fun clear_removes_the_entry() {
        store.write("x")
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun clear_on_missing_entry_is_a_clean_no_op() {
        store.clear() // must not throw (errSecItemNotFound is success for the contract)
        assertNull(store.read())
    }

    @Test
    fun round_trip_of_a_realistic_envelope_blob() {
        val blob = """{"version":1,"seq":1,"cards":[]}"""
        store.write(blob)
        assertEquals(blob, store.read())
    }

    @Test
    fun distinct_namespaces_are_isolated() {
        val other = IosSecureCardStore("other.$namespace")
        store.write("mine")
        try {
            assertNull(other.read())
        } finally {
            other.clear()
        }
    }

    @Test
    fun undecodable_entry_reads_null_and_purges_itself() {
        seedRawBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0xC3.toByte()))
        assertNull(store.read())
        assertFalse(rawItemExists()) // dead entry purged
        store.write("recovered")
        assertEquals("recovered", store.read())
    }

    @Test
    fun first_launch_purge_clears_every_namespace_once() {
        val config = HiPayConfig("test-user", "pw", Environment.STAGE)
        val configStore = IosSecureCardStore(secureCardStoreNamespace(config))
        val defaults = NSUserDefaults.standardUserDefaults
        val hadFlag = defaults.boolForKey(SAVED_CARDS_LAUNCHED_KEY)
        try {
            defaults.removeObjectForKey(SAVED_CARDS_LAUNCHED_KEY)
            store.write("residual")
            createSecureCardStore(config)
            assertNull(store.read()) // the purge swept ALL namespaces, not just the config's
            assertTrue(defaults.boolForKey(SAVED_CARDS_LAUNCHED_KEY))
            store.write("kept")
            createSecureCardStore(config) // flag now set — no second purge
            assertEquals("kept", store.read())
        } finally {
            configStore.clear()
            if (hadFlag) defaults.setBool(true, SAVED_CARDS_LAUNCHED_KEY)
        }
    }

    @Test
    fun factory_returns_a_working_store() {
        val config = HiPayConfig("test-user", "pw", Environment.STAGE)
        val secureStore = createSecureCardStore(config)
        val configStore = IosSecureCardStore(secureCardStoreNamespace(config))
        try {
            assertTrue(secureStore.list().isEmpty()) // fresh namespace + real clock: no crash, no cards
        } finally {
            configStore.clear()
        }
    }

    // --- raw Keychain access (bypasses the store) ---

    @OptIn(ExperimentalForeignApi::class)
    private fun seedRawBytes(bytes: ByteArray) {
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
        val cfService = CFBridgingRetain(SAVED_CARDS_SERVICE)
        val cfAccount = CFBridgingRetain(namespace)
        val cfData = CFBridgingRetain(data)
        try {
            memScoped {
                val keys = allocArray<CFTypeRefVar>(4)
                val values = allocArray<CFTypeRefVar>(4)
                listOf<Pair<CFTypeRef?, CFTypeRef?>>(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfService,
                    kSecAttrAccount to cfAccount,
                    kSecValueData to cfData,
                ).forEachIndexed { i, (k, v) ->
                    keys[i] = k
                    values[i] = v
                }
                val attrs = CFDictionaryCreate(kCFAllocatorDefault, keys, values, 4, null, null)
                val status = SecItemAdd(attrs, null)
                attrs?.let { CFRelease(it) }
                check(status == errSecSuccess) { "test seed failed (status $status)" }
            }
        } finally {
            CFBridgingRelease(cfData)
            CFBridgingRelease(cfAccount)
            CFBridgingRelease(cfService)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun rawItemExists(): Boolean {
        val cfService = CFBridgingRetain(SAVED_CARDS_SERVICE)
        val cfAccount = CFBridgingRetain(namespace)
        try {
            return memScoped {
                val keys = allocArray<CFTypeRefVar>(5)
                val values = allocArray<CFTypeRefVar>(5)
                listOf<Pair<CFTypeRef?, CFTypeRef?>>(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfService,
                    kSecAttrAccount to cfAccount,
                    kSecReturnData to kCFBooleanTrue,
                    kSecMatchLimit to kSecMatchLimitOne,
                ).forEachIndexed { i, (k, v) ->
                    keys[i] = k
                    values[i] = v
                }
                val query = CFDictionaryCreate(kCFAllocatorDefault, keys, values, 5, null, null)
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                query?.let { CFRelease(it) }
                if (status == errSecSuccess) CFBridgingRelease(result.value)
                status != errSecItemNotFound
            }
        } finally {
            CFBridgingRelease(cfAccount)
            CFBridgingRelease(cfService)
        }
    }
}
