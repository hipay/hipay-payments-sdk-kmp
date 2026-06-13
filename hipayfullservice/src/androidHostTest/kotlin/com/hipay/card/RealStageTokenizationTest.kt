package com.hipay.card

import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gated real-stage verification (story 2.4 AC: a real token through the REAL
 * SDK code path). Runs only when the git-ignored `.hipay_stage_env` file is
 * present at the repo root; values are parsed at runtime and NEVER printed.
 */
class RealStageTokenizationTest {

    @Test
    fun realStageTokenizationThroughTheSdk() {
        val credentials = loadStageCredentials() ?: return // no credentials: skip silently
        val (username, password) = credentials
        val config = HiPayConfig(username, password, Environment.STAGE)

        val token = runBlocking {
            CardTokenizer(config).generateToken(
                cardNumber = "4111111111111111",
                expiryMonth = "12",
                expiryYear = "2026",
                holder = "Test",
                cvc = "123",
                multiUse = false,
            )
        }
        assertTrue(token.token.isNotEmpty())
        assertTrue(token.pan?.startsWith("411111") == true)
    }

    /**
     * Real-stage card-network resolution: a Maestro card co-badged BCMC
     * (`6703…449`, verified live 2026-06-13) resolves to brand MAESTRO +
     * domestic_network bcmc → BCMC selected first (co-brand default).
     */
    @Test
    fun realStageCardInfoResolvesBcmcCoBrand() {
        val credentials = loadStageCredentials() ?: return
        val (username, password) = credentials
        val config = HiPayConfig(username, password, Environment.STAGE)

        val info = runBlocking {
            CardTokenizer(config).resolveCardInfo(
                cardNumber = "6703444444444449",
                expiryMonth = "12",
                expiryYear = "2027",
            )
        }
        assertEquals("MAESTRO", info.brand)
        assertEquals("bcmc", info.domesticNetwork)
        assertEquals(
            listOf(com.hipay.card.validation.CardNetwork.BCMC, com.hipay.card.validation.CardNetwork.MAESTRO),
            info.resolvedNetworks(),
        )
    }

    /** Parses `export KEY=value` lines; walks up from the working dir. */
    private fun loadStageCredentials(): Pair<String, String>? {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(4) {
            val candidate = File(dir, ".hipay_stage_env")
            if (candidate.isFile) {
                val values = candidate.readLines()
                    .mapNotNull { line ->
                        Regex("""export\s+(\w+)=(.*)""").find(line.trim())
                            ?.let { it.groupValues[1] to it.groupValues[2].trim('"', '\'') }
                    }.toMap()
                val user = values["HIPAY_STAGE_USERNAME"] ?: return null
                val pass = values["HIPAY_STAGE_PASSWORD"] ?: return null
                return user to pass
            }
            dir = dir?.parentFile ?: return null
        }
        return null
    }
}
