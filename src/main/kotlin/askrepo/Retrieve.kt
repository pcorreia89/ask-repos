package askrepo

import kotlin.math.ln
import kotlin.math.sqrt

object Retrieve {

    data class Hit(val chunk: StoredChunk, val score: Float)

    private const val BM25_K1 = 1.2f
    private const val BM25_B = 0.75f
    private const val VECTOR_WEIGHT = 0.7f
    private const val BM25_WEIGHT = 0.3f

    fun topK(queryVector: FloatArray, chunks: List<StoredChunk>, vectors: List<FloatArray>, k: Int): List<Hit> {
        require(chunks.size == vectors.size) { "chunks/vectors length mismatch" }
        if (chunks.isEmpty()) return emptyList()
        val qNorm = norm(queryVector)
        if (qNorm == 0f) return emptyList()
        val scored = ArrayList<Hit>(chunks.size)
        for (i in chunks.indices) {
            val v = vectors[i]
            val vNorm = norm(v)
            if (vNorm == 0f) continue
            val score = dot(queryVector, v) / (qNorm * vNorm)
            scored.add(Hit(chunks[i], score))
        }
        scored.sortByDescending { it.score }
        return scored.take(k)
    }

    fun topKHybrid(
        query: String,
        queryVector: FloatArray,
        chunks: List<StoredChunk>,
        vectors: List<FloatArray>,
        k: Int,
    ): List<Hit> {
        require(chunks.size == vectors.size) { "chunks/vectors length mismatch" }
        if (chunks.isEmpty()) return emptyList()

        val vectorScores = vectorScores(queryVector, vectors)
        val bm25Scores = bm25Scores(query, chunks)

        val maxVector = vectorScores.max().coerceAtLeast(1e-6f)
        val maxBm25 = bm25Scores.max().coerceAtLeast(1e-6f)

        val scored = ArrayList<Hit>(chunks.size)
        for (i in chunks.indices) {
            val normVector = vectorScores[i] / maxVector
            val normBm25 = bm25Scores[i] / maxBm25
            val combined = VECTOR_WEIGHT * normVector + BM25_WEIGHT * normBm25
            scored.add(Hit(chunks[i], combined))
        }
        scored.sortByDescending { it.score }
        return scored.take(k)
    }

    private fun vectorScores(queryVector: FloatArray, vectors: List<FloatArray>): FloatArray {
        val qNorm = norm(queryVector)
        return FloatArray(vectors.size) { i ->
            val v = vectors[i]
            val vNorm = norm(v)
            if (qNorm == 0f || vNorm == 0f) 0f
            else dot(queryVector, v) / (qNorm * vNorm)
        }
    }

    internal fun bm25Scores(query: String, chunks: List<StoredChunk>): FloatArray {
        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return FloatArray(chunks.size)

        val n = chunks.size.toFloat()
        val docLengths = IntArray(chunks.size)
        val tokenizedDocs = Array(chunks.size) { i ->
            val tokens = tokenize(chunks[i].text)
            docLengths[i] = tokens.size
            tokens.groupingBy { it }.eachCount()
        }
        val avgDl = if (chunks.isNotEmpty()) docLengths.average().toFloat() else 1f

        val df = HashMap<String, Int>()
        for (doc in tokenizedDocs) {
            for (term in doc.keys) {
                df[term] = (df[term] ?: 0) + 1
            }
        }

        return FloatArray(chunks.size) { i ->
            val doc = tokenizedDocs[i]
            val dl = docLengths[i].toFloat()
            var score = 0f
            for (term in queryTerms) {
                val tf = (doc[term] ?: 0).toFloat()
                val docFreq = (df[term] ?: 0).toFloat()
                val idf = ln((n - docFreq + 0.5f) / (docFreq + 0.5f) + 1f).toFloat()
                val tfNorm = (tf * (BM25_K1 + 1f)) / (tf + BM25_K1 * (1f - BM25_B + BM25_B * dl / avgDl))
                score += idf * tfNorm
            }
            score.coerceAtLeast(0f)
        }
    }

    internal fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "vector dim mismatch" }
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum.toFloat()
    }

    private fun norm(a: FloatArray): Float {
        var sum = 0.0
        for (x in a) sum += x * x
        return sqrt(sum).toFloat()
    }
}
