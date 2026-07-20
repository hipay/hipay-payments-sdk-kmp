package com.hipay.card.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardNetworksTest {

    // --- Detection (local BIN prefixes; backend detection deferred) ---

    @Test
    fun detectsNetworksFromPrefixes() {
        assertEquals(CardNetwork.VISA, CardNetworks.detect("4111111111111111"))
        assertEquals(CardNetwork.VISA, CardNetworks.detect("4"))
        assertEquals(CardNetwork.MASTERCARD, CardNetworks.detect("5496198584584769"))
        assertEquals(CardNetwork.MASTERCARD, CardNetworks.detect("2221000000000009"))
        assertEquals(CardNetwork.MASTERCARD, CardNetworks.detect("2720"))
        assertEquals(CardNetwork.AMEX, CardNetworks.detect("34"))
        assertEquals(CardNetwork.AMEX, CardNetworks.detect("371449635398431"))
        assertEquals(CardNetwork.MAESTRO, CardNetworks.detect("5018000000000009"))
        assertEquals(CardNetwork.MAESTRO, CardNetworks.detect("6759000000000000"))
        // BCMC (Bancontact) wins over the Maestro 56-69 range on prefix 6703
        assertEquals(CardNetwork.BCMC, CardNetworks.detect("6703"))
        assertEquals(CardNetwork.BCMC, CardNetworks.detect("67030000000000000"))
        assertEquals(CardNetwork.UNKNOWN, CardNetworks.detect(""))
        assertEquals(CardNetwork.UNKNOWN, CardNetworks.detect("1234"))
    }

    @Test
    fun mastercardRangeBoundaries() {
        assertEquals(CardNetwork.UNKNOWN, CardNetworks.detect("2220990000000000"))
        assertEquals(CardNetwork.MASTERCARD, CardNetworks.detect("2221"))
        assertEquals(CardNetwork.MASTERCARD, CardNetworks.detect("2720999999999999"))
        // 2721 is outside the Mastercard 2-series
        assertEquals(CardNetwork.UNKNOWN, CardNetworks.detect("2721000000000000"))
    }

    // --- Completion lengths (auto-advance trigger) ---

    @Test
    fun numberCompletionDependsOnNetwork() {
        assertTrue(CardNetworks.isNumberComplete("4111111111111111"))      // visa 16
        assertFalse(CardNetworks.isNumberComplete("411111111111111"))     // visa 15
        assertTrue(CardNetworks.isNumberComplete("371449635398431"))      // amex 15
        assertFalse(CardNetworks.isNumberComplete("37144963539843"))      // amex 14
        assertTrue(CardNetworks.isNumberComplete("5496198584584769"))     // mc 16
        // maestro is variable length: complete only at max (19)
        assertFalse(CardNetworks.isNumberComplete("501800000000000"))
        assertTrue(CardNetworks.isNumberComplete("5018000000000000000"))
    }

    // --- CVC rules ---

    @Test
    fun bcmcSeventeenDigitsCompleteAndCbRulesDefined() {
        // Bancontact PANs run to 17 digits
        assertFalse(CardNetworks.isNumberComplete("6703000000000000"))
        assertTrue(CardNetworks.isNumberComplete("67030000000000000"))
        // CB is co-badged Visa/MC — locally undetectable (backend detection,
        // next epic) but its rules are defined: 3-digit CVC required, 16 digits
        assertEquals(3, CardNetworks.cvcLength(CardNetwork.CB))
        assertTrue(CardNetworks.isCvcRequired(CardNetwork.CB))
    }

    @Test
    fun cvcLengthIsFourForAmexThreeOtherwise() {
        assertEquals(4, CardNetworks.cvcLength(CardNetwork.AMEX))
        assertEquals(3, CardNetworks.cvcLength(CardNetwork.VISA))
        assertEquals(3, CardNetworks.cvcLength(CardNetwork.MASTERCARD))
        assertEquals(3, CardNetworks.cvcLength(CardNetwork.UNKNOWN))
    }

    @Test
    fun cvcPolicyIsCoBrandAwareForMaestro() {
        // Story 11.5: mono Maestro requires a CVC; co-branded Maestro does not.
        // Single-arg = lone network = mono → required.
        assertTrue(CardNetworks.isCvcRequired(CardNetwork.MAESTRO))
        assertTrue(CardNetworks.isCvcRequired(CardNetwork.MAESTRO, listOf(CardNetwork.MAESTRO)))
        // Co-branded (≥2 offered) → not required.
        assertFalse(CardNetworks.isCvcRequired(CardNetwork.MAESTRO, listOf(CardNetwork.MAESTRO, CardNetwork.CB)))
        assertFalse(CardNetworks.isCvcRequired(CardNetwork.MAESTRO, listOf(CardNetwork.MAESTRO, CardNetwork.VISA)))
        // Bancontact never requires a CVV (mono or co-branded).
        assertFalse(CardNetworks.isCvcRequired(CardNetwork.BCMC))
        assertFalse(CardNetworks.isCvcRequired(CardNetwork.BCMC, listOf(CardNetwork.BCMC, CardNetwork.MAESTRO)))
        // Every other network always requires a CVV, regardless of the offered set.
        assertTrue(CardNetworks.isCvcRequired(CardNetwork.VISA))
        assertTrue(CardNetworks.isCvcRequired(CardNetwork.AMEX))
        assertTrue(CardNetworks.isCvcRequired(CardNetwork.VISA, listOf(CardNetwork.VISA, CardNetwork.MAESTRO)))
        assertTrue(CardNetworks.isCvcRequired(CardNetwork.UNKNOWN))
    }

    // --- Formatting (network-dependent grouping) ---

    @Test
    fun formatsVisaMastercardInGroupsOfFour() {
        assertEquals("4111 1111 1111 1111", CardNetworks.format("4111111111111111"))
        assertEquals("5496 1985 8458 4769", CardNetworks.format("5496198584584769"))
        assertEquals("4111 11", CardNetworks.format("411111"))
    }

    @Test
    fun formatsAmexFourSixFive() {
        assertEquals("3714 496353 98431", CardNetworks.format("371449635398431"))
        assertEquals("3714 4963", CardNetworks.format("37144963"))
    }

    @Test
    fun formatStripsNothingAndIgnoresNonDigitsInput() {
        assertEquals("4111 1111", CardNetworks.format("4111 1111"))
        assertEquals("", CardNetworks.format(""))
    }

    // --- Prefix viability (immediate invalid-pattern detection) ---

    @Test
    fun viablePrefixesForEverySupportedNetwork() {
        assertTrue(CardNetworks.isPrefixViable(""))
        assertTrue(CardNetworks.isPrefixViable("3"))    // could become 34/37 (Amex)
        assertTrue(CardNetworks.isPrefixViable("34"))
        assertTrue(CardNetworks.isPrefixViable("4"))    // Visa
        assertTrue(CardNetworks.isPrefixViable("5"))    // 50/51-55/56-69
        assertTrue(CardNetworks.isPrefixViable("6"))    // 56-69 / 6703
        assertTrue(CardNetworks.isPrefixViable("2"))    // could become 2221-2720
        assertTrue(CardNetworks.isPrefixViable("22"))
        assertTrue(CardNetworks.isPrefixViable("2221"))
        assertTrue(CardNetworks.isPrefixViable("2720"))
        assertTrue(CardNetworks.isPrefixViable("6703"))
        assertTrue(CardNetworks.isPrefixViable("4111111111111111"))
    }

    @Test
    fun unviablePrefixesAreDetectedAtTheFirstWrongDigit() {
        assertFalse(CardNetworks.isPrefixViable("0"))
        assertFalse(CardNetworks.isPrefixViable("1"))
        assertFalse(CardNetworks.isPrefixViable("7"))
        assertFalse(CardNetworks.isPrefixViable("8"))
        assertFalse(CardNetworks.isPrefixViable("9"))
        assertFalse(CardNetworks.isPrefixViable("30"))   // neither 34 nor 37
        assertFalse(CardNetworks.isPrefixViable("35"))
        assertFalse(CardNetworks.isPrefixViable("21"))   // below 2221
        assertFalse(CardNetworks.isPrefixViable("2220"))
        assertFalse(CardNetworks.isPrefixViable("2721")) // above 2720
        assertFalse(CardNetworks.isPrefixViable("28"))
    }

    // --- Possible backend resolutions per locally-detected network (co-brand model) ---

    @Test
    fun amexHasNoDomesticCoBrand() {
        assertEquals(setOf(CardNetwork.AMEX), CardNetworks.possibleResolutions(CardNetwork.AMEX))
    }

    @Test
    fun internationalRailsCanCarryDomesticCoBrands() {
        assertTrue(CardNetwork.CB in CardNetworks.possibleResolutions(CardNetwork.VISA))
        assertTrue(CardNetwork.BCMC in CardNetworks.possibleResolutions(CardNetwork.VISA))
        assertTrue(CardNetwork.CB in CardNetworks.possibleResolutions(CardNetwork.MASTERCARD))
        // A Visa BIN can never be Amex or Mastercard (disjoint international ranges).
        assertTrue(CardNetwork.AMEX !in CardNetworks.possibleResolutions(CardNetwork.VISA))
        assertTrue(CardNetwork.MASTERCARD !in CardNetworks.possibleResolutions(CardNetwork.VISA))
    }

    @Test
    fun unknownCouldStillBecomeAnything() {
        assertEquals(CardNetwork.entries.toSet(), CardNetworks.possibleResolutions(CardNetwork.UNKNOWN))
    }

    // Review fix: a non-ASCII Unicode digit must NOT count as a card digit
    // (consistent with CardValidators). Arabic-Indic "٤" is not "4".
    @Test
    fun unicodeDigitsAreNotTreatedAsCardDigits() {
        assertEquals(CardNetwork.UNKNOWN, CardNetworks.detect("٤")) // not VISA
        assertEquals("", CardNetworks.format("٤١١١"))               // dropped, not grouped
        assertFalse(CardNetworks.isNumberComplete("٤".repeat(16)))
    }
}
