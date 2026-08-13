package com.hipay.core.gateway

import com.hipay.card.CardTokenizer
import com.hipay.card.validation.CardNetwork
import com.hipay.card.validation.CardNetworks
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayException
import com.hipay.core.gateway.model.CustomerInfo
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.TransactionState
import com.hipay.core.http.HipayHttpClient
import com.hipay.core.http.defaultHttpClientEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Gated real-stage probe of the order endpoint's CUSTOMER/SHIPPING contract.
 *
 * The SDK validates none of the eleven `CustomerInfo` properties — whatever a caller sets goes on the
 * wire as-is, and every null is simply omitted. So what a partially filled form produces is decided
 * entirely by the server, and reading the client-side serializer cannot answer it. This submits real
 * stage orders across field subsets and reports what the gateway does with each.
 *
 * Only the empty-customer baseline is asserted: an order with no customer and no shipping must still
 * complete. Every other probe is REPORTED, never asserted — the gateway's answer is the finding here,
 * and pinning a finding as an assertion would turn a relaxed server rule into a red build.
 *
 * Credentials come from the git-ignored `.hipay_stage_env`; with no file the tests skip silently, like
 * the other real-stage tests. Field NAMES and outcomes are printed, never the PAN, the token or the
 * credentials.
 */
class RealStageCustomerFieldsTest {

    /**
     * The invariant the demo customer form must never break: empty by default, and a payment still
     * succeeds. This is the ONLY assertion in this class.
     */
    @Test
    fun orderWithNoCustomerAndNoShippingStillCompletes() {
        val ctx = stageContext() ?: return // no credentials: skip silently

        runBlocking {
            val outcome = ctx.submit("baseline-empty", customer = null, shipping = null)
            assertEquals(
                TransactionState.COMPLETED,
                (outcome as? ProbeOutcome.Accepted)?.state,
                "an order carrying no customer and no shipping data must still complete — got $outcome",
            )
        }
    }

    /**
     * The matrix run. One test so a single execution yields the whole table; each probe is an
     * independent real order (fresh single-use token, unique order id).
     */
    @Test
    fun customerFieldContractMatrix() {
        val ctx = stageContext() ?: return // no credentials: skip silently

        runBlocking {
            // Guard: if the baseline itself does not complete, the account or the card product is the
            // problem and every following probe would be misread as a field rejection.
            val baseline = ctx.submit("baseline-empty", customer = null, shipping = null)
            if ((baseline as? ProbeOutcome.Accepted)?.state != TransactionState.COMPLETED) {
                println(
                    "[real-stage] ABORTED — the empty-customer baseline did not complete ($baseline).\n" +
                        "[real-stage] The account or the card product is the problem, NOT the customer fields.\n" +
                        "[real-stage] product=${ctx.paymentProduct}; check the account's accepted products first.",
                )
                return@runBlocking
            }

            val rows = mutableListOf<Pair<Probe, ProbeOutcome>>()
            rows += Probe("baseline-empty", null, null) to baseline
            for (probe in PROBES) {
                rows += probe to ctx.submit(probe.label, probe.customer, probe.shipping)
            }

            printMatrix(ctx, rows)
        }
    }

    // ---------------------------------------------------------------- probes

    private class Probe(
        val label: String,
        val customer: CustomerInfo?,
        val shipping: CustomerInfo?,
    )

    private sealed class ProbeOutcome {
        /** The gateway accepted the order. [echoed] lists the submitted keys found in the
         *  transaction payload — the accepted-versus-stored distinction. */
        class Accepted(
            val state: TransactionState,
            val echoed: List<String>?,
        ) : ProbeOutcome() {
            override fun toString(): String = "accepted/$state"
        }

        /** The gateway refused. A refusal with no api code is itself a finding. */
        class Rejected(
            val errorCode: String,
            val httpStatus: Int?,
            val apiCode: Int?,
            val apiMessage: String?,
        ) : ProbeOutcome() {
            override fun toString(): String = "rejected/$errorCode(http=$httpStatus, api=$apiCode)"
        }
    }

    // ------------------------------------------------------------- execution

