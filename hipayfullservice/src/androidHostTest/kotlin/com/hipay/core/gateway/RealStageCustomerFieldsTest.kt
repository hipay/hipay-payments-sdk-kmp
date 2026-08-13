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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Gated real-stage probe of the order endpoint's CUSTOMER/SHIPPING contract.
 *
 * The SDK validates none of the eleven `CustomerInfo` properties — whatever a caller sets goes on the
 * wire as-is, and every null is simply omitted. So what a partially filled form produces is decided
 * entirely by the server, and reading the client-side serializer cannot answer it. This submits real
 * stage orders across field subsets and reports what the gateway does with each.
 *
 * Only the empty-customer baseline is asserted: an order with no customer and no shipping must still
 * be accepted. Every other probe is REPORTED, never asserted — the gateway's answer is the finding
 * here, and pinning a finding as an assertion would turn a relaxed server rule into a red build.
 *
 * **Opt-in.** Each run books real stage transactions of 1.00 EUR, so credential presence alone does
 * not start it: pass `-Phipay.stage.probe=true`. Without it every test returns silently, which keeps
 * a routine `check` free even on a machine holding `.hipay_stage_env`. With the flag set but the
 * credentials unusable the run FAILS rather than passing green — an opted-in run that measured
 * nothing is a misconfiguration, not a success.
 *
 * Credentials come from the git-ignored `.hipay_stage_env`. Field NAMES, outcomes and order ids are
 * printed, never the PAN, the token or the credentials.
 */
class RealStageCustomerFieldsTest {

    /**
     * The invariant the demo customer form must never break: empty by default, and the payment is
     * still accepted. This is the ONLY assertion in this class.
     *
     * It asserts ACCEPTANCE, not `COMPLETED`. The acquirer is a live third party and has been
     * observed answering `PENDING` to an unchanged field set, so asserting `COMPLETED` would go red
     * for a reason this story explicitly declares out of scope. What must hold is that the gateway
     * did not REFUSE the order for carrying no customer data.
     */
    @Test
    fun orderWithNoCustomerAndNoShippingStillCompletes() {
        val ctx = stageContext() ?: return // not opted in, or no credentials: skip silently

        ctx.use {
            runBlocking {
                val outcome = ctx.submit("baseline-empty", customer = null, shipping = null)
                if (outcome is ProbeOutcome.HarnessError) {
                    fail("the probe harness failed before the gateway answered: ${outcome.detail}")
                }
                val accepted = outcome as? ProbeOutcome.Accepted
                assertTrue(
                    accepted != null,
                    "an order carrying no customer and no shipping data must still be accepted — got $outcome",
                )
                if (accepted.state != TransactionState.COMPLETED) {
                    println(
                        "[real-stage] NOTE — the empty baseline was accepted as ${accepted.state}, not COMPLETED. " +
                            "Acceptance is what this asserts; a non-COMPLETED state is acquirer-side and must " +
                            "never be attributed to the customer fields.",
                    )
                }
            }
        }
    }

    /**
     * The matrix run. One test so a single execution yields the whole table; each probe is an
     * independent real order (fresh single-use token, unique order id).
     */
    @Test
    fun customerFieldContractMatrix() {
        val ctx = stageContext() ?: return // not opted in, or no credentials: skip silently

        ctx.use {
            runBlocking {
                // Guard: if the baseline itself is not accepted, the account or the card product is
                // the problem and every following probe would be misread as a field rejection.
                val baseline = ctx.submit("baseline-empty", customer = null, shipping = null)
                if (baseline !is ProbeOutcome.Accepted) {
                    println(
                        "[real-stage] ABORTED — the empty-customer baseline was not accepted ($baseline).\n" +
                            "[real-stage] The account or the card product is the problem, NOT the customer fields.\n" +
                            "[real-stage] product=${ctx.paymentProduct}; check the account's accepted products first.",
                    )
                    return@runBlocking
                }

                val rows = mutableListOf<Pair<Probe, ProbeOutcome>>()
                rows += Probe("baseline-empty", null, null) to baseline
                // Printed from a finally block: a cancellation or an unmapped failure on probe N must
                // not discard the N-1 rows already paid for in real transactions.
                try {
                    for (probe in PROBES) {
                        rows += probe to ctx.submit(probe.label, probe.customer, probe.shipping)
                    }
                } finally {
                    printMatrix(ctx, rows)
                }
            }
        }
    }

