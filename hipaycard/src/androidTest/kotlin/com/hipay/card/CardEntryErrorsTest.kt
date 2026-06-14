package com.hipay.card

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Story 7.4 — inline errors, CVV tooltip, allowed-networks. NETWORK-FREE:
 * incomplete (non-Luhn) prefixes only, and the allowed-networks case is driven
 * by the constructor param, so `resolveCardInfo`/tokenization never fire.
 */
@RunWith(AndroidJUnit4::class)
class CardEntryErrorsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun controller(allowed: List<HiPayCardNetwork> = emptyList()) =
        HiPayCardEntryController(HiPayConfig("test-user", "test-pass", Environment.STAGE), allowedNetworks = allowed)

    @Test
    fun incompleteNumberShowsErrorOnBlur() {
        val robot = CardEntryRobot(composeRule)
        robot.setContent { HiPayCardEntry(controller()) }

        // No error before blur.
        robot.assertTagAbsent(HiPayCardEntryTags.error("number"))

        robot.type(HiPayCardEntryTags.NUMBER, "4111") // incomplete Visa, not Luhn-valid → no network
        robot.focus(HiPayCardEntryTags.HOLDER) // blur the number field

        robot.assertTagExists(HiPayCardEntryTags.error("number"))
        robot.assertTextShown("Card number is incomplete")
    }

    @Test
    fun networkNotAuthorizedTakesPrecedence() {
        val robot = CardEntryRobot(composeRule)
        // Merchant allows only Mastercard; the user types a Visa prefix.
        robot.setContent { HiPayCardEntry(controller(allowed = listOf(HiPayCardNetwork.MASTERCARD))) }

        robot.type(HiPayCardEntryTags.NUMBER, "4111")
        robot.focus(HiPayCardEntryTags.HOLDER) // blur

        // The number slot shows the network message, NOT the incomplete one (D1 precedence).
        robot.assertTagExists(HiPayCardEntryTags.error("number"))
        robot.assertTextShown("This card network is not accepted")
        robot.assertTextAbsent("Card number is incomplete")
    }

    @Test
    fun cvvInfoShowsAndDismissesTooltip() {
        val robot = CardEntryRobot(composeRule)
        robot.setContent { HiPayCardEntry(controller()) }

        // Visa prefix → CVC required → the info affordance is present.
        robot.type(HiPayCardEntryTags.NUMBER, "4111")
        robot.assertTagExists(HiPayCardEntryTags.CVC_INFO)
        robot.assertTagAbsent(HiPayCardEntryTags.CVC_TOOLTIP)

        robot.tap(HiPayCardEntryTags.CVC_INFO)
        robot.assertTagExists(HiPayCardEntryTags.CVC_TOOLTIP)

        robot.tap(HiPayCardEntryTags.CVC_INFO) // toggle off
        robot.assertTagAbsent(HiPayCardEntryTags.CVC_TOOLTIP)
    }
}
