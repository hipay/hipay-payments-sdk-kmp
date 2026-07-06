package com.hipay.card.store

import com.hipay.card.model.CardToken
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SavedCardPaymentTest {

    // --- savedCardFromToken ---

    private fun fullToken(
        pan: String? = "411111xxxxxx1111",
        brand: String? = "VISA",
        holder: String? = "JANE DOE",
        month: String? = "12",
        year: String? = "2029",
    ) = CardToken(
        token = "a".repeat(64),
        pan = pan,
        brand = brand,
        cardHolder = holder,
        cardExpiryMonth = month,
        cardExpiryYear = year,
    )

    @Test
    fun mapsEveryFieldFromTheTokenizeResponse() {
        val card = assertNotNull(savedCardFromToken(fullToken()))
        assertEquals("a".repeat(64), card.token)
        assertEquals("411111xxxxxx1111", card.maskedPan)
        assertEquals("VISA", card.network)
        assertEquals("JANE DOE", card.holder)
        assertEquals("12", card.expiryMonth)
        assertEquals("2029", card.expiryYear)
    }

    @Test
    fun missingIdentityFieldsFailSoftToNull() {
        // pan + expiry form the store's overwrite identity: without them the
        // card cannot be deduplicated, so nothing is persisted.
        assertNull(savedCardFromToken(fullToken(pan = null)))
        assertNull(savedCardFromToken(fullToken(month = null)))
        assertNull(savedCardFromToken(fullToken(year = null)))
    }

    @Test
    fun missingDisplayOnlyFieldsDefaultToEmpty() {
        val card = assertNotNull(savedCardFromToken(fullToken(brand = null, holder = null)))
        assertEquals("", card.network)
        assertEquals("", card.holder)
    }

    // --- savedCardPaymentProduct ---

    @Test
    fun paymentProductDerivesFromTheStoredBrand() {
        fun product(brand: String) =
            savedCardPaymentProduct(assertNotNull(savedCardFromToken(fullToken(brand = brand))))
        assertEquals("visa", product("VISA"))
        assertEquals("mastercard", product("MASTERCARD"))
        assertEquals("american-express", product("american-express"))
        // the vault may answer the display form with a space
        assertEquals("american-express", product("AMERICAN EXPRESS"))
        assertEquals("maestro", product("maestro"))
        assertEquals("cb", product("CB"))
        assertEquals("bcmc", product("bcmc"))
        // unknown/empty brand falls back to visa (frozen `?: "visa"` contract convention)
        assertEquals("visa", product("something-new"))
        assertEquals("visa", savedCardPaymentProduct(assertNotNull(savedCardFromToken(fullToken(brand = null)))))
    }

    // --- cardNoLongerValidOrNull ---

    private fun exception(
        code: HiPayErrorCode,
        apiCode: Int? = null,
        httpStatus: Int? = null,
    ) = HiPayException(
        code = code,
        message = "test",
        httpStatus = httpStatus,
        apiCode = apiCode,
        apiMessage = if (apiCode != null) "Unknown Token" else null,
    )

    @Test
    fun unknownTokenOnServerClassBecomesCardNoLongerValid() {
        // Stage-captured signature: HTTP 500 + {"code":"3040001","message":"Unknown Token"}.
        val original = exception(HiPayErrorCode.SERVER, apiCode = 3040001, httpStatus = 500)
        val mapped = assertNotNull(cardNoLongerValidOrNull(original))
        assertEquals(HiPayErrorCode.CARD_NO_LONGER_VALID, mapped.code)
        assertEquals(3040001, mapped.apiCode)
        assertEquals(500, mapped.httpStatus)
        assertEquals(original, mapped.cause)
        // PCI: the synthesized message carries no backend text
        assertFalse(mapped.message!!.contains("Unknown Token"))
    }

    @Test
    fun unknownTokenOnApiClassAlsoMatches() {
        // Defensive: if the backend ever moves the rejection to a structured 4xx.
        val mapped = cardNoLongerValidOrNull(exception(HiPayErrorCode.API, apiCode = 3040001, httpStatus = 400))
        assertEquals(HiPayErrorCode.CARD_NO_LONGER_VALID, assertNotNull(mapped).code)
    }

    @Test
    fun transientAndUnrecognizedFailuresNeverMatch() {
        // Two-tier rule: a transient failure must never destroy a valid card.
        assertNull(cardNoLongerValidOrNull(exception(HiPayErrorCode.NETWORK)))
        assertNull(cardNoLongerValidOrNull(exception(HiPayErrorCode.CLIENT, httpStatus = 401)))
        assertNull(cardNoLongerValidOrNull(exception(HiPayErrorCode.VALIDATION)))
        // plain 500 without a structured payload
        assertNull(cardNoLongerValidOrNull(exception(HiPayErrorCode.SERVER, httpStatus = 500)))
        // structured but different business code
        assertNull(cardNoLongerValidOrNull(exception(HiPayErrorCode.API, apiCode = 1000001, httpStatus = 401)))
        assertNull(cardNoLongerValidOrNull(exception(HiPayErrorCode.SERVER, apiCode = 3040999, httpStatus = 500)))
        // an apiCode smuggled onto a transport-class failure still never matches
        assertNull(cardNoLongerValidOrNull(exception(HiPayErrorCode.NETWORK, apiCode = 3040001)))
    }

    @Test
    fun alreadyMappedExceptionIsNotReMapped() {
        assertNull(cardNoLongerValidOrNull(exception(HiPayErrorCode.CARD_NO_LONGER_VALID, apiCode = 3040001)))
    }
}
