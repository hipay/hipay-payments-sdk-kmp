package com.hipay.core

/**
 * SDK configuration injected into clients by the host application.
 *
 * No global state: build one instance and pass it to the clients you create
 * (architecture D7 — no singleton, no DI framework). The optional [settings] follow the same
 * rule — an injected instance shared across the components built from this config, never a
 * global singleton.
 *
 * @property settings optional cross-cutting SDK settings (e.g. display locale). Defaults to `null`
 *   (each component follows the device locale); additive since 0.3.0.
 */
public class HiPayConfig(
    public val username: String,
    public val password: String,
    public val environment: Environment,
    public val settings: HiPaySettings? = null,
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
    public val gatewayV2Url: String,
    public val secureVaultV2Url: String,
) {
    STAGE(
        gatewayV1Url = "https://stage-secure-gateway.hipay-tpp.com/rest/v1/",
        gatewayV2Url = "https://stage-secure-gateway.hipay-tpp.com/rest/v2/",
        secureVaultV2Url = "https://stage-secure2-vault.hipay-tpp.com/rest/v2/",
    ),
    PRODUCTION(
        gatewayV1Url = "https://secure-gateway.hipay-tpp.com/rest/v1/",
        gatewayV2Url = "https://secure-gateway.hipay-tpp.com/rest/v2/",
        secureVaultV2Url = "https://secure2-vault.hipay-tpp.com/rest/v2/",
    ),
}
