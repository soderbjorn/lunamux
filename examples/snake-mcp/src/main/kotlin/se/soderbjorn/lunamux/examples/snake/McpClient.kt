/**
 * Minimal MCP (Model Context Protocol) client over streamable HTTP for
 * the Snake example driver.
 *
 * Speaks plain JSON-RPC 2.0 against Lunamux's `/mcp` endpoint using
 * `java.net.http.HttpClient` — no SDK, no extra dependencies — exactly
 * the wire surface any external MCP client (e.g. Claude Code's Node
 * client) uses: `initialize` (capturing the `Mcp-Session-Id` header),
 * `notifications/initialized`, and `tools/call`, authenticated via the
 * `X-Termtastic-Auth` header.
 *
 * The same server verified by this client also drives Node/TS MCP
 * clients unchanged — the transport is the spec's streamable HTTP with
 * plain-JSON responses.
 *
 * @see SnakeDriver
 */
package se.soderbjorn.lunamux.examples.snake

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Thrown when the server returns a JSON-RPC error or an isError tool result. */
class McpClientException(message: String) : RuntimeException(message)

/**
 * One MCP connection to a Lunamux server.
 *
 * @param baseUrl the endpoint, e.g. `https://localhost:8443/mcp`.
 * @param token an MCP token minted in Lunamux's settings dialog
 *   (needs `read+write` scope for the console tools).
 * @param insecureTls accept the server's self-signed certificate without
 *   verification — for localhost play only. The verified alternative is
 *   importing the server's exported `server-leaf.pem` into a truststore.
 */
class McpClient(
    private val baseUrl: String,
    private val token: String,
    insecureTls: Boolean = false,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicLong(0)
    private var sessionId: String? = null

    private val http: HttpClient = HttpClient.newBuilder().apply {
        if (insecureTls) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(trustAll), SecureRandom())
            sslContext(ctx)
        }
    }.build()

    /** POST one JSON-RPC message; returns the parsed response body. */
    private fun post(body: JsonObject, expectResponse: Boolean = true): JsonObject? {
        val request = HttpRequest.newBuilder(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-Termtastic-Auth", token)
            .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw McpClientException("HTTP ${response.statusCode()}: ${response.body().take(300)}")
        }
        response.headers().firstValue("Mcp-Session-Id").ifPresent { sessionId = it }
        if (!expectResponse) return null
        val parsed = json.parseToJsonElement(response.body()).jsonObject
        (parsed["error"] as? JsonObject)?.let { err ->
            throw McpClientException("JSON-RPC error: $err")
        }
        return parsed
    }

    /**
     * Perform the MCP handshake: `initialize` (capturing the session id)
     * followed by the `notifications/initialized` notification.
     */
    fun initialize() {
        post(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ids.incrementAndGet())
            put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", "2025-06-18")
                put("capabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject {
                    put("name", "snake-mcp")
                    put("version", "1.0.0")
                })
            })
        })
        post(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }, expectResponse = false)
    }

    /**
     * Call an MCP tool and return its payload: tools returning JSON text
     * get it parsed into a [JsonObject]; plain-text results come back as
     * `{"text": …}`.
     *
     * @param name the tool name.
     * @param args the tool arguments.
     * @throws McpClientException on protocol errors or isError results.
     */
    fun callTool(name: String, args: JsonObject = JsonObject(emptyMap())): JsonObject {
        val response = post(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ids.incrementAndGet())
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", name)
                put("arguments", args)
            })
        })!!
        val result = response["result"]!!.jsonObject
        val text = result["content"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
        if ((result["isError"] as? JsonPrimitive)?.contentOrNull == "true") {
            throw McpClientException("Tool '$name' failed: $text")
        }
        val parsed: JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()
        return (parsed as? JsonObject) ?: buildJsonObject { put("text", text) }
    }
}
