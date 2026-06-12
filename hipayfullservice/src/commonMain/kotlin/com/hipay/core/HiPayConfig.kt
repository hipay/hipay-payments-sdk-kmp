package com.hipay.core

/**
 * SDK configuration injected into clients by the host application.
 *
 * No global state: build one instance and pass it to the clients you create
 * (architecture D7 — no singleton, no DI framework).
 */
public class HiPayConfig(
    public val username: String,
    public val password: String,
    public val environment: Environment,
) {
    // The password must never reach host logs (NFR2).
    override fun toString(): String =
        "HiPayConfig(username=$username, password=***, environment=$environment)"
}

/**
 * Target HiPay platform. Resolves the documented base URLs (api-contracts.md).
 */
public enum class Environment(
    public val gatewayV1Url: String,
    public val secureVaultV2Url: String,
) {
    STAGE(
        gatewayV1Url = "https://stage-secure-gateway.hipay-tpp.com/rest/v1/",
        secureVaultV2Url = "https://stage-secure2-vault.hipay-tpp.com/rest/v2/",
    ),
    PRODUCTION(
        gatewayV1Url = "https://secure-gateway.hipay-tpp.com/rest/v1/",
        secureVaultV2Url = "https://secure2-vault.hipay-tpp.com/rest/v2/",
    ),
}
