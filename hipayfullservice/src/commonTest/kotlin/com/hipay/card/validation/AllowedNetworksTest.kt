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
    fun unresolvedNetworkIsAuthorized() {
        // A not-yet-resolved network must not be flagged NOT_AUTHORIZED while
        // the merchant restricts the set (D2 review fix).
        val allowed = listOf(CardNetwork.VISA, CardNetwork.MASTERCARD)
        assertEquals(true, AllowedNetworks.isAuthorized(CardNetwork.UNKNOWN, allowed))
        assertEquals(ValidationReason.VALID, AllowedNetworks.reason(CardNetwork.UNKNOWN, allowed))
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
