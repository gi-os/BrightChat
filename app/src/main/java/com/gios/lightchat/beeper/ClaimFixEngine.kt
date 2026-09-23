package com.gios.lightchat.beeper

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.toByteArray

/**
 * Wraps the HTTP engine Trixnity talks to Beeper through, to repair one malformed response.
 *
 * Beeper's homeserver leaves the `failures` member out of `/keys/claim` responses. Trixnity's
 * response type declares it without a default, so the whole response fails to deserialize, no olm
 * session is created, and the bridge never receives the room key: every message we send into an
 * encrypted WhatsApp room is undecryptable on the other side. Inserting an empty `failures` object
 * fixes it and is a no-op for a server that follows the spec. The fix itself comes from
 * Beeper4LightOS and fenleon/chats (both MIT), which proved it on the LP3; they did it as an OkHttp
 * interceptor, and this does the same at the Ktor engine because BrightChat does not run Ktor on
 * OkHttp (see app/build.gradle.kts).
 *
 * `install` is overridden only to call the interface's own default. Interface delegation would
 * otherwise forward it to [delegate], whose pipeline calls [delegate]'s `execute` and skips ours.
 */
@OptIn(InternalAPI::class)
class ClaimFixEngine(private val delegate: HttpClientEngine) : HttpClientEngine by delegate {

    override fun install(client: HttpClient) {
        super<HttpClientEngine>.install(client)
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val response = delegate.execute(data)
        if (!data.url.encodedPath.contains("/keys/claim")) return response
        val channel = response.body as? ByteReadChannel ?: return response
        val text = channel.toByteArray().decodeToString()
        val fixed = patchClaim(text)
        val headers = Headers.build {
            response.headers.forEach { name, values ->
                if (!name.equals(HttpHeaders.ContentLength, ignoreCase = true)) appendAll(name, values)
            }
        }
        return HttpResponseData(
            statusCode = response.statusCode,
            requestTime = response.requestTime,
            headers = headers,
            version = response.version,
            body = ByteReadChannel(fixed.encodeToByteArray()),
            callContext = response.callContext,
        )
    }

    companion object {
        /** Adds `"failures":{}` to a claim response that lacks it. Pure, for the unit test. */
        fun patchClaim(body: String): String {
            if (body.contains("\"failures\"")) return body
            val brace = body.indexOf('{')
            if (brace < 0) return body
            val rest = body.substring(brace + 1)
            // `{}` must become `{"failures":{}}`, not `{"failures":{},}`.
            val separator = if (rest.trimStart().startsWith("}")) "" else ","
            return body.substring(0, brace + 1) + "\"failures\":{}" + separator + rest
        }
    }
}
