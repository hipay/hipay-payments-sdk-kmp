package com.hipay.core.monitoring

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckoutDataTest {

    @AfterTest
    fun restoreSurface() = resetSurfaceForTest()

    @Test
    fun identityNamesThisSdkAndItsVersion() {
        val components = Components()

        val cms = components.cms
        assertTrue(cms.startsWith("sdk_kmp_"), "unexpected cms: $cms")
        assertTrue(cms.endsWith("_native"), "default surface must be native: $cms")
        assertTrue(cms.contains(components.sdkClient), "cms must name the platform: $cms")
        // cms_version is the SDK's version; sdk_client_version is the device's OS version, which a
        // host test cannot pin to a value.
        assertTrue(components.cmsVersion.isNotBlank())
    }

    @Test
    fun declaringComposeMultiplatformChangesTheIdentity() {
        @OptIn(HiPayInternalApi::class)
        HiPayIntegrationSurface.declareComposeMultiplatform()

        assertTrue(Components().cms.endsWith("_cmp"), "declaration ignored: ${Components().cms}")
        // Nothing declares native, so the controller Android delegates to cannot take the label back.
        assertTrue(Components().cms.endsWith("_cmp"))
    }

    @Test
    fun absentFieldsAreOmittedRatherThanSentAsNull() {
        val json = checkoutDataJson.encodeToString(CheckoutData(event = CheckoutEvent.REQUEST))

        assertFalse(json.contains("null"), "nulls must not reach the wire: $json")
        assertFalse(json.contains("status"), json)
        assertFalse(json.contains("monitoring"), json)
        // The identity is never omitted: an event that cannot name its SDK is pointless.
        assertTrue(json.contains("\"cms\""), json)
    }

    @Test
    fun payloadUsesTheKeysTheIngestionReads() {
        val json = checkoutDataJson.encodeToString(
            CheckoutData(
                event = CheckoutEvent.REQUEST,
                id = "hash",
                status = "116",
                paymentMethod = "visa",
                cardCountry = "FR",
                monitoring = Monitoring(dateRequest = "2026-01-01T00:00:00.000Z", dateResponse = "x"),
            ),
        )

        listOf(
            "\"event\":\"request\"", "\"payment_method\":\"visa\"", "\"card_country\":\"FR\"",
            "\"date_request\":", "\"date_response\":",
            "\"cms\":", "\"cms_version\":", "\"sdk_client\":", "\"sdk_client_version\":",
        ).forEach { key -> assertTrue(json.contains(key), "missing $key in $json") }
    }

    @Test
    fun theCorrelationIdIsOpaqueAndTiedToNothing() {
        val id = checkoutEventId()

        assertEquals(64, id.length, "expected a SHA-256 hex digest, got $id")
        assertTrue(id.all { it in "0123456789abcdef" }, id)
        // A fresh UUID each time: the id correlates one journey, never a device.
        assertTrue(id != checkoutEventId())
    }

    @Test
    fun noEventCanNameAPayerOrAMerchant() {
        val json = checkoutDataJson.encodeToString(
            CheckoutData(event = CheckoutEvent.REQUEST, id = "hash", status = "116"),
        )

        listOf("order_id", "transaction_id", "domain", "amount", "currency").forEach { field ->
            assertFalse(json.contains(field), "$field is back on the wire: $json")
        }
    }

    @Test
    fun timestampsAreUtcWithMillisecondPrecision() {
        val stamp = utcTimestamp()

        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""").matches(stamp),
            "unexpected timestamp shape: $stamp",
        )
    }

    @Test
    fun sha256MatchesTheKnownVector() {
        // The cheapest proof that both platform implementations agree.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(""),
        )
    }
}
