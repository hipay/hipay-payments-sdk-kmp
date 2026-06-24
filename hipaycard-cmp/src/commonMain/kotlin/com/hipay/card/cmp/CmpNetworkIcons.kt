// PCI (NFR2): com.hipay.card path — never log card data here.
package com.hipay.card.cmp

import com.hipay.card.validation.CardNetwork
import com.hipay.fullservice.hipaycard_cmp.generated.resources.Res
import com.hipay.fullservice.hipaycard_cmp.generated.resources.hp_amex
import com.hipay.fullservice.hipaycard_cmp.generated.resources.hp_bcmc
import com.hipay.fullservice.hipaycard_cmp.generated.resources.hp_card_neutral
import com.hipay.fullservice.hipaycard_cmp.generated.resources.hp_cb
import com.hipay.fullservice.hipaycard_cmp.generated.resources.hp_maestro
import com.hipay.fullservice.hipaycard_cmp.generated.resources.hp_mastercard
import com.hipay.fullservice.hipaycard_cmp.generated.resources.hp_visa
import org.jetbrains.compose.resources.DrawableResource

/**
 * Brand-icon mapping for the CMP network chips (story 11.4). Mirrors the Android
 * `HiPayCardNetwork.drawableRes` source of truth; the icons are the same `hp_*` PNGs copied into
 * this module's `composeResources/drawable/`. UNKNOWN → the dimmed neutral card.
 */
internal fun CardNetwork.iconResource(): DrawableResource = when (this) {
    CardNetwork.VISA -> Res.drawable.hp_visa
    CardNetwork.MASTERCARD -> Res.drawable.hp_mastercard
    CardNetwork.AMEX -> Res.drawable.hp_amex
    CardNetwork.MAESTRO -> Res.drawable.hp_maestro
    CardNetwork.CB -> Res.drawable.hp_cb
    CardNetwork.BCMC -> Res.drawable.hp_bcmc
    CardNetwork.UNKNOWN -> Res.drawable.hp_card_neutral
}

/** Human-facing brand name for accessibility (proper noun, not localized) — parity with Android. */
internal fun CardNetwork.displayName(): String = when (this) {
    CardNetwork.VISA -> "Visa"
    CardNetwork.MASTERCARD -> "Mastercard"
    CardNetwork.AMEX -> "American Express"
    CardNetwork.MAESTRO -> "Maestro"
    CardNetwork.CB -> "CB"
    CardNetwork.BCMC -> "Bancontact"
    CardNetwork.UNKNOWN -> ""
}

/** Neutral (no detected/authorized network) placeholder icon. */
internal val neutralCardIcon: DrawableResource get() = Res.drawable.hp_card_neutral
