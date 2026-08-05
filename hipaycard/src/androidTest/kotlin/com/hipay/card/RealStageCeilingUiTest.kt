package com.hipay.card

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.gateway.GatewayClient
import com.hipay.card.validation.CardNetworks
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check of the account network ceiling: the REAL component, on a device, against the REAL
 * gateway. Every other instrumented test presets a permissive ceiling to stay network-free; this one
 * exists to answer the question those cannot — does the production path actually refuse a card the
 * merchant account is not contracted for.
 *
 * Credentials come from instrumentation arguments, so the test skips silently without them:
 * ```
 * ./gradlew :hipaycard:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.hipay.card.RealStageCeilingUiTest \
 *   -Pandroid.testInstrumentationRunnerArguments.hipayUser=… \
 *   -Pandroid.testInstrumentationRunnerArguments.hipayPass=…
 * ```
 * Requires an account that does NOT accept the network of [refusedPan].
 *
 * It also guards against a false negative that cost an hour of hunting: when the device cannot reach
 * the gateway, the ceiling degrades open by design and the component behaves exactly as it did before
 * the ceiling existed. An emulator whose clock has drifted fails OCSP validation on every HTTPS call
 * and looks precisely like a broken feature, so this test reports the gateway's own answer alongside
 * the component's verdict rather than only asserting the UI.
 */
@RunWith(AndroidJUnit4::class)
class RealStageCeilingUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** A Visa test PAN; the reference account is contracted for everything BUT Visa. */
    private val refusedPan = "4111111111111111"

    @Test
    fun aNetworkTheAccountRefusesIsRefusedByTheRealComponent() {
        val args = InstrumentationRegistry.getArguments()
        val user = args.getString("hipayUser") ?: return
        val pass = args.getString("hipayPass") ?: return
        val config = HiPayConfig(user, pass, Environment.STAGE)

        // Resolve the ceiling from this device FIRST. It is what makes a network or clock problem
        // impossible to mistake for a broken feature: an unreachable gateway throws here, with its own
        // cause, instead of silently degrading the component into looking like the fix is absent.
        // No logging on the card path (PCI) — the set travels in the assertion messages below.
        val accepted = runBlocking {
            GatewayClient(config).getAvailablePaymentProducts(CardNetworks.cardPaymentProductCodes, "EUR")
        }
        check(CardNetworks.fromApiBrand("visa") !in accepted) {
            "this account accepts Visa ($accepted), so it cannot demonstrate a refusal — use another one"
        }

        val robot = CardEntryRobot(composeRule)
        val controller = HiPayCardEntryController(config)
        robot.setContent { HiPayCardEntry(controller, localeOverride = "en") }

        robot.type(HiPayCardEntryTags.NUMBER, refusedPan)
        // Real network: Compose idleness does not wait for it, and `networks` is non-empty from local
        // detection within a frame, so the ceiling's own effect is what must be waited on.
        composeRule.waitUntil(20_000) { controller.numberSlotErrorKey != null }

        robot.assertTextShown("Card type not allowed")
        assert(controller.networks.isEmpty()) {
            "a refused network must not be offered: ${controller.networks} against a ceiling of $accepted"
        }
        assert(!controller.isNetworkAuthorized)
    }
}
