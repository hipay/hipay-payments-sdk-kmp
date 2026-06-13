package com.hipay.card.validation

import kotlin.test.Test
import kotlin.test.assertEquals

class AllowedNetworksTest {

    @Test
    fun emptyAllowedListAllowsAllResolved() {
        val resolved = listOf(CardNetwork.CB, CardNetwork.VISA)
        assertEquals(resolved, AllowedNetworks.offered(resolved, emptyList()))
        assertEquals(true, AllowedNetworks.isAuthorized(CardNetwork.VISA, emptyList()))
        assertEquals(ValidationReason.VALID, AllowedNetworks.reason(CardNetwork.CB, emptyList()))
    }

    @Test
    fun offeredIsResolvedIntersectAllowed() {
        // Co-brand resolved [CB, VISA]; merchant accepts only CB → CB offered, default
        assertEquals(
            listOf(CardNetwork.CB),
            AllowedNetworks.offered(listOf(CardNetwork.CB, CardNetwork.VISA), listOf(CardNetwork.CB)),
        )
    }

    @Test
    fun networkOutsideAllowedIsNotAuthorized() {
        val allowed = listOf(CardNetwork.VISA, CardNetwork.MASTERCARD)
        assertEquals(false, AllowedNetworks.isAuthorized(CardNetwork.AMEX, allowed))
        assertEquals(
            ValidationReason.NETWORK_NOT_AUTHORIZED,
            AllowedNetworks.reason(CardNetwork.AMEX, allowed),
        )
        assertEquals(ValidationReason.VALID, AllowedNetworks.reason(CardNetwork.VISA, allowed))
    }
}
