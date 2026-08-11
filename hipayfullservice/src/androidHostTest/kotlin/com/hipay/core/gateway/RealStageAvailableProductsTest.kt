package com.hipay.core.gateway

import com.hipay.card.validation.CardNetworks
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * Gated real-stage verification of the account network ceiling: what
 * `available-payment-products` actually answers for the configured stage account.
 *
 * This is the only check that can tell a wrong ceiling from a wrong transport — a component showing
 * no "not authorized" error could mean the query failed (ceiling left open by design) just as well
 * as the account still accepting the network. It prints the resolved networks (never the
 * credentials) so a back-office change can be confirmed against the endpoint in one run.
 *
 * Credentials come from the git-ignored `.hipay_stage_env`; with no file the test skips silently,
 * like the other real-stage tests.
 */
class RealStageAvailableProductsTest {

    @Test
    fun accountNetworksReflectTheBackOfficeConfiguration() {
        val env = loadStageEnv() ?: return // no credentials: skip silently
        val username = env["HIPAY_STAGE_USERNAME"] ?: return
        val password = env["HIPAY_STAGE_PASSWORD"] ?: return
        val config = HiPayConfig(username, password, Environment.STAGE)

        runBlocking {
            val networks = GatewayClient(config).getAvailablePaymentProducts(
                paymentProducts = CardNetworks.cardPaymentProductCodes,
                currency = "EUR",
            )
            println("[real-stage] available-payment-products (EUR) → $networks")
            println("[real-stage] asked for → ${CardNetworks.cardPaymentProductCodes}")
        }
    }

    /** What the Secure Vault resolves the QA test PAN to — the other half of the verdict. A card the
     *  vault reports as a domestic co-brand the account DOES accept is payable, and showing no error
     *  would then be correct; this tells the two situations apart. */
    @Test
    fun vaultVerdictForTheQaTestPan() {
        val env = loadStageEnv() ?: return
        val username = env["HIPAY_STAGE_USERNAME"] ?: return
        val password = env["HIPAY_STAGE_PASSWORD"] ?: return
        val config = HiPayConfig(username, password, Environment.STAGE)

        runBlocking {
            val info = com.hipay.card.CardTokenizer(config)
                .resolveCardInfo("4111111111111111", "12", "2027")
            println("[real-stage] vault verdict for the QA PAN → brand=${info.brand} domestic=${info.domesticNetwork}")
            println("[real-stage] resolvedNetworks → ${info.resolvedNetworks()}")
        }
    }

    /** The wire SHAPE of the answer, pinned against the real gateway: the parser treats a bare JSON
     *  array as the authoritative product list, so if the endpoint ever wrapped it in an object the
     *  ceiling would silently stop being resolvable. Prints the first characters, never the body. */
    @Test
    fun accountProductsAnswerIsABareJsonArray() {
        val env = loadStageEnv() ?: return
        val username = env["HIPAY_STAGE_USERNAME"] ?: return
        val password = env["HIPAY_STAGE_PASSWORD"] ?: return
        val config = HiPayConfig(username, password, Environment.STAGE)

        val query = "eci=7&operation=4&customer_country=&currency=EUR" +
            "&payment_product=" + CardNetworks.cardPaymentProductCodes.joinToString(",") +
            "&with_options=true"
        runBlocking {
            val body = com.hipay.core.http.HipayHttpClient(
                config,
                com.hipay.core.http.defaultHttpClientEngine(),
            ).get(config.environment.gatewayV2Url + "available-payment-products.json?" + query)
            println("[real-stage] answer starts with: '" + body.trimStart().take(24) + "' (length " + body.length + ")")
            kotlin.test.assertTrue(
                body.trimStart().startsWith("["),
                "the parser treats a bare array as authoritative; the gateway answered something else",
            )
        }
    }

    private fun loadStageEnv(): Map<String, String>? {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(4) {
            val candidate = File(dir, ".hipay_stage_env")
            if (candidate.isFile) {
                return candidate.readLines().mapNotNull { line ->
                    Regex("""export\s+(\w+)=(.*)""").find(line.trim())
                        ?.let { it.groupValues[1] to it.groupValues[2].trim('"', '\'') }
                }.toMap()
            }
            dir = dir?.parentFile ?: return null
        }
        return null
    }
}
