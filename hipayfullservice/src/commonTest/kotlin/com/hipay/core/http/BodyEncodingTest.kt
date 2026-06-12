package com.hipay.core.http

import kotlin.test.Test
import kotlin.test.assertEquals

class BodyEncodingTest {

    @Test
    fun encodesSimpleFieldsAsFormUrlEncoded() {
        assertEquals(
            "orderid=TEST-1&amount=10.00",
            encodeFormBody(linkedMapOf("orderid" to "TEST-1", "amount" to "10.00")),
        )
    }

    @Test
    fun escapesReservedCharactersAndSpaces() {
        val encoded = encodeFormBody(
            linkedMapOf(
                "description" to "Café & thé = 100%",
                "accept_url" to "hipaydemo://hipay-fullservice/gateway/orders/1/accept",
            ),
        )
        assertEquals(
            "description=Caf%C3%A9+%26+th%C3%A9+%3D+100%25" +
                "&accept_url=hipaydemo%3A%2F%2Fhipay-fullservice%2Fgateway%2Forders%2F1%2Faccept",
            encoded,
        )
    }
}
