package com.hipay.card.model

import com.hipay.card.validation.CardNetwork
import com.hipay.card.validation.CardNetworks
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CardInfoTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun decode(body: String) = json.decodeFromString(CardInfo.serializer(), body)

    // --- Brand-string mapping ---

    @Test
    fun mapsApiBrandStringsCaseInsensitively() {
        assertEquals(CardNetwork.VISA, CardNetworks.fromApiBrand("VISA"))
        assertEquals(CardNetwork.MASTERCARD, CardNetworks.fromApiBrand("mastercard"))
        assertEquals(CardNetwork.AMEX, CardNetworks.fromApiBrand("american-express"))
        assertEquals(CardNetwork.MAESTRO, CardNetworks.fromApiBrand("MAESTRO"))
        assertEquals(CardNetwork.CB, CardNetworks.fromApiBrand("cb"))
        assertEquals(CardNetwork.BCMC, CardNetworks.fromApiBrand("bcmc"))
        assertEquals(null, CardNetworks.fromApiBrand("unknown"))
        assertEquals(null, CardNetworks.fromApiBrand(null))
    }

    // --- Single network (no co-brand) ---

    @Test
    fun singleNetworkWhenNoDomestic() {
        // real stage shape (4111…): brand VISA, domestic_network null
        val info = decode(
            """{"brand":"VISA","domestic_network":null,"card_type":"DEBIT","country":"PL"}""",
        )
        assertEquals(listOf(CardNetwork.VISA), info.resolvedNetworks())
    }

    // --- Co-branding: domestic wins (selected first) ---

    @Test
    fun bcmcCoBrandPutsDomesticFirst() {
        // real stage shape (6703…449): MAESTRO co-badged BCMC
        val info = decode(
            """{"brand":"MAESTRO","domestic_network":"bcmc","card_type":"DEBIT","country":"BE"}""",
        )
        assertEquals(listOf(CardNetwork.BCMC, CardNetwork.MAESTRO), info.resolvedNetworks())
    }

    @Test
    fun cbCoBrandPutsCbFirst() {
        val info = decode("""{"brand":"VISA","domestic_network":"cb","country":"FR"}""")
        assertEquals(listOf(CardNetwork.CB, CardNetwork.VISA), info.resolvedNetworks())
    }

    @Test
    fun unknownBrandsResolveToEmpty() {
        assertEquals(emptyList(), decode("""{"brand":"DINERS"}""").resolvedNetworks())
    }
}