    private class StageContext(
        val config: HiPayConfig,
        val passphrase: String,
        val paymentProduct: String,
        val pan: String,
    ) {
        private val gateway = GatewayClient(config)
        private val tokenizer = CardTokenizer(config)
        private val raw = HipayHttpClient(config, defaultHttpClientEngine())
        private var sequence = 0

        suspend fun submit(label: String, customer: CustomerInfo?, shipping: CustomerInfo?): ProbeOutcome {
            val orderId = "TEST-KMP-165-${System.currentTimeMillis()}-${sequence++}"
            val signature = sha1Hex(orderId + AMOUNT + CURRENCY + passphrase)
            return try {
                // A single-use token is consumed by its order: minting a fresh one per probe keeps a
                // token-reuse refusal from being read as a field refusal.
                val token = tokenizer.generateToken(
                    cardNumber = pan,
                    expiryMonth = "12",
                    expiryYear = "2030",
                    holder = "Probe",
                    cvc = "123",
                    multiUse = false,
                )
                val transaction = gateway.requestNewOrder(
                    OrderRequest(
                        orderId = orderId,
                        paymentProduct = paymentProduct,
                        amount = AMOUNT,
                        currency = CURRENCY,
                        description = "customer-field contract probe: $label",
                        language = "fr_FR",
                        acceptUrl = callbackUrl(orderId, "accept"),
                        declineUrl = callbackUrl(orderId, "decline"),
                        pendingUrl = callbackUrl(orderId, "pending"),
                        exceptionUrl = callbackUrl(orderId, "exception"),
                        cancelUrl = callbackUrl(orderId, "cancel"),
                        cardToken = token.token,
                        authenticationIndicator = 0,
                        customer = customer,
                        shippingAddress = shipping,
                    ),
                    signature = signature,
                )
                ProbeOutcome.Accepted(
                    state = transaction.state,
                    echoed = transaction.transactionReference?.let {
                        echoedKeys(it, signature, customer, shipping)
                    },
                )
            } catch (e: HiPayException) {
                ProbeOutcome.Rejected(
                    errorCode = e.code.name,
                    httpStatus = e.httpStatus,
                    apiCode = e.apiCode,
                    apiMessage = e.apiMessage,
                )
            }
        }

        /**
         * Which submitted keys the gateway echoes back — an accepted order is not proof the field was
         * kept. The typed [com.hipay.core.gateway.model.Transaction] carries no customer data, so the
         * raw payload is read through the internal client.
         *
         * Only values of four characters or more are searched: `FR`, `US`, `CA` are too short to be
         * told apart from unrelated content, so those keys are reported as inconclusive rather than
         * guessed.
         */
        private suspend fun echoedKeys(
            reference: String,
            signature: String,
            customer: CustomerInfo?,
            shipping: CustomerInfo?,
        ): List<String>? = try {
            val body = raw.get(
                url = config.environment.gatewayV1Url + "transaction/" + reference,
                signature = signature,
            )
            // The payload escapes non-ASCII as \uXXXX, so an accented value would never match a raw
            // substring search — decode first, or every accented field reads as "not echoed".
            val readable = decodeUnicodeEscapes(body)
            submittedFields(customer, shipping)
                .filter { (_, value) -> value.length >= 4 && readable.contains(value, ignoreCase = true) }
                .keys
                .toList()
        } catch (_: HiPayException) {
            null
        }

        private fun callbackUrl(orderId: String, status: String) =
            "hipaydemo://hipay-fullservice/gateway/orders/$orderId/$status"
    }

    // ------------------------------------------------------------- reporting

    private fun printMatrix(ctx: StageContext, rows: List<Pair<Probe, ProbeOutcome>>) {
        println("[real-stage] customer-field contract — product=${ctx.paymentProduct}, ${rows.size} probes")
        println("")
        println("| Probe | Submitted keys | Outcome | apiCode / message | Echoed back? |")
        println("|---|---|---|---|---|")
        for ((probe, outcome) in rows) {
            val keys = submittedFields(probe.customer, probe.shipping).keys
            val submitted = if (keys.isEmpty()) "*(none)*" else keys.joinToString(", ")
            val detail: String
            val echoed: String
            when (outcome) {
                is ProbeOutcome.Accepted -> {
                    detail = ""
                    echoed = when {
                        outcome.echoed == null -> "unreadable"
                        keys.isEmpty() -> "n/a"
                        outcome.echoed.isEmpty() -> "none"
                        else -> outcome.echoed.joinToString(", ")
                    }
                }
                is ProbeOutcome.Rejected -> {
                    detail = listOfNotNull(
                        outcome.apiCode?.toString() ?: "no api code",
                        outcome.apiMessage,
                    ).joinToString(" — ")
                    echoed = "n/a"
                }
            }
            println("| `${probe.label}` | $submitted | $outcome | $detail | $echoed |")
        }
        println("")
        println("[real-stage] values shorter than 4 characters (country, state) are not echo-checkable")
    }

    // --------------------------------------------------------------- helpers

    private fun stageContext(): StageContext? {
        val env = loadStageEnv() ?: return null
        val username = env["HIPAY_STAGE_USERNAME"] ?: return null
        val password = env["HIPAY_STAGE_PASSWORD"] ?: return null
        val passphrase = env["HIPAY_STAGE_PASSPHRASE"] ?: return null
        val config = HiPayConfig(username, password, Environment.STAGE)

        // The account decides which product is payable at all. Hard-coding one the account refuses
        // would fail every probe for a reason that has nothing to do with customer fields.
        val accepted = runBlocking {
            try {
                GatewayClient(config).getAvailablePaymentProducts(
                    paymentProducts = CardNetworks.cardPaymentProductCodes,
                    currency = CURRENCY,
                )
            } catch (_: HiPayException) {
                emptySet()
            }
        }
        val usable = PROBE_CARDS.entries.firstOrNull { it.key in accepted }
        if (usable == null) {
            println(
                "[real-stage] no probe card matches the account's accepted products ($accepted) — " +
                    "add a test PAN for one of them, or enable visa/mastercard on the account",
            )
            return null
        }
        return StageContext(config, passphrase, usable.value.first, usable.value.second)
    }

