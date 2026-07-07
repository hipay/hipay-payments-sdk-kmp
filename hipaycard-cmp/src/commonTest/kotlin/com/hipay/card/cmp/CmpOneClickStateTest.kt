package com.hipay.card.cmp

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
        assertNull(c.savedCard)
        assertFalse(c.isSavedCardSelected)
        assertFalse(c.canPay)
    }

    @Test
    fun selectSavedCard_isANoOpWithoutACard() {
        val c = controller(oneClickEnabled = true)
        c.selectSavedCard()
        assertFalse(c.isSavedCardSelected) // never a selection pointing at nothing
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
        assertFalse(c.isSavedCardSelected)
        assertFalse(c.canPay) // empty fields
    }
}
