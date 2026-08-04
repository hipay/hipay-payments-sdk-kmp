package com.hipay.card.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AllowedNetworksTest {

    // `null` = nothing restricts the card at all.
    @Test
    fun noRestrictionAllowsAllResolved() {
        val resolved = listOf(CardNetwork.CB, CardNetwork.VISA)
        assertEquals(resolved, AllowedNetworks.offered(resolved, null))
        assertEquals(true, AllowedNetworks.isAuthorized(CardNetwork.VISA, null))
        assertEquals(ValidationReason.VALID, AllowedNetworks.reason(CardNetwork.CB, null))
    }

    // An EMPTY list is the opposite of `null`: a restriction that authorizes nothing. Conflating the
    // two is what let a component accept networks its account could not process.
    @Test
    fun anEmptyRestrictionAuthorizesNothing() {
        val resolved = listOf(CardNetwork.CB, CardNetwork.VISA)
        assertEquals(emptyList(), AllowedNetworks.offered(resolved, emptyList()))
        assertEquals(false, AllowedNetworks.isAuthorized(CardNetwork.VISA, emptyList()))
        assertEquals(
            ValidationReason.NETWORK_NOT_AUTHORIZED,
            AllowedNetworks.reason(CardNetwork.CB, emptyList()),
        )
        // And it rejects on local detection alone: no backend resolution can rescue a card on an
        // account that takes none, so there is nothing to wait for.
        assertEquals(true, AllowedNetworks.isLocallyUnauthorized(CardNetwork.AMEX, emptyList()))
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
    fun noRestrictionOrUnknownIsNeverLocallyUnauthorized() {
        assertEquals(false, AllowedNetworks.isLocallyUnauthorized(CardNetwork.AMEX, null))
        assertEquals(false, AllowedNetworks.isLocallyUnauthorized(CardNetwork.UNKNOWN, listOf(CardNetwork.CB)))
    }

    // ---- effectiveAllowed: the account is the ceiling ----

    // With no integrator restriction the account's own set IS the allowed set — which is the whole
    // point: a component built without an allow-list must not accept every network on earth.
    @Test
    fun accountSetAloneBecomesTheAllowedSet() {
        // Compared as a set: nothing downstream depends on the order of the allowed list, so asserting
        // an iteration order would pin a mechanism instead of the behaviour.
        assertEquals(
            setOf(CardNetwork.VISA, CardNetwork.CB),
            AllowedNetworks.effectiveAllowed(setOf(CardNetwork.VISA, CardNetwork.CB), emptyList())?.toSet(),
        )
    }

    // The integrator narrows. Nothing else.
    @Test
    fun integratorRestrictionNarrowsTheAccountSet() {
        assertEquals(
            listOf(CardNetwork.VISA),
            AllowedNetworks.effectiveAllowed(
                account = setOf(CardNetwork.VISA, CardNetwork.MASTERCARD),
                integrator = listOf(CardNetwork.VISA),
            ),
        )
    }

    // The invariant that matters: an integrator cannot authorize what the account cannot process.
    // The gateway would refuse the order anyway, so offering it would only fail late.
    @Test
    fun integratorCannotWidenBeyondTheAccount() {
        assertEquals(
            listOf(CardNetwork.VISA),
            AllowedNetworks.effectiveAllowed(
                account = setOf(CardNetwork.VISA),
                integrator = listOf(CardNetwork.VISA, CardNetwork.AMEX),
            ),
        )
    }

    // Disjoint sets authorize NOTHING. It must not collapse to "empty means allow all" — that
    // conflation is exactly the bug this closes.
    @Test
    fun disjointAccountAndIntegratorAuthorizeNothing() {
        val effective = AllowedNetworks.effectiveAllowed(
            account = setOf(CardNetwork.VISA),
            integrator = listOf(CardNetwork.AMEX),
        )
        assertEquals(emptyList(), effective)
        // An empty EFFECTIVE list is a restriction, not an absence of one — the whole point.
        assertEquals(false, AllowedNetworks.isAuthorized(CardNetwork.VISA, effective))
        assertEquals(emptyList(), AllowedNetworks.offered(listOf(CardNetwork.VISA), effective))
    }

    // An account contracted for no card at all refuses every card.
    @Test
    fun emptyAccountSetAuthorizesNothing() {
        assertEquals(emptyList(), AllowedNetworks.effectiveAllowed(emptySet(), emptyList()))
        assertEquals(
            emptyList(),
            AllowedNetworks.offered(listOf(CardNetwork.VISA), AllowedNetworks.effectiveAllowed(emptySet(), emptyList())),
        )
    }

    // A ceiling that is not known yet — or whose query failed — leaves the integrator list
    // untouched, so entry keeps working exactly as before instead of being blocked.
    @Test
    fun unknownAccountLeavesTheIntegratorListUntouched() {
        assertNull(AllowedNetworks.effectiveAllowed(null, emptyList()))
        assertEquals(
            listOf(CardNetwork.VISA),
            AllowedNetworks.effectiveAllowed(null, listOf(CardNetwork.VISA)),
        )
        assertEquals(true, AllowedNetworks.isAuthorized(CardNetwork.AMEX, AllowedNetworks.effectiveAllowed(null, emptyList())))
        assertEquals(
            listOf(CardNetwork.VISA, CardNetwork.CB),
            AllowedNetworks.offered(listOf(CardNetwork.VISA, CardNetwork.CB), AllowedNetworks.effectiveAllowed(null, emptyList())),
        )
    }
}
