package com.hipay.core.callback

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallbackUrlParserTest {

    @Test
    fun parsesAllFiveStatuses() {
        val cases = mapOf(
            "accept" to CallbackStatus.ACCEPT,
            "decline" to CallbackStatus.DECLINE,
            "pending" to CallbackStatus.PENDING,
            "exception" to CallbackStatus.EXCEPTION,
            "cancel" to CallbackStatus.CANCEL,
        )
        for ((segment, expected) in cases) {
            val result = CallbackUrlParser.parse(
                "hipaydemo://hipay-fullservice/gateway/orders/ORDER-42/$segment",
            )
            assertEquals(expected, result.status, "segment=$segment")
            assertEquals("ORDER-42", result.orderId)
        }
    }

    @Test
    fun extractsPercentEncodedOrderId() {
        val result = CallbackUrlParser.parse(
            "hipaydemo://hipay-fullservice/gateway/orders/ORDER%2042/accept",
        )
        assertEquals("ORDER 42", result.orderId)
    }

    @Test
    fun preservesQueryParamsDecoded() {
        val result = CallbackUrlParser.parse(
            "hipaydemo://hipay-fullservice/gateway/orders/O1/accept" +
                "?reference=800435540002&state=completed&msg=Captured%20OK",
        )
        assertEquals("800435540002", result.queryParams["reference"])
        assertEquals("completed", result.queryParams["state"])
        assertEquals("Captured OK", result.queryParams["msg"])
    }

    @Test
    fun emptyQueryYieldsEmptyMap() {
        assertTrue(
            CallbackUrlParser.parse("hipaydemo://hipay-fullservice/gateway/orders/O1/cancel")
                .queryParams.isEmpty(),
        )
    }

    @Test
    fun acceptsArbitrarySchemes() {
        assertEquals(
            "O1",
            CallbackUrlParser.parse("myapp123://hipay-fullservice/gateway/orders/O1/accept").orderId,
        )
        assertEquals(
            CallbackStatus.PENDING,
            CallbackUrlParser.parse("com.merchant.app://hipay-fullservice/gateway/orders/O1/pending").status,
        )
    }

    @Test
    fun malformedUrlsThrowTypedValidationError() {
        val malformed = listOf(
            "",
            "not a url at all",
            "hipaydemo://wrong-host/gateway/orders/O1/accept",
            "hipaydemo://hipay-fullservice/gateway/orders/accept",          // missing orderId
            "hipaydemo://hipay-fullservice/something/orders/O1/accept",     // wrong path root
            "hipaydemo://hipay-fullservice/gateway/orders/O1/approved",     // unknown status
        )
        for (url in malformed) {
            val ex = assertFailsWith<HiPayException>("should reject: $url") {
                CallbackUrlParser.parse(url)
            }
            assertEquals(HiPayErrorCode.VALIDATION, ex.code)
            // value-free message: never echoes the URL content
            assertFalse(ex.message!!.contains("O1"))
        }
    }
}
