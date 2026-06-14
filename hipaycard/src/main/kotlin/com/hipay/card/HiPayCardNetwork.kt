package com.hipay.card

import androidx.annotation.DrawableRes
import com.hipay.card.validation.CardNetwork

/**
 * The card networks the Android component renders, mirroring the iOS
 * `HiPayCardNetwork`. UNKNOWN is intentionally absent — it is not a selectable
 * brand (use [from] which returns null for it).
 */
public enum class HiPayCardNetwork(
    /** The wire payment_product code sent in the order. */
    public val paymentProductCode: String,
    /** Human-facing brand name (proper noun, not localized). */
    public val displayName: String,
    /** Stable test/semantics tag suffix and accessibility id segment. */
    internal val code: String,
    @DrawableRes public val drawableRes: Int,
) {
    VISA("visa", "Visa", "visa", R.drawable.hp_visa),
    MASTERCARD("mastercard", "Mastercard", "mastercard", R.drawable.hp_mastercard),
    AMEX("american-express", "American Express", "amex", R.drawable.hp_amex),
    MAESTRO("maestro", "Maestro", "maestro", R.drawable.hp_maestro),
    CB("cb", "CB", "cb", R.drawable.hp_cb),
    BCMC("bcmc", "Bancontact", "bcmc", R.drawable.hp_bcmc);

    /** The shared-contract network this maps to. */
    public val kmpNetwork: CardNetwork
        get() = when (this) {
            VISA -> CardNetwork.VISA
            MASTERCARD -> CardNetwork.MASTERCARD
            AMEX -> CardNetwork.AMEX
            MAESTRO -> CardNetwork.MAESTRO
            CB -> CardNetwork.CB
            BCMC -> CardNetwork.BCMC
        }

    public companion object {
        /** Safe conversion from the shared contract; UNKNOWN → null. */
        public fun from(kmp: CardNetwork): HiPayCardNetwork? = when (kmp) {
            CardNetwork.VISA -> VISA
            CardNetwork.MASTERCARD -> MASTERCARD
            CardNetwork.AMEX -> AMEX
            CardNetwork.MAESTRO -> MAESTRO
            CardNetwork.CB -> CB
            CardNetwork.BCMC -> BCMC
            CardNetwork.UNKNOWN -> null
        }
    }
}
