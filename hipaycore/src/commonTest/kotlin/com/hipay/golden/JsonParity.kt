package com.hipay.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parity harness (architecture D10/NFR1): compares parsed [JsonElement]s,
 * never raw strings — key order and whitespace are irrelevant, key names,
 * value types and values are not. Fails with the JSON-pointer-ish path of the
 * first mismatch so contract drift is immediately locatable.
 *
 * Stories 2.4/3.1 plug their serializer output into this harness against the
 * committed golden files.
 */
internal fun assertJsonParity(goldenJson: String, actual: JsonElement) {
    val golden = Json.parseToJsonElement(goldenJson)
    val mismatch = firstMismatch(golden, actual, path = "$")
    if (mismatch != null) {
        throw AssertionError("JSON parity violation: $mismatch")
    }
}

private fun firstMismatch(expected: JsonElement, actual: JsonElement, path: String): String? {
    return when {
        expected is JsonNull && actual is JsonNull -> null

        expected is JsonPrimitive && actual is JsonPrimitive ->
            if (expected.isString != actual.isString) {
                "$path: type mismatch (expected ${typeName(expected)}, got ${typeName(actual)})"
            } else if (expected.content != actual.content) {
                "$path: expected ${expected.content}, got ${actual.content}"
            } else {
                null
            }

        expected is JsonObject && actual is JsonObject -> {
            val missing = expected.keys - actual.keys
            val unexpected = actual.keys - expected.keys
            when {
                missing.isNotEmpty() -> "$path: missing key(s) $missing"
                unexpected.isNotEmpty() -> "$path: unexpected key(s) $unexpected"
                else -> expected.entries.firstNotNullOfOrNull { (key, value) ->
                    firstMismatch(value, actual.getValue(key), "$path.$key")
                }
            }
        }

        expected is JsonArray && actual is JsonArray ->
            if (expected.size != actual.size) {
                "$path: array size mismatch (expected ${expected.size}, got ${actual.size})"
            } else {
                expected.indices.firstNotNullOfOrNull { i ->
                    firstMismatch(expected[i], actual[i], "$path[$i]")
                }
            }

        else -> "$path: kind mismatch (expected ${typeName(expected)}, got ${typeName(actual)})"
    }
}

private fun typeName(element: JsonElement): String = when (element) {
    is JsonNull -> "null"
    is JsonPrimitive -> if (element.isString) "string" else "number/boolean"
    is JsonObject -> "object"
    is JsonArray -> "array"
}
