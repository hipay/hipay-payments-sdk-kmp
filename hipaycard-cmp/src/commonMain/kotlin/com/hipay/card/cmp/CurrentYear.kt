package com.hipay.card.cmp

/**
 * Current calendar year without a datetime dependency — a local expect/actual because the
 * core's `currentYearMonth()` is internal to `hipaycore`, and neither widening the
 * core's public API nor adding kotlinx-datetime is worth it for a non-authoritative value.
 * Only used to build the plausible near-future expiry for the card-info resolution.
 */
internal expect fun currentYear(): Int
