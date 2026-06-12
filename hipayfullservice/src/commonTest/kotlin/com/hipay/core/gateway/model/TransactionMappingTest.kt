package com.hipay.core.gateway.model

import com.hipay.golden.GOLDEN_ORDER_RESPONSE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TransactionMappingTest {

    @Test
    fun goldenResponseMapsFully() {
        val tx = Transaction.fromJson(GOLDEN_ORDER_RESPONSE)
        assertEquals(TransactionState.COMPLETED, tx.state)
        assertEquals("completed", tx.stateRaw)
        assertEquals("118", tx.status) // numeric status is a STRING on the wire
        assertEquals("800000000001", tx.transactionReference)
        assertEquals("", tx.forwardUrl)
        assertEquals("visa", tx.paymentProduct)
        assertEquals("1.00", tx.authorizedAmount)
        val tds = assertNotNull(tx.threeDSecure)
        assertEquals("Y", tds.authenticationStatus)
        assertEquals("Authentication Successful", tds.authenticationMessage)
        val order = assertNotNull(tx.order)
        assertEquals("TEST-ORDER-1", order.id)
        assertEquals("1.00", order.amount)
        val pm = assertNotNull(tx.paymentMethod)
        assertEquals("411111******1111", pm.pan)
        assertEquals("VISA", pm.brand)
    }

    @Test
    fun allFiveStatesMap() {
        assertEquals(TransactionState.COMPLETED, TransactionState.fromWire("completed"))
        assertEquals(TransactionState.FORWARDING, TransactionState.fromWire("forwarding"))
        assertEquals(TransactionState.PENDING, TransactionState.fromWire("pending"))
        assertEquals(TransactionState.DECLINED, TransactionState.fromWire("declined"))
        assertEquals(TransactionState.ERROR, TransactionState.fromWire("error"))
    }

    @Test
    fun unknownStateFallsBackToError() {
        assertEquals(TransactionState.ERROR, TransactionState.fromWire("surprise"))
        assertEquals(TransactionState.ERROR, TransactionState.fromWire(""))
    }

    @Test
    fun minimalJsonIsTolerated() {
        val tx = Transaction.fromJson("""{"state":"forwarding","forwardUrl":"https://x/3ds"}""")
        assertEquals(TransactionState.FORWARDING, tx.state)
        assertEquals("https://x/3ds", tx.forwardUrl)
        assertEquals(null, tx.transactionReference)
        assertEquals(null, tx.threeDSecure)
    }

    // Real challenge-order shape (stage capture 2026-06-13, sanitized): on a
    // 3DS-challenge order the Gateway sends `"threeDSecure": ""` — an empty
    // STRING where an object is otherwise returned.
    @Test
    fun emptyStringSubObjectsMapToNull() {
        val tx = Transaction.fromJson(
            """
            {"state":"forwarding","reason":"",
             "forwardUrl":"https://stage-secure-gateway.hipay-tpp.com/gateway/forward/abc",
             "transactionReference":"800000000002","status":"140",
             "message":"Authentication requested","threeDSecure":"",
             "paymentMethod":{"pan":"424242******4242","brand":"VISA"},
             "order":{"id":"TEST-ORDER-2","amount":"1.00"}}
            """.trimIndent()
        )
        assertEquals(TransactionState.FORWARDING, tx.state)
        assertEquals("140", tx.status)
        assertEquals(null, tx.threeDSecure)
        assertEquals("", tx.reason)
        assertNotNull(tx.paymentMethod)
        assertNotNull(tx.order)
    }

    // Legacy mappers show `reason` can be a {code, message} object instead of
    // a string — keep only its message.
    @Test
    fun objectReasonMapsToItsMessage() {
        val tx = Transaction.fromJson(
            """{"state":"declined","reason":{"code":"4000001","message":"Refused by bank"},"order":""}"""
        )
        assertEquals(TransactionState.DECLINED, tx.state)
        assertEquals("Refused by bank", tx.reason)
        assertEquals(null, tx.order)
    }
}
