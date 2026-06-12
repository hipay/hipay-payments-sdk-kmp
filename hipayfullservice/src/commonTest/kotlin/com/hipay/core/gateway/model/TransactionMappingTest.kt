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
}
