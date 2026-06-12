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
        val digits = number.filter { it.isDigit() }
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

    /** Length at which the number field is considered complete (auto-advance). */
    public fun isNumberComplete(number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        return when (detect(digits)) {
            CardNetwork.AMEX -> digits.length == 15
            CardNetwork.VISA, CardNetwork.MASTERCARD, CardNetwork.CB -> digits.length == 16
            CardNetwork.BCMC -> digits.length == 17
            // variable-length networks: complete only at the 19-digit max
            CardNetwork.MAESTRO, CardNetwork.UNKNOWN -> digits.length == 19
        }
    }

    /** CVC length: 4 for Amex, 3 for the other networks. */
    public fun cvcLength(network: CardNetwork): Int =
        if (network == CardNetwork.AMEX) 4 else 3

    /** Maestro and Bancontact do not require a CVC; the other networks do. */
    public fun isCvcRequired(network: CardNetwork): Boolean =
        network != CardNetwork.MAESTRO && network != CardNetwork.BCMC

    /** Display formatting: Amex 4-6-5, everything else groups of 4. */
    public fun format(number: String): String {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        val groups = when (detect(digits)) {
            CardNetwork.AMEX -> listOf(4, 6, 5)
            else -> listOf(4, 4, 4, 4, 3)
        }
        val builder = StringBuilder()
        var index = 0
        for (size in groups) {
            if (index >= digits.length) break
            if (builder.isNotEmpty()) builder.append(' ')
            val end = minOf(index + size, digits.length)
            builder.append(digits, index, end)
            index = end
        }
        return builder.toString()
    }
}
