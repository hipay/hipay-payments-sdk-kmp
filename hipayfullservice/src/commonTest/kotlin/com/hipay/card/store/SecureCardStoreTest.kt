package com.hipay.card.store

import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory [RawSecureStore] fake — lets the whole store logic run in commonTest with no platform. */
private class FakeRawSecureStore(
    var blob: String? = null,
    private val failRead: Boolean = false,
) : RawSecureStore {
    override fun read(): String? {
        if (failRead) throw RuntimeException("storage unavailable")
        return blob
    }
    override fun write(value: String) { blob = value }
    override fun clear() { blob = null }
}

class SecureCardStoreTest {

    private val jan2030: () -> YearMonth = { YearMonth(2030, 1) }

    private fun card(
        pan: String = "411111xxxxxx1111",
        month: String = "12",
        year: String = "2030",
        holder: String = "JANE DOE",
        token: String = "tok-1",
        network: String = "visa",
    ) = SavedCard(token, pan, network, holder, month, year)

    private fun store(
        raw: RawSecureStore = FakeRawSecureStore(),
        now: () -> YearMonth = jan2030,
    ) = SecureCardStore(raw, now)

    // AC4 — consent enforced at the store
    @Test fun saves_only_with_consent() {
        val raw = FakeRawSecureStore()
        val s = store(raw)
        s.save(card(), consentGiven = false)
        assertTrue(s.list().isEmpty())
        assertNull(raw.blob)
        s.save(card(), consentGiven = true)
        assertEquals(1, s.list().size)
    }

    // AC3 — overwrite-on-match by maskedPan + expiry
    @Test fun overwrites_on_matching_maskedpan_and_expiry() {
        val s = store()
        s.save(card(holder = "OLD", token = "t1"), true)
        s.save(card(holder = "NEW", token = "t2"), true)
        val list = s.list()
        assertEquals(1, list.size)
        assertEquals("NEW", list[0].holder)
        assertEquals("t2", list[0].token)
    }

    @Test fun different_expiry_is_a_distinct_card() {
        val s = store()
        s.save(card(year = "2030"), true)
        s.save(card(year = "2031"), true)
        assertEquals(2, s.list().size)
    }

    // AC5 — cap 3 + LRU
    @Test fun caps_at_three_evicting_least_recently_used() {
        val s = store()
        s.save(card(pan = "1", token = "a"), true)
        s.save(card(pan = "2", token = "b"), true)
        s.save(card(pan = "3", token = "c"), true)
        s.save(card(pan = "4", token = "d"), true)
        val pans = s.list().map { it.maskedPan }.toSet()
        assertEquals(3, pans.size)
        assertFalse("1" in pans)
        assertTrue("4" in pans)
    }

    @Test fun touch_updates_recency_so_lru_evicts_the_truly_oldest() {
        val s = store()
        s.save(card(pan = "1"), true)
        s.save(card(pan = "2"), true)
        s.save(card(pan = "3"), true)
        s.touch(s.list().first { it.maskedPan == "1" }) // 1 becomes most-recent
        s.save(card(pan = "4"), true)                    // evicts 2 (now oldest), not 1
        val pans = s.list().map { it.maskedPan }.toSet()
        assertTrue("1" in pans)
        assertFalse("2" in pans)
    }

    @Test fun list_is_most_recently_used_first() {
        val s = store()
        s.save(card(pan = "1"), true)
        s.save(card(pan = "2"), true)
        assertEquals(listOf("2", "1"), s.list().map { it.maskedPan })
    }

    // AC6 — expired auto-purge on load
    @Test fun purges_expired_on_load() {
        val raw = FakeRawSecureStore()
        val s = store(raw, now = { YearMonth(2030, 6) })
        s.save(card(month = "01", year = "2030", pan = "old"), true)
        s.save(card(month = "12", year = "2030", pan = "live"), true)
        assertEquals(listOf("live"), s.list().map { it.maskedPan })
    }

    @Test fun expiry_in_the_current_month_is_still_valid() {
        val s = store(now = { YearMonth(2030, 12) })
        s.save(card(month = "12", year = "2030"), true)
        assertEquals(1, s.list().size)
    }

    // AC8 — fail-soft on corrupt blob
    @Test fun corrupt_blob_fails_soft_to_empty() {
        val raw = FakeRawSecureStore(blob = "not-json{{{")
        assertTrue(store(raw).list().isEmpty())
    }

    // AC2 — fail-closed on unknown version
    @Test fun unknown_version_fails_closed() {
        val raw = FakeRawSecureStore(blob = """{"version":999,"seq":1,"cards":[]}""")
        assertTrue(store(raw).list().isEmpty())
    }

    // AC8 — read failure degrades to empty, never throws
    @Test fun read_failure_degrades_to_empty_without_throwing() {
        val raw = FakeRawSecureStore(failRead = true)
        assertEquals(emptyList(), store(raw).list())
    }

    @Test fun clearAll_removes_everything() {
        val raw = FakeRawSecureStore()
        val s = store(raw)
        s.save(card(), true)
        s.clearAll()
        assertTrue(s.list().isEmpty())
        assertNull(raw.blob)
    }

    @Test fun delete_removes_matching_card_only() {
        val s = store()
        s.save(card(pan = "1"), true)
        s.save(card(pan = "2"), true)
        s.delete(s.list().first { it.maskedPan == "1" })
        assertEquals(listOf("2"), s.list().map { it.maskedPan })
    }

    // AC7 — per-merchant + per-environment namespacing
    @Test fun namespace_is_per_merchant_and_environment() {
        val stageA = HiPayConfig("user-A", "pw", Environment.STAGE)
        val prodA = HiPayConfig("user-A", "pw", Environment.PRODUCTION)
        val stageB = HiPayConfig("user-B", "pw", Environment.STAGE)
        assertNotEquals(secureCardStoreNamespace(stageA), secureCardStoreNamespace(prodA))
        assertNotEquals(secureCardStoreNamespace(stageA), secureCardStoreNamespace(stageB))
        // stable regardless of the password
        assertEquals(
            secureCardStoreNamespace(stageA),
            secureCardStoreNamespace(HiPayConfig("user-A", "other-pw", Environment.STAGE)),
        )
    }

    // AC1 — token never exposed via toString
    @Test fun toString_never_exposes_the_token() {
        assertFalse(card(token = "SECRET-TOKEN").toString().contains("SECRET-TOKEN"))
    }

    // AC1/AC2 — round-trips through (de)serialization across store instances (persistence proof)
    @Test fun survives_a_new_store_instance_over_the_same_raw() {
        val raw = FakeRawSecureStore()
        store(raw).save(card(pan = "411111xxxxxx9999", token = "keep"), true)
        val reopened = store(raw).list()
        assertEquals(1, reopened.size)
        assertEquals("keep", reopened[0].token)
    }
}
