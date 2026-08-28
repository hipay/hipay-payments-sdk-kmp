package com.hipay.card.store

import kotlin.test.Test
import kotlin.test.assertEquals

class SavedCardsDisplayCountTest {

    @Test fun default_is_three() {
        assertEquals(3, DEFAULT_SAVED_CARDS_DISPLAY_COUNT)
    }

    @Test fun bounds_are_one_to_ten() {
        assertEquals(1, MIN_SAVED_CARDS_DISPLAY_COUNT)
        assertEquals(10, MAX_SAVED_CARDS_DISPLAY_COUNT)
    }

    @Test fun clamps_below_one_to_one() {
        assertEquals(1, coerceSavedCardsDisplayCount(0))
        assertEquals(1, coerceSavedCardsDisplayCount(-5))
    }

    @Test fun clamps_above_ten_to_ten() {
        assertEquals(10, coerceSavedCardsDisplayCount(11))
        assertEquals(10, coerceSavedCardsDisplayCount(999))
    }

    @Test fun passes_values_in_range_unchanged() {
        (1..10).forEach { assertEquals(it, coerceSavedCardsDisplayCount(it)) }
    }
}