    // ---------------------------------------------------------------- probes

    private class Probe(
        val label: String,
        val customer: CustomerInfo?,
        val shipping: CustomerInfo?,
    )

    /** What the echo check could establish about one submitted key. */
    private enum class Echo {
        /** The exact value came back as a complete JSON string. */
        PRESENT,

        /** Found, but not as a whole value — normalized, truncated or embedded. */
        PARTIAL,

        /** Searchable and genuinely not in the payload. */
        ABSENT,

        /** Too short to be told apart from unrelated content — NOT evidence either way. */
        INCONCLUSIVE,
    }

    private sealed class ProbeOutcome {
        /**
         * The gateway accepted the order. [echo] is the per-key verdict, or null when the payload
         * could not be read at all — see [EchoFailure] for why that is kept distinct.
         */
        class Accepted(
            val state: TransactionState,
            val orderId: String,
            val echo: Map<String, Echo>?,
            val echoFailure: EchoFailure?,
        ) : ProbeOutcome() {
            override fun toString(): String = "accepted/$state"
        }

        /** The gateway refused. A refusal with no api code is itself a finding. */
        class Rejected(
            val errorCode: String,
            val orderId: String,
            val httpStatus: Int?,
            val apiCode: Int?,
            val apiMessage: String?,
        ) : ProbeOutcome() {
            override fun toString(): String = "rejected/$errorCode(http=$httpStatus, api=$apiCode)"
        }

        /**
         * The harness broke before the gateway could answer — tokenization refused, transport died,
         * anything not attributable to the customer fields. Kept apart from [Rejected] on purpose: a
         * Secure Vault outage reported as a rejection reads as "the gateway refuses this field
         * subset", which is the single most misleading thing this matrix could say.
         */
        class HarnessError(
            val stage: String,
            val orderId: String,
            val detail: String,
        ) : ProbeOutcome() {
            override fun toString(): String = "harness-error/$stage"
        }
    }

    /** Why the accepted-vs-stored check could not run — three situations, not one. */
    private enum class EchoFailure {
        /** The gateway accepted the order but returned no transaction reference. */
        NO_REFERENCE,

        /** The transaction fetch itself was refused or failed. */
        FETCH_FAILED,
    }

    // ------------------------------------------------------------- execution

