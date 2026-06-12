package com.hipay.core.http

import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayException
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.HttpSendPipeline
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.charset
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The only component that reaches the network (architecture D8).
 *
 * Internal by design: public clients (vault, gateway) go through it. No Ktor
 * Logging plugin may ever be installed here — request bodies and auth headers
 * must never be loggable (NFR2, PCI).
 */
internal class HipayHttpClient(
    config: HiPayConfig,
    engine: HttpClientEngine = defaultHttpClientEngine(),
) {
    private val http = HttpClient(engine) {
        expectSuccess = false
    }.apply {
        // The HiPay stage WAF answers 403 to any request carrying the
        // Accept-Charset header that Ktor's HttpPlainText plugin adds by
        // default (verified live 2026-06-12: same request 201 without it,
        // 403 with it; the legacy SDK never sends it). Strip it.
        sendPipeline.intercept(HttpSendPipeline.Before) {
            context.headers.remove(HttpHeaders.AcceptCharset)
        }
    }

    // Single construction point for the Authorization header: story 3.2 adds
    // the alternative "HS base64(username:signature)" scheme here when a
    // Gateway signature is provided (legacy HPFHTTPClient.m:110-131).
    @OptIn(ExperimentalEncodingApi::class)
    private val authorizationHeader: String =
        "Basic " + Base64.encode("${config.username}:${config.password}".encodeToByteArray())

    suspend fun get(url: String): String = execute {
        http.get(url) { hipayHeaders() }
    }

    suspend fun postForm(url: String, fields: Map<String, String>): String = execute {
        http.post(url) {
            hipayHeaders()
            setBody(
                TextContent(
                    text = encodeFormBody(fields),
                    contentType = ContentType.Application.FormUrlEncoded.withCharset(Charsets.UTF_8),
                ),
            )
        }
    }

    private fun HttpRequestBuilder.hipayHeaders() {
        header(HttpHeaders.Authorization, authorizationHeader)
        header(HttpHeaders.Accept, "application/json")
    }

    private suspend fun execute(block: suspend () -> HttpResponse): String {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HiPayException) {
            throw e
        } catch (e: Throwable) {
            throw mapNetworkFailure(e)
        }
        val body = response.bodyAsText()
        if (response.status.value >= 400) {
            throw mapErrorResponse(response.status.value, body)
        }
        return body
    }
}

/** Platform HTTP engine: OkHttp on Android, Darwin on iOS (D8). */
internal expect fun defaultHttpClientEngine(): HttpClientEngine
