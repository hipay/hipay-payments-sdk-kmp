package com.hipay.core.monitoring

import com.hipay.core.Environment
import com.hipay.core.http.defaultHttpClientEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpSendPipeline
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Posts `checkout-data` events: own scope, short deadline, response ignored, failures swallowed, no
 * retry. Monitoring must never delay a payment nor be the reason one fails. It carries NO
 * `Authorization` header — this is an analytics endpoint, not the gateway.
 */
internal class CheckoutDataSender(
    private val environment: Environment,
    engine: HttpClientEngine = defaultHttpClientEngine(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val http = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        // An unreachable endpoint must give up rather than hold a coroutine open.
        install(HttpTimeout) {
            connectTimeoutMillis = 1_000
            requestTimeoutMillis = 3_000
            socketTimeoutMillis = 3_000
        }
    }.apply {
        // Same strip as the gateway client: the WAF answers 403 to Ktor's default Accept-Charset.
        sendPipeline.intercept(HttpSendPipeline.Before) {
            context.headers.remove(HttpHeaders.AcceptCharset)
        }
    }

    fun send(data: CheckoutData) {
        scope.launch {
            try {
                http.post(checkoutDataUrl(environment)) {
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header(WHO_API_HEADER, whoApiHeader())
                    setBody(
                        TextContent(
                            text = checkoutDataJson.encodeToString(data),
                            contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Best effort, and nothing is logged: this SDK logs nothing.
            }
        }
    }
}

private const val WHO_API_HEADER = "X-Who-Api"

/** The ingestion endpoints. */
internal fun checkoutDataUrl(environment: Environment): String = when (environment) {
    Environment.STAGE -> "https://stage-data.hipay.com/checkout-data"
    Environment.PRODUCTION -> "https://data.hipay.com/checkout-data"
}
