package askrepo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class EmbeddingsClient(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String = Defaults.VOYAGE_ENDPOINT,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    enum class InputType { DOCUMENT, QUERY }

    fun embed(texts: List<String>, type: InputType): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val out = ArrayList<FloatArray>(texts.size)
        texts.chunked(Defaults.EMBED_BATCH_SIZE).forEach { batch ->
            out.addAll(embedBatch(batch, type))
        }
        return out
    }

    private fun embedBatch(batch: List<String>, type: InputType): List<FloatArray> {
        val request = VoyageRequest(
            input = batch,
            model = model,
            inputType = when (type) {
                InputType.DOCUMENT -> "document"
                InputType.QUERY -> "query"
            },
        )
        val body = json.encodeToString(VoyageRequest.serializer(), request)
        val response = postWithRetry(body)
        val parsed = json.decodeFromString(VoyageResponse.serializer(), response)
        // Voyage returns results in the same order as inputs, with explicit indices.
        val sorted = parsed.data.sortedBy { it.index }
        return sorted.map { item ->
            val arr = FloatArray(item.embedding.size)
            for (i in item.embedding.indices) arr[i] = item.embedding[i].toFloat()
            arr
        }
    }

    private fun postWithRetry(body: String): String {
        var lastError: String? = null
        for (attempt in 0 until Defaults.HTTP_RETRIES) {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(120))
                .header("content-type", "application/json")
                .header("authorization", "Bearer $apiKey")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val res = try {
                http.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (t: Throwable) {
                lastError = "transport: ${t.message}"
                sleepBackoff(attempt)
                continue
            }
            val status = res.statusCode()
            if (status in 200..299) return res.body()
            val shouldRetry = status == 429 || status in 500..599
            lastError = "http $status: ${res.body().take(300)}"
            if (!shouldRetry) break
            sleepBackoff(attempt)
        }
        error("voyage embeddings failed after ${Defaults.HTTP_RETRIES} attempts: $lastError")
    }

    private fun sleepBackoff(attempt: Int) {
        val ms = 1000L * (1 shl attempt) // 1s, 2s, 4s
        Thread.sleep(ms)
    }

    @Serializable
    private data class VoyageRequest(
        val input: List<String>,
        val model: String,
        @SerialName("input_type") val inputType: String,
    )

    @Serializable
    private data class VoyageResponse(
        val data: List<Item>,
        val model: String? = null,
    ) {
        @Serializable
        data class Item(
            val index: Int,
            val embedding: List<Double>,
        )
    }
}
