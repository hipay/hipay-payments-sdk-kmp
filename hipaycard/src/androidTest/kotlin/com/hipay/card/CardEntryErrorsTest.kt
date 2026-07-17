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
 * incomplete (non-Luhn) prefixes only, except the allowed-networks case which
 * fakes the BIN verdict through the controller's `cardInfoResolver` seam (the
 * "not authorized" error is backend-verdict-gated) — real
 * `resolveCardInfo`/tokenization still never fire.
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
        // Pin the locale so English string assertions are deterministic regardless of the
        // emulator's device locale (matches the one-click tests' convention).
        robot.setContent { HiPayCardEntry(controller(), localeOverride = "en") }

        // No error before blur.
        robot.assertTagAbsent(HiPayCardEntryTags.error("number"))

        robot.type(HiPayCardEntryTags.NUMBER, "4111") // incomplete Visa, not Luhn-valid → no network
        robot.focus(HiPayCardEntryTags.HOLDER) // blur the number field

        robot.assertTagExists(HiPayCardEntryTags.error("number"))
        robot.assertTextShown("Card number is incomplete")
    }

    @Test
    fun networkNotAuthorizedShowsOnBackendVerdictOnly() {
        val robot = CardEntryRobot(composeRule)
        // Merchant allows only Mastercard; the faked BIN verdict identifies a mono-network
        // Visa (the error is backend-verdict-gated — local detection alone never shows it).
        val controller = controller(allowed = listOf(HiPayCardNetwork.MASTERCARD))
        controller.cardInfoResolver = { com.hipay.card.model.CardInfo(brand = "VISA") }
        robot.setContent { HiPayCardEntry(controller, localeOverride = "en") }

        robot.type(HiPayCardEntryTags.NUMBER, "4111") // partial: local detection only
        // No error while only locally detected (a co-branded card would flash a false one),
        // even though the disallowed visa chip is already hidden.
        robot.assertTagAbsent(HiPayCardEntryTags.error("number"))

        robot.type(HiPayCardEntryTags.NUMBER, "111111111111") // complete Luhn-valid PAN → verdict
        // The error shows on the verdict, without any blur, and wins over the number's own error.
        robot.assertTagExists(HiPayCardEntryTags.error("number"))
        robot.assertTextShown("Card type not allowed")
        robot.assertTextAbsent("Card number is incomplete")
    }

    @Test
    fun cvvInfoShowsAndDismissesTooltip() {
        val robot = CardEntryRobot(composeRule)
        // Pin the locale so English string assertions are deterministic regardless of the
        // emulator's device locale (matches the one-click tests' convention).
        robot.setContent { HiPayCardEntry(controller(), localeOverride = "en") }

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