    private class StageContext(
        val config: HiPayConfig,
        val passphrase: String,
        val paymentProduct: String,
        val pan: String,
        val gateway: GatewayClient,
    ) : AutoCloseable {
        private val tokenizer = CardTokenizer(config)
        private val rawEngine = defaultHttpClientEngine()
        private val raw = HipayHttpClient(config, rawEngine)
        private var sequence = 0

        suspend fun submit(label: String, customer: CustomerInfo?, shipping: CustomerInfo?): ProbeOutcome {
            val orderId = "$ORDER_ID_PREFIX${System.currentTimeMillis()}-${sequence++}"
            // An over-long orderid is refused with the SAME generic 1010202 "Invalid Parameter" as a bad
            // country, so without this check a prefix rename reads as a customer-field rejection — which
            // is precisely how it presented once. Fail as a harness error instead.
            if (orderId.length > MAX_ORDER_ID_LENGTH) {
                return ProbeOutcome.HarnessError(
                    stage = "order-id",
                    orderId = orderId,
                    detail = "orderid is ${orderId.length} characters, over the $MAX_ORDER_ID_LENGTH " +
                        "the gateway accepts — shorten ORDER_ID_PREFIX; this is not a gateway verdict",
                )
            }
            val signature = sha1Hex(orderId + AMOUNT + CURRENCY + passphrase)

            // Tokenization is a SEPARATE failure domain from the order. Sharing one try/catch with
            // the order would let a vault outage surface as a field rejection.
            val token = try {
                // A single-use token is consumed by its order: minting a fresh one per probe keeps a
                // token-reuse refusal from being read as a field refusal.
                tokenizer.generateToken(
                    cardNumber = pan,
                    expiryMonth = "12",
                    expiryYear = tokenExpiryYear(),
                    holder = CARD_HOLDER,
                    cvc = "123",
                    multiUse = false,
                )
            } catch (e: HiPayException) {
                return ProbeOutcome.HarnessError(
                    stage = "tokenization",
                    orderId = orderId,
                    detail = "${e.code.name}(http=${e.httpStatus}, api=${e.apiCode})",
                )
            }

            return try {
                val transaction = gateway.requestNewOrder(
                    OrderRequest(
                        orderId = orderId,
                        paymentProduct = paymentProduct,
                        amount = AMOUNT,
                        currency = CURRENCY,
                        // Deliberately carries no probe value: the echo check searches this payload,
                        // and a label or a field value repeated here would match itself.
                        description = ORDER_DESCRIPTION,
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
                val reference = transaction.transactionReference
                if (reference == null) {
                    ProbeOutcome.Accepted(transaction.state, orderId, null, EchoFailure.NO_REFERENCE)
                } else {
                    when (val echo = echoedKeys(reference, signature, customer, shipping)) {
                        null -> ProbeOutcome.Accepted(
                            transaction.state, orderId, null, EchoFailure.FETCH_FAILED,
                        )
                        else -> ProbeOutcome.Accepted(transaction.state, orderId, echo, null)
                    }
                }
            } catch (e: HiPayException) {
                ProbeOutcome.Rejected(
                    errorCode = e.code.name,
                    orderId = orderId,
                    httpStatus = e.httpStatus,
                    apiCode = e.apiCode,
                    apiMessage = e.apiMessage,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Anything the SDK does not map stays out of the rejection column.
                ProbeOutcome.HarnessError(
                    stage = "order",
                    orderId = orderId,
                    detail = "${e::class.simpleName}: ${e.message}",
                )
            }
        }

        /**
         * Which submitted keys the gateway echoes back — an accepted order is not proof the field was
         * kept. The typed [com.hipay.core.gateway.model.Transaction] carries no customer data, so the
         * raw payload is read through the internal client.
         *
         * A value is matched as a COMPLETE JSON string (`"value"`), not as a bare substring: a
         * five-digit postcode would otherwise match the digits of a transaction reference or a
         * timestamp. A bare-substring hit that is not a whole value is reported [Echo.PARTIAL], which
         * is how server-side normalization or truncation shows up instead of masquerading as a clean
         * echo. Values under four characters are [Echo.INCONCLUSIVE] — never reported as absent.
         *
         * Returns null when the payload could not be read at all.
         */
        private suspend fun echoedKeys(
            reference: String,
            signature: String,
            customer: CustomerInfo?,
            shipping: CustomerInfo?,
        ): Map<String, Echo>? = try {
            val body = raw.get(
                url = config.environment.gatewayV1Url + "transaction/" + reference,
                signature = signature,
            )
            // The payload escapes non-ASCII as \uXXXX, so an accented value would never match a raw
            // substring search — decode first, or every accented field reads as "not echoed".
            val readable = decodeUnicodeEscapes(body)
            submittedFields(customer, shipping).mapValues { (_, value) ->
                when {
                    value.length < MIN_ECHO_LENGTH -> Echo.INCONCLUSIVE
                    readable.contains("\"$value\"", ignoreCase = true) -> Echo.PRESENT
                    readable.contains(value, ignoreCase = true) -> Echo.PARTIAL
                    else -> Echo.ABSENT
                }
            }
        } catch (_: HiPayException) {
            null
        }

        private fun callbackUrl(orderId: String, status: String) =
            "hipaydemo://hipay-fullservice/gateway/orders/$orderId/$status"

        override fun close() {
            // Only the engine this class constructs can be released: GatewayClient and CardTokenizer
            // own theirs privately and expose no close(), so those leak for the test JVM's lifetime.
            // Releasing them would need an SDK API change, which this story's scope forbids.
            runCatching { rawEngine.close() }
        }
    }

    // ------------------------------------------------------------- reporting

    private fun printMatrix(ctx: StageContext, rows: List<Pair<Probe, ProbeOutcome>>) {
        val probeCount = rows.size - 1 // the baseline is a control, not a probe
        println(
            "[real-stage] customer-field contract — product=${ctx.paymentProduct}, " +
                "1 baseline + $probeCount probes",
        )
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
                        outcome.echoFailure == EchoFailure.NO_REFERENCE -> "no transaction reference"
                        outcome.echoFailure == EchoFailure.FETCH_FAILED -> "fetch failed"
                        keys.isEmpty() -> "n/a"
                        else -> describeEcho(outcome.echo.orEmpty())
                    }
                }
                is ProbeOutcome.Rejected -> {
                    detail = cell(
                        listOfNotNull(
                            outcome.apiCode?.toString() ?: "no api code",
                            outcome.apiMessage,
                        ).joinToString(" — "),
                    )
                    echoed = "n/a"
                }
                is ProbeOutcome.HarnessError -> {
                    // Never blank: a reader must not mistake a harness failure for a clean accept.
                    detail = cell("HARNESS FAILURE at ${outcome.stage} — ${outcome.detail}; not a gateway verdict")
                    echoed = "n/a"
                }
            }
            println("| `${probe.label}` | $submitted | $outcome | $detail | $echoed |")
        }
        println("")
        println(
            "[real-stage] echo legend: bare = returned as a whole value; `~key` = returned but " +
                "normalized/truncated; `!key` = searchable and absent; `?key` = under " +
                "$MIN_ECHO_LENGTH characters, NOT echo-checkable (no conclusion either way)",
        )
        println("[real-stage] order ids, for back-office reconciliation of the transactions just created:")
        for ((probe, outcome) in rows) {
            val orderId = when (outcome) {
                is ProbeOutcome.Accepted -> outcome.orderId
                is ProbeOutcome.Rejected -> outcome.orderId
                is ProbeOutcome.HarnessError -> outcome.orderId
            }
            println("[real-stage]   ${probe.label} -> $orderId")
        }
    }

    /** Renders the per-key verdicts, keeping "not checkable" visibly apart from "not returned". */
    private fun describeEcho(echo: Map<String, Echo>): String {
        if (echo.isEmpty()) return "n/a"
        val rendered = echo.map { (key, verdict) ->
            when (verdict) {
                Echo.PRESENT -> key
                Echo.PARTIAL -> "~$key"
                Echo.ABSENT -> "!$key"
                Echo.INCONCLUSIVE -> "?$key"
            }
        }
        return rendered.joinToString(", ")
    }

    /** Server-controlled text must not break the table this run exists to have copy-pasted. */
    private fun cell(text: String): String =
        text.replace("|", "\\|").replace("\n", " ").replace("\r", " ").trim()

    // --------------------------------------------------------------- helpers

    private fun stageContext(): StageContext? {
        // Opt-in first: without the flag nothing here may touch the network, whatever credentials
        // happen to be on the machine.
        if (System.getProperty(PROBE_FLAG) != "true") return null

        val env = loadStageEnv() ?: return null
        val username = env.usable("HIPAY_STAGE_USERNAME") ?: return null
        val password = env.usable("HIPAY_STAGE_PASSWORD") ?: return null
        val passphrase = env.usable("HIPAY_STAGE_PASSPHRASE") ?: return null
        val config = HiPayConfig(username, password, Environment.STAGE)

        // The account decides which product is payable at all. Hard-coding one the account refuses
        // would fail every probe for a reason that has nothing to do with customer fields.
        val gateway = GatewayClient(config)
        val accepted = runBlocking {
            try {
                gateway.getAvailablePaymentProducts(
                    paymentProducts = CardNetworks.cardPaymentProductCodes,
                    currency = CURRENCY,
                )
            } catch (e: HiPayException) {
                // Opted in, credentials present, and the account could not be queried: failing is
                // the point. Swallowing this produced a green run that asserted nothing at all.
                fail(
                    "opted in with credentials, but the account's payment products could not be " +
                        "resolved (${e.code.name}, http=${e.httpStatus}) — the probe measured nothing",
                )
            }
        }
        val usable = PROBE_CARDS.entries.firstOrNull { it.key in accepted }
            ?: fail(
                "opted in with credentials, but no probe card matches the account's accepted " +
                    "products ($accepted) — add a test PAN for one of them, or enable " +
                    "visa/mastercard on the account",
            )
        return StageContext(config, passphrase, usable.value.first, usable.value.second, gateway)
    }

    /** A key present but blank is an INCOMPLETE credential file, not a usable value. */
    private fun Map<String, String>.usable(key: String): String? = this[key]?.takeIf { it.isNotBlank() }

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
        const val PROBE_FLAG = "hipay.stage.probe"
        const val AMOUNT = "1.00"
        const val CURRENCY = "EUR"

        /**
         * The gateway refuses a longer `orderid` with a generic 1010202, so the prefix has to leave room
         * for a 13-digit millisecond stamp and a two-digit sequence: 32 - 13 - 1 - 2 = 16 characters max.
         */
        const val MAX_ORDER_ID_LENGTH = 32
        const val ORDER_ID_PREFIX = "TEST-KMP-CUST-"

        /** Below this length a value cannot be told apart from unrelated payload content. */
        const val MIN_ECHO_LENGTH = 4

        /**
         * Neither may contain a probe value: both are echoed by the transaction payload, so a shared
         * string would match itself and read as a stored customer field.
         */
        const val ORDER_DESCRIPTION = "customer-field contract probe"
        const val CARD_HOLDER = "Cardholder"

        /** Networks we hold a usable stage PAN for, in preference order → (wire product code, PAN). */
        val PROBE_CARDS: Map<CardNetwork, Pair<String, String>> = linkedMapOf(
            CardNetwork.VISA to ("visa" to "4111111111111111"),
            CardNetwork.MASTERCARD to ("mastercard" to "5555555555554444"),
        )

        /**
         * A card that expires during the probe's lifetime would fail tokenization on every row, so
         * the expiry tracks the clock instead of being pinned to a year that eventually arrives.
         */
        fun tokenExpiryYear(): String = (LocalDate.now().year + 3).toString()

        /**
         * Customer-block values. Distinctive enough to be searched for in the transaction payload.
         */
        const val FIRST = "Custfirstname"
        const val LAST = "Custlastname"
        const val STREET = "1 Custstreet Road"
        const val STREET2 = "Custbuilding Annex"
        const val RECIPIENT = "Custrecipient Name"
        const val CITY = "Custcityville"
        const val ZIP = "75002"
        const val EMAIL = "customer.fields@example.com"
        const val PHONE = "+33102030405"

        /**
         * Shipping-block values, deliberately DISTINCT from the customer ones. The echo check
         * searches values, not key/value pairs, so reusing a customer value in the `shipto_` block
         * would let one occurrence satisfy both keys and make the combined probe unattributable.
         */
        const val SHIP_FIRST = "Shipfirstname"
        const val SHIP_LAST = "Shiplastname"
        const val SHIP_STREET = "2 Shipstreet Avenue"
        const val SHIP_STREET2 = "Shipbuilding Annex"
        const val SHIP_RECIPIENT = "Shiprecipient Name"
        const val SHIP_CITY = "Shipcityville"
        const val SHIP_ZIP = "69003"

        val PROBES: List<Probe> = listOf(
            Probe(
                // All eleven properties, `state` included — a full customer means full.
                "customer-full",
                CustomerInfo(
                    firstName = FIRST, lastName = LAST, streetAddress = STREET,
                    streetAddress2 = STREET2, recipientInfo = RECIPIENT, city = CITY,
                    state = "IDF", zipCode = ZIP, country = "FR", email = EMAIL, phone = PHONE,
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

            // --- country: one rejection is not a rule. Three shapes of "not plain alpha-2", so the
            // --- client-side validation 16-3 must write is measured rather than extrapolated.
            // --- `FRANCE` is retained as the known-rejection control for the run.
            Probe("country-invalid-name", CustomerInfo(country = "FRANCE"), null),
            Probe("country-alpha3", CustomerInfo(country = "FRA"), null),
            Probe("country-lowercase", CustomerInfo(country = "fr"), null),

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

            // --- empty strings. The demo form is empty by default, so a focused-then-cleared field
            // --- will emit "" rather than null. The SDK omits nulls but sends empties as-is, so
            // --- these two target exactly the fields the gateway is known to validate.
            Probe("empty-country", CustomerInfo(country = ""), null),
            Probe("empty-email", CustomerInfo(email = ""), null),

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
                // The full personal-info subset the shipping block can carry: nine keys, `state`
                // included — `email`/`phone` are customer-only by client-side design.
                "shipping-only",
                null,
                CustomerInfo(
                    firstName = SHIP_FIRST, lastName = SHIP_LAST, streetAddress = SHIP_STREET,
                    streetAddress2 = SHIP_STREET2, recipientInfo = SHIP_RECIPIENT, city = SHIP_CITY,
                    state = "ARA", zipCode = SHIP_ZIP, country = "FR",
                ),
            ),
            Probe(
                "shipping-partial",
                null,
                CustomerInfo(streetAddress = SHIP_STREET, city = SHIP_CITY),
            ),
            Probe(
                "shipping-no-country",
                null,
                CustomerInfo(streetAddress = SHIP_STREET, city = SHIP_CITY, zipCode = SHIP_ZIP),
            ),
            // Does the country rule apply to the shipping block too, or only to the customer?
            Probe("shipping-country-invalid", null, CustomerInfo(country = "FRANCE")),
            Probe(
                // Distinct values per block, so an echo can be attributed to the block that sent it.
                "customer-and-shipping",
                CustomerInfo(
                    firstName = FIRST, lastName = LAST, email = EMAIL, phone = PHONE,
                    streetAddress = STREET, city = CITY, zipCode = ZIP, country = "FR",
                ),
                CustomerInfo(
                    firstName = SHIP_FIRST, lastName = SHIP_LAST, streetAddress = SHIP_STREET,
                    city = SHIP_CITY, zipCode = SHIP_ZIP, country = "FR",
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

        /**
         * Decodes `\uXXXX` while leaving an ESCAPED backslash alone: in `\\u0041` the `\\` is a
         * literal backslash, so the `u0041` is data, not an escape. The other JSON escapes are
         * decoded too, so a value containing a quote or a solidus can still be matched.
         */
        private val JSON_ESCAPE = Regex("""\\(?:u([0-9a-fA-F]{4})|(["\\/bfnrt]))""")

        fun decodeUnicodeEscapes(text: String): String =
            JSON_ESCAPE.replace(text) { match ->
                val hex = match.groupValues[1]
                if (hex.isNotEmpty()) {
                    hex.toInt(16).toChar().toString()
                } else {
                    when (match.groupValues[2]) {
                        "b" -> "\b"
                        "f" -> "\u000C"
                        "n" -> "\n"
                        "r" -> "\r"
                        "t" -> "\t"
                        else -> match.groupValues[2] // ", \ and / stand for themselves
                    }
                }
            }

        fun sha1Hex(input: String): String =
            MessageDigest.getInstance("SHA-1")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