    private fun loadStageEnv(): Map<String, String>? {
        var dir: File? = System.getProperty("user.dir")?.let(::File) ?: return null
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

    private companion object {
        const val AMOUNT = "1.00"
        const val CURRENCY = "EUR"

        /** Networks we hold a usable stage PAN for, in preference order → (wire product code, PAN). */
        val PROBE_CARDS: Map<CardNetwork, Pair<String, String>> = linkedMapOf(
            CardNetwork.VISA to ("visa" to "4111111111111111"),
            CardNetwork.MASTERCARD to ("mastercard" to "5555555555554444"),
        )

        /** Distinctive enough to be searched for in the transaction payload. */
        const val FIRST = "Probefirst"
        const val LAST = "Probelast"
        const val STREET = "1 Probe Street"
        const val STREET2 = "Building Probe"
        const val RECIPIENT = "Probe Recipient"
        const val CITY = "Probeville"
        const val ZIP = "75002"
        const val EMAIL = "probe.sixteen.five@example.com"
        const val PHONE = "+33102030405"

        val PROBES: List<Probe> = listOf(
            Probe(
                "customer-full",
                CustomerInfo(
                    firstName = FIRST, lastName = LAST, streetAddress = STREET,
                    streetAddress2 = STREET2, recipientInfo = RECIPIENT, city = CITY,
                    state = null, zipCode = ZIP, country = "FR", email = EMAIL, phone = PHONE,
                ),
                null,
            ),
            Probe(
                "dsp2-subset",
                CustomerInfo(
                    firstName = FIRST, lastName = LAST, email = EMAIL, phone = PHONE,
                    streetAddress = STREET, city = CITY, zipCode = ZIP, country = "FR",
                ),
                null,
            ),
            Probe("identity-only", CustomerInfo(firstName = FIRST, lastName = LAST), null),
            Probe("email-only", CustomerInfo(email = EMAIL), null),
            Probe("phone-only", CustomerInfo(phone = PHONE), null),
            Probe("address-partial", CustomerInfo(streetAddress = STREET, city = CITY), null),
            Probe(
                "address-no-country",
                CustomerInfo(streetAddress = STREET, city = CITY, zipCode = ZIP),
                null,
            ),
            Probe("country-only", CustomerInfo(country = "FR"), null),
            Probe("country-invalid", CustomerInfo(country = "FRANCE"), null),
            Probe(
                "state-on-fr",
                CustomerInfo(
                    streetAddress = STREET, city = CITY, state = "IDF",
                    zipCode = ZIP, country = "FR",
                ),
                null,
            ),
            Probe(
                "state-on-us",
                CustomerInfo(
                    streetAddress = STREET, city = CITY, state = "CA",
                    zipCode = "94105", country = "US",
                ),
                null,
            ),
            Probe("email-malformed", CustomerInfo(email = "not-an-email"), null),
            Probe("phone-non-e164", CustomerInfo(phone = "0102030405"), null),
            Probe(
                "utf8-accents",
                CustomerInfo(
                    firstName = "Éloïse", lastName = "Nguyễn",
                    streetAddress = "12 rue de l'Église", city = "Besançon",
                    zipCode = ZIP, country = "FR",
                ),
                null,
            ),
            Probe(
                "value-overlong",
                CustomerInfo(
                    streetAddress = "P".repeat(300), city = CITY,
                    zipCode = ZIP, country = "FR",
                ),
                null,
            ),
            Probe(
                "shipping-only",
                null,
                CustomerInfo(
                    firstName = FIRST, lastName = LAST, streetAddress = STREET,
                    streetAddress2 = STREET2, recipientInfo = RECIPIENT, city = CITY,
                    zipCode = ZIP, country = "FR",
                ),
            ),
            Probe("shipping-partial", null, CustomerInfo(streetAddress = STREET, city = CITY)),
            Probe(
                "shipping-no-country",
                null,
                CustomerInfo(streetAddress = STREET, city = CITY, zipCode = ZIP),
            ),
            Probe(
                "customer-and-shipping",
                CustomerInfo(
                    firstName = FIRST, lastName = LAST, email = EMAIL, phone = PHONE,
                    streetAddress = STREET, city = CITY, zipCode = ZIP, country = "FR",
                ),
                CustomerInfo(
                    firstName = FIRST, lastName = LAST, streetAddress = STREET,
                    city = CITY, zipCode = ZIP, country = "FR",
                ),
            ),
        )

        /** The exact wire keys a probe puts on the request — the same mapping the order builds. */
        fun submittedFields(customer: CustomerInfo?, shipping: CustomerInfo?): Map<String, String> {
            val fields = linkedMapOf<String, String>()
            customer?.let { fields.putAll(it.toFields()) }
            shipping?.let { fields.putAll(it.toFields(prefix = "shipto_", personalInfoOnly = true)) }
            return fields
        }

        private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")

        fun decodeUnicodeEscapes(text: String): String =
            UNICODE_ESCAPE.replace(text) { it.groupValues[1].toInt(16).toChar().toString() }

        fun sha1Hex(input: String): String =
            MessageDigest.getInstance("SHA-1")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
