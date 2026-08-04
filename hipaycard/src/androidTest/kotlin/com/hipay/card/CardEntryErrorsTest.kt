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
        HiPayCardEntryController(HiPayConfig("test-user", "test-pass", Environment.STAGE), allowedNetworks = allowed).withOfflineCeiling()

    /** No permissive preset: the two tests below are ABOUT the account ceiling, so they must own it. */
    private fun controllerWithoutCeiling(allowed: List<HiPayCardNetwork> = emptyList()) =
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
        // AMBIGUOUS case (refinement 2026-07-20): merchant allows only CB and the BIN is Visa.
        // CB can ride a Visa BIN, so local detection alone must NOT reject — the error stays
        // backend-verdict-gated. The faked verdict is a mono Visa (no CB), so it resolves to
        // "not allowed". (The UNAMBIGUOUS case — Amex detected, CB-only — now errors immediately;
        // see CardEntryValidationGherkinTest.)
        val controller = controller(allowed = listOf(HiPayCardNetwork.CB))
        controller.cardInfoResolver = { com.hipay.card.model.CardInfo(brand = "VISA") }
        robot.setContent { HiPayCardEntry(controller, localeOverride = "en") }

        robot.type(HiPayCardEntryTags.NUMBER, "4111") // partial: local detection only; CB could ride it
        // No error while only locally detected (a co-branded card would flash a false one),
        // even though the disallowed visa chip is already hidden.
        robot.assertTagAbsent(HiPayCardEntryTags.error("number"))

        robot.type(HiPayCardEntryTags.NUMBER, "111111111111") // complete Luhn-valid PAN → verdict
        // The error shows on the verdict, without any blur, and wins over the number's own error.
        robot.assertTagExists(HiPayCardEntryTags.error("number"))
        robot.assertTextShown("Card type not allowed")
        robot.assertTextAbsent("Card number is incomplete")
    }

    // The account's own contract is the ceiling: a network it does not accept must be refused even
    // when the integrator restricts nothing. Before the ceiling existed, an absent allow-list
    // accepted every network and the refusal only came back as a gateway error at order time.
    @Test
    fun networkTheAccountDoesNotAcceptIsRefusedWithoutAnyIntegratorRestriction() {
        val robot = CardEntryRobot(composeRule)
        val controller = controllerWithoutCeiling() // no integrator restriction at all
        controller.cardInfoResolver = { com.hipay.card.model.CardInfo(brand = "VISA") }
        // Preset, not a resolver: the refusal must be asserted against a KNOWN ceiling, not against a
        // fetch the assertion could outrun.
        controller.presetAccountNetworks(
            setOf(com.hipay.card.validation.CardNetwork.MASTERCARD, com.hipay.card.validation.CardNetwork.CB),
        )
        robot.setContent { HiPayCardEntry(controller, localeOverride = "en") }

        // NB `type` appends: this is the whole Luhn-valid Visa PAN, which is what triggers the
        // vault verdict the contractual error is gated on.
        robot.type(HiPayCardEntryTags.NUMBER, "4111111111111111")

        robot.assertTagExists(HiPayCardEntryTags.error("number"))
        robot.assertTextShown("Card type not allowed")
    }

    // A technical failure of that query must never block entry: the ceiling stays open, no error.
    @Test
    fun aFailedAccountQueryLeavesEntryOpen() {
        val robot = CardEntryRobot(composeRule)
        val controller = controllerWithoutCeiling()
        controller.cardInfoResolver = { com.hipay.card.model.CardInfo(brand = "VISA") }
        controller.accountNetworksResolver = { throw IllegalStateException("offline") }
        robot.setContent { HiPayCardEntry(controller, localeOverride = "en") }

        robot.type(HiPayCardEntryTags.NUMBER, "4111111111111111")

        robot.assertTagAbsent(HiPayCardEntryTags.error("number"))
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
