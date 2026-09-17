package com.hipay.core.monitoring

import com.hipay.core.gateway.model.platformBrand
import kotlin.concurrent.Volatile

/** Which HiPay surface drives this integration. */
internal enum class Surface(val slug: String) {
    NATIVE("native"),
    COMPOSE_MULTIPLATFORM("cmp"),
}

@Volatile
private var surface: Surface = Surface.NATIVE

/**
 * Declared by the SDK's own UI modules, never by an integrator. Nothing declares native — it is the
 * default, which is what keeps the declaration safe on Android, where the CMP controller delegates
 * to the native one and would otherwise have the label taken back from it.
 */
@HiPayInternalApi
public object HiPayIntegrationSurface {
    public fun declareComposeMultiplatform() {
        surface = Surface.COMPOSE_MULTIPLATFORM
    }
}

/**
 * `components.cms`: one value per surface — `sdk_kmp_android_native`, `sdk_kmp_android_cmp`,
 * `sdk_kmp_ios_native`, `sdk_kmp_ios_cmp`. Lowercase and underscored like the `sdk_php` the
 * server-side SDKs report, so the column stays groupable.
 */
internal fun cmsIdentity(): String = "sdk_kmp_${platformBrand()}_${surface.slug}"

/** The surface is process-global, so a test that declares CMP must undo it. */
internal fun resetSurfaceForTest() {
    surface = Surface.NATIVE
}

/** The ingestion's caller header. */
internal fun whoApiHeader(): String = "sdk-${platformBrand()}-hipay"

/** SHA-256 of [input], lowercase hex. */
internal expect fun sha256Hex(input: String): String

/** Now, as `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` in UTC. */
internal expect fun utcTimestamp(): String

/** A random UUID v4, the seed of the correlation id. */
internal expect fun randomUuid(): String
