package com.hipay.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiPayConfigTest {

    @Test
    fun stageEnvironmentResolvesDocumentedBaseUrls() {
        assertEquals(
            "https://stage-secure-gateway.hipay-tpp.com/rest/v1/",
            Environment.STAGE.gatewayV1Url,
        )
        assertEquals(
            "https://stage-secure2-vault.hipay-tpp.com/rest/v2/",
            Environment.STAGE.secureVaultV2Url,
        )
    }

    @Test
    fun productionEnvironmentResolvesDocumentedBaseUrls() {
        assertEquals(
            "https://secure-gateway.hipay-tpp.com/rest/v1/",
            Environment.PRODUCTION.gatewayV1Url,
        )
        assertEquals(
            "https://secure2-vault.hipay-tpp.com/rest/v2/",
            Environment.PRODUCTION.secureVaultV2Url,
        )
    }

    @Test
    fun toStringNeverContainsThePassword() {
        val config = HiPayConfig("merchant-user", "s3cret-p4ss", Environment.STAGE)
        assertFalse(config.toString().contains("s3cret-p4ss"))
        assertTrue(config.toString().contains("merchant-user"))
    }
}
