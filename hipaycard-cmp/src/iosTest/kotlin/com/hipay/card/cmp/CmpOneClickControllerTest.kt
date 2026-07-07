package com.hipay.card.cmp

import com.hipay.card.store.SavedCard
import com.hipay.card.store.createSecureCardStore
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * One-click load + selection on the shared controller against the REAL simulator Keychain
 * (no fake). No network: nothing here reaches the gateway.
 */
class CmpOneClickControllerTest {

    private val config = HiPayConfig("cmp-oneclick-test-user", "pw", Environment.STAGE)

    @AfterTest
    fun tearDown() {
        runCatching { createSecureCardStore(config).clearAll() }
    }

    private fun seedCard() {
        val card = SavedCard(
            token = "t".repeat(64), maskedPan = "411111xxxxxx1111", network = "VISA",
            holder = "JANE DOE", expiryMonth = "12", expiryYear = "2031",
        )
        assertTrue(createSecureCardStore(config).save(card, consentGiven = true))
    }

    @Test
    fun refresh_loadsAndPreselectsTheSavedCard() = runBlocking {
        seedCard()
        val c = CmpCardController(config, oneClickEnabled = true)
        c.refreshSavedCards()
        assertEquals("411111xxxxxx1111", assertNotNull(c.savedCard).maskedPan)
        assertTrue(c.isSavedCardSelected)
        assertTrue(c.canPay) // one tap away, fields empty
    }

    @Test
    fun refresh_withoutCards_selectsTheNewCardBranch() = runBlocking {
        val c = CmpCardController(config, oneClickEnabled = true)
        c.refreshSavedCards()
        assertFalse(c.isSavedCardSelected)
        assertFalse(c.canPay)
    }

    @Test
    fun selectionRoundTrip_preservesTypedFieldValues() = runBlocking {
        seedCard()
        val c = CmpCardController(config, oneClickEnabled = true)
        c.refreshSavedCards()
        c.selectNewCard()
        c.onHolderChange("marie martin")
        c.selectSavedCard()
        assertTrue(c.isSavedCardSelected)
        assertEquals("MARIE MARTIN", c.holder) // preserved (and uppercased by the handler)
    }
}
