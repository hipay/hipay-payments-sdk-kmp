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

    // --- Local (pre-backend) unauthorized detection — refinement 2026-07-20 ---

    @Test
    fun locallyUnauthorizedOnlyForUnambiguousMismatch() {
        // UNAMBIGUOUS: Amex (34/37) carries no domestic co-brand, so with only CB allowed
        // no backend resolution could rescue it → reject locally, immediately.
        assertEquals(true, AllowedNetworks.isLocallyUnauthorized(CardNetwork.AMEX, listOf(CardNetwork.CB)))
        // UNAMBIGUOUS: a Visa BIN can never become Mastercard (disjoint ranges).
        assertEquals(true, AllowedNetworks.isLocallyUnauthorized(CardNetwork.VISA, listOf(CardNetwork.MASTERCARD)))
        // UNAMBIGUOUS: a Visa BIN can never be Amex.
        assertEquals(true, AllowedNetworks.isLocallyUnauthorized(CardNetwork.VISA, listOf(CardNetwork.AMEX)))
    }

    @Test
    fun locallyAuthorizedWhenAnAllowedCoBrandCouldRideTheBin() {
        // AMBIGUOUS (the 4111 / CB-only case): CB can ride on a Visa BIN, so a locally
        // detected Visa might resolve to CB → must NOT reject locally, wait for the backend.
        assertEquals(false, AllowedNetworks.isLocallyUnauthorized(CardNetwork.VISA, listOf(CardNetwork.CB)))
        assertEquals(false, AllowedNetworks.isLocallyUnauthorized(CardNetwork.MASTERCARD, listOf(CardNetwork.CB)))
        // BCMC can ride on a Maestro BIN.
        assertEquals(false, AllowedNetworks.isLocallyUnauthorized(CardNetwork.MAESTRO, listOf(CardNetwork.BCMC)))
    }

    @Test
    fun locallyAuthorizedWhenTheDetectedNetworkIsItselfAllowed() {
        assertEquals(false, AllowedNetworks.isLocallyUnauthorized(CardNetwork.VISA, listOf(CardNetwork.VISA)))
    }

    @Test
    fun emptyAllowedOrUnknownIsNeverLocallyUnauthorized() {
        assertEquals(false, AllowedNetworks.isLocallyUnauthorized(CardNetwork.AMEX, emptyList()))
        assertEquals(false, AllowedNetworks.isLocallyUnauthorized(CardNetwork.UNKNOWN, listOf(CardNetwork.CB)))
    }
}
