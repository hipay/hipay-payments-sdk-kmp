package com.hipay.card

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hipay.card.model.CardInfo
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PI-6078 — co-branded network prioritization (BIN as source of truth). NETWORK-FREE:
 * the backend BIN verdict is faked through the controller's `cardInfoResolver` seam
 * (the "domestic co-brand first" rule is what's under test — real
 * `resolveCardInfo`/tokenization never fire). Mirrors `CmpCoBrandResolutionTest`.
 */
@RunWith(AndroidJUnit4::class)
class CardEntryCoBrandTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Real co-branded stage test PANs (Luhn-valid): CB+Visa and Bancontact+Mastercard.
    private val cbVisaPan = "4484120000000029"
    private val bcmcMcPan = "5127880999999990"

    private fun controller(allowed: List<HiPayCardNetwork> = emptyList()) =
        HiPayCardEntryController(HiPayConfig("test-user", "test-pass", Environment.STAGE), allowedNetworks = allowed).withOfflineCeiling()

    // PI-6078 — Scénario : Carte CB/Visa — CB priorisé par défaut.
    //   Étant donné que les réseaux activés sont "cb" et "visa"
    //   Quand je saisis un BIN cobrandé "cb" et "visa"
    //   Alors le réseau "cb" est sélectionné par défaut
    //   Et les logos "cb" et "visa" sont affichés
    @Test
    fun cbVisaCoBrand_cbSelectedByDefault_bothLogosShown() {
        val robot = CardEntryRobot(composeRule)
        val controller = controller(allowed = listOf(HiPayCardNetwork.CB, HiPayCardNetwork.VISA))
        controller.cardInfoResolver = { CardInfo(brand = "VISA", domesticNetwork = "cb") }
        robot.setContent { HiPayCardEntry(controller) }

        robot.type(HiPayCardEntryTags.NUMBER, cbVisaPan)

        robot.assertSelected(HiPayCardEntryTags.network("cb"), selected = true)
        robot.assertSelected(HiPayCardEntryTags.network("visa"), selected = false)
        assertEquals(HiPayCardNetwork.CB, controller.selectedNetwork)
    }

    // PI-6078 — Scénario : Carte Bancontact/Mastercard — Bancontact priorisé par défaut.
    //   Étant donné que les réseaux activés sont "bancontact" et "mastercard"
    //   Quand je saisis un BIN cobrandé "bancontact" et "mastercard"
    //   Alors le réseau "bancontact" est sélectionné par défaut
    //   Et les logos "bancontact" et "mastercard" sont affichés
    @Test
    fun bancontactMastercardCoBrand_bancontactSelectedByDefault_bothLogosShown() {
        val robot = CardEntryRobot(composeRule)
        val controller = controller(allowed = listOf(HiPayCardNetwork.BCMC, HiPayCardNetwork.MASTERCARD))
        controller.cardInfoResolver = { CardInfo(brand = "MASTERCARD", domesticNetwork = "bcmc") }
        robot.setContent { HiPayCardEntry(controller) }

        robot.type(HiPayCardEntryTags.NUMBER, bcmcMcPan)

        robot.assertSelected(HiPayCardEntryTags.network("bcmc"), selected = true)
        robot.assertSelected(HiPayCardEntryTags.network("mastercard"), selected = false)
        assertEquals(HiPayCardNetwork.BCMC, controller.selectedNetwork)
    }

    // PI-6078 — Scénario : Sélection manuelle du réseau sur une carte cobrandée.
    //   Étant donné qu'un BIN cobrandé CB/Visa est saisi avec "cb" sélectionné par défaut
    //   Quand l'utilisateur sélectionne manuellement "visa" via le bouton visa
    //   Alors le réseau "cb" est désélectionné
    //   Et le réseau "visa" est sélectionné
    @Test
    fun manualSelectionOnCoBrandedCard_visaSelectedCbDeselected() {
        val robot = CardEntryRobot(composeRule)
        val controller = controller(allowed = listOf(HiPayCardNetwork.CB, HiPayCardNetwork.VISA))
        controller.cardInfoResolver = { CardInfo(brand = "VISA", domesticNetwork = "cb") }
        robot.setContent { HiPayCardEntry(controller) }

        robot.type(HiPayCardEntryTags.NUMBER, cbVisaPan)
        robot.assertSelected(HiPayCardEntryTags.network("cb"), selected = true)

        robot.tap(HiPayCardEntryTags.network("visa"))

        robot.assertSelected(HiPayCardEntryTags.network("cb"), selected = false)
        robot.assertSelected(HiPayCardEntryTags.network("visa"), selected = true)
        assertEquals(HiPayCardNetwork.VISA, controller.selectedNetwork)
    }
}
