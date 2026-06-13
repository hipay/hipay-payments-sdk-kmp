package com.hipay.card

import com.hipay.card.model.CardToken
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.golden.GOLDEN_TOKEN_CREATE_RESPONSE
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardTokenizerTest {

    private val config = HiPayConfig("user", "pass", Environment.STAGE)

    private fun goldenVaultEngine() = MockEngine {
        respond(
            GOLDEN_TOKEN_CREATE_RESPONSE,
            HttpStatusCode.Created,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    // --- Success + full mapping (AC #1, #6) ---

    @Test
    fun successMapsTheFullGoldenResponseToCardToken() = runTest {
        val token: CardToken = CardTokenizer(config, goldenVaultEngine())
            .generateToken("4111111111111111", "12", "2026", "Test", "123", multiUse = false)

        assertEquals("f0e1d2c3b4a5968778695a4b3c2d1e0f".repeat(2), token.token)
        assertEquals("VISA", token.brand)
        assertEquals("411111xxxxxx1111", token.pan)
        assertEquals("Test", token.cardHolder)
        assertEquals("12", token.cardExpiryMonth)   // STRING — real contract
        assertEquals("2026", token.cardExpiryYear)  // STRING — real contract
        assertEquals("CONOTOXIA SP. Z O.O", token.issuer)
        assertEquals("PL", token.country)
        assertEquals(null, token.domesticNetwork)
        assertEquals("DEBIT", token.cardType)
        assertEquals("CLASSIC", token.cardCategory)
        assertEquals(false, token.forbiddenIssuerCountry)
        assertEquals(0, token.multiUse)
        assertEquals("00000000-0000-4000-8000-000000000001", token.cardId)
    }

    // --- Request correctness (AC #1) ---

    @Test
    fun requestHitsTokenCreateWithExactFieldsAndMultiUseMapping() = runTest {
        val engine = MockEngine { request ->
            assertEquals("stage-secure2-vault.hipay-tpp.com", request.url.host)
            assertEquals("/rest/v2/token/create", request.url.encodedPath)
            assertEquals(
                "card_number=4111111111111111&card_expiry_month=12&card_expiry_year=2030" +
                    "&card_holder=Test&cvc=123&multi_use=1",
                request.body.toByteArray().decodeToString(),
            )
            respond(
                GOLDEN_TOKEN_CREATE_RESPONSE,
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        CardTokenizer(config, engine)
            .generateToken("4111111111111111", "12", "2030", "Test", "123", multiUse = true)
    }

    // --- API error (AC #6) ---

    @Test
    fun apiErrorMapsToApiCodeWithFields() = runTest {
        val engine = MockEngine {
            respond(
                """{"code":"409","message":"Luhn check failed","description":"invalid"}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val ex = assertFailsWith<HiPayException> {
            CardTokenizer(config, engine)
                .generateToken("4111111111111111", "12", "2030", "Test", "123", multiUse = false)
        }
        assertEquals(HiPayErrorCode.API, ex.code)
        assertEquals(409, ex.apiCode)
    }

    // --- Booby-trap: backend echoes card data in its error (AC #4) ---

    @Test
    fun boobyTrappedErrorNeverLeaksCardDataThroughMessageOrCauseChain() = runTest {
        val pan = "4111111111111111"
        val engine = MockEngine {
            respond(
                """{"code":"409","message":"Luhn failed for $pan cvc 123",""" +
                    """"description":"card_number=$pan&card_holder=Test"}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val ex = assertFailsWith<HiPayException> {
            CardTokenizer(config, engine)
                .generateToken(pan, "12", "2030", "Test", "123", multiUse = false)
        }
        // message and toString: SDK-synthesized, never backend text
        assertFalse(ex.message!!.contains(pan))
        assertFalse(ex.toString().contains(pan))
        // entire cause chain free of card data
        var cause: Throwable? = ex.cause
        while (cause != null) {
            assertFalse(cause.message.orEmpty().contains(pan))
            assertFalse(cause.toString().contains(pan))
            cause = cause.cause
        }
        // backend text intentionally available in the dedicated property only
        assertTrue(ex.apiMessage!!.contains("Luhn failed"))
        // ...but PAN-like runs are redacted from it too (defense in depth)
        assertFalse(ex.apiMessage!!.contains(pan))
        assertFalse(ex.apiDescription!!.contains(pan))
    }

    // A 2xx with an empty token is a backend contract violation, not a token.
    @Test
    fun emptyTokenInSuccessBodyMapsToServer() = runTest {
        val engine = MockEngine {
            respond("""{"token":""}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val ex = assertFailsWith<HiPayException> {
            CardTokenizer(config, engine)
                .generateToken("4111111111111111", "12", "2030", "Test", "123", multiUse = false)
        }
        assertEquals(HiPayErrorCode.SERVER, ex.code)
    }

    // --- Validation short-circuit: no network call (AC from 2.3 wiring) ---

    @Test
    fun invalidInputShortCircuitsBeforeAnyNetworkCall() = runTest {
        val engine = goldenVaultEngine()
        val ex = assertFailsWith<HiPayException> {
            CardTokenizer(config, engine)
                .generateToken("4111111111111112", "12", "2030", "Test", "123", multiUse = false)
        }
        assertEquals(HiPayErrorCode.VALIDATION, ex.code)
        assertTrue(engine.requestHistory.isEmpty(), "validation failure must not reach the network")
    }

    // --- Malformed success body (defensive) ---

    @Test
    fun malformedSuccessBodyMapsToServerWithoutEchoingBody() = runTest {
        val secret = "weird-body-with-card_number=4111111111111111"
        val engine = MockEngine { respond(secret, HttpStatusCode.OK) }
        val ex = assertFailsWith<HiPayException> {
            CardTokenizer(config, engine)
                .generateToken("4111111111111111", "12", "2030", "Test", "123", multiUse = false)
        }
        assertEquals(HiPayErrorCode.SERVER, ex.code)
        assertFalse(ex.message!!.contains("4111111111111111"))
    }

    // --- toString masking (AC #3) ---

    @Test
    fun cardTokenToStringExposesOnlyTokenBrandAndMaskedPan() = runTest {
        val token = CardTokenizer(config, goldenVaultEngine())
            .generateToken("4111111111111111", "12", "2026", "Test", "123", multiUse = false)
        val s = token.toString()
        assertTrue(s.contains("411111xxxxxx1111")) // backend-masked pan: allowed
        assertFalse(s.contains("CONOTOXIA"))       // no full field dump
        assertFalse(s.contains("Test"))
    }
}
