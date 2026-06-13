package com.hipay.card.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StringKeyMappingTest {

    @Test
    fun onlyValidHasNoMessageKey() {
        assertNull(ValidationReason.VALID.messageKey())
    }

    @Test
    fun everyNonValidReasonHasAMessageKey() {
        // The cross-platform guard's commonMain half: any reason the UI shows
        // must resolve to a key (5.2 then asserts each key has FR/EN/IT values).
        for (reason in ValidationReason.entries) {
            if (reason == ValidationReason.VALID) continue
            assertNotNull(reason.messageKey(), "missing message key for $reason")
        }
    }

    @Test
    fun messageKeysAreDistinctPerReason() {
        val keys = ValidationReason.entries
            .filter { it != ValidationReason.VALID }
            .map { it.messageKey() }
        assertEquals(keys.size, keys.toSet().size, "two reasons map to the same key")
    }
}
