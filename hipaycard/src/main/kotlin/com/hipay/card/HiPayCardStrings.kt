package com.hipay.card

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hipay.card.validation.CardEntryStringKey

/**
 * Resolves the shared [CardEntryStringKey] contract to Android `strings.xml`
 * (FR/EN/IT, default EN — story 7.3, D11). Each key maps 1:1 to a `<string>`
 * whose `name` equals the enum constant; the key-parity guard
 * (`scripts/check-i18n-parity.sh`) asserts every key exists in every locale on
 * both platforms.
 */
internal object HiPayCardStrings {

    @StringRes
    fun resFor(key: CardEntryStringKey): Int = when (key) {
        CardEntryStringKey.LABEL_HOLDER -> R.string.LABEL_HOLDER
        CardEntryStringKey.LABEL_NUMBER -> R.string.LABEL_NUMBER
        CardEntryStringKey.LABEL_EXPIRY -> R.string.LABEL_EXPIRY
        CardEntryStringKey.LABEL_CVV -> R.string.LABEL_CVV
        CardEntryStringKey.PLACEHOLDER_HOLDER -> R.string.PLACEHOLDER_HOLDER
        CardEntryStringKey.PLACEHOLDER_NUMBER -> R.string.PLACEHOLDER_NUMBER
        CardEntryStringKey.PLACEHOLDER_EXPIRY -> R.string.PLACEHOLDER_EXPIRY
        CardEntryStringKey.PLACEHOLDER_CVV -> R.string.PLACEHOLDER_CVV
        CardEntryStringKey.CVV_OPTIONAL -> R.string.CVV_OPTIONAL
        CardEntryStringKey.CVV_TOOLTIP -> R.string.CVV_TOOLTIP
        CardEntryStringKey.ERROR_INVALID_NUMBER -> R.string.ERROR_INVALID_NUMBER
        CardEntryStringKey.ERROR_INCOMPLETE_NUMBER -> R.string.ERROR_INCOMPLETE_NUMBER
        CardEntryStringKey.ERROR_INVALID_EXPIRY -> R.string.ERROR_INVALID_EXPIRY
        CardEntryStringKey.ERROR_EXPIRED -> R.string.ERROR_EXPIRED
        CardEntryStringKey.ERROR_INVALID_CVV -> R.string.ERROR_INVALID_CVV
        CardEntryStringKey.ERROR_INCOMPLETE_CVV -> R.string.ERROR_INCOMPLETE_CVV
        CardEntryStringKey.ERROR_HOLDER_TOO_LONG -> R.string.ERROR_HOLDER_TOO_LONG
        CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED -> R.string.ERROR_NETWORK_NOT_AUTHORIZED
        CardEntryStringKey.LABEL_SAVED_CARDS -> R.string.LABEL_SAVED_CARDS
        CardEntryStringKey.LABEL_NEW_CARD -> R.string.LABEL_NEW_CARD
        CardEntryStringKey.LABEL_SAVE_CARD -> R.string.LABEL_SAVE_CARD
        CardEntryStringKey.CONSENT_SAVE_CARD -> R.string.CONSENT_SAVE_CARD
        CardEntryStringKey.A11Y_SAVED_CARD -> R.string.A11Y_SAVED_CARD
        CardEntryStringKey.A11Y_EXPANDED -> R.string.A11Y_EXPANDED
        CardEntryStringKey.A11Y_COLLAPSED -> R.string.A11Y_COLLAPSED
        CardEntryStringKey.LABEL_DELETE_CARD -> R.string.LABEL_DELETE_CARD
        CardEntryStringKey.CONFIRM_DELETE_CARD -> R.string.CONFIRM_DELETE_CARD
        CardEntryStringKey.LABEL_CANCEL -> R.string.LABEL_CANCEL
        CardEntryStringKey.ERROR_ONE_CLICK_DECLINED -> R.string.ERROR_ONE_CLICK_DECLINED
        CardEntryStringKey.ERROR_ONE_CLICK_CARD_REMOVED -> R.string.ERROR_ONE_CLICK_CARD_REMOVED
        CardEntryStringKey.ERROR_ONE_CLICK_3DS -> R.string.ERROR_ONE_CLICK_3DS
        CardEntryStringKey.ERROR_ONE_CLICK_EXPIRED -> R.string.ERROR_ONE_CLICK_EXPIRED
        CardEntryStringKey.ERROR_ONE_CLICK_GENERIC -> R.string.ERROR_ONE_CLICK_GENERIC
        CardEntryStringKey.ERROR_ONE_CLICK_PENDING -> R.string.ERROR_ONE_CLICK_PENDING
    }
}

/** Composable resolver for a [CardEntryStringKey] (device locale; FR/EN/IT). */
@Composable
internal fun cardString(key: CardEntryStringKey): String = stringResource(HiPayCardStrings.resFor(key))
