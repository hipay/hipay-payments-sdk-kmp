package com.hipay.card.validation

/** Card networks recognized locally (BIN prefixes). */
public enum class CardNetwork {
    VISA,
    MASTERCARD,
    AMEX,
    MAESTRO,

    /** Carte Bancaire — co-badged with Visa/Mastercard, locally undetectable
     *  by BIN prefix; assigned once backend network detection lands (next
     *  epic). Rules are defined now so the case is API-stable. */
    CB,

    /** Bancontact (BCMC) — prefix 6703, no CVV, PANs up to 17 digits. */
    BCMC,

    UNKNOWN,
}

/**
 * Local card-network rules driving the entry component UX (detection,
 * completion length, CVC policy, display formatting). Logic lives in
 * commonMain (D1) — the Swift layer only renders.
 *
 * NOTE: local prefix detection is a v1 stopgap; authoritative network
 * detection via the HiPay backend is planned with the next epic (see
 * decision log 2026-06-12).
 */
public object CardNetworks {

    public fun detect(number: String): CardNetwork {
        val digits = number.filter { it in '0'..'9' }
        return when {
            digits.isEmpty() -> CardNetwork.UNKNOWN
            digits.startsWith("34") || digits.startsWith("37") -> CardNetwork.AMEX
            digits.startsWith("4") -> CardNetwork.VISA
            digits.take(2).toIntOrNull() in 51..55 -> CardNetwork.MASTERCARD
            digits.length >= 4 && digits.take(4).toIntOrNull() in 2221..2720 -> CardNetwork.MASTERCARD
            digits.startsWith("6703") -> CardNetwork.BCMC
            digits.startsWith("50") || digits.take(2).toIntOrNull() in 56..69 -> CardNetwork.MAESTRO
            else -> CardNetwork.UNKNOWN
        }
    }

    /** Digit count at which a network's PAN is complete. Variable-length
     *  networks (Maestro/unknown) only "complete" at the 19-digit max. */
    public fun completionLength(network: CardNetwork): Int = when (network) {
        CardNetwork.AMEX -> 15
        CardNetwork.VISA, CardNetwork.MASTERCARD, CardNetwork.CB -> 16
        CardNetwork.BCMC -> 17
        CardNetwork.MAESTRO, CardNetwork.UNKNOWN -> 19
    }

    /** Length at which the number field is considered complete (auto-advance). */
    public fun isNumberComplete(number: String): Boolean {
        val digits = number.filter { it in '0'..'9' }
        return digits.length == completionLength(detect(digits))
    }

    /**
     * Maps a HiPay API brand / domestic-network string (e.g. "VISA",
     * "mastercard", "cb", "bcmc", "american-express") to a [CardNetwork].
     * Returns null for an unknown or null value. Case-insensitive.
     */
    public fun fromApiBrand(brand: String?): CardNetwork? = when (brand?.lowercase()) {
        "visa" -> CardNetwork.VISA
        "mastercard" -> CardNetwork.MASTERCARD
        "american-express", "amex" -> CardNetwork.AMEX
        "maestro" -> CardNetwork.MAESTRO
        "cb", "carte-bancaire", "carte bancaire" -> CardNetwork.CB
        "bcmc", "bancontact" -> CardNetwork.BCMC
        else -> null
    }

    /** CVC length: 4 for Amex, 3 for the other networks. */
    public fun cvcLength(network: CardNetwork): Int =
        if (network == CardNetwork.AMEX) 4 else 3

    /** Maestro and Bancontact do not require a CVC; the other networks do. */
    public fun isCvcRequired(network: CardNetwork): Boolean =
        network != CardNetwork.MAESTRO && network != CardNetwork.BCMC

    /** Display formatting: Amex 4-6-5, everything else groups of 4. */
    public fun format(number: String): String =
        formatWithOffsets(number, detect(number.filter { it in '0'..'9' })).text

    /**
     * Formatting + caret offset maps (story 11.1) — the single source of truth for the
     * card-number `VisualTransformation` on every platform. The raw digits are the value; the
     * spaces are display-only and [originalToTransformed]/[transformedToOriginal] keep the caret
     * correct. Grouping: Amex 4-6-5, otherwise 4×4 (mirrors [format]).
     */
    public fun formatWithOffsets(number: String, network: CardNetwork): FormattedNumber {
        val digits = number.filter { it in '0'..'9' }
        val bounds = groupBoundaries(network)
        val out = StringBuilder()
        val o2t = IntArray(digits.length + 1)
        var t = 0
        for (i in digits.indices) {
            if (i in bounds) { out.append(' '); t++ }
            o2t[i] = t
            out.append(digits[i]); t++
        }
        o2t[digits.length] = t

        val formatted = out.toString()
        val t2o = IntArray(formatted.length + 1)
        var oi = 0
        for (j in formatted.indices) {
            t2o[j] = oi
            if (formatted[j] != ' ') oi++
        }
        t2o[formatted.length] = oi
        return FormattedNumber(formatted, o2t, t2o)
    }

    /** Digit indices that get a preceding separator space (cumulative group sizes). */
    private fun groupBoundaries(network: CardNetwork): Set<Int> {
        val sizes = if (network == CardNetwork.AMEX) listOf(4, 6, 5) else listOf(4, 4, 4, 4, 3)
        val b = mutableSetOf<Int>()
        var acc = 0
        for (s in sizes) { acc += s; b.add(acc) }
        return b
    }
}

/**
 * Formatted card number + caret offset maps (story 11.1). `originalToTransformed[i]` is the
 * transformed (display) offset for raw offset `i`; `transformedToOriginal[j]` is the inverse.
 * Consumed by the per-platform `VisualTransformation` wrappers (Android + Compose-Multiplatform).
 */
public class FormattedNumber(
    public val text: String,
    public val originalToTransformed: IntArray,
    public val transformedToOriginal: IntArray,
)
