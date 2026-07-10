package com.hipay.card.cmp

import com.hipay.card.store.SavedCard
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline one-click state invariants on the shared controller — opt-in-off behaviour and the
 * selection state machine (no store, no network; the store-backed load path — including the
 * opt-in-off refresh no-op — is covered by the iosTest against the real Keychain).
 */
class CmpOneClickStateTest {

    private fun controller(oneClickEnabled: Boolean = false) =
        CmpCardController(
            HiPayConfig(username = "u", password = "p", environment = Environment.STAGE),
            oneClickEnabled = oneClickEnabled,
        )

    @Test
    fun optInOff_nothingIsSelectedAndNothingLoaded() {
        val c = controller(oneClickEnabled = false)
        assertTrue(c.savedCards.isEmpty())
        assertNull(c.selectedSavedCard)
        assertFalse(c.canPay)
    }

    @Test
    fun selectSavedCard_ignoresACardThatIsNotInTheLoadedList() {
        val c = controller(oneClickEnabled = true)
        val foreign = SavedCard(
            token = "t".repeat(64), maskedPan = "411111xxxxxx1111", network = "VISA",
            holder = "J", expiryMonth = "12", expiryYear = "2031",
        )
        c.selectSavedCard(foreign)
        assertNull(c.selectedSavedCard) // never a selection pointing outside savedCards
    }

    @Test
    fun saveSwitchState_togglesAndDefaultsOff() {
        val c = controller(oneClickEnabled = true)
        assertFalse(c.saveCardOptIn)
        c.onSaveCardOptInChange(true)
        assertTrue(c.saveCardOptIn)
        c.onSaveCardOptInChange(false)
        assertFalse(c.saveCardOptIn)
    }

    @Test
    fun selectNewCard_neverThrows_andKeepsFieldsPayableLogicIntact() {
        val c = controller(oneClickEnabled = true)
        c.selectNewCard()
        assertNull(c.selectedSavedCard)
        assertFalse(c.canPay) // empty fields
    }

    @Test
    fun oneClickError_isTransient_clearedByFieldEditsAndSelectionIntent() {
        val c = controller(oneClickEnabled = true)
        val card = SavedCard(
            token = "t".repeat(64), maskedPan = "411111xxxxxx1111", network = "VISA",
            holder = "J", expiryMonth = "12", expiryYear = "2031",
        )
        listOf<(CmpCardController) -> Unit>(
            { it.onHolderChange("J") },
            { it.onNumberChange("4") },
            { it.onExpiryChange("1") },
            { it.onCvcChange("1") },
            { it.selectNewCard() },
        ).forEach { intent ->
            c.lastOneClickError = com.hipay.card.store.OneClickError(
                card,
                com.hipay.card.store.OneClickErrorReason.GENERIC,
            )
            intent(c)
            assertNull(c.lastOneClickError) // a new intent supersedes the previous failure
        }
    }
}
