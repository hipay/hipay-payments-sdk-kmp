package com.hipay.core

// Replaces any run of 13-19 digits (optionally grouped by single spaces or
// dashes) with a marker — the PAN length range, narrow enough to leave order
// references, amounts and short codes untouched. Defense in depth only: HiPay
// does not echo card numbers, but the SDK must not relay one if it ever did
// (PCI, NFR2). Shared by the error path (apiMessage/apiDescription) and the
// transaction `reason` field.
internal val PAN_LIKE = Regex("""\d(?:[ -]?\d){12,18}""")

internal fun redactPanLike(text: String): String = PAN_LIKE.replace(text, "[REDACTED]")

internal fun redactPanLikeOrNull(text: String?): String? = text?.let(::redactPanLike)
