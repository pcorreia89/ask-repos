package askrepo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class AnthropicClient(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String = Defaults.ANTHROPIC_ENDPOINT,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun message(systemPrompt: String, userContent: String, maxTokens: Int): String {
        val req = Request(
            model = model,
            maxTokens = maxTokens,
            system = systemPrompt,
            messages = listOf(Message("user", userContent)),
        )
        val body = json.encodeToString(Request.serializer(), req)
        val response = postWithRetry(body)
        val parsed = json.decodeFromString(Response.serializer(), response)
        return parsed.content.filter { it.type == "text" }.joinToString("") { it.text.orEmpty() }
    }

    private fun postWithRetry(body: String): String {
        var lastError: String? = null
        for (attempt in 0 until Defaults.HTTP_RETRIES) {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(180))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", Defaults.ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val res = try {
                http.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (t: Throwable) {
                lastError = "transport: ${t.message}"
                Thread.sleep(1000L * (1 shl attempt))
                continue
            }
            val status = res.statusCode()
            if (status in 200..299) return res.body()
            lastError = "http $status: ${res.body().take(400)}"
            if (status != 429 && status !in 500..599) break
            Thread.sleep(1000L * (1 shl attempt))
        }
        error("anthropic request failed after ${Defaults.HTTP_RETRIES} attempts: $lastError")
    }

    @Serializable
    private data class Request(
        val model: String,
        @kotlinx.serialization.SerialName("max_tokens") val maxTokens: Int,
        val system: String,
        val messages: List<Message>,
    )

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class Response(val content: List<Block> = emptyList())

    @Serializable
    private data class Block(
        val type: String,
        val text: String? = null,
    )
}
