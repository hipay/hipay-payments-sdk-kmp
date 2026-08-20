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

    /**
     * True while the digit prefix can still become a PAN of one of the
     * locally known networks ([detect]'s prefix rules: Amex 34/37, Visa 4,
     * Mastercard 51-55 / 2221-2720, BCMC 6703, Maestro 50 / 56-69 — CB is
     * co-badged Visa/Mastercard so it needs no rule of its own). An empty
     * prefix is viable. The moment this returns false no further typing can
     * repair the number, so the UI may flag it immediately without waiting
     * for focus loss.
     */
    public fun isPrefixViable(number: String): Boolean {
        val digits = number.filter { it in '0'..'9' }
        if (digits.isEmpty()) return true
        return prefixCanMatch(digits, "34") ||
            prefixCanMatch(digits, "37") ||
            prefixCanMatch(digits, "4") ||
            prefixCanMatchRange(digits, "51", "55") ||
            prefixCanMatchRange(digits, "2221", "2720") ||
            prefixCanMatch(digits, "6703") ||
            prefixCanMatch(digits, "50") ||
            prefixCanMatchRange(digits, "56", "69")
    }

    /** The typed digits and the fixed BIN prefix agree on their common length. */
    private fun prefixCanMatch(digits: String, prefix: String): Boolean {
        val k = minOf(digits.length, prefix.length)
        return digits.take(k) == prefix.take(k)
    }

    /** The typed digits, truncated to the range's width, still fall inside
     *  [lo]..[hi] truncated the same way (same-length digit strings compare
     *  correctly as text). */
    private fun prefixCanMatchRange(digits: String, lo: String, hi: String): Boolean {
        val k = minOf(digits.length, lo.length)
        val d = digits.take(k)
        return d >= lo.take(k) && d <= hi.take(k)
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
     * The card payment-product codes to ask the account about, so the answer covers every network
     * this SDK can enter. Sent as the `payment_product` filter of
     * `GatewayClient.getAvailablePaymentProducts`: the endpoint answers with the subset the account
     * is actually contracted for, which is the ceiling a card component may offer.
     *
     * Kept as a wire-code list rather than [CardNetwork] values because it IS a wire parameter, and
     * deliberately covers all six networks — narrowing here would silently hide a network the
     * merchant does accept.
     */
    public val cardPaymentProductCodes: List<String> = listOf(
        "visa",
        "mastercard",
        "american-express",
        "maestro",
        "cb",
        "bcmc",
    )

    /**
     * Maps a HiPay API brand / domestic-network string (e.g. "VISA",
     * "mastercard", "cb", "bcmc", "american-express") to a [CardNetwork].
     * Returns null for an unknown or null value. Case-insensitive.
     */
    public fun fromApiBrand(brand: String?): CardNetwork? = when (brand?.lowercase()) {
        "visa" -> CardNetwork.VISA
        "mastercard" -> CardNetwork.MASTERCARD
        "american-express", "amex", "american express" -> CardNetwork.AMEX
        "maestro" -> CardNetwork.MAESTRO
        "cb", "carte-bancaire", "carte bancaire" -> CardNetwork.CB
        "bcmc", "bancontact" -> CardNetwork.BCMC
        else -> null
    }

    /** CVC length: 4 for Amex, 3 for the other networks. */
    public fun cvcLength(network: CardNetwork): Int =
        if (network == CardNetwork.AMEX) 4 else 3

    /**
     * CVC requirement, **co-brand aware** (story 11.5). A **mono-network Maestro** (Maestro is the
     * only offered/authorized network) requires a CVC; a **co-branded** Maestro (≥2 offered) does
     * not. Bancontact never requires a CVC; every other network does. [offered] is the offered set
     * from the controller (the co-brand set) — pass the networks currently on offer.
     */
    public fun isCvcRequired(network: CardNetwork, offered: List<CardNetwork>): Boolean = when (network) {
        CardNetwork.MAESTRO -> offered.size <= 1 // mono Maestro → CVC required; co-branded → not
        CardNetwork.BCMC -> false
        else -> true
    }

    /** Convenience: a lone network is treated as mono (so a bare Maestro requires a CVC). */
    public fun isCvcRequired(network: CardNetwork): Boolean =
        isCvcRequired(network, listOf(network))

    /**
     * The networks the backend could plausibly resolve for a card whose LOCAL prefix
     * detection is [detected]. Local detection sees only the international BIN, never a
     * DOMESTIC co-brand (CB in France, BCMC in Belgium) that the backend adds — so a "4…"
     * (Visa) card may still resolve to CB, a Maestro to BCMC, and so on. Used to decide
     * whether a local "not authorized" verdict is safe to surface BEFORE the backend
     * responds (see [AllowedNetworks.isLocallyUnauthorized]).
     *
     * Deliberately GENEROUS (a conservative superset): over-including a co-brand only makes
     * the UI wait for the backend, whereas under-including would wrongly reject a real card
     * mid-typing. Amex (34/37) is the one range that carries no domestic co-brand.
     */
    public fun possibleResolutions(detected: CardNetwork): Set<CardNetwork> = when (detected) {
        CardNetwork.AMEX -> setOf(CardNetwork.AMEX)
        CardNetwork.VISA -> setOf(CardNetwork.VISA, CardNetwork.CB, CardNetwork.BCMC)
        CardNetwork.MASTERCARD -> setOf(CardNetwork.MASTERCARD, CardNetwork.CB, CardNetwork.BCMC)
        CardNetwork.MAESTRO -> setOf(CardNetwork.MAESTRO, CardNetwork.CB, CardNetwork.BCMC)
        CardNetwork.BCMC ->
            setOf(CardNetwork.BCMC, CardNetwork.MAESTRO, CardNetwork.VISA, CardNetwork.MASTERCARD)
        // CB is never detected locally; UNKNOWN (mid-typing) could still become anything.
        CardNetwork.CB, CardNetwork.UNKNOWN -> CardNetwork.entries.toSet()
    }

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
