package com.hipay.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoldenParityTest {

    /**
     * AC #4 — token/create request parity. The field map is built exactly the
     * way CardTokenizer will build it (story 2.4 rewires the real serializer
     * into this assertion).
     */
    @Test
    fun tokenCreateRequestMatchesGolden() {
        val fields = linkedMapOf(
            "card_number" to "4111111111111111",
            "card_expiry_month" to "12",
            "card_expiry_year" to "2026",
            "card_holder" to "Test",
            "cvc" to "123",
            "multi_use" to "0",
        )
        val actual = JsonObject(fields.mapValues { JsonPrimitive(it.value) })
        assertJsonParity(GOLDEN_TOKEN_CREATE_REQUEST, actual)
    }

    @Test
    fun orderRequestGoldenCarriesRealApiVerdicts() {
        val golden = Json.parseToJsonElement(GOLDEN_ORDER_REQUEST).jsonObject
        // operation is a STRING "Sale"/"Authorization" — real-API verdict
        // (a 400 "non alphabetic characters" rejects integers).
        assertEquals("Sale", golden.getValue("operation").jsonPrimitive.content)
        assertTrue(golden.getValue("operation").jsonPrimitive.isString)
        // card fields ride the same order request (FR12)
        assertTrue("cardtoken" in golden)
        assertEquals("7", golden.getValue("eci").jsonPrimitive.content)
    }

    /**
     * Guards the camelCase truth observed on real stage traffic (2026-06-12):
     * api-contracts.md transcribed snake_case, the REAL API and the legacy
     * mapper (HPFTransactionMapper.m:61) use camelCase. Nobody "fixes" the
     * models back to snake_case without this test screaming.
     */
    @Test
    fun orderResponseGoldenKeepsCamelCaseContract() {
        val golden = Json.parseToJsonElement(GOLDEN_ORDER_RESPONSE).jsonObject
        assertTrue("transactionReference" in golden)
        assertTrue("forwardUrl" in golden)
        assertTrue("paymentProduct" in golden)
        assertTrue("threeDSecure" in golden)
        assertEquals("completed", golden.getValue("state").jsonPrimitive.content)
        // status and amounts are STRINGS on the wire
        assertTrue(golden.getValue("status").jsonPrimitive.isString)
        assertEquals("118", golden.getValue("status").jsonPrimitive.content)
        assertTrue(golden.getValue("authorizedAmount").jsonPrimitive.isString)
    }

    @Test
    fun tokenResponseGoldenKeepsStringExpiryContract() {
        val golden = Json.parseToJsonElement(GOLDEN_TOKEN_CREATE_RESPONSE).jsonObject
        // api-contracts.md said int — the real vault answers strings.
        assertTrue(golden.getValue("card_expiry_month").jsonPrimitive.isString)
        assertTrue(golden.getValue("card_expiry_year").jsonPrimitive.isString)
        assertEquals("411111xxxxxx1111", golden.getValue("pan").jsonPrimitive.content)
    }

    // --- The harness itself must catch drift (negative tests) ---

    @Test
    fun parityFailsOnMissingKeyWithPath() {
        val actual = JsonObject(mapOf("card_number" to JsonPrimitive("4111111111111111")))
        val failure = assertFailsWith<AssertionError> {
            assertJsonParity(GOLDEN_TOKEN_CREATE_REQUEST, actual)
        }
        assertTrue(failure.message!!.contains("missing key"))
    }

    @Test
    fun parityFailsOnTypeMismatchWithPath() {
        val golden = """{"eci": "7"}"""
        val actual = JsonObject(mapOf("eci" to JsonPrimitive(7)))
        val failure = assertFailsWith<AssertionError> { assertJsonParity(golden, actual) }
        assertTrue(failure.message!!.contains("$.eci"))
        assertTrue(failure.message!!.contains("type mismatch"))
    }

    @Test
    fun parityFailsOnValueMismatchWithPreciseNestedPath() {
        val golden = """{"order": {"amount": "1.00"}}"""
        val actual = JsonObject(
            mapOf("order" to JsonObject(mapOf("amount" to JsonPrimitive("2.00")))),
        )
        val failure = assertFailsWith<AssertionError> { assertJsonParity(golden, actual) }
        assertTrue(failure.message!!.contains("$.order.amount"))
    }
}
