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
import io.ktor.client.plugins.HttpTimeout
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
    private val config: HiPayConfig,
    engine: HttpClientEngine = defaultHttpClientEngine(),
) {
    private val http = HttpClient(engine) {
        expectSuccess = false
        // Never auto-follow redirects: a 3xx on a card-carrying POST would
        // otherwise re-send the Authorization header and form body to the
        // redirect target (PCI / NFR2). HiPay signals 3DS via a 200 body
        // (state=forwarding + forwardUrl), never an HTTP redirect.
        followRedirects = false
        // Payment processing is slow by nature: a stage order takes ~12s
        // (measured 2026-06-12) and OkHttp's default 10s read timeout kills
        // it. 60s matches the NSURLSession default the legacy SDK relied on.
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }.apply {
        // The HiPay stage WAF answers 403 to any request carrying the
        // Accept-Charset header that Ktor's HttpPlainText plugin adds by
        // default (verified live 2026-06-12: same request 201 without it,
        // 403 with it; the legacy SDK never sends it). Strip it.
        sendPipeline.intercept(HttpSendPipeline.Before) {
            context.headers.remove(HttpHeaders.AcceptCharset)
        }
    }

    // Single construction point for the Authorization header. When a Gateway
    // signature is provided (computed by the merchant backend — NEVER by this
    // library), the legacy "HS" scheme replaces Basic auth
    // (HPFHTTPClient.m:110-131; validated live on stage 2026-06-12).
    @OptIn(ExperimentalEncodingApi::class)
    private fun authorizationHeader(signature: String?): String = if (signature != null) {
        "HS " + Base64.encode("${config.username}:$signature".encodeToByteArray())
    } else {
        "Basic " + Base64.encode("${config.username}:${config.password}".encodeToByteArray())
    }

    suspend fun get(url: String, signature: String? = null): String = execute {
        http.get(url) { hipayHeaders(signature) }
    }

    suspend fun postForm(url: String, fields: Map<String, String>, signature: String? = null): String = execute {
        http.post(url) {
            hipayHeaders(signature)
            setBody(
                TextContent(
                    text = encodeFormBody(fields),
                    contentType = ContentType.Application.FormUrlEncoded.withCharset(Charsets.UTF_8),
                ),
            )
        }
    }

    private fun HttpRequestBuilder.hipayHeaders(signature: String?) {
        header(HttpHeaders.Authorization, authorizationHeader(signature))
        header(HttpHeaders.Accept, "application/json")
    }

    private suspend fun execute(block: suspend () -> HttpResponse): String = try {
        val response = block()
        // bodyAsText() reaches the network too (streamed read): keep it inside
        // the guarded block so a charset-decode error or a mid-read connection
        // drop maps to NETWORK instead of escaping as a raw Ktor/IO exception.
        val body = response.bodyAsText()
        if (response.status.value >= 400) {
            throw mapErrorResponse(response.status.value, body)
        }
        body
    } catch (e: CancellationException) {
        throw e
    } catch (e: HiPayException) {
        // mapErrorResponse verdict — already synthesized, never remap to NETWORK.
        throw e
    } catch (e: Throwable) {
        throw mapNetworkFailure(e)
    }
}

/** Platform HTTP engine: OkHttp on Android, Darwin on iOS (D8). */
internal expect fun defaultHttpClientEngine(): HttpClientEngine
