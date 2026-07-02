package com.hipay.card.store

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test — exercises the REAL Android Keystore + DataStore (no fake). Runs on a connected
 * device/emulator (API <= 35 per the module's CI note). The pure store logic is covered by the shared
 * commonTest; here we verify the platform primitive round-trips through encrypted persistence and
 * self-heals on the failure paths (tampered/malformed/key-less entries).
 */
@RunWith(AndroidJUnit4::class)
class AndroidSecureCardStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "test.hipay.savedcards.${System.nanoTime()}"
    private val store = AndroidSecureCardStore(context, namespace)

    @After
    fun tearDown() {
        store.clear()
        store.deleteKey()
    }

    // Direct access to the raw persisted value, bypassing the store (same DataStore instance —
    // the delegate is process-wide, so this does not violate the single-instance rule).
    private fun rawStored(ns: String = namespace): String? =
        runBlocking { context.savedCardsDataStore.data.first()[stringPreferencesKey(ns)] }

    private fun seedRaw(value: String, ns: String = namespace) =
        runBlocking { context.savedCardsDataStore.edit { it[stringPreferencesKey(ns)] = value } }

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
    fun encrypted_round_trip_of_a_realistic_envelope_blob() {
        val blob = """{"version":1,"seq":1,"cards":[]}"""
        store.write(blob)
        assertEquals(blob, store.read())
    }

    @Test
    fun distinct_namespaces_are_isolated() {
        val other = AndroidSecureCardStore(context, "other.$namespace")
        store.write("mine")
        try {
            assertNull(other.read())
        } finally {
            other.clear()
            other.deleteKey()
        }
    }

    @Test
    fun stored_value_is_ciphertext_not_plaintext() {
        val blob = """{"version":1,"seq":7,"cards":[]}"""
        store.write(blob)
        val atRest = rawStored()
        assertNotNull(atRest)
        assertFalse(atRest == blob)
        assertFalse(atRest!!.contains("cards"))
    }

    @Test
    fun tampered_ciphertext_reads_null_and_self_heals() {
        store.write("sensitive")
        val stored = rawStored()!!
        val (iv, ct) = stored.split(":", limit = 2)
        val flipped = if (ct[1] == 'A') 'B' else 'A'
        seedRaw("$iv:${ct[0]}$flipped${ct.substring(2)}")
        assertNull(store.read())
        assertNull(rawStored()) // dead entry purged
        store.write("fresh") // re-keys and works again
        assertEquals("fresh", store.read())
    }

    @Test
    fun malformed_blob_without_separator_reads_null_and_purges_the_entry() {
        seedRaw("garbage-without-separator")
        assertNull(store.read())
        assertNull(rawStored())
    }

    @Test
    fun non_base64_blob_reads_null_and_purges_the_entry() {
        seedRaw("%%%:@@@")
        assertNull(store.read())
        assertNull(rawStored())
    }

    @Test
    fun blob_without_key_reads_null_purges_and_recovers() {
        store.write("orphan")
        store.deleteKey() // simulates a backup-restored ciphertext: blob present, key absent
        assertNull(store.read())
        assertNull(rawStored()) // undecryptable entry purged
        store.write("recovered")
        assertEquals("recovered", store.read())
    }

    @Test
    fun first_launch_purge_clears_every_namespace_once() {
        val config = HiPayConfig("test-user", "pw", Environment.STAGE)
        val configNs = secureCardStoreNamespace(config)
        try {
            runBlocking { context.savedCardsDataStore.edit { it.remove(LAUNCHED_FLAG) } }
            store.write("residual")
            createSecureCardStore(context, config)
            assertNull(store.read()) // purge swept ALL namespaces, not just the config's
            store.write("kept")
            createSecureCardStore(context, config) // flag now set — no second purge
            assertEquals("kept", store.read())
        } finally {
            val configStore = AndroidSecureCardStore(context, configNs)
            configStore.clear()
            configStore.deleteKey()
        }
    }

    @Test
    fun factory_rejects_the_main_thread() {
        val config = HiPayConfig("test-user", "pw", Environment.STAGE)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertThrows(IllegalStateException::class.java) {
                createSecureCardStore(context, config)
            }
        }
    }
}
