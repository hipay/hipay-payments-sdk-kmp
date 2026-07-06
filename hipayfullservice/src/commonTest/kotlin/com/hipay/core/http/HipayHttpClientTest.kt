package com.hipay.core.http

import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HipayHttpClientTest {

    private val config = HiPayConfig("user", "pass", Environment.STAGE)

    // --- Auth & headers (AC #2) ---

    @Test
    fun everyRequestCarriesBasicAuthAndAcceptJson() = runTest {
        val engine = MockEngine { request ->
            // base64("user:pass") = dXNlcjpwYXNz
            assertEquals("Basic dXNlcjpwYXNz", request.headers[HttpHeaders.Authorization])
            assertEquals("application/json", request.headers[HttpHeaders.Accept])
            respond("{}", HttpStatusCode.OK)
        }
        val client = HipayHttpClient(config, engine)
        client.get(config.environment.gatewayV1Url + "transaction/ref1")
        client.postForm(config.environment.secureVaultV2Url + "token/create", mapOf("a" to "b"))
        assertEquals(2, engine.requestHistory.size)
    }

    // --- URL resolution (AC #1) ---

    @Test
    fun stageUrlsAreUsedWhenEnvironmentIsStage() = runTest {
        val engine = MockEngine { request ->
            assertEquals("stage-secure2-vault.hipay-tpp.com", request.url.host)
            assertEquals("/rest/v2/token/create", request.url.encodedPath)
            respond("{}", HttpStatusCode.OK)
        }
        HipayHttpClient(config, engine)
            .postForm(config.environment.secureVaultV2Url + "token/create", mapOf("a" to "b"))
    }

    @Test
    fun productionUrlsAreUsedWhenEnvironmentIsProduction() = runTest {
        val prodConfig = HiPayConfig("user", "pass", Environment.PRODUCTION)
        val engine = MockEngine { request ->
            assertEquals("secure-gateway.hipay-tpp.com", request.url.host)
            respond("{}", HttpStatusCode.OK)
        }
        HipayHttpClient(prodConfig, engine)
            .get(prodConfig.environment.gatewayV1Url + "transaction/ref1")
    }

    // --- Body encoding through the client (AC #4) ---

    @Test
    fun postBodyIsFormUrlEncodedWithContentType() = runTest {
        val engine = MockEngine { request ->
            assertEquals(
                "application/x-www-form-urlencoded; charset=UTF-8",
                request.body.contentType.toString(),
            )
            assertEquals("card_number=4111111111111111&multi_use=0", request.body.toByteArray().decodeToString())
            respond("{}", HttpStatusCode.OK)
        }
        HipayHttpClient(config, engine).postForm(
            config.environment.secureVaultV2Url + "token/create",
            linkedMapOf("card_number" to "4111111111111111", "multi_use" to "0"),
        )
    }

    // --- Error mapping branches (AC #3, #5) ---

    @Test
    fun networkFailureMapsToNetwork() = runTest {
        val engine = MockEngine { throw IOException("connection reset") }
        val ex = assertFailsWith<HiPayException> {
            HipayHttpClient(config, engine).get(config.environment.gatewayV1Url + "transaction/x")
        }
        assertEquals(HiPayErrorCode.NETWORK, ex.code)
        assertNull(ex.httpStatus)
    }

    @Test
    fun http400WithApiErrorBodyMapsToApiWithExtractedFields() = runTest {
        val engine = MockEngine {
            respond(
                """{"code":"409","message":"Luhn check failed","description":"Invalid card number"}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val ex = assertFailsWith<HiPayException> {
            HipayHttpClient(config, engine).postForm(
                config.environment.secureVaultV2Url + "token/create",
                mapOf("a" to "b"),
            )
        }
        assertEquals(HiPayErrorCode.API, ex.code)
        assertEquals(400, ex.httpStatus)
        assertEquals(409, ex.apiCode)
        assertEquals("Luhn check failed", ex.apiMessage)
        assertEquals("Invalid card number", ex.apiDescription)
        // PCI by design: backend text never leaks into message/toString
        assertFalse(ex.message!!.contains("Luhn"))
        assertFalse(ex.toString().contains("Invalid card number"))
    }

    @Test
    fun http400WithoutParseableBodyMapsToClient() = runTest {
        val engine = MockEngine { respond("nope", HttpStatusCode.BadRequest) }
        val ex = assertFailsWith<HiPayException> {
            HipayHttpClient(config, engine).get(config.environment.gatewayV1Url + "transaction/x")
        }
        assertEquals(HiPayErrorCode.CLIENT, ex.code)
        assertEquals(400, ex.httpStatus)
    }

    @Test
    fun http401MapsToClientNotApiWhenBodyHasNoCodeMessage() = runTest {
        val engine = MockEngine { respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized) }
        val ex = assertFailsWith<HiPayException> {
            HipayHttpClient(config, engine).get(config.environment.gatewayV1Url + "transaction/x")
        }
        assertEquals(HiPayErrorCode.CLIENT, ex.code)
        assertEquals(401, ex.httpStatus)
    }

    @Test
    fun http500MapsToServer() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val ex = assertFailsWith<HiPayException> {
            HipayHttpClient(config, engine).get(config.environment.gatewayV1Url + "transaction/x")
        }
        assertEquals(HiPayErrorCode.SERVER, ex.code)
        assertEquals(500, ex.httpStatus)
        assertEquals(null, ex.apiCode)
    }

    @Test
    fun http500WithStructuredBodyStaysServerButExposesApiFields() = runTest {
        // The gateway answers some business rejections with a structured
        // {code,message} body on a 5xx (observed on stage: "Unknown Token" is
        // a 500). Classification stays SERVER, but the payload is preserved
        // so callers can identify the definitive verdict.
        val engine = MockEngine {
            respond(
                """{"code":"3040001","message":"Unknown Token","description":""}""",
                HttpStatusCode.InternalServerError,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val ex = assertFailsWith<HiPayException> {
            HipayHttpClient(config, engine).get(config.environment.gatewayV1Url + "transaction/x")
        }
        assertEquals(HiPayErrorCode.SERVER, ex.code)
        assertEquals(500, ex.httpStatus)
        assertEquals(3040001, ex.apiCode)
        assertEquals("Unknown Token", ex.apiMessage)
        // PCI by design: backend text never leaks into message/toString
        assertFalse(ex.message!!.contains("Unknown Token"))
    }

    @Test
    fun successReturnsRawBody() = runTest {
        val engine = MockEngine {
            respond(
                """{"state":"completed"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val body = HipayHttpClient(config, engine)
            .get(config.environment.gatewayV1Url + "transaction/x")
        assertEquals("""{"state":"completed"}""", body)
    }

    @Test
    fun exceptionMessagesNeverContainAuthOrBody() = runTest {
        val engine = MockEngine { respond("secret-echo card_number=4111", HttpStatusCode.BadRequest) }
        val ex = assertFailsWith<HiPayException> {
            HipayHttpClient(config, engine).postForm(
                config.environment.secureVaultV2Url + "token/create",
                mapOf("card_number" to "4111111111111111"),
            )
        }
        assertFalse(ex.message!!.contains("4111"))
        assertFalse(ex.message!!.contains("dXNlcjpwYXNz"))
        assertTrue(ex.message!!.contains("400"))
    }

    // --- Review patches (story 2.1) ---

    // PCI: a 3xx must NOT be auto-followed — the auth header / form body would
    // otherwise be re-sent to the redirect target (no second request issued).
    @Test
    fun redirectsAreNotFollowed() = runTest {
        val engine = MockEngine {
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://evil.example/steal"),
            )
        }
        val client = HipayHttpClient(config, engine)
        runCatching { client.get(config.environment.gatewayV1Url + "transaction/x") }
        assertEquals(1, engine.requestHistory.size)
    }

    // Real stage error (1000001 Insufficient Privilege) sent as a JSON NUMBER:
    // parsed exactly, no Double round-trip truncation.
    @Test
    fun largeIntegerApiCodeParsedWithoutPrecisionLoss() = runTest {
        val engine = MockEngine {
            respond(
                """{"code":1000001,"message":"Insufficient Privilege"}""",
                HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val ex = assertFailsWith<HiPayException> {
            HipayHttpClient(config, engine).get(config.environment.gatewayV1Url + "transaction/x")
        }
        assertEquals(HiPayErrorCode.API, ex.code)
        assertEquals(1000001, ex.apiCode)
    }

    // A non-integer code is not a structured API error -> CLIENT (no silent
    // truncation of "409.9" to 409 as the previous Double parse did).
    @Test
    fun nonIntegerApiCodeFallsBackToClient() = runTest {
        val engine = MockEngine {
            respond(
                """{"code":"409.9","message":"x"}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val ex = assertFailsWith<HiPayException> {
            HipayHttpClient(config, engine).get(config.environment.gatewayV1Url + "transaction/x")
        }
        assertEquals(HiPayErrorCode.CLIENT, ex.code)
    }

    // The stage WAF 403s on the Accept-Charset header Ktor adds by default;
    // the sendPipeline interceptor strips it. Lock that the outgoing request
    // never carries it (a Ktor upgrade must not silently regress to 403).
    @Test
    fun outgoingRequestsCarryNoAcceptCharsetHeader() = runTest {
        val engine = MockEngine { request ->
            assertNull(request.headers[HttpHeaders.AcceptCharset])
            respond("{}", HttpStatusCode.OK)
        }
        val client = HipayHttpClient(config, engine)
        client.get(config.environment.gatewayV1Url + "transaction/x")
        client.postForm(config.environment.secureVaultV2Url + "token/create", mapOf("a" to "b"))
    }

    // Defense in depth: a backend that echoes a PAN into its error text must
    // not leak it through apiMessage/apiDescription (PCI, NFR2).
    @Test
    fun apiErrorRedactsPanLikeRunsFromBackendText() = runTest {
        val engine = MockEngine {
            respond(
                """{"code":"409","message":"Refused for 4111111111111111",""" +
                    """"description":"pan 4111 1111 1111 1111 cvc 123"}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val ex = assertFailsWith<HiPayException> {
            HipayHttpClient(config, engine).get(config.environment.gatewayV1Url + "transaction/x")
        }
        assertEquals(HiPayErrorCode.API, ex.code)
        assertFalse(ex.apiMessage!!.contains("4111"))
        assertFalse(ex.apiDescription!!.contains("4111"))
        assertTrue(ex.apiMessage!!.contains("[REDACTED]"))
        // short codes (cvc) and the human-readable reason survive
        assertTrue(ex.apiDescription!!.contains("cvc 123"))
    }
}
