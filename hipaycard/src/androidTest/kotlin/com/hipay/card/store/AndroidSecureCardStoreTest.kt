package com.hipay.card.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test — exercises the REAL Android Keystore + DataStore (no fake). Runs on a connected
 * device/emulator (API <= 35 per the module's CI note). The pure store logic is covered by the shared
 * commonTest; here we verify the platform primitive round-trips through encrypted persistence.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSecureCardStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "test.hipay.savedcards.${System.nanoTime()}"
    private val store = AndroidSecureCardStore(context, namespace)

    @After
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
        }
    }
}
