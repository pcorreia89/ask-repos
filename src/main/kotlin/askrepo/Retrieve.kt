package askrepo

import kotlin.math.sqrt

object Retrieve {

    data class Hit(val chunk: StoredChunk, val score: Float)

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
