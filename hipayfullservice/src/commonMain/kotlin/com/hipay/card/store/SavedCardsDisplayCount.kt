// PCI: com.hipay.card path — NEVER log here.
package com.hipay.card.store

/**
 * Saved-cards DISPLAY count (story 12-9) — how many saved cards a one-click component shows before a
 * "Show more" control. This is distinct from the STORAGE cap (`SecureCardStore`, 20): storage keeps
 * every valid card; the display count only bounds what is shown by default.
 *
 * Single-sourced here so Android / CMP / iOS apply the SAME default and the SAME bounds.
 */

/** Default number of saved cards shown before "Show more". */
public const val DEFAULT_SAVED_CARDS_DISPLAY_COUNT: Int = 3

/** Inclusive lower bound for the display count. */
public const val MIN_SAVED_CARDS_DISPLAY_COUNT: Int = 1

/** Inclusive upper bound for the display count. */
public const val MAX_SAVED_CARDS_DISPLAY_COUNT: Int = 10

/**
 * Clamp an integrator-supplied display count into [[MIN_SAVED_CARDS_DISPLAY_COUNT],
 * [MAX_SAVED_CARDS_DISPLAY_COUNT]]. Never throws — a value below 1 clamps to 1, above 10 clamps to 10.
 */
public fun coerceSavedCardsDisplayCount(count: Int): Int =
    count.coerceIn(MIN_SAVED_CARDS_DISPLAY_COUNT, MAX_SAVED_CARDS_DISPLAY_COUNT)
