package com.hipay.core.gateway

import com.hipay.card.CardTokenizer
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.TransactionState
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Gated real-stage verification (story 3.2 AC: frictionless COMPLETED
 * confirmed via getTransaction). The SHA-1 signature is computed HERE — the
 * test plays the merchant-backend role; the library never computes it.
 * Credentials/passphrase come from the git-ignored `.hipay_stage_env` and are
 * never printed.
 */
class RealStageGatewayTest {

    @Test
    fun frictionlessOrderCompletesAndIsConfirmedViaGetTransaction() {
        val env = loadStageEnv() ?: return // no credentials: skip silently
        val username = env["HIPAY_STAGE_USERNAME"] ?: return
        val password = env["HIPAY_STAGE_PASSWORD"] ?: return
        val passphrase = env["HIPAY_STAGE_PASSPHRASE"] ?: return
        val config = HiPayConfig(username, password, Environment.STAGE)

        val orderId = "TEST-KMP-32-${System.currentTimeMillis()}"
        val amount = "1.00"
        val currency = "EUR"
        // Merchant-backend role: signature computed OUTSIDE the library.
        val signature = sha1Hex(orderId + amount + currency + passphrase)

        runBlocking {
            val token = CardTokenizer(config).generateToken(
                cardNumber = "4111111111111111",
                expiryMonth = "12",
                expiryYear = "2027",
                holder = "Test",
                cvc = "123",
                multiUse = false,
            )

            val gateway = GatewayClient(config)
            val tx = gateway.requestNewOrder(
                OrderRequest(
                    orderId = orderId,
                    paymentProduct = "visa",
                    amount = amount,
                    currency = currency,
                    description = "KMP SDK story 3.2 check",
                    language = "fr_FR",
                    acceptUrl = "hipaydemo://hipay-fullservice/gateway/orders/$orderId/accept",
                    declineUrl = "hipaydemo://hipay-fullservice/gateway/orders/$orderId/decline",
                    pendingUrl = "hipaydemo://hipay-fullservice/gateway/orders/$orderId/pending",
                    exceptionUrl = "hipaydemo://hipay-fullservice/gateway/orders/$orderId/exception",
                    cancelUrl = "hipaydemo://hipay-fullservice/gateway/orders/$orderId/cancel",
                    cardToken = token.token,
                    authenticationIndicator = 0,
                ),
                signature = signature,
            )
            assertEquals(TransactionState.COMPLETED, tx.state)
            val reference = assertNotNull(tx.transactionReference)

            // FR9: confirm server-side (same signature, legacy demo model)
            val confirmed = GatewayClient(config).getTransaction(reference, signature = signature)
            assertEquals(TransactionState.COMPLETED, confirmed.state)
        }
    }

    /**
     * Challenge order (authentication_indicator=2): the Gateway answers
     * FORWARDING with a forwardUrl and `"threeDSecure": ""` — the empty-string
     * sub-object shape that broke parsing in the demo (bug 2026-06-13).
     */
    @Test
    fun challengeOrderReturnsForwardingWithForwardUrl() {
        val env = loadStageEnv() ?: return // no credentials: skip silently
        val username = env["HIPAY_STAGE_USERNAME"] ?: return
        val password = env["HIPAY_STAGE_PASSWORD"] ?: return
        val passphrase = env["HIPAY_STAGE_PASSPHRASE"] ?: return
        val config = HiPayConfig(username, password, Environment.STAGE)

        val orderId = "TEST-KMP-3DS-${System.currentTimeMillis()}"
        val amount = "1.00"
        val currency = "EUR"
        val signature = sha1Hex(orderId + amount + currency + passphrase)

        runBlocking {
            val token = CardTokenizer(config).generateToken(
                cardNumber = "4242424242424242",
                expiryMonth = "12",
                expiryYear = "2027",
                holder = "Test",
                cvc = "123",
                multiUse = false,
            )

            val tx = GatewayClient(config).requestNewOrder(
                OrderRequest(
                    orderId = orderId,
                    paymentProduct = "visa",
                    amount = amount,
                    currency = currency,
                    description = "KMP SDK 3DS challenge check",
                    language = "fr_FR",
                    acceptUrl = "hipaydemo://hipay-fullservice/gateway/orders/$orderId/accept",
                    declineUrl = "hipaydemo://hipay-fullservice/gateway/orders/$orderId/decline",
                    pendingUrl = "hipaydemo://hipay-fullservice/gateway/orders/$orderId/pending",
                    exceptionUrl = "hipaydemo://hipay-fullservice/gateway/orders/$orderId/exception",
                    cancelUrl = "hipaydemo://hipay-fullservice/gateway/orders/$orderId/cancel",
                    cardToken = token.token,
                    authenticationIndicator = 2,
                ),
                signature = signature,
            )
            assertEquals(TransactionState.FORWARDING, tx.state)
            val forwardUrl = assertNotNull(tx.forwardUrl)
            assertEquals(true, forwardUrl.startsWith("https://"))
            assertEquals(null, tx.threeDSecure) // "" on the wire -> null
        }
    }

    private fun sha1Hex(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

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
